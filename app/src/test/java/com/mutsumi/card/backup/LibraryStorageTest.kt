package com.mutsumi.card.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.data.*
import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.data.image.FileCardImageStore
import com.mutsumi.card.data.local.MutsumiCardDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LibraryStorageTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var database: MutsumiCardDatabase
    private lateinit var repository: RoomCardRepository
    private lateinit var store: CardImageStore
    @Before fun setup() {
        database = MutsumiCardDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        store = FileCardImageStore(folder.root)
        repository = RoomCardRepository(database.cardDao(), store, pngValidator = testPngValidator)
    }
    @After fun close() { database.close() }

    @Test fun `批量复制子树图片独立且删除原卡不影响副本`() = runTest {
        val root = repository.createChildDeck("数学", null)
        val child = repository.createChildDeck("微积分", root)
        val card = repository.saveCard(child, "梯度", validPng())
        repository.manage(LibrarySelection(deckIds = setOf(root, child)), LibraryAction.Copy)
        val copiedDeck = repository.libraryDecks.first().single { it.name == "数学（副本）" }
        val copiedChild = repository.libraryDecks.first().single { it.parentId == copiedDeck.id }
        val copiedCard = repository.cards(copiedChild.id).first().single()
        val originalPath = repository.cards(child).first().single().valueImagePath
        assertThat(copiedCard.valueImagePath).isNotEqualTo(originalPath)
        repository.deleteCard(card)
        assertThat(store.read(copiedCard.valueImagePath)).isEqualTo(validPng())
        assertThat(repository.libraryDecks.first().size).isEqualTo(4)
    }

    @Test fun `循环移动拒绝并保持所有原卡和卡组关系`() = runTest {
        val root = repository.createChildDeck("数学", null)
        val child = repository.createChildDeck("微积分", root)
        repository.saveCard(child, "梯度", validPng())
        assertThat(runCatching { repository.manage(LibrarySelection(deckIds = setOf(root)), LibraryAction.Move, child) }.isFailure).isTrue()
        assertThat(repository.libraryDecks.first().single { it.id == child }.parentId).isEqualTo(root)
        assertThat(repository.cards(child).first().size).isEqualTo(1)
    }

    @Test fun `索引仅对新图片路径读取数据且复习写入会使修订号变化`() = runTest {
        val deck = repository.createDeck("数学")
        val card = repository.saveCard(deck, "梯度", validPng())
        var reads = 0
        val counted = object : CardImageStore by store {
            override suspend fun read(path: String): ByteArray { reads++; return store.read(path) }
        }
        val data = RoomCloudBackupDataAccess(database.cardDao(), counted, indexFile = File(folder.root, "index.json"))
        val first = data.readMetadata()
        assertThat(reads).isEqualTo(1)
        data.readMetadata()
        assertThat(reads).isEqualTo(1)
        repository.applyFeedback(card, com.mutsumi.card.domain.review.ReviewFeedback.Know, 300)
        val next = data.readMetadata()
        assertThat(reads).isEqualTo(1)
        assertThat(next.revision).isNotEqualTo(first.revision)
        val failed = runCatching { data.applyPlan(first.document, mapOf(first.document.images.single().sha256 to validPng()), first.revision, true) }
        assertThat(failed.exceptionOrNull()).isInstanceOf(CloudBackupException::class.java)
        assertThat(repository.cards(deck).first().single().review.seenCount).isEqualTo(1)
    }

    @Test fun `导出包含归档卡及完整层级导入保留复习计数`() = runTest {
        val root = repository.createChildDeck("数学", null)
        val child = repository.createChildDeck("微积分", root)
        val card = repository.saveCard(child, "梯度", validPng())
        repository.applyFeedback(card, com.mutsumi.card.domain.review.ReviewFeedback.Know, 300)
        repository.archiveCard(card)
        val data = RoomCloudBackupDataAccess(database.cardDao(), store)
        val export = data.loadExportData()
        assertThat(export.snapshot.cards.single().archived).isTrue()
        assertThat(export.snapshot.decks.single { it.id == child }.parentId).isEqualTo(root)
        data.importBatch(ImportBatch(export.snapshot, export.images))
        val all = data.loadExportData()
        assertThat(all.snapshot.cards.size).isEqualTo(2)
        assertThat(all.snapshot.reviews.map { it.seenCount }).containsExactly(1, 1)
        assertThat(all.snapshot.decks.count { it.parentId != null }).isEqualTo(2)
    }

    @Test fun `旧版本遗留删除队列不得删除仍被数据库引用的卡面`() = runTest {
        val deck = repository.createDeck("数学")
        repository.saveCard(deck,"梯度",validPng())
        val path = repository.cards(deck).first().single().valueImagePath
        database.openHelper.writableDatabase.execSQL("INSERT INTO pending_image_deletions(path,queuedAt) VALUES (?,?)",arrayOf<Any>(path,100L))
        repository.retryPendingImageCleanup()
        assertThat(store.resolve(path).isFile).isTrue()
        assertThat(database.cardDao().getPendingImageDeletions()).isEmpty()
        assertThat(repository.cards(deck).first().single().keyText).isEqualTo("梯度")
    }

    @Test fun `归档父卡组隐藏整个子树的学习候选但管理页仍可恢复`() = runTest {
        val root=repository.createChildDeck("数学",null)
        val child=repository.createChildDeck("微积分",root)
        repository.saveCard(child,"梯度",validPng())
        repository.manage(LibrarySelection(deckIds=setOf(root)),LibraryAction.Archive)
        assertThat(repository.decks.first()).isEmpty()
        assertThat(repository.cards(child).first()).isEmpty()
        assertThat(repository.managementCards(child,includeArchived=true).first().size).isEqualTo(1)
        repository.manage(LibrarySelection(deckIds=setOf(root)),LibraryAction.Restore)
        assertThat(repository.cards(child).first().size).isEqualTo(1)
    }

    @Test fun `落盘期间新增本地卡片会在真实数据库提交事务中拒绝过期计划`() = runTest {
        val deck=repository.createDeck("数学")
        repository.saveCard(deck,"原卡",validPng())
        var injected=false
        val intercepted=object:CardImageStore by store {
            override suspend fun writePng(bytes:ByteArray):String = store.writePng(bytes).also {
                if(!injected) {
                    injected=true
                    repository.saveCard(deck,"落盘期间新增",validPng())
                }
            }
        }
        val data=RoomCloudBackupDataAccess(database.cardDao(),intercepted)
        val before=data.readMetadata()
        val incoming=before.document.snapshot.cards.single().copy(syncId="cloud-new-card",keyText="云端卡片")
        val document=before.document.copy(snapshot=before.document.snapshot.copy(
            cards=before.document.snapshot.cards+incoming,
            reviews=before.document.snapshot.reviews+CloudReviewRecord(incoming.syncId,1.0,0,0,0,0,null)))
        val failure=runCatching { data.applyPlan(document,mapOf(document.images.single().sha256 to validPng()),before.revision,true) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CloudBackupException::class.java)
        assertThat(repository.cards(deck).first().map { it.keyText }).containsExactly("原卡","落盘期间新增")
        assertThat(File(folder.root,"images").listFiles().orEmpty().size).isEqualTo(2)
    }
}

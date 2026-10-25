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
}

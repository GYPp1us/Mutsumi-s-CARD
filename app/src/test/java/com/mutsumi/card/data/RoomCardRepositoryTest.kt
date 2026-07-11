package com.mutsumi.card.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.data.image.FileCardImageStore
import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.data.image.PngDecoder
import com.mutsumi.card.data.image.ValuePngValidator
import com.mutsumi.card.data.local.MutsumiCardDatabase
import com.mutsumi.card.domain.model.Deck
import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.review.WeightedCardPicker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class RoomCardRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: MutsumiCardDatabase
    private lateinit var repository: RoomCardRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MutsumiCardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomCardRepository(
            dao = database.cardDao(),
            imageStore = FileCardImageStore(temporaryFolder.root),
            picker = WeightedCardPicker(Random(7)),
            now = { 100L },
            pngValidator = testPngValidator,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createsDefaultDeckAndSupportsCreateRenameAndCounts() = runTest {
        val defaultId = repository.ensureDefaultDeck()
        val customId = repository.createDeck("  生物  ")
        repository.renameDeck(customId, "  植物  ")
        repository.saveCard(customId, "叶绿体", validPng)

        assertThat(repository.decks.first()).containsExactly(
            Deck(id = defaultId, name = "默认卡组", cardCount = 0),
            Deck(id = customId, name = "植物", cardCount = 1),
        ).inOrder()
    }

    @Test
    fun cardLifecycleReplacesAndDeletesOwnedImages() = runTest {
        val deckId = repository.ensureDefaultDeck()
        val cardId = repository.saveCard(deckId, "  细胞  ", validPng)
        val saved = repository.cards(deckId).first().single()

        assertThat(saved.keyText).isEqualTo("细胞")
        assertThat(saved.review.weight).isEqualTo(1.0)
        assertThat(repository.imageStore.resolve(saved.valueImagePath).readBytes())
            .isEqualTo(validPng)

        repository.updateCard(cardId, "细胞膜", validPng)
        val updated = repository.cards(deckId).first().single()
        assertThat(updated.keyText).isEqualTo("细胞膜")
        assertThat(updated.valueImagePath).isNotEqualTo(saved.valueImagePath)
        assertThat(repository.imageStore.resolve(saved.valueImagePath).exists()).isFalse()

        repository.archiveCard(cardId)
        assertThat(repository.cards(deckId).first()).isEmpty()
        repository.deleteCard(cardId)
        assertThat(database.cardDao().getCard(cardId)).isNull()
        assertThat(repository.imageStore.resolve(updated.valueImagePath).exists()).isFalse()
    }

    @Test
    fun feedbackUpdatesDomainReviewState() = runTest {
        val deckId = repository.ensureDefaultDeck()
        val cardId = repository.saveCard(deckId, "反馈", validPng)

        repository.applyFeedback(cardId, ReviewFeedback.Again, now = 900L)

        val review = repository.cards(deckId).first().single().review
        assertThat(review.weight).isEqualTo(1.8)
        assertThat(review.seenCount).isEqualTo(1)
        assertThat(review.againCount).isEqualTo(1)
        assertThat(review.lastReviewedAt).isEqualTo(900L)
    }

    @Test
    fun failedDatabaseInsertDeletesNewImage() = runTest {
        val deckId = repository.ensureDefaultDeck()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_card_insert
            BEFORE INSERT ON cards
            BEGIN
                SELECT RAISE(ABORT, '测试卡片写入失败');
            END
            """.trimIndent(),
        )

        val error = runCatching {
            repository.saveCard(deckId, "失败", validPng)
        }.exceptionOrNull()

        assertThat(error).isNotNull()
        assertThat(temporaryFolder.root.resolve("images").listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun recommendationExcludesLastThreeWhenEnoughAlternativesExist() = runTest {
        val deckId = repository.ensureDefaultDeck()
        val ids = (1..5).map { index ->
            repository.saveCard(deckId, "卡片$index", validPng)
        }

        val picked = repository.pickRecommendedCard(deckId, ids.take(3))

        assertThat(picked?.id).isAnyOf(ids[3], ids[4])
    }

    @Test
    fun blankNamesAndKeysFailExplicitly() = runTest {
        val deckId = repository.ensureDefaultDeck()

        assertThat(runCatching { repository.createDeck(" ") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { repository.renameDeck(deckId, " ") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { repository.saveCard(deckId, " ", byteArrayOf(1)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsCorruptAndWrongSizedImages() = runTest {
        val deckId = repository.ensureDefaultDeck()

        val corrupt = runCatching {
            repository.saveCard(deckId, "损坏", byteArrayOf(1, 2, 3))
        }.exceptionOrNull()
        val wrongSize = runCatching {
            repository.saveCard(deckId, "尺寸", png(width = 512, height = 1024))
        }.exceptionOrNull()

        assertThat(corrupt).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(corrupt).hasMessageThat().contains("PNG")
        assertThat(wrongSize).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(wrongSize).hasMessageThat().contains("1024×2048")
        assertThat(temporaryFolder.root.resolve("images").listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun failedDatabaseUpdateDeletesNewImageAndKeepsOldImage() = runTest {
        val deckId = repository.ensureDefaultDeck()
        val cardId = repository.saveCard(deckId, "原卡", validPng)
        val original = repository.cards(deckId).first().single()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_card_update
            BEFORE UPDATE ON cards
            BEGIN
                SELECT RAISE(ABORT, '测试卡片更新失败');
            END
            """.trimIndent(),
        )

        val error = runCatching {
            repository.updateCard(cardId, "新卡", validPng)
        }.exceptionOrNull()

        assertThat(error).isNotNull()
        assertThat(repository.imageStore.resolve(original.valueImagePath).isFile).isTrue()
        assertThat(temporaryFolder.root.resolve("images").listFiles()?.toList().orEmpty()).hasSize(1)
        assertThat(database.cardDao().getCard(cardId)?.valueImagePath)
            .isEqualTo(original.valueImagePath)
    }

    @Test
    fun failedOldImageDeletionRemainsQueuedAndCanBeRetried() = runTest {
        val root = temporaryFolder.newFolder("queued-cleanup")
        val failingStore = FailingDeleteImageStore(FileCardImageStore(root))
        val queuedRepository = RoomCardRepository(
            dao = database.cardDao(),
            imageStore = failingStore,
            now = { 100L },
            pngValidator = testPngValidator,
        )
        val deckId = queuedRepository.ensureDefaultDeck()
        val cardId = queuedRepository.saveCard(deckId, "原卡", validPng)
        val oldPath = queuedRepository.cards(deckId).first().single().valueImagePath
        failingStore.failDeletes = true

        queuedRepository.updateCard(cardId, "新卡", validPng)

        assertThat(database.cardDao().getPendingImageDeletions().map { it.path })
            .containsExactly(oldPath)
        assertThat(failingStore.resolve(oldPath).isFile).isTrue()

        failingStore.failDeletes = false
        queuedRepository.retryPendingImageCleanup()

        assertThat(database.cardDao().getPendingImageDeletions()).isEmpty()
        assertThat(failingStore.resolve(oldPath).exists()).isFalse()
    }

    private class FailingDeleteImageStore(
        private val delegate: CardImageStore,
    ) : CardImageStore by delegate {
        var failDeletes: Boolean = false

        override suspend fun delete(path: String) {
            if (failDeletes) throw IOException("模拟图片删除失败")
            delegate.delete(path)
        }
    }

    private companion object {
        val testPngValidator = ValuePngValidator(PngDecoder { bytes: ByteArray ->
            if (bytes.size >= 24) {
                val input = DataInputStream(bytes.inputStream())
                input.skipBytes(16)
                Pair(input.readInt(), input.readInt())
            } else {
                null as Pair<Int, Int>?
            }
        })
        val validPng: ByteArray by lazy { png(1024, 2048) }

        fun png(width: Int, height: Int): ByteArray {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { png ->
                png.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                val header = ByteArrayOutputStream().also { bytes ->
                    DataOutputStream(bytes).use { data ->
                        data.writeInt(width)
                        data.writeInt(height)
                        data.write(byteArrayOf(8, 6, 0, 0, 0))
                    }
                }.toByteArray()
                writeChunk(png, "IHDR", header)
                val compressed = ByteArrayOutputStream().also { bytes ->
                    DeflaterOutputStream(bytes).use { deflater ->
                        val transparentRow = ByteArray(width * 4 + 1)
                        repeat(height) { deflater.write(transparentRow) }
                    }
                }.toByteArray()
                writeChunk(png, "IDAT", compressed)
                writeChunk(png, "IEND", byteArrayOf())
            }
            return output.toByteArray()
        }

        private fun writeChunk(output: DataOutputStream, type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            output.writeInt(data.size)
            output.write(typeBytes)
            output.write(data)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }
            output.writeInt(crc.value.toInt())
        }
    }
}

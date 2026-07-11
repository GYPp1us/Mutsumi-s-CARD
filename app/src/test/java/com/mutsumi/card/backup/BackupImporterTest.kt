package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupImporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `导入总是生成新卡组卡片 ID 和图片路径并保持引用一致`() = runTest {
        val gateway = RecordingImportGateway()
        val ids = ArrayDeque(listOf(101L, 202L))
        val importer = BackupImporter(gateway, idGenerator = { ids.removeFirst() }, imageNameGenerator = { "new.png" })
        val archive = validatedArchive()

        val result = archive.use { importer.importCopy(it) }

        assertThat(result).isEqualTo(ImportSummary(1, 1))
        val batch = gateway.batch!!
        assertThat(batch.snapshot.decks.single().id).isEqualTo(101)
        assertThat(batch.snapshot.cards.single().id).isEqualTo(202)
        assertThat(batch.snapshot.cards.single().deckId).isEqualTo(101)
        assertThat(batch.snapshot.cards.single().valueImagePath).isEqualTo("images/new.png")
        assertThat(batch.snapshot.reviews.single().cardId).isEqualTo(202)
        assertThat(batch.images.keys).containsExactly("images/new.png")
        assertThat(batch.images.getValue("images/new.png")).isSameInstanceAs(archiveImage)
    }

    @Test
    fun `网关失败时导入失败且不伪造成功`() = runTest {
        val expected = IllegalStateException("事务失败")
        val importer = BackupImporter(object : ImportGateway {
            override suspend fun importAtomically(batch: ImportBatch): ImportSummary = throw expected
        })

        val error = runCatching { validatedArchive().use { importer.importCopy(it) } }.exceptionOrNull()

        assertThat(error).isSameInstanceAs(expected)
    }

    @Test
    fun `ID 生成器冲突会在调用网关前失败`() = runTest {
        val gateway = RecordingImportGateway()
        val importer = BackupImporter(gateway, idGenerator = { 7L })
        val snapshot = fixtureSnapshot().copy(
            decks = listOf(BackupDeck(1, "A", 1, 1), BackupDeck(3, "B", 1, 1)),
        )
        val archive = validatedArchive(snapshot)

        val error = runCatching { archive.use { importer.importCopy(it) } }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(gateway.batch).isNull()
    }

    private lateinit var archiveImage: File

    private fun validatedArchive(snapshot: BackupSnapshot = fixtureSnapshot()): ValidatedArchive {
        val session = File(temporaryFolder.root, "validated-${System.nanoTime()}").apply { mkdirs() }
        archiveImage = File(session, "image.png").apply { writeBytes(byteArrayOf(1)) }
        return ValidatedArchive(
            session,
            BackupManifest(2, 1, "0.4.0", snapshot.decks.size, snapshot.cards.size, snapshot.reviews.size, 1, emptyList()),
            snapshot,
            mapOf("images/value-2.png" to archiveImage),
        )
    }

    private fun fixtureSnapshot() = BackupSnapshot(
        listOf(BackupDeck(1, "默认卡组", 1, 1)),
        listOf(BackupCard(2, 1, "雨", "images/value-2.png", 1, 1, false)),
        listOf(BackupReviewState(2, 1.0, 0, 0, 0, 0, null)),
    )

    private class RecordingImportGateway : ImportGateway {
        var batch: ImportBatch? = null
        override suspend fun importAtomically(batch: ImportBatch): ImportSummary {
            this.batch = batch
            return ImportSummary(batch.snapshot.decks.size, batch.snapshot.cards.size)
        }
    }
}

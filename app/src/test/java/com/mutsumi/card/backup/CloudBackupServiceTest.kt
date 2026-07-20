package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloudBackupServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `组变更会写入新快照并修复云端历史中丢失的正反图片对象`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("默认卡组"))
        val remote = MemoryRemoteStore()
        val timestamps = ArrayDeque(listOf(10L, 20L))
        val ids = ArrayDeque(listOf("first", "second"))
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            now = { timestamps.removeFirst() },
            idGenerator = { ids.removeFirst() },
            remoteFactory = { remote },
        )

        operations.backup(config())
        remote.files.remove("objects/${sha(backBytes)}.png")
        source.exportData = exportData("已改名卡组", BackupDeck(2, "新增空卡组", 2, 2))

        val second = operations.backup(config())
        val snapshotId = second.overview.snapshots.first().id
        val document = Json.decodeFromString<CloudSnapshotDocument>(
            remote.files.getValue("snapshots/$snapshotId.json").decodeToString(),
        )

        assertThat(document.snapshot.decks.map { it.name }).containsExactly("已改名卡组", "新增空卡组").inOrder()
        assertThat(remote.files["objects/${sha(frontBytes)}.png"]).isEqualTo(frontBytes)
        assertThat(remote.files["objects/${sha(backBytes)}.png"]).isEqualTo(backBytes)

        operations.restore(config(), snapshotId)

        assertThat(source.importedBatch!!.snapshot.decks.map { it.name })
            .containsExactly("已改名卡组", "新增空卡组").inOrder()
        assertThat(source.importedBatch!!.images.keys)
            .containsExactly("images/front-2.png", "images/value-2.png")
    }

    private fun config() = CloudBackupConfig("https://example.test/dav", "user", "password")

    private fun exportData(firstDeckName: String, vararg extraDecks: BackupDeck): BackupExportData {
        val front = File(temporaryFolder.root, "front.png").apply { writeBytes(frontBytes) }
        val back = File(temporaryFolder.root, "back.png").apply { writeBytes(backBytes) }
        val card = BackupCard(
            id = 2,
            deckId = 1,
            keyText = "细胞",
            valueImagePath = "images/value-2.png",
            createdAt = 1,
            updatedAt = 1,
            archived = false,
            frontImagePath = "images/front-2.png",
        )
        return BackupExportData(
            snapshot = BackupSnapshot(
                decks = listOf(BackupDeck(1, firstDeckName, 1, 1)) + extraDecks,
                cards = listOf(card),
                reviews = listOf(BackupReviewState(2, 1.0, 0, 0, 0, 0, null)),
            ),
            images = mapOf(card.frontImagePath!! to front, card.valueImagePath to back),
        )
    }

    private class FakeCloudBackupDataAccess(
        var exportData: BackupExportData,
    ) : CloudBackupDataAccess {
        var importedBatch: ImportBatch? = null
        override suspend fun loadExportData(): BackupExportData = exportData
        override suspend fun importBatch(batch: ImportBatch): ImportSummary {
            importedBatch = batch
            return ImportSummary(batch.snapshot.decks.size, batch.snapshot.cards.size)
        }
    }

    private class MemoryRemoteStore : CloudRemoteStore {
        val files = mutableMapOf<String, ByteArray>()
        override suspend fun ensureDirectories() = Unit
        override suspend fun get(path: String): ByteArray? = files[path]?.copyOf()
        override suspend fun put(path: String, bytes: ByteArray, contentType: String) {
            files[path] = bytes.copyOf()
        }
        override suspend fun delete(path: String) {
            files.remove(path)
        }
    }

    private companion object {
        val frontBytes = byteArrayOf(1, 2, 3)
        val backBytes = byteArrayOf(4, 5, 6)
    }
}

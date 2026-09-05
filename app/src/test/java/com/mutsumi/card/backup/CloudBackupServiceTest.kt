package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

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
            baselineFile = File(temporaryFolder.root, "baseline.json"),
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

    @Test
    fun `冲突在用户选择前不写快照，选择云端后会保留云端冲突版本`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("初始卡组"))
        val remote = MemoryRemoteStore()
        val baseline = File(temporaryFolder.root, "baseline.json")
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            baselineFile = baseline,
            now = ArrayDeque(listOf(10L, 20L))::removeFirst,
            idGenerator = ArrayDeque(listOf("first", "second"))::removeFirst,
            remoteFactory = { remote },
        )

        operations.backup(config())
        source.exportData = exportData("本地卡组")
        rewriteLatestDeckName(remote, "10-first", "云端卡组")
        val indexBefore = remote.files.getValue("index.json").copyOf()
        val baselineBefore = baseline.readBytes()

        val conflict = try {
            operations.backup(config())
            null
        } catch (error: CloudConflictException) {
            error
        }

        assertThat(requireNotNull(conflict).entries).hasSize(1)
        assertThat(conflict.entries.single().kind).isEqualTo(CloudConflictKind.Deck)
        assertThat(remote.files.getValue("index.json")).isEqualTo(indexBefore)
        assertThat(baseline.readBytes()).isEqualTo(baselineBefore)

        val result = operations.backup(
            config = config(),
            conflictResolution = CloudConflictResolution.UseCloud,
        )
        val document = readDocument(remote, result.overview.snapshots.first().id)

        assertThat(document.snapshot.decks.single().name).isEqualTo("云端卡组")
        assertThat(source.importedBatch!!.snapshot.decks.single().name).isEqualTo("云端卡组")
    }

    @Test
    fun `无冲突的云端独立改动会回写本地以免下次同步反向覆盖`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("初始卡组"))
        val remote = MemoryRemoteStore()
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            baselineFile = File(temporaryFolder.root, "baseline.json"),
            now = ArrayDeque(listOf(10L, 20L))::removeFirst,
            idGenerator = ArrayDeque(listOf("first", "second"))::removeFirst,
            remoteFactory = { remote },
        )

        operations.backup(config())
        rewriteLatestDeckName(remote, "10-first", "云端独立改名")

        operations.backup(config())

        assertThat(source.importedBatch!!.snapshot.decks.single().name).isEqualTo("云端独立改名")
    }

    @Test
    fun `无冲突的云端删除会精确回写本地而不受推送删除开关影响`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("初始卡组"))
        val remote = MemoryRemoteStore()
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            baselineFile = File(temporaryFolder.root, "baseline.json"),
            now = ArrayDeque(listOf(10L, 20L))::removeFirst,
            idGenerator = ArrayDeque(listOf("first", "second"))::removeFirst,
            remoteFactory = { remote },
        )

        operations.backup(config())
        rewriteLatestSnapshot(remote, "10-first", CloudSnapshot(emptyList(), emptyList(), emptyList()))

        operations.backup(config(), pushDelete = false)

        assertThat(source.importedBatch!!.snapshot.cards).isEmpty()
        assertThat(source.lastDeleteExtras).isTrue()
    }

    @Test
    fun `恢复会精确应用云端删除而不受拉取删除开关影响`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("初始卡组"))
        val remote = MemoryRemoteStore()
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            baselineFile = File(temporaryFolder.root, "baseline.json"),
            now = { 10L },
            idGenerator = { "first" },
            remoteFactory = { remote },
        )

        operations.backup(config())
        rewriteLatestSnapshot(remote, "10-first", CloudSnapshot(emptyList(), emptyList(), emptyList()))

        operations.restore(config(), "10-first", pullDelete = false)

        assertThat(source.importedBatch!!.snapshot.cards).isEmpty()
        assertThat(source.lastDeleteExtras).isTrue()
    }

    @Test
    fun `确认期间冲突内容变化时旧选择不会套用到新版本`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("初始卡组"))
        val remote = MemoryRemoteStore()
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            baselineFile = File(temporaryFolder.root, "baseline.json"),
            now = ArrayDeque(listOf(10L, 20L))::removeFirst,
            idGenerator = ArrayDeque(listOf("first", "second"))::removeFirst,
            remoteFactory = { remote },
        )
        operations.backup(config())
        source.exportData = exportData("本地卡组")
        rewriteLatestDeckName(remote, "10-first", "云端卡组 A")
        val shown = try {
            operations.backup(config())
            null
        } catch (error: CloudConflictException) {
            error
        }
        val indexBeforeRetry = remote.files.getValue("index.json").copyOf()

        rewriteLatestDeckName(remote, "10-first", "云端卡组 B")
        val retried = try {
            operations.backup(
                config = config(),
                conflictResolution = CloudConflictResolution.UseCloud,
                expectedConflicts = requireNotNull(shown).entries,
            )
            null
        } catch (error: CloudConflictException) {
            error
        }

        assertThat(requireNotNull(retried).entries.single().cloudSummary).isEqualTo("云端卡组 B")
        assertThat(remote.files.getValue("index.json")).isEqualTo(indexBeforeRetry)
        assertThat(source.importedBatch).isNull()
    }

    @Test
    fun `缺失基线的首同步会检测同稳定ID的不同记录`() = runTest {
        val remote = MemoryRemoteStore()
        val cloudOperations = RepositoryCloudBackupOperations(
            repositoryOperations = FakeCloudBackupDataAccess(exportData("云端卡组")),
            temporaryDirectory = File(temporaryFolder.root, "cloud-restore"),
            now = { 10L },
            idGenerator = { "cloud" },
            remoteFactory = { remote },
        )
        cloudOperations.backup(config())
        val localOperations = RepositoryCloudBackupOperations(
            repositoryOperations = FakeCloudBackupDataAccess(exportData("本地卡组")),
            temporaryDirectory = File(temporaryFolder.root, "local-restore"),
            baselineFile = File(temporaryFolder.root, "missing-baseline.json"),
            now = { 20L },
            idGenerator = { "local" },
            remoteFactory = { remote },
        )

        val conflict = try {
            localOperations.backup(config())
            null
        } catch (error: CloudConflictException) {
            error
        }

        assertThat(requireNotNull(conflict).entries.map(CloudConflict::kind))
            .containsExactly(CloudConflictKind.Deck)
    }

    @Test
    fun `云端没有索引时按空快照检测基线后的本地改动`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("初始卡组"))
        val remote = MemoryRemoteStore()
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            baselineFile = File(temporaryFolder.root, "baseline.json"),
            now = ArrayDeque(listOf(10L, 20L))::removeFirst,
            idGenerator = ArrayDeque(listOf("first", "second"))::removeFirst,
            remoteFactory = { remote },
        )

        operations.backup(config())
        source.exportData = exportData("本地卡组")
        remote.files.remove("index.json")

        val conflict = try {
            operations.backup(config())
            null
        } catch (error: CloudConflictException) {
            error
        }

        assertThat(requireNotNull(conflict).entries.map(CloudConflict::kind))
            .containsExactly(CloudConflictKind.Deck)
    }

    @Test
    fun `冲突解决后的本地应用失败不会发布新的远端索引`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("初始卡组"))
        val remote = MemoryRemoteStore()
        val baseline = File(temporaryFolder.root, "baseline.json")
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            baselineFile = baseline,
            now = ArrayDeque(listOf(10L, 20L))::removeFirst,
            idGenerator = ArrayDeque(listOf("first", "second"))::removeFirst,
            remoteFactory = { remote },
        )

        operations.backup(config())
        source.exportData = exportData("本地卡组")
        rewriteLatestDeckName(remote, "10-first", "云端卡组")
        source.importFailure = IOException("数据库事务失败")
        val indexBefore = remote.files.getValue("index.json").copyOf()
        val baselineBefore = baseline.readBytes()

        val failure = try {
            operations.backup(
                config = config(),
                conflictResolution = CloudConflictResolution.UseCloud,
            )
            null
        } catch (error: IOException) {
            error
        }

        assertThat(requireNotNull(failure)).hasMessageThat().contains("数据库事务失败")
        assertThat(remote.files.getValue("index.json")).isEqualTo(indexBefore)
        assertThat(remote.files.keys).doesNotContain("snapshots/20-second.json")
        assertThat(baseline.readBytes()).isEqualTo(baselineBefore)
    }

    @Test
    fun `冲突解决后的本地应用失败不会更新同步基线`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("初始卡组"))
        val remote = MemoryRemoteStore()
        val baseline = File(temporaryFolder.root, "baseline.json")
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            baselineFile = baseline,
            now = { 10L },
            idGenerator = { "first" },
            remoteFactory = { remote },
        )

        operations.backup(config())
        source.exportData = exportData("本地卡组")
        rewriteLatestDeckName(remote, "10-first", "云端卡组")
        source.importFailure = IOException("数据库事务失败")
        val baselineBefore = baseline.readBytes()

        val failure = try {
            operations.restore(
                config = config(),
                snapshotId = "10-first",
                conflictResolution = CloudConflictResolution.KeepLocal,
            )
            null
        } catch (error: IOException) {
            error
        }

        assertThat(requireNotNull(failure)).hasMessageThat().contains("数据库事务失败")
        assertThat(baseline.readBytes()).isEqualTo(baselineBefore)
    }

    @Test
    fun `发布索引时云端版本变化不会覆盖新索引或推进本地基线`() = runTest {
        val source = FakeCloudBackupDataAccess(exportData("初始卡组"))
        val remote = MemoryRemoteStore()
        val baseline = File(temporaryFolder.root, "baseline.json")
        val operations = RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "restore"),
            baselineFile = baseline,
            now = ArrayDeque(listOf(10L, 20L))::removeFirst,
            idGenerator = ArrayDeque(listOf("first", "second"))::removeFirst,
            remoteFactory = { remote },
        )
        operations.backup(config())
        val indexBefore = remote.files.getValue("index.json").copyOf()
        val baselineBefore = baseline.readBytes()
        source.exportData = exportData("本地卡组")
        remote.rejectNextIndexPublish = true

        val failure = try {
            operations.backup(config())
            null
        } catch (error: CloudBackupException) {
            error
        }

        assertThat(requireNotNull(failure)).hasMessageThat().contains("云端索引已变化")
        assertThat(remote.files.getValue("index.json")).isEqualTo(indexBefore)
        assertThat(baseline.readBytes()).isEqualTo(baselineBefore)
    }

    private fun config() = CloudBackupConfig("https://example.test/dav", "user", "password")

    private fun rewriteLatestDeckName(remote: MemoryRemoteStore, snapshotId: String, name: String) {
        val document = readDocument(remote, snapshotId)
        rewriteLatestSnapshot(
            remote,
            snapshotId,
            document.snapshot.copy(
                decks = document.snapshot.decks.map { it.copy(name = name, updatedAt = it.updatedAt + 1) },
            ),
        )
    }

    private fun rewriteLatestSnapshot(remote: MemoryRemoteStore, snapshotId: String, snapshot: CloudSnapshot) {
        val document = readDocument(remote, snapshotId)
        remote.files["snapshots/$snapshotId.json"] = Json.encodeToString(
            document.copy(
                snapshot = snapshot,
                images = document.images.filter { reference ->
                    snapshot.cards.any { card ->
                        reference.sha256 == card.valueImageSha256 || reference.sha256 == card.frontImageSha256
                    }
                },
            ),
        ).encodeToByteArray()
    }

    private fun readDocument(remote: MemoryRemoteStore, snapshotId: String): CloudSnapshotDocument =
        Json.decodeFromString(remote.files.getValue("snapshots/$snapshotId.json").decodeToString())

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
        var importFailure: IOException? = null
        var lastDeleteExtras: Boolean? = null
        override suspend fun loadExportData(): BackupExportData = exportData
        override suspend fun applyCloudSnapshot(
            snapshot: CloudSnapshot,
            images: Map<String, ByteArray>,
            deleteExtras: Boolean,
            sourcePaths: Map<String, String>,
        ): ImportSummary {
            lastDeleteExtras = deleteExtras
            return super<CloudBackupDataAccess>.applyCloudSnapshot(snapshot, images, deleteExtras, sourcePaths)
        }
        override suspend fun importBatch(batch: ImportBatch): ImportSummary {
            importFailure?.let { throw it }
            importedBatch = batch
            return ImportSummary(batch.snapshot.decks.size, batch.snapshot.cards.size)
        }
    }

    private class MemoryRemoteStore : CloudRemoteStore {
        val files = mutableMapOf<String, ByteArray>()
        var rejectNextIndexPublish = false
        private val versions = mutableMapOf<String, String>()
        private var nextVersion = 0L
        override suspend fun ensureDirectories() = Unit
        override suspend fun get(path: String): ByteArray? = files[path]?.copyOf()
        override suspend fun getWithVersion(path: String): CloudRemoteFile? = files[path]?.let { bytes ->
            CloudRemoteFile(bytes.copyOf(), versions[path])
        }
        override suspend fun put(path: String, bytes: ByteArray, contentType: String) {
            files[path] = bytes.copyOf()
            versions[path] = (++nextVersion).toString()
        }
        override suspend fun putIfUnchanged(
            path: String,
            bytes: ByteArray,
            contentType: String,
            expectedVersion: String?,
        ): Boolean {
            if (path == "index.json" && rejectNextIndexPublish) {
                rejectNextIndexPublish = false
                return false
            }
            if (expectedVersion == null && path in files) return false
            if (expectedVersion != null && versions[path] != expectedVersion) return false
            put(path, bytes, contentType)
            return true
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

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

/** v0.9.0 事故复现：明确区分时间顺序、旧内容回写和同步期间的本地新增。 */
class CloudBackupDataLossReproductionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `仅云端备份时间较晚不会删除基线外的本地新增`() = runTest {
        val remote = MemoryRemote()
        val source = MemoryData(snapshot("原卡"))
        val first = operations("a", source, remote, 10000)
        first.backup(config)
        source.state = snapshot("原卡", "未上传新增")
        operations("b", MemoryData(snapshot("原卡")), remote, 30000).backup(config)

        val result = operations("a", source, remote, 40000).backup(config)
        val latest = Json.decodeFromString<CloudSnapshotDocument>(
            remote.files.getValue("snapshots/${result.overview.snapshots.first().id}.json").decodeToString(),
        )

        assertThat(source.state.cards.map { it.keyText }).containsExactly("原卡", "未上传新增")
        assertThat(latest.snapshot.cards.map { it.keyText }).containsExactly("原卡", "未上传新增")
    }

    @Test fun `复现旧设备推送删除后另一设备普通备份静默删除基线内卡片`() = runTest {
        val remote = MemoryRemote()
        val source = MemoryData(snapshot("原卡", "新增卡"))
        operations("a", source, remote, 20000).backup(config)
        operations("b", MemoryData(snapshot("原卡")), remote, 30000).backup(config, pushDelete = true)

        operations("a", source, remote, 40000).backup(config, pushDelete = false)

        assertThat(source.state.cards.map { it.keyText }).containsExactly("原卡")
        assertThat(source.destructiveApplyCount).isEqualTo(1)
    }

    @Test fun `复现读取本地之后新增卡片在云端独立改动回写时被删除`() = runTest {
        val remote = MemoryRemote()
        val source = MemoryData(snapshot("原卡"))
        operations("a", source, remote, 10000).backup(config)
        rewriteDeck(remote, "10000-a")
        remote.onNextSnapshotRead = { source.state = snapshot("原卡", "同步期间新增") }

        operations("a", source, remote, 30000).backup(config)

        assertThat(source.state.cards.map { it.keyText }).containsExactly("原卡")
        assertThat(source.state.decks.single().name).isEqualTo("云端独立改名")
        assertThat(source.destructiveApplyCount).isEqualTo(1)
    }

    @Test fun `复现索引发布冲突失败仍已删除本地并且没有回滚`() = runTest {
        val remote = MemoryRemote()
        val source = MemoryData(snapshot("原卡"))
        operations("a", source, remote, 10000).backup(config)
        val baseline = File(temporaryFolder.root, "a-baseline.json").readBytes()
        rewriteDeck(remote, "10000-a")
        remote.onNextSnapshotRead = { source.state = snapshot("原卡", "同步期间新增") }
        remote.rejectIndex = true

        val failure = try {
            operations("a", source, remote, 30000).backup(config)
            null
        } catch (error: CloudBackupException) {
            error
        }

        assertThat(requireNotNull(failure).message).contains("云端索引已变化")
        assertThat(source.state.cards.map { it.keyText }).containsExactly("原卡")
        assertThat(source.destructiveApplyCount).isEqualTo(1)
        assertThat(File(temporaryFolder.root, "a-baseline.json").readBytes()).isEqualTo(baseline)
    }

    private fun operations(device: String, source: MemoryData, remote: MemoryRemote, time: Long) =
        RepositoryCloudBackupOperations(
            repositoryOperations = source,
            temporaryDirectory = File(temporaryFolder.root, "$device-temp"),
            baselineFile = File(temporaryFolder.root, "$device-baseline.json"),
            now = { time }, idGenerator = { device }, remoteFactory = { remote },
        )

    private fun rewriteDeck(remote: MemoryRemote, id: String) {
        val document = Json.decodeFromString<CloudSnapshotDocument>(remote.files.getValue("snapshots/$id.json").decodeToString())
        remote.files["snapshots/$id.json"] = Json.encodeToString(document.copy(snapshot = document.snapshot.copy(
            decks = document.snapshot.decks.map { it.copy(name = "云端独立改名", updatedAt = 25000) },
        ))).encodeToByteArray()
    }

    private fun snapshot(vararg keys: String) = CloudSnapshot(
        decks = listOf(CloudDeckRecord("deck", "卡组", 1, 1)),
        cards = keys.mapIndexed { index, key -> CloudCardRecord("card-$index", "deck", key, 1, 1, false, imageHash) },
        reviews = keys.mapIndexed { index, _ -> CloudReviewRecord("card-$index", 1.0, 0, 0, 0, 0, null) },
    )

    private class MemoryData(var state: CloudSnapshot) : CloudBackupDataAccess {
        var destructiveApplyCount = 0
        override suspend fun loadExportData(): BackupExportData = error("仅测试真实云同步路径")
        override suspend fun importBatch(batch: ImportBatch): ImportSummary = error("不使用 ZIP 兼容导入替代真实删除行为")
        override suspend fun loadCloudState() = CloudLocalState(
            CloudSnapshotDocument(snapshot = state, images = listOf(CloudImageReference(imageHash, image.size.toLong()))),
            mapOf(imageHash to image),
        )
        override suspend fun applyCloudSnapshot(snapshot: CloudSnapshot, images: Map<String, ByteArray>, deleteExtras: Boolean,
                                              sourcePaths: Map<String, String>): ImportSummary {
            if (deleteExtras) destructiveApplyCount++
            check(deleteExtras) { "复现原始版本的精确覆盖调用" }
            state = snapshot
            return ImportSummary(state.decks.size, state.cards.size)
        }
    }

    private class MemoryRemote : CloudRemoteStore {
        val files = mutableMapOf<String, ByteArray>()
        var onNextSnapshotRead: (() -> Unit)? = null
        var rejectIndex = false
        private val versions = mutableMapOf<String, String>()
        private var sequence = 0
        override suspend fun ensureDirectories() = Unit
        override suspend fun get(path: String): ByteArray? {
            if (path.startsWith("snapshots/")) {
                val callback = onNextSnapshotRead
                onNextSnapshotRead = null
                callback?.invoke()
            }
            return files[path]?.copyOf()
        }
        override suspend fun getWithVersion(path: String) = files[path]?.let { CloudRemoteFile(it.copyOf(), versions[path]) }
        override suspend fun put(path: String, bytes: ByteArray, contentType: String) {
            files[path] = bytes.copyOf()
            versions[path] = (++sequence).toString()
        }
        override suspend fun putIfUnchanged(path: String, bytes: ByteArray, contentType: String, expectedVersion: String?): Boolean {
            if (rejectIndex || versions[path] != expectedVersion) return false
            put(path, bytes, contentType)
            return true
        }
        override suspend fun delete(path: String) { files.remove(path) }
    }

    companion object {
        private val config = CloudBackupConfig("https://example.test/dav", "test", "test")
        private val image = byteArrayOf(1, 2, 3)
        private val imageHash = sha256(image)
    }
}

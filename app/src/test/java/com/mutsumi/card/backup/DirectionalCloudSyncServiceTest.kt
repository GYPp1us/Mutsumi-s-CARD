package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectionalCloudSyncServiceTest {
    @get:Rule val folder = TemporaryFolder()
    private val png = validPng()
    private val hash = sha256(png)
    private val config = CloudBackupConfig("https://example.test", "user", "private")
    private fun doc(vararg keys: String) = CloudSnapshotDocument(snapshot = CloudSnapshot(
        listOf(CloudDeckRecord("deck", "卡组", 1, 1)),
        keys.map { CloudCardRecord(it, "deck", it, 1, 1, false, hash) },
        keys.map { CloudReviewRecord(it, 1.0, 0, 0, 0, 0, null) },
    ), images = if (keys.isEmpty()) emptyList() else listOf(CloudImageReference(hash, png.size.toLong())))

    private inner class Data(var document: CloudSnapshotDocument) : CloudSyncDataAccess {
        var revision = 0L
        var imageReads = 0
        var applies = 0
        var onImages: (() -> Unit)? = null
        override suspend fun readMetadata() = CloudMetadataState(document, revision.toString())
        override suspend fun readImages(hashes: Set<String>): Map<String, ByteArray> {
            imageReads++
            onImages?.also { onImages = null }?.invoke()
            return hashes.associateWith { png }
        }
        override suspend fun applyPlan(document: CloudSnapshotDocument, images: Map<String, ByteArray>, expectedRevision: String, deleteExtras: Boolean): ImportSummary {
            if (expectedRevision != revision.toString()) throw CloudBackupException("本地数据已变化")
            this.document = document; applies++; revision++
            return ImportSummary(document.snapshot.decks.size, document.snapshot.cards.size)
        }
    }

    private class Remote : CloudRemoteStore {
        val files = mutableMapOf<String, ByteArray>()
        var writes = 0
        var reject = false
        override suspend fun ensureDirectories() = Unit
        override suspend fun get(path: String) = files[path]
        override suspend fun getWithVersion(path: String) = files[path]?.let { CloudRemoteFile(it, sha256(it)) }
        override suspend fun put(path: String, bytes: ByteArray, contentType: String) { files[path] = bytes; writes++ }
        override suspend fun putIfUnchanged(path: String, bytes: ByteArray, contentType: String, expectedVersion: String?): Boolean {
            if (reject || files[path]?.let(::sha256) != expectedVersion) return false
            put(path, bytes, contentType); return true
        }
        override suspend fun delete(path: String) = error("同步不得物理删除历史对象")
    }

    private fun remote(document: CloudSnapshotDocument) = Remote().apply {
        files["library-index.json"] = Json.encodeToString(CloudLibraryIndex(
            revision = "cloud-1", updatedAt = 100, document = document, decks = CloudSyncPlanner.deckIndex(document),
        )).encodeToByteArray()
        files["library-v3/objects/$hash.png"] = png
    }
    private fun service(data: Data, remote: Remote) = DirectionalCloudSyncService(data, folder.root, remoteFactory = { remote }, pngValidator = testPngValidator)

    @Test fun `预览只读元数据不读取图片不写入任何一侧`() = runTest {
        val data = Data(doc("本地新增")); val remote = remote(doc("云端旧卡"))
        val preview = service(data, remote).preview(config, CloudSyncMode.Pull)
        assertThat(preview.plan.entries.map { it.title }).containsExactly("云端旧卡")
        assertThat(data.imageReads).isEqualTo(0); assertThat(data.applies).isEqualTo(0); assertThat(remote.writes).isEqualTo(0)
    }

    @Test fun `普通推送和发布失败均不会反向修改本地`() = runTest {
        val data = Data(doc("本地新增")); val remote = remote(doc("云端旧卡"))
        val service = service(data, remote)
        val preview = service.preview(config, CloudSyncMode.Push)
        remote.reject = true
        val result = runCatching { service.execute(config, preview.token) {} }
        assertThat(result.exceptionOrNull()).isInstanceOf(CloudBackupException::class.java)
        assertThat(data.applies).isEqualTo(0)
        assertThat(data.document.snapshot.cards.single().keyText).isEqualTo("本地新增")
    }

    @Test fun `预览后新增本地卡片或修改云端索引均拒绝执行`() = runTest {
        val data = Data(doc("原卡")); val remote = remote(doc("云端卡")); val service = service(data, remote)
        val first = service.preview(config, CloudSyncMode.PullDelete)
        data.document = doc("原卡", "预览后新增"); data.revision++
        assertThat(runCatching { service.execute(config, first.token) {} }.exceptionOrNull()).isInstanceOf(CloudBackupException::class.java)
        val second = service.preview(config, CloudSyncMode.PushDelete)
        remote.files["library-index.json"] = remote.files.getValue("library-index.json") + byteArrayOf(32)
        assertThat(runCatching { service.execute(config, second.token) {} }.exceptionOrNull()).isInstanceOf(CloudBackupException::class.java)
        assertThat(data.applies).isEqualTo(0)
        assertThat(data.document.snapshot.cards.size).isEqualTo(2)
    }

    @Test fun `一次预览只能执行一次且推送保留云端独有卡片`() = runTest {
        val data = Data(doc("本地新增")); val remote = remote(doc("云端旧卡")); val service = service(data, remote)
        val preview = service.preview(config, CloudSyncMode.Push)
        val progress = mutableListOf<CloudSyncProgress>()
        service.execute(config, preview.token) { progress += it }
        val index = Json.decodeFromString<CloudLibraryIndex>(remote.files.getValue("library-index.json").decodeToString())
        assertThat(index.document.snapshot.cards.map { it.keyText }).containsExactly("本地新增", "云端旧卡")
        assertThat(progress.last().fraction).isEqualTo(1f)
        assertThat(data.applies).isEqualTo(0)
        assertThat(runCatching { service.execute(config, preview.token) {} }.isFailure).isTrue()
    }

    @Test fun `普通拉取不删除本地独有卡片并留下可独立恢复的安全副本`() = runTest {
        val data = Data(doc("本地新增")); val remote = remote(doc("云端旧卡")); val service = service(data, remote)
        val preview = service.preview(config, CloudSyncMode.Pull)
        service.execute(config, preview.token) {}
        assertThat(data.document.snapshot.cards.map { it.keyText }).containsExactly("本地新增", "云端旧卡")
        assertThat(data.applies).isEqualTo(1)
        val safety = requireNotNull(java.io.File(folder.root, "local-safety").listFiles()).single()
        java.util.zip.ZipFile(safety).use { zip ->
            val old = Json.decodeFromString<CloudSnapshotDocument>(zip.getInputStream(zip.getEntry("metadata.json")).readBytes().decodeToString())
            assertThat(old.snapshot.cards.single().keyText).isEqualTo("本地新增")
            assertThat(zip.getInputStream(zip.getEntry("objects/$hash.png")).readBytes()).isEqualTo(png)
        }
    }

    @Test fun `拉取期间新增卡片会在最终提交时被修订检查保护`() = runTest {
        val data = Data(doc("原卡")); val remote = remote(doc("云端独立更新")); val service = service(data, remote)
        val preview = service.preview(config, CloudSyncMode.Pull)
        data.onImages = { data.document = doc("原卡", "传输期间新增"); data.revision++ }
        assertThat(runCatching { service.execute(config, preview.token) {} }.exceptionOrNull()).isInstanceOf(CloudBackupException::class.java)
        assertThat(data.document.snapshot.cards.map { it.keyText }).containsExactly("原卡", "传输期间新增")
        assertThat(data.applies).isEqualTo(0)
    }

    @Test fun `拉取删除不新增不更新且普通推送可保留另一设备删除前的本地卡片`() = runTest {
        val data = Data(doc("共同", "本地新增")); val remote = remote(doc("共同", "云端新增")); val service = service(data, remote)
        service.execute(config, service.preview(config, CloudSyncMode.PullDelete).token) {}
        assertThat(data.document.snapshot.cards.map { it.keyText }).containsExactly("共同")
        assertThat(remote.writes).isEqualTo(0)
        data.document = doc("共同", "另一设备未上传卡片"); data.revision++
        service.execute(config, service.preview(config, CloudSyncMode.Push).token) {}
        assertThat(data.document.snapshot.cards.map { it.keyText }).containsExactly("共同", "另一设备未上传卡片")
        assertThat(data.applies).isEqualTo(1)
    }

    @Test fun `未建立云端数据库时不允许拉取删除`() = runTest {
        val data = Data(doc("原卡")); val remote = Remote()
        assertThat(runCatching { service(data, remote).preview(config, CloudSyncMode.PullDelete) }.exceptionOrNull()).isInstanceOf(CloudBackupException::class.java)
        assertThat(data.applies).isEqualTo(0)
    }
}

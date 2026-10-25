package com.mutsumi.card.backup

import com.mutsumi.card.data.image.ValuePngValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CloudMetadataState(val document: CloudSnapshotDocument, val revision: String)

interface CloudSyncDataAccess {
    suspend fun readMetadata(): CloudMetadataState
    suspend fun readImages(hashes: Set<String>): Map<String, ByteArray>
    /** 实现必须在写入事务中检查预览修订号；拒绝过期清单。 */
    suspend fun applyPlan(document: CloudSnapshotDocument, images: Map<String, ByteArray>, expectedRevision: String, deleteExtras: Boolean): ImportSummary
}

data class CloudSyncProgress(val stage: String, val completed: Int, val total: Int, val bytes: Long = 0, val totalBytes: Long = 0) {
    val fraction: Float get() = if (total == 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}

data class CloudSyncPreview(val token: String, val mode: CloudSyncMode, val plan: CloudSyncPlan, val cloudUpdatedAt: Long, val transferBytes: Long)

interface CloudSyncOperations {
    suspend fun inspect(config: CloudBackupConfig): CloudLibraryIndex?
    suspend fun preview(config: CloudBackupConfig, mode: CloudSyncMode, snapshotId: String? = null): CloudSyncPreview
    suspend fun execute(config: CloudBackupConfig, token: String, progress: (CloudSyncProgress) -> Unit): ImportSummary
}

/** 四个单向操作共用预览令牌；推送永远不调用本地写入。 */
class DirectionalCloudSyncService(
    private val data: CloudSyncDataAccess,
    private val storageDirectory: File,
    client: OkHttpClient = OkHttpClient(),
    private val remoteFactory: (CloudBackupConfig) -> CloudRemoteStore = { WebDavClient(it, client) },
    private val json: Json = Json { encodeDefaults = true },
    private val now: () -> Long = System::currentTimeMillis,
    private val pngValidator: ValuePngValidator = ValuePngValidator(),
) : CloudSyncOperations {
    private data class Head(val index: CloudLibraryIndex?, val file: CloudRemoteFile?)
    private data class Pending(val configKey: String, val local: CloudMetadataState, val head: Head, val preview: CloudSyncPreview, val objectDirectory: String)
    private val mutex = Mutex()
    private val pending = linkedMapOf<String, Pending>()

    override suspend fun inspect(config: CloudBackupConfig): CloudLibraryIndex? = withContext(Dispatchers.IO) {
        readHead(remoteFactory(config)).index
    }

    override suspend fun preview(config: CloudBackupConfig, mode: CloudSyncMode, snapshotId: String?): CloudSyncPreview =
        withContext(Dispatchers.IO) { mutex.withLock {
            val remote = remoteFactory(config)
            val head = readHead(remote)
            if (mode == CloudSyncMode.PullDelete && head.index == null) throw CloudBackupException("云端尚无索引，不能据此删除本地数据")
            var storageVersion = if (head.file == null) 2 else 3
            val cloud = if (snapshotId != null) {
                if (head.index?.snapshots?.none { it.id == snapshotId } != false) throw CloudBackupException("该历史版本已失效，请刷新")
                storageVersion = head.index.snapshots.first { it.id == snapshotId }.storageVersion
                readDocument(remote, snapshotId, storageVersion)
            } else head.index?.document ?: emptyDocument()
            val local = data.readMetadata()
            val plan = CloudSyncPlanner.plan(mode, local.document, cloud)
            val targetHashes = if (!mode.isPull && head.file == null) emptySet() else
                (if (mode.isPull) local.document else cloud).images.mapTo(hashSetOf()) { it.sha256 }
            val bytes = plan.document.images.filter { it.sha256 !in targetHashes }.sumOf { it.size }
            val preview = CloudSyncPreview(UUID.randomUUID().toString(), mode, plan, head.index?.updatedAt ?: 0, bytes)
            pending.clear()
            pending[preview.token] = Pending(configKey(config), local, head, preview, if (storageVersion == 3) "library-v3/objects" else "objects")
            preview
        } }

    override suspend fun execute(config: CloudBackupConfig, token: String, progress: (CloudSyncProgress) -> Unit): ImportSummary =
        withContext(Dispatchers.IO) { mutex.withLock {
            val prepared = pending.remove(token) ?: throw CloudBackupException("请先预览；预览已失效或执行过")
            if (prepared.configKey != configKey(config)) throw CloudBackupException("连接已变化，请重新预览")
            val remote = remoteFactory(config)
            val current = readHead(remote)
            checkHead(prepared.head, current)
            checkLocal(prepared.local)
            val document = prepared.preview.plan.document
            val mode = prepared.preview.mode
            if (prepared.preview.plan.entries.isEmpty()) {
                progress(CloudSyncProgress("已是最新", 1, 1))
                return@withLock ImportSummary(0, 0)
            }
            if (mode.isPull) pull(remote, prepared, document, progress) else push(remote, prepared, document, progress)
        } }

    private suspend fun pull(remote: CloudRemoteStore, prepared: Pending, document: CloudSnapshotDocument, progress: (CloudSyncProgress) -> Unit): ImportSummary {
        val localHashes = prepared.local.document.images.mapTo(hashSetOf()) { it.sha256 }
        progress(CloudSyncProgress("保全本地副本", 0, 1))
        val localImages = data.readImages(localHashes)
        saveSafetyCopy(prepared.local.document, localImages)
        val images = linkedMapOf<String, ByteArray>()
        var bytes = 0L
        document.images.forEachIndexed { index, ref ->
            val image = localImages[ref.sha256] ?: remote.get("${prepared.objectDirectory}/${ref.sha256}.png")
                ?: throw CloudBackupException("云端缺少图片，请保留当前数据并重新连接")
            if (image.size.toLong() != ref.size || sha256(image) != ref.sha256) throw CloudBackupException("云端图片校验失败，未修改本地")
            if (ref.sha256 !in localImages) pngValidator.validate(image)
            images[ref.sha256] = image
            bytes += image.size
            progress(CloudSyncProgress("下载并校验卡面", index + 1, document.images.size + 1, bytes, document.images.sumOf { it.size }))
        }
        checkHead(prepared.head, readHead(remote))
        // Room 实现在提交事务内再次检查，不依赖网络请求之前的检查。
        val result = data.applyPlan(document, images, prepared.local.revision, prepared.preview.mode.deletes)
        progress(CloudSyncProgress("完成", 1, 1, bytes, bytes))
        return result
    }

    private suspend fun push(remote: CloudRemoteStore, prepared: Pending, document: CloudSnapshotDocument, progress: (CloudSyncProgress) -> Unit): ImportSummary {
        val head = prepared.head
        if (head.file != null && head.file.version == null) throw CloudBackupException("服务器未提供索引版本，无法安全发布；请检查 WebDAV 条件写入支持")
        val existing = if (head.file == null) emptySet() else head.index?.document?.images?.mapTo(hashSetOf()) { it.sha256 }.orEmpty()
        val needed = document.images.filter { it.sha256 !in existing }
        val localHashes = prepared.local.document.images.mapTo(hashSetOf()) { it.sha256 }
        val images = data.readImages(needed.mapTo(hashSetOf()) { it.sha256 }.intersect(localHashes))
        remote.ensureDirectories()
        var bytes = 0L
        needed.forEachIndexed { index, ref ->
            val image = images[ref.sha256] ?: remote.get("${prepared.objectDirectory}/${ref.sha256}.png")?.also(pngValidator::validate)
                ?: throw CloudBackupException("迁移所需图片缺失，请保留旧备份并重新预览")
            if (image.size.toLong() != ref.size || sha256(image) != ref.sha256) throw CloudBackupException("本地图片已变化，请重新预览")
            remote.put("library-v3/objects/${ref.sha256}.png", image, "image/png")
            bytes += image.size
            progress(CloudSyncProgress("上传新增卡面", index + 1, needed.size + 2, bytes, needed.sumOf { it.size }))
        }
        checkLocal(prepared.local)
        val timestamp = now()
        val snapshotId = "$timestamp-${UUID.randomUUID()}"
        remote.put("library-v3/snapshots/$snapshotId.json", json.encodeToString(document).encodeToByteArray(), "application/json")
        val entries = prepared.preview.plan.entries
        val summary = CloudSnapshotSummary(snapshotId, timestamp,
            entries.count { it.kind == CloudConflictKind.Card && it.change != CloudSyncChange.Delete },
            entries.count { it.kind == CloudConflictKind.Card && it.change == CloudSyncChange.Delete },
            document.snapshot.cards.size, document.snapshot.decks.size,
            entries.count { it.kind == CloudConflictKind.Deck && it.change != CloudSyncChange.Delete },
            entries.count { it.kind == CloudConflictKind.Deck && it.change == CloudSyncChange.Delete }, document.images.map { it.sha256 }, storageVersion = 3)
        val index = CloudLibraryIndex(revision = UUID.randomUUID().toString(), updatedAt = timestamp, document = document,
            decks = CloudSyncPlanner.deckIndex(document), snapshots = (listOf(summary) + head.index?.snapshots.orEmpty()).take(CLOUD_BACKUP_WINDOW_SIZE))
        checkLocal(prepared.local)
        progress(CloudSyncProgress("发布索引", needed.size + 1, needed.size + 2, bytes, bytes))
        if (!remote.putIfUnchanged("library-index.json", json.encodeToString(index).encodeToByteArray(), "application/json", head.file?.version)) {
            throw CloudBackupException("云端索引已变化，未覆盖远端；本地数据未改动，请重新预览")
        }
        // 不立即清理历史对象：旧版本和事故恢复依赖这些原件。
        progress(CloudSyncProgress("完成", 1, 1, bytes, bytes))
        return ImportSummary(entries.count { it.kind == CloudConflictKind.Deck }, entries.count { it.kind == CloudConflictKind.Card })
    }

    private suspend fun checkLocal(expected: CloudMetadataState) {
        val current = data.readMetadata()
        if (current.revision != expected.revision || current.document != expected.document) throw CloudBackupException("本地数据已变化，请重新预览")
    }

    private fun checkHead(expected: Head, current: Head) {
        val same = if (expected.file != null || current.file != null)
            expected.file?.bytes?.contentEquals(current.file?.bytes) == true && expected.file.version == current.file?.version
        else expected.index == current.index
        if (!same) throw CloudBackupException("云端索引已变化，请重新预览")
    }

    private suspend fun readHead(remote: CloudRemoteStore): Head {
        val file = remote.getWithVersion("library-index.json")
        if (file != null) {
            val index = decode<CloudLibraryIndex>(file.bytes)
            if (index.formatVersion != 3) throw CloudBackupException("云端索引格式较新，请升级应用")
            CloudSyncPlanner.validate(index.document)
            if (index.decks != CloudSyncPlanner.deckIndex(index.document)) throw CloudBackupException("云端卡组索引与数据不一致")
            return Head(index, file)
        }
        val old = remote.get("index.json") ?: return Head(null, null)
        val legacy = decode<CloudBackupIndex>(old)
        val summary = legacy.snapshots.firstOrNull() ?: return Head(null, null)
        val document = readDocument(remote, summary.id)
        return Head(CloudLibraryIndex(revision = "legacy-${sha256(old)}", updatedAt = summary.createdAt,
            document = document, decks = CloudSyncPlanner.deckIndex(document), snapshots = legacy.snapshots), null)
    }

    private suspend fun readDocument(remote: CloudRemoteStore, id: String, storageVersion: Int = 2): CloudSnapshotDocument {
        if (!Regex("[0-9]+-[A-Za-z0-9-]+").matches(id)) throw CloudBackupException("快照标识无效")
        if (storageVersion !in 2..3) throw CloudBackupException("快照存储版本不兼容")
        val prefix = if (storageVersion == 3) "library-v3/snapshots" else "snapshots"
        val bytes = remote.get("$prefix/$id.json") ?: throw CloudBackupException("快照已不存在")
        val document = try { json.decodeFromString<CloudSnapshotDocument>(bytes.decodeToString()) }
        catch (_: kotlinx.serialization.SerializationException) {
            val old = decode<LegacyDocument>(bytes)
            val hashes = old.images.associate { it.localPath to it.sha256 }
            CloudSnapshotDocument(snapshot = CloudSnapshot(
                old.snapshot.decks.map { CloudDeckRecord("legacy-deck-${it.id}", it.name, it.createdAt, it.updatedAt) },
                old.snapshot.cards.map { CloudCardRecord("legacy-card-${it.id}", "legacy-deck-${it.deckId}", it.keyText, it.createdAt, it.updatedAt,
                    it.archived, hashes.getValue(it.valueImagePath), it.frontImagePath?.let(hashes::getValue)) },
                old.snapshot.reviews.map { CloudReviewRecord("legacy-card-${it.cardId}", it.weight, it.seenCount, it.againCount, it.unsureCount, it.knownCount, it.lastReviewedAt) }),
                images = old.images.map { CloudImageReference(it.sha256, it.size) })
        }
        CloudSyncPlanner.validate(document)
        return document
    }

    private fun saveSafetyCopy(document: CloudSnapshotDocument, images: Map<String, ByteArray>) {
        val directory = File(storageDirectory, "local-safety").apply { check(isDirectory || mkdirs()) }
        val target = File(directory, "${now()}-${UUID.randomUUID()}.zip")
        val temporary = File(directory, ".${target.name}.pending")
        FileOutputStream(temporary).use { output ->
            val zip = ZipOutputStream(object : java.io.FilterOutputStream(output) { override fun close() { flush() } })
            zip.use {
            zip.putNextEntry(ZipEntry("metadata.json")); zip.write(json.encodeToString(document).encodeToByteArray()); zip.closeEntry()
            images.forEach { (hash, image) ->
                if (sha256(image) != hash) throw CloudBackupException("本地保全图片校验失败，未执行拉取")
                zip.putNextEntry(ZipEntry("objects/$hash.png")); zip.write(image); zip.closeEntry()
            }
            }
            output.fd.sync()
        }
        atomicReplace(temporary, target)
    }

    private inline fun <reified T> decode(bytes: ByteArray): T = try { json.decodeFromString(bytes.decodeToString()) }
    catch (error: kotlinx.serialization.SerializationException) { throw CloudBackupException("云端元数据无效，未执行同步", error) }
    private fun configKey(config: CloudBackupConfig) = sha256("${config.serverUrl.trimEnd('/')}|${config.username}|${config.remoteDirectory}|${sha256(config.password.encodeToByteArray())}".encodeToByteArray())
    private fun emptyDocument() = CloudSnapshotDocument(snapshot = CloudSnapshot(emptyList(), emptyList(), emptyList()), images = emptyList())
}

internal fun atomicReplace(temporary: File, target: File) {
    try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
}

@Serializable private data class LegacyDocument(val formatVersion: Int = 1, val snapshot: BackupSnapshot, val images: List<LegacyImage>)
@Serializable private data class LegacyImage(val localPath: String, val sha256: String, val size: Long)

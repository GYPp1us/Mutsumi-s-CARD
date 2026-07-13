package com.mutsumi.card.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

class RepositoryCloudBackupOperations(
    private val repositoryOperations: RepositoryBackupOperations,
    private val temporaryDirectory: File,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val now: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : CloudBackupOperations {
    override suspend fun inspect(config: CloudBackupConfig): CloudBackupOverview = withContext(Dispatchers.IO) {
        val remote = WebDavClient(config, client)
        val index = loadIndex(remote)
        val current = buildCurrentDocument(repositoryOperations.loadExportData())
        overview(index, current, remote)
    }

    override suspend fun backup(config: CloudBackupConfig): CloudBackupResult = withContext(Dispatchers.IO) {
        val remote = WebDavClient(config, client)
        val index = loadIndex(remote)
        val exportData = repositoryOperations.loadExportData()
        val current = buildCurrentDocument(exportData)
        val currentOverview = overview(index, current.document, remote)
        remote.ensureDirectories()
        val knownHashes = index.snapshots.flatMapTo(mutableSetOf()) { it.imageHashes }
        current.bytesByHash.forEach { (hash, bytes) ->
            if (hash !in knownHashes) remote.put("objects/$hash.png", bytes, "image/png")
        }

        val createdAt = now()
        val snapshotId = "$createdAt-${idGenerator()}"
        remote.put(
            "snapshots/$snapshotId.json",
            json.encodeToString(current.document).encodeToByteArray(),
            "application/json; charset=utf-8",
        )
        val summary = CloudSnapshotSummary(
            id = snapshotId,
            createdAt = createdAt,
            addedOrChangedCount = currentOverview.addedOrChangedCount,
            deletedCount = currentOverview.deletedCount,
            cardCount = current.document.snapshot.cards.size,
            imageHashes = current.document.images.map { it.sha256 }.distinct().sorted(),
        )
        val allSnapshots = listOf(summary) + index.snapshots.filterNot { it.id == snapshotId }
        val retained = allSnapshots.take(CLOUD_BACKUP_WINDOW_SIZE)
        val expired = allSnapshots.drop(CLOUD_BACKUP_WINDOW_SIZE)
        remote.put(
            "index.json",
            json.encodeToString(CloudBackupIndex(snapshots = retained)).encodeToByteArray(),
            "application/json; charset=utf-8",
        )

        val warnings = cleanupExpired(remote, expired, retained)
        CloudBackupResult(
            overview = CloudBackupOverview(retained, 0, 0),
            createdSnapshot = true,
            warnings = warnings,
        )
    }

    override suspend fun restore(config: CloudBackupConfig, snapshotId: String): ImportSummary =
        withContext(Dispatchers.IO) {
            requireSafeSnapshotId(snapshotId)
            val remote = WebDavClient(config, client)
            val index = loadIndex(remote)
            if (index.snapshots.none { it.id == snapshotId }) {
                throw CloudBackupException("云端版本不存在或已超出保留窗口")
            }
            val document = loadDocument(remote, snapshotId)
            requireDirectory(temporaryDirectory)
            val session = File(temporaryDirectory, ".cloud-restore-${UUID.randomUUID()}")
            check(session.mkdir()) { "无法创建云端恢复临时目录" }
            var imported: ImportSummary? = null
            var failure: Exception? = null
            try {
                val filesByHash = mutableMapOf<String, File>()
                document.images.map { it.sha256 }.distinct().forEach { hash ->
                    val bytes = remote.get("objects/$hash.png")
                        ?: throw CloudBackupException("云端图片对象缺失：$hash")
                    if (sha256(bytes) != hash) throw CloudBackupException("云端图片校验失败：$hash")
                    filesByHash[hash] = File(session, "$hash.png").apply { writeBytes(bytes) }
                }
                val images = document.images.associate { reference ->
                    reference.localPath to requireNotNull(filesByHash[reference.sha256])
                }
                repositoryOperations.importBatch(ImportBatch(document.snapshot, images)).also { imported = it }
            } catch (error: Exception) {
                failure = error
                throw error
            } finally {
                try {
                    if (session.exists() && !session.deleteRecursively()) error("无法删除云端恢复临时目录")
                } catch (cleanupError: Exception) {
                    if (imported == null) {
                        failure?.addSuppressed(cleanupError)
                    } else {
                        imported = imported!!.copy(warnings = imported!!.warnings + "恢复已完成，但临时文件清理失败")
                    }
                }
            }
            imported ?: error("云端恢复未生成结果")
        }

    private suspend fun overview(
        index: CloudBackupIndex,
        current: CloudSnapshotDocument,
        remote: CloudRemoteStore,
    ): CloudBackupOverview {
        val previous = index.snapshots.firstOrNull()?.let { loadDocument(remote, it.id) }
        val currentFingerprints = fingerprints(current)
        val previousFingerprints = previous?.let(::fingerprints).orEmpty()
        val addedOrChanged = currentFingerprints.count { (id, fingerprint) ->
            previousFingerprints[id] != fingerprint
        }
        val deleted = previousFingerprints.keys.count { it !in currentFingerprints }
        return CloudBackupOverview(index.snapshots, addedOrChanged, deleted)
    }

    private suspend fun loadIndex(remote: CloudRemoteStore): CloudBackupIndex {
        val bytes = remote.get("index.json") ?: return CloudBackupIndex()
        val index = decode<CloudBackupIndex>(bytes, "云端索引")
        if (index.formatVersion != CLOUD_BACKUP_FORMAT_VERSION) {
            throw CloudBackupException("不支持的云端备份格式：${index.formatVersion}")
        }
        if (index.snapshots.size > CLOUD_BACKUP_WINDOW_SIZE) throw CloudBackupException("云端索引版本数量异常")
        index.snapshots.forEach { summary ->
            requireSafeSnapshotId(summary.id)
            if (summary.createdAt < 0 || summary.addedOrChangedCount < 0 || summary.deletedCount < 0) {
                throw CloudBackupException("云端索引包含无效统计")
            }
            summary.imageHashes.forEach(::requireSha256)
        }
        return index
    }

    private suspend fun loadDocument(remote: CloudRemoteStore, snapshotId: String): CloudSnapshotDocument {
        val bytes = remote.get("snapshots/$snapshotId.json")
            ?: throw CloudBackupException("云端快照缺失：$snapshotId")
        val document = decode<CloudSnapshotDocument>(bytes, "云端快照")
        validateDocument(document)
        return document
    }

    private fun buildCurrentDocument(data: BackupExportData): CurrentDocument {
        validateSnapshot(data.snapshot)
        val references = data.images.toSortedMap().map { (path, file) ->
            if (!file.isFile) throw CloudBackupException("本地图片不存在：$path")
            val bytes = file.readBytes()
            CloudImageReference(path, sha256(bytes), bytes.size.toLong()) to bytes
        }
        return CurrentDocument(
            document = CloudSnapshotDocument(
                snapshot = data.snapshot,
                images = references.map { it.first },
            ),
            bytesByHash = references.associate { it.first.sha256 to it.second },
        )
    }

    private fun validateDocument(document: CloudSnapshotDocument) {
        if (document.formatVersion != CLOUD_BACKUP_FORMAT_VERSION) {
            throw CloudBackupException("不支持的云端快照格式：${document.formatVersion}")
        }
        try {
            validateSnapshot(document.snapshot)
        } catch (error: BackupFormatException) {
            throw CloudBackupException("云端快照无效：${error.message}", error)
        }
        val expectedPaths = document.snapshot.cards.map { it.valueImagePath }.toSet()
        val actualPaths = document.images.map { it.localPath }
        if (actualPaths.toSet() != expectedPaths || actualPaths.size != expectedPaths.size) {
            throw CloudBackupException("云端快照图片引用不完整")
        }
        document.images.forEach { reference ->
            requireSafeImagePath(reference.localPath)
            requireSha256(reference.sha256)
            if (reference.size <= 0) throw CloudBackupException("云端图片大小无效")
        }
    }

    private fun fingerprints(document: CloudSnapshotDocument): Map<Long, String> {
        val images = document.images.associate { it.localPath to it.sha256 }
        val reviews = document.snapshot.reviews.associateBy { it.cardId }
        return document.snapshot.cards.associate { card ->
            val fingerprint = CloudCardFingerprint(
                deckId = card.deckId,
                keyText = card.keyText,
                archived = card.archived,
                imageSha256 = requireNotNull(images[card.valueImagePath]),
                review = requireNotNull(reviews[card.id]),
            )
            card.id to sha256(json.encodeToString(fingerprint).encodeToByteArray())
        }
    }

    private suspend fun cleanupExpired(
        remote: CloudRemoteStore,
        expired: List<CloudSnapshotSummary>,
        retained: List<CloudSnapshotSummary>,
    ): List<String> {
        if (expired.isEmpty()) return emptyList()
        val warnings = mutableListOf<String>()
        expired.forEach { summary ->
            try {
                remote.delete("snapshots/${summary.id}.json")
            } catch (error: CloudBackupException) {
                warnings += "旧版本清理失败：${summary.id}"
            }
        }
        val retainedHashes = retained.flatMapTo(mutableSetOf()) { it.imageHashes }
        expired.flatMapTo(mutableSetOf()) { it.imageHashes }.filterNot { it in retainedHashes }.forEach { hash ->
            try {
                remote.delete("objects/$hash.png")
            } catch (error: CloudBackupException) {
                warnings += "旧图片清理失败：$hash"
            }
        }
        return warnings
    }

    private inline fun <reified T> decode(bytes: ByteArray, label: String): T = try {
        json.decodeFromString(bytes.decodeToString())
    } catch (error: SerializationException) {
        throw CloudBackupException("$label JSON 无效", error)
    } catch (error: IllegalArgumentException) {
        throw CloudBackupException("$label JSON 无效", error)
    }

    private fun requireDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) error("无法创建云端备份临时目录")
    }

    private data class CurrentDocument(
        val document: CloudSnapshotDocument,
        val bytesByHash: Map<String, ByteArray>,
    )
}

@Serializable
private data class CloudCardFingerprint(
    val deckId: Long,
    val keyText: String,
    val archived: Boolean,
    val imageSha256: String,
    val review: BackupReviewState,
)

private fun requireSafeSnapshotId(value: String) {
    if (!Regex("^[0-9]+-[A-Za-z0-9-]+$").matches(value)) {
        throw CloudBackupException("云端版本 ID 无效")
    }
}

private fun requireSha256(value: String) {
    if (!Regex("^[0-9a-f]{64}$").matches(value)) throw CloudBackupException("云端图片哈希无效")
}

package com.mutsumi.card.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class RepositoryCloudBackupOperations(
    private val repositoryOperations: CloudBackupDataAccess,
    private val temporaryDirectory: File,
    private val baselineFile: File? = null,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val now: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val remoteFactory: (CloudBackupConfig) -> CloudRemoteStore = { config -> WebDavClient(config, client) },
) : CloudBackupOperations {
    suspend fun backup(
        config: CloudBackupConfig,
        pushDelete: Boolean = false,
    ): CloudBackupResult = backup(config, pushDelete, conflictResolution = null, expectedConflicts = null)

    suspend fun previewRestore(
        config: CloudBackupConfig,
        snapshotId: String,
        pullDelete: Boolean = false,
    ): CloudRestorePreview = previewRestore(
        config,
        snapshotId,
        pullDelete,
        conflictResolution = null,
        expectedConflicts = null,
    )

    suspend fun restore(
        config: CloudBackupConfig,
        snapshotId: String,
        pullDelete: Boolean = false,
    ): ImportSummary = restore(
        config,
        snapshotId,
        pullDelete,
        conflictResolution = null,
        expectedConflicts = null,
    )

    override suspend fun inspect(config: CloudBackupConfig): CloudBackupOverview = withContext(Dispatchers.IO) {
        val remote = remoteFactory(config)
        val index = loadIndex(remote)
        val current = repositoryOperations.loadCloudState()
        val previous = index.snapshots.firstOrNull()?.let { loadDocument(remote, it.id) }
        CloudBackupOverview(index.snapshots, stats(previous?.snapshot, current.document.snapshot))
    }

    override suspend fun backup(
        config: CloudBackupConfig,
        pushDelete: Boolean,
        conflictResolution: CloudConflictResolution?,
        expectedConflicts: List<CloudConflict>?,
    ): CloudBackupResult = withContext(Dispatchers.IO) {
        val remote = remoteFactory(config)
        val loadedIndex = loadIndexWithVersion(remote)
        val index = loadedIndex.index
        val current = repositoryOperations.loadCloudState()
        val previous = index.snapshots.firstOrNull()?.let { loadDocument(remote, it.id) }
        val baseline = loadBaseline(config)?.snapshot
        val conflicts = resolveConflicts(
            baseline = baseline,
            local = current.document.snapshot,
            cloud = previous?.snapshot,
            conflictResolution = conflictResolution,
            expectedConflicts = expectedConflicts,
        )
        val effective = CloudConflictResolver.merge(
            baseline = baseline,
            local = current.document.snapshot,
            cloud = previous?.snapshot ?: CloudSnapshot(emptyList(), emptyList(), emptyList()),
            defaultResolution = CloudConflictResolution.KeepLocal,
            deleteExtras = pushDelete,
            conflicts = conflicts,
            conflictResolution = conflictResolution,
        )
        val refs = imageReferencesFor(
            effective,
            mergeImageReferences(previous?.images.orEmpty(), current.document.images)
                .map { it.copy(sourcePath = null) },
        )
        val document = CloudSnapshotDocument(snapshot = effective, images = refs)
        validateDocument(document)

        // 有效快照只要不同于本地，就必须先落到本地。这里不仅包含用户裁决的
        // 冲突，也包含无冲突的云端独立改动；否则推进基线后，下次同步会把旧
        // 本地状态误判为新改动并反向覆盖云端。
        //
        // 合并结果是此轮双方一致的完整状态，因此本地应用时镜像其中的删除，
        // 而不是沿用“推送删除”的远端发布策略。
        if (fingerprints(effective) != fingerprints(current.document.snapshot)) {
            val images = loadImages(remote, document, current.bytesByHash)
            repositoryOperations.applyCloudSnapshot(
                snapshot = effective,
                images = images,
                deleteExtras = true,
            )
        }
        remote.ensureDirectories()
        current.bytesByHash.forEach { (hash, bytes) ->
            val path = "objects/$hash.png"
            val existing = remote.get(path)
            if (existing == null || sha256(existing) != hash) remote.put(path, bytes, "image/png")
        }
        val createdAt = now()
        val snapshotId = "$createdAt-${idGenerator()}"
        remote.put("snapshots/$snapshotId.json", json.encodeToString(document).encodeToByteArray(), "application/json; charset=utf-8")
        val change = stats(previous?.snapshot, effective)
        val summary = CloudSnapshotSummary(
            id = snapshotId,
            createdAt = createdAt,
            addedOrChangedCount = change.addedOrChangedCount,
            deletedCount = change.deletedCount,
            cardCount = change.cardCount,
            deckCount = change.deckCount,
            addedOrChangedDeckCount = change.addedOrChangedDeckCount,
            deletedDeckCount = change.deletedDeckCount,
            imageHashes = document.images.map { it.sha256 }.distinct().sorted(),
        )
        val retained = (listOf(summary) + index.snapshots.filterNot { it.id == snapshotId }).take(CLOUD_BACKUP_WINDOW_SIZE)
        val expired = (listOf(summary) + index.snapshots.filterNot { it.id == snapshotId }).drop(CLOUD_BACKUP_WINDOW_SIZE)
        val indexPublished = remote.putIfUnchanged(
            path = "index.json",
            bytes = json.encodeToString(CloudBackupIndex(snapshots = retained)).encodeToByteArray(),
            contentType = "application/json; charset=utf-8",
            expectedVersion = loadedIndex.version,
        )
        if (!indexPublished) {
            throw CloudBackupException("云端索引已变化，未覆盖远端数据，请重新同步后再试")
        }
        saveBaseline(config, snapshotId, effective)
        CloudBackupResult(CloudBackupOverview(retained, change), true, cleanupExpired(remote, expired, retained))
    }

    override suspend fun previewRestore(
        config: CloudBackupConfig,
        snapshotId: String,
        pullDelete: Boolean,
        conflictResolution: CloudConflictResolution?,
        expectedConflicts: List<CloudConflict>?,
    ): CloudRestorePreview = withContext(Dispatchers.IO) {
        val remote = remoteFactory(config)
        val index = loadIndex(remote)
        require(index.snapshots.any { it.id == snapshotId }) { "云端版本不存在或已超出保留窗口" }
        val remoteDocument = loadDocument(remote, snapshotId)
        val local = repositoryOperations.loadCloudState()
        val baseline = loadBaseline(config)?.snapshot
        val conflicts = resolveConflicts(
            baseline = baseline,
            local = local.document.snapshot,
            cloud = remoteDocument.snapshot,
            conflictResolution = conflictResolution,
            expectedConflicts = expectedConflicts,
        )
        val effective = CloudConflictResolver.merge(
            baseline = baseline,
            local = local.document.snapshot,
            cloud = remoteDocument.snapshot,
            defaultResolution = CloudConflictResolution.UseCloud,
            deleteExtras = pullDelete,
            conflicts = conflicts,
            conflictResolution = conflictResolution,
        )
        val refs = imageReferencesFor(
            effective,
            mergeImageReferences(local.document.images, remoteDocument.images),
        )
        val document = CloudSnapshotDocument(snapshot = effective, images = refs)
        val images = loadImages(remote, document, local.bytesByHash)
        val deckNames = effective.decks.associateBy { it.syncId }
        val cards = effective.cards.map { card ->
            CloudPreviewCard(
                deckName = requireNotNull(deckNames[card.deckSyncId]).name,
                keyText = card.keyText,
                frontPng = card.frontImageSha256?.let(images::getValue),
                backPng = images.getValue(card.valueImageSha256),
            )
        }
        CloudRestorePreview(
            snapshotId = snapshotId,
            createdAt = index.snapshots.first { it.id == snapshotId }.createdAt,
            stats = stats(local.document.snapshot, effective),
            cards = cards,
            deletionWarning = if (pullDelete) "将删除本地不在该云端版本中的卡组和卡片" else null,
        )
    }

    override suspend fun restore(
        config: CloudBackupConfig,
        snapshotId: String,
        pullDelete: Boolean,
        conflictResolution: CloudConflictResolution?,
        expectedConflicts: List<CloudConflict>?,
    ): ImportSummary = withContext(Dispatchers.IO) {
        val remote = remoteFactory(config)
        val index = loadIndex(remote)
        require(index.snapshots.any { it.id == snapshotId }) { "云端版本不存在或已超出保留窗口" }
        val remoteDocument = loadDocument(remote, snapshotId)
        val local = repositoryOperations.loadCloudState()
        val baseline = loadBaseline(config)?.snapshot
        val conflicts = resolveConflicts(
            baseline = baseline,
            local = local.document.snapshot,
            cloud = remoteDocument.snapshot,
            conflictResolution = conflictResolution,
            expectedConflicts = expectedConflicts,
        )
        val effective = CloudConflictResolver.merge(
            baseline = baseline,
            local = local.document.snapshot,
            cloud = remoteDocument.snapshot,
            defaultResolution = CloudConflictResolution.UseCloud,
            deleteExtras = pullDelete,
            conflicts = conflicts,
            conflictResolution = conflictResolution,
        )
        val document = CloudSnapshotDocument(
            snapshot = effective,
            images = imageReferencesFor(
                effective,
                mergeImageReferences(local.document.images, remoteDocument.images),
            ),
        )
        val images = loadImages(remote, document, local.bytesByHash)
        val sourcePaths = document.images.mapNotNull { reference ->
            reference.sourcePath?.let { reference.sha256 to it }
        }.toMap()
        val result = repositoryOperations.applyCloudSnapshot(
            effective,
            images,
            // pullDelete 只参与三方合并时的取舍；effective 已是最终完整状态，
            // 本地必须精确收敛，避免云端删除在推进基线后被下次同步复活。
            deleteExtras = true,
            sourcePaths = sourcePaths,
        )
        saveBaseline(config, snapshotId, effective)
        result
    }

    private suspend fun loadImages(
        remote: CloudRemoteStore,
        document: CloudSnapshotDocument,
        localBytes: Map<String, ByteArray>,
    ): Map<String, ByteArray> {
        val hashes = document.images.mapTo(mutableSetOf()) { it.sha256 }
        return hashes.associateWith { hash ->
            localBytes[hash] ?: remote.get("objects/$hash.png")?.also { bytes ->
                if (sha256(bytes) != hash) throw CloudBackupException("云端图片校验失败：$hash")
            } ?: throw CloudBackupException("云端图片对象缺失：$hash")
        }
    }

    private fun resolveConflicts(
        baseline: CloudSnapshot?,
        local: CloudSnapshot,
        cloud: CloudSnapshot?,
        conflictResolution: CloudConflictResolution?,
        expectedConflicts: List<CloudConflict>?,
    ): List<CloudConflict> {
        val emptySnapshot = CloudSnapshot(emptyList(), emptyList(), emptyList())
        val conflicts = CloudConflictResolver.findConflicts(
            baseline = baseline ?: emptySnapshot,
            local = local,
            cloud = cloud ?: emptySnapshot,
        )
        if (conflicts.isNotEmpty() && (conflictResolution == null || expectedConflicts != null && conflicts != expectedConflicts)) {
            throw CloudConflictException(conflicts)
        }
        return conflicts
    }

    private fun mergeImageReferences(first: List<CloudImageReference>, second: List<CloudImageReference>): List<CloudImageReference> {
        val result = linkedMapOf<String, CloudImageReference>()
        (first + second).forEach { reference ->
            val previous = result[reference.sha256]
            result[reference.sha256] = reference.copy(sourcePath = reference.sourcePath ?: previous?.sourcePath)
        }
        return result.values.toList()
    }

    private fun imageReferencesFor(
        snapshot: CloudSnapshot,
        references: List<CloudImageReference>,
    ): List<CloudImageReference> {
        val hashes = snapshot.cards.flatMapTo(mutableSetOf()) { card ->
            buildList {
                add(card.valueImageSha256)
                card.frontImageSha256?.let(::add)
            }
        }
        return references.filter { it.sha256 in hashes }
    }

    private fun stats(previous: CloudSnapshot?, current: CloudSnapshot): CloudChangeStats {
        val oldDecks = previous?.decks.orEmpty().associateBy { it.syncId }
        val newDecks = current.decks.associateBy { it.syncId }
        val oldCards = previous?.cards.orEmpty().associateBy { it.syncId }
        val newCards = current.cards.associateBy { it.syncId }
        return CloudChangeStats(
            cardCount = newCards.size,
            deckCount = newDecks.size,
            addedOrChangedCount = newCards.count { (id, value) -> oldCards[id] != value },
            deletedCount = oldCards.keys.count { it !in newCards },
            addedOrChangedDeckCount = newDecks.count { (id, value) -> oldDecks[id] != value },
            deletedDeckCount = oldDecks.keys.count { it !in newDecks },
        )
    }

    private fun fingerprints(snapshot: CloudSnapshot): Map<String, String> {
        val result = linkedMapOf<String, String>()
        snapshot.decks.forEach { result["deck:${it.syncId}"] = it.toString() }
        snapshot.cards.forEach { result["card:${it.syncId}"] = it.toString() }
        snapshot.reviews.forEach { result["review:${it.cardSyncId}"] = it.toString() }
        return result
    }

    private suspend fun loadIndex(remote: CloudRemoteStore): CloudBackupIndex =
        remote.get("index.json")?.let(::decodeIndex) ?: CloudBackupIndex()

    private suspend fun loadIndexWithVersion(remote: CloudRemoteStore): CloudIndexWithVersion {
        val remoteIndex = remote.getWithVersion("index.json") ?: return CloudIndexWithVersion(CloudBackupIndex(), null)
        return CloudIndexWithVersion(decodeIndex(remoteIndex.bytes), remoteIndex.version)
    }

    private fun decodeIndex(bytes: ByteArray): CloudBackupIndex {
        val index = decode<CloudBackupIndex>(bytes, "云端索引")
        require(index.formatVersion in 1..CLOUD_BACKUP_FORMAT_VERSION) { "不支持的云端备份格式：${index.formatVersion}" }
        require(index.snapshots.size <= CLOUD_BACKUP_WINDOW_SIZE) { "云端索引版本数量异常" }
        index.snapshots.forEach { summary -> requireSafeSnapshotId(summary.id) }
        return index
    }

    private suspend fun loadDocument(remote: CloudRemoteStore, snapshotId: String): CloudSnapshotDocument {
        val bytes = remote.get("snapshots/$snapshotId.json") ?: throw CloudBackupException("云端快照缺失：$snapshotId")
        val raw = json.decodeFromString<JsonElement>(bytes.decodeToString())
        val version = raw.jsonObject["formatVersion"]?.jsonPrimitive?.intOrNull ?: 1
        val document = if (version == 1) {
            runCatching { decode<CloudSnapshotDocument>(bytes, "云端快照") }
                .getOrElse { convertLegacy(decode<LegacyCloudSnapshotDocument>(bytes, "旧云端快照")) }
        } else {
            decode(bytes, "云端快照")
        }
        validateDocument(document)
        return document
    }

    private fun convertLegacy(legacy: LegacyCloudSnapshotDocument): CloudSnapshotDocument {
        val imageHashes = legacy.images.associate { it.localPath to it.sha256 }
        val decks = legacy.snapshot.decks.map { CloudDeckRecord("legacy-deck-${it.id}", it.name, it.createdAt, it.updatedAt) }
        val cards = legacy.snapshot.cards.map { card ->
            CloudCardRecord(
                syncId = "legacy-card-${card.id}",
                deckSyncId = "legacy-deck-${card.deckId}",
                keyText = card.keyText,
                createdAt = card.createdAt,
                updatedAt = card.updatedAt,
                archived = card.archived,
                valueImageSha256 = requireNotNull(imageHashes[card.valueImagePath]),
                frontImageSha256 = card.frontImagePath?.let { requireNotNull(imageHashes[it]) },
            )
        }
        val reviews = legacy.snapshot.reviews.map { review ->
            CloudReviewRecord("legacy-card-${review.cardId}", review.weight, review.seenCount, review.againCount, review.unsureCount, review.knownCount, review.lastReviewedAt)
        }
        return CloudSnapshotDocument(
            snapshot = CloudSnapshot(decks, cards, reviews),
            images = legacy.images.map { CloudImageReference(it.sha256, it.size, it.localPath) },
        )
    }

    private fun validateDocument(document: CloudSnapshotDocument) {
        require(document.snapshot.decks.map { it.syncId }.toSet().size == document.snapshot.decks.size) { "云端卡组 ID 重复" }
        require(document.snapshot.cards.map { it.syncId }.toSet().size == document.snapshot.cards.size) { "云端卡片 ID 重复" }
        val hashes = document.images.map { it.sha256 }.toSet()
        document.snapshot.cards.forEach { card ->
            require(card.deckSyncId in document.snapshot.decks.map { it.syncId }) { "云端卡片引用了不存在的卡组" }
            require(card.valueImageSha256 in hashes) { "云端缺少卡片图片" }
            require(card.frontImageSha256 == null || card.frontImageSha256 in hashes) { "云端缺少正面图片" }
        }
        document.images.forEach {
            requireSha256(it.sha256)
            require(it.size > 0)
            it.sourcePath?.let(::requireSafeImagePath)
        }
    }

    private suspend fun cleanupExpired(
        remote: CloudRemoteStore,
        expired: List<CloudSnapshotSummary>,
        retained: List<CloudSnapshotSummary>,
    ): List<String> {
        val warnings = mutableListOf<String>()
        expired.forEach { summary ->
            try { remote.delete("snapshots/${summary.id}.json") } catch (error: Exception) { warnings += "旧版本清理失败：${summary.id}" }
        }
        val retainedHashes = retained.flatMapTo(mutableSetOf()) { it.imageHashes }
        expired.flatMapTo(mutableSetOf()) { it.imageHashes }.filterNot { it in retainedHashes }.forEach { hash ->
            try { remote.delete("objects/$hash.png") } catch (error: Exception) { warnings += "旧图片清理失败：$hash" }
        }
        return warnings
    }

    private fun saveBaseline(config: CloudBackupConfig, snapshotId: String, snapshot: CloudSnapshot) {
        val file = baselineFile ?: return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.pending")
        temp.writeText(json.encodeToString(CloudBaseline(configKey(config), snapshotId, snapshot)))
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun loadBaseline(config: CloudBackupConfig): CloudBaseline? {
        val file = baselineFile ?: return null
        if (!file.isFile) return null
        return decode<CloudBaseline>(file.readBytes(), "云同步基线").takeIf { it.configKey == configKey(config) }
    }

    private fun configKey(config: CloudBackupConfig): String = sha256(
        "${config.serverUrl}|${config.username}|${config.remoteDirectory}".encodeToByteArray(),
    )

    private inline fun <reified T> decode(bytes: ByteArray, label: String): T = try {
        json.decodeFromString(bytes.decodeToString())
    } catch (error: SerializationException) {
        throw CloudBackupException("$label JSON 无效", error)
    } catch (error: IllegalArgumentException) {
        throw CloudBackupException("$label JSON 无效", error)
    }
}

@Serializable
private data class CloudBaseline(val configKey: String, val snapshotId: String, val snapshot: CloudSnapshot)

private data class CloudIndexWithVersion(val index: CloudBackupIndex, val version: String?)

@Serializable
private data class LegacyCloudSnapshotDocument(
    val formatVersion: Int = 1,
    val snapshot: BackupSnapshot,
    val images: List<LegacyCloudImageReference>,
)

@Serializable
private data class LegacyCloudImageReference(val localPath: String, val sha256: String, val size: Long)

private fun requireSafeSnapshotId(value: String) {
    require(Regex("^[0-9]+-[A-Za-z0-9-]+$").matches(value)) { "云端版本 ID 无效" }
}

private fun requireSha256(value: String) {
    require(Regex("^[0-9a-f]{64}$").matches(value)) { "云端图片哈希无效" }
}

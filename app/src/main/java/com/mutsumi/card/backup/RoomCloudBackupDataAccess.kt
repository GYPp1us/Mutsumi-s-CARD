package com.mutsumi.card.backup

import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.data.local.CardDao
import com.mutsumi.card.data.local.CardEntity
import com.mutsumi.card.data.local.DeckEntity
import com.mutsumi.card.data.local.ReviewStateEntity
import com.mutsumi.card.data.local.SyncApplyRows
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class RoomCloudBackupDataAccess(
    private val dao: CardDao,
    private val imageStore: CardImageStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val indexFile: File? = null,
) : CloudBackupDataAccess, CloudSyncDataAccess {
    private val indexMutex = Mutex()
    private val json = Json { encodeDefaults = true }
    private var cached: LocalLibraryCache? = null

    override suspend fun readMetadata(): CloudMetadataState = indexMutex.withLock {
        val revision = dao.getLibraryRevision()
        val previous = cached ?: indexFile?.takeIf { it.isFile }?.let {
            try { json.decodeFromString<LocalLibraryCache>(it.readText()) }
            catch (error: kotlinx.serialization.SerializationException) {
                android.util.Log.w("MutsumiCard", "派生索引缓存损坏，重新生成", error)
                null
            }
        }
        if (previous?.revision == revision) {
            cached = previous
            return@withLock CloudMetadataState(previous.document, revision.toString())
        }
        val rows = dao.getLibraryRows()
        val pathRefs = linkedMapOf<String, CloudImageReference>()
        suspend fun reference(path: String): CloudImageReference = pathRefs[path] ?: (
            previous?.paths?.get(path) ?: imageStore.read(path).let { CloudImageReference(sha256(it), it.size.toLong(), path) }
        ).also { pathRefs[path] = it }
        val deckIds = rows.decks.associate { it.id to it.syncId }
        val cardSyncIds = rows.cards.associate { it.id to it.syncId }
        val document = CloudSnapshotDocument(snapshot = CloudSnapshot(
            decks = rows.decks.map { CloudDeckRecord(it.syncId, it.name, it.createdAt, it.updatedAt,
                it.parentId?.let(deckIds::getValue), it.archived) },
            cards = rows.cards.map { CloudCardRecord(it.syncId, deckIds.getValue(it.deckId), it.keyText, it.createdAt,
                it.updatedAt, it.archived, reference(it.valueImagePath).sha256, it.frontImagePath?.let { path -> reference(path).sha256 }) },
            reviews = rows.reviews.map { review ->
                CloudReviewRecord(cardSyncIds.getValue(review.cardId), review.weight, review.seenCount, review.againCount, review.unsureCount, review.knownCount, review.lastReviewedAt)
            },
        ), images = pathRefs.values.distinctBy { it.sha256 })
        CloudSyncPlanner.validate(document)
        val next = LocalLibraryCache(rows.revision, document, pathRefs)
        indexFile?.let { target ->
            check(target.parentFile?.let { it.isDirectory || it.mkdirs() } != false)
            val pending = File(target.parentFile, ".${target.name}.pending")
            FileOutputStream(pending).use { output -> output.write(json.encodeToString(next).encodeToByteArray()); output.fd.sync() }
            atomicReplace(pending, target)
        }
        cached = next
        CloudMetadataState(document, rows.revision.toString())
    }

    override suspend fun readImages(hashes: Set<String>): Map<String, ByteArray> {
        val metadata = readMetadata()
        val refs = metadata.document.images.associateBy { it.sha256 }
        return hashes.associateWith { hash ->
            val path = refs[hash]?.sourcePath ?: throw CloudBackupException("本地图片引用已变化，请重新预览")
            try { imageStore.read(path) }
            catch (error: java.io.IOException) { throw CloudBackupException("本地卡面读取失败，未执行同步", error) }
            catch (error: IllegalArgumentException) { throw CloudBackupException("本地卡面已变化或缺失，请重新预览", error) }
        }
    }

    override suspend fun applyPlan(document: CloudSnapshotDocument, images: Map<String, ByteArray>, expectedRevision: String, deleteExtras: Boolean): ImportSummary =
        applySnapshot(document.snapshot, images, deleteExtras, expectedRevision.toLong())
    override suspend fun loadExportData(): BackupExportData {
        val rows = dao.getLibraryRows()
        return BackupExportData(BackupSnapshot(
            rows.decks.map { BackupDeck(it.id, it.name, it.createdAt, it.updatedAt, it.parentId, it.archived) },
            rows.cards.map { BackupCard(it.id, it.deckId, it.keyText, it.valueImagePath, it.createdAt, it.updatedAt, it.archived, it.frontImagePath) },
            rows.reviews.map { BackupReviewState(it.cardId, it.weight, it.seenCount, it.againCount, it.unsureCount, it.knownCount, it.lastReviewedAt) },
        ), rows.cards.flatMap { listOfNotNull(it.valueImagePath, it.frontImagePath) }.associateWith(imageStore::resolve))
    }

    override suspend fun importBatch(batch: ImportBatch): ImportSummary {
        val decks = batch.snapshot.decks.associate { it.id to java.util.UUID.randomUUID().toString() }
        val cards = batch.snapshot.cards.associate { it.id to java.util.UUID.randomUUID().toString() }
        val images = batch.images.values.associate { file -> file.readBytes().let { sha256(it) to it } }
        val hashes = batch.images.mapValues { sha256(it.value.readBytes()) }
        val snapshot = CloudSnapshot(
            batch.snapshot.decks.map { CloudDeckRecord(decks.getValue(it.id), it.name, it.createdAt, it.updatedAt, it.parentId?.let(decks::getValue), it.archived) },
            batch.snapshot.cards.map { CloudCardRecord(cards.getValue(it.id), decks.getValue(it.deckId), it.keyText, it.createdAt, it.updatedAt, it.archived,
                hashes.getValue(it.valueImagePath), it.frontImagePath?.let(hashes::getValue)) },
            batch.snapshot.reviews.map { CloudReviewRecord(cards.getValue(it.cardId), it.weight, it.seenCount, it.againCount, it.unsureCount, it.knownCount, it.lastReviewedAt) },
        )
        return applySnapshot(snapshot, images, false, null)
    }

    override suspend fun loadCloudState(): CloudLocalState {
        val metadata = readMetadata()
        return CloudLocalState(metadata.document, readImages(metadata.document.images.mapTo(hashSetOf()) { it.sha256 }))
    }

    override suspend fun applyCloudSnapshot(
        snapshot: CloudSnapshot,
        images: Map<String, ByteArray>,
        deleteExtras: Boolean,
        sourcePaths: Map<String, String>,
    ): ImportSummary = applySnapshot(snapshot, images, deleteExtras, null)

    private suspend fun applySnapshot(snapshot: CloudSnapshot, images: Map<String, ByteArray>, deleteExtras: Boolean, expectedRevision: Long?): ImportSummary {
        require(snapshot.decks.map { it.syncId }.toSet().size == snapshot.decks.size) { "云端包含重复卡组 ID" }
        require(snapshot.cards.map { it.syncId }.toSet().size == snapshot.cards.size) { "云端包含重复卡片 ID" }
        val referencedHashes = snapshot.cards.flatMap { card ->
            buildList { add(card.valueImageSha256); card.frontImageSha256?.let(::add) }
        }.toSet()
        require(referencedHashes == images.keys) { "云端图片引用不完整" }

        val local = dao.getLibraryRows()
        if (expectedRevision != null && expectedRevision != local.revision) throw CloudBackupException("本地数据已变化，请重新预览")
        val localDecks = local.decks.associateBy { it.syncId }
        val localCards = local.cards.associateBy { it.syncId }
        val remoteDeckIds = snapshot.decks.mapTo(mutableSetOf()) { it.syncId }
        val remoteCardIds = snapshot.cards.mapTo(mutableSetOf()) { it.syncId }
        val deckRows = linkedMapOf<String, DeckEntity>()
        var nextDeckId = (localDecks.values.maxOfOrNull { it.id } ?: 0L) + 1L
        snapshot.decks.forEach { remote ->
            val current = localDecks[remote.syncId]
            deckRows[remote.syncId] = current?.copy(
                name = remote.name,
                createdAt = remote.createdAt,
                updatedAt = remote.updatedAt,
                archived = remote.archived,
            ) ?: DeckEntity(
                id = nextDeckId++,
                syncId = remote.syncId,
                name = remote.name,
                createdAt = remote.createdAt,
                updatedAt = remote.updatedAt,
                archived = remote.archived,
            )
        }
        snapshot.decks.forEach { remote ->
            val row = deckRows.getValue(remote.syncId)
            deckRows[remote.syncId] = row.copy(parentId = remote.parentSyncId?.let { deckRows.getValue(it).id })
        }

        val insertedCards = mutableListOf<CardEntity>()
        val updatedCards = mutableListOf<CardEntity>()
        val localIdBySyncId = mutableMapOf<String, Long>()
        val oldImages = mutableListOf<String>()
        val createdImages = mutableListOf<String>()
        var nextCardId = (localCards.values.maxOfOrNull { it.id } ?: 0L) + 1L
        var committed = false
        try {
            snapshot.cards.forEach { remote ->
                val deck = requireNotNull(deckRows[remote.deckSyncId]) { "卡片引用的卡组不存在" }
                val current = localCards[remote.syncId]
                val valuePath = resolveImagePath(
                    current?.valueImagePath,
                    current?.let { cached?.paths?.get(it.valueImagePath)?.sha256 ?: sha256(imageStore.read(it.valueImagePath)) },
                    remote.valueImageSha256,
                    images,
                    oldImages,
                    createdImages,
                )
                val frontPath = remote.frontImageSha256?.let { hash ->
                    resolveImagePath(
                        current?.frontImagePath,
                        current?.frontImagePath?.let { cached?.paths?.get(it)?.sha256 ?: sha256(imageStore.read(it)) },
                        hash,
                        images,
                        oldImages,
                        createdImages,
                    )
                } ?: run { current?.frontImagePath?.let(oldImages::add); null }
                val row = (current ?: CardEntity(
                    id = nextCardId++,
                    syncId = remote.syncId,
                    deckId = deck.id,
                    keyText = remote.keyText,
                    valueImagePath = valuePath,
                    createdAt = remote.createdAt,
                    updatedAt = remote.updatedAt,
                    archived = remote.archived,
                    frontImagePath = frontPath,
                )).copy(
                    deckId = deck.id,
                    keyText = remote.keyText,
                    valueImagePath = valuePath,
                    createdAt = remote.createdAt,
                    updatedAt = remote.updatedAt,
                    archived = remote.archived,
                    frontImagePath = frontPath,
                )
                localIdBySyncId[remote.syncId] = row.id
                if (current == null) insertedCards += row else updatedCards += row
            }
            val reviewsByCard = snapshot.reviews.associateBy { it.cardSyncId }
            val reviewRows = snapshot.cards.map { card ->
                val review = requireNotNull(reviewsByCard[card.syncId]) { "云端缺少卡片复习状态" }
                ReviewStateEntity(
                    cardId = localIdBySyncId.getValue(card.syncId),
                    weight = review.weight,
                    seenCount = review.seenCount,
                    againCount = review.againCount,
                    unsureCount = review.unsureCount,
                    knownCount = review.knownCount,
                    lastReviewedAt = review.lastReviewedAt,
                )
            }
            val cardsToDelete = if (deleteExtras) localCards.values.filter { it.syncId !in remoteCardIds } else emptyList()
            val decksToDelete = if (deleteExtras) localDecks.values.filter { it.syncId !in remoteDeckIds } else emptyList()
            dao.applySyncRows(
                SyncApplyRows(
                    decksToInsert = deckRows.values.filter { localDecks[it.syncId] == null },
                    decksToUpdate = deckRows.values.filter { localDecks[it.syncId] != null },
                    cardsToInsert = insertedCards,
                    cardsToUpdate = updatedCards,
                    reviews = reviewRows,
                    cardsToDelete = cardsToDelete,
                    decksToDelete = decksToDelete,
                    oldImagesToDelete = oldImages.distinct(),
                    queuedAt = now(),
                    expectedRevision = expectedRevision ?: local.revision,
                ),
            )
            committed = true
            return ImportSummary(snapshot.decks.size, snapshot.cards.size)
        } catch (error: Exception) {
            if (!committed) {
                createdImages.forEach { path ->
                    try {
                        imageStore.delete(path)
                    } catch (cleanupError: Exception) {
                        error.addSuppressed(cleanupError)
                    }
                }
            }
            throw error
        }
    }

    private suspend fun resolveImagePath(
        currentPath: String?,
        currentHash: String?,
        expectedHash: String,
        images: Map<String, ByteArray>,
        oldImages: MutableList<String>,
        createdImages: MutableList<String>,
    ): String {
        if (currentPath != null && currentHash == expectedHash) return currentPath
        currentPath?.let(oldImages::add)
        return imageStore.writePng(requireNotNull(images[expectedHash]) { "缺少图片对象：$expectedHash" })
            .also(createdImages::add)
    }
}

@Serializable
private data class LocalLibraryCache(val revision: Long, val document: CloudSnapshotDocument, val paths: Map<String, CloudImageReference>)

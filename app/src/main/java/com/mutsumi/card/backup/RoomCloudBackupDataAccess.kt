package com.mutsumi.card.backup

import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.data.local.CardDao
import com.mutsumi.card.data.local.CardEntity
import com.mutsumi.card.data.local.DeckEntity
import com.mutsumi.card.data.local.ReviewStateEntity
import com.mutsumi.card.data.local.SyncApplyRows

class RoomCloudBackupDataAccess(
    private val dao: CardDao,
    private val imageStore: CardImageStore,
    private val now: () -> Long = System::currentTimeMillis,
) : CloudBackupDataAccess {
    override suspend fun loadExportData(): BackupExportData = error("云同步数据访问层不负责本地 ZIP 导出")

    override suspend fun importBatch(batch: ImportBatch): ImportSummary = error("云同步数据访问层不负责本地 ZIP 导入")

    override suspend fun loadCloudState(): CloudLocalState {
        dao.ensureSyncIds()
        val decks = dao.getAllDecks()
        val cards = dao.getAllCards()
        val reviews = dao.getAllReviewStates().associateBy { it.cardId }
        val bytes = linkedMapOf<String, ByteArray>()
        val deckById = decks.associateBy { it.id }
        val cloudCards = cards.map { card ->
            val valueBytes = imageStore.read(card.valueImagePath)
            val valueHash = sha256(valueBytes).also { bytes.putIfAbsent(it, valueBytes) }
            val frontHash = card.frontImagePath?.let { path ->
                val frontBytes = imageStore.read(path)
                sha256(frontBytes).also { bytes.putIfAbsent(it, frontBytes) }
            }
            CloudCardRecord(
                syncId = card.syncId,
                deckSyncId = requireNotNull(deckById[card.deckId]).syncId,
                keyText = card.keyText,
                createdAt = card.createdAt,
                updatedAt = card.updatedAt,
                archived = card.archived,
                valueImageSha256 = valueHash,
                frontImageSha256 = frontHash,
            )
        }
        val cloudReviews = cards.map { card ->
            val review = requireNotNull(reviews[card.id]) { "卡片 ${card.id} 缺少复习状态" }
            CloudReviewRecord(
                cardSyncId = card.syncId,
                weight = review.weight,
                seenCount = review.seenCount,
                againCount = review.againCount,
                unsureCount = review.unsureCount,
                knownCount = review.knownCount,
                lastReviewedAt = review.lastReviewedAt,
            )
        }
        val snapshot = CloudSnapshot(
            decks = decks.map { CloudDeckRecord(it.syncId, it.name, it.createdAt, it.updatedAt) },
            cards = cloudCards,
            reviews = cloudReviews,
        )
        return CloudLocalState(
            document = CloudSnapshotDocument(
                snapshot = snapshot,
                images = bytes.map { (hash, data) -> CloudImageReference(hash, data.size.toLong()) },
            ),
            bytesByHash = bytes,
        )
    }

    override suspend fun applyCloudSnapshot(
        snapshot: CloudSnapshot,
        images: Map<String, ByteArray>,
        deleteExtras: Boolean,
        sourcePaths: Map<String, String>,
    ): ImportSummary {
        require(snapshot.decks.map { it.syncId }.toSet().size == snapshot.decks.size) { "云端包含重复卡组 ID" }
        require(snapshot.cards.map { it.syncId }.toSet().size == snapshot.cards.size) { "云端包含重复卡片 ID" }
        val referencedHashes = snapshot.cards.flatMap { card ->
            buildList { add(card.valueImageSha256); card.frontImageSha256?.let(::add) }
        }.toSet()
        require(referencedHashes == images.keys) { "云端图片引用不完整" }

        val localDecks = dao.getAllDecks().associateBy { it.syncId }
        val localCards = dao.getAllCards().associateBy { it.syncId }
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
            ) ?: DeckEntity(
                id = nextDeckId++,
                syncId = remote.syncId,
                name = remote.name,
                createdAt = remote.createdAt,
                updatedAt = remote.updatedAt,
            )
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
                    current?.let { imageStore.read(it.valueImagePath) },
                    remote.valueImageSha256,
                    images,
                    oldImages,
                    createdImages,
                )
                val frontPath = remote.frontImageSha256?.let { hash ->
                    resolveImagePath(
                        current?.frontImagePath,
                        current?.frontImagePath?.let { imageStore.read(it) },
                        hash,
                        images,
                        oldImages,
                        createdImages,
                    )
                } ?: current?.frontImagePath?.also(oldImages::add)
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
        currentBytes: ByteArray?,
        expectedHash: String,
        images: Map<String, ByteArray>,
        oldImages: MutableList<String>,
        createdImages: MutableList<String>,
    ): String {
        if (currentPath != null && currentBytes != null && sha256(currentBytes) == expectedHash) return currentPath
        currentPath?.let(oldImages::add)
        return imageStore.writePng(requireNotNull(images[expectedHash]) { "缺少图片对象：$expectedHash" })
            .also(createdImages::add)
    }
}

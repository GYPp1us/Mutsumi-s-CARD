package com.mutsumi.card.data

import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.data.image.ValuePngValidator
import com.mutsumi.card.data.local.CardDao
import com.mutsumi.card.data.local.CardEntity
import com.mutsumi.card.data.local.CardWithReviewState
import com.mutsumi.card.data.local.DeckEntity
import com.mutsumi.card.domain.model.Deck
import com.mutsumi.card.domain.model.MemoryCard
import com.mutsumi.card.domain.model.ReviewState
import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.review.WeightedCardPicker
import com.mutsumi.card.domain.review.WeightedReviewCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import com.mutsumi.card.cards.LibraryTree
import com.mutsumi.card.data.local.SyncApplyRows

class RoomCardRepository(
    private val dao: CardDao,
    val imageStore: CardImageStore,
    private val picker: WeightedCardPicker = WeightedCardPicker(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pngValidator: ValuePngValidator = ValuePngValidator(),
) : CardRepository {
    override val libraryDecks: Flow<List<Deck>> = dao.observeDecksWithCardCount().map { rows ->
        rows.map { row ->
            Deck(
                id = row.deck.id,
                name = row.deck.name,
                cardCount = row.cardCount,
                parentId = row.deck.parentId,
                archived = row.deck.archived,
            )
        }
    }

    override val decks: Flow<List<Deck>> = libraryDecks.map { rows ->
        rows.filter { row -> LibraryTree.breadcrumbs(rows, row.id).none { it.archived } }
    }

    override fun managementCards(deckId: Long, query: String, includeArchived: Boolean): Flow<List<MemoryCard>> =
        dao.observeManagementCards(deckId, query.trim(), includeArchived).map { it.map(::toDomain) }

    override suspend fun createChildDeck(name: String, parentId: Long?): Long {
        if (parentId != null) requireNotNull(dao.getDeck(parentId)) { "父卡组不存在" }
        val timestamp = now()
        return dao.insertDeck(DeckEntity(name = requireText(name, "卡组名称不能为空"), createdAt = timestamp, updatedAt = timestamp, parentId = parentId))
    }

    override suspend fun managementImpact(selection: LibrarySelection): LibraryImpact {
        val rows = dao.getLibraryRows()
        val decks = rows.decks.map { Deck(it.id, it.name, 0, it.parentId, it.archived) }
        val subtree = LibraryTree.descendants(decks, selection.deckIds)
        val cards = rows.cards.filter { it.id in selection.cardIds || it.deckId in subtree }
        return LibraryImpact(cards.size, subtree.size, cards.count { it.archived }, rows.revision)
    }

    override suspend fun manage(selection: LibrarySelection, action: LibraryAction, targetDeckId: Long?, expectedRevision: Long?): Int {
        val rows = dao.getLibraryRows()
        if (expectedRevision != null && rows.revision != expectedRevision) throw IOException("删除预览后数据已变化，请重新预览")
        val decks = rows.decks.map { Deck(it.id, it.name, 0, it.parentId, it.archived) }
        require(selection.deckIds.all { id -> rows.decks.any { it.id == id } } && selection.cardIds.all { id -> rows.cards.any { it.id == id } }) { "选择的项目已变化，请刷新" }
        val subtree = LibraryTree.descendants(decks, selection.deckIds)
        val roots = LibraryTree.roots(decks, selection.deckIds)
        val selectedCards = rows.cards.filter { it.id in selection.cardIds || it.deckId in subtree }
        if (action == LibraryAction.Move || action == LibraryAction.Copy) {
            LibraryTree.requireMove(decks, selection.deckIds, targetDeckId)
            require(targetDeckId != null || selection.cardIds.isEmpty()) { "卡片需要移入卡组，不能放在全部卡组根目录" }
        }
        val stamp = now()
        val newImages = mutableListOf<String>()
        var committed = false
        try {
            var deckId = (rows.decks.maxOfOrNull { it.id } ?: 0) + 1
            var cardId = (rows.cards.maxOfOrNull { it.id } ?: 0) + 1
            val deckMap = subtree.associateWith { deckId++ }
            val copiedCards = if (action == LibraryAction.Copy) selectedCards.map { card ->
                suspend fun copyImage(path: String) = imageStore.writePng(imageStore.read(path)).also(newImages::add)
                card.copy(id = cardId++, syncId = UUID.randomUUID().toString(),
                    deckId = if (card.deckId in subtree) deckMap.getValue(card.deckId) else requireNotNull(targetDeckId),
                    valueImagePath = copyImage(card.valueImagePath), frontImagePath = card.frontImagePath?.let { copyImage(it) },
                    createdAt = stamp, updatedAt = stamp)
            } else emptyList()
            val copiedDecks = if (action == LibraryAction.Copy) rows.decks.filter { it.id in subtree }.map { deck ->
                deck.copy(id = deckMap.getValue(deck.id), syncId = UUID.randomUUID().toString(),
                    name = if (deck.id in roots) "${deck.name}（副本）" else deck.name,
                    parentId = if (deck.id in roots) targetDeckId else deck.parentId?.let(deckMap::getValue), createdAt = stamp, updatedAt = stamp)
            } else emptyList()
            val reviews = rows.reviews.associateBy { it.cardId }
            val copiedReviews = copiedCards.zip(selectedCards).map { (new, old) -> reviews.getValue(old.id).copy(cardId = new.id) }
            val updatedDecks = rows.decks.filter { it.id in roots }.mapNotNull { deck -> when (action) {
                LibraryAction.Move -> deck.copy(parentId = targetDeckId, updatedAt = stamp)
                LibraryAction.Archive -> deck.copy(archived = true, updatedAt = stamp)
                LibraryAction.Restore -> deck.copy(archived = false, updatedAt = stamp)
                else -> null
            } }
            val updatedCards = rows.cards.filter { it.id in selection.cardIds && it.deckId !in subtree }.mapNotNull { card -> when (action) {
                LibraryAction.Move -> card.copy(deckId = requireNotNull(targetDeckId), updatedAt = stamp)
                LibraryAction.Archive -> card.copy(archived = true, updatedAt = stamp)
                LibraryAction.Restore -> card.copy(archived = false, updatedAt = stamp)
                else -> null
            } }
            dao.applySyncRows(SyncApplyRows(copiedDecks, updatedDecks, copiedCards, updatedCards, copiedReviews,
                if (action == LibraryAction.Delete) selectedCards else emptyList(),
                if (action == LibraryAction.Delete) rows.decks.filter { it.id in subtree } else emptyList(), emptyList(), stamp, rows.revision))
            committed = true
            if (action == LibraryAction.Delete) retryPendingImageCleanup()
            return selectedCards.size + subtree.size
        } catch (error: Exception) {
            if (!committed) newImages.forEach { deleteNewImageAfterFailure(it, error) }
            throw error
        }
    }

    override fun cards(deckId: Long, query: String): Flow<List<MemoryCard>> {
        val normalizedQuery = query.trim()
        val source = if (normalizedQuery.isEmpty()) {
            dao.observeActiveCardsWithReview(deckId)
        } else {
            dao.observeActiveCardsWithReview(deckId, normalizedQuery)
        }
        return source.map { cards -> cards.map(::toDomain) }
    }

    override suspend fun ensureDefaultDeck(): Long {
        dao.ensureSyncIds()
        return dao.ensureDefaultDeck(now()).also { dao.ensureSyncIds() }
    }

    override suspend fun createDeck(name: String): Long {
        val normalized = requireText(name, "卡组名称不能为空")
        val timestamp = now()
        return dao.insertDeck(
            DeckEntity(name = normalized, createdAt = timestamp, updatedAt = timestamp, syncId = UUID.randomUUID().toString()),
        )
    }

    override suspend fun renameDeck(deckId: Long, name: String) {
        val normalized = requireText(name, "卡组名称不能为空")
        val deck = dao.getDeck(deckId) ?: throw IllegalArgumentException("卡组不存在：$deckId")
        dao.updateDeck(deck.copy(name = normalized, updatedAt = now()))
    }

    override suspend fun saveCard(deckId: Long, keyText: String, png: ByteArray): Long {
        return saveCard(deckId, keyText, frontPng = null, backPng = png)
    }

    override suspend fun saveCard(
        deckId: Long,
        keyText: String,
        frontPng: ByteArray?,
        backPng: ByteArray,
    ): Long {
        val normalizedKey = requireText(keyText, "key 不能为空")
        frontPng?.let(pngValidator::validate)
        pngValidator.validate(backPng)
        requireNotNull(dao.getDeck(deckId)) { "卡组不存在：$deckId" }
        val frontPath = frontPng?.let { imageStore.writePng(it) }
        val backPath = try {
            imageStore.writePng(backPng)
        } catch (error: Exception) {
            frontPath?.let { deleteNewImageAfterFailure(it, error) }
            throw error
        }
        val timestamp = now()
        return withContext(NonCancellable) {
            try {
                dao.insertCardWithReview(
                    CardEntity(
                        deckId = deckId,
                        syncId = UUID.randomUUID().toString(),
                        keyText = normalizedKey,
                        frontImagePath = frontPath,
                        valueImagePath = backPath,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    ),
                )
            } catch (error: Exception) {
                deleteNewImageAfterFailure(backPath, error)
                frontPath?.let { deleteNewImageAfterFailure(it, error) }
                throw error
            }
        }
    }

    override suspend fun updateCard(cardId: Long, keyText: String, png: ByteArray?) {
        val normalizedKey = requireText(keyText, "key 不能为空")
        png?.let(pngValidator::validate)
        val current = dao.getCard(cardId) ?: throw IllegalArgumentException("卡片不存在：$cardId")
        val newPath = png?.let { imageStore.writePng(it) }
        withContext(NonCancellable) {
            try {
                val updated = current.copy(
                    keyText = normalizedKey,
                    valueImagePath = newPath ?: current.valueImagePath,
                    updatedAt = now(),
                )
                if (newPath == null) {
                    dao.updateCard(updated)
                } else {
                    dao.updateCardAndQueueImageDeletion(updated, current.valueImagePath, now())
                }
            } catch (error: Exception) {
                newPath?.let { deleteNewImageAfterFailure(it, error) }
                throw error
            }
        }
        if (newPath != null) retryPendingImageCleanup()
    }

    override suspend fun archiveCard(cardId: Long) {
        val card = dao.getCard(cardId) ?: throw IllegalArgumentException("卡片不存在：$cardId")
        dao.updateCard(card.copy(archived = true, updatedAt = now()))
    }

    override suspend fun deleteCard(cardId: Long) {
        val card = dao.getCard(cardId) ?: throw IllegalArgumentException("卡片不存在：$cardId")
        dao.deleteCardAndQueueImageDeletion(card, now())
        retryPendingImageCleanup()
    }

    override suspend fun applyFeedback(cardId: Long, feedback: ReviewFeedback, now: Long) {
        dao.updateFeedback(cardId, feedback, now)
    }

    override suspend fun pickRecommendedCard(
        deckId: Long,
        recentCardIds: List<Long>,
    ): MemoryCard? {
        val cards = cards(deckId).first()
        val pickedId = picker.pick(
            cards = cards.map { WeightedReviewCard(it.id, it.review.weight) },
            recentCardIds = recentCardIds,
        )?.cardId ?: return null
        return cards.first { it.id == pickedId }
    }

    override suspend fun retryPendingImageCleanup() {
        withContext(NonCancellable + Dispatchers.IO) {
            dao.getPendingImageDeletions().forEach { deletion ->
                try {
                    imageStore.delete(deletion.path)
                    dao.removePendingImageDeletion(deletion.path)
                } catch (_: IOException) {
                    // The durable queue keeps recoverable I/O failures for the next startup retry.
                }
            }
        }
    }

    private suspend fun deleteNewImageAfterFailure(path: String, original: Exception) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                imageStore.delete(path)
            } catch (cleanupError: Exception) {
                original.addSuppressed(cleanupError)
            }
        }
    }

    private fun toDomain(row: CardWithReviewState): MemoryCard = MemoryCard(
        id = row.card.id,
        deckId = row.card.deckId,
        keyText = row.card.keyText,
        frontImagePath = row.card.frontImagePath,
        valueImagePath = row.card.valueImagePath,
        archived = row.card.archived,
        review = ReviewState(
            weight = row.reviewState.weight,
            seenCount = row.reviewState.seenCount,
            againCount = row.reviewState.againCount,
            unsureCount = row.reviewState.unsureCount,
            knownCount = row.reviewState.knownCount,
            lastReviewedAt = row.reviewState.lastReviewedAt,
        ),
    )

    private fun requireText(value: String, message: String): String =
        value.trim().also { require(it.isNotEmpty()) { message } }
}

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

class RoomCardRepository(
    private val dao: CardDao,
    val imageStore: CardImageStore,
    private val picker: WeightedCardPicker = WeightedCardPicker(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pngValidator: ValuePngValidator = ValuePngValidator(),
) : CardRepository {
    override val decks: Flow<List<Deck>> = dao.observeDecksWithCardCount().map { rows ->
        rows.map { row ->
            Deck(
                id = row.deck.id,
                name = row.deck.name,
                cardCount = row.cardCount,
            )
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

    override suspend fun ensureDefaultDeck(): Long = dao.ensureDefaultDeck(now())

    override suspend fun createDeck(name: String): Long {
        val normalized = requireText(name, "卡组名称不能为空")
        val timestamp = now()
        return dao.insertDeck(
            DeckEntity(name = normalized, createdAt = timestamp, updatedAt = timestamp),
        )
    }

    override suspend fun renameDeck(deckId: Long, name: String) {
        val normalized = requireText(name, "卡组名称不能为空")
        val deck = dao.getDeck(deckId) ?: throw IllegalArgumentException("卡组不存在：$deckId")
        dao.updateDeck(deck.copy(name = normalized, updatedAt = now()))
    }

    override suspend fun saveCard(deckId: Long, keyText: String, png: ByteArray): Long {
        val normalizedKey = requireText(keyText, "key 不能为空")
        pngValidator.validate(png)
        requireNotNull(dao.getDeck(deckId)) { "卡组不存在：$deckId" }
        val path = imageStore.writePng(png)
        val timestamp = now()
        return withContext(NonCancellable) {
            try {
                dao.insertCardWithReview(
                    CardEntity(
                        deckId = deckId,
                        keyText = normalizedKey,
                        valueImagePath = path,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    ),
                )
            } catch (error: Exception) {
                deleteNewImageAfterFailure(path, error)
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

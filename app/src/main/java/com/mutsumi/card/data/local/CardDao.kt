package com.mutsumi.card.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.review.ReviewWeightPolicy
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CardDao {
    @Insert
    abstract suspend fun insertDeck(deck: DeckEntity): Long

    @Insert
    protected abstract suspend fun insertCard(card: CardEntity): Long

    @Insert
    protected abstract suspend fun insertReviewState(reviewState: ReviewStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPendingImageDeletion(
        deletion: PendingImageDeletionEntity,
    ): Long

    @Update
    protected abstract suspend fun updateDeckRow(deck: DeckEntity): Int

    @Update
    protected abstract suspend fun updateCardRow(card: CardEntity): Int

    @Update
    protected abstract suspend fun updateReviewStateRow(reviewState: ReviewStateEntity): Int

    @Delete
    protected abstract suspend fun deleteDeckRow(deck: DeckEntity): Int

    @Delete
    protected abstract suspend fun deleteCardRow(card: CardEntity): Int

    @Query("SELECT * FROM decks ORDER BY createdAt ASC, id ASC")
    abstract fun observeDecks(): Flow<List<DeckEntity>>

    @Query(
        """
        SELECT decks.*, COUNT(cards.id) AS cardCount
        FROM decks
        LEFT JOIN cards ON cards.deckId = decks.id AND cards.archived = 0
        GROUP BY decks.id
        ORDER BY decks.createdAt ASC, decks.id ASC
        """,
    )
    abstract fun observeDecksWithCardCount(): Flow<List<DeckWithCardCount>>

    @Transaction
    @Query("SELECT * FROM cards WHERE deckId = :deckId AND archived = 0 ORDER BY updatedAt DESC, id DESC")
    abstract fun observeActiveCardsWithReview(deckId: Long): Flow<List<CardWithReviewState>>

    @Transaction
    @Query(
        """
        SELECT * FROM cards
        WHERE deckId = :deckId AND archived = 0 AND instr(keyText, :query) > 0
        ORDER BY updatedAt DESC, id DESC
        """,
    )
    abstract fun observeActiveCardsWithReview(
        deckId: Long,
        query: String,
    ): Flow<List<CardWithReviewState>>

    @Query("SELECT * FROM cards WHERE archived = 0 AND instr(keyText, :key) > 0 ORDER BY updatedAt DESC, id DESC")
    abstract suspend fun findActiveCardsByKey(key: String): List<CardEntity>

    @Query("SELECT * FROM decks WHERE id = :deckId")
    abstract suspend fun getDeck(deckId: Long): DeckEntity?

    @Query("SELECT * FROM cards WHERE id = :cardId")
    abstract suspend fun getCard(cardId: Long): CardEntity?

    @Query("SELECT * FROM review_states WHERE cardId = :cardId")
    abstract suspend fun getReviewState(cardId: Long): ReviewStateEntity?

    @Query("SELECT * FROM pending_image_deletions ORDER BY queuedAt ASC, path ASC")
    abstract suspend fun getPendingImageDeletions(): List<PendingImageDeletionEntity>

    @Query("DELETE FROM pending_image_deletions WHERE path = :path")
    abstract suspend fun removePendingImageDeletion(path: String): Int

    @Query("SELECT * FROM decks ORDER BY createdAt ASC, id ASC LIMIT 1")
    protected abstract suspend fun findEarliestDeck(): DeckEntity?

    @Transaction
    open suspend fun insertCardWithReview(card: CardEntity): Long {
        val cardId = insertCard(card)
        insertReviewState(ReviewStateEntity(cardId = cardId))
        return cardId
    }

    @Transaction
    open suspend fun updateDeck(deck: DeckEntity) {
        check(updateDeckRow(deck) == 1) { "卡组 ${deck.id} 不存在，无法更新" }
    }

    @Transaction
    open suspend fun updateCard(card: CardEntity) {
        check(updateCardRow(card) == 1) { "卡片 ${card.id} 不存在，无法更新" }
    }

    @Transaction
    open suspend fun updateCardAndQueueImageDeletion(
        card: CardEntity,
        oldImagePath: String,
        queuedAt: Long,
    ) {
        check(updateCardRow(card) == 1) { "卡片 ${card.id} 不存在，无法更新" }
        insertPendingImageDeletion(PendingImageDeletionEntity(oldImagePath, queuedAt))
    }

    @Transaction
    open suspend fun deleteDeck(deck: DeckEntity) {
        check(deleteDeckRow(deck) == 1) { "卡组 ${deck.id} 不存在，无法删除" }
    }

    @Transaction
    open suspend fun deleteCard(card: CardEntity) {
        check(deleteCardRow(card) == 1) { "卡片 ${card.id} 不存在，无法删除" }
    }

    @Transaction
    open suspend fun deleteCardAndQueueImageDeletion(card: CardEntity, queuedAt: Long) {
        check(deleteCardRow(card) == 1) { "卡片 ${card.id} 不存在，无法删除" }
        insertPendingImageDeletion(PendingImageDeletionEntity(card.valueImagePath, queuedAt))
        card.frontImagePath?.let { insertPendingImageDeletion(PendingImageDeletionEntity(it, queuedAt)) }
    }

    @Transaction
    open suspend fun ensureDefaultDeck(now: Long): Long {
        val existing = findEarliestDeck()
        return existing?.id ?: insertDeck(
            DeckEntity(
                name = DEFAULT_DECK_NAME,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transaction
    open suspend fun updateFeedback(cardId: Long, feedback: ReviewFeedback, reviewedAt: Long) {
        val current = getReviewState(cardId)
            ?: throw IllegalStateException("卡片 $cardId 缺少复习状态")
        val next = current.copy(
            weight = ReviewWeightPolicy().nextWeight(current.weight, feedback),
            seenCount = current.seenCount + 1,
            againCount = current.againCount + if (feedback == ReviewFeedback.Again) 1 else 0,
            unsureCount = current.unsureCount + if (feedback == ReviewFeedback.Unsure) 1 else 0,
            knownCount = current.knownCount + if (feedback == ReviewFeedback.Know) 1 else 0,
            lastReviewedAt = reviewedAt,
        )
        check(updateReviewStateRow(next) == 1) { "卡片 $cardId 缺少复习状态，无法更新反馈" }
    }

    private companion object {
        const val DEFAULT_DECK_NAME = "默认卡组"
    }
}

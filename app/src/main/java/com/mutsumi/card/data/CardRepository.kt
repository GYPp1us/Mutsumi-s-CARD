package com.mutsumi.card.data

import com.mutsumi.card.domain.model.Deck
import com.mutsumi.card.domain.model.MemoryCard
import com.mutsumi.card.domain.review.ReviewFeedback
import kotlinx.coroutines.flow.Flow

interface CardRepository {
    val decks: Flow<List<Deck>>

    fun cards(deckId: Long, query: String = ""): Flow<List<MemoryCard>>
    suspend fun ensureDefaultDeck(): Long
    suspend fun createDeck(name: String): Long
    suspend fun renameDeck(deckId: Long, name: String)
    suspend fun saveCard(deckId: Long, keyText: String, png: ByteArray): Long
    suspend fun updateCard(cardId: Long, keyText: String, png: ByteArray? = null)
    suspend fun archiveCard(cardId: Long)
    suspend fun deleteCard(cardId: Long)
    suspend fun applyFeedback(cardId: Long, feedback: ReviewFeedback, now: Long)
    suspend fun pickRecommendedCard(deckId: Long, recentCardIds: List<Long>): MemoryCard?
    suspend fun retryPendingImageCleanup()
}

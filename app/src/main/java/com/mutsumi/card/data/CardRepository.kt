package com.mutsumi.card.data

import com.mutsumi.card.domain.model.Deck
import com.mutsumi.card.domain.model.MemoryCard
import com.mutsumi.card.domain.review.ReviewFeedback
import kotlinx.coroutines.flow.Flow

interface CardRepository {
    val decks: Flow<List<Deck>>
    val libraryDecks: Flow<List<Deck>> get() = decks
    fun managementCards(deckId: Long, query: String = "", includeArchived: Boolean = false): Flow<List<MemoryCard>> = cards(deckId, query)
    suspend fun createChildDeck(name: String, parentId: Long?): Long = createDeck(name)
    suspend fun managementImpact(selection: LibrarySelection): LibraryImpact = LibraryImpact(selection.cardIds.size, selection.deckIds.size, 0, null)
    suspend fun manage(selection: LibrarySelection, action: LibraryAction, targetDeckId: Long? = null, expectedRevision: Long? = null): Int = error("当前存储未实现批量管理")

    fun cards(deckId: Long, query: String = ""): Flow<List<MemoryCard>>
    suspend fun ensureDefaultDeck(): Long
    suspend fun createDeck(name: String): Long
    suspend fun renameDeck(deckId: Long, name: String)
    suspend fun saveCard(deckId: Long, keyText: String, png: ByteArray): Long
    suspend fun saveCard(deckId: Long, keyText: String, frontPng: ByteArray?, backPng: ByteArray): Long
    suspend fun updateCard(cardId: Long, keyText: String, png: ByteArray? = null)
    suspend fun archiveCard(cardId: Long)
    suspend fun deleteCard(cardId: Long)
    suspend fun applyFeedback(cardId: Long, feedback: ReviewFeedback, now: Long)
    suspend fun pickRecommendedCard(deckId: Long, recentCardIds: List<Long>): MemoryCard?
    suspend fun retryPendingImageCleanup()
}

data class LibrarySelection(val cardIds: Set<Long> = emptySet(), val deckIds: Set<Long> = emptySet())
data class LibraryImpact(val cardCount: Int, val deckCount: Int, val archivedCardCount: Int, val revision: Long?)
enum class LibraryAction(val label: String) { Move("移动"), Copy("复制"), Archive("归档"), Restore("恢复"), Delete("删除") }

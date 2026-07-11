package com.mutsumi.card.domain.model

data class Deck(
    val id: Long,
    val name: String,
    val cardCount: Int,
)

data class ReviewState(
    val weight: Double,
    val seenCount: Int,
    val againCount: Int,
    val unsureCount: Int,
    val knownCount: Int,
    val lastReviewedAt: Long?,
)

data class MemoryCard(
    val id: Long,
    val deckId: Long,
    val keyText: String,
    val valueImagePath: String,
    val archived: Boolean,
    val review: ReviewState,
)

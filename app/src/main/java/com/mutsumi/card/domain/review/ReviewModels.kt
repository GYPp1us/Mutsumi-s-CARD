package com.mutsumi.card.domain.review

enum class ReviewFeedback {
    Again,
    Unsure,
    Know,
}

data class WeightedReviewCard(
    val cardId: Long,
    val weight: Double,
)


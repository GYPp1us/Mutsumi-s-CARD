package com.mutsumi.card.study

import com.mutsumi.card.domain.review.ReviewFeedback

class StudyGesturePolicy(
    private val cardWidth: Float,
    private val cardHeight: Float,
) {
    init {
        require(cardWidth > 0f) { "卡片宽度必须大于 0" }
        require(cardHeight > 0f) { "卡片高度必须大于 0" }
    }

    private val horizontalThreshold: Float = cardWidth / 10f
    private val verticalThreshold: Float = cardHeight / 10f

    fun flipProgress(horizontalDrag: Float): Float {
        return (horizontalDrag / horizontalThreshold).coerceIn(-1f, 1f)
    }

    fun feedbackFor(verticalDrag: Float): ReviewFeedback? {
        return when {
            verticalDrag <= -verticalThreshold -> ReviewFeedback.Know
            verticalDrag >= verticalThreshold -> ReviewFeedback.Again
            else -> null
        }
    }
}

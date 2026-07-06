package com.mutsumi.card.domain.review

import kotlin.math.max
import kotlin.math.min

class ReviewWeightPolicy {
    fun nextWeight(currentWeight: Double, feedback: ReviewFeedback): Double {
        val next = when (feedback) {
            ReviewFeedback.Again -> min(currentWeight + 0.8, 5.0)
            ReviewFeedback.Unsure -> min(currentWeight + 0.3, 5.0)
            ReviewFeedback.Know -> max(currentWeight * 0.55, 0.2)
        }
        return (next * 100.0).toInt() / 100.0
    }
}


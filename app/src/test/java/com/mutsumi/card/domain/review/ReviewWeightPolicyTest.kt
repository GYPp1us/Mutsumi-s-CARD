package com.mutsumi.card.domain.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewWeightPolicyTest {
    private val policy = ReviewWeightPolicy()

    @Test
    fun againIncreasesWeightUpToFive() {
        assertThat(policy.nextWeight(4.6, ReviewFeedback.Again)).isEqualTo(5.0)
    }

    @Test
    fun unsureSlightlyIncreasesWeight() {
        assertThat(policy.nextWeight(1.0, ReviewFeedback.Unsure)).isEqualTo(1.3)
    }

    @Test
    fun knownLowersWeightButKeepsMinimum() {
        assertThat(policy.nextWeight(0.3, ReviewFeedback.Know)).isEqualTo(0.2)
    }
}


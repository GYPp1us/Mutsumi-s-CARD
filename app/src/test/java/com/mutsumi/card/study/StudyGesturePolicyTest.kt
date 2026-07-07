package com.mutsumi.card.study

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.domain.review.ReviewFeedback
import org.junit.Test

class StudyGesturePolicyTest {
    @Test
    fun horizontalDragAtOneTenthWidthFullyFlipsCard() {
        val policy = StudyGesturePolicy(cardWidth = 1000f, cardHeight = 1600f)

        assertThat(policy.flipProgress(horizontalDrag = 100f)).isEqualTo(1f)
        assertThat(policy.flipProgress(horizontalDrag = -100f)).isEqualTo(-1f)
        assertThat(policy.flipProgress(horizontalDrag = 50f)).isEqualTo(0.5f)
    }

    @Test
    fun verticalDragMapsToFeedback() {
        val policy = StudyGesturePolicy(cardWidth = 1000f, cardHeight = 1600f)

        assertThat(policy.feedbackFor(verticalDrag = -180f)).isEqualTo(ReviewFeedback.Know)
        assertThat(policy.feedbackFor(verticalDrag = 180f)).isEqualTo(ReviewFeedback.Again)
        assertThat(policy.feedbackFor(verticalDrag = 80f)).isNull()
    }
}

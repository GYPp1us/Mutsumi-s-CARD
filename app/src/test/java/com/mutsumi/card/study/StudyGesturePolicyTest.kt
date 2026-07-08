package com.mutsumi.card.study

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.domain.review.ReviewFeedback
import org.junit.Test

class StudyGesturePolicyTest {
    private val policy = StudyGesturePolicy(screenWidth = 1000f, cardWidth = 980f, cardHeight = 490f)
    private val anchor = StudyTouchPoint(500f, 500f)

    @Test
    fun dragInsideQuarterScreenOnlyTiltsWithoutAction() {
        val projection = policy.project(
            anchor = anchor,
            current = StudyTouchPoint(625f, 500f),
            committedSide = CardSide.Front,
        )

        assertThat(projection.rotationY).isWithin(0.01f).of(6f)
        assertThat(projection.translationY).isWithin(0.01f).of(0f)
        assertThat(projection.flipProgress).isWithin(0.01f).of(0f)
        assertThat(projection.releaseAction).isNull()
        assertThat(projection.showingBack).isFalse()
    }

    @Test
    fun rightDragAfterQuarterScreenFlipsWithinOneTenthScreenWidth() {
        val halfFlip = policy.project(anchor, StudyTouchPoint(800f, 500f), CardSide.Front)
        val fullFlip = policy.project(anchor, StudyTouchPoint(850f, 500f), CardSide.Front)

        assertThat(halfFlip.flipProgress).isWithin(0.01f).of(0.5f)
        assertThat(halfFlip.rotationY).isWithin(0.01f).of(90f)
        assertThat(halfFlip.showingBack).isTrue()
        assertThat(halfFlip.releaseAction).isEqualTo(StudyReleaseAction.ToggleSide)

        assertThat(fullFlip.flipProgress).isWithin(0.01f).of(1f)
        assertThat(fullFlip.rotationY).isWithin(0.01f).of(180f)
        assertThat(fullFlip.showingBack).isTrue()
    }

    @Test
    fun crossingNinetyDegreesDoesNotCommitUntilReleaseAndCanDragBack() {
        val crossed = policy.project(anchor, StudyTouchPoint(825f, 500f), CardSide.Front)
        val draggedBack = policy.project(anchor, StudyTouchPoint(790f, 500f), CardSide.Front)

        assertThat(crossed.showingBack).isTrue()
        assertThat(crossed.releaseAction).isEqualTo(StudyReleaseAction.ToggleSide)

        assertThat(draggedBack.showingBack).isFalse()
        assertThat(draggedBack.releaseAction).isNull()
    }

    @Test
    fun backSideCanContinuouslyFlipBackToFront() {
        val projection = policy.project(anchor, StudyTouchPoint(150f, 500f), CardSide.Back)

        assertThat(projection.flipProgress).isWithin(0.01f).of(-1f)
        assertThat(projection.rotationY).isWithin(0.01f).of(0f)
        assertThat(projection.showingBack).isFalse()
        assertThat(projection.releaseAction).isEqualTo(StudyReleaseAction.ToggleSide)
    }

    @Test
    fun upwardDragOutsideQuarterScreenFollowsFingerAndFades() {
        val projection = policy.project(anchor, StudyTouchPoint(500f, 200f), CardSide.Front)

        assertThat(projection.translationY).isLessThan(-130f)
        assertThat(projection.alpha).isLessThan(0.75f)
        assertThat(projection.releaseAction).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Know))
    }

    @Test
    fun downwardDragOutsideQuarterScreenTriggersAgain() {
        val projection = policy.project(anchor, StudyTouchPoint(500f, 800f), CardSide.Front)

        assertThat(projection.translationY).isGreaterThan(130f)
        assertThat(projection.releaseAction).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Again))
    }

    @Test
    fun upwardIntentSuppressesHorizontalOffsetAndFlip() {
        val projection = policy.project(anchor, StudyTouchPoint(700f, 200f), CardSide.Front)

        assertThat(projection.translationX).isLessThan(30f)
        assertThat(projection.flipProgress).isWithin(0.01f).of(0f)
        assertThat(projection.releaseAction).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Know))
    }
}

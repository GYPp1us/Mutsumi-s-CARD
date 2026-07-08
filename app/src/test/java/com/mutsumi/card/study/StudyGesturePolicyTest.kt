package com.mutsumi.card.study

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.domain.review.ReviewFeedback
import org.junit.Test

class StudyGesturePolicyTest {
    private val policy = StudyGesturePolicy(
        screenWidth = 1000f,
        screenHeight = 1200f,
        cardWidth = 980f,
        cardHeight = 490f,
    )
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
        assertThat(projection.cardAlpha).isEqualTo(1f)
    }

    @Test
    fun rightDragAfterQuarterScreenCarriesTiltIntoFlipBand() {
        val boundary = policy.project(anchor, StudyTouchPoint(750f, 500f), CardSide.Front)
        val halfFlip = policy.project(anchor, StudyTouchPoint(800f, 500f), CardSide.Front)
        val fullFlip = policy.project(anchor, StudyTouchPoint(850f, 500f), CardSide.Front)

        assertThat(boundary.rotationY).isWithin(0.01f).of(12f)
        assertThat(halfFlip.flipProgress).isWithin(0.01f).of(0.5f)
        assertThat(halfFlip.rotationY).isWithin(0.01f).of(96f)
        assertThat(halfFlip.showingBack).isTrue()
        assertThat(halfFlip.releaseAction).isEqualTo(StudyReleaseAction.ToggleSide)

        assertThat(fullFlip.flipProgress).isWithin(0.01f).of(1f)
        assertThat(fullFlip.rotationY).isWithin(0.01f).of(180f)
        assertThat(fullFlip.showingBack).isTrue()
    }

    @Test
    fun completedFlipNeverRotatesPastOneHundredEightyDegrees() {
        val projection = policy.project(anchor, StudyTouchPoint(1300f, 500f), CardSide.Front)

        assertThat(projection.flipProgress).isWithin(0.01f).of(1f)
        assertThat(projection.rotationY).isWithin(0.01f).of(180f)
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
    fun cardKeepsPhysicalOpacityDuringUpwardDrag() {
        val projection = policy.project(anchor, StudyTouchPoint(500f, 100f), CardSide.Front)

        assertThat(projection.translationY).isLessThan(-360f)
        assertThat(projection.cardAlpha).isEqualTo(1f)
        assertThat(projection.frontAlpha).isEqualTo(1f)
    }

    @Test
    fun upwardReleaseUsesOneThirdScreenHeight() {
        val justInside = policy.project(anchor, StudyTouchPoint(500f, 101f), CardSide.Front)
        val atThreshold = policy.project(anchor, StudyTouchPoint(500f, 100f), CardSide.Front)

        assertThat(justInside.releaseAction).isNull()
        assertThat(atThreshold.releaseAction).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Know))
    }

    @Test
    fun downwardReleaseUsesOneThirdScreenHeight() {
        val justInside = policy.project(anchor, StudyTouchPoint(500f, 899f), CardSide.Front)
        val atThreshold = policy.project(anchor, StudyTouchPoint(500f, 900f), CardSide.Front)

        assertThat(justInside.releaseAction).isNull()
        assertThat(atThreshold.releaseAction).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Again))
    }

    @Test
    fun upwardIntentSuppressesHorizontalOffsetAndFlip() {
        val projection = policy.project(anchor, StudyTouchPoint(700f, 100f), CardSide.Front)

        assertThat(projection.translationX).isLessThan(30f)
        assertThat(projection.flipProgress).isWithin(0.01f).of(0f)
        assertThat(projection.releaseAction).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Know))
    }

    @Test
    fun faceAlphasComeFromProjectionInsteadOfConditionalTreeSwitching() {
        val front = policy.project(anchor, StudyTouchPoint(625f, 500f), CardSide.Front)
        val back = policy.project(anchor, StudyTouchPoint(850f, 500f), CardSide.Front)

        assertThat(front.frontAlpha).isEqualTo(1f)
        assertThat(front.backAlpha).isEqualTo(0f)
        assertThat(back.frontAlpha).isEqualTo(0f)
        assertThat(back.backAlpha).isEqualTo(1f)
    }
}

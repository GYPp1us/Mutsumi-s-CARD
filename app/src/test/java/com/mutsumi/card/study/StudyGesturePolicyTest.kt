package com.mutsumi.card.study

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.domain.review.ReviewFeedback
import org.junit.Test

class StudyGesturePolicyTest {
    private val screenWidth = 1000f
    private val policy = StudyGesturePolicy(
        screenWidth = screenWidth,
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

        assertThat(projection.physicalState.angle.axisRotationZ).isWithin(0.01f).of(0f)
        assertThat(projection.physicalState.angle.deflection).isWithin(0.01f).of(6f)
        assertThat(projection.physicalState.center.y).isWithin(0.01f).of(0f)
        assertThat(projection.flipProgress).isWithin(0.01f).of(0f)
        assertThat(projection.releaseAction).isNull()
    }

    @Test
    fun rightDragAfterQuarterScreenCarriesTiltIntoFlipBand() {
        val boundary = policy.project(anchor, StudyTouchPoint(750f, 500f), CardSide.Front)
        val halfFlip = policy.project(anchor, StudyTouchPoint(800f, 500f), CardSide.Front)
        val fullFlip = policy.project(anchor, StudyTouchPoint(850f, 500f), CardSide.Front)

        assertThat(boundary.physicalState.angle.axisRotationZ).isWithin(0.01f).of(0f)
        assertThat(boundary.physicalState.angle.deflection).isWithin(0.01f).of(12f)
        assertThat(halfFlip.flipProgress).isWithin(0.01f).of(0.5f)
        assertThat(halfFlip.physicalState.angle.deflection).isWithin(0.01f).of(96f)
        assertThat(halfFlip.releaseAction).isEqualTo(StudyReleaseAction.ToggleSide)

        assertThat(fullFlip.flipProgress).isWithin(0.01f).of(1f)
        assertThat(fullFlip.physicalState.angle.deflection).isWithin(0.01f).of(180f)
    }

    @Test
    fun completedFlipNeverRotatesPastOneHundredEightyDegrees() {
        val projection = policy.project(anchor, StudyTouchPoint(1300f, 500f), CardSide.Front)

        assertThat(projection.flipProgress).isWithin(0.01f).of(1f)
        assertThat(projection.physicalState.angle.deflection).isWithin(0.01f).of(180f)
    }

    @Test
    fun crossingNinetyDegreesDoesNotCommitUntilReleaseAndCanDragBack() {
        val crossed = policy.project(anchor, StudyTouchPoint(825f, 500f), CardSide.Front)
        val draggedBack = policy.project(anchor, StudyTouchPoint(790f, 500f), CardSide.Front)

        assertThat(crossed.releaseAction).isEqualTo(StudyReleaseAction.ToggleSide)

        assertThat(draggedBack.releaseAction).isNull()
    }

    @Test
    fun backSideCanContinuouslyFlipBackToFront() {
        val projection = policy.project(anchor, StudyTouchPoint(150f, 500f), CardSide.Back)

        assertThat(projection.flipProgress).isWithin(0.01f).of(-1f)
        assertThat(projection.physicalState.angle.deflection).isWithin(0.01f).of(0f)
        assertThat(projection.releaseAction).isEqualTo(StudyReleaseAction.ToggleSide)
    }

    @Test
    fun physicalDeflectionStaysBetweenFrontAndBackFaces() {
        val frontOvershoot = policy.project(anchor, StudyTouchPoint(1300f, 500f), CardSide.Front)
        val backOvershoot = policy.project(anchor, StudyTouchPoint(1300f, 500f), CardSide.Back)

        assertThat(frontOvershoot.physicalState.angle.deflection).isAtMost(180f)
        assertThat(backOvershoot.physicalState.angle.deflection).isAtMost(180f)
        assertThat(backOvershoot.physicalState.angle.deflection).isAtLeast(0f)
    }

    @Test
    fun verticalDragMovesTheSamePhysicalCenter() {
        val projection = policy.project(anchor, StudyTouchPoint(500f, 100f), CardSide.Front)

        assertThat(projection.physicalState.center.y).isLessThan(-250f)
        assertThat(projection.physicalState.angle.axisRotationZ).isWithin(0.01f).of(-90f)
        assertThat(projection.physicalState.angle.deflection).isWithin(0.01f).of(180f)
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
    fun upwardDragDoesNotSuppressThePhysicalHorizontalCenter() {
        val projection = policy.project(anchor, StudyTouchPoint(700f, 100f), CardSide.Front)

        assertThat(projection.physicalState.center.x).isGreaterThan(120f)
        assertThat(projection.physicalState.center.y).isLessThan(-250f)
        assertThat(projection.flipProgress).isWithin(0.01f).of(0f)
        assertThat(projection.releaseAction).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Know))
    }

    @Test
    fun dragPhysicsMapsVerticalAndHorizontalCenterWithOneFunction() {
        val vertical = studyCardPhysicsFromDrag(
            dx = 0f,
            dy = -400f,
            committedSide = CardSide.Front,
            screenWidth = screenWidth,
        )
        val horizontal = studyCardPhysicsFromDrag(
            dx = 400f,
            dy = 0f,
            committedSide = CardSide.Front,
            screenWidth = screenWidth,
        )

        assertThat(-vertical.center.y).isWithin(0.01f).of(horizontal.center.x)
        assertThat(vertical.center.x).isWithin(0.01f).of(0f)
        assertThat(horizontal.center.y).isWithin(0.01f).of(0f)
        assertThat(vertical.angle.deflection).isWithin(0.01f).of(horizontal.angle.deflection)
        assertThat(vertical.angle.axisRotationZ).isWithin(0.01f).of(-90f)
        assertThat(horizontal.angle.axisRotationZ).isWithin(0.01f).of(0f)
    }
}

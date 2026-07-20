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
    fun dragInsideInteractionCircleOnlyTiltsWithoutAction() {
        val projection = policy.project(
            anchor = anchor,
            current = StudyTouchPoint(625f, 500f),
            committedSide = CardSide.Front,
        )

        assertThat(projection.physicalState.angle.axisRotationZ).isWithin(0.01f).of(0f)
        assertThat(projection.physicalState.angle.deflection).isWithin(0.01f).of(5.46875f)
        assertThat(projection.physicalState.center.y).isWithin(0.01f).of(0f)
        assertThat(projection.flipProgress).isWithin(0.01f).of(0f)
        assertThat(projection.releaseAction).isNull()
    }

    @Test
    fun rightDragAfterInteractionCircleCarriesTiltIntoFlipBand() {
        val boundary = policy.project(anchor, StudyTouchPoint(700f, 500f), CardSide.Front)
        val halfFlip = policy.project(anchor, StudyTouchPoint(750f, 500f), CardSide.Front)
        val fullFlip = policy.project(anchor, StudyTouchPoint(800f, 500f), CardSide.Front)

        assertThat(boundary.physicalState.angle.axisRotationZ).isWithin(0.01f).of(0f)
        assertThat(boundary.physicalState.angle.deflection).isWithin(0.01f).of(8f)
        assertThat(halfFlip.flipProgress).isWithin(0.01f).of(0.5f)
        assertThat(halfFlip.physicalState.angle.deflection).isWithin(0.01f).of(94f)
        assertThat(policy.release(halfFlip, 0f, 0f, 600f)).isEqualTo(StudyReleaseAction.ToggleSide)

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
        val crossed = policy.project(anchor, StudyTouchPoint(775f, 500f), CardSide.Front)
        val draggedBack = policy.project(anchor, StudyTouchPoint(740f, 500f), CardSide.Front)

        assertThat(crossed.releaseAction).isNull()
        assertThat(policy.release(crossed, 0f, 0f, 600f)).isEqualTo(StudyReleaseAction.ToggleSide)

        assertThat(policy.release(draggedBack, 0f, 0f, 600f)).isNull()
    }

    @Test
    fun backSideCanContinuouslyFlipBackToFront() {
        val projection = policy.project(anchor, StudyTouchPoint(150f, 500f), CardSide.Back)

        assertThat(projection.flipProgress).isWithin(0.01f).of(-1f)
        assertThat(projection.physicalState.angle.deflection).isWithin(0.01f).of(0f)
        assertThat(policy.release(projection, 0f, 0f, 600f)).isEqualTo(StudyReleaseAction.ToggleSide)
    }

    @Test
    fun backSideInteractionStartsFromReversedVisibleAxis() {
        val front = studyCardPhysicsFromDrag(
            dx = 125f,
            dy = 0f,
            committedSide = CardSide.Front,
            interactionStartDirection = CardSide.Front,
            screenWidth = screenWidth,
        )
        val back = studyCardPhysicsFromDrag(
            dx = 125f,
            dy = 0f,
            committedSide = CardSide.Back,
            interactionStartDirection = CardSide.Back,
            screenWidth = screenWidth,
        )

        assertThat(front.angle.axisRotationZ).isWithin(0.01f).of(0f)
        assertThat(front.angle.deflection).isWithin(0.01f).of(5.46875f)
        assertThat(back.angle.axisRotationZ).isWithin(0.01f).of(180f)
        assertThat(180f - back.angle.deflection).isWithin(0.01f).of(front.angle.deflection)
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
        assertThat(projection.physicalState.angle.deflection).isWithin(0.01f).of(7f)
    }

    @Test
    fun horizontalDragStillAllowsAFullFlipAfterVerticalRollLimit() {
        val projection = policy.project(anchor, StudyTouchPoint(800f, 500f), CardSide.Front)

        assertThat(projection.physicalState.angle.axisRotationZ).isWithin(0.01f).of(0f)
        assertThat(projection.physicalState.angle.deflection).isWithin(0.01f).of(180f)
        assertThat(policy.release(projection, 0f, 0f, 600f)).isEqualTo(StudyReleaseAction.ToggleSide)
    }

    @Test
    fun upwardReleaseUsesOneThirdScreenHeight() {
        val justInside = policy.project(anchor, StudyTouchPoint(500f, 101f), CardSide.Front)
        val atThreshold = policy.project(anchor, StudyTouchPoint(500f, 100f), CardSide.Front)

        assertThat(policy.release(justInside, 0f, 0f, 600f)).isNull()
        assertThat(policy.release(atThreshold, 0f, 0f, 600f)).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Know))
    }

    @Test
    fun downwardReleaseUsesOneThirdScreenHeight() {
        val justInside = policy.project(anchor, StudyTouchPoint(500f, 899f), CardSide.Front)
        val atThreshold = policy.project(anchor, StudyTouchPoint(500f, 900f), CardSide.Front)

        assertThat(policy.release(justInside, 0f, 0f, 600f)).isNull()
        assertThat(policy.release(atThreshold, 0f, 0f, 600f)).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Again))
    }

    @Test
    fun upwardDragDoesNotSuppressThePhysicalHorizontalCenter() {
        val projection = policy.project(anchor, StudyTouchPoint(700f, 100f), CardSide.Front)

        assertThat(projection.physicalState.center.x).isWithin(0.01f).of(15f)
        assertThat(projection.physicalState.center.y).isLessThan(-250f)
        assertThat(projection.flipProgress).isWithin(0.01f).of(0f)
        assertThat(policy.release(projection, 0f, 0f, 600f)).isEqualTo(StudyReleaseAction.Feedback(ReviewFeedback.Know))
    }

    @Test
    fun horizontalCenterMovementUsesSubtleFollowRatio() {
        val projection = policy.project(anchor, StudyTouchPoint(900f, 500f), CardSide.Front)

        assertThat(projection.physicalState.center.x).isWithin(0.01f).of(30f)
        assertThat(projection.physicalState.center.y).isWithin(0.01f).of(0f)
    }

    @Test
    fun dragPhysicsMapsVerticalAndHorizontalCenterWithOneFunction() {
        val vertical = studyCardPhysicsFromDrag(
            dx = 0f,
            dy = -400f,
            committedSide = CardSide.Front,
            interactionStartDirection = CardSide.Front,
            screenWidth = screenWidth,
        )
        val horizontal = studyCardPhysicsFromDrag(
            dx = 400f,
            dy = 0f,
            committedSide = CardSide.Front,
            interactionStartDirection = CardSide.Front,
            screenWidth = screenWidth,
        )

        assertThat(-vertical.center.y).isWithin(0.01f).of(400f)
        assertThat(horizontal.center.x).isWithin(0.01f).of(30f)
        assertThat(vertical.center.x).isWithin(0.01f).of(0f)
        assertThat(horizontal.center.y).isWithin(0.01f).of(0f)
        assertThat(vertical.angle.deflection).isLessThan(horizontal.angle.deflection)
        assertThat(vertical.angle.axisRotationZ).isWithin(0.01f).of(-90f)
        assertThat(horizontal.angle.axisRotationZ).isWithin(0.01f).of(0f)
    }

    @Test
    fun returnAnimationEasingIsNonLinearAndEndsAtRest() {
        assertThat(studyCardReturnEasing(0f)).isWithin(0.0001f).of(0f)
        assertThat(studyCardReturnEasing(0.5f)).isGreaterThan(0.5f)
        assertThat(studyCardReturnEasing(1f)).isWithin(0.0001f).of(1f)
    }

    @Test
    fun returnAnimationKeepsAxisContinuousWhenTargetFaceIsDegenerate() {
        val start = StudyCardPhysicalState(
            center = StudyCardCenter(x = 30f, y = 0f),
            angle = StudyCardAngle(axisRotationZ = 180f, deflection = 174f),
        )
        val backResting = StudyCardPhysicalState(
            center = StudyCardCenter(x = 0f, y = 0f),
            angle = StudyCardAngle(axisRotationZ = 0f, deflection = 180f),
        )

        val middle = interpolateStudyCardPhysicalState(
            from = start,
            to = backResting,
            fraction = 0.5f,
        )

        assertThat(middle.angle.axisRotationZ).isWithin(0.01f).of(180f)
        assertThat(middle.angle.deflection).isWithin(0.01f).of(177f)
    }
}

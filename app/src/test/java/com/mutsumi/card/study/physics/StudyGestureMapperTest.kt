package com.mutsumi.card.study.physics

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.study.CardSide
import org.junit.Test

class StudyGestureMapperTest {
    private val viewport = Size2(width = 1000f, height = 1200f)
    private val mapper = StudyGestureMapper()
    private val front = CardPose(
        center = Vec2(500f, 600f),
        orientation = Quaternion.Identity,
    )
    private val back = CardPose(
        center = Vec2(500f, 600f),
        orientation = Quaternion.fromAxisAngle(Vec3.UnitY, 180f),
    )

    @Test
    fun zeroDistanceReturnsExactStartingPoseAndNoAction() {
        val projection = mapper.poseForDrag(Vec2.Zero, front, CardSide.Front, viewport)

        assertThat(projection.pose).isEqualTo(front)
        assertThat(projection.releaseAction).isNull()
        assertThat(projection.relativeTurnDegrees).isEqualTo(0f)
    }

    @Test
    fun neighborhoodBoundaryHasContinuousValueAndFirstTrend() {
        val radius = viewport.width / 4f
        val step = 0.1f
        val before = mapper.poseForDrag(Vec2(radius - step, 0f), front, CardSide.Front, viewport)
        val boundary = mapper.poseForDrag(Vec2(radius, 0f), front, CardSide.Front, viewport)
        val after = mapper.poseForDrag(Vec2(radius + step, 0f), front, CardSide.Front, viewport)

        val leftSlope = (boundary.relativeTurnDegrees - before.relativeTurnDegrees) / step
        val rightSlope = (after.relativeTurnDegrees - boundary.relativeTurnDegrees) / step

        assertThat(boundary.relativeTurnDegrees).isWithin(0.001f).of(12f)
        assertThat(after.relativeTurnDegrees - boundary.relativeTurnDegrees).isLessThan(0.01f)
        assertThat(rightSlope).isWithin(0.002f).of(leftSlope)
    }

    @Test
    fun horizontalExtraTenthOfScreenCompletesExactlyOneHalfTurn() {
        val drag = viewport.width / 4f + viewport.width / 10f

        val projection = mapper.poseForDrag(Vec2(drag, 0f), front, CardSide.Front, viewport)

        assertThat(projection.relativeTurnDegrees).isWithin(0.001f).of(180f)
        assertThat(Quaternion.angularDistanceDegrees(front.orientation, projection.pose.orientation))
            .isWithin(0.001f)
            .of(180f)
        assertThat(projection.releaseAction).isEqualTo(GestureReleaseAction.ToggleSide)
    }

    @Test
    fun horizontalOvershootNeverExceedsHalfTurnFromGestureStart() {
        val projection = mapper.poseForDrag(Vec2(4000f, 0f), front, CardSide.Front, viewport)

        assertThat(projection.relativeTurnDegrees).isEqualTo(180f)
        assertThat(Quaternion.angularDistanceDegrees(front.orientation, projection.pose.orientation))
            .isWithin(0.001f)
            .of(180f)
    }

    @Test
    fun backSideStartsFromItsExplicitDirectionAndUsesTheSameLocalTurn() {
        val delta = Vec2(300f, 0f)
        val frontProjection = mapper.poseForDrag(delta, front, CardSide.Front, viewport)
        val backProjection = mapper.poseForDrag(delta, back, CardSide.Back, viewport)

        val frontRelative = front.orientation.inverse() * frontProjection.pose.orientation
        val backRelative = back.orientation.inverse() * backProjection.pose.orientation

        assertThat(frontRelative.isEquivalentTo(backRelative)).isTrue()
        assertThat(Quaternion.angularDistanceDegrees(back.orientation, backProjection.pose.orientation))
            .isWithin(0.001f)
            .of(backProjection.relativeTurnDegrees)
    }

    @Test
    fun sameStartPoseUsesSideToSelectOppositeNormalsAndMatchingScreenTilt() {
        val delta = Vec2(125f, 0f)

        val frontProjection = mapper.poseForDrag(delta, front, CardSide.Front, viewport)
        val backProjection = mapper.poseForDrag(delta, front, CardSide.Back, viewport)
        val frontNormal = frontProjection.pose.normalFor(CardSide.Front)
        val backNormal = backProjection.pose.normalFor(CardSide.Back)

        assertThat(front.normalFor(CardSide.Front).z).isWithin(0.001f).of(1f)
        assertThat(front.normalFor(CardSide.Back).z).isWithin(0.001f).of(-1f)
        assertThat(frontProjection.pose.orientation.isEquivalentTo(backProjection.pose.orientation)).isFalse()
        assertThat(frontNormal.x).isGreaterThan(0f)
        assertThat(backNormal.x).isGreaterThan(0f)
    }

    @Test
    fun explicitBackPoseIsNotRotatedAnotherHalfTurn() {
        val resting = mapper.poseForDrag(Vec2.Zero, back, CardSide.Back, viewport)
        val dragged = mapper.poseForDrag(Vec2(125f, 0f), back, CardSide.Back, viewport)

        assertThat(resting.pose).isEqualTo(back)
        assertThat(resting.pose.orientation.isEquivalentTo(Quaternion.Identity)).isFalse()
        assertThat(dragged.pose.normalFor(CardSide.Back).x).isGreaterThan(0f)
        assertThat(Quaternion.angularDistanceDegrees(back.orientation, dragged.pose.orientation))
            .isWithin(0.001f)
            .of(6f)
    }

    @Test
    fun oppositeHorizontalDirectionProducesAnOppositeButBoundedTurn() {
        val right = mapper.poseForDrag(Vec2(350f, 0f), back, CardSide.Back, viewport)
        val left = mapper.poseForDrag(Vec2(-350f, 0f), back, CardSide.Back, viewport)

        assertThat(right.relativeTurnDegrees).isEqualTo(180f)
        assertThat(left.relativeTurnDegrees).isEqualTo(180f)
        assertThat(right.pose.orientation.isEquivalentTo(left.pose.orientation)).isTrue()
    }

    @Test
    fun horizontalCenterUsesSevenPointFivePercentFollowRatio() {
        val projection = mapper.poseForDrag(Vec2(400f, 0f), front, CardSide.Front, viewport)

        assertThat(projection.pose.center.x).isWithin(0.001f).of(530f)
        assertThat(projection.pose.center.y).isWithin(0.001f).of(600f)
    }

    @Test
    fun verticalRollStopsAtThirtyTwoDegrees() {
        val projection = mapper.poseForDrag(Vec2(0f, -2000f), front, CardSide.Front, viewport)

        assertThat(projection.verticalRollDegrees).isWithin(0.001f).of(32f)
        assertThat(Quaternion.angularDistanceDegrees(front.orientation, projection.pose.orientation))
            .isWithin(0.001f)
            .of(32f)
    }

    @Test
    fun upwardOuterDragSmoothlySuppressesHorizontalCenterMovement() {
        val inside = mapper.poseForDrag(Vec2(200f, -100f), front, CardSide.Front, viewport)
        val justOutside = mapper.poseForDrag(Vec2(200f, -251f), front, CardSide.Front, viewport)
        val fartherOutside = mapper.poseForDrag(Vec2(200f, -330f), front, CardSide.Front, viewport)

        assertThat(inside.horizontalCenterSuppression).isWithin(0.001f).of(1f)
        assertThat(justOutside.horizontalCenterSuppression).isGreaterThan(fartherOutside.horizontalCenterSuppression)
        assertThat(fartherOutside.pose.center.x).isLessThan(justOutside.pose.center.x)
        assertThat(fartherOutside.horizontalCenterSuppression).isGreaterThan(0f)
    }

    @Test
    fun oneThirdScreenHeightCommitsVerticalFeedback() {
        val threshold = viewport.height / 3f
        val upward = mapper.poseForDrag(Vec2(0f, -threshold), front, CardSide.Front, viewport)
        val downward = mapper.poseForDrag(Vec2(0f, threshold), front, CardSide.Front, viewport)
        val justInside = mapper.poseForDrag(Vec2(0f, -threshold + 0.1f), front, CardSide.Front, viewport)

        assertThat(upward.releaseAction).isEqualTo(GestureReleaseAction.Know)
        assertThat(downward.releaseAction).isEqualTo(GestureReleaseAction.Again)
        assertThat(justInside.releaseAction).isNull()
    }

    @Test
    fun compactLandscapeCardFollowsFingerAtVerticalCommitInsideWidthNeighborhood() {
        val compactViewport = Size2(width = 800f, height = 360f)
        val start = CardPose(Vec2(400f, 180f), Quaternion.Identity)

        val projection = mapper.poseForDrag(Vec2(0f, -120f), start, CardSide.Front, compactViewport)

        assertThat(projection.releaseAction).isEqualTo(GestureReleaseAction.Know)
        assertThat(projection.pose.center.y).isWithin(0.01f).of(60f)
    }

    @Test
    fun wideLandscapeCardFollowsFingerAtVerticalCommitInsideWidthNeighborhood() {
        val wideViewport = Size2(width = 1280f, height = 800f)
        val start = CardPose(Vec2(640f, 400f), Quaternion.Identity)
        val commitDistance = wideViewport.height / 3f

        val projection = mapper.poseForDrag(
            Vec2(0f, -commitDistance),
            start,
            CardSide.Front,
            wideViewport,
        )

        assertThat(projection.releaseAction).isEqualTo(GestureReleaseAction.Know)
        assertThat(projection.pose.center.y).isWithin(0.01f).of(400f - commitDistance)
    }

    @Test
    fun verticalCenterIsContinuousAcrossCommitThreshold() {
        val compactViewport = Size2(width = 800f, height = 360f)
        val start = CardPose(Vec2(400f, 180f), Quaternion.Identity)
        val threshold = compactViewport.height / 3f
        val step = 0.01f

        val before = mapper.poseForDrag(Vec2(0f, -threshold + step), start, CardSide.Front, compactViewport)
        val at = mapper.poseForDrag(Vec2(0f, -threshold), start, CardSide.Front, compactViewport)
        val after = mapper.poseForDrag(Vec2(0f, -threshold - step), start, CardSide.Front, compactViewport)

        assertThat(at.pose.center.y - before.pose.center.y).isWithin(0.02f).of(-step)
        assertThat(after.pose.center.y - at.pose.center.y).isWithin(0.02f).of(-step)
    }

    @Test
    fun extremeVerticalDragDoesNotAccumulateHorizontalFlipFromTotalDistance() {
        val projection = mapper.poseForDrag(Vec2(50f, -5000f), front, CardSide.Front, viewport)

        assertThat(projection.relativeTurnDegrees).isWithin(0.001f).of(2.4f)
        assertThat(projection.relativeTurnDegrees).isLessThan(5f)
        assertThat(projection.releaseAction).isEqualTo(GestureReleaseAction.Know)
    }

    @Test
    fun horizontalTurnRemainsContinuousWhenVerticalDistanceIsAlreadyFarOutside() {
        val horizontalBoundary = viewport.width / 4f
        val step = 0.1f
        val before = mapper.poseForDrag(
            Vec2(horizontalBoundary - step, -5000f),
            front,
            CardSide.Front,
            viewport,
        )
        val at = mapper.poseForDrag(
            Vec2(horizontalBoundary, -5000f),
            front,
            CardSide.Front,
            viewport,
        )
        val after = mapper.poseForDrag(
            Vec2(horizontalBoundary + step, -5000f),
            front,
            CardSide.Front,
            viewport,
        )
        val leftSlope = (at.relativeTurnDegrees - before.relativeTurnDegrees) / step
        val rightSlope = (after.relativeTurnDegrees - at.relativeTurnDegrees) / step

        assertThat(at.relativeTurnDegrees).isWithin(0.001f).of(12f)
        assertThat(rightSlope).isWithin(0.002f).of(leftSlope)
    }

    @Test
    fun returnAnimationUsesNonlinearCenterAndShortestOrientationArc() {
        val target = CardPose(
            center = Vec2.Zero,
            orientation = Quaternion.Identity,
        )
        val start = CardPose(
            center = Vec2(100f, -80f),
            orientation = Quaternion.fromAxisAngle(Vec3.UnitY, 350f),
        )
        val animation = PoseAnimation(startPose = start, targetPose = target)

        val middle = animation.sample(0.5f)

        assertThat(middle.center.x).isLessThan(50f)
        assertThat(middle.center.y).isGreaterThan(-40f)
        assertThat(Quaternion.angularDistanceDegrees(start.orientation, middle.orientation))
            .isWithin(0.01f)
            .of(5f)
        assertThat(animation.sample(1f)).isEqualTo(target)
    }

    @Test
    fun interruptedReturnContinuesFromTheCurrentPoseWithoutJumping() {
        val first = PoseAnimation(
            startPose = CardPose(Vec2(120f, 40f), Quaternion.fromAxisAngle(Vec3.UnitY, 120f)),
            targetPose = front,
        )
        val current = first.sample(0.4f)

        val interrupted = first.interruptAt(0.4f, newTargetPose = back)

        assertThat(interrupted.startPose).isEqualTo(current)
        assertThat(interrupted.sample(0f)).isEqualTo(current)
    }
}

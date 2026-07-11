package com.mutsumi.card.study.physics

import com.mutsumi.card.study.CardSide
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

enum class GestureReleaseAction {
    ToggleSide,
    Know,
    Again,
}

data class GestureProjection(
    val pose: CardPose,
    val startSide: CardSide,
    val relativeTurnDegrees: Float,
    val verticalRollDegrees: Float,
    val neighborhoodProgress: Float,
    val horizontalCenterSuppression: Float,
    val releaseAction: GestureReleaseAction?,
)

class StudyGestureMapper {
    /**
     * 将整次拖动映射为相对 [startPose] 的唯一刚体姿态。
     *
     * [startSide] 只选择刚体上参与交互的表面法线，不修改 [startPose] 的绝对方向。
     * 因此已包含 180 度方向的背面姿态不会被重复翻转。手势旋转方向在起手时由该
     * 法线固定，随后即使经过 90 度也不会更换法线或欧拉角分支。
     */
    fun poseForDrag(
        delta: Vec2,
        startPose: CardPose,
        startSide: CardSide,
        viewport: Size2,
    ): GestureProjection {
        if (delta == Vec2.Zero) {
            return GestureProjection(
                pose = startPose,
                startSide = startSide,
                relativeTurnDegrees = 0f,
                verticalRollDegrees = 0f,
                neighborhoodProgress = 0f,
                horizontalCenterSuppression = 1f,
                releaseAction = null,
            )
        }

        val distance = delta.length
        val neighborhoodRadius = viewport.width * NEIGHBORHOOD_RADIUS_RATIO
        val outerTurnDistance = viewport.width * FULL_TURN_EXTRA_RATIO
        val neighborhoodProgress = (distance / neighborhoodRadius).coerceIn(0f, 1f)

        val horizontalTurn = componentAngle(
            componentDistance = abs(delta.x),
            neighborhoodRadius = neighborhoodRadius,
            outerTurnDistance = outerTurnDistance,
            maximumAngle = MAXIMUM_HORIZONTAL_TURN_DEGREES,
        )
        val verticalRoll = componentAngle(
            componentDistance = abs(delta.y),
            neighborhoodRadius = neighborhoodRadius,
            outerTurnDistance = outerTurnDistance,
            maximumAngle = MAXIMUM_VERTICAL_ROLL_DEGREES,
        )

        val startNormal = startPose.normalFor(startSide)
        val facingDirection = facingDirection(startNormal, startSide)
        val signedHorizontalTurn = horizontalTurn * delta.x.sign * facingDirection
        val signedVerticalRoll = -verticalRoll * delta.y.sign * facingDirection
        val worldOrientation = Quaternion.fromAxisAngle(Vec3.UnitX, signedVerticalRoll) *
            Quaternion.fromAxisAngle(Vec3.UnitY, signedHorizontalTurn)

        val verticalCommitDistance = viewport.height / VERTICAL_COMMIT_DIVISOR
        val verticalTravelRange = max(
            verticalCommitDistance - neighborhoodRadius,
            outerTurnDistance,
        )
        val upwardOuterProgress = if (delta.y < 0f) {
            (abs(delta.y) - neighborhoodRadius).coerceAtLeast(0f) / verticalTravelRange
        } else {
            0f
        }
        val horizontalCenterSuppression =
            1f - MAXIMUM_UPWARD_HORIZONTAL_SUPPRESSION * smootherStep(upwardOuterProgress)
        val verticalFollow = smootherStep(abs(delta.y) / verticalCommitDistance)
        val center = startPose.center + Vec2(
            x = delta.x * HORIZONTAL_CENTER_FOLLOW_RATIO * horizontalCenterSuppression,
            y = delta.y * verticalFollow,
        )

        val releaseAction = releaseAction(
            delta = delta,
            horizontalTurn = horizontalTurn,
            verticalCommitDistance = verticalCommitDistance,
        )

        return GestureProjection(
            pose = CardPose(
                center = center,
                orientation = (worldOrientation * startPose.orientation).normalized(),
            ),
            startSide = startSide,
            relativeTurnDegrees = horizontalTurn.coerceIn(0f, MAXIMUM_HORIZONTAL_TURN_DEGREES),
            verticalRollDegrees = verticalRoll.coerceAtMost(MAXIMUM_VERTICAL_ROLL_DEGREES),
            neighborhoodProgress = neighborhoodProgress,
            horizontalCenterSuppression = horizontalCenterSuppression,
            releaseAction = releaseAction,
        )
    }

    private fun componentAngle(
        componentDistance: Float,
        neighborhoodRadius: Float,
        outerTurnDistance: Float,
        maximumAngle: Float,
    ): Float {
        val linearAngle = INNER_TILT_DEGREES * componentDistance / neighborhoodRadius
        val effectiveOuterDistance = (componentDistance - neighborhoodRadius).coerceAtLeast(0f)
        val outerProgress = smootherStep(effectiveOuterDistance / outerTurnDistance)
        return lerp(linearAngle, maximumAngle, outerProgress).coerceIn(0f, maximumAngle)
    }

    private fun facingDirection(startNormal: Vec3, startSide: CardSide): Float = when {
        startNormal.z > NORMAL_DIRECTION_EPSILON -> 1f
        startNormal.z < -NORMAL_DIRECTION_EPSILON -> -1f
        startSide == CardSide.Front -> 1f
        else -> -1f
    }

    private fun releaseAction(
        delta: Vec2,
        horizontalTurn: Float,
        verticalCommitDistance: Float,
    ): GestureReleaseAction? = when {
        abs(delta.y) >= verticalCommitDistance && abs(delta.y) >= abs(delta.x) -> {
            if (delta.y < 0f) GestureReleaseAction.Know else GestureReleaseAction.Again
        }

        horizontalTurn >= TOGGLE_TURN_DEGREES && abs(delta.x) > abs(delta.y) -> {
            GestureReleaseAction.ToggleSide
        }

        else -> null
    }

    private companion object {
        const val NEIGHBORHOOD_RADIUS_RATIO = 0.25f
        const val FULL_TURN_EXTRA_RATIO = 0.10f
        const val VERTICAL_COMMIT_DIVISOR = 3f
        const val HORIZONTAL_CENTER_FOLLOW_RATIO = 0.075f
        const val MAXIMUM_UPWARD_HORIZONTAL_SUPPRESSION = 0.95f
        const val INNER_TILT_DEGREES = 12f
        const val MAXIMUM_HORIZONTAL_TURN_DEGREES = 180f
        const val MAXIMUM_VERTICAL_ROLL_DEGREES = 32f
        const val TOGGLE_TURN_DEGREES = 90f
        const val NORMAL_DIRECTION_EPSILON = 1e-4f
    }
}

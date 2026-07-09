package com.mutsumi.card.study

import com.mutsumi.card.domain.review.ReviewFeedback
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.sign

data class StudyTouchPoint(
    val x: Float,
    val y: Float,
)

data class StudyCardCenter(
    val x: Float,
    val y: Float,
)

data class StudyCardAngle(
    val axisRotationZ: Float,
    val deflection: Float,
)

data class StudyCardPhysicalState(
    val center: StudyCardCenter,
    val angle: StudyCardAngle,
)

enum class CardSide {
    Front,
    Back,
}

sealed class StudyReleaseAction {
    data object ToggleSide : StudyReleaseAction()
    data class Feedback(val feedback: ReviewFeedback) : StudyReleaseAction()
}

data class StudyGestureProjection(
    val physicalState: StudyCardPhysicalState,
    val flipProgress: Float,
    val releaseAction: StudyReleaseAction?,
)

fun studyCardPhysicsFromDrag(
    dx: Float,
    dy: Float,
    committedSide: CardSide,
    interactionStartDirection: CardSide = committedSide,
    screenWidth: Float,
): StudyCardPhysicalState {
    require(screenWidth > 0f) { "灞忓箷瀹藉害蹇呴』澶т簬 0" }

    val radius = screenWidth / 4f
    val actionBand = screenWidth / 10f
    val maxTilt = 12f
    val maxVerticalRoll = 32f
    val horizontalCenterRatio = 0.075f
    val distance = hypot(dx, dy).coerceAtLeast(0.0001f)
    val rho = distance / radius
    val innerTilt = smoothstep(0f, 1f, rho.coerceAtMost(1f))
    val centerMotion = radialMotionOutsideNeighborhood(distance, radius, actionBand)
    val actionRatio = ((distance - radius) / actionBand).coerceIn(0f, 1f)
    val rawDeflection = maxTilt * innerTilt + (180f - maxTilt) * smoothstep(0f, 1f, actionRatio)
    val horizontalWeight = (abs(dx) / distance).coerceIn(0f, 1f)
    val directionalLimit = maxVerticalRoll +
        (180f - maxVerticalRoll) * smoothstep(0.2f, 0.85f, horizontalWeight)
    val dragDeflection = rawDeflection.coerceAtMost(directionalLimit)
    val axisRotationZ = (atan2(dy, dx).toDegrees() + interactionStartDirection.axisOffset).normalizeDegrees()

    return StudyCardPhysicalState(
        center = StudyCardCenter(
            x = dx * centerMotion * horizontalCenterRatio,
            y = dy * centerMotion,
        ),
        angle = when (committedSide) {
            CardSide.Front -> StudyCardAngle(
                axisRotationZ = axisRotationZ,
                deflection = dragDeflection.coerceIn(0f, 180f),
            )
            CardSide.Back -> StudyCardAngle(
                axisRotationZ = axisRotationZ,
                deflection = (committedSide.baseDeflection - dragDeflection).coerceIn(0f, 180f),
            )
        },
    )
}

fun studyCardReturnEasing(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    val inverse = 1f - t
    return 1f - inverse * inverse * inverse
}

fun interpolateStudyCardPhysicalState(
    from: StudyCardPhysicalState,
    to: StudyCardPhysicalState,
    fraction: Float,
): StudyCardPhysicalState {
    val t = fraction.coerceIn(0f, 1f)
    val continuousTarget = to.withContinuousAxisFrom(from)
    return StudyCardPhysicalState(
        center = StudyCardCenter(
            x = lerp(from.center.x, continuousTarget.center.x, t),
            y = lerp(from.center.y, continuousTarget.center.y, t),
        ),
        angle = StudyCardAngle(
            axisRotationZ = lerpAngleDegrees(from.angle.axisRotationZ, continuousTarget.angle.axisRotationZ, t),
            deflection = lerp(from.angle.deflection, continuousTarget.angle.deflection, t).coerceIn(0f, 180f),
        ),
    )
}

class StudyGesturePolicy(
    private val screenWidth: Float,
    private val screenHeight: Float,
    private val cardWidth: Float,
    private val cardHeight: Float,
) {
    init {
        require(screenWidth > 0f) { "屏幕宽度必须大于 0" }
        require(screenHeight > 0f) { "屏幕高度必须大于 0" }
        require(cardWidth > 0f) { "卡片宽度必须大于 0" }
        require(cardHeight > 0f) { "卡片高度必须大于 0" }
    }

    private val radius: Float = screenWidth / 4f
    private val flipBand: Float = screenWidth / 10f
    private val verticalCommit: Float = screenHeight / 3f

    fun resting(committedSide: CardSide): StudyGestureProjection {
        return StudyGestureProjection(
            physicalState = StudyCardPhysicalState(
                center = StudyCardCenter(x = 0f, y = 0f),
                angle = StudyCardAngle(axisRotationZ = 0f, deflection = committedSide.baseDeflection),
            ),
            flipProgress = 0f,
            releaseAction = null,
        )
    }

    fun project(
        anchor: StudyTouchPoint,
        current: StudyTouchPoint,
        committedSide: CardSide,
        interactionStartDirection: CardSide = committedSide,
    ): StudyGestureProjection {
        val rawDx = current.x - anchor.x
        val dy = current.y - anchor.y
        val distance = hypot(rawDx, dy).coerceAtLeast(0.0001f)
        val unitX = rawDx / distance
        val unitY = dy / distance

        val rightIntent = smoothstep(0.55f, 0.85f, unitX)
        val leftIntent = smoothstep(0.55f, 0.85f, -unitX)
        val upIntent = smoothstep(0.55f, 0.85f, -unitY)
        val downIntent = smoothstep(0.55f, 0.85f, unitY)
        val intentSum = rightIntent + leftIntent + upIntent + downIntent
        val rightWeight = if (intentSum > 0f) rightIntent / intentSum else 0f
        val leftWeight = if (intentSum > 0f) leftIntent / intentSum else 0f
        val upWeight = if (intentSum > 0f) upIntent / intentSum else 0f
        val downWeight = if (intentSum > 0f) downIntent / intentSum else 0f
        val horizontalWeight = leftWeight + rightWeight

        val physicalState = studyCardPhysicsFromDrag(
            dx = rawDx,
            dy = dy,
            committedSide = committedSide,
            interactionStartDirection = interactionStartDirection,
            screenWidth = screenWidth,
        )
        val flipProgress = horizontalFlipProgress(rawDx, radius, flipBand)

        return StudyGestureProjection(
            physicalState = physicalState,
            flipProgress = flipProgress,
            releaseAction = releaseAction(
                dy = dy,
                upWeight = upWeight,
                downWeight = downWeight,
                horizontalWeight = horizontalWeight,
                flipProgress = flipProgress,
            ),
        )
    }

    private fun releaseAction(
        dy: Float,
        upWeight: Float,
        downWeight: Float,
        horizontalWeight: Float,
        flipProgress: Float,
    ): StudyReleaseAction? {
        return when {
            dy <= -verticalCommit && upWeight > downWeight && upWeight > horizontalWeight ->
                StudyReleaseAction.Feedback(ReviewFeedback.Know)
            dy >= verticalCommit && downWeight > upWeight && downWeight > horizontalWeight ->
                StudyReleaseAction.Feedback(ReviewFeedback.Again)
            abs(flipProgress) >= 0.5f ->
                StudyReleaseAction.ToggleSide
            else -> null
        }
    }

}

fun StudyCardAngle.isStudyCardBackFacing(): Boolean {
    val normalZ = cos(deflection.toRadians())
    return normalZ < 0.0
}

private fun horizontalFlipProgress(dx: Float, radius: Float, flipBand: Float): Float {
    val overshoot = (abs(dx) - radius).coerceAtLeast(0f)
    val rawFlip = (overshoot / flipBand).coerceIn(0f, 1f)
    return sign(dx) * smoothstep(0f, 1f, rawFlip)
}

private fun radialMotionOutsideNeighborhood(distance: Float, radius: Float, actionBand: Float): Float {
    val t = ((distance - radius) / actionBand).coerceIn(0f, 1f)
    return smoothstep(0f, 1f, t)
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun StudyCardPhysicalState.withContinuousAxisFrom(
    start: StudyCardPhysicalState,
): StudyCardPhysicalState {
    if (!angle.deflection.isDegenerateFace()) {
        return this
    }
    return copy(angle = angle.copy(axisRotationZ = start.angle.axisRotationZ))
}

private fun Float.isDegenerateFace(): Boolean {
    return this <= 0.001f || this >= 179.999f
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun lerpAngleDegrees(start: Float, stop: Float, fraction: Float): Float {
    return start + shortestAngleDeltaDegrees(start, stop) * fraction
}

private fun shortestAngleDeltaDegrees(start: Float, stop: Float): Float {
    var delta = (stop - start) % 360f
    if (delta <= -180f) {
        delta += 360f
    }
    if (delta > 180f) {
        delta -= 360f
    }
    return delta
}

private fun Float.toRadians(): Double = this / 180.0 * PI

private fun Float.toDegrees(): Float = (this * 180f / PI.toFloat())

private fun Float.normalizeDegrees(): Float {
    var value = this % 360f
    if (value <= -180f) {
        value += 360f
    }
    if (value > 180f) {
        value -= 360f
    }
    return value
}

private val CardSide.baseDeflection: Float
    get() = when (this) {
        CardSide.Front -> 0f
        CardSide.Back -> 180f
    }

private val CardSide.axisOffset: Float
    get() = when (this) {
        CardSide.Front -> 0f
        CardSide.Back -> 180f
    }

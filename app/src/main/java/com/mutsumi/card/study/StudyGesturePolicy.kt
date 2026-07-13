package com.mutsumi.card.study

import com.mutsumi.card.domain.review.ReviewFeedback
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign

data class StudyTouchPoint(val x: Float, val y: Float)
data class StudyCardCenter(val x: Float, val y: Float)
data class StudyCardAngle(val axisRotationZ: Float, val deflection: Float)
data class StudyCardPhysicalState(val center: StudyCardCenter, val angle: StudyCardAngle)

enum class CardSide { Front, Back }
enum class StudyDragMode { VerticalFeedback, HorizontalFlip }

sealed class StudyReleaseAction {
    data object ToggleSide : StudyReleaseAction()
    data class Feedback(val feedback: ReviewFeedback) : StudyReleaseAction()
}

data class StudyGestureProjection(
    val physicalState: StudyCardPhysicalState,
    val flipProgress: Float,
    val releaseAction: StudyReleaseAction? = null,
    val mode: StudyDragMode? = null,
    val lockedAxisRotationZ: Float? = null,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val releaseVelocityX: Float = 0f,
)

fun studyCardPhysicsFromDrag(
    dx: Float,
    dy: Float,
    committedSide: CardSide,
    interactionStartDirection: CardSide = committedSide,
    screenWidth: Float,
    lockedMode: StudyDragMode? = null,
    lockedAxisRotationZ: Float? = null,
): StudyCardPhysicalState {
    require(screenWidth > 0f) { "屏幕宽度必须大于 0" }
    val radius = screenWidth / 5f
    val actionBand = screenWidth / 10f
    val distance = hypot(dx, dy)
    if (distance <= 0.0001f) {
        return StudyCardPhysicalState(
            StudyCardCenter(0f, 0f),
            StudyCardAngle(0f, committedSide.baseDeflection),
        )
    }

    val mode = lockedMode ?: if (distance > radius) classifyMode(dx, dy) else null
    val centerMotion = radialMotionOutsideNeighborhood(distance, radius, actionBand)
    val center = StudyCardCenter(
        x = dx * centerMotion * HORIZONTAL_CENTER_RATIO,
        y = dy * centerMotion,
    )

    val interactionAxis = interactionAxisFromDrag(dx, dy, interactionStartDirection)
    if (mode == StudyDragMode.VerticalFeedback) {
        return StudyCardPhysicalState(
            center = center,
            angle = StudyCardAngle(
                axisRotationZ = lockedAxisRotationZ ?: interactionAxis,
                deflection = committedSide.applyRelativeDeflection(VERTICAL_FIXED_TILT),
            ),
        )
    }

    val relativeDeflection = if (mode == StudyDragMode.HorizontalFlip) {
        val progress = ((abs(dx) - radius) / actionBand).coerceIn(0f, 1f)
        INNER_MAX_TILT + (180f - INNER_MAX_TILT) * smoothstep(0f, 1f, progress)
    } else {
        INNER_MAX_TILT * smoothstep(0f, 1f, distance / radius)
    }
    return StudyCardPhysicalState(
        center = center,
        angle = StudyCardAngle(
            axisRotationZ = interactionAxis,
            deflection = committedSide.applyRelativeDeflection(relativeDeflection),
        ),
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
    private val radius = screenWidth / 5f
    private val flipBand = screenWidth / 10f
    private val verticalCommit = screenHeight * 0.28f

    init {
        require(screenWidth > 0f && screenHeight > 0f) { "屏幕尺寸必须大于 0" }
        require(cardWidth > 0f && cardHeight > 0f) { "卡片尺寸必须大于 0" }
    }

    fun resting(committedSide: CardSide) = StudyGestureProjection(
        physicalState = StudyCardPhysicalState(
            StudyCardCenter(0f, 0f),
            StudyCardAngle(0f, committedSide.baseDeflection),
        ),
        flipProgress = 0f,
    )

    fun project(
        anchor: StudyTouchPoint,
        current: StudyTouchPoint,
        committedSide: CardSide,
        interactionStartDirection: CardSide = committedSide,
        lockedMode: StudyDragMode? = null,
        lockedAxisRotationZ: Float? = null,
    ): StudyGestureProjection {
        val dx = current.x - anchor.x
        val dy = current.y - anchor.y
        val distance = hypot(dx, dy)
        val mode = lockedMode ?: if (distance > radius) classifyMode(dx, dy) else null
        val verticalAxis = lockedAxisRotationZ ?: if (
            mode == StudyDragMode.VerticalFeedback && lockedMode == null
        ) interactionAxisFromDrag(dx, dy, interactionStartDirection) else null
        val flipProgress = horizontalFlipProgress(dx, radius, flipBand)
        return StudyGestureProjection(
            physicalState = studyCardPhysicsFromDrag(
                dx = dx,
                dy = dy,
                committedSide = committedSide,
                interactionStartDirection = interactionStartDirection,
                screenWidth = screenWidth,
                lockedMode = mode,
                lockedAxisRotationZ = verticalAxis,
            ),
            flipProgress = flipProgress,
            mode = mode,
            lockedAxisRotationZ = verticalAxis,
            dx = dx,
            dy = dy,
        )
    }

    fun release(
        projection: StudyGestureProjection,
        velocityX: Float,
        velocityY: Float,
        minimumFlingVelocity: Float,
        minimumHorizontalFlingVelocity: Float = minimumFlingVelocity,
    ): StudyReleaseAction? {
        if (projection.mode == StudyDragMode.VerticalFeedback) {
            val effectiveVelocity = if (abs(velocityY) >= minimumFlingVelocity) velocityY else 0f
            val projectedDy = projection.dy + effectiveVelocity * VELOCITY_PROJECTION_SECONDS
            if (projectedDy <= -verticalCommit) return StudyReleaseAction.Feedback(ReviewFeedback.Know)
            if (projectedDy >= verticalCommit) return StudyReleaseAction.Feedback(ReviewFeedback.Again)
            return null
        }
        if (projection.mode != StudyDragMode.HorizontalFlip) return null
        val effectiveVelocityX = if (abs(velocityX) >= minimumHorizontalFlingVelocity) velocityX else 0f
        val projectedDx = projection.dx + effectiveVelocityX * HORIZONTAL_VELOCITY_PROJECTION_SECONDS
        if (projectedDx * projection.dx <= 0f) return null
        val projectedProgress = horizontalFlipProgress(projectedDx, radius, flipBand)
        return if (abs(projectedProgress) >= 0.5f) StudyReleaseAction.ToggleSide else null
    }

    fun horizontalAnimationProgressVelocity(projection: StudyGestureProjection): Float {
        if (projection.mode != StudyDragMode.HorizontalFlip || projection.dx == 0f) return 0f
        val velocityTowardTarget = projection.releaseVelocityX * sign(projection.dx)
        return (velocityTowardTarget.coerceAtLeast(0f) / flipBand)
            .coerceAtMost(MAX_HORIZONTAL_PROGRESS_VELOCITY)
    }

    fun exitTarget(
        projection: StudyGestureProjection,
        feedback: ReviewFeedback,
    ): StudyCardPhysicalState = projection.physicalState.copy(
        center = projection.physicalState.center.copy(
            y = if (feedback == ReviewFeedback.Know) {
                -(screenHeight + cardHeight)
            } else {
                screenHeight + cardHeight
            },
        ),
    )
}

fun StudyCardAngle.isStudyCardBackFacing(): Boolean = cos(deflection.toRadians()) < 0.0

private fun classifyMode(dx: Float, dy: Float): StudyDragMode =
    if (abs(dy) >= abs(dx)) StudyDragMode.VerticalFeedback else StudyDragMode.HorizontalFlip

private fun interactionAxisFromDrag(dx: Float, dy: Float, interactionStartDirection: CardSide): Float =
    (atan2(dy, dx).toDegrees() + interactionStartDirection.axisOffset).normalizeDegrees()

private fun CardSide.applyRelativeDeflection(value: Float): Float = when (this) {
    CardSide.Front -> value.coerceIn(0f, 180f)
    CardSide.Back -> (180f - value).coerceIn(0f, 180f)
}

private fun horizontalFlipProgress(dx: Float, radius: Float, flipBand: Float): Float {
    val overshoot = (abs(dx) - radius).coerceAtLeast(0f)
    return sign(dx) * smoothstep(0f, 1f, (overshoot / flipBand).coerceIn(0f, 1f))
}

private fun radialMotionOutsideNeighborhood(distance: Float, radius: Float, actionBand: Float): Float =
    smoothstep(0f, 1f, ((distance - radius) / actionBand).coerceIn(0f, 1f))

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun StudyCardPhysicalState.withContinuousAxisFrom(start: StudyCardPhysicalState): StudyCardPhysicalState =
    if (angle.deflection <= 0.001f || angle.deflection >= 179.999f) {
        copy(angle = angle.copy(axisRotationZ = start.angle.axisRotationZ))
    } else this

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

private fun lerpAngleDegrees(start: Float, stop: Float, fraction: Float) =
    start + shortestAngleDeltaDegrees(start, stop) * fraction

private fun shortestAngleDeltaDegrees(start: Float, stop: Float): Float {
    var delta = (stop - start) % 360f
    if (delta <= -180f) delta += 360f
    if (delta > 180f) delta -= 360f
    return delta
}

private fun Float.toRadians(): Double = this / 180.0 * PI
private fun Float.toDegrees(): Float = this * 180f / PI.toFloat()
private fun Float.normalizeDegrees(): Float {
    var value = this % 360f
    if (value <= -180f) value += 360f
    if (value > 180f) value -= 360f
    return value
}

private val CardSide.baseDeflection: Float
    get() = if (this == CardSide.Front) 0f else 180f

private val CardSide.axisOffset: Float
    get() = if (this == CardSide.Front) 0f else 180f

private const val INNER_MAX_TILT = 8f
private const val VERTICAL_FIXED_TILT = 7f
private const val HORIZONTAL_CENTER_RATIO = 0.075f
private const val VELOCITY_PROJECTION_SECONDS = 0.14f
private const val HORIZONTAL_VELOCITY_PROJECTION_SECONDS = 0.12f
private const val MAX_HORIZONTAL_PROGRESS_VELOCITY = 8f

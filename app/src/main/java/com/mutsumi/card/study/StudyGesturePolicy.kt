package com.mutsumi.card.study

import com.mutsumi.card.domain.review.ReviewFeedback
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

data class StudyTouchPoint(
    val x: Float,
    val y: Float,
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
    val rotationX: Float,
    val rotationY: Float,
    val translationX: Float,
    val translationY: Float,
    val cardAlpha: Float,
    val frontAlpha: Float,
    val backAlpha: Float,
    val showingBack: Boolean,
    val flipProgress: Float,
    val releaseAction: StudyReleaseAction?,
)

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
    private val maxTilt = 12f

    fun resting(committedSide: CardSide): StudyGestureProjection {
        val rotationY = committedSide.baseAngle
        val faceAlpha = faceAlpha(rotationY)
        return StudyGestureProjection(
            rotationX = 0f,
            rotationY = rotationY,
            translationX = 0f,
            translationY = 0f,
            cardAlpha = 1f,
            frontAlpha = faceAlpha.front,
            backAlpha = faceAlpha.back,
            showingBack = committedSide == CardSide.Back,
            flipProgress = 0f,
            releaseAction = null,
        )
    }

    fun project(
        anchor: StudyTouchPoint,
        current: StudyTouchPoint,
        committedSide: CardSide,
    ): StudyGestureProjection {
        val rawDx = current.x - anchor.x
        val dy = current.y - anchor.y
        val distance = hypot(rawDx, dy).coerceAtLeast(0.0001f)
        val rho = distance / radius
        val outer = outerMotion(rho)
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
        val verticalWeight = upWeight + downWeight
        val horizontalWeight = leftWeight + rightWeight

        val upSuppression = horizontalSuppressionForUp(upWeight = upWeight, outer = outer)
        val effectiveDx = rawDx * upSuppression
        val rotationDelta = horizontalRotationDelta(effectiveDx)
        val flipProgress = flipProgress(effectiveDx)

        val verticalAction = outer * verticalWeight
        val translationX = 0f
        val translationY = dy * verticalAction

        val inner = smoothstep(0f, 1f, rho.coerceAtMost(1f))
        val tiltX = -unitY * maxTilt * inner

        val visualAngle = committedSide.baseAngle + rotationDelta
        val showingBack = visualAngle.isBackFacing()
        val faceAlpha = faceAlpha(visualAngle)

        return StudyGestureProjection(
            rotationX = tiltX,
            rotationY = visualAngle,
            translationX = translationX,
            translationY = translationY,
            cardAlpha = 1f,
            frontAlpha = faceAlpha.front,
            backAlpha = faceAlpha.back,
            showingBack = showingBack,
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

    private fun flipProgress(effectiveDx: Float): Float {
        val overshoot = (abs(effectiveDx) - radius).coerceAtLeast(0f)
        val rawFlip = (overshoot / flipBand).coerceIn(0f, 1f)
        return sign(effectiveDx) * smoothstep(0f, 1f, rawFlip)
    }

    private fun horizontalRotationDelta(effectiveDx: Float): Float {
        val direction = sign(effectiveDx)
        if (direction == 0f) return 0f

        val absoluteDx = abs(effectiveDx)
        val innerRatio = (absoluteDx / radius).coerceIn(0f, 1f)
        val tiltAngle = maxTilt * smoothstep(0f, 1f, innerRatio)
        val flipRatio = ((absoluteDx - radius) / flipBand).coerceIn(0f, 1f)
        val flipAngle = (180f - maxTilt) * smoothstep(0f, 1f, flipRatio)

        return direction * (tiltAngle + flipAngle).coerceAtMost(180f)
    }

    private fun horizontalSuppressionForUp(upWeight: Float, outer: Float): Float {
        val upDominance = upWeight * outer
        return 1f - 0.98f * smoothstep(0.15f, 0.85f, upDominance)
    }

    private fun outerMotion(rho: Float): Float {
        val t = (rho - 1f).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun Float.isBackFacing(): Boolean {
        val normalized = ((this % 360f) + 360f) % 360f
        return normalized in 90f..270f
    }

    private fun faceAlpha(angle: Float): FaceAlpha {
        return if (angle.isBackFacing()) {
            FaceAlpha(front = 0f, back = 1f)
        } else {
            FaceAlpha(front = 1f, back = 0f)
        }
    }

    private data class FaceAlpha(
        val front: Float,
        val back: Float,
    )

    private val CardSide.baseAngle: Float
        get() = when (this) {
            CardSide.Front -> 0f
            CardSide.Back -> 180f
        }
}

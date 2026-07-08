package com.mutsumi.card.study

import com.mutsumi.card.domain.review.ReviewFeedback
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
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
        val outer = outerEase(rho)
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
        val flipProgress = flipProgress(effectiveDx)
        val signedFlipAngle = sign(effectiveDx).takeIf { it != 0f }
            ?.let { it * 180f * abs(flipProgress).coerceAtMost(1f) }
            ?: 0f

        val verticalAction = outer * verticalWeight
        val horizontalAction = outer * horizontalWeight
        val translationX = if (abs(flipProgress) > 0f) 0f else effectiveDx * horizontalAction * 0.12f
        val translationY = dy * verticalAction

        val actionSuppression = smoothstep(0f, 0.3f, outer) * max(horizontalWeight, verticalWeight)
        val tiltWeight = (1f - actionSuppression).coerceIn(0f, 1f)
        val inner = smoothstep(0f, 1f, rho.coerceAtMost(1f))
        val tiltX = -unitY * maxTilt * inner * tiltWeight
        val tiltY = unitX * maxTilt * inner * tiltWeight

        val visualAngle = committedSide.baseAngle + signedFlipAngle
        val showingBack = visualAngle.isBackFacing()
        val normalSign = if (showingBack) -1f else 1f
        val rotationY = visualAngle + tiltY * normalSign
        val faceAlpha = faceAlpha(visualAngle)

        return StudyGestureProjection(
            rotationX = tiltX,
            rotationY = rotationY,
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

    private fun horizontalSuppressionForUp(upWeight: Float, outer: Float): Float {
        val upDominance = upWeight * outer
        return 1f - 0.95f * smoothstep(0.2f, 0.8f, upDominance)
    }

    private fun outerEase(rho: Float): Float {
        val x = (rho - 1f).coerceAtLeast(0f)
        return 1f - exp(-3f * x)
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
        val normalized = ((angle % 360f) + 360f) % 360f
        val frontToBack = smoothstep(88f, 92f, normalized)
        val backToFront = smoothstep(268f, 272f, normalized)
        val front = when {
            normalized < 88f -> 1f
            normalized <= 92f -> 1f - frontToBack
            normalized < 268f -> 0f
            normalized <= 272f -> backToFront
            else -> 1f
        }
        val back = when {
            normalized < 88f -> 0f
            normalized <= 92f -> frontToBack
            normalized < 268f -> 1f
            normalized <= 272f -> 1f - backToFront
            else -> 0f
        }
        return FaceAlpha(front = front, back = back)
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

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
    val alpha: Float,
    val showingBack: Boolean,
    val flipProgress: Float,
    val releaseAction: StudyReleaseAction?,
)

class StudyGesturePolicy(
    private val screenWidth: Float,
    private val cardWidth: Float,
    private val cardHeight: Float,
) {
    init {
        require(screenWidth > 0f) { "屏幕宽度必须大于 0" }
        require(cardWidth > 0f) { "卡片宽度必须大于 0" }
        require(cardHeight > 0f) { "卡片高度必须大于 0" }
    }

    private val radius: Float = screenWidth / 4f
    private val flipBand: Float = screenWidth / 10f
    private val maxTilt = 12f

    fun resting(committedSide: CardSide): StudyGestureProjection {
        val rotationY = committedSide.baseAngle
        return StudyGestureProjection(
            rotationX = 0f,
            rotationY = rotationY,
            translationX = 0f,
            translationY = 0f,
            alpha = 1f,
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
        val verticalIntent = max(upIntent, downIntent)
        val horizontalIntent = max(leftIntent, rightIntent)

        val upSuppression = horizontalSuppressionForUp(unitY = unitY, outer = outer)
        val effectiveDx = rawDx * upSuppression
        val flipProgress = flipProgress(effectiveDx)
        val signedFlipAngle = sign(effectiveDx).takeIf { it != 0f }?.let { it * 180f * abs(flipProgress) } ?: 0f

        val verticalAction = outer * verticalIntent
        val horizontalAction = outer * horizontalIntent
        val translationX = if (abs(flipProgress) > 0f) 0f else effectiveDx * horizontalAction * 0.12f
        val translationY = dy * verticalAction
        val alpha = (1f - 0.65f * verticalAction).coerceIn(0.25f, 1f)

        val actionSuppression = smoothstep(0f, 0.3f, outer) * max(horizontalIntent, verticalIntent)
        val tiltWeight = (1f - actionSuppression).coerceIn(0f, 1f)
        val inner = smoothstep(0f, 1f, rho.coerceAtMost(1f))
        val tiltX = -unitY * maxTilt * inner * tiltWeight
        val tiltY = unitX * maxTilt * inner * tiltWeight

        val visualAngle = committedSide.baseAngle + signedFlipAngle
        val showingBack = visualAngle.isBackFacing()
        val normalSign = if (showingBack) -1f else 1f
        val rotationY = visualAngle + tiltY * normalSign

        return StudyGestureProjection(
            rotationX = tiltX,
            rotationY = rotationY,
            translationX = translationX,
            translationY = translationY,
            alpha = alpha,
            showingBack = showingBack,
            flipProgress = flipProgress,
            releaseAction = releaseAction(
                verticalAction = verticalAction,
                upIntent = upIntent,
                downIntent = downIntent,
                horizontalIntent = horizontalIntent,
                flipProgress = flipProgress,
            ),
        )
    }

    private fun releaseAction(
        verticalAction: Float,
        upIntent: Float,
        downIntent: Float,
        horizontalIntent: Float,
        flipProgress: Float,
    ): StudyReleaseAction? {
        return when {
            verticalAction >= 0.35f && upIntent > downIntent && upIntent > horizontalIntent ->
                StudyReleaseAction.Feedback(ReviewFeedback.Know)
            verticalAction >= 0.35f && downIntent > upIntent && downIntent > horizontalIntent ->
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

    private fun horizontalSuppressionForUp(unitY: Float, outer: Float): Float {
        val upDirection = smoothstep(0.25f, 0.85f, -unitY)
        val outerGate = smoothstep(0f, 0.75f, outer)
        return 1f - 0.95f * upDirection * outerGate
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

    private val CardSide.baseAngle: Float
        get() = when (this) {
            CardSide.Front -> 0f
            CardSide.Back -> 180f
        }
}

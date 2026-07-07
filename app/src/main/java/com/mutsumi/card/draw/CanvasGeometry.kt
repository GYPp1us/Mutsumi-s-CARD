package com.mutsumi.card.draw

import kotlin.math.min

object CardCanvasSpec {
    const val logicalWidth = 1200f
    const val logicalHeight = 600f
    const val exportWidth = 1200
    const val exportHeight = 600
    const val aspectRatio = logicalWidth / logicalHeight
}

data class CanvasRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

data class CanvasCamera(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
) {
    val visibleWidth: Float = viewportWidth / scale
    val visibleHeight: Float = viewportHeight / scale

    fun clamp(): CanvasCamera {
        val clampedScale = scale.coerceIn(0.5f, 6f)
        val clampedVisibleWidth = viewportWidth / clampedScale
        val clampedVisibleHeight = viewportHeight / clampedScale
        val maxOffsetX = (CardCanvasSpec.logicalWidth - clampedVisibleWidth).coerceAtLeast(0f)
        val maxOffsetY = (CardCanvasSpec.logicalHeight - clampedVisibleHeight).coerceAtLeast(0f)
        return copy(
            scale = clampedScale,
            offsetX = offsetX.coerceIn(0f, maxOffsetX),
            offsetY = offsetY.coerceIn(0f, maxOffsetY),
        )
    }

    companion object {
        fun initial(viewportWidth: Float, viewportHeight: Float): CanvasCamera {
            require(viewportWidth > 0f && viewportHeight > 0f) { "视口尺寸必须大于 0" }
            val scale = min(viewportWidth / 420f, viewportHeight / 210f).coerceAtLeast(0.5f)
            return CanvasCamera(
                scale = scale,
                offsetX = 0f,
                offsetY = 0f,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            ).clamp()
        }
    }
}

fun fitCenterRect(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): CanvasRect {
    require(sourceWidth > 0 && sourceHeight > 0) { "源图片尺寸必须大于 0" }
    require(targetWidth > 0 && targetHeight > 0) { "目标画布尺寸必须大于 0" }
    val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    return CanvasRect(
        left = (targetWidth - width) / 2f,
        top = (targetHeight - height) / 2f,
        width = width,
        height = height,
    )
}

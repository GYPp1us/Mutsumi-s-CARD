package com.mutsumi.card.draw

import kotlin.math.min

object DrawingCanvasSpec {
    const val width = 1024
    const val height = 1624
    const val aspectRatio = 53.98f / 85.60f
    const val initialVisibleFraction = 0.35f
    const val maximumZoom = 6f
}

/** 任务 6 重写旧界面前保留的兼容名称。 */
object CardCanvasSpec {
    const val logicalWidth = DrawingCanvasSpec.width.toFloat()
    const val logicalHeight = DrawingCanvasSpec.height.toFloat()
    const val exportWidth = DrawingCanvasSpec.width
    const val exportHeight = DrawingCanvasSpec.height
    const val aspectRatio = DrawingCanvasSpec.aspectRatio
}

data class CanvasRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(left.isFinite() && top.isFinite()) { "矩形位置必须是有限数值" }
        require(width.isFinite() && height.isFinite() && width >= 0f && height >= 0f) {
            "矩形尺寸不能小于 0"
        }
    }

    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

class CanvasCamera private constructor(
    val zoom: Float,
    val centerX: Float,
    val centerY: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    @Suppress("UNUSED_PARAMETER") normalized: Unit,
) {
    constructor(
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) : this(
        zoom = scale / fitScale(viewportWidth, viewportHeight),
        centerX = offsetX + viewportWidth / scale / 2f,
        centerY = offsetY + viewportHeight / scale / 2f,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        normalized = Unit,
    )

    init {
        require(zoom.isFinite() && zoom > 0f) { "相机缩放必须大于 0" }
        require(centerX.isFinite() && centerY.isFinite()) { "相机中心必须是有限数值" }
        require(viewportWidth.isFinite() && viewportHeight.isFinite()) { "视口尺寸必须是有限数值" }
        require(viewportWidth > 0f && viewportHeight > 0f) { "视口尺寸必须大于 0" }
    }

    val fitScale: Float get() = fitScale(viewportWidth, viewportHeight)
    val scale: Float get() = fitScale * zoom
    val visibleFraction: Float get() = 1f / zoom
    val visibleWidth: Float get() = viewportWidth / scale
    val visibleHeight: Float get() = viewportHeight / scale
    val offsetX: Float get() = centerX - visibleWidth / 2f
    val offsetY: Float get() = centerY - visibleHeight / 2f

    fun clamp(): CanvasCamera {
        val clampedZoom = zoom.coerceIn(1f, DrawingCanvasSpec.maximumZoom)
        val visibleWidth = viewportWidth / (fitScale * clampedZoom)
        val visibleHeight = viewportHeight / (fitScale * clampedZoom)
        return CanvasCamera(
            zoom = clampedZoom,
            centerX = clampCenter(centerX, visibleWidth, DrawingCanvasSpec.width.toFloat()),
            centerY = clampCenter(centerY, visibleHeight, DrawingCanvasSpec.height.toFloat()),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            normalized = Unit,
        )
    }

    fun withViewport(width: Float, height: Float): CanvasCamera = CanvasCamera(
        zoom = zoom,
        centerX = centerX,
        centerY = centerY,
        viewportWidth = width,
        viewportHeight = height,
        normalized = Unit,
    ).clamp()

    fun withZoom(value: Float): CanvasCamera = CanvasCamera(
        zoom = value,
        centerX = centerX,
        centerY = centerY,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        normalized = Unit,
    ).clamp()

    /** 以当前帧双指质心为不动点，同时应用平移和缩放。 */
    fun transform(centroidX: Float, centroidY: Float, panX: Float, panY: Float, zoomFactor: Float): CanvasCamera {
        require(centroidX.isFinite() && centroidY.isFinite()) { "缩放质心必须是有限数值" }
        require(panX.isFinite() && panY.isFinite()) { "平移量必须是有限数值" }
        require(zoomFactor.isFinite() && zoomFactor > 0f) { "缩放倍率必须大于 0" }
        val anchorX = offsetX + centroidX / scale
        val anchorY = offsetY + centroidY / scale
        val nextZoom = (zoom * zoomFactor).coerceIn(1f, DrawingCanvasSpec.maximumZoom)
        val nextScale = fitScale * nextZoom
        return CanvasCamera(
            scale = nextScale,
            offsetX = anchorX - (centroidX + panX) / nextScale,
            offsetY = anchorY - (centroidY + panY) / nextScale,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        ).clamp()
    }

    /** 兼容任务 6 重写前旧画布对绝对 scale/offset 的更新调用。 */
    fun copy(
        scale: Float? = null,
        offsetX: Float? = null,
        offsetY: Float? = null,
        viewportWidth: Float = this.viewportWidth,
        viewportHeight: Float = this.viewportHeight,
    ): CanvasCamera {
        val targetFitScale = fitScale(viewportWidth, viewportHeight)
        val targetZoom = scale?.div(targetFitScale) ?: zoom
        val targetScale = targetFitScale * targetZoom
        return CanvasCamera(
            zoom = targetZoom,
            centerX = offsetX?.plus(viewportWidth / targetScale / 2f) ?: centerX,
            centerY = offsetY?.plus(viewportHeight / targetScale / 2f) ?: centerY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            normalized = Unit,
        )
    }

    override fun equals(other: Any?): Boolean = other is CanvasCamera &&
        zoom == other.zoom &&
        centerX == other.centerX &&
        centerY == other.centerY &&
        viewportWidth == other.viewportWidth &&
        viewportHeight == other.viewportHeight

    override fun hashCode(): Int {
        var result = zoom.hashCode()
        result = 31 * result + centerX.hashCode()
        result = 31 * result + centerY.hashCode()
        result = 31 * result + viewportWidth.hashCode()
        return 31 * result + viewportHeight.hashCode()
    }

    override fun toString(): String =
        "CanvasCamera(zoom=$zoom, centerX=$centerX, centerY=$centerY, " +
            "viewportWidth=$viewportWidth, viewportHeight=$viewportHeight)"

    companion object {
        fun initial(viewportWidth: Float, viewportHeight: Float): CanvasCamera = CanvasCamera(
            zoom = 1f / DrawingCanvasSpec.initialVisibleFraction,
            centerX = DrawingCanvasSpec.width / 2f,
            centerY = DrawingCanvasSpec.height / 2f,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            normalized = Unit,
        ).clamp()
    }
}

private fun fitScale(viewportWidth: Float, viewportHeight: Float): Float {
    require(viewportWidth > 0f && viewportHeight > 0f) { "视口尺寸必须大于 0" }
    return min(
        viewportWidth / DrawingCanvasSpec.width,
        viewportHeight / DrawingCanvasSpec.height,
    )
}

private fun clampCenter(center: Float, visibleSize: Float, canvasSize: Float): Float =
    if (visibleSize >= canvasSize) {
        canvasSize / 2f
    } else {
        center.coerceIn(visibleSize / 2f, canvasSize - visibleSize / 2f)
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

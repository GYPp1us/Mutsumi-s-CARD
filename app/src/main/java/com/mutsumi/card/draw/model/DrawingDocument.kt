package com.mutsumi.card.draw.model

import kotlin.math.hypot

data class CanvasPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "画布坐标必须是有限数值" }
    }
}

data class DrawingStroke(
    val points: List<CanvasPoint>,
    val colorArgb: Int,
    val width: Float,
) {
    init {
        require(points.isNotEmpty()) { "笔迹至少需要一个点" }
        require(width.isFinite() && width > 0f) { "笔刷宽度必须大于 0" }
    }

    fun intersects(point: CanvasPoint, radius: Float): Boolean {
        require(radius.isFinite() && radius >= 0f) { "橡皮半径不能小于 0" }
        val hitDistance = radius + width / 2f
        if (points.size == 1) return distance(points.single(), point) <= hitDistance
        return points.zipWithNext().any { (start, end) ->
            distanceToSegment(point = point, start = start, end = end) <= hitDistance
        }
    }
}

data class DrawingDocument(
    val strokes: List<DrawingStroke> = emptyList(),
    val baseImage: ByteArray? = null,
) {
    val hasContent: Boolean
        get() = baseImage != null || strokes.isNotEmpty()

    fun appendStroke(stroke: DrawingStroke): DrawingDocument = copy(strokes = strokes + stroke)

    fun eraseStrokesAt(point: CanvasPoint, radius: Float): DrawingDocument = copy(
        strokes = strokes.filterNot { it.intersects(point, radius) },
    )
}

private fun distance(first: CanvasPoint, second: CanvasPoint): Float =
    hypot(first.x - second.x, first.y - second.y)

private fun distanceToSegment(
    point: CanvasPoint,
    start: CanvasPoint,
    end: CanvasPoint,
): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return distance(point, start)
    val projection = (
        ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared
        ).coerceIn(0f, 1f)
    return hypot(
        point.x - (start.x + projection * dx),
        point.y - (start.y + projection * dy),
    )
}

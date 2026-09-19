package com.mutsumi.card.draw

/** 世界坐标内只变换底图，保留宽高比，不改变笔迹与相机。 */
internal fun CanvasRect.transformImage(anchorX: Float, anchorY: Float, panX: Float, panY: Float, zoom: Float): CanvasRect {
    require(listOf(anchorX, anchorY, panX, panY, zoom).all { it.isFinite() } && zoom > 0f)
    val x = anchorX + panX - (anchorX - left) * zoom
    val y = anchorY + panY - (anchorY - top) * zoom
    val w = width * zoom
    val h = height * zoom
    return if (listOf(x, y, w, h, x + w, y + h).all { it.isFinite() } && w > 0f && h > 0f) {
        CanvasRect(left = x, top = y, width = w, height = h)
    } else this
}

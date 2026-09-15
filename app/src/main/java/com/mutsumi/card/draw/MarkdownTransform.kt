package com.mutsumi.card.draw

import kotlin.math.roundToInt

/** 独立的卡面坐标。缩放通过 SDK 排版宽度重新换行，移动不改变排版。 */
data class MarkdownTransform(
    val width: Float = 512f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    init {
        require(width.isFinite() && width in 64f..4096f)
        require(offsetX.isFinite() && offsetY.isFinite())
    }

    val layoutWidth: Int get() = width.roundToInt()

    fun transform(anchorX: Float, anchorY: Float, panX: Float, panY: Float, zoom: Float): MarkdownTransform {
        require(listOf(anchorX, anchorY, panX, panY, zoom).all { it.isFinite() } && zoom > 0f)
        val nextWidth = (width / zoom).coerceIn(64f, 4096f)
        val ratio = width / nextWidth
        val x = anchorX + panX - (anchorX - offsetX) * ratio
        val y = anchorY + panY - (anchorY - offsetY) * ratio
        return if (x.isFinite() && y.isFinite()) MarkdownTransform(nextWidth, x, y) else this
    }
}

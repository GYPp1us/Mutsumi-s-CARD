package com.mutsumi.card.draw

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers

/** 绘图、AI 和成品导出共用 md2svg + resvg，不依赖设备字体或屏幕密度。 */
class MarkdownLayerRenderer(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun render(
        source: String,
        width: Int,
        height: Int,
        transform: MarkdownTransform = MarkdownTransform(),
    ): Bitmap? {
        if (source.isBlank()) return null
        require(width in 1..2048 && height in 1..4096) { "Markdown 渲染尺寸超出范围" }
        val pixels = Md2SvgNative.render(
            source, transform.layoutWidth, width, height, transform.offsetX, transform.offsetY,
        )
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

/** 单个后台队列；界面使用 collectLatest 合并尚未开始的手势请求。 */
internal val MarkdownRenderDispatcher = Dispatchers.Default.limitedParallelism(1)

internal object Md2SvgNative {
    init { System.loadLibrary("mutsumi_md2svg") }

    external fun render(
        source: String,
        layoutWidth: Int,
        width: Int,
        height: Int,
        offsetX: Float,
        offsetY: Float,
    ): IntArray
}

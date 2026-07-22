package com.mutsumi.card.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlin.math.roundToInt

/** Markdown 只绘制文字与表格线，输出 Bitmap 始终保留透明背景。 */
class MarkdownLayerRenderer(context: Context) {
    private val appContext = context.applicationContext
    // 空白草稿不需要 Markdown 引擎；保留插件初始化异常，首次实际渲染时直接暴露。
    private val markwon by lazy(LazyThreadSafetyMode.NONE) {
        Markwon.builder(appContext)
            .usePlugin(TablePlugin.create(appContext))
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(48f) { builder -> builder.inlinesEnabled(true) })
            .build()
    }

    fun render(source: String, width: Int, height: Int): Bitmap? {
        if (source.isBlank()) return null
        require(width > 0 && height > 0) { "Markdown 渲染尺寸必须大于 0" }
        val scale = width.toFloat() / DrawingCanvasSpec.width
        val padding = (56f * scale).roundToInt()
        val textView = TextView(appContext).apply {
            setTextColor(Color.rgb(32, 38, 35))
            background = null
            setPadding(padding, padding, padding, padding)
            textSize = 34f * scale
            includeFontPadding = true
        }
        markwon.setMarkdown(textView, source)
        val contentWidth = (width - padding * 2).coerceAtLeast(1)
        textView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(contentWidth, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.AT_MOST),
        )
        textView.layout(0, 0, width, minOf(height, textView.measuredHeight))
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.TRANSPARENT)
            textView.draw(Canvas(bitmap))
        }
    }
}

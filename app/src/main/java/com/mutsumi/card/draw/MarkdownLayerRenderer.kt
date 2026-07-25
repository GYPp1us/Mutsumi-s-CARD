package com.mutsumi.card.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.util.TypedValue
import io.noties.markwon.Markwon
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.max
import kotlin.math.roundToInt

/** 将支持的 Markdown 子集同步绘制到透明 Bitmap，保证预览与导出使用同一条路径。 */
class MarkdownLayerRenderer(context: Context) {
    private val appContext = context.applicationContext

    init {
        JLatexMathAndroid.init(appContext)
    }

    private val markwon by lazy(LazyThreadSafetyMode.NONE) {
        Markwon.builder(appContext)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .build()
    }

    fun render(source: String, width: Int, height: Int): Bitmap? {
        if (source.isBlank()) return null
        require(width > 0 && height > 0) { "Markdown 渲染尺寸必须大于 0" }

        val scale = width.toFloat() / DrawingCanvasSpec.width
        val padding = (56f * scale).roundToInt()
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(32, 38, 35)
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                34f * scale,
                appContext.resources.displayMetrics,
            )
        }
        val contentWidth = (width - padding * 2).coerceAtLeast(1)
        val tableSpecs = mutableListOf<TableSpec>()
        val sourceWithoutTables = replaceTables(source, tableSpecs)
        val latexSpecs = mutableListOf<LatexSpec>()
        val preparedSource = replaceLatex(sourceWithoutTables, latexSpecs)
        val rendered = SpannableString(markwon.render(markwon.parse(preparedSource)))
        val tableBitmaps = mutableListOf<Bitmap>()

        try {
            attachPlaceholders(rendered, TABLE_PLACEHOLDER, tableSpecs) { spec ->
                val bitmap = renderTable(spec, contentWidth, textPaint, scale)
                tableBitmaps += bitmap
                TableSpan(bitmap)
            }
            attachPlaceholders(rendered, LATEX_PLACEHOLDER, latexSpecs) { spec ->
                LatexSpan(spec, textPaint.textSize, textPaint.color)
            }
            val layout = createLayout(rendered, textPaint, contentWidth, includePadding = true)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(bitmap)
                canvas.save()
                canvas.clipRect(padding, padding, width - padding, height - padding)
                canvas.translate(padding.toFloat(), padding.toFloat())
                layout.draw(canvas)
                canvas.restore()
            }
        } finally {
            tableBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun renderInline(source: String, paint: TextPaint): Spanned {
        val latexSpecs = mutableListOf<LatexSpec>()
        val prepared = replaceLatex(source, latexSpecs)
        val result = SpannableString(markwon.render(markwon.parse(prepared)))
        attachPlaceholders(result, LATEX_PLACEHOLDER, latexSpecs) { spec ->
            LatexSpan(spec, paint.textSize, paint.color)
        }
        return result
    }

    private fun replaceTables(source: String, specs: MutableList<TableSpec>): String {
        val lines = source.replace("\r\n", "\n").split('\n')
        val result = StringBuilder(source.length)
        var index = 0
        while (index < lines.size) {
            val header = parseTableRow(lines[index])
            val separator = lines.getOrNull(index + 1)
            if (header != null && separator != null && isSeparatorRow(separator)) {
                val rows = mutableListOf(header)
                index += 2
                while (index < lines.size) {
                    val row = parseTableRow(lines[index]) ?: break
                    rows += row
                    index += 1
                }
                specs += TableSpec(rows)
                result.append(TABLE_PLACEHOLDER)
            } else {
                result.append(lines[index])
                index += 1
            }
            if (index < lines.size) result.append('\n')
        }
        return result.toString()
    }

    private fun parseTableRow(line: String): List<String>? {
        val value = line.trim()
        if (!value.contains('|')) return null
        val body = value.removePrefix("|").removeSuffix("|")
        val cells = body.split('|').map { it.trim() }
        return cells.takeIf { it.size >= 2 }
    }

    private fun isSeparatorRow(line: String): Boolean {
        val cells = parseTableRow(line) ?: return false
        return cells.all { it.matches(Regex(":?-{1,}:?")) }
    }

    private fun replaceLatex(source: String, specs: MutableList<LatexSpec>): String {
        val result = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val block = source.startsWith("$$", index)
            val delimiter = if (block) "$$" else "${'$'}"
            if (source[index] == '$' && (block || !source.startsWith("$$", index))) {
                val end = source.indexOf(delimiter, index + delimiter.length)
                if (end > index + delimiter.length) {
                    val value = source.substring(index + delimiter.length, end)
                    specs += LatexSpec(value, block)
                    result.append(LATEX_PLACEHOLDER)
                    index = end + delimiter.length
                    continue
                }
            }
            result.append(source[index])
            index += 1
        }
        return result.toString()
    }

    private fun <T> attachPlaceholders(
        text: SpannableString,
        placeholder: Char,
        values: List<T>,
        createSpan: (T) -> ReplacementSpan,
    ) {
        var valueIndex = 0
        text.forEachIndexed { index, character ->
            if (character == placeholder) {
                check(valueIndex < values.size) { "Markdown 占位符数量不匹配" }
                text.setSpan(
                    createSpan(values[valueIndex]),
                    index,
                    index + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                valueIndex += 1
            }
        }
        check(valueIndex == values.size) { "Markdown 占位符在解析过程中丢失" }
    }

    private fun renderTable(
        spec: TableSpec,
        width: Int,
        basePaint: TextPaint,
        scale: Float,
    ): Bitmap {
        val columnCount = spec.rows.maxOf { it.size }
        val cellWidth = (width / columnCount.toFloat()).roundToInt().coerceAtLeast(1)
        val cellPadding = (12f * scale).roundToInt().coerceAtLeast(2)
        val borderWidth = max(1, (scale * 2f).roundToInt())
        val layouts = spec.rows.mapIndexed { rowIndex, row ->
            row.map { cell ->
                val paint = TextPaint(basePaint).apply {
                    typeface = if (rowIndex == 0) {
                        Typeface.create(typeface, Typeface.BOLD)
                    } else {
                        typeface
                    }
                }
                val availableWidth = (cellWidth - cellPadding * 2 - borderWidth).coerceAtLeast(1)
                val content = renderInline(cell, paint)
                createLayout(content, paint, availableWidth, includePadding = false)
            }
        }
        val rowHeights = layouts.map { row ->
            max(
                row.maxOfOrNull { it.height } ?: 0,
                basePaint.fontMetricsInt.bottom - basePaint.fontMetricsInt.top,
            ) + cellPadding * 2 + borderWidth
        }
        val totalHeight = rowHeights.sum().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(125, 135, 130)
            style = Paint.Style.STROKE
            strokeWidth = borderWidth.toFloat()
        }
        var top = 0
        layouts.forEachIndexed { rowIndex, row ->
            val rowHeight = rowHeights[rowIndex]
            fillPaint.color = if (rowIndex == 0) Color.argb(28, 32, 38, 35) else Color.TRANSPARENT
            canvas.drawRect(0f, top.toFloat(), width.toFloat(), (top + rowHeight).toFloat(), fillPaint)
            row.forEachIndexed { columnIndex, layout ->
                val left = columnIndex * cellWidth
                val verticalOffset = (rowHeight - layout.height) / 2f
                canvas.save()
                canvas.translate((left + cellPadding).toFloat(), top + verticalOffset)
                layout.draw(canvas)
                canvas.restore()
            }
            canvas.drawRect(
                0.5f,
                top + 0.5f,
                width - 0.5f,
                top + rowHeight - 0.5f,
                linePaint,
            )
            for (column in 1 until columnCount) {
                val x = column * cellWidth.toFloat()
                canvas.drawLine(x, top.toFloat(), x, (top + rowHeight).toFloat(), linePaint)
            }
            top += rowHeight
        }
        return bitmap
    }

    private fun createLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        includePadding: Boolean,
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1f)
        .setIncludePad(includePadding)
        .build()

    private data class LatexSpec(val source: String, val block: Boolean)
    private data class TableSpec(val rows: List<List<String>>)

    private class LatexSpan(
        spec: LatexSpec,
        textSize: Float,
        color: Int,
    ) : ReplacementSpan() {
        private val drawable = JLatexMathDrawable.builder(spec.source)
            .textSize(if (spec.block) textSize * 1.2f else textSize)
            .color(color)
            .padding(0)
            .build()

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fontMetricsInt: Paint.FontMetricsInt?,
        ): Int {
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            fontMetricsInt?.apply {
                ascent = -height
                descent = 0
                top = ascent
                bottom = descent
            }
            return drawable.intrinsicWidth.coerceAtLeast(1)
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val left = x.roundToInt()
            drawable.setBounds(left, y - drawable.intrinsicHeight, left + drawable.intrinsicWidth, y)
            drawable.draw(canvas)
        }
    }

    private class TableSpan(private val bitmap: Bitmap) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fontMetricsInt: Paint.FontMetricsInt?,
        ): Int {
            fontMetricsInt?.apply {
                ascent = -bitmap.height
                descent = 0
                top = ascent
                bottom = descent
            }
            return bitmap.width
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            canvas.drawBitmap(bitmap, x, (y - bitmap.height).toFloat(), paint)
        }
    }

    private companion object {
        const val LATEX_PLACEHOLDER = '\uFFF1'
        const val TABLE_PLACEHOLDER = '\uFFF0'
    }
}

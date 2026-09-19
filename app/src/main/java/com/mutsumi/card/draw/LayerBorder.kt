package com.mutsumi.card.draw

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class LayerBorderStyle(val label: String, val layerLabel: String) {
    Solid("单实线", "绘制"), Dashed("虚线", "Markdown"), Double("双线", "底图"),
}

internal val DrawLayerStyleKey = SemanticsPropertyKey<String>("图层边框")

/** 画布和工具按钮共用的线型；描边绘制在内容上方并向内收，保持布局尺寸不变。 */
internal fun Modifier.layerBorder(style: LayerBorderStyle, color: Color, selected: Boolean = true): Modifier =
    semantics { this[DrawLayerStyleKey] = style.label }.drawWithContent {
        drawContent()
        val line = if (selected) 1.5.dp.toPx() else 1.dp.toPx()
        val stroke = Stroke(line, pathEffect = if (style == LayerBorderStyle.Dashed) {
            PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))
        } else null)
        fun outline(inset: Float) {
            val w = size.width - inset * 2
            val h = size.height - inset * 2
            if (w > 0f && h > 0f) drawRoundRect(
                color = color, topLeft = Offset(inset, inset), size = Size(w, h),
                cornerRadius = CornerRadius((7.dp.toPx() - inset).coerceAtLeast(0f)), style = stroke,
            )
        }
        outline(line / 2)
        if (style == LayerBorderStyle.Double) outline(line / 2 + 3.dp.toPx())
    }

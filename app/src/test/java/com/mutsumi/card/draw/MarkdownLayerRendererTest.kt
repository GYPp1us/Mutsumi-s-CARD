package com.mutsumi.card.draw

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarkdownLayerRendererTest {
    @Test
    fun 非空Markdown表格和公式可以渲染为透明图层() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = MarkdownLayerRenderer(context).render(
            source = "# 标题\n\n| 列 | 值 |\n|---|---|\n| A | 1 |\n\n${'$'}E = mc^2${'$'}",
            width = DrawingCanvasSpec.width,
            height = DrawingCanvasSpec.height,
        )

        assertThat(bitmap).isNotNull()
        assertThat(bitmap!!.width).isEqualTo(DrawingCanvasSpec.width)
        assertThat(bitmap.height).isEqualTo(DrawingCanvasSpec.height)
        assertThat(Color.alpha(bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))).isEqualTo(0)
        bitmap.recycle()
    }
}

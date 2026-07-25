package com.mutsumi.card.draw

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarkdownLayerRendererTest {
    @Test
    fun markdownWithSingleAndDoubleLatexAndTableRendersToExpectedBitmapSize() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = MarkdownLayerRenderer(context).render(
            source = "# Title\n\n${'$'}E = mc^2${'$'}\n\n" +
                "${'$'}${'$'}\\frac{1}{2}${'$'}${'$'}\n\n" +
                "| Name | Value |\n| --- | --- |\n| A | 1 |",
            width = DrawingCanvasSpec.width,
            height = DrawingCanvasSpec.height,
        )

        assertThat(bitmap).isNotNull()
        assertThat(bitmap!!.width).isEqualTo(DrawingCanvasSpec.width)
        assertThat(bitmap.height).isEqualTo(DrawingCanvasSpec.height)
        bitmap.recycle()
    }

    @Test
    fun blankMarkdownDoesNotAllocateBitmap() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertThat(MarkdownLayerRenderer(context).render("  ", 1024, 1624)).isNull()
    }
}

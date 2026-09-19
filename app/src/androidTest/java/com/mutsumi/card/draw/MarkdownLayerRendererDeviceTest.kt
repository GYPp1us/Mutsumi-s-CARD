package com.mutsumi.card.draw

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownLayerRendererDeviceTest {
    @Test
    fun previewAndExportShareCardCoordinatesAndPanReusesLayout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = MarkdownLayerRenderer(context)
        val source = "# 中文标题\n\n${'$'}E=mc^2${'$'}\n\n```mermaid\nflowchart LR\nA[开始]-->B[结束]\n```"
        val transform = MarkdownTransform(width = 384f, offsetX = 64f, offsetY = 120f)
        val first = requireNotNull(renderer.render(source, 1024, 1624, transform))
        val second = requireNotNull(renderer.render(source, 1024, 1624, transform))
        val moved = requireNotNull(renderer.render(source, 1024, 1624, transform.copy(offsetY = 152f)))
        try {
            assertTrue(first.sameAs(second))
            assertTrue(nonTransparentPixelCount(first) > 100)
            var checked = 0
            for (y in 0 until 1592 step 4) for (x in 0 until 1024 step 4) {
                assertEquals(first.getPixel(x, y), moved.getPixel(x, y + 32))
                checked++
            }
            assertTrue(checked > 100)
        } finally {
            first.recycle(); second.recycle(); moved.recycle()
        }
    }

    @Test
    fun invalidMarkdownIsReportedAndNextRenderRecovers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = MarkdownLayerRenderer(context)
        val error = runCatching { renderer.render("<script>无效</script>", 256, 406) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        val bitmap = requireNotNull(renderer.render("# 恢复正常", 256, 406))
        try { assertTrue(nonTransparentPixelCount(bitmap) > 10) } finally { bitmap.recycle() }
    }
    @Test
    fun markdownFormulaAndTableProduceActualCanvasPixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(MarkdownLayerRenderer(context).render(
            source = "${'$'}E = mc^2${'$'}\n\n" +
                "${'$'}${'$'}\\frac{1}{2}${'$'}${'$'}\n\n" +
                "| Name | Value |\n| --- | --- |\n| A | 1 |",
            width = DrawingCanvasSpec.width,
            height = DrawingCanvasSpec.height,
        ))
        try {
            assertTrue(nonTransparentPixelCount(bitmap) > 100)
            assertTrue(darkPixelCount(bitmap, 56, 512) > 20)
            assertTrue(darkPixelCount(bitmap, 512, 968) > 20)
        } finally {
            bitmap.recycle()
        }
    }

    private fun nonTransparentPixelCount(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) count += 1
            }
        }
        return count
    }

    private fun darkPixelCount(bitmap: Bitmap, left: Int, right: Int): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 0 && Color.red(pixel) < 100 && Color.green(pixel) < 110) count += 1
            }
        }
        return count
    }
}

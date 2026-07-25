package com.mutsumi.card.draw

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownLayerRendererDeviceTest {
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

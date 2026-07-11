package com.mutsumi.card.draw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.draw.model.CanvasPoint
import com.mutsumi.card.draw.model.DrawingDocument
import com.mutsumi.card.draw.model.DrawingStroke
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingSceneRendererTest {
    private val renderer = DrawingSceneRenderer()

    @Test
    fun 仅底图可以导出为真实1024乘2048的PNG() {
        val png = renderer.exportPng(
            DrawingDocument(baseImage = solidPng(width = 8, height = 4, color = Color.RED)),
        )

        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
        try {
            assertThat(decoded).isNotNull()
            assertThat(decoded.width).isEqualTo(1024)
            assertThat(decoded.height).isEqualTo(2048)
            assertThat(png.copyOfRange(0, 8)).isEqualTo(PNG_SIGNATURE)
        } finally {
            decoded?.recycle()
        }
    }

    @Test
    fun 底图使用共享FitCenter几何且留白为白色() {
        renderer.useExportBitmap(
            DrawingDocument(baseImage = solidPng(width = 8, height = 4, color = Color.RED)),
        ) { bitmap ->
            assertThat(bitmap.getPixel(512, 100)).isEqualTo(Color.WHITE)
            assertThat(bitmap.getPixel(512, 1024)).isEqualTo(Color.RED)
            assertThat(bitmap.getPixel(512, 1900)).isEqualTo(Color.WHITE)
        }
    }

    @Test
    fun 单点笔触渲染为圆点() {
        renderer.useExportBitmap(
            DrawingDocument(
                strokes = listOf(
                    DrawingStroke(
                        points = listOf(CanvasPoint(512f, 1024f)),
                        colorArgb = Color.BLUE,
                        width = 40f,
                    ),
                ),
            ),
        ) { bitmap ->
            assertThat(bitmap.getPixel(512, 1024)).isEqualTo(Color.BLUE)
            assertThat(bitmap.getPixel(540, 1024)).isEqualTo(Color.WHITE)
        }
    }

    @Test
    fun 预览和导出共享竖向画布几何并裁剪边界() {
        val document = DrawingDocument(
            strokes = listOf(
                DrawingStroke(
                    points = listOf(CanvasPoint(0f, 1000f), CanvasPoint(0f, 1100f)),
                    colorArgb = Color.GREEN,
                    width = 80f,
                ),
            ),
        )

        val preview = renderer.renderPreviewBitmap(
            document = document,
            viewportWidth = 600,
            viewportHeight = 600,
            workspaceColorArgb = Color.MAGENTA,
        )

        try {
            assertThat(renderer.sceneRectForViewport(600, 600))
                .isEqualTo(CanvasRect(left = 150f, top = 0f, width = 300f, height = 600f))
            assertThat(preview.getPixel(149, 300)).isEqualTo(Color.MAGENTA)
            assertThat(preview.getPixel(150, 300)).isEqualTo(Color.GREEN)
            assertThat(preview.getPixel(450, 300)).isEqualTo(Color.MAGENTA)
        } finally {
            preview.recycle()
        }
    }

    @Test
    fun 空文档拒绝导出并给出明确异常() {
        val error = runCatching { renderer.exportPng(DrawingDocument()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("为空")
    }

    @Test
    fun 完全位于画布外的路径不会被压到边框() {
        val document = DrawingDocument().appendStroke(
            DrawingStroke(
                points = listOf(CanvasPoint(-100f, 100f), CanvasPoint(-100f, 1900f)),
                colorArgb = Color.GREEN,
                width = 80f,
            ),
        )

        val preview = renderer.renderPreviewBitmap(
            document = document,
            viewportWidth = 600,
            viewportHeight = 600,
            workspaceColorArgb = Color.MAGENTA,
        )
        try {
            assertThat(preview.getPixel(149, 300)).isEqualTo(Color.MAGENTA)
            assertThat(preview.getPixel(150, 300)).isEqualTo(Color.WHITE)
            assertThat(preview.getPixel(151, 300)).isEqualTo(Color.WHITE)
        } finally {
            preview.recycle()
        }
    }

    @Test
    fun 底图先读取边界并按目标尺寸采样() {
        val scene = renderer.prepareScene(
            DrawingDocument(baseImage = solidPng(width = 2048, height = 512, color = Color.RED)),
        )
        val baseImage = requireNotNull(scene.baseImage)
        try {
            assertThat(baseImage.width).isEqualTo(1024)
            assertThat(baseImage.height).isEqualTo(256)
            assertThat(baseImage.isRecycled).isFalse()
        } finally {
            scene.close()
        }
        assertThat(baseImage.isRecycled).isTrue()
    }

    @Test
    fun 同一场景资源重复绘制不会重复解码底图() {
        val decoder = RecordingDecoder()
        val cachedRenderer = DrawingSceneRenderer(decoder)
        val document = DrawingDocument(baseImage = byteArrayOf(1, 2, 3))
        val scene = cachedRenderer.prepareScene(document)
        val first = Bitmap.createBitmap(1024, 2048, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(1024, 2048, Bitmap.Config.ARGB_8888)
        try {
            cachedRenderer.drawScene(
                Canvas(first),
                document,
                CanvasRect(0f, 0f, 1024f, 2048f),
                scene,
            )
            cachedRenderer.drawScene(
                Canvas(second),
                document,
                CanvasRect(0f, 0f, 1024f, 2048f),
                scene,
            )

            assertThat(decoder.boundsCalls).isEqualTo(1)
            assertThat(decoder.decodeCalls).isEqualTo(1)
            assertThat(decoder.lastSampleSize).isEqualTo(4)
            assertThat(scene.baseImage?.isRecycled).isFalse()
        } finally {
            scene.close()
            first.recycle()
            second.recycle()
        }
        assertThat(decoder.decodedBitmap.isRecycled).isTrue()
    }

    @Test
    fun 导出位图在回调结束后由渲染器回收() {
        var ownedBitmap: Bitmap? = null

        renderer.useExportBitmap(
            DrawingDocument(strokes = listOf(singlePointStroke())),
        ) { bitmap ->
            ownedBitmap = bitmap
            assertThat(bitmap.isRecycled).isFalse()
        }

        assertThat(ownedBitmap?.isRecycled).isTrue()
    }

    private fun solidPng(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        try {
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun singlePointStroke() = DrawingStroke(
        points = listOf(CanvasPoint(20f, 20f)),
        colorArgb = Color.BLACK,
        width = 4f,
    )

    private class RecordingDecoder : DrawingBitmapDecoder {
        var boundsCalls = 0
        var decodeCalls = 0
        var lastSampleSize = 0
        val decodedBitmap: Bitmap = Bitmap.createBitmap(1024, 256, Bitmap.Config.ARGB_8888)

        override fun readBounds(bytes: ByteArray): BitmapBounds {
            boundsCalls += 1
            return BitmapBounds(width = 4096, height = 1024)
        }

        override fun decode(bytes: ByteArray, sampleSize: Int): Bitmap {
            decodeCalls += 1
            lastSampleSize = sampleSize
            return decodedBitmap
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}

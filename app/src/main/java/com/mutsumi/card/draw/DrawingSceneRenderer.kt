package com.mutsumi.card.draw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.mutsumi.card.draw.model.DrawingDocument
import com.mutsumi.card.draw.model.DrawingStroke
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

data class BitmapBounds(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "图片边界必须大于 0" }
    }
}

interface DrawingBitmapDecoder {
    fun readBounds(bytes: ByteArray): BitmapBounds
    fun decode(bytes: ByteArray, sampleSize: Int): Bitmap
}

class AndroidDrawingBitmapDecoder : DrawingBitmapDecoder {
    override fun readBounds(bytes: ByteArray): BitmapBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "底图不是可解码的图片" }
        return BitmapBounds(options.outWidth, options.outHeight)
    }

    override fun decode(bytes: ByteArray, sampleSize: Int): Bitmap {
        require(sampleSize >= 1) { "图片采样率必须大于 0" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
            "底图不是可解码的图片"
        }
    }
}

class DrawingScene internal constructor(
    val baseImage: Bitmap?,
) : AutoCloseable {
    override fun close() {
        baseImage?.takeUnless(Bitmap::isRecycled)?.recycle()
    }
}

class DrawingSceneRenderer(
    private val bitmapDecoder: DrawingBitmapDecoder = AndroidDrawingBitmapDecoder(),
) {
    fun sceneRectForViewport(viewportWidth: Int, viewportHeight: Int): CanvasRect = fitCenterRect(
        sourceWidth = DrawingCanvasSpec.width,
        sourceHeight = DrawingCanvasSpec.height,
        targetWidth = viewportWidth,
        targetHeight = viewportHeight,
    )

    fun prepareScene(document: DrawingDocument): DrawingScene {
        val bytes = document.baseImage ?: return DrawingScene(baseImage = null)
        val bounds = bitmapDecoder.readBounds(bytes)
        val sampleSize = calculateSampleSize(bounds)
        val bitmap = bitmapDecoder.decode(bytes, sampleSize)
        check(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "底图解码结果无效" }
        return DrawingScene(bitmap)
    }

    /** 返回的预览 Bitmap 由调用方持有并负责 recycle。 */
    fun renderPreviewBitmap(
        document: DrawingDocument,
        viewportWidth: Int,
        viewportHeight: Int,
        workspaceColorArgb: Int = Color.TRANSPARENT,
    ): Bitmap {
        require(viewportWidth > 0 && viewportHeight > 0) { "预览尺寸必须大于 0" }
        val scene = prepareScene(document)
        var output: Bitmap? = null
        try {
            output = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
            output.eraseColor(workspaceColorArgb)
            drawScene(
                canvas = Canvas(output),
                document = document,
                destination = sceneRectForViewport(viewportWidth, viewportHeight),
                scene = scene,
            )
            return output
        } catch (failure: Throwable) {
            output?.takeUnless(Bitmap::isRecycled)?.recycle()
            throw failure
        } finally {
            scene.close()
        }
    }

    fun <T> useExportBitmap(document: DrawingDocument, block: (Bitmap) -> T): T {
        require(document.hasContent) { "绘图文档为空，无法保存" }
        val scene = prepareScene(document)
        var output: Bitmap? = null
        try {
            output = Bitmap.createBitmap(
                DrawingCanvasSpec.width,
                DrawingCanvasSpec.height,
                Bitmap.Config.ARGB_8888,
            )
            drawScene(
                canvas = Canvas(output),
                document = document,
                destination = CanvasRect(
                    0f,
                    0f,
                    DrawingCanvasSpec.width.toFloat(),
                    DrawingCanvasSpec.height.toFloat(),
                ),
                scene = scene,
            )
            return block(output)
        } finally {
            output?.takeUnless(Bitmap::isRecycled)?.recycle()
            scene.close()
        }
    }

    fun exportPng(document: DrawingDocument): ByteArray = useExportBitmap(document) { bitmap ->
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
            output.toByteArray()
        }
    }

    /** 可重复调用；scene 由调用方跨帧复用并在离开预览时关闭。 */
    fun drawScene(
        canvas: Canvas,
        document: DrawingDocument,
        destination: CanvasRect,
        scene: DrawingScene,
    ) {
        require(destination.width > 0f && destination.height > 0f) { "场景尺寸必须大于 0" }
        val scale = destination.width / DrawingCanvasSpec.width
        val expectedHeight = DrawingCanvasSpec.height * scale
        require(kotlin.math.abs(expectedHeight - destination.height) < 0.01f) {
            "场景必须保持 1:2 比例"
        }
        require(document.baseImage == null || scene.baseImage != null) { "场景缺少已解码底图" }
        check(scene.baseImage?.isRecycled != true) { "场景底图已经释放" }

        val checkpoint = canvas.save()
        try {
            canvas.clipRect(destination.left, destination.top, destination.right, destination.bottom)
            canvas.drawRect(
                destination.left,
                destination.top,
                destination.right,
                destination.bottom,
                Paint().apply { color = Color.WHITE },
            )
            canvas.translate(destination.left, destination.top)
            canvas.scale(scale, scale)
            drawBaseImage(canvas, scene.baseImage)
            document.strokes.forEach { drawStroke(canvas, it) }
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun calculateSampleSize(bounds: BitmapBounds): Int {
        val targetScale = min(
            1f,
            min(
                DrawingCanvasSpec.width.toFloat() / bounds.width,
                DrawingCanvasSpec.height.toFloat() / bounds.height,
            ),
        )
        val targetWidth = max(1, (bounds.width * targetScale).toInt())
        val targetHeight = max(1, (bounds.height * targetScale).toInt())
        var sampleSize = 1
        while (
            bounds.width / (sampleSize * 2) >= targetWidth &&
            bounds.height / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun drawBaseImage(canvas: Canvas, bitmap: Bitmap?) {
        if (bitmap == null) return
        val destination = fitCenterRect(
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            targetWidth = DrawingCanvasSpec.width,
            targetHeight = DrawingCanvasSpec.height,
        )
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(destination.left, destination.top, destination.right, destination.bottom),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
    }

    private fun drawStroke(canvas: Canvas, stroke: DrawingStroke) {
        val points = stroke.points
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.colorArgb
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = stroke.width
        }
        if (points.size == 1) {
            paint.style = Paint.Style.FILL
            val point = points.single()
            canvas.drawCircle(point.x, point.y, stroke.width / 2f, paint)
            return
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, paint)
    }
}

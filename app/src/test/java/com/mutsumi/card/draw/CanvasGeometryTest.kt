package com.mutsumi.card.draw

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CanvasGeometryTest {
    @Test
    fun 画布固定为竖向银行卡比例1024乘1624() {
        assertThat(DrawingCanvasSpec.width).isEqualTo(1024)
        assertThat(DrawingCanvasSpec.height).isEqualTo(1624)
        assertThat(DrawingCanvasSpec.aspectRatio).isWithin(0.0001f).of(53.98f / 85.60f)
    }

    @Test
    fun 横向底图按FitCenter居中且不拉伸() {
        val rect = fitCenterRect(
            sourceWidth = 800,
            sourceHeight = 400,
            targetWidth = DrawingCanvasSpec.width,
            targetHeight = DrawingCanvasSpec.height,
        )

        assertThat(rect).isEqualTo(
            CanvasRect(left = 0f, top = 556f, width = 1024f, height = 512f),
        )
    }

    @Test
    fun 初始相机精确显示限制方向的百分之三十五() {
        val camera = CanvasCamera.initial(viewportWidth = 360f, viewportHeight = 640f)

        assertThat(camera.visibleFraction).isWithin(0.0001f).of(0.35f)
        assertThat(camera.zoom).isWithin(0.0001f).of(1f / 0.35f)
    }

    @Test
    fun 相机旋转再回转保留相对FitScale的缩放() {
        val portrait = CanvasCamera.initial(360f, 640f).withZoom(1.8f)

        val landscape = portrait.withViewport(width = 640f, height = 360f)
        val restored = landscape.withViewport(width = 360f, height = 640f)

        assertThat(portrait.zoom).isWithin(0.0001f).of(1.8f)
        assertThat(landscape.zoom).isWithin(0.0001f).of(1.8f)
        assertThat(restored.zoom).isWithin(0.0001f).of(1.8f)
        assertThat(restored.scale).isWithin(0.0001f).of(portrait.scale)
        assertThat(restored.centerX).isWithin(0.001f).of(portrait.centerX)
        assertThat(restored.centerY).isWithin(0.001f).of(portrait.centerY)
    }

    @Test
    fun 双指缩放以实时质心为锚点() {
        val camera = CanvasCamera.initial(1000f, 1000f).withZoom(2f)
        val centroidX = 420f
        val centroidY = 460f
        val panX = 18f
        val panY = -12f
        val anchorX = camera.offsetX + centroidX / camera.scale
        val anchorY = camera.offsetY + centroidY / camera.scale

        val transformed = camera.transform(centroidX, centroidY, panX, panY, 1.15f)

        assertThat(transformed.offsetX + (centroidX + panX) / transformed.scale)
            .isWithin(0.001f).of(anchorX)
        assertThat(transformed.offsetY + (centroidY + panY) / transformed.scale)
            .isWithin(0.001f).of(anchorY)
    }

    @Test
    fun 连续平移十五帧不会只应用第一帧() {
        val initial = CanvasCamera.initial(1000f, 1000f).withZoom(2f)
        val transformed = (1..15).fold(initial) { camera, _ ->
            camera.transform(500f, 500f, 2f, 0f, 1f)
        }

        assertThat(transformed.centerX).isLessThan(initial.centerX - 20f)
    }
}

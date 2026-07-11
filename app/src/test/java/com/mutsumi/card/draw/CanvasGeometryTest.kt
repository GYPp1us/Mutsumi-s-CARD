package com.mutsumi.card.draw

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CanvasGeometryTest {
    @Test
    fun 画布固定为竖向1024乘2048() {
        assertThat(DrawingCanvasSpec.width).isEqualTo(1024)
        assertThat(DrawingCanvasSpec.height).isEqualTo(2048)
        assertThat(DrawingCanvasSpec.aspectRatio).isEqualTo(0.5f)
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
            CanvasRect(left = 0f, top = 768f, width = 1024f, height = 512f),
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
}

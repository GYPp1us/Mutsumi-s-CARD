package com.mutsumi.card.draw

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CanvasGeometryTest {
    @Test
    fun cardCanvasUsesTwoToOneRatio() {
        assertThat(CardCanvasSpec.exportWidth).isEqualTo(1200)
        assertThat(CardCanvasSpec.exportHeight).isEqualTo(600)
        assertThat(CardCanvasSpec.aspectRatio).isEqualTo(2f)
    }

    @Test
    fun baseImageFitsInsideCanvasWithoutStretching() {
        val rect = fitCenterRect(
            sourceWidth = 400,
            sourceHeight = 800,
            targetWidth = 1200,
            targetHeight = 600,
        )

        assertThat(rect.width).isEqualTo(300f)
        assertThat(rect.height).isEqualTo(600f)
        assertThat(rect.left).isEqualTo(450f)
        assertThat(rect.top).isEqualTo(0f)
    }

    @Test
    fun initialCameraShowsOnlyPartOfLargeCanvas() {
        val camera = CanvasCamera.initial(viewportWidth = 600f, viewportHeight = 300f)

        assertThat(camera.visibleWidth).isLessThan(CardCanvasSpec.logicalWidth)
        assertThat(camera.visibleHeight).isLessThan(CardCanvasSpec.logicalHeight)
    }
}

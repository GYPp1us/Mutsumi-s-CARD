package com.mutsumi.card.draw

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BaseImageTransformTest {
    @Test fun panPreservesImageSizeAtNegativeCoordinates() {
        val rect = CanvasRect(100f, 200f, 800f, 400f)
        val moved = rect.transformImage(500f, 400f, -1000f, -2000f, 1f)
        assertThat(moved).isEqualTo(CanvasRect(-900f, -1800f, 800f, 400f))
    }

    @Test fun zoomIsUniformAndKeepsGestureAnchor() {
        val rect = CanvasRect(100f, 200f, 800f, 400f)
        val zoomed = rect.transformImage(500f, 400f, 20f, 30f, 2f)
        assertThat(zoomed).isEqualTo(CanvasRect(-280f, 30f, 1600f, 800f))
    }
}

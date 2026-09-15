package com.mutsumi.card.draw

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownTransformTest {
    @Test fun pinchChangesSdkWidthAndKeepsAnchor() {
        val start = MarkdownTransform()
        val moved = start.transform(200f, 300f, 20f, -10f, 2f)
        assertThat(moved.layoutWidth).isEqualTo(start.layoutWidth / 2)
        assertThat(moved.offsetX).isWithin(0.01f).of(-180f)
        assertThat(moved.offsetY).isWithin(0.01f).of(-310f)
    }

    @Test fun panDoesNotChangeLayoutWidthAndIsUnbounded() {
        val moved = MarkdownTransform().transform(0f, 0f, 100000f, -100000f, 1f)
        assertThat(moved.layoutWidth).isEqualTo(256)
        assertThat(moved.offsetX).isEqualTo(100000f)
        assertThat(moved.offsetY).isEqualTo(-100000f)
    }

    @Test fun fractionalPinchesAccumulateAndWidthHonorsSdkLimits() {
        var state = MarkdownTransform()
        repeat(100) { state = state.transform(0f, 0f, 0f, 0f, 1.001f) }
        assertThat(state.layoutWidth).isLessThan(235)
        assertThat(state.transform(0f, 0f, 0f, 0f, 100000f).layoutWidth).isEqualTo(64)
        assertThat(state.transform(0f, 0f, 0f, 0f, 0.00001f).layoutWidth).isEqualTo(4096)
    }
}

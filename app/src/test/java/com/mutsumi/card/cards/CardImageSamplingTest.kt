package com.mutsumi.card.cards

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardImageSamplingTest {
    @Test
    fun samplesPowerOfTwoWithoutDroppingBelowTarget() {
        assertThat(calculateInSampleSize(1024, 2048, 54, 108)).isEqualTo(16)
        assertThat(calculateInSampleSize(1024, 2048, 150, 300)).isEqualTo(4)
    }

    @Test
    fun unknownOrLargeTargetUsesOriginalResolution() {
        assertThat(calculateInSampleSize(1024, 2048, 0, 0)).isEqualTo(1)
        assertThat(calculateInSampleSize(1024, 2048, 1024, 2048)).isEqualTo(1)
    }
}

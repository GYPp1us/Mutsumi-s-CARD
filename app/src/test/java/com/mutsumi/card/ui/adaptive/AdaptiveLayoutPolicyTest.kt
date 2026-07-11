package com.mutsumi.card.ui.adaptive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test
    fun targetSizesUseApprovedModes() {
        assertThat(AdaptiveLayoutPolicy.mode(360, 800)).isEqualTo(AppLayoutMode.Portrait)
        assertThat(AdaptiveLayoutPolicy.mode(800, 360)).isEqualTo(AppLayoutMode.LandscapeThreePane)
        assertThat(AdaptiveLayoutPolicy.mode(800, 1280)).isEqualTo(AppLayoutMode.Portrait)
        assertThat(AdaptiveLayoutPolicy.mode(1280, 800)).isEqualTo(AppLayoutMode.LandscapeThreePane)
    }

    @Test
    fun compactLandscapeKeepsBottomNavigation() {
        assertThat(AdaptiveLayoutPolicy.mode(640, 360))
            .isEqualTo(AppLayoutMode.CompactLandscape)
    }

    @Test
    fun squareAndPortraitTabletNeverUseThreePanes() {
        assertThat(AdaptiveLayoutPolicy.mode(800, 800)).isEqualTo(AppLayoutMode.Portrait)
        assertThat(AdaptiveLayoutPolicy.mode(1000, 1200)).isEqualTo(AppLayoutMode.Portrait)
    }

    @Test
    fun contextWidthIsClampedToApprovedRange() {
        assertThat(AdaptiveLayoutPolicy.contextWidthDp(800)).isEqualTo(228)
        assertThat(AdaptiveLayoutPolicy.contextWidthDp(1000)).isEqualTo(250)
        assertThat(AdaptiveLayoutPolicy.contextWidthDp(2000)).isEqualTo(310)
    }
}

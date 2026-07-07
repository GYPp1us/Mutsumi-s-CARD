package com.mutsumi.card.ui.adaptive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test
    fun landscapeUsesNavigationRailEvenWhenWidthIsBelowTabletBreakpoint() {
        assertThat(AdaptiveLayoutPolicy.useNavigationRail(widthDp = 780, heightDp = 360)).isTrue()
    }

    @Test
    fun portraitPhoneUsesBottomNavigation() {
        assertThat(AdaptiveLayoutPolicy.useNavigationRail(widthDp = 360, heightDp = 780)).isFalse()
    }

    @Test
    fun tabletWidthUsesNavigationRail() {
        assertThat(AdaptiveLayoutPolicy.useNavigationRail(widthDp = 840, heightDp = 1200)).isTrue()
    }
}


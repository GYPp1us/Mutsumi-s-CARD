package com.mutsumi.card.ui.adaptive

enum class AppLayoutMode {
    Portrait,
    CompactLandscape,
    LandscapeThreePane,
}

object AdaptiveLayoutPolicy {
    fun mode(widthDp: Int, heightDp: Int): AppLayoutMode {
        require(widthDp > 0 && heightDp > 0) { "可用尺寸必须大于 0" }
        return when {
            heightDp >= widthDp -> AppLayoutMode.Portrait
            widthDp >= THREE_PANE_MIN_WIDTH_DP -> AppLayoutMode.LandscapeThreePane
            else -> AppLayoutMode.CompactLandscape
        }
    }

    fun contextWidthDp(windowWidthDp: Int): Int =
        (windowWidthDp * 0.25f).toInt().coerceIn(CONTEXT_MIN_WIDTH_DP, CONTEXT_MAX_WIDTH_DP)

    private const val THREE_PANE_MIN_WIDTH_DP = 680
    private const val CONTEXT_MIN_WIDTH_DP = 228
    private const val CONTEXT_MAX_WIDTH_DP = 310
}

package com.mutsumi.card.ui.adaptive

object AdaptiveLayoutPolicy {
    fun useNavigationRail(widthDp: Int, heightDp: Int): Boolean {
        return widthDp >= 840 || widthDp > heightDp
    }
}


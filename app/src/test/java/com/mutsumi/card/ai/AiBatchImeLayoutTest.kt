package com.mutsumi.card.ai

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiBatchImeLayoutTest {
    @Test
    fun landscapeWorkflowPrioritizesManualSourceWhileImeIsVisible() {
        assertThat(useImeSourceLayout(728.dp, 360.dp, imeVisible = true)).isTrue()
        assertThat(useImeSourceLayout(1_280.dp, 800.dp, imeVisible = true)).isTrue()
    }

    @Test
    fun normalWorkflowLayoutRemainsWhenImeIsHiddenOrViewportIsNotLandscape() {
        assertThat(useImeSourceLayout(728.dp, 360.dp, imeVisible = false)).isFalse()
        assertThat(useImeSourceLayout(800.dp, 1_280.dp, imeVisible = true)).isFalse()
    }
}

package com.mutsumi.card.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiManualSourceLimitTest {
    @Test
    fun `手动素材限制为单份材料上限`() {
        val value = "字".repeat(MAX_MANUAL_SOURCE_CHARACTERS + 1)

        val limited = limitManualSource(value)

        assertThat(limited).hasLength(MAX_MANUAL_SOURCE_CHARACTERS)
    }
}

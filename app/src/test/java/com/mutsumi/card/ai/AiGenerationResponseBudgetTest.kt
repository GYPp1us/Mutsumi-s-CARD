package com.mutsumi.card.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AiGenerationResponseBudgetTest {
    @Test
    fun `response budget rejects a group before tool arguments grow without bound`() {
        val budget = AiGenerationResponseBudget(maxCharactersPerGroup = 5, maxTotalCharacters = 8)

        budget.append(groupIndex = 0, characterCount = 5)
        budget.append(groupIndex = 1, characterCount = 3)

        assertThat(budget.totalCharacters).isEqualTo(8)
        val error = assertThrows(AiGenerationException::class.java) {
            budget.append(groupIndex = 1, characterCount = 1)
        }
        assertThat(error).hasMessageThat().contains("总长度")
    }
}

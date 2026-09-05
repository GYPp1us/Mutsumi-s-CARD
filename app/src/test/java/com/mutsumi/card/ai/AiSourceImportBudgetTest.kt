package com.mutsumi.card.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiSourceImportBudgetTest {
    @Test
    fun `budget rejects a file before imported text can exceed its bounded cache`() {
        val budget = AiSourceImportBudget(
            AiSourceImportLimits(
                maxFiles = 2,
                maxCharactersPerFile = 5,
                maxTotalCharacters = 8,
            ),
        )

        assertThat(budget.maximumReadableCharacters).isEqualTo(5)
        assertThat(budget.rejectionReasonFor(6)).contains("单个文件")

        budget.record(5)

        assertThat(budget.maximumReadableCharacters).isEqualTo(3)
        assertThat(budget.rejectionReasonFor(4)).contains("总量")

        budget.record(3)

        assertThat(budget.maximumReadableCharacters).isEqualTo(0)
        assertThat(budget.rejectionReasonFor(1)).contains("文件数量")
    }

    @Test
    fun `later import inherits existing cache budget and cannot exceed total limit`() {
        val budget = AiSourceImportBudget(
            limits = AiSourceImportLimits(
                maxFiles = 3,
                maxCharactersPerFile = 5,
                maxTotalCharacters = 8,
            ),
            initialImportedFiles = 1,
            initialImportedCharacters = 5,
        )

        assertThat(budget.maximumReadableCharacters).isEqualTo(3)
        assertThat(budget.rejectionReasonFor(4)).contains("总量")

        budget.record(3)

        assertThat(budget.maximumReadableCharacters).isEqualTo(0)
    }
}

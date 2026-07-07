package com.mutsumi.card.domain.workflow

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupWorkflowTest {
    @Test
    fun exportClickReturnsVisibleFeedback() {
        val deck = CardDeckState()
        deck.saveCard("雨の音", 2)

        val message = BackupActions.exportMessage(deck.cards.size)

        assertThat(message).contains("导出")
        assertThat(message).contains("1")
    }

    @Test
    fun importClickReturnsVisibleFeedback() {
        val message = BackupActions.importMessage()

        assertThat(message).contains("导入")
        assertThat(message).contains("文件选择器")
    }
}


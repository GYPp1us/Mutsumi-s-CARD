package com.mutsumi.card.domain.workflow

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardWorkflowTest {
    @Test
    fun saveCardCreatesCardAndFeedback() {
        val deck = CardDeckState()

        val result = deck.saveCard(keyText = "雨の音", strokeCount = 3)

        assertThat(deck.cards).hasSize(1)
        assertThat(deck.cards.first().keyText).isEqualTo("雨の音")
        assertThat(result.message).contains("已保存")
    }

    @Test
    fun saveCardRejectsBlankKey() {
        val deck = CardDeckState()

        val error = runCatching {
            deck.saveCard(keyText = "   ", strokeCount = 3)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("key")
    }

    @Test
    fun saveCardRejectsEmptyDrawing() {
        val deck = CardDeckState()

        val error = runCatching {
            deck.saveCard(keyText = "雨の音", strokeCount = 0)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("图片")
    }

    @Test
    fun selectingCardUpdatesSelection() {
        val deck = CardDeckState()
        val first = deck.saveCard("雨の音", 2).card
        val second = deck.saveCard("木漏れ日", 2).card

        val selected = deck.selectCard(second.id)

        assertThat(selected).isEqualTo(second)
        assertThat(deck.selectedCardId).isEqualTo(second.id)
        assertThat(deck.selectedCardId).isNotEqualTo(first.id)
    }

    @Test
    fun selectingUnknownCardThrows() {
        val deck = CardDeckState()

        val error = runCatching { deck.selectCard(404) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("不存在")
    }
}


package com.mutsumi.card.domain.workflow

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardWorkflowTest {
    @Test
    fun saveCardCreatesCardAndFeedback() {
        val deck = CardDeckState()

        val result = deck.saveCard(
            keyText = "雨の音",
            valueImagePath = "images/value-1.png",
            baseImagePath = null,
            strokeCount = 3,
        )

        assertThat(deck.cards).hasSize(1)
        assertThat(deck.cards.first().keyText).isEqualTo("雨の音")
        assertThat(deck.cards.first().valueImagePath).isEqualTo("images/value-1.png")
        assertThat(deck.cards.first().valueDescription).doesNotContain("保存结果")
        assertThat(deck.cards.first().valueDescription).doesNotContain("当前预览")
        assertThat(result.message).contains("已保存")
    }

    @Test
    fun saveCardRejectsBlankKey() {
        val deck = CardDeckState()

        val error = runCatching {
            deck.saveCard(keyText = "   ", valueImagePath = "images/value-1.png", strokeCount = 3)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("key")
    }

    @Test
    fun saveCardRejectsEmptyDrawing() {
        val deck = CardDeckState()

        val error = runCatching {
            deck.saveCard(keyText = "雨の音", valueImagePath = "images/value-1.png", strokeCount = 0)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("图片")
    }

    @Test
    fun selectingCardUpdatesSelection() {
        val deck = CardDeckState()
        val first = deck.saveCard("雨の音", "images/value-1.png", strokeCount = 2).card
        val second = deck.saveCard("木漏れ日", "images/value-2.png", strokeCount = 2).card

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

    @Test
    fun saveCardRejectsBlankImagePath() {
        val deck = CardDeckState()

        val error = runCatching {
            deck.saveCard(keyText = "雨の音", valueImagePath = "   ", strokeCount = 2)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("图片")
    }

    @Test
    fun snapshotRoundTripKeepsImagePathsAndSelection() {
        val deck = CardDeckState()
        val card = deck.saveCard(
            keyText = "雨の音",
            valueImagePath = "images/value-1.png",
            baseImagePath = "images/base-1.png",
            strokeCount = 2,
        ).card

        val restored = CardDeckState.fromSnapshot(deck.toSnapshot())

        assertThat(restored.cards).containsExactly(card)
        assertThat(restored.selectedCardId).isEqualTo(card.id)
        assertThat(restored.nextCardId).isEqualTo(card.id + 1)
    }
}

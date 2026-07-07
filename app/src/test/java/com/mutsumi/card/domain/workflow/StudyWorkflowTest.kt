package com.mutsumi.card.domain.workflow

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.domain.review.ReviewFeedback
import org.junit.Test

class StudyWorkflowTest {
    @Test
    fun knownFeedbackMovesToAnotherCardWhenPossible() {
        val deck = CardDeckState()
        val first = deck.saveCard("雨の音", 2).card
        val second = deck.saveCard("木漏れ日", 2).card
        val session = StudySession()

        val next = session.applyFeedback(
            cards = deck.cards,
            currentCardId = first.id,
            feedback = ReviewFeedback.Know,
        )

        assertThat(next.card.id).isEqualTo(second.id)
        assertThat(next.message).contains("下一张")
    }

    @Test
    fun knownFeedbackKeepsSingleCardAvailable() {
        val deck = CardDeckState()
        val only = deck.saveCard("雨の音", 2).card
        val session = StudySession()

        val next = session.applyFeedback(
            cards = deck.cards,
            currentCardId = only.id,
            feedback = ReviewFeedback.Know,
        )

        assertThat(next.card.id).isEqualTo(only.id)
    }
}


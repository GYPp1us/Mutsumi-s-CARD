package com.mutsumi.card.domain.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class WeightedCardPickerTest {
    @Test
    fun excludesRecentCardsWhenEnoughAlternativesExist() {
        val picker = WeightedCardPicker(Random(0))
        val picked = picker.pick(
            cards = listOf(
                WeightedReviewCard(1, 5.0),
                WeightedReviewCard(2, 5.0),
                WeightedReviewCard(3, 5.0),
                WeightedReviewCard(4, 1.0),
                WeightedReviewCard(5, 1.0),
            ),
            recentCardIds = listOf(1, 2, 3),
        )
        assertThat(picked?.cardId).isAnyOf(4L, 5L)
    }

    @Test
    fun allowsRecentCardsWhenDeckIsSmall() {
        val picker = WeightedCardPicker(Random(0))
        val picked = picker.pick(
            cards = listOf(WeightedReviewCard(1, 1.0), WeightedReviewCard(2, 1.0)),
            recentCardIds = listOf(1, 2),
        )
        assertThat(picked?.cardId).isAnyOf(1L, 2L)
    }

    @Test
    fun returnsNullForEmptyDeck() {
        assertThat(WeightedCardPicker(Random(0)).pick(emptyList(), emptyList())).isNull()
    }
}


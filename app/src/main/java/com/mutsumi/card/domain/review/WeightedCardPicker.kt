package com.mutsumi.card.domain.review

import kotlin.random.Random

class WeightedCardPicker(
    private val random: Random = Random.Default,
) {
    fun pick(cards: List<WeightedReviewCard>, recentCardIds: List<Long>): WeightedReviewCard? {
        if (cards.isEmpty()) return null
        val recent = recentCardIds.takeLast(3).toSet()
        val candidates = cards.filterNot { it.cardId in recent }.ifEmpty { cards }
        val total = candidates.sumOf { it.weight.coerceAtLeast(0.01) }
        var cursor = random.nextDouble(total)
        for (candidate in candidates) {
            cursor -= candidate.weight.coerceAtLeast(0.01)
            if (cursor <= 0.0) return candidate
        }
        return candidates.last()
    }
}

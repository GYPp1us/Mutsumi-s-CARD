package com.mutsumi.card.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiCandidateQueueTest {
    @Test
    fun `saving a candidate consumes its group so it cannot be saved twice`() {
        val first = candidate("第一张")
        val second = candidate("第二张")

        val result = AiCandidateQueue.consume(
            groups = listOf(AiCandidateGroup(index = 1, cards = listOf(first, second))),
            groupIndex = 0,
            cardIndex = 0,
        )

        assertThat(result.groups).isEmpty()
        assertThat(result.groupIndex).isEqualTo(0)
        assertThat(result.selectedCardIndex).isEqualTo(0)
        assertThat(result.removedGroup).isTrue()
    }

    @Test
    fun `consuming the last candidate removes its group and selects the next group`() {
        val result = AiCandidateQueue.consume(
            groups = listOf(
                AiCandidateGroup(index = 1, cards = listOf(candidate("第一张"))),
                AiCandidateGroup(index = 2, cards = listOf(candidate("第二张"))),
            ),
            groupIndex = 0,
            cardIndex = 0,
        )

        assertThat(result.groups.map(AiCandidateGroup::index)).containsExactly(2)
        assertThat(result.groupIndex).isEqualTo(0)
        assertThat(result.selectedCardIndex).isEqualTo(0)
        assertThat(result.removedGroup).isTrue()
    }

    @Test
    fun `twenty plus range has an explicit upper bound for a single run`() {
        assertThat(AiGroupCountRange.TwentyPlus.accepts(24)).isTrue()
        assertThat(AiGroupCountRange.TwentyPlus.accepts(25)).isFalse()
    }

    @Test
    fun `candidate preview keys remain unique when AI returns duplicate text keys`() {
        assertThat(candidatePreviewItemKey(groupIndex = 2, cardIndex = 0))
            .isNotEqualTo(candidatePreviewItemKey(groupIndex = 2, cardIndex = 1))
    }

    private fun candidate(key: String) = AiCardCandidate(
        keyText = key,
        frontMarkdown = key,
        backMarkdown = key,
        frontPng = byteArrayOf(1),
        backPng = byteArrayOf(2),
    )
}

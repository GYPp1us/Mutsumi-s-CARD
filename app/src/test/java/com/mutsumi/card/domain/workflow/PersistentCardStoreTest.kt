package com.mutsumi.card.domain.workflow

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentCardStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun saveAndLoadSnapshotKeepsCardImagePath() {
        val store = PersistentCardStore(temp.root)
        val deck = CardDeckState()
        val card = deck.saveCard("雨の音", "images/value-1.png", strokeCount = 2).card

        store.save(deck.toSnapshot())
        val loaded = store.load()

        assertThat(loaded.cards).containsExactly(card)
        assertThat(loaded.selectedCardId).isEqualTo(card.id)
    }

    @Test
    fun saveImageWritesPngBytesAndReturnsRelativePath() {
        val store = PersistentCardStore(temp.root)

        val path = store.saveImage(byteArrayOf(1, 2, 3), prefix = "value")

        assertThat(path).startsWith("images/value-")
        assertThat(store.readImage(path)).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun saveImageRejectsEmptyBytes() {
        val store = PersistentCardStore(temp.root)

        val error = runCatching { store.saveImage(byteArrayOf(), prefix = "value") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("图片")
    }
}

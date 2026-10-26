package com.mutsumi.card.cards

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.domain.model.Deck
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryTreeTest {
    private val decks = listOf(Deck(1, "根卡组", 0), Deck(2, "子卡组", 0, 1), Deck(3, "孙卡组", 0, 2), Deck(4, "其他", 0))
    @Test fun `选择父子卡组批量操作不会重复计入子树`() {
        assertThat(LibraryTree.descendants(decks, setOf(1, 2))).containsExactly(1L, 2L, 3L)
        assertThat(LibraryTree.roots(decks, setOf(1, 2, 3))).containsExactly(1L)
    }
    @Test fun `父卡组不能移入自身或任何后代`() {
        assertThrows(IllegalArgumentException::class.java) { LibraryTree.requireMove(decks, setOf(1), 3) }
        assertThrows(IllegalArgumentException::class.java) { LibraryTree.requireMove(decks, setOf(1), 1) }
        LibraryTree.requireMove(decks, setOf(2), 4)
        LibraryTree.requireMove(decks, setOf(2), null)
    }
    @Test fun `面包屑按祖先顺序并保留中文名称`() {
        assertThat(LibraryTree.breadcrumbs(decks, 3).map { it.name }).containsExactly("根卡组", "子卡组", "孙卡组").inOrder()
    }
}

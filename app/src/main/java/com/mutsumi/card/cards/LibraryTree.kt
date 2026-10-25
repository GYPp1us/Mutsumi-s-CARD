package com.mutsumi.card.cards

import com.mutsumi.card.domain.model.Deck

/** 管理树只使用稳定父级关系，名称可重复。 */
object LibraryTree {
    fun descendants(decks: List<Deck>, selected: Set<Long>): Set<Long> {
        val children = decks.groupBy { it.parentId }
        val result = linkedSetOf<Long>()
        fun visit(id: Long) { if (result.add(id)) children[id].orEmpty().forEach { visit(it.id) } }
        selected.forEach(::visit)
        return result
    }

    fun roots(decks: List<Deck>, selected: Set<Long>): Set<Long> = selected.filterTo(linkedSetOf()) { id ->
        breadcrumbs(decks, id).dropLast(1).none { it.id in selected }
    }

    fun breadcrumbs(decks: List<Deck>, id: Long?): List<Deck> {
        val rows = decks.associateBy { it.id }
        val visited = hashSetOf<Long>()
        val result = mutableListOf<Deck>()
        var current = id
        while (current != null) {
            require(visited.add(current)) { "卡组层级存在循环" }
            val row = requireNotNull(rows[current]) { "卡组不存在" }
            result += row; current = row.parentId
        }
        return result.asReversed()
    }

    fun requireMove(decks: List<Deck>, selected: Set<Long>, target: Long?) {
        require(target == null || decks.any { it.id == target }) { "目标卡组不存在" }
        require(target !in descendants(decks, selected)) { "不能把卡组移入自身或后代" }
    }
}

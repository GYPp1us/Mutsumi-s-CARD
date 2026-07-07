package com.mutsumi.card.domain.workflow

import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.review.WeightedCardPicker
import com.mutsumi.card.domain.review.WeightedReviewCard

data class MemoryCard(
    val id: Long,
    val keyText: String,
    val valueDescription: String,
    val strokeCount: Int,
    val weight: Double = 1.0,
)

data class SaveCardResult(
    val card: MemoryCard,
    val message: String,
)

data class StudyTransition(
    val card: MemoryCard,
    val message: String,
)

class CardDeckState(
    initialCards: List<MemoryCard> = emptyList(),
) {
    private val mutableCards = initialCards.toMutableList()
    private var nextId = (initialCards.maxOfOrNull { it.id } ?: 0L) + 1L

    var selectedCardId: Long? = initialCards.firstOrNull()?.id
        private set

    val cards: List<MemoryCard>
        get() = mutableCards.toList()

    fun saveCard(keyText: String, strokeCount: Int): SaveCardResult {
        require(keyText.isNotBlank()) { "key 不能为空" }
        require(strokeCount > 0) { "图片 value 至少需要一笔绘制内容" }

        val card = MemoryCard(
            id = nextId++,
            keyText = keyText.trim(),
            valueDescription = "手绘图片，笔画 $strokeCount",
            strokeCount = strokeCount,
        )
        mutableCards += card
        selectedCardId = card.id
        return SaveCardResult(card = card, message = "已保存：${card.keyText}")
    }

    fun selectCard(cardId: Long): MemoryCard {
        val card = mutableCards.firstOrNull { it.id == cardId }
            ?: throw IllegalArgumentException("卡片不存在：$cardId")
        selectedCardId = card.id
        return card
    }
}

class StudySession(
    private val picker: WeightedCardPicker = WeightedCardPicker(),
) {
    fun applyFeedback(
        cards: List<MemoryCard>,
        currentCardId: Long?,
        feedback: ReviewFeedback,
    ): StudyTransition {
        check(cards.isNotEmpty()) { "没有可推荐的卡片" }

        val current = cards.firstOrNull { it.id == currentCardId } ?: cards.first()
        if (feedback != ReviewFeedback.Know) {
            return StudyTransition(current, feedback.messageForCurrentCard())
        }

        val candidates = cards.filterNot { it.id == current.id }.ifEmpty { cards }
        val picked = picker.pick(
            cards = candidates.map { WeightedReviewCard(cardId = it.id, weight = it.weight) },
            recentCardIds = listOf(current.id),
        ) ?: current
        val nextCard = cards.first { it.id == picked.cardId }
        return StudyTransition(nextCard, "已标记为记住了，切换到下一张：${nextCard.keyText}")
    }

    private fun ReviewFeedback.messageForCurrentCard(): String {
        return when (this) {
            ReviewFeedback.Again -> "已标记为记不住，稍后会更常出现"
            ReviewFeedback.Unsure -> "已标记为模糊，稍后会稍微增加"
            ReviewFeedback.Know -> error("Know feedback should advance to next card")
        }
    }
}

object BackupActions {
    fun exportMessage(cardCount: Int): String {
        return "导出入口已触发：当前卡片 $cardCount 张，下一步接入系统文件保存器"
    }

    fun importMessage(): String {
        return "导入入口已触发：下一步接入系统文件选择器"
    }
}

fun seedCards(): List<MemoryCard> {
    return listOf(
        MemoryCard(id = 1, keyText = "雨の音", valueDescription = "示例图片 value", strokeCount = 2, weight = 1.0),
        MemoryCard(id = 2, keyText = "木漏れ日", valueDescription = "示例图片 value", strokeCount = 3, weight = 1.3),
        MemoryCard(id = 3, keyText = "夕焼け", valueDescription = "示例图片 value", strokeCount = 2, weight = 0.7),
    )
}


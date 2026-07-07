package com.mutsumi.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mutsumi.card.backup.BackupScreen
import com.mutsumi.card.cards.CardsScreen
import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.workflow.MemoryCard
import com.mutsumi.card.domain.workflow.StudySession
import com.mutsumi.card.domain.workflow.seedCards
import com.mutsumi.card.draw.DrawScreen
import com.mutsumi.card.study.StudyScreen
import com.mutsumi.card.ui.adaptive.AdaptiveScaffold
import com.mutsumi.card.ui.navigation.AppDestination

@Composable
fun MutsumiCardApp() {
    var selected by remember { mutableStateOf(AppDestination.Study) }
    val cards = remember { mutableStateListOf<MemoryCard>().apply { addAll(seedCards()) } }
    var nextCardId by remember { mutableLongStateOf((cards.maxOfOrNull { it.id } ?: 0L) + 1L) }
    var selectedCardId by remember { mutableStateOf(cards.firstOrNull()?.id) }
    var studyCardId by remember { mutableStateOf(cards.firstOrNull()?.id) }
    val studySession = remember { StudySession() }

    AdaptiveScaffold(
        destinations = AppDestination.entries.toList(),
        selected = selected,
        onSelect = { selected = it },
    ) {
        when (selected) {
            AppDestination.Study -> StudyScreen(
                cards = cards,
                currentCardId = studyCardId,
                onFeedback = { cardId, feedback ->
                    val transition = studySession.applyFeedback(cards, cardId, feedback)
                    studyCardId = transition.card.id
                    selectedCardId = transition.card.id
                    transition.message
                },
            )
            AppDestination.Cards -> CardsScreen(
                cards = cards,
                selectedCardId = selectedCardId,
                onSelectCard = { card ->
                    selectedCardId = card.id
                    studyCardId = card.id
                },
                onAddSampleCard = {
                    val card = MemoryCard(
                        id = nextCardId++,
                        keyText = "新卡片 ${nextCardId - 1}",
                        valueDescription = "示例图片 value",
                        strokeCount = 1,
                    )
                    cards += card
                    selectedCardId = card.id
                    studyCardId = card.id
                },
            )
            AppDestination.Draw -> DrawScreen(
                onSaveCard = { keyText, strokeCount ->
                    val card = MemoryCard(
                        id = nextCardId++,
                        keyText = keyText.trim(),
                        valueDescription = "手绘图片，笔画 $strokeCount",
                        strokeCount = strokeCount,
                    )
                    cards += card
                    selectedCardId = card.id
                    studyCardId = card.id
                    "已保存：${card.keyText}，当前卡组 ${cards.size} 张"
                },
            )
            AppDestination.Backup -> BackupScreen(cardCount = cards.size)
        }
    }
}

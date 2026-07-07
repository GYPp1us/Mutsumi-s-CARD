package com.mutsumi.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mutsumi.card.backup.BackupScreen
import com.mutsumi.card.cards.CardsScreen
import com.mutsumi.card.domain.workflow.CardDeckSnapshot
import com.mutsumi.card.domain.workflow.CardDeckState
import com.mutsumi.card.domain.workflow.MemoryCard
import com.mutsumi.card.domain.workflow.PersistentCardStore
import com.mutsumi.card.domain.workflow.StudySession
import com.mutsumi.card.draw.DrawScreen
import com.mutsumi.card.study.StudyScreen
import com.mutsumi.card.ui.adaptive.AdaptiveScaffold
import com.mutsumi.card.ui.navigation.AppDestination
import java.io.File

@Composable
fun MutsumiCardApp() {
    val context = LocalContext.current
    val storeRoot = remember { File(context.filesDir, "card-store") }
    val store = remember { PersistentCardStore(storeRoot) }
    val initialSnapshot = remember { store.load() }

    var selected by remember { mutableStateOf(AppDestination.Study) }
    val cards = remember { mutableStateListOf<MemoryCard>().apply { addAll(initialSnapshot.cards) } }
    var nextCardId by remember { mutableLongStateOf(initialSnapshot.nextCardId) }
    var selectedCardId by remember { mutableStateOf(initialSnapshot.selectedCardId ?: cards.firstOrNull()?.id) }
    var studyCardId by remember { mutableStateOf(selectedCardId) }
    val studySession = remember { StudySession() }

    fun snapshot(): CardDeckSnapshot {
        return CardDeckSnapshot(
            cards = cards.toList(),
            nextCardId = nextCardId,
            selectedCardId = selectedCardId,
        )
    }

    fun persist() {
        store.save(snapshot())
    }

    fun replaceFromDeck(deck: CardDeckState) {
        cards.clear()
        cards.addAll(deck.cards)
        nextCardId = deck.nextCardId
        selectedCardId = deck.selectedCardId
        studyCardId = deck.selectedCardId
        persist()
    }

    AdaptiveScaffold(
        destinations = AppDestination.entries.toList(),
        selected = selected,
        onSelect = { selected = it },
    ) {
        when (selected) {
            AppDestination.Study -> StudyScreen(
                cards = cards,
                currentCardId = studyCardId,
                imageRoot = storeRoot,
                onFeedback = { cardId, feedback ->
                    val transition = studySession.applyFeedback(cards, cardId, feedback)
                    studyCardId = transition.card.id
                    selectedCardId = transition.card.id
                    persist()
                    transition.message
                },
            )
            AppDestination.Cards -> CardsScreen(
                cards = cards,
                selectedCardId = selectedCardId,
                imageRoot = storeRoot,
                onSelectCard = { card ->
                    selectedCardId = card.id
                    studyCardId = card.id
                    persist()
                },
                onAddSampleCard = {
                    val card = MemoryCard(
                        id = nextCardId++,
                        keyText = "新卡片 ${nextCardId - 1}",
                        valueImagePath = "sample://new-${nextCardId - 1}",
                        valueDescription = "示例图片 value",
                        strokeCount = 1,
                    )
                    cards += card
                    selectedCardId = card.id
                    studyCardId = card.id
                    persist()
                },
            )
            AppDestination.Draw -> DrawScreen(
                onSaveCard = { keyText, image ->
                    val valueImagePath = store.saveImage(image.pngBytes, prefix = "value")
                    val baseImagePath = image.baseImageBytes?.let { store.saveImage(it, prefix = "base") }
                    val deck = CardDeckState.fromSnapshot(snapshot())
                    val result = deck.saveCard(
                        keyText = keyText,
                        valueImagePath = valueImagePath,
                        baseImagePath = baseImagePath,
                        strokeCount = image.strokeCount,
                    )
                    replaceFromDeck(deck)
                    selectedCardId = result.card.id
                    studyCardId = result.card.id
                    persist()
                    result.message
                },
            )
            AppDestination.Backup -> BackupScreen(cardCount = cards.size)
        }
    }
}

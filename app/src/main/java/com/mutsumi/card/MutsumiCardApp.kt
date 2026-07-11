package com.mutsumi.card

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mutsumi.card.backup.BackupScreen
import com.mutsumi.card.backup.BackupViewModel
import com.mutsumi.card.cards.CardsCallbacks
import com.mutsumi.card.cards.CardsContextPane
import com.mutsumi.card.cards.CardsEvent
import com.mutsumi.card.cards.CardsScreen
import com.mutsumi.card.cards.CardsViewModel
import com.mutsumi.card.cards.StoredCardValueImage
import com.mutsumi.card.data.AppContainer
import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.workflow.MemoryCard
import com.mutsumi.card.draw.DrawScreen
import com.mutsumi.card.study.StudyScreen
import com.mutsumi.card.ui.adaptive.AdaptiveLayoutPolicy
import com.mutsumi.card.ui.adaptive.AppLayoutMode
import com.mutsumi.card.ui.adaptive.AdaptiveScaffold
import com.mutsumi.card.ui.components.FeedbackController
import com.mutsumi.card.ui.components.FeedbackHost
import com.mutsumi.card.ui.navigation.AppDestination
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MutsumiCardApp(appContainer: AppContainer) {
    var selectedName by rememberSaveable { mutableStateOf(AppDestination.Study.name) }
    val selected = AppDestination.valueOf(selectedName)
    var selectedDeckId by rememberSaveable { mutableLongStateOf(0L) }
    val feedback = remember { FeedbackController() }
    val scope = rememberCoroutineScope()

    val cardsViewModel: CardsViewModel = viewModel {
        CardsViewModel(appContainer.cardRepository, appContainer.appPreferences, SavedStateHandle())
    }
    val cardsState by cardsViewModel.uiState.collectAsState()
    val backupViewModel: BackupViewModel = viewModel { BackupViewModel(appContainer.backupOperations) }

    LaunchedEffect(appContainer) { selectedDeckId = appContainer.ensureSelectedDeck() }
    LaunchedEffect(cardsViewModel) {
        cardsViewModel.events.collect { event ->
            when (event) {
                is CardsEvent.Message -> feedback.show(event.text)
                is CardsEvent.OpenNewCard -> {
                    selectedDeckId = event.deckId
                    selectedName = AppDestination.Draw.name
                }
                is CardsEvent.OpenRedraw -> {
                    feedback.show("重新绘制会在后续版本保留原图；当前请新建卡片")
                    selectedName = AppDestination.Draw.name
                }
            }
        }
    }

    BoxWithConstraints {
        val mode = AdaptiveLayoutPolicy.mode(maxWidth.value.toInt(), maxHeight.value.toInt())
        val callbacks = cardsViewModel.callbacks()
        AdaptiveScaffold(
            selected = selected,
            onSelect = { selectedName = it.name },
            contextContent = {
                if (selected == AppDestination.Cards) {
                    CardsContextPane(
                        card = cardsState.selectedCard,
                        imageContent = { card, modifier -> StoredCardValueImage(card, appContainer.imageStore, modifier) },
                        keySaveRevision = cardsState.keySaveRevision,
                        isBusy = cardsState.isBusy,
                        compactHeight = maxHeight <= 420.dp,
                        onSaveKey = callbacks.onSaveKey,
                        onRedraw = callbacks.onRedraw,
                        onArchive = callbacks.onArchive,
                        onDelete = callbacks.onDelete,
                        modifier = Modifier,
                    )
                } else Text(selected.label)
            },
            onOpenSettings = { scope.launch { feedback.show("设置将在后续版本提供") } },
            snackbarHost = { FeedbackHost(feedback) },
        ) {
            when (selected) {
                AppDestination.Study -> StudyDestination(appContainer, selectedDeckId, feedback)
                AppDestination.Cards -> CardsScreen(
                    uiState = cardsState,
                    layoutMode = mode,
                    imageContent = { card, modifier -> StoredCardValueImage(card, appContainer.imageStore, modifier) },
                    callbacks = callbacks,
                )
                AppDestination.Draw -> DrawScreen { key, image ->
                    val deckId = selectedDeckId.takeIf { it > 0 } ?: cardsState.currentDeck?.id
                    requireNotNull(deckId) { "当前没有可用卡组" }
                    scope.launch {
                        val cardId = appContainer.cardRepository.saveCard(deckId, key, image.pngBytes)
                        feedback.show("卡片已保存：$key")
                        selectedName = AppDestination.Study.name
                    }
                    "正在保存卡片"
                }
                AppDestination.Backup -> BackupScreen(backupViewModel)
            }
        }
    }
}

@Composable
private fun StudyDestination(appContainer: AppContainer, deckId: Long, feedback: FeedbackController) {
    val scope = rememberCoroutineScope()
    val cards by remember(deckId) {
        if (deckId > 0) appContainer.cardRepository.cards(deckId) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    var currentId by rememberSaveable(deckId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(cards) { if (cards.none { it.id == currentId }) currentId = cards.firstOrNull()?.id }
    val legacyCards = cards.map {
        MemoryCard(it.id, it.keyText, it.valueImagePath, "", 1, weight = it.review.weight)
    }
    val imageRoot = cards.firstOrNull()?.let { appContainer.imageStore.resolve(it.valueImagePath).parentFile?.parentFile } ?: File(".")
    StudyScreen(legacyCards, currentId, imageRoot) { cardId, result ->
        scope.launch {
            appContainer.cardRepository.applyFeedback(cardId, result, System.currentTimeMillis())
            currentId = appContainer.cardRepository.pickRecommendedCard(deckId, listOf(cardId))?.id
            feedback.show(if (result == ReviewFeedback.Know) "记住了，已切换下一张" else "已记录本次反馈")
        }
        "正在记录"
    }
}

private fun CardsViewModel.callbacks() = CardsCallbacks(
    onQueryChange = ::setQuery,
    onClearQuery = ::clearQuery,
    onSelectCard = ::selectCard,
    onSwitchDeck = ::switchDeck,
    onCreateDeck = ::createDeck,
    onRenameDeck = ::renameCurrentDeck,
    onNewCard = ::requestNewCard,
    onSaveKey = ::updateSelectedKey,
    onRedraw = ::requestRedrawSelectedCard,
    onArchive = ::archiveSelectedCard,
    onDelete = ::deleteSelectedCard,
)

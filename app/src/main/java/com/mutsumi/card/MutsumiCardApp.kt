package com.mutsumi.card

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mutsumi.card.ai.AiBatchScreen
import com.mutsumi.card.ai.AiBatchViewModel
import com.mutsumi.card.ai.AiSettingsScreen
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
import com.mutsumi.card.draw.DrawSaveResult
import com.mutsumi.card.draw.DrawScreen
import com.mutsumi.card.study.StudyScreen
import com.mutsumi.card.settings.AndroidUpdateDownloader
import com.mutsumi.card.settings.AppUpdateEvent
import com.mutsumi.card.settings.AppUpdateViewModel
import com.mutsumi.card.ui.adaptive.AdaptiveLayoutPolicy
import com.mutsumi.card.ui.adaptive.AdaptiveScaffold
import com.mutsumi.card.ui.components.FeedbackController
import com.mutsumi.card.ui.components.FeedbackHost
import com.mutsumi.card.ui.navigation.AppDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.File

@Composable
fun MutsumiCardApp(appContainer: AppContainer) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusReporter = appContainer.focusReporter
    DisposableEffect(lifecycleOwner, focusReporter) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> focusReporter?.setForeground(true)
                Lifecycle.Event.ON_STOP -> focusReporter?.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        focusReporter?.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            focusReporter?.setForeground(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var selectedName by rememberSaveable { mutableStateOf(AppDestination.Study.name) }
    val selected = AppDestination.valueOf(selectedName)
    var selectedDeckId by rememberSaveable { mutableLongStateOf(0L) }
    val feedback = remember { FeedbackController() }
    val scope = rememberCoroutineScope()

    val cardsViewModel: CardsViewModel = viewModel {
        CardsViewModel(appContainer.cardRepository, appContainer.appPreferences, SavedStateHandle())
    }
    val cardsState by cardsViewModel.uiState.collectAsState()
    val backupViewModel: BackupViewModel = viewModel {
        BackupViewModel(
            operations = appContainer.backupOperations,
            cloudOperations = appContainer.cloudBackupOperations,
            cloudSettings = appContainer.cloudBackupSettings,
        )
    }
    val aiViewModel: AiBatchViewModel = viewModel {
        AiBatchViewModel(
            context = context,
            repository = appContainer.cardRepository,
            settingsStore = requireNotNull(appContainer.aiSettingsStore),
        )
    }
    val appUpdateViewModel: AppUpdateViewModel = viewModel {
        AppUpdateViewModel(
            settingsStore = requireNotNull(appContainer.appUpdateSettingsStore),
            checker = requireNotNull(appContainer.appUpdateChecker),
        )
    }
    val updateDownloader = remember(context) { AndroidUpdateDownloader(context.applicationContext) }

    LaunchedEffect(appUpdateViewModel, updateDownloader) {
        appUpdateViewModel.events.collect { event ->
            when (event) {
                is AppUpdateEvent.DownloadRequested -> {
                    val message = try {
                        updateDownloader.enqueue(event.update)
                    } catch (error: SecurityException) {
                        "更新下载启动失败：${error.message ?: "系统拒绝了下载请求"}"
                    } catch (error: IllegalArgumentException) {
                        "更新下载启动失败：${error.message ?: "更新地址无效"}"
                    } catch (error: IllegalStateException) {
                        "更新下载启动失败：${error.message ?: "系统下载服务不可用"}"
                    }
                    feedback.show(message)
                }
            }
        }
    }

    LaunchedEffect(appContainer) {
        try {
            appContainer.initializeDefaultSeed(context)
            selectedDeckId = appContainer.ensureSelectedDeck()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            feedback.show("应用初始化失败：${error.message ?: "无法准备本地数据"}")
        }
    }
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

    BoxWithConstraints(Modifier.testTag(if (selectedDeckId > 0) "app-initialized" else "app-initializing")) {
        val mode = AdaptiveLayoutPolicy.mode(maxWidth.value.toInt(), maxHeight.value.toInt())
        val callbacks = cardsViewModel.callbacks()
        val contextContent: (@Composable () -> Unit)? = if (selected == AppDestination.Cards) {
            {
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
            }
        } else {
            null
        }
        AdaptiveScaffold(
            selected = selected,
            onSelect = { selectedName = it.name },
            contextContent = contextContent,
            onOpenSettings = { selectedName = AppDestination.Settings.name },
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
                    if (deckId == null) {
                        val message = "当前没有可用卡组"
                        scope.launch { feedback.show("卡片保存失败：$message") }
                        return@DrawScreen DrawSaveResult.Rejected(message)
                    }
                    selectedDeckId = deckId
                    try {
                        appContainer.cardRepository.saveCard(
                            deckId = deckId,
                            keyText = key,
                            frontPng = image.frontPngBytes,
                            backPng = image.backPngBytes,
                        )
                    } catch (error: IOException) {
                        val message = error.message ?: "无法写入卡片图片"
                        scope.launch { feedback.show("卡片保存失败：$message") }
                        return@DrawScreen DrawSaveResult.Rejected(message)
                    }
                    scope.launch { feedback.show("卡片已保存：$key") }
                    selectedName = AppDestination.Study.name
                    DrawSaveResult.Saved("卡片已保存")
                }
                AppDestination.Backup -> BackupScreen(backupViewModel, feedback)
                AppDestination.AiBatch -> AiBatchScreen(aiViewModel, feedback)
                AppDestination.Settings -> AiSettingsScreen(
                    store = requireNotNull(appContainer.aiSettingsStore),
                    appUpdateViewModel = appUpdateViewModel,
                    focusReporter = focusReporter,
                    feedback = feedback,
                )
            }
        }
    }
}

@Composable
private fun StudyDestination(appContainer: AppContainer, deckId: Long, feedback: FeedbackController) {
    val scope = rememberCoroutineScope()
    val focusReporter = appContainer.focusReporter
    DisposableEffect(focusReporter) {
        onDispose { focusReporter?.setStudying(false) }
    }
    val cards by remember(deckId) {
        if (deckId > 0) appContainer.cardRepository.cards(deckId) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    LaunchedEffect(focusReporter, cards.isNotEmpty()) {
        focusReporter?.setStudying(cards.isNotEmpty())
    }
    var currentId by rememberSaveable(deckId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(cards) { if (cards.none { it.id == currentId }) currentId = cards.firstOrNull()?.id }
    val legacyCards = cards.map {
        MemoryCard(
            id = it.id,
            keyText = it.keyText,
            valueImagePath = it.valueImagePath,
            valueDescription = "",
            strokeCount = 1,
            weight = it.review.weight,
            frontImagePath = it.frontImagePath,
        )
    }
    val imageRoot = cards.firstOrNull()?.let { appContainer.imageStore.resolve(it.valueImagePath).parentFile?.parentFile } ?: File(".")
    StudyScreen(legacyCards, currentId, imageRoot) { cardId, result ->
        scope.launch {
            try {
                appContainer.cardRepository.applyFeedback(cardId, result, System.currentTimeMillis())
                currentId = appContainer.cardRepository.pickRecommendedCard(deckId, listOf(cardId))?.id
                feedback.show(if (result == ReviewFeedback.Know) "记住了，已切换下一张" else "已记录本次反馈")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                feedback.show("记录复习反馈失败：${error.message ?: "未知错误"}")
            }
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

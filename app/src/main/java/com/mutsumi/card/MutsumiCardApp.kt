package com.mutsumi.card

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mutsumi.card.data.AppContainer
import com.mutsumi.card.ui.adaptive.AdaptiveScaffold
import com.mutsumi.card.ui.components.FeedbackController
import com.mutsumi.card.ui.components.FeedbackHost
import com.mutsumi.card.ui.navigation.AppDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private sealed interface InitializationState {
    data object Loading : InitializationState
    data class Ready(val deckId: Long) : InitializationState
    data class Failed(val message: String) : InitializationState
}

@Composable
fun MutsumiCardApp(appContainer: AppContainer) {
    var selectedName by rememberSaveable { mutableStateOf(AppDestination.Study.name) }
    val selected = AppDestination.valueOf(selectedName)
    var initialization by remember { mutableStateOf<InitializationState>(InitializationState.Loading) }
    var initializationAttempt by rememberSaveable { mutableStateOf(0) }
    val feedback = remember { FeedbackController() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(appContainer, initializationAttempt) {
        initialization = InitializationState.Loading
        try {
            initialization = InitializationState.Ready(appContainer.ensureSelectedDeck())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            initialization = InitializationState.Failed(error.message ?: "初始化失败")
            feedback.report(error, "初始化失败")
        }
    }

    AdaptiveScaffold(
        selected = selected,
        onSelect = { selectedName = it.name },
        contextContent = {
            Text(selected.contextLabel)
        },
        onOpenSettings = { scope.launch { feedback.show("设置将在后续版本提供") } },
        snackbarHost = { FeedbackHost(feedback) },
    ) {
        when (val state = initialization) {
            InitializationState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is InitializationState.Failed -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.message)
                        Button(onClick = { initializationAttempt += 1 }) { Text("重试") }
                    }
                }
            }
            is InitializationState.Ready -> PendingDestination(selected)
        }
    }
}

@Composable
private fun PendingDestination(destination: AppDestination) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(destination.pendingLabel)
    }
}

private val AppDestination.pendingLabel: String
    get() = when (this) {
        AppDestination.Study -> "当前卡组还没有卡片"
        AppDestination.Cards -> "当前卡组还没有卡片"
        AppDestination.Draw -> "录入界面准备中"
        AppDestination.Backup -> "备份界面准备中"
    }

private val AppDestination.contextLabel: String
    get() = when (this) {
        AppDestination.Study -> "本组进度将在学习流程接入后显示"
        AppDestination.Cards -> "选择卡片后显示详情"
        AppDestination.Draw -> "卡片信息和预览将在绘图流程接入后显示"
        AppDestination.Backup -> "备份统计将在备份流程接入后显示"
    }

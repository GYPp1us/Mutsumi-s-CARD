package com.mutsumi.card.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val automaticCheckEnabled: Boolean = true,
    val automaticDownloadEnabled: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val isChecking: Boolean = false,
    val availableUpdate: AvailableUpdate? = null,
    val message: String = "将自动检查 GitHub Release 更新。",
    val errorMessage: String? = null,
)

sealed interface AppUpdateEvent {
    data class DownloadRequested(val update: AvailableUpdate) : AppUpdateEvent
}

class AppUpdateViewModel(
    private val settingsStore: AppUpdateSettingsStore,
    private val checker: AppUpdateChecker,
    private val clock: () -> Long = System::currentTimeMillis,
    private val operationScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope
        get() = operationScope ?: viewModelScope

    private val mutableState = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = mutableState.asStateFlow()

    // 自动检查可能早于设置页组合完成；Channel 会保留待交付事件，避免首启自动下载丢失。
    private val mutableEvents = Channel<AppUpdateEvent>(Channel.BUFFERED)
    val events: Flow<AppUpdateEvent> = mutableEvents.receiveAsFlow()

    init {
        scope.launch {
            settingsStore.settings.collectLatest { settings ->
                mutableState.value = mutableState.value.copy(
                    automaticCheckEnabled = settings.automaticCheckEnabled,
                    automaticDownloadEnabled = settings.automaticDownloadEnabled,
                    lastCheckedAt = settings.lastCheckedAt,
                )
                if (settings.automaticCheckEnabled && shouldCheckAutomatically(settings.lastCheckedAt)) {
                    checkForUpdate(automatic = true)
                }
            }
        }
    }

    fun setAutomaticCheckEnabled(enabled: Boolean) {
        scope.launch {
            try {
                settingsStore.setAutomaticCheckEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError("更新设置保存失败：${error.message ?: "无法保存自动检查设置"}")
            }
        }
    }

    fun setAutomaticDownloadEnabled(enabled: Boolean) {
        scope.launch {
            try {
                settingsStore.setAutomaticDownloadEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError("更新设置保存失败：${error.message ?: "无法保存自动下载设置"}")
            }
        }
    }

    fun checkNow() = checkForUpdate(automatic = false)

    fun requestDownload() {
        val update = mutableState.value.availableUpdate ?: return
        emitDownloadRequested(update)
    }

    private fun checkForUpdate(automatic: Boolean) {
        if (mutableState.value.isChecking) return
        mutableState.value = mutableState.value.copy(
            isChecking = true,
            message = if (automatic) "正在自动检查更新…" else "正在检查更新…",
            errorMessage = null,
        )
        scope.launch {
            try {
                val update = checker.check()
                val checkedAt = clock()
                settingsStore.markCheckedAt(checkedAt)
                mutableState.value = mutableState.value.copy(
                    isChecking = false,
                    lastCheckedAt = checkedAt,
                    availableUpdate = update,
                    message = update?.let { "发现新版本 ${it.versionName}" } ?: "当前已是最新版本",
                    errorMessage = null,
                )
                if (automatic && update != null && mutableState.value.automaticDownloadEnabled) {
                    emitDownloadRequested(update)
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(isChecking = false)
                throw cancelled
            } catch (error: Exception) {
                reportError("更新检查失败：${error.message ?: "无法连接更新服务"}")
            }
        }
    }

    private fun shouldCheckAutomatically(lastCheckedAt: Long): Boolean =
        clock() - lastCheckedAt >= AUTOMATIC_CHECK_INTERVAL_MILLIS

    private fun reportError(message: String) {
        mutableState.value = mutableState.value.copy(
            isChecking = false,
            message = message,
            errorMessage = message,
        )
    }

    private fun emitDownloadRequested(update: AvailableUpdate) {
        check(mutableEvents.trySend(AppUpdateEvent.DownloadRequested(update)).isSuccess) {
            "更新下载事件投递失败"
        }
    }

    private companion object {
        const val AUTOMATIC_CHECK_INTERVAL_MILLIS = 12L * 60L * 60L * 1000L
    }
}

package com.mutsumi.card.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

interface BackupOperations {
    suspend fun export(output: OutputStream): ExportSummary
    suspend fun import(input: InputStream): ImportSummary
}

data class BackupUiState(
    val isBusy: Boolean = false,
    val message: String = "可导出完整备份，或从备份包导入新副本。",
    val cloudServerUrl: String = "",
    val cloudUsername: String = "",
    val cloudPassword: String = "",
    val cloudRemoteDirectory: String = "MutsumiCard",
    val isCloudConfigured: Boolean = false,
    val isEditingCloudConfig: Boolean = true,
    val cloudSnapshots: List<CloudSnapshotSummary> = emptyList(),
    val latestCloudEvent: String? = null,
    val errorMessage: String? = null,
    val cloudEventSucceeded: Boolean? = null,
    val libraryIndex: CloudLibraryIndex? = null,
    val syncPreview: CloudSyncPreview? = null,
    val syncProgress: CloudSyncProgress? = null,
)

class BackupViewModel(
    private val operations: BackupOperations,
    private val operationScope: CoroutineScope? = null,
    private val cloudSettings: CloudBackupSettings? = null,
    private val syncOperations: CloudSyncOperations? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()
    private var cloudInitialized = false

    fun previewSync(mode: CloudSyncMode, snapshotId: String? = null) {
        val sync = syncOperations ?: return reportError("当前未装配安全同步服务")
        val config = configuredCloudConfig() ?: return reportError("请先保存 WebDAV 连接")
        launchOperation("读取${mode.label}预览") {
            val preview = sync.preview(config, mode, snapshotId)
            mutableState.value = mutableState.value.copy(syncPreview = preview, syncProgress = null)
            "请核对预览，尚未执行${mode.label}"
        }
    }

    fun dismissSyncPreview() {
        if (!mutableState.value.isBusy) mutableState.value = mutableState.value.copy(syncPreview = null)
    }

    fun confirmSyncPreview(deletionConfirmed: Boolean = false) {
        if (mutableState.value.isBusy) return
        val preview = mutableState.value.syncPreview ?: return
        if (preview.mode.deletes && !deletionConfirmed) return reportError("请在预览页滑动确认删除")
        val sync = syncOperations ?: return
        val config = configuredCloudConfig() ?: return
        launchOperation(preview.mode.label) {
            val result = sync.execute(config, preview.token) { progress ->
                mutableState.value = mutableState.value.copy(syncProgress = progress)
            }
            val refresh = try { sync.inspect(config) to null }
            catch (error: IOException) { mutableState.value.libraryIndex to "同步已完成，刷新云端状态失败，请稍后刷新" }
            val index = refresh.first
            val message = buildSuccessMessage("${preview.mode.label}完成：处理 ${result.deckCount} 个卡组、${result.cardCount} 张卡片", result.warnings) +
                (refresh.second?.let { "；$it" } ?: "")
            mutableState.value = mutableState.value.copy(syncPreview = null, libraryIndex = index,
                cloudSnapshots = index?.snapshots.orEmpty(), latestCloudEvent = message, cloudEventSucceeded = true)
            message
        }
    }

    fun initializeCloud() {
        if (cloudInitialized) return
        cloudInitialized = true
        try {
            val config = cloudSettings?.load() ?: return
            mutableState.value = mutableState.value.copy(
                cloudServerUrl = config.serverUrl,
                cloudUsername = config.username,
                cloudPassword = config.password,
                cloudRemoteDirectory = config.remoteDirectory,
                isCloudConfigured = true,
                isEditingCloudConfig = false,
            )
            refreshCloud()
        } catch (error: Exception) {
            reportError("云端初始化失败：${error.message ?: "无法读取云端配置"}")
        }
    }

    fun setCloudServerUrl(value: String) = updateCloudFields { copy(cloudServerUrl = value) }
    fun setCloudUsername(value: String) = updateCloudFields { copy(cloudUsername = value) }
    fun setCloudPassword(value: String) = updateCloudFields { copy(cloudPassword = value) }
    fun setCloudRemoteDirectory(value: String) = updateCloudFields { copy(cloudRemoteDirectory = value) }

    fun toggleCloudConfig() {
        if (!mutableState.value.isBusy) {
            mutableState.value = mutableState.value.copy(
                isEditingCloudConfig = !mutableState.value.isEditingCloudConfig,
            )
        }
    }

    fun saveCloudConfig() {
        val sync = syncOperations ?: return reportError("当前未装配安全同步服务")
        val settings = cloudSettings ?: return reportError("无法保存连接")
        val config = currentCloudConfig()
        launchOperation("保存连接") {
            WebDavClient.validateConfig(config)
            val index = sync.inspect(config)
            settings.save(config)
            mutableState.value = mutableState.value.copy(isCloudConfigured = true, isEditingCloudConfig = false,
                libraryIndex = index, cloudSnapshots = index?.snapshots.orEmpty(), syncPreview = null)
            "云端连接已保存；请选择操作并先查看预览"
        }
    }

    fun refreshCloud() {
        val sync = syncOperations ?: return
        val config = configuredCloudConfig() ?: return
        launchOperation("连接云端") {
            val index = sync.inspect(config)
            mutableState.value = mutableState.value.copy(libraryIndex = index, cloudSnapshots = index?.snapshots.orEmpty())
            "云端索引已刷新"
        }
    }

    fun onExportDocumentResult(output: OutputStream?) {
        if (output == null) {
            if (!mutableState.value.isBusy) mutableState.value = mutableState.value.copy(message = "已取消导出")
            return
        }
        launchOperation("导出") {
            output.use { operations.export(it) }.let { result ->
                buildSuccessMessage("已导出 ${result.cardCount} 张卡片", result.warnings)
            }
        }
    }

    fun onImportDocumentResult(input: InputStream?) {
        if (input == null) {
            if (!mutableState.value.isBusy) mutableState.value = mutableState.value.copy(message = "已取消导入")
            return
        }
        launchOperation("导入") {
            input.use { operations.import(it) }.let { result ->
                buildSuccessMessage("已导入 ${result.deckCount} 个卡组、${result.cardCount} 张卡片", result.warnings)
            }
        }
    }

    fun onExportAccessFailure(error: Exception) {
        reportError("导出失败：${error.message ?: "无法打开目标文件"}")
    }

    fun onImportAccessFailure(error: Exception) {
        reportError("导入失败：${error.message ?: "无法打开来源文件"}")
    }

    private fun launchOperation(label: String, block: suspend () -> String) {
        if (mutableState.value.isBusy) return
        mutableState.value = mutableState.value.copy(isBusy = true, message = "正在$label…", errorMessage = null)
        (operationScope ?: viewModelScope).launch {
            try {
                val message = block()
                mutableState.value = mutableState.value.copy(message = message, errorMessage = null)
            } catch (error: CancellationException) { throw error }
            catch (error: IOException) { showOperationFailure(label, error) }
            catch (error: BackupFormatException) { showOperationFailure(label, error) }
            finally { mutableState.value = mutableState.value.copy(isBusy = false) }
        }
    }

    private fun showOperationFailure(label: String, error: Exception) {
        val message = "${label}失败：${error.message ?: fallbackFor(label)}"
        mutableState.value = mutableState.value.copy(
            isBusy = false,
            message = message,
            errorMessage = message,
            syncPreview = null,
            latestCloudEvent = message.takeIf { label.startsWith("云端") } ?: mutableState.value.latestCloudEvent,
            cloudEventSucceeded = false.takeIf { label.startsWith("云端") } ?: mutableState.value.cloudEventSucceeded,
        )
    }

    private fun fallbackFor(label: String): String = when {
        label.contains("云端") -> "云端连接或读写失败"
        label == "导入" || label == "导出" -> "文件读写失败"
        else -> "未知错误"
    }

    private fun reportError(message: String) {
        if (!mutableState.value.isBusy) mutableState.value = mutableState.value.copy(
            message = message,
            errorMessage = message,
        )
    }

    private fun updateCloudFields(transform: BackupUiState.() -> BackupUiState) {
        if (!mutableState.value.isBusy) mutableState.value = mutableState.value.transform().copy(syncPreview = null, isCloudConfigured = false)
    }

    private fun currentCloudConfig(): CloudBackupConfig = mutableState.value.let { state ->
        CloudBackupConfig(
            serverUrl = state.cloudServerUrl.trim(),
            username = state.cloudUsername.trim(),
            password = state.cloudPassword,
            remoteDirectory = state.cloudRemoteDirectory.trim(),
        )
    }

    private fun configuredCloudConfig(): CloudBackupConfig? =
        currentCloudConfig().takeIf { mutableState.value.isCloudConfigured }

    private fun buildSuccessMessage(success: String, warnings: List<String>): String =
        if (warnings.isEmpty()) success else "$success：${warnings.joinToString("；")}"
}

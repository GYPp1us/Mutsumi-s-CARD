package com.mutsumi.card.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.IOException
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
    val cloudAddedOrChangedCount: Int = 0,
    val cloudDeletedCount: Int = 0,
    val latestCloudEvent: String? = null,
)

class BackupViewModel(
    private val operations: BackupOperations,
    private val operationScope: CoroutineScope? = null,
    private val cloudOperations: CloudBackupOperations? = null,
    private val cloudSettings: CloudBackupSettings? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()
    private var cloudInitialized = false

    fun initializeCloud() {
        if (cloudInitialized) return
        cloudInitialized = true
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
        val settings = cloudSettings ?: error("当前未装配云端备份配置")
        val config = currentCloudConfig()
        val cloud = cloudOperations ?: error("当前未装配云端备份")
        launchOperation("保存连接") {
            WebDavClient.validateConfig(config)
            settings.save(config)
            mutableState.value = mutableState.value.copy(
                isCloudConfigured = true,
                isEditingCloudConfig = false,
            )
            val overview = cloud.inspect(config)
            applyOverview(overview)
            "云端连接已保存"
        }
    }

    fun refreshCloud() {
        val cloud = cloudOperations ?: return
        val config = configuredCloudConfig() ?: return
        launchOperation("连接云端") {
            val overview = cloud.inspect(config)
            applyOverview(overview)
            "云端已连接"
        }
    }

    fun backupToCloud() {
        val cloud = cloudOperations ?: error("当前未装配云端备份")
        val config = configuredCloudConfig() ?: run {
            mutableState.value = mutableState.value.copy(
                isEditingCloudConfig = true,
                message = "请先填写并保存 WebDAV 连接",
            )
            return
        }
        launchOperation("云端备份") {
            val result = cloud.backup(config)
            applyOverview(result.overview)
            val success = "云端增量备份完成"
            mutableState.value = mutableState.value.copy(latestCloudEvent = success)
            buildSuccessMessage(success, result.warnings)
        }
    }

    fun restoreCloudSnapshot(snapshotId: String) {
        val cloud = cloudOperations ?: error("当前未装配云端备份")
        val config = configuredCloudConfig() ?: return
        launchOperation("云端恢复") {
            val result = cloud.restore(config, snapshotId)
            val success = "已从云端导入 ${result.deckCount} 个卡组、${result.cardCount} 张卡片"
            mutableState.value = mutableState.value.copy(latestCloudEvent = success)
            buildSuccessMessage(success, result.warnings)
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
        if (!mutableState.value.isBusy) mutableState.value = mutableState.value.copy(
            message = "导出失败：${error.message ?: "无法打开目标文件"}",
        )
    }

    fun onImportAccessFailure(error: Exception) {
        if (!mutableState.value.isBusy) mutableState.value = mutableState.value.copy(
            message = "导入失败：${error.message ?: "无法打开来源文件"}",
        )
    }

    private fun launchOperation(label: String, block: suspend () -> String) {
        if (mutableState.value.isBusy) return
        mutableState.value = mutableState.value.copy(isBusy = true, message = "正在$label…")
        (operationScope ?: viewModelScope).launch {
            try {
                val resultMessage = block()
                mutableState.value = mutableState.value.copy(isBusy = false, message = resultMessage)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BackupFormatException) {
                mutableState.value = mutableState.value.copy(
                    isBusy = false,
                    message = "${label}失败：${error.message ?: "未知错误"}",
                )
            } catch (error: IOException) {
                mutableState.value = mutableState.value.copy(
                    isBusy = false,
                    message = "${label}失败：${error.message ?: "读写失败"}",
                )
            }
        }
    }

    private fun updateCloudFields(transform: BackupUiState.() -> BackupUiState) {
        if (!mutableState.value.isBusy) mutableState.value = mutableState.value.transform()
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

    private fun applyOverview(overview: CloudBackupOverview) {
        mutableState.value = mutableState.value.copy(
            cloudSnapshots = overview.snapshots,
            cloudAddedOrChangedCount = overview.addedOrChangedCount,
            cloudDeletedCount = overview.deletedCount,
        )
    }

    private fun buildSuccessMessage(success: String, warnings: List<String>): String =
        if (warnings.isEmpty()) success else "$success；${warnings.joinToString("；")}"
}

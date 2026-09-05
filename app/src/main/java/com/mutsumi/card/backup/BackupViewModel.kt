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
    val cloudAddedOrChangedCount: Int = 0,
    val cloudDeletedCount: Int = 0,
    val latestCloudEvent: String? = null,
    val errorMessage: String? = null,
    val cloudDeckCount: Int = 0,
    val cloudAddedOrChangedDeckCount: Int = 0,
    val cloudDeletedDeckCount: Int = 0,
    val pushDelete: Boolean = false,
    val pullDelete: Boolean = false,
    val pendingRestorePreview: CloudRestorePreview? = null,
    val pendingRestorePullDelete: Boolean? = null,
    val pendingRestoreConflictResolution: CloudConflictResolution? = null,
    val pendingRestoreExpectedConflicts: List<CloudConflict>? = null,
    val pendingCloudConflict: PendingCloudConflict? = null,
    val cloudEventSucceeded: Boolean? = null,
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
    fun setPushDelete(value: Boolean) = updateCloudFields { copy(pushDelete = value) }
    fun setPullDelete(value: Boolean) = updateCloudFields { copy(pullDelete = value) }

    fun toggleCloudConfig() {
        if (!mutableState.value.isBusy) {
            mutableState.value = mutableState.value.copy(
                isEditingCloudConfig = !mutableState.value.isEditingCloudConfig,
            )
        }
    }

    fun saveCloudConfig() {
        val settings = cloudSettings ?: run {
            reportError("保存连接失败：当前未装配云端配置")
            return
        }
        val cloud = cloudOperations ?: run {
            reportError("保存连接失败：当前未装配云端备份")
            return
        }
        val config = currentCloudConfig()
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
        backupToCloud(
            pushDelete = mutableState.value.pushDelete,
            conflictResolution = null,
        )
    }

    private fun backupToCloud(
        pushDelete: Boolean,
        conflictResolution: CloudConflictResolution?,
        expectedConflicts: List<CloudConflict>? = null,
    ) {
        val cloud = cloudOperations ?: run {
            reportError("云端备份失败：当前未装配云端备份")
            return
        }
        val config = configuredCloudConfig() ?: run {
            mutableState.value = mutableState.value.copy(
                isEditingCloudConfig = true,
                message = "请先填写并保存 WebDAV 连接",
                errorMessage = "请先填写并保存 WebDAV 连接",
            )
            return
        }
        launchOperation("云端备份", CloudConflictAction.Backup(pushDelete)) {
            val result = cloud.backup(config, pushDelete, conflictResolution, expectedConflicts)
            applyOverview(result.overview)
            val stats = result.overview.current
            val success = "云端增量备份完成：卡组 ${stats.deckCount}（+${stats.addedOrChangedDeckCount}/-${stats.deletedDeckCount}），卡片 ${stats.cardCount}（+${stats.addedOrChangedCount}/-${stats.deletedCount}）"
            mutableState.value = mutableState.value.copy(latestCloudEvent = success, cloudEventSucceeded = true)
            buildSuccessMessage(success, result.warnings)
        }
    }

    fun restoreCloudSnapshot(snapshotId: String) {
        restoreCloudSnapshot(
            snapshotId = snapshotId,
            pullDelete = mutableState.value.pullDelete,
            conflictResolution = null,
        )
    }

    private fun restoreCloudSnapshot(
        snapshotId: String,
        pullDelete: Boolean,
        conflictResolution: CloudConflictResolution?,
        expectedConflicts: List<CloudConflict>? = null,
    ) {
        val cloud = cloudOperations ?: run {
            reportError("云端恢复失败：当前未装配云端备份")
            return
        }
        val config = configuredCloudConfig() ?: run {
            reportError("云端恢复失败：请先配置 WebDAV 连接")
            return
        }
        launchOperation("云端恢复", CloudConflictAction.Restore(snapshotId, pullDelete)) {
            val result = cloud.restore(config, snapshotId, pullDelete, conflictResolution, expectedConflicts)
            applyOverview(cloud.inspect(config))
            val success = "已从云端导入 ${result.deckCount} 个卡组、${result.cardCount} 张卡片"
            mutableState.value = mutableState.value.copy(latestCloudEvent = success, cloudEventSucceeded = true)
            buildSuccessMessage(success, result.warnings)
        }
    }

    fun openRestorePreview(snapshotId: String) {
        openRestorePreview(
            snapshotId = snapshotId,
            pullDelete = mutableState.value.pullDelete,
            conflictResolution = null,
        )
    }

    private fun openRestorePreview(
        snapshotId: String,
        pullDelete: Boolean,
        conflictResolution: CloudConflictResolution?,
        expectedConflicts: List<CloudConflict>? = null,
    ) {
        val cloud = cloudOperations ?: run {
            reportError("读取恢复预览失败：当前未配置云端备份")
            return
        }
        val config = configuredCloudConfig() ?: run {
            reportError("读取恢复预览失败：请先配置 WebDAV 连接")
            return
        }
        launchOperation("读取恢复预览", CloudConflictAction.PreviewRestore(snapshotId, pullDelete)) {
            val preview = cloud.previewRestore(config, snapshotId, pullDelete, conflictResolution, expectedConflicts)
            mutableState.value = mutableState.value.copy(
                pendingRestorePreview = preview,
                pendingRestorePullDelete = pullDelete,
                pendingRestoreConflictResolution = conflictResolution,
                pendingRestoreExpectedConflicts = expectedConflicts,
                cloudEventSucceeded = true,
            )
            "请确认恢复 ${preview.stats.deckCount} 个卡组、${preview.stats.cardCount} 张卡片"
        }
    }

    fun dismissRestorePreview() {
        if (!mutableState.value.isBusy) {
            mutableState.value = mutableState.value.copy(
                pendingRestorePreview = null,
                pendingRestorePullDelete = null,
                pendingRestoreConflictResolution = null,
                pendingRestoreExpectedConflicts = null,
            )
        }
    }

    fun confirmRestorePreview() {
        val preview = mutableState.value.pendingRestorePreview ?: return
        val cloud = cloudOperations ?: run {
            reportError("云端恢复失败：当前未配置云端备份")
            return
        }
        val config = configuredCloudConfig() ?: run {
            reportError("云端恢复失败：请先配置 WebDAV 连接")
            return
        }
        val pullDelete = requireNotNull(mutableState.value.pendingRestorePullDelete)
        val conflictResolution = mutableState.value.pendingRestoreConflictResolution
        val expectedConflicts = mutableState.value.pendingRestoreExpectedConflicts
        launchOperation("云端恢复", CloudConflictAction.Restore(preview.snapshotId, pullDelete)) {
            val result = cloud.restore(config, preview.snapshotId, pullDelete, conflictResolution, expectedConflicts)
            applyOverview(cloud.inspect(config))
            val success = "云端恢复完成：${result.deckCount} 个卡组、${result.cardCount} 张卡片"
            mutableState.value = mutableState.value.copy(
                pendingRestorePreview = null,
                pendingRestorePullDelete = null,
                pendingRestoreConflictResolution = null,
                pendingRestoreExpectedConflicts = null,
                latestCloudEvent = success,
                cloudEventSucceeded = true,
            )
            buildSuccessMessage(success, result.warnings)
        }
    }

    fun dismissCloudConflict() {
        if (!mutableState.value.isBusy) {
            mutableState.value = mutableState.value.copy(
                pendingCloudConflict = null,
                message = "已取消云端冲突处理，数据未变更。",
            )
        }
    }

    fun resolveCloudConflict(resolution: CloudConflictResolution) {
        if (mutableState.value.isBusy) return
        val pending = mutableState.value.pendingCloudConflict ?: return
        mutableState.value = mutableState.value.copy(pendingCloudConflict = null)
        when (val action = pending.action) {
            is CloudConflictAction.Backup -> backupToCloud(action.pushDelete, resolution, pending.conflicts)
            is CloudConflictAction.PreviewRestore -> openRestorePreview(
                snapshotId = action.snapshotId,
                pullDelete = action.pullDelete,
                conflictResolution = resolution,
                expectedConflicts = pending.conflicts,
            )
            is CloudConflictAction.Restore -> restoreCloudSnapshot(
                snapshotId = action.snapshotId,
                pullDelete = action.pullDelete,
                conflictResolution = resolution,
                expectedConflicts = pending.conflicts,
            )
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

    private fun launchOperation(
        label: String,
        conflictAction: CloudConflictAction? = null,
        block: suspend () -> String,
    ) {
        if (mutableState.value.isBusy) return
        mutableState.value = mutableState.value.copy(isBusy = true, message = "正在$label…", errorMessage = null)
        (operationScope ?: viewModelScope).launch {
            try {
                val resultMessage = block()
                mutableState.value = mutableState.value.copy(isBusy = false, message = resultMessage, errorMessage = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: CloudConflictException) {
                if (conflictAction == null) throw error
                val message = "检测到 ${error.entries.size} 项同步冲突，请选择保留本地或采用云端版本。"
                mutableState.value = mutableState.value.copy(
                    isBusy = false,
                    message = message,
                    errorMessage = null,
                    latestCloudEvent = message,
                    cloudEventSucceeded = false,
                    pendingRestorePreview = if (conflictAction is CloudConflictAction.Restore) {
                        null
                    } else {
                        mutableState.value.pendingRestorePreview
                    },
                    pendingRestorePullDelete = if (conflictAction is CloudConflictAction.Restore) {
                        null
                    } else {
                        mutableState.value.pendingRestorePullDelete
                    },
                    pendingRestoreConflictResolution = if (conflictAction is CloudConflictAction.Restore) {
                        null
                    } else {
                        mutableState.value.pendingRestoreConflictResolution
                    },
                    pendingRestoreExpectedConflicts = if (conflictAction is CloudConflictAction.Restore) {
                        null
                    } else {
                        mutableState.value.pendingRestoreExpectedConflicts
                    },
                    pendingCloudConflict = PendingCloudConflict(error.entries, conflictAction),
                )
            } catch (error: IOException) {
                showOperationFailure(label, error)
            } catch (error: BackupFormatException) {
                showOperationFailure(label, error)
            }
        }
    }

    private fun showOperationFailure(label: String, error: Exception) {
        val message = "${label}失败：${error.message ?: fallbackFor(label)}"
        mutableState.value = mutableState.value.copy(
            isBusy = false,
            message = message,
            errorMessage = message,
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
            cloudAddedOrChangedCount = overview.current.addedOrChangedCount,
            cloudDeletedCount = overview.current.deletedCount,
            cloudDeckCount = overview.current.deckCount,
            cloudAddedOrChangedDeckCount = overview.current.addedOrChangedDeckCount,
            cloudDeletedDeckCount = overview.current.deletedDeckCount,
        )
    }

    private fun buildSuccessMessage(success: String, warnings: List<String>): String =
        if (warnings.isEmpty()) success else "$success：${warnings.joinToString("；")}"
}

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
)

class BackupViewModel(
    private val operations: BackupOperations,
    private val operationScope: CoroutineScope? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()

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
        if (!mutableState.value.isBusy) mutableState.value = BackupUiState(message = "导出失败：${error.message ?: "无法打开目标文件"}")
    }

    fun onImportAccessFailure(error: Exception) {
        if (!mutableState.value.isBusy) mutableState.value = BackupUiState(message = "导入失败：${error.message ?: "无法打开来源文件"}")
    }

    private fun launchOperation(label: String, block: suspend () -> String) {
        if (mutableState.value.isBusy) return
        mutableState.value = BackupUiState(isBusy = true, message = "正在$label…")
        (operationScope ?: viewModelScope).launch {
            try {
                mutableState.value = BackupUiState(message = block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: BackupFormatException) {
                mutableState.value = BackupUiState(message = "${label}失败：${error.message ?: "未知错误"}")
            } catch (error: IOException) {
                mutableState.value = BackupUiState(message = "${label}失败：${error.message ?: "读写失败"}")
            }
        }
    }

    private fun buildSuccessMessage(success: String, warnings: List<String>): String =
        if (warnings.isEmpty()) success else "$success；${warnings.joinToString("；")}"
}

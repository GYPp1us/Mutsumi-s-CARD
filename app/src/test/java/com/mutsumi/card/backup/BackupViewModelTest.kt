package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    @Test
    fun `选择器取消会给出明确反馈且不进入忙碌状态`() = runTest {
        val viewModel = viewModel(this)

        viewModel.onExportDocumentResult(null)
        assertThat(viewModel.state.value.message).isEqualTo("已取消导出")
        viewModel.onImportDocumentResult(null)
        assertThat(viewModel.state.value.message).isEqualTo("已取消导入")
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `执行期间禁用重复提交并在成功后显示数量`() = runTest {
        val operations = SuspendingOperations()
        val viewModel = viewModel(this, operations)

        viewModel.onExportDocumentResult(ByteArrayOutputStream())
        assertThat(viewModel.state.value.isBusy).isTrue()
        viewModel.onImportDocumentResult(ByteArrayInputStream(byteArrayOf(1)))
        assertThat(operations.importCalls).isEqualTo(0)

        operations.finishExport = true
        advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(BackupUiState(false, "已导出 3 张卡片"))
    }

    @Test
    fun `导入成功和失败均给出明确反馈`() = runTest {
        val success = viewModel(this, object : BackupOperations {
            override suspend fun export(output: OutputStream): ExportSummary = ExportSummary(0)
            override suspend fun import(input: InputStream) = ImportSummary(2, 5)
        })
        success.onImportDocumentResult(ByteArrayInputStream(byteArrayOf(1)))
        advanceUntilIdle()
        assertThat(success.state.value.message).isEqualTo("已导入 2 个卡组、5 张卡片")

        val failure = viewModel(this, object : BackupOperations {
            override suspend fun export(output: OutputStream): ExportSummary = throw BackupFormatException("无法导出")
            override suspend fun import(input: InputStream): ImportSummary = error("unused")
        })
        failure.onExportDocumentResult(ByteArrayOutputStream())
        advanceUntilIdle()
        assertThat(failure.state.value.message).isEqualTo("导出失败：无法导出")
        assertThat(failure.state.value.isBusy).isFalse()
    }

    @Test
    fun `SAF 流打开失败有明确反馈且未知程序异常继续抛出`() {
        val errors = mutableListOf<Throwable>()
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> errors += error })
        val viewModel = BackupViewModel(object : BackupOperations {
            override suspend fun export(output: OutputStream): ExportSummary = throw IllegalStateException("程序错误")
            override suspend fun import(input: InputStream): ImportSummary = error("unused")
        }, scope)
        viewModel.onExportAccessFailure(IOException("没有写入权限"))
        assertThat(viewModel.state.value.message).isEqualTo("导出失败：没有写入权限")

        viewModel.onExportDocumentResult(ByteArrayOutputStream())
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(errors.single()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `成功结果包含清理警告时不会显示为失败`() = runTest {
        val viewModel = viewModel(this, object : BackupOperations {
            override suspend fun export(output: OutputStream) =
                ExportSummary(3, listOf("临时文件清理已排队"))
            override suspend fun import(input: InputStream) =
                ImportSummary(1, 2, listOf("临时文件清理已排队"))
        })
        viewModel.onExportDocumentResult(ByteArrayOutputStream())
        advanceUntilIdle()
        assertThat(viewModel.state.value.message).contains("已导出 3 张卡片")
        assertThat(viewModel.state.value.message).contains("清理已排队")
    }

    @Test fun `增量推送必须预览后才能执行且执行中不重复提交`() = runTest {
        val sync = PreviewSync()
        val vm = configuredViewModel(this, sync)
        vm.confirmSyncPreview()
        assertThat(sync.executions).isEqualTo(0)
        vm.previewSync(CloudSyncMode.Push)
        advanceUntilIdle()
        assertThat(sync.executions).isEqualTo(0)
        assertThat(vm.state.value.syncPreview).isNotNull()
        vm.confirmSyncPreview(); vm.confirmSyncPreview()
        advanceUntilIdle()
        assertThat(sync.executions).isEqualTo(1)
        assertThat(vm.state.value.syncPreview).isNull()
    }

    @Test fun `删除预览还需滑动确认标记配置变化后旧预览失效`() = runTest {
        val sync = PreviewSync()
        val vm = configuredViewModel(this, sync)
        vm.previewSync(CloudSyncMode.PullDelete); advanceUntilIdle()
        vm.confirmSyncPreview(); advanceUntilIdle()
        assertThat(sync.executions).isEqualTo(0)
        assertThat(vm.state.value.message).contains("滑动确认")
        vm.confirmSyncPreview(deletionConfirmed = true); advanceUntilIdle()
        assertThat(sync.executions).isEqualTo(1)
        vm.previewSync(CloudSyncMode.Pull); advanceUntilIdle()
        vm.setCloudRemoteDirectory("另一个目录")
        vm.confirmSyncPreview(); advanceUntilIdle()
        assertThat(sync.executions).isEqualTo(1)
        assertThat(vm.state.value.syncPreview).isNull()
    }

    @Test fun `执行成功后刷新失败仍明确报告操作成功不复用令牌`() = runTest {
        val sync = PreviewSync()
        val vm = configuredViewModel(this, sync)
        vm.previewSync(CloudSyncMode.Push); advanceUntilIdle()
        sync.failInspect = true
        vm.confirmSyncPreview(); advanceUntilIdle()
        assertThat(sync.executions).isEqualTo(1)
        assertThat(vm.state.value.message).contains("同步已完成")
        assertThat(vm.state.value.errorMessage).isNull()
        assertThat(vm.state.value.syncPreview).isNull()
    }

    private suspend fun configuredViewModel(scope: TestScope, sync: PreviewSync): BackupViewModel {
        val vm = BackupViewModel(object : BackupOperations {
            override suspend fun export(output: OutputStream) = ExportSummary(0)
            override suspend fun import(input: InputStream) = ImportSummary(0, 0)
        }, scope, object : CloudBackupSettings {
            override fun load(): CloudBackupConfig? = null
            override fun save(config: CloudBackupConfig) = Unit
        }, sync)
        vm.setCloudServerUrl("https://example.test"); vm.setCloudUsername("测试"); vm.setCloudPassword("仅测试")
        vm.saveCloudConfig(); scope.testScheduler.advanceUntilIdle()
        return vm
    }

    private class PreviewSync : CloudSyncOperations {
        var executions = 0
        var failInspect = false
        override suspend fun inspect(config: CloudBackupConfig): CloudLibraryIndex? {
            if (failInspect) throw IOException("断线")
            return null
        }
        override suspend fun preview(config: CloudBackupConfig, mode: CloudSyncMode, snapshotId: String?): CloudSyncPreview =
            CloudSyncPreview("令牌", mode, CloudSyncPlan(CloudSnapshotDocument(snapshot = CloudSnapshot(emptyList(), emptyList(), emptyList()), images = emptyList()), emptyList()), 0, 0)
        override suspend fun execute(config: CloudBackupConfig, token: String, progress: (CloudSyncProgress) -> Unit): ImportSummary {
            executions++; kotlinx.coroutines.yield(); progress(CloudSyncProgress("完成", 1, 1)); return ImportSummary(0, 1)
        }
    }

    private fun viewModel(scope: TestScope, operations: BackupOperations = SuspendingOperations()) =
        BackupViewModel(operations, scope)

    private class SuspendingOperations : BackupOperations {
        var finishExport = false
        var importCalls = 0
        override suspend fun export(output: OutputStream): ExportSummary {
            while (!finishExport) kotlinx.coroutines.yield()
            return ExportSummary(3)
        }
        override suspend fun import(input: InputStream): ImportSummary {
            importCalls++
            return ImportSummary(1, 1)
        }
    }

}

package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class ValidatedBackupOperationsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `导入完整验证后提交并清理临时目录`() = runTest {
        var calls = 0
        val operations = operations(ImportGateway { batch ->
            calls++
            ImportSummary(batch.snapshot.decks.size, batch.snapshot.cards.size)
        })

        val result = operations.import(ByteArrayInputStream(createArchive()))

        assertThat(result).isEqualTo(ImportSummary(1, 1))
        assertThat(calls).isEqualTo(1)
        assertThat(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".backup-") }).isTrue()
    }

    @Test
    fun `提交后清理失败返回成功警告并加入清理队列`() = runTest {
        val queue = RecordingCleanupQueue()
        val validator = BackupValidator(
            pngValidator = testPngValidator,
            archiveCleanup = { throw IOException("目录占用") },
        )
        val operations = operations(
            gateway = ImportGateway { ImportSummary(1, 1) },
            validator = validator,
            cleanupQueue = queue,
        )

        val result = operations.import(ByteArrayInputStream(createArchive()))

        assertThat(result.deckCount).isEqualTo(1)
        assertThat(result.warnings.single()).contains("清理")
        assertThat(queue.warnings).hasSize(1)
    }

    @Test
    fun `导出先完成私有ZIP再复制到SAF且复制失败明确残留警告`() = runTest {
        val operations = operations(ImportGateway { ImportSummary(0, 0) })
        val output = object : OutputStream() {
            override fun write(b: Int) = throw IOException("SAF 写入失败")
        }

        val error = runCatching { operations.export(output) }.exceptionOrNull()

        assertThat(error).isInstanceOf(BackupDestinationException::class.java)
        assertThat(error).hasMessageThat().contains("可能残留")
        assertThat(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".backup-export-") }).isTrue()
    }

    @Test
    fun `导出复制期间取消会清理私有临时文件`() = runTest {
        val operations = operations(ImportGateway { ImportSummary(0, 0) })
        lateinit var job: Job
        val output = object : OutputStream() {
            override fun write(b: Int) { job.cancel() }
            override fun write(b: ByteArray, off: Int, len: Int) { job.cancel() }
        }
        job = launch { operations.export(output) }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".backup-export-") }).isTrue()
    }

    private fun operations(
        gateway: ImportGateway,
        validator: BackupValidator = BackupValidator(pngValidator = testPngValidator),
        cleanupQueue: CleanupWarningQueue = RecordingCleanupQueue(),
    ): ValidatedBackupOperations {
        val image = File(temporaryFolder.root, "source-${System.nanoTime()}.png").writeFixture(validPng())
        return ValidatedBackupOperations(
            snapshotSource = BackupSnapshotSource { BackupExportData(validSnapshot(), mapOf("images/value-2.png" to image)) },
            service = BackupService(appVersion = "0.4.0", syncFile = {}),
            validator = validator,
            importer = BackupImporter(gateway, idGenerator = sequenceOf(10L, 20L).iterator()::next),
            temporaryDirectory = temporaryFolder.root,
            cleanupQueue = cleanupQueue,
            now = { 5L },
        )
    }

    private class RecordingCleanupQueue : CleanupWarningQueue {
        val warnings = mutableListOf<CleanupWarning>()
        override fun enqueue(warning: CleanupWarning) { warnings += warning }
    }
}

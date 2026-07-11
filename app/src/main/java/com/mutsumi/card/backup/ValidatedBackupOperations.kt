package com.mutsumi.card.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class BackupExportData(val snapshot: BackupSnapshot, val images: Map<String, File>)
fun interface BackupSnapshotSource { suspend fun load(): BackupExportData }

data class ExportSummary(val cardCount: Int, val warnings: List<String> = emptyList())
data class CleanupWarning(val path: String, val reason: String)
fun interface CleanupWarningQueue { fun enqueue(warning: CleanupWarning) }

object ProcessCleanupWarningQueue : CleanupWarningQueue {
    private val pending = CopyOnWriteArrayList<CleanupWarning>()
    override fun enqueue(warning: CleanupWarning) { pending += warning }
    fun snapshot(): List<CleanupWarning> = pending.toList()
}

class BackupDestinationException(message: String, cause: Throwable) : IOException(message, cause)

class ValidatedBackupOperations(
    private val snapshotSource: BackupSnapshotSource,
    private val service: BackupService,
    private val validator: BackupValidator,
    private val importer: BackupImporter,
    private val temporaryDirectory: File,
    private val cleanupQueue: CleanupWarningQueue = ProcessCleanupWarningQueue,
    private val now: () -> Long = System::currentTimeMillis,
) : BackupOperations {
    override suspend fun export(output: OutputStream): ExportSummary = withContext(Dispatchers.IO) {
        requireDirectory(temporaryDirectory)
        val session = File(temporaryDirectory, ".backup-export-${UUID.randomUUID()}")
        check(session.mkdir()) { "无法创建导出临时目录" }
        var result: ExportSummary? = null
        var failure: Exception? = null
        try {
            val data = snapshotSource.load()
            val archive = File(session, "complete.zip")
            service.exportToFile(data.snapshot, data.images, archive, now())
            try {
                archive.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                throw BackupDestinationException("导出失败，目标文件可能残留不完整内容", error)
            }
            ExportSummary(data.snapshot.cards.size).also { result = it }
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            cleanupSession(session, result, failure)?.let { warning -> result = result?.copy(warnings = result!!.warnings + warning) }
        }
        result ?: error("导出未生成结果")
    }

    override suspend fun import(input: InputStream): ImportSummary = withContext(Dispatchers.IO) {
        val archive = validator.validateToTemporary(input, temporaryDirectory)
        var committed: ImportSummary? = null
        var failure: Exception? = null
        try {
            importer.importCopy(archive).also { committed = it }
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            try {
                archive.close()
            } catch (cleanupError: Exception) {
                val warning = CleanupWarning(archive.temporaryDirectory.path, cleanupError.message ?: "临时文件清理失败")
                cleanupQueue.enqueue(warning)
                if (committed != null) {
                    committed = committed!!.copy(warnings = committed!!.warnings + "导入已完成，但临时文件清理已排队")
                } else {
                    failure?.addSuppressed(cleanupError)
                }
            }
        }
        committed ?: error("导入未生成结果")
    }

    private fun cleanupSession(session: File, success: ExportSummary?, failure: Exception?): String? = try {
        if (session.exists() && !session.deleteRecursively()) error("无法删除导出临时目录")
        null
    } catch (cleanupError: Exception) {
        cleanupQueue.enqueue(CleanupWarning(session.path, cleanupError.message ?: "临时文件清理失败"))
        if (success == null) {
            failure?.addSuppressed(cleanupError)
            null
        } else {
            "导出已完成，但临时文件清理已排队"
        }
    }

    private fun requireDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) error("无法创建备份临时目录")
    }
}

package com.mutsumi.card.backup

import com.mutsumi.card.data.image.ValuePngValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

class BackupValidator(
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val limits: BackupLimits = BackupLimits(),
    private val pngValidator: ValuePngValidator = ValuePngValidator(),
    private val archiveCleanup: (File) -> Unit = { directory ->
        if (directory.exists() && !directory.deleteRecursively()) error("无法删除备份临时目录：${directory.path}")
    },
) {
    suspend fun validateToTemporary(input: InputStream, temporaryDirectory: File): ValidatedArchive =
        withContext(Dispatchers.IO) {
            requireDirectory(temporaryDirectory)
            val session = File(temporaryDirectory, ".backup-${UUID.randomUUID()}")
            check(session.mkdir()) { "无法创建备份验证目录" }
            try {
                val sourceZip = File(session, "source.zip")
                copyInput(input, sourceZip)
                validateZip(sourceZip, session)
            } catch (error: Exception) {
                try {
                    if (session.exists() && !session.deleteRecursively()) {
                        error.addSuppressed(IllegalStateException("无法清理备份验证目录：${session.path}"))
                    }
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        }

    private suspend fun copyInput(input: InputStream, target: File) {
        input.use { source ->
            FileOutputStream(target).use { output ->
                copyBounded(source, output, limits.maxArchiveBytes, "备份压缩文件大小超限")
                output.flush()
                output.fd.sync()
            }
        }
    }

    private suspend fun validateZip(sourceZip: File, session: File): ValidatedArchive {
        val entries = linkedMapOf<String, ExtractedEntry>()
        var total = 0L
        try {
            ZipInputStream(sourceZip.inputStream().buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    if (!isSafeArchivePath(path)) throw BackupFormatException("ZIP 路径非法：$path")
                    if (entry.isDirectory) throw BackupFormatException("ZIP 不允许目录条目：$path")
                    if (path in entries) throw BackupFormatException("ZIP 包含重复路径：$path")
                    if (entries.size >= limits.maxEntries) throw BackupFormatException("ZIP 条目数量超限")
                    val extracted = when {
                        path == MANIFEST_PATH || path == DATABASE_PATH -> extractJson(zip, path, total)
                        path.startsWith("images/") -> extractImage(zip, path, total, session)
                        else -> throw BackupFormatException("ZIP 资源路径非法：$path")
                    }
                    total += extracted.size
                    entries[path] = extracted
                    zip.closeEntry()
                }
            }
        } catch (error: BackupFormatException) {
            throw error
        } catch (error: ZipException) {
            throw BackupFormatException("备份 ZIP 已损坏", error)
        }

        val manifestEntry = entries[MANIFEST_PATH] ?: throw BackupFormatException("缺失 manifest.json")
        val databaseEntry = entries[DATABASE_PATH] ?: throw BackupFormatException("缺失 database.json")
        val manifest = decode<BackupManifest>(requireNotNull(manifestEntry.bytes), MANIFEST_PATH)
        if (manifest.formatVersion != BACKUP_FORMAT_VERSION) {
            throw BackupFormatException("不兼容的备份版本：${manifest.formatVersion}")
        }
        validateManifest(manifest, entries)
        val snapshot = decode<BackupSnapshot>(requireNotNull(databaseEntry.bytes), DATABASE_PATH)
        validateSnapshot(snapshot)
        validateCounts(manifest, snapshot)

        val imagePaths = snapshot.cards.map { it.valueImagePath }.toSet()
        val declaredImages = manifest.resources.map { it.path }.filter { it.startsWith("images/") }.toSet()
        if (declaredImages != imagePaths) throw BackupFormatException("清单图片与数据库引用不一致")
        val images = imagePaths.associateWith { path ->
            entries[path]?.file ?: throw BackupFormatException("缺失图片条目：$path")
        }
        images.forEach { (path, file) ->
            currentCoroutineContext().ensureActive()
            try {
                pngValidator.validate(file.readBytes())
            } catch (error: IllegalArgumentException) {
                throw BackupFormatException("图片不是有效的银行卡比例或兼容旧版 PNG：$path", error)
            }
        }
        return ValidatedArchive(session, manifest, snapshot, images, archiveCleanup)
    }

    private suspend fun extractJson(input: InputStream, path: String, consumed: Long): ExtractedEntry {
        val output = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        val size = copyEntry(input, limits.maxJsonBytes, consumed, path) { buffer, count ->
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
        }
        return ExtractedEntry(size, digest.hex(), output.toByteArray(), null)
    }

    private suspend fun extractImage(input: InputStream, path: String, consumed: Long, session: File): ExtractedEntry {
        requireSafeImagePath(path)
        val imagesRoot = File(session, "entries").apply { if (!isDirectory && !mkdirs()) error("无法创建图片临时目录") }
        val target = File(imagesRoot, path.removePrefix("images/"))
        val canonicalRoot = imagesRoot.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (!canonicalTarget.toPath().startsWith(canonicalRoot.toPath())) throw BackupFormatException("图片路径越界：$path")
        target.parentFile?.let(::requireDirectory)
        val digest = MessageDigest.getInstance("SHA-256")
        val size = FileOutputStream(target).use { output ->
            copyEntry(input, limits.maxImageBytes, consumed, path) { buffer, count ->
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }.also { output.flush(); output.fd.sync() }
        }
        return ExtractedEntry(size, digest.hex(), null, target)
    }

    private suspend fun copyEntry(
        input: InputStream,
        limit: Long,
        consumed: Long,
        path: String,
        write: (ByteArray, Int) -> Unit,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            if (size > limit) {
                val label = if (path.startsWith("images/")) "图片大小超限" else "JSON 大小超限"
                throw BackupFormatException("$label：$path")
            }
            if (consumed + size > limits.maxTotalBytes) throw BackupFormatException("ZIP 解压总大小超限")
            write(buffer, count)
        }
        return size
    }

    private suspend fun copyBounded(input: InputStream, output: FileOutputStream, limit: Long, message: String) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw BackupFormatException(message)
            output.write(buffer, 0, count)
        }
    }

    private fun validateManifest(manifest: BackupManifest, entries: Map<String, ExtractedEntry>) {
        if (manifest.resources.map { it.path }.toSet().size != manifest.resources.size) {
            throw BackupFormatException("清单包含重复资源路径")
        }
        if (manifest.resources.map { it.path }.toSet() != entries.keys - MANIFEST_PATH) {
            throw BackupFormatException("ZIP 包含未声明或缺失的资源条目")
        }
        manifest.resources.forEach { resource ->
            val entry = entries.getValue(resource.path)
            if (resource.size != entry.size) throw BackupFormatException("资源大小不匹配：${resource.path}")
            if (!Regex("^[0-9a-f]{64}$").matches(resource.sha256) || resource.sha256 != entry.sha256) {
                throw BackupFormatException("资源 SHA-256 不匹配：${resource.path}")
            }
        }
    }

    private fun validateCounts(manifest: BackupManifest, snapshot: BackupSnapshot) {
        if (manifest.deckCount != snapshot.decks.size || manifest.cardCount != snapshot.cards.size ||
            manifest.reviewCount != snapshot.reviews.size || manifest.imageCount != snapshot.cards.size
        ) throw BackupFormatException("备份清单数量与数据库数量不一致")
    }

    private inline fun <reified T> decode(bytes: ByteArray, name: String): T = try {
        json.decodeFromString(bytes.decodeToString())
    } catch (error: SerializationException) {
        throw BackupFormatException("$name 内容无效", error)
    } catch (error: IllegalArgumentException) {
        throw BackupFormatException("$name 内容无效", error)
    }

    private fun requireDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw BackupFormatException("无法创建临时目录：${directory.path}")
    }

    private data class ExtractedEntry(val size: Long, val sha256: String, val bytes: ByteArray?, val file: File?)
    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
}

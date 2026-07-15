package com.mutsumi.card.backup

import com.mutsumi.card.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.FileDescriptor
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupService(
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val json: Json = Json { prettyPrint = true },
    private val syncFile: (FileDescriptor) -> Unit = FileDescriptor::sync,
) {
    suspend fun exportToFile(
        snapshot: BackupSnapshot,
        images: Map<String, File>,
        target: File,
        exportedAt: Long,
    ) = withContext(Dispatchers.IO) {
        validateSnapshot(snapshot)
        val referencedImages = snapshot.cards.flatMap { card ->
            buildList {
                add(card.valueImagePath)
                card.frontImagePath?.let(::add)
            }
        }.toSet()
        if (images.keys != referencedImages) throw BackupFormatException("导出图片必须与数据库引用完全一致")
        images.forEach { (path, file) ->
            requireSafeImagePath(path)
            if (!file.isFile) throw BackupFormatException("导出图片不存在：$path")
        }
        if (target.exists() && !target.delete()) throw BackupFormatException("无法替换备份临时文件")
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) error("无法创建备份临时目录") }
        try {
            val databaseBytes = json.encodeToString(snapshot).encodeToByteArray()
            val orderedImages = images.toSortedMap()
            val resources = buildList {
                add(BackupResource(DATABASE_PATH, databaseBytes.size.toLong(), sha256(databaseBytes)))
                orderedImages.forEach { (path, file) ->
                    currentCoroutineContext().ensureActive()
                    add(BackupResource(path, file.length(), sha256(file)))
                }
            }
            val manifest = BackupManifest(
                BACKUP_FORMAT_VERSION, exportedAt, appVersion, snapshot.decks.size, snapshot.cards.size,
                snapshot.reviews.size, orderedImages.size, resources,
            )
            FileOutputStream(target).use { fileOutput ->
                ZipOutputStream(fileOutput).use { zip ->
                    zip.writeEntry(MANIFEST_PATH, json.encodeToString(manifest).encodeToByteArray())
                    zip.writeEntry(DATABASE_PATH, databaseBytes)
                    orderedImages.forEach { (path, file) -> zip.writeFileEntry(path, file) }
                    zip.finish()
                }
                fileOutput.flush()
                syncFile(fileOutput.fd)
            }
        } catch (error: Exception) {
            try {
                if (target.exists() && !target.delete()) error.addSuppressed(
                    IllegalStateException("无法清理未完成备份：${target.path}"),
                )
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        write(bytes)
        closeEntry()
    }

    private suspend fun ZipOutputStream.writeFileEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        FileInputStream(file).use { input -> copyCancellable(input) { buffer, count -> write(buffer, 0, count) } }
        closeEntry()
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> copyCancellable(input) { buffer, count -> digest.update(buffer, 0, count) } }
        return digest.digest().hex()
    }
}

internal const val MANIFEST_PATH = "manifest.json"
internal const val DATABASE_PATH = "database.json"

internal fun requireSafeImagePath(path: String) {
    if (!isSafeArchivePath(path) || !path.startsWith("images/") || !path.endsWith(".png")) {
        throw BackupFormatException("图片路径非法：$path")
    }
}

internal fun isSafeArchivePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || path.contains('\\')) return false
    if (path.any { it.code < 0x20 || it.code == 0x7f }) return false
    if (Regex("^[A-Za-z]:").containsMatchIn(path)) return false
    val segments = path.split('/')
    return segments.all { it.isNotBlank() && it != "." && it != ".." }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).hex()

private suspend fun copyCancellable(input: InputStream, consume: (ByteArray, Int) -> Unit) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        consume(buffer, count)
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

internal fun validateSnapshot(snapshot: BackupSnapshot) {
    val deckIds = snapshot.decks.map { it.id }
    snapshot.decks.forEach { deck ->
        if (deck.id <= 0) throw BackupFormatException("卡组 ID 必须为正数")
        if (deck.name.isBlank()) throw BackupFormatException("卡组名称不能为空")
        if (deck.createdAt < 0 || deck.updatedAt < 0) throw BackupFormatException("卡组时间不能为负数")
    }
    if (deckIds.toSet().size != deckIds.size) throw BackupFormatException("数据库包含重复卡组 ID")
    val cardIds = snapshot.cards.map { it.id }
    if (cardIds.toSet().size != cardIds.size) throw BackupFormatException("数据库包含重复卡片 ID")
    val reviewIds = snapshot.reviews.map { it.cardId }
    if (reviewIds.toSet().size != reviewIds.size) throw BackupFormatException("数据库包含重复复习状态")
    snapshot.cards.forEach { card ->
        if (card.id <= 0 || card.deckId <= 0) throw BackupFormatException("卡片 ID 必须为正数")
        if (card.deckId !in deckIds) throw BackupFormatException("卡片引用的卡组不存在：${card.deckId}")
        if (card.keyText.isBlank()) throw BackupFormatException("卡片 key 不能为空：${card.id}")
        if (card.createdAt < 0 || card.updatedAt < 0) throw BackupFormatException("卡片时间不能为负数")
        requireSafeImagePath(card.valueImagePath)
        card.frontImagePath?.let(::requireSafeImagePath)
    }
    val imagePaths = snapshot.cards.flatMap { card ->
        buildList {
            add(card.valueImagePath)
            card.frontImagePath?.let(::add)
        }
    }
    if (imagePaths.toSet().size != imagePaths.size) throw BackupFormatException("每张卡片必须使用独立图片路径")
    snapshot.reviews.forEach { review ->
        if (review.cardId <= 0) throw BackupFormatException("复习状态 ID 必须为正数")
        if (review.cardId !in cardIds) throw BackupFormatException("复习状态引用的卡片不存在：${review.cardId}")
        if (!review.weight.isFinite() || review.weight !in 0.2..5.0) throw BackupFormatException("复习权重必须在 0.2..5.0")
        if (review.seenCount < 0 || review.againCount < 0 || review.unsureCount < 0 || review.knownCount < 0) {
            throw BackupFormatException("复习计数不能为负数")
        }
        if (review.seenCount != review.againCount + review.unsureCount + review.knownCount) {
            throw BackupFormatException("seen 必须等于 again + unsure + known")
        }
        if (review.lastReviewedAt != null && review.lastReviewedAt < 0) throw BackupFormatException("复习时间不能为负数")
    }
    if (reviewIds.toSet() != cardIds.toSet()) throw BackupFormatException("每张卡片必须恰好有一个复习状态")
}

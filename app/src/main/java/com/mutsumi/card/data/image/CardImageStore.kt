package com.mutsumi.card.data.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

interface CardImageStore {
    suspend fun writePng(bytes: ByteArray): String
    suspend fun read(path: String): ByteArray
    suspend fun delete(path: String)
    fun resolve(path: String): File
}

internal interface ImageFileOperations {
    fun writeAndSync(pending: File, bytes: ByteArray) {
        FileOutputStream(pending).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    fun move(source: File, target: File, vararg options: CopyOption) {
        Files.move(source.toPath(), target.toPath(), *options)
    }

    fun deleteIfExists(file: File): Boolean = Files.deleteIfExists(file.toPath())
}

private object DefaultImageFileOperations : ImageFileOperations

class FileCardImageStore internal constructor(
    private val root: File,
    private val fileOperations: ImageFileOperations,
) : CardImageStore {
    constructor(root: File) : this(root, DefaultImageFileOperations)

    override suspend fun writePng(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "图片内容不能为空" }
        val imagesDirectory = safeImagesDirectory()
        val id = UUID.randomUUID().toString()
        val pending = File(imagesDirectory, ".pending-$id")
        val target = File(imagesDirectory, "value-$id.png")
        try {
            return withContext(Dispatchers.IO) {
                check(imagesDirectory.isDirectory || imagesDirectory.mkdirs()) { "无法创建图片目录" }
                fileOperations.writeAndSync(pending, bytes)
                try {
                    fileOperations.move(
                        pending,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    fileOperations.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
                }
                "$IMAGES_DIRECTORY/${target.name}"
            }
        } catch (error: CancellationException) {
            cleanupAfterFailure(error, pending, target)
            throw error
        } catch (error: Exception) {
            cleanupAfterFailure(error, pending, target)
            throw error
        }
    }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        val file = resolve(path)
        require(file.isFile) { "图片文件不存在：$path" }
        file.readBytes()
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val file = resolve(path)
        fileOperations.deleteIfExists(file)
        Unit
    }

    override fun resolve(path: String): File {
        require(path.isNotBlank() && !path.contains('\\')) { "图片路径非法：$path" }
        val segments = path.split('/')
        require(
            segments.size >= 2 &&
                segments.first() == IMAGES_DIRECTORY &&
                segments.none { it.isBlank() || it == "." || it == ".." },
        ) { "图片路径非法：$path" }

        val imagesDirectory = safeImagesDirectory()
        val resolved = File(root.canonicalFile, path).canonicalFile
        require(resolved.toPath().startsWith(imagesDirectory.toPath())) { "图片路径越界：$path" }
        return resolved
    }

    private fun safeImagesDirectory(): File {
        val canonicalRoot = root.canonicalFile
        val rawImagesDirectory = File(canonicalRoot, IMAGES_DIRECTORY)
        require(!Files.isSymbolicLink(rawImagesDirectory.toPath())) {
            "图片目录不能是符号链接：${rawImagesDirectory.path}"
        }
        val imagesDirectory = rawImagesDirectory.canonicalFile
        require(
            imagesDirectory != canonicalRoot &&
                imagesDirectory.toPath().startsWith(canonicalRoot.toPath()),
        ) { "图片目录越界：${imagesDirectory.path}" }
        return imagesDirectory
    }

    private suspend fun cleanupAfterFailure(error: Exception, vararg files: File) {
        withContext(NonCancellable + Dispatchers.IO) {
            files.forEach { file ->
                try {
                    fileOperations.deleteIfExists(file)
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
            }
        }
    }

    private companion object {
        const val IMAGES_DIRECTORY = "images"
    }
}

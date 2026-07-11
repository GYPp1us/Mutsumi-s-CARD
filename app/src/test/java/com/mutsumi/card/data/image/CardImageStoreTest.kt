package com.mutsumi.card.data.image

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeNoException
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class CardImageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectsEmptyContent() = runTest {
        val store = FileCardImageStore(temporaryFolder.root)

        val error = runCatching { store.writePng(byteArrayOf()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("图片内容不能为空")
    }

    @Test
    fun writesReadsResolvesAndDeletesImage() = runTest {
        val store = FileCardImageStore(temporaryFolder.root)
        val bytes = byteArrayOf(1, 2, 3, 4)

        val path = store.writePng(bytes)

        assertThat(path).matches("images/value-[0-9a-f-]+\\.png")
        assertThat(store.read(path)).isEqualTo(bytes)
        assertThat(store.resolve(path).isFile).isTrue()
        store.delete(path)
        assertThat(store.resolve(path).exists()).isFalse()
    }

    @Test
    fun rejectsAbsoluteTraversalAndPathsOutsideImages() {
        val store = FileCardImageStore(temporaryFolder.root)
        val invalidPaths = listOf(
            "../secret.png",
            "images/../secret.png",
            "other/value.png",
            File(temporaryFolder.root, "absolute.png").absolutePath,
        )

        invalidPaths.forEach { path ->
            val error = runCatching { store.resolve(path) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(error).hasMessageThat().contains("图片路径非法")
        }
    }

    @Test
    fun failedMoveLeavesNoPendingFile() = runTest {
        val operations = object : ImageFileOperations {
            override fun move(source: File, target: File, vararg options: CopyOption) {
                throw IOException("模拟移动失败")
            }
        }
        val store = FileCardImageStore(temporaryFolder.root, operations)

        val error = runCatching { store.writePng(byteArrayOf(1)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        val images = File(temporaryFolder.root, "images")
        assertThat(images.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun fallsBackWhenAtomicMoveIsUnsupported() = runTest {
        val events = mutableListOf<String>()
        val moveOptions = mutableListOf<List<CopyOption>>()
        val moveSources = mutableListOf<String>()
        val moveTargets = mutableListOf<String>()
        lateinit var syncedBytes: ByteArray
        lateinit var pendingName: String
        val operations = object : ImageFileOperations {
            override fun writeAndSync(pending: File, bytes: ByteArray) {
                events += "writeAndSync"
                pendingName = pending.name
                syncedBytes = bytes.copyOf()
                pending.writeBytes(bytes)
            }

            override fun move(source: File, target: File, vararg options: CopyOption) {
                events += "move"
                moveSources += source.name
                moveTargets += target.name
                moveOptions += options.toList()
                if (moveOptions.size == 1) {
                    throw AtomicMoveNotSupportedException(source.path, target.path, "测试不支持原子移动")
                }
                Files.move(source.toPath(), target.toPath(), *options)
            }
        }
        val store = FileCardImageStore(temporaryFolder.root, operations)

        val path = store.writePng(byteArrayOf(7, 8))

        assertThat(events).containsExactly("writeAndSync", "move", "move").inOrder()
        assertThat(pendingName).startsWith(".pending-")
        assertThat(syncedBytes).isEqualTo(byteArrayOf(7, 8))
        assertThat(moveSources).containsExactly(pendingName, pendingName).inOrder()
        assertThat(moveTargets).hasSize(2)
        assertThat(moveTargets).containsExactly(moveTargets.first(), moveTargets.first()).inOrder()
        assertThat(moveTargets.first()).matches("value-[0-9a-f-]+\\.png")
        assertThat(moveOptions.first()).containsExactly(
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        assertThat(moveOptions[1]).containsExactly(StandardCopyOption.REPLACE_EXISTING)
        assertThat(moveOptions[1]).doesNotContain(StandardCopyOption.ATOMIC_MOVE)
        assertThat(store.read(path)).isEqualTo(byteArrayOf(7, 8))
    }

    @Test
    fun failedWriteAndSyncLeavesNoPendingFile() = runTest {
        var moveCalled = false
        val operations = object : ImageFileOperations {
            override fun writeAndSync(pending: File, bytes: ByteArray) {
                pending.writeBytes(byteArrayOf(9))
                throw IOException("模拟同步失败")
            }

            override fun move(source: File, target: File, vararg options: CopyOption) {
                moveCalled = true
            }
        }
        val store = FileCardImageStore(temporaryFolder.root, operations)

        val error = runCatching { store.writePng(byteArrayOf(1, 2)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(moveCalled).isFalse()
        val images = File(temporaryFolder.root, "images")
        assertThat(images.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun cancellationThrownAfterMoveCleansTarget() = runTest {
        val operations = object : ImageFileOperations {
            override fun move(source: File, target: File, vararg options: CopyOption) {
                Files.move(source.toPath(), target.toPath(), *options)
                throw CancellationException("模拟移动后取消")
            }
        }
        val store = FileCardImageStore(temporaryFolder.root, operations)

        val error = runCatching { store.writePng(byteArrayOf(1, 2)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(imageFiles()).isEmpty()
    }

    @Test
    fun cancellationAtIoContextExitCleansTarget() = runTest {
        lateinit var writerJob: Job
        var error: Throwable? = null
        val operations = object : ImageFileOperations {
            override fun move(source: File, target: File, vararg options: CopyOption) {
                Files.move(source.toPath(), target.toPath(), *options)
                writerJob.cancel(CancellationException("模拟 IO 上下文退出时取消"))
            }
        }
        val store = FileCardImageStore(temporaryFolder.root, operations)

        writerJob = launch {
            error = runCatching { store.writePng(byteArrayOf(3, 4)) }.exceptionOrNull()
        }
        writerJob.join()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(imageFiles()).isEmpty()
    }

    @Test
    fun cleanupFailureIsSuppressedOnOriginalFailure() = runTest {
        val original = IOException("原始移动失败")
        val cleanupTargets = mutableListOf<String>()
        val operations = object : ImageFileOperations {
            override fun move(source: File, target: File, vararg options: CopyOption) {
                throw original
            }

            override fun deleteIfExists(file: File): Boolean {
                cleanupTargets += file.name
                throw IOException("清理失败：${file.name}")
            }
        }
        val store = FileCardImageStore(temporaryFolder.root, operations)

        val error = runCatching { store.writePng(byteArrayOf(5, 6)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(error).hasMessageThat().isEqualTo(original.message)
        assertThat(error!!.suppressed).hasLength(2)
        assertThat(cleanupTargets).hasSize(2)
        assertThat(cleanupTargets.first()).startsWith(".pending-")
        assertThat(cleanupTargets[1]).matches("value-[0-9a-f-]+\\.png")
        assertThat(error.suppressed.map { it.message }).containsExactlyElementsIn(
            cleanupTargets.map { "清理失败：$it" },
        ).inOrder()
    }

    @Test
    fun rejectsImagesDirectorySymlinkOutsideRoot() = runTest {
        val outside = Files.createTempDirectory("mutsumi-card-outside").toFile()
        val imagesLink = File(temporaryFolder.root, "images").toPath()
        try {
            try {
                Files.createSymbolicLink(imagesLink, outside.toPath())
            } catch (error: UnsupportedOperationException) {
                assumeNoException(error)
            } catch (error: IOException) {
                assumeNoException(error)
            } catch (error: SecurityException) {
                assumeNoException(error)
            }
            val store = FileCardImageStore(temporaryFolder.root)

            val resolveError = runCatching { store.resolve("images/value.png") }.exceptionOrNull()
            val writeError = runCatching { store.writePng(byteArrayOf(1)) }.exceptionOrNull()

            assertThat(resolveError).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(writeError).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(outside.listFiles()?.toList().orEmpty()).isEmpty()
        } finally {
            outside.deleteRecursively()
        }
    }

    private fun imageFiles(): List<File> =
        File(temporaryFolder.root, "images").listFiles()?.toList().orEmpty()
}

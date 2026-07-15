package com.mutsumi.card.backup

import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.File

const val BACKUP_FORMAT_VERSION = 2

@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val deckCount: Int,
    val cardCount: Int,
    val reviewCount: Int,
    val imageCount: Int,
    val resources: List<BackupResource>,
)

@Serializable
data class BackupResource(
    val path: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class BackupSnapshot(
    val decks: List<BackupDeck>,
    val cards: List<BackupCard>,
    val reviews: List<BackupReviewState>,
)

@Serializable
data class BackupDeck(val id: Long, val name: String, val createdAt: Long, val updatedAt: Long)

@Serializable
data class BackupCard(
    val id: Long,
    val deckId: Long,
    val keyText: String,
    val valueImagePath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean,
    val frontImagePath: String? = null,
)

@Serializable
data class BackupReviewState(
    val cardId: Long,
    val weight: Double,
    val seenCount: Int,
    val againCount: Int,
    val unsureCount: Int,
    val knownCount: Int,
    val lastReviewedAt: Long?,
)

data class BackupLimits(
    val maxImageBytes: Long = 16L * 1024 * 1024,
    val maxJsonBytes: Long = 2L * 1024 * 1024,
    val maxTotalBytes: Long = 64L * 1024 * 1024,
    val maxArchiveBytes: Long = 64L * 1024 * 1024,
    val maxEntries: Int = 20_000,
)

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ValidatedArchive internal constructor(
    val temporaryDirectory: File,
    val manifest: BackupManifest,
    val snapshot: BackupSnapshot,
    images: Map<String, File>,
    private val cleanup: (File) -> Unit = { directory ->
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IllegalStateException("无法删除备份临时目录：${directory.path}")
        }
    },
) : Closeable {
    val images: Map<String, File> = images.toMap()

    override fun close() {
        cleanup(temporaryDirectory)
    }
}

package com.mutsumi.card.backup

import kotlinx.serialization.Serializable
import java.io.IOException

const val CLOUD_BACKUP_FORMAT_VERSION = 2
const val CLOUD_BACKUP_WINDOW_SIZE = 8

data class CloudBackupConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val remoteDirectory: String = "MutsumiCard",
)

@Serializable
data class CloudBackupIndex(
    val formatVersion: Int = CLOUD_BACKUP_FORMAT_VERSION,
    val snapshots: List<CloudSnapshotSummary> = emptyList(),
)

@Serializable
data class CloudSnapshotSummary(
    val id: String,
    val createdAt: Long,
    val addedOrChangedCount: Int = 0,
    val deletedCount: Int = 0,
    val cardCount: Int = 0,
    val deckCount: Int = 0,
    val addedOrChangedDeckCount: Int = 0,
    val deletedDeckCount: Int = 0,
    val imageHashes: List<String> = emptyList(),
    val storageVersion: Int = 2,
)

@Serializable
data class CloudSnapshotDocument(
    val formatVersion: Int = CLOUD_BACKUP_FORMAT_VERSION,
    val snapshot: CloudSnapshot,
    val images: List<CloudImageReference>,
)

@Serializable
data class CloudSnapshot(
    val decks: List<CloudDeckRecord>,
    val cards: List<CloudCardRecord>,
    val reviews: List<CloudReviewRecord>,
)

@Serializable
data class CloudDeckRecord(
    val syncId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val parentSyncId: String? = null,
    val archived: Boolean = false,
)

@Serializable
data class CloudCardRecord(
    val syncId: String,
    val deckSyncId: String,
    val keyText: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean,
    val valueImageSha256: String,
    val frontImageSha256: String? = null,
)

@Serializable
data class CloudReviewRecord(
    val cardSyncId: String,
    val weight: Double,
    val seenCount: Int,
    val againCount: Int,
    val unsureCount: Int,
    val knownCount: Int,
    val lastReviewedAt: Long?,
)

@Serializable
data class CloudImageReference(
    val sha256: String,
    val size: Long,
    val sourcePath: String? = null,
)

enum class CloudConflictKind(val label: String) {
    Deck("卡组"), Card("卡片"), Review("复习状态"),
}

class CloudBackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

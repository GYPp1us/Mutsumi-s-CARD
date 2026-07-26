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

data class CloudLocalState(
    val document: CloudSnapshotDocument,
    val bytesByHash: Map<String, ByteArray>,
)

data class CloudBackupOverview(
    val snapshots: List<CloudSnapshotSummary>,
    val current: CloudChangeStats,
)

data class CloudChangeStats(
    val cardCount: Int,
    val deckCount: Int,
    val addedOrChangedCount: Int,
    val deletedCount: Int,
    val addedOrChangedDeckCount: Int,
    val deletedDeckCount: Int,
)

data class CloudBackupResult(
    val overview: CloudBackupOverview,
    val createdSnapshot: Boolean,
    val warnings: List<String> = emptyList(),
)

data class CloudPreviewCard(
    val deckName: String,
    val keyText: String,
    val frontPng: ByteArray?,
    val backPng: ByteArray,
)

data class CloudRestorePreview(
    val snapshotId: String,
    val createdAt: Long,
    val stats: CloudChangeStats,
    val cards: List<CloudPreviewCard>,
    val deletionWarning: String? = null,
)

class CloudConflictException(
    val conflicts: List<String>,
) : IOException("检测到本地与云端同时修改：${conflicts.joinToString("、")}")

class CloudBackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface CloudBackupOperations {
    suspend fun inspect(config: CloudBackupConfig): CloudBackupOverview
    suspend fun backup(config: CloudBackupConfig, pushDelete: Boolean = false): CloudBackupResult
    suspend fun previewRestore(
        config: CloudBackupConfig,
        snapshotId: String,
        pullDelete: Boolean = false,
    ): CloudRestorePreview
    suspend fun restore(
        config: CloudBackupConfig,
        snapshotId: String,
        pullDelete: Boolean = false,
    ): ImportSummary
}

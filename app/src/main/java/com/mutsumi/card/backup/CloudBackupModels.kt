package com.mutsumi.card.backup

import kotlinx.serialization.Serializable
import java.io.IOException

const val CLOUD_BACKUP_FORMAT_VERSION = 1
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
    val addedOrChangedCount: Int,
    val deletedCount: Int,
    val cardCount: Int,
    val imageHashes: List<String>,
)

@Serializable
data class CloudSnapshotDocument(
    val formatVersion: Int = CLOUD_BACKUP_FORMAT_VERSION,
    val snapshot: BackupSnapshot,
    val images: List<CloudImageReference>,
)

@Serializable
data class CloudImageReference(
    val localPath: String,
    val sha256: String,
    val size: Long,
)

data class CloudBackupOverview(
    val snapshots: List<CloudSnapshotSummary>,
    val addedOrChangedCount: Int,
    val deletedCount: Int,
)

data class CloudBackupResult(
    val overview: CloudBackupOverview,
    val createdSnapshot: Boolean,
    val warnings: List<String> = emptyList(),
)

class CloudBackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface CloudBackupOperations {
    suspend fun inspect(config: CloudBackupConfig): CloudBackupOverview
    suspend fun backup(config: CloudBackupConfig): CloudBackupResult
    suspend fun restore(config: CloudBackupConfig, snapshotId: String): ImportSummary
}

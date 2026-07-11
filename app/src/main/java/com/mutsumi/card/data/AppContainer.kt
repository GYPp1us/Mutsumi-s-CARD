package com.mutsumi.card.data

import android.content.Context
import com.mutsumi.card.backup.BackupOperations
import com.mutsumi.card.backup.RepositoryBackupOperations
import com.mutsumi.card.backup.ExportSummary
import com.mutsumi.card.backup.ImportSummary
import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.data.image.FileCardImageStore
import com.mutsumi.card.data.local.MutsumiCardDatabase
import com.mutsumi.card.data.preferences.AppPreferences
import com.mutsumi.card.data.preferences.DataStoreAppPreferences
import kotlinx.coroutines.flow.first
import java.io.File

class AppContainer(
    val cardRepository: CardRepository,
    val appPreferences: AppPreferences,
    val imageStore: CardImageStore,
    val backupOperations: BackupOperations = UnavailableBackupOperations,
) {
    suspend fun ensureSelectedDeck(): Long {
        cardRepository.retryPendingImageCleanup()
        val defaultDeckId = cardRepository.ensureDefaultDeck()
        val existingIds = cardRepository.decks.first().mapTo(mutableSetOf()) { it.id }
        val preferred = appPreferences.currentDeckId.first()
        val selected = preferred
            ?.takeIf { it in existingIds }
            ?: defaultDeckId
        if (selected != preferred) {
            appPreferences.setCurrentDeckId(selected)
        }
        return selected
    }

    companion object {
        fun create(context: Context): AppContainer {
            val database = MutsumiCardDatabase.build(context)
            val imageStore = FileCardImageStore(File(context.filesDir, "card-store-v2"))
            val repository = RoomCardRepository(database.cardDao(), imageStore)
            return AppContainer(
                cardRepository = repository,
                appPreferences = DataStoreAppPreferences.create(context),
                imageStore = imageStore,
                backupOperations = RepositoryBackupOperations(
                    repository = repository,
                    imageStore = imageStore,
                    temporaryDirectory = File(context.cacheDir, "backup-v2"),
                ),
            )
        }
    }
}

private object UnavailableBackupOperations : BackupOperations {
    override suspend fun export(output: java.io.OutputStream): ExportSummary =
        error("当前 AppContainer 未装配备份导出")

    override suspend fun import(input: java.io.InputStream): ImportSummary =
        error("当前 AppContainer 未装配备份导入")
}

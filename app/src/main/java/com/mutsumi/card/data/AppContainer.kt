package com.mutsumi.card.data

import android.content.Context
import com.mutsumi.card.backup.BackupOperations
import com.mutsumi.card.backup.CloudBackupSettings
import com.mutsumi.card.backup.PrivateCloudBackupSettings
import com.mutsumi.card.backup.RepositoryBackupOperations
import com.mutsumi.card.backup.RoomCloudBackupDataAccess
import com.mutsumi.card.backup.CloudSyncOperations
import com.mutsumi.card.backup.DirectionalCloudSyncService
import com.mutsumi.card.backup.ExportSummary
import com.mutsumi.card.backup.ImportSummary
import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.data.image.FileCardImageStore
import com.mutsumi.card.data.local.MutsumiCardDatabase
import com.mutsumi.card.data.preferences.AppPreferences
import com.mutsumi.card.data.preferences.DataStoreAppPreferences
import com.mutsumi.card.ai.AiSettingsStore
import com.mutsumi.card.BuildConfig
import com.mutsumi.card.focus.AndroidFocusSettingsStorage
import com.mutsumi.card.focus.FocusReporterController
import com.mutsumi.card.focus.HttpFocusReporterGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.mutsumi.card.settings.AppUpdateChecker
import com.mutsumi.card.settings.AppUpdateSettingsStore
import com.mutsumi.card.settings.DataStoreAppUpdateSettingsStore
import com.mutsumi.card.settings.GitHubReleaseUpdateSource
import kotlinx.coroutines.flow.first
import java.io.File

class AppContainer(
    val cardRepository: CardRepository,
    val appPreferences: AppPreferences,
    val imageStore: CardImageStore,
    val backupOperations: BackupOperations = UnavailableBackupOperations,
    val cloudBackupSettings: CloudBackupSettings? = null,
    val aiSettingsStore: AiSettingsStore? = null,
    val appUpdateSettingsStore: AppUpdateSettingsStore? = null,
    val appUpdateChecker: AppUpdateChecker? = null,
    val focusReporter: FocusReporterController? = null,
    val cloudSyncOperations: CloudSyncOperations? = null,
) {
    suspend fun initializeDefaultSeed(context: Context) {
        DefaultSeedInitializer(context, cardRepository).initialize()
    }

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
            val cloudDataAccess = RoomCloudBackupDataAccess(database.cardDao(), imageStore,
                indexFile = File(context.filesDir, "library-index-cache.json"))
            val repositoryBackupOperations = RepositoryBackupOperations(
                dataAccess = cloudDataAccess,
                temporaryDirectory = File(context.cacheDir, "backup-v2"),
            )
            return AppContainer(
                cardRepository = repository,
                focusReporter = FocusReporterController(
                    AndroidFocusSettingsStorage(context), HttpFocusReporterGateway(),
                    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                ),
                appPreferences = DataStoreAppPreferences.create(context),
                imageStore = imageStore,
                backupOperations = repositoryBackupOperations,
                cloudSyncOperations = DirectionalCloudSyncService(cloudDataAccess, File(context.filesDir, "cloud-sync-v3")),
                cloudBackupSettings = PrivateCloudBackupSettings(context),
                aiSettingsStore = AiSettingsStore.create(context),
                appUpdateSettingsStore = DataStoreAppUpdateSettingsStore.create(context),
                appUpdateChecker = AppUpdateChecker(
                    source = GitHubReleaseUpdateSource(),
                    installedVersionName = BuildConfig.VERSION_NAME,
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

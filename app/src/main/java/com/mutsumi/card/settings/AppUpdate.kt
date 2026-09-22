package com.mutsumi.card.settings

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private val Context.appUpdateDataStore by preferencesDataStore(name = "mutsumi-app-update")

data class AppUpdateSettings(
    val automaticCheckEnabled: Boolean = true,
    val automaticDownloadEnabled: Boolean = false,
    val lastCheckedAt: Long = 0L,
)

interface AppUpdateSettingsStore {
    val settings: Flow<AppUpdateSettings>

    suspend fun setAutomaticCheckEnabled(enabled: Boolean)
    suspend fun setAutomaticDownloadEnabled(enabled: Boolean)
    suspend fun markCheckedAt(timestamp: Long)
}

class DataStoreAppUpdateSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : AppUpdateSettingsStore {
    override val settings: Flow<AppUpdateSettings> = dataStore.data.map { preferences ->
        AppUpdateSettings(
            automaticCheckEnabled = preferences[AUTOMATIC_CHECK_ENABLED] ?: true,
            automaticDownloadEnabled = preferences[AUTOMATIC_DOWNLOAD_ENABLED] ?: false,
            lastCheckedAt = preferences[LAST_CHECKED_AT] ?: 0L,
        )
    }

    override suspend fun setAutomaticCheckEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTOMATIC_CHECK_ENABLED] = enabled
            if (!enabled) preferences[AUTOMATIC_DOWNLOAD_ENABLED] = false
        }
    }

    override suspend fun setAutomaticDownloadEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            if (enabled && !(preferences[AUTOMATIC_CHECK_ENABLED] ?: true)) {
                throw IllegalStateException("请先开启自动检查更新")
            }
            preferences[AUTOMATIC_DOWNLOAD_ENABLED] = enabled
        }
    }

    override suspend fun markCheckedAt(timestamp: Long) {
        require(timestamp >= 0L) { "更新时间不能为负数" }
        dataStore.edit { it[LAST_CHECKED_AT] = timestamp }
    }

    companion object {
        private val AUTOMATIC_CHECK_ENABLED = booleanPreferencesKey("automatic_check_enabled")
        private val AUTOMATIC_DOWNLOAD_ENABLED = booleanPreferencesKey("automatic_download_enabled")
        private val LAST_CHECKED_AT = longPreferencesKey("last_checked_at")

        fun create(context: Context): DataStoreAppUpdateSettingsStore = DataStoreAppUpdateSettingsStore(
            context.applicationContext.appUpdateDataStore,
        )
    }
}

data class AvailableUpdate(
    val versionName: String,
    val releasePageUrl: String,
    val apkUrl: String,
    val releaseNotes: String = "",
)

interface ReleaseUpdateSource {
    suspend fun latestRelease(): AvailableUpdate?
}

class GitHubReleaseUpdateSource(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val repository: String = "GYPp1us/Mutsumi-s-CARD",
) : ReleaseUpdateSource {
    override suspend fun latestRelease(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MutsumiCard-UpdateChecker")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) {
                throw IOException("更新检查失败：HTTP ${response.code} ${response.message}")
            }
            val body = response.body?.string() ?: throw IOException("更新检查失败：GitHub 返回为空")
            val root = try {
                json.parseToJsonElement(body).jsonObject
            } catch (error: Exception) {
                throw IOException("更新检查失败：GitHub 返回格式无效", error)
            }
            val versionName = root["tag_name"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IOException("更新检查失败：发布版本为空")
            val releasePageUrl = root["html_url"]?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "https://github.com/$repository/releases/tag/$versionName"
            val apkUrl = releaseApkUrlFromAssets(root["assets"]?.jsonArray, Build.SUPPORTED_ABIS.toList())
                ?: throw IOException("更新检查失败：发布未包含 $RELEASE_APK_ASSET_NAME")
            AvailableUpdate(
                versionName = versionName,
                releasePageUrl = releasePageUrl,
                apkUrl = apkUrl,
                releaseNotes = root["body"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }
}

internal const val RELEASE_APK_ASSET_NAME = "mutsumi-card-release.apk"

/** 优先采用设备支持的小包；旧发布和旧更新器仍使用固定名称的通用包。 */
internal fun releaseApkUrlFromAssets(assets: JsonArray?, supportedAbis: List<String> = emptyList()): String? {
    val supported = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    val names = supportedAbis.filter { it in supported }.map { "mutsumi-card-$it-release.apk" } + RELEASE_APK_ASSET_NAME
    val candidates = assets.orEmpty().filterIsInstance<JsonObject>()
    return names.firstNotNullOfOrNull { name ->
        candidates.firstOrNull { it["name"]?.jsonPrimitive?.content == name }
            ?.get("browser_download_url")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
    }
}

class AppUpdateChecker(
    private val source: ReleaseUpdateSource,
    private val installedVersionName: String,
) {
    suspend fun check(): AvailableUpdate? {
        val latest = source.latestRelease() ?: return null
        return latest.takeIf { isRemoteVersionNewer(it.versionName, installedVersionName) }
    }
}

data class SemanticVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String?,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int = compareValuesBy(
        this,
        other,
        SemanticVersion::major,
        SemanticVersion::minor,
        SemanticVersion::patch,
    ).takeIf { it != 0 } ?: comparePreRelease(preRelease, other.preRelease)

    companion object {
        private val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(value: String): SemanticVersion {
            val match = pattern.matchEntire(value.trim())
                ?: throw IllegalArgumentException("版本号格式无效：$value")
            return SemanticVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                preRelease = match.groupValues[4].takeIf { it.isNotEmpty() },
            )
        }

        private fun comparePreRelease(first: String?, second: String?): Int = when {
            first == second -> 0
            first == null -> 1
            second == null -> -1
            else -> first.compareTo(second)
        }
    }
}

fun isRemoteVersionNewer(remote: String, installed: String): Boolean =
    SemanticVersion.parse(remote) > SemanticVersion.parse(installed)

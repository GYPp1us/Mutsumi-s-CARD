package com.mutsumi.card.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateCheckerTest {
    @Test
    fun `按设备架构优先级选择小包并兼容旧版通用包`() {
        val assets = Json.parseToJsonElement("""[
            {"name":"mutsumi-card-release.apk","browser_download_url":"https://example.test/universal.apk"},
            {"name":"mutsumi-card-armeabi-v7a-release.apk","browser_download_url":"https://example.test/arm32.apk"},
            {"name":"mutsumi-card-arm64-v8a-release.apk","browser_download_url":"https://example.test/arm64.apk"},
            {"name":"mutsumi-card-x86_64-release.apk","browser_download_url":"https://example.test/x64.apk"}
        ]""").jsonArray
        assertThat(releaseApkUrlFromAssets(assets, listOf("arm64-v8a", "armeabi-v7a"))).isEqualTo("https://example.test/arm64.apk")
        assertThat(releaseApkUrlFromAssets(assets, listOf("armeabi-v7a"))).isEqualTo("https://example.test/arm32.apk")
        assertThat(releaseApkUrlFromAssets(assets, listOf("x86_64", "x86"))).isEqualTo("https://example.test/x64.apk")
        assertThat(releaseApkUrlFromAssets(assets, listOf("unknown"))).isEqualTo("https://example.test/universal.apk")
        assertThat(releaseApkUrlFromAssets(kotlinx.serialization.json.JsonArray(assets.take(1)), listOf("arm64-v8a"))).isEqualTo("https://example.test/universal.apk")
        assertThat(releaseApkUrlFromAssets(kotlinx.serialization.json.JsonArray(assets.drop(1)), listOf("unknown"))).isNull()
    }

    @Test
    fun `稳定版与预发布版本按语义版本比较`() {
        assertThat(isRemoteVersionNewer("v0.6.7", "0.6.6")).isTrue()
        assertThat(isRemoteVersionNewer("0.6.6", "v0.6.6-rc.1")).isTrue()
        assertThat(isRemoteVersionNewer("0.6.6-rc.1", "0.6.6")).isFalse()
        assertThat(isRemoteVersionNewer("0.6.6", "0.6.6")).isFalse()
    }

    @Test
    fun `检查器仅返回比已安装版本新的发布`() = runTest {
        val newer = availableUpdate("v0.7.0")
        val checker = AppUpdateChecker(FakeSource(newer), installedVersionName = "0.6.6")
        assertThat(checker.check()).isEqualTo(newer)

        val current = AppUpdateChecker(FakeSource(availableUpdate("v0.6.6")), installedVersionName = "0.6.6")
        assertThat(current.check()).isNull()
    }

    @Test
    fun `更新器只选择正式 release APK 且拒绝非 HTTPS 地址`() {
        val assets = Json.parseToJsonElement(
            """[
                {"name":"app-debug.apk","browser_download_url":"https://example.test/debug.apk"},
                {"name":"mutsumi-card-release.apk","browser_download_url":"https://example.test/release.apk"}
            ]""",
        ).jsonArray
        val insecureAssets = Json.parseToJsonElement(
            """[{"name":"mutsumi-card-release.apk","browser_download_url":"http://example.test/release.apk"}]""",
        ).jsonArray

        assertThat(releaseApkUrlFromAssets(assets)).isEqualTo("https://example.test/release.apk")
        assertThat(releaseApkUrlFromAssets(insecureAssets)).isNull()
    }

    @Test
    fun `自动检查仅在间隔到期时执行并记录结果`() = runTest {
        val settings = FakeSettingsStore(
            AppUpdateSettings(
                automaticCheckEnabled = true,
                automaticDownloadEnabled = false,
                lastCheckedAt = 0L,
            ),
        )
        val source = FakeSource(availableUpdate("v0.6.7"))
        val clock = 13L * 60L * 60L * 1000L
        val operationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val viewModel = AppUpdateViewModel(
            settingsStore = settings,
            checker = AppUpdateChecker(source, "0.6.6"),
            clock = { clock },
            operationScope = operationScope,
        )

        try {
            advanceUntilIdle()

            assertThat(source.calls).isEqualTo(1)
            assertThat(viewModel.state.value.availableUpdate?.versionName).isEqualTo("v0.6.7")
            assertThat(settings.current.lastCheckedAt).isEqualTo(clock)
        } finally {
            operationScope.cancel()
        }
    }

    @Test
    fun `自动下载事件在界面开始收集前也会交付`() = runTest {
        val update = availableUpdate("v0.7.0")
        val settings = FakeSettingsStore(
            AppUpdateSettings(
                automaticCheckEnabled = true,
                automaticDownloadEnabled = true,
                lastCheckedAt = 0L,
            ),
        )
        val operationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val viewModel = AppUpdateViewModel(
            settingsStore = settings,
            checker = AppUpdateChecker(FakeSource(update), "0.6.6"),
            clock = { 13L * 60L * 60L * 1000L },
            operationScope = operationScope,
        )

        try {
            advanceUntilIdle()

            val event = withTimeout(1_000) { viewModel.events.first() }

            assertThat(event).isEqualTo(AppUpdateEvent.DownloadRequested(update))
        } finally {
            operationScope.cancel()
        }
    }

    @Test
    fun `关闭自动检查会同步关闭自动下载`() = runTest {
        val settings = FakeSettingsStore(
            AppUpdateSettings(automaticCheckEnabled = true, automaticDownloadEnabled = true),
        )
        val operationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val viewModel = AppUpdateViewModel(
            settingsStore = settings,
            checker = AppUpdateChecker(FakeSource(null), "0.6.6"),
            clock = { 1L },
            operationScope = operationScope,
        )

        try {
            viewModel.setAutomaticCheckEnabled(false)
            advanceUntilIdle()

            assertThat(settings.current.automaticCheckEnabled).isFalse()
            assertThat(settings.current.automaticDownloadEnabled).isFalse()
        } finally {
            operationScope.cancel()
        }
    }

    private fun availableUpdate(version: String) = AvailableUpdate(
        versionName = version,
        releasePageUrl = "https://example.test/release/$version",
        apkUrl = "https://example.test/release/$version.apk",
    )

    private class FakeSource(private val update: AvailableUpdate?) : ReleaseUpdateSource {
        var calls = 0

        override suspend fun latestRelease(): AvailableUpdate? {
            calls++
            return update
        }
    }

    private class FakeSettingsStore(initial: AppUpdateSettings) : AppUpdateSettingsStore {
        private val mutableSettings = MutableStateFlow(initial)
        override val settings: Flow<AppUpdateSettings> = mutableSettings.asStateFlow()
        val current: AppUpdateSettings get() = mutableSettings.value

        override suspend fun setAutomaticCheckEnabled(enabled: Boolean) {
            mutableSettings.value = mutableSettings.value.copy(
                automaticCheckEnabled = enabled,
                automaticDownloadEnabled = mutableSettings.value.automaticDownloadEnabled && enabled,
            )
        }

        override suspend fun setAutomaticDownloadEnabled(enabled: Boolean) {
            check(!enabled || mutableSettings.value.automaticCheckEnabled)
            mutableSettings.value = mutableSettings.value.copy(automaticDownloadEnabled = enabled)
        }

        override suspend fun markCheckedAt(timestamp: Long) {
            mutableSettings.value = mutableSettings.value.copy(lastCheckedAt = timestamp)
        }
    }
}

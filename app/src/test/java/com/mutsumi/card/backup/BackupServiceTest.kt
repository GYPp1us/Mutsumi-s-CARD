package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.BuildConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class BackupServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val json = Json

    @Test
    fun `导出文件包含v2清单BuildConfig版本和流式摘要`() = runTest {
        val image = temporaryFolder.newFile("value.png").writeFixture(validPng())
        val target = File(temporaryFolder.root, "backup.zip")

        BackupService(syncFile = {}).exportToFile(
            validSnapshot(), mapOf("images/value-2.png" to image), target, 100,
        )

        ZipFile(target).use { zip ->
            val manifest = json.decodeFromString<BackupManifest>(
                zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString(),
            )
            assertThat(manifest.formatVersion).isEqualTo(2)
            assertThat(manifest.appVersion).isEqualTo(BuildConfig.VERSION_NAME)
            assertThat(manifest.exportedAt).isEqualTo(100)
            val imageResource = manifest.resources.single { it.path.startsWith("images/") }
            assertThat(imageResource.size).isEqualTo(image.length())
            assertThat(imageResource.sha256).isEqualTo(sha(image.readBytes()))
        }
    }

    @Test
    fun `导出失败或取消不会保留未完成私有ZIP`() = runTest {
        val missing = File(temporaryFolder.root, "missing.png")
        val target = File(temporaryFolder.root, "backup.zip")

        val error = runCatching {
            BackupService(appVersion = "0.4.0", syncFile = {}).exportToFile(
                validSnapshot(), mapOf("images/value-2.png" to missing), target, 1,
            )
        }.exceptionOrNull()

        assertThat(error).isNotNull()
        assertThat(target.exists()).isFalse()
    }
}

package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class BackupValidatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `默认移动端预算为总解压64MiB和单图片16MiB`() {
        assertThat(BackupLimits().maxTotalBytes).isEqualTo(64L * 1024 * 1024)
        assertThat(BackupLimits().maxImageBytes).isEqualTo(16L * 1024 * 1024)
    }

    @Test
    fun `验证后的图片只保留文件引用且关闭递归清理会话目录`() = runTest {
        val archive = validator().validateToTemporary(ByteArrayInputStream(createArchive()), temporaryFolder.root)
        val session = archive.temporaryDirectory
        val image = archive.images.getValue("images/value-2.png")

        assertThat(image.isFile).isTrue()
        assertThat(image.toPath().startsWith(session.toPath())).isTrue()
        archive.close()
        assertThat(session.exists()).isFalse()
        assertThat(temporaryFolder.root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `大图片流式写盘并在越过16MiB时拒绝且无残留`() = runTest {
        val oversized = ByteArray(17 * 1024 * 1024)
        assertInvalid(createArchive(image = oversized), "图片大小超限")
    }

    @Test
    fun `伪PNG即使摘要正确也拒绝`() = runTest {
        assertInvalid(createArchive(image = byteArrayOf(1, 2, 3)), "PNG")
    }

    @Test
    fun `拒绝路径重复缺失版本和摘要问题`() = runTest {
        assertInvalid(zipOf("../x" to byteArrayOf(1)), "路径")
        assertInvalid(zipOf("manifest.json" to byteArrayOf(1)), "database.json")
        assertInvalid(createArchive(formatVersion = 3), "版本")
        assertInvalid(createArchive(imageSha = "0".repeat(64)), "SHA-256")
        assertInvalid(createArchive(imageSize = 1), "大小")
    }

    @Test
    fun `拒绝非法领域值`() = runTest {
        val cases = listOf(
            validSnapshot(deck = BackupDeck(0, "默认卡组", 1, 1)) to "ID",
            validSnapshot(deck = BackupDeck(1, " ", 1, 1)) to "名称",
            validSnapshot(card = BackupCard(2, 1, "雨", "images/value-2.png", -1, 1, false)) to "时间",
            validSnapshot(review = BackupReviewState(2, 0.1, 0, 0, 0, 0, null)) to "权重",
            validSnapshot(review = BackupReviewState(2, 1.0, -1, 0, 0, 0, null)) to "计数",
            validSnapshot(review = BackupReviewState(2, 1.0, 2, 1, 0, 0, null)) to "seen",
            validSnapshot(review = BackupReviewState(2, 1.0, 0, 0, 0, 0, -1)) to "时间",
        )
        cases.forEach { (snapshot, message) -> assertInvalid(createArchive(snapshot), message) }

        val nonFinite = runCatching {
            validateSnapshot(validSnapshot(review = BackupReviewState(2, Double.NaN, 0, 0, 0, 0, null)))
        }.exceptionOrNull()
        assertThat(nonFinite).hasMessageThat().contains("权重")
    }

    private fun validator(limits: BackupLimits = BackupLimits()) =
        BackupValidator(limits = limits, pngValidator = testPngValidator)

    private suspend fun assertInvalid(bytes: ByteArray, message: String) {
        val error = runCatching {
            validator().validateToTemporary(ByteArrayInputStream(bytes), temporaryFolder.root)
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(BackupFormatException::class.java)
        assertThat(error).hasMessageThat().contains(message)
        assertThat(temporaryFolder.root.listFiles().orEmpty()).isEmpty()
    }
}

package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.data.image.PngDecoder
import com.mutsumi.card.data.image.ValuePngValidator
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudRecoveryPackageCompatibilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `只读恢复工具生成的共享图片双面包通过原应用全部格式校验`() = runTest {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/recovery-tool-synthetic.zip"))
        val validator = BackupValidator(pngValidator = ValuePngValidator(PngDecoder { 1024 to 1624 }))
        validator.validateToTemporary(fixture, temporaryFolder.root).use { archive ->
            assertThat(archive.snapshot.cards.map { it.keyText }).containsExactly("甲", "乙")
            assertThat(archive.snapshot.cards.map { it.frontImagePath }).doesNotContain(null)
            assertThat(archive.images).hasSize(4)
            assertThat(archive.snapshot.decks.single().name).startsWith("恢复副本")
        }
        assertThat(temporaryFolder.root.listFiles().orEmpty()).isEmpty()
    }
}

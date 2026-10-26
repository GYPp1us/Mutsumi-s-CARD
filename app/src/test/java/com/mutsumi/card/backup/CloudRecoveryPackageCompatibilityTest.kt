package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.data.image.PngDecoder
import com.mutsumi.card.data.image.ValuePngValidator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

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

    @Test fun `显式指定的本地恢复包可校验并导入为全新标识且原图不变`() = runTest {
        // Windows 测试 JVM 参数不能直接携带含空格和嵌套引号的 JSON。
        val configured = System.getProperty("mutsumi.recovery.validationCasesBase64")
        assumeTrue("仅在显式提供本地恢复包时运行，不将真实数据纳入测试资源", !configured.isNullOrBlank())
        val cases = Json.parseToJsonElement(Base64.getDecoder().decode(requireNotNull(configured)).decodeToString()).jsonArray
        val validator = BackupValidator(pngValidator = ValuePngValidator(PngDecoder { bytes ->
            ByteBuffer.wrap(bytes).let { it.getInt(16) to it.getInt(20) }
        }))
        cases.forEach { item ->
            val entry = item.jsonObject
            val file = File(entry.getValue("path").jsonPrimitive.content)
            val expectedCards = entry.getValue("cards").jsonPrimitive.int
            val expectedDecks = entry.getValue("decks").jsonPrimitive.int
            assertThat(file.isFile).isTrue()
            validator.validateToTemporary(file.inputStream(), temporaryFolder.root).use { archive ->
                assertThat(archive.snapshot.cards).hasSize(expectedCards)
                assertThat(archive.snapshot.decks).hasSize(expectedDecks)
                val originalCardIds = archive.snapshot.cards.map { it.id }.toSet()
                val originalDeckIds = archive.snapshot.decks.map { it.id }.toSet()
                val originalPaths = archive.images.keys
                val generatedIds = AtomicLong(100_000)
                val importer = BackupImporter(
                    gateway = ImportGateway { batch ->
                        assertThat(batch.snapshot.cards).hasSize(expectedCards)
                        assertThat(batch.snapshot.decks).hasSize(expectedDecks)
                        assertThat(batch.snapshot.cards.none { it.id in originalCardIds }).isTrue()
                        assertThat(batch.snapshot.decks.none { it.id in originalDeckIds }).isTrue()
                        assertThat(batch.images.keys.none { it in originalPaths }).isTrue()
                        archive.snapshot.cards.zip(batch.snapshot.cards).forEach { (source, copied) ->
                            assertThat(copied.keyText).isEqualTo(source.keyText)
                            assertThat(batch.images.getValue(copied.valueImagePath).readBytes())
                                .isEqualTo(archive.images.getValue(source.valueImagePath).readBytes())
                            source.frontImagePath?.let { original ->
                                assertThat(batch.images.getValue(requireNotNull(copied.frontImagePath)).readBytes())
                                    .isEqualTo(archive.images.getValue(original).readBytes())
                            }
                            val originalReview = archive.snapshot.reviews.single { it.cardId == source.id }
                            val copiedReview = batch.snapshot.reviews.single { it.cardId == copied.id }
                            assertThat(copiedReview.copy(cardId = source.id)).isEqualTo(originalReview)
                        }
                        ImportSummary(batch.snapshot.decks.size, batch.snapshot.cards.size)
                    },
                    idGenerator = generatedIds::getAndIncrement,
                )
                assertThat(importer.importCopy(archive).cardCount).isEqualTo(expectedCards)
            }
        }
        assertThat(temporaryFolder.root.listFiles().orEmpty()).isEmpty()
    }
}

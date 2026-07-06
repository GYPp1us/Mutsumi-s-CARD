package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class BackupServiceTest {
    @Test
    fun exportCreatesManifestDatabaseAndImageEntries() {
        val output = ByteArrayOutputStream()
        BackupService().export(
            snapshot = BackupSnapshot(
                decks = listOf(BackupDeck(1, "日语", 1, 1)),
                cards = listOf(BackupCard(2, 1, "雨の音", "images/value-2.png", null, 2, 2, false)),
                reviews = listOf(BackupReviewState(2, 1.0, 0, 0, 0, 0, null)),
            ),
            images = mapOf("images/value-2.png" to byteArrayOf(1, 2, 3)),
            output = output,
            exportedAt = 100L,
        )

        val names = ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }

        assertThat(names).containsExactly("manifest.json", "database.json", "images/value-2.png")
    }
}


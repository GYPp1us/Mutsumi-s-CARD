package com.mutsumi.card.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupService(
    private val json: Json = Json { prettyPrint = true },
) {
    fun export(
        snapshot: BackupSnapshot,
        images: Map<String, ByteArray>,
        output: OutputStream,
        exportedAt: Long,
    ) {
        ZipOutputStream(output).use { zip ->
            val manifest = BackupManifest(
                version = 1,
                exportedAt = exportedAt,
                appVersion = "0.1.0",
                deckCount = snapshot.decks.size,
                cardCount = snapshot.cards.size,
            )
            zip.writeTextEntry("manifest.json", json.encodeToString(manifest))
            zip.writeTextEntry("database.json", json.encodeToString(snapshot))
            images.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun ZipOutputStream.writeTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}


package com.mutsumi.card.backup

import com.mutsumi.card.data.image.PngDecoder
import com.mutsumi.card.data.image.ValuePngValidator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal val testPngValidator = ValuePngValidator(PngDecoder { 1024 to 2048 })

internal fun validPng(): ByteArray {
    val pixels = ByteArray((1024 * 4 + 1) * 2048)
    val compressed = ByteArrayOutputStream().also { output ->
        DeflaterOutputStream(output).use { it.write(pixels) }
    }.toByteArray()
    return ByteArrayOutputStream().also { output ->
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        output.writePngChunk("IHDR", ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(1024); data.writeInt(2048)
                data.writeByte(8); data.writeByte(6); data.writeByte(0); data.writeByte(0); data.writeByte(0)
            }
        }.toByteArray())
        output.writePngChunk("IDAT", compressed)
        output.writePngChunk("IEND", byteArrayOf())
    }.toByteArray()
}

private fun ByteArrayOutputStream.writePngChunk(type: String, data: ByteArray) {
    val typeBytes = type.encodeToByteArray()
    DataOutputStream(this).apply { writeInt(data.size) }
    write(typeBytes); write(data)
    val crc = CRC32().apply { update(typeBytes); update(data) }.value
    DataOutputStream(this).apply { writeInt(crc.toInt()) }
}

internal fun validSnapshot(
    deck: BackupDeck = BackupDeck(1, "默认卡组", 1, 1),
    card: BackupCard = BackupCard(2, 1, "雨", "images/value-2.png", 1, 1, false),
    review: BackupReviewState = BackupReviewState(2, 1.0, 0, 0, 0, 0, null),
) = BackupSnapshot(listOf(deck), listOf(card), listOf(review))

internal fun createArchive(
    snapshot: BackupSnapshot = validSnapshot(),
    image: ByteArray = validPng(),
    formatVersion: Int = 2,
    imageSha: String? = null,
    imageSize: Long = image.size.toLong(),
): ByteArray {
    val json = Json
    val database = json.encodeToString(snapshot).encodeToByteArray()
    val manifest = BackupManifest(
        formatVersion, 10, "0.4.0", snapshot.decks.size, snapshot.cards.size,
        snapshot.reviews.size, snapshot.cards.size,
        listOf(
            BackupResource("database.json", database.size.toLong(), sha(database)),
            BackupResource("images/value-2.png", imageSize, imageSha ?: sha(image)),
        ),
    )
    return zipOf(
        "manifest.json" to json.encodeToString(manifest).encodeToByteArray(),
        "database.json" to database,
        "images/value-2.png" to image,
    )
}

internal fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    } }
}.toByteArray()

internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun File.writeFixture(bytes: ByteArray): File = apply { writeBytes(bytes) }

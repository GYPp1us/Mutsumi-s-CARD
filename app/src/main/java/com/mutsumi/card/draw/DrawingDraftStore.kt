package com.mutsumi.card.draw

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable data class DraftPointData(val x: Float, val y: Float)
@Serializable data class DraftStrokeData(val points: List<DraftPointData>, val color: Int, val width: Float)
@Serializable data class DraftRectData(val x: Float, val y: Float, val width: Float, val height: Float)
@Serializable data class DraftCameraData(val zoom: Float, val x: Float, val y: Float)
@Serializable data class DraftFaceData(
    val strokes: List<DraftStrokeData> = emptyList(),
    val baseImagePresent: Boolean = false,
    @Transient val baseImageBytes: ByteArray? = null,
    val baseRect: DraftRectData? = null,
    val camera: DraftCameraData? = null,
    val markdown: String = "",
    val markdownWidth: Float = 256f,
    val markdownX: Float = 0f,
    val markdownY: Float = 0f,
) {
    fun sameContent(other: DraftFaceData): Boolean =
        copy(baseImageBytes = null) == other.copy(baseImageBytes = null) &&
            when {
                baseImageBytes == null -> other.baseImageBytes == null
                other.baseImageBytes == null -> false
                else -> baseImageBytes.contentEquals(other.baseImageBytes)
            }
}

@Serializable data class DrawingDraftProject(
    val version: Int = 1,
    val deckId: Long = 0,
    val keyText: String = "",
    val keyLocked: Boolean = false,
    val activeFront: Boolean = true,
    val tool: String = "Pen",
    val penColor: Int = 0xFF16352E.toInt(),
    val penWidth: Float = 6f,
    val front: DraftFaceData = DraftFaceData(),
    val back: DraftFaceData = DraftFaceData(),
) {
    fun sameContent(other: DrawingDraftProject): Boolean = deckId == other.deckId && keyText == other.keyText &&
        front.sameContent(other.front) && back.sameContent(other.back)
}

/** 工程清单和两面底图置于一个原子 ZIP 中，正式卡片仍只存成品 PNG。 */
class DrawingDraftStore(
    private val file: File,
    private val writeAndSync: (File, ByteArray) -> Unit = { pending, bytes ->
        FileOutputStream(pending).use { output -> output.write(bytes); output.flush(); output.fd.sync() }
    },
) {
    private val json = Json { encodeDefaults = true }

    @Synchronized fun save(project: DrawingDraftProject) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建草稿目录" }
        val bytes = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip ->
                fun entry(name: String, content: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
                }
                entry("project.json", json.encodeToString(project).toByteArray(Charsets.UTF_8))
                project.front.baseImageBytes?.let { entry("front-image", it) }
                project.back.baseImageBytes?.let { entry("back-image", it) }
            }
            buffer.toByteArray()
        }
        val pending = File(file.parentFile, "${file.name}.pending")
        try {
            writeAndSync(pending, bytes)
            try {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending.toPath())
        }
    }

    @Synchronized fun load(): DrawingDraftProject? {
        if (!file.exists()) return null
        return ZipFile(file).use { zip ->
            val manifest = requireNotNull(zip.getEntry("project.json")) { "草稿清单缺失" }
            val project = json.decodeFromString<DrawingDraftProject>(zip.getInputStream(manifest).use { it.readBytes().toString(Charsets.UTF_8) })
            require(project.version == 1) { "草稿格式版本不受支持" }
            fun image(face: DraftFaceData, name: String): DraftFaceData = if (face.baseImagePresent) {
                val entry = requireNotNull(zip.getEntry(name)) { "草稿底图缺失" }
                face.copy(baseImageBytes = zip.getInputStream(entry).use { it.readBytes() })
            } else face
            project.copy(front = image(project.front, "front-image"), back = image(project.back, "back-image"))
        }
    }

    @Synchronized fun clear() { Files.deleteIfExists(file.toPath()) }
}

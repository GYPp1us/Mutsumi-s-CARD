package com.mutsumi.card.domain.workflow

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

class PersistentCardStore(
    private val root: File,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    },
) {
    private val snapshotFile = File(root, "cards.json")
    private val imageDir = File(root, "images")

    fun load(): CardDeckSnapshot {
        if (!snapshotFile.exists()) {
            return CardDeckState(seedCards()).toSnapshot()
        }
        return json.decodeFromString<CardDeckSnapshot>(snapshotFile.readText(Charsets.UTF_8))
    }

    fun save(snapshot: CardDeckSnapshot) {
        require(snapshot.nextCardId > 0) { "下一张卡片 ID 必须大于 0" }
        snapshot.cards.forEach { card ->
            require(card.keyText.isNotBlank()) { "持久化失败：存在空 key 卡片 ${card.id}" }
            require(card.valueImagePath.isNotBlank()) { "持久化失败：存在空图片卡片 ${card.id}" }
        }
        root.mkdirs()
        snapshotFile.writeText(json.encodeToString(snapshot), Charsets.UTF_8)
    }

    fun saveImage(bytes: ByteArray, prefix: String): String {
        require(bytes.isNotEmpty()) { "图片内容不能为空" }
        require(prefix.matches(Regex("[a-zA-Z0-9_-]+"))) { "图片文件前缀非法：$prefix" }
        imageDir.mkdirs()
        val name = "$prefix-${System.currentTimeMillis()}-${bytes.contentHashCode().toUInt()}.png"
        val file = File(imageDir, name)
        file.writeBytes(bytes)
        return "images/$name"
    }

    fun readImage(path: String): ByteArray {
        return resolveImage(path).readBytes()
    }

    fun resolveImage(path: String): File {
        require(path.isNotBlank()) { "图片路径不能为空" }
        require(!path.startsWith("sample://")) { "示例图片没有文件路径：$path" }
        val normalized = path.replace('\\', '/')
        require(normalized.startsWith("images/")) { "图片路径必须位于 images 目录：$path" }
        val file = File(root, normalized)
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        require(filePath.startsWith(rootPath)) { "图片路径越界：$path" }
        check(file.exists()) { "图片文件不存在：$path" }
        return file
    }
}

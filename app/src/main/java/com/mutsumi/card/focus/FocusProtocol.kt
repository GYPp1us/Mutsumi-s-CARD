package com.mutsumi.card.focus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI

data class FocusSubject(val id: Int, val name: String)
data class FocusItem(val id: Int, val subjectId: Int, val name: String, val sortOrder: Int)

data class FocusCatalog(val subjects: List<FocusSubject>, val items: List<FocusItem>) {
    fun itemsForSubject(subjectId: Int) = items.filter { it.subjectId == subjectId }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    fun containsSelection(subjectId: Int, itemId: Int) =
        subjects.any { it.id == subjectId } && items.any { it.id == itemId && it.subjectId == subjectId }

    companion object {
        fun parse(raw: String): FocusCatalog {
            val root = Json.parseToJsonElement(raw).jsonObject
            val subjects = root.getValue("subjects").jsonArray.map { entry ->
                val item = entry.jsonObject
                FocusSubject(item.getValue("id").jsonPrimitive.int, item.getValue("name").jsonPrimitive.content)
            }
            val items = root.getValue("focus_items").jsonArray.map { entry ->
                val item = entry.jsonObject
                FocusItem(
                    item.getValue("id").jsonPrimitive.int,
                    item.getValue("subject_id").jsonPrimitive.int,
                    item.getValue("name").jsonPrimitive.content,
                    item["sort_order"]?.jsonPrimitive?.int ?: Int.MAX_VALUE,
                )
            }
            require(subjects.map { it.id }.distinct().size == subjects.size && items.map { it.id }.distinct().size == items.size) { "目录 ID 重复" }
            require(items.all { row -> subjects.any { it.id == row.subjectId } }) { "目录事项缺少科目" }
            return FocusCatalog(subjects, items)
        }
    }
}

object FocusProtocol {
    const val SOURCE = "mutsumi_card"
    const val HEARTBEAT_MILLIS = 20_000L
    private val keyPattern = Regex("[0-9]+\\.[0-9a-f]{64}")
    private const val HOST = "platform.arcol.site"

    fun parseKey(input: String): String {
        val trimmed = input.trim()
        if (keyPattern.matches(trimmed)) return trimmed
        val uri = try { URI(trimmed) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的 408 Dashboard 目录链接或密钥") }
        val prefix = "/api/focus-reporter/"
        val path = uri.path.orEmpty()
        require(uri.scheme == "https" && uri.host == HOST && uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null && path.startsWith(prefix) && path.endsWith("/catalog")) {
            "请输入指定站点的 HTTPS 目录链接"
        }
        val key = path.removePrefix(prefix).removeSuffix("/catalog")
        require(keyPattern.matches(key)) { "目录链接中的密钥格式无效" }
        return key
    }

    fun catalogUrl(key: String): String = "https://$HOST/api/focus-reporter/${parseKey(key)}/catalog"
    fun frameUrl(key: String): String = "https://$HOST/api/focus-reporter/${parseKey(key)}/frame"

    fun focusFrame(catalog: FocusCatalog, subjectId: Int, itemId: Int): String {
        require(catalog.containsSelection(subjectId, itemId)) { "科目与事项不匹配" }
        return buildJsonObject {
            put("source", SOURCE)
            put("state", "focus")
            put("subject_id", subjectId)
            put("focus_item_id", itemId)
        }.toString()
    }

    fun idleFrame(): String = buildJsonObject {
        put("source", SOURCE)
        put("state", "idle")
    }.toString()
}

package com.mutsumi.card.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class AiGenerationException(message: String, cause: Throwable? = null) : IOException(message, cause)

class OpenAiBatchClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun generate(
        settings: AiSettings,
        context: String,
        parameters: AiGenerationParameters,
        onGroup: suspend (AiRawGroup) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(settings.apiKey.isNotBlank()) { "请先在设置中填写 AI API Key" }
        require(parameters.groupCount > 0 && parameters.candidatesPerGroup > 0) { "生成数量必须大于 0" }
        require(context.length <= MAX_CONTEXT_CHARS) { "AI 上下文超过 100K 字符" }
        val endpoint = settings.endpoint.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(buildRequest(settings, context, parameters).toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AiGenerationException("AI 请求失败：HTTP ${response.code}")
            val body = response.body ?: throw AiGenerationException("AI 返回为空")
            val arguments = linkedMapOf<Int, StringBuilder>()
            body.charStream().buffered().forEachLine { line ->
                if (!line.startsWith("data:")) return@forEachLine
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") return@forEachLine
                val root = try { json.parseToJsonElement(payload).jsonObject } catch (error: Exception) {
                    throw AiGenerationException("AI 流数据 JSON 无效", error)
                }
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw AiGenerationException("AI 流数据缺少 choices")
                val calls = choice["delta"]?.jsonObject?.get("tool_calls")?.jsonArray
                    ?: choice["message"]?.jsonObject?.get("tool_calls")?.jsonArray
                    ?: emptyList()
                calls.forEach { element ->
                    val call = element.jsonObject
                    val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val fragment = call["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
                    arguments.getOrPut(index) { StringBuilder() }.append(fragment)
                }
            }
            if (arguments.isEmpty()) throw AiGenerationException("AI 没有返回 tool 调用")
            val groups = arguments.toSortedMap().map { (index, raw) ->
                parseGroup(index, raw.toString(), parameters)
            }
            require(groups.size == parameters.groupCount) {
                "tool 返回 ${groups.size} 组，期望 ${parameters.groupCount} 组"
            }
            require(groups.map { it.index }.toSet().size == groups.size) {
                "tool 返回了重复的 group_index"
            }
            require(groups.map { it.index }.toSet() == (1..parameters.groupCount).toSet()) {
                "tool group_index 不完整"
            }
            groups.sortedBy { it.index }.forEach { group ->
                delay(4000)
                onGroup(group)
            }
        }
    }

    private fun buildRequest(
        settings: AiSettings,
        context: String,
        parameters: AiGenerationParameters,
    ): JsonObject = buildJsonObject {
        put("model", settings.model)
        put("stream", true)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put(
                    "content",
                    "你是记忆卡片编辑助手。只调用 generate_card_group 工具。" +
                        "请生成 ${parameters.groupCount} 组，每组 ${parameters.candidatesPerGroup} 张中文双面 Markdown 卡片。" +
                        "group_index 从 1 开始且每组只调用一次；不得输出空字段。",
                )
            })
            add(buildJsonObject { put("role", "user"); put("content", context) })
        })
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "generate_card_group")
                    put("description", "生成一组双面 Markdown 记忆卡片")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("group_index", buildJsonObject { put("type", "integer") })
                            put("cards", buildJsonObject {
                                put("type", "array")
                                put("minItems", parameters.candidatesPerGroup)
                                put("maxItems", parameters.candidatesPerGroup)
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("key_text", buildJsonObject { put("type", "string") })
                                        put("front_markdown", buildJsonObject { put("type", "string") })
                                        put("back_markdown", buildJsonObject { put("type", "string") })
                                    })
                                    put("required", buildJsonArray { add(JsonPrimitive("key_text")); add(JsonPrimitive("front_markdown")); add(JsonPrimitive("back_markdown")) })
                                })
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("group_index")); add(JsonPrimitive("cards")) })
                    })
                })
            })
        })
        put("tool_choice", buildJsonObject { put("type", "function"); put("function", buildJsonObject { put("name", "generate_card_group") }) })
    }

    private fun parseGroup(index: Int, raw: String, parameters: AiGenerationParameters): AiRawGroup {
        val root = try { json.parseToJsonElement(raw).jsonObject } catch (error: Exception) {
            throw AiGenerationException("tool 参数 JSON 无效", error)
        }
        val cards = root["cards"]?.jsonArray ?: throw AiGenerationException("tool 缺少 cards")
        require(cards.size == parameters.candidatesPerGroup) {
            "tool 返回 ${cards.size} 张卡片，期望 ${parameters.candidatesPerGroup} 张"
        }
        val groupIndex = root["group_index"]?.jsonPrimitive?.intOrNull ?: index + 1
        require(groupIndex in 1..parameters.groupCount) { "tool group_index 超出范围：$groupIndex" }
        return AiRawGroup(groupIndex, cards.map { element ->
            val card = element.jsonObject
            AiRawCard(card.requiredText("key_text"), card.requiredText("front_markdown"), card.requiredText("back_markdown"))
        })
    }

    private fun JsonObject.requiredText(name: String): String = get(name)?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() } ?: throw AiGenerationException("tool 字段为空：$name")

    private companion object { const val MAX_CONTEXT_CHARS = 100_000 }
}

data class AiRawGroup(val index: Int, val cards: List<AiRawCard>)
data class AiRawCard(val keyText: String, val frontMarkdown: String, val backMarkdown: String)

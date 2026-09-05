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
        onProgress: (sentCharacters: Int, receivedCharacters: Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        require(settings.apiKey.isNotBlank()) { "请先在设置中填写 AI API Key" }
        require(parameters.candidatesPerGroup in 1..5) { "每组候选数量必须在 1 到 5 之间" }
        require(context.length <= MAX_CONTEXT_CHARS) { "AI 上下文超过 100K 字符" }
        val requestCharacters = buildRequest(settings, context, parameters).toString().length
        onProgress(requestCharacters, 0)
        val endpoints = endpointCandidates(settings.endpoint)
        var response = executeRequest(endpoints.first(), settings, context, parameters)
        if (response.code == 404 && endpoints.size > 1) {
            response.close()
            response = executeRequest(endpoints[1], settings, context, parameters)
        }
        response.use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()
                    ?.replace(settings.apiKey, "[已隐藏]")
                    ?.trim()
                    ?.take(2000)
                    ?.takeIf { it.isNotEmpty() }
                val suffix = detail?.let { "：$it" }.orEmpty()
                throw AiGenerationException("AI 请求失败：HTTP ${response.code} ${response.message}$suffix")
            }
            val body = response.body ?: throw AiGenerationException("AI 返回为空")
            val arguments = linkedMapOf<Int, StringBuilder>()
            val argumentBudget = AiGenerationResponseBudget()
            var receivedCharacters = 0
            body.charStream().buffered().forEachLine { line ->
                receivedCharacters += line.length
                onProgress(requestCharacters, receivedCharacters)
                if (!line.startsWith("data:")) return@forEachLine
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") return@forEachLine
                val root = try {
                    json.parseToJsonElement(payload).jsonObject
                } catch (error: Exception) {
                    throw AiGenerationException("AI 流数据 JSON 无效", error)
                }
                val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw AiGenerationException("AI 流数据缺少 choices")
                val calls = choice["delta"]?.jsonObject?.get("tool_calls")?.jsonArray
                    ?: choice["message"]?.jsonObject?.get("tool_calls")?.jsonArray
                    ?: emptyList()
                calls.forEach { element ->
                    val call = element.jsonObject
                    val index = call["index"]?.jsonPrimitive?.intOrNull
                        ?: throw AiGenerationException("AI tool 调用缺少 index")
                    val fragment = call["function"]?.jsonObject
                        ?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
                    argumentBudget.append(index, fragment.length)
                    arguments.getOrPut(index) { StringBuilder() }.append(fragment)
                }
            }
            if (arguments.isEmpty()) throw AiGenerationException("AI 没有返回 tool 调用")
            val groups = arguments.toSortedMap().map { (index, raw) ->
                parseGroup(index, raw.toString(), parameters)
            }
            require(parameters.groupCountRange.accepts(groups.size)) {
                "tool 返回 ${groups.size} 组，期望范围 ${parameters.groupCountRange.label}"
            }
            require(groups.map { it.index }.toSet().size == groups.size) {
                "tool 返回了重复的 group_index"
            }
            require(groups.map { it.index }.toSet() == (1..groups.size).toSet()) {
                "tool group_index 必须从 1 连续编号"
            }
            groups.sortedBy { it.index }.forEach { group ->
                delay(4000)
                onGroup(group)
            }
        }
    }

    private fun executeRequest(
        endpoint: String,
        settings: AiSettings,
        context: String,
        parameters: AiGenerationParameters,
    ) = client.newCall(
        Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(buildRequest(settings, context, parameters).toString().toRequestBody("application/json".toMediaType()))
            .build(),
    ).execute()

    private fun endpointCandidates(rawEndpoint: String): List<String> {
        val endpoint = rawEndpoint.trim().trimEnd('/')
        require(endpoint.isNotEmpty()) { "AI 地址不能为空" }
        return when {
            endpoint.endsWith("/chat/completions") -> listOf(endpoint)
            endpoint.endsWith("/v1") -> listOf("$endpoint/chat/completions")
            else -> listOf(
                "$endpoint/chat/completions",
                "$endpoint/v1/chat/completions",
            ).distinct()
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
                        "请生成 ${parameters.groupCountRange.label} 组，每组 ${parameters.candidatesPerGroup} 张中文双面 Markdown 卡片。" +
                        "group_index 从 1 开始连续编号，每组只调用一次；不得输出空字段。",
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
                                    put("required", buildJsonArray {
                                        add(JsonPrimitive("key_text"))
                                        add(JsonPrimitive("front_markdown"))
                                        add(JsonPrimitive("back_markdown"))
                                    })
                                })
                            })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("group_index"))
                            add(JsonPrimitive("cards"))
                        })
                    })
                })
            })
        })
    }

    private fun parseGroup(index: Int, raw: String, parameters: AiGenerationParameters): AiRawGroup {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (error: Exception) {
            throw AiGenerationException(
                "tool 参数 JSON 无效：${error.message ?: "未知 JSON 错误"}",
                error,
            )
        }
        val cards = root["cards"]?.jsonArray ?: throw AiGenerationException("tool 缺少 cards")
        require(cards.size == parameters.candidatesPerGroup) {
            "tool 返回 ${cards.size} 张卡片，期望 ${parameters.candidatesPerGroup} 张"
        }
        val groupIndex = root["group_index"]?.jsonPrimitive?.intOrNull
            ?: throw AiGenerationException("tool 缺少 group_index")
        require(groupIndex > 0) { "tool group_index 必须大于 0：$groupIndex" }
        return AiRawGroup(groupIndex, cards.map { element ->
            val card = element.jsonObject
            AiRawCard(
                card.requiredText("key_text"),
                card.requiredText("front_markdown"),
                card.requiredText("back_markdown"),
            )
        })
    }

    private fun JsonObject.requiredText(name: String): String = get(name)?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() } ?: throw AiGenerationException("tool 字段为空：$name")

    private companion object {
        const val MAX_CONTEXT_CHARS = 100_000
    }
}

data class AiRawGroup(val index: Int, val cards: List<AiRawCard>)
data class AiRawCard(val keyText: String, val frontMarkdown: String, val backMarkdown: String)

/** 为流式 tool 参数设置上限，避免异常服务返回无限长文本时堆积 StringBuilder。 */
internal class AiGenerationResponseBudget(
    private val maxCharactersPerGroup: Int = 160_000,
    private val maxTotalCharacters: Int = 3_200_000,
) {
    private val groupCharacters = mutableMapOf<Int, Int>()
    var totalCharacters: Int = 0
        private set

    init {
        require(maxCharactersPerGroup > 0) { "单组 tool 参数上限必须大于零" }
        require(maxTotalCharacters >= maxCharactersPerGroup) { "tool 参数总上限不能小于单组上限" }
    }

    fun append(groupIndex: Int, characterCount: Int) {
        require(characterCount >= 0) { "tool 参数增量不能为负数" }
        val groupTotal = (groupCharacters[groupIndex] ?: 0) + characterCount
        if (groupTotal > maxCharactersPerGroup) {
            throw AiGenerationException("AI 单个候选组内容超过 ${maxCharactersPerGroup} 字符上限")
        }
        if (totalCharacters + characterCount > maxTotalCharacters) {
            throw AiGenerationException("AI tool 参数总长度超过 ${maxTotalCharacters} 字符上限")
        }
        groupCharacters[groupIndex] = groupTotal
        totalCharacters += characterCount
    }
}

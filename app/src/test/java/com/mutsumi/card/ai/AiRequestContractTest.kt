package com.mutsumi.card.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AiRequestContractTest {
    @Test fun 两种输入的自定义提示词进入真实HTTP请求并返回候选() = runBlocking {
        AiInputMode.entries.forEach { mode ->
            ServerSocket(0).use { server ->
                val captured = CompletableFuture<String>()
                val arguments = """{"group_index":1,"cards":[{"key_text":"主题卡","front_markdown":"问题","back_markdown":"答案"}]}"""
                val event = buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("delta", buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("function", buildJsonObject { put("arguments", arguments) })
                        }) }) })
                    }) })
                }
                val worker = thread {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 10000
                            val input = socket.getInputStream().buffered()
                            val header = StringBuilder()
                            while (!header.endsWith("\r\n\r\n")) {
                                val byte = input.read(); check(byte >= 0); header.append(byte.toChar())
                            }
                            val length = Regex("(?i)content-length: (\\d+)").find(header)?.groupValues?.get(1)!!.toInt()
                            val body = ByteArray(length)
                            var offset = 0
                            while (offset < length) { val read = input.read(body, offset, length-offset); check(read > 0); offset += read }
                            captured.complete(body.toString(Charsets.UTF_8))
                            val payload = "data: $event\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().use { output ->
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                output.write(payload); output.flush()
                            }
                        }
                    } catch (error: Throwable) { captured.completeExceptionally(error) }
                }
                val settings = AiSettings(endpoint = "http://127.0.0.1:${server.localPort}/v1", apiKey = "test-only",
                    knowledgePrompt = "知识库保存的提示", quickTopicPrompt = "主题保存的提示", generationPrompt = "生成保存的提示")
                val state = AiBatchUiState(settings = settings, inputMode = mode, rawText = "知识素材",
                    workflow = AiWorkflowDefinition(setOf("manual-source")), quickTopic = "计算机网络",
                    parameters = AiGenerationParameters(candidatesPerGroup = 1))
                val groups = mutableListOf<AiRawGroup>()
                OpenAiBatchClient().generate(settings, AiInputContext.compose(state).text, state.parameters, { groups.add(it) })
                val request = Json.parseToJsonElement(captured.get(10, TimeUnit.SECONDS)).jsonObject
                val messages = request["messages"]!!.jsonArray
                assertThat(messages[0].jsonObject["content"]!!.jsonPrimitive.content).contains("生成保存的提示")
                assertThat(messages[1].jsonObject["content"]!!.jsonPrimitive.content)
                    .contains(if (mode == AiInputMode.Knowledge) "知识库保存的提示" else "主题保存的提示")
                assertThat(groups.single().cards.single().keyText).isEqualTo("主题卡")
                worker.join(1000)
            }
        }
    }
}

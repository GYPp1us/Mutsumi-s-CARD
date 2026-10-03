package com.mutsumi.card.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AiInputContextTest {
    @Test fun 快速主题无文件也可生成且不会泄入知识库输入() {
        val settings = AiSettings(quickTopicPrompt = "自定义主题提示")
        val state = AiBatchUiState(settings = settings, inputMode = AiInputMode.QuickTopic,
            quickTopic = "二叉树", quickInstructions = "加入复杂度例子", rawText = "不应读取",
            files = listOf(ImportedAiFile("未选文件", "不应发送")))
        val context = AiInputContext.compose(state)
        assertThat(context.text).contains("自定义主题提示")
        assertThat(context.text).contains("二叉树")
        assertThat(context.text).contains("加入复杂度例子")
        assertThat(context.text).doesNotContain("不应")
    }

    @Test fun 知识库只读选中素材并使用保存的提示词() {
        val context = AiInputContext.compose(AiBatchUiState(settings = AiSettings(knowledgePrompt = "知识库自定义"),
            rawText = "保留", workflow = AiWorkflowDefinition(setOf("manual-source")), quickTopic = "主题不得发送"))
        assertThat(context.text).contains("知识库自定义")
        assertThat(context.text).contains("保留")
        assertThat(context.text).doesNotContain("主题不得发送")
    }

    @Test fun 实际请求使用生成助手提示词并保留tool协议约束() {
        val request = OpenAiBatchClient().buildRequest(AiSettings(generationPrompt = "我保存的生成提示词"), "主题内容", AiGenerationParameters())
        val system = request["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertThat(system).contains("我保存的生成提示词")
        assertThat(system).contains("generate_card_group")
        assertThat(request["tools"]!!.jsonArray).hasSize(1)
    }

    @Test fun 空主题拒绝生成() {
        assertThat(runCatching { AiInputContext.compose(AiBatchUiState(inputMode = AiInputMode.QuickTopic)) }.exceptionOrNull())
            .isInstanceOf(AiWorkflowException::class.java)
    }
}

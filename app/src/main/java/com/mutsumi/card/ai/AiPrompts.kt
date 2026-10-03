package com.mutsumi.card.ai

object AiPrompts {
    const val KNOWLEDGE = "根据已连接的笔记材料生成双面 Markdown 记忆卡片。先遵循工作流拆分与拼接结果，再覆盖所有材料主题。"
    const val QUICK_TOPIC = "根据用户给出的学习主题与补充要求，生成准确、清晰的中文双面记忆卡片。拆成独立知识点，正面提出可回忆的问题，背面给出答案、解释与必要的例子。"
    const val GENERATION = "你是记忆卡片编辑助手。用清晰简洁的中文制作适合主动回忆的双面 Markdown 卡片，避免把多个无关知识点塞进一张卡片。"
}

enum class AiInputMode(val label: String) { Knowledge("知识库分割"), QuickTopic("快速主题录入") }

/** 两分支只读取各自输入，共用候选参数与上下文预算。 */
object AiInputContext {
    fun compose(state: AiBatchUiState): AiWorkflowContext {
        if (state.inputMode == AiInputMode.QuickTopic) {
            val topic = state.quickTopic.trim()
            if (topic.isEmpty()) throw AiWorkflowException("请输入学习主题")
            val source = ImportedAiFile("主题说明.md", "学习主题：$topic\n补充要求：${state.quickInstructions.trim()}", "quick-topic")
            val definition = AiWorkflowDefinition(setOf(source.id), AiSplitMode.WholeDocument, AiCombineMode.Separate)
            return AiWorkflowContextComposer.compose(AiWorkflowPlanner.plan(listOf(source), definition), definition,
                state.parameters, state.settings.quickTopicPrompt)
        }
        val sources = state.files + state.rawText.trim().takeIf(String::isNotEmpty)?.let {
            listOf(ImportedAiFile("手动补充素材.md", it, "manual-source"))
        }.orEmpty()
        return AiWorkflowContextComposer.compose(AiWorkflowPlanner.plan(sources, state.workflow), state.workflow,
            state.parameters, state.settings.knowledgePrompt)
    }
}

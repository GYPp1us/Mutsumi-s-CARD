package com.mutsumi.card.ai

private const val FIXED_CHUNK_SIZE = 2_400

class AiWorkflowException(message: String) : IllegalArgumentException(message)

data class AiWorkflowSegment(
    val sourceName: String,
    val ordinal: Int,
    val content: String,
)

data class AiMaterialBundle(
    val index: Int,
    val sourceNames: List<String>,
    val content: String,
) {
    val label: String
        get() = "材料组 $index · ${sourceNames.joinToString("、")}"
}

data class AiWorkflowPlan(
    val selectedSources: List<ImportedAiFile>,
    val segments: List<AiWorkflowSegment>,
    val bundles: List<AiMaterialBundle>,
) {
    val summary: AiWorkflowSummary = AiWorkflowSummary(
        selectedSourceCount = selectedSources.size,
        segmentCount = segments.size,
        materialBundleCount = bundles.size,
    )
}

/**
 * 将素材选择、拆分和拼接节点落实为发送给模型的材料组。
 * 所有转换都是纯函数，便于在请求前预览和单元测试。
 */
object AiWorkflowPlanner {
    fun plan(
        sources: List<ImportedAiFile>,
        definition: AiWorkflowDefinition,
    ): AiWorkflowPlan {
        val selectedSources = sources.filter { it.id in definition.selectedSourceIds }
        if (selectedSources.isEmpty()) {
            throw AiWorkflowException("请至少连接一份非空素材到工作流")
        }
        val segments = selectedSources.flatMap { source -> split(source, definition.splitMode) }
        if (segments.isEmpty()) {
            throw AiWorkflowException("所选素材没有可用内容")
        }
        return AiWorkflowPlan(
            selectedSources = selectedSources,
            segments = segments,
            bundles = combine(segments, definition),
        )
    }

    fun summary(
        sources: List<ImportedAiFile>,
        definition: AiWorkflowDefinition,
    ): AiWorkflowSummary = if (sources.any { it.id in definition.selectedSourceIds && it.content.isNotBlank() }) {
        plan(sources, definition).summary
    } else {
        AiWorkflowSummary(selectedSourceCount = sources.count { it.id in definition.selectedSourceIds })
    }

    private fun split(source: ImportedAiFile, mode: AiSplitMode): List<AiWorkflowSegment> {
        val pieces = when (mode) {
            AiSplitMode.WholeDocument -> listOf(source.content)
            AiSplitMode.MarkdownHeading -> source.content.split(Regex("(?m)(?=^#{1,6}\\s+)"))
            AiSplitMode.Paragraph -> source.content.split(Regex("(?:\\r?\\n)\\s*(?:\\r?\\n)+"))
            AiSplitMode.FixedLength -> source.content.chunked(FIXED_CHUNK_SIZE)
        }.map(String::trim).filter(String::isNotBlank)
        return pieces.mapIndexed { index, content ->
            AiWorkflowSegment(
                sourceName = source.name,
                ordinal = index + 1,
                content = content,
            )
        }
    }

    private fun combine(
        segments: List<AiWorkflowSegment>,
        definition: AiWorkflowDefinition,
    ): List<AiMaterialBundle> {
        val groups = when (definition.combineMode) {
            AiCombineMode.Separate -> segments.map(::listOf)
            AiCombineMode.Adjacent -> segments.chunked(definition.combineSize.coerceIn(2, 8))
            AiCombineMode.AllSelected -> listOf(segments)
        }
        return groups.mapIndexed { index, group ->
            AiMaterialBundle(
                index = index + 1,
                sourceNames = group.map(AiWorkflowSegment::sourceName).distinct(),
                content = group.joinToString("\n\n--- 下一片段 ---\n\n") { segment ->
                    "[${segment.sourceName} · 片段 ${segment.ordinal}]\n${segment.content}"
                },
            )
        }
    }
}

data class AiWorkflowContext(
    val text: String,
    val wasTruncated: Boolean,
    val omittedCharacters: Int,
)

/** 保持 AI 上下文的固定顺序，并只裁切材料内容，不裁切生成参数。 */
object AiWorkflowContextComposer {
    const val MAX_CONTEXT_CHARACTERS = 100_000
    private const val TRUNCATION_MARKER = "\n[材料内容因 100K 上限被截断]\n"

    fun compose(
        plan: AiWorkflowPlan,
        definition: AiWorkflowDefinition,
        parameters: AiGenerationParameters,
    ): AiWorkflowContext {
        val systemSection = buildString {
            append("系统提示：\n")
            append("根据已连接的笔记材料生成双面 Markdown 记忆卡片。")
            append("先遵循工作流拆分与拼接结果，再覆盖所有材料主题。\n\n")
        }
        val fileListSection = buildString {
            append("文件列表：\n")
            plan.selectedSources.forEach { append("- ").append(it.name).append('\n') }
            append('\n')
        }
        val parameterSection = buildString {
            append("\n生成参数：")
            append("卡组数量范围=").append(parameters.groupCountRange.label)
            append("；每组候选数量=").append(parameters.candidatesPerGroup)
            append("；目标卡组=").append(parameters.targetDeckId)
            append("；拆分=").append(definition.splitMode.label)
            append("；拼接=").append(definition.combineMode.label)
            if (definition.combineMode == AiCombineMode.Adjacent) {
                append("（每组 ").append(definition.combineSize.coerceIn(2, 8)).append(" 片段）")
            }
            append('\n')
        }
        val prefix = systemSection + fileListSection + "文件内容：\n"
        val reservedCharacters = prefix.length + parameterSection.length
        if (reservedCharacters >= MAX_CONTEXT_CHARACTERS) {
            throw AiWorkflowException("文件列表和生成参数已超过 100K 字符，请减少所选素材")
        }

        val unboundedContent = plan.bundles.joinToString("\n\n") { bundle ->
            "## ${bundle.label}\n${bundle.content}"
        }
        val contentBudget = MAX_CONTEXT_CHARACTERS - reservedCharacters
        val wasTruncated = unboundedContent.length > contentBudget
        val includedContent = if (wasTruncated) {
            val usable = (contentBudget - TRUNCATION_MARKER.length).coerceAtLeast(0)
            unboundedContent.take(usable) + TRUNCATION_MARKER
        } else {
            unboundedContent
        }
        val text = prefix + includedContent + parameterSection
        check(text.length <= MAX_CONTEXT_CHARACTERS) { "AI 上下文长度计算错误" }
        return AiWorkflowContext(
            text = text,
            wasTruncated = wasTruncated,
            omittedCharacters = (unboundedContent.length - includedContent.length).coerceAtLeast(0),
        )
    }
}

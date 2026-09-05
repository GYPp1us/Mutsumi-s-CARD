package com.mutsumi.card.ai

enum class AiGroupCountRange(
    val label: String,
    val minimum: Int,
    val maximum: Int?,
) {
    OneToFive("1-5", 1, 5),
    FiveToTen("5-10", 5, 10),
    TenToTwenty("10-20", 10, 20),
    TwentyPlus("20-24", 20, MAXIMUM_GROUPS_PER_RUN),
    ;

    fun accepts(count: Int): Boolean = count >= minimum && (maximum == null || count <= maximum)
}

data class ImportedAiFile(
    val name: String,
    val content: String,
    val id: String = name,
)

/** 用于将大型笔记库转为可控 AI 上下文的节点配置。 */
data class AiWorkflowDefinition(
    val selectedSourceIds: Set<String> = emptySet(),
    val splitMode: AiSplitMode = AiSplitMode.MarkdownHeading,
    val combineMode: AiCombineMode = AiCombineMode.Adjacent,
    val combineSize: Int = 2,
)

enum class AiSplitMode(
    val label: String,
    val description: String,
) {
    WholeDocument("整篇", "每份笔记保持完整"),
    MarkdownHeading("按标题", "按 Markdown 标题拆分"),
    Paragraph("按段落", "按空行拆分"),
    FixedLength("定长", "每 2,400 字符拆分"),
}

enum class AiCombineMode(
    val label: String,
    val description: String,
) {
    Separate("分别生成", "每个片段独立提供给模型"),
    Adjacent("相邻拼接", "将相邻片段合为一个材料组"),
    AllSelected("全部拼接", "把当前所有片段合为一个材料组"),
}

data class AiWorkflowSummary(
    val selectedSourceCount: Int = 0,
    val segmentCount: Int = 0,
    val materialBundleCount: Int = 0,
)

data class AiCardCandidate(
    val keyText: String,
    val frontMarkdown: String,
    val backMarkdown: String,
    val frontPng: ByteArray,
    val backPng: ByteArray,
)

data class AiCandidateGroup(val index: Int, val cards: List<AiCardCandidate>)

data class AiGenerationParameters(
    val groupCountRange: AiGroupCountRange = AiGroupCountRange.OneToFive,
    val candidatesPerGroup: Int = 3,
    val targetDeckId: Long = 0L,
)

data class AiBatchUiState(
    val settings: AiSettings = AiSettings(),
    val files: List<ImportedAiFile> = emptyList(),
    val rawText: String = "",
    val workflow: AiWorkflowDefinition = AiWorkflowDefinition(),
    val workflowSummary: AiWorkflowSummary = AiWorkflowSummary(),
    val sourceImportIssues: List<AiSourceImportIssue> = emptyList(),
    val parameters: AiGenerationParameters = AiGenerationParameters(),
    val decks: List<com.mutsumi.card.domain.model.Deck> = emptyList(),
    val groups: List<AiCandidateGroup> = emptyList(),
    val groupIndex: Int = 0,
    val selectedCardIndex: Int = 0,
    val isImporting: Boolean = false,
    val isGenerating: Boolean = false,
    val isWaitingForCandidateCapacity: Boolean = false,
    val isSaving: Boolean = false,
    val message: String = "",
    val errorMessage: String? = null,
    val contextWarning: String? = null,
    val manualSourceWarning: String? = null,
    val rawTextEdited: Boolean = false,
)

private const val MAXIMUM_GROUPS_PER_RUN = 24

internal const val MAX_MANUAL_SOURCE_CHARACTERS = 100_000

internal fun limitManualSource(value: String): String = value.take(MAX_MANUAL_SOURCE_CHARACTERS)

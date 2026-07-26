package com.mutsumi.card.ai

data class ImportedAiFile(val name: String, val content: String)

data class AiCardCandidate(
    val keyText: String,
    val frontMarkdown: String,
    val backMarkdown: String,
    val frontPng: ByteArray,
    val backPng: ByteArray,
)

data class AiCandidateGroup(val index: Int, val cards: List<AiCardCandidate>)

data class AiGenerationParameters(
    val groupCount: Int = 3,
    val candidatesPerGroup: Int = 3,
    val targetDeckId: Long = 0L,
)

data class AiBatchUiState(
    val settings: AiSettings = AiSettings(),
    val files: List<ImportedAiFile> = emptyList(),
    val rawText: String = "",
    val parameters: AiGenerationParameters = AiGenerationParameters(),
    val decks: List<com.mutsumi.card.domain.model.Deck> = emptyList(),
    val groups: List<AiCandidateGroup> = emptyList(),
    val groupIndex: Int = 0,
    val selectedCardIndex: Int = 0,
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val message: String = "",
    val contextWarning: String? = null,
    val rawTextEdited: Boolean = false,
)

package com.mutsumi.card.ai

import android.graphics.Bitmap
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutsumi.card.data.CardRepository
import com.mutsumi.card.draw.DrawingCanvasSpec
import com.mutsumi.card.draw.MarkdownLayerRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class AiBatchViewModel(
    private val context: Context,
    private val repository: CardRepository,
    private val settingsStore: AiSettingsStore,
    private val client: OpenAiBatchClient = OpenAiBatchClient(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(AiBatchUiState())
    val state: StateFlow<AiBatchUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val decks = repository.decks.first()
            mutableState.value = mutableState.value.copy(
                settings = settingsStore.load(),
                decks = decks,
                parameters = mutableState.value.parameters.copy(targetDeckId = decks.firstOrNull()?.id ?: 0L),
            )
        }
    }

    fun setRawText(value: String) { update { copy(rawText = value, rawTextEdited = true) } }
    fun showMessage(value: String) { update { copy(message = value) } }
    fun setParameters(parameters: AiGenerationParameters) { update { copy(parameters = parameters) } }
    fun selectGroup(index: Int) { update { copy(groupIndex = index.coerceIn(0, groups.lastIndex.coerceAtLeast(0)), selectedCardIndex = 0) } }
    fun selectCard(index: Int) { update { copy(selectedCardIndex = index.coerceAtLeast(0)) } }

    fun setImportedFiles(files: List<ImportedAiFile>) {
        val content = files.joinToString("\n\n") { "# 文件：${it.name}\n\n${it.content}" }
        update { copy(files = files, rawText = content, rawTextEdited = false) }
    }

    fun saveSettings(settings: AiSettings) {
        viewModelScope.launch {
            settingsStore.save(settings)
            update { copy(settings = settings, message = "AI 设置已保存") }
        }
    }

    fun createDeck(name: String) {
        viewModelScope.launch {
            val id = repository.createDeck(name)
            val decks = repository.decks.first()
            update { copy(decks = decks, parameters = parameters.copy(targetDeckId = id), message = "已创建卡组：$name") }
        }
    }

    fun generate() {
        val current = mutableState.value
        if (current.isGenerating || current.isSaving) return
        viewModelScope.launch {
            val contextResult = buildContext(current)
            update {
                copy(
                    isGenerating = true,
                    message = "正在等待 AI 生成",
                    contextWarning = if (contextResult.wasTruncated) "上下文超过 100K 字符，已截断到 100K" else null,
                )
            }
            try {
                client.generate(current.settings, contextResult.text, current.parameters) { rawGroup ->
                    val group = rawGroup.toCandidateGroup()
                    update {
                        copy(
                            groups = groups + group,
                            message = "已收到第 ${groups.size + 1} 组候选",
                        )
                    }
                }
                update { copy(isGenerating = false, message = "候选生成完成") }
            } catch (error: Exception) {
                update { copy(isGenerating = false, message = "生成失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun saveCurrentAndNext() {
        val current = mutableState.value
        val group = current.groups.getOrNull(current.groupIndex) ?: return
        val candidate = group.cards.getOrNull(current.selectedCardIndex) ?: return
        val deckId = current.parameters.targetDeckId.takeIf { it > 0 } ?: current.decks.firstOrNull()?.id ?: return
        viewModelScope.launch {
            update { copy(isSaving = true, message = "正在保存卡片") }
            try {
                repository.saveCard(deckId, candidate.keyText, candidate.frontPng, candidate.backPng)
                val next = (current.groupIndex + 1).coerceAtMost(current.groups.lastIndex.coerceAtLeast(0))
                update { copy(isSaving = false, groupIndex = next, selectedCardIndex = 0, message = "已保存：${candidate.keyText}") }
            } catch (error: Exception) {
                update { copy(isSaving = false, message = "保存失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    fun previousGroup() { update { copy(groupIndex = (groupIndex - 1).coerceAtLeast(0), selectedCardIndex = 0) } }
    fun nextGroup() { update { copy(groupIndex = (groupIndex + 1).coerceAtMost(groups.lastIndex.coerceAtLeast(0)), selectedCardIndex = 0) } }

    private fun buildContext(state: AiBatchUiState): ContextBuildResult {
        val sources = when {
            state.files.isEmpty() -> listOf(ImportedAiFile("手动输入素材.txt", state.rawText))
            state.rawTextEdited -> listOf(ImportedAiFile("手动编辑素材.txt", state.rawText))
            else -> state.files
        }
        val builder = StringBuilder()
        builder.append("system prompt：生成双面 Markdown 记忆卡片。\n")
        builder.append("文件列表：\n")
        sources.forEach { builder.append("- ").append(it.name).append('\n') }
        sources.forEach {
            builder.append("\n文件内容：").append(it.name).append('\n')
                .append(it.content).append('\n')
        }
        builder.append("\n生成参数：卡片组数量=").append(state.parameters.groupCount)
            .append("，每组候选数量=").append(state.parameters.candidatesPerGroup)
            .append("，目标卡组=").append(state.parameters.targetDeckId).append('\n')
        val fullText = builder.toString()
        return ContextBuildResult(fullText.take(MAX_CONTEXT_CHARS), fullText.length > MAX_CONTEXT_CHARS)
    }

    private fun AiRawGroup.toCandidateGroup(): AiCandidateGroup = AiCandidateGroup(
        index = index,
        cards = cards.map { card ->
            val renderer = MarkdownLayerRenderer(context)
            val front = requireNotNull(renderer.render(card.frontMarkdown, DrawingCanvasSpec.width, DrawingCanvasSpec.height))
            val back = requireNotNull(renderer.render(card.backMarkdown, DrawingCanvasSpec.width, DrawingCanvasSpec.height))
            AiCardCandidate(card.keyText, card.frontMarkdown, card.backMarkdown, front.toPng(), back.toPng()).also {
                front.recycle()
                back.recycle()
            }
        },
    )

    private fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().use { output ->
        check(compress(Bitmap.CompressFormat.PNG, 100, output)) { "Markdown 图片生成失败" }
        output.toByteArray()
    }

    private fun update(transform: AiBatchUiState.() -> AiBatchUiState) {
        mutableState.value = mutableState.value.transform()
    }

    private data class ContextBuildResult(val text: String, val wasTruncated: Boolean)

    private companion object { const val MAX_CONTEXT_CHARS = 100_000 }
}

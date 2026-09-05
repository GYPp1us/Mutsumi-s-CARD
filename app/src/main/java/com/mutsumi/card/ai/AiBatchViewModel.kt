package com.mutsumi.card.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutsumi.card.data.CardRepository
import com.mutsumi.card.draw.DrawingCanvasSpec
import com.mutsumi.card.draw.MarkdownLayerRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update as updateState
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class AiBatchViewModel(
    private val context: Context,
    private val repository: CardRepository,
    private val settingsStore: AiSettingsStore,
    private val client: OpenAiBatchClient = OpenAiBatchClient(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(AiBatchUiState())
    private val sourceImporter = AiSourceImporter(context.applicationContext)
    private val candidateQueueSlots = Semaphore(AiCandidateQueue.MAX_PENDING_GROUPS)
    private val candidateQueueLock = Any()
    private val workflowSummaryRevision = AtomicInteger()
    private var workflowSummaryJob: Job? = null
    val state: StateFlow<AiBatchUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val decks = repository.decks.first()
                mutableState.value = mutableState.value.copy(
                    settings = settingsStore.load(),
                    decks = decks,
                    parameters = mutableState.value.parameters.copy(targetDeckId = decks.firstOrNull()?.id ?: 0L),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError("AI 页面初始化失败：${error.message ?: "无法读取本地设置或卡组"}")
            }
        }
    }

    fun setRawText(value: String) {
        val limitedValue = limitManualSource(value)
        updateWorkflow { current ->
            val selected = current.workflow.selectedSourceIds.toMutableSet().apply {
                if (limitedValue.isBlank()) remove(MANUAL_SOURCE_ID) else add(MANUAL_SOURCE_ID)
            }
            current.copy(
                rawText = limitedValue,
                rawTextEdited = true,
                workflow = current.workflow.copy(selectedSourceIds = selected),
                manualSourceWarning = if (limitedValue.length < value.length) {
                    "手动素材最多 100,000 字符，超出部分未加入工作流"
                } else {
                    null
                },
            )
        }
    }

    fun showMessage(value: String) { update { copy(message = value, errorMessage = null) } }
    fun showError(value: String) { update { copy(message = value, errorMessage = value) } }
    fun setParameters(parameters: AiGenerationParameters) { update { copy(parameters = parameters) } }
    fun selectGroup(index: Int) {
        if (mutableState.value.isSaving) return
        update { copy(groupIndex = index.coerceIn(0, groups.lastIndex.coerceAtLeast(0)), selectedCardIndex = 0) }
    }
    fun selectCard(index: Int) {
        if (mutableState.value.isSaving) return
        update { copy(selectedCardIndex = index.coerceAtLeast(0)) }
    }

    fun importDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) {
            showMessage("已取消选择素材")
            return
        }
        importSources(
            progressMessage = "正在读取 ${uris.size} 份素材",
            emptyMessage = "没有导入可用的文本素材",
        ) { existingFiles ->
            sourceImporter.readDocuments(uris, existingFiles)
        }
    }

    fun importTree(uri: Uri) {
        importSources(
            progressMessage = "正在递归读取文件夹素材",
            emptyMessage = "所选文件夹中没有 .txt、.md 或 .markdown 素材",
        ) { existingFiles ->
            sourceImporter.readTree(uri, existingFiles)
        }
    }

    fun setImportedFiles(files: List<ImportedAiFile>) {
        if (files.isEmpty()) return
        updateWorkflow { current ->
            val merged = (current.files + files)
                .distinctBy(ImportedAiFile::id)
            current.copy(
                files = merged,
                workflow = current.workflow.copy(
                    selectedSourceIds = current.workflow.selectedSourceIds + files.map(ImportedAiFile::id),
                ),
            )
        }
    }

    fun setSourceImportIssues(issues: List<AiSourceImportIssue>) {
        update {
            copy(
                sourceImportIssues = issues,
                message = when {
                    issues.isEmpty() -> message
                    else -> "素材导入完成，其中 ${issues.size} 项需要处理"
                },
            )
        }
    }

    fun setSourceSelected(sourceId: String, selected: Boolean) {
        updateWorkflow { current ->
            val sourceIds = current.workflow.selectedSourceIds.toMutableSet().apply {
                if (selected) add(sourceId) else remove(sourceId)
            }
            current.copy(workflow = current.workflow.copy(selectedSourceIds = sourceIds))
        }
    }

    fun removeSource(sourceId: String) {
        updateWorkflow { current ->
            current.copy(
                files = current.files.filterNot { it.id == sourceId },
                workflow = current.workflow.copy(
                    selectedSourceIds = current.workflow.selectedSourceIds - sourceId,
                ),
            )
        }
    }

    fun setWorkflow(definition: AiWorkflowDefinition) {
        updateWorkflow { current -> current.copy(workflow = definition) }
    }

    fun saveSettings(settings: AiSettings) {
        viewModelScope.launch {
            try {
                settingsStore.save(settings)
                update { copy(settings = settings, message = "AI 设置已保存", errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError("AI 设置保存失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun createDeck(name: String) {
        viewModelScope.launch {
            try {
                val normalized = name.trim()
                require(normalized.isNotEmpty()) { "卡组名称不能为空" }
                val id = repository.createDeck(normalized)
                val decks = repository.decks.first()
                update {
                    copy(
                        decks = decks,
                        parameters = parameters.copy(targetDeckId = id),
                        message = "已创建卡组：$normalized",
                        errorMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError("创建卡组失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun generate() {
        val current = mutableState.value
        if (current.isGenerating || current.isSaving || current.isImporting) return
        if (current.groups.isNotEmpty()) {
            showError("请先保存当前候选队列后再运行新的工作流")
            return
        }
        viewModelScope.launch {
            try {
                val contextResult = buildContext(current)
                update {
                    copy(
                        isGenerating = true,
                        isWaitingForCandidateCapacity = false,
                        message = "正在等待 AI 生成",
                        errorMessage = null,
                        contextWarning = if (contextResult.wasTruncated) {
                            "材料内容超过 100K 字符，已截断 ${contextResult.omittedCharacters} 个字符"
                        } else {
                            null
                        },
                    )
                }
                client.generate(
                    settings = current.settings,
                    context = contextResult.text,
                    parameters = current.parameters,
                    onGroup = { rawGroup ->
                        enqueueCandidateGroup(rawGroup)
                    },
                    onProgress = { sentCharacters, receivedCharacters ->
                        update {
                            copy(message = "正在生成：↑$sentCharacters,↓$receivedCharacters")
                        }
                    },
                )
                update { copy(isGenerating = false, isWaitingForCandidateCapacity = false, message = "候选生成完成") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "生成失败：${error.message ?: "未知错误"}"
                update {
                    copy(
                        isGenerating = false,
                        isWaitingForCandidateCapacity = false,
                        message = message,
                        errorMessage = message,
                    )
                }
            }
        }
    }

    fun saveCurrentAndNext() {
        val current = mutableState.value
        if (current.isSaving) return
        val group = current.groups.getOrNull(current.groupIndex)
        if (group == null) {
            showError("保存失败：当前没有可保存的候选组")
            return
        }
        val candidate = group.cards.getOrNull(current.selectedCardIndex)
        if (candidate == null) {
            showError("保存失败：请先选择一张候选卡片")
            return
        }
        val deckId = current.parameters.targetDeckId.takeIf { it > 0 } ?: current.decks.firstOrNull()?.id
        if (deckId == null) {
            showError("保存失败：请先选择目标卡组")
            return
        }
        update { copy(isSaving = true, message = "正在保存卡片", errorMessage = null) }
        viewModelScope.launch {
            try {
                repository.saveCard(deckId, candidate.keyText, candidate.frontPng, candidate.backPng)
                val consumed = synchronized(candidateQueueLock) {
                    val latest = mutableState.value
                    val latestGroupIndex = latest.groups.indexOfFirst { it.index == group.index }
                    if (latestGroupIndex < 0) {
                        false
                    } else {
                        val consumption = AiCandidateQueue.consume(
                            groups = latest.groups,
                            groupIndex = latestGroupIndex,
                            cardIndex = current.selectedCardIndex,
                        )
                        mutableState.value = latest.copy(
                            isSaving = false,
                            groups = consumption.groups,
                            groupIndex = consumption.groupIndex,
                            selectedCardIndex = consumption.selectedCardIndex,
                            message = "已保存：${candidate.keyText}",
                        )
                        true
                    }
                }
                if (consumed) {
                    candidateQueueSlots.release()
                } else {
                    update {
                        copy(
                            isSaving = false,
                            message = "保存完成，但候选队列已更新，请检查结果",
                            errorMessage = "保存完成，但候选队列已更新，请检查结果",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "保存失败：${error.message ?: "未知错误"}"
                update { copy(isSaving = false, message = message, errorMessage = message) }
            }
        }
    }

    fun previousGroup() {
        if (mutableState.value.isSaving) return
        update { copy(groupIndex = (groupIndex - 1).coerceAtLeast(0), selectedCardIndex = 0) }
    }
    fun nextGroup() {
        if (mutableState.value.isSaving) return
        update { copy(groupIndex = (groupIndex + 1).coerceAtMost(groups.lastIndex.coerceAtLeast(0)), selectedCardIndex = 0) }
    }

    private fun importSources(
        progressMessage: String,
        emptyMessage: String,
        read: (List<ImportedAiFile>) -> AiSourceImportResult,
    ) {
        if (mutableState.value.isImporting) return
        val existingFiles = mutableState.value.files
        update { copy(isImporting = true, message = progressMessage, errorMessage = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { read(existingFiles) }
                completeSourceImport(result, emptyMessage)
            } catch (cancelled: CancellationException) {
                update { copy(isImporting = false) }
                throw cancelled
            } catch (error: AiWorkflowException) {
                showError("素材读取失败：${error.message ?: "无法读取素材"}")
                update { copy(isImporting = false) }
            } catch (error: SecurityException) {
                showError("素材读取失败：${error.message ?: "没有读取权限"}")
                update { copy(isImporting = false) }
            }
        }
    }

    private fun completeSourceImport(result: AiSourceImportResult, emptyMessage: String) {
        updateWorkflow { current ->
            val merged = (current.files + result.files).distinctBy(ImportedAiFile::id)
            val isEmpty = result.files.isEmpty() && result.issues.isEmpty()
            current.copy(
                files = merged,
                workflow = current.workflow.copy(
                    selectedSourceIds = current.workflow.selectedSourceIds + result.files.map(ImportedAiFile::id),
                ),
                sourceImportIssues = result.issues,
                isImporting = false,
                message = when {
                    isEmpty -> emptyMessage
                    result.issues.isNotEmpty() -> "素材导入完成，其中 ${result.issues.size} 项需要处理"
                    else -> "已导入 ${result.files.size} 份素材"
                },
                errorMessage = if (isEmpty) emptyMessage else null,
            )
        }
    }

    private suspend fun enqueueCandidateGroup(rawGroup: AiRawGroup) {
        var acquired = false
        var enqueued = false
        try {
            if (!candidateQueueSlots.tryAcquire()) {
                update {
                    copy(
                        isWaitingForCandidateCapacity = true,
                        message = "候选队列已满，请保存一组候选后继续生成",
                        errorMessage = null,
                    )
                }
                candidateQueueSlots.acquire()
            }
            acquired = true
            val group = rawGroup.toCandidateGroup()
            AiCandidateQueue.requireWithinByteLimit(group)
            synchronized(candidateQueueLock) {
                update {
                    copy(
                        groups = groups + group,
                        isWaitingForCandidateCapacity = false,
                        message = "已生成第 ${group.index} 组候选",
                    )
                }
            }
            enqueued = true
        } finally {
            if (acquired && !enqueued) {
                candidateQueueSlots.release()
                update { copy(isWaitingForCandidateCapacity = false) }
            }
        }
    }

    private fun buildContext(state: AiBatchUiState): AiWorkflowContext = AiWorkflowContextComposer.compose(
        plan = AiWorkflowPlanner.plan(sourcesForWorkflow(state), state.workflow),
        definition = state.workflow,
        parameters = state.parameters,
    )

    private fun AiRawGroup.toCandidateGroup(): AiCandidateGroup = AiCandidateGroup(
        index = index,
        cards = cards.map { card ->
            require(card.keyText.length <= MAX_CANDIDATE_KEY_CHARACTERS) { "AI 候选 key 过长" }
            require(card.frontMarkdown.length <= MAX_CANDIDATE_MARKDOWN_CHARACTERS) { "AI 候选正面内容过长" }
            require(card.backMarkdown.length <= MAX_CANDIDATE_MARKDOWN_CHARACTERS) { "AI 候选背面内容过长" }
            val renderer = MarkdownLayerRenderer(context)
            val front = requireNotNull(renderer.render(card.frontMarkdown, DrawingCanvasSpec.width, DrawingCanvasSpec.height))
            try {
                val back = requireNotNull(renderer.render(card.backMarkdown, DrawingCanvasSpec.width, DrawingCanvasSpec.height))
                try {
                    AiCardCandidate(card.keyText, card.frontMarkdown, card.backMarkdown, front.toPng(), back.toPng())
                } finally {
                    back.recycle()
                }
            } finally {
                front.recycle()
            }
        },
    )

    private fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().use { output ->
        check(compress(Bitmap.CompressFormat.PNG, 100, output)) { "Markdown 图片生成失败" }
        output.toByteArray()
    }

    private fun update(transform: AiBatchUiState.() -> AiBatchUiState) {
        mutableState.updateState { current -> current.transform() }
    }

    private fun updateWorkflow(transform: (AiBatchUiState) -> AiBatchUiState) {
        val revision = workflowSummaryRevision.incrementAndGet()
        mutableState.updateState { current -> transform(current) }
        val snapshot = mutableState.value
        workflowSummaryJob?.cancel()
        workflowSummaryJob = viewModelScope.launch(Dispatchers.Default) {
            val summary = AiWorkflowPlanner.summary(sourcesForWorkflow(snapshot), snapshot.workflow)
            withContext(Dispatchers.Main.immediate) {
                if (workflowSummaryRevision.get() == revision) {
                    update { copy(workflowSummary = summary) }
                }
            }
        }
    }

    private fun sourcesForWorkflow(state: AiBatchUiState): List<ImportedAiFile> = buildList {
        addAll(state.files)
        state.rawText.trim().takeIf(String::isNotEmpty)?.let { manualText ->
            add(
                ImportedAiFile(
                    name = "手动补充素材.md",
                    content = manualText,
                    id = MANUAL_SOURCE_ID,
                ),
            )
        }
    }

    private companion object {
        const val MANUAL_SOURCE_ID = "manual-source"
        const val MAX_CANDIDATE_KEY_CHARACTERS = 300
        const val MAX_CANDIDATE_MARKDOWN_CHARACTERS = 12_000
    }
}

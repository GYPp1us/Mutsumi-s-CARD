package com.mutsumi.card.draw

import androidx.lifecycle.ViewModel
import com.mutsumi.card.draw.model.CanvasPoint
import com.mutsumi.card.draw.model.DrawingDocument
import com.mutsumi.card.draw.model.DrawingHistory
import com.mutsumi.card.draw.model.DrawingStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DrawingStep { Key, Canvas }

enum class DrawingTool { Pen, Eraser, Move }

sealed interface DrawingSaveStatus {
    data object Idle : DrawingSaveStatus
    data object Saving : DrawingSaveStatus
    data class Saved(val cardId: Long) : DrawingSaveStatus
    data class Failed(val message: String) : DrawingSaveStatus
}

data class DrawingUiState(
    val keyText: String = "",
    val step: DrawingStep = DrawingStep.Key,
    val history: DrawingHistory = DrawingHistory(),
    val tool: DrawingTool = DrawingTool.Pen,
    val penColorArgb: Int = 0xFF16352E.toInt(),
    val penWidth: Float = 12f,
    val camera: CanvasCamera? = null,
    val saveStatus: DrawingSaveStatus = DrawingSaveStatus.Idle,
) {
    val document: DrawingDocument get() = history.document
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
}

class DrawingViewModel(
    private val renderer: DrawingSceneRenderer = DrawingSceneRenderer(),
    private val saveCard: suspend (keyText: String, png: ByteArray) -> Long,
) : ViewModel() {
    private val saveMutex = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(DrawingUiState())
    val state: StateFlow<DrawingUiState> = mutableState.asStateFlow()

    fun updateKey(value: String) {
        editState { it.copy(keyText = value, saveStatus = DrawingSaveStatus.Idle) }
    }

    fun enterCanvas() {
        editState {
            require(it.keyText.isNotBlank()) { "请输入文字 key" }
            it.copy(step = DrawingStep.Canvas, saveStatus = DrawingSaveStatus.Idle)
        }
    }

    fun returnToKey() {
        editState { it.copy(step = DrawingStep.Key) }
    }

    fun selectTool(tool: DrawingTool) {
        editState { it.copy(tool = tool) }
    }

    fun updatePen(colorArgb: Int = mutableState.value.penColorArgb, width: Float = mutableState.value.penWidth) {
        require(width.isFinite() && width > 0f) { "笔刷宽度必须大于 0" }
        editState { it.copy(penColorArgb = colorArgb, penWidth = width) }
    }

    fun updateCamera(camera: CanvasCamera) {
        editState { it.copy(camera = camera.clamp()) }
    }

    fun setBaseImage(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "底图不能为空" }
        mutateDocument { it.copy(baseImage = bytes.copyOf()) }
    }

    fun appendStroke(stroke: DrawingStroke) {
        mutateDocument { it.appendStroke(stroke) }
    }

    fun eraseAt(point: CanvasPoint, radius: Float) {
        mutateDocument { it.eraseStrokesAt(point, radius) }
    }

    fun undo() {
        editState { it.copy(history = it.history.undo(), saveStatus = DrawingSaveStatus.Idle) }
    }

    fun redo() {
        editState { it.copy(history = it.history.redo(), saveStatus = DrawingSaveStatus.Idle) }
    }

    fun clearDocument() {
        editState {
            it.copy(
                history = DrawingHistory(),
                camera = null,
                saveStatus = DrawingSaveStatus.Idle,
            )
        }
    }

    suspend fun save(): Long = saveMutex.withLock {
        val (snapshot, normalizedKey) = synchronized(stateLock) {
            val current = mutableState.value
            val normalizedKey = current.keyText.trim()
            require(normalizedKey.isNotEmpty()) { "请输入文字 key" }
            require(current.document.hasContent) { "请先提供图片 value" }
            mutableState.value = current.copy(saveStatus = DrawingSaveStatus.Saving)
            current to normalizedKey
        }
        try {
            val png = renderer.exportPng(snapshot.document)
            val cardId = saveCard(normalizedKey, png)
            synchronized(stateLock) {
                mutableState.value = DrawingUiState(
                    tool = snapshot.tool,
                    penColorArgb = snapshot.penColorArgb,
                    penWidth = snapshot.penWidth,
                    saveStatus = DrawingSaveStatus.Saved(cardId),
                )
            }
            cardId
        } catch (cancelled: CancellationException) {
            synchronized(stateLock) {
                mutableState.value = snapshot.copy(saveStatus = DrawingSaveStatus.Idle)
            }
            throw cancelled
        } catch (failure: Throwable) {
            synchronized(stateLock) {
                mutableState.value = snapshot.copy(
                    saveStatus = DrawingSaveStatus.Failed(failure.message ?: "保存失败"),
                )
            }
            throw failure
        }
    }

    private fun mutateDocument(transform: (DrawingDocument) -> DrawingDocument) {
        editState { current ->
            current.copy(
                history = current.history.commit(transform(current.document)),
                saveStatus = DrawingSaveStatus.Idle,
            )
        }
    }

    private inline fun editState(transform: (DrawingUiState) -> DrawingUiState) {
        synchronized(stateLock) {
            val current = mutableState.value
            check(current.saveStatus !is DrawingSaveStatus.Saving) { "卡片正在保存，暂时不能编辑" }
            mutableState.value = transform(current)
        }
    }
}

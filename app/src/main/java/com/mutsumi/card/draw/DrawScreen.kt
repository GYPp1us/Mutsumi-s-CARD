package com.mutsumi.card.draw

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.roundToInt

data class DrawnCardImage(
    val frontPngBytes: ByteArray?,
    val backPngBytes: ByteArray,
)

sealed interface DrawSaveResult {
    data class Saved(val message: String) : DrawSaveResult
    data class Rejected(val message: String) : DrawSaveResult
}

private enum class CardFace { Front, Back }
private enum class DrawTool { Pen, Eraser, Move, Markdown }
internal val DrawCameraCenterXKey = SemanticsPropertyKey<Float>("绘图相机中心 X")

private data class FacePoint(val position: Offset)
private data class FaceStroke(val points: List<FacePoint>, val color: Color, val width: Float)

private class FaceDraft {
    val strokes = mutableStateListOf<FaceStroke>()
    val currentPoints = mutableStateListOf<FacePoint>()
    val baseImageBytes = mutableStateOf<ByteArray?>(null)
    val baseImageSize = mutableStateOf(IntSize.Zero)
    val baseImageRect = mutableStateOf<CanvasRect?>(null)
    val camera = mutableStateOf<CanvasCamera?>(null)
    val viewport = mutableStateOf(IntSize.Zero)
    val markdownSource = mutableStateOf("")
    val markdownEditing = mutableStateOf(false)

    fun hasContent(): Boolean = strokes.isNotEmpty() || baseImageBytes.value != null || markdownSource.value.isNotBlank()

    fun clear() {
        strokes.clear()
        currentPoints.clear()
        baseImageBytes.value = null
        baseImageSize.value = IntSize.Zero
        baseImageRect.value = null
        markdownSource.value = ""
        markdownEditing.value = false
        viewport.value.takeIf { it.width > 0 && it.height > 0 }?.let { size ->
            camera.value = CanvasCamera.initial(size.width.toFloat(), size.height.toFloat())
        }
    }
}

private class DualFaceDrawingViewModel : ViewModel() {
    val keyText = mutableStateOf("")
    val isKeyLocked = mutableStateOf(false)
    val isSaving = mutableStateOf(false)
    val activeFace = mutableStateOf(CardFace.Front)
    val front = FaceDraft()
    val back = FaceDraft()
    val tool = mutableStateOf(DrawTool.Pen)
    val penColor = mutableStateOf(Color(0xFF16352E))
    val penWidth = mutableFloatStateOf(6f)
    val status = mutableStateOf("正面可选；背面必须有图片内容。")

    fun face(side: CardFace): FaceDraft = if (side == CardFace.Front) front else back
}

@Composable
fun DrawScreen(onSaveCard: suspend (String, DrawnCardImage) -> DrawSaveResult) {
    val session: DualFaceDrawingViewModel = viewModel { DualFaceDrawingViewModel() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val markdownRenderer = remember(context) { MarkdownLayerRenderer(context) }
    val activity = remember(context) { context.findActivity() }
    val currentOnSaveCard by rememberUpdatedState(onSaveCard)
    var pickerTarget by remember { mutableStateOf<CardFace?>(null) }

    fun setMarkdownEditingFace(face: CardFace?) {
        if (session.isSaving.value) return
        session.front.markdownEditing.value = face == CardFace.Front
        session.back.markdownEditing.value = face == CardFace.Back
    }

    fun selectFace(face: CardFace) {
        if (session.isSaving.value) return
        if (session.activeFace.value != face) {
            focusManager.clearFocus(force = true)
            session.activeFace.value = face
        }
        if (session.tool.value == DrawTool.Markdown) {
            setMarkdownEditingFace(face)
        }
    }

    fun selectTool(tool: DrawTool) {
        if (session.isSaving.value) return
        session.tool.value = tool
        setMarkdownEditingFace(if (tool == DrawTool.Markdown) session.activeFace.value else null)
    }

    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            // A rotation disposes this composition before the replacement Activity is ready.
            // Restoring here during that handoff requests portrait again and creates a rotation loop.
            if (activity != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (session.isSaving.value) return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val face = requireNotNull(pickerTarget) { "未指定底图目标卡面" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取底图：$uri")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("底图不是可解码的图片：$uri")
        val draft = session.face(face)
        draft.baseImageSize.value = IntSize(bitmap.width, bitmap.height)
        draft.baseImageRect.value = fitImageInCardWorld(bitmap.width, bitmap.height)
        bitmap.recycle()
        draft.baseImageBytes.value = bytes
        session.activeFace.value = face
        session.status.value = "${face.label}底图已插入。"
    }

    fun save() {
        if (session.isSaving.value) return
        val key = session.keyText.value.trim()
        if (key.isEmpty()) {
            session.status.value = "请输入文字 key。"
            return
        }
        if (!session.back.hasContent()) {
            session.status.value = "背面需要笔迹、底图或 Markdown 内容。"
            return
        }
        val back = renderFacePng(session.back, markdownRenderer)
        val frontFallsBackToKey = !session.front.hasContent()
        val front = if (frontFallsBackToKey) null else renderFacePng(session.front, markdownRenderer)
        focusManager.clearFocus(force = true)
        session.isSaving.value = true
        scope.launch {
            try {
                persistDrawnCard(
                    onSave = { currentOnSaveCard(key, DrawnCardImage(front, back)) },
                    onPersisted = { message ->
                        session.keyText.value = ""
                        session.isKeyLocked.value = false
                        session.front.clear()
                        session.back.clear()
                        session.activeFace.value = CardFace.Front
                        session.status.value = "$message；${frontSaveFeedback(frontFallsBackToKey)}。"
                    },
                    onRejected = { message ->
                        session.status.value = "卡片保存失败：$message"
                    },
                )
            } finally {
                session.isSaving.value = false
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (!session.isSaving.value && event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.W) {
                val draft = session.face(session.activeFace.value)
                session.tool.value = DrawTool.Markdown
                setMarkdownEditingFace(if (draft.markdownEditing.value) null else session.activeFace.value)
                true
            } else {
                false
            }
        },
    ) {
        if (maxWidth <= maxHeight) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("录入仅支持横屏", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            val compactControls = maxWidth < 960.dp || maxHeight < 480.dp
            val contextWidth = if (compactControls) 184.dp else 224.dp
            val toolRailWidth = if (compactControls) 96.dp else 56.dp
            Row(
                modifier = Modifier.fillMaxSize().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
            EditorToolRail(
                activeFace = session.activeFace.value,
                tool = session.tool.value,
                compact = compactControls,
                onToolChange = ::selectTool,
                onFaceChange = ::selectFace,
                onInsertBase = {
                    if (!session.isSaving.value) {
                        pickerTarget = session.activeFace.value
                        imagePicker.launch("image/*")
                    }
                },
                onUndo = {
                    if (!session.isSaving.value) {
                        val draft = session.face(session.activeFace.value)
                        if (draft.strokes.isNotEmpty()) draft.strokes.removeAt(draft.strokes.lastIndex)
                    }
                },
                onClear = {
                    if (!session.isSaving.value) session.face(session.activeFace.value).clear()
                },
                modifier = Modifier.width(toolRailWidth).fillMaxHeight(),
            )
            FacePanel(
                face = CardFace.Front,
                draft = session.front,
                active = session.activeFace.value == CardFace.Front,
                tool = session.tool.value,
                penColor = session.penColor.value,
                penWidth = session.penWidth.floatValue,
                markdownRenderer = markdownRenderer,
                onSelect = { selectFace(CardFace.Front) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            FacePanel(
                face = CardFace.Back,
                draft = session.back,
                active = session.activeFace.value == CardFace.Back,
                tool = session.tool.value,
                penColor = session.penColor.value,
                penWidth = session.penWidth.floatValue,
                markdownRenderer = markdownRenderer,
                onSelect = { selectFace(CardFace.Back) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
                EditorContextPanel(
                keyText = session.keyText.value,
                keyLocked = session.isKeyLocked.value,
                penColor = session.penColor.value,
                penWidth = session.penWidth.floatValue,
                status = session.status.value,
                isSaving = session.isSaving.value,
                markdownEditing = session.face(session.activeFace.value).markdownEditing.value,
                onKeyChange = {
                    if (!session.isSaving.value) session.keyText.value = it
                },
                onKeyLockChange = { locked ->
                    if (!session.isSaving.value) {
                        session.isKeyLocked.value = locked
                        session.status.value = if (locked) "文字 key 已锁定，绘图时不会被误改。" else "文字 key 已解锁。"
                    }
                },
                onColorChange = {
                    if (!session.isSaving.value) session.penColor.value = it
                },
                onWidthChange = {
                    if (!session.isSaving.value) session.penWidth.floatValue = it
                },
                onToggleMarkdown = {
                    if (!session.isSaving.value) {
                        val draft = session.face(session.activeFace.value)
                        session.tool.value = DrawTool.Markdown
                        setMarkdownEditingFace(if (draft.markdownEditing.value) null else session.activeFace.value)
                    }
                },
                onSave = ::save,
                    modifier = Modifier.width(contextWidth).fillMaxHeight(),
                )
            }
        }
        if (session.isSaving.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("正在保存卡片…", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

internal suspend fun persistDrawnCard(
    onSave: suspend () -> DrawSaveResult,
    onPersisted: (String) -> Unit,
    onRejected: (String) -> Unit,
) {
    when (val result = onSave()) {
        is DrawSaveResult.Saved -> onPersisted(result.message)
        is DrawSaveResult.Rejected -> onRejected(result.message)
    }
}

@Composable
private fun EditorToolRail(
    activeFace: CardFace,
    tool: DrawTool,
    compact: Boolean,
    onToolChange: (DrawTool) -> Unit,
    onFaceChange: (CardFace) -> Unit,
    onInsertBase: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val faceActions = listOf(
        EditorToolAction(Icons.Default.Description, "选择正面画布", "draw-face-selector-front", activeFace == CardFace.Front) {
            onFaceChange(CardFace.Front)
        },
        EditorToolAction(Icons.Default.Image, "选择背面画布", "draw-face-selector-back", activeFace == CardFace.Back) {
            onFaceChange(CardFace.Back)
        },
    )
    val toolActions = listOf(
        EditorToolAction(Icons.Default.Edit, "画笔", "draw-tool-pen", tool == DrawTool.Pen) { onToolChange(DrawTool.Pen) },
        EditorToolAction(Icons.Default.Backspace, "橡皮擦", "draw-tool-eraser", tool == DrawTool.Eraser) { onToolChange(DrawTool.Eraser) },
        EditorToolAction(Icons.Default.OpenWith, "移动和缩放画布", "draw-tool-move", tool == DrawTool.Move) { onToolChange(DrawTool.Move) },
        EditorToolAction(Icons.Default.Code, "编辑 Markdown", "draw-tool-markdown", tool == DrawTool.Markdown) { onToolChange(DrawTool.Markdown) },
    )
    val actionButtons = listOf(
        EditorToolAction(Icons.Default.Image, "插入底图", "draw-insert-base", onClick = onInsertBase),
        EditorToolAction(Icons.Default.Undo, "撤销笔迹", "draw-undo", onClick = onUndo),
        EditorToolAction(Icons.Default.Clear, "清空当前卡面", "draw-clear", onClick = onClear),
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (compact) {
            listOf(faceActions, toolActions.take(2), toolActions.drop(2), actionButtons.take(2), actionButtons.drop(2)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { action -> EditorToolActionButton(action, 40.dp) }
                    if (row.size == 1) Spacer(Modifier.size(40.dp))
                }
            }
        } else {
            faceActions.forEach { action -> EditorToolActionButton(action, 48.dp) }
            Box(Modifier.width(30.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            toolActions.forEach { action -> EditorToolActionButton(action, 48.dp) }
            Box(Modifier.width(30.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            actionButtons.forEach { action -> EditorToolActionButton(action, 48.dp) }
        }
    }
}

private data class EditorToolAction(
    val icon: ImageVector,
    val contentDescription: String,
    val testTag: String,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun EditorToolActionButton(action: EditorToolAction, buttonSize: Dp) {
    ToolIconButton(
        icon = action.icon,
        contentDescription = action.contentDescription,
        selected = action.selected,
        onClick = action.onClick,
        modifier = Modifier.testTag(action.testTag),
        buttonSize = buttonSize,
    )
}

@Composable
private fun ToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    buttonSize: Dp = 48.dp,
) {
    val shape = RoundedCornerShape(8.dp)
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(buttonSize)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EditorContextPanel(
    keyText: String,
    keyLocked: Boolean,
    penColor: Color,
    penWidth: Float,
    status: String,
    isSaving: Boolean,
    markdownEditing: Boolean,
    onKeyChange: (String) -> Unit,
    onKeyLockChange: (Boolean) -> Unit,
    onColorChange: (Color) -> Unit,
    onWidthChange: (Float) -> Unit,
    onToggleMarkdown: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier,
) {
    var showCustomColorDialog by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KeyTextField(
            value = keyText,
            locked = keyLocked,
            onValueChange = onKeyChange,
            onLockChange = onKeyLockChange,
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("画笔颜色", style = MaterialTheme.typography.labelMedium)
            PenColorChoices(
                penColor = penColor,
                onColorChange = onColorChange,
                onOpenCustomColor = { showCustomColorDialog = true },
            )
            Text("笔刷 ${penWidth.roundToInt()} px", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = penWidth,
                onValueChange = onWidthChange,
                valueRange = 2f..24f,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onToggleMarkdown,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("draw-toggle-markdown"),
            ) {
                Icon(
                    imageVector = if (markdownEditing) Icons.Default.Visibility else Icons.Default.Code,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(if (markdownEditing) "预览 Markdown" else "编辑 Markdown", maxLines = 1)
            }
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag("draw-status"),
        )
        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save-card"),
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (isSaving) "正在保存" else "保存", maxLines = 1)
        }
    }
    if (showCustomColorDialog) {
        CustomPenColorDialog(
            initialColor = penColor,
            onDismiss = { showCustomColorDialog = false },
            onConfirm = {
                onColorChange(it)
                showCustomColorDialog = false
            },
        )
    }
}

@Composable
private fun KeyTextField(
    value: String,
    locked: Boolean,
    onValueChange: (String) -> Unit,
    onLockChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val lockDescription = if (locked) "解锁文字 key" else "锁定文字 key"
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (locked) "文字 key · 已锁定" else "文字 key") },
        readOnly = locked,
        minLines = 1,
        maxLines = 3,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        trailingIcon = {
            IconButton(onClick = { onLockChange(!locked) }, modifier = Modifier.testTag("draw-key-lock")) {
                Icon(
                    imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = lockDescription,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp, max = 132.dp)
            .testTag("draw-key-input"),
    )
}

@Composable
private fun PenColorChoices(
    penColor: Color,
    onColorChange: (Color) -> Unit,
    onOpenCustomColor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = listOf(
        "墨绿" to Color(0xFF16352E),
        "珊瑚红" to Color(0xFFC65F4C),
        "蓝灰" to Color(0xFF496F83),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier.fillMaxWidth()) {
        presets.forEach { (label, color) ->
            ColorSwatch(
                color = color,
                label = "使用$label",
                selected = penColor == color,
                onClick = { onColorChange(color) },
            )
        }
        ToolIconButton(
            icon = Icons.Default.Palette,
            contentDescription = "自定义画笔颜色",
            selected = presets.none { it.second == penColor },
            onClick = onOpenCustomColor,
            modifier = Modifier.testTag("draw-custom-color"),
            buttonSize = 40.dp,
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Black, shape),
    ) {
        Box(modifier = Modifier.fillMaxSize().semantics { contentDescription = label })
    }
}

@Composable
private fun CustomPenColorDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    var red by remember(initialColor) { mutableFloatStateOf(initialColor.red) }
    var green by remember(initialColor) { mutableFloatStateOf(initialColor.green) }
    var blue by remember(initialColor) { mutableFloatStateOf(initialColor.blue) }
    val selectedColor = Color(red = red, green = green, blue = blue)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义画笔颜色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp)).background(selectedColor),
                )
                ColorChannel("红", red) { red = it }
                ColorChannel("绿", green) { green = it }
                ColorChannel("蓝", blue) { blue = it }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Clear, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("取消")
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedColor) }) {
                Icon(Icons.Default.Palette, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("应用")
            }
        },
    )
}

@Composable
private fun ColorChannel(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(18.dp))
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..1f, modifier = Modifier.weight(1f))
        Text((value * 255).roundToInt().toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(24.dp))
    }
}

internal fun frontSaveFeedback(frontFallsBackToKey: Boolean): String = if (frontFallsBackToKey) {
    "正面将以文字 key 显示"
} else {
    "正面将保存为图片"
}

@Composable
private fun FacePanel(
    face: CardFace,
    draft: FaceDraft,
    active: Boolean,
    tool: DrawTool,
    penColor: Color,
    penWidth: Float,
    markdownRenderer: MarkdownLayerRenderer,
    onSelect: () -> Unit,
    modifier: Modifier,
) {
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val frontFallsBackToKey = face == CardFace.Front && !draft.hasContent()
    Column(
        modifier = modifier.testTag("draw-face-${face.name.lowercase()}").semantics {
            contentDescription = "${face.label}画布，${faceSaveWatermark(face, frontFallsBackToKey).joinToString("，")}"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val widthFromHeight = maxHeight * DrawingCanvasSpec.aspectRatio
            val canvasModifier = if (widthFromHeight <= maxWidth) {
                Modifier.height(maxHeight).width(widthFromHeight)
            } else {
                Modifier.width(maxWidth).aspectRatio(DrawingCanvasSpec.aspectRatio)
            }
            Box(
                modifier = canvasModifier.border(2.dp, borderColor, RoundedCornerShape(7.dp)).clip(RoundedCornerShape(7.dp)),
            ) {
                if (draft.markdownEditing.value) {
                    OutlinedTextField(
                        value = draft.markdownSource.value,
                        onValueChange = { draft.markdownSource.value = it },
                        label = { Text("Markdown") },
                        modifier = Modifier.fillMaxSize().heightIn(min = 56.dp).testTag("draw-markdown-${face.name.lowercase()}"),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        placeholder = { Text("# 标题\n\n${'$'}E = mc^2${'$'}\n\n| 列 | 值 |\n|---|---|\n| A | 1 |") },
                    )
                } else {
                    FaceCanvas(
                        face = face,
                        frontFallsBackToKey = frontFallsBackToKey,
                        draft = draft,
                        tool = tool,
                        penColor = penColor,
                        penWidth = penWidth,
                        markdownRenderer = markdownRenderer,
                        onActivate = onSelect,
                        modifier = Modifier.fillMaxSize().testTag("drawing-canvas-${face.name.lowercase()}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun FaceCanvas(
    face: CardFace,
    frontFallsBackToKey: Boolean,
    draft: FaceDraft,
    tool: DrawTool,
    penColor: Color,
    penWidth: Float,
    markdownRenderer: MarkdownLayerRenderer,
    onActivate: () -> Unit,
    modifier: Modifier,
) {
    val baseBytes = draft.baseImageBytes.value
    val baseBitmap = remember(baseBytes) { baseBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) ?: error("底图无法解码") } }
    DisposableEffect(baseBitmap) { onDispose { baseBitmap?.takeUnless(Bitmap::isRecycled)?.recycle() } }
    val size = draft.viewport.value
    val markdownBitmap = remember(draft.markdownSource.value, size) {
        if (size.width > 0 && size.height > 0) markdownRenderer.render(draft.markdownSource.value, size.width, size.height) else null
    }
    DisposableEffect(markdownBitmap) { onDispose { markdownBitmap?.takeUnless(Bitmap::isRecycled)?.recycle() } }
    LaunchedEffect(size) {
        if (size.width > 0 && size.height > 0) {
            draft.camera.value = draft.camera.value?.withViewport(size.width.toFloat(), size.height.toFloat())
                ?: CanvasCamera.initial(size.width.toFloat(), size.height.toFloat())
            if (draft.baseImageRect.value == null && draft.baseImageSize.value != IntSize.Zero) {
                draft.baseImageRect.value = fitImageInCardWorld(
                    draft.baseImageSize.value.width,
                    draft.baseImageSize.value.height,
                )
            }
        }
    }
    val activeCamera = draft.camera.value
    val latestCamera = rememberUpdatedState(draft.camera.value)
    val pointerModifier = when (tool) {
        DrawTool.Pen -> immediateStrokeInput(draft, penColor, penWidth, latestCamera, onActivate)
        DrawTool.Eraser -> immediateEraserInput(draft, latestCamera, onActivate)
        DrawTool.Move -> moveInput(draft, latestCamera, onActivate)
        DrawTool.Markdown -> activateOnlyInput(onActivate)
    }
    Canvas(
        modifier = modifier
            .background(Color.White)
            .onSizeChanged { draft.viewport.value = it }
            .semantics { activeCamera?.let { this[DrawCameraCenterXKey] = it.centerX } }
            .then(pointerModifier),
    ) {
        clipRect {
            drawFaceWatermark(face, frontFallsBackToKey)
            if (activeCamera == null) return@clipRect
            drawBasePreview(baseBitmap, draft.baseImageRect.value, activeCamera)
            markdownBitmap?.let { drawImage(it.asImageBitmap()) }
            draft.strokes.forEach { drawStrokePreview(it, activeCamera) }
            drawStrokePreview(FaceStroke(draft.currentPoints.toList(), penColor, penWidth / activeCamera.scale), activeCamera)
        }
    }
}

private fun immediateStrokeInput(
    draft: FaceDraft,
    penColor: Color,
    penWidth: Float,
    latestCamera: State<CanvasCamera?>,
    onActivate: () -> Unit,
): Modifier = Modifier.pointerInput(draft, penColor, penWidth) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onActivate()
        val camera = latestCamera.value ?: return@awaitEachGesture
        draft.currentPoints.clear()
        fun appendPoint(position: Offset) {
            val world = camera.screenToWorld(position)
            if (draft.currentPoints.lastOrNull()?.position != world) draft.currentPoints += FacePoint(world)
        }
        appendPoint(down.position)
        val width = penWidth / camera.scale
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.historical.forEach { sample -> appendPoint(sample.position) }
            appendPoint(change.position)
            if (change.changedToUp()) {
                draft.strokes += FaceStroke(draft.currentPoints.toList(), penColor, width)
                draft.currentPoints.clear()
                change.consume()
                break
            }
            if (change.pressed) change.consume()
        }
    }
}

private fun immediateEraserInput(
    draft: FaceDraft,
    latestCamera: State<CanvasCamera?>,
    onActivate: () -> Unit,
): Modifier = Modifier.pointerInput(draft) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onActivate()
        val camera = latestCamera.value ?: return@awaitEachGesture
        fun erase(position: Offset) {
            val point = camera.screenToWorld(position)
            draft.strokes.removeAll { stroke -> stroke.points.any { hypot(it.position.x - point.x, it.position.y - point.y) <= stroke.width * 1.5f } }
        }
        erase(down.position)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            erase(change.position)
            if (change.changedToUp()) { change.consume(); break }
            if (change.pressed) change.consume()
        }
    }
}

private fun moveInput(
    draft: FaceDraft,
    latestCamera: State<CanvasCamera?>,
    onActivate: () -> Unit,
): Modifier = Modifier.pointerInput(draft) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onActivate()
        var camera = latestCamera.value ?: return@awaitEachGesture
        do {
            val event = awaitPointerEvent()
            val centroid = event.calculateCentroid(useCurrent = false)
            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            if (centroid.x.isFinite() && centroid.y.isFinite() && (pan != Offset.Zero || zoom != 1f)) {
                camera = camera.transform(centroid.x, centroid.y, pan.x, pan.y, zoom)
                draft.camera.value = camera
            }
            event.changes.forEach { if (it.pressed) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

private fun activateOnlyInput(onActivate: () -> Unit): Modifier = Modifier.pointerInput(onActivate) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onActivate()
    }
}

private fun faceSaveWatermark(face: CardFace, frontFallsBackToKey: Boolean): List<String> = when (face) {
    CardFace.Front -> if (frontFallsBackToKey) {
        listOf("正面", "将以文字 key 保存")
    } else {
        listOf("正面", "将保存为图片")
    }
    CardFace.Back -> listOf("背面", "图片 value")
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFaceWatermark(
    face: CardFace,
    frontFallsBackToKey: Boolean,
) {
    val lines = faceSaveWatermark(face, frontFallsBackToKey)
    val mainSize = size.minDimension * 0.2f
    val detailSize = mainSize * 0.48f
    val lineGap = mainSize * 0.16f
    val totalHeight = mainSize + lineGap + detailSize
    val startTop = (size.height - totalHeight) / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color(0xFFCDD3CF).toArgb()
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    drawIntoCanvas { canvas ->
        paint.textSize = mainSize
        canvas.nativeCanvas.drawText(lines.first(), size.width / 2f, startTop - paint.ascent(), paint)
        paint.textSize = detailSize
        canvas.nativeCanvas.drawText(
            lines.last(),
            size.width / 2f,
            startTop + mainSize + lineGap - paint.ascent(),
            paint,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBasePreview(bitmap: Bitmap?, rect: CanvasRect?, camera: CanvasCamera) {
    if (bitmap == null || rect == null) return
    val target = camera.worldToScreen(rect)
    if (target.width < 1f || target.height < 1f) return
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmap(
            bitmap,
            null,
            RectF(target.left, target.top, target.right, target.bottom),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePreview(stroke: FaceStroke, camera: CanvasCamera) {
    if (stroke.points.isEmpty()) return
    if (stroke.points.size == 1) {
        val point = camera.worldToScreen(stroke.points.single().position)
        drawCircle(stroke.color, stroke.width * camera.scale / 2f, point)
        return
    }
    val path = Path().apply {
        camera.worldToScreen(stroke.points.first().position).let { moveTo(it.x, it.y) }
        stroke.points.drop(1).forEach { camera.worldToScreen(it.position).let { point -> lineTo(point.x, point.y) } }
    }
    drawPath(path, stroke.color, style = Stroke(stroke.width * camera.scale, cap = StrokeCap.Round))
}

private fun renderFacePng(draft: FaceDraft, markdownRenderer: MarkdownLayerRenderer): ByteArray {
    val camera = requireNotNull(draft.camera.value) { "画布相机尚未初始化" }
    val base = draft.baseImageBytes.value?.let { BitmapFactory.decodeByteArray(it, 0, it.size) ?: error("底图无法解码") }
    try {
        val bitmap = Bitmap.createBitmap(DrawingCanvasSpec.width, DrawingCanvasSpec.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val exportScale = DrawingCanvasSpec.width / camera.visibleWidth
        val exportCamera = CanvasCamera(
            scale = exportScale,
            offsetX = camera.offsetX,
            offsetY = camera.offsetY,
            viewportWidth = DrawingCanvasSpec.width.toFloat(),
            viewportHeight = DrawingCanvasSpec.height.toFloat(),
        )
        drawBaseExport(canvas, base, draft.baseImageRect.value, exportCamera)
        markdownRenderer.render(draft.markdownSource.value, DrawingCanvasSpec.width, DrawingCanvasSpec.height)?.let { markdown ->
            canvas.drawBitmap(markdown, 0f, 0f, null)
            markdown.recycle()
        }
        draft.strokes.forEach { drawStrokeExport(canvas, it, exportCamera) }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    } finally {
        base?.recycle()
    }
}

private fun drawBaseExport(canvas: AndroidCanvas, bitmap: Bitmap?, rect: CanvasRect?, camera: CanvasCamera) {
    if (bitmap == null || rect == null) return
    val target = camera.worldToScreen(rect)
    canvas.drawBitmap(bitmap, null, RectF(target.left, target.top, target.right, target.bottom), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
}

private fun drawStrokeExport(canvas: AndroidCanvas, stroke: FaceStroke, camera: CanvasCamera) {
    if (stroke.points.isEmpty()) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke.color.toArgb(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = stroke.width * camera.scale
    }
    if (stroke.points.size == 1) {
        val point = camera.worldToScreen(stroke.points.single().position)
        canvas.drawCircle(point.x, point.y, paint.strokeWidth / 2f, paint.apply { style = Paint.Style.FILL })
        return
    }
    val path = AndroidPath().apply {
        camera.worldToScreen(stroke.points.first().position).let { moveTo(it.x, it.y) }
        stroke.points.drop(1).forEach { camera.worldToScreen(it.position).let { point -> lineTo(point.x, point.y) } }
    }
    canvas.drawPath(path, paint)
}

private fun CanvasCamera.screenToWorld(point: Offset): Offset = Offset(centerX + (point.x - viewportWidth / 2f) / scale, centerY + (point.y - viewportHeight / 2f) / scale)
private fun CanvasCamera.worldToScreen(point: Offset): Offset = Offset(viewportWidth / 2f + (point.x - centerX) * scale, viewportHeight / 2f + (point.y - centerY) * scale)
private fun CanvasCamera.worldToScreen(rect: CanvasRect): CanvasRect {
    val origin = worldToScreen(Offset(rect.left, rect.top))
    return CanvasRect(origin.x, origin.y, rect.width * scale, rect.height * scale)
}

private val CardFace.label: String get() = if (this == CardFace.Front) "正面" else "背面"

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

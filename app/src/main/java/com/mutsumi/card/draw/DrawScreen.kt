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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream
import kotlin.math.hypot

data class DrawnCardImage(
    val frontPngBytes: ByteArray?,
    val backPngBytes: ByteArray,
)

private enum class CardFace { Front, Back }
private enum class DrawTool { Pen, Eraser, Move, Markdown }

private data class FacePoint(val position: Offset)
private data class FaceStroke(val points: List<FacePoint>, val color: Color, val width: Float)

private class FaceDraft {
    val strokes = mutableStateListOf<FaceStroke>()
    val currentPoints = mutableStateListOf<FacePoint>()
    val baseImageBytes = mutableStateOf<ByteArray?>(null)
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
fun DrawScreen(onSaveCard: (String, DrawnCardImage) -> String) {
    val session: DualFaceDrawingViewModel = viewModel { DualFaceDrawingViewModel() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val markdownRenderer = remember(context) { MarkdownLayerRenderer(context) }
    val activity = remember(context) { context.findActivity() }
    var pickerTarget by remember { mutableStateOf<CardFace?>(null) }

    fun setMarkdownEditingFace(face: CardFace?) {
        session.front.markdownEditing.value = face == CardFace.Front
        session.back.markdownEditing.value = face == CardFace.Back
    }

    fun selectFace(face: CardFace) {
        focusManager.clearFocus(force = true)
        session.activeFace.value = face
        if (session.tool.value == DrawTool.Markdown) {
            setMarkdownEditingFace(face)
        }
    }

    fun selectTool(tool: DrawTool) {
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
        if (uri == null) return@rememberLauncherForActivityResult
        val face = requireNotNull(pickerTarget) { "未指定底图目标卡面" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取底图：$uri")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("底图不是可解码的图片：$uri")
        val draft = session.face(face)
        draft.baseImageRect.value = fitImageInVisibleWorld(bitmap.width, bitmap.height, draft.camera.value)
        bitmap.recycle()
        draft.baseImageBytes.value = bytes
        session.activeFace.value = face
        session.status.value = "${face.label}底图已插入。"
    }

    fun save() {
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
        val front = if (session.front.hasContent()) renderFacePng(session.front, markdownRenderer) else null
        val message = onSaveCard(key, DrawnCardImage(front, back))
        session.keyText.value = ""
        session.front.clear()
        session.back.clear()
        session.activeFace.value = CardFace.Front
        session.status.value = "$message。"
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.W) {
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
            return@BoxWithConstraints
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SharedTools(
                keyText = session.keyText.value,
                activeFace = session.activeFace.value,
                tool = session.tool.value,
                penColor = session.penColor.value,
                penWidth = session.penWidth.floatValue,
                status = session.status.value,
                markdownEditing = session.face(session.activeFace.value).markdownEditing.value,
                onKeyChange = { session.keyText.value = it },
                onToolChange = ::selectTool,
                onFaceChange = ::selectFace,
                onColorChange = { session.penColor.value = it },
                onWidthChange = { session.penWidth.floatValue = it },
                onInsertBase = {
                    pickerTarget = session.activeFace.value
                    imagePicker.launch("image/*")
                },
                onUndo = {
                    val draft = session.face(session.activeFace.value)
                    if (draft.strokes.isNotEmpty()) draft.strokes.removeAt(draft.strokes.lastIndex)
                },
                onClear = { session.face(session.activeFace.value).clear() },
                onToggleMarkdown = {
                    val draft = session.face(session.activeFace.value)
                    session.tool.value = DrawTool.Markdown
                    setMarkdownEditingFace(if (draft.markdownEditing.value) null else session.activeFace.value)
                },
                onSave = ::save,
                modifier = Modifier.width(264.dp).fillMaxHeight(),
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
        }
    }
}

@Composable
private fun SharedTools(
    keyText: String,
    activeFace: CardFace,
    tool: DrawTool,
    penColor: Color,
    penWidth: Float,
    status: String,
    markdownEditing: Boolean,
    onKeyChange: (String) -> Unit,
    onToolChange: (DrawTool) -> Unit,
    onFaceChange: (CardFace) -> Unit,
    onColorChange: (Color) -> Unit,
    onWidthChange: (Float) -> Unit,
    onInsertBase: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onToggleMarkdown: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = keyText,
            onValueChange = onKeyChange,
            label = { Text("文字 key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("draw-key-input"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            FaceChip(
                label = "正面",
                selected = activeFace == CardFace.Front,
                onClick = { onFaceChange(CardFace.Front) },
                modifier = Modifier.weight(1f).testTag("draw-face-selector-front"),
            )
            FaceChip(
                label = "背面",
                selected = activeFace == CardFace.Back,
                onClick = { onFaceChange(CardFace.Back) },
                modifier = Modifier.weight(1f).testTag("draw-face-selector-back"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            ToolChip("笔", tool == DrawTool.Pen, Modifier.weight(1f)) { onToolChange(DrawTool.Pen) }
            ToolChip("橡", tool == DrawTool.Eraser, Modifier.weight(1f)) { onToolChange(DrawTool.Eraser) }
            ToolChip("移", tool == DrawTool.Move, Modifier.weight(1f)) { onToolChange(DrawTool.Move) }
            ToolChip("MD", tool == DrawTool.Markdown, Modifier.weight(1f)) { onToolChange(DrawTool.Markdown) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Color(0xFF16352E), Color(0xFFC65F4C), Color(0xFF496F83), Color(0xFFE2B94F)).forEach { color ->
                Box(
                    Modifier.size(25.dp).clip(RoundedCornerShape(13.dp)).background(color)
                        .border(if (color == penColor) 2.dp else 1.dp, Color.Black, RoundedCornerShape(13.dp))
                        .pointerInput(color) { awaitEachGesture { awaitFirstDown(); onColorChange(color) } },
                )
            }
        }
        Slider(value = penWidth, onValueChange = onWidthChange, valueRange = 2f..24f, modifier = Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onInsertBase, modifier = Modifier.weight(1f).height(36.dp).testTag("draw-insert-base")) { Text("底图") }
            OutlinedButton(onClick = onToggleMarkdown, modifier = Modifier.weight(1f).height(36.dp)) {
                Text(if (markdownEditing) "MD 预览" else "MD 编辑")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f).height(36.dp)) { Text("撤销") }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).height(36.dp)) { Text("清空") }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(40.dp).testTag("save-card")) { Text("保存卡片") }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = modifier.height(40.dp))
}

@Composable
private fun FaceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = modifier.height(36.dp))
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
    Column(modifier = modifier.testTag("draw-face-${face.name.lowercase()}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(face.label, style = MaterialTheme.typography.titleSmall)
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
                        modifier = Modifier.fillMaxSize().testTag("draw-markdown-${face.name.lowercase()}"),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        placeholder = { Text("# 标题\n\n${'$'}E = mc^2${'$'}\n\n| 列 | 值 |\n|---|---|\n| A | 1 |") },
                    )
                } else {
                    FaceCanvas(
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
        }
    }
    val activeCamera = draft.camera.value
    val latestCamera by rememberUpdatedState(draft.camera.value)
    val pointerModifier = when (tool) {
        DrawTool.Pen -> immediateStrokeInput(draft, penColor, penWidth, latestCamera, onActivate)
        DrawTool.Eraser -> immediateEraserInput(draft, latestCamera, onActivate)
        DrawTool.Move -> moveInput(draft, latestCamera, onActivate)
        DrawTool.Markdown -> activateOnlyInput(onActivate)
    }
    Canvas(
        modifier = modifier.background(Color.White).onSizeChanged { draft.viewport.value = it }.then(pointerModifier),
    ) {
        clipRect {
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
    latestCamera: CanvasCamera?,
    onActivate: () -> Unit,
): Modifier = Modifier.pointerInput(draft, penColor, penWidth, latestCamera) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onActivate()
        val camera = latestCamera ?: return@awaitEachGesture
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
    latestCamera: CanvasCamera?,
    onActivate: () -> Unit,
): Modifier = Modifier.pointerInput(draft, latestCamera) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onActivate()
        val camera = latestCamera ?: return@awaitEachGesture
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
    latestCamera: CanvasCamera?,
    onActivate: () -> Unit,
): Modifier = Modifier.pointerInput(draft, latestCamera) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onActivate()
        var camera = latestCamera ?: return@awaitEachGesture
        do {
            val event = awaitPointerEvent()
            val centroid = event.calculateCentroid(useCurrent = true)
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBasePreview(bitmap: Bitmap?, rect: CanvasRect?, camera: CanvasCamera) {
    if (bitmap == null || rect == null) return
    val target = camera.worldToScreen(rect)
    if (target.width < 1f || target.height < 1f) return
    drawImage(bitmap.asImageBitmap(), IntOffset(target.left.toInt(), target.top.toInt()), IntSize(target.width.toInt(), target.height.toInt()))
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

private fun fitImageInVisibleWorld(imageWidth: Int, imageHeight: Int, camera: CanvasCamera?): CanvasRect {
    val active = camera ?: return CanvasRect(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
    val scale = minOf(active.visibleWidth * 0.9f / imageWidth, active.visibleHeight * 0.9f / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return CanvasRect(active.centerX - width / 2f, active.centerY - height / 2f, width, height)
}

private val CardFace.label: String get() = if (this == CardFace.Front) "正面" else "背面"

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

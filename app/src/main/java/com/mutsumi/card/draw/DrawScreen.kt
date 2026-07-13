package com.mutsumi.card.draw

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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream

data class DrawnCardImage(
    val pngBytes: ByteArray,
    val baseImageBytes: ByteArray?,
    val strokeCount: Int,
)

private data class StrokePoint(val position: Offset)
private data class DrawingStroke(val points: List<StrokePoint>, val color: Color, val width: Float)
private enum class EditMode { Draw, Move }
private enum class DrawStep { Key, Canvas }

private class DrawingSessionViewModel : ViewModel() {
    val keyText = mutableStateOf("")
    val status = mutableStateOf("先填写文字 key，再进入画布绘制图片 value。")
    val step = mutableStateOf(DrawStep.Key)
    val strokes = mutableStateListOf<DrawingStroke>()
    val currentPoints = mutableStateListOf<StrokePoint>()
    val penColor = mutableStateOf(Color(0xFF16352E))
    val penWidth = androidx.compose.runtime.mutableFloatStateOf(6f)
    val editMode = mutableStateOf(EditMode.Draw)
    val viewportSize = mutableStateOf(IntSize.Zero)
    val camera = mutableStateOf<CanvasCamera?>(null)
    val baseImageBytes = mutableStateOf<ByteArray?>(null)
}

@Composable
fun DrawScreen(
    onSaveCard: (String, DrawnCardImage) -> String,
) {
    val session: DrawingSessionViewModel = viewModel { DrawingSessionViewModel() }
    val context = LocalContext.current
    var keyText by session.keyText
    var status by session.status
    var step by session.step
    val strokes = session.strokes
    val currentPoints = session.currentPoints
    var penColor by session.penColor
    var penWidth by session.penWidth
    var editMode by session.editMode
    var viewportSize by session.viewportSize
    var camera by session.camera
    var baseImageBytes by session.baseImageBytes
    val baseBitmap = remember(baseImageBytes) {
        baseImageBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("底图不是可解码的图片")
        }
    }
    DisposableEffect(baseBitmap) {
        onDispose { baseBitmap?.takeUnless(Bitmap::isRecycled)?.recycle() }
    }

    LaunchedEffect(viewportSize) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            camera = camera
                ?.withViewport(viewportSize.width.toFloat(), viewportSize.height.toFloat())
                ?: CanvasCamera.initial(viewportSize.width.toFloat(), viewportSize.height.toFloat())
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取底图：$uri")
            checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also(Bitmap::recycle)) {
                "底图不是可解码的图片：$uri"
            }
            baseImageBytes = bytes
            status = "底图已插入：保存时会等比居中放入银行卡比例画布。"
            step = DrawStep.Canvas
        }
    }

    fun clearCanvas() {
        strokes.clear()
        currentPoints.clear()
        baseImageBytes = null
        camera = viewportSize.takeIf { it.width > 0 && it.height > 0 }?.let {
            CanvasCamera.initial(it.width.toFloat(), it.height.toFloat())
        }
        status = "画布已清空。"
    }

    fun save() {
        if (keyText.isBlank()) {
            status = "请输入文字 key。"
            step = DrawStep.Key
            return
        }
        if (strokes.isEmpty() && baseBitmap == null) {
            status = "请先绘制或插入图片 value。"
            step = DrawStep.Canvas
            return
        }
        val pngBytes = renderCanvasPng(
            strokes = strokes,
            baseBitmap = baseBitmap,
            backgroundColor = Color(0xFFF8FAF9),
        )
        val message = onSaveCard(
            keyText,
            DrawnCardImage(
                pngBytes = pngBytes,
                baseImageBytes = baseImageBytes,
                strokeCount = strokes.size,
            ),
        )
        keyText = ""
        clearCanvas()
        step = DrawStep.Key
        status = "$message。下一张卡片可以继续录入。"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LandscapeTools(
                    keyText = keyText,
                    status = status,
                    penColor = penColor,
                    penWidth = penWidth,
                    editMode = editMode,
                    onKeyChange = { keyText = it },
                    onPenColorChange = { penColor = it },
                    onPenWidthChange = { penWidth = it },
                    onEditModeChange = { editMode = it },
                    onInsertBase = { imagePicker.launch("image/*") },
                    onUndo = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                    onClear = ::clearCanvas,
                    onSave = ::save,
                    modifier = Modifier.width(260.dp).fillMaxHeight(),
                )
                CardCanvasFrame(Modifier.weight(1f).fillMaxHeight()) { canvasModifier ->
                    DrawingCanvas(
                        strokes = strokes,
                        currentPoints = currentPoints,
                        penColor = penColor,
                        penWidth = penWidth,
                        editMode = editMode,
                        camera = camera,
                        baseBitmap = baseBitmap,
                        onCameraChange = { camera = it },
                        onSizeChange = { viewportSize = it },
                        modifier = canvasModifier,
                    )
                }
            }
        } else {
            when (step) {
                DrawStep.Key -> KeyEntryPanel(
                    keyText = keyText,
                    status = status,
                    onKeyChange = { keyText = it },
                    onNext = {
                        if (keyText.isBlank()) {
                            status = "请输入文字 key。"
                        } else {
                            status = "保存图为统一银行卡比例；底图等比居中；移动缩放用于浏览实体画布。"
                            step = DrawStep.Canvas
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                DrawStep.Canvas -> Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompactPortraitTools(
                        penColor = penColor,
                        penWidth = penWidth,
                        editMode = editMode,
                        onPenColorChange = { penColor = it },
                        onPenWidthChange = { penWidth = it },
                        onEditModeChange = { editMode = it },
                        onInsertBase = { imagePicker.launch("image/*") },
                        onBackToKey = { step = DrawStep.Key },
                    )
                    CardCanvasFrame(Modifier.fillMaxWidth().weight(1f)) { canvasModifier ->
                        DrawingCanvas(
                            strokes = strokes,
                            currentPoints = currentPoints,
                            penColor = penColor,
                            penWidth = penWidth,
                            editMode = editMode,
                            camera = camera,
                            baseBitmap = baseBitmap,
                            onCameraChange = { camera = it },
                            onSizeChange = { viewportSize = it },
                            modifier = canvasModifier,
                        )
                    }
                    BottomActions(
                        onUndo = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                        onClear = ::clearCanvas,
                        onSave = ::save,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyEntryPanel(
    keyText: String,
    status: String,
    onKeyChange: (String) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("录入卡片", style = MaterialTheme.typography.titleLarge)
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("key 只存文字；value 只存图片。图片统一保存为银行卡比例，底图不会拉伸。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = keyText,
            onValueChange = onKeyChange,
            label = { Text("文字 key") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("进入绘图")
        }
    }
}

@Composable
private fun CardCanvasFrame(
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val widthFromHeight = maxHeight * DrawingCanvasSpec.aspectRatio
        val canvasModifier = if (widthFromHeight <= maxWidth) {
            Modifier.height(maxHeight).width(widthFromHeight)
        } else {
            Modifier.width(maxWidth).height(maxWidth / DrawingCanvasSpec.aspectRatio)
        }
        content(canvasModifier)
    }
}

@Composable
private fun LandscapeTools(
    keyText: String,
    status: String,
    penColor: Color,
    penWidth: Float,
    editMode: EditMode,
    onKeyChange: (String) -> Unit,
    onPenColorChange: (Color) -> Unit,
    onPenWidthChange: (Float) -> Unit,
    onEditModeChange: (EditMode) -> Unit,
    onInsertBase: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("录入", style = MaterialTheme.typography.titleMedium)
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = keyText, onValueChange = onKeyChange, label = { Text("文字 key") })
            ModeChips(editMode = editMode, onEditModeChange = onEditModeChange)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorChoices(penColor = penColor, onPenColorChange = onPenColorChange, modifier = Modifier.weight(1f))
                VerticalBrushSlider(penWidth = penWidth, onPenWidthChange = onPenWidthChange)
            }
            OutlinedButton(onClick = onInsertBase, modifier = Modifier.fillMaxWidth()) {
                Text("插入底图")
            }
        }
        BottomActions(onUndo = onUndo, onClear = onClear, onSave = onSave)
    }
}

@Composable
private fun CompactPortraitTools(
    penColor: Color,
    penWidth: Float,
    editMode: EditMode,
    onPenColorChange: (Color) -> Unit,
    onPenWidthChange: (Float) -> Unit,
    onEditModeChange: (EditMode) -> Unit,
    onInsertBase: () -> Unit,
    onBackToKey: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeChips(editMode = editMode, onEditModeChange = onEditModeChange, modifier = Modifier.weight(1.15f))
            OutlinedButton(onClick = onInsertBase, modifier = Modifier.weight(0.85f)) {
                Text("底图")
            }
            OutlinedButton(onClick = onBackToKey, modifier = Modifier.weight(0.75f)) {
                Text("key")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorChoices(penColor = penColor, onPenColorChange = onPenColorChange, modifier = Modifier.weight(1f))
            Column(modifier = Modifier.weight(1f)) {
                Text("笔刷 ${penWidth.toInt()}", style = MaterialTheme.typography.labelSmall)
                Slider(value = penWidth, onValueChange = onPenWidthChange, valueRange = 2f..24f, modifier = Modifier.height(30.dp))
            }
        }
    }
}

@Composable
private fun ModeChips(
    editMode: EditMode,
    onEditModeChange: (EditMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = editMode == EditMode.Draw, onClick = { onEditModeChange(EditMode.Draw) }, label = { Text("绘制") })
        FilterChip(selected = editMode == EditMode.Move, onClick = { onEditModeChange(EditMode.Move) }, label = { Text("移动") })
    }
}

@Composable
private fun ColorChoices(
    penColor: Color,
    onPenColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorOptions = listOf(Color(0xFF16352E), Color(0xFFC23B22), Color(0xFF2B5EAA), Color(0xFF7B4BA3))
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        colorOptions.forEach { color ->
            OutlinedButton(
                onClick = { onPenColorChange(color) },
                shape = CircleShape,
                modifier = Modifier.size(38.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                border = if (color == penColor) {
                    androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color)
                }
            }
        }
    }
}

@Composable
private fun VerticalBrushSlider(
    penWidth: Float,
    onPenWidthChange: (Float) -> Unit,
) {
    Box(modifier = Modifier.width(54.dp).height(170.dp), contentAlignment = Alignment.Center) {
        Slider(
            value = penWidth,
            onValueChange = onPenWidthChange,
            valueRange = 2f..24f,
            modifier = Modifier.width(160.dp).graphicsLayer { rotationZ = -90f },
        )
        Text(penWidth.toInt().toString(), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BottomActions(
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f)) {
            Text("撤销")
        }
        OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
            Text("清空")
        }
        Button(onClick = onSave, modifier = Modifier.weight(1f)) {
            Text("保存")
        }
    }
}

@Composable
private fun DrawingCanvas(
    strokes: MutableList<DrawingStroke>,
    currentPoints: MutableList<StrokePoint>,
    penColor: Color,
    penWidth: Float,
    editMode: EditMode,
    camera: CanvasCamera?,
    baseBitmap: Bitmap?,
    onCameraChange: (CanvasCamera) -> Unit,
    onSizeChange: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val activeCamera = camera
    val latestCamera by rememberUpdatedState(camera)
    val latestOnCameraChange by rememberUpdatedState(onCameraChange)
    val pointerModifier = when (editMode) {
        EditMode.Draw -> Modifier.pointerInput(activeCamera, penColor, penWidth) {
            if (activeCamera == null) return@pointerInput
            fun toModel(position: Offset): Offset = Offset(
                x = activeCamera.offsetX + position.x / activeCamera.scale,
                y = activeCamera.offsetY + position.y / activeCamera.scale,
            )
            detectDragGestures(
                onDragStart = {
                    currentPoints.clear()
                    currentPoints += StrokePoint(toModel(it))
                },
                onDrag = { change, _ ->
                    if (change.positionChange() != Offset.Zero) {
                        currentPoints += StrokePoint(toModel(change.position))
                    }
                    change.consume()
                },
                onDragEnd = {
                    if (currentPoints.size >= 2) {
                        strokes += DrawingStroke(currentPoints.toList(), penColor, penWidth)
                    }
                    currentPoints.clear()
                },
            )
        }
        EditMode.Move -> Modifier.pointerInput(editMode) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var gestureCamera = latestCamera ?: return@awaitEachGesture
                var hasPressedPointers: Boolean
                do {
                    val event = awaitPointerEvent()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    if (centroid.x.isFinite() && centroid.y.isFinite() && (pan != Offset.Zero || zoom != 1f)) {
                        gestureCamera = gestureCamera.transform(
                            centroidX = centroid.x,
                            centroidY = centroid.y,
                            panX = pan.x,
                            panY = pan.y,
                            zoomFactor = zoom,
                        )
                        latestOnCameraChange(gestureCamera)
                    }
                    event.changes.forEach { change ->
                        if (change.pressed) change.consume()
                    }
                    hasPressedPointers = event.changes.any { it.pressed }
                } while (hasPressedPointers)
            }
        }
    }

    Canvas(
        modifier = modifier
            .background(Color(0xFFF8FAF9), shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clip(shape)
            .onSizeChanged(onSizeChange)
            .then(pointerModifier),
    ) {
        clipRect {
            drawRect(Color(0xFFF8FAF9), size = size)
            if (activeCamera != null) {
                withTransform({
                    scale(activeCamera.scale, activeCamera.scale)
                    translate(-activeCamera.offsetX, -activeCamera.offsetY)
                }) {
                    drawLogicalCanvasBackground()
                    drawBaseImage(baseBitmap)
                    strokes.forEach(::drawStroke)
                    drawStroke(DrawingStroke(currentPoints.toList(), penColor, penWidth))
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLogicalCanvasBackground() {
    drawRect(
        color = Color(0xFFFFFFFF),
        topLeft = Offset.Zero,
        size = androidx.compose.ui.geometry.Size(CardCanvasSpec.logicalWidth, CardCanvasSpec.logicalHeight),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBaseImage(baseBitmap: Bitmap?) {
    baseBitmap ?: return
    val rect = fitCenterRect(
        sourceWidth = baseBitmap.width,
        sourceHeight = baseBitmap.height,
        targetWidth = CardCanvasSpec.exportWidth,
        targetHeight = CardCanvasSpec.exportHeight,
    )
    drawImage(
        image = baseBitmap.asImageBitmap(),
        dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
        dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(stroke: DrawingStroke) {
    if (stroke.points.size < 2) return
    val path = Path().apply {
        moveTo(stroke.points.first().position.x, stroke.points.first().position.y)
        stroke.points.drop(1).forEach { lineTo(it.position.x, it.position.y) }
    }
    drawPath(path, stroke.color, style = Stroke(width = stroke.width, cap = StrokeCap.Round))
}

private fun renderCanvasPng(
    strokes: List<DrawingStroke>,
    baseBitmap: Bitmap?,
    backgroundColor: Color,
): ByteArray {
    require(strokes.isNotEmpty() || baseBitmap != null) { "图片 value 需要笔迹或底图" }

    val bitmap = Bitmap.createBitmap(CardCanvasSpec.exportWidth, CardCanvasSpec.exportHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(backgroundColor.toArgb())
    baseBitmap?.let {
        val rect = fitCenterRect(it.width, it.height, CardCanvasSpec.exportWidth, CardCanvasSpec.exportHeight)
        canvas.drawBitmap(
            it,
            null,
            RectF(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height),
            Paint(Paint.ANTI_ALIAS_FLAG),
        )
    }
    strokes.forEach { stroke ->
        if (stroke.points.size >= 2) {
            val path = AndroidPath().apply {
                moveTo(stroke.points.first().position.x, stroke.points.first().position.y)
                stroke.points.drop(1).forEach { lineTo(it.position.x, it.position.y) }
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color.toArgb()
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = stroke.width
            }
            canvas.drawPath(path, paint)
        }
    }

    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
        output.toByteArray()
    }
}

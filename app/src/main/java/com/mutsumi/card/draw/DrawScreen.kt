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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

data class DrawnCardImage(
    val pngBytes: ByteArray,
    val baseImageBytes: ByteArray?,
    val strokeCount: Int,
)

private data class StrokePoint(val position: Offset)
private data class DrawingStroke(val points: List<StrokePoint>, val color: Color, val width: Float)

@Composable
fun DrawScreen(
    onSaveCard: (String, DrawnCardImage) -> String,
) {
    val context = LocalContext.current
    var keyText by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf("输入 key 后，在画布上绘制图片 value。保存图会与当前预览一致。")
    }
    val strokes = remember { mutableStateListOf<DrawingStroke>() }
    val currentPoints = remember { mutableStateListOf<StrokePoint>() }
    var penColor by remember { mutableStateOf(Color(0xFF16352E)) }
    var penWidth by remember { mutableFloatStateOf(6f) }
    var editMode by remember { mutableStateOf(EditMode.Draw) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var baseImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取底图：$uri")
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("底图不是可解码的图片：$uri")
            baseImageBytes = bytes
            baseBitmap = bitmap
            status = "底图已插入，会作为图片 value 的底层一起保存。"
        }
    }

    fun save() {
        if (keyText.isBlank()) {
            status = "请输入文字 key。"
            return
        }
        if (strokes.isEmpty()) {
            status = "请先绘制图片 value。"
            return
        }
        val pngBytes = renderCanvasPng(
            width = canvasSize.width,
            height = canvasSize.height,
            strokes = strokes,
            baseBitmap = baseBitmap,
            zoom = zoom,
            pan = pan,
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
        strokes.clear()
        currentPoints.clear()
        baseImageBytes = null
        baseBitmap = null
        zoom = 1f
        pan = Offset.Zero
        status = "$message；保存图片与当前预览一致。"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DrawControls(
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
                    onClear = {
                        strokes.clear()
                        currentPoints.clear()
                        baseImageBytes = null
                        baseBitmap = null
                        zoom = 1f
                        pan = Offset.Zero
                        status = "画布已清空。"
                    },
                    onSave = ::save,
                    modifier = Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                )
                DrawingCanvas(
                    strokes = strokes,
                    currentPoints = currentPoints,
                    penColor = penColor,
                    penWidth = penWidth,
                    editMode = editMode,
                    zoom = zoom,
                    pan = pan,
                    baseBitmap = baseBitmap,
                    onZoomPanChange = { newZoom, newPan ->
                        zoom = newZoom.coerceIn(0.5f, 4f)
                        pan = newPan
                    },
                    onSizeChange = { canvasSize = it },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DrawControls(
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
                    onClear = {
                        strokes.clear()
                        currentPoints.clear()
                        baseImageBytes = null
                        baseBitmap = null
                        zoom = 1f
                        pan = Offset.Zero
                        status = "画布已清空。"
                    },
                    onSave = ::save,
                    modifier = Modifier.fillMaxWidth(),
                )
                DrawingCanvas(
                    strokes = strokes,
                    currentPoints = currentPoints,
                    penColor = penColor,
                    penWidth = penWidth,
                    editMode = editMode,
                    zoom = zoom,
                    pan = pan,
                    baseBitmap = baseBitmap,
                    onZoomPanChange = { newZoom, newPan ->
                        zoom = newZoom.coerceIn(0.5f, 4f)
                        pan = newPan
                    },
                    onSizeChange = { canvasSize = it },
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                )
            }
        }
    }
}

private enum class EditMode { Draw, Move }

@Composable
private fun DrawControls(
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
    val colorOptions = listOf(Color(0xFF16352E), Color(0xFFC23B22), Color(0xFF2B5EAA), Color(0xFF7B4BA3))

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("录入卡片", style = MaterialTheme.typography.titleMedium)
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = keyText,
            onValueChange = onKeyChange,
            label = { Text("文字 key") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = editMode == EditMode.Draw,
                onClick = { onEditModeChange(EditMode.Draw) },
                label = { Text("绘制") },
            )
            FilterChip(
                selected = editMode == EditMode.Move,
                onClick = { onEditModeChange(EditMode.Move) },
                label = { Text("移动缩放") },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("笔刷大小 ${penWidth.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(value = penWidth, onValueChange = onPenWidthChange, valueRange = 2f..24f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            colorOptions.forEach { color ->
                OutlinedButton(
                    onClick = { onPenColorChange(color) },
                    shape = CircleShape,
                    border = if (color == penColor) {
                        androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                        drawCircle(color)
                    }
                }
            }
        }
        OutlinedButton(onClick = onInsertBase, modifier = Modifier.fillMaxWidth()) {
            Text("插入底图")
        }
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
}

@Composable
private fun DrawingCanvas(
    strokes: MutableList<DrawingStroke>,
    currentPoints: MutableList<StrokePoint>,
    penColor: Color,
    penWidth: Float,
    editMode: EditMode,
    zoom: Float,
    pan: Offset,
    baseBitmap: Bitmap?,
    onZoomPanChange: (Float, Offset) -> Unit,
    onSizeChange: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val pointerModifier = when (editMode) {
        EditMode.Draw -> Modifier.pointerInput(zoom, pan, penColor, penWidth) {
            fun toModel(position: Offset): Offset = (position - pan) / zoom
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
        EditMode.Move -> Modifier.pointerInput(Unit) {
            detectTransformGestures { centroid, gesturePan, gestureZoom, _ ->
                val newZoom = (zoom * gestureZoom).coerceIn(0.5f, 4f)
                val newPan = centroid - (centroid - pan) * (newZoom / zoom) + gesturePan
                onZoomPanChange(newZoom, newPan)
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
            withTransform({
                translate(pan.x, pan.y)
                scale(zoom, zoom)
            }) {
                baseBitmap?.let {
                    drawImage(
                        image = it.asImageBitmap(),
                        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }
                strokes.forEach(::drawStroke)
                drawStroke(DrawingStroke(currentPoints.toList(), penColor, penWidth))
            }
        }
    }
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
    width: Int,
    height: Int,
    strokes: List<DrawingStroke>,
    baseBitmap: Bitmap?,
    zoom: Float,
    pan: Offset,
    backgroundColor: Color,
): ByteArray {
    require(width > 0 && height > 0) { "画布尺寸无效：${width}x$height" }
    require(strokes.isNotEmpty()) { "图片 value 至少需要一笔绘制内容" }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(backgroundColor.toArgb())
    canvas.save()
    canvas.clipRect(0, 0, width, height)
    canvas.translate(pan.x, pan.y)
    canvas.scale(zoom, zoom)
    baseBitmap?.let {
        canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG))
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
    canvas.restore()

    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
        output.toByteArray()
    }
}

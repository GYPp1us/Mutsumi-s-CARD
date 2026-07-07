package com.mutsumi.card.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private data class StrokePoint(val position: Offset)
private data class DrawingStroke(val points: List<StrokePoint>, val color: Color, val width: Float)

@Composable
fun DrawScreen(
    onSaveCard: (String, Int) -> String,
) {
    var keyText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("输入 key 后，在画布上绘制图片 value。") }
    val strokes = remember { mutableStateListOf<DrawingStroke>() }
    val currentPoints = remember { mutableStateListOf<StrokePoint>() }
    val penColor = Color(0xFF16352E)
    val penWidth = 6f

    fun save() {
        status = when {
            keyText.isBlank() -> "请输入文字 key。"
            strokes.isEmpty() -> "请先绘制图片 value。"
            else -> {
                val message = onSaveCard(keyText, strokes.size)
                keyText = ""
                strokes.clear()
                message
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DrawControls(
                    keyText = keyText,
                    status = status,
                    onKeyChange = { keyText = it },
                    onUndo = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                    onClear = {
                        strokes.clear()
                        status = "画布已清空。"
                    },
                    onSave = ::save,
                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                )
                DrawingCanvas(
                    strokes = strokes,
                    currentPoints = currentPoints,
                    penColor = penColor,
                    penWidth = penWidth,
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
                    onKeyChange = { keyText = it },
                    onUndo = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                    onClear = {
                        strokes.clear()
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
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                )
            }
        }
    }
}

@Composable
private fun DrawControls(
    keyText: String,
    status: String,
    onKeyChange: (String) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .background(Color(0xFFF8FAF9), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        currentPoints.clear()
                        currentPoints += StrokePoint(it)
                    },
                    onDrag = { change, _ -> currentPoints += StrokePoint(change.position) },
                    onDragEnd = {
                        if (currentPoints.size >= 2) {
                            strokes += DrawingStroke(currentPoints.toList(), penColor, penWidth)
                        }
                        currentPoints.clear()
                    },
                )
            },
    ) {
        fun drawStroke(stroke: DrawingStroke) {
            if (stroke.points.size < 2) return
            val path = Path().apply {
                moveTo(stroke.points.first().position.x, stroke.points.first().position.y)
                stroke.points.drop(1).forEach { lineTo(it.position.x, it.position.y) }
            }
            drawPath(path, stroke.color, style = Stroke(width = stroke.width, cap = StrokeCap.Round))
        }
        strokes.forEach(::drawStroke)
        drawStroke(DrawingStroke(currentPoints.toList(), penColor, penWidth))
    }
}


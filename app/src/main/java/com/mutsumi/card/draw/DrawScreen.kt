package com.mutsumi.card.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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
fun DrawScreen() {
    var keyText by remember { mutableStateOf("") }
    val strokes = remember { mutableStateListOf<DrawingStroke>() }
    val currentPoints = remember { mutableStateListOf<StrokePoint>() }
    val penColor = Color(0xFF16352E)
    val penWidth = 6f

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it },
            label = { Text("文字 key") },
            modifier = Modifier.fillMaxWidth(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
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
                            strokes += DrawingStroke(currentPoints.toList(), penColor, penWidth)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, modifier = Modifier.weight(1f)) {
                Text("撤销")
            }
            OutlinedButton(onClick = { strokes.clear() }, modifier = Modifier.weight(1f)) {
                Text("清空")
            }
            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                Text("保存")
            }
        }
    }
}


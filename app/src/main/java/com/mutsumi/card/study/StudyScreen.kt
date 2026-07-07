package com.mutsumi.card.study

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.workflow.MemoryCard
import com.mutsumi.card.ui.CardValueImage
import java.io.File
import kotlin.math.abs

@Composable
fun StudyScreen(
    cards: List<MemoryCard>,
    currentCardId: Long?,
    imageRoot: File,
    onFeedback: (Long, ReviewFeedback) -> String,
) {
    if (cards.isEmpty()) {
        EmptyStudyState()
        return
    }

    val card = cards.firstOrNull { it.id == currentCardId } ?: cards.first()
    var message by remember { mutableStateOf("左右滑动翻面，上滑记住，下滑忘记") }
    var backVisible by remember(card.id) { mutableStateOf(false) }
    var dragOffset by remember(card.id) { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val tilt = rememberGyroTilt()

    LaunchedEffect(card.id) {
        backVisible = false
        dragOffset = Offset.Zero
        message = "当前推荐：${card.keyText}"
    }

    val policy = remember(cardSize) {
        StudyGesturePolicy(
            cardWidth = cardSize.width.coerceAtLeast(1).toFloat(),
            cardHeight = cardSize.height.coerceAtLeast(1).toFloat(),
        )
    }
    val flipProgress = policy.flipProgress(dragOffset.x)
    val baseRotation = if (backVisible) 180f else 0f
    val rotationY = baseRotation + flipProgress * 180f + tilt.y
    val normalized = ((rotationY % 360f) + 360f) % 360f
    val showingBack = normalized in 90f..270f

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "随机推荐", style = MaterialTheme.typography.titleMedium)
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            FloatingStudyCard(
                card = card,
                imageRoot = imageRoot,
                showingBack = showingBack,
                rotationY = rotationY,
                rotationX = tilt.x + (dragOffset.y / cardSize.height.coerceAtLeast(1)) * -8f,
                onSizeChange = { cardSize = it },
                onDragChange = { dragOffset += it },
                onDragEnd = {
                    val feedback = policy.feedbackFor(dragOffset.y)
                    if (feedback != null && abs(dragOffset.y) > abs(dragOffset.x)) {
                        message = onFeedback(card.id, feedback)
                    } else {
                        if (abs(policy.flipProgress(dragOffset.x)) >= 1f) {
                            backVisible = !backVisible
                        }
                    }
                    dragOffset = Offset.Zero
                },
                modifier = Modifier.fillMaxWidth(0.98f).aspectRatio(2f),
            )
        }
    }
}

@Composable
private fun FloatingStudyCard(
    card: MemoryCard,
    imageRoot: File,
    showingBack: Boolean,
    rotationY: Float,
    rotationX: Float,
    onSizeChange: (IntSize) -> Unit,
    onDragChange: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .onSizeChanged(onSizeChange)
            .graphicsLayer {
                cameraDistance = 14f * density
                this.rotationY = rotationY
                this.rotationX = rotationX
                shadowElevation = 18f
            }
            .pointerInput(card.id) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        onDragChange(dragAmount)
                        change.consume()
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            if (showingBack) {
                Box(modifier = Modifier.graphicsLayer { this.rotationY = 180f }.fillMaxSize()) {
                    CardValueImage(card = card, imageRoot = imageRoot, modifier = Modifier.fillMaxSize())
                }
            } else {
                Text(
                    text = card.keyText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun rememberGyroTilt(): Offset {
    val context = LocalContext.current
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    SensorManager.getOrientation(rotation, orientation)
                    tiltX = (orientation[1] * 8f).coerceIn(-6f, 6f)
                    tiltY = (orientation[2] * -8f).coerceIn(-6f, 6f)
                } else {
                    tiltX = (event.values.getOrNull(1) ?: 0f).coerceIn(-6f, 6f)
                    tiltY = (event.values.getOrNull(0) ?: 0f).coerceIn(-6f, 6f)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            manager.unregisterListener(listener)
        }
    }

    return Offset(tiltX, tiltY)
}

@Composable
private fun EmptyStudyState() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "随机推荐", style = MaterialTheme.typography.titleMedium)
        Text("当前卡组为空，请先到“录入”创建第一张卡片。")
    }
}

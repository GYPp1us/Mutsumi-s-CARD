package com.mutsumi.card.study

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.workflow.MemoryCard
import com.mutsumi.card.ui.CardValueImage
import java.io.File

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
    var committedSide by remember(card.id) { mutableStateOf(CardSide.Front) }
    var activeProjection by remember(card.id) { mutableStateOf<StudyGestureProjection?>(null) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val tilt = rememberGyroTilt()

    LaunchedEffect(card.id) {
        committedSide = CardSide.Front
        activeProjection = null
        message = "当前推荐：${card.keyText}"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val policy = remember(cardSize, screenWidthPx, screenHeightPx) {
            StudyGesturePolicy(
                screenWidth = screenWidthPx.coerceAtLeast(1f),
                screenHeight = screenHeightPx.coerceAtLeast(1f),
                cardWidth = cardSize.width.coerceAtLeast(1).toFloat(),
                cardHeight = cardSize.height.coerceAtLeast(1).toFloat(),
            )
        }
        val restingProjection = policy.resting(committedSide)
        val projection = activeProjection ?: restingProjection.copy(
            rotationX = restingProjection.rotationX + tilt.x,
            rotationY = restingProjection.rotationY + if (restingProjection.showingBack) -tilt.y else tilt.y,
        )

        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "随机推荐", style = MaterialTheme.typography.titleMedium)
            Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                FloatingStudyCard(
                    card = card,
                    imageRoot = imageRoot,
                    projection = projection,
                    onSizeChange = { cardSize = it },
                    onProjectionChange = { activeProjection = it },
                    onGestureEnd = { releaseAction ->
                        when (releaseAction) {
                            is StudyReleaseAction.Feedback -> {
                                message = onFeedback(card.id, releaseAction.feedback)
                                committedSide = CardSide.Front
                            }
                            StudyReleaseAction.ToggleSide -> {
                                committedSide = when (committedSide) {
                                    CardSide.Front -> CardSide.Back
                                    CardSide.Back -> CardSide.Front
                                }
                            }
                            null -> Unit
                        }
                        activeProjection = null
                    },
                    policy = policy,
                    committedSide = committedSide,
                    modifier = Modifier.fillMaxWidth(0.98f).aspectRatio(2f),
                )
            }
        }
    }
}

@Composable
private fun FloatingStudyCard(
    card: MemoryCard,
    imageRoot: File,
    projection: StudyGestureProjection,
    onSizeChange: (IntSize) -> Unit,
    onProjectionChange: (StudyGestureProjection) -> Unit,
    onGestureEnd: (StudyReleaseAction?) -> Unit,
    policy: StudyGesturePolicy,
    committedSide: CardSide,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .onSizeChanged(onSizeChange)
            .graphicsLayer {
                cameraDistance = 14f * density
                this.rotationY = projection.rotationY
                this.rotationX = projection.rotationX
                this.translationX = projection.translationX
                this.translationY = projection.translationY
                this.alpha = projection.cardAlpha
                shadowElevation = 0f
                this.shape = shape
                clip = true
            }
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clip(shape)
            .pointerInput(card.id, committedSide, policy) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val anchor = StudyTouchPoint(down.position.x, down.position.y)
                    var latestProjection = policy.project(anchor, anchor, committedSide)
                    onProjectionChange(latestProjection)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                        if (change == null) {
                            onGestureEnd(latestProjection.releaseAction)
                            break
                        }
                        if (change.changedToUp()) {
                            onGestureEnd(latestProjection.releaseAction)
                            change.consume()
                            break
                        }
                        if (change.pressed) {
                            latestProjection = policy.project(
                                anchor = anchor,
                                current = StudyTouchPoint(change.position.x, change.position.y),
                                committedSide = committedSide,
                            )
                            onProjectionChange(latestProjection)
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = projection.frontAlpha }
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = card.keyText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = projection.backAlpha
                        scaleX = -1f
                    }
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CardValueImage(card = card, imageRoot = imageRoot, modifier = Modifier.fillMaxSize())
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

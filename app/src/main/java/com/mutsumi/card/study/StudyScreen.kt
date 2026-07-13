package com.mutsumi.card.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.workflow.MemoryCard
import com.mutsumi.card.draw.DrawingCanvasSpec
import com.mutsumi.card.ui.CardValueImage
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

private data class StudyReturnAnimation(
    val start: StudyCardPhysicalState,
    val target: StudyCardPhysicalState,
    val sequence: Int,
    val feedback: ReviewFeedback? = null,
    val useInertialSpring: Boolean = false,
    val initialProgressVelocity: Float = 0f,
)

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
    var returnAnimation by remember(card.id) { mutableStateOf<StudyReturnAnimation?>(null) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(card.id) {
        committedSide = CardSide.Front
        activeProjection = null
        returnAnimation = null
        message = "当前推荐：${card.keyText}"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val density = LocalDensity.current
        val context = LocalContext.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val minimumFlingVelocity = max(
            android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat(),
            with(density) { 600.dp.toPx() },
        )
        val minimumHorizontalFlingVelocity = max(
            android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat(),
            with(density) { 700.dp.toPx() },
        )
        val maximumFlingVelocity = android.view.ViewConfiguration.get(context)
            .scaledMaximumFlingVelocity.toFloat()
        val policy = remember(cardSize, screenWidthPx, screenHeightPx) {
            StudyGesturePolicy(
                screenWidth = screenWidthPx.coerceAtLeast(1f),
                screenHeight = screenHeightPx.coerceAtLeast(1f),
                cardWidth = cardSize.width.coerceAtLeast(1).toFloat(),
                cardHeight = cardSize.height.coerceAtLeast(1).toFloat(),
            )
        }
        val restingProjection = policy.resting(committedSide)
        val returnProgress = remember(card.id) { Animatable(1f) }

        LaunchedEffect(returnAnimation?.sequence) {
            val animation = returnAnimation ?: return@LaunchedEffect
            returnProgress.snapTo(0f)
            returnProgress.animateTo(
                targetValue = 1f,
                animationSpec = if (animation.useInertialSpring) {
                    spring(dampingRatio = 0.82f, stiffness = 420f)
                } else {
                    tween(durationMillis = if (animation.feedback == null) 260 else 220)
                },
                initialVelocity = animation.initialProgressVelocity,
            )
            if (returnAnimation?.sequence == animation.sequence) {
                val feedbackValue = animation.feedback
                if (feedbackValue == null) {
                    returnAnimation = null
                } else {
                    message = onFeedback(card.id, feedbackValue)
                    delay(120)
                    if (returnAnimation?.sequence == animation.sequence) {
                        returnAnimation = null
                        committedSide = CardSide.Front
                    }
                }
            }
        }

        val returnState = returnAnimation?.let { animation ->
            interpolateStudyCardPhysicalState(
                from = animation.start,
                to = animation.target,
                fraction = studyCardReturnEasing(returnProgress.value),
            )
        }
        val renderedState = activeProjection?.physicalState ?: returnState ?: restingProjection.physicalState

        Column(
            modifier = Modifier.fillMaxSize().padding(if (landscape) 4.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!landscape) {
                Text(text = "随机推荐", style = MaterialTheme.typography.titleMedium)
                Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val maximumCardWidth = maxWidth * 0.8f
                val widthFromHeight = maxHeight * DrawingCanvasSpec.aspectRatio
                val cardWidth = if (widthFromHeight < maximumCardWidth) widthFromHeight else maximumCardWidth
                FloatingStudyCard(
                    card = card,
                    imageRoot = imageRoot,
                    center = renderedState.center,
                    angle = renderedState.angle,
                    onSizeChange = { cardSize = it },
                    onProjectionChange = {
                        returnAnimation = null
                        activeProjection = it
                    },
                    onGestureEnd = { releaseProjection ->
                        val releaseAction = releaseProjection.releaseAction
                        when (releaseAction) {
                            is StudyReleaseAction.Feedback -> {
                                returnAnimation = StudyReturnAnimation(
                                    start = releaseProjection.physicalState,
                                    target = policy.exitTarget(releaseProjection, releaseAction.feedback),
                                    sequence = (returnAnimation?.sequence ?: 0) + 1,
                                    feedback = releaseAction.feedback,
                                )
                            }
                            StudyReleaseAction.ToggleSide -> {
                                val targetSide = when (committedSide) {
                                    CardSide.Front -> CardSide.Back
                                    CardSide.Back -> CardSide.Front
                                }
                                committedSide = targetSide
                                returnAnimation = StudyReturnAnimation(
                                    start = releaseProjection.physicalState,
                                    target = policy.resting(targetSide).physicalState,
                                    sequence = (returnAnimation?.sequence ?: 0) + 1,
                                    useInertialSpring = true,
                                    initialProgressVelocity = policy.horizontalAnimationProgressVelocity(
                                        releaseProjection,
                                    ),
                                )
                            }
                            null -> {
                                returnAnimation = StudyReturnAnimation(
                                    start = releaseProjection.physicalState,
                                    target = restingProjection.physicalState,
                                    sequence = (returnAnimation?.sequence ?: 0) + 1,
                                )
                            }
                        }
                        activeProjection = null
                    },
                    policy = policy,
                    committedSide = committedSide,
                    minimumFlingVelocity = minimumFlingVelocity,
                    minimumHorizontalFlingVelocity = minimumHorizontalFlingVelocity,
                    maximumFlingVelocity = maximumFlingVelocity,
                    gestureEnabled = returnAnimation?.feedback == null,
                    modifier = Modifier.width(cardWidth).height(cardWidth / DrawingCanvasSpec.aspectRatio),
                )
            }
        }
    }
}

@Composable
private fun FloatingStudyCard(
    card: MemoryCard,
    imageRoot: File,
    center: StudyCardCenter,
    angle: StudyCardAngle,
    onSizeChange: (IntSize) -> Unit,
    onProjectionChange: (StudyGestureProjection) -> Unit,
    onGestureEnd: (StudyGestureProjection) -> Unit,
    policy: StudyGesturePolicy,
    committedSide: CardSide,
    minimumFlingVelocity: Float,
    minimumHorizontalFlingVelocity: Float,
    maximumFlingVelocity: Float,
    gestureEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val pointerModifier = if (!gestureEnabled) Modifier else Modifier.pointerInput(card.id, committedSide, policy) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val velocityTracker = VelocityTracker().apply {
                addPosition(down.uptimeMillis, down.position)
            }
            val anchor = StudyTouchPoint(down.position.x, down.position.y)
            val interactionStartDirection = committedSide
            var latestProjection = policy.project(
                anchor = anchor,
                current = anchor,
                committedSide = committedSide,
                interactionStartDirection = interactionStartDirection,
            )
            onProjectionChange(latestProjection)

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                if (change == null) {
                    onGestureEnd(latestProjection.copy(releaseAction = null))
                    break
                }
                if (change.changedToUp()) {
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    latestProjection = policy.project(
                        anchor = anchor,
                        current = StudyTouchPoint(change.position.x, change.position.y),
                        committedSide = committedSide,
                        interactionStartDirection = interactionStartDirection,
                        lockedMode = latestProjection.mode,
                        lockedAxisRotationZ = latestProjection.lockedAxisRotationZ,
                    )
                    val velocity = velocityTracker.calculateVelocity()
                    val releaseVelocityX = velocity.x.coerceIn(-maximumFlingVelocity, maximumFlingVelocity)
                    onGestureEnd(
                        latestProjection.copy(
                            releaseVelocityX = releaseVelocityX,
                            releaseAction = policy.release(
                                projection = latestProjection,
                                velocityX = releaseVelocityX,
                                velocityY = velocity.y.coerceIn(-maximumFlingVelocity, maximumFlingVelocity),
                                minimumFlingVelocity = minimumFlingVelocity,
                                minimumHorizontalFlingVelocity = minimumHorizontalFlingVelocity,
                            ),
                        ),
                    )
                    change.consume()
                    break
                }
                if (change.pressed) {
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    latestProjection = policy.project(
                        anchor = anchor,
                        current = StudyTouchPoint(change.position.x, change.position.y),
                        committedSide = committedSide,
                        interactionStartDirection = interactionStartDirection,
                        lockedMode = latestProjection.mode,
                        lockedAxisRotationZ = latestProjection.lockedAxisRotationZ,
                    )
                    onProjectionChange(latestProjection)
                    change.consume()
                }
            }
        }
    }

    PhysicalStudyCard(
        card = card,
        imageRoot = imageRoot,
        center = center,
        angle = angle,
        onSizeChange = onSizeChange,
        modifier = modifier.then(pointerModifier),
    )
}

@Composable
private fun PhysicalStudyCard(
    card: MemoryCard,
    imageRoot: File,
    center: StudyCardCenter,
    angle: StudyCardAngle,
    onSizeChange: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val showingBack = angle.isStudyCardBackFacing()

    Box(
        modifier = modifier
            .onSizeChanged(onSizeChange)
            .graphicsLayer {
                this.translationX = center.x
                this.translationY = center.y
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = angle.axisRotationZ
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        cameraDistance = 14f * density
                        rotationY = angle.deflection
                        shadowElevation = 0f
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = if (showingBack) angle.axisRotationZ else -angle.axisRotationZ
                            this.shape = shape
                            clip = true
                        }
                        .background(MaterialTheme.colorScheme.surface, shape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .clip(shape)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer { alpha = if (showingBack) 0f else 1f }
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
                                alpha = if (showingBack) 1f else 0f
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
    }
}

@Composable
private fun EmptyStudyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "随机推荐", style = MaterialTheme.typography.titleMedium)
        Text("当前卡组为空，请先到“录入”创建第一张卡片。")
    }
}

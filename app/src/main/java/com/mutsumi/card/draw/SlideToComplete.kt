package com.mutsumi.card.draw

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun SlideToComplete(enabled: Boolean, modifier: Modifier = Modifier, onComplete: () -> Unit) {
    var width by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val handle = with(LocalDensity.current) { 44.dp.toPx() }
    val travel = (width - handle).coerceAtLeast(1f)
    val shown by animateFloatAsState(progress, tween(if (dragging) 0 else 180), label = "完成滑块回位")
    val complete by rememberUpdatedState(onComplete)
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp), shadowElevation = 4.dp,
        modifier = modifier.height(48.dp).testTag("complete-card").onSizeChanged { width = it.width }
            .semantics {
                contentDescription = "滑动完成制作"
                customActions = listOf(CustomAccessibilityAction("完成制作") { if (enabled) { complete(); true } else false })
            }
            .pointerInput(enabled, travel) {
                if (enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 必须使用真正的按下位置；快速滑动可能一次采样就越过整个手势阈值。
                    if (down.position.x <= handle + 8.dp.toPx()) {
                        down.consume()
                        dragging = true
                        try {
                            var released = false
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.size > 1 || change.isConsumed) break
                                progress = ((change.position.x - down.position.x) / travel).coerceIn(0f, 1f)
                                change.consume()
                                released = !change.pressed
                            } while (!released)
                            if (released && progress >= 0.92f) complete()
                        } finally {
                            dragging = false
                            progress = 0f
                        }
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize().padding(2.dp), contentAlignment = Alignment.Center) {
            Text("完成制作", fontSize = 12.sp, modifier = Modifier.padding(start = 24.dp))
            Box(
                Modifier.align(Alignment.CenterStart).offset { IntOffset((shown * travel).roundToInt(), 0) }
                    .size(44.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

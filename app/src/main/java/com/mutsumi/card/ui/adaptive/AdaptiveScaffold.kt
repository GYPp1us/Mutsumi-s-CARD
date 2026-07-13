package com.mutsumi.card.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsumi.card.ui.navigation.AppDestination
import com.mutsumi.card.ui.theme.AccentYellow
import com.mutsumi.card.ui.theme.Divider
import com.mutsumi.card.ui.theme.PrimaryGreen
import com.mutsumi.card.ui.theme.PrimaryGreenSoft
import com.mutsumi.card.ui.theme.Surface
import kotlin.math.roundToInt

@Composable
fun AdaptiveScaffold(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    contextContent: (@Composable () -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("app-shell")) {
        val outerWidthDp = maxWidth.value.roundToInt()
        val outerHeightDp = maxHeight.value.roundToInt()
        val outerMode = AdaptiveLayoutPolicy.mode(outerWidthDp, outerHeightDp)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = snackbarHost,
            bottomBar = {
                if (outerMode != AppLayoutMode.LandscapeThreePane) {
                    BottomNavigationBar(selected, onSelect)
                }
            },
        ) { safePadding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(safePadding)) {
            val widthDp = maxWidth.value.roundToInt()
            val heightDp = maxHeight.value.roundToInt()
            when (AdaptiveLayoutPolicy.mode(widthDp, heightDp)) {
                AppLayoutMode.LandscapeThreePane -> ThreePaneShell(
                    selected = selected,
                    onSelect = onSelect,
                    onOpenSettings = onOpenSettings,
                    contextContent = contextContent,
                    content = content,
                )
                AppLayoutMode.Portrait,
                AppLayoutMode.CompactLandscape,
                -> Box(Modifier.fillMaxSize().testTag("main-workspace")) { content() }
                }
            }
        }
    }
}

@Composable
private fun ThreePaneShell(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    onOpenSettings: () -> Unit,
    contextContent: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        NavigationRail(selected, onSelect, onOpenSettings)
        Box(Modifier.weight(1f).fillMaxSize().testTag("main-workspace")) { content() }
        if (contextContent != null) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .border(width = 1.dp, color = Divider)
                    .background(Surface)
                    .testTag("context-pane"),
            ) {
                Text(
                    text = "上下文 · ${selected.label}",
                    modifier = Modifier.height(56.dp).padding(horizontal = 16.dp, vertical = 18.dp),
                    fontWeight = FontWeight.SemiBold,
                )
                Box(Modifier.weight(1f).padding(16.dp)) { contextContent() }
            }
        }
    }
}

@Composable
private fun NavigationRail(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .width(72.dp)
            .fillMaxSize()
            .background(Surface)
            .border(width = 1.dp, color = Divider)
            .padding(vertical = 10.dp)
            .testTag("navigation-rail"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(38.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(PrimaryGreen),
            contentAlignment = Alignment.Center,
        ) {
            Text("M", color = Surface, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        AppDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label, fontSize = 10.sp) },
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "设置")
        }
    }
}

@Composable
private fun BottomNavigationBar(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
) {
    Row(
        Modifier
            .height(64.dp)
            .background(Surface)
            .border(width = 1.dp, color = Divider)
            .testTag("bottom-navigation"),
    ) {
        AppDestination.entries.forEach { destination ->
            NavigationBarItem(
                modifier = Modifier.weight(1f),
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.width(28.dp).height(3.dp)
                                .background(if (destination == selected) AccentYellow else Surface),
                        )
                        Icon(destination.icon, contentDescription = destination.label)
                    }
                },
                label = { Text(destination.label, fontSize = 10.sp) },
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = PrimaryGreen,
                    selectedTextColor = PrimaryGreen,
                    indicatorColor = PrimaryGreenSoft,
                ),
            )
        }
    }
}

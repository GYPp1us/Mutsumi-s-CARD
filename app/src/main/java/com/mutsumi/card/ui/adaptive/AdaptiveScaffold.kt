package com.mutsumi.card.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mutsumi.card.ui.navigation.AppDestination

@Composable
fun AdaptiveScaffold(
    destinations: List<AppDestination>,
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    destinations.forEach { destination ->
                        NavigationRailItem(
                            selected = destination == selected,
                            onClick = { onSelect(destination) },
                            icon = { DestinationGlyph(destination) },
                            label = { Text(destination.label) },
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                    content()
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = destination == selected,
                                onClick = { onSelect(destination) },
                                icon = { DestinationGlyph(destination) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun DestinationGlyph(destination: AppDestination) {
    Surface(shape = CircleShape, tonalElevation = 2.dp, modifier = Modifier.size(28.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(destination.glyph, fontWeight = FontWeight.Bold)
        }
    }
}


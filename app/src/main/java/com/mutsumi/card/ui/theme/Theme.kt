package com.mutsumi.card.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF16352E),
    secondary = Color(0xFFE2B84A),
    background = Color(0xFFF5F7F8),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color(0xFF211B08),
    onBackground = Color(0xFF17201D),
    onSurface = Color(0xFF17201D),
)

@Composable
fun MutsumiCardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}


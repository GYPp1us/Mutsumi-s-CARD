package com.mutsumi.card.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

val Ink = Color(0xFF202623)
val MutedInk = Color(0xFF68716C)
val PageBackground = Color(0xFFF3F5F2)
val WorkspaceBackground = Color(0xFFE7EBE7)
val Surface = Color(0xFFFFFFFF)
val Divider = Color(0xFFD8DDD9)
val StrongDivider = Color(0xFFB8C1BB)
val PrimaryGreen = Color(0xFF285447)
val PrimaryGreenSoft = Color(0xFFDCE8E2)
val AccentYellow = Color(0xFFE2B94F)
val DangerCoral = Color(0xFFC65F4C)
val AssistBlue = Color(0xFF496F83)

private val LightColors = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = Surface,
    primaryContainer = PrimaryGreenSoft,
    onPrimaryContainer = Ink,
    secondary = AccentYellow,
    onSecondary = Ink,
    secondaryContainer = Color(0xFFF8EAC1),
    onSecondaryContainer = Ink,
    tertiary = AssistBlue,
    onTertiary = Surface,
    tertiaryContainer = Color(0xFFDCE7EC),
    onTertiaryContainer = Ink,
    error = DangerCoral,
    onError = Surface,
    errorContainer = Color(0xFFF5DFDB),
    onErrorContainer = Ink,
    background = PageBackground,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = WorkspaceBackground,
    onSurfaceVariant = MutedInk,
    surfaceTint = PrimaryGreen,
    surfaceDim = Divider,
    surfaceBright = Surface,
    surfaceContainerLowest = Surface,
    surfaceContainerLow = PageBackground,
    surfaceContainer = WorkspaceBackground,
    surfaceContainerHigh = Divider,
    surfaceContainerHighest = StrongDivider,
    inverseSurface = Ink,
    inverseOnSurface = Surface,
    inversePrimary = Color(0xFFA8CCBE),
    outline = StrongDivider,
    outlineVariant = Divider,
    scrim = Color(0x66000000),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(7.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

private val BaseTypography = Typography()
private val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(letterSpacing = 0.sp),
    displayMedium = BaseTypography.displayMedium.copy(letterSpacing = 0.sp),
    displaySmall = BaseTypography.displaySmall.copy(letterSpacing = 0.sp),
    headlineLarge = BaseTypography.headlineLarge.copy(letterSpacing = 0.sp),
    headlineMedium = BaseTypography.headlineMedium.copy(letterSpacing = 0.sp),
    headlineSmall = BaseTypography.headlineSmall.copy(letterSpacing = 0.sp),
    titleLarge = BaseTypography.titleLarge.copy(letterSpacing = 0.sp),
    titleMedium = BaseTypography.titleMedium.copy(letterSpacing = 0.sp),
    titleSmall = BaseTypography.titleSmall.copy(letterSpacing = 0.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(letterSpacing = 0.sp),
    bodySmall = BaseTypography.bodySmall.copy(letterSpacing = 0.sp),
    labelLarge = BaseTypography.labelLarge.copy(letterSpacing = 0.sp),
    labelMedium = BaseTypography.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = BaseTypography.labelSmall.copy(letterSpacing = 0.sp),
)

@Composable
fun MutsumiCardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

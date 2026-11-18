package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = EnterprisePrimary,
    onPrimary = EnterpriseOnPrimary,
    primaryContainer = ObsidianSurfaceHighlight,
    onPrimaryContainer = TextPrimary,
    secondary = CodingCyan,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = CodingCyanContainer,
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = ReasoningIndigo,
    onTertiary = Color(0xFF1E1B4B),
    tertiaryContainer = ReasoningIndigoContainer,
    onTertiaryContainer = Color(0xFFE0E7FF),
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceElevated,
    surfaceContainerHigh = ObsidianSurfaceHighlight,
    surfaceContainerHighest = Color(0xFF2B2B30),
    outline = ObsidianBorder,
    outlineVariant = ObsidianBorderSubtle,
    error = StatusDanger,
    onError = Color.White,
    errorContainer = StatusDangerContainer,
    onErrorContainer = Color(0xFFFFE4E6)
)

private val LightColorScheme = lightColorScheme(
    primary = LightCodingPrimary,
    onPrimary = Color.White,
    primaryContainer = LightSurfaceHighlight,
    onPrimaryContainer = LightTextPrimary,
    secondary = LightVisionSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = LightReasoningTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF312E81),
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainerLowest = LightBg,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightSurfaceHighlight,
    surfaceContainerHighest = Color(0xFFD4D4D8),
    outline = LightBorder,
    outlineVariant = Color(0xFFEAEAEF),
    error = Color(0xFFE11D48),
    onError = Color.White
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun LocalDevAiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

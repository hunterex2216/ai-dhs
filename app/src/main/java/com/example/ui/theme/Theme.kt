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
    primary = ElectricCyan,
    onPrimary = Color(0xFF002026),
    primaryContainer = CyanContainer,
    onPrimaryContainer = OnCyanContainer,
    secondary = NeuralViolet,
    onSecondary = Color(0xFF15003D),
    secondaryContainer = VioletContainer,
    onSecondaryContainer = OnVioletContainer,
    tertiary = PulseRose,
    onTertiary = Color(0xFF330016),
    tertiaryContainer = RoseContainer,
    onTertiaryContainer = OnRoseContainer,
    background = ObsidianBackground,
    onBackground = Color(0xFFEAF0FF),
    surface = ObsidianSurface,
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = Color(0xFFB0C0E0),
    outline = Color(0xFF344568)
)

private val LightColorScheme = lightColorScheme(
    primary = DeepCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4F6FF),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = DeepViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DEFF),
    onSecondaryContainer = Color(0xFF170065),
    tertiary = Color(0xFFB91456),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E3),
    onTertiaryContainer = Color(0xFF3E001B),
    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to sleek Cyber-Obsidian Dark Theme for AI Command Center
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}

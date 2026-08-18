package com.goldfish.android.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

val GoldfishOrange = Color(0xFFFF8C00)
val GoldfishDark = Color(0xFF1A1A2E)
val GoldfishSurface = Color(0xFF16213E)
val GoldfishCard = Color(0xFF0F3460)
val GoldfishAccent = Color(0xFFE94560)

private val DarkColorScheme = darkColorScheme(
    primary = GoldfishOrange,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF5C3900),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = GoldfishAccent,
    onSecondary = Color.White,
    background = GoldfishDark,
    onBackground = Color.White,
    surface = GoldfishSurface,
    onSurface = Color(0xFFE1E1E1),
    surfaceVariant = GoldfishCard,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    error = Color(0xFFCF6679)
)

// Custom Typography mit etwas größeren Defaults — Goldfish-User mag größere Schrift
private val GoldfishTypography = Typography(
    titleLarge = Typography().titleLarge.copy(fontSize = 24.sp),
    titleMedium = Typography().titleMedium.copy(fontSize = 18.sp),
    titleSmall = Typography().titleSmall.copy(fontSize = 16.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 18.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 16.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 14.sp),
    labelLarge = Typography().labelLarge.copy(fontSize = 16.sp),
    labelMedium = Typography().labelMedium.copy(fontSize = 14.sp),
    labelSmall = Typography().labelSmall.copy(fontSize = 12.sp)
)

@Composable
fun GoldfishTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = GoldfishTypography,
        content = content
    )
}

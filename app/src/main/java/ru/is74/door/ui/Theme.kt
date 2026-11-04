package ru.is74.door.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0A1019)
val Panel = Color(0xFF141E2B)
val Muted = Color(0xFF8B9BAE)
val Mint = Color(0xFF76E5CC)
val Blue = Color(0xFF83B8FF)
val Amber = Color(0xFFFFCD86)

@Composable
fun DoorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint, onPrimary = Ink, primaryContainer = Color(0xFF193D38),
            secondary = Blue, background = Ink, surface = Panel,
            surfaceVariant = Color(0xFF1C2A3A), onSurface = Color(0xFFF1F5FA),
            onSurfaceVariant = Muted, outline = Color(0xFF304154), error = Color(0xFFFF9B98)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize=32.sp, fontWeight=FontWeight.Bold, letterSpacing=(-1).sp),
            headlineMedium = TextStyle(fontSize=26.sp, fontWeight=FontWeight.Bold, letterSpacing=(-0.6).sp),
            titleLarge = TextStyle(fontSize=21.sp, fontWeight=FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize=16.sp, fontWeight=FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize=16.sp, lineHeight=24.sp),
            bodyMedium = TextStyle(fontSize=14.sp, lineHeight=20.sp),
            labelLarge = TextStyle(fontSize=14.sp, fontWeight=FontWeight.SemiBold),
            labelSmall = TextStyle(fontSize=11.sp, fontWeight=FontWeight.Medium, letterSpacing=0.5.sp)
        ), content = content
    )
}

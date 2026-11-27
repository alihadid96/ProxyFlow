package com.proxyflow.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.proxyflow.app.data.ThemeMode

val DeepBlue = Color(0xFF2563EB)
val Cyan = Color(0xFF06B6D4)
val LightBg = Color(0xFFEFF6FF)
val DarkBg = Color(0xFF0B1220)
val DarkSurface = Color(0xFF111B2E)
val DarkSurface2 = Color(0xFF17233B)
val Success = Color(0xFF22C55E)
val Warning = Color(0xFFF59E0B)
val ErrorRed = Color(0xFFEF4444)

private val darkColors = darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Cyan,
    onSecondary = Color(0xFF00222A),
    secondaryContainer = Color(0xFF0E4A57),
    onSecondaryContainer = Color(0xFFCFFAFE),
    background = DarkBg,
    onBackground = Color(0xFFE2E8F0),
    surface = DarkSurface,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurface2,
    surfaceContainerHighest = Color(0xFF1E2B46),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
    error = ErrorRed,
)

private val lightColors = lightColorScheme(
    primary = DeepBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),
    background = LightBg,
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE0E9F8),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainer = Color(0xFFF5F9FF),
    surfaceContainerHigh = Color(0xFFEAF1FC),
    surfaceContainerHighest = Color(0xFFE0E9F8),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
)

private val base = Typography()
private val appTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

val LocalDarkTheme = staticCompositionLocalOf { true }

@Composable
fun ProxyFlowTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) darkColors else lightColors, typography = appTypography, content = content)
    }
}

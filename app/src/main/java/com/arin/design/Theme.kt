package com.arin.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arin.data.ThemeMode

/** Arin's palette: deep night blue, teal for action, violet for accents. */
object ArinColors {
    val Teal = Color(0xFF2EC4B6)
    val TealDeep = Color(0xFF0E9F92)
    val Violet = Color(0xFF8B7CF6)
    val Night = Color(0xFF0E1117)
}

/** Colours Material doesn't have a slot for. */
@Immutable
data class ArinExtras(
    val userBubble: Color,
    val onUserBubble: Color,
    val codeBackground: Color,
    val subtle: Color,
)

val LocalArinExtras = staticCompositionLocalOf {
    ArinExtras(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

private val Dark = darkColorScheme(
    primary = ArinColors.Teal,
    onPrimary = Color(0xFF03211F),
    primaryContainer = Color(0xFF123B3A),
    onPrimaryContainer = Color(0xFFBDF4EE),
    secondary = ArinColors.Violet,
    onSecondary = Color(0xFF16104A),
    background = ArinColors.Night,
    onBackground = Color(0xFFE6EDF3),
    surface = ArinColors.Night,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C222B),
    onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainer = Color(0xFF151A22),
    surfaceContainerHigh = Color(0xFF1C222B),
    surfaceContainerHighest = Color(0xFF242B35),
    outline = Color(0xFF39424F),
    outlineVariant = Color(0xFF262E39),
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = ArinColors.TealDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5F1),
    onPrimaryContainer = Color(0xFF00332F),
    secondary = Color(0xFF6A5AE0),
    onSecondary = Color.White,
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF6F8FA),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE9EEF2),
    onSurfaceVariant = Color(0xFF5B6574),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F3F6),
    surfaceContainerHighest = Color(0xFFE6EBEF),
    outline = Color(0xFFC5CDD6),
    outlineVariant = Color(0xFFDDE3E9),
    error = Color(0xFFC62828),
)

private val DarkExtras = ArinExtras(
    userBubble = Color(0xFF173B3D),
    onUserBubble = Color(0xFFE6FBF8),
    codeBackground = Color(0xFF161B22),
    subtle = Color(0xFF6E7887),
)

private val LightExtras = ArinExtras(
    userBubble = Color(0xFFD7F3EF),
    onUserBubble = Color(0xFF0B2E2B),
    codeBackground = Color(0xFFEEF2F5),
    subtle = Color(0xFF8792A2),
)

private val ArinShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun ArinTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(LocalArinExtras provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = ArinShapes, content = content)
    }
}

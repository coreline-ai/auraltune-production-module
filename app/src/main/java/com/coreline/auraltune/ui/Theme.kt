// Theme.kt
// Material3 light/dark color schemes for AuralTune. Dynamic color is intentionally
// disabled so the catalog/results UI looks the same on every OEM skin during MVP.
package com.coreline.auraltune.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6EF6),
    onPrimary = Color.White,
    secondary = Color(0xFF5E5CE6),
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E7EB),
    onSurfaceVariant = Color(0xFF374151),
    error = Color(0xFFB00020),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AA2FF),
    onPrimary = Color(0xFF002B6B),
    secondary = Color(0xFF8E8AFF),
    onSecondary = Color(0xFF1A1947),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFB0B5BD),
    error = Color(0xFFCF6679),
    onError = Color.Black,
)

/** Top-level theme composable. Use [androidx.compose.material3.MaterialTheme] only. */
@Composable
fun AuralTuneTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

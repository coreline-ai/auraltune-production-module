// Theme.kt
// Material3 light/dark color schemes for AuralTune. Dynamic color is intentionally
// disabled so the catalog/results UI looks the same on every OEM skin during MVP.
package com.coreline.auraltune.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AuralTuneCyan = Color(0xFF00F4FE)
val AuralTuneCyanSoft = Color(0xFF63F7FF)
val AuralTuneBlue = Color(0xFF648AFF)
val AuralTunePanel = Color(0xFF201F21)
val AuralTunePanelHigh = Color(0xFF2A2A2C)
val AuralTunePanelHighest = Color(0xFF353437)
val AuralTuneSlot = Color(0xFF0E0E10)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C4FF),
    onPrimary = Color(0xFF00297B),
    primaryContainer = AuralTuneBlue,
    onPrimaryContainer = Color(0xFF00236C),
    secondary = Color(0xFFE6FEFF),
    onSecondary = Color(0xFF003739),
    secondaryContainer = AuralTuneCyan,
    onSecondaryContainer = Color(0xFF006C71),
    tertiary = Color(0xFFFFB3AC),
    onTertiary = Color(0xFF680008),
    tertiaryContainer = Color(0xFFFF544E),
    onTertiaryContainer = Color(0xFF5C0006),
    background = Color(0xFF131315),
    onBackground = Color(0xFFE5E1E4),
    surface = Color(0xFF131315),
    onSurface = Color(0xFFE5E1E4),
    surfaceVariant = AuralTunePanelHighest,
    onSurfaceVariant = Color(0xFFC3C5D7),
    surfaceContainerLowest = AuralTuneSlot,
    surfaceContainerLow = Color(0xFF1C1B1D),
    surfaceContainer = AuralTunePanel,
    surfaceContainerHigh = AuralTunePanelHigh,
    surfaceContainerHighest = AuralTunePanelHighest,
    outline = Color(0xFF8D90A0),
    outlineVariant = Color(0xFF434655),
    inverseSurface = Color(0xFFE5E1E4),
    inverseOnSurface = Color(0xFF313032),
    inversePrimary = Color(0xFF0F52DB),
    surfaceTint = Color(0xFFB5C4FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AuralTuneTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.8.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
)

private val AuralTuneShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
)

/** Top-level theme composable. Use [androidx.compose.material3.MaterialTheme] only. */
@Composable
fun AuralTuneTheme(
    @Suppress("UNUSED_PARAMETER")
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The design handoff is a dark, hardware-inspired studio surface. Keep it stable
    // across OEM light/dark system settings during this product phase.
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AuralTuneTypography,
        shapes = AuralTuneShapes,
        content = content,
    )
}

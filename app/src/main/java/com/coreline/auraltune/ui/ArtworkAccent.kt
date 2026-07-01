// ArtworkAccent.kt
// Derives a player accent colour from the current track's cover art (Palette) so the seek slider
// and transport controls take on the album's colour, animating on track change. Always falls back
// to a theme colour when there's no art, extraction fails, or the colour is too dark to read on the
// dark player surface.
package com.coreline.auraltune.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.coreline.auraltune.R
import com.coreline.auraltune.audio.AlbumArtCache
import com.coreline.auraltune.audio.TrackMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Dark colours are LIFTED to at least this HSL lightness so the accent reads on the dark player
// surface without discarding the cover's hue. (The old hard relative-luminance cutoff dropped many
// common saturated colours — deep blue/red/purple all sit below ~0.20 luminance.)
private const val MIN_ACCENT_LIGHTNESS = 0.55f

@Volatile private var defaultCoverBitmap: Bitmap? = null

/**
 * Accent colour derived from [artwork] (off the main thread), animated on change. Robust across
 * covers: broad swatch candidates (③), dark colours lifted rather than rejected (②), and tracks
 * with no embedded art use the bundled default cover's colour (①) instead of the theme.
 * Only genuinely undecodable art / swatch-less images return [fallback].
 */
@Composable
fun rememberArtworkAccent(artwork: Bitmap?, fallback: Color): Color {
    val context = LocalContext.current
    val target by produceState(fallback, artwork) {
        value = withContext(Dispatchers.Default) {
            val bmp = artwork ?: defaultCover(context) // ① no embedded art → default cover colour
            if (bmp == null) fallback
            else runCatching { pickAccent(Palette.from(bmp).generate()) }.getOrNull() ?: fallback
        }
    }
    return animateColorAsState(target, tween(durationMillis = 500), label = "artwork-accent").value
}

/** ③ broadened candidate order, then ② lift dark colours. Null only if the image has no swatches. */
private fun pickAccent(palette: Palette): Color? {
    val swatch = palette.vibrantSwatch
        ?: palette.lightVibrantSwatch
        ?: palette.darkVibrantSwatch
        ?: palette.mutedSwatch
        ?: palette.lightMutedSwatch
        ?: palette.darkMutedSwatch
        ?: palette.dominantSwatch
        ?: return null
    return Color(swatch.rgb).copy(alpha = 1f).liftForDarkSurface()
}

/** Raise a too-dark colour's HSL lightness (keeping hue/saturation) so it reads on the dark surface. */
private fun Color.liftForDarkSurface(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    if (hsl[2] < MIN_ACCENT_LIGHTNESS) hsl[2] = MIN_ACCENT_LIGHTNESS
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Decode the bundled default cover once (cached) for tracks without embedded art (①). */
private fun defaultCover(context: Context): Bitmap? {
    defaultCoverBitmap?.let { return it }
    return runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.default_album_art)
    }.getOrNull()?.also { defaultCoverBitmap = it }
}

/** Legible content colour (black/white) for text/icons drawn on top of [accent]. */
fun contentColorOn(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

/**
 * Per-track queue-row metadata (cover + artist + album), decoded/cached by [AlbumArtCache].
 * Returns cached metadata immediately if present, else [TrackMeta.EMPTY] until the off-thread
 * resolve completes. Keyed on [uri].
 */
@Composable
fun rememberTrackMeta(cache: AlbumArtCache, uri: Uri): TrackMeta {
    val meta by produceState(cache.peek(uri) ?: TrackMeta.EMPTY, uri) {
        value = cache.get(uri)
    }
    return meta
}

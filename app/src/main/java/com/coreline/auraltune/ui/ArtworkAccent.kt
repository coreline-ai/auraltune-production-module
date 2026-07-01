// ArtworkAccent.kt
// Derives a player accent colour from the current track's cover art (Palette) so the seek slider
// and transport controls take on the album's colour, animating on track change. Always falls back
// to a theme colour when there's no art, extraction fails, or the colour is too dark to read on the
// dark player surface.
package com.coreline.auraltune.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import com.coreline.auraltune.audio.AlbumArtCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MIN_ACCENT_LUMINANCE = 0.20f

/**
 * Accent colour extracted from [artwork] (off the main thread), animated on change.
 * Returns [fallback] when there is no art, Palette fails, or the colour is too dark for the
 * dark player surface. Keyed on [artwork] so it recomputes per track only.
 */
@Composable
fun rememberArtworkAccent(artwork: Bitmap?, fallback: Color): Color {
    val target by produceState(fallback, artwork) {
        value = if (artwork == null) {
            fallback
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    val palette = Palette.from(artwork).generate()
                    val swatch = palette.vibrantSwatch
                        ?: palette.lightVibrantSwatch
                        ?: palette.dominantSwatch
                    swatch?.let { Color(it.rgb).copy(alpha = 1f) }
                }.getOrNull()
                    ?.takeIf { it.luminance() >= MIN_ACCENT_LUMINANCE }
                    ?: fallback
            }
        }
    }
    return animateColorAsState(target, tween(durationMillis = 500), label = "artwork-accent").value
}

/** Legible content colour (black/white) for text/icons drawn on top of [accent]. */
fun contentColorOn(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

/**
 * Per-track cover thumbnail for a queue row, decoded/cached by [AlbumArtCache]. Returns the cached
 * bitmap immediately if present, otherwise null until the off-thread decode completes. Keyed on [uri].
 */
@Composable
fun rememberTrackArtwork(cache: AlbumArtCache, uri: Uri): Bitmap? {
    val art by produceState(cache.peek(uri), uri) {
        value = cache.get(uri)
    }
    return art
}

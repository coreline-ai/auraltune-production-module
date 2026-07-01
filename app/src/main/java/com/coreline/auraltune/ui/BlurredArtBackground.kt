// BlurredArtBackground.kt
// Premium blurred backdrop for the player's now-playing section: draws the current track's cover
// (or the bundled default cover) heavily blurred behind a dark scrim so the foreground stays legible
// over ANY album art. The source bitmap is intentionally small (see ArtworkDecoder) so the upscale
// itself reads as soft; Modifier.blur adds a true Gaussian pass on API 31+ (no-op below, still soft).
package com.coreline.auraltune.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.coreline.auraltune.R

/** Heavy blur so the backdrop reads as an ambient colour wash, not a legible thumbnail. */
private val BlurRadius = 28.dp

/**
 * Wraps [content] over a blurred album-art backdrop.
 *
 * The [Box] is sized by [content]; the backdrop + scrim [matchParentSize] behind it. Clipping to a
 * rounded shape is the caller's job (the enclosing Card already clips to `shapes.large`).
 */
@Composable
fun BlurredArtBackground(
    artwork: Bitmap?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        // Backdrop layer — crossfades when the track (and thus the art) changes.
        Crossfade(
            targetState = artwork,
            animationSpec = tween(durationMillis = 450),
            modifier = Modifier.matchParentSize(),
            label = "player-artwork",
        ) { art ->
            val painter = if (art != null) {
                remember(art) { BitmapPainter(art.asImageBitmap(), filterQuality = FilterQuality.Low) }
            } else {
                // No embedded cover → bundled default album art (gold mic).
                painterResource(R.drawable.default_album_art)
            }
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(BlurRadius),
            )
        }
        // Dark scrim for legibility over arbitrary album art (darker toward the lower text/controls).
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.72f)),
                ),
            ),
        )
        content()
    }
}

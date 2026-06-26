// EqGraphView.kt
// Phase G2 — frequency-response graph (Canvas).
// Draws the COMPOSITE curve = Manual 20-band (graphic EQ) + AutoEQ profile filters,
// computed entirely in Kotlin via BiquadResponse (no native call). The AutoEQ dashed
// curve is overlaid so the user can see the headphone correction separately.
package com.coreline.auraltune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.coreline.audio.EqFilterType
import com.coreline.auraltune.audio.eq.BiquadResponse
import com.coreline.auraltune.audio.eq.BiquadSpec
import com.coreline.auraltune.audio.eq.BiquadType
import com.coreline.autoeq.model.AutoEqFilter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

internal const val GRAPH_MIN_HZ = 20.0
internal const val GRAPH_MAX_HZ = 20_000.0
private const val MIN_HZ = GRAPH_MIN_HZ
private const val MAX_HZ = GRAPH_MAX_HZ
private const val MIN_GRAPH_DB = 15.0    // minimum y-axis half-range (±15 dB)
internal const val GRAPH_SAMPLE_RATE = 48_000.0
private const val CURVE_POINTS = 180

/** Map an AutoEQ profile filter to the graph's BiquadSpec (enum names align 1:1). */
internal fun AutoEqFilter.toBiquadSpec(): BiquadSpec = BiquadSpec(
    type = when (type) {
        EqFilterType.PEAKING -> BiquadType.PEAKING
        EqFilterType.LOW_SHELF -> BiquadType.LOW_SHELF
        EqFilterType.HIGH_SHELF -> BiquadType.HIGH_SHELF
        EqFilterType.HIGH_PASS -> BiquadType.HIGH_PASS
    },
    freqHz = frequency,
    gainDb = gainDB.toDouble(),
    q = q,
)

@Composable
fun EqGraphView(
    manualSpecs: List<BiquadSpec>,
    autoEqFilters: List<AutoEqFilter>,
    modifier: Modifier = Modifier,
    /** Sample rate the curve is computed at — should match the engine's live rate so graph == sound. */
    sampleRate: Double = GRAPH_SAMPLE_RATE,
    /** Active profile preamp (dB, usually negative). Drawn as a horizontal reference line. */
    preampDb: Float = 0f,
    /** Whether to overlay the preamp reference line (marker). */
    showPreamp: Boolean = false,
    /**
     * Whether the preamp gain is actually in the signal path (correction + preamp
     * both on). When true the response curves are shifted DOWN by [preampDb] so the
     * graph reflects the real output level (curves settle onto the preamp marker line).
     */
    preampApplied: Boolean = false,
) {
    // Log-spaced sample frequencies (stable across recompositions).
    val freqs = remember {
        DoubleArray(CURVE_POINTS) { i ->
            MIN_HZ * (MAX_HZ / MIN_HZ).pow(i.toDouble() / (CURVE_POINTS - 1))
        }
    }

    val autoSpecs = remember(autoEqFilters) { autoEqFilters.map { it.toBiquadSpec() } }

    val composite = remember(manualSpecs, autoSpecs, sampleRate) {
        BiquadResponse.compositeDb(freqs, manualSpecs + autoSpecs, sampleRate)
    }
    val autoCurve = remember(autoSpecs, sampleRate) {
        if (autoSpecs.isEmpty()) null else BiquadResponse.compositeDb(freqs, autoSpecs, sampleRate)
    }
    val curveShift = if (preampApplied) preampDb.toDouble() else 0.0
    val graphMaxDb = remember(composite, autoCurve, preampDb, showPreamp, curveShift) {
        val compositeMax = composite.maxOfOrNull { abs(it + curveShift) } ?: 0.0
        val autoMax = autoCurve?.maxOfOrNull { abs(it + curveShift) } ?: 0.0
        val preampMax = if (showPreamp) abs(preampDb.toDouble()) else 0.0
        val curveMax = maxOf(compositeMax, autoMax, preampMax)
        maxOf(MIN_GRAPH_DB, ceil((curveMax + 1.5) / 5.0) * 5.0)
    }
    val gridDbValues = remember(graphMaxDb) {
        val max = graphMaxDb.toInt()
        (-max..max step 5).filter { it != 0 }.map { it.toDouble() }
    }

    val colorScheme = MaterialTheme.colorScheme
    val graphBackground = colorScheme.surfaceContainerLowest
    val gridColor = colorScheme.outlineVariant.copy(alpha = 0.36f)
    val zeroColor = colorScheme.outline.copy(alpha = 0.55f)
    val autoColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    val compositeColor = colorScheme.secondaryContainer
    val preampColor = Color(0xFFFF6D00) // orange — distinct from blue composite / gray AutoEQ

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(horizontal = 4.dp),
    ) {
        val w = size.width
        val h = size.height
        val leftPad = 8f
        val rightPad = 8f
        val topPad = 18f
        val bottomPad = 18f
        val plotLeft = leftPad
        val plotRight = (w - rightPad).coerceAtLeast(plotLeft + 1f)
        val plotTop = topPad
        val plotBottom = (h - bottomPad).coerceAtLeast(plotTop + 1f)
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        drawRect(graphBackground)

        fun xOf(freqHz: Double): Float {
            val t = (log10(freqHz) - log10(MIN_HZ)) / (log10(MAX_HZ) - log10(MIN_HZ))
            return plotLeft + (t * plotWidth).toFloat()
        }
        fun yOf(db: Double): Float {
            val clamped = db.coerceIn(-graphMaxDb, graphMaxDb)
            return plotTop + (plotHeight * (0.5 - clamped / (2 * graphMaxDb))).toFloat()
        }

        // Vertical grid at decade marks (100, 1k, 10k) + octave-ish helpers.
        for (f in listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)) {
            val x = xOf(f)
            drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
        }
        // Horizontal dB grid lines every 5 dB.
        for (db in gridDbValues) {
            val y = yOf(db)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
        }
        // 0 dB baseline.
        drawLine(zeroColor, Offset(plotLeft, yOf(0.0)), Offset(plotRight, yOf(0.0)), strokeWidth = 2f)

        // Preamp reference line (orange dashed, horizontal) at the profile's preamp level.
        if (showPreamp && kotlin.math.abs(preampDb) > 0.05f) {
            val y = yOf(preampDb.toDouble())
            drawLine(
                preampColor,
                Offset(plotLeft, y),
                Offset(plotRight, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f)),
            )
        }

        // When the preamp is actually applied, the entire output is attenuated by
        // preampDb (frequency-independent) → shift the curves DOWN in parallel so
        // the graph matches what is heard (flat regions settle onto the preamp line).
        fun yOfShifted(db: Double): Float = yOf(db + curveShift)

        // AutoEQ-only curve (dashed) when a profile is active.
        autoCurve?.let { curve ->
            drawCurve(freqs, curve, autoColor, ::xOf, ::yOfShifted, dashed = true)
        }
        // Composite curve (solid) — what the user effectively hears.
        drawCurve(freqs, composite, compositeColor, ::xOf, ::yOfShifted, dashed = false)
    }
}

private fun DrawScope.drawCurve(
    freqs: DoubleArray,
    curveDb: DoubleArray,
    color: Color,
    xOf: (Double) -> Float,
    yOf: (Double) -> Float,
    dashed: Boolean,
) {
    val path = Path()
    for (i in freqs.indices) {
        val x = xOf(freqs[i])
        val y = yOf(curveDb[i])
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = if (dashed) 2f else 3f,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null,
        ),
    )
}

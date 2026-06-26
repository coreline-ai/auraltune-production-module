package com.coreline.auraltune.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpectrumAnalyzerTest {

    @Test
    fun computeBandEdges_areMonotonicAndStayBelowDisplayCeiling() {
        val bands = 12
        val lo = IntArray(bands)
        val hi = IntArray(bands)

        SpectrumAnalyzerMath.computeBandEdges(lo, hi, sampleRate = 48_000, fftSize = 2048)

        var previousHi = 0
        for (i in 0 until bands) {
            assertTrue("low bin must be positive", lo[i] >= 1)
            assertTrue("high bin must cover low bin", hi[i] >= lo[i])
            assertTrue("bands must move upward", lo[i] >= previousHi || i == 0)
            previousHi = hi[i]
        }
        val lastHz = hi.last() * 48_000.0 / 2048.0
        assertTrue("visualizer caps display bands near 18 kHz", lastHz <= 18_000.0 + 48_000.0 / 2048.0)
    }

    @Test
    fun fft_placesPureToneEnergyInExpectedBin() {
        val n = 1024
        val toneBin = 16
        val re = FloatArray(n) { i -> sin(2.0 * PI * toneBin * i / n).toFloat() }
        val im = FloatArray(n)

        SpectrumAnalyzerMath.fft(re, im)

        var maxBin = 1
        var maxMag = 0.0
        for (bin in 1 until n / 2) {
            val mag = sqrt((re[bin] * re[bin] + im[bin] * im[bin]).toDouble())
            if (mag > maxMag) {
                maxMag = mag
                maxBin = bin
            }
        }
        assertEquals(toneBin, maxBin)
        assertTrue("tone bin should dominate the spectrum", maxMag > n / 3.0)
    }
}

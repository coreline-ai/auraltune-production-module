package com.coreline.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kotlin <-> C++ lockstep contract for [PcmFormat].
 *
 * [PcmFormat.nativeId] MUST equal `auraltune::PcmFormat` in core/PcmConvert.h
 * (S16=0, S24=1, S32=2, F32=3) and [PcmFormat.bytesPerSample] MUST equal
 * `auraltune::bytesPerSample()` (2/3/4/4). If either drifts, the format-agnostic
 * I/O path (nativeProcessFormatted capacity math + decode/encode dispatch) would
 * silently mis-size buffers or pick the wrong codec. This test pins both sides.
 */
class PcmFormatContractTest {

    @Test
    fun `nativeId matches the C++ PcmFormat enum values`() {
        assertEquals(0, PcmFormat.S16.nativeId)
        assertEquals(1, PcmFormat.S24.nativeId)
        assertEquals(2, PcmFormat.S32.nativeId)
        assertEquals(3, PcmFormat.F32.nativeId)
    }

    @Test
    fun `bytesPerSample matches the C++ bytesPerSample contract`() {
        assertEquals(2, PcmFormat.S16.bytesPerSample)
        assertEquals(3, PcmFormat.S24.bytesPerSample)
        assertEquals(4, PcmFormat.S32.bytesPerSample)
        assertEquals(4, PcmFormat.F32.bytesPerSample)
    }

    @Test
    fun `enum ordinal equals nativeId for every entry`() {
        // The native dispatch relies on the Kotlin ordinal lining up with the
        // C++ enum int; assert no reordering has crept in.
        PcmFormat.values().forEach { f ->
            assertEquals("nativeId must equal ordinal for $f", f.ordinal, f.nativeId)
        }
    }

    @Test
    fun `exactly four formats are defined`() {
        assertEquals(4, PcmFormat.values().size)
    }
}

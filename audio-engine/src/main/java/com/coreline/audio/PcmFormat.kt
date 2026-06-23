// PcmFormat.kt
// PCM sample formats the engine can accept/emit at the I/O boundary. The DSP core is always
// float32; this only selects how bytes are decoded/encoded around it. So the .so supports any
// common bit depth at any sample rate (8 kHz–384 kHz) — the host pins whatever it needs.
//
// [nativeId] is the Kotlin<->C++ lockstep contract: it MUST equal `auraltune::PcmFormat` in
// core/PcmConvert.h (S16=0, S24=1, S32=2, F32=3). [bytesPerSample] is per single channel sample.
package com.coreline.audio

enum class PcmFormat(val nativeId: Int, val bytesPerSample: Int) {
    /** 16-bit signed little-endian. */
    S16(0, 2),

    /** 24-bit signed little-endian, PACKED 3 bytes per sample (not 24-in-32). */
    S24(1, 3),

    /** 32-bit signed little-endian. */
    S32(2, 4),

    /** 32-bit IEEE float (passthrough to/from the DSP core). */
    F32(3, 4),
}

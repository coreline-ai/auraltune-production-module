// PcmConvert.h
// JNI-free, header-only PCM format <-> float32 conversion for the engine's format-agnostic I/O.
//
// The DSP core always operates in float32 (highest practical precision for biquads). This layer
// lets the .so ACCEPT and EMIT any common PCM bit depth without changing the DSP: decode any
// format to float32, process, encode to any format. Little-endian (Android native byte order).
//
// Formats (enum values are the Kotlin<->C++ lockstep contract — see AudioEngine.PcmFormat):
//   S16 = 0  : 16-bit signed int, 2 bytes/sample
//   S24 = 1  : 24-bit signed int, PACKED 3 bytes/sample (little-endian)
//   S32 = 2  : 32-bit signed int, 4 bytes/sample
//   F32 = 3  : 32-bit float,      4 bytes/sample (passthrough)
#ifndef AURALTUNE_PCM_CONVERT_H
#define AURALTUNE_PCM_CONVERT_H

#include <cstdint>
#include <cstring>
#include <cmath>

namespace auraltune {

enum class PcmFormat : int {
    S16 = 0,
    S24 = 1,
    S32 = 2,
    F32 = 3,
};

inline bool isValidPcmFormat(int v) { return v >= 0 && v <= 3; }

inline int bytesPerSample(PcmFormat f) {
    switch (f) {
        case PcmFormat::S16: return 2;
        case PcmFormat::S24: return 3;
        case PcmFormat::S32: return 4;
        case PcmFormat::F32: return 4;
    }
    return 0;
}

// Clamp helper to a signed range.
inline int64_t clampI64(int64_t v, int64_t lo, int64_t hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

/** Decode [samples] interleaved samples from [in] (format [f]) into float32 [out] in [-1, 1). */
inline void decodeToFloat(const uint8_t* in, PcmFormat f, float* out, int samples) {
    switch (f) {
        case PcmFormat::S16: {
            for (int i = 0; i < samples; ++i) {
                int16_t s;
                std::memcpy(&s, in + i * 2, 2);
                out[i] = static_cast<float>(s) / 32768.0f;
            }
            break;
        }
        case PcmFormat::S24: {
            for (int i = 0; i < samples; ++i) {
                const uint8_t* p = in + i * 3;
                int32_t v = static_cast<int32_t>(p[0]) |
                            (static_cast<int32_t>(p[1]) << 8) |
                            (static_cast<int32_t>(p[2]) << 16);
                if (v & 0x800000) v |= ~0xFFFFFF; // sign-extend 24->32
                out[i] = static_cast<float>(v) / 8388608.0f; // 2^23
            }
            break;
        }
        case PcmFormat::S32: {
            for (int i = 0; i < samples; ++i) {
                int32_t s;
                std::memcpy(&s, in + i * 4, 4);
                out[i] = static_cast<float>(static_cast<double>(s) / 2147483648.0); // 2^31
            }
            break;
        }
        case PcmFormat::F32: {
            std::memcpy(out, in, static_cast<size_t>(samples) * 4);
            break;
        }
    }
}

/** Encode float32 [in] into [out] (format [f]), clamping to the target range. */
inline void encodeFromFloat(const float* in, uint8_t* out, PcmFormat f, int samples) {
    switch (f) {
        case PcmFormat::S16: {
            for (int i = 0; i < samples; ++i) {
                int64_t v = static_cast<int64_t>(std::lround(in[i] * 32768.0f));
                v = clampI64(v, -32768, 32767);
                int16_t s = static_cast<int16_t>(v);
                std::memcpy(out + i * 2, &s, 2);
            }
            break;
        }
        case PcmFormat::S24: {
            for (int i = 0; i < samples; ++i) {
                int64_t v = static_cast<int64_t>(std::lround(in[i] * 8388608.0f));
                v = clampI64(v, -8388608, 8388607); // [-2^23, 2^23-1]
                out[i * 3 + 0] = static_cast<uint8_t>(v & 0xFF);
                out[i * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
                out[i * 3 + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
            }
            break;
        }
        case PcmFormat::S32: {
            for (int i = 0; i < samples; ++i) {
                int64_t v = static_cast<int64_t>(std::llround(static_cast<double>(in[i]) * 2147483648.0));
                v = clampI64(v, -2147483648LL, 2147483647LL);
                int32_t s = static_cast<int32_t>(v);
                std::memcpy(out + i * 4, &s, 4);
            }
            break;
        }
        case PcmFormat::F32: {
            std::memcpy(out, in, static_cast<size_t>(samples) * 4);
            break;
        }
    }
}

}  // namespace auraltune

#endif  // AURALTUNE_PCM_CONVERT_H

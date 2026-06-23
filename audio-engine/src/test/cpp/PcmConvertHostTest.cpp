// PcmConvertHostTest.cpp
// Adversarial host test for core/PcmConvert.h (header-only, JNI-free).
// Compile with NDK base clang++ (targets host) or g++ -std=c++17. No android/log.h needed.
#include "../../main/cpp/core/PcmConvert.h"

#include <cstdio>
#include <cmath>
#include <cstring>
#include <cstdint>

using namespace auraltune;

static int g_pass = 0;
static int g_fail = 0;

static void check(bool cond, const char* label, double got, double expect) {
    if (cond) { ++g_pass; std::printf("  PASS  %-45s got=%.10g expect=%.10g\n", label, got, expect); }
    else      { ++g_fail; std::printf("  FAIL  %-45s got=%.10g expect=%.10g\n", label, got, expect); }
}

static void checkBytes(bool cond, const char* label, unsigned b0, unsigned b1, unsigned b2) {
    if (cond) { ++g_pass; std::printf("  PASS  %-45s bytes={%02X,%02X,%02X}\n", label, b0, b1, b2); }
    else      { ++g_fail; std::printf("  FAIL  %-45s bytes={%02X,%02X,%02X}\n", label, b0, b1, b2); }
}

int main() {
    std::printf("=== B. PcmConvert.h adversarial host test ===\n");

    // ---- B1. ROUND-TRIP ----
    std::printf("\n[B1] ROUND-TRIP encode(decode(x)) within quantization step\n");
    {
        // S16: build all-bit-pattern-ish set of float values, encode then decode, compare.
        const float xs[] = {0.0f, 0.5f, -0.5f, 0.25f, -0.25f, 0.999969f, -1.0f, 0.123456f, -0.777f, 0.000031f};
        const int n = (int)(sizeof(xs)/sizeof(xs[0]));
        // S16 step = 1/32768
        {
            float maxErr = 0.f;
            for (int i = 0; i < n; ++i) {
                uint8_t buf[2]; float back;
                encodeFromFloat(&xs[i], buf, PcmFormat::S16, 1);
                decodeToFloat(buf, PcmFormat::S16, &back, 1);
                float e = std::fabs(back - xs[i]);
                if (e > maxErr) maxErr = e;
            }
            check(maxErr <= 1.0f/32768.0f + 1e-9f, "S16 round-trip max err <= 1/32768", maxErr, 1.0/32768.0);
        }
        // S24 step = 1/8388608
        {
            float maxErr = 0.f;
            for (int i = 0; i < n; ++i) {
                uint8_t buf[3]; float back;
                encodeFromFloat(&xs[i], buf, PcmFormat::S24, 1);
                decodeToFloat(buf, PcmFormat::S24, &back, 1);
                float e = std::fabs(back - xs[i]);
                if (e > maxErr) maxErr = e;
            }
            check(maxErr <= 1.0f/8388608.0f + 1e-9f, "S24 round-trip max err <= 1/8388608", maxErr, 1.0/8388608.0);
        }
        // S32 step = 1/2147483648 (float precision dominates ~6e-8)
        {
            float maxErr = 0.f;
            for (int i = 0; i < n; ++i) {
                uint8_t buf[4]; float back;
                encodeFromFloat(&xs[i], buf, PcmFormat::S32, 1);
                decodeToFloat(buf, PcmFormat::S32, &back, 1);
                float e = std::fabs(back - xs[i]);
                if (e > maxErr) maxErr = e;
            }
            // S32 quant step is tiny; float32 mantissa (~1.2e-7 relative) is the real bound near 1.0.
            check(maxErr <= 1.2e-7f, "S32 round-trip max err <= float eps (~1.2e-7)", maxErr, 1.2e-7);
        }
        // F32 must be EXACT (bit-identical)
        {
            const float fx[] = {0.0f, -0.0f, 1.0f, -1.0f, 3.14159f, 1e-30f, 1e30f, -123.456f};
            const int fn = (int)(sizeof(fx)/sizeof(fx[0]));
            bool allExact = true;
            for (int i = 0; i < fn; ++i) {
                uint8_t buf[4]; float back;
                encodeFromFloat(&fx[i], buf, PcmFormat::F32, 1);
                decodeToFloat(buf, PcmFormat::F32, &back, 1);
                uint32_t a, b;
                std::memcpy(&a, &fx[i], 4); std::memcpy(&b, &back, 4);
                if (a != b) { allExact = false; }
            }
            check(allExact, "F32 round-trip BIT-IDENTICAL", allExact ? 1 : 0, 1);
        }
    }

    // ---- B2. CLAMP ----
    std::printf("\n[B2] CLAMP out-of-range floats to format min/max (no wrap)\n");
    {
        float hi = 2.0f, lo = -2.0f;
        // S16
        {
            uint8_t b[2]; int16_t s;
            encodeFromFloat(&hi, b, PcmFormat::S16, 1); std::memcpy(&s, b, 2);
            check(s == 32767, "+2.0 -> S16 == 32767", s, 32767);
            encodeFromFloat(&lo, b, PcmFormat::S16, 1); std::memcpy(&s, b, 2);
            check(s == -32768, "-2.0 -> S16 == -32768", s, -32768);
        }
        // S24 (packed) -> reconstruct signed value
        {
            uint8_t b[3];
            encodeFromFloat(&hi, b, PcmFormat::S24, 1);
            int32_t v = (int32_t)b[0] | ((int32_t)b[1]<<8) | ((int32_t)b[2]<<16);
            if (v & 0x800000) v |= ~0xFFFFFF;
            check(v == 8388607, "+2.0 -> S24 == 8388607", v, 8388607);
            encodeFromFloat(&lo, b, PcmFormat::S24, 1);
            v = (int32_t)b[0] | ((int32_t)b[1]<<8) | ((int32_t)b[2]<<16);
            if (v & 0x800000) v |= ~0xFFFFFF;
            check(v == -8388608, "-2.0 -> S24 == -8388608", v, -8388608);
        }
        // S32
        {
            uint8_t b[4]; int32_t s;
            encodeFromFloat(&hi, b, PcmFormat::S32, 1); std::memcpy(&s, b, 4);
            check(s == 2147483647, "+2.0 -> S32 == 2147483647", (double)s, 2147483647.0);
            encodeFromFloat(&lo, b, PcmFormat::S32, 1); std::memcpy(&s, b, 4);
            check(s == (int32_t)(-2147483647-1), "-2.0 -> S32 == -2147483648", (double)s, -2147483648.0);
        }
        // also test exactly +1.0 (a classic overflow trap: 1.0*32768 = 32768 needs clamp to 32767)
        {
            float one = 1.0f; uint8_t b[2]; int16_t s;
            encodeFromFloat(&one, b, PcmFormat::S16, 1); std::memcpy(&s, b, 2);
            check(s == 32767, "+1.0 -> S16 clamps to 32767 (not -32768 wrap)", s, 32767);
            uint8_t b3[3];
            encodeFromFloat(&one, b3, PcmFormat::S24, 1);
            int32_t v = (int32_t)b3[0] | ((int32_t)b3[1]<<8) | ((int32_t)b3[2]<<16);
            if (v & 0x800000) v |= ~0xFFFFFF;
            check(v == 8388607, "+1.0 -> S24 clamps to 8388607", v, 8388607);
        }
    }

    // ---- B3. S24 SIGN-EXTENSION ----
    std::printf("\n[B3] S24 sign-extension: encode -0.5 to S24, decode back ~ -0.5\n");
    {
        float neg = -0.5f; uint8_t b[3]; float back;
        encodeFromFloat(&neg, b, PcmFormat::S24, 1);
        // raw 24-bit value should be -0x400000 = 0xC00000 packed LE => {00,00,C0}
        decodeToFloat(b, PcmFormat::S24, &back, 1);
        check(std::fabs(back - (-0.5f)) <= 1.0f/8388608.0f, "S24 decode of -0.5 ~ -0.5 (not large +)", back, -0.5);
        check(back < 0.0f, "S24 decoded value is NEGATIVE (sign-extend worked)", back, -0.5);
        checkBytes(b[0]==0x00 && b[1]==0x00 && b[2]==0xC0, "S24 -0.5 packs LE {00,00,C0}", b[0], b[1], b[2]);
        // most-negative full-scale: 0x800000 must decode to -1.0 (top bit set)
        {
            uint8_t mn[3] = {0x00, 0x00, 0x80}; float v;
            decodeToFloat(mn, PcmFormat::S24, &v, 1);
            check(std::fabs(v - (-1.0f)) < 1e-6f, "S24 raw 0x800000 -> -1.0 (sign bit)", v, -1.0);
        }
    }

    // ---- B4. S24 PACKING ----
    std::printf("\n[B4] S24 packing: bytesPerSample==3 and LE byte order\n");
    {
        check(bytesPerSample(PcmFormat::S24) == 3, "bytesPerSample(S24) == 3", bytesPerSample(PcmFormat::S24), 3);
        check(bytesPerSample(PcmFormat::S16) == 2, "bytesPerSample(S16) == 2", bytesPerSample(PcmFormat::S16), 2);
        check(bytesPerSample(PcmFormat::S32) == 4, "bytesPerSample(S32) == 4", bytesPerSample(PcmFormat::S32), 4);
        check(bytesPerSample(PcmFormat::F32) == 4, "bytesPerSample(F32) == 4", bytesPerSample(PcmFormat::F32), 4);
        // +0.5 -> raw 0x400000 -> LE {00,00,40}
        float pos = 0.5f; uint8_t b[3];
        encodeFromFloat(&pos, b, PcmFormat::S24, 1);
        checkBytes(b[0]==0x00 && b[1]==0x00 && b[2]==0x40, "S24 +0.5 packs LE {00,00,40}", b[0], b[1], b[2]);
        // A value exercising all 3 bytes: raw 0x123456 = 1193046 -> /8388608
        {
            uint8_t mid[3] = {0x56, 0x34, 0x12}; float v;
            decodeToFloat(mid, PcmFormat::S24, &v, 1);
            double expect = 0x123456 / 8388608.0;
            check(std::fabs(v - (float)expect) < 1e-6f, "S24 {56,34,12} -> 0x123456/2^23", v, expect);
        }
    }

    // ---- B5. KNOWN VALUES (S16) ----
    std::printf("\n[B5] KNOWN VALUES (S16 LE)\n");
    {
        uint8_t pos[2] = {0x00, 0x40}; // 0x4000 = 16384
        uint8_t neg[2] = {0x00, 0xC0}; // 0xC000 = -16384
        float v;
        decodeToFloat(pos, PcmFormat::S16, &v, 1);
        check(std::fabs(v - 0.5f) < 1e-7f, "S16 {00,40}=16384 -> 0.5", v, 0.5);
        decodeToFloat(neg, PcmFormat::S16, &v, 1);
        check(std::fabs(v - (-0.5f)) < 1e-7f, "S16 {00,C0}=-16384 -> -0.5", v, -0.5);
    }

    std::printf("\n=== RESULT: %d passed, %d failed ===\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}

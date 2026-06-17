// KWeightingFilter.h
//
// ITU-R BS.1770 K-weighting pre-filter for loudness measurement.
//
// Two biquad stages in series, applied to a mono sidechain:
//   Stage 1 — High-shelf pre-emphasis (+4 dB at ~1.5 kHz, Q=1/√2)
//   Stage 2 — High-pass RLB (38 Hz Butterworth, Q=1/√2)
//
// Implemented as transposed direct-form II with scalar state. RT-safe:
// processSample() does no allocation and never throws.

#pragma once

#include "../BiquadFilter.h"   // BiquadCoeffs

namespace auraltune::audio {

class KWeightingFilter {
public:
    explicit KWeightingFilter(double sampleRate) noexcept;

    // Process one mono sample. Allocation-free. Returns the K-weighted sample.
    inline float processSample(float sample) noexcept {
        // Stage 1 — high-shelf (TDF-II direct form)
        const float y1 = static_cast<float>(s1_.b0) * sample + s1z1_;
        s1z1_ = static_cast<float>(s1_.b1) * sample - static_cast<float>(s1_.a1) * y1 + s1z2_;
        s1z2_ = static_cast<float>(s1_.b2) * sample - static_cast<float>(s1_.a2) * y1;

        // Stage 2 — high-pass
        const float y2 = static_cast<float>(s2_.b0) * y1 + s2z1_;
        s2z1_ = static_cast<float>(s2_.b1) * y1 - static_cast<float>(s2_.a1) * y2 + s2z2_;
        s2z2_ = static_cast<float>(s2_.b2) * y1 - static_cast<float>(s2_.a2) * y2;

        return y2;
    }

    void reset() noexcept {
        s1z1_ = 0.0f; s1z2_ = 0.0f;
        s2z1_ = 0.0f; s2z2_ = 0.0f;
    }

    // Test helpers — return the underlying coefficient sets so unit tests
    // can verify the cascade against published BS.1770 magnitudes.
    BiquadCoeffs stage1Coeffs() const noexcept { return s1_; }
    BiquadCoeffs stage2Coeffs() const noexcept { return s2_; }

private:
    BiquadCoeffs s1_;
    BiquadCoeffs s2_;
    float s1z1_ = 0.0f, s1z2_ = 0.0f;
    float s2z1_ = 0.0f, s2z2_ = 0.0f;
};

} // namespace auraltune::audio

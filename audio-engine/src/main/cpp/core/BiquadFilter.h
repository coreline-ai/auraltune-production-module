// BiquadFilter.h
//
// Stereo Direct-Form II Transposed (TDF2) biquad — STATELESS COEFFICIENTS.
//
// Threading contract (post P0-1 / P0-2 fix):
//   - This class only owns the per-channel delay state (z1L, z2L, z1R, z2R).
//   - Coefficients are passed in to processStereoInterleaved() by const reference;
//     they live in the engine's atomically-published EngineSnapshot.
//   - reset() may only be called from the audio thread (callback context) or
//     from a control thread that is provably not running concurrently with the
//     audio thread (e.g. construction, post-shutdown).
//
// The TDF2 difference equation we run on each sample x:
//     y  = b0 * x + s1
//     s1 = b1 * x - a1 * y + s2
//     s2 = b2 * x - a2 * y
// All coefficients are stored already normalized by a0 (see RBJ cookbook).

#pragma once

#include <cstdint>

namespace auraltune::audio {

struct BiquadCoeffs {
    // a0 has already been factored out (coeffs are in vDSP-style normalized form).
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;

    // Convenience: unity passthrough (used as Nyquist guard / inactive slot fill).
    static BiquadCoeffs unity() { return BiquadCoeffs{1.0, 0.0, 0.0, 0.0, 0.0}; }
};

class BiquadFilter {
public:
    BiquadFilter() = default;
    ~BiquadFilter() = default;

    // Non-copyable, non-movable to avoid accidental aliasing of delay state.
    BiquadFilter(const BiquadFilter&) = delete;
    BiquadFilter& operator=(const BiquadFilter&) = delete;
    BiquadFilter(BiquadFilter&&) = delete;
    BiquadFilter& operator=(BiquadFilter&&) = delete;

    // Audio-thread only.
    void reset() noexcept;
    void processStereoInterleaved(const BiquadCoeffs& c,
                                  float* data,
                                  int numFrames) noexcept;

private:
    // TDF2 delay state — per channel pair (stereo interleaved).
    double z1L_ = 0.0;
    double z2L_ = 0.0;
    double z1R_ = 0.0;
    double z2R_ = 0.0;
};

} // namespace auraltune::audio

// BiquadFilter.cpp
//
// Audio-thread-only TDF2 biquad. Coefficients are passed by const reference
// per call — the engine snapshot owns them and we just read one stable copy
// per cascade. This decouples coefficient publish (whole-snapshot atomic swap
// in AuralTuneEQEngine) from the per-section delay state owned here.

#include "BiquadFilter.h"

namespace auraltune::audio {

void BiquadFilter::reset() noexcept {
    z1L_ = 0.0;
    z2L_ = 0.0;
    z1R_ = 0.0;
    z2R_ = 0.0;
}

void BiquadFilter::processStereoInterleaved(const BiquadCoeffs& c,
                                            float* data,
                                            int numFrames) noexcept {
    if (numFrames <= 0 || data == nullptr) return;

    // Local copies into doubles so the inner loop runs in registers.
    const double b0 = c.b0;
    const double b1 = c.b1;
    const double b2 = c.b2;
    const double a1 = c.a1;
    const double a2 = c.a2;

    double z1L = z1L_;
    double z2L = z2L_;
    double z1R = z1R_;
    double z2R = z2R_;

    for (int i = 0; i < numFrames; ++i) {
        // Left channel.
        const double xL = static_cast<double>(data[2 * i]);
        const double yL = b0 * xL + z1L;
        z1L = b1 * xL - a1 * yL + z2L;
        z2L = b2 * xL - a2 * yL;
        data[2 * i] = static_cast<float>(yL);

        // Right channel.
        const double xR = static_cast<double>(data[2 * i + 1]);
        const double yR = b0 * xR + z1R;
        z1R = b1 * xR - a1 * yR + z2R;
        z2R = b2 * xR - a2 * yR;
        data[2 * i + 1] = static_cast<float>(yR);
    }

    z1L_ = z1L;
    z2L_ = z2L;
    z1R_ = z1R;
    z2R_ = z2R;
}

} // namespace auraltune::audio

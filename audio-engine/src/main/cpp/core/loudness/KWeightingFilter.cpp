// KWeightingFilter.cpp

#include "KWeightingFilter.h"
#include "../AuralTuneEQEngine.h"   // for static peakingCoeffs etc.

#include <cmath>

namespace auraltune::audio {

KWeightingFilter::KWeightingFilter(double sampleRate) noexcept {
    const double q = 1.0 / std::sqrt(2.0);
    // Stage 1: high-shelf +4 dB @ 1500 Hz
    s1_ = AuralTuneEQEngine::highShelfCoeffs(1500.0, 4.0f, q, sampleRate);
    // Stage 2: high-pass 38 Hz Butterworth (Q=1/√2)
    s2_ = AuralTuneEQEngine::highPassCoeffs(38.0, q, sampleRate);
}

} // namespace auraltune::audio

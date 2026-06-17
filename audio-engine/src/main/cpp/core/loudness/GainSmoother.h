// GainSmoother.h
//
// One-pole smoother with asymmetric attack/release time constants in dB.
// Ticks once per analysis hop. RT-safe.

#pragma once

#include "LoudnessEqualizerSettings.h"
#include "LoudnessDetector.h"   // for timeConstantCoeff

namespace auraltune::audio {

class GainSmoother {
public:
    GainSmoother(const LoudnessEqualizerSettings& settings, float /*sampleRate*/) noexcept
        : attackCoeff_(LoudnessDetector::timeConstantCoeff(
              settings.gainAttackMs, settings.analysisHopMs)),
          releaseCoeff_(LoudnessDetector::timeConstantCoeff(
              settings.gainReleaseMs, settings.analysisHopMs)) {}

    inline float process(float targetGainDb) noexcept {
        const float coeff = (targetGainDb < currentGainDb_) ? attackCoeff_ : releaseCoeff_;
        currentGainDb_ += coeff * (targetGainDb - currentGainDb_);
        return currentGainDb_;
    }

    void reset(float initialGainDb = 0.0f) noexcept { currentGainDb_ = initialGainDb; }
    float currentGainDb() const noexcept { return currentGainDb_; }

private:
    float attackCoeff_;
    float releaseCoeff_;
    float currentGainDb_ = 0.0f;
};

} // namespace auraltune::audio

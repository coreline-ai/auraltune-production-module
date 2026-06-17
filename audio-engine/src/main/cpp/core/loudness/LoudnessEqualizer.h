// LoudnessEqualizer.h
//
// ITU-R BS.1770 K-weighted auto-leveler. Integrates:
//   1. KWeightingFilter — sidechain pre-filter
//   2. LoudnessDetector — RMS detection + envelope smoothing (dB)
//   3. GainComputer     — target gain selection (boost + soft-knee cut)
//   4. GainSmoother     — gain envelope smoothing (asymmetric attack/release)
//
// Process input/output is interleaved Float32 stereo. Multi-channel
// extension can be added but stereo is sufficient for our pipeline.
//
// All state is single-thread-owned (the audio callback). Settings and
// sample-rate changes use the engine's snapshot publish pattern; this
// class does not own a snapshot itself.

#pragma once

#include "GainComputer.h"
#include "GainSmoother.h"
#include "KWeightingFilter.h"
#include "LoudnessDetector.h"
#include "LoudnessEqualizerSettings.h"

#include <cmath>

namespace auraltune::audio {

class LoudnessEqualizer {
public:
    LoudnessEqualizer(const LoudnessEqualizerSettings& settings,
                      float sampleRate) noexcept
        : settings_(settings),
          kFilter_(static_cast<double>(sampleRate)),
          detector_(settings, sampleRate),
          gainComputer_(settings),
          gainSmoother_(settings, sampleRate),
          currentLinearGain_(dbToLinear(gainSmoother_.currentGainDb())) {}

    bool isEnabled() const noexcept { return settings_.enabled; }

    // Process stereo interleaved Float32 in place.
    // RT-safe: no allocations, no logging.
    void processStereoInterleaved(float* pcm, int numFrames) noexcept {
        if (!settings_.enabled) return;
        float linearGain = currentLinearGain_;
        for (int f = 0; f < numFrames; ++f) {
            const int idx = f * 2;
            const float mono = (pcm[idx] + pcm[idx + 1]) * 0.5f;
            const float weighted = kFilter_.processSample(mono);
            const float newLevel = detector_.ingest(weighted);
            if (!std::isnan(newLevel)) {
                const float desired = gainComputer_.desiredGainDb(newLevel);
                const float smoothed = gainSmoother_.process(desired);
                linearGain = dbToLinear(smoothed);
                currentLinearGain_ = linearGain;
            }
            pcm[idx]     *= linearGain;
            pcm[idx + 1] *= linearGain;
        }
    }

    void reset() noexcept {
        kFilter_.reset();
        detector_.reset();
        gainSmoother_.reset();
        currentLinearGain_ = dbToLinear(gainSmoother_.currentGainDb());
    }

    static inline float dbToLinear(float db) noexcept {
        return std::pow(10.0f, db / 20.0f);
    }

private:
    LoudnessEqualizerSettings settings_;
    KWeightingFilter          kFilter_;
    LoudnessDetector          detector_;
    GainComputer              gainComputer_;
    GainSmoother              gainSmoother_;
    float                     currentLinearGain_ = 1.0f;
};

} // namespace auraltune::audio

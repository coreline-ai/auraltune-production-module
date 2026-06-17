// LoudnessDetector.h
//
// Ring-buffer RMS estimator + asymmetric attack/release envelope smoothing
// in the dB domain. RT-safe single-thread state.

#pragma once

#include "LoudnessEqualizerSettings.h"

#include <cmath>
#include <vector>

namespace auraltune::audio {

class LoudnessDetector {
public:
    LoudnessDetector(const LoudnessEqualizerSettings& settings,
                     float sampleRate) noexcept
        : settings_(settings), sampleRate_(sampleRate) {
        const int ws = static_cast<int>(settings.analysisWindowMs / 1000.0f * sampleRate);
        windowSamples_       = ws > 0 ? ws : 1;
        inverseWindowSamples_ = 1.0f / static_cast<float>(windowSamples_);
        const int hs = static_cast<int>(settings.analysisHopMs / 1000.0f * sampleRate);
        hopSamples_          = hs > 0 ? hs : 1;
        ringBuffer_.assign(static_cast<size_t>(windowSamples_), 0.0f);

        attackCoeff_  = timeConstantCoeff(settings.detectorAttackMs,  settings.analysisHopMs);
        releaseCoeff_ = timeConstantCoeff(settings.detectorReleaseMs, settings.analysisHopMs);
    }

    // Ingest one K-weighted sample. Returns the new smoothed level (dB) when
    // a hop boundary is reached, else NaN (caller checks via std::isnan).
    inline float ingest(float weightedSample) noexcept {
        runningSquareSum_ -= ringBuffer_[writeIndex_];
        ringBuffer_[writeIndex_] = weightedSample * weightedSample;
        runningSquareSum_ += ringBuffer_[writeIndex_];

        if (++writeIndex_ == windowSamples_) writeIndex_ = 0;

        if (++hopCounter_ >= hopSamples_) {
            hopCounter_ = 0;
            const float meanSquare = runningSquareSum_ * inverseWindowSamples_;
            const float levelDb = 10.0f * std::log10((meanSquare > 1e-12f) ? meanSquare : 1e-12f);
            const float coeff = (levelDb > smoothedLevel_) ? attackCoeff_ : releaseCoeff_;
            smoothedLevel_ += coeff * (levelDb - smoothedLevel_);
            return smoothedLevel_;
        }
        return std::nanf("0");   // sentinel
    }

    void reset() noexcept {
        for (auto& v : ringBuffer_) v = 0.0f;
        writeIndex_     = 0;
        hopCounter_     = 0;
        runningSquareSum_ = 0.0f;
        smoothedLevel_  = -120.0f;
    }

    float currentSmoothedLevelDb() const noexcept { return smoothedLevel_; }

    // Shared static helper — reused by GainSmoother.
    static inline float timeConstantCoeff(float timeMs, float stepMs) noexcept {
        const float tau = (timeMs > 1e-6f) ? timeMs : 1e-6f;
        return 1.0f - std::exp(-stepMs / tau);
    }

private:
    LoudnessEqualizerSettings settings_;
    float sampleRate_;

    std::vector<float> ringBuffer_;
    int   writeIndex_       = 0;
    int   hopCounter_       = 0;
    int   windowSamples_    = 0;
    int   hopSamples_       = 0;
    float inverseWindowSamples_ = 0.0f;
    float runningSquareSum_ = 0.0f;

    float attackCoeff_      = 0.0f;
    float releaseCoeff_     = 0.0f;
    float smoothedLevel_    = -120.0f;
};

} // namespace auraltune::audio

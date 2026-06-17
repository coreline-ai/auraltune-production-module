// GainComputer.h
//
// Stateless gain calculator: given a smoothed loudness level (dB), returns
// the desired gain (dB) — soft-knee compression above target, capped boost
// below target with noise-floor protection.

#pragma once

#include "LoudnessEqualizerSettings.h"

#include <algorithm>
#include <cmath>

namespace auraltune::audio {

class GainComputer {
public:
    explicit GainComputer(const LoudnessEqualizerSettings& s) noexcept : s_(s) {}

    inline float desiredGainDb(float smoothedLevelDb) const noexcept {
        return desiredBoostDb(smoothedLevelDb) + desiredCutDb(smoothedLevelDb);
    }

private:
    const LoudnessEqualizerSettings s_;

    inline float desiredBoostDb(float level) const noexcept {
        const float raw = s_.targetLoudnessDb - level;
        float clamped = std::max(0.0f, std::min(raw, s_.maxBoostDb));
        if (level < s_.noiseFloorThresholdDb && clamped > 0.0f) {
            clamped = std::min(clamped, s_.lowLevelMaxBoostDb);
        }
        return clamped;
    }

    inline float desiredCutDb(float level) const noexcept {
        if (s_.maxCutDb <= 0.0f) return 0.0f;
        const float threshold = s_.targetLoudnessDb + s_.compressionThresholdOffsetDb;
        const float ratio     = std::max(s_.compressionRatio, 1.0f);
        const float kneeWidth = std::max(s_.compressionKneeDb, 0.0f);
        const float compressed = softKneeCompressed(level, threshold, ratio, kneeWidth);
        const float reduction  = compressed - level;
        return std::max(-s_.maxCutDb, std::min(reduction, 0.0f));
    }

    static inline float softKneeCompressed(float in, float threshold, float ratio, float knee) noexcept {
        if (ratio <= 1.0f) return in;
        if (knee <= 0.0f) {
            return (in > threshold) ? threshold + (in - threshold) / ratio : in;
        }
        const float halfKnee = knee * 0.5f;
        const float kneeStart = threshold - halfKnee;
        const float kneeEnd   = threshold + halfKnee;
        if (in <= kneeStart) return in;
        if (in >= kneeEnd)   return threshold + (in - threshold) / ratio;
        const float normDist = in - kneeStart;
        const float quadGain = (1.0f / ratio - 1.0f) * normDist * normDist / (2.0f * knee);
        return in + quadGain;
    }
};

} // namespace auraltune::audio

// LoudnessEqualizerSettings.h
//
// POD configuration for the auto-leveler chain. Defaults match macOS
// reference values (target -12 dB, max boost +15 dB, max cut -4 dB,
// 30 ms RMS window with 15 ms hop, etc.).
//
// All fields are public for direct serialization. No allocation, no
// virtual methods — RT-safe value type.

#pragma once

namespace auraltune::audio {

struct LoudnessEqualizerSettings {
    float targetLoudnessDb            = -12.0f;
    float maxBoostDb                  =  15.0f;
    float maxCutDb                    =   4.0f;
    float compressionThresholdOffsetDb =  6.0f;
    float compressionRatio             =  1.6f;
    float compressionKneeDb            =  8.0f;

    float analysisWindowMs            = 30.0f;
    float analysisHopMs               = 15.0f;

    float detectorAttackMs            = 25.0f;
    float detectorReleaseMs           = 400.0f;

    float gainAttackMs                = 180.0f;
    float gainReleaseMs               = 5000.0f;

    float noiseFloorThresholdDb       = -48.0f;
    float lowLevelMaxBoostDb          =   1.5f;

    bool  enabled                     = false;
};

} // namespace auraltune::audio

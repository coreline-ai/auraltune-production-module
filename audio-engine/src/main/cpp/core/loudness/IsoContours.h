// IsoContours.h
//
// ISO 226:2023 equal-loudness contour utilities.
//
// Normative contour computation uses ISO 226:2023 Formula (1) with Table 1
// coefficients (29 preferred 1/3-octave frequencies, 20 Hz – 12.5 kHz).
//
// All calculations are pure double-precision math, allocation-free, and
// thread-agnostic. No external dependencies.
//
// Reference values pinned by `Iso226ReferenceTest.kt` (host JVM unit test);
// see that file for golden-value rationale and BS/EBU sources.

#pragma once

#include <array>

namespace auraltune::audio {

class IsoContours {
public:
    // Table 1 row count (matches the Kotlin reference).
    static constexpr int kTableSize = 29;

    // Reference loudness exponent αr at 1 kHz (ISO 226:2023).
    static constexpr double kReferenceLoudnessExponent = 0.300;

    // Index of the 1 kHz row in Table 1 (the reference frequency).
    static constexpr int kReferenceFrequencyIndex = 17;

    // Reference phon level corresponding to "100 % system volume" in our
    // loudness compensation policy. 80 phon ≈ 94 dB SPL — realistic for
    // headphone listening; higher values produce excessive bass boosts at
    // moderate volumes. Matches the macOS reference.
    static constexpr double kDefaultReferencePhon = 80.0;

    // Phon range supported by the formula. ISO is normative from 20 phon up.
    static constexpr double kMinSupportedPhon = 20.0;
    static constexpr double kMaxSupportedPhon = 90.0;

    // Reference sound pressure squared (Pa²). 4·10⁻¹⁰ ≈ (20 µPa)² · 1.
    static constexpr double kReferenceSoundPressureSquaredPa = 4e-10;

    using Array29 = std::array<double, kTableSize>;

    // Table 1 accessors — verbatim from ISO 226:2023.
    static const Array29& frequencies() noexcept;
    static const Array29& loudnessPerceptionExponents() noexcept;  // αf
    static const Array29& transferMagnitudesDB() noexcept;          // Lu
    static const Array29& hearingThresholdsDB() noexcept;           // Tf

    // ISO 226:2023 Formula (1): compute SPL contour at the 29 Table 1
    // frequencies for the given phon level. Phon is clamped to the
    // supported range before evaluation.
    //
    // Output: 29 SPL values in dB. SPL[reference_index] == phon by definition.
    static Array29 contourSpl(double phon) noexcept;

    // App-specific volume → phon mapping (NOT defined by ISO 226).
    //   phon = 20 + (80 - 20) · √(volume)
    // volume is clamped to [0, 1].
    static double estimatedPhonFromSystemVolume(float volume) noexcept;

    // Frequency-dependent loudness compensation in dB, normalized at 1 kHz
    // so only spectral balance is corrected (no broadband gain change).
    //
    //   gain[i] = (current[i] - current[1kHz]) - (reference[i] - reference[1kHz])
    //
    // `amount` ∈ [0, 1] scales the result. `maxGainDB` clips per-band gain
    // (use a large value to disable clipping; default unlimited).
    static Array29 compensationGains(double currentPhon,
                                     double referencePhon = kDefaultReferencePhon,
                                     double amount = 1.0,
                                     double maxGainDB = 1.0e9) noexcept;

    // Log-domain interpolation of a 29-point gain curve at an arbitrary
    // frequency. Used to fit the contour to filter band centers that don't
    // coincide with Table 1 entries.
    static double interpolateCompensation(const Array29& gains,
                                          double frequencyHz) noexcept;
};

} // namespace auraltune::audio

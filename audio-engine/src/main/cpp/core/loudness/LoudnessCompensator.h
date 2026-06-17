// LoudnessCompensator.h
//
// Computes a 4-section biquad cascade that approximates the ISO 226:2023
// equal-loudness compensation curve at a given phon level.
//
// Topology (verbatim from macOS reference, optimized for low DSP cost):
//   1. Low shelf  @ 80 Hz,  Q=0.707
//   2. Peaking    @ 180 Hz, Q=0.7
//   3. Peaking    @ 3.2 kHz, Q=0.7
//   4. High shelf @ 10 kHz, Q=0.85
//
// The per-section gains are fitted via Gauss-Newton least-squares against a
// 96-point logarithmic grid of the ISO target curve, 3 iterations.
//
// Output: 4 BiquadCoeffs ready to be cascaded by the engine.

#pragma once

#include <array>

#include "../BiquadFilter.h"   // BiquadCoeffs

namespace auraltune::audio {

class LoudnessCompensator {
public:
    static constexpr int kBandCount = 4;
    using FourCoeffs = std::array<BiquadCoeffs, kBandCount>;
    using FourGains  = std::array<float, kBandCount>;

    // Section topology — exposed so tests can pin frequencies / Q values.
    enum class SectionKind { LowShelf, Peaking, HighShelf };
    struct SectionDef {
        SectionKind kind;
        double frequency;
        double q;
    };
    static const std::array<SectionDef, kBandCount>& sectionDefinitions() noexcept;

    // Compute the 4 cascade coefficients for a given listening phon level
    // and engine sample rate. Internally:
    //   1. Compute ISO target compensation curve (29 points) at currentPhon
    //      relative to a reference phon (default 80, "100 % volume").
    //   2. Fit per-section gains via Gauss-Newton on a 96-point log grid.
    //   3. Build biquad coefficients with the fitted gains.
    //
    // If all fitted gains are below `negligibleGainDB` (default 0.1 dB),
    // the function returns four unity coefficients — caller can use that
    // signal to bypass the chain entirely.
    static FourCoeffs computeCoefficients(double currentPhon,
                                          double sampleRate,
                                          double referencePhon = 80.0,
                                          double negligibleGainDB = 0.1) noexcept;

    // Test helper: returns just the fitted per-section gains in dB. Same
    // computation as `computeCoefficients` but stops before biquad
    // synthesis. Used by `LoudnessCompensatorTest` to pin convergence.
    static FourGains fittedSectionGains(double currentPhon,
                                        double sampleRate,
                                        double referencePhon = 80.0) noexcept;

    // Test helper: solve a (square) linear system Ax=b via Gaussian elimination
    // with partial pivoting. Returns false if the matrix is (near-)singular.
    // Exposed for unit tests; the implementation is self-contained.
    static bool solveLinearSystem(double* augmented, int n) noexcept;
};

} // namespace auraltune::audio

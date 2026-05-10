// LoudnessCompensatorTest.cpp
//
// Native pre-implementation test for **C. Loudness Compensation (ISO 226:2023)**.
//
// Verifies that the C++ port produces results consistent with the host-JVM
// reference test (`Iso226ReferenceTest.kt`):
//
//   1. Table 1 values match verbatim (29 frequencies, αf, Lu, Tf).
//   2. Formula (1) — at 1 kHz reference frequency, SPL ≡ phon for all phon.
//   3. compensationGains: 40 vs 80 phon shows expected ISO shape (heavy bass
//      boost, slight mid-treble dip in 3-5 kHz, recovery + boost above 8 kHz).
//   4. fittedSectionGains: Gauss-Newton converges (4 finite section gains in
//      ±15 dB range).
//   5. computeCoefficients: at reference phon → unity bypass; at low phon →
//      non-unity coefficients with finite stability properties.
//   6. solveLinearSystem: Vandermonde 3×3 toy problem solves to (1, 2, 3).
//
// Build:
//   g++ -std=c++17 -O2 -fno-finite-math-only -I src/main/cpp \
//       src/test/cpp/LoudnessCompensatorTest.cpp \
//       src/main/cpp/BiquadFilter.cpp \
//       src/main/cpp/AuralTuneEQEngine.cpp \
//       src/main/cpp/loudness/IsoContours.cpp \
//       src/main/cpp/loudness/LoudnessCompensator.cpp \
//       -o /tmp/run_loudness_comp && /tmp/run_loudness_comp

#include <cassert>
#include <cmath>
#include <cstdio>

#include "loudness/IsoContours.h"
#include "loudness/LoudnessCompensator.h"

using auraltune::audio::IsoContours;
using auraltune::audio::LoudnessCompensator;
using auraltune::audio::BiquadCoeffs;

namespace {

// ─── 1. Table 1 pinning ─────────────────────────────────────────────────────

void testTable1FrequenciesMatchKotlinReference() {
    const auto& freqs = IsoContours::frequencies();
    assert(freqs.size() == 29);
    assert(freqs[0]  == 20.0);
    assert(freqs[2]  == 31.5);
    assert(freqs[7]  == 100.0);
    assert(freqs[17] == 1000.0);   // reference frequency
    assert(freqs[28] == 12500.0);
    std::printf("PASS testTable1FrequenciesMatchKotlinReference\n");
}

void testTable1ConstantsAt1kHzAreReferenceValues() {
    const auto& af = IsoContours::loudnessPerceptionExponents();
    const auto& lu = IsoContours::transferMagnitudesDB();
    assert(af[17] == IsoContours::kReferenceLoudnessExponent);   // αr = 0.300
    assert(lu[17] == 0.0);                                       // Lu = 0 dB at 1 kHz
    std::printf("PASS testTable1ConstantsAt1kHzAreReferenceValues\n");
}

// ─── 2. Formula (1) ─────────────────────────────────────────────────────────

void testFormula1At1kHzGivesSplEqualToPhon() {
    for (double phon : {20.0, 40.0, 60.0, 80.0, 90.0}) {
        const auto contour = IsoContours::contourSpl(phon);
        assert(std::fabs(contour[17] - phon) < 0.05);
    }
    std::printf("PASS testFormula1At1kHzGivesSplEqualToPhon\n");
}

void testFormula1At60Phon100HzMatchesIsoNormative() {
    // ISO 226:2023 normative value ≈ 78.48 dB SPL.
    const auto c60 = IsoContours::contourSpl(60.0);
    assert(std::fabs(c60[7] - 78.48) < 0.5);
    std::printf("PASS testFormula1At60Phon100HzMatchesIsoNormative (got %.4f)\n", c60[7]);
}

void testFormula1MonotonicInPhon() {
    const auto c40 = IsoContours::contourSpl(40.0);
    const auto c60 = IsoContours::contourSpl(60.0);
    const auto c80 = IsoContours::contourSpl(80.0);
    for (int i = 0; i < 29; ++i) {
        assert(c60[i] > c40[i]);
        assert(c80[i] > c60[i]);
    }
    std::printf("PASS testFormula1MonotonicInPhon\n");
}

// ─── 3. Compensation gains shape ────────────────────────────────────────────

void testCompensationCurveAt40Vs80PhonHasIsoShape() {
    const auto gains = IsoContours::compensationGains(40.0, 80.0);

    // Bass boost
    assert(gains[7]  >= 10.0);   // 100 Hz
    assert(gains[5]  >= 12.0);   // 63 Hz
    assert(gains[2]  >= 16.0);   // 31.5 Hz

    // Mid-treble dip (slight cut in 3-5 kHz region — ISO 226 characteristic)
    assert(gains[22] >= -3.0 && gains[22] <= 0.0);   // 3.15 kHz
    assert(gains[23] >= -3.0 && gains[23] <= 0.0);   // 4 kHz
    assert(gains[24] >= -3.0 && gains[24] <= 0.0);   // 5 kHz

    // High-treble boost
    assert(gains[27] > 0.0);    // 10 kHz
    assert(gains[28] >= 4.0);   // 12.5 kHz

    // 1 kHz must be zero by construction
    assert(std::fabs(gains[17]) < 1e-9);

    std::printf("PASS testCompensationCurveAt40Vs80PhonHasIsoShape\n");
}

void testCompensationAtReferencePhonIsZero() {
    const auto gains = IsoContours::compensationGains(80.0, 80.0);
    for (int i = 0; i < 29; ++i) assert(std::fabs(gains[i]) < 1e-9);
    std::printf("PASS testCompensationAtReferencePhonIsZero\n");
}

// ─── 4. Volume → phon mapping ───────────────────────────────────────────────

void testVolumeToPhonMappingMonotonicAndBounded() {
    assert(IsoContours::estimatedPhonFromSystemVolume(0.0f) == 20.0);
    assert(std::fabs(IsoContours::estimatedPhonFromSystemVolume(1.0f) - 80.0) < 1e-9);
    assert(std::fabs(IsoContours::estimatedPhonFromSystemVolume(0.25f) - 50.0) < 1e-9);
    // Out-of-range clamping
    assert(IsoContours::estimatedPhonFromSystemVolume(-1.0f) == 20.0);
    assert(std::fabs(IsoContours::estimatedPhonFromSystemVolume(2.0f) - 80.0) < 1e-9);

    double prev = IsoContours::estimatedPhonFromSystemVolume(0.0f);
    for (int i = 1; i <= 100; ++i) {
        const double cur = IsoContours::estimatedPhonFromSystemVolume(i / 100.0f);
        assert(cur >= prev);
        prev = cur;
    }
    std::printf("PASS testVolumeToPhonMappingMonotonicAndBounded\n");
}

// ─── 5. Linear solver ───────────────────────────────────────────────────────

void testLinearSolverVandermonde3x3() {
    // Same toy problem as Kotlin reference — solution (1, 2, 3)
    constexpr int n = 3;
    double aug[n * (n + 1)] = {
        1.0, 1.0, 1.0,  6.0,
        1.0, 2.0, 3.0, 14.0,
        1.0, 4.0, 9.0, 36.0,
    };
    const bool ok = LoudnessCompensator::solveLinearSystem(aug, n);
    assert(ok);
    assert(std::fabs(aug[0 * (n + 1) + n] - 1.0) < 1e-9);
    assert(std::fabs(aug[1 * (n + 1) + n] - 2.0) < 1e-9);
    assert(std::fabs(aug[2 * (n + 1) + n] - 3.0) < 1e-9);
    std::printf("PASS testLinearSolverVandermonde3x3\n");
}

void testLinearSolverSingularReturnsFalse() {
    constexpr int n = 2;
    double aug[n * (n + 1)] = {
        1.0, 2.0, 3.0,
        2.0, 4.0, 6.0,    // proportional row → singular
    };
    assert(!LoudnessCompensator::solveLinearSystem(aug, n));
    std::printf("PASS testLinearSolverSingularReturnsFalse\n");
}

// ─── 6. Gauss-Newton fit + Coefficients ─────────────────────────────────────

void testFittedSectionGainsAreFiniteAndSensible() {
    const auto gains = LoudnessCompensator::fittedSectionGains(40.0, 48000.0, 80.0);
    for (int i = 0; i < LoudnessCompensator::kBandCount; ++i) {
        assert(std::isfinite(gains[i]));
        // 4-section fit can't perfectly capture the ISO curve; allow up to ±20 dB.
        assert(std::fabs(gains[i]) < 20.0);
    }
    // At 40 phon vs 80 phon, low-shelf section (80 Hz) should be POSITIVE
    // (bass boost). Section 0 = low shelf @ 80 Hz.
    assert(gains[0] > 0.0);
    std::printf("PASS testFittedSectionGainsAreFiniteAndSensible "
                "(LS=%.2f, LM=%.2f, UM=%.2f, HS=%.2f dB)\n",
                gains[0], gains[1], gains[2], gains[3]);
}

void testCoefficientsAtReferencePhonAreUnity() {
    // At currentPhon == referencePhon, all section gains should be ~0 →
    // computeCoefficients returns 4 unity coeffs (bypass).
    const auto coeffs = LoudnessCompensator::computeCoefficients(80.0, 48000.0, 80.0);
    for (int i = 0; i < LoudnessCompensator::kBandCount; ++i) {
        assert(std::fabs(coeffs[i].b0 - 1.0) < 1e-9);
        assert(std::fabs(coeffs[i].b1) < 1e-9);
        assert(std::fabs(coeffs[i].b2) < 1e-9);
        assert(std::fabs(coeffs[i].a1) < 1e-9);
        assert(std::fabs(coeffs[i].a2) < 1e-9);
    }
    std::printf("PASS testCoefficientsAtReferencePhonAreUnity\n");
}

void testCoefficientsAtLowPhonAreNonUnityAndStable() {
    const auto coeffs = LoudnessCompensator::computeCoefficients(40.0, 48000.0, 80.0);
    bool anyNonUnity = false;
    for (int i = 0; i < LoudnessCompensator::kBandCount; ++i) {
        // Stability: |a2| < 1 AND |a1| < 1 + a2 (poles inside unit circle)
        assert(std::isfinite(coeffs[i].b0));
        assert(std::isfinite(coeffs[i].a1));
        assert(std::isfinite(coeffs[i].a2));
        assert(std::fabs(coeffs[i].a2) < 1.0);
        assert(std::fabs(coeffs[i].a1) < 1.0 + coeffs[i].a2 + 1e-9);
        if (std::fabs(coeffs[i].b0 - 1.0) > 1e-6 || std::fabs(coeffs[i].b1) > 1e-6) {
            anyNonUnity = true;
        }
    }
    assert(anyNonUnity);
    std::printf("PASS testCoefficientsAtLowPhonAreNonUnityAndStable\n");
}

// ─── 7. Section topology pinning ───────────────────────────────────────────

void testSectionTopologyMatchesMacOSReference() {
    const auto& defs = LoudnessCompensator::sectionDefinitions();
    assert(defs.size() == 4);
    // Section 0: low shelf @ 80 Hz, Q=0.707
    assert(defs[0].kind == LoudnessCompensator::SectionKind::LowShelf);
    assert(defs[0].frequency == 80.0);
    // Section 1: peaking @ 180 Hz, Q=0.7
    assert(defs[1].kind == LoudnessCompensator::SectionKind::Peaking);
    assert(defs[1].frequency == 180.0);
    // Section 2: peaking @ 3.2 kHz, Q=0.7
    assert(defs[2].kind == LoudnessCompensator::SectionKind::Peaking);
    assert(defs[2].frequency == 3200.0);
    // Section 3: high shelf @ 10 kHz, Q=0.85
    assert(defs[3].kind == LoudnessCompensator::SectionKind::HighShelf);
    assert(defs[3].frequency == 10000.0);
    std::printf("PASS testSectionTopologyMatchesMacOSReference\n");
}

}  // namespace

int main() {
    testTable1FrequenciesMatchKotlinReference();
    testTable1ConstantsAt1kHzAreReferenceValues();
    testFormula1At1kHzGivesSplEqualToPhon();
    testFormula1At60Phon100HzMatchesIsoNormative();
    testFormula1MonotonicInPhon();
    testCompensationCurveAt40Vs80PhonHasIsoShape();
    testCompensationAtReferencePhonIsZero();
    testVolumeToPhonMappingMonotonicAndBounded();
    testLinearSolverVandermonde3x3();
    testLinearSolverSingularReturnsFalse();
    testFittedSectionGainsAreFiniteAndSensible();
    testCoefficientsAtReferencePhonAreUnity();
    testCoefficientsAtLowPhonAreNonUnityAndStable();
    testSectionTopologyMatchesMacOSReference();
    std::printf("\nAll loudness-compensator tests passed.\n");
    return 0;
}

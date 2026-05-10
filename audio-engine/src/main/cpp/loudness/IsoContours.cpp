// IsoContours.cpp
//
// ISO 226:2023 contour math. All values verbatim from the published Table 1.
// Pinned by `Iso226ReferenceTest.kt` golden tests.

#include "IsoContours.h"

#include <algorithm>
#include <cmath>

namespace auraltune::audio {

namespace {

constexpr IsoContours::Array29 kFrequencies = {
    20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
    200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
    2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
};

constexpr IsoContours::Array29 kAlphaF = {
    0.635, 0.602, 0.569, 0.537, 0.509, 0.482, 0.456, 0.433, 0.412, 0.391,
    0.373, 0.357, 0.343, 0.330, 0.320, 0.311, 0.303, 0.300, 0.295, 0.292,
    0.290, 0.290, 0.289, 0.289, 0.289, 0.293, 0.303, 0.323, 0.354,
};

constexpr IsoContours::Array29 kTransferLuDB = {
    -31.5, -27.2, -23.1, -19.3, -16.1, -13.1, -10.4, -8.2, -6.3, -4.6,
    -3.2, -2.1, -1.2, -0.5, 0.0, 0.4, 0.5, 0.0, -2.7, -4.2,
    -1.2, 1.4, 2.3, 1.0, -2.3, -7.2, -11.2, -10.9, -3.5,
};

constexpr IsoContours::Array29 kThresholdTfDB = {
    78.1, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
    14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
    -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3,
};

inline double clamp(double v, double lo, double hi) {
    return std::min(std::max(v, lo), hi);
}

}  // namespace

const IsoContours::Array29& IsoContours::frequencies() noexcept              { return kFrequencies; }
const IsoContours::Array29& IsoContours::loudnessPerceptionExponents() noexcept { return kAlphaF; }
const IsoContours::Array29& IsoContours::transferMagnitudesDB() noexcept     { return kTransferLuDB; }
const IsoContours::Array29& IsoContours::hearingThresholdsDB() noexcept      { return kThresholdTfDB; }

IsoContours::Array29 IsoContours::contourSpl(double phon) noexcept {
    // Clamp phon to ISO-supported range. Outside this band the formula is
    // not normative — better to flat-line at the boundary than emit nonsense.
    const double clampedPhon = clamp(phon, kMinSupportedPhon, kMaxSupportedPhon);
    const double Tf17 = kThresholdTfDB[kReferenceFrequencyIndex];
    const double pSqRef = kReferenceSoundPressureSquaredPa;
    const double aR = kReferenceLoudnessExponent;

    Array29 out{};
    for (int i = 0; i < kTableSize; ++i) {
        const double af = kAlphaF[i];
        const double lu = kTransferLuDB[i];
        const double tf = kThresholdTfDB[i];

        // ISO 226:2023 Formula (1) — direct port of the published equation.
        const double excitation =
            std::pow(pSqRef, aR - af) *
                (std::pow(10.0, (aR * clampedPhon) / 10.0)
                 - std::pow(10.0, (aR * Tf17) / 10.0))
            + std::pow(10.0, (aR * (tf + lu)) / 10.0);

        out[i] = (10.0 / af) * std::log10(excitation) - lu;
    }
    return out;
}

double IsoContours::estimatedPhonFromSystemVolume(float volume) noexcept {
    const double v = clamp(static_cast<double>(volume), 0.0, 1.0);
    return kMinSupportedPhon + (kDefaultReferencePhon - kMinSupportedPhon) * std::sqrt(v);
}

IsoContours::Array29 IsoContours::compensationGains(double currentPhon,
                                                   double referencePhon,
                                                   double amount,
                                                   double maxGainDB) noexcept {
    const double clampedAmount = clamp(amount, 0.0, 1.0);
    const auto cur = contourSpl(currentPhon);
    const auto ref = contourSpl(referencePhon);
    const double curAt1k = cur[kReferenceFrequencyIndex];
    const double refAt1k = ref[kReferenceFrequencyIndex];

    Array29 out{};
    for (int i = 0; i < kTableSize; ++i) {
        const double gain = ((cur[i] - curAt1k) - (ref[i] - refAt1k)) * clampedAmount;
        out[i] = clamp(gain, -maxGainDB, maxGainDB);
    }
    return out;
}

double IsoContours::interpolateCompensation(const Array29& gains, double freqHz) noexcept {
    if (!std::isfinite(freqHz) || freqHz <= 0.0) return 0.0;

    const double logF = std::log(freqHz);
    Array29 logFreqs{};
    for (int i = 0; i < kTableSize; ++i) logFreqs[i] = std::log(kFrequencies[i]);

    if (logF <= logFreqs.front()) return gains.front();
    if (logF >= logFreqs.back())  return gains.back();

    int lo = 0;
    for (int i = 0; i < kTableSize - 1; ++i) {
        if (logFreqs[i + 1] >= logF) { lo = i; break; }
    }
    const int hi = lo + 1;
    const double t = (logF - logFreqs[lo]) / (logFreqs[hi] - logFreqs[lo]);
    return gains[lo] + t * (gains[hi] - gains[lo]);
}

}  // namespace auraltune::audio

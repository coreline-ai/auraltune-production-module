// LoudnessCompensator.cpp
//
// 4-section ISO-226 compensation fit. Direct port of the macOS implementation,
// expressed in plain double-precision math (no Accelerate / vDSP dependency).
//
// Fit approach: 3-iteration Gauss-Newton on a 96-point log grid spanning
// 20 Hz – 20 kHz. At each iteration we evaluate the cascade response,
// build a Gram matrix from the per-section unit responses, solve the
// normal equations via Gaussian elimination with partial pivoting, and
// add the delta to the current section gains.

#include "LoudnessCompensator.h"
#include "IsoContours.h"
#include "../AuralTuneEQEngine.h"   // for static peakingCoeffs/lowShelfCoeffs/highShelfCoeffs

#include <algorithm>
#include <cmath>

namespace auraltune::audio {

namespace {

constexpr int kFitGridPointCount = 96;
constexpr int kFitIterationCount = 3;

constexpr std::array<LoudnessCompensator::SectionDef, LoudnessCompensator::kBandCount> kSectionDefs = {{
    {LoudnessCompensator::SectionKind::LowShelf,   80.0,    0.707},
    {LoudnessCompensator::SectionKind::Peaking,   180.0,    0.7},
    {LoudnessCompensator::SectionKind::Peaking,  3200.0,    0.7},
    {LoudnessCompensator::SectionKind::HighShelf, 10000.0,  0.85},
}};

// ─── Helpers ────────────────────────────────────────────────────────────────

inline double clamp(double v, double lo, double hi) {
    return std::min(std::max(v, lo), hi);
}

// 96-point log grid 20 Hz → 20 kHz.
std::array<double, kFitGridPointCount> fitGridFrequencies() {
    std::array<double, kFitGridPointCount> out{};
    for (int i = 0; i < kFitGridPointCount; ++i) {
        out[i] = 20.0 * std::pow(20000.0 / 20.0, static_cast<double>(i) / (kFitGridPointCount - 1));
    }
    return out;
}

// Pick the appropriate RBJ coefficient builder for a section, with given
// gain (dB) at the section's defined frequency / Q.
BiquadCoeffs sectionCoeffsFor(const LoudnessCompensator::SectionDef& def,
                              float gainDB,
                              double sampleRate) {
    using Kind = LoudnessCompensator::SectionKind;
    // Above-Nyquist guard: return unity if section freq exceeds Nyquist.
    if (def.frequency >= sampleRate / 2.0) return BiquadCoeffs::unity();
    switch (def.kind) {
        case Kind::LowShelf:
            return AuralTuneEQEngine::lowShelfCoeffs(def.frequency, gainDB, def.q, sampleRate);
        case Kind::Peaking:
            return AuralTuneEQEngine::peakingCoeffs(def.frequency, gainDB, def.q, sampleRate);
        case Kind::HighShelf:
            return AuralTuneEQEngine::highShelfCoeffs(def.frequency, gainDB, def.q, sampleRate);
    }
    return BiquadCoeffs::unity();
}

// Magnitude (dB) at a single frequency for one biquad section.
double sectionMagnitudeDB(const BiquadCoeffs& c, double freqHz, double sampleRate) {
    const double omega = 2.0 * M_PI * freqHz / sampleRate;
    const double cosW  = std::cos(omega);
    const double sinW  = std::sin(omega);
    const double cos2W = std::cos(2.0 * omega);
    const double sin2W = std::sin(2.0 * omega);
    const double numRe = c.b0 + c.b1 * cosW + c.b2 * cos2W;
    const double numIm = -(c.b1 * sinW + c.b2 * sin2W);
    const double denRe = 1.0 + c.a1 * cosW + c.a2 * cos2W;
    const double denIm = -(c.a1 * sinW + c.a2 * sin2W);
    const double numMag = std::sqrt(numRe * numRe + numIm * numIm);
    const double denMag = std::sqrt(denRe * denRe + denIm * denIm);
    if (denMag <= 0.0 || !std::isfinite(numMag) || !std::isfinite(denMag)) return 0.0;
    return 20.0 * std::log10(numMag / denMag);
}

// Per-section magnitude at the 96-point grid for unit gain (1 dB) — used as
// the basis for the linear least-squares fit.
std::array<std::array<double, kFitGridPointCount>, LoudnessCompensator::kBandCount>
basisResponsesDB(double sampleRate) {
    const auto grid = fitGridFrequencies();
    std::array<std::array<double, kFitGridPointCount>, LoudnessCompensator::kBandCount> out{};
    for (int s = 0; s < LoudnessCompensator::kBandCount; ++s) {
        const auto coeffs = sectionCoeffsFor(kSectionDefs[s], 1.0f, sampleRate);
        for (int g = 0; g < kFitGridPointCount; ++g) {
            out[s][g] = sectionMagnitudeDB(coeffs, grid[g], sampleRate);
        }
    }
    return out;
}

// Realized cascade response at the grid for given section gains.
std::array<double, kFitGridPointCount>
realizedResponseDB(const LoudnessCompensator::FourGains& sectionGains, double sampleRate) {
    const auto grid = fitGridFrequencies();
    std::array<BiquadCoeffs, LoudnessCompensator::kBandCount> coeffs{};
    for (int s = 0; s < LoudnessCompensator::kBandCount; ++s) {
        coeffs[s] = sectionCoeffsFor(kSectionDefs[s], sectionGains[s], sampleRate);
    }
    std::array<double, kFitGridPointCount> out{};
    for (int g = 0; g < kFitGridPointCount; ++g) {
        double total = 0.0;
        for (int s = 0; s < LoudnessCompensator::kBandCount; ++s) {
            total += sectionMagnitudeDB(coeffs[s], grid[g], sampleRate);
        }
        out[g] = total;
    }
    return out;
}

// Target curve at the 96-point grid — interpolation of the 29-point
// ISO compensation curve.
std::array<double, kFitGridPointCount>
targetCurveDB(double currentPhon, double referencePhon) {
    const auto isoGains = IsoContours::compensationGains(currentPhon, referencePhon);
    const auto grid = fitGridFrequencies();
    std::array<double, kFitGridPointCount> out{};
    for (int g = 0; g < kFitGridPointCount; ++g) {
        out[g] = IsoContours::interpolateCompensation(isoGains, grid[g]);
    }
    return out;
}

}  // namespace

const std::array<LoudnessCompensator::SectionDef, LoudnessCompensator::kBandCount>&
LoudnessCompensator::sectionDefinitions() noexcept {
    return kSectionDefs;
}

bool LoudnessCompensator::solveLinearSystem(double* aug, int n) noexcept {
    // Gaussian elimination with partial pivoting on an augmented matrix
    // laid out row-major as `aug[row * (n+1) + col]`.
    for (int k = 0; k < n; ++k) {
        // Pick best pivot
        int best = k;
        for (int r = k + 1; r < n; ++r) {
            if (std::fabs(aug[r * (n + 1) + k]) > std::fabs(aug[best * (n + 1) + k])) {
                best = r;
            }
        }
        if (std::fabs(aug[best * (n + 1) + k]) < 1e-12) return false;
        if (best != k) {
            for (int j = 0; j <= n; ++j) {
                std::swap(aug[k * (n + 1) + j], aug[best * (n + 1) + j]);
            }
        }
        const double pivot = aug[k * (n + 1) + k];
        for (int j = k; j <= n; ++j) {
            aug[k * (n + 1) + j] /= pivot;
        }
        for (int r = 0; r < n; ++r) {
            if (r == k) continue;
            const double f = aug[r * (n + 1) + k];
            if (f == 0.0) continue;
            for (int j = k; j <= n; ++j) {
                aug[r * (n + 1) + j] -= f * aug[k * (n + 1) + j];
            }
        }
    }
    return true;
}

LoudnessCompensator::FourGains
LoudnessCompensator::fittedSectionGains(double currentPhon,
                                       double sampleRate,
                                       double referencePhon) noexcept {
    const auto target = targetCurveDB(currentPhon, referencePhon);
    const auto basis  = basisResponsesDB(sampleRate);

    // Pre-compute Gram matrix G[i,j] = <basis[i], basis[j]>
    double gram[kBandCount][kBandCount] = {{0}};
    for (int i = 0; i < kBandCount; ++i) {
        for (int j = 0; j < kBandCount; ++j) {
            double sum = 0.0;
            for (int g = 0; g < kFitGridPointCount; ++g) {
                sum += basis[i][g] * basis[j][g];
            }
            gram[i][j] = sum;
        }
    }

    FourGains gains{};   // initialized to zero
    for (int iter = 0; iter < kFitIterationCount; ++iter) {
        const auto realized = realizedResponseDB(gains, sampleRate);

        // residual[g] = target[g] - realized[g]; rhs[i] = <basis[i], residual>
        double rhs[kBandCount] = {0};
        for (int i = 0; i < kBandCount; ++i) {
            double sum = 0.0;
            for (int g = 0; g < kFitGridPointCount; ++g) {
                sum += basis[i][g] * (target[g] - realized[g]);
            }
            rhs[i] = sum;
        }

        // Build augmented matrix [G | rhs] and solve in place
        double aug[kBandCount * (kBandCount + 1)] = {0};
        for (int i = 0; i < kBandCount; ++i) {
            for (int j = 0; j < kBandCount; ++j) {
                aug[i * (kBandCount + 1) + j] = gram[i][j];
            }
            aug[i * (kBandCount + 1) + kBandCount] = rhs[i];
        }
        if (!solveLinearSystem(aug, kBandCount)) {
            // Singular Gram (extremely unlikely for our 4-section topology).
            // Return current gains unchanged — caller will see negligible
            // gains and bypass.
            break;
        }
        for (int i = 0; i < kBandCount; ++i) {
            gains[i] += static_cast<float>(aug[i * (kBandCount + 1) + kBandCount]);
        }
    }
    return gains;
}

LoudnessCompensator::FourCoeffs
LoudnessCompensator::computeCoefficients(double currentPhon,
                                        double sampleRate,
                                        double referencePhon,
                                        double negligibleGainDB) noexcept {
    const auto gains = fittedSectionGains(currentPhon, sampleRate, referencePhon);

    // Bypass: all gains negligible (typical near reference phon).
    bool allNegligible = true;
    for (int i = 0; i < kBandCount; ++i) {
        if (std::fabs(static_cast<double>(gains[i])) >= negligibleGainDB) {
            allNegligible = false; break;
        }
    }
    FourCoeffs out{};
    for (int i = 0; i < kBandCount; ++i) {
        out[i] = allNegligible
            ? BiquadCoeffs::unity()
            : sectionCoeffsFor(kSectionDefs[i], gains[i], sampleRate);
    }
    return out;
}

}  // namespace auraltune::audio

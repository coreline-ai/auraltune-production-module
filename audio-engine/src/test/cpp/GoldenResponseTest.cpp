// GoldenResponseTest.cpp
//
// Phase 7 production gate — frequency response golden test.
//
// Strategy:
//   1. Compute an analytical reference via the biquad transfer function
//      H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (1 + a1·z⁻¹ + a2·z⁻²)
//      evaluated at z = e^(jω) for every test frequency. This is independent
//      from the engine's TDF2 inner loop — same coefficients, different math
//      path — so a regression in the cascade implementation will diverge from
//      the analytical model.
//   2. Run an impulse (length kImpulseLen, single 1.0 at index 0) through the
//      ACTUAL AuralTuneEQEngine::process() cascade.
//   3. Take a DFT of the resulting impulse response at each test frequency.
//   4. Assert |analytical_dB(f) - engine_dB(f)| ≤ 0.5 dB for f ∈ [20 Hz, 20 kHz].
//
// Coverage: 44.1 kHz, 48 kHz, 96 kHz × HD 600 oratory1990-shaped profile
// (3 filters: LS 105 Hz +2.5 dB Q 0.7, PK 3 kHz −2 dB Q 1.0, HS 8 kHz +1.5 dB Q 0.7).
//
// Compile (host):
//   g++ -std=c++17 -O2 -fno-finite-math-only -I src/main/cpp \
//       src/test/cpp/GoldenResponseTest.cpp \
//       src/main/cpp/BiquadFilter.cpp \
//       src/main/cpp/AuralTuneEQEngine.cpp -o /tmp/run_golden && /tmp/run_golden

#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

#include "AuralTuneEQEngine.h"

using auraltune::audio::AuralTuneEQEngine;
using auraltune::audio::EqFilterType;
using auraltune::audio::BiquadCoeffs;

namespace {

constexpr int kImpulseLen = 16384;       // long enough for steady-state IR decay
constexpr double kTolerance_dB = 0.5;    // dev-plan Phase 7 spec

// HD 600 oratory1990-shaped fixture (per dev-plan line 455).
struct FilterSpec {
    EqFilterType type;
    double frequency;
    float  gainDB;
    double q;
};
const FilterSpec kHd600[3] = {
    { EqFilterType::LowShelf,   105.0,  2.5f, 0.7 },
    { EqFilterType::Peaking,   3000.0, -2.0f, 1.0 },
    { EqFilterType::HighShelf, 8000.0,  1.5f, 0.7 },
};
const float kPreampDB = 0.0f;            // simplifies analytical reference
constexpr double kProfileRate = 48000.0; // matches dev-plan optimizedSampleRate

// ----------- Independent analytical reference (NOT shared with engine) -----------

// RBJ peaking. Returns [b0..a2] normalized by a0. Independent re-derivation —
// any divergence from the engine's coefficient math shows up as a test failure.
BiquadCoeffs refPeaking(double f, float gDB, double q, double fs) {
    const double A     = std::pow(10.0, gDB / 40.0);
    const double w0    = 2.0 * M_PI * f / fs;
    const double alpha = std::sin(w0) / (2.0 * q);
    const double cosw  = std::cos(w0);

    const double b0 = 1.0 + alpha * A;
    const double b1 = -2.0 * cosw;
    const double b2 = 1.0 - alpha * A;
    const double a0 = 1.0 + alpha / A;
    const double a1 = -2.0 * cosw;
    const double a2 = 1.0 - alpha / A;
    return { b0/a0, b1/a0, b2/a0, a1/a0, a2/a0 };
}
BiquadCoeffs refLowShelf(double f, float gDB, double q, double fs) {
    const double A     = std::pow(10.0, gDB / 40.0);
    const double w0    = 2.0 * M_PI * f / fs;
    const double alpha = std::sin(w0) / (2.0 * q);
    const double cosw  = std::cos(w0);
    const double sa    = 2.0 * std::sqrt(A) * alpha;

    const double b0 = A * ((A + 1.0) - (A - 1.0) * cosw + sa);
    const double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw);
    const double b2 = A * ((A + 1.0) - (A - 1.0) * cosw - sa);
    const double a0 = (A + 1.0) + (A - 1.0) * cosw + sa;
    const double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw);
    const double a2 = (A + 1.0) + (A - 1.0) * cosw - sa;
    return { b0/a0, b1/a0, b2/a0, a1/a0, a2/a0 };
}
BiquadCoeffs refHighShelf(double f, float gDB, double q, double fs) {
    const double A     = std::pow(10.0, gDB / 40.0);
    const double w0    = 2.0 * M_PI * f / fs;
    const double alpha = std::sin(w0) / (2.0 * q);
    const double cosw  = std::cos(w0);
    const double sa    = 2.0 * std::sqrt(A) * alpha;

    const double b0 = A * ((A + 1.0) + (A - 1.0) * cosw + sa);
    const double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw);
    const double b2 = A * ((A + 1.0) + (A - 1.0) * cosw - sa);
    const double a0 = (A + 1.0) - (A - 1.0) * cosw + sa;
    const double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosw);
    const double a2 = (A + 1.0) - (A - 1.0) * cosw - sa;
    return { b0/a0, b1/a0, b2/a0, a1/a0, a2/a0 };
}

// Pre-warp — independent re-implementation.
double refPreWarp(double f, double fromRate, double toRate) {
    const double fAnalog = (fromRate / M_PI) * std::tan(M_PI * f / fromRate);
    return (toRate / M_PI) * std::atan(M_PI * fAnalog / toRate);
}

// Magnitude of biquad at frequency f: |H(e^(jω))|.
double biquadMagnitude(const BiquadCoeffs& c, double f, double fs) {
    const double w = 2.0 * M_PI * f / fs;
    const double cw  = std::cos(w);
    const double sw  = std::sin(w);
    const double c2w = std::cos(2.0 * w);
    const double s2w = std::sin(2.0 * w);

    const double numRe = c.b0 + c.b1 * cw + c.b2 * c2w;
    const double numIm = -c.b1 * sw - c.b2 * s2w;
    const double denRe = 1.0 + c.a1 * cw + c.a2 * c2w;
    const double denIm = -c.a1 * sw - c.a2 * s2w;

    const double numMag = std::sqrt(numRe * numRe + numIm * numIm);
    const double denMag = std::sqrt(denRe * denRe + denIm * denIm);
    return numMag / denMag;
}

// Cascade reference: build coefficients with pre-warp + Nyquist guard, then
// product the magnitudes (=sum the dBs).
double cascadeRefDb(double freqHz, double engineRate) {
    double mag = 1.0;
    for (auto& f : kHd600) {
        double effF = f.frequency;
        if (std::fabs(kProfileRate - engineRate) > 1.0) {
            effF = refPreWarp(f.frequency, kProfileRate, engineRate);
        }
        if (effF <= 0.0 || effF >= engineRate / 2.0) continue; // unity passthrough
        BiquadCoeffs c;
        switch (f.type) {
            case EqFilterType::LowShelf:  c = refLowShelf(effF, f.gainDB, f.q, engineRate); break;
            case EqFilterType::HighShelf: c = refHighShelf(effF, f.gainDB, f.q, engineRate); break;
            case EqFilterType::Peaking:
            default:                       c = refPeaking(effF, f.gainDB, f.q, engineRate); break;
        }
        mag *= biquadMagnitude(c, freqHz, engineRate);
    }
    return 20.0 * std::log10(mag);
}

// ----------- Engine-side measurement: impulse → process() → DFT magnitude -----------

void runImpulse(AuralTuneEQEngine& eng, std::vector<float>& outIRStereo) {
    outIRStereo.assign(kImpulseLen * 2, 0.0f);
    outIRStereo[0] = 1.0f; // L
    outIRStereo[1] = 1.0f; // R
    // process() in-place. Use a single big call so coefficient publish window
    // can't cut across the impulse response.
    int rc = eng.process(outIRStereo.data(), kImpulseLen);
    assert(rc == 0);
}

double dftMagnitudeAtFrequency(const float* mono, int N, double f, double fs) {
    double real = 0.0, imag = 0.0;
    const double k = -2.0 * M_PI * f / fs;
    for (int n = 0; n < N; ++n) {
        const double angle = k * n;
        real += mono[n] * std::cos(angle);
        imag += mono[n] * std::sin(angle);
    }
    return std::sqrt(real * real + imag * imag);
}

double engineDb(const std::vector<float>& irStereo, double f, double fs) {
    // Use the L channel only — biquad is mono-per-channel, both should agree.
    static thread_local std::vector<float> mono;
    mono.assign(kImpulseLen, 0.0f);
    for (int i = 0; i < kImpulseLen; ++i) {
        mono[i] = irStereo[i * 2];
    }
    const double mag = dftMagnitudeAtFrequency(mono.data(), kImpulseLen, f, fs);
    if (mag <= 1e-30) return -300.0; // clamp to avoid log(0)
    return 20.0 * std::log10(mag);
}

// ----------- Test driver: assert engine vs reference within tolerance -----------

void runForRate(double engineRate) {
    AuralTuneEQEngine eng(engineRate);
    eng.setAutoEqEnabled(true);

    int   types[3]   = { 1, 0, 2 }; // matches kHd600
    float freqs[3]   = { 105.0f, 3000.0f, 8000.0f };
    float gains[3]   = { 2.5f, -2.0f, 1.5f };
    float qs[3]      = { 0.7f, 1.0f, 0.7f };

    int rc = eng.updateAutoEq(kPreampDB, /*limiter=*/false,
                              kProfileRate, types, freqs, gains, qs, 3);
    assert(rc == 0);

    std::vector<float> ir;
    runImpulse(eng, ir);

    // Logarithmically spaced check frequencies between 20 Hz and 20 kHz, plus
    // dense coverage of the filter centers where we expect peak deviation.
    const std::vector<double> testFreqs = {
        20, 30, 50, 80, 105, 150, 250, 500, 1000, 2000, 3000, 4000,
        5000, 6000, 8000, 10000, 12000, 15000, 18000, 20000,
    };

    int worstIdx = -1;
    double worstAbsErr = 0.0;
    int failures = 0;
    for (size_t i = 0; i < testFreqs.size(); ++i) {
        const double f = testFreqs[i];
        if (f >= engineRate / 2.0) break; // above Nyquist

        const double refDb     = cascadeRefDb(f, engineRate);
        const double engineDbV = engineDb(ir, f, engineRate);
        const double err       = engineDbV - refDb;
        const double absErr    = std::fabs(err);

        if (absErr > worstAbsErr) {
            worstAbsErr = absErr;
            worstIdx = static_cast<int>(i);
        }
        if (absErr > kTolerance_dB) {
            ++failures;
            std::printf("  FAIL @ %.0f Hz: ref=%.3f dB engine=%.3f dB diff=%.3f dB\n",
                        f, refDb, engineDbV, err);
        }
    }
    std::printf("  fs=%.0f Hz : worst |Δ| = %.4f dB at %.0f Hz (failures: %d)\n",
                engineRate, worstAbsErr,
                worstIdx >= 0 ? testFreqs[worstIdx] : 0.0,
                failures);
    assert(failures == 0);
}

} // namespace

int main() {
    std::printf("Golden response test — HD 600 oratory1990 fixture, ±%.2f dB tolerance\n",
                kTolerance_dB);
    runForRate(48000.0);
    runForRate(44100.0);
    runForRate(96000.0);
    std::printf("\nGolden response: PASS\n");
    return 0;
}

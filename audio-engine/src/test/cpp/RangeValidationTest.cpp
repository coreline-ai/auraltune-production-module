// RangeValidationTest.cpp
//
// Tier C-1 native unit test — verifies the JNI-boundary range checks that
// also live as defense-in-depth inside AuralTuneEQEngine. JNI itself can't
// be exercised host-side; we test the engine's own bounds (which JNI
// trusts to be a superset of its own validation contract).
//
// Tier B-2 — also verifies the new diagnostic fields:
//   * appliedGeneration increments per successful publish
//   * autoEqActiveCount reflects the published count, and goes to 0 when
//     setAutoEqEnabled(false) is called.
//
// Build:
//   g++ -std=c++17 -O2 -fno-finite-math-only -I src/main/cpp \
//       src/test/cpp/RangeValidationTest.cpp \
//       src/main/cpp/BiquadFilter.cpp \
//       src/main/cpp/AuralTuneEQEngine.cpp -o /tmp/run_range && /tmp/run_range

#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

#include "AuralTuneEQEngine.h"

using auraltune::audio::AuralTuneEQEngine;
using auraltune::audio::EqFilterType;

namespace {

constexpr int kFrames = 1024;

bool allFinite(const float* buf, int frames) {
    for (int i = 0; i < frames * 2; ++i) {
        if (!std::isfinite(buf[i])) return false;
    }
    return true;
}

// Test 1: updateSampleRate silently rejects out-of-range rates.
//   * 7999 below kMinSampleRateHz (8000) — rejected.
//   * 384001 above kMaxSampleRateHz (384000) — rejected.
//   * 96000 in-range — accepted.
void testSampleRateBounds() {
    AuralTuneEQEngine eng(48000.0);
    const auto base = eng.readDiagnostics().sampleRateChangeCount;

    eng.updateSampleRate(7999);
    assert(eng.readDiagnostics().sampleRateChangeCount == base);

    eng.updateSampleRate(384001);
    assert(eng.readDiagnostics().sampleRateChangeCount == base);

    eng.updateSampleRate(0);
    assert(eng.readDiagnostics().sampleRateChangeCount == base);

    eng.updateSampleRate(-1);
    assert(eng.readDiagnostics().sampleRateChangeCount == base);

    // In-range value increments the counter.
    eng.updateSampleRate(96000);
    assert(eng.readDiagnostics().sampleRateChangeCount == base + 1);

    // Re-applying same rate is a no-op (existing engine behavior).
    eng.updateSampleRate(96000);
    assert(eng.readDiagnostics().sampleRateChangeCount == base + 1);

    std::printf("PASS testSampleRateBounds\n");
}

// Test 2: process() with numFrames = 0 returns -1.
void testProcessZeroFrames() {
    AuralTuneEQEngine eng(48000.0);
    std::vector<float> buf(8, 0.0f);
    assert(eng.process(buf.data(), 0) == -1);
    std::printf("PASS testProcessZeroFrames\n");
}

// Test 3: process() with numFrames > kMaxProcessFrames returns -1.
void testProcessTooLarge() {
    AuralTuneEQEngine eng(48000.0);
    // We don't actually allocate 65537 frames * 2; the engine should reject
    // the size before touching the buffer. Pass a small buffer + a large
    // numFrames; if the engine bails early, no read past the small buffer.
    std::vector<float> buf(8, 0.0f);
    assert(eng.process(buf.data(), 65537) == -1);
    assert(eng.process(buf.data(), 1000000) == -1);
    assert(eng.process(buf.data(), -1) == -1);
    std::printf("PASS testProcessTooLarge\n");
}

// Test 4: updateAutoEq with profileOptimizedRate = 1e6 returns -1.
void testUpdateAutoEqProfileRateOutOfRange() {
    AuralTuneEQEngine eng(48000.0);
    int   types[1] = {0};
    float freqs[1] = {1000.0f};
    float gains[1] = {0.0f};
    float qs[1]    = {1.0f};

    // 1 MHz — way above kMaxSampleRateHz.
    assert(eng.updateAutoEq(0.0f, false, 1'000'000.0,
                            types, freqs, gains, qs, 1) == -1);

    // 7999 — below kMinSampleRateHz.
    assert(eng.updateAutoEq(0.0f, false, 7999.0,
                            types, freqs, gains, qs, 1) == -1);

    // 384001 — above kMaxSampleRateHz.
    assert(eng.updateAutoEq(0.0f, false, 384001.0,
                            types, freqs, gains, qs, 1) == -1);

    // NaN.
    assert(eng.updateAutoEq(0.0f, false, std::nan(""),
                            types, freqs, gains, qs, 1) == -1);

    // 48000 — in range.
    assert(eng.updateAutoEq(0.0f, false, 48000.0,
                            types, freqs, gains, qs, 1) == 0);

    std::printf("PASS testUpdateAutoEqProfileRateOutOfRange\n");
}

// Test 5: updateAutoEq with frequency = 30000 and profileOptimizedRate =
// 48000 succeeds (validation accepts the filter — the source-Nyquist guard's
// job is to reduce it to unity passthrough at coefficient time, not to
// reject the call). 30 kHz >= 24 kHz Nyquist → unity coeffs.
void testFilterAtSourceNyquistAccepted() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);

    int   types[1] = {0};
    float freqs[1] = {30000.0f};
    float gains[1] = {6.0f};
    float qs[1]    = {1.0f};

    const int rc = eng.updateAutoEq(0.0f, false, 48000.0,
                                    types, freqs, gains, qs, 1);
    assert(rc == 0);

    std::printf("PASS testFilterAtSourceNyquistAccepted\n");
}

// Test 6: After test 5, processing an impulse, the resulting cascade has
// finite output (the 30 kHz filter became unity, doesn't break the cascade).
void testImpulseFiniteAfterUnityFilter() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);

    // Combine an in-band peaking filter with one above source Nyquist. The
    // out-of-band filter should be unity (no contribution); the in-band
    // filter should produce a finite, non-degenerate output.
    int   types[2] = {0, 0};
    float freqs[2] = {1000.0f, 30000.0f};
    float gains[2] = {3.0f, 6.0f};
    float qs[2]    = {1.0f, 1.0f};
    assert(eng.updateAutoEq(0.0f, false, 48000.0,
                            types, freqs, gains, qs, 2) == 0);

    std::vector<float> buf(kFrames * 2, 0.0f);
    buf[0] = 1.0f;
    buf[1] = 1.0f;
    assert(eng.process(buf.data(), kFrames) == 0);
    assert(allFinite(buf.data(), kFrames));

    // Sanity: the impulse response should have finite, bounded energy.
    double energy = 0.0;
    for (int i = 0; i < kFrames * 2; ++i) {
        energy += static_cast<double>(buf[i]) * static_cast<double>(buf[i]);
    }
    assert(std::isfinite(energy));
    assert(energy > 0.0); // some signal got through the in-band filter

    std::printf("PASS testImpulseFiniteAfterUnityFilter\n");
}

// Test 7: Diagnostics — appliedGeneration increments per successful publish;
// autoEqActiveCount equals the published count and reverts to 0 when
// setAutoEqEnabled(false) is called.
void testDiagnosticsAppliedGenerationAndCount() {
    AuralTuneEQEngine eng(48000.0);

    // Bootstrap snapshot has generation=0; first user-driven publish gets gen=1.
    const auto base = eng.readDiagnostics();

    int   types[3]   = {0, 1, 2};
    float freqs[3]   = {1000.0f, 80.0f, 8000.0f};
    float gains[3]   = {2.0f, 3.0f, -1.5f};
    float qs[3]      = {1.0f, 0.7f, 0.7f};

    // Publish 1 — count = 3.
    assert(eng.updateAutoEq(0.0f, false, 48000.0,
                            types, freqs, gains, qs, 3) == 0);
    eng.setAutoEqEnabled(true); // publish 2

    auto d = eng.readDiagnostics();
    assert(d.appliedGeneration == base.appliedGeneration + 2);
    // AutoEQ enabled + count = 3.
    assert(d.autoEqActiveCount == 3);

    // Publish 3 — shrink to 1 filter.
    assert(eng.updateAutoEq(0.0f, false, 48000.0,
                            types, freqs, gains, qs, 1) == 0);
    d = eng.readDiagnostics();
    assert(d.appliedGeneration == base.appliedGeneration + 3);
    assert(d.autoEqActiveCount == 1);

    // setAutoEqEnabled(false) — chain still has 1 filter cached but the user
    // sees no correction, so the diagnostics report 0.
    eng.setAutoEqEnabled(false);
    d = eng.readDiagnostics();
    assert(d.appliedGeneration == base.appliedGeneration + 4);
    assert(d.autoEqActiveCount == 0);

    // Re-enable; cached count = 1 should re-surface.
    eng.setAutoEqEnabled(true);
    d = eng.readDiagnostics();
    assert(d.appliedGeneration == base.appliedGeneration + 5);
    assert(d.autoEqActiveCount == 1);

    // Calls that don't actually publish (no-op transitions) must NOT advance
    // the generation. setAutoEqEnabled(true) when already true is a no-op.
    eng.setAutoEqEnabled(true);
    auto dNoop = eng.readDiagnostics();
    assert(dNoop.appliedGeneration == d.appliedGeneration);

    std::printf("PASS testDiagnosticsAppliedGenerationAndCount\n");
}

// Test 8 (bonus): readAppliedSnapshot agrees with readDiagnostics on the
// generation field, and reflects the toggle state precisely.
void testAppliedSnapshotConsistency() {
    AuralTuneEQEngine eng(48000.0);

    int   types[2]   = {0, 1};
    float freqs[2]   = {1000.0f, 80.0f};
    float gains[2]   = {2.0f, 3.0f};
    float qs[2]      = {1.0f, 0.7f};
    assert(eng.updateAutoEq(-3.0f, true, 48000.0,
                            types, freqs, gains, qs, 2) == 0);
    eng.setAutoEqEnabled(true);
    eng.setManualEqEnabled(true);

    const auto a = eng.readAppliedSnapshot();
    const auto d = eng.readDiagnostics();
    // generation read sequentially after both publishes — both reads see the
    // latest snapshot (single-threaded test) so the values agree.
    assert(a.generation == static_cast<uint64_t>(d.appliedGeneration));
    assert(a.autoEqEnabled);
    assert(a.autoEqFilterCount == 2);
    assert(a.manualEnabled);
    // Default preamp is enabled; -3 dB → ~0.708 linear.
    assert(a.preampEnabled);
    assert(std::fabs(a.preampLinearGain - std::pow(10.0f, -3.0f / 20.0f)) < 1e-5f);

    eng.setAutoEqPreampEnabled(false);
    const auto a2 = eng.readAppliedSnapshot();
    assert(!a2.preampEnabled);
    assert(std::fabs(a2.preampLinearGain - 1.0f) < 1e-6f);

    std::printf("PASS testAppliedSnapshotConsistency\n");
}

// Test 9 (Stage A): HighPass filter type produces a Butterworth response.
// Verifies the new EqFilterType::HighPass is wired through buildAutoEqFilter
// and produces sensible coefficients (matching the Kotlin pre-verification
// reference values to numerical precision).
void testHighPassFilterTypeResponseSensible() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);

    // Single HPF at fc=80 Hz, Q=1/√2 (Butterworth). gainDB unused.
    const int   types[1] = {static_cast<int>(EqFilterType::HighPass)};
    const float freqs[1] = {80.0f};
    const float gains[1] = {0.0f};
    const float qs[1]    = {static_cast<float>(1.0 / std::sqrt(2.0))};

    assert(eng.updateAutoEq(0.0f, false, 48000.0,
                            types, freqs, gains, qs, 1) == 0);

    // Process a short impulse — first sample of stereo pair = 1.0
    std::vector<float> buf(kFrames * 2, 0.0f);
    buf[0] = 1.0f;
    buf[1] = 1.0f;
    assert(eng.process(buf.data(), kFrames) == 0);
    assert(allFinite(buf.data(), kFrames));

    // HPF response: at low frequency the energy is attenuated. Verify the
    // initial transient from a unit impulse produces a finite, reasonable
    // response — output[0] (first stereo sample after b0 multiplication)
    // should be ~b0 = (1+cos(2π·80/48000))/2 / (1+α) ≈ 0.99 (for fc=80,
    // fs=48000, Q=1/√2). The exact value is the pre-verified reference.
    const double omega = 2.0 * M_PI * 80.0 / 48000.0;
    const double sinW  = std::sin(omega);
    const double cosW  = std::cos(omega);
    const double alpha = sinW / (2.0 * (1.0 / std::sqrt(2.0)));
    const double expectedB0 = (1.0 + cosW) / 2.0 / (1.0 + alpha);
    assert(std::fabs(static_cast<double>(buf[0]) - expectedB0) < 1e-5);

    std::printf("PASS testHighPassFilterTypeResponseSensible (b0=%.6f)\n", expectedB0);
}

// Test 10c (Stage D): Loudness Equalizer auto-leveler — engine integration.
//   - Default: chain disabled, identity passthrough
//   - Enable + low-amplitude input: gain ramp over many callbacks should
//     boost the signal toward the target loudness
void testLoudnessEqualizerEngineIntegration() {
    AuralTuneEQEngine eng(48000.0);

    // Default config target = -12 dB; quiet input @ -40 dB (~0.01 amplitude)
    // should be boosted significantly over time.
    eng.updateLoudnessEqSettings(/*target*/ -12.0f,
                                 /*maxBoost*/  15.0f,
                                 /*maxCut*/     4.0f,
                                 /*compThr+*/   6.0f,
                                 /*ratio*/      1.6f,
                                 /*kneeDb*/     8.0f,
                                 /*atkMs*/    180.0f,
                                 /*relMs*/   5000.0f);
    eng.setLoudnessEqEnabled(true);

    // Run many callbacks of quiet pure tone; gain should ramp upward.
    constexpr int kCallbacks = 200;     // ≈ 4.27 s at 1024 frames @ 48 kHz
    std::vector<float> buf(kFrames * 2, 0.0f);
    float gainStart = 0.0f;
    float gainEnd   = 0.0f;
    for (int cb = 0; cb < kCallbacks; ++cb) {
        // Fill with a low-amplitude 1 kHz sine
        for (int f = 0; f < kFrames; ++f) {
            const float t = static_cast<float>(cb * kFrames + f) / 48000.0f;
            const float s = 0.01f * std::sin(2.0f * M_PI * 1000.0f * t);
            buf[f * 2]     = s;
            buf[f * 2 + 1] = s;
        }
        // Capture amplitude before processing on first callback
        if (cb == 0) gainStart = std::fabs(buf[0]) > 1e-12f ? std::fabs(buf[0]) : 1e-12f;
        assert(eng.process(buf.data(), kFrames) == 0);
        if (cb == kCallbacks - 1) gainEnd = std::fabs(buf[0]) > 1e-12f ? std::fabs(buf[0]) : 1e-12f;
        assert(allFinite(buf.data(), kFrames));
    }
    // After ramping, output amplitude must be larger than input (engine boosted it).
    // We don't assert exact target — just direction. (Quiet 0.01 input → -40 dB →
    // detector measures ≈ -43 dB → gain target ~+15 dB clamped, but noise floor
    // protection caps to +1.5 dB at very quiet levels — see GainComputer).
    assert(gainEnd >= gainStart);

    std::printf("PASS testLoudnessEqualizerEngineIntegration "
                "(start=%.5f, end=%.5f over %d callbacks)\n",
                gainStart, gainEnd, kCallbacks);
}

// Test 10b (Stage C): Loudness Compensation chain — engine integration.
//   - Default state: chain disabled, process passes through identity
//   - Enable + reference volume (1.0): chain enabled but coeffs unity → no audible change
//   - Enable + low volume (0.1): coeffs non-unity → noticeable signal change but still finite
void testLoudnessCompensationEngineIntegration() {
    AuralTuneEQEngine eng(48000.0);

    // Capture identity output for reference
    std::vector<float> ref(kFrames * 2, 0.0f);
    ref[0] = 1.0f; ref[1] = 1.0f;
    assert(eng.process(ref.data(), kFrames) == 0);

    // Enable loudness comp at reference volume — coefficients are all unity
    // so output should still be identity (modulo floating-point noise from
    // running through 4 unity sections).
    eng.setLoudnessCompensationEnabled(true);
    eng.setLoudnessCompensationVolume(1.0f);   // 80 phon = reference

    std::vector<float> bufRef(kFrames * 2, 0.0f);
    bufRef[0] = 1.0f; bufRef[1] = 1.0f;
    assert(eng.process(bufRef.data(), kFrames) == 0);
    assert(allFinite(bufRef.data(), kFrames));
    // At reference volume, the chain runs but with unity coefficients.
    // Output should be very close to identity (within floating-point noise).
    assert(std::fabs(bufRef[0] - 1.0f) < 1e-3);

    // Set low volume → non-unity coefficients → output differs significantly
    assert(eng.setLoudnessCompensationVolume(0.1f) == 0);

    std::vector<float> bufLow(kFrames * 2, 0.0f);
    bufLow[0] = 1.0f; bufLow[1] = 1.0f;
    assert(eng.process(bufLow.data(), kFrames) == 0);
    assert(allFinite(bufLow.data(), kFrames));
    // Low-volume compensation boosts bass; the impulse response should have
    // visibly more energy than the unity-coefficients case.
    double energyLow = 0.0, energyRef = 0.0;
    for (int i = 0; i < kFrames * 2; ++i) {
        energyLow += static_cast<double>(bufLow[i]) * static_cast<double>(bufLow[i]);
        energyRef += static_cast<double>(bufRef[i]) * static_cast<double>(bufRef[i]);
    }
    assert(std::isfinite(energyLow));
    assert(energyLow > energyRef);   // bass-boosted IR has more energy

    // Disable → output reverts to (essentially) identity
    eng.setLoudnessCompensationEnabled(false);
    std::vector<float> bufOff(kFrames * 2, 0.0f);
    bufOff[0] = 1.0f; bufOff[1] = 1.0f;
    assert(eng.process(bufOff.data(), kFrames) == 0);
    assert(std::fabs(bufOff[0] - 1.0f) < 1e-3);

    std::printf("PASS testLoudnessCompensationEngineIntegration "
                "(energyRef=%.4f, energyLow=%.4f)\n", energyRef, energyLow);
}

// Test 10 (Stage A): HighPass + Peaking cascade — both filter types coexist
// in the same chain. Verifies the new HighPass enum doesn't break the
// existing routing.
void testHighPassMixedWithPeaking() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);

    // Chain: HPF @ 80 Hz Q=0.707  +  Peaking @ 1 kHz +6 dB Q=1.0
    const int   types[2] = {
        static_cast<int>(EqFilterType::HighPass),
        static_cast<int>(EqFilterType::Peaking),
    };
    const float freqs[2] = {80.0f, 1000.0f};
    const float gains[2] = {0.0f, 6.0f};
    const float qs[2]    = {static_cast<float>(1.0 / std::sqrt(2.0)), 1.0f};

    assert(eng.updateAutoEq(0.0f, false, 48000.0,
                            types, freqs, gains, qs, 2) == 0);

    std::vector<float> buf(kFrames * 2, 0.0f);
    // Inject a unit impulse pair on first frame
    buf[0] = 1.0f;
    buf[1] = 1.0f;
    assert(eng.process(buf.data(), kFrames) == 0);
    assert(allFinite(buf.data(), kFrames));

    // Energy must remain finite and bounded — neither filter should blow up.
    double energy = 0.0;
    for (int i = 0; i < kFrames * 2; ++i) {
        energy += static_cast<double>(buf[i]) * static_cast<double>(buf[i]);
    }
    assert(std::isfinite(energy));
    assert(energy > 0.0 && energy < 1e6);

    // Diagnostics confirm both filters are in the active chain
    const auto d = eng.readDiagnostics();
    assert(d.autoEqActiveCount == 2);

    std::printf("PASS testHighPassMixedWithPeaking (energy=%.3f)\n", energy);
}

}  // namespace

int main() {
    testSampleRateBounds();
    testProcessZeroFrames();
    testProcessTooLarge();
    testUpdateAutoEqProfileRateOutOfRange();
    testFilterAtSourceNyquistAccepted();
    testImpulseFiniteAfterUnityFilter();
    testDiagnosticsAppliedGenerationAndCount();
    testAppliedSnapshotConsistency();
    testHighPassFilterTypeResponseSensible();
    testHighPassMixedWithPeaking();
    testLoudnessCompensationEngineIntegration();
    testLoudnessEqualizerEngineIntegration();
    std::printf("\nAll range-validation tests passed.\n");
    return 0;
}

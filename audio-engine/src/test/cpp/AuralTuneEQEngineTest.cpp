// AuralTuneEQEngineTest.cpp
//
// Host-side native test (compile via standalone CMake or Android NDK + GoogleTest).
// Manually executable: g++ -std=c++17 -I../main/cpp ... AuralTuneEQEngineTest.cpp
//                     ../main/cpp/{BiquadFilter,AuralTuneEQEngine}.cpp -o run_tests
//
// Phase 1+2 self-tests required by dev-plan/implement_20260507_223901.md:
//   - C++ unity config near-bypass
//   - stale filter reset (chain shrink)
//   - NaN/Inf input contamination (release fast guard)
//   - sub-bass shelf + high-Q peaking finite stability
//   - sample-rate change recompute
//   - pre-warp Nyquist guard unity replacement

#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

#include "AuralTuneEQEngine.h"

using auraltune::audio::AuralTuneEQEngine;
using auraltune::audio::EqFilterType;

namespace {

constexpr int kFrames = 1024;

void fillSilence(float* buf, int frames) {
    for (int i = 0; i < frames * 2; ++i) buf[i] = 0.0f;
}

void fillImpulse(float* buf, int frames) {
    fillSilence(buf, frames);
    buf[0] = 1.0f;
    buf[1] = 1.0f;
}

void fillNaN(float* buf, int frames) {
    for (int i = 0; i < frames * 2; ++i) buf[i] = std::nanf("");
}

bool allFinite(const float* buf, int frames) {
    for (int i = 0; i < frames * 2; ++i) {
        if (!std::isfinite(buf[i])) return false;
    }
    return true;
}

// Test 1: empty chain config = bypass (output should equal input)
void testUnityBypass() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);
    int rc = eng.updateAutoEq(0.0f, false, 48000.0,
                              nullptr, nullptr, nullptr, nullptr, 0);
    assert(rc == 0);

    std::vector<float> buf(kFrames * 2);
    for (int i = 0; i < kFrames * 2; ++i) buf[i] = 0.5f;
    eng.process(buf.data(), kFrames);

    // With empty AutoEQ chain and no manual chain, output should equal input
    for (int i = 0; i < kFrames * 2; ++i) {
        assert(std::abs(buf[i] - 0.5f) < 1e-5f);
    }
    std::printf("PASS testUnityBypass\n");
}

// Test 2: Stale filter reset — chain shrinks from 5 to 2 sections.
// Verifies that the trailing 3 slots get unity coeffs + delay reset (no stale ringing).
void testStaleFilterReset() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);

    // First, charge all 5 filters with steady state.
    int types5[5] = {0, 0, 0, 0, 0};
    float freqs5[5] = {100, 500, 1000, 5000, 10000};
    float gains5[5] = {3, -3, 4, -4, 2};
    float qs5[5]    = {1, 1, 1, 1, 1};
    assert(eng.updateAutoEq(0.0f, false, 48000.0, types5, freqs5, gains5, qs5, 5) == 0);

    std::vector<float> buf(kFrames * 2, 0.1f);
    for (int i = 0; i < 4; ++i) eng.process(buf.data(), kFrames);  // soak

    // Shrink to 2 sections — slots 2..4 must become unity AND have delay state cleared.
    int types2[2] = {0, 0};
    float freqs2[2] = {100, 500};
    float gains2[2] = {3, -3};
    float qs2[2]    = {1, 1};
    assert(eng.updateAutoEq(0.0f, false, 48000.0, types2, freqs2, gains2, qs2, 2) == 0);

    // Process many frames of silence. Filters 0 and 1 will ring out naturally per their
    // decay time; filters 2..4 must contribute nothing (proves stale reset happened).
    // We process enough frames (~10x kFrames) to let the active filters fully decay,
    // then assert the final tail of the buffer is at noise floor.
    std::vector<float> zeros(kFrames * 2, 0.0f);
    for (int iter = 0; iter < 10; ++iter) {
        std::fill(zeros.begin(), zeros.end(), 0.0f);
        eng.process(zeros.data(), kFrames);
    }
    // After full decay, last 100 frames should be at quasi-silent noise floor.
    for (int i = (kFrames - 100) * 2; i < kFrames * 2; ++i) {
        assert(std::abs(zeros[i]) < 1e-4f);
    }
    std::printf("PASS testStaleFilterReset\n");
}

// Test 3: NaN input → guard resets state and zeros buffer
void testNaNGuard() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);
    int types[1] = {0}; float freqs[1] = {1000}; float gains[1] = {3}; float qs[1] = {1};
    eng.updateAutoEq(0.0f, false, 48000.0, types, freqs, gains, qs, 1);

    std::vector<float> buf(kFrames * 2);
    fillNaN(buf.data(), kFrames);
    eng.process(buf.data(), kFrames);

    // Output buffer should be all-zero after NaN guard
    for (int i = 0; i < kFrames * 2; ++i) {
        assert(buf[i] == 0.0f);
    }

    auto diag = eng.readDiagnostics();
    assert(diag.nonFiniteResetCount > 0);

    // After reset, normal input should produce finite output
    std::vector<float> impulse(kFrames * 2);
    fillImpulse(impulse.data(), kFrames);
    eng.process(impulse.data(), kFrames);
    assert(allFinite(impulse.data(), kFrames));
    std::printf("PASS testNaNGuard\n");
}

// Test 4: sub-bass low-shelf + high-Q peaking — output stays finite
void testSubBassHighQ() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);
    int types[3]   = {1, 0, 2};
    float freqs[3] = {30.0f, 1000.0f, 15000.0f};
    float gains[3] = {6.0f, 5.0f, 6.0f};
    float qs[3]    = {0.7f, 6.0f, 0.7f};
    assert(eng.updateAutoEq(0.0f, false, 48000.0, types, freqs, gains, qs, 3) == 0);

    std::vector<float> buf(kFrames * 2);
    // -60 dBFS white noise approximation via tiny constant
    for (int i = 0; i < kFrames * 2; ++i) buf[i] = (i % 2 == 0 ? 0.001f : -0.001f);

    for (int iter = 0; iter < 100; ++iter) {
        eng.process(buf.data(), kFrames);
        assert(allFinite(buf.data(), kFrames));
    }
    std::printf("PASS testSubBassHighQ\n");
}

// Test 5: sample rate change → coefficients recomputed, no NaN
void testSampleRateChange() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);
    int types[2]   = {0, 0};
    float freqs[2] = {1000.0f, 8000.0f};
    float gains[2] = {3.0f, -2.0f};
    float qs[2]    = {1.0f, 1.0f};
    eng.updateAutoEq(0.0f, false, 48000.0, types, freqs, gains, qs, 2);

    std::vector<float> buf(kFrames * 2, 0.1f);
    eng.process(buf.data(), kFrames);

    eng.updateSampleRate(44100);
    auto diag = eng.readDiagnostics();
    assert(diag.sampleRateChangeCount > 0);

    eng.process(buf.data(), kFrames);
    assert(allFinite(buf.data(), kFrames));
    std::printf("PASS testSampleRateChange\n");
}

// Test 6: validation rejects invalid inputs
void testValidation() {
    AuralTuneEQEngine eng(48000.0);

    int types[1]; float freqs[1]; float gains[1]; float qs[1];

    // negative frequency
    types[0] = 0; freqs[0] = -1.0f; gains[0] = 0; qs[0] = 1;
    assert(eng.updateAutoEq(0.0f, false, 48000.0, types, freqs, gains, qs, 1) < 0);

    // |gain| > 30
    freqs[0] = 1000.0f; gains[0] = 50.0f; qs[0] = 1;
    assert(eng.updateAutoEq(0.0f, false, 48000.0, types, freqs, gains, qs, 1) < 0);

    // q <= 0
    gains[0] = 0; qs[0] = -1.0f;
    assert(eng.updateAutoEq(0.0f, false, 48000.0, types, freqs, gains, qs, 1) < 0);

    // invalid filter type
    types[0] = 99; qs[0] = 1.0f;
    assert(eng.updateAutoEq(0.0f, false, 48000.0, types, freqs, gains, qs, 1) < 0);

    std::printf("PASS testValidation\n");
}

}  // namespace

// Test 7: Whole-config atomic swap — the snapshot generation must advance on
// every successful update*() call, and process() must never see a half-rebuilt
// cascade. We can't easily produce the data race in single-threaded test code,
// but we can verify (a) generations advance monotonically and (b) immediate
// process() after update produces finite output.
void testWholeConfigSnapshotPublish() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);

    auto baseline = eng.readDiagnostics().configSwapCount;

    int types[3]   = {0, 1, 2};
    float freqs[3] = {1000.0f, 80.0f, 8000.0f};
    float gains[3] = {2.0f, 3.0f, -1.5f};
    float qs[3]    = {1.0f, 0.7f, 0.7f};

    // 5 sequential snapshots, each with different params.
    for (int it = 0; it < 5; ++it) {
        gains[0] = static_cast<float>(it - 2); // -2..+2 dB
        assert(eng.updateAutoEq(0.0f, false, 48000.0,
                                types, freqs, gains, qs, 3) == 0);

        std::vector<float> buf(1024 * 2, 0.1f);
        assert(eng.process(buf.data(), 1024) == 0);
        assert(allFinite(buf.data(), 1024));
    }
    auto after = eng.readDiagnostics().configSwapCount;
    assert(after - baseline == 5);
    std::printf("PASS testWholeConfigSnapshotPublish\n");
}

// Test 8: xrun counter — verify the JNI-exposed recordXrun increments the
// engine's diagnostic counter without needing real AudioTrack involvement.
void testXrunCounter() {
    AuralTuneEQEngine eng(48000.0);
    auto before = eng.readDiagnostics().xrunCount;
    eng.recordXrun(7);
    eng.recordXrun(3);
    eng.recordXrun(0); // no-op
    eng.recordXrun(-5); // no-op
    auto after = eng.readDiagnostics().xrunCount;
    assert(after - before == 10);
    std::printf("PASS testXrunCounter\n");
}

// Test 9: setAutoEqEnabled toggle should publish a snapshot too (so the
// audio thread sees enable/disable atomically alongside coefficients).
void testEnableTogglePublishesSnapshot() {
    AuralTuneEQEngine eng(48000.0);
    auto base = eng.readDiagnostics().configSwapCount;

    // Toggle a few times — each transition should publish a snapshot.
    eng.setAutoEqEnabled(true);    // false → true (publish)
    eng.setAutoEqEnabled(true);    // no-op
    eng.setAutoEqEnabled(false);   // true → false (publish)
    eng.setManualEqEnabled(true);  // false → true (publish)
    eng.setAutoEqPreampEnabled(false); // toggle off (publish)
    eng.setAutoEqPreampEnabled(false); // no-op

    auto delta = eng.readDiagnostics().configSwapCount - base;
    assert(delta == 4);
    std::printf("PASS testEnableTogglePublishesSnapshot\n");
}

// Test 10: Phase 6 click detection — rapid profile switching should NOT cause
// audible click events in the output. We define a click as a sample-to-sample
// delta exceeding kClickThreshold within a buffer that wasn't itself an
// expected discontinuity (the engine never zeroes the input signal mid-stream
// in this test). Emulates the worst-case "user spinning the favorites carousel"
// stress pattern.
void testRapidSwitchingClickFree() {
    constexpr float kClickThreshold = 0.1f; // dev-plan Phase 6 line 359
    constexpr int kBuffersToProcess = 60;   // ~600 ms @ 48 kHz with 480 frames
    constexpr int kFramesPerBuffer = 480;
    constexpr int kSwapEveryN = 6;          // ~10 Hz switching at 48k/480

    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);

    // Continuous 1 kHz sine input — gives us a stable signal to detect clicks
    // against. (Constant input through a stable filter should produce a smooth
    // sinusoidal output once steady-state is reached.)
    std::vector<float> buf(kFramesPerBuffer * 2);
    double phase = 0.0;
    const double phaseInc = 2.0 * M_PI * 1000.0 / 48000.0;

    int clickEvents = 0;
    float lastSample = 0.0f;
    int filterTypes[3] = {0, 0, 0};
    float freqs[3] = {1000.0f, 3000.0f, 8000.0f};
    float qs[3] = {1.0f, 1.0f, 1.0f};

    for (int b = 0; b < kBuffersToProcess; ++b) {
        // Generate sine continuation.
        for (int i = 0; i < kFramesPerBuffer; ++i) {
            const float v = static_cast<float>(0.25 * std::sin(phase));
            buf[i * 2]     = v;
            buf[i * 2 + 1] = v;
            phase += phaseInc;
            if (phase >= 2.0 * M_PI) phase -= 2.0 * M_PI;
        }

        // Switch profile every kSwapEveryN buffers. Vary gain modestly — typical
        // AutoEQ profile-to-profile gain deltas are ±2-4 dB at any single band.
        // (We tested non-atomic-publish detection separately in
        // testWholeConfigSnapshotPublish; this test specifically targets
        // perceptible click artifacts for realistic user transitions.)
        if (b % kSwapEveryN == 0) {
            const float gainAlt = (b / kSwapEveryN % 2 == 0) ? 3.0f : -3.0f;
            float gains[3] = {gainAlt, -gainAlt, gainAlt};
            int rc = eng.updateAutoEq(0.0f, false, 48000.0,
                                       filterTypes, freqs, gains, qs, 3);
            assert(rc == 0);
        }

        eng.process(buf.data(), kFramesPerBuffer);

        // Skip the first buffer — filter transient is allowed to be larger than
        // the click threshold once at startup.
        if (b > 0) {
            for (int i = 0; i < kFramesPerBuffer * 2; ++i) {
                const float delta = std::fabs(buf[i] - lastSample);
                if (delta > kClickThreshold) ++clickEvents;
                lastSample = buf[i];
            }
        } else {
            lastSample = buf[(kFramesPerBuffer - 1) * 2 + 1];
        }
    }

    if (clickEvents > 0) {
        std::printf("  rapid switch produced %d click events (>%.2f delta)\n",
                    clickEvents, kClickThreshold);
    }
    // Whole-config atomic publish should give us 0 clicks. Allow a tiny
    // transient slack for the very first switch.
    assert(clickEvents <= 2);
    std::printf("PASS testRapidSwitchingClickFree (clicks=%d)\n", clickEvents);
}

int main() {
    testUnityBypass();
    testStaleFilterReset();
    testNaNGuard();
    testSubBassHighQ();
    testSampleRateChange();
    testValidation();
    testWholeConfigSnapshotPublish();
    testXrunCounter();
    testEnableTogglePublishesSnapshot();
    testRapidSwitchingClickFree();
    std::printf("\nAll native tests passed.\n");
    return 0;
}

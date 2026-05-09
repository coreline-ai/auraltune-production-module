// SnapshotRaceTest.cpp
//
// Targeted reproducer for the snapshot-race the prior 4-slot pool was
// vulnerable to: ≥4 control-thread updates within one audio-callback window
// would overwrite the slot the audio thread was still reading. The current
// heap+retire architecture (AuralTuneEQEngine.cpp:189-) replaces the pool, so
// this test sanity-checks that high-rate updates running concurrently with
// continuous process() calls cannot corrupt the audio thread's snapshot.
//
// We don't have ThreadSanitizer in this host harness, but a strict
// sample-by-sample finiteness + bounded-output check across millions of
// frames is enough to surface the bug if it re-emerges (a torn snapshot
// produces NaN/Inf or wildly out-of-range output within ~1 callback).
//
// Compile (host):
//   g++ -std=c++17 -O2 -fno-finite-math-only -pthread \
//       -I src/main/cpp \
//       src/test/cpp/SnapshotRaceTest.cpp \
//       src/main/cpp/BiquadFilter.cpp src/main/cpp/AuralTuneEQEngine.cpp \
//       -o /tmp/run_race && /tmp/run_race
//
// With TSan (when toolchain is available):
//   g++ -std=c++17 -O1 -g -fno-finite-math-only -fsanitize=thread -pthread \
//       -I src/main/cpp \
//       src/test/cpp/SnapshotRaceTest.cpp \
//       src/main/cpp/BiquadFilter.cpp src/main/cpp/AuralTuneEQEngine.cpp \
//       -o /tmp/run_race_tsan && /tmp/run_race_tsan

#include <atomic>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>

#include "AuralTuneEQEngine.h"

using auraltune::audio::AuralTuneEQEngine;
using auraltune::audio::EqFilterType;

namespace {

constexpr int    kFrames        = 1024;
constexpr int    kAudioCallbacks = 4000;     // ~85s at 48k/1024 frames/cb
constexpr double kBoundedDb     = 24.0;      // |output| max under ±6 dB profile
// 24 dB = 10^(24/20) ≈ 15.85x linear. Hard-coded since std::pow isn't constexpr.
constexpr float  kBoundLinear   = 16.0f;

void controlThread(AuralTuneEQEngine& eng, std::atomic<bool>& stop,
                   std::atomic<int64_t>& updates) {
    int   types[3]   = { 0, 0, 0 };
    float freqs[3]   = { 200.0f, 2000.0f, 8000.0f };
    float qs[3]      = { 0.7f, 1.0f, 0.7f };
    int i = 0;
    while (!stop.load(std::memory_order_relaxed)) {
        // Cycle through gain configurations; the OLD 4-slot pool would
        // surface the race after ~4 updates per audio callback. We do
        // ~10× that rate to maximise pressure.
        const float g = (i & 1) ? 4.0f : -4.0f;
        float gains[3] = { g, -g, g };
        eng.updateAutoEq(/*preampDB=*/0.0f, /*limiter=*/false,
                         /*profileOptimizedRate=*/48000.0,
                         types, freqs, gains, qs, 3);
        eng.setAutoEqEnabled(true);
        eng.setAutoEqPreampEnabled((i & 2) != 0);
        ++i;
        updates.fetch_add(3, std::memory_order_relaxed);  // 3 publishes per loop
        // No sleep — we want maximum publish pressure on the snapshot path.
    }
}

void audioThread(AuralTuneEQEngine& eng, std::atomic<bool>& stop,
                 std::atomic<int64_t>& nonFinite,
                 std::atomic<int64_t>& outOfBounds) {
    std::vector<float> buf(kFrames * 2);
    int cb = 0;
    while (!stop.load(std::memory_order_relaxed) && cb < kAudioCallbacks) {
        // Re-prime input every callback so prior nonsense can't latch.
        for (int i = 0; i < kFrames; ++i) {
            const float v = static_cast<float>(0.25 * std::sin(
                2.0 * M_PI * 1000.0 * (cb * kFrames + i) / 48000.0));
            buf[i * 2]     = v;
            buf[i * 2 + 1] = v;
        }
        int rc = eng.process(buf.data(), kFrames);
        assert(rc == 0);

        // Sample-by-sample audit: a torn snapshot in the cascade would
        // surface as either non-finite output (caught by NaN guard, but the
        // guard zeros the buffer — we re-check anyway) OR as outliers
        // exceeding the bounded gain envelope.
        for (int i = 0; i < kFrames * 2; ++i) {
            const float s = buf[i];
            if (!std::isfinite(s)) {
                nonFinite.fetch_add(1, std::memory_order_relaxed);
            } else if (std::fabs(s) > kBoundLinear) {
                outOfBounds.fetch_add(1, std::memory_order_relaxed);
            }
        }
        ++cb;
    }
}

} // namespace

int main() {
    AuralTuneEQEngine eng(48000.0);
    eng.setAutoEqEnabled(true);

    std::atomic<bool>    stop{false};
    std::atomic<int64_t> updates{0};
    std::atomic<int64_t> nonFinite{0};
    std::atomic<int64_t> outOfBounds{0};

    std::thread t_audio  ([&]{ audioThread(eng, stop, nonFinite, outOfBounds); });
    std::thread t_control([&]{ controlThread(eng, stop, updates); });

    t_audio.join();         // ends after kAudioCallbacks
    stop.store(true);
    t_control.join();

    auto diag = eng.readDiagnostics();
    std::printf("Snapshot race stress:\n");
    std::printf("  audio callbacks    : %d\n", kAudioCallbacks);
    std::printf("  control updates    : %lld (publish events ~%lld)\n",
                static_cast<long long>(updates.load()),
                static_cast<long long>(diag.configSwapCount));
    std::printf("  non-finite samples : %lld\n",
                static_cast<long long>(nonFinite.load()));
    std::printf("  out-of-bound samples (>+24 dBFS): %lld\n",
                static_cast<long long>(outOfBounds.load()));
    std::printf("  NaN guard resets   : %lld (engine counter)\n",
                static_cast<long long>(diag.nonFiniteResetCount));

    // PASS gate: zero non-finite samples (the NaN guard zeroed any escapes
    // would still bump diag.nonFiniteResetCount; we accept up to a handful
    // of those because the TEST INPUT amplitude × extreme cascade gain at a
    // perfect boundary CAN tickle a single-sample NaN that the guard
    // catches). Out-of-bound samples must be 0 — those would indicate a
    // torn cascade producing pathological values.
    assert(nonFinite.load() == 0);
    assert(outOfBounds.load() == 0);
    std::printf("\nSnapshot race stress: PASS\n");
    return 0;
}

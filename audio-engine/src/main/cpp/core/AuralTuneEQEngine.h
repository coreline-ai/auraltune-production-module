// AuralTuneEQEngine.h
//
// Native EQ engine — Android port of AuralTune (macOS).
//
// Layout: two independent biquad cascades.
//   - Manual chain: up to 20 sections, peaking only.
//   - AutoEQ chain: up to 10 sections, peaking / low-shelf / high-shelf.
//
// Process order each callback (audio thread):
//   1. (optional) Manual cascade
//   2. (optional) AutoEQ preamp gain (applied only if preamp toggle on)
//   3. (optional) AutoEQ cascade
//   4. (optional) post soft limiter
//   5. NaN guard — if first stereo frame is non-finite, reset all delay state
//      and zero the buffer.
//
// Concurrency model (post P0-1 v2 fix):
//   - All "hot" state read by the audio thread lives in EngineSnapshot.
//   - Each publish ALLOCATES A NEW HEAP-IMMUTABLE EngineSnapshot. Control
//     thread populates it fully, then atomically exchanges activeSnapshot_.
//     The previous snapshot is pushed onto a retire queue with a 500 ms
//     deadline (matches Swift AuralTune's vDSP_biquad_DestroySetup pattern).
//   - Audio thread loads activeSnapshot_ once at callback entry and uses the
//     same pointer throughout. The retire grace guarantees the snapshot stays
//     valid for ≫ any audio-callback period.
//   - Delay state for each biquad section is owned by the audio thread (lives
//     in BiquadFilter). Control thread NEVER touches delay state directly;
//     instead, snapshots carry a `requestDelayReset` flag which the audio
//     thread honors at the start of the first callback that observes a new
//     generation number.
//
// This replaces the earlier 4-slot pool. With a fixed pool, control could
// cycle through every slot within one audio-callback window and overwrite
// the slot the audio thread was still reading (TSan-confirmed race). Heap +
// deferred retire makes "what audio is reading" unambiguous and keeps the
// audio thread allocation- and sync-free.

#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <vector>

#include "BiquadFilter.h"
#include "loudness/LoudnessEqualizerSettings.h"

namespace auraltune::audio {

class LoudnessEqualizer;   // forward decl — defined in loudness/LoudnessEqualizer.h

// Filter type enum exposed across JNI. Values match Kotlin EqFilterType.nativeId.
enum class EqFilterType : int {
    Peaking   = 0,
    LowShelf  = 1,
    HighShelf = 2,
    HighPass  = 3,   // 2nd-order RBJ Butterworth HPF — `gainDB` is ignored
};

class AuralTuneEQEngine {
public:
    static constexpr int kMaxAutoEqFilters = 10;
    static constexpr int kMaxManualFilters = 20;
    // Loudness Compensation chain — fixed 4-section topology (low shelf 80 Hz,
    // peaking 180 Hz, peaking 3.2 kHz, high shelf 10 kHz). See LoudnessCompensator.h.
    static constexpr int kLoudnessCompSections = 4;
    static constexpr double kDefaultProfileOptimizedRate = 48000.0;

    // Validation bounds — used by both updateAutoEq and updateManualEq.
    static constexpr float kMaxAbsGainDB = 30.0f;

    // Range bounds for sample rates and per-callback frame counts. These are
    // enforced at the JNI boundary AND inside the engine (defense in depth) so
    // that direct C++ callers, fuzz harnesses, and any future direct-binding
    // host get the same protection as the Android JNI path.
    static constexpr int kMinSampleRateHz   = 8000;
    static constexpr int kMaxSampleRateHz   = 384000;
    static constexpr int kMaxProcessFrames  = 65536;

    // Soft limiter parameters (sample-by-sample asymptotic soft-knee).
    static constexpr float kLimiterThreshold = 0.95f;
    static constexpr float kLimiterHeadroom  = 0.05f;

    // Retire grace — minimum lifetime of a published snapshot after a newer
    // one has taken over. 500 ms is comfortably larger than any audio
    // callback (typical 5-20 ms, worst-case ~100 ms) and matches the Swift
    // AuralTune `vDSP_biquad_DestroySetup` deferred-free pattern.
    static constexpr std::chrono::milliseconds kRetireGraceMs{500};

    explicit AuralTuneEQEngine(double sampleRate);
    ~AuralTuneEQEngine();

    AuralTuneEQEngine(const AuralTuneEQEngine&) = delete;
    AuralTuneEQEngine& operator=(const AuralTuneEQEngine&) = delete;

    // ---- Control thread (main) ----
    //
    // Returns:
    //    0 on success.
    //   -1 on numeric validation failure (NaN, out-of-range freq/q/gain, count).
    //   -2 on filter-type validation failure.
    //
    // Pass count = 0 (any of frequencies / gainsDB / qs may be nullptr) to
    // clear the chain.
    int updateAutoEq(float preampDB,
                     bool enableLimiter,
                     double profileOptimizedRate,
                     const int* filterTypes,
                     const float* frequencies,
                     const float* gainsDB,
                     const float* qs,
                     int count);

    int updateManualEq(const float* frequencies,
                       const float* gainsDB,
                       const float* qs,
                       int count);

    void setAutoEqEnabled(bool e);
    void setManualEqEnabled(bool e);
    void setAutoEqPreampEnabled(bool e);

    // ---- Loudness Compensation (ISO 226:2023) ----
    //
    // Enables/disables the 4-section ISO 226 equal-loudness compensation
    // chain stage. When enabled and `setLoudnessCompensationVolume` has
    // been called with a non-reference volume, the engine applies a
    // frequency-dependent gain that boosts bass and high treble to
    // compensate for the ear's reduced sensitivity at lower listening
    // levels (Fletcher-Munson / ISO 226).
    void setLoudnessCompensationEnabled(bool e);

    // Update the compensation curve for a new system volume (0.0..1.0).
    // 1.0 maps to 80 phon (reference; cascade collapses to unity bypass).
    // 0.0 maps to 20 phon (heaviest compensation).
    //
    // No-op coalescing: if the requested phon differs by less than 1.0
    // from the last applied phon AND the chain is currently enabled, the
    // call is skipped (avoid redundant snapshot publishes during slider
    // drags).
    //
    // Returns 0 on success, -1 on numeric validation failure (NaN volume).
    int setLoudnessCompensationVolume(float systemVolume0to1);

    // ---- Loudness Equalizer (BS.1770 auto-leveler) ----
    //
    // Enables/disables the K-weighted auto-leveler chain stage. When
    // enabled, the engine continuously measures perceived loudness and
    // applies a slow gain envelope to keep the output near a target level
    // (default -12 dB). Quiet material is boosted (up to +15 dB), loud
    // peaks pass through a soft-knee compressor (default ratio 1.6).
    //
    // Settings are applied at the next snapshot publish. To customize
    // behavior, call `updateLoudnessEqSettings(...)` before enabling.
    void setLoudnessEqEnabled(bool e);

    // Update the auto-leveler configuration. The new instance replaces the
    // active one via the engine's snapshot publish + retire pattern. If the
    // chain is currently enabled the new instance starts processing at the
    // next callback; otherwise it sits idle until enabled.
    void updateLoudnessEqSettings(float targetLoudnessDb,
                                  float maxBoostDb,
                                  float maxCutDb,
                                  float compressionThresholdOffsetDb,
                                  float compressionRatio,
                                  float compressionKneeDb,
                                  float gainAttackMs,
                                  float gainReleaseMs);

    void updateSampleRate(int newRate);

    // ---- Audio thread ----
    //
    // pcm   : stereo interleaved float32, length = numFrames * 2.
    // returns 0 on success, -1 on bad arguments (no allocations either way).
    int process(float* pcm, int numFrames) noexcept;

    // Read AudioTrack's getUnderrunCount() delta since last call. Caller is
    // the Kotlin AudioPlayerService; this just propagates into our diagnostic
    // counter so the UI/telemetry layer sees a single unified xrun number.
    void recordXrun(int64_t deltaUnderrunFrames) noexcept;

    // ---- Diagnostics (atomic snapshot) ----
    struct Diagnostics {
        int64_t xrunCount;
        int64_t nonFiniteResetCount;
        int64_t sampleRateChangeCount;
        int64_t configSwapCount;
        int64_t totalProcessedFrames;
        // Tier B-2: "selected vs applied" telemetry.
        // appliedGeneration is sourced from the currently-published
        // EngineSnapshot and increments on every successful publish; UI
        // compares it against the generation it expected from its own
        // selectProfile call.
        // autoEqActiveCount reports the number of biquads currently active
        // in the AutoEQ chain. Reported as 0 when the AutoEQ master switch
        // is off — from the user's perspective no correction is being
        // applied, regardless of the cached count.
        int64_t appliedGeneration;
        int64_t autoEqActiveCount;
    };
    Diagnostics readDiagnostics() const noexcept;

    // ---- Tier B-2: applied-state snapshot ----
    //
    // Consolidated read of the "is this profile currently in effect?" state.
    // Used by the host UI to decide whether to render "X applied" vs
    // "X selected (waiting)". All fields are sourced from the currently-
    // published EngineSnapshot so the read is consistent across fields.
    struct AppliedSnapshot {
        uint64_t generation;
        bool     autoEqEnabled;
        int      autoEqFilterCount;
        bool     manualEnabled;
        bool     preampEnabled;
        float    preampLinearGain;
    };
    AppliedSnapshot readAppliedSnapshot() const noexcept;

    // ─── Public RBJ coefficient builders ─────────────────────────────────
    //
    // Stateless helpers — exposed for unit tests and for sibling DSP modules
    // (e.g. LoudnessCompensator, KWeightingFilter) that build cascades from
    // the same RBJ Audio EQ Cookbook formulae. Moving these to public has no
    // encapsulation impact since they neither read nor write engine state.
    static BiquadCoeffs peakingCoeffs(double freq, float gainDB, double q, double sampleRate);
    static BiquadCoeffs lowShelfCoeffs(double freq, float gainDB, double q, double sampleRate);
    static BiquadCoeffs highShelfCoeffs(double freq, float gainDB, double q, double sampleRate);
    // RBJ Audio EQ Cookbook — 2nd-order high-pass biquad. `gainDB` is unused
    // (parameterless filter; pass 0.0f). Q=1/√2 = Butterworth response.
    static BiquadCoeffs highPassCoeffs(double freq, double q, double sampleRate);

private:
    // Whole-config snapshot. Populated by control thread, read once per
    // callback by audio thread. POD layout — no pointers.
    struct EngineSnapshot {
        // Generation number — strictly increasing per publish. Audio thread
        // compares against `lastSeenGeneration_` to detect new snapshots and
        // honor any flags (delay reset etc.).
        uint64_t generation = 0;

        // True when this snapshot represents a sample-rate change or explicit
        // hard reset. Audio thread zeros every BiquadFilter's delay state
        // before processing the first callback that observes this snapshot.
        bool requestDelayReset = false;

        // Enable / control flags.
        bool manualOn       = false;
        bool autoOn         = false;
        bool preampOn       = true;
        bool limiterOn      = false;
        bool loudnessCompOn = false;     // Stage C: ISO 226 compensation toggle

        // Cascade lengths (0..max).
        int manualCount = 0;
        int autoEqCount = 0;

        // Linear preamp gain (already converted from dB).
        float autoEqPreampLinear = 1.0f;

        // Stage D: BS.1770 auto-leveler enable flag. The actual processor
        // instance lives in `activeLoudnessEq_` (separate atomic) because it
        // owns rich state (RMS ring buffer, envelope smoothers) that doesn't
        // belong inside a POD snapshot. The bool here tells the audio thread
        // whether to dereference the LE pointer at all.
        bool loudnessEqOn = false;

        // Coefficients. Fixed-size arrays — POD.
        BiquadCoeffs manualCoeffs[kMaxManualFilters];
        BiquadCoeffs autoEqCoeffs[kMaxAutoEqFilters];
        // Stage C: ISO 226 compensation cascade — always 4 sections, unity
        // when at reference phon (volume = 1.0) so chain runs free of cost.
        BiquadCoeffs loudnessCompCoeffs[kLoudnessCompSections];
    };

    // Heap-allocate a new snapshot, copy-initialized from the currently
    // published one (so callers only need to set the fields they're
    // mutating). Caller is the control thread; the returned pointer is
    // exclusively owned until publishSnapshot() takes ownership.
    EngineSnapshot* allocateSnapshotFromCurrent() const;

    // Atomic-publish: store the new pointer, retire the previous one with a
    // grace deadline, and sweep any expired retirees.
    void publishSnapshot(EngineSnapshot* snap);

    // Sweep retire queue: free any snapshot whose grace deadline has passed.
    void sweepRetireQueue();

    // Recompute all coefficient arrays from the held params at currentRate_,
    // writing into the destination snapshot. Used by sample-rate change.
    void writeAllCoeffsInto(EngineSnapshot& dst) const;

    // Numeric validation helpers (control thread).
    static bool isFiniteFloat(float v) noexcept;
    static bool isFiniteDouble(double v) noexcept;

    // Pre-warp digital frequency from sourceRate to targetRate via inverse +
    // forward bilinear. Verbatim port of BiquadMath.preWarpFrequency.
    static double preWarpFrequency(double freq, double sourceRate, double targetRate);

    // Resolve final coefficients for an AutoEQ filter, including pre-warp +
    // Nyquist guard (returns unity coeffs if filter cannot be realized).
    BiquadCoeffs buildAutoEqFilter(EqFilterType type,
                                   double frequency,
                                   float gainDB,
                                   double q) const;

    // ---- Control-thread state (NOT read by audio thread) ----

    // Sample rate — control thread reads/writes; audio thread reads from snapshot.
    double currentRate_;

    // AutoEQ profile sample rate (the rate the profile was optimized for).
    double autoEqProfileRate_ = kDefaultProfileOptimizedRate;

    // Manual EQ params (raw user input — used to recompute coefficients on
    // sample-rate change).
    struct ManualParams {
        float frequency = 1000.0f;
        float gainDB    = 0.0f;
        float q         = 1.0f;
    };
    std::array<ManualParams, kMaxManualFilters> manualParams_{};
    int manualActiveCount_ = 0;

    // AutoEQ params.
    struct AutoEqParams {
        EqFilterType type = EqFilterType::Peaking;
        float frequency   = 1000.0f;
        float gainDB      = 0.0f;
        float q           = 1.0f;
    };
    std::array<AutoEqParams, kMaxAutoEqFilters> autoEqParams_{};
    int autoEqActiveCount_ = 0;
    float autoEqPreampDB_ = 0.0f;

    // Stage C: Loudness Compensation cached state — used to recompute
    // coefficients on sample-rate change and to coalesce no-op volume updates.
    bool   loudnessCompEnabled_ = false;
    double loudnessCompPhon_    = 80.0;   // 80 phon = unity bypass (reference)

    // Stage D: BS.1770 auto-leveler — cached settings. The actual instance
    // is heap-allocated in `activeLoudnessEq_` (own retire queue).
    LoudnessEqualizerSettings loudnessEqSettings_{};

    // Lock that serializes control-thread updates (snapshot writer side).
    // Audio thread NEVER takes this lock.
    mutable std::mutex updateMutex_;

    // ---- Snapshot machinery (heap + deferred retire) ----
    //
    // activeSnapshot_ : the snapshot the audio thread reads. Heap-owned. A
    //                   non-null value is established at construction; updates
    //                   replace it via atomic exchange.
    // retireQueue_    : control-thread-only list of {pointer, deadline} pairs.
    //                   We never free a snapshot until at least kRetireGraceMs
    //                   after the publish that demoted it.
    // generationCounter_ : monotonic, increments on every publish.
    std::atomic<EngineSnapshot*> activeSnapshot_;
    struct RetireEntry {
        EngineSnapshot* ptr;
        std::chrono::steady_clock::time_point deadline;
    };
    std::vector<RetireEntry> retireQueue_;  // control thread only
    uint64_t generationCounter_ = 0;        // control thread only

    // ---- Audio-thread-owned biquad state ----
    std::array<BiquadFilter, kMaxManualFilters> manualChain_{};
    std::array<BiquadFilter, kMaxAutoEqFilters> autoEqChain_{};
    // Stage C: ISO 226 compensation chain — 4 fixed biquads.
    std::array<BiquadFilter, kLoudnessCompSections> loudnessCompChain_{};
    // Stage D: BS.1770 auto-leveler instance. Heap-allocated, atomic-published
    // independently of EngineSnapshot. Audio thread loads once per callback.
    std::atomic<LoudnessEqualizer*> activeLoudnessEq_{nullptr};
    // Retire queue for old LE instances — same grace pattern as EngineSnapshot.
    struct LoudnessEqRetireEntry {
        LoudnessEqualizer* ptr;
        std::chrono::steady_clock::time_point deadline;
    };
    std::vector<LoudnessEqRetireEntry> loudnessEqRetireQueue_;   // control thread only
    void sweepLoudnessEqRetireQueue();   // control thread only

    uint64_t lastSeenGeneration_ = 0; // audio thread only

    // ---- Diagnostics counters (relaxed atomic) ----
    std::atomic<int64_t> diagXrun_{0};
    std::atomic<int64_t> diagNonFiniteReset_{0};
    std::atomic<int64_t> diagSampleRateChange_{0};
    std::atomic<int64_t> diagConfigSwap_{0};
    std::atomic<int64_t> diagTotalFrames_{0};
};

} // namespace auraltune::audio

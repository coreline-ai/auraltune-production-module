// AuralTuneEQEngine.cpp
//
// Implementation of the native EQ engine. See header for the threading and
// snapshot-publish contract.
//
// Coefficient math is a verbatim port of AuralTune/Audio/EQ/BiquadMath.swift —
// the exact RBJ cookbook formulas, normalized by a0, plus the inverse +
// forward bilinear pre-warp.

#include "AuralTuneEQEngine.h"

#if defined(__ANDROID__)
#include <android/log.h>
#endif
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace auraltune::audio {

namespace {
constexpr const char* kTag = "AuralTuneEQEngine";

inline void logInfo(const char* msg) {
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", msg);
#else
    (void)kTag; (void)msg;
#endif
}
} // namespace

// ---------------------------------------------------------------------------
// Construction
// ---------------------------------------------------------------------------

AuralTuneEQEngine::AuralTuneEQEngine(double sampleRate)
    : currentRate_(sampleRate > 0.0 ? sampleRate : 48000.0) {
    // Bootstrap with a heap snapshot of "all disabled, unity coeffs". The
    // audio thread can run immediately after construction and produce
    // silence-equivalent passthrough until the first publish.
    auto* boot = new EngineSnapshot{};
    for (auto& c : boot->manualCoeffs) c = BiquadCoeffs::unity();
    for (auto& c : boot->autoEqCoeffs) c = BiquadCoeffs::unity();
    boot->generation = 0;
    activeSnapshot_.store(boot, std::memory_order_release);
}

AuralTuneEQEngine::~AuralTuneEQEngine() {
    // Caller (Kotlin AudioEngine.close()) guarantees the audio thread has
    // been joined before destruction (see AudioPlayerService.stop() lifecycle).
    // We can therefore free the active snapshot immediately without waiting
    // for the retire grace.
    delete activeSnapshot_.load(std::memory_order_relaxed);
    for (auto& entry : retireQueue_) {
        delete entry.ptr;
    }
}

// ---------------------------------------------------------------------------
// Validation helpers
// ---------------------------------------------------------------------------

bool AuralTuneEQEngine::isFiniteFloat(float v) noexcept { return std::isfinite(v); }
bool AuralTuneEQEngine::isFiniteDouble(double v) noexcept { return std::isfinite(v); }

// ---------------------------------------------------------------------------
// RBJ coefficient builders — direct port of BiquadMath.swift.
// All return [b0, b1, b2, a1, a2] normalized by a0.
// ---------------------------------------------------------------------------

BiquadCoeffs AuralTuneEQEngine::peakingCoeffs(double frequency,
                                             float gainDB,
                                             double q,
                                             double sampleRate) {
    const double A     = std::pow(10.0, static_cast<double>(gainDB) / 40.0);
    const double omega = 2.0 * M_PI * frequency / sampleRate;
    const double sinW  = std::sin(omega);
    const double cosW  = std::cos(omega);
    const double alpha = sinW / (2.0 * q);

    const double b0 = 1.0 + alpha * A;
    const double b1 = -2.0 * cosW;
    const double b2 = 1.0 - alpha * A;
    const double a0 = 1.0 + alpha / A;
    const double a1 = -2.0 * cosW;
    const double a2 = 1.0 - alpha / A;

    BiquadCoeffs c;
    c.b0 = b0 / a0;
    c.b1 = b1 / a0;
    c.b2 = b2 / a0;
    c.a1 = a1 / a0;
    c.a2 = a2 / a0;
    return c;
}

BiquadCoeffs AuralTuneEQEngine::lowShelfCoeffs(double frequency,
                                              float gainDB,
                                              double q,
                                              double sampleRate) {
    const double A             = std::pow(10.0, static_cast<double>(gainDB) / 40.0);
    const double omega         = 2.0 * M_PI * frequency / sampleRate;
    const double sinW          = std::sin(omega);
    const double cosW          = std::cos(omega);
    const double alpha         = sinW / (2.0 * q);
    const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;

    const double b0 = A * ((A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha);
    const double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW);
    const double b2 = A * ((A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha);
    const double a0 = (A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha;
    const double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW);
    const double a2 = (A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha;

    BiquadCoeffs c;
    c.b0 = b0 / a0;
    c.b1 = b1 / a0;
    c.b2 = b2 / a0;
    c.a1 = a1 / a0;
    c.a2 = a2 / a0;
    return c;
}

BiquadCoeffs AuralTuneEQEngine::highShelfCoeffs(double frequency,
                                               float gainDB,
                                               double q,
                                               double sampleRate) {
    const double A             = std::pow(10.0, static_cast<double>(gainDB) / 40.0);
    const double omega         = 2.0 * M_PI * frequency / sampleRate;
    const double sinW          = std::sin(omega);
    const double cosW          = std::cos(omega);
    const double alpha         = sinW / (2.0 * q);
    const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;

    const double b0 = A * ((A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha);
    const double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW);
    const double b2 = A * ((A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha);
    const double a0 = (A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha;
    const double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW);
    const double a2 = (A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha;

    BiquadCoeffs c;
    c.b0 = b0 / a0;
    c.b1 = b1 / a0;
    c.b2 = b2 / a0;
    c.a1 = a1 / a0;
    c.a2 = a2 / a0;
    return c;
}

// Inverse-bilinear (digital→analog at sourceRate) followed by forward-bilinear
// (analog→digital at targetRate). Matches BiquadMath.preWarpFrequency.
double AuralTuneEQEngine::preWarpFrequency(double freq,
                                          double sourceRate,
                                          double targetRate) {
    const double fAnalog = (sourceRate / M_PI) * std::tan(M_PI * freq / sourceRate);
    return (targetRate / M_PI) * std::atan(M_PI * fAnalog / targetRate);
}

BiquadCoeffs AuralTuneEQEngine::buildAutoEqFilter(EqFilterType type,
                                                 double frequency,
                                                 float gainDB,
                                                 double q) const {
    // P0 source-Nyquist guard: if the profile filter is at or above the
    // profile rate's Nyquist before any pre-warp, the bilinear can't realize
    // it. Reject up front so we don't depend on numerical edge cases of
    // tan() / atan() with arguments at or beyond π/2. Tier C-1 (defense in
    // depth alongside the post-warp target-Nyquist guard below).
    if (!std::isfinite(frequency) || frequency <= 0.0
        || frequency >= autoEqProfileRate_ / 2.0) {
        return BiquadCoeffs::unity();
    }

    double effectiveFreq = frequency;
    const bool needsPreWarp =
        std::fabs(autoEqProfileRate_ - currentRate_) > 1.0;
    if (needsPreWarp) {
        effectiveFreq = preWarpFrequency(frequency, autoEqProfileRate_, currentRate_);
    }
    if (effectiveFreq <= 0.0 || effectiveFreq >= currentRate_ / 2.0) {
        return BiquadCoeffs::unity();
    }
    switch (type) {
        case EqFilterType::Peaking:
            return peakingCoeffs(effectiveFreq, gainDB, q, currentRate_);
        case EqFilterType::LowShelf:
            return lowShelfCoeffs(effectiveFreq, gainDB, q, currentRate_);
        case EqFilterType::HighShelf:
            return highShelfCoeffs(effectiveFreq, gainDB, q, currentRate_);
    }
    return BiquadCoeffs::unity();
}

// ---------------------------------------------------------------------------
// Snapshot machinery (control thread, serialized by updateMutex_).
//
// Heap + deferred retire pattern. Each publish allocates a fresh snapshot,
// atomic-exchanges activeSnapshot_, and queues the previous pointer for
// deletion after kRetireGraceMs. The audio thread sees a stable pointer for
// the entire callback because its lifetime is provably longer than any
// audio-callback period.
// ---------------------------------------------------------------------------

AuralTuneEQEngine::EngineSnapshot*
AuralTuneEQEngine::allocateSnapshotFromCurrent() const {
    const EngineSnapshot* prev = activeSnapshot_.load(std::memory_order_acquire);
    // Copy-construct so callers only need to overwrite the fields they care
    // about (e.g. setAutoEqEnabled flips one bool, leaves coefficients alone).
    return new EngineSnapshot(*prev);
}

void AuralTuneEQEngine::publishSnapshot(EngineSnapshot* snap) {
    snap->generation = ++generationCounter_;

    // Atomic publish — release semantics so the audio thread observes a
    // fully-initialized snapshot.
    EngineSnapshot* old = activeSnapshot_.exchange(snap, std::memory_order_acq_rel);

    if (old != nullptr) {
        // Defer deletion. The audio thread may still be using `old` for the
        // remainder of its current callback; kRetireGraceMs is comfortably
        // longer than the worst-case AudioTrack callback (~100 ms with the
        // legacy buffer path), so by the time we delete, no audio thread
        // can possibly still hold the pointer.
        retireQueue_.push_back(
            { old, std::chrono::steady_clock::now() + kRetireGraceMs });
    }
    sweepRetireQueue();

    diagConfigSwap_.fetch_add(1, std::memory_order_relaxed);
}

void AuralTuneEQEngine::sweepRetireQueue() {
    const auto now = std::chrono::steady_clock::now();
    auto it = retireQueue_.begin();
    while (it != retireQueue_.end()) {
        if (it->deadline <= now) {
            delete it->ptr;
            it = retireQueue_.erase(it);
        } else {
            ++it;
        }
    }
}

void AuralTuneEQEngine::writeAllCoeffsInto(EngineSnapshot& dst) const {
    // Manual chain — peaking only, no pre-warp (user-driven for current rate).
    for (int i = 0; i < manualActiveCount_; ++i) {
        const auto& p = manualParams_[i];
        if (p.frequency <= 0.0f ||
            p.frequency >= static_cast<float>(currentRate_ / 2.0)) {
            dst.manualCoeffs[i] = BiquadCoeffs::unity();
        } else {
            dst.manualCoeffs[i] = peakingCoeffs(static_cast<double>(p.frequency),
                                                p.gainDB,
                                                static_cast<double>(p.q),
                                                currentRate_);
        }
    }
    for (int i = manualActiveCount_; i < kMaxManualFilters; ++i) {
        dst.manualCoeffs[i] = BiquadCoeffs::unity();
    }

    // AutoEQ chain — buildAutoEqFilter handles pre-warp + Nyquist guard.
    for (int i = 0; i < autoEqActiveCount_; ++i) {
        const auto& p = autoEqParams_[i];
        dst.autoEqCoeffs[i] = buildAutoEqFilter(p.type,
                                                 static_cast<double>(p.frequency),
                                                 p.gainDB,
                                                 static_cast<double>(p.q));
    }
    for (int i = autoEqActiveCount_; i < kMaxAutoEqFilters; ++i) {
        dst.autoEqCoeffs[i] = BiquadCoeffs::unity();
    }
}

// ---------------------------------------------------------------------------
// Public update API (control thread, serialized by updateMutex_).
//
// Pattern: validate → mutate cached params → acquire write slot → fully
// populate → publish atomically. The audio thread either sees the OLD
// snapshot end-to-end or the NEW snapshot end-to-end. Never a hybrid.
// ---------------------------------------------------------------------------

int AuralTuneEQEngine::updateAutoEq(float preampDB,
                                   bool enableLimiter,
                                   double profileOptimizedRate,
                                   const int* filterTypes,
                                   const float* frequencies,
                                   const float* gainsDB,
                                   const float* qs,
                                   int count) {
    if (count < 0 || count > kMaxAutoEqFilters) return -1;
    if (!isFiniteFloat(preampDB) || std::fabs(preampDB) > kMaxAbsGainDB) return -1;
    if (!isFiniteDouble(profileOptimizedRate)
        || profileOptimizedRate < static_cast<double>(kMinSampleRateHz)
        || profileOptimizedRate > static_cast<double>(kMaxSampleRateHz)) {
        return -1;
    }

    // Validate every input filter before mutating any state.
    for (int i = 0; i < count; ++i) {
        const float freq = frequencies ? frequencies[i] : NAN;
        const float gain = gainsDB     ? gainsDB[i]     : NAN;
        const float q    = qs          ? qs[i]          : NAN;
        const int   ft   = filterTypes ? filterTypes[i] : -1;

        if (!isFiniteFloat(freq) || freq <= 0.0f) return -1;
        if (!isFiniteFloat(q)    || q    <= 0.0f) return -1;
        if (!isFiniteFloat(gain) || std::fabs(gain) > kMaxAbsGainDB) return -1;
        if (ft < 0 || ft > 2) return -2;
    }

    std::lock_guard<std::mutex> lock(updateMutex_);

    // Mutate cached params (control-thread only state, no race).
    autoEqProfileRate_ = profileOptimizedRate;
    autoEqPreampDB_    = preampDB;
    autoEqActiveCount_ = count;
    for (int i = 0; i < count; ++i) {
        autoEqParams_[i].type      = static_cast<EqFilterType>(filterTypes[i]);
        autoEqParams_[i].frequency = frequencies[i];
        autoEqParams_[i].gainDB    = gainsDB[i];
        autoEqParams_[i].q         = qs[i];
    }

    // Build the new snapshot off the previously published one (so flags we
    // are not changing — manual config, manual enable, etc. — carry over).
    EngineSnapshot* slot = allocateSnapshotFromCurrent();
    slot->limiterOn          = enableLimiter;
    slot->autoEqCount        = count;
    slot->autoEqPreampLinear = slot->preampOn
                                 ? std::pow(10.0f, preampDB / 20.0f)
                                 : 1.0f;
    slot->requestDelayReset  = false;

    // Refresh AutoEQ coefficients only (manual untouched).
    for (int i = 0; i < count; ++i) {
        const auto& p = autoEqParams_[i];
        slot->autoEqCoeffs[i] = buildAutoEqFilter(p.type,
                                                   static_cast<double>(p.frequency),
                                                   p.gainDB,
                                                   static_cast<double>(p.q));
    }
    for (int i = count; i < kMaxAutoEqFilters; ++i) {
        slot->autoEqCoeffs[i] = BiquadCoeffs::unity();
    }

    publishSnapshot(slot);
    return 0;
}

int AuralTuneEQEngine::updateManualEq(const float* frequencies,
                                     const float* gainsDB,
                                     const float* qs,
                                     int count) {
    if (count < 0 || count > kMaxManualFilters) return -1;

    for (int i = 0; i < count; ++i) {
        const float freq = frequencies ? frequencies[i] : NAN;
        const float gain = gainsDB     ? gainsDB[i]     : NAN;
        const float q    = qs          ? qs[i]          : NAN;
        if (!isFiniteFloat(freq) || freq <= 0.0f) return -1;
        if (!isFiniteFloat(q)    || q    <= 0.0f) return -1;
        if (!isFiniteFloat(gain) || std::fabs(gain) > kMaxAbsGainDB) return -1;
    }

    std::lock_guard<std::mutex> lock(updateMutex_);

    manualActiveCount_ = count;
    for (int i = 0; i < count; ++i) {
        manualParams_[i].frequency = frequencies[i];
        manualParams_[i].gainDB    = gainsDB[i];
        manualParams_[i].q         = qs[i];
    }

    EngineSnapshot* slot = allocateSnapshotFromCurrent();
    slot->manualCount       = count;
    slot->requestDelayReset = false;

    for (int i = 0; i < count; ++i) {
        const auto& p = manualParams_[i];
        if (p.frequency <= 0.0f ||
            p.frequency >= static_cast<float>(currentRate_ / 2.0)) {
            slot->manualCoeffs[i] = BiquadCoeffs::unity();
        } else {
            slot->manualCoeffs[i] = peakingCoeffs(static_cast<double>(p.frequency),
                                                  p.gainDB,
                                                  static_cast<double>(p.q),
                                                  currentRate_);
        }
    }
    for (int i = count; i < kMaxManualFilters; ++i) {
        slot->manualCoeffs[i] = BiquadCoeffs::unity();
    }

    publishSnapshot(slot);
    return 0;
}

void AuralTuneEQEngine::setAutoEqEnabled(bool e) {
    std::lock_guard<std::mutex> lock(updateMutex_);
    const EngineSnapshot* prev = activeSnapshot_.load(std::memory_order_acquire);
    if (prev->autoOn == e) return;
    EngineSnapshot* slot = allocateSnapshotFromCurrent();
    slot->autoOn = e;
    slot->requestDelayReset = false;
    publishSnapshot(slot);
}

void AuralTuneEQEngine::setManualEqEnabled(bool e) {
    std::lock_guard<std::mutex> lock(updateMutex_);
    const EngineSnapshot* prev = activeSnapshot_.load(std::memory_order_acquire);
    if (prev->manualOn == e) return;
    EngineSnapshot* slot = allocateSnapshotFromCurrent();
    slot->manualOn = e;
    slot->requestDelayReset = false;
    publishSnapshot(slot);
}

void AuralTuneEQEngine::setAutoEqPreampEnabled(bool e) {
    std::lock_guard<std::mutex> lock(updateMutex_);
    const EngineSnapshot* prev = activeSnapshot_.load(std::memory_order_acquire);
    if (prev->preampOn == e) return;
    EngineSnapshot* slot = allocateSnapshotFromCurrent();
    slot->preampOn          = e;
    slot->autoEqPreampLinear = e ? std::pow(10.0f, autoEqPreampDB_ / 20.0f) : 1.0f;
    slot->requestDelayReset = false;
    publishSnapshot(slot);
}

void AuralTuneEQEngine::updateSampleRate(int newRate) {
    // Tier C-1: defense-in-depth bounds. JNI also validates, but direct
    // C++ callers must get the same protection.
    if (newRate < kMinSampleRateHz || newRate > kMaxSampleRateHz) return;
    if (newRate == static_cast<int>(currentRate_)) return;

    std::lock_guard<std::mutex> lock(updateMutex_);
    currentRate_ = static_cast<double>(newRate);

    // Build a snapshot with all coefficients recomputed for the new rate
    // and the delay-reset flag set. The audio thread will reset its delay
    // state on its own at the start of the first callback observing this
    // new generation — control thread NEVER touches delay state.
    EngineSnapshot* slot = allocateSnapshotFromCurrent();
    slot->requestDelayReset = true;
    writeAllCoeffsInto(*slot);
    publishSnapshot(slot);

    diagSampleRateChange_.fetch_add(1, std::memory_order_relaxed);

    char buf[96];
    std::snprintf(buf, sizeof(buf), "sample rate -> %d Hz", newRate);
    logInfo(buf);
}

// ---------------------------------------------------------------------------
// Audio thread — process()
// ---------------------------------------------------------------------------

int AuralTuneEQEngine::process(float* pcm, int numFrames) noexcept {
    // Tier C-1: defense-in-depth bounds. JNI also validates, but direct
    // C++ callers must get the same protection.
    if (pcm == nullptr || numFrames < 1 || numFrames > kMaxProcessFrames) return -1;

    // Single atomic load — this snapshot is stable for the entire callback.
    const EngineSnapshot* s = activeSnapshot_.load(std::memory_order_acquire);

    // Detect new generation. If this snapshot requests a delay-state reset,
    // honor it before processing. Reset is audio-thread-owned: only here.
    if (s->generation != lastSeenGeneration_) {
        if (s->requestDelayReset) {
            for (auto& f : manualChain_) f.reset();
            for (auto& f : autoEqChain_) f.reset();
        }
        lastSeenGeneration_ = s->generation;
    }

    // 1. Manual cascade.
    if (s->manualOn) {
        const int n = (s->manualCount < kMaxManualFilters)
                          ? s->manualCount : kMaxManualFilters;
        for (int i = 0; i < n; ++i) {
            manualChain_[i].processStereoInterleaved(s->manualCoeffs[i], pcm, numFrames);
        }
    }

    // 2 + 3. AutoEQ preamp + cascade.
    if (s->autoOn) {
        if (s->preampOn && s->autoEqPreampLinear != 1.0f) {
            const float g = s->autoEqPreampLinear;
            const int total = numFrames * 2;
            for (int i = 0; i < total; ++i) {
                pcm[i] *= g;
            }
        }
        const int n = (s->autoEqCount < kMaxAutoEqFilters)
                          ? s->autoEqCount : kMaxAutoEqFilters;
        for (int i = 0; i < n; ++i) {
            autoEqChain_[i].processStereoInterleaved(s->autoEqCoeffs[i], pcm, numFrames);
        }
    }

    // 4. Soft limiter — sample-by-sample asymptotic soft knee.
    if (s->limiterOn) {
        constexpr float thr = kLimiterThreshold;
        constexpr float hr  = kLimiterHeadroom;
        const int total     = numFrames * 2;
        for (int i = 0; i < total; ++i) {
            const float x  = pcm[i];
            const float ax = std::fabs(x);
            if (ax > thr) {
                const float over = ax - thr;
                const float comp = thr + hr * (1.0f - std::exp(-over / hr));
                pcm[i] = std::copysign(comp, x);
            }
        }
    }

    // 5. NaN guard — fast first-frame check. Audio-thread context, so we
    //    can directly reset our biquad delay state. -fno-finite-math-only
    //    ensures the optimizer cannot dead-code this.
    if (numFrames >= 1 &&
        (!std::isfinite(pcm[0]) || !std::isfinite(pcm[1]))) {
        for (auto& f : manualChain_) f.reset();
        for (auto& f : autoEqChain_) f.reset();
        std::memset(pcm, 0, static_cast<size_t>(numFrames) * 2u * sizeof(float));
        diagNonFiniteReset_.fetch_add(1, std::memory_order_relaxed);
    }

    diagTotalFrames_.fetch_add(numFrames, std::memory_order_relaxed);
    return 0;
}

void AuralTuneEQEngine::recordXrun(int64_t deltaUnderrunFrames) noexcept {
    if (deltaUnderrunFrames <= 0) return;
    diagXrun_.fetch_add(deltaUnderrunFrames, std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

AuralTuneEQEngine::Diagnostics AuralTuneEQEngine::readDiagnostics() const noexcept {
    Diagnostics d{};
    d.xrunCount             = diagXrun_.load(std::memory_order_relaxed);
    d.nonFiniteResetCount   = diagNonFiniteReset_.load(std::memory_order_relaxed);
    d.sampleRateChangeCount = diagSampleRateChange_.load(std::memory_order_relaxed);
    d.configSwapCount       = diagConfigSwap_.load(std::memory_order_relaxed);
    d.totalProcessedFrames  = diagTotalFrames_.load(std::memory_order_relaxed);

    // Tier B-2: read appliedGeneration / autoEqActiveCount from the currently
    // published snapshot. autoEqActiveCount reports 0 when AutoEQ is disabled
    // — even if the cached cascade still has N coefficients, the user sees
    // no correction.
    const EngineSnapshot* s = activeSnapshot_.load(std::memory_order_acquire);
    if (s != nullptr) {
        d.appliedGeneration  = static_cast<int64_t>(s->generation);
        d.autoEqActiveCount  = s->autoOn ? static_cast<int64_t>(s->autoEqCount) : 0;
    } else {
        d.appliedGeneration  = 0;
        d.autoEqActiveCount  = 0;
    }
    return d;
}

AuralTuneEQEngine::AppliedSnapshot
AuralTuneEQEngine::readAppliedSnapshot() const noexcept {
    AppliedSnapshot a{};
    const EngineSnapshot* s = activeSnapshot_.load(std::memory_order_acquire);
    if (s == nullptr) {
        a.generation         = 0;
        a.autoEqEnabled      = false;
        a.autoEqFilterCount  = 0;
        a.manualEnabled      = false;
        a.preampEnabled      = false;
        a.preampLinearGain   = 1.0f;
        return a;
    }
    a.generation         = s->generation;
    a.autoEqEnabled      = s->autoOn;
    a.autoEqFilterCount  = s->autoEqCount;
    a.manualEnabled      = s->manualOn;
    a.preampEnabled      = s->preampOn;
    a.preampLinearGain   = s->autoEqPreampLinear;
    return a;
}

} // namespace auraltune::audio

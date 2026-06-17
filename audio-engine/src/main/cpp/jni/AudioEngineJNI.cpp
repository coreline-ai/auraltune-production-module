// AudioEngineJNI.cpp
//
// JNI bridge for com.coreline.audio.AudioEngine. Handle-based lifetime — every
// native method takes a jlong handle that wraps an AuralTuneEQEngine*.
//
// Validation policy: never trust JNI input. We check handle != 0, array length
// agreements, MAX caps for both chains, DirectByteBuffer capacity vs requested
// numFrames, and so on. On any validation failure we return a negative error
// code; we never throw across the JNI boundary except for nativeCreate failure
// (where there is no other channel).

#include <android/log.h>
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <new>

#include "AuralTuneEQEngine.h"

using auraltune::audio::AuralTuneEQEngine;

namespace {

constexpr const char* kJniTag = "AudioEngineJNI";

constexpr int kMaxAutoEqFilters = AuralTuneEQEngine::kMaxAutoEqFilters;
constexpr int kMaxManualFilters = AuralTuneEQEngine::kMaxManualFilters;
constexpr int kMinSampleRateHz  = AuralTuneEQEngine::kMinSampleRateHz;
constexpr int kMaxSampleRateHz  = AuralTuneEQEngine::kMaxSampleRateHz;
constexpr int kMaxProcessFrames = AuralTuneEQEngine::kMaxProcessFrames;

inline AuralTuneEQEngine* fromHandle(jlong handle) {
    return reinterpret_cast<AuralTuneEQEngine*>(static_cast<uintptr_t>(handle));
}

inline jlong toHandle(AuralTuneEQEngine* p) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(p));
}

// RAII wrapper for primitive array critical pointers. We use GetXArrayElements
// (not GetPrimitiveArrayCritical) so our update path remains compatible with
// any future logging / Java callbacks. JNI_ABORT means the JVM does NOT copy
// modifications back, since we never write into the borrowed buffer.
template <typename ElemT>
struct PrimitiveArrayLease {
    JNIEnv* env;
    jarray  arr;
    ElemT*  data;

    PrimitiveArrayLease(JNIEnv* e, jarray a, ElemT* d)
        : env(e), arr(a), data(d) {}

    ~PrimitiveArrayLease() = default;
    PrimitiveArrayLease(const PrimitiveArrayLease&) = delete;
    PrimitiveArrayLease& operator=(const PrimitiveArrayLease&) = delete;
};

} // namespace

extern "C" {

// ---------------------------------------------------------------------------
// nativeCreate / nativeDestroy
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_coreline_audio_AudioEngine_nativeCreate(JNIEnv* /*env*/,
                                                 jobject /*thiz*/,
                                                 jint sampleRate) {
    // Tier C-1: reject out-of-range sampleRate before allocating the engine.
    // Returning 0 = null handle = construction failure (Kotlin side throws
    // on `require(handle != 0L)`).
    if (sampleRate < kMinSampleRateHz || sampleRate > kMaxSampleRateHz) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag,
                            "nativeCreate: sampleRate %d out of [%d, %d]",
                            sampleRate, kMinSampleRateHz, kMaxSampleRateHz);
        return 0;
    }
    auto* engine = new (std::nothrow) AuralTuneEQEngine(static_cast<double>(sampleRate));
    if (engine == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag,
                            "nativeCreate: allocation failed");
        return 0;
    }
    return toHandle(engine);
}

JNIEXPORT void JNICALL
Java_com_coreline_audio_AudioEngine_nativeDestroy(JNIEnv* /*env*/,
                                                  jobject /*thiz*/,
                                                  jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        delete engine;
    }
}

// ---------------------------------------------------------------------------
// nativeUpdateAutoEq
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_coreline_audio_AudioEngine_nativeUpdateAutoEq(JNIEnv* env,
                                                       jobject /*thiz*/,
                                                       jlong handle,
                                                       jfloat preampDB,
                                                       jboolean enableLimiter,
                                                       jdouble profileOptimizedRate,
                                                       jintArray filterTypes,
                                                       jfloatArray frequencies,
                                                       jfloatArray gainsDB,
                                                       jfloatArray qFactors) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return -1;
    if (filterTypes == nullptr || frequencies == nullptr ||
        gainsDB == nullptr || qFactors == nullptr) {
        return -1;
    }

    // Tier C-1: range-check profileOptimizedRate at the JNI boundary so
    // pathological values (e.g. 1e9 from a corrupt JSON) are rejected before
    // touching engine state. The engine also re-checks finite + range.
    if (!std::isfinite(profileOptimizedRate)
        || profileOptimizedRate < static_cast<jdouble>(kMinSampleRateHz)
        || profileOptimizedRate > static_cast<jdouble>(kMaxSampleRateHz)) {
        return -1;
    }

    const jsize tCount = env->GetArrayLength(filterTypes);
    const jsize fCount = env->GetArrayLength(frequencies);
    const jsize gCount = env->GetArrayLength(gainsDB);
    const jsize qCount = env->GetArrayLength(qFactors);
    if (tCount != fCount || fCount != gCount || gCount != qCount) {
        return -1;
    }
    if (tCount < 0 || tCount > kMaxAutoEqFilters) {
        return -1;
    }

    // Empty-update fast path — no need to lease arrays.
    if (tCount == 0) {
        return engine->updateAutoEq(preampDB,
                                    enableLimiter == JNI_TRUE,
                                    profileOptimizedRate,
                                    nullptr, nullptr, nullptr, nullptr,
                                    0);
    }

    jint*   types = env->GetIntArrayElements(filterTypes, nullptr);
    jfloat* freqs = env->GetFloatArrayElements(frequencies, nullptr);
    jfloat* gains = env->GetFloatArrayElements(gainsDB, nullptr);
    jfloat* qs    = env->GetFloatArrayElements(qFactors, nullptr);

    if (types == nullptr || freqs == nullptr || gains == nullptr || qs == nullptr) {
        if (types) env->ReleaseIntArrayElements(filterTypes, types, JNI_ABORT);
        if (freqs) env->ReleaseFloatArrayElements(frequencies, freqs, JNI_ABORT);
        if (gains) env->ReleaseFloatArrayElements(gainsDB, gains, JNI_ABORT);
        if (qs)    env->ReleaseFloatArrayElements(qFactors, qs, JNI_ABORT);
        return -1;
    }

    // jint may be wider than int on some platforms — copy into int buffer.
    int filterTypeBuf[kMaxAutoEqFilters];
    for (jsize i = 0; i < tCount; ++i) {
        filterTypeBuf[i] = static_cast<int>(types[i]);
    }

    const int rc = engine->updateAutoEq(preampDB,
                                        enableLimiter == JNI_TRUE,
                                        profileOptimizedRate,
                                        filterTypeBuf,
                                        freqs,
                                        gains,
                                        qs,
                                        static_cast<int>(tCount));

    env->ReleaseIntArrayElements(filterTypes, types, JNI_ABORT);
    env->ReleaseFloatArrayElements(frequencies, freqs, JNI_ABORT);
    env->ReleaseFloatArrayElements(gainsDB, gains, JNI_ABORT);
    env->ReleaseFloatArrayElements(qFactors, qs, JNI_ABORT);

    return rc;
}

// ---------------------------------------------------------------------------
// nativeUpdateManualEq
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_coreline_audio_AudioEngine_nativeUpdateManualEq(JNIEnv* env,
                                                         jobject /*thiz*/,
                                                         jlong handle,
                                                         jfloatArray frequencies,
                                                         jfloatArray gainsDB,
                                                         jfloatArray qFactors) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return -1;
    if (frequencies == nullptr || gainsDB == nullptr || qFactors == nullptr) {
        return -1;
    }

    const jsize fCount = env->GetArrayLength(frequencies);
    const jsize gCount = env->GetArrayLength(gainsDB);
    const jsize qCount = env->GetArrayLength(qFactors);
    if (fCount != gCount || gCount != qCount) return -1;
    if (fCount < 0 || fCount > kMaxManualFilters) return -1;

    if (fCount == 0) {
        return engine->updateManualEq(nullptr, nullptr, nullptr, 0);
    }

    jfloat* freqs = env->GetFloatArrayElements(frequencies, nullptr);
    jfloat* gains = env->GetFloatArrayElements(gainsDB, nullptr);
    jfloat* qs    = env->GetFloatArrayElements(qFactors, nullptr);

    if (freqs == nullptr || gains == nullptr || qs == nullptr) {
        if (freqs) env->ReleaseFloatArrayElements(frequencies, freqs, JNI_ABORT);
        if (gains) env->ReleaseFloatArrayElements(gainsDB, gains, JNI_ABORT);
        if (qs)    env->ReleaseFloatArrayElements(qFactors, qs, JNI_ABORT);
        return -1;
    }

    const int rc = engine->updateManualEq(freqs, gains, qs, static_cast<int>(fCount));

    env->ReleaseFloatArrayElements(frequencies, freqs, JNI_ABORT);
    env->ReleaseFloatArrayElements(gainsDB, gains, JNI_ABORT);
    env->ReleaseFloatArrayElements(qFactors, qs, JNI_ABORT);
    return rc;
}

// ---------------------------------------------------------------------------
// Toggle setters
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_coreline_audio_AudioEngine_nativeSetAutoEqEnabled(JNIEnv* /*env*/,
                                                           jobject /*thiz*/,
                                                           jlong handle,
                                                           jboolean enabled) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) engine->setAutoEqEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_coreline_audio_AudioEngine_nativeSetManualEqEnabled(JNIEnv* /*env*/,
                                                             jobject /*thiz*/,
                                                             jlong handle,
                                                             jboolean enabled) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) engine->setManualEqEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_coreline_audio_AudioEngine_nativeSetAutoEqPreampEnabled(JNIEnv* /*env*/,
                                                                 jobject /*thiz*/,
                                                                 jlong handle,
                                                                 jboolean enabled) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) engine->setAutoEqPreampEnabled(enabled == JNI_TRUE);
}

// ---------------------------------------------------------------------------
// Stage C: Loudness Compensation (ISO 226:2023) — JNI methods
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_coreline_audio_AudioEngine_nativeSetLoudnessCompensationEnabled(JNIEnv* /*env*/,
                                                                          jobject /*thiz*/,
                                                                          jlong handle,
                                                                          jboolean enabled) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) engine->setLoudnessCompensationEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_coreline_audio_AudioEngine_nativeSetLoudnessCompensationVolume(JNIEnv* /*env*/,
                                                                         jobject /*thiz*/,
                                                                         jlong handle,
                                                                         jfloat systemVolume) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return -1;
    return engine->setLoudnessCompensationVolume(systemVolume);
}

// ---------------------------------------------------------------------------
// Stage D: Loudness Equalizer (BS.1770 auto-leveler) — JNI methods
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_coreline_audio_AudioEngine_nativeSetLoudnessEqEnabled(JNIEnv* /*env*/,
                                                                jobject /*thiz*/,
                                                                jlong handle,
                                                                jboolean enabled) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) engine->setLoudnessEqEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_coreline_audio_AudioEngine_nativeUpdateLoudnessEqSettings(JNIEnv* /*env*/,
                                                                    jobject /*thiz*/,
                                                                    jlong handle,
                                                                    jfloat targetLoudnessDb,
                                                                    jfloat maxBoostDb,
                                                                    jfloat maxCutDb,
                                                                    jfloat compressionThresholdOffsetDb,
                                                                    jfloat compressionRatio,
                                                                    jfloat compressionKneeDb,
                                                                    jfloat gainAttackMs,
                                                                    jfloat gainReleaseMs) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->updateLoudnessEqSettings(targetLoudnessDb, maxBoostDb, maxCutDb,
                                     compressionThresholdOffsetDb,
                                     compressionRatio, compressionKneeDb,
                                     gainAttackMs, gainReleaseMs);
}

// ---------------------------------------------------------------------------
// nativeUpdateSampleRate
// ---------------------------------------------------------------------------

// #2: returns 0 on success, -1 on rejection (range or null handle). The Kotlin
// wrapper checks the return value and throws IllegalStateException on -1, so
// any future drift between Kotlin's and native's bound constants surfaces as
// an immediate runtime error rather than silent state divergence between
// the wrapper's `sampleRate` property and the engine's actual rate.
//
// Status codes (kept narrow on purpose — caller only needs ok/not-ok):
//   0   success: engine->updateSampleRate(newRate) was invoked
//  -1   rejection: handle was null, or newRate was outside [kMin, kMax]

JNIEXPORT jint JNICALL
Java_com_coreline_audio_AudioEngine_nativeUpdateSampleRate(JNIEnv* /*env*/,
                                                           jobject /*thiz*/,
                                                           jlong handle,
                                                           jint newRate) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return -1;
    // Tier C-1: reject out-of-range rates. Engine also bounds-checks (defense
    // in depth); we drop early to avoid a logging-attempt at this layer.
    if (newRate < kMinSampleRateHz || newRate > kMaxSampleRateHz) return -1;
    engine->updateSampleRate(newRate);
    return 0;
}

// ---------------------------------------------------------------------------
// nativeProcessDirectBuffer
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_coreline_audio_AudioEngine_nativeProcessDirectBuffer(JNIEnv* env,
                                                              jobject /*thiz*/,
                                                              jlong handle,
                                                              jobject directBuffer,
                                                              jint numFrames) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return -1;
    if (directBuffer == nullptr) return -2;
    // Tier C-1: numFrames must be in [1, kMaxProcessFrames]. Replaces the
    // earlier `<= 0` guard. 65536 frames is comfortably above any realistic
    // audio-callback size (typical 256-2048; worst-case ~8192 on legacy
    // AudioTrack paths).
    if (numFrames < 1 || numFrames > kMaxProcessFrames) return -3;

    void* addr = env->GetDirectBufferAddress(directBuffer);
    if (addr == nullptr) return -4;

    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (capacity < 0) return -5;

    const jlong required = static_cast<jlong>(numFrames) *
                           static_cast<jlong>(2) *
                           static_cast<jlong>(sizeof(float));
    if (capacity < required) return -6;

    return engine->process(static_cast<float*>(addr), numFrames);
}

// ---------------------------------------------------------------------------
// nativeGetDiagnostics — returns long[7]
//   [0] xrunCount
//   [1] nonFiniteResetCount
//   [2] sampleRateChangeCount
//   [3] configSwapCount
//   [4] totalProcessedFrames
//   [5] appliedGeneration              (Tier B-2)
//   [6] autoEqActiveCount (as Long, narrowed to Int Kotlin-side)  (Tier B-2)
// ---------------------------------------------------------------------------

JNIEXPORT jlongArray JNICALL
Java_com_coreline_audio_AudioEngine_nativeGetDiagnostics(JNIEnv* env,
                                                         jobject /*thiz*/,
                                                         jlong handle) {
    constexpr jsize kCount = 7;
    jlongArray out = env->NewLongArray(kCount);
    if (out == nullptr) return nullptr;

    auto* engine = fromHandle(handle);
    if (engine == nullptr) {
        // Return zeros if the engine has been destroyed already.
        jlong zeros[kCount] = {0, 0, 0, 0, 0, 0, 0};
        env->SetLongArrayRegion(out, 0, kCount, zeros);
        return out;
    }

    auto d = engine->readDiagnostics();
    jlong values[kCount] = {
        static_cast<jlong>(d.xrunCount),
        static_cast<jlong>(d.nonFiniteResetCount),
        static_cast<jlong>(d.sampleRateChangeCount),
        static_cast<jlong>(d.configSwapCount),
        static_cast<jlong>(d.totalProcessedFrames),
        static_cast<jlong>(d.appliedGeneration),
        static_cast<jlong>(d.autoEqActiveCount),
    };
    env->SetLongArrayRegion(out, 0, kCount, values);
    return out;
}

// ---------------------------------------------------------------------------
// nativeGetAppliedSnapshot — returns long[6]
//   [0] generation
//   [1] autoEqEnabled       (0/1)
//   [2] autoEqFilterCount
//   [3] manualEnabled       (0/1)
//   [4] preampEnabled       (0/1)
//   [5] preampLinearGain    (raw bits of float, decoded Kotlin-side via
//                            Float.fromBits((value & 0xFFFFFFFF).toInt()))
//
// Tier B-2: consolidated read for the host UI's "is this profile actually
// applied right now?" decision. A single JNI hop returns a coherent snapshot
// (all six fields come from the same EngineSnapshot pointer load).
// ---------------------------------------------------------------------------

JNIEXPORT jlongArray JNICALL
Java_com_coreline_audio_AudioEngine_nativeGetAppliedSnapshot(JNIEnv* env,
                                                             jobject /*thiz*/,
                                                             jlong handle) {
    constexpr jsize kCount = 6;
    jlongArray out = env->NewLongArray(kCount);
    if (out == nullptr) return nullptr;

    auto* engine = fromHandle(handle);
    if (engine == nullptr) {
        // unity passthrough representation: all-zero, gain bits = 1.0f
        const float unity = 1.0f;
        uint32_t unityBits;
        std::memcpy(&unityBits, &unity, sizeof(unityBits));
        jlong zeros[kCount] = {0, 0, 0, 0, 0, static_cast<jlong>(unityBits)};
        env->SetLongArrayRegion(out, 0, kCount, zeros);
        return out;
    }

    const auto a = engine->readAppliedSnapshot();
    uint32_t gainBits;
    std::memcpy(&gainBits, &a.preampLinearGain, sizeof(gainBits));
    jlong values[kCount] = {
        static_cast<jlong>(a.generation),
        a.autoEqEnabled ? 1 : 0,
        static_cast<jlong>(a.autoEqFilterCount),
        a.manualEnabled ? 1 : 0,
        a.preampEnabled ? 1 : 0,
        static_cast<jlong>(gainBits),
    };
    env->SetLongArrayRegion(out, 0, kCount, values);
    return out;
}

// ---------------------------------------------------------------------------
// nativeRecordXrun — increment the engine's xrun counter by a delta.
// Called from AudioPlayerService after reading AudioTrack.getUnderrunCount().
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_coreline_audio_AudioEngine_nativeRecordXrun(JNIEnv* /*env*/,
                                                      jobject /*thiz*/,
                                                      jlong handle,
                                                      jlong deltaUnderrunFrames) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->recordXrun(static_cast<int64_t>(deltaUnderrunFrames));
}

} // extern "C"

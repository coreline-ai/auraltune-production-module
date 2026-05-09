<div align="center">

# 🎧 AuralTune Android

<img width="2752" height="1536" alt="AuralTune 앱 주요 기능 소개" src="https://github.com/user-attachments/assets/90dc5e5f-ec9f-45d9-8715-e7fc0a93c015" /><br>

**Production-grade AutoEQ headphone correction for Android**

[![minSdk](https://img.shields.io/badge/minSdk-26%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#-build-matrix)
[![targetSdk](https://img.shields.io/badge/targetSdk-34-3DDC84?style=flat-square&logo=android&logoColor=white)](#-build-matrix)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#-build-matrix)
[![AGP](https://img.shields.io/badge/AGP-8.5.2-02303A?style=flat-square&logo=gradle&logoColor=white)](#-build-matrix)
[![NDK](https://img.shields.io/badge/NDK-r27.0.12077973-blue?style=flat-square)](#-build-matrix)
[![16KB pages](https://img.shields.io/badge/16KB%20pages-✓-orange?style=flat-square)](#-correctness-invariants)

[![Tests](https://img.shields.io/badge/Kotlin%20unit-105%20PASS-brightgreen?style=flat-square)](#-testing)
[![Native tests](https://img.shields.io/badge/native%20suites-6%20PASS-brightgreen?style=flat-square)](#-testing)
[![DSP parity](https://img.shields.io/badge/scipy.lfilter%20SNR-140--157%20dB-success?style=flat-square)](#-correctness-invariants)
[![TSan](https://img.shields.io/badge/ThreadSanitizer-clean-success?style=flat-square)](#-correctness-invariants)
[![R8](https://img.shields.io/badge/R8%20release-PASS-success?style=flat-square)](#-quick-start)

DSP-accurate biquad cascade · RT-safe atomic publish · Source-level integration ready

[Quick Start](#-quick-start) · [Project Layout](#-project-layout) · [Build Matrix](#-build-matrix) · [Testing](#-testing) · [Invariants](#-correctness-invariants) · [API Surface](#-api-surface) · [Out of Scope](#-whats-intentionally-not-in-this-drop)

</div>

---

## ✨ Highlights

- 🎯 **DSP parity verified** — scipy.lfilter SNR 140–157 dB across 44.1k / 48k / 96k; freqz match to 0.0000 dB worst-case.
- 🛡️ **TSan-clean atomic publish** — heap `EngineSnapshot` + 500 ms deferred retire closes the audio-thread / control-thread race surface end-to-end.
- ⚡ **Zero-allocation audio callback** — single `acquire` load per buffer, no locks, no Java callbacks across the JNI boundary.
- 🧪 **105 Kotlin unit + 6 native suites + 12 instrumented** — all green; pre-verified `LazyDeferredBehaviorTest` pins kotlinx coroutine assumptions.
- 🧱 **3-layer range validation** — Kotlin `require` → JNI guard → engine bounds-check; cross-language status code surfaces drift as `IllegalStateException`.
- 🔌 **Source-level integration ready** — `AudioEngine.Builder` enforces sample-rate match, `useInAudioSession` DSL enforces lifecycle, deprecated aliases preserved for downstream binary compat.
- 📦 **16 KB page-size compatible** — `-Wl,-z,max-page-size=16384` linker flag, verified post-link via `llvm-readelf -l` (`Align 0x4000`).
- 🧮 **Diagnostics fingerprint** — `appliedGeneration` + `autoEqActiveCount` give the host UI a coherent "selected vs applied" signal without torn reads.

---

## 📂 Project Layout

```
android-app/
├── audio-engine/             # :audio-engine — Kotlin lib + native C++ DSP
│   └── src/main/cpp/
│       ├── BiquadFilter.{h,cpp}        TDF2 biquad — audio-thread-only delay state
│       ├── AuralTuneEQEngine.{h,cpp}   Heap snapshot + retire queue, Manual + AutoEQ
│       │                               chains, RBJ pre-warp, NaN guard, generation token
│       └── AudioEngineJNI.cpp          Handle-based JNI bridge (12 methods)
│
├── autoeq-data/              # :autoeq-data — Pure Kotlin data layer
│   ├── parser/               IndexMd + ParametricEQ parsers
│   ├── repository/           OkHttp + LRU cache + caller-cancel-safe coalescing
│   ├── search/               3-tier fuzzy scoring (substring → normalized → token+edit)
│   └── cache/                Catalog cache + LRU profile cache + import store
│
└── app/                      # :app — Compose UI + AudioTrack pipeline
    ├── audio/                AudioPlayerService (Float32 stereo loop)
    ├── data/                 SettingsStore (DataStore)
    ├── di/                   ServiceLocator (manual DI)
    └── ui/                   AutoEqViewModel + Compose Material 3 screens
```

**Module dependencies (compile-time graph):**

```
        ┌──────────┐
        │  :app    │  Compose UI · AudioTrack · DI
        └────┬─────┘
             │ implementation(project(...))
       ┌─────┴─────┐
       ▼           ▼
 ┌──────────┐ ┌──────────────┐
 │:audio-   │ │:autoeq-data  │  Pure Kotlin: parser/cache/repo/search
 │ engine   │ └──────────────┘
 │ (JNI/    │      │
 │  native) │      │ implementation libs.okhttp (api'd)
 └──────────┘      │
                   ▼
                OkHttp 4.12
```

`:audio-engine` and `:autoeq-data` are **independent of `:app`** and can be vendored / source-included into other host apps.

---

## 🚀 Quick Start

### Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17 (Temurin / Homebrew `openjdk@17`) |
| Android Studio | Koala 2024.1.1 or later — OR command-line tools below |
| Android SDK | API 34 |
| Android NDK | r27 (`27.0.12077973`) — earlier NDKs lack 16 KB page support |
| CMake | 3.22.1+ |
| Device / emulator | API ≥ 26 (Android 8.0) |

### One-time setup

```bash
cd android-app
cp local.properties.example local.properties
# Then edit local.properties to set sdk.dir / ndk.dir
```

### Build

```bash
# Debug APK
./gradlew :app:assembleDebug

# Release APK with R8 + lintVital
./gradlew :app:assembleRelease
```

### Install + launch

```bash
./gradlew :app:installDebug
adb shell am start -n com.coreline.auraltune/.MainActivity
```

### Run all unit tests

```bash
./gradlew :audio-engine:testDebugUnitTest :autoeq-data:testDebugUnitTest
# Expected: 105 tests, all PASS
```

### Run native unit tests (host JVM)

```bash
cd audio-engine
g++ -std=c++17 -O2 -fno-finite-math-only \
    -I src/main/cpp \
    src/test/cpp/AuralTuneEQEngineTest.cpp \
    src/main/cpp/BiquadFilter.cpp \
    src/main/cpp/AuralTuneEQEngine.cpp \
    -o /tmp/run_native_tests && /tmp/run_native_tests
```

> See [`ci/android.yml`](ci/android.yml) for the full native-test build matrix (golden response, scipy.lfilter, snapshot race, range validation).

### Verify 16 KB page-size compliance

```bash
SO=$(find app/build/intermediates \
  -path '*/merged_native_libs/release/*/out/lib/arm64-v8a/libauraltune_audio.so' \
  | head -1)
$ANDROID_HOME/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf -l "$SO" \
    | awk '/LOAD/ {print "Align="$NF}'
# Expected: Align=0x4000  (16 KB). 0x1000 means linker flags are missing.
```

---

## 🧱 Build Matrix

| Setting | Value | Source / rationale |
|---|---|---|
| `minSdk` | **26** | AAudio support, Float32 PCM, modern audio APIs |
| `targetSdk` / `compileSdk` | **34** | Android 14 |
| AGP | **8.5.2** | [`gradle/libs.versions.toml`](gradle/libs.versions.toml) |
| Kotlin | **1.9.24** | catalog |
| NDK | **27.0.12077973** | 16 KB page size — verified via `llvm-readelf -l` (Align 0x4000) |
| ABI | `arm64-v8a`, `x86_64` | `armeabi-v7a` explicitly excluded |
| Build tool | CMake 3.22.1+ via `externalNativeBuild` | – |
| MVP playback pipeline | **AudioTrack Float32 loop** | simplest deterministic baseline |
| UI | **Jetpack Compose Material 3** | – |
| DI | Manual `ServiceLocator` | no Hilt for MVP |
| Foreground Service | **out of MVP** | playback runs only while activity is foregrounded |
| Catalog bootstrap | first-launch download | smaller APK |
| Profile ID | `sha256(source\|relativePath\|name).take(24)` | not slug |

---

## 🔌 API Surface

> All public types live under `com.coreline.audio` (`:audio-engine`) and `com.coreline.autoeq` (`:autoeq-data`).

### `:audio-engine` — DSP core

```kotlin
// Build with explicit rate-match enforcement (preferred)
val engine = AudioEngine.Builder()
    .forAudioTrack(audioTrack)   // reads track.sampleRate, no drift possible
    .build()

// Lifecycle DSL — compile-time-enforces stop → join → close ordering
engine.useInAudioSession(audioThreadJoiner = { player.stop() }) { eng ->
    eng.updateAutoEq(preampDB, enableLimiter = true, profileOptimizedRate = 48_000.0,
                     filterTypes, frequencies, gainsDB, qFactors)
    // safe to eng.process(buf, frames) from the audio thread inside this block
}

// Diagnostics — coherent snapshot, no torn reads across fields
val applied: AppliedSnapshot = engine.readAppliedSnapshot()
val coherent = applied.generation == expectedGen && applied.autoEqFilterCount > 0
```

### `:autoeq-data` — Catalog + profiles

```kotlin
val repo = AutoEqRepository(context)
repo.primeImports()                      // load user-imported profiles from disk

val catalog: Flow<CatalogState> = repo.loadCatalog(scope)   // cache-first, refresh-stale
val profile = repo.fetchProfile(entry)   // coalesced via inflight CHM, scope-cancel safe

// On long-running shutdown
repo.close()                              // cancels all inflight HTTP work
```

---

## 🧪 Testing

### Test inventory

| Suite | Count | Type | Purpose |
|---|---|---|---|
| `:audio-engine` Kotlin unit | **29** | Robolectric + ShadowAudioEngine | API contracts, lifecycle DSL, builder, range gates, native-create failure, native-update rejection |
| `:autoeq-data` Kotlin unit | **76** | Robolectric + OkHttp interceptor | Parsers, caches, repository (coalescing, cancellation, close), search, kotlinx-behavior pinning |
| `:app` instrumented | **12** | on-device (PD20 baseline) | End-to-end audio path |
| Native — `AuralTuneEQEngineTest` | 8 | host g++ | Engine: NaN guard, sample-rate change, validation, snapshot publish, xrun, enable toggle, rapid switching click-free |
| Native — `RangeValidationTest` | 8 | host g++ | Native-side range bounds, `process()` size validation, applied-snapshot consistency |
| Native — `GoldenResponseTest` | 3 fs | host g++ | `freqz` parity to **0.0000 dB** worst-case at 48k / 44.1k / 96k |
| Native — `ScipyGoldenResponseTest` | – | host g++ | Cross-check with scipy frequency-domain ground truth |
| Native — `ScipyLfilterIRTest` | 3 fs | host g++ | Time-domain IR parity at **SNR 140–157 dB** |
| Native — `SnapshotRaceTest` | 1 (stress) | host g++ + TSan | Concurrent publish/process — TSan-clean |

### Behavioral pinning

[`LazyDeferredBehaviorTest`](autoeq-data/src/test/java/com/coreline/autoeq/repository/LazyDeferredBehaviorTest.kt) pins five kotlinx-coroutines guarantees the repository's coalescing logic depends on (G1: LAZY async doesn't pre-run; G2/G2b: `invokeOnCompletion` fires once on success/cancel; G3: scope cancel between async and start fires cleanup; G4: start on cancelled scope is a no-op; G5: eager `UNDISPATCHED` complete-before-register is observable). A kotlinx upgrade that breaks any of these surfaces here immediately, BEFORE shipping a regressed library to integrators.

### Single command — full unit suite

```bash
./gradlew :audio-engine:testDebugUnitTest :autoeq-data:testDebugUnitTest \
          :audio-engine:lintDebug :autoeq-data:lintDebug \
          :app:assembleDebug :app:assembleRelease
```

---

## 🛡️ Correctness Invariants

These MUST NOT be regressed. Each has at least one regression test that fails on violation.

<details>
<summary><b>1. Pre-warp</b> — coefficient math is sample-rate aware</summary>

Without it, profile center frequencies drift at sample rates ≠ the profile's optimized rate. RBJ + bilinear pre-warp restores parity. Verified by `GoldenResponseTest` and `ScipyGoldenResponseTest` across 44.1k / 48k / 96k.
</details>

<details>
<summary><b>2. TDF2 topology</b> — <code>y = b0·x + s1; s1 = b1·x − a1·y + s2; s2 = b2·x − a2·y</code></summary>

DF1 regression check is in `testStaleFilterReset` and the golden-response suite. Time-domain parity to scipy.lfilter is verified at SNR 140–157 dB.
</details>

<details>
<summary><b>3. Whole-config atomic publish (heap + deferred retire)</b></summary>

`AuralTuneEQEngine` allocates a fresh `EngineSnapshot` on the heap for every publish, populates it with the entire new cascade (manual + AutoEQ + flags + linear preamp), and atomically `exchange`s the published pointer. The previous snapshot goes onto a retire queue with a 500 ms grace deadline. Audio thread loads the pointer **once** per callback (`acquire`) and uses the same snapshot end-to-end. The grace is comfortably longer than the worst-case AudioTrack callback (~100 ms with the legacy buffer path), so the audio thread can never see a freed snapshot. **No mid-cascade tearing, no slot reuse race.** TSan-verified by `SnapshotRaceTest`.
</details>

<details>
<summary><b>4. Audio-thread-owned delay state</b></summary>

`BiquadFilter` only holds delay state (`z1L, z2L, z1R, z2R`). Coefficients live in the snapshot. Control thread NEVER calls `reset()` directly. Sample-rate change / hard reset publishes a snapshot with `requestDelayReset=true`; the audio thread observes the generation bump at the next callback and resets its own state before processing.
</details>

<details>
<summary><b>5. Lifecycle UAF guard</b></summary>

`AudioEngine` wraps the native handle in `AtomicLong` with CAS-then-destroy `close()`. `AudioPlayerService.stop()` calls `audioTrack.pause() + flush()` to unblock pending `WRITE_BLOCKING` writes, joins with timeout, and escalates via `Thread.interrupt()` if the thread is still alive. Engine is only freed AFTER thread join confirms exit. The Kotlin handle short-circuits stale calls into `process()` / `readDiagnostics()` BEFORE they reach JNI; the underlying JNI does NOT independently validate handle freshness, so callers MUST follow the ordering: `deviceManager.close()` → `player.close()` → `engine.close()`. The `useInAudioSession` DSL makes this ordering compile-time-enforced.
</details>

<details>
<summary><b>6. Single engine writer</b></summary>

`DeviceAutoEqManager` is the only class that calls `engine.updateAutoEq` / `engine.clearAutoEq`. ViewModel UI selections delegate via `selectProfileForCurrentDevice(...)`. This means route changes and UI selections cannot race / overwrite the engine state.
</details>

<details>
<summary><b>7. AudioTrack is the source of truth for sample rate</b></summary>

Engine sample rate is locked to AudioPlayerService's configured rate; route detection (`DeviceAutoEqManager.reconcile`) does NOT call `engine.updateSampleRate(...)`. A coordinated AudioTrack rebuild path is post-MVP work — until then, AutoEQ pre-warp targets the AudioTrack rate, not the route-reported rate.
</details>

<details>
<summary><b>8. NaN guard</b></summary>

First-stereo-frame `!isfinite` check on the audio thread. Never disable `-fno-finite-math-only` (CI grep enforces).
</details>

<details>
<summary><b>9. Stale filter reset</b></summary>

When chain shrinks, trailing slots are filled with `BiquadCoeffs::unity()` in the next snapshot. Delay state for those slots either stays at zero (already reset) or gets reset via the generation bump on the next callback.
</details>

<details>
<summary><b>10. 16 KB page size</b></summary>

CMake links with `-Wl,-z,max-page-size=16384` and `-Wl,-z,common-page-size=16384`. Verify via `llvm-readelf -l libauraltune_audio.so | grep LOAD` → `Align 0x4000`. Without this, the loader fails on Android 15+ devices with 16 KB pages.
</details>

<details>
<summary><b>11. Manual + AutoEQ preamp policy</b></summary>

`EqEngineConfig` has `manualPreampDB = 0` forced; only the AutoEQ chain has a preamp stage. Single-pass cascade order: <b>Manual → AutoEQ preamp → AutoEQ → soft limiter</b>.
</details>

<details>
<summary><b>12. Cross-language constant lockstep</b></summary>

`AudioEngine.MIN/MAX_SAMPLE_RATE_HZ` (Kotlin) and `AuralTuneEQEngine::kMin/kMaxSampleRateHz` (C++) MUST share the same literal values. `AudioEngineRangeContractTest.kt` and `RangeValidationTest.cpp` each pin their side independently — drift on either side fails its respective test. As a runtime defense, `nativeUpdateSampleRate` returns `0`/`-1` and the Kotlin wrapper throws `IllegalStateException` on a non-zero status.
</details>

<details>
<summary><b>13. Caller-cancel-safe fetch coalescing</b></summary>

`AutoEqRepository.fetchProfile` uses `CoroutineStart.LAZY + start()` to keep the async block from running before `invokeOnCompletion` is registered, and the inflight-map cleanup is bound to the deferred's lifecycle (NOT each caller's `finally`). A caller cancelling its `await()` does not yank the still-running deferred from the map, so a second caller arriving moments later coalesces onto the same fetch instead of triggering a duplicate HTTP request. Verified by `caller cancellation does not yank still-running deferred from inflight map (C3)` regression test.
</details>

---

## 🎛️ What the App Does (MVP)

On first launch:

1. **Test tone toggle** — turning it on starts a 1 kHz stereo sine through the entire pipeline (`AudioTrack ← AudioEngine ← TestTone`).
2. **Catalog download** — pulls `INDEX.md` from `raw.githubusercontent.com/jaakkopasanen/AutoEq` and parses it into a 5,000+ entry catalog (cached for 7 days).
3. **Fuzzy search** — "airpod pro" → "Apple AirPods Pro", with diacritic / case / token-order tolerance.
4. **Profile fetch** — tapping a result downloads its `ParametricEQ.txt`, parses, and pushes to the native engine.
5. **Status card** — shows the active profile + Correction / Preamp toggles.
6. **Diagnostics card** (collapsible) — native counters: xrun, NaN reset, config swap, sample-rate change, total processed frames, applied generation, AutoEQ active filter count.
7. **Empty states** — distinguishes offline / no catalog / no match / loading.
8. **User imports** — pick a `ParametricEQ.txt` via SAF, deduped by `sha256(text|name)`.
9. **Kill switch** — local DataStore flag bypasses the entire DSP cascade.

---

## 📈 Phase Coverage

| Phase | Status | Notes |
|---|---|---|
| **0 — build / contract / thread / UX** | ✅ | All decisions locked in `gradle/libs.versions.toml`. NDK r27 / 16 KB page. |
| **1 — Native / JNI hardening** | ✅ | `AtomicLong` handle (UAF-safe close), validated JNI, NaN guard, atomic counters |
| **2 — DSP accuracy + atomic publish** | ✅ | Heap `EngineSnapshot` + 500 ms deferred retire for realtime-safe pointer lifetime. Pre-warp, Nyquist guard, RBJ, TDF2, sample-rate change. |
| **3 — Kotlin parser** | ✅ | BOM / CRLF, NaN reject, comment skip, ≤10 filters, error sealed class |
| **4 — Catalog / cache / search** | ✅ | LRU 200 / 5 MB, coalescing (LAZY + invokeOnCompletion cleanup), 3-tier fuzzy, telemetry interface |
| **5 — Per-device persistence** | 🟡 | `SettingsStore` + `AudioDeviceCallback` (`RouteWatcher`) → `engine.updateSampleRate(...)`. Profile-per-device key migration still post-MVP. |
| **6 — AudioTrack pipeline + UX** | ✅ | Float32 stereo, `URGENT_AUDIO` priority, `AudioTrack.getUnderrunCount → engine.recordXrun(...)`, diagnostics UI, empty states, profile restore on launch. Lifecycle UAF closed via `stop() → pause/flush + interruptible join`. |
| **7 — Verification** | 🟡 | Native + Kotlin unit tests + on-device instrumented suite all PASS (12/12). Golden response (scipy) and Android CI workflow are follow-ups. |

---

## 🚫 What's Intentionally NOT in This Drop

Per the dev plan's exclusion list:

- ExoPlayer / Oboe pipelines (chosen pipeline = AudioTrack for MVP)
- Foreground service / MediaSession / background playback
- Remote feature flag / kill switch (local DataStore flag only)
- Expert grouped multi-source / rig UI
- Latency compensation (measurement hook only)
- Limiter attack / release / lookahead upgrade
- Loudness compensation (separate scope)

---

## 🔒 Telemetry & Privacy

`AutoEqTelemetry` is an interface — the MVP wires `NoOp`, so nothing leaves the device. When integrating Crashlytics / Sentry / DataDog / etc., **do not** log:

- Bluetooth MAC addresses (use device key hash)
- Imported file names
- Raw profile IDs

`AutoEqRepository.setProtectedIds()` is the single source of truth for *"don't evict this profile from cache"*.

---

## 🤝 Integration Onboarding Checklist

For teams source-vendoring `:audio-engine` and / or `:autoeq-data`:

- [ ] Use `AudioEngine.Builder().forAudioTrack(track).build()` — never the bare constructor.
- [ ] Stop / join the audio thread BEFORE `engine.close()` — or wrap the session in `engine.useInAudioSession(audioThreadJoiner = { ... }) { ... }`.
- [ ] Forward `AudioTrack.getUnderrunCount()` deltas to `engine.recordXrun(delta)` — the only silent-failure surface in the API.
- [ ] If you hold an `AutoEqRepository` for the application lifetime, call `repo.close()` on shutdown.
- [ ] Use `Diagnostics.appliedGeneration` for "selected vs applied" UI states; do not rely on caller-side bookkeeping.
- [ ] Wire `AutoEqTelemetry` to your production observability stack — `NoOp` is intentional default.
- [ ] Call `repo.primeImports()` once at startup, BEFORE subscribing to `loadCatalog(...)`.

---

## 📜 License

본 프로젝트의 라이선스는 **별도 협의** — 사용 전 [coreline-ai](https://github.com/coreline-ai)에 문의해 주세요.

### Third-party attribution

Headphone correction profiles courtesy of Jaakko Pasanen's [AutoEq project](https://github.com/jaakkopasanen/AutoEq) (MIT License).

---

<div align="center">

Made with ⚡ by **Coreline**

</div>

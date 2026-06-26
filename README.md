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

[![Tests](https://img.shields.io/badge/Kotlin%20unit-236%20PASS-brightgreen?style=flat-square)](#-testing)
[![Native tests](https://img.shields.io/badge/native%20suites-8%20PASS-brightgreen?style=flat-square)](#-testing)
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
- 🧪 **217 Kotlin unit (71 engine / 96 autoeq-data / 27 opra-data / 23 app) + 8 native suites** — all green; pre-verified `LazyDeferredBehaviorTest` pins kotlinx coroutine assumptions.
- 🆚 **OPRA comparison + A/B/C listen modes** — second data source (OPRA, CC BY-SA 4.0) bundled offline alongside AutoEq, sharing the same DSP engine; one-tap 원음 / EQ 적용 / 내 설정 (profile + graphic EQ) for instant A-B-C auditioning.
- 🎛️ **Player-first auditioning** — local-file queue persists across restarts, the player exposes 원음/EQ 적용/커스텀 + preamp controls, and the real-time post-EQ spectrum shows bit depth / sample rate.
- 🧱 **3-layer range validation** — Kotlin `require` → JNI guard → engine bounds-check; cross-language status code surfaces drift as `IllegalStateException`.
- 🔌 **Source-level integration ready** — `AudioEngine.Builder` enforces sample-rate match, `useInAudioSession` DSL enforces lifecycle, deprecated aliases preserved for downstream binary compat.
- 📦 **16 KB page-size compatible** — `-Wl,-z,max-page-size=16384` linker flag, verified post-link via `llvm-readelf -l` (`Align 0x4000`).
- 🧮 **Diagnostics fingerprint** — `appliedGeneration` + `autoEqActiveCount` give the host UI a coherent "selected vs applied" signal without torn reads.

---

## 📂 Project Layout

```
auraltune-production-module/
├── audio-engine/             # :audio-engine — Kotlin lib + native C++ DSP
│   └── src/main/cpp/
│       ├── core/             JNI-free DSP core (HAL/effect-portable)
│       │   ├── BiquadFilter.{h,cpp}        TDF2 biquad — audio-thread-only delay state
│       │   ├── AuralTuneEQEngine.{h,cpp}   Heap snapshot + retire queue, Manual + AutoEQ
│       │   │                               chains, RBJ pre-warp, NaN guard, generation token
│       │   └── loudness/                   ISO 226 contours, K-weighting, LoudnessCompensator
│       └── jni/AudioEngineJNI.cpp          Handle-based JNI bridge
│
├── autoeq-data/              # :autoeq-data — Pure Kotlin data layer (Room DB-first)
│   ├── parser/               IndexMd + ParametricEQ parsers
│   ├── repository/           OkHttp + DB-first repo + caller-cancel-safe coalescing
│   ├── search/               3-tier fuzzy scoring (substring → normalized → token+edit)
│   ├── db/                   Room: AutoEqDatabase(v2), Catalog/Profile DAO+Store, MIGRATION_1_2
│   ├── cache/                Legacy file caches (one-shot migration sources only)
│   └── src/main/assets/autoeq/INDEX.md     Bundled offline seed (851 KB snapshot)
│
├── opra-data/               # :opra-data — OPRA (Open Profiles for Revealing Audio) data layer
│   ├── model/                OpraModel + OpraFilterType (engine-supported-filter check)
│   ├── dto/                  database_v1.jsonl line DTOs + bundled manifest DTO
│   ├── db/                   Room: OpraDatabase + DAO/Store (separate opra_catalog.db)
│   ├── OpraJsonlParser       Streaming JSONL parser (vendor/product/eq join, orphan/malformed)
│   ├── *SnapshotSource       Bundled(release, sha256-verified) / GitHubRaw(debug) sources
│   └── src/main/assets/opra/database_v1.jsonl.gz + manifest.json  (bundled snapshot, ~1.1 MB gz)
│   # Depends ONLY on :audio-engine (NOT :autoeq-data); OpraEqProfile→engine adapter lives in :app
│
└── app/                      # :app — Compose UI + ExoPlayer (Media3) T1 pipeline
    ├── audio/                MusicPlayerController + AuralTuneAudioProcessor (engine.process),
    │                         DeviceAutoEqManager (single engine writer), audiofx/ (T2 probe)
    ├── audio/eq/             BiquadResponse (Kotlin freqz) + GraphicEqBands (20-band)
    ├── data/                 SettingsStore (DataStore) + GraphicEqPreset
    ├── di/                   ServiceLocator (manual DI)
    └── ui/                   AutoEqViewModel (owns audio stack) + Compose screens
                              (Graphic EQ: GraphicEqView, EqGraphView)
    └── src/debug/            AndroidManifest overlay (READ_MEDIA_AUDIO — debug-only)
```

**Module dependencies (compile-time graph):**

```
              ┌──────────┐
              │  :app    │  Compose UI · ExoPlayer(Media3) · DI · OpraEqProfile→engine adapter
              └────┬─────┘
                   │ implementation(project(...))
       ┌───────────┼───────────────┐
       ▼           ▼               ▼
 ┌──────────┐ ┌──────────────┐ ┌──────────────┐
 │:audio-   │ │:autoeq-data  │ │ :opra-data   │  OPRA fetch/parse/cache/search
 │ engine   │ └──────────────┘ └──────┬───────┘  (CC BY-SA 4.0 data, bundled snapshot)
 │ (JNI/    │      │                  │ api(project(":audio-engine"))  ← NOT :autoeq-data
 │  native) │◄─────┘──────────────────┘ implementation libs.okhttp / room
 └──────────┘      │
                   ▼
                OkHttp 4.12 · Room
```

`:audio-engine`, `:autoeq-data`, and `:opra-data` are **independent of `:app`** and can be vendored / source-included. `:opra-data` is fully isolated from `:autoeq-data` (a separate data source for the same shared engine); the OPRA→engine adapter is the only bridge and lives in `:app`.

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
cd auraltune-production-module
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
./gradlew :audio-engine:testDebugUnitTest :autoeq-data:testDebugUnitTest :opra-data:testDebugUnitTest :app:testDebugUnitTest
# Expected: 236 tests, all PASS across :audio-engine, :autoeq-data, :opra-data, and :app
```

### 2026-06-26 Release Readiness Notes

Current `main` includes the Phase 7 hardening pass:

- Player errors are surfaced to the user and failed tracks are removed from the queue instead of silently looping.
- The player audio processor accepts PCM 16-bit, 24-bit, 32-bit integer, and float decode outputs, then emits 16-bit PCM for Media3 sink compatibility.
- AutoEQ/OPRA profile application is route-gated: speaker, HDMI, line, and telephony routes actively clear stale headphone correction.
- OPRA restore/apply failure keeps the saved selection but does not show OPRA as currently applied when the output route is ineligible.
- OPRA search escapes SQL LIKE wildcards (`%`, `_`, `\`) so user queries are literal.
- Adaptive launcher icon resources are tracked under `drawable/` with density PNG fallbacks.

Verified locally on 2026-06-26:

```bash
./gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" \
  :audio-engine:testDebugUnitTest :autoeq-data:testDebugUnitTest :opra-data:testDebugUnitTest :app:testDebugUnitTest

./gradlew.bat --no-daemon --no-build-cache --max-workers=2 \
  "-Dorg.gradle.parallel=false" \
  "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -XX:+HeapDumpOnOutOfMemoryError" \
  "-Dkotlin.compiler.execution.strategy=in-process" :app:assembleRelease
```

Validation snapshot: `236` unit tests PASS, release build PASS, APK debug-marker scan PASS, native LOAD alignment `0x4000` PASS.

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
| T1 playback pipeline | **ExoPlayer (Media3)** + `AuralTuneAudioProcessor` (engine.process) → `DefaultAudioSink` | local-file playback with in-line EQ |
| UI | **Jetpack Compose Material 3** | – |
| DI | Manual `ServiceLocator` | no Hilt for MVP |
| Foreground Service | **out of MVP** | playback runs only while activity is foregrounded |
| Catalog bootstrap | **bundled `assets/autoeq/INDEX.md` seed → Room DB-first** | offline from first launch; network only refreshes (ETag) |
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

val catalog: Flow<CatalogState> = repo.loadCatalog(scope)   // DB-first: bundled seed → DB → ETag refresh
val profile = repo.fetchProfile(entry)   // DB-first: DB hit → network → DB upsert; coalesced, scope-cancel safe

// On long-running shutdown
repo.close()                              // cancels all inflight HTTP work
```

---

## 🧪 Testing

### Test inventory

| Suite | Count | Type | Purpose |
|---|---|---|---|
| `:audio-engine` Kotlin unit | **71** | Robolectric + ShadowAudioEngine | API contracts, lifecycle DSL, builder, range gates, native-create failure, native-update rejection |
| `:autoeq-data` Kotlin unit | **96** | Robolectric + OkHttp interceptor + in-memory Room | Parsers, caches, DB-first repo (catalog seed, tombstone sweep, profile DB hit/miss/kill-switch, coalescing), search |
| `:opra-data` Kotlin unit | **27** | Robolectric + in-memory Room | OPRA JSONL parser (join/orphan/malformed, headphone display-name guard), filter-type→engine mapping, Room store, repository (NoChange/force/Updated/Failed + checksum-mismatch/offline cache-retention), bundled sha256 source + gz-fallback |
| `:app` Kotlin unit | **23** | Robolectric + JUnit | Graphic-EQ freqz (BiquadResponse), SpectrumAnalyzer FFT/log-band guards, T2-OS approximation fit (AutoEqApprox), OpraEngineAdapter, SettingsStore provider/playback-snapshot migration, OpraSourcePolicy (release≠GitHub) |
| `:app` instrumented | (on-device) | 3 devices (S25 / PD20 / SP4000T) | End-to-end audio path, rotation, offline DB |
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
./gradlew :audio-engine:testDebugUnitTest :autoeq-data:testDebugUnitTest :opra-data:testDebugUnitTest \
          :audio-engine:lintDebug :autoeq-data:lintDebug :opra-data:lintDebug \
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

`AudioEngine` wraps the native handle in `AtomicLong` with CAS-then-destroy `close()`. The audio stack (engine, `MusicPlayerController`, `DeviceAutoEqManager`) is owned by the **retained `AutoEqViewModel`**, not the Composable, so it survives configuration changes (rotation). Teardown happens in `AutoEqViewModel.onCleared()` in the order `deviceManager.close()` → `musicController.close()` → `engine.close()` (each pre-close wrapped in `runCatching` so a throw never blocks `engine.close()`). `MusicPlayerController.close()` releases ExoPlayer (joining its internal audio thread) before the engine is freed. The Kotlin handle short-circuits stale calls into `process()` / `readDiagnostics()` BEFORE they reach JNI. (The legacy `useInAudioSession` DSL remains for direct integrators.)
</details>

<details>
<summary><b>6. Single engine writer</b></summary>

`DeviceAutoEqManager` is the only class that calls `engine.updateAutoEq` / `engine.clearAutoEq`. ViewModel UI selections delegate via `selectProfileForCurrentDevice(...)`. This means route changes and UI selections cannot race / overwrite the engine state.
</details>

<details>
<summary><b>7. The playback path is the source of truth for sample rate</b></summary>

The engine sample rate is driven by the playback path — `MusicPlayerController`'s `AuralTuneAudioProcessor` reconfigures the engine to the decoded stream rate (e.g. 44100/48000). Route detection (`DeviceAutoEqManager.reconcile`) does NOT call `engine.updateSampleRate(...)` from `AudioDeviceInfo.sampleRates` (those are rates the device CAN do, not the rate actually being fed). So AutoEQ pre-warp always targets the real PCM rate, and route changes never drift it.
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

1. **Local music playback** — pick an audio file via SAF (`ACTION_OPEN_DOCUMENT`); it plays through ExoPlayer with the AutoEQ + graphic-EQ engine inline. (A debug build adds a MediaStore "play first track" shortcut.)
2. **Offline catalog** — the bundled `assets/autoeq/INDEX.md` seed is imported into Room on first run, so the 5,000+ entry catalog is searchable with **zero network**; a background ETag-conditional refresh updates it only when upstream changed.
3. **Fuzzy search** — "airpod pro" → "Apple AirPods Pro", with diacritic / case / token-order tolerance.
4. **Profile fetch (DB-first)** — tapping a result returns the DB-stored profile if present, else downloads `ParametricEQ.txt`, parses, stores parsed filters in Room, and pushes to the engine. Re-loads offline thereafter.
5. **Graphic EQ (20-band)** — top response-curve graph (Kotlin freqz, composite of Manual + AutoEQ) + 20 vertical sliders driving the engine's Manual chain; named presets save/load (DataStore), current gains auto-restore.
6. **Status card** — active profile + Correction / Preamp toggles.
7. **Diagnostics card** (collapsible) — native counters: xrun, NaN reset, config swap, sample-rate change, total processed frames, applied generation, AutoEQ active filter count.
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
| **5 — Per-device persistence** | ✅ | `SettingsStore` + `AudioDeviceCallback` per-device profile selection (device-key). Profiles persisted in Room (`ProfileStore`). |
| **6 — Playback pipeline + UX** | ✅ | **ExoPlayer (Media3)** + `AuralTuneAudioProcessor` (engine.process inline), persistent queue, realtime spectrum with bit depth/sample rate, diagnostics UI, profile restore on launch, 20-band graphic EQ. Lifecycle owned by retained ViewModel (`onCleared` ordered close). |
| **7 — Verification** | ✅ | Kotlin unit, release build, release debug/probe marker scan, and 16 KB native alignment gates are documented/automated; on-device spot checks remain manual for device-specific audio routes. |

> Note: the table above is the original **engine/MVP** phase plan. The graphic-EQ + DB-first + release-gate workstream is tracked separately in `dev-plan/implement_20260622_110525.md` (Phases 0–7) and `dev-plan/remaining_20260622.md`.

---

## 🚫 What's Intentionally NOT in This Drop

Per the dev plan's exclusion list:

- Oboe / AAudio low-latency pipeline (T1 uses ExoPlayer/Media3)
- Foreground service / MediaSession / background playback
- Remote feature flag / kill switch (local DataStore flag only)
- Expert grouped multi-source / rig UI
- Latency compensation (measurement hook only)
- Limiter attack / release / lookahead upgrade
- T3 HAL/effect `.so` global apply (deferred — core kept JNI-free for future porting)

> Note: ExoPlayer (T1 playback) and loudness compensation (`core/loudness/`) are now **implemented**, not out of scope. T2-OS (MusicFX external-app approximation) is at the probe stage (`AudioFxSessionProbe`).

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

- **AutoEq** — headphone correction profiles courtesy of Jaakko Pasanen's [AutoEq project](https://github.com/jaakkopasanen/AutoEq) (MIT License).
- **OPRA** — headphone & EQ data from [OPRA (Open Profiles for Revealing Audio)](https://github.com/opra-project/OPRA), a community database started by Roon Labs. OPRA data is licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). AuralTune bundles a versioned snapshot, uses the values as published (format-converted for playback only, OPRA-derived), and is **not endorsed by or affiliated with** OPRA contributors or Roon Labs. In-app attribution/notices live in the *About & licenses* card, the OPRA profile detail sheet, and the OPRA-tab footer.

---

<div align="center">

Made with ⚡ by **Coreline**

</div>

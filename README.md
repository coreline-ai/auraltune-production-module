# AuralTune Android — AutoEQ integration

Production-grade Android app for AutoEQ headphone correction.
This module implements the dev plan at [`../dev-plan/implement_20260507_223901.md`](../dev-plan/implement_20260507_223901.md).

## Architecture

```
android-app/
├── audio-engine/         (:audio-engine — Kotlin lib + native C++ DSP)
│   └── src/main/cpp/
│       ├── BiquadFilter.{h,cpp}     TDF2 biquad — audio-thread-only delay state
│       ├── AuralTuneEQEngine.{h,cpp} Heap EngineSnapshot + 500ms deferred retire;
│       │                            Manual + AutoEQ chains, pre-warp, NaN guard,
│       │                            audio-thread-owned delay reset on generation change
│       └── AudioEngineJNI.cpp       Handle-based JNI bridge
│
├── autoeq-data/          (:autoeq-data — Pure Kotlin data layer)
│   ├── parser/           IndexMd + ParametricEQ parsers
│   ├── repository/       OkHttp + cache + coalescing
│   ├── search/           3-tier fuzzy scoring
│   └── cache/            Catalog + LRU profile cache
│
└── app/                  (:app — Compose UI + AudioTrack pipeline)
    ├── audio/            AudioPlayerService (Float32 stereo loop)
    ├── data/             SettingsStore (DataStore)
    ├── di/               ServiceLocator (manual DI)
    └── ui/               AutoEqViewModel + Compose screens
```

## Phase 0 build matrix (locked)

| Setting | Value | Source |
|---|---|---|
| `minSdk` | 26 | AAudio support, Float32 PCM |
| `targetSdk` / `compileSdk` | 34 | Android 14 |
| AGP | 8.5.2 | `gradle/libs.versions.toml` |
| Kotlin | 1.9.24 | catalog |
| NDK | 27.0.12077973 | 16 KB page size — verified via `llvm-readelf -l` (Align 0x4000) |
| ABI | arm64-v8a, x86_64 | armeabi-v7a explicitly excluded |
| MVP pipeline | AudioTrack Float32 loop | simplest deterministic baseline |
| UI | Jetpack Compose Material 3 | – |
| DI | Manual `ServiceLocator` | no Hilt for MVP |
| Foreground Service | **out of MVP** | playback runs only while activity foregrounded |
| Catalog bootstrap | first-launch download | smaller APK |
| Profile ID | `sha256(source\|relativePath\|name).take(24)` | not slug |

## Build & run

### Prerequisites
- JDK 17 (Temurin / Homebrew `openjdk@17`)
- Android Studio Koala (2024.1.1) or later, OR command-line:
  - Android SDK 34
  - Android NDK 27.0.12077973 (or any r27+ for 16 KB page support)
  - CMake 3.22.1+
- A device or emulator with API ≥ 26 (Android 8.0)

### One-time setup
```bash
cd android-app
cp local.properties.example local.properties   # then edit sdk.dir / ndk.dir
```

### Build the APK
```bash
./gradlew :app:assembleDebug
```

### Install + launch on a connected device
```bash
./gradlew :app:installDebug
adb shell am start -n com.coreline.auraltune/.MainActivity
```

### Run unit tests
```bash
./gradlew :autoeq-data:test
```

### Run native unit tests (host)
```bash
cd audio-engine
g++ -std=c++17 -O2 -fno-finite-math-only \
    -I src/main/cpp \
    src/test/cpp/AuralTuneEQEngineTest.cpp \
    src/main/cpp/BiquadFilter.cpp \
    src/main/cpp/AuralTuneEQEngine.cpp \
    -o /tmp/run_native_tests && /tmp/run_native_tests
```

### Verify 16 KB page-size compliance (Android 15+)
```bash
SO=$(find app/build/intermediates \
  -path '*/merged_native_libs/release/*/out/lib/arm64-v8a/libauraltune_audio.so' \
  | head -1)
$ANDROID_HOME/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf -l "$SO" \
    | awk '/LOAD/ {print "Align="$NF}'
# Expected: Align=0x4000  (16 KB). 0x1000 means flags are missing.
```

## What the app does (MVP)

On first launch:
1. App shows a **"Test tone: ON/OFF"** toggle. Turning it on starts a 1 kHz stereo sine
   through the entire pipeline (AudioTrack ← AudioEngine ← TestTone).
2. App downloads `INDEX.md` from `raw.githubusercontent.com/jaakkopasanen/AutoEq` and
   parses it into a 5,000+ entry catalog (cached for 7 days).
3. Search field lets the user fuzzy-search ("airpod pro" → "Apple AirPods Pro").
4. Tapping a result downloads its `ParametricEQ.txt`, parses, and pushes to the
   native engine.
5. **Status card** shows the active profile + Correction/Preamp toggles.
6. **Diagnostics card** (collapsible) shows native counters: xrun, NaN reset,
   config swap, sample-rate change.
7. Empty state distinguishes: offline / no catalog / no match / loading.

## Phase coverage

| Phase | Status | Notes |
|---|---|---|
| Phase 0 — build/contract/thread/UX | ✅ | All decisions locked above. NDK r27 / 16 KB page. |
| Phase 1 — Native/JNI hardening | ✅ | AtomicLong handle (UAF-safe close), validated JNI, NaN guard, atomic counters |
| Phase 2 — DSP accuracy + atomic publish | ✅ | **Heap EngineSnapshot + 500 ms deferred retire** for realtime-safe pointer lifetime. Audio thread reads one pointer per callback; control thread allocates a fresh snapshot, atomic-exchanges, and queues the previous pointer for delayed deletion. Delay reset is audio-thread-owned via generation token. Pre-warp, Nyquist guard, RBJ, TDF2, sample-rate change. |
| Phase 3 — Kotlin parser | ✅ | BOM/CRLF, NaN reject, comment skip, ≤10 filters, error sealed class |
| Phase 4 — Catalog/cache/search | ✅ | LRU 200/5MB, coalescing, 3-tier fuzzy, telemetry interface |
| Phase 5 — Per-device persistence | 🟡 | SettingsStore + `AudioDeviceCallback` (`RouteWatcher`) → `engine.updateSampleRate(...)`. Profile-per-device key migration still post-MVP. |
| Phase 6 — AudioTrack pipeline + UX | ✅ | Float32 stereo, URGENT_AUDIO priority, **AudioTrack.getUnderrunCount → engine xrun counter**, diagnostics UI, empty states, profile restore on launch. Lifecycle UAF closed via stop()→pause/flush + interruptible join. |
| Phase 7 — Verification | 🟡 | Native + Kotlin unit tests + on-device instrumented suite included (12/12 PASS). Golden response (scipy) and Android CI workflow are follow-ups. |

## What's intentionally NOT in this drop

Per the dev plan's exclusion list:
- ExoPlayer/Oboe pipelines (chosen pipeline = AudioTrack for MVP)
- Foreground service / MediaSession / background playback
- Remote feature flag / kill switch (local DataStore flag only)
- Expert grouped multi-source/rig UI
- Latency compensation (measurement hook only)
- Limiter attack/release/lookahead upgrade

## Critical correctness invariants

These MUST NOT be regressed:

1. **Pre-warp** — coefficient math remains sample-rate aware. Without it, profile
   center frequencies drift at sample rates ≠ 48 kHz.
2. **TDF2** — `y = b0*x + s1; s1 = b1*x − a1*y + s2; s2 = b2*x − a2*y`.
   DF1 regression check is in `testStaleFilterReset` and golden response test.
3. **Whole-config atomic publish (heap + deferred retire)** — `AuralTuneEQEngine`
   allocates a fresh `EngineSnapshot` on the heap for every publish, populates
   it with the entire new cascade (manual + AutoEQ + flags + linear preamp),
   and atomically exchanges the published pointer. The previous snapshot goes
   onto a retire queue with a 500 ms grace deadline. Audio thread loads the
   pointer **once** per callback (`acquire`) and uses the same snapshot
   end-to-end. The grace is comfortably longer than the worst-case AudioTrack
   callback (~100 ms with the legacy buffer path), so the audio thread can
   never see a freed snapshot. **No mid-cascade tearing, no slot reuse race.**
4. **Audio-thread-owned delay state** — `BiquadFilter` only holds delay state
   (`z1L, z2L, z1R, z2R`). Coefficients live in the snapshot. Control thread
   NEVER calls `reset()` directly. Sample-rate change / hard reset publishes
   a snapshot with `requestDelayReset=true`; the audio thread observes the
   generation bump at the next callback and resets its own state before
   processing.
5. **Lifecycle UAF guard** — `AudioEngine` wraps the native handle in
   `AtomicLong` with CAS-then-destroy `close()`. `AudioPlayerService.stop()`
   calls `audioTrack.pause() + flush()` to unblock pending `WRITE_BLOCKING`
   writes, joins with timeout, and escalates via `Thread.interrupt()` if the
   thread is still alive. Engine is only freed AFTER thread join confirms
   exit. The Kotlin handle short-circuits stale calls into `process()` /
   `readDiagnostics()` BEFORE they reach JNI; the underlying JNI does NOT
   independently validate handle freshness, so callers MUST follow the
   ordering: `deviceManager.close()` → `player.close()` → `engine.close()`.

6. **Single engine writer (P0-3)** — `DeviceAutoEqManager` is the only class
   that calls `engine.updateAutoEq` / `engine.clearAutoEq`. ViewModel UI
   selections delegate via `selectProfileForCurrentDevice(...)`. This means
   route changes and UI selections cannot race / overwrite the engine state.

7. **AudioTrack is the source of truth for sample rate (P0-2)** — engine
   sample rate is locked to AudioPlayerService's configured rate; route
   detection (DeviceAutoEqManager.reconcile) does NOT call
   `engine.updateSampleRate(...)`. A coordinated AudioTrack rebuild path is
   post-MVP work — until then, AutoEQ pre-warp targets the AudioTrack rate,
   not the route-reported rate.
8. **NaN guard** — first-stereo-frame `!isfinite` check on the audio thread.
   Never disable `-fno-finite-math-only` (CI grep enforces).
9. **Stale filter reset** — when chain shrinks, trailing slots are filled with
   `BiquadCoeffs::unity()` in the next snapshot. Delay state for those slots
   either stays at zero (already reset) or gets reset via the generation
   bump on the next callback.
10. **16 KB page size** — CMake links with `-Wl,-z,max-page-size=16384`. Verify
    via `llvm-readelf -l libauraltune_audio.so | grep LOAD` → `Align 0x4000`.
    Without this, the loader fails on Android 15+ devices with 16 KB pages.
11. **Manual + AutoEQ preamp policy** — `EqEngineConfig` has `manualPreampDB = 0`
    forced; only AutoEQ chain has a preamp stage. Single-pass cascade order:
    Manual → AutoEQ preamp → AutoEQ → soft limiter.

## Telemetry / privacy (Phase 6)

`AutoEqTelemetry` is an interface — the MVP wires `NoOp` so nothing leaves the
device. When integrating Crashlytics/Sentry/etc, **do not** log:
- Bluetooth MAC addresses (use device key hash)
- Imported file names
- Raw profile IDs

`AutoEqRepository.setProtectedIds()` is the single source of truth for
"don't evict this profile from cache."

## Attribution

Headphone correction profiles courtesy of Jaakko Pasanen's
[AutoEq project](https://github.com/jaakkopasanen/AutoEq) (MIT License).

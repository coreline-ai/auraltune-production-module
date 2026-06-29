# AGENTS.md — AuralTune 개발 가이드 (에이전트용)

이 문서는 코딩 에이전트가 이 저장소에서 작업할 때 가장 먼저 읽는 단일 진입점이다.
제품 상세는 [`README.md`](README.md), 진행 중 작업 계획은 워크스트림별
`dev-plan/implement_*.md`(저장소 밖 작업 디렉터리)에 있다.

---

## 1. 프로젝트 정의

**AuralTune** — 헤드폰/이어폰마다 다른 주파수 응답을 보정해 "정확한 소리"로 만들어주는
Android 앱. Jaakko Pasanen의 **AutoEq** 보정 데이터를 받아, **자체 C++ DSP 엔진**
(RBJ 바이쿼드 EQ + ISO 226 라우드니스 보정 + BS.1770 오토레벨러)으로 실시간 적용한다.

> 핵심 자산 = **기기 무관하게 정확한 보정 엔진**(scipy.lfilter 대비 SNR 140–157 dB, TSan-clean,
> 힙 스냅샷 + atomic publish로 RT-safe). 이 정확도는 회귀시키면 안 되는 1순위 불변식이다.

---

## 2. 개발 목적 (현재 워크스트림: `multi-frontend`)

원래 앱은 **자기 앱 내부 소리에만** EQ를 적용했다. 이 워크스트림의 목적은:

> **검증된 자체 엔진(`.so`) 하나를, 상황에 맞는 여러 진입점으로 재사용해 EQ 적용 범위를 넓히는 것.**
> 엔진을 새로 만들지 않는다 — "어디에 꽂느냐"만 늘린다.

### 3트랙

| 트랙 | 적용 대상 | 처리(두뇌) | 음질 | 조건 | 상태 |
|---|---|---|---|---|---|
| **T1 · NDK** | 우리 앱이 재생하는 소리 | 자체 `.so` | **완전 정확** | 비루트·전 기기 | 코드 완성 |
| **T2 · MusicFX (AudioEffect)** | 외부 일부 앱(허용 앱) + 내부 세션 | 안드로이드 표준 effect | 근사 | 비루트·전 기기 | 예정 |
| **T3 · HAL/effect `.so`** | 전 앱(글로벌) | 자체 `.so` 시스템 등록 | 완전 정확 | 루트/Magisk | **보류(미래)** |

### 의사결정 배경 (왜 이렇게 갈렸나)
- **"진짜 전역(타 앱) ∧ 자체 `.so` 정확도"는 비루트에서 양립 불가** — 표준 effect로 전역을 얻으면
  처리를 OS/HAL에 위임해야 해 자체 엔진을 못 쓴다. 그래서 전역+정확은 루트(T3)에서만.
- 그래서 현실 선택: **내부앱은 정밀(T1), 외부앱은 표준 effect로 근사(T2), 전역 정확은 보류(T3)**.
- **T2-OS 근사**: `DynamicsProcessing`(API 28+) 우선, API 26–27은 `Equalizer` 폴백.
  필터 1:1 매핑이 아니라 **자체 엔진 freqz 곡선을 타깃으로 N밴드 피팅**해 근사 품질을 높인다.
- **외부앱 커버리지 주의**: `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`을 broadcast하는 앱에만
  적용 가능 — 메이저 스트리밍 다수가 미지원. T2는 **선행 PoC로 실측 후** 규모 결정(베타 가정).

---

## 3. 아키텍처 — 공유 코어 + 어댑터

```
                 core (공유, JNI-free, HAL-portable)
                 BiquadFilter · AuralTuneEQEngine · loudness/*
                          │
        ┌─────────────────┼─────────────────────┐
        ▼                 ▼                     ▼ (Phase F, 보류)
   jni/ 어댑터       effect/ 어댑터          (audio_effect.h ABI)
 libauraltune_audio.so   libauraltune_effect.so
   T1 (앱 내장)            T2-Custom/T3 (Magisk 등록)

[별도] T2-OS = 순수 Kotlin (android.media.audiofx), 코어 .so 무관
```

### Gradle 모듈
- **`:audio-engine`** — Kotlin `AudioEngine` 래퍼 + C++ DSP. `src/main/cpp/{core,jni}/`.
- **`:autoeq-data`** — 순수 Kotlin: AutoEq 카탈로그/프로파일 파서·캐시·리포지토리·검색.
- **`:app`** — Compose UI + 오디오 파이프라인.
  - `MusicPlayerController` + `AuralTuneAudioProcessor` — **T1 음악 재생**(ExoPlayer → EQ). 유일한 재생 경로.
  - (삭제됨) `AudioPlayerService`/`TestTone` — 테스트톤 전용이라 제거됨.
  - `AutoEqViewModel` — 오디오 스택(engine/musicController/deviceManager) **소유자**(retained). `audio/eq/`=20밴드 그래픽 EQ.

### T1 신호 경로
```
음악 파일(SAF) → ExoPlayer 디코드 → AuralTuneAudioProcessor(16bit→float, engine.process in-place)
              → DefaultAudioSink → AudioTrack (PCM_FLOAT)
```

---

## 4. 작업 시 반드시 지킬 원칙

1. **코어 무수정** — `core/`의 DSP 로직(특히 `AuralTuneEQEngine::process`, 바이쿼드 수학)은
   행위 변경 금지. 어댑터에서 호출만 한다.
2. **코어 순수성(HAL 포팅 대비)** — `core/`는 **JNI 의존 0**(`jni.h`/`JNIEnv`/j-타입 없음).
   `<android/log.h>`는 `#if defined(__ANDROID__)` 가드 + 호스트 no-op 패턴만 허용(현 코드 준수 중).
3. **불변식 보존** — README의 13개 Correctness Invariants(pre-warp, TDF2, atomic publish,
   NaN guard, 16KB page 등) 회귀 금지. `-fno-finite-math-only`와 16KB 링커 플래그(`Align 0x4000`) 유지.
4. **단일 엔진** — engine 인스턴스는 하나(retained `AutoEqViewModel` 소유)를 공유. 재생 경로는 ExoPlayer 단일.
5. **라이프사이클** — 오디오 스택은 **retained `AutoEqViewModel`이 소유**하고 `onCleared()`에서
   `deviceManager.close() → musicController.close() → engine.close()` 순으로 정리(각 사전단계 `runCatching`).
   Compose `remember` 소유 금지(회전 시 dead engine 참조 방지 — Phase 2에서 해소).
6. **PII 금지** — BT MAC / 파일명 / raw 프로파일 ID 로깅 금지(기기 키는 해시).
7. **범위 준수** — 현재 워크스트림 범위 밖(특히 T3 구현)은 하지 않는다. 부수 이슈는 dev-plan
   "이슈 추적"에 기록만.

---

## 5. 현재 진행 상황

> 멀티 프론트엔드(T1/T2/T3) 워크스트림과 그래픽EQ/DB/release-gate 워크스트림이 병행됐다.
> 후자의 상세·잔여는 `dev-plan/implement_20260622_110525.md` + `dev-plan/remaining_20260622.md` 참조.
```
✅ Phase 0  코어 분리 (core/ ↔ jni/, CMake OBJECT+SHARED)   → main
✅ Phase 1  T1 내부앱 정밀 (Media3 ExoPlayer + AudioProcessor) → 실기기 3종 검증(S25/PD20/SP4000T)
✅ 그래픽EQ 20밴드 + preset + Room DB-first(catalog/profile) + release gate → 실기기 검증
✅ 회전 버그 — RESOLVED(Phase 2: 오디오 스택을 retained ViewModel 소유로 이전)
⬜ Phase 2(T2-OS) 외부앱 근사 (MusicFX)                       → probe(AudioFxSessionProbe)만 존재, 본구현 대기
⏸ Phase F  T3 HAL 글로벌                                      → 보류 (코어 구조만 준비됨)
```

확정된 결정: D1 회전버그=**해소(retained VM)** · D2 디코더=ExoPlayer(로컬파일만) · D3 내부 AudioEffect=A/B검증한정(택일) ·
D4 근사밴드=DynamicsProcessing 10–15 · D5 T2=베타(PoC 후 정식화).

---

## 6. 빌드 & 검증

### 환경
JDK 17+ (이 저장소는 JDK 21로도 빌드 확인됨) · Android SDK 34 · NDK r27(`27.0.12077973`) ·
CMake 3.22.1+ · Gradle 8.9. `local.properties`에 `sdk.dir` 설정(gitignored).

### 단위 + 빌드 게이트
```bash
./gradlew :audio-engine:testDebugUnitTest :autoeq-data:testDebugUnitTest :app:testDebugUnitTest   # 180 PASS (71/96/13)
./gradlew :app:assembleDebug :app:assembleRelease
```

### 네이티브 호스트 테스트 (NDK clang, core/ 경로)
```bash
g++ -std=c++17 -O2 -fno-finite-math-only -I audio-engine/src/main/cpp/core \
    audio-engine/src/test/cpp/AuralTuneEQEngineTest.cpp \
    audio-engine/src/main/cpp/core/BiquadFilter.cpp \
    audio-engine/src/main/cpp/core/AuralTuneEQEngine.cpp \
    audio-engine/src/main/cpp/core/loudness/IsoContours.cpp \
    audio-engine/src/main/cpp/core/loudness/LoudnessCompensator.cpp \
    audio-engine/src/main/cpp/core/loudness/KWeightingFilter.cpp \
    -o /tmp/t && /tmp/t
```
> loudness 소스 3개는 필수다(`AuralTuneEQEngine`가 `LoudnessCompensator`/`IsoContours`/
> `LoudnessEqualizer` 심볼을 참조). 빠지면 링크 에러. (호스트 네이티브 테스트 빌드 시 직접 포함할 것.)

### 16KB 페이지 검증
```bash
llvm-readelf -l <.../arm64-v8a/libauraltune_audio.so> | awk '/LOAD/{print $NF}'   # 0x4000
```

CI 없음 — 이 저장소는 순수 코드 저장소이며 모든 검증은 개발 중 로컬에서 수행한다.
릴리스 전 로컬 게이트: `.\tools\release_readiness.ps1`(단위 + R8 + 디버그마커 스캔 + 16KB 정렬).

---

## 7. 협업 규칙

- **브랜치**: 워크스트림/리팩토링마다 새 브랜치(`refactor/*`, `feat/*`). main 직접 작업 금지.
- **커밋/푸시**: 사용자가 요청할 때만. 커밋 메시지 끝에 Co-Authored-By 라인.
- **dev-plan**: 작업은 해당 워크스트림 `dev-plan/implement_*.md`의 Phase·자가검증·완료조건을 따른다.
  현재 단계 자가검증을 통과하기 전 다음 단계로 넘어가지 않는다.
- **검증 없는 커밋 지양**: 빌드/테스트로 확인한 뒤 커밋. 실기기 동작이 필요한 변경은 그 한계를 명시.

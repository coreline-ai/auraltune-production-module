# 남은 작업 정리 (Remaining Backlog)

> 생성: 2026-06-22 · 기준 브랜치: `feat/t2-poc-probe` (G0만 커밋, 이후 미커밋)
> 출처: `implement_20260622_110525.md`(Phase 1–7) + `implement_20260617_122721.md`(멀티 프론트엔드 T1/T2/T3) + `implement_20260622_092110.md`(그래픽 EQ)
> 이 문서는 "무엇이 남았는지"의 단일 인덱스. 실제 착수 시 해당 Phase 문서를 갱신한다.

---

## ✅ 완료 (이번 세션까지)

| 항목 | 상태 | 검증 |
|---|---|---|
| 그래픽 EQ GUI (G0–G3) | ✅ | 실기기 3종(S25/PD20/SP4000T) |
| 디바이스 독립 동작 | ✅ | 비루트 표준 API + 순수 .so, 3기종 실측 |
| Test tone 코드 제거 | ✅ | 빌드/테스트 |
| **Phase 0** 기준점 | ✅ | — |
| **Phase 2** 회전/생명주기(retained VM 소유) | ✅ | 실기기 회전 |
| **Phase 3** 그래픽 EQ preset 저장/복원 | ✅ | 실기기 재시작/리셋/round-trip |
| **Phase 5** 프로파일 DB 저장 + miss fallback | ✅ | 단위 + 실기기 v2 마이그레이션·오프라인 로드 |

---

## 🟢 추가 완료 (2026-06-23 — 포맷 무관 엔진 I/O)
- **엔진 비트뎁스 I/O 범용화**: `core/PcmConvert.h`(16/24/32-int + float32 ↔ float32) + `AuralTuneEQEngine::processFormatted` + JNI `nativeProcessFormatted` + Kotlin `AudioEngine.processFormatted`/`PcmFormat` enum. **DSP 코어는 float32 유지**(정밀도 최상). 샘플레이트 8000–384000은 이미 지원. → **.so가 모든 비트뎁스/레이트 입출력 지원**(호스트는 고정해서 사용). QA: 변환 24단언(round-trip/클램프/S24 부호확장/packing) + Kotlin lockstep 4테스트 PASS.
- 후속: 실제 하이레이트(24/32·384k) 직접재생 경로는 미구현(T1 ExoPlayer는 16/소스레이트 고정 유지). 필요 시 별도 AudioTrack 경로.

## 🟢 추가 완료 (2026-06-23 — 전체 prebuilt seed + 증분 갱신)
> 상세: `dev-plan/implement_20260623_090000.md`
- **전체 프로파일 prebuilt seed** ✅: catalog **6,028** + profiles **6,027** + filters **60,270** → `autoeq_seed.db`(11.65MB, APK +3.45MB) createFromAsset. 검색→선택→**적용까지 즉시·오프라인**. QA: createFromAsset 오픈(identityHash 일치)+카운트+샘플 검증.
- **증분(delta) 갱신** ✅: `syncDelta()` GitHub compare API — 변경분만 fetch/upsert, removed 삭제, NoChange 0다운로드, kill switch 차단. QA 7테스트 + 엣지 2건 수정(300-file 페이지네이션→Failed·커밋미전진 / 비ASCII·`+` 경로 URLDecoder fallback).
- 후속: 실기기 오프라인 즉시적용 검증(삼성·데이터초기화), 번들 INDEX.md 중복 제거, 300+ delta full-resync 경로 + 백그라운드 트리거/UI.

## 🟢 추가 완료 (2026-06-22 후속 세션 — 병렬 분석 + 전담 QA 적대적 검증)

### Phase 7 — 통합 검증 + 문서 정합  ·  상태 `~` (대부분 완료)
- **완료**: 적대적 릴리스 감사로 **미게이트 PII 로그 누수 발견·수정**(MusicPlayerController/DeviceAutoEqManager `Log` → `BuildConfig.DEBUG` gate), proguard `-assumenosideeffects Log{v,d,i}` 추가. QA 재감사: release dex PII 문자열 **0건**, probe TAG도 **0건**(proguard가 미게이트 probe까지 제거), 16KB 정렬 PASS, manifest 권한 정확히 5개. 테스트 **156개**(67/83/6) 확정. README/AGENTS/docs(autoeq·loudness)/AudioEngine KDoc 정합 완료.
- **남은**: [ ] Android CI workflow(ci/android.yml) 갱신, [ ] release APK analyzer 정기 검사 자동화.

### Phase 1 — Release DEBUG gate  ·  상태 `x` (완료 — facade 포함)
- **완료**: 미디어 권한 `src/debug` manifest 분리 + 모든 debug 코드 `BuildConfig.DEBUG` gate + proguard Log strip.
- **완료 (A1)**: ✅ `DebugSupport` 파사드(`src/debug`=실제 firstPlayableUri+AudioFxProbeCard / `src/release`=no-op) + `AudioFxSessionProbe.kt`를 src/debug로 이동. **QA 검증: main-source 참조 0건, release dex에 probe 클래스·디버그 문자열 구조적 부재(0), debug 빌드엔 기능 유지, lintVital release clean.**

### 신규 항목 (QA 식별)
- [ ] **allowBackup 결정** (MEDIUM): `android:allowBackup="true"` + 백업 규칙 없음. per-device EQ 선택/device-key 해시가 백업 대상. → `allowBackup=false` 또는 `dataExtractionRules` allowlist. **사용자 결정 필요**(프리셋 백업 vs 프라이버시 트레이드오프).

---

## 🟠 진행 중 (부분 완료, 후속 정밀화 필요)

### Phase 4 — Room DB-first catalog  ·  상태 `~` (4a + tombstone 완료)
- **완료(4a)**: Room v1, 번들 INDEX.md 첫 실행 seed, ETag 조건부 갱신, 레거시 catalog.json 마이그레이션, 오프라인 검색(실기기).
- **완료(4b 일부)**: ✅ **tombstone sweep**(`CatalogDao.tombstoneOlderThan` + `applyRemote` 전체 동기화 시 제거 항목 isDeleted) — QA 적대적 검증(동일 ms 이중동기화/빈리스트 무wipe/시계역행 회귀가드 3개) 통과.
- **남은 (Phase 4b 후속)**:
  - [ ] `catalog_fts`(FTS4/5) 테이블로 DB-side 검색 성능 강화 (현재는 메모리 fuzzy index — 충분히 빠름)
  - [ ] Room `MigrationTestHelper` 기반 migration test (현재 v1→v2 실기기 검증으로 대체됨)
  - [ ] ETag 회귀 테스트: unchanged → DB write 0, malformed index → 기존 DB 보존
  - 우선순위: **낮음** · 규모: S–M · 사유: 핵심 정합(tombstone)은 완료. FTS는 성능 최적화(불요불급).

---

## 🟡 신규 착수 대기 Phase

### Phase 6 — T2-OS MusicFX 외부앱 근사 EQ  ·  상태 `~` (피팅/백엔드 코드 완료)
> 외부 플레이어가 effect-control-session을 제공할 때만 OS AudioEffect로 근사 EQ를 attach.
- **완료(코드)**: ✅ 6-2 `OsEffectBackend`(DynamicsProcessing API28+ / Equalizer fallback, attach 팩토리, 생성자 누수 가드) · ✅ 6-3 `ExternalAudioFxController`(OPEN/CLOSE 수신, session map, timeout release, provider 예외 가드, close 메인스레드 confine) · ✅ 6-4 `AutoEqApprox`(자체 freqz 타깃 → OS 밴드 fitting + RMS/max dB 오차, 단위테스트). **QA가 `gridPoints≤1` NaN 버그 발견 → 수정·green.**
- **남은**:
  - [ ] 6-1 **coverage gate**(기기 필요): Spotify/YouTube/Samsung Music 등 OPEN/CLOSE 수신 실측표 — `AudioFxSessionProbe`로 측정. **외부앱·계정·수동재생 필요.**
  - [ ] 6-5 internal A/B: 자체 `.so` 정밀 경로와 T2 effect 동시 적용 금지(택일) 가드
  - [ ] 6-6 UX: "근사/지원 앱 한정/기기 의존" 표시 + 와이어링(현재 컨트롤러 미기동)
  - [ ] 6-7 실기기 검증: 지원 앱 attach→apply→release, DynamicsProcessing/Equalizer 분기
  - 우선순위: **중** · 규모: 남은 부분 M · 사유: 코드 기반은 섰고, **가치는 coverage(외부앱 실측)에 좌우**. 기기·외부앱 확보 후 6-1부터.

### Phase 7 — 통합 검증 및 문서 정합화  ·  상태 `[ ]`
- [ ] 검증 명령 일괄: `compileDebugKotlin`+모든 모듈 `testDebugUnitTest`+`assembleRelease`
- [ ] **16KB native alignment** 확인(`llvm-readelf -l libauraltune_audio.so`, Android 15+)
- [ ] release APK analyzer로 debug class/string 노출 0 확인(apkanalyzer/dex dump)
- [ ] `README.md` 갱신: DB-first catalog, retained VM lifecycle, release gate, 현재 아키텍처
- [ ] `docs/autoeq.md`: DB schema + fallback 흐름 / `docs/loudness-compensation.md` drift 재검토
- [ ] dev-plan 문서들 상태 정합화
- 우선순위: **높음(릴리스 직전)** · 규모: M · 사유: 릴리스 후보 고정.

---

## 🔵 query-driven 온라인 fallback (Phase 4b/5b 공통)
> 프로파일 fetch의 DB-first/coalescing/kill-switch는 Phase 5에서 완료. 아래는 **카탈로그 검색** 경로의 온라인 인덱스 fallback(타이핑 루프에서 네트워크 금지 정책).
- [ ] `searchWithFallback()`(또는 Flow) API: 자동 타이핑 검색에서 네트워크 미접속
- [ ] 정책: 최소 3자 / debounce 800ms / 사용자 "온라인 검색" 버튼 or background sync / single-flight / cooldown / kill-switch 차단
- [ ] "Clear cache" 시 DB catalog 재seed + DB profile 정리(현재는 레거시 캐시만 clear)
- 우선순위: **낮음** · 규모: M · 사유: 오프라인 seed가 대부분 커버. 신규 헤드폰 즉시 검색 시 가치.

---

## ⚪ 소규모 마무리 (Nice-to-have)
| ID | 항목 | 규모 | 비고 |
|---|---|---|---|
| A2 | `ActivityScenario.recreate()` 계측 테스트(회전) | S | 실기기 회전으로 동등 검증됨 |
| A3 | 그래픽 EQ default presets(코드 상수) | S | 요구 없었음 |
| A5 | "stored filters == parser output" 동치 테스트 | S | Phase 5 보강 |
| — | 그래픽 EQ 게인 범위 ±12↔±15, preamp 그래프 반영 여부 | S | 사용자 결정 대기(092110 Open) |
| — | 20밴드 Q 튜닝(겹침 자연스러움) | S | 청취 기반 |

---

## ⏸ 보류 (미래 워크스트림 · 122721)

### Phase F — T3 HAL/effect `.so` 글로벌 적용
> 전 앱 시스템 전역 적용. **이번 범위 제외**, 코어 `.so`는 포팅 가능하도록 순수 유지(JNI 의존 0 — 검증됨).
- effect `.so`(`audio_effect.h` 비공개 ABI) 구현
- Magisk 모듈 / SELinux(audioserver dlopen) / `/vendor` 등록(overlay)
- AudioFlinger s16↔float 협상, SET_DEVICE 라우팅(헤드폰만)
- 우선순위: **보류** · 규모: **XL** · 사유: 루트 필요, OS 버전별 ABI 리스크. 별도 워크스트림.

---

## 권장 진행 순서

1. **Phase 7**(릴리스 검증/문서) — 지금까지 산출물을 릴리스 후보로 고정. 16KB·release leak·문서 정합.
2. **Phase 1 A1**(src/debug facade) — 정적 위생 마무리(Phase 7과 함께 하면 시너지).
3. **Phase 4b**(FTS/tombstone/migration test) — DB 정합/성능.
4. **Phase 6**(T2-OS) — 외부앱 coverage 조사부터. 가치 확인 후 본구현.
5. **query-driven 온라인 fallback** — 필요 시.
6. (보류) **Phase F (T3)** — 별도 워크스트림.

> 비고: 핵심 사용자 가치(인앱 AutoEQ + 그래픽 EQ + 오프라인 카탈로그/프로파일 + 디바이스 독립)는 이미 동작·검증 완료. 남은 항목은 **릴리스 위생(7,1) · DB 정합 강화(4b) · 외부앱 확장(6) · 글로벌 적용(F)** 으로 분류된다.

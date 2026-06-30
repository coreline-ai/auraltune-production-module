# 남은 작업 정리 (Remaining Backlog)

> 생성: 2026-06-22 · 갱신: 2026-06-26
> 출처: `implement_20260622_110525.md`(Phase 1–7) + `implement_20260617_122721.md`(멀티 프론트엔드 T1/T2/T3) + `implement_20260622_092110.md`(그래픽 EQ)
> 이 문서는 "무엇이 남았는지"의 단일 인덱스. 실제 착수 시 해당 Phase 문서를 갱신한다.

---

## 🟦 2026-06-26 현재 상태 (정합화)

핵심 사용자 가치는 `main` 기준으로 완료되었고, 2026-06-26 남은 작업 패치는 **현재 작업트리(커밋 전)**에 반영되어 있다. 최신 기준:
- ✅ 그래픽 EQ 게인 한계 칩(±6/±12/±15/±20, 불변식 강제) + 프리앰프 마커선 + preamp 적용 시 곡선 평행 하강
- ✅ AutoEQ ON/OFF **0.5초 선형 wet/dry 크로스페이드**("스르륵") + kill switch **즉시 차단**(4-렌즈 적대적 리뷰: kill 잔존·램프 off-by-one 수정)
- ✅ recents 빠른선택 스피너(큐레이션 pre-seed), SAF 곡 선택(+ActivityNotFound 가드), 빈상태 문구 분기
- ✅ UI 재배치: kill switch 상단 / 프로파일+correction·preamp 토글을 그래픽 EQ 위로 묶음
- ✅ allowBackup=false + `data_extraction_rules.xml`(프라이버시), Compose BOM 2024.09.00(호버 크래시)
- ✅ 사용자 결정: allowBackup(끄기), 게인범위(칩 선택) — 둘 다 반영

**완료(feat/release-hygiene, 2026-06-24):**
1. ✅ manual 체인 무조건 enable 버그(AutoEqViewModel) 정정 — applyGraphicEq와 동일 규칙
2. ✅ 로컬 release 검증 스크립트(`tools/release_readiness.ps1`) + 본 문서 정합화
3. ✅ 번들 INDEX.md(860KB) 제거(prebuilt seed로 중복, seedIfNeeded는 안전 no-op) + delta 백그라운드 자동체크(24h 쿨다운) + "프로파일 업데이트 확인" 버튼
4. ✅ Phase 6 T2-OS coverage **조사 완료** → `t2-os-coverage-20260624.md` (결론: 주력 No-go, 폴백 conditional-go; 측정 도구 준비됨, 실기기 측정은 사용자 수행). 본구현 보류 유지.

**🚫 범위 제외 — 2026-06-24 사용자 결정: "외부앱은 고려 대상에서 제외".**
앱은 **T1(인앱 정확 EQ) 전용**으로 확정. 외부앱/시스템 전역 적용 트랙은 **전부 제외**:
- **T2-OS**(내장 effect 세션 attach) — coverage 빈약·플랫폼 역풍(`t2-os-coverage-20260624.md`)
- **T2-Custom / T3(HAL)** + **M0 effect `.so` 분리** — 외부/전역 + 루트 필요 → 불필요
- 관련 코드는 휴면 보존(삭제 안 함): `app/.../audio/audiofx/*`, `src/debug/.../AudioFxSessionProbe.kt`, PoC 카드. 멀티프론트엔드 계획(`implement_20260617_122721.md`)·coverage 조사는 보관(archived).

**현재 남음(T1 범위 내):** 필수 기능 관점의 미완료 항목은 없다. OPRA 상업 출시 고지는 현재 문구로 충분하다는 사용자 결정에 따라 완료로 본다. 기기별 오디오 라우트 수동 QA는 기능 완료 판단과 분리된 선택 운영 증적이다. AutoEQ DB 회귀 테스트(Migration/ETag/malformed index/stored-filter 동치), 300+ delta cap 초과의 전체 카탈로그 resync fallback + UI 안내는 `implement_20260626_144046.md`에서 패치 완료. 파라메트릭 EQ 시작점 프리셋은 `implement_20260629_165553.md` 기준으로 구현 완료. query-driven 온라인 검색 fallback, 하이레이트 직접재생(24/32·384k AudioTrack), 그래픽 EQ 추가 튜닝은 선택 후속으로 유지.

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
| **파라메트릭 EQ 시작점 프리셋** | ✅ | 단위 테스트 + debug/release 빌드 |

---

## 🟢 추가 완료 (2026-06-23 — 포맷 무관 엔진 I/O)
- **엔진 비트뎁스 I/O 범용화**: `core/PcmConvert.h`(16/24/32-int + float32 ↔ float32) + `AuralTuneEQEngine::processFormatted` + JNI `nativeProcessFormatted` + Kotlin `AudioEngine.processFormatted`/`PcmFormat` enum. **DSP 코어는 float32 유지**(정밀도 최상). 샘플레이트 8000–384000은 이미 지원. → **.so가 모든 비트뎁스/레이트 입출력 지원**(호스트는 고정해서 사용). QA: 변환 24단언(round-trip/클램프/S24 부호확장/packing) + Kotlin lockstep 4테스트 PASS.
- 후속: 실제 하이레이트(24/32·384k) 직접재생 경로는 미구현(T1 ExoPlayer는 16/소스레이트 고정 유지). 필요 시 별도 AudioTrack 경로.

## 🟢 추가 완료 (2026-06-23 — 전체 prebuilt seed + 증분 갱신)
> 상세: `dev-plan/implement_20260623_090000.md`
- **전체 프로파일 prebuilt seed** ✅: catalog **6,028** + profiles **6,027** + filters **60,270** → `autoeq_seed.db`(11.65MB, APK +3.45MB) createFromAsset. 검색→선택→**적용까지 즉시·오프라인**. QA: createFromAsset 오픈(identityHash 일치)+카운트+샘플 검증.
- **증분(delta) 갱신** ✅: `syncDelta()` GitHub compare API — 변경분만 fetch/upsert, removed 삭제, NoChange 0다운로드, kill switch 차단. QA 7테스트 + 엣지 2건 수정(300-file 페이지네이션→Failed·커밋미전진 / 비ASCII·`+` 경로 URLDecoder fallback).
- 후속: 실기기 오프라인 즉시적용 검증(삼성·데이터초기화), 번들 INDEX.md 중복 제거. 300+ delta는 cap 초과 시 partial apply를 금지하고 전체 INDEX를 재적용한 뒤 built-in/fetched 프로파일을 on-demand 재다운로드 대상으로 무효화한다. 전체 프로파일 6천여 개 즉시 재다운로드는 출하 UX/네트워크 리스크로 제외했다.

## 🟢 추가 완료 (2026-06-22 후속 세션 — 병렬 분석 + 전담 QA 적대적 검증)

### Phase 7 — 통합 검증 + 문서 정합  ·  상태 `x` (릴리스 게이트 보강 완료, 외부 수동 증적만 잔여)
- **완료**: 적대적 릴리스 감사로 **미게이트 PII 로그 누수 발견·수정**(MusicPlayerController/DeviceAutoEqManager `Log` → `BuildConfig.DEBUG` gate), proguard `-assumenosideeffects Log{v,d,i}` 추가. QA 재감사: release dex PII 문자열 **0건**, probe TAG도 **0건**(proguard가 미게이트 probe까지 제거), 16KB 정렬 PASS, manifest 권한 정확히 5개. 이후 OPRA/플레이어/스펙트럼/파라메트릭/DB/라우트 정책 회귀 hardening 반영 기준 테스트 목표는 **246개**. README/디자인핸드오프는 탭별 독립선택, OPRA parser-version force import, 큐 영속화, 실시간 스펙트럼, 로컬 release 검증 기준으로 갱신.
- **완료(2026-06-26)**: 로컬 release debug-marker scan과 AutoEQ DB 회귀 테스트(Migration/ETag/malformed index/stored-filter 동치), 300+ delta 전체 카탈로그 resync fallback/UX 안내를 추가했다.
- **선택 운영 증적**: [ ] 기기별 오디오 라우트 수동 spot check 증적 채우기(유선/Bluetooth/USB/스피커/HDMI 물리 연결 필요). 기능 완료 판단에는 영향 없음.

### Phase 1 — Release DEBUG gate  ·  상태 `x` (완료 — facade 포함)
- **완료**: 미디어 권한 `src/debug` manifest 분리 + 모든 debug 코드 `BuildConfig.DEBUG` gate + proguard Log strip.
- **완료 (A1)**: ✅ `DebugSupport` 파사드(`src/debug`=실제 firstPlayableUri+AudioFxProbeCard / `src/release`=no-op) + `AudioFxSessionProbe.kt`를 src/debug로 이동. **QA 검증: main-source 참조 0건, release dex에 probe 클래스·디버그 문자열 구조적 부재(0), debug 빌드엔 기능 유지, lintVital release clean.**

### 신규 항목 (QA 식별)
- [x] **allowBackup 결정** (MEDIUM): `android:allowBackup="false"` + `data_extraction_rules.xml` 전 도메인 제외로 확정. per-device EQ 선택/device-key 해시는 백업되지 않는다.

---

## 🟠 선택 후속 / 수동 검증

### Phase 4 — Room DB-first catalog  ·  상태 `~` (4a + tombstone 완료)
- **완료(4a)**: Room v1, 현재 prebuilt `autoeq_seed.db` 첫 실행 seed, ETag 조건부 갱신, 레거시 catalog.json/INDEX.md seed fallback, 오프라인 검색(실기기).
- **완료(4b 일부)**: ✅ **tombstone sweep**(`CatalogDao.tombstoneOlderThan` + `applyRemote` 전체 동기화 시 제거 항목 isDeleted) — QA 적대적 검증(동일 ms 이중동기화/빈리스트 무wipe/시계역행 회귀가드 3개) 통과.
- **완료(2026-06-26)**: Room v1→v2 schema-open migration test, ETag 304 no-op, malformed index 기존 DB 보존, stored-filter/parser 동치 테스트 추가.
- **남은 (Phase 4b 후속)**:
  - [ ] `catalog_fts`(FTS4/5) 테이블로 DB-side 검색 성능 강화 (현재는 메모리 fuzzy index — 충분히 빠름)
  - [x] Room `MigrationTestHelper` 기반 migration test 또는 schema-open 회귀 테스트
  - [x] ETag 회귀 테스트: unchanged → DB write 0, malformed index → 기존 DB 보존
  - 우선순위: **낮음** · 규모: S–M · 사유: 핵심 정합(tombstone)은 완료. FTS는 성능 최적화(불요불급).

---

## 🟡 신규 착수 대기 Phase

### Phase 6 — T2-OS MusicFX 외부앱 근사 EQ  ·  상태 `보류` (피팅/백엔드 코드는 휴면 보존)
> 외부 플레이어가 effect-control-session을 제공할 때만 OS AudioEffect로 근사 EQ를 attach.
- **완료(코드)**: ✅ 6-2 `OsEffectBackend`(DynamicsProcessing API28+ / Equalizer fallback, attach 팩토리, 생성자 누수 가드) · ✅ 6-3 `ExternalAudioFxController`(OPEN/CLOSE 수신, session map, timeout release, provider 예외 가드, close 메인스레드 confine) · ✅ 6-4 `AutoEqApprox`(자체 freqz 타깃 → OS 밴드 fitting + RMS/max dB 오차, 단위테스트). **QA가 `gridPoints≤1` NaN 버그 발견 → 수정·green.**
- **남은**: 없음(현재 출시 범위에서는 제외). 필요 시 별도 워크스트림에서 coverage gate부터 재개한다.
- 우선순위: **보류** · 규모: M · 사유: 사용자 결정으로 외부앱/시스템 전역은 제외.

### Phase 7 — 통합 검증 및 문서 정합화  ·  상태 `[~]`
- [x] 검증 명령 일괄 대상 갱신: `compileDebugKotlin`+모든 모듈 `testDebugUnitTest`+`assembleRelease`
- [x] **16KB native alignment** 확인(`llvm-readelf -l libauraltune_audio.so`, Android 15+)
- [x] release APK analyzer로 debug class/string 노출 0 확인(로컬 검증 완료). `tools/release_readiness.ps1`로 Windows 로컬 release 검증을 재실행할 수 있다.
- [x] `README.md` 갱신: DB-first catalog, retained VM lifecycle, 로컬 release 검증, 현재 아키텍처
- [x] `docs/autoeq.md`: DB schema + fallback 흐름 / `docs/loudness-compensation.md` drift 재검토 완료. AutoEQ는 prebuilt Room seed/v2 profile table/delta full-resync 흐름을 문서화했고, loudness는 엔진 구현 완료 + 앱 UI 후속 범위로 정정했다.
- [x] dev-plan 문서들 상태 정합화(2026-06-26 릴리스 후보 기준으로 갱신 완료). 잔여는 선택 운영 증적인 물리 라우트 QA뿐이며 기능 완료 판단에는 영향 없음.
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
| A2 | `ActivityScenario.recreate()` 계측 테스트(회전) | S | 완료: `SmokeTest.mainActivity_recreate_survives`, SM-S931N connected 13 tests PASS |
| A3 | 그래픽 EQ default presets(코드 상수) | S | 요구 없었음 |
| A5 | "stored filters == parser output" 동치 테스트 | S | 완료: `AutoEqRepositoryTest` |
| A6 | 실제 실패 파일 fixture 주입 테스트 | S | 완료: malformed `ParametricEQ.txt` fixture 주입, DB row 미생성 확인 |
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

1. **현재 작업트리 최종 diff 검토 후 커밋** — AutoEQ DB 테스트, delta fallback, 문서 정합화 패치를 한 묶음으로 고정.
2. **로컬 release 검증 재실행** — `tools/release_readiness.ps1` 기준으로 빌드/테스트/마커 스캔/16KB 정렬을 확인.
3. (선택) **기기별 오디오 라우트 수동 QA 매트릭스 채우기** — 유선/Bluetooth/USB/스피커/HDMI 별 적용 유지 증적 확보.
4. (선택) **query-driven online fallback / hi-res direct path / tuning 편의 기능** — 별도 착수.
5. (보류) **Phase F (T3)** — 별도 워크스트림.

> 비고: 핵심 사용자 가치(인앱 AutoEQ + OPRA + 그래픽/파라메트릭 EQ + 오프라인 AutoEQ catalog/profile + bundled OPRA snapshot + 디바이스 독립)는 이미 동작·검증 완료. OPRA 고지는 현재 문구로 충분하다는 사용자 결정에 따라 완료로 본다. 남은 항목은 **선택 수동 QA 증적 · 선택 후속 기능**으로 분류한다. DB 정합 강화와 delta cap 정책은 `implement_20260626_144046.md`에서 패치 완료했다.

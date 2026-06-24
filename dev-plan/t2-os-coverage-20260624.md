# Phase 6 (T2-OS) — 외부앱 effect-control-session Coverage 조사

> 생성: 2026-06-24 · 워크스트림: `implement_20260617_122721.md` Phase 6 (T2-OS MusicFX 방식)
> 목적: "외부 플레이어가 `OPEN_AUDIO_EFFECT_CONTROL_SESSION`을 브로드캐스트하면 그 세션에 OS Equalizer/DynamicsProcessing을 attach" 전략의 실효 coverage를 측정·판단.

> **📦 ARCHIVED — 2026-06-24 사용자 결정: "외부앱은 고려 대상에서 제외".**
> 앱은 T1(인앱 정확 EQ) 전용으로 확정 → 이 조사(및 T2-OS 본구현)는 보류. 관련 코드는 휴면 보존(삭제 안 함).
> 아래 내용은 추후 외부앱을 재고할 경우의 참고 자료로 보관한다.

---

## 결론 (TL;DR) — 주력 전략으론 **No-go**, 폴백 1계층으로만 **conditional-go**

브로드캐스트는 **앱의 선택(opt-in)**이고 강제 규약이 없어, 트렌드상 보내는 앱이 늘지 않고 줄어든다.
- **고가치 스트리밍앱 미지원**: Tidal, Apple Music, Amazon Music, YouTube(메인), SoundCloud, Qobuz, Pandora — 세션 기반 effect 제어 불가.
- **Spotify·YouTube Music "동작"**은 깨끗한 per-session OPEN이 아니라 **deprecated된 global-session(세션0) 자동감지** 경로. 기기 의존적.
- **실질 coverage가 좋은 곳은 로컬/오픈소스 플레이어**(ExoPlayer/Media3 기반이 세션을 열거나 브로드캐스트하는 경우) — 정작 사용자가 EQ를 원하는 메이저 스트리밍이 아님.
- **플랫폼 역풍**: global effect(세션0) deprecated, 암시적 브로드캐스트 제한(Android 8+, 컨텍스트 등록+FGS 필요), Android 14 FGS 타입/권한 강제, 타 앱 세션 열거 불가(공개 API 없음, DUMP는 adb로만 부여), DRM/Hi-Res/Atmos 경로는 effect 우회.
- **선두 서드파티(Wavelet/Poweramp)조차** 브로드캐스트만으론 부족해 **ADB DUMP 권한·접근성/알림리스너·global-mix·capture** 등 특권 우회로 이동.

→ "모든 앱에서 동작"으로 마케팅 불가. **이 프로젝트의 가치 축은 T1(인앱 플레이어, 100% 정확)과 T2-Custom/T3(루트, 정확)에 유지**하고, T2-OS는 측정 후 가치가 확인된 일부 앱에 한해 폴백으로만 고려.

---

## 측정 도구 (이미 준비됨)

`app/src/debug/.../audiofx/AudioFxSessionProbe.kt` — 디버그 빌드 전용.
- 런타임 BroadcastReceiver로 `ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION` 수신(암시적 브로드캐스트라 manifest 정적 등록 불가).
- 기록: `action(OPEN/CLOSE) · packageName · audioSession · contentType` (최근 50건, StateFlow → `DebugSupport.AudioFxProbeCard` 화면 "외부앱 신호 측정 (PoC)").
- API 33+ `RECEIVER_EXPORTED` 처리. PII: 패키지명은 디버그 표시에만, telemetry 미전송.

실제 attach 컨트롤러 `ExternalAudioFxController`는 **의도적으로 미기동(게이트)** — coverage가 확인된 뒤 본구현.

## 측정 절차 (실기기, 사용자 수행)

1. **디버그 빌드** 설치(예: S25). AuralTune 실행 → 화면에 "외부앱 신호 측정 (PoC)" 카드("감지 N건").
2. 앱을 **켠 채로(프로브 수신 중)** 외부 음악앱을 하나씩 재생 시작/정지.
3. 카드의 "감지 N건"과 logcat `AudioFxProbe` 태그로 OPEN/CLOSE·패키지·세션 확인.
4. 아래 표에 기록(보냄/안보냄, OPEN인지 CLOSE 오발신인지).
> 주의: 프로브가 수신하려면 앱이 살아있어야 함(컨텍스트 등록). 백그라운드 장시간 수신은 본구현에서 mediaPlayback 타입 FGS 필요.

### 결과표 템플릿 (측정해서 채우기)
| 앱 | OPEN 세션 브로드캐스트? | 비고(세션id/콘텐츠타입/이상동작) |
|---|---|---|
| Spotify | ? | 리서치: global-session으로 동작(깨끗한 OPEN 아닐 수 있음) |
| YouTube Music | ? | 리서치: global-session |
| YouTube(메인) | ? | 리서치: 미지원(세션ID 로깅만) |
| 삼성 Music | ? | 리서치: 동작 보고 |
| Tidal | ? | 리서치: 미지원 |
| Apple Music | ? | 리서치: 미지원/버그(DRM·Atmos 우회) |
| Amazon Music | ? | 리서치: 미지원/불안정 |
| SoundCloud | ? | 리서치: 미지원 |
| Deezer | ? | 리서치: CLOSE를 OPEN 대신 오발신(버그, 2024 확인) → 방어 처리 필요 |
| VLC / 로컬 플레이어 | ? | 리서치: 추가설정/대체로 양호 |

---

## 리서치 요약 (출처 기반, 2024–2026)

- **플랫폼**: 세션0 global effect는 수년째 deprecated(기기 의존). 브로드캐스트는 voluntary — ExoPlayer/Media3 기본 미발신. 타 앱 세션 열거 공개 API 없음. "enhanced" 경로는 **DUMP 권한(adb로만)** 필요.
- **OEM**: 삼성 OneUI는 자체 "사운드 품질 및 효과"로 라우팅 경향, Hi-Res 미감지 시 밴드 주파수 시프트. global-mix EQ는 삼성/샤오미 동작·최신 픽셀 실패 등 기기별 상반.
- **서드파티 현황**: Wavelet(DynamicsProcessing, legacy 모드는 OEM 의존 + enhanced는 ADB DUMP), Poweramp(Advanced Player Tracking = 추가 권한), capture 기반(MediaProjection + mic 권한 + 지연/ DRM 제약).

### 핵심 출처
- Esper: https://www.esper.io/blog/android-equalizer-apps-inconsistent
- nift4 (Android audio stack): https://nift4.org/2025/08/09/android-audio-stack-music-player/
- Wavelet 호환 매트릭스: https://pittvandewitt.github.io/Wavelet/ · https://github.com/Pittvandewitt/Wavelet/blob/master/docs/Settings.md
- Poweramp EQ KB: https://forum.powerampapp.com/peq-kb/
- Deezer OPEN/CLOSE 오발신: https://en.deezercommunity.com/android-47/app-reports-incorrect-...-78641
- Android 14 FGS types: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Google issuetracker(세션0 deprecate): https://issuetracker.google.com/issues/36936557

---

## 권고 / 다음 단계

1. **본구현 보류 유지** — coverage가 메이저 스트리밍에서 빈약하고 플랫폼 역풍이 큼.
2. 만약 진행한다면 **다계층 + 정직한 기대치**: ① OPEN/CLOSE 수신(Deezer식 오발신 방어) + mediaPlayback FGS, ② global-mix(MODIFY_AUDIO_SETTINGS, 기기 의존 명시) 폴백, ③ 파워유저용 ADB DUMP enhanced 모드, ④ 미지원 앱 인앱 명시(지원부하 절감), ⑤ 인앱 Media3 플레이어(T1)로 로컬은 100% 보장.
3. **측정 우선**: 위 절차로 S25(+가능 시 추가 기기)에서 실제 표를 채워 "우리 타깃 사용자 앱"의 실 coverage를 수치화한 뒤 ①의 가부 재결정.
4. 가치 축은 **T1(인앱, 정확) + T3(루트, 시스템 전역 정확)** 유지. T2-OS는 근사·부분 폴백 이상으로 과투자 금지.

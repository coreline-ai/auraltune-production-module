# Loudness Compensation

> 사람 귀의 주파수 민감도가 음량에 따라 비선형적으로 바뀌는 현상을 보정하는 DSP 기능.

## 구현 상태

| 영역 | 현재 상태 | 근거 |
|---|---|---|
| Native DSP | 구현됨 | `audio-engine/src/main/cpp/core/loudness/` |
| Kotlin API | 구현됨 | `AudioEngine.setLoudnessCompensationEnabled`, `setLoudnessCompensationVolume`, `setLoudnessEqEnabled`, `updateLoudnessEqSettings` |
| 엔진 cascade | 구현됨 | `manual → autoEqPreamp → autoEq → loudnessComp → loudnessEq → softLimiter` |
| 기준 알고리즘 | 구현됨 | ISO 226:2023 equal-loudness compensation, ITU-R BS.1770 K-weighted auto-leveler |
| 앱 UI 노출 | 현재 릴리스 후보의 주 UX 범위 밖 | `app/src/main`에서 loudness API를 직접 호출하는 설정 UI 없음 |
| 검증 | 구현/참조 테스트 존재 | `Iso226ReferenceTest`, `Bs1770AutoLevelerReferenceTest`, `LoudnessCompensatorTest`, `RangeValidationTest` |

이 문서는 더 이상 "미구현 기능 제안서"가 아니다. 현재 기준으로는 **엔진 기능은 구현되어 있고, 제품 UI 노출과 사용자 설정 UX가 후속 범위**다.

---

## 1. 무엇이고 왜 필요한가

사람 귀는 모든 주파수를 똑같이 듣지 않는다. 작은 볼륨에서는 저음과 초고음이 상대적으로 약하게 들리고, 큰 볼륨에서는 대역 간 균형이 더 평탄하게 느껴진다. AuralTune의 loudness compensation은 이 차이를 보정하기 위해 재생 볼륨 또는 지각 loudness 기준으로 보정량을 계산한다.

```text
재생 볼륨 80 phon 근처 → 보정 거의 없음
재생 볼륨이 낮아짐     → 저역/고역 보정 증가
```

AutoEQ가 헤드폰의 물리적 주파수 응답을 정적으로 보정한다면, loudness compensation은 사용자 볼륨과 입력 loudness에 따라 청각 균형을 동적으로 보존한다.

---

## 2. 엔진 구성

| 구성요소 | 역할 |
|---|---|
| `IsoContours` | ISO 226:2023 equal-loudness contour 상수와 보정 gain 계산 |
| `LoudnessCompensator` | ISO 226 보정 곡선을 4-section RBJ cascade로 근사 |
| `KWeightingFilter` | ITU-R BS.1770 loudness 측정용 K-weighting 필터 |
| `GainComputer` | target loudness와 현재 loudness 차이를 gain으로 변환 |
| `GainSmoother` | attack/release smoothing으로 펌핑 억제 |
| `LoudnessEqualizer` | BS.1770 기반 auto-leveler 처리 |
| `LoudnessEqualizerSettings` | target, boost/cut cap, compressor, attack/release 설정 |

엔진 처리 순서는 다음과 같다.

```text
Manual EQ
→ AutoEQ preamp
→ AutoEQ
→ ISO 226 Loudness Compensation
→ BS.1770 Loudness Equalizer
→ Soft Limiter
→ NaN guard
```

두 loudness stage는 기본적으로 host/API에서 명시적으로 켜야 한다. 현재 앱 릴리스 후보의 주요 UX는 AutoEQ/OPRA/Graphic/Parametric EQ이며, loudness 설정 UI는 별도 제품 결정이 필요하다.

---

## 3. AutoEQ와의 차이

| 항목 | AutoEQ | Loudness Compensation |
|---|---|---|
| 목적 | 헤드폰의 물리적 응답 보정 | 볼륨별 청각 민감도 보정 |
| 입력 | 헤드폰 모델/측정 프로파일 | 시스템 볼륨 또는 입력 loudness |
| 동작 | 정적 프로파일 | 동적 보정 |
| 데이터 | AutoEQ catalog/profile | ISO 226 표준 테이블, BS.1770 측정 |
| UI 상태 | 현재 앱 핵심 UX | 엔진 구현됨, 앱 노출은 후속 |
| 같이 사용 | 가능 | 가능 |

---

## 4. 검증 기준

| 검증 | 상태 | 위치 |
|---|---|---|
| ISO 226 reference parity | 구현 | `audio-engine/src/test/java/com/coreline/audio/loudness/Iso226ReferenceTest.kt` |
| BS.1770 auto-leveler reference | 구현 | `audio-engine/src/test/java/com/coreline/audio/loudness/Bs1770AutoLevelerReferenceTest.kt` |
| Native loudness compensator | 구현 | `audio-engine/src/test/cpp/LoudnessCompensatorTest.cpp` |
| Engine integration/range guard | 구현 | `audio-engine/src/test/cpp/RangeValidationTest.cpp` |
| 앱 UX 회귀 | 후속 | loudness UI가 제품 범위에 들어올 때 별도 추가 |

릴리스 후보의 현재 핵심 검증은 AutoEQ/OPRA 적용, 플레이어 경로, 로컬 release 빌드, 16KB alignment, debug marker scan이다. Loudness DSP는 엔진 기능으로 유지하되, 앱 사용자 기능으로 노출하려면 별도 UX와 설정 persistence, on/off 상태 표시, 청감 QA가 필요하다.

---

## 5. 제품화 시 남은 작업

| 항목 | 필요 조치 |
|---|---|
| UX 결정 | Auto-leveler와 ISO 226 compensation을 한 기능으로 노출할지, 별도 토글로 나눌지 결정 |
| 설정 저장 | target loudness, max boost/cut, attack/release, compensation enable 상태를 DataStore에 저장 |
| Player 연동 | 시스템 볼륨 변화 또는 플레이어 loudness 측정값을 엔진 API에 전달 |
| 접근성/i18n | 기능 설명, 토글, 위험 안내 문자열 리소스화 |
| 청감 QA | 작은 볼륨/큰 볼륨, 다양한 장르, AutoEQ/OPRA 병행 시 artifact 확인 |

---

## 참고 표준 / 자료

- **ISO 226:2023** — Acoustics — Normal equal-loudness-level contours
- **ITU-R BS.1770-5** — Algorithms to measure audio programme loudness and true-peak audio level
- **ITU-R BS.1771** — Requirements for loudness and true-peak indicating meters
- **EBU R 128** — Loudness normalization and permitted maximum level of audio signals
- **AES TD1004** — Recommendations for Loudness of Internet Audio Streaming

---

## 한 줄 요약

> AuralTune의 loudness compensation은 **엔진/DSP 레벨에서는 구현 완료**이고, 현재 릴리스 후보에서는 **사용자 노출 UI와 제품 설정 UX가 후속 범위**다.

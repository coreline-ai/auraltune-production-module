# Loudness Compensation

> 사람 귀의 주파수 민감도가 음량에 따라 비선형적으로 바뀌는 현상을 보정하는 기능.
> AutoEQ와는 완전히 다른 목적의 별도 기능이라 [dev-plan](../dev-plan/implement_20260507_223901.md)에서 의도적으로 제외했다.

---

## 1. 무엇이고 왜 필요한가

### 1-1. 사람 귀의 비밀 — Fletcher-Munson curves

사람 귀는 **모든 주파수를 똑같이 듣지 않는다.** 1933년 Fletcher와 Munson이 처음 측정한 이래로, 현대 표준(ISO 226:2023)이 이를 정량화했다.

```
음량이 작을수록 → 저음(20-200 Hz)과 초고음(10 kHz+)이 약하게 들림
음량이 클수록   → 주파수 대역 간 균형이 자연스럽게 평탄해짐
```

**예시:** 콘서트장(100 dB SPL)에서 들은 음악을 작은 볼륨(40 dB SPL, 도서관 수준)으로 재생하면:

- 저음(베이스, 드럼 킥)이 **사라진 듯** 들림
- 고음(심벌, 보컬 시빌런스)도 약해짐
- 중역(보컬, 기타)만 또렷이 들리는 "얇은" 소리가 됨

**원인:** 청각 임계값이 주파수마다 다르다. 스튜디오 엔지니어는 대개 75-85 dB SPL로 믹싱하는데, 사용자가 작은 볼륨으로 재생하면 그 균형이 깨진다.

### 1-2. Loudness Compensation이 하는 일

**재생 볼륨이 작아질수록 저음/고음을 자동으로 부스트해서 청각상의 음색 균형을 유지**한다.

```
재생 볼륨 80 dB → 부스트 0 dB (원음 그대로)
재생 볼륨 60 dB → 저음 +6 dB, 고음 +2 dB (도서관 볼륨 보정)
재생 볼륨 40 dB → 저음 +12 dB, 고음 +4 dB (속삭이는 볼륨 보정)
```

옛 Hi-Fi 앰프의 "Loudness" 버튼이 정확히 이것이다. 다만 옛 버튼은 고정 곡선이었고, 현대 구현은 입력 신호의 실시간 음량을 측정해서 **동적으로** 보정한다.

---

## 2. AuralTune에서의 구현 방향

Loudness compensation을 추가할 경우 AuralTune Android 모듈에는 다음 구성요소가 필요하다.

| 구성요소 | 역할 |
|---|---|
| K-weighting filter | ITU-R BS.1770 K-weighting — 사람 귀가 인지하는 음량을 측정하기 위한 사전 필터 (저역 컷 + 고역 보정 shelf) |
| Loudness detector | K-weighted RMS로 현재 입력 LUFS 측정 |
| ISO 226 contours | ISO 226:2023 equal-loudness contour — 29개 1/3 옥타브 주파수에 대한 αf, Lu, Tf 계수 테이블 |
| Gain computer | 측정된 LUFS와 target LUFS의 차이로 각 주파수 대역 보정량 계산 |
| Gain smoother | Asymmetric attack/release — 음량 변화 시 EQ가 펌핑하지 않도록 부드럽게 |
| Loudness EQ math | 위 결과를 biquad shelf로 변환 |
| Loudness settings | 설정 (target LUFS, attack/release 시간 등) |
| Loudness processor | 메인 처리 클래스 |
| Loudness controller | UI/설정과 오디오 처리 계층을 연결하는 컨트롤러 |

### 2-1. 핵심 알고리즘

```
1. 입력 신호 → K-weighting 필터 → 인지 음량(LUFS) 측정
2. target 음량(예: -23 LUFS)과의 차이 계산
3. ISO 226 contour를 사용해 주파수별 보정량 계산
4. low-shelf + high-shelf biquad로 적용
5. attack/release smoothing으로 펌핑 방지
```

### 2-2. ISO 226:2023 equal-loudness contour 발췌

```text
// 1/3 옥타브 frequencies, 20 Hz ~ 12.5 kHz
[20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400,
 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000,
 6300, 8000, 10000, 12500]

// 각 주파수의 loudness perception exponent αf
[0.635, 0.602, 0.569, 0.537, ..., 0.354]
```

이 테이블 + 사용자 현재 음량 → 주파수별 보정 dB 양 계산.

---

## 3. AutoEQ와의 차이

| | **AutoEQ (headphone correction)** | **Loudness compensation** |
|---|---|---|
| 목적 | 헤드폰의 **물리적 주파수 응답 왜곡** 보정 | 사람 귀의 **음량별 비선형 민감도** 보정 |
| 기준 | 측정자(oratory1990 등)의 헤드폰 측정값 | ISO 226:2023 equal-loudness contours |
| 입력 | 헤드폰 모델 ID | 실시간 재생 음량 측정 |
| 동작 | 정적 (한 번 적용 후 고정) | 동적 (음량 변화에 따라 실시간) |
| 필요 데이터 | AutoEQ GitHub 카탈로그 | ISO 표준 테이블 (앱 내장) |
| 음원 의존성 | 헤드폰만 | 마스터링된 reference level (-14~-23 LUFS) |
| 같이 쓸 수 있나 | **YES** — 직렬 cascade로 둘 다 적용 가능 | |

**둘은 완전히 직교 (orthogonal)** 한 기능이다. AutoEQ가 "이 헤드폰의 결함을 펴는 것"이라면, Loudness compensation은 "사용자 볼륨에 따라 청각 균형을 보존하는 것"이다.

추가할 경우 AuralTune에서는 두 기능을 **별도 chain**으로 직렬 적용할 수 있다:

```
[Manual EQ] → [AutoEQ] → [Loudness Compensation] → [Limiter]
```

---

## 4. 왜 Android dev-plan에서 제외됐나

`dev-plan`은 **AutoEQ headphone correction**으로 명시 범위를 한정했기 때문이다 ([개발 목적/제외 범위](../dev-plan/implement_20260507_223901.md#개발-범위) 참조). 이유:

1. **별도 기능, 별도 복잡성**
   - ISO 226 테이블, K-weighting 필터, gain smoother 등 추가 8개 클래스가 필요
2. **다른 production gate 필요**
   - AutoEQ는 정적이라 frequency response golden test로 검증되지만, loudness comp는 시간영역 펌핑/release 행동을 검증해야 함
3. **MVP 우선순위**
   - AutoEQ 6,000개 헤드폰 카탈로그는 그 자체로 사용자에게 큰 가치
   - loudness comp는 "있으면 좋은" 보조 기능

---

## 5. Android에 추가한다면 — 작업량 견적

| 작업 | 예상 LOC | 비고 |
|---|---:|---|
| `IsoContoursTable.kt` (29 freq × 3 coeff) | ~200 | ISO 226 표준 테이블 기반 |
| Native: `KWeightingFilter` (TDF2 biquad 2단) | ~100 | C++ |
| Native: `LoudnessDetector` (RMS + LUFS 변환) | ~150 | RT-safe 통계 |
| Native: `GainComputer` + `GainSmoother` | ~250 | attack/release |
| Native: `LoudnessShelf` (low+high shelf 동적 적용) | ~150 | 기존 `BiquadFilter` 재사용 |
| JNI 확장 + 새 chain stage | ~100 | `EngineSnapshot`에 loudness 섹션 추가 |
| Kotlin: target LUFS 설정 UI + 토글 | ~200 | DataStore + Compose |
| 테스트: pumping behavior + ISO 226 reference | ~300 | scipy 기반 reference 가능 |

**총 ~1,450 LOC, 별도 dev-plan으로 1-2주 작업 분량.**

---

## 6. 추가 시 검증해야 할 production gate

AutoEQ가 frequency response golden test로 검증되는 것과 별도로, loudness comp는 다음을 검증해야 한다:

1. **ISO 226 reference parity** — 측정된 contour를 ISO 226:2023 표준 테이블과 ±0.5 dB 이내로 비교
2. **K-weighting filter 응답** — ITU-R BS.1770 specification (low-pass + high-shelf)과 ±0.1 dB 이내로 비교
3. **Pumping/breathing 부재** — 1 kHz tone 입력 음량을 -40 dB → 0 dB로 step 변경 시 출력에 audible artifact 없음
4. **Attack/release 시정수** — step input에 대해 90% 도달 시간이 설정값(예: attack 50ms / release 200ms)과 ±10% 이내
5. **음량 범위 안정성** — -60 dBFS ~ 0 dBFS 입력 전 범위에서 NaN/Inf/비정상 출력 없음
6. **AutoEQ와 함께 사용 시 cascade 안정성** — AutoEQ + loudness comp + limiter 직렬에서 click/pop 0회

---

## 7. 참고 표준 / 자료

- **ISO 226:2023** — Acoustics — Normal equal-loudness-level contours
- **ITU-R BS.1770-5** — Algorithms to measure audio programme loudness and true-peak audio level (K-weighting filter spec)
- **ITU-R BS.1771** — Requirements for loudness and true-peak indicating meters
- **EBU R 128** — Loudness normalization and permitted maximum level of audio signals (방송용 LUFS target = -23 LUFS)
- **AES TD1004** — Recommendations for Loudness of Internet Audio Streaming
- Fletcher, H. and Munson, W.A. (1933) "Loudness, its definition, measurement and calculation" — JASA 5, 82-108

---

## 한 줄 요약

> **AutoEQ가 "헤드폰을 평탄하게 만드는 정적 보정"이라면, Loudness compensation은 "재생 볼륨에 따라 사람 귀의 비선형 응답을 동적으로 메우는 보정"이다.** 두 기능은 직교하며, 함께 cascade로 적용할 수 있다. AuralTune Android에서는 dev-plan 범위에서 제외했으며, 추가 시 ~1,450 LOC + 별도 production gate가 필요하다.

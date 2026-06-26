# OPRA 상업 출시 고지 초안

작성일: `2026-06-26`

이 문서는 AuralTune 상업 출시 전 OPRA 데이터 사용 고지를 EULA, OSS notice, 앱스토어 설명, 법무 검토 체크리스트로 옮기기 위한 초안이다. 법률 자문이 아니며, 최종 문구는 출시 전 법무 검토를 거쳐 확정한다.

## 적용 기준

| 항목 | 현재 기준 |
|---|---|
| 데이터 출처 | OPRA, Open Profiles for Revealing Audio |
| 데이터 라이선스 | Creative Commons Attribution-ShareAlike 4.0 International, CC BY-SA 4.0 |
| 앱 코드와 OPRA 관계 | AuralTune은 OPRA 또는 Roon Labs와 제휴, 후원, 인증 관계가 아님 |
| release 데이터 운영 | GitHub raw/API 또는 Roon Labs mirror를 기본 데이터 서버로 직접 fetch하지 않음 |
| release 데이터 공급 | 앱에 포함된 bundled sha256 snapshot 또는 AuralTune 관리 mirror/cache만 사용 |
| 앱 내 고지 | About 카드, OPRA 탭 footer, OPRA profile 상세에 표시 |

## EULA / 약관 삽입 초안

```text
AuralTune includes headphone equalization profile data derived from OPRA
(Open Profiles for Revealing Audio). OPRA data is licensed under the
Creative Commons Attribution-ShareAlike 4.0 International License
(CC BY-SA 4.0).

OPRA data remains available under CC BY-SA 4.0. Nothing in these terms is
intended to restrict any rights granted to you under that license. If you
copy, share, modify, or redistribute OPRA-derived data, you are responsible
for complying with the attribution and share-alike requirements of CC BY-SA 4.0.

AuralTune is not affiliated with, endorsed by, sponsored by, or certified by
OPRA, Roon Labs, or any headphone manufacturer referenced in the profile data.
Headphone names and measurement metadata are shown only to identify compatible
profiles and their source.
```

## OSS / Attribution Notice 초안

```text
OPRA (Open Profiles for Revealing Audio)
Data license: Creative Commons Attribution-ShareAlike 4.0 International
License: https://creativecommons.org/licenses/by-sa/4.0/
Source: https://github.com/opra-project/OPRA

AuralTune uses OPRA-derived headphone EQ profile data for local playback
equalization. The data may be format-converted for app playback, while the
original source values and attribution metadata are preserved where available.
AuralTune is not affiliated with or endorsed by OPRA or Roon Labs.
```

## 앱스토어 설명 초안

```text
AuralTune can apply headphone correction profiles from AutoEQ and OPRA.
OPRA profile data is provided under CC BY-SA 4.0 with attribution inside the
app. AuralTune is an independent app and is not affiliated with or endorsed by
OPRA, Roon Labs, AutoEQ, or headphone manufacturers.
```

## 한국어 사용자 고지 초안

```text
AuralTune은 OPRA(Open Profiles for Revealing Audio)에서 파생된 헤드폰 EQ
프로파일 데이터를 포함합니다. OPRA 데이터는 CC BY-SA 4.0 라이선스에 따라
제공되며, 앱 내 정보 화면과 OPRA 상세 화면에서 출처와 라이선스를 확인할 수
있습니다.

AuralTune은 OPRA, Roon Labs 또는 각 헤드폰 제조사의 공식 앱이 아니며,
제휴·후원·인증 관계가 없습니다.
```

## 출시 패키지 배치 매트릭스

| 위치 | 포함 문구 | 상태 | 출시 전 확인 |
|---|---|---:|---|
| 앱 About / 정보 카드 | OPRA 출처, CC BY-SA 4.0, source link, snapshot commit, no endorsement, 권리 비제한 | 구현됨 | release APK에서 표시 확인 |
| OPRA 탭 footer | OPRA data, CC BY-SA 4.0, Roon Labs / OPRA 비제휴 | 구현됨 | OPRA 탭 하단 노출 확인 |
| OPRA profile 상세 | author/source/license, format-converted notice, no endorsement | 구현됨 | 임의 OPRA 프로파일 상세에서 확인 |
| EULA / 이용약관 | OPRA-derived data, CC BY-SA 4.0 권리 비제한, share-alike 안내, no endorsement | 초안 준비 | 법무 반영/승인 필요 |
| OSS / Third-party notice | OPRA source URL, license URL, data license, non-affiliation | 초안 준비 | 앱 내 고지와 외부 notice의 문구 불일치 확인 |
| 앱스토어 설명 | AutoEQ/OPRA 데이터 출처, CC BY-SA attribution, 독립 앱/비공식 고지 | 초안 준비 | 플랫폼별 글자 수/정책 검토 |
| 개인정보 / 데이터 세이프티 | OPRA 데이터는 앱 번들 snapshot 또는 AuralTune mirror/cache, release 기본 GitHub/Roon 직접 fetch 없음 | 구현 기준 확인 | 스토어 data safety 답변과 네트워크 동작 일치 확인 |

## 스토어 등록 체크리스트

| 항목 | PASS 기준 |
|---|---|
| 데이터 출처 | AutoEQ와 OPRA를 별도 출처로 표기하고, OPRA 약칭을 `Open Profiles for Revealing Audio`로 표기 |
| 라이선스 | `CC BY-SA 4.0`와 license URL을 앱 설명 또는 고지 링크에서 확인 가능 |
| 상업 사용 오해 방지 | 유료 앱/인앱결제 여부와 무관하게 OPRA 데이터 자체의 CC BY-SA 권리를 제한하지 않는다고 명시 |
| 비공식/비제휴 | OPRA, Roon Labs, AutoEQ, 헤드폰 제조사와 제휴/인증/후원 관계가 아니라고 명시 |
| 추가 제한 금지 | EULA/DRM/스토어 약관이 OPRA-derived data의 복사/공유/재배포 권리를 제한하는 표현을 넣지 않음 |
| 데이터 운영 | release 앱이 GitHub raw/API 또는 Roon Labs mirror를 기본 데이터 서버로 직접 사용하지 않음 |
| 변경 표시 | EQ 값은 원본 값 유지, 앱 재생을 위한 format conversion만 수행한다고 표시 |

## 금지 표현

| 피해야 할 문구 | 이유 | 대체 문구 |
|---|---|---|
| `OPRA 공식 앱` | no endorsement 위반 소지 | `OPRA 데이터를 사용하는 독립 앱` |
| `Roon Labs 인증` | 인증/제휴 오해 | `Roon Labs와 제휴 또는 인증 관계가 없음` |
| `AuralTune 전용 OPRA 데이터` | CC BY-SA 권리 제한 오해 | `OPRA-derived data remains available under CC BY-SA 4.0` |
| `데이터 재배포 금지` | additional restrictions 금지와 충돌 가능 | `OPRA 데이터 재사용은 CC BY-SA 4.0 조건을 따름` |
| `헤드폰 제조사 보증 튜닝` | 제조사 보증/공식 인증 오해 | `측정 기반 보정 프로파일` |

## 법무 검토 체크리스트

| 항목 | 상태 | 확인 포인트 |
|---|---:|---|
| Attribution | 준비됨 | OPRA 이름, source URL, license URL, snapshot commit 표시 |
| Share-alike | 준비됨 | OPRA-derived data 권리가 앱 약관에 의해 제한되지 않는다는 문구 포함 |
| No endorsement | 준비됨 | OPRA/Roon Labs/제조사와 비제휴 문구 포함 |
| Additional restriction 금지 | 준비됨 | 앱 약관이 CC BY-SA 권리를 제한하지 않는다는 문구 포함 |
| DRM / 앱스토어 약관 | 법무 검토 필요 | 스토어 DRM과 CC BY-SA 재배포 권리의 충돌 여부 |
| 데이터베이스권 / 관할권 | 법무 검토 필요 | 국가별 database right 적용 가능성 |
| 상업 배포 데이터 운영 | 구현됨 | release는 bundled sha256 snapshot 사용, debug만 GitHub raw 허용 |
| OSS notice 위치 | 구현됨 | 앱 내 About 카드. 별도 웹/스토어 OSS notice 병행 여부 결정 |

## 출시 전 확인 명령

```powershell
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest
.\gradlew.bat --no-daemon --no-build-cache --max-workers=2 "-Dorg.gradle.parallel=false" "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -XX:+HeapDumpOnOutOfMemoryError" "-Dkotlin.compiler.execution.strategy=in-process" :app:assembleRelease
```

release APK에서 OPRA GitHub raw/API fetch 경로가 기본 source로 쓰이지 않는지는 `OpraSourcePolicyTest`와 release debug-marker scan으로 함께 확인한다.

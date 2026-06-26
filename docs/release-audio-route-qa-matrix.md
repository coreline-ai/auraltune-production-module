# Release Audio Route QA Matrix

작성일: `2026-06-26`

이 문서는 AuralTune release 후보의 오디오 출력 라우트별 AutoEQ/OPRA 적용 정책을 수동 검증하기 위한 QA 매트릭스다. 자동 테스트는 route 분류와 engine clear/apply 정책을 보조하지만, 실제 Android 기기 라우팅은 제조사/OS/연결 장치 영향을 받으므로 출시 전 수동 증적을 남긴다.

## 현재 검증 경계

| 범위 | 상태 | 증적 |
|---|---|---|
| 앱 실행/탭 진입/회전 복원 | 자동 계측 PASS | `:app:connectedDebugAndroidTest`, `SM-S931N - 16`, 13 tests |
| route policy 코드 | 단위 테스트 PASS | `DeviceKeyTest`: headphone/Bluetooth/USB 지원, speaker/HDMI/line/telephony 미지원 clear-sentinel 정책 |
| Bluetooth 시스템 라우트 관측 | 부분 증적 확보 | `adb shell dumpsys audio`: `STREAM_MUSIC` device `bt_a2dp(80)`, `mBluetoothName=Soundcore Life Q30`, connected `type:0x80 (bt_a2dp)` |
| Bluetooth 라우트에서 앱 foreground | 부분 증적 확보 | `dumpsys activity top`: `com.coreline.auraltune/.MainActivity`, `mResumed=true`; `uiautomator dump`: `AuralTune`, `플레이어`, `AutoEQ`, `OPRA`, `블루투스 기기 식별 권한` |
| 라우트별 AutoEQ/OPRA 실제 apply/clear UX | 수동 필요 | 물리 장치 연결 후 화면/로그/`dumpsys audio` 기록 필요 |

## 현재 정책

| 라우트 | supportsAutoEq | 기대 동작 |
|---|---:|---|
| Wired headphones / headset | true | 선택된 AutoEQ 또는 OPRA 보정 적용 |
| Bluetooth A2DP headphones | true | 권한 허용 시 주소/식별자 기반, 미허용 시 이름 기반으로 선택/복원 |
| USB headphones / USB DAC | true | 헤드폰/USB 오디오로 식별되면 보정 적용 |
| Built-in speaker | false | 이전 헤드폰 보정 clear, 현재 사용중 배지 해제 |
| HDMI / line / dock / telephony | false | 보정 clear, AutoEQ/OPRA 현재 사용중으로 표시하지 않음 |
| Unknown / unsupported | false by default | 안전하게 보정 clear 또는 미적용 |

## 수동 검증 절차

1. release 또는 release-equivalent debug 빌드를 설치한다.
2. AutoEQ 탭에서 임의의 헤드폰 프로파일을 선택하고 `EQ 적용` 상태를 확인한다.
3. OPRA 탭에서 임의의 프로파일을 선택하고 현재 사용중 배지와 프리앰프/그래프 상태를 확인한다.
4. 각 라우트로 전환한 뒤 다음을 기록한다.
5. 부적격 라우트에서는 엔진 보정이 clear되고, AutoEQ/OPRA가 현재 사용중처럼 보이지 않아야 한다.

## 증적 수집 명령

```powershell
$env:ANDROID_SERIAL='R3CY40PXCAP'
$adb='C:\Users\hwan\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell dumpsys audio > docs\qa-dumpsys-audio-<route>.txt
& $adb exec-out screencap -p > docs\qa-screen-<route>.png
& $adb shell uiautomator dump /sdcard/window.xml
& $adb pull /sdcard/window.xml docs\qa-window-<route>.xml
```

## 라우트별 확인 항목

| 라우트 | 확인 순서 | PASS 기준 |
|---|---|---|
| Wired headphones / headset | 연결 → AutoEQ 선택 → OPRA 선택 → 앱 재시작 | 헤드폰 라우트에서만 현재 사용중 배지와 엔진 apply 상태가 일치 |
| Bluetooth A2DP | 권한 허용/거부 각각 확인 → 이름/주소 기반 식별 확인 | 권한 허용 시 안정 식별, 거부 시 앱 중단 없이 fallback 또는 보정 없음 표시 |
| USB DAC/headset | USB 연결 → 라우트 전환 후 선택 복원 확인 | USB/headphone 계열이면 apply, 제거 시 stale 보정 clear |
| Built-in speaker | 헤드폰 보정 적용 후 모든 외부 출력 해제 | 현재 사용중 배지 해제, engine clear, 스피커에 헤드폰 보정 잔존 없음 |
| HDMI / dock / line | dock/HDMI 연결 → 보정 탭 진입 | 부적격 출력으로 표시하고 AutoEQ/OPRA를 현재 사용중으로 표시하지 않음 |

## 검증 매트릭스

| 날짜 | 기기 / OS | 라우트 | 연결 장치 | AutoEQ 선택 복원 | OPRA 선택 복원 | active provider 표시 | 엔진 clear/apply | 결과 | 증적 |
|---|---|---|---|---|---|---|---|---|---|
| 2026-06-26 | SM-S931N / Android 16 | 자동 계측 스모크 | 현재 시스템 라우트 의존 | 미검증 | 미검증 | 앱 실행/탭 진입 | 미검증 | PASS: 앱 실행/회전/탭 진입 | `app/build/outputs/androidTest-results/connected/debug/TEST-SM-S931N - 16-_app-.xml` |
| 2026-06-26 | SM-S931N / Android 16 / SDK 36 | Bluetooth A2DP | Soundcore Life Q30 | 수동 필요 | 수동 필요 | 앱 foreground 및 탭 표시 확인 | 수동 필요 | PARTIAL: 시스템 라우트 + AuralTune foreground + Bluetooth 권한 안내 UI 확인. 프로파일 apply/clear는 미수행 | `dumpsys audio`: `STREAM_MUSIC` → `bt_a2dp(80)`, `mBluetoothName=Soundcore Life Q30`, `type:0x80 (bt_a2dp)`; `dumpsys activity top`: `mResumed=true`; `uiautomator dump`: `AuralTune`, `블루투스 기기 식별 권한` |
|  |  | Wired headphones | 물리 연결 필요 | 수동 필요 | 수동 필요 | 수동 필요 | 수동 필요 | NEEDS_DEVICE |  |
|  |  | USB DAC/headset | 물리 연결 필요 | 수동 필요 | 수동 필요 | 수동 필요 | 수동 필요 | NEEDS_DEVICE |  |
|  |  | Built-in speaker | 스피커 출력 강제 필요 | 수동 필요 | 수동 필요 | 현재 사용중 해제 확인 필요 | clear 확인 필요 | NEEDS_MANUAL_QA |  |
|  |  | HDMI / dock / line | 물리 연결 필요 | 해당 없음 | 해당 없음 | 현재 사용중 해제 확인 필요 | clear 확인 필요 | NEEDS_DEVICE |  |

## PASS 기준

| 항목 | 기준 |
|---|---|
| 적격 헤드폰 라우트 | 검색/선택된 프로파일이 적용되고 현재 사용중 배지가 실제 provider와 일치 |
| 부적격 라우트 | 이전 헤드폰 보정이 남지 않고 engine clear 상태로 전환 |
| 권한 미허용 Bluetooth | 앱이 중단되지 않고 이름 기반 fallback 또는 보정 없음 상태를 명확히 표시 |
| 탭 전환 | AutoEQ/OPRA 선택값은 유지하되 실제 active provider만 현재 사용중으로 표시 |
| 재시작 복원 | 저장된 선택은 복원되지만 부적격 라우트에서는 적용 중으로 표시하지 않음 |

## 권장 증적

- `adb shell dumpsys audio` 라우트 일부
- 앱 화면 스크린샷 또는 `uiautomator dump`
- 로그가 필요한 경우 debug 빌드에서만 수집
- 검증 APK commit hash와 빌드 variant

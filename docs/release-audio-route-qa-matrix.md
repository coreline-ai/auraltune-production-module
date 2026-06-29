# Release Audio Route QA Matrix

작성일: `2026-06-26` · 정책 개정: `2026-06-29`

이 문서는 AuralTune release 후보의 오디오 출력 라우트별 보정 적용을 수동 검증하기 위한 QA 매트릭스다.

## 정책 (2026-06-29 개정)

**보정은 출력 라우트와 무관하게 항상 적용된다.** 라우트 타입은 더 이상 게이트가 아니다. 사용자가 자신의
헤드폰을 선택한 프로파일에 맞추는 것은 사용자의 몫이고, 엔진은 어떤 출력(유선/블루투스/USB/스피커/HDMI/라인/
기타)에서든 활성 프로파일대로 출력한다. `DeviceKey`는 라우트를 식별만 하며(per-device 선택 자동 복원용),
보정을 끄지 않는다. 보정이 비는 경우는 "선택된 프로파일이 아예 없을 때"뿐이며, 라우트 전환 때문에 비는 일은 없다.

| 라우트 | 기대 동작 |
|---|---|
| Wired headphones / headset | 선택된 AutoEQ 또는 OPRA 보정 적용 |
| Bluetooth A2DP / BLE | 권한 허용 시 주소/식별자 기반, 미허용 시 이름 기반으로 선택/복원 (보정은 동일하게 적용) |
| USB headphones / USB DAC | 보정 적용 |
| Built-in speaker | 보정 적용 (라우트 전환 시 해제되지 않음) |
| HDMI / dock / line / telephony | 보정 적용 (라우트 전환 시 해제되지 않음) |
| Unknown / 기타 출력 | 보정 적용 (`other|…` 키로 식별, 적격) |

## 현재 검증 경계

| 범위 | 상태 | 증적 |
|---|---|---|
| 앱 실행/탭 진입/회전 복원 | 자동 계측 PASS | `:app:connectedDebugAndroidTest`, `SM-S931N - 16`, 13 tests |
| route 키 매핑 코드 | 단위 테스트 PASS | `DeviceKeyTest`: 모든 출력 라우트(헤드폰/BT/USB/스피커/HDMI/line/기타)가 키로 매핑되고 null을 반환하지 않음 |
| Bluetooth 시스템 라우트 관측 | 부분 증적 확보 | `adb shell dumpsys audio`: `STREAM_MUSIC` device `bt_a2dp(80)`, `mBluetoothName=Soundcore Life Q30`, connected `type:0x80 (bt_a2dp)` |
| 라우트별 보정 apply 유지 UX | 수동 필요 | 물리 장치 연결 후 화면/로그/`dumpsys audio` 기록 필요 |

## 수동 검증 절차

1. release 또는 release-equivalent debug 빌드를 설치한다.
2. AutoEQ 탭에서 임의의 헤드폰 프로파일을 선택하고 `EQ 적용` 상태를 확인한다.
3. OPRA 탭에서 임의의 프로파일을 선택하고 현재 사용중 배지와 프리앰프/그래프 상태를 확인한다.
4. 각 라우트로 전환한 뒤, 보정이 **계속 적용된 상태로 유지**되는지 확인한다(라우트 변경으로 인한 해제가 없어야 함).
5. 선택된 프로파일을 명시적으로 해제했을 때에만 보정이 비는지 확인한다.

## 증적 수집 명령

```powershell
$env:ANDROID_SERIAL='R3CY40PXCAP'
$adb='C:\Users\hwan\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell dumpsys audio > docs\qa-dumpsys-audio-<route>.txt
& $adb exec-out screencap -p > docs\qa-screen-<route>.png
& $adb shell uiautomator dump /sdcard/window.xml
& $adb pull /sdcard/window.xml docs\qa-window-<route>.xml
```

## 검증 매트릭스

| 날짜 | 기기 / OS | 라우트 | 연결 장치 | 보정 유지(apply) | active provider 표시 | 결과 | 증적 |
|---|---|---|---|---|---|---|---|
| 2026-06-26 | SM-S931N / Android 16 | 자동 계측 스모크 | 현재 시스템 라우트 의존 | 미검증 | 앱 실행/탭 진입 | PASS: 앱 실행/회전/탭 진입 | `app/build/outputs/androidTest-results/connected/debug/TEST-SM-S931N - 16-_app-.xml` |
| 2026-06-26 | SM-S931N / Android 16 / SDK 36 | Bluetooth A2DP | Soundcore Life Q30 | 수동 필요 | 앱 foreground 및 탭 표시 확인 | PARTIAL: 시스템 라우트 + AuralTune foreground 확인. 프로파일 apply는 미수행 | `dumpsys audio`: `STREAM_MUSIC` → `bt_a2dp(80)`, `mBluetoothName=Soundcore Life Q30` |
|  |  | Wired headphones | 물리 연결 필요 | 수동 필요 | 수동 필요 | NEEDS_DEVICE |  |
|  |  | USB DAC/headset | 물리 연결 필요 | 수동 필요 | 수동 필요 | NEEDS_DEVICE |  |
|  |  | Built-in speaker | 스피커 출력 강제 필요 | 보정 유지 확인 필요 | 현재 사용중 유지 확인 | NEEDS_MANUAL_QA |  |
|  |  | HDMI / dock / line | 물리 연결 필요 | 보정 유지 확인 필요 | 현재 사용중 유지 확인 | NEEDS_DEVICE |  |

## PASS 기준

| 항목 | 기준 |
|---|---|
| 모든 라우트 | 선택된 프로파일이 적용되고, 라우트 전환에도 보정이 유지된다 |
| active provider 표시 | 현재 사용중 배지가 실제 provider(AutoEQ/OPRA)와 일치 |
| 권한 미허용 Bluetooth | 앱이 중단되지 않고 이름 기반 fallback으로 식별, 보정은 동일하게 적용 |
| 탭 전환 | AutoEQ/OPRA 선택값은 유지하되 실제 active provider만 현재 사용중으로 표시 |
| 재시작 복원 | 저장된 선택이 복원되고 라우트와 무관하게 적용 상태가 유지됨 |
| 명시적 해제 | 사용자가 선택을 해제하면(그리고 그때만) 엔진 보정이 비워짐 |

## 권장 증적

- `adb shell dumpsys audio` 라우트 일부
- 앱 화면 스크린샷 또는 `uiautomator dump`
- 로그가 필요한 경우 debug 빌드에서만 수집
- 검증 APK commit hash와 빌드 variant

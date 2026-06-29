# AuralTune — 디자인 핸드오프 사양서

## 2026-06-26 구현 현행화 메모

| 항목 | 현재 구현 기준 |
| --- | --- |
| 런처 아이콘 | Adaptive icon XML은 `@drawable/ic_launcher_background`, `foreground`, `monochrome`를 참조하고, density PNG fallback은 mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192 규격으로 확인했다. |
| 플레이어 오류 UX | 재생 실패 시 실패한 트랙을 큐에서 제거하고 snackbar로 파일명과 Media3 error code를 표시한다. |
| 플레이어 포맷 표시 | 그래프 상단 포맷 표시는 AudioProcessor configure 이벤트에서 bit depth/sample rate를 갱신한다. PCM 16/24/32-bit integer 및 float 입력을 처리한다. |
| AutoEQ/OPRA 적용 상태 | 카드 선택값과 현재 적용 provider를 분리한다. 부적격 출력 장치에서는 OPRA/AutoEQ가 현재 사용 중으로 표시되지 않으며 엔진 보정은 clear된다. |
| 접근성 | 하단 내비, 플레이어 transport, 검색 clear, 프로파일 picker, 그래픽/파라메트릭 EQ 주요 버튼에 contentDescription을 둔다. 파라메트릭 밴드 추가 아이콘도 설명을 가진다. |
| i18n | 주요 플레이어/검색/상태/진단/캐시 문자열은 한국어 리소스로 정리했다. OPRA 라이선스 문구는 법적 고지 성격상 일부 영문 고유명과 라이선스 명칭을 유지한다. |

> **이 문서 하나로 UI 전체를 재현할 수 있도록** 작성된 디자인 사양서입니다.
> 1부는 **현재 구현 사양**(코드 전수 감사 기반, redline 포함), 2부는 **개선 제안 / 열린 질문**입니다.
> 모든 사양은 소스 코드(`c803fde`, main)와 2026-06-26 현재 작업트리 패치를 기준으로 직접 추출·검증했습니다.

| 항목 | 값 |
|---|---|
| 앱 이름 | **AuralTune** (`app_name`, launcher label) |
| 버전 | `BuildConfig.VERSION_NAME` = `0.1.0` (versionCode 1) — About 카드에 런타임 주입 |
| 플랫폼 | Android, **단일 Activity**(`MainActivity`) + Jetpack Compose, Material 3 |
| 최소 SDK / 타겟 | BLUETOOTH_CONNECT `minSdk 31`, `targetApi 34` |
| 테마 | 다크/라이트 **OS 설정 추종**(`isSystemInDarkTheme`). **Dynamic Color(Material You) 의도적 비활성** |
| 타이포 / 셰이프 | **Material 3 기본값 그대로** (커스텀 폰트·타입스케일·코너 없음 → 정의 시 전부 신규) |
| 화면 모드 | **Edge-to-edge**(상태바/내비바 투명, 콘텐츠가 시스템 바 아래까지 그려짐) |
| RTL | `supportsRtl=true` |
| 백업 | 전면 비활성(allowBackup=false + data_extraction_rules 전 도메인 제외) |
| 네트워크 | HTTPS 전용(cleartext 금지, GitHub raw) |
| 앱 아이콘 | Adaptive icon 적용 완료. XML은 `@drawable/ic_launcher_background`, `foreground`, `monochrome`를 참조하며 density PNG fallback은 mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192 규격. |
| 작성일 | 2026-06-25 |

AuralTune은 **헤드폰 음질 보정(EQ) 플레이어**입니다. 사용자가 헤드폰 모델을 고르면 측정 기반 보정 프로파일(AutoEQ 또는 OPRA)을 자동 적용하고, 20밴드 그래픽 EQ로 직접 조정하며, 원음/보정/내설정 3단 비교(A/B/C)로 즉시 청취 비교합니다. 자체 NDK C++ DSP 엔진이 앱 내 플레이어 소리를 실시간 보정합니다.

---

# 1부 · 현재 사양

## 1. 정보 구조 (Information Architecture)

단일 Activity 안의 `Scaffold` 하나에서 **하단 탭 3개**를 `when(selectedTab)`으로 전환합니다. **NavHost·백스택 없음** — 탭 전환은 백스택에 쌓이지 않고, 시스템 back은 기본(Activity finish) 동작입니다.

```
┌───────────────────────────────────────┐
│  TopAppBar  (제목만, 내비아이콘·액션 없음)  │  ← 탭별 제목 변경
├───────────────────────────────────────┤
│                                       │
│   화면 본문 (LazyColumn, 세로 스크롤)     │
│   · PLAYER → PlayerScreen             │
│   · AUTOEQ → CorrectionScreen(false)  │
│   · OPRA   → CorrectionScreen(true)   │
│                                       │
├───────────────────────────────────────┤
│  [ MiniPlayer ]   ← 탭≠PLAYER & 미디어有  │  ← bottomBar = Column
│  ⌂ 플레이어   ◧ AutoEQ   ◔ OPRA          │     (MiniPlayer + NavigationBar)
└───────────────────────────────────────┘
   (+ OPRA 상세 다이얼로그: 어느 탭이든 위에 전역 오버레이)
```

### 1.1 TopAppBar 사양

- Material3 `TopAppBar`, **제목 Text 1개만** — 내비게이션 아이콘 ❌, 액션 ❌, **검색창 ❌**(검색은 본문에 있음, §4.2/4.3).
- 제목은 `selectedTab`로 결정. `AuralTune · AutoEQ`, `AuralTune · OPRA` 형식은 `app_title_with_source` 리소스로 관리한다.

| 탭 | 제목 |
|----|------|
| PLAYER | `AuralTune` |
| AUTOEQ | `AuralTune · AutoEQ` |
| OPRA | `AuralTune · OPRA` |

### 1.2 하단 내비게이션 (NavigationBar)

`bottomBar`는 `Column`: 위에 (조건부) MiniPlayer, 아래에 항상 `NavigationBar`(아이템 3개, 고정 순서).

| 순서 | 탭 | 아이콘 | 라벨 | 비고 |
|------|----|--------|------|------|
| 1 | PLAYER | `Icons.Default.LibraryMusic` | `플레이어` (`tab_player`) | 홈/기본 |
| 2 | AUTOEQ | `Icons.Default.GraphicEq` | `AutoEQ` (`tab_autoeq`) | |
| 3 | OPRA | `Icons.Default.Tune` | `OPRA` (`tab_opra`) | |

> ✅ 3개 아이콘 모두 탭 라벨과 동일한 `contentDescription`을 가진다.
> ✅ **활성 소스 배지** — 현재 적용 중인 보정 소스 탭(AutoEQ/OPRA) 아이콘에 점 배지(`BadgedBox`+`Badge`). 어느 소스가 실제로 도는지 탭에서 바로 보임(§9.1).

### 1.3 핵심 IA 규칙

1. **미니 플레이어 영속 (중요)** — 재생 세션은 앱 전역 **하나**(`MusicPlayerController`, ViewModel 보유, 회전에도 유지). 플레이어 탭에서 재생 시작 후 다른 탭으로 가면 하단에 MiniPlayer로 계속. **표시 조건 = `selectedTab != PLAYER` AND `playback.hasMedia`**. MiniPlayer 아무 곳이나 탭 → PLAYER 탭으로 펼침.
2. **탭 상태 보존** — 탭별 `LazyListState` 3개(player/autoEq/opra)를 상위에서 호이스팅 → 스크롤 위치 유지. `selectedTab`은 `rememberSaveable`(프로세스 사망/회전에도 유지). 검색어·선택 프로파일은 ViewModel에 있어 탭 전환에도 유지.
3. **탭별 선택 + 활성 provider 1개** — AutoEQ·OPRA 선택값은 각각 보존하고, 실제 엔진에는 `activeCorrectionProvider`의 선택만 적용한다. 비활성 탭의 카드 전체를 누르면 해당 provider가 “현재 사용중”으로 전환된다(§9.1).
4. **Edge-to-edge** — 상태바·내비바 투명. 콘텐츠가 시스템 바 아래까지 그려지며 `Scaffold`가 inset 패딩 공급. 상태바 아이콘 극성은 테마에 따라 반전(라이트=어두운 아이콘, 다크=밝은 아이콘).

---

## 2. 디자인 토큰 & Redline

### 2.1 컬러 (Material 3 ColorScheme)

`Theme.kt`에서 **일부 역할만** 오버라이드. 나머지(아래 §2.1.3)는 M3 기본값.

#### 2.1.1 라이트
| 역할 | HEX | 역할 | HEX |
|------|-----|------|-----|
| primary | `#3B6EF6` | surface | `#FFFFFF` |
| onPrimary | `#FFFFFF` | onSurface | `#1C1C1E` |
| secondary | `#5E5CE6` | surfaceVariant | `#E5E7EB` |
| onSecondary | `#FFFFFF` | onSurfaceVariant | `#374151` |
| background | `#FAFAFA` | error | `#B00020` |
| onBackground | `#1C1C1E` | onError | `#FFFFFF` |

#### 2.1.2 다크
| 역할 | HEX | 역할 | HEX |
|------|-----|------|-----|
| primary | `#7AA2FF` | surface | `#1C1C1E` |
| onPrimary | `#002B6B` | onSurface | `#F2F2F2` |
| secondary | `#8E8AFF` | surfaceVariant | `#2C2C2E` |
| onSecondary | `#1A1947` | onSurfaceVariant | `#B0B5BD` |
| background | `#121212` | error | `#CF6679` |
| onBackground | `#F2F2F2` | onError | `#000000` |

#### 2.1.3 M3 기본값(미정의) — ⚠️ 디자이너 정의 필요 (현재 출하 = 언브랜디드 M3 baseline)
`tertiary` 및 **모든 `*Container` 역할**, `outline`, `outlineVariant`, `scrim`, `inverse*`, `surfaceTint`는 코드에서 **오버라이드 안 됨 → M3 baseline 팔레트가 그대로 출하**.

> 🟠 **시각 사양 재동기화 필요 (코드 변경 감지)**: 작업 중 `Components.kt`가 재스타일됨. **SourceBadge·StatusCard·카드 컨테이너의 현재(실측) 사양은 아래 정정본**을 따르세요. 다른 카드(GraphicEq/Diagnostics/About 등)도 함께 변경됐을 수 있어 **전체 시각 재감사 권장**.

> 🔴 **소스 배지(실측)** — `Surface`(`RoundedCornerShape(50)`), 라벨 `uppercase()` `labelSmall`, 패딩 가로 8·세로 **3** dp, 13% 알파 채움 + 45% 알파 보더. **AutoEQ → accent=`secondaryContainer`**, **OPRA → accent=`tertiary`**. 그런데 `secondaryContainer`·`tertiary` 둘 다 **미오버라이드(M3 baseline)** → 여전히 언브랜디드. 배지는 3곳(지금재생·MiniPlayer·StatusCard) 반복 노출 → **브랜드 값 정의 필요**.

아래는 **현재 출하 중인 M3 baseline 기본값(추정, 코드 미오버라이드 · Compose M3 버전에 따라 미세 상이 가능)** — 현 상태 목업용 시작값이며 브랜드 확정 대상:

| 역할 | 라이트 | 다크 | 쓰임 |
|------|--------|------|------|
| primaryContainer | `#EADDFF` | `#4F378B` | **AutoEQ 배지 배경** |
| onPrimaryContainer | `#21005D` | `#EADDFF` | AutoEQ 배지 글자 |
| tertiaryContainer | `#FFD8E4` | `#633B48` | **OPRA 배지 배경** |
| onTertiaryContainer | `#31111D` | `#FFD8E4` | OPRA 배지 글자 |
| outline / outlineVariant | `#79747E` / `#CAC4D0` | `#938F99` / `#49454F` | OutlinedButton 보더, Divider |
| scrim | `#000000` | `#000000` | OPRA 상세 다이얼로그 스크림 |

#### 2.1.4 런처 윈도우 색 (별도 소스, 시작 플래시 전용)
`colors.xml`(Compose 아님): brand_primary `#3B6EF6`, brand_background `#FAFAFA` / dark `#121212` 등. Compose 토큰 변경 시 **동기화 필요**.

### 2.2 타이포 역할 매핑 (요소 → M3 type role)

> M3 기본 타입스케일 사용. 아래는 각 요소가 쓰는 역할.

| 요소 | role | 요소 | role |
|------|------|------|------|
| 카드 제목(Status/ListenMode/Graphic/Diag/About) | `titleMedium` | 검색결과 이름(CatalogEntry/Opra product) | `bodyLarge` |
| 재생목록 헤더 | `titleSmall` | 본문/큐행/토글 라벨 | `bodyMedium` |
| 그래픽EQ '한계' 라벨 | `labelLarge` | 부가설명(measuredBy/subtitle/vendor) | `bodySmall` (onSurfaceVariant) |
| 소스 배지 / 재생시간 / 페이더 값·주파수 / 스냅샷 커밋 / OPRA footer / OPRA license행 | `labelSmall` | — | — |

**해상 타입 램프 (M3 baseline, 폰트 Roboto — 코드 미오버라이드, 출하값이지만 브랜드 미확정)**

| role | size | line-height | weight | letter-spacing |
|------|------|-------------|--------|----------------|
| titleMedium | 16sp | 24sp | Medium(500) | +0.15 |
| titleSmall | 14sp | 20sp | Medium(500) | +0.1 |
| bodyLarge | 16sp | 24sp | Regular(400) | +0.5 |
| bodyMedium | 14sp | 20sp | Regular(400) | +0.25 |
| bodySmall | 12sp | 16sp | Regular(400) | +0.4 |
| labelLarge | 14sp | 20sp | Medium(500) | +0.1 |
| labelSmall | 11sp | 16sp | Medium(500) | +0.5 |

### 2.3 간격 / 치수 스케일 (실제 코드값)

| 컨텍스트 | 값 |
|----------|-----|
| 화면 LazyColumn contentPadding | 가로 16 / 세로 12 dp, 아이템 간 12 dp |
| 카드 내부 패딩 | 16 dp |
| 접이식 카드 헤더 / 펼친 콘텐츠 | 헤더 16 dp / 콘텐츠 start16·end16·bottom16, 행 간 4(Diag)·8(About) dp |
| QueueRow | 가로 8 / 세로 10 dp |
| CatalogEntryRow · OpraCatalogRow | 가로 16 / 세로 12 dp |
| MiniPlayer 콘텐츠 / 진행바 | 가로 12·세로 6 dp / 진행바 높이 **2 dp** |
| 파일 액션 Row 간격 | 8 dp |
| 트랜스포트 버튼 간격 / 재생·정지 버튼 | 16 dp / **FilledIconButton 64 dp** (좌우 SkipPrev/Next 및 MiniPlayer play·next = 기본 `IconButton` 48 dp 터치타깃 / 아이콘 24 dp) |
| SourceBadge | 패딩 가로 8·세로 2 dp, `RoundedCornerShape(50)`(완전 알약) |
| EqGraphView | 높이 **160 dp**, 가로 패딩 4 dp |
| VerticalEqBand(페이더) | 컬럼 폭 34 dp, 슬라이더 박스 150(높이)×40 dp, 페이더 간 2 dp, **가로 스크롤** |
| WebLink | 세로 패딩 6 dp, 밑줄 + primary 색 |

### 2.4 아이콘 인벤토리 (Material Icons)

| 위치 | 아이콘 | cd |
|------|--------|-----|
| 내비 1/2/3 | `LibraryMusic` / `GraphicEq` / `Tune` | tab label |
| 트랜스포트 | `SkipPrevious` / `PlayArrow` / `Pause` / `SkipNext` | 있음(player_*) |
| QueueRow 선두 | `MusicNote`(비현재) / `PlayArrow`(현재·정지) / `Pause`(현재·재생) | player_queue_item_* |
| QueueRow 말미 / StatusCard | `Close` | player_remove / clear_selection |
| CatalogEntryRow 즐겨찾기 | `Filled.Star`(on) / `Outlined.Star`(off) | favorite_toggle |
| 접이식 카드 헤더 | `ExpandLess`(펼침) / `ExpandMore`(접힘) | diagnostics_* / about_* |
| 파일 추가 버튼 | `Add` | player_add_files |

### 2.5 카드 컨테이너 (실측 — `AuralTunePanel` 공통 래퍼)

> 🟠 코드 재스타일로 카드 시스템이 **`AuralTunePanel` 공통 컴포넌트**로 통합됨(이전 "surface vs 기본 Card" 구분은 폐기).

- **`AuralTunePanel`** = `Card`(`shape = shapes.large`, `containerColor = surfaceContainer`, 보더 `outlineVariant` @58% 알파 1dp, elevation `elevated`면 6dp·아니면 0dp).
- 확인됨: **StatusCard**(profile 있을 때 elevated=6dp), **ListenModeBar**(내부 패딩 12dp)가 `AuralTunePanel` 사용.
- ⚠️ GraphicEqCard·DiagnosticsCard·AboutCard·지금재생/큐 카드의 현재 컨테이너는 **재확인 필요**(이 문서 작성 후 변경 가능성).

### 2.6 M3 기본값 참고 (코드 미오버라이드 — 출하값이나 브랜드 미확정)

> 코드가 셰이프·고도·컴포넌트 높이를 오버라이드하지 않으므로 아래는 **M3 baseline 기본값**입니다. 현 상태 목업의 시작값으로 쓰고, 브랜드 확정 시 갱신하세요. (Compose M3 버전에 따라 미세 상이 가능)

| 항목 | M3 기본값 |
|------|-----------|
| `Card` 코너 반경 | 12 dp (medium shape) |
| 셰이프 스케일 | extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28 dp |
| 기본 `Card` 고도 / surface 카드 | filled Card 톤 컨테이너, elevation 0 dp (그림자 없음, 톤으로 구분) |
| `HorizontalDivider` | 두께 1 dp, 색 `outlineVariant` (CatalogEntryRow 아래, About 구분선) |
| `TopAppBar`(small) 높이 | 64 dp |
| `NavigationBar` 높이 | 80 dp (아이템 indicator pill 포함) |
| MiniPlayer 총 높이 | divider 1 + 진행바 2 + 콘텐츠 Row(아이콘 24 + 세로패딩 6×2 ≈ 36) ≈ **약 39 dp** + NavigationBar 80 dp |
| 기본 `IconButton` | 48 dp 터치타깃 / 아이콘 24 dp |
| `FilterChip`(게인 한계) | 높이 32 dp |
| `Button` / `OutlinedButton` | 최소 높이 40 dp |
| 지금재생 `Slider` | 트랙 4 dp, thumb 20 dp (M3 기본) |
| `Switch`(프리앰프) | 트랙 52×32 dp (M3 기본) |

---

## 3. 화면 개요 & 세로 배치 순서

| 탭 | 본문(LazyColumn) 위→아래 순서 |
|----|------|
| **PLAYER** | ① 지금재생 카드(스펙트럼 + 비트뎁스/샘플레이트) → ② Android 12+ 블루투스 권한 안내(필요 시) → ③ 원음/EQ 적용/커스텀 + 프리앰프 컨트롤 → ④ 재생목록 헤더(+ 파일 추가 버튼) → ⑤ 큐(빈상태 or 행들) |
| **AUTOEQ** | 〔소스블록 A〕검색 → (최근 빠른선택 드롭다운) → Import EQ·Clear cache → 프로파일 업데이트 확인 → 카탈로그 상태/목록 〔공유 보정영역〕StatusCard → ListenModeBar → GraphicEqCard → AutoEqPreampCard → DiagnosticsCard → (DEBUG)AudioFxProbe → AboutCard |
| **OPRA** | 〔소스블록 B〕검색 → 데이터 갱신 → 결과/빈상태 → **CC footer** 〔공유 보정영역〕(AUTOEQ와 동일 순서) |

> ⚠️ OPRA의 CC footer는 결과 목록과 StatusCard **사이**(화면 중간)에 위치 — 코드상 소스블록의 마지막 항목이기 때문. (개선 후보: §2부 C)

---

## 4. 화면별 상세 + Redline

### 4.1 PLAYER (홈)

```
미디어 있음:                                   미디어 없음(빈 상태):
┌─────────────────────────────────┐          ┌─────────────────────────────┐
│ ╭───── 지금재생 카드 ─────────╮   │          │   (지금재생 카드: 제목만)      │
│ │ 곡 제목 (titleMedium,2줄)   │   │          │   "재생할 파일이 없습니다"    │
│ │ [AutoEQ] 보정프로파일명 ←보정시│  │          │   슬라이더 0·비활성           │
│ │ ──●────────────  3:07 / 4:07 │  │          │   ⏮(비활성) ⏯(비활성) ⏭(비활성)│
│ │      ⏮    (⏯ 64dp)    ⏭     │  │          ├─────────────────────────────┤
│ ╰─────────────────────────────╯  │          │ 재생목록 (0)              [+] │
│ 재생목록 (3)                 [+] │          ├─────────────────────────────┤
│                                   │          │   (큐 영역)                   │
│  ▶ 1. track-a.wav            ✕   │          │  "'파일 추가'로 음악을 담아보세요"│
│    2. track-b.flac           ✕   │          └─────────────────────────────┘
│    3. track-c.mp3            ✕   │
└─────────────────────────────────┘
```

**① 지금재생 카드** (기본 Card, 내부 16dp)
- 제목: `titleMedium`, 최대 2줄. 미디어 있으면 곡 제목, 없으면 `재생할 파일이 없습니다`.
- (보정 있을 때만) Spacer 8dp + Row[`SourceBadge` + Spacer 8dp + 보정프로파일명 `bodySmall`/onSurfaceVariant/1줄].
- Spacer 12dp + 탐색 Slider(값=position/duration 0..1, dur≤0이면 0 & **비활성**, 드래그 시 seek) + Row[경과시간 `labelSmall` … 전체시간 `labelSmall`].
- Spacer 8dp + 가운데 정렬 트랜스포트 Row: `SkipPrevious` IconButton + Spacer16 + **`PlayArrow`/`Pause` FilledIconButton 64dp** + Spacer16 + `SkipNext` IconButton. **미디어 없으면 전부 비활성**.
- ⚠️ 시간 포맷 = `mm:ss`, **분 앞 0 없음**(예 `3:07`, `0:05`, 음수→`0:00`).

**② 재생목록 헤더**
- 헤더 Row: 좌측 `재생목록 (N)` `titleSmall`, 우측 40dp 사각 아이콘 버튼 `Add`(`player_add_files`).
- `Add` 아이콘 버튼 → SAF `OpenMultipleDocuments(audio/*)` 다중선택 → `addToQueue`(첫 추가 시 자동 재생). 선택기 없으면 Toast `saf_unavailable`(LONG).
- 목록 비우기/첫곡 버튼은 현재 플레이어 UI에서 제거한다. 큐 관리는 개별 행의 제거 버튼과 파일 추가 흐름으로 제한한다.

**③ 큐**
- 비어있으면 `EmptyStateMessage(player_queue_empty)`.
- 있으면 헤더 `재생목록 (N)` `titleSmall`(top 4dp) + `QueueRow` 리스트.

**QueueRow** (clickable, 패딩 가로8/세로10)
- 선두 아이콘: 현재+재생 `Pause` / 현재+정지 `PlayArrow` / 비현재 `MusicNote`. tint=현재면 primary, 아니면 onSurfaceVariant.
- 제목 `bodyMedium`(weight 1, 1줄, 현재면 primary 색).
- 말미 `Close` IconButton(`player_remove`).
- 행 탭 → `playIndex`, Close 탭 → `removeFromQueue`.

### 4.2 AUTOEQ — 소스 블록 A

> ⚠️ 검색창은 **TopBar가 아니라 본문 LazyColumn 첫 아이템**입니다.

순서:
1. **검색 OutlinedTextField** — value=`query`, placeholder `search_placeholder`("Search headphones…"), singleLine, ImeAction.Search. **입력 있으면** 오른쪽 trailingIcon `Close`(cd `clear_search`) → 검색어 비움. **AutoEQ 검색에는 최소 글자수 게이트 없음**(빈 검색어 허용, 150ms 디바운스 후 결과).
2. (최근목록 있을 때만) **빠른선택** — `OutlinedButton`(전폭) 라벨=선택됨이면 `프로파일: <이름>` 아니면 `프로파일 빠른 선택 ▾` → `DropdownMenu`(최근 프로파일, 최대 10, 최근순) 항목 탭 시 선택+닫힘.
3. **Row**: `Button` 「Import EQ」(`import_button`, weight1) → SAF `OpenDocument(text/plain, application/octet-stream, */*)` → import(스낵바 결과) · `OutlinedButton` 「Clear cache」(`clear_cache_button`, weight1) → 네트워크 캐시 삭제(스낵바 `Cache cleared`).
4. **OutlinedButton**(전폭) 「프로파일 업데이트 확인」 → 델타 동기화(스낵바 결과).
5. **카탈로그 상태머신** (아래 §7 상태매트릭스):
   - Loading → 중앙 Column(24dp)[`CircularProgressIndicator` + `Loading headphone catalog…`]
   - Error → `EmptyStateMessage(에러 메시지)`
   - Idle → `EmptyStateMessage(catalog_offline)` ("Offline — using cached catalog")
   - Loaded·결과 없음 → `EmptyStateMessage(`검색어 비었으면 `search_prompt` 아니면 `no_results`)`
   - Loaded·결과 있음 → `CatalogEntryRow` 리스트

**CatalogEntryRow** (clickable, 가로16/세로12, 행 아래 `HorizontalDivider`)
- 좌측 Column(weight1): 이름 `bodyLarge`(선택 시 primary, 아니면 onSurface) + (있으면) measuredBy `bodySmall`/onSurfaceVariant.
- 우측: `Star` IconButton — 즐겨찾기면 `Filled.Star`/primary, 아니면 `Outlined.Star`/onSurfaceVariant. (cd `favorite_toggle`)
- 행 탭 → 프로파일 선택, 별 탭 → 즐겨찾기 토글(선택과 독립).

### 4.3 OPRA — 소스 블록 B

순서:
1. **검색 OutlinedTextField** — value=`opraQuery`, placeholder `OPRA 헤드폰 검색…`(**하드코딩**), singleLine, ImeAction.Search, trailingIcon `Close`(입력 시).
2. **OutlinedButton**(전폭) — `opraRefreshing`이면 **비활성** + 라벨 `OPRA 갱신 중…`, 아니면 `OPRA 데이터 갱신` → 스냅샷 갱신(수 초 소요).
3. 결과:
   - `opraResults` 비었을 때 → `EmptyStateMessage(`갱신 중이면 `OPRA 데이터를 불러오는 중…` 아니면 `OPRA 헤드폰을 검색하거나 'OPRA 데이터 갱신'을 누르세요`)`
   - 있으면 → `OpraCatalogRow` 리스트
4. **footer** `opra_tab_footer` ("OPRA data · CC BY-SA 4.0 · not affiliated with Roon Labs / OPRA") `labelSmall`/onSurfaceVariant.

> ⚠️ **OPRA 검색은 2자 게이트**: trim 후 2자 미만이면 결과가 항상 빈 목록(가이드 문구만, 전체 카탈로그 덤프 안 함). DB LIMIT 100.

**OpraCatalogRow** (가로16/세로12, **clickable는 `isSupported`일 때만**)
- Column(weight1) 3줄: ① product `bodyLarge`(선택=primary / 미지원=onSurfaceVariant / 그외 onSurface) ② `vendor • author` `bodySmall`/onSurfaceVariant ③ `license`(+미지원 시 ` • 적용 불가`) `labelSmall`/onSurfaceVariant.
- **미지원 행**: product 색 onSurfaceVariant(딤) + `• 적용 불가` 접미사 + **탭 불가**.
- 지원 행 탭 → 상세 다이얼로그 오픈(즉시 적용 아님).

### 4.4 공유 보정 영역 (AUTOEQ·OPRA 동일)

#### ① StatusCard (실측: `AuralTunePanel`, profile 있을 때 elevated 6dp, 내부 16dp)
```
활성 탭(현재 사용중):                    비활성 탭(선택만 기억, 카드 탭으로 사용 전환):
╭──────────────────────────────╮      ╭──────────────────────────────╮
│ (◉)  [AUTOEQ]              ✕ │      │ (◉)  [AUTOEQ]              ✕ │
│  48dp  AKG K712 PRO          │      │  48dp  AKG K712 PRO          │
│  원형   Rtings               │      │  원형   Rtings               │
│ (● 현재 사용중)               │      │  카드 탭 → 현재 사용          │
╰──────────────────────────────╯      ╰──────────────────────────────╯
                                       보정 없음 → No correction active (titleMedium)
```
- 보정 시 Row: **48dp 원형 아이콘**(`GraphicEq`, 배경 `surfaceContainerLowest`, tint `secondaryContainer`) + Spacer14 + Column(weight1)[(sourceLabel 있으면)`SourceBadge`(이 탭 소스)+Spacer6 + 이름 `titleMedium` 2줄말줄임 + (measuredBy 비공백 시)`bodySmall`/onSurfaceVariant] + 우측 `Close`(탭별 클리어).
- 없으면: `no_correction_active` `titleMedium`만.
- **현재 사용중 표시**: 이 탭 선택이 활성이면 `SourceBadge` 옆에 `● 현재 사용중` 칩(secondaryContainer 계열 채움+보더, `labelLarge`, 라운드50)을 표시한다.
- **비활성 탭 사용 전환**: `지금 사용` 버튼은 두지 않고, 선택된 프로파일 카드 전체를 탭하면 해당 탭의 선택값이 현재 사용중으로 전환된다. 비활성 카드 배경은 기본 surface, 활성 카드는 `surfaceContainerHigh` + source accent border로 구분한다.

#### ② ListenModeBar (containerColor=surface, 내부 16dp) — A/B/C 비교
- 제목 `비교 모드`(`listen_mode_title`) `titleMedium` + Spacer8 + Row(간격8)[ModeButton×3 각 weight1] + Spacer6 + subtitle `bodySmall`/onSurfaceVariant.
- **ModeButton**: 선택=filled `Button`, 미선택=`OutlinedButton`(최소 높이 40dp 기본). 항상 정확히 하나만 채워짐. weight1로 3등분 — 라벨 오버플로/줄바꿈은 코드 미지정(기본 동작, 라벨이 짧아 1줄 가정).
- ⚠️ **subtitle은 `maxLines` 미지정 → 자유 줄바꿈**. USER 평탄 힌트(`프로파일 + 내 그래픽 EQ (현재 평탄 = AutoEQ와 동일)`)가 가장 길어 **2줄까지 늘어날 수 있음** → 카드 높이가 모드/언어에 따라 가변. 고정 높이가 필요하면 디자이너가 max-lines/예약높이를 정의.

| 버튼 | 라벨 | subtitle(현재 모드 힌트) |
|------|------|------|
| ORIGINAL | `원음` | `EQ 미적용 — 순수 원음` |
| AUTOEQ | `AutoEQ` | `AutoEQ/OPRA 프로파일만 적용` |
| USER | `내 설정` | bands非평탄: `프로파일 + 내 그래픽 EQ 보정` / 평탄: `프로파일 + 내 그래픽 EQ (현재 평탄 = AutoEQ와 동일)` |

#### ③ GraphicEqCard → §6 (그래픽 EQ 심화)

#### ④ AutoEqPreampCard (containerColor=surface, 내부 16dp)
- `ToggleRow`: 라벨 `AutoEQ preamp`(`preamp_toggle`) + 우측 `Switch`. (스위치 값은 무시하고 항상 토글 호출 — fire-on-change)

#### ⑤ DiagnosticsCard (**접이식, 기본 접힘**, 기본 Card)
- 헤더 Row(16dp, 전체가 탭 타깃): 제목 `Diagnostics`(`diagnostics_title`) `titleMedium`(weight1) + chevron(`ExpandMore`/`ExpandLess`, cd null).
- 펼치면 행(간격4dp) 순서: `device`(없으면 `— no eligible device —`) · `current sample rate`(값 `N Hz`) · `xrun count` · `non-finite reset count` · `config swap count` · `sample-rate change count` · `total processed frames` · **`AutoEQ active filters`(하드코딩)** · **`Applied generation`(하드코딩)**. (1Hz 폴링, 초기 전부 0)

#### ⑥ (DEBUG만) AudioFxProbeCard — 릴리스 없음(§11).

#### ⑦ AboutCard (**접이식, 기본 접힘**, 기본 Card)
- 헤더: `About & licenses`(`about_title`) `titleMedium` + chevron.
- 펼친 콘텐츠 순서(간격8dp): 버전 `AuralTune 0.1.0` `bodyMedium` → divider → AutoEq 출처 `bodySmall` → divider → OPRA 출처 `bodyMedium` → OPRA 라이선스 라벨 `bodySmall` → `WebLink`(라이선스) → `WebLink`(OPRA 프로젝트) → (opraSourceUrl 있으면)`WebLink`(데이터 소스) → 스냅샷 커밋 `OPRA snapshot · commit <앞8자>` 또는 `OPRA snapshot not loaded yet` `labelSmall` → divider → 변경 고지 → 비보증 고지 → 권리 비제한 고지(각 `bodySmall`).
- ⚠️ CC BY-SA 4.0 / MIT 출처·변경·비보증 문구는 **법적 필수**, 그대로 유지.

### 4.5 OpraProfileDetailDialog (전역 오버레이 AlertDialog)

지원 OPRA 행 탭 시, **어느 탭 위에서든** 뜸(OPRA 탭 전용 아님).
```
╭──────────────────────────────────────╮
│ <profile.profileName>                 │  (title)
│ Measured by <author>  (또는 Author not specified)
│ <details>            ← 비공백일 때만   │  bodySmall/onSurfaceVariant
│ · View measurement source ← link 있을 때│  WebLink
│ · License: CC BY-SA 4.0               │  WebLink
│ Cannot apply: <reason> ← 미지원일 때    │  error 색
│ ──────────────────────────────────    │
│ OPRA-derived: format-converted…(고지)  │  labelSmall
│              [Close]  [Apply this profile]│
╰──────────────────────────────────────╯
```
- **지원**: `Apply this profile`(`opra_detail_apply`) **활성**. **미지원**: Apply **비활성** + `Cannot apply: <reason>` error 색.
- Apply → 적용 후 **즉시 닫힘**(중복탭 방지). Close/스크림/back → 닫힘.

### 4.6 MiniPlayer (하단, 내비바 바로 위)

```
├─────────────────────────────────────┤
│ ▔▔▔▔▔▔▔▔──────────────  (2dp 진행바, dur>0일 때만)
│ ♪ [AutoEQ] 곡 제목                ⏯ ⏭ │  (가로12/세로6)
├─────────────────────────────────────┤
│  ⌂ 플레이어   ◧ AutoEQ   ◔ OPRA      │
└─────────────────────────────────────┘
```
- 위: `HorizontalDivider` → (durationMs>0이면) 2dp 진행바(frac=pos/dur).
- 콘텐츠 Row: `MusicNote`(primary) + Spacer10 + (보정 있으면)`SourceBadge`+Spacer8 + 제목 `bodyMedium`(weight1, 1줄, 비면 `player_no_media`) + `PlayArrow`/`Pause` IconButton + `SkipNext` IconButton.
- ⚠️ **이전 곡·탐색(seek) 없음** — play/pause + next 만. 본체 탭 → PLAYER로 펼침. (이는 개선 후보가 아니라 현재 확정 사실)

---

## 5. 컴포넌트 레퍼런스 (재사용)

| 컴포넌트 | 핵심 사양 |
|----------|-----------|
| **SourceBadge** | (실측 §2.1.3) `Surface`+`RoundedCornerShape(50)`, 라벨 `uppercase()` `labelSmall`, 패딩 가로8/세로3, accent 13% 채움+45% 보더. AutoEQ→`secondaryContainer` / OPRA→`tertiary`. 비인터랙티브. 노출 3곳: 지금재생·MiniPlayer·StatusCard |
| **ListenModeBar / ModeButton** | §4.4② |
| **AutoEqPreampCard / ToggleRow** | §4.4④ — ToggleRow=라벨(weight1)+Switch, 세로 패딩4 |
| **CatalogEntryRow** | §4.2 |
| **OpraCatalogRow** | §4.3 |
| **DiagnosticsCard / DiagnosticsTextRow / DiagnosticsRow** | §4.4⑤. TextRow 값=onSurfaceVariant, Row(숫자) 값=onSurface |
| **AboutCard / WebLink** | §4.4⑦. WebLink=`bodyMedium`/primary/밑줄, 세로 패딩6 |
| **OpraProfileDetailDialog** | §4.5 |
| **MiniPlayer / QueueRow** | §4.6 / §4.1 |
| **EmptyStateMessage** | Row 전폭·패딩24·가운데, `bodyMedium` 한 줄. 빈/오프라인/무결과/가이드에 재사용(문구만 다름) |
| **TextLinkButton** | 단순 `TextButton`(text) |

---

## 6. 그래픽 EQ 심화 (GraphicEqCard + EqGraphView)

### 6.1 GraphicEqCard 구조 (기본 Card, 내부 16dp) — 위→아래
1. **제목 Row**: `graphic_eq_title` 리소스(`그래픽 EQ (20밴드)`) `titleMedium`(weight1) + `TextButton` `graphic_eq_reset`(`리셋`).
2. **게인 한계 Row**: 라벨 `한계` `labelLarge` + Spacer8 + 각 옵션마다 `FilterChip` `±N`(간격6dp). 선택칩 = `gainLimitDb`에 가장 가까운 값(항상 정확히 하나 선택, nearest-snap).
   - 옵션 = **6 / 12 / 15 / 20 dB** (기본 **12**). 20은 저장 한계이기도 함.
3. **프리셋 Row**: `OutlinedButton` 「프리셋 저장」 + Spacer8 + Box[`TextButton`(선택명 또는 `프리셋 불러오기 (N)`) → DropdownMenu].
   - 프리셋 메뉴: 비었으면 비활성 항목 `저장된 프리셋 없음`; 있으면 각 항목에 말미 `삭제` 버튼(삭제는 메뉴 안 닫음).
4. (조건부) **SavePresetDialog**: 제목 `프리셋 저장`, `OutlinedTextField`(label `이름`, singleLine), 확인 `저장`(비공백 전까지 비활성), 취소 `취소`.
5. Spacer8 → **EqGraphView**(§6.2).
6. **프리앰프 표시 Row**: `Checkbox`(enabled=`|preampDb|>0.05`) + 라벨 — 프로파일 있으면 `프리앰프 표시 (±x.x dB)`(`%+.1f`), 없으면 비활성 + `프리앰프 표시 (프로파일 없음)`.
7. Spacer8 → **가로 스크롤 Row**(간격2dp)의 **VerticalEqBand ×20**. 총 콘텐츠 폭 = 20×34 + 19×2 = **718 dp**(화면 폭 초과 → 가로 스크롤. 기본 뷰포트에 보이는 페이더 수는 화면 폭÷36dp).

**VerticalEqBand**: 컬럼 폭 34dp. 상단 값 라벨 `%+.0f`(정수 dB, 부호, `labelSmall`) → 150dp 높이×40dp 박스의 회전(270°) 슬라이더(범위 `−gainLimitDb..+gainLimitDb`) → 하단 주파수 라벨 `labelSmall`.
- 주파수 라벨 포맷: `<1k`→정수; `1k~9.9k`→소수1자리+k; `≥10k`→정수+k.
- **실제 20밴드 라벨(고정, log f_i=20·1000^(i/19))**: `20 · 28 · 41 · 59 · 85 · 123 · 177 · 254 · 366 · 527 · 758 · 1.1k · 1.6k · 2.3k · 3.2k · 4.7k · 6.7k · 9.7k · 13k · 20k`. (이 값들로 페이더 캡션·축 눈금을 그릴 것 — 그래프 그리드의 50/100/…/10k와는 별개)

### 6.2 EqGraphView Redline (Canvas, 높이 160dp)

축: log x 20Hz–20kHz, dB y는 **±15 dB 클램프**(절반범위). **축 숫자·범례·곡선 라벨 없음**(그리드 라인만).

| 요소 | 색 | 두께 | 점선 | 조건 |
|------|-----|------|------|------|
| 세로 그리드 | `#22000000` | 1px | — | 50·100·200·500·1k·2k·5k·10k Hz |
| 가로 dB 그리드 | `#22000000` | 1px | — | ±5·±10·±15 dB |
| 0 dB 기준선 | `#55000000` | 2px | — | 항상 |
| 프리앰프 라인(주황, 수평) | `#FF6D00` | 2px | 12/6 | `showPreamp && |preampDb|>0.05` |
| AutoEQ 곡선(회색) | `#9E9E9E` | 2px | 8/8 | 프로파일 활성 시만 |
| 합성(유효) 곡선(파랑) | `#2962FF` | 3px | 없음 | 항상(맨 위) |

- `preampApplied`면 두 곡선을 `preampDb`만큼 아래로 평행 이동(평탄 구간이 프리앰프 라인에 안착). `preampDb` 엔진 허용 범위 `[-30, +30]`이나 보정 프리앰프는 보통 음수(클리핑 방지, 예 −6.0), 그래프 표시는 ±15 dB로 클램프.
- 입력: `autoEqFilters`/`preampDb`는 `listenMode != ORIGINAL`(=autoEqAudible)일 때만 전달, ORIGINAL이면 빈/0 → 그래프 평탄.
- ⚠️ **범례·축 눈금은 현재 코드에 없음 → 디자이너 신규 추가**(캔버스 밖). 160dp 캔버스는 가로패딩 4dp뿐 **예약 공간 없음** → 범례(파랑=유효 / 회색=AutoEQ / 주황=프리앰프)와 dB 눈금(±5/±10/±15), 주파수 눈금(50…10k)을 그래프 위/아래 또는 좌측 거터에 별도 배치하고 그만큼 카드 높이를 늘릴지 결정 필요.

---

## 7. 상태 매트릭스

| 화면/컴포넌트 | 상태 | 트리거/표시 |
|---------------|------|-------------|
| PlayerScreen | no-media / has-media·보정無 / has-media·보정有 / playing / paused / 큐 빈 / 큐 있음 / SAF 불가 | hasMedia·isPlaying·correctionSource·queue·ActivityNotFound |
| 지금재생 슬라이더·트랜스포트 | 활성 / 비활성 | hasMedia(+dur>0) |
| AutoEQ 카탈로그 | Idle(오프라인) / Loading / Error / Loaded·빈·검색어無(prompt) / Loaded·빈·검색어有(no_results) / Loaded·목록 | catalogState + query |
| AutoEQ 빠른선택 | 숨김(최근 없음) / `빠른 선택 ▾` / `프로파일: <명>` | recents·selected |
| OPRA 검색 | 2자 미만(가이드) / 2자+·무결과 / 결과 / 갱신중(불러오는 중) | opraQuery·opraResults·opraRefreshing |
| OPRA 갱신 버튼 | 활성 / 비활성+`갱신 중…` | opraRefreshing |
| OpraCatalogRow | 선택 / 지원·미선택 / 미지원(딤·`적용 불가`·탭불가) | isSelected·isSupported |
| StatusCard | 보정 없음 / 보정 있음(배지+이름+measuredBy?) | profile·sourceLabel·measuredBy |
| ListenModeBar | ORIGINAL / AUTOEQ / USER(평탄 힌트 변형) | listenMode·bands平坦여부 |
| GraphicEqCard | 평탄 vs 활성 / 프리앰프 有無(체크박스 활성/비활성) / 프리셋 없음(비활성 항목)·선택·dirty / 한계 nearest-snap | bands·preampDb·presets·selectedPresetId·gainLimit |
| DiagnosticsCard / AboutCard | 접힘(기본) / 펼침 ; device null 폴백 ; 커밋 null 폴백 | expanded·deviceHash·commit |
| OpraDetailDialog | 닫힘 / 지원(Apply) / 미지원(Apply 비활성+사유) / author 미상 / source 링크 無 | opraDetail·isSupported |
| MiniPlayer | 숨김 / 표시(진행바 dur>0) / playing·paused / 제목 폴백 | tab·hasMedia·durationMs |

---

## 8. 카피 카탈로그

### 8.1 문자열 리소스 (언어·치환·특수문자 보존)
> 특수문자 보존 필수: 말줄임 `…`, em-dash `—`, 가운뎃점 `·`, 한국어 곱은따옴표 `‘ ’`, `&amp;`. 치환자: `%s`/`%1$d`/`%1$s` 그대로.

**KO (플레이어 · 내비 · 비교 모드)**

| key | 텍스트 |
|-----|--------|
| tab_player | 플레이어 |
| player_no_media | 재생할 파일이 없습니다 |
| player_play / player_pause | 재생 / 일시정지 |
| player_prev / player_next | 이전 곡 / 다음 곡 |
| player_add_files | 파일 추가 |
| player_remove | 목록에서 제거 |
| player_queue_empty | ‘파일 추가’로 음악을 담아보세요 |
| player_queue_title | 재생목록 (%1$d) |
| saf_unavailable | 이 기기에서 파일 선택기를 열 수 없습니다 (SAF 미지원) |
| listen_mode_title | 비교 모드 |
| listen_mode_original / _autoeq / _user | 원음 / AutoEQ / 내 설정 |
| listen_mode_hint_original | EQ 미적용 — 순수 원음 |
| listen_mode_hint_autoeq | AutoEQ/OPRA 프로파일만 적용 |
| listen_mode_hint_user | 프로파일 + 내 그래픽 EQ 보정 |
| listen_mode_hint_user_flat | 프로파일 + 내 그래픽 EQ (현재 평탄 = AutoEQ와 동일) |

**EN (검색 · 상태 · 진단 · 캐시 · About · OPRA)**

| key | 텍스트 |
|-----|--------|
| search_placeholder | Search headphones… |
| no_results | No headphones found |
| search_prompt | Type a model name to search, or pick from the quick-select above |
| clear_search | Clear search |
| no_correction_active | No correction active |
| import_button | Import EQ |
| preamp_toggle | AutoEQ preamp |
| clear_selection / favorite_toggle | Clear selection / Favorite |
| catalog_loading | Loading headphone catalog… |
| catalog_offline | Offline — using cached catalog |
| catalog_stale_format | Last updated %s ago |
| clear_cache / clear_cache_button | Clear cache (동일 값 2키) |
| diagnostics_title | Diagnostics |
| diagnostics_device | device |
| diagnostics_no_device | — no eligible device — |
| diagnostics_sample_rate_now | current sample rate |
| diagnostics_total_frames | total processed frames |
| diagnostics_xrun | xrun count |
| diagnostics_non_finite | non-finite reset count |
| diagnostics_config_swap | config swap count |
| diagnostics_sample_rate | sample-rate change count |
| about_title | About & licenses |
| about_app_version | AuralTune %1$s |
| autoeq_attribution | Headphone correction profiles courtesy of Jaakko Pasanen's AutoEq project (MIT License) |
| opra_attribution | Headphone & EQ data from OPRA (Open Profiles for Revealing Audio), a community database started by Roon Labs. |
| opra_license_label | OPRA data license: CC BY-SA 4.0 |
| opra_license_link_label | View CC BY-SA 4.0 license |
| opra_project_link_label | OPRA project on GitHub |
| opra_source_data_link_label | OPRA dataset source |
| opra_changes_notice | Changes: EQ values are used as published by OPRA; AuralTune only converts their format for playback (OPRA-derived, original values preserved). |
| opra_no_endorsement | AuralTune is not endorsed, certified, or affiliated with OPRA contributors, Roon Labs, or the preset authors. |
| opra_license_not_restricted | This app's terms do not restrict any rights granted to you by CC BY-SA 4.0 for the OPRA data. |
| opra_snapshot_commit_format | OPRA snapshot · commit %1$s |
| opra_snapshot_none | OPRA snapshot not loaded yet |
| opra_tab_footer | OPRA data · CC BY-SA 4.0 · not affiliated with Roon Labs / OPRA |
| opra_detail_author_format | Measured by %1$s |
| opra_detail_author_unknown | Author not specified |
| opra_detail_source_link | View measurement source |
| opra_detail_license_link | License: CC BY-SA 4.0 |
| opra_detail_apply | Apply this profile |
| opra_detail_unsupported_format | Cannot apply: %1$s |
| opra_detail_close | Close |
| opra_detail_notice | OPRA-derived: format-converted for playback, original values preserved. Not affiliated with OPRA / Roon Labs. |
| opra_link_open_failed | No app found to open the link |

> ⚠️ AutoEq(MIT)·OPRA(CC BY-SA 4.0) 출처·변경(opra_changes_notice)·비보증(opra_no_endorsement)·권리비제한(opra_license_not_restricted) 문구는 **법적 필수 — 위 텍스트 그대로** About 카드/OPRA 상세에 노출.

### 8.2 리소스화 현황
내비, TopBar, AutoEQ/OPRA 검색, OPRA 갱신, 프로파일 업데이트, 빠른선택, 진단, 그래픽 EQ 주요 버튼/라벨, 플레이어 오류 메시지는 `strings.xml` 기준으로 리소스화됐다. `AutoEQ`, `OPRA`, `CC BY-SA 4.0`, `Roon Labs` 같은 고유명과 라이선스 명칭은 의도적으로 영문 표기를 유지한다.

### 8.3 동적 스낵바/토스트 (1회성 채널 `importMessage`)
주요 사용자 노출 메시지는 한국어 리소스로 정리됐다. OPRA/AutoEQ 고유명, 에러 코드, 라이선스명은 원문을 유지한다. (Material3 기본 스낵바, 액션 없음, short. 표시 후 ACK)

### 8.4 레거시/미사용 문자열 (현재 UI에 안 나옴)
`correction_toggle`("AutoEQ correction"), `kill_switch_label`, `kill_switch_hint` — 비교모드 바로 흡수됨. 새 디자인에서 노출 금지.

---

## 9. 동작 규칙 (디자인 제약)

### 9.1 탭별 독립 선택 · 단일 '현재 사용중'  *(구현·S25 검증 완료)*
엔진은 한 번에 하나의 보정만 재생 가능하지만, **선택 상태는 탭별로 독립**합니다.

- **탭별 선택 기억** — AutoEQ 탭은 `selectedAutoEqProfile`, OPRA 탭은 `selectedOpraProfile`를 각자 보유·영속(영속 키 `selectedProfileId` / `activeOpraProfileId`). 같은 헤드폰이 양쪽에 있어도 각 탭에서 독립 선택. 각 탭의 **StatusCard·그래프는 자기 탭 선택**을 표시.
- **현재 사용중 = activeProfile** — 실제 엔진에 적용된 하나 = `activeProfile`(= `correctionProvider`가 가리키는 탭의 선택). 플레이어·미니 배지는 이 활성 프로파일을 표시.
- **선택 = 즉시 적용** — 탭에서 프로파일을 고르면 그 즉시 엔진 적용(그 탭이 활성 provider가 됨). 다른 탭의 선택은 그대로 기억(미적용).
- **StatusCard 상태 표시**:
  - 이 탭 선택이 활성이면 → **`● 현재 사용중`** 칩(secondaryContainer 18% 배경 + 보더).
  - 활성이 아니면(다른 탭이 재생 중) → **`지금 사용`** 버튼(`onUse`) — 누르면 이 탭 선택을 다시 엔진에 적용·활성 전환.
  - `✕` 클리어는 탭별(`clearAutoEqSelection`/`clearOpraSelection`). 활성 탭 클리어 시에만 엔진 정지, 비활성 탭은 기억만 제거.
- **하단 내비 활성 점** — 현재 사용중인 소스 탭(AutoEQ/OPRA) 아이콘에 `BadgedBox`+`Badge()` 점(`correctionSource`와 일치하는 탭).
- **최근 10개 빠른선택** — AutoEQ·**OPRA 양 탭** 모두 최근 선택 10개를 기억(`recentProfiles`/`recentOpraProfiles`), 드롭다운에서 바로 재선택(OPRA는 상세 시트 생략하고 직접 적용).

> 참고(데이터 품질, 본 변경과 별개): OPRA 프로파일의 StatusCard 표시명이 변환 어댑터(`toAutoEqProfile`) 매핑상 제품명 대신 `Measured by …`로 나오는 경우가 있음 — OPRA→AutoEq 네이밍 보정은 별도 과제.

### 9.2 비교 모드 ↔ 엔진 (기본 USER)
- ORIGINAL = 전부 off(순수 패스스루) **+ 백그라운드 네트워크 fetch 중단**(킬스위치). AUTOEQ = 프로파일만. USER = 프로파일 + 비평탄 그래픽 EQ.
- 모드 전환은 **0.5초 크로스페이드**(클릭 방지) — 급격한 점프 없음.
- **그래픽 EQ 슬라이더를 만지면 자동으로 USER로 전환**(편집이 즉시 들리도록).
- ℹ️ ORIGINAL이 멈추는 건 **백그라운드 프로파일 업데이트/델타 fetch(네트워크)** 뿐 — 이미 로드된 카탈로그에 대한 **로컬 검색·선택은 정상 동작**. 즉 ORIGINAL 모드에서도 AutoEQ 탭 검색 결과는 평소대로 보임(카탈로그 상태머신은 그대로). 단 「프로파일 업데이트 확인」은 스킵되며 스낵바 `원음 모드 — 업데이트를 건너뜁니다`.

### 9.3 그래픽 EQ
- 항상 **고정 20밴드**(log 20Hz–20kHz, 피킹 전용) — 가변 밴드/커스텀 주파수/다른 셰이프 불가.
- 게인 한계 4값(6/12/15/20), 낮추면 현재 게인 즉시 클램프(한계+게인 함께 저장).
- 슬라이더 변경: 40ms 디바운스 후 엔진 반영, 400ms 후 영속. 어떤 밴드 편집/리셋/한계 변경이든 선택 프리셋을 **dirty**(해제).
- 프리셋은 **밴드 게인 20개만** 저장(프로파일/계수 아님), 최근 프로파일은 최대 10개.

### 9.4 OPRA 적용 2단계 + 한계
- 행 탭 = 메타데이터 시트 오픈(적용 아님) → Apply 버튼이 적용 + 즉시 닫힘.
- **OPRA >10밴드는 통째 배제**(사유 `too many bands (N > 10)`), 미지원 필터/무밴드/잘못된 값도 배제. 반면 **AutoEQ 카탈로그 >10밴드는 앞 10개로 잘림**(비대칭 — 메시지 다름).
- OPRA 라이선스 배지 텍스트 고정: `CC BY-SA 4.0`.

### 9.5 재생 세션
- ExoPlayer **세션 1개**가 플레이어 탭·미니 공유. 진행 위치 500ms 폴링. 제목 폴백 `Unknown`, durationMs=0=길이 미상. **반복/셔플 컨트롤 없음**(API 미노출 — 추가 시 엔진 확장 필요).
- EQ는 **스테레오 PCM에만** 적용(모노 등은 패스스루), 출력 항상 16-bit.

---

## 10. 권한 & 플랫폼

| 항목 | 내용 |
|------|------|
| `INTERNET`, `ACCESS_NETWORK_STATE` | 카탈로그/프로파일 HTTPS fetch, 오프라인 감지 |
| `MODIFY_AUDIO_SETTINGS` | 오디오 라우팅 (설치시 권한) |
| `BLUETOOTH_CONNECT` (minSdk31) | 기기 식별 기반 AutoEQ. **Android 12+ 런타임 권한** 사유 카드와 허용/나중에 버튼은 구현됨. 남은 범위는 거부·영구거부 후 재안내 문구와 라우트별 수동 QA 증적. |
| 미디어 권한 | **릴리스: SAF(OpenDocument)만**, 미디어 권한 없음. `READ_MEDIA_AUDIO`/`READ_EXTERNAL_STORAGE`는 **DEBUG 빌드 전용** |
| 네트워크 | HTTPS 전용, cleartext 금지, 시스템 신뢰앵커만 (GitHub raw) |
| 백업/전송 | 전면 비활성 (사용자 EQ/기기 데이터 단말 밖 유출 안 함) |
| 구조 | 단일 Activity, MAIN/LAUNCHER 인텐트만 (딥링크/공유 없음), RTL 지원 |

---

## 11. 디버그 전용 / 비출하 (릴리스 목업에 넣지 말 것)

- **첫곡 자동 선택 helper** (`DebugSupport.firstPlayableUri`) — debug 구현은 남아 있으나 현재 플레이어 UI 버튼은 제거됨.
- **AudioFxProbeCard** (보정화면, DiagnosticsCard 이후) — 이펙트 세션 브로드캐스트 커버리지 측정 카드.
- **EqState Logcat** — 진단 폴링 시 디버그 로그.
- **OS-effect 근사 백엔드 전체**(`ExternalAudioFxController`/`OsEffectBackend`/`AutoEqApprox`) — **동면 상태**(코드·테스트 존재, 프로덕션 UI에서 한 번도 호출 안 됨). **외부앱/OS 이퀄라이저 근사 화면을 출하 UI인 것처럼 디자인하지 말 것.**
- DEBUG 빌드는 OPRA 시드를 GitHub raw에서, 릴리스는 번들 스냅샷에서.

---

# 2부 · 개선 제안 / 열린 질문

> 현재 동작을 바꾸자는 게 아니라, 디자이너가 검토·결정할 지점입니다.

## 0. 목업 시작 전 결정 사항 (deliverable 전제)

- **렌더 로케일** — 주요 UI/스낵바는 한국어 리소스로 정리됨. OPRA, CC BY-SA, URL, 제조사명 같은 법적/고유 명칭은 영문 유지. 목업 산출 시 `한국어 우선 + 법적 고유명 영문 유지` 기준으로 통일.
- **신규 설계가 필요한 "출하 필수" 화면(코드에 아직 없음)**:
  - **BLUETOOTH_CONNECT 런타임 권한 플로우**(Android 12+) — 플레이어 화면에 사유 카드와 허용/나중에 버튼은 구현됨. 거부·영구거부 후 재안내 UX와 라우트별 수동 QA 증적은 후속.
  - **EqGraphView 범례·축 눈금**(§6.2) — 캔버스에 없음, 배치·공간 신규 설계.
  - **앱 아이콘 세트**(§D12) — 구현 완료. 후속은 Play Store 고해상도 아이콘/피처 그래픽 산출물.

## A. 일관성 (1순위)
1. **언어 혼재 해소** — 주요 UI와 스낵바는 한국어 리소스로 정리됨. 남은 범위는 법적 고유명/라이선스 영문 유지 정책, ko/en 다국어 분리 여부 결정.
2. **카피 톤** — 진단/About의 개발자향 영어를 사용자 친화 문구로.

## B. 플레이어
3. **앨범 아트** — 현재 ♪ 아이콘만. 지금재생·미니에 커버 노출 시 체감 ↑.
4. **큐 드래그 재정렬 / 스와이프 삭제** — 현재 점프·✕만.
5. **미니 플레이어 진행 표현** — 2dp 바 외 대안(원형/남은시간). (현재 seek·이전곡 없음은 의도된 사실)

## C. 보정 화면
6. **OPRA CC footer 위치** — 현재 결과목록과 StatusCard 사이(중간). 화면 최하단 이동 검토.
7. **소스 배지 접근성** — AutoEQ/OPRA 색 대비(WCAG) + 색맹 대비 형태/아이콘 병행. **tertiaryContainer(OPRA) 미정의 → 색 명시 필요(§2.1.3).**
8. **그래픽 EQ 가독성** — 20개 페이더 터치타깃, EqGraphView **범례·축 눈금 디자이너가 추가**(§6.2).
9. **OPRA 상세 → 바텀시트** — 현재 AlertDialog.
10. **두 탭 통합 검토** — 공유 보정영역이 동일하고 슬롯도 공유. "소스 토글 + 단일 보정화면"으로 IA 단순화 가능(검색 블록만 분기). (가장 큰 IA 결정)
11. **접이식 카드 chevron 접근성** — 주요 접이식/버튼 contentDescription은 코드 기준 점검 완료. 디자인 QA에서는 TalkBack 실제 낭독 흐름만 후속 확인.

## D. 시스템/브랜딩
12. **스토어 그래픽 산출물** — 앱 실행 아이콘은 구현 완료. Play Store용 512px 아이콘, feature graphic, 스크린샷 세트는 별도 산출 필요.
13. **다크/라이트 + Dynamic Color** — 현재 OS 추종, Material You 의도적 off. 채택 여부 결정.
14. **타입스케일/셰이프 토큰** — 현재 M3 기본. 브랜드 타입/코너 정의 시 전부 신규.
15. **BLUETOOTH_CONNECT 런타임 권한 UI** — 사유/요청 기본 화면은 구현됨. 후속은 거부·영구거부 후 재안내 UX와 실제 Bluetooth 라우트 QA.

## E. 열린 질문 (디자이너 결정)
- AutoEQ·OPRA **탭 분리 vs 단일화면+소스토글**? (→ C10)
- "보정 1개" 모델을 UI에서 더 강조할까? (예: 다른 소스 적용 시 "기존 보정 교체" 확인)
- 진단 카드: 일반 사용자 노출 유지 vs 고급/설정 영역으로 이동?
- 미니 플레이어 확장 액션 범위(seek/큐 미리보기 추가할지)?

---

## 부록 · 기술 제약 요약 (디자이너 필독)

- 보정 체인 = **단일 프로파일(최대 10 biquad)** + 사용자 20밴드 오버레이. 다중 스택·AutoEQ 프로파일 밴드 편집 불가.
- 캐스케이드 순서: manual → autoEqPreamp → autoEq → loudnessComp → loudnessEq → softLimiter.
- 그래픽 EQ 고정 20밴드(피킹), 한계 6/12/15/20(기본12).
- OPRA >10밴드 배제 / AutoEQ >10밴드 절단 (비대칭).
- 모드 전환 0.5s 크로스페이드. 그래픽EQ는 즉시.
- 재생 세션 1개(미니=플레이어 공유), 반복/셔플 없음, 스테레오 EQ·16bit 출력.
- OPRA 오프라인 동작(APK 번들 스냅샷), 출처/라이선스 표기 의무.
- 단일 Activity, NavHost 없음, edge-to-edge, RTL.
```

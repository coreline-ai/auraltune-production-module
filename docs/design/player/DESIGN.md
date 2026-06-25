---
name: AuralTune Design System
colors:
  surface: '#131315'
  surface-dim: '#131315'
  surface-bright: '#39393b'
  surface-container-lowest: '#0e0e10'
  surface-container-low: '#1c1b1d'
  surface-container: '#201f21'
  surface-container-high: '#2a2a2c'
  surface-container-highest: '#353437'
  on-surface: '#e5e1e4'
  on-surface-variant: '#c3c5d7'
  inverse-surface: '#e5e1e4'
  inverse-on-surface: '#313032'
  outline: '#8d90a0'
  outline-variant: '#434655'
  surface-tint: '#b5c4ff'
  primary: '#b5c4ff'
  on-primary: '#00297b'
  primary-container: '#648aff'
  on-primary-container: '#00236c'
  inverse-primary: '#0f52db'
  secondary: '#e6feff'
  on-secondary: '#003739'
  secondary-container: '#00f4fe'
  on-secondary-container: '#006c71'
  tertiary: '#ffb3ac'
  on-tertiary: '#680008'
  tertiary-container: '#ff544e'
  on-tertiary-container: '#5c0006'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#dbe1ff'
  primary-fixed-dim: '#b5c4ff'
  on-primary-fixed: '#00164d'
  on-primary-fixed-variant: '#003cac'
  secondary-fixed: '#63f7ff'
  secondary-fixed-dim: '#00dce5'
  on-secondary-fixed: '#002021'
  on-secondary-fixed-variant: '#004f53'
  tertiary-fixed: '#ffdad6'
  tertiary-fixed-dim: '#ffb3ac'
  on-tertiary-fixed: '#410003'
  on-tertiary-fixed-variant: '#930010'
  background: '#131315'
  on-background: '#e5e1e4'
  surface-variant: '#353437'
typography:
  headline-lg:
    fontFamily: Geist
    fontSize: 32px
    fontWeight: '700'
    lineHeight: '1.2'
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Geist
    fontSize: 24px
    fontWeight: '700'
    lineHeight: '1.2'
  headline-md:
    fontFamily: Geist
    fontSize: 20px
    fontWeight: '600'
    lineHeight: '1.4'
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.6'
  data-display:
    fontFamily: Geist
    fontSize: 14px
    fontWeight: '500'
    lineHeight: '1'
    letterSpacing: 0.05em
  label-sm:
    fontFamily: Geist
    fontSize: 12px
    fontWeight: '600'
    lineHeight: '1'
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  unit: 4px
  gutter: 16px
  margin: 24px
  container-max: 1440px
---

## Brand & Style
본 디자인 시스템은 전문가용 오디오 엔지니어링 툴을 위한 고정밀, 고성능 아이덴티티를 지향합니다. 사용자에게 스튜디오 급 하드웨어를 제어하는 듯한 신뢰감과 몰입감을 제공하는 것이 핵심입니다.

- **브랜드 페르소나**: 전문가적(Professional), 정밀함(Precise), 첨단 기술(High-tech), 우아함(Elegant).
- **디자인 스타일**: 'Industrial Modernism'과 'Subtle Glassmorphism'의 결합. 기계적인 정밀함과 현대적인 소프트웨어의 유연함이 공존하는 스타일입니다.
- **감성적 목표**: 사용자가 복잡한 오디오 데이터를 다룰 때 느낄 수 있는 피로도를 최소화하고, 모든 조작이 즉각적이고 정확하게 반영된다는 확신을 줍니다.

## Colors
전문적인 작업 환경을 위해 심해의 깊은 색감을 베이스로 하며, 데이터의 가시성을 높이기 위한 고채도의 포인트 컬러를 사용합니다.

- **Surface Layers**: Obsidian(#0A0A0C)을 최하단 배경으로 사용하고, UI 요소는 Deep Charcoal(#121214) 레이어로 구분하여 깊이감을 형성합니다.
- **Accents**: 
  - **Electric Blue (#3B6EF6)**: 주요 액션, 활성화된 상태, 브랜드 핵심 요소에 사용합니다.
  - **Neon Cyan (#00F5FF)**: 시각적인 피드백, 하이라이트, 미터링 시스템의 피크 지점에 사용합니다.
  - **Status Red (#FF3B3B)**: 경고, 클리핑(Clipping), 녹음 상태 등 주의가 필요한 곳에 제한적으로 사용합니다.
- **Glow Effect**: 모든 액센트 컬러는 0.1~0.2 오퍼시티의 은은한 글로우 효과를 동반하여 하드웨어의 LED 느낌을 재현합니다.

## Typography
고정밀 데이터를 다루는 만큼 숫자의 가독성과 위계 질서가 가장 중요합니다.

- **Primary Typeface**: `Geist`를 사용하여 기술적이고 현대적인 룩을 연출합니다. 특히 숫자 데이터와 라벨에 사용하여 단단한 느낌을 줍니다.
- **Secondary Typeface**: `Inter`를 본문과 설명 텍스트에 사용하여 긴 작업 시간에도 눈의 피로를 줄입니다.
- **Numeric Data**: 모든 수치 데이터는 가급적 고정폭(Monospaced) 성격이 강한 폰트 설정을 적용하여, 값이 변할 때 레이아웃이 흔들리지 않도록 합니다.
- **Capitalization**: 메뉴 라벨과 섹션 헤더는 주로 대문자(Uppercase)와 적절한 자간(Letter-spacing)을 사용하여 기능적인 가시성을 확보합니다.

## Layout & Spacing
데이터 밀도가 높은 전문가용 툴의 특성에 맞춰 효율적인 공간 배분을 원칙으로 합니다.

- **Grid System**: 4px 베이스의 그리드 시스템을 사용하여 요소 간의 정밀한 간격을 유지합니다.
- **Density**: 작업 영역은 정보 밀도를 높게(High Density) 유지하되, 각 모듈(EQ, Compressor 등) 사이에는 명확한 거터(Gutter)를 두어 시각적으로 분리합니다.
- **Responsive Strategy**: 데스크탑 환경에서는 고정된 사이드바와 유연한 중앙 작업 영역을 갖춘 고정 그리드(Fixed Grid)를 선호하며, 모바일 환경에서는 스택형 레이아웃으로 전환합니다.

## Elevation & Depth
하드웨어 장비의 입체감을 소프트웨어적으로 재해석합니다.

- **Tactile Surfaces**: 요소의 상단 에지에는 미세한 White-inner-glow(1px, 5-10% Opacity)를, 하단에는 Dark-shadow를 배치하여 버튼과 슬라이더가 돌출된 듯한 효과를 줍니다.
- **Glassmorphism**: 팝업 메뉴나 플로팅 패널은 `backdrop-filter: blur(20px)`와 Semi-transparent Obsidian 배경을 사용하여 하위 레이어와의 연결성을 유지합니다.
- **Inner Depth**: 입력 필드나 트랙 영역은 안으로 파여 있는 듯한 'Inner Shadow'를 적용하여 물리적인 '슬롯'의 느낌을 구현합니다.

## Shapes
산업용 장비의 견고함을 표현하기 위해 절제된 곡률을 사용합니다.

- **Corner Radius**: 기본 4px에서 최대 8px를 넘지 않도록 하여 샤프하고 정밀한 인상을 유지합니다.
- **Interactive States**: 호버(Hover) 시에는 외곽선(Stroke)의 강도를 높이거나 Accent Color 글로우를 추가하여 활성화 상태를 명확히 알립니다.
- **Control Elements**: 노브(Knob)는 완전한 원형을 사용하되, 내부 인디케이터는 날카로운 직선을 사용하여 지시 방향을 명확히 합니다.

## Components
본 디자인 시스템의 컴포넌트는 오디오 엔지니어링의 특수성을 반영합니다.

- **Buttons**: 다크 모드에 최적화된 Ghost 스타일과 포인트 컬러가 채워진 Solid 스타일로 구분합니다. 클릭 시 물리적인 눌림 효과를 시각화합니다.
- **Sliders & Faders**: 트랙의 페이더는 금속 질감의 핸들을 가지며, 현재 수치가 실시간으로 옆에 표시됩니다. 배경 레일은 안으로 파인 형태입니다.
- **Technical Graphs**: 파형(Waveform)이나 EQ 커브는 Neon Cyan 컬러의 얇은 선으로 표현하며, 선 아래에 미세한 그라데이션 채우기를 적용합니다.
- **Knobs**: 마우스 드래그로 조작하는 다이얼 형태로, 현재 값을 나타내는 LED 링(Ring) 효과를 액센트 컬러로 표현합니다.
- **Input Fields**: 라벨이 필드 상단에 고정된 작고 응축된 형태를 사용하며, 포커스 시 Electric Blue 외곽선이 강조됩니다.
- **Cards/Modules**: 각 오디오 프로세서 유닛은 미세한 보더와 상단 하이라이트가 있는 다크 카드로 감싸 하드웨어 랙(Rack)에 장착된 듯한 느낌을 줍니다.
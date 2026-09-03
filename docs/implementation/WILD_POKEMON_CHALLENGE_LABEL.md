# 야생 포켓몬 전투 안내 폰트

## 증상과 원인

포켓몬 이름 아래의 `R 키를 눌러 전투`는 메뉴나 HUD가 아니라 Cobblemon
`PokemonRenderer.renderNameTag`의 `challenge_label`이다.

기존 수정은 이름에 `minecraft:uniform`, 안내 문구에는 `minecraft:default`를
지정했다. 하지만 모드팩의 `default`는 Pokemon BW Caxton 리소스팩으로
교체되어 있으므로 바닐라 월드 텍스트 렌더링으로 우회되지 않았다.
또한 출력 직전에만 폰트를 바꾸면 가로폭은 이전 폰트로 계산된다.

## 수정 범위

- `LocalizationUtilsKt.lang`에서 `challenge_label`이 생성되는 시점에만
  `PokemonChallengeLabelFont.apply`를 적용한다.
- 번역과 키 이름을 포함한 모든 텍스트 조각에 `minecraft:uniform`을 지정한다.
- 너비 계산과 두 번의 원래 출력 호출이 같은 텍스트를 사용한다.
- 기존 키 설정, 언어, 색상, `entity.canBattle(player)` 조건은 유지한다.
- 첫 승리 후 안내를 숨기는 설정을 우회하는 기존 처리는 유지한다.
- 전역 Caxton 폰트, UI 크기, 안내 위치, 투명도, 셰이더는 변경하지 않는다.

## 검증

`PokemonChallengeLabelFontTest`는 한글 번역, 자식 키 컴포넌트의 폰트,
색상 유지, 원본 불변성, R이 아닌 키 표시를 검사한다.
설치된 Cobblemon 1.7.3의 바이트코드에서도 번역 생성 → 너비 계산 →
두 출력 호출 순서와 Redirect 대상 시그니처를 확인했다.

2026-09-03: 컴파일, 단위 테스트 2개, JAR 생성 통과.
Computer Use의 javaw 접근이 허용되지 않아 실제 게임 화면 확인은 미완료다.
컴파일 통과를 화면 표시 확인으로 보고하지 않는다.

화면 확인은 수정 JAR로 게임을 재시작한 후 진행한다.
Caxton 리소스팩과 기존 셰이더를 켠 상태에서, 싸울 수 있는 포켓몬을 소지하고
전투 가능한 야생 포켓몬을 바라본다. 이름 아래 한글 안내·현재 키 이름이
표시되는지 확인하고, 영문 및 키 변경도 확인한다.

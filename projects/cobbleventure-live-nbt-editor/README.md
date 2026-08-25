# Cobbleventure Live NBT Editor

기존 Structure Builder와 별개인 단일 NBT 편집 모드다. Content Studio가 월드의
`generated/cobbleventure_builder/live` 브리지에 선택한 NBT를 보내면 전용 편집 차원에는
항상 구조물 하나만 배치된다. 다른 NBT를 열기 전 현재 구조물을 자동 저장하며, 웹은 저장
결과를 원래 `content/structures` 파일에 반영한다.

`edit_world`는 단일 원본 편집 전용이고 `test_world`는 현재 NBT 사본을 격자에 계속
추가하는 통합 테스트 공간이다. 테스트 사본은 원본 저장 범위와 분리된다.

각 차원은 시작할 때 아침(1000틱)으로 맞춰지고 시간 흐름은 멈춘다. `/time` 명령으로
다른 고정 시각을 선택할 수 있다. 전용 모드팩은 Cobblemon 자연 스폰을 끄고 저장소에서
검증한 Iris + Complementary Reimagined/Euphoria 셰이더 조합을 기본 활성화한다.

```bat
build.bat live-editor-world
```

게임 명령은 `/cobbleventure_live status`, `/cobbleventure_live sync`,
`/cobbleventure_live save`, `/cobbleventure_live tp`,
`/cobbleventure_live test place`, `/cobbleventure_live test tp`를 제공한다. 외부 NBT 추가, 구조물
선택, 편집 범위 변경은 Content Studio의 빌드 화면에서 수행한다.

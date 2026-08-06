# Cobbleventure Player Menu

인벤토리 키를 Cobbleventure의 8칸 원형 플레이어 메뉴 진입점으로 바꾸는
NeoForge 1.21.1 모드다.

## 현재 동작

- 바닐라 인벤토리 화면이 열릴 때 원형 메뉴로 바꾼다.
- 포켓몬, 가방, 장비, PC, 트레이너 카드, 퀘스트, 지도와 도감을 고정 위치에 표시한다.
- 마우스와 숫자키 `1`~`8`로 항목을 선택한다.
- 인벤토리 키를 다시 누르거나 `Esc`를 누르면 닫는다.
- 장비 항목은 바닐라 인벤토리 화면으로 연결한다.
- 도감 항목은 플레이어 인벤토리에 있는 첫 Cobblemon 도감의 색상을 유지해 기존 도감 화면을 연다.
- 도감 아이템이 없으면 필요한 아이템을 안내한다.
- 아직 화면이 없는 나머지 항목은 선택 시 준비 중 상태를 표시한다.

## 빌드

저장소에서 사용하는 공용 Gradle Wrapper로 빌드한다.

```bat
projects\cobbleventure-battle-ai\gradlew.bat ^
  -p projects\cobbleventure-player-menu build
```

빌드가 끝나면 JAR이 다음 개발 팩 경로에 설치된다.

```text
pack/overrides/development-placeholder/mods/
```

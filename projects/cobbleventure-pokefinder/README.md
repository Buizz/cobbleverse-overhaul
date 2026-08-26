# Cobbleventure Pokefinder

CobbleNav 2.3.3 포켓파인더 HUD 위에 Cobbleventure 탐색 마커를 렌더링하는 전용 NeoForge 모듈이다.

현재 구현 범위는 정적 장소, NPC 상태, 현재 목표, 통합 설정과 마커 겹침 처리다.

- CobbleNav와 독립적인 범용 `RadarMarker` 모델
- 원자적으로 교체되는 클라이언트 마커 스냅샷
- CobbleNav 2.3.3의 패널 크기, 축척, 오프셋과 손 배치를 재현하는 어댑터
- 기존 CobbleNav HUD 이후에 동작하는 `Gui.renderTitle` 후처리 Mixin
- 로컬 범위와 256블록 임시 상한, 가장자리 고정 좌표 변환
- 실제 건물 배치의 회전된 출입구와 관문·숲·동굴 입구 조회
- 로그인·차원 이동·20틱 간격 변경 감지 기반 서버 스냅샷
- 센터·상점·체육관·카지노·동굴·숲·관문용 소형 형태 아이콘
- 포켓내비의 미완성 연락처 슬롯을 포켓파인더 앱 버튼으로 교체
- 앱 버튼과 `O` 키로 HUD를 `꺼짐 → 왼쪽 아래 → 오른쪽 아래` 순환

포켓내비의 원형 메뉴에서 포켓파인더 아이콘을 누르면 별도 아이템을 들지 않아도
내장 포켓파인더 HUD가 표시된다. 원래의 미완성 지도 슬롯은 플레이어 메뉴 모듈이
Cobbleventure 월드맵으로 연결하며, 기존 지도 잠금 해제 상태를 그대로 따른다.

## 빌드와 테스트

저장소 루트에서 다음 명령을 사용한다.

```bat
build.bat mod-pokefinder
```

좌표 변환 테스트만 실행하려면 공유 Gradle wrapper를 사용한다.

```bat
projects\cobbleventure-battle-ai\gradlew.bat -p projects\cobbleventure-pokefinder test
```

## 시각 확인용 마커

개발 클라이언트 JVM에 아래 속성을 추가하면 플레이어 기준 동쪽 96블록의 테스트 목표가 레이더 가장자리에 표시된다. 기본값은 비활성화다.

```text
-Dcobbleventure.pokefinder.testMarker=true
```

전체 아이콘, 상태 표시, 우선순위 겹침, 일반 장소의 범위 제한과 장거리 목표의
가장자리 고정을 한 화면에서 확인하려면 시각 회귀 시나리오를 사용한다. 이
시나리오의 마커는 사용자의 탐색 정보 필터와 관계없이 보이며 실제 서버
스냅샷에는 저장되지 않는다.

```bat
set "COBBLEVENTURE_INTEGRATION_MODS_DIR=%USERPROFILE%\curseforge\minecraft\Instances\Cobbleventure Development Test Pack\mods"
projects\cobbleventure-battle-ai\gradlew.bat -p projects\cobbleventure-pokefinder -Dcobbleventure.pokefinder.visualRegression=true runClient
```

`COBBLEVENTURE_INTEGRATION_MODS_DIR`에는 CobbleNav와 Cobbleventure World
Bootstrap을 포함한 개발 모드팩의 `mods` 디렉터리를 지정한다. Gradle의
`integration_mods_dir` 프로젝트 속성으로도 같은 값을 지정할 수 있다.
PowerShell이 `-D` 인수를 변형하는 환경에서는 위 명령을 `cmd`에서 실행한다.

포켓파인더를 주손과 보조손에 번갈아 들고 Minecraft의 GUI 배율을 1, 2, 3,
4로 바꿔 레이더 경계와 아이콘 정렬을 확인한다. 플레이어를 회전하면 모든
마커가 포켓몬 점과 같은 방향으로 회전해야 한다.

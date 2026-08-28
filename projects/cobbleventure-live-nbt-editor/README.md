# Cobbleventure Live NBT Editor

기존 Structure Builder와 별개인 단일 NBT 편집 모드다. Content Studio가 월드의
`generated/cobbleventure_builder/live` 브리지에 선택한 NBT를 보내면 전용 편집 차원에는
항상 구조물 하나만 배치된다. 다른 NBT를 열면 게임에서 저장 여부를 확인하며, `저장 후
전환`, `저장하지 않고 전환`, `취소` 중 하나를 고를 수 있다. 웹은 수동 저장 결과를 원래
`content/structures` 파일에 반영한다.

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

현재 NBT는 `Ctrl+S` 또는 `/cobbleventure_live save`로 직접 저장한다. 게임 종료 시에는
자동 저장하지 않으므로, 종료 전에 필요한 변경을 명시적으로 저장해야 한다.

로그인하면 구형 건축팩과 같은 편집 막대기와 전용 블록 팔레트를 지급한다. 실제 문을
우클릭하면 door, 베리어를 우클릭하면 transition, 일반 블록을 우클릭하면 NPC 위치를
기록한다. 문·베리어·일반 블록은 자동 판별되므로 UI에서는 이름만 입력한다.
웅크린 채 블록을 좌클릭하면 해당 위치의 앵커를 삭제한다. Door는 주황, NPC는 청록,
Transition은 보라 입체 윤곽선과 이름표로 계속 표시되며 활성 NBT의 `.structure.json`에
저장되어 웹 저장 결과에도 반영된다.

던전 조각 NBT를 열면 `content/dungeon_pieces` 정의의 조우·보스·전리품·입출구 등
이벤트 마커도 종류별 색상의 입체 윤곽선과 이름표로 표시된다. 라이브 에디터에서
위치를 바꾸거나 마커를 추가·삭제한 뒤 저장하면 별도 구조물 메타데이터가 아니라 원래
던전 조각 JSON의 `markers` 목록에 반영된다.

라벨과 모드는 `/cobbleventure_live tool npc <label>`, `tool door <label>`,
`tool transition <label>`, `tool arrival <label>`, `tool mode <mode>`로 지정하고,
`/cobbleventure_live anchor list|show`로 확인한다.

로그인 시 `cobbleventure_bootstrap:excavation_marker`를 64개까지 자동 보충한다.
보이지 않거나 다시 필요하면 `/cobbleventure_live palette`로 편집 팔레트를 재지급한다.
이 마커를 흙·산을 제거할 공간에 채우면 NBT에는 마커로 보존되고, 실제 월드와 테스트
차원 배치 때 해당 위치만 공기로 치환된다. 일반 NBT 공기는 기존 지형을 지우지 않는다.

Playing Cards 포커 테이블은 NBT 배치 시 시스템 소유자 정보를 자동으로 보정하고 블록
엔티티를 동기화한다. 이전 버전에서 소유자 없이 배치된 테이블도 플레이어 로그인 때
복구하므로 테이블 위 카드·칩 외형이 다시 표시된다.

Create 엘리베이터 객실 엔티티와 Super Glue를 포함한 구조물 엔티티도 저장 범위에서
명시적으로 수집한다. 배치할 때 Anchor, 접촉 높이, Actor 위치, 컨트롤러 상대 좌표를
새 편집·테스트 차원 좌표로 옮기므로 조립된 엘리베이터가 NBT와 함께 보존된다.

편집·테스트 차원은 구조물 바닥 높이 바로 아래에 밝은 회색 작업 바닥을 제공한다. NBT를
교체할 때 이전 검정·노랑 편집 경계는 작업 바닥으로 복원한다. 업데이트 전 경계를 잔디
블록으로 바꾸던 버전에서 누적된 잔디·흙 모양 테두리도 이미 로드된 청크에서 정리한다.
기존 월드의 작업 바닥은 현재 NBT 주변만 갱신해 종료 시 불필요한 대량 청크 저장을 막는다.

웹 Content Studio와 게임을 오갈 때 편집 월드가 멈추지 않도록 클라이언트 시작 시
`pauseOnLostFocus`를 자동으로 끈다. Esc 메뉴처럼 직접 일시정지 화면을 연 경우는
Minecraft의 기존 동작을 유지한다.

현재 활성 NBT 영역은 편집 차원에서 청록색 3D 와이어프레임으로 항상 표시하고 HUD에
NBT ID와 크기를 함께 보여 준다. `V`를 누른 뒤 `WorldEdit 영역 선택`을 선택하면
원점부터 크기 끝 좌표까지 WorldEdit cuboid 선택 영역으로 즉시 등록한다. 명령으로는
`/cobbleventure_live worldedit select`를 사용할 수 있다.

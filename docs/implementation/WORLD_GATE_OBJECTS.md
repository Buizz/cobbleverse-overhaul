# 월드맵 오브젝트: 관문과 예약형 장소

> 구현 상태: 월드맵 배치·검증, 벽/배리어/NBT 생성, 플레이어별 통과 조건 판정 구현

월드맵 `objects` 레이어의 첫 실제 오브젝트 타입은 `gate`다. 관문은 지정한 육각
타일의 중심을 가로지르는 두꺼운 벽을 만들고, 벽 위는 배리어 블록으로 막는다. 중앙
통로와 관문 건물은 지정한 구조물 NBT로 표현한다.

중앙 통로는 플레이어마다 조건 결과가 다를 수 있으므로 블록으로 영구 폐쇄하지 않는다.
서버가 통로의 관문 선분 횡단을 감지하고 조건을 만족하지 않은 플레이어만 진입 전
방향으로 되돌린다. 벽의 나머지 부분과 상부 배리어는 모든 플레이어에게 동일하다.

```json
{
  "id": "route_01_gate",
  "type": "gate",
  "anchor": { "q": 1, "r": -1 },
  "resource": "cobbleventure:gate/route_01",
  "rotation": 1,
  "properties": {
    "facing": "east",
    "center_placement": "gate_npc",
    "surrounding_type": "natural",
    "wall_block": "minecraft:stone_bricks",
    "tree_log": "minecraft:oak_log",
    "tree_leaves": "minecraft:oak_leaves",
    "wall_thickness": 5,
    "wall_height": 7,
    "passage_width": 7,
    "barrier_height": 24,
    "condition_mode": "all",
    "conditions": [
      {
        "type": "variable",
        "source": "scoreboard",
        "key": "badge_count",
        "operator": ">=",
        "value": 2
      },
      { "type": "item", "item": "minecraft:paper", "count": 1 },
      { "type": "pokemon", "species": "cobblemon:pikachu" }
    ],
    "npc": "easy_npc:preset/encounter/gatekeeper.npc.snbt",
    "deny_dialog": "greeting",
    "deny_message": "배지 두 개가 필요합니다."
  }
}
```

`facing`이 `north` 또는 `south`면 벽은 동서 방향(ㅡ)으로 놓이고, `east` 또는
`west`면 남북 방향(ㅣ)으로 놓인다. `rotation`은 NBT만 0°, 90°, 180°, 270°로
회전한다. 벽 두께와 통로 폭은 중심 정렬을 위해 홀수만 허용한다.

`center_placement`는 가운데 배치물을 `gate`, `gate_npc`, `npc` 중에서 선택한다.
`gate_npc` 구조물은 구조물 메타데이터의 `npc_position` 앵커 `npc1`, `npc2`를
필수로 사용한다. 양방향에서 같은 관문지기와 자연스럽게 대화할 수 있도록 각 앵커에
NPC를 한 명씩 배치하며, 구조물 중심 좌표에는 NPC를 생성하지 않는다. 이미 생성된
관문도 다음 로드에서 기존 중심 NPC를 두 앵커 위치로 한 번 정리한다.
`surrounding_type`은 `wall` 또는 주변 이동 불가 지형을 잇는 `natural`을 사용한다.
`natural`은 관문의 진행 방향 앞뒤로 벽을 뻗지 않는다. 대신 관문 좌우의 이동 불가
지형이 육각형 외곽에서는 넓고 중앙 통로 옆에서는 한 점에 가깝게 좁아지는 두 개의
쐐기형 자연지물로 이어진다. 나무·바위·지면 장식을 먼저 온전하게 생성한 뒤,
자연지물 사이에 남은 교체 가능한 공간을 보이지 않는 배리어로 밀봉한다.
수관과 바위 일부는 경계 밖으로 튀어나올 수 있지만 통과 가능한 틈은 생기지 않는다.
기본 숲은 짙은 참나무, 우거진 숲은 가문비나무를 사용하며 일반 배경 숲보다 나무
후보를 촘촘하게 배치한다.
기존 월드의 NPC 단독 자연 관문도 생성 버전이 바뀌면
다음 서버 로드에서 한 번 새 형태로 갱신된다.

관문 조건은 [공용 플레이어 조건 규격](PLAYER_CONDITIONS.md)을 그대로 사용한다.
관문 전용 조건 타입이나 판정 의미를 추가하면 안 된다. 빈 `all` 조건 배열은 제한 없는
관문이며, `any`이면 하나 이상의 조건을 만족해야 한다.

NPC가 없는 차단 관문의 최소 예시는 다음과 같다.

```json
{
  "id": "story_lock",
  "type": "gate",
  "anchor": { "q": 5, "r": -1 },
  "rotation": 0,
  "properties": {
    "facing": "north",
    "center_placement": "gate",
    "surrounding_type": "wall",
    "wall_block": "minecraft:stone_bricks",
    "tree_log": "minecraft:oak_log",
    "tree_leaves": "minecraft:oak_leaves",
    "wall_thickness": 5,
    "wall_height": 7,
    "passage_width": 7,
    "barrier_height": 24,
    "condition_mode": "all",
    "conditions": [{
      "type": "variable",
      "source": "scoreboard",
      "key": "badge_count",
      "operator": ">=",
      "value": 2
    }],
    "deny_message": "배지 두 개를 얻기 전에는 들어갈 수 없습니다."
  }
}
```

## NPC 프리셋 연동

관문 화면은 NPC의 외형이나 대화를 복제하지 않는다. NPC 편집기에서 역할을
`gatekeeper`(관문지키미)로 지정하고 대화·분기·행동을 구성한 뒤, 월드맵 관문의
`npc`가 생성된 EasyNPC 프리셋을 참조한다.

조건을 만족하지 못한 플레이어가 통로를 건너면 시스템은 현재 통로 바닥에서 플레이어를
잠시 멈추고, 가까운 관문지기의 CVES V5 대화를 자동으로 연다. 대화가 완전히 닫힌 뒤에만
들어온 쪽 진입로로 물러나게 한다. 높이 판정은 구조물 지붕을 포함하는 높이맵이 아니라
플레이어가 서 있던 통로층과 `road_anchor`를 기준으로 한다. NPC를 찾지 못했거나 CVES
대화를 열지 못한 경우에만 `deny_message`를 액션바에 표시한다. NPC 외형과 대화 본문은
NPC 콘텐츠에서 관리한다.

NPC 이벤트에는 `teleport_to_gate` 액션을 넣을 수 있다. 좌표 대신 월드맵 관문 ID를
참조하므로 관문을 다른 타일로 옮겨도 NPC 이벤트를 수정할 필요가 없다.

```json
{
  "type": "teleport_to_gate",
  "gate": "route_01_gate",
  "subject": "player",
  "side": "front"
}
```

- `subject`: 대화 중인 `player` 또는 프리셋의 `npc`
- `side`: 관문이 바라보는 쪽인 `front`, 반대편 `back`, 건물 중심 `center`

EasyNPC 생성기는 이 액션을 `/cobbleventure_gate teleport` 명령으로 변환한다.
`front`와 `back`은 해당 방향의 `road_anchor` 바깥 통로 바닥으로 이동하며, `center`도
관문 내부의 실제 보행 가능 높이를 탐색한다. 따라서 지붕 높이로 순간이동하지 않는다.
따라서 제지 대화 뒤 플레이어를 관문 앞으로 돌려보내거나, NPC를 관문 중앙으로
복귀시키는 연출을 기존 NPC 이벤트 화면에서 구성할 수 있다.

## 빌런기지 예약 타입

`villain_base`는 세부 동작을 정하기 전 월드맵 위치와 NBT만 먼저 관리하는 예약형
오브젝트다. 콘텐츠 매니저에서 기존 NBT 구조물 목록을 선택할 수 있으며 ID, 위치,
리소스, 회전을 저장한다.

```json
{
  "id": "rocket_hideout",
  "type": "villain_base",
  "anchor": { "q": 3, "r": -1 },
  "resource": "cobbleventure:villain_base/rocket_hideout",
  "rotation": 2
}
```

현재 런타임은 빌런기지 NBT를 자동 생성하지 않는다. 추후 진입 조건, 내부 공간,
조무래기와 보스 NPC, 점령 상태가 정해지면 `properties`와 전용 런타임을 확장한다.

## 전설 포켓몬 장소 예약 타입

`legendary_site`도 빌런기지와 동일하게 위치와 NBT만 먼저 기록한다. 아직 특정
포켓몬 ID, 출현 조건, 일회성 포획 상태나 등장 연출은 저장하지 않는다.

```json
{
  "id": "sea_guardian_shrine",
  "type": "legendary_site",
  "anchor": { "q": 4, "r": -1 },
  "resource": "cobbleventure:legendary_site/sea_guardian_shrine",
  "rotation": 1
}
```

콘텐츠 매니저에서는 기존 NBT 목록에서 구조물을 선택하고 지도에서 별 모양 마커로
구분한다. 런타임 자동 생성과 전설 포켓몬 이벤트는 세부 기획 후 별도로 연결한다.

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
    "gate_mode": "classic",
    "building_enabled": true,
    "surrounding_type": "trees",
    "wall_block": "minecraft:stone_bricks",
    "tree_log": "minecraft:oak_log",
    "tree_leaves": "minecraft:oak_leaves",
    "wall_thickness": 5,
    "wall_height": 7,
    "opening_width": 7,
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
    "deny_message": "배지 두 개가 필요합니다."
  }
}
```

`facing`이 `north` 또는 `south`면 벽은 동서 방향(ㅡ)으로 놓이고, `east` 또는
`west`면 남북 방향(ㅣ)으로 놓인다. `rotation`은 NBT만 0°, 90°, 180°, 270°로
회전한다. 벽 두께와 통로 폭은 중심 정렬을 위해 홀수만 허용한다.

`gate_mode`는 관문의 제지 방식을 결정한다.

- `classic`: 중앙 NBT 건물과 주변 차단물을 만들고 관문 통로 횡단을 판정한다.
- `npc_only`: 지형이나 건물을 생성하지 않고 NPC만 배치한다. 제지는 NPC의
  `proximity` 이벤트와 `teleport_to_gate` 액션으로 처리한다.
- `system_only`: 건물·주변 차단물·NPC를 생성하지 않는다. 조건 미충족 플레이어가
  관문 육각 타일에 들어오면 이전 위치로 되돌리고 `deny_message`를 액션바에 표시한다.

`center_placement`는 가운데 배치물을 `gate`, `gate_npc`, `npc` 중에서 선택한다.
`surrounding_type`은 `wall` 또는 주변 이동 불가 지형을 잇는 `natural`을 사용한다.

관문 조건은 [공용 플레이어 조건 규격](PLAYER_CONDITIONS.md)을 그대로 사용한다.
관문 전용 조건 타입이나 판정 의미를 추가하면 안 된다. 빈 `all` 조건 배열은 제한 없는
관문이며, `any`이면 하나 이상의 조건을 만족해야 한다.

시스템 차단 관문의 최소 예시는 다음과 같다.

```json
{
  "id": "story_lock",
  "type": "gate",
  "anchor": { "q": 5, "r": -1 },
  "rotation": 0,
  "properties": {
    "facing": "north",
    "gate_mode": "system_only",
    "building_enabled": false,
    "surrounding_type": "none",
    "wall_block": "minecraft:stone_bricks",
    "tree_log": "minecraft:oak_log",
    "tree_leaves": "minecraft:oak_leaves",
    "wall_thickness": 5,
    "wall_height": 7,
    "opening_width": 7,
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

`npc_only`의 자연스러운 제지 연출은 NPC 이벤트의 트리거를 `proximity`로 두고,
조건 불충족 대화 뒤 `teleport_to_gate`를 실행하도록 구성한다. NPC 외형·대화·트리거는
관문 화면에 복제하지 않는다. 시스템 안전망이 필요하면 `system_only`를 사용한다.

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

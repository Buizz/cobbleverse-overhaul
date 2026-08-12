# 월드맵 조건부 관문 오브젝트

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
    "wall_block": "minecraft:stone_bricks",
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

조건은 다음 세 종류를 지원한다.

- `variable`: `scoreboard` 점수 또는 플레이어 `persistent_data` 숫자를 비교한다.
- `item`: 일반 플레이어 인벤토리에 지정 아이템이 수량 이상 있는지 확인한다.
- `pokemon`: 현재 파티에 지정 종이 있는지 확인한다.

빈 조건 배열은 제한 없는 관문이다. `condition_mode`가 `all`이면 모든 조건을,
`any`이면 하나 이상의 조건을 만족해야 한다. `npc`를 생략하면 관문 건물만 생성한다.

## NPC 프리셋 연동

관문 화면은 NPC의 외형이나 대화를 복제하지 않는다. NPC 편집기에서 역할을
`gatekeeper`(관문지키미)로 지정하고 대화·분기·행동을 구성한 뒤, 월드맵 관문의
`npc`가 생성된 EasyNPC 프리셋을 참조한다.

자연스러운 제지 연출은 NPC 이벤트의 트리거를 `proximity`로 두고, 조건 불충족
대화 뒤 `teleport_to_gate`를 실행하도록 구성한다. 관문 런타임의 조건 판정은
대화가 발동하지 않거나 플레이어가 NPC 범위를 우회하는 경우를 막는 안전망이며,
NPC 외형·대화·트리거를 따로 복제하지 않는다.

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

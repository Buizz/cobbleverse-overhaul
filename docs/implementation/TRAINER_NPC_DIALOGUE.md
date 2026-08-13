# NPC 이벤트 스크립트와 배틀 프리셋

## 책임 분리

NPC 문서는 외형, 이동 행동과 이벤트 스크립트만 소유한다. 포켓몬 팀, AI, 전투
규칙, 특수 기믹과 전투 가방은 독립 배틀 프리셋이 소유한다.

```text
NPC event script                      Battle preset
├─ trigger                            ├─ trainer_id / format
├─ dialogue / choices                 ├─ AI / level mode / rules
├─ branch / label / goto              ├─ mechanics / bag
├─ item / money / loot reward         └─ Pokémon team
└─ start_battle ── battle ID ────────▶
```

전투가 없는 상인·안내자 NPC에는 `start_battle` 명령이 필요 없다. 배틀 프리셋은
NPC의 대화, 진행 플래그와 보상을 알지 못한다.

## 시작 트리거

조우 방식과 거리는 `npc.behavior`가 아니라 각 이벤트의 `trigger`에서 설정한다.

- `interact`: 플레이어가 NPC에게 말을 걸 때 실행한다. `range`는 대화 가능 거리다.
- `proximity`: 플레이어가 `range` 안으로 들어올 때 자동 실행한다.
- `warning_offset`: proximity 전용 경고 여유 거리이며 기본값은 `2`다.

예를 들어 proximity 발동 거리가 4이고 경고 여유 거리가 +2라면 4~6 블록 구간에서
경고한다. 말 걸기 이벤트에는 자동 조우 거리나 경고 거리 설정이 존재하지 않는다.

EasyNPC 7.x 거리 이벤트는 4·8·16·32 블록 단계이므로 생성 프리셋에서는 가장
가까운 이벤트로 변환한다. 원본 `range + warning_offset` 값은 전용 런타임이 정확히
처리할 수 있도록 보존한다.

## 이벤트 명령

이벤트는 RPG Maker의 이벤트 명령이나 StarCraft 트리거처럼 `commands` 배열을
위에서 아래로 실행한다.

- `branch`: 플래그·아이템 조건을 검사하고 지정 라벨로 이동
- `label`: 분기, 선택지와 배틀 결과가 이동할 위치
- `dialogue`: 대사 표시
- `choices`: 선택지별 이동 라벨 지정
- `goto`: 지정 라벨로 이동
- `start_battle`: 배틀 프리셋을 시작하고 승리·패배·취소 라벨 지정
- `set_flag`: 진행 플래그 변경
- `give_money`: 고정 금액 또는 현재 레벨캡 배율로 돈 지급
- `give_item`: 고정 아이템 지급
- `grant_loot`: 확률형 Minecraft 루트 테이블 실행
- `grant_field_move`: 대화 중인 플레이어에게 지정한 비전머신 권한을 영구 해금
- `end`: 이벤트 종료

`grant_field_move`의 `move`에는 `surf`, `fly`, `flash`, `defog`, `rock_climb`,
`waterfall`, `whirlpool`, `strength` 중 하나를 사용한다. 이 이벤트가 있는 NPC를
마을의 `npc_placement.trainer_slots[].members[].npc_profile`에 배치하면 플레이어 메뉴의
지도 마을 설명에 `NPC 정보`와 `파도타기를 주는 NPC` 형식의 안내가 자동으로 표시된다.

이벤트 명령의 순서 자체가 실행 순서이므로 별도 시작 대화 라우팅 테이블은 사용하지
않는다. 조건에 따른 첫 대화 변경은 이벤트 앞부분에 `branch` 명령을 배치해 표현한다.

## 테스트 데이터

- NPC 이벤트: `content/source/examples/ai_test.json`
- 배틀 프리셋: `content/battles/examples/ai_test.json`

EasyNPC 프리셋 생성:

```powershell
python tools/content-manager/generate_easy_npc_presets.py
```

게임 내 테스트 소환:

```mcfunction
/easy_npc preset import_new data easy_npc:preset/encounter/ai_test.npc.snbt ~ ~ ~
```

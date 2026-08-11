# NPC 대화·트레이너 조우·보상 설계

## 현재 테스트 단위

`content/source/examples/ai_test.json`은 NPC 외형과 행동, 조건부 시작 대화,
선택지와 보상 액션을 담는다. 실제 팀과 AI 설정은
`content/battles/examples/ai_test.json` 배틀 프리셋으로 분리한다.

```text
NPC entry_routes → 조건에 맞는 시작 대화 → 선택지
                                      └→ start_battle(battle ID)
                                          → 결과 노드 → 보상 액션
```

EasyNPC 프리셋은 다음 명령으로 생성한다.

```powershell
python tools/content-manager/generate_easy_npc_presets.py
```

게임에서는 아래 명령으로 테스트 NPC를 플레이어 위치에 생성한다.

```mcfunction
/easy_npc preset import_new data cobbleventure:encounter/ai_test ~ ~ ~
```

## 조건부 시작 대화

`interaction.entry_routes`를 위에서 아래로 평가하고, 처음 통과한 경로의 `entry`
노드에서 대화를 시작한다. 마지막 항목은 반드시 조건이 없는 기본 경로여야 한다.
따라서 승리 여부, 보유 아이템 등에 따라 같은 NPC도 첫 대화를 다르게 시작할 수 있다.

시작 경로, 대화 노드와 선택지는 동일한 `conditions` 배열을 사용한다. 현재 기준
데이터가 지원하는 최소 조건은 다음과 같다.

- `flag_equals`: 플레이어별 진행 플래그 값 비교
- `has_item`: 인벤토리에 지정 아이템과 수량이 있는지 확인
- `always`: 무조건 통과

EasyNPC 어댑터는 진행 플래그를 길이 16 이하의 결정론적 scoreboard objective로
변환한다. `has_item`은 EasyNPC 7.x의 `HAS_ITEM_IN_INVENTORY` 조건으로 변환한다.

## 보상

보상은 NPC 상호작용 그래프의 결과 액션 노드가 소유한다. `give_money`는 `fixed`와
`level_cap_multiplier` 중 하나를 사용한다. 고정 상금은 즉시 점수판에 더하고,
레벨캡 배율 방식은 현재 레벨캡 점수와 정수 배율을 임시 점수판에서 계산한 뒤
화폐 점수판에 더한다.

상품 액션은 다음 두 방식을 사용한다.

- `give_item`: 아이템 ID와 수량을 직접 지정
- `grant_loot`: Minecraft 루트 테이블 ID를 지정해 가중치·조건·함수를 재사용

확률 상품은 번들 안에 중복 작성하지 않는다. 예제는
`cobbleventure:trainer/ai_test_rewards` 루트 테이블을 사용한다.

## 조우 방식과 거리 경고

`npc.behavior.encounter.mode`은 두 값을 사용한다.

- `interaction`: 플레이어가 NPC에게 말을 걸어야 대화와 전투가 시작된다.
- `proximity`: 플레이어가 `trigger_range`에 들어오면 자동으로 전투를 시작한다.

`warning_range.min`과 `warning_range.max`는 화면 경고를 표시할 거리 띠다. 예를 들어
발동 거리가 4칸이면 `min: 4`, `max: 6`으로 4~6칸 구간에 트레이너 경고를 표시한다.

EasyNPC 7.x의 기본 거리 이벤트는 4·8·16·32칸으로 고정되어 정확한 4~6칸 띠를
표현하지 못한다. EasyNPC 어댑터는 프로토타입에서 가장 가까운 이벤트를 사용하고,
정식 구현은 클라이언트 HUD와 서버 권위 거리 판정을 가진 Cobbleventure 런타임
어댑터가 원본의 정확한 범위를 처리한다.

## 일반 NPC와 배틀 프리셋

스키마 3은 NPC를 기준 엔티티로 두고 배틀을 독립 프리셋으로 분리한다. NPC는
`start_battle.battle` 액션이 있을 때만 해당 배틀 프리셋을 참조한다.

```text
NPC 프리셋
├─ appearance / behavior
├─ interaction.entry_routes / nodes
└─ actions
   ├─ give_item / grant_loot / give_money
   └─ start_battle → Battle preset (선택)
                       └─ team / AI / rules / bag
```

상인·안내자처럼 전투가 없는 NPC도 같은 대화 편집기를 사용한다. 승리·패배·취소
후 이동할 결과 노드는 `start_battle.results`에서 각각 지정하며, 돈과 상품은 그 결과
노드의 액션으로 실행한다. 배틀 프리셋은 NPC 대화나 보상을 알지 못한다.

# 길 조우 방식별 포켓몬 풀

길 프리셋의 `pokemon_spawns`는 기존 필드를 일반 육상 조우로 유지하고,
`encounter_pools`에 조우 방식별 전용 풀을 선택적으로 저장한다. 기존 길 JSON은
수정 없이 계속 동작한다.

지원 방식은 다음과 같다.

| 키 | 용도 |
| --- | --- |
| `surf` | 파도타기 중 조우 |
| `old_rod` | 낡은낚싯대 |
| `good_rod` | 좋은낚싯대 |
| `super_rod` | 대단한낚싯대 |
| `headbutt` | 나무 박치기 |

```json
{
  "pokemon_spawns": {
    "inherit_biome": true,
    "excluded_species": [],
    "additions": [],
    "level_overrides": [],
    "encounter_pools": {
      "old_rod": {
        "enabled": true,
        "inherit_biome": false,
        "excluded_species": [],
        "additions": [
          {
            "species": "cobblemon:magikarp",
            "min_level": 5,
            "max_level": 10,
            "weight": 70,
            "spawn_as_evolved": false
          }
        ],
        "level_overrides": [],
        "trigger_chance": 0.65
      }
    }
  }
}
```

- `trigger_chance`는 0~1 범위의 실제 발동 확률이다.
- `weight`는 같은 풀 안에서의 상대 출현 가중치이며 기본값은 1이다.
- 각 방식의 `enabled`가 꺼져 있으면 해당 방식의 전용 조우를 발생시키지 않는다.
- 웹 에디터에서는 일반 조우, 파도타기, 낚싯대 3종, 박치기를 탭으로 전환해
  독립적으로 편집한다.

서버 연동 코드는 `CobbleventureAdventure.authoredEncounterRule(...)`에 좌표와
`AdventureWorldContext.WildEncounterMethod`를 전달해 현재 길의 풀을 조회한다.
반환된 규칙의 `enabled`, `triggerChance`, 가중치와 레벨 범위를 사용해 실제
낚시 또는 박치기 상호작용을 발생시킬 수 있다. 일반 자연 스폰은 기존과 같이
`LAND` 풀을 사용한다.

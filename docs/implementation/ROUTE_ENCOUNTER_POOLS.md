# 길 조우 방식별 포켓몬 풀

> 실제 작업 순서, 바이옴 기본 풀과의 우선순위, `encounter_cells` 및 레벨 설정은
> [포켓몬 서식지·출현 설정 작업 지침](POKEMON_ENCOUNTER_AUTHORING.md)을 우선한다.

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
- 종별 레벨을 고정하려면 `additions`와 `level_overrides`에 같은 범위를 함께
  작성한다. 현재 런타임은 `level_overrides`가 없으면 월드 셀 평균 레벨을 사용한다.
- 각 방식의 `enabled`가 꺼져 있으면 해당 방식의 전용 조우를 발생시키지 않는다.
- 웹 에디터에서는 일반 조우, 파도타기, 낚싯대 3종, 박치기를 탭으로 전환해
  독립적으로 편집한다.

## 인게임 동작

- Cobblemon 낚시 성공 시 찌와 전투 연출은 유지하고, 현재 길의 낚싯대 풀로
  포켓몬·출현 레벨을 결정한다.
- `poke_rod`는 낡은낚싯대, `great_rod`는 좋은낚싯대,
  `ultra_rod`·`master_rod`는 대단한낚싯대 풀을 사용한다. 그 밖의 테마 낚싯대는
  기본적으로 낡은낚싯대 풀을 사용한다.
- 박치기는 생존한 파티 포켓몬이 `headbutt`를 배우고 있을 때
  **웅크리기 + 빈손으로 통나무 우클릭**으로 사용한다.
- 박치기 성공 시 나무 타격음과 파편 연출 뒤 포켓몬이 가까운 안전한 공간에
  나타나 즉시 야생 전투가 시작된다. 플레이어와 나무에는 각각 재사용 대기시간이
  적용된다.
- 현재 좌표에 해당 방식의 전용 풀이 없으면 Cobblemon의 기본 낚시를 건드리지
  않으며, 박치기 상호작용도 소비하지 않는다.

서버 연동은 `CobbleventureAdventure.authoredEncounterRule(...)`에 좌표와
`AdventureWorldContext.WildEncounterMethod`를 전달해 현재 길의 풀을 조회한다.
일반 자연 스폰은 기존과 같이 `LAND` 풀을 사용한다.

길 중심과 떨어진 강·바다 또는 도시 내부 수면에 이 풀을 적용하려면
`content/worlds/<generation>.json`의 해당 connection에 실제 수면 육각 좌표를
`encounter_cells`로 등록해야 한다.

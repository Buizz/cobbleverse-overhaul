# 포켓몬 서식지·출현 설정 작업 지침

이 문서는 포켓몬 출현을 수정할 때 어느 데이터를 고쳐야 하는지 결정하는 기준이다.
“특정 장소에서 다른 포켓몬이 나온다”는 문제를 풀 하나의 문제로 보지 않고
**좌표 소유권, 조우 방식, 후보 풀, 레벨**의 네 항목으로 나누어 확인한다.

## 1. 먼저 구분할 네 가지

| 항목 | 확인할 질문 | 주요 데이터 |
|---|---|---|
| 좌표 소유권 | 이 블록 좌표는 어느 길·숲·동굴·던전에 속하는가? | 월드 `cells`, `encounter_cells`, 각 공간의 bounds |
| 조우 방식 | 육상, 파도타기, 낚시, 박치기 중 무엇인가? | `pokemon_spawns`, `encounter_pools` |
| 후보 풀 | 바이옴 기본 후보를 섞을 것인가, 전용 풀로 교체할 것인가? | `inherit_biome`, `excluded_species`, `additions` |
| 레벨 | 종별 고정 범위인가, 지역 평균 레벨을 따를 것인가? | `level_overrides`, 월드 `level_overrides`, 공간 기본 레벨 |

블록을 물로 바꾸거나 바이옴을 강·바다로 바꾸는 것만으로는 본가식 수면·낚시
엔트리가 만들어지지 않는다. 반대로 출현 풀만 작성해도 해당 좌표가 그 풀의
소유 영역에 포함되지 않으면 적용되지 않는다.

## 2. 출현 데이터의 층

### 2.1 바이옴 기본 포켓몬

다음 데이터가 세대 월드의 기본 후보를 만든다.

- `content/catalogs/pokemon-habitats.json`: 종의 지역도감, 서식지, 희귀도,
  전설·환상 여부
- `content/catalogs/biome-profiles.json`: Minecraft 바이옴을 평원·숲·담수·바다
  등의 서식지 프로필에 연결하고 강제 포함·제외 적용
- Cobblemon 원본 스폰 규칙: 지상·수중 위치, 시간, 날씨, 광량, 블록 조건과
  원본 가중치

좌표에 더 구체적인 규칙이 없으면 이 기본 풀이 사용된다. 따라서 명시적 지역에서
“기존 바이옴별 포켓몬이 나온다”면 가장 먼저 전용 풀의 `inherit_biome`과 좌표
소유권을 확인한다.

### 2.2 길과 길 주변 수면

- 풀: `content/routes/generation_1/<route>.json`
- 배치와 적용 셀:
  `content/worlds/generation_1.json`의 해당 connection

일반 육상 풀은 `pokemon_spawns`의 바로 아래 필드를 사용한다.

| 필드 | 의미 |
|---|---|
| `inherit_biome` | 바이옴 기본 후보를 유지할지 여부 |
| `excluded_species` | 상속한 기본 후보에서 제외할 종 |
| `additions` | 길에서 추가하거나 전용 풀로 사용할 종과 상대 가중치 |
| `level_overrides` | 실제 종별 출현 레벨 범위 |

일반 길 영역은 월드 connection의 `cells`로 소유한다. 강, 바다, 도시 안 수로처럼
길의 중심선과 다른 셀에 파도타기·낚시 풀을 적용하려면 `encounter_cells`에도
그 셀을 명시한다.

- 특수 방식은 `encounter_cells`를 우선 조회하므로 도시 셀의 강에도 적용할 수 있다.
- 여러 길이 같은 셀을 소유하면 `surface_style: water`인 길이 우선한다.
- `encounter_cells`에 없는 인접 물은 해당 길의 낚시·파도타기 풀을 사용하지 않는다.

### 2.3 숲과 동굴

- 숲: `content/forests/<generation>/<forest>.json`
- 동굴: `content/caves/<generation>/<cave>.json`
- 설정: 각 파일의 `random_encounters`

숲·동굴은 전용 추적 조우 시스템이 소유하며 같은 공간에서 Cobblemon 자연 스폰을
중복시키지 않는다. `pokemon_biome`과 `inherit_biome`으로 기본 후보를 정하고,
`additions`, `excluded_species`, `level_overrides`로 덮어쓴다.

숲·동굴에서 `additions`에 적은 `min_level`과 `max_level`만으로는 종별 레벨이
고정되지 않는다. 실제 우선순위는 다음과 같다.

1. 같은 종의 `level_overrides`
2. 공간 `random_encounters.minimum_level/maximum_level`

따라서 본가 엔트리처럼 종마다 레벨이 다르면 반드시 `level_overrides`를 함께 쓴다.

### 2.4 던전

- 파일: `content/dungeons/<generation>/<dungeon>.json`
- 설정: `random_encounters`

던전 실행 중에는 던전 풀이 최우선이며 바이옴 자연 스폰은 차단된다. 던전의
`random_encounters.additions`는 종, `min_level`, `max_level`, `weight`를
직접 가진다. 길의 `pokemon_spawns`나 바이옴 프로필을 수정해서 던전 출현을
고치지 않는다.

## 3. 조우 방식별 독립 풀

길의 `pokemon_spawns.encounter_pools`에서 다음 키를 각각 설정한다.

| 키 | 실제 동작 |
|---|---|
| `surf` | 물속에서 발생한 자연 스폰과 파도타기 후보 |
| `old_rod` | 낡은낚싯대 계열 |
| `good_rod` | 좋은낚싯대 계열 |
| `super_rod` | 대단한낚싯대 계열 |
| `headbutt` | 박치기 가능한 통나무 |

한 풀을 작성해도 다른 방식으로 복사되거나 상속되지 않는다. 바다를 완성하려면 보통
`surf`와 낚싯대 세 종류를 모두 검토하고, 나무가 있는 길은 `headbutt` 필요 여부를
별도로 결정한다.

- 전용 풀이 아예 없으면 기본 Cobblemon 낚시를 유지하며 박치기 입력을 소비하지 않는다.
- 풀이 있고 `enabled: false`이면 해당 방식은 의도적으로 비활성화된다.
- `trigger_chance`는 조우 시도 성공 확률이고, `weight`는 성공한 풀 내부의 상대값이다.
- 박치기는 박치기를 배운 생존 포켓몬이 있을 때 웅크리기+빈손으로 통나무를
  우클릭하여 사용한다.
- 현재 박치기 런타임은 바이옴 후보를 섞지 않고 `headbutt.additions`만 뽑는다.
  따라서 `inherit_biome: true`여도 박치기 후보가 자동으로 채워지지 않으며,
  `additions`가 비어 있으면 아무것도 출현하지 않는다.

## 4. `inherit_biome` 결정표

| 의도 | 값 | 결과 |
|---|---:|---|
| 본가 도로·강·바다 엔트리를 그대로 재현 | `false` | `additions`만 후보가 됨 |
| 바이옴 기본 포켓몬에 지역 고유종을 추가 | `true` | 기본 후보 - 제외종 + 추가종 |
| 해당 방식에서 아무것도 나오지 않게 함 | `false` + 빈 `additions` | 빈 전용 풀 |

본가식 전용 지역을 설정할 때는 기본적으로 `false`를 사용한다. `true`는 혼합을
의도한 경우에만 사용하고, 바이옴 기본 포켓몬이 함께 나오는 것을 오류로 취급하지 않는다.

## 5. 레벨 규칙

길의 육상·파도타기·낚시·박치기 풀은 다음 순서로 레벨을 결정한다.

1. 해당 풀의 종별 `level_overrides`
2. `worlds/generation_1.json`의 현재 셀 평균 레벨 ±2
3. Cobblemon이 처음 만든 레벨

`additions.min_level/max_level`는 콘텐츠 표시와 검증에 필요하지만, 현재 런타임에서
종별 레벨 강제의 단일 기준은 `level_overrides`다. 그러므로 전용 엔트리는 같은
종과 범위를 두 배열에 모두 기록한다.

길 풀의 `level_overrides`에는 선택적으로 `level_weights`를 적을 수 있다.
예: `{"species":"cobblemon:pidgey","min_level":2,"max_level":5,"level_weights":{"2":10,"3":35,"4":4,"5":1}}`.
키는 범위 안의 정수 레벨 문자열, 값은 1~10000의 상대 가중치다. 이 설정이 있으면
명시된 레벨만 가중 추첨하고, 없으면 최소~최대 사이를 균등 추첨한다. 종을 뽑는
`additions.weight`와 그 종의 레벨을 뽑는 `level_weights`는 별개다.
웹에서 동일 범위를 다시 적용해도 가중치는 보존한다. 범위를 변경하면 기존 가중치를
해제한다는 안내 후 균등 추첨으로 전환한다. 가중치 자체는 JSON에서 편집한다.
이 옵션은 길의 육상·파도타기·낚시·박치기 풀에 적용하며 숲·동굴·던전에는 적용하지 않는다.

1번도로(`route_custom_03`)는 파이어레드의 슬롯별 확률을 사용한다.
구구와 꼬렛은 각각 50%이며 전체 조우 중 Lv.2는 20%, Lv.3은 70%, Lv.4는 9%,
Lv.5 구구는 1%다. 꼬렛은 Lv.4가 상한이다. 원본:
[pret/pokefirered wild_encounters.json의 MAP_ROUTE1](https://github.com/pret/pokefirered/blob/master/src/data/wild_encounters.json).
최소·최대만 맞아도 체감 레벨 분포가 원작과 같지는 않으므로 슬롯별 확률도 확인한다.

마을 셀은 육상 길 풀을 적용하지 않는다. 그곳의 바이옴 자연 출현은 월드 평균 ±2를
사용할 수 있으므로 길에서 나온 개체와 구분한다. 런타임의 `[Spawn diagnosis] Final wild level`
로그에서 좌표, 조우 방식, `speciesOverride`, `worldAverage`, 최종 레벨을 확인한다.
설정 변경 전에 이미 출현한 개체는 새 레벨로 자동 변경되지 않는다.

`spawn_as_evolved: true`는 낮은 레벨의 진화형을 원본 그대로 출현시킬 때 사용한다.
이를 빠뜨리면 런타임의 레벨 진화 정규화로 다른 진화 단계가 될 수 있다.

## 6. 길 전용 풀 작성 예시

```json
{
  "pokemon_spawns": {
    "inherit_biome": false,
    "excluded_species": [],
    "additions": [],
    "level_overrides": [],
    "encounter_pools": {
      "surf": {
        "enabled": true,
        "inherit_biome": false,
        "excluded_species": [],
        "additions": [
          {
            "species": "cobblemon:psyduck",
            "min_level": 20,
            "max_level": 30,
            "weight": 100
          }
        ],
        "level_overrides": [
          {
            "species": "cobblemon:psyduck",
            "min_level": 20,
            "max_level": 30
          }
        ],
        "trigger_chance": 1
      }
    }
  }
}
```

이 설정이 실제 강에 적용되려면 같은 route preset을 참조하는 월드 connection에 강의
육각 셀이 있어야 한다.

```json
{
  "id": "route_example",
  "route_preset": "cobbleventure:route/route_example",
  "cells": [{ "q": 1, "r": 2 }],
  "encounter_cells": [
    { "q": 2, "r": 2 },
    { "q": 2, "r": 3 }
  ]
}
```

## 7. 작업 절차

1. 요청한 장소가 길, 숲, 동굴, 던전 중 어디에 속하는지 찾는다.
2. 월드에서 실제 육각 셀과 Minecraft 바이옴을 확인한다.
3. 육상은 `cells`, 수면·낚시·박치기 확장 영역은 `encounter_cells` 포함 여부를 확인한다.
4. 육상, 파도타기, 낚싯대 3종, 박치기 중 필요한 방식을 모두 나열한다.
5. 혼합인지 전용인지 결정하고 `inherit_biome`을 명시한다.
6. 종, 가중치, 진화형 강제 여부를 `additions`에 작성한다.
7. 종별 레벨을 `level_overrides`에도 같은 범위로 작성한다.
8. 월드 평균 레벨과 스토리 진행 레벨이 충돌하지 않는지 확인한다.
9. 콘텐츠 전체 검증과 장소별 회귀 테스트를 실행한다.
10. 인게임에서 육상·물속·각 낚싯대·박치기를 각각 따로 확인한다.

## 8. 증상별 확인 순서

| 증상 | 먼저 확인할 것 |
|---|---|
| 바이옴별 기본 포켓몬이 섞임 | `inherit_biome`이 `false`인지 |
| 설정한 물 포켓몬이 전혀 안 나옴 | 월드 connection의 `encounter_cells`가 실제 물 셀을 포함하는지 |
| 육상 포켓몬이 물에서 나옴 | `surf` 풀이 있는지, 스폰 위치가 실제 물인지 |
| 낡은낚싯대만 맞고 나머지가 틀림 | `good_rod`, `super_rod`를 각각 작성했는지 |
| 박치기 입력이 동작하지 않음 | `headbutt` 풀 존재, 통나무, 빈손, 웅크리기, 기술 보유 여부 |
| 종은 맞지만 레벨이 지역 평균을 따름 | 해당 종의 `level_overrides` 누락 여부 |
| 진화형이 다른 단계로 바뀜 | `spawn_as_evolved: true` 누락 여부 |
| 던전에서 바이옴 포켓몬이 나옴 | 던전 `random_encounters`와 던전 소유 bounds 확인 |
| 인접 길의 풀이 적용됨 | 겹친 `cells`/`encounter_cells`, water 경로 우선순위 확인 |

### 지도 표시와 실제 조우를 함께 검증하기

- 웹 `world_pokemon_map`과 인게임 `MapContent`는 `encounter_cells` 우선,
  같은 영역 종류에서는 water 길 우선으로 소유 길을 고른다. 마을의 일반 육상
  풀은 유지하고 명시적 특수 조우 영역만 마을 셀을 덮어쓸 수 있다.
- 바다·강·해변 타일에 `surf` 풀이 있으면 지도 기본 목록은 파도타기다.
  인게임의 방식 전환 버튼과 웹 타일의 **방식별 출현**에서 낚싯대별 목록을
  별도로 확인한다. 비활성 풀은 0종이며, 다른 방식의 풀로 대체하지 않는다.
- 웹 포켓몬 출현처 검색에는 낚시·박치기만으로 출현하는 종도 포함한다.
  지도 표시용 목록을 원본 바이옴 후보에 덮어쓰지 않는다. 인게임의
  `spawnBiome`은 자연 스폰용 원본 후보를 계속 유지한다.
- 물 타일을 추가했으면 기존 `encounter_cells`와 좌표를 다시 대조한다.
  과거 셀 목록만 그대로 비교하는 테스트는 새 물 타일의 누락을 잡지 못한다.
- 지도는 표시 검증이며 실제 조우 검증을 대신하지 않는다. 콘텐츠와 지도 모듈을
  함께 빌드·반영한 뒤 인게임에서 각 방식으로 확인한다.

## 9. 검증 명령과 회귀 테스트

```powershell
cd tools/content-manager
python content_manager.py validate --root ..\.. --project content-projects\cobbleventure-main --json
python tests/test_route_encounter_pools.py
python tests/test_generation_one_wild_encounters.py
python tests/test_generation_one_firered_spawns.py
python tests/test_water_habitat_map.py
```

새 장소를 추가하거나 기존 장소를 분리하면 해당 좌표 소유권, `inherit_biome`,
조우 방식별 종·레벨·가중치를 고정하는 회귀 테스트도 함께 추가한다.

## 10. 관련 문서

- [길 조우 방식별 포켓몬 풀](ROUTE_ENCOUNTER_POOLS.md)
- [포켓몬 스폰 프로필](POKEMON_SPAWNS.md)
- [월드 서식지 탐색과 포켓몬 스폰 설계](../HABITAT_DISCOVERY_AND_SPAWNING.md)
- [바이옴 카탈로그와 지역 생성 체계](BIOME_REGION_SYSTEM.md)

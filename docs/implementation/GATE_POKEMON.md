# 이벤트 포켓몬 관문

관문의 `center_placement: "pokemon"`은 실제 Cobblemon 포켓몬을 사용하는 고정 길막이다.
NPC 프리셋과 건물 NBT는 사용하지 않으며, 주변 벽·자연지물 및 육각 타일 우회 방지는 기존 관문과 공유한다.
자연 출현 풀이나 서식지 설정과는 독립적이다. 기존 월드 복구·마이그레이션을 추가하지 않는다.

## 콘텐츠 작성

웹 월드맵의 관문 도구 또는 관문 속성에서 가운데 배치물을 **포켓몬**으로 선택한다.
종, 레벨, 자세, 배율, 충돌 크기, 상호작용 시작 조건, 직접 사용할 도구 ID, 완료 플래그를 설정한다.
`현재 충돌 폭에 통로 맞추기`는 충돌 폭 안에 들어가는 최대 홀수 통로 폭을 적용한다.
충돌 폭 × 배율이 통로 폭보다 작으면 저장을 거부한다.

관문 `properties` 예시:

```json
{
  "facing": "north",
  "center_placement": "pokemon",
  "surrounding_type": "natural",
  "wall_thickness": 5,
  "wall_height": 7,
  "passage_width": 3,
  "barrier_height": 24,
  "condition_mode": "all",
  "conditions": [],
  "deny_message": "잠든 포켓몬이 길을 막고 있습니다.",
  "pokemon": {
    "species": "cobblemon:snorlax",
    "level": 30,
    "pose": "sleep",
    "scale": 1,
    "collision": {"width": 3, "height": 2, "depth": 4},
    "completion_flag": "cobbleventure:flag/gate/route_snorlax_cleared",
    "activation_item": "cobbleventure_bootstrap:poke_flute",
    "activation_conditions": [
      {"type": "item", "item": "cobbleventure_bootstrap:poke_flute", "count": 1}
    ]
  }
}
```

`activation_item`을 지정하면 그 도구를 실제로 사용해야 한다. 조건에 아이템 소지만 지정하는 것과는 다르다.
생략하면 기존처럼 빈손 우클릭도 허용한다. 포켓몬 피리는 주변 관문 검색을 구현한 도구이며,
다른 아이템을 지정하는 것만으로 해당 아이템에 주변 검색 기능이 생기지는 않는다(들고 배우를 우클릭하는 것은 가능).
꼬지모는 `species: "cobblemon:sudowoodo"`, `pose: "stand"`로 작성하고 물뿌리개 획득 조건 등을 연결한다.
`sleep`은 설치된 모델의 Cobblemon `SLEEP` 자세를 사용한다. 잠만보 모델에는 눕는 수면 자세가 있지만 모든 종에 동일한 눕기 연출을 보장하지 않는다.
충돌은 애니메이션 메시 자동 추출이 아닌 작성한 직육면체이며 모델과 맞는 크기를 인게임에서 확인해야 한다.

## 상호작용과 해제

1. 완료하지 않은 플레이어는 몸체와 충돌하고, 우클릭으로 상호작용한다. `activation_item` 지정 시 사용한 아이템도 검사한다.
2. 공통 `PlayerConditions`로 `activation_conditions`를 모두 검사한다. 조건은 아이템을 소비하지 않는다.
3. 기본 동작은 해당 플레이어에게 1초간 서 있는 자세를 보여준 뒤 별도의 야생 포켓몬과 전투한다.
4. 해당 전투의 승리 또는 해당 포켓몬 포획만 완료 플래그를 설정한다. 패배·도주·시작 실패는 해제하지 않는다.
5. 완료 플래그와 기존 관문의 `conditions`가 모두 충족되면 해당 플레이어에게만 표시·충돌을 제거한다.

`completion_flag`를 생략하면 `cobbleventure:flag/gate/<관문ID>_cleared`를 사용한다.
일반 통과 조건은 **완료 후 추가 조건**, `activation_conditions`는 **깨우기 전 조건**이다.
완료 후 추가 조건이 부족해도 전투를 다시 시작시키지 않는다.
표시용 포켓몬은 밀기·목줄·일반 전투·직접 포획을 막으며, 전투용 포켓몬은 도전자 외의 전투 시작을 거부한다.

`pokemon.event_binding`을 지정하면 시작 조건 검사 후 기존 CVES 바인딩을 실행하며 기본 야생전투는 실행하지 않는다.
대화, 도구 사용, 아이템 소비 등 별도 연출이 필요할 때 사용한다. 이 경우 이벤트가 완료 플래그 설정을 책임진다.
기본 전투 흐름에 대화창은 포함하지 않는다.

## 1세대 갈색시티 잠만보와 포켓몬 피리

- `generation_1`의 `route_custom_14`(갈색시티–보라타운 길) 끝 타일 `(6, 6)`의 동쪽에
  `vermilion_east_snorlax`를 배치한다. Lv.30 잠만보, 수면 자세, 자연지물 관문이다.
- 보라타운 `rocket_pokemon_tower` 던전의 **첫 클리어 퇴장 보상**으로 피리 1개를 지급한다.
  기존 첫 클리어 아이템은 중첩 보상 테이블로 유지하고, 반복 클리어 보상에는 피리를 추가하지 않는다.
- 실제 아이템 ID는 `cobbleventure_bootstrap:poke_flute`다. 가방의 중요 도구로 분류하며
  기존 중요 아이템 보호 정책을 적용한다. 보상 지급 시 `cobbleventure:flag/story/poke_flute_received`를 기록한다.
  보스 처치 플래그와 분리하여 퇴장 전 조기 지급을 방지한다.
- 잠만보를 볼 수 있는 8블록 이내에서 가방의 **사용** 또는 피리를 든 우클릭으로 깨운다.
  피리는 소비하지 않는다. 소지하거나 가까이 가는 것만으로 전투를 시작하지 않는다.
- 피리 소리 → 1초간 깨어난 자세 → 야생전투 순서다. 승리 또는 포획해야
  `cobbleventure:flag/gate/vermilion_east_snorlax_cleared`가 설정된다. 도주·패배 후에는 다시 깨워야 한다.
  길 개방은 개인별이며 다른 플레이어의 잠만보는 남는다.
- 새 콘텐츠로 생성하는 월드 기준이며 기존 월드에 배치·보상을 소급하는 마이그레이션은 추가하지 않는다.

## 개인별 충돌·수명

표시 UUID, 월드 좌표 충돌 영역, 표시 여부, 자세를 서버가 플레이어별로 동기화한다.
클라이언트와 서버의 기본 이동 충돌 계산에 같은 영역을 더하므로 평상시에는 순간이동으로 밀어내지 않는다.
관전자에게는 추가 충돌을 적용하지 않는다. 육각 경계 우회 보정은 기존 안전장치로 유지한다.
전투 중에는 도전자용 표시와 충돌을 숨기고 실제 전투 엔티티를 사용한다.
표시/전투 엔티티는 청크에 저장하지 않고, 완료 상태만 기존 플레이어 플래그에 저장한다.
서버 종료, 로그아웃, 차원 이동, 리스폰 시 임시 도전 상태를 정리한다.

## 인게임 확인 항목

- 잠만보의 수면 자세, 회전 네 방향, 지면 높이 및 몸체/충돌 크기 일치
- 나무 양옆 틈, 점프, 달리기, 고지대 우회와 육각 경계 안전장치
- 시작 조건 미충족, 포켓몬 파티 전멸, 승리/포획/도주/패배/전투 시작 실패
- 두 플레이어 중 한 명만 완료했을 때 표시·충돌 독립성
- 전투 중 로그아웃·차원 이동 및 재접속 후 재도전

개발 회귀 테스트: `GatePokemonConfigTest`, `WorldGateEdgePlacementTest`, 콘텐츠 관리의 관문 저장 검증 및 `gate_pokemon_editor.test.cjs`.

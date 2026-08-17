# 공용 플레이어 조건 규격

관문, 건물 문, 체육관 입구, NPC 분기처럼 플레이어의 진행 상태를 검사하는 기능은
모두 이 문서의 조건 형식을 사용한다. 기능별로 새로운 조건 이름이나 별도 판정기를
만들지 않는다.

## 기준 구현

- JSON Schema: `content/schemas/player-condition.schema.json`
- 서버 판정기: `PlayerConditions`
- 콘텐츠 검증: `content_manager._validate_player_condition`
- 편집 UI: `defaultPlayerCondition`, `playerConditionTypeOptions`, `validatePlayerConditions`
- EasyNPC 변환: `generate_easy_npc_presets.easy_npc_condition`

조건을 추가하거나 의미를 바꿀 때는 위 다섯 곳과 이 문서를 같은 변경 단위로 수정한다.

## 조건 묶음

```json
{
  "condition_mode": "all",
  "conditions": []
}
```

- `all`: 모든 조건이 참이어야 한다. 빈 배열은 참이다.
- `any`: 하나 이상의 조건이 참이어야 한다. 빈 배열은 거짓이다.
- 조건 배열의 순서는 결과에 영향을 주지 않는다.

통과 제한이 없는 콘텐츠는 `all`과 빈 배열을 사용한다.

## 정식 조건 타입

### 진행 플래그

```json
{ "type": "flag", "key": "cobbleventure:flag/story/starter_received", "value": true }
```

리소스 ID 형태의 키는 안정적인 `cvf_` 스코어보드 목적 이름으로 변환된다. NPC의
`set_flag`와 체육관 클리어가 기록하는 값과 같은 저장소를 본다.

### 아이템 소지

```json
{ "type": "item", "item": "cobblemon:potion", "count": 3 }
```

기본 인벤토리와 Cobbleventure 가방 수량을 합산한다. `negate: true`이면 지정 수량
미만일 때 참이다.

### 배지 클리어

```json
{ "type": "badge", "badge": "cobbleventure:badge/kanto/boulder" }
```

서버 배지 진행 데이터를 검사한다. `negate: true`를 지원한다.

### 특정 파티 포켓몬

```json
{ "type": "pokemon", "species": "cobblemon:pikachu" }
```

현재 파티에 해당 종이 한 마리 이상 있는지 검사한다. PC는 포함하지 않으며
`negate: true`를 지원한다.

### 파티 포켓몬 수

```json
{ "type": "party_count", "operator": ">=", "value": 1 }
```

현재 파티 수를 `0`부터 `6`까지 비교한다. 연산자는 `==`, `!=`, `>`, `>=`, `<`,
`<=`를 사용할 수 있다.

### 숫자 변수

```json
{
  "type": "variable",
  "source": "scoreboard",
  "key": "quest_progress",
  "operator": ">=",
  "value": 2
}
```

`source`는 `scoreboard` 또는 `persistent_data`다. 진행 플래그는 이 타입으로 우회하지
말고 `flag`를 사용한다.

### 항상

```json
{ "type": "always" }
```

NPC 기본 분기처럼 명시적인 폴백이 필요한 곳에서만 사용한다. 일반 관문이나 문은 빈
`all` 조건 배열을 사용한다.

## 기존 NPC 조건 호환

| 기존 타입 | 정식 타입 |
| --- | --- |
| `flag_equals` | `flag` |
| `has_item` | `item` |

기존 타입은 읽기 호환용이다. 새 콘텐츠와 UI 저장 결과는 정식 타입을 사용한다.

## 사용처

- 월드 관문 `objects[].properties.conditions`
- 건물 설정 `door_routes.*.conditions`
- 체육관 입구와 내부 연결 `conditions`
- NPC `branch.conditions`와 진입 대화 경로
- EasyNPC 대화 조건

EasyNPC는 직접 파티나 가방을 읽지 않는다. 플레이어 메뉴 모드의 조건 추적기가 공용
판정 결과를 5틱마다 `cvi_` 또는 `cvc_` 목적에 반영하고, 생성된 프리셋은 그 값을 읽는다.

## 새 조건 타입 추가 절차

1. 공용 스키마에 타입과 필수 필드를 추가한다.
2. `PlayerConditions.parse`와 판정 구현을 추가한다.
3. Python 공용 검증과 Web 공용 편집기를 추가한다.
4. NPC에서 사용한다면 EasyNPC 미러 목적 생성도 추가한다.
5. 관문·건물·체육관에 별도 판정 구현을 만들지 않는다.
6. 스키마, 런타임, UI, EasyNPC 변환 테스트를 함께 추가한다.

이 절차 중 일부만 구현된 타입은 정식 공용 조건으로 취급하지 않는다.

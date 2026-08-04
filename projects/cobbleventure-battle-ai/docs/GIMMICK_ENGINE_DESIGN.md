# 전투 기믹 엔진 설계

> 작성일: 2026-07-25  
> 대상: Cobbleventure 자체 전투 엔진
> 상태: 설계 확정 / 공통 자원 상태와 2단계 턴 큐 구현 시작

## 1. 목표

메가진화, Z파워, 다이맥스·거다이맥스와 테라스탈을 임시 피해 배율이 아닌
독립적인 전투 규칙으로 구현한다.

이 설계는 다음 환경에서 같은 의미를 가져야 한다.

- 웹 PvE·EvE 전투 실험실
- Minecraft 서버의 Cobblemon 전투
- AI의 행동 후보 생성과 상태 예측
- Showdown·Cobblemon 결과와의 차등 테스트

Mega Showdown은 네 기믹을 모두 제공하며 커스텀 메가, 거다이맥스 폼,
Z크리스탈과 기믹 도구를 데이터팩으로 확장할 수 있다. 자체 엔진도 이 확장성을
잃지 않도록 포켓몬 이름별 조건문 대신 레지스트리와 정규화된 정의를 사용한다.

## 2. Cobbleventure 기본 정책

자체 엔진의 기본 프로필 이름은 `cobbleventure_all`로 한다.

| 정책 | 기본값 |
|------|--------|
| 사용 가능한 기믹 | 메가진화, Z파워, 다이맥스, 테라스탈 모두 |
| 사용 횟수 | 진영마다 각 기믹 1회 |
| 자원 공유 | 네 기믹의 사용 횟수는 서로 독립 |
| 한 전투에서 네 기믹 사용 | 서로 다른 포켓몬을 이용하면 가능 |
| 한 포켓몬의 변신 중첩 | 금지 |
| 한 포켓몬의 연속 변신 | 기본적으로 금지 |
| Z파워 | 변신이 아닌 일회성 기술 강화 |
| 메가스톤·Z크리스탈 | 장착 도구와 대상 호환성 필요 |
| 다이맥스·테라스탈 | 진영의 기믹 자격과 포켓몬별 적합성 필요 |

따라서 한 진영이 한 전투에서 메가진화, Z기술, 다이맥스, 테라스탈을 모두
사용할 수는 있지만 같은 포켓몬에게 메가진화 후 테라스탈 같은 변신을
겹치지는 않는다.

규칙 비교와 회귀 테스트용으로 다음 프로필도 유지한다.

| 프로필 | 용도 |
|--------|------|
| `official_gen7` | 메가진화와 Z기술 기준 비교 |
| `official_gen8` | 메가진화·Z기술·다이맥스 기준 비교 |
| `official_gen9` | 테라스탈 기준 비교 |
| `cobbleventure_all` | 실제 Cobbleventure 자체 엔진 |
| `sandbox` | 제한과 중첩 규칙을 개별 설정하는 개발 테스트 |

Showdown 엔진을 선택한 경우 세대별 공식 프로필만 사용한다. 여러 세대의
기믹을 동시에 쓰는 `cobbleventure_all`은 자체 엔진에서만 제공한다.

## 3. 현재 임시 구현의 문제

현재 `cobbleventure-battle-engine.mjs`의 기믹 처리는 UI와 명령 흐름을 확인하기
위한 임시 구현이다.

- 메가진화가 실제 폼 대신 모든 전투 능력치를 일괄 10% 올린다.
- Z기술이 원본 위력의 1.5배 또는 위력 100이라는 임시 공식만 사용한다.
- 다이맥스 기술이 타입과 관계없이 같은 임시 위력 공식을 사용한다.
- 거다이맥스 전용 폼과 G-Max 기술이 없다.
- 다이맥스 레벨과 기술별 Max 위력표가 없다.
- 일반 테라와 스텔라의 자속·방어 타입·타입별 1회 강화가 구현되었다.
- 오거폰은 가면별 고정 테라 타입·전용 폼·태세변환 능력치 상승과 가면의
  1.2배 위력 보정을 적용한다.
- 테라파고스는 테라 체인지, 테라 셸, 스텔라 폼, 테라폼제로의 날씨·필드
  제거와 폼 변경 시 HP 보존을 적용한다.
- 종별 실제 메가 폼 능력치와 특성 변환은 아직 카탈로그로 연결되지 않았다.
- 기믹이 행동 순서에 반영되기 전에 이미 스피드 순서가 계산된다.

정식 구현에서는 이 임시 배율을 제거하고, 필요한 데이터가 없으면
`unsupported_effect` 또는 `gimmick_unavailable`로 실패시킨다.

## 4. 공통 아키텍처

```text
Cobblemon + Mega Showdown 데이터팩
  → GimmickCatalogAdapter
  → GimmickRegistry
  → GimmickEligibilityResolver
  → GimmickActionValidator
  → GimmickStateMachine
  → FormTransformer / MoveTransformer
  → 전투 사건 로그
```

### 4.1 책임

| 구성 요소 | 책임 |
|-----------|------|
| `GimmickRegistry` | 기믹, 폼, 도구, 기술 변환 정의 보관 |
| `GimmickEligibilityResolver` | 현재 포켓몬이 기믹을 쓸 수 있는지 판정 |
| `GimmickActionValidator` | 사용 횟수, 장착 도구, 상태와 명령 검증 |
| `GimmickStateMachine` | 예약·발동·지속·종료·소비 상태 전이 |
| `FormTransformer` | 종족, 타입, 능력치, 특성과 외형 폼 변경 |
| `MoveTransformer` | Z기술, Max 기술, G-Max 기술과 테라 기술 변환 |
| `GimmickEventProjector` | 웹·Minecraft UI가 소비할 사건 생성 |

전투 코어는 Minecraft 클래스나 NBT를 참조하지 않는다. Minecraft 어댑터가
Mega Showdown 데이터팩과 포켓몬 정보를 읽고 아래의 정규 모델로 바꾼다.

### 4.2 진영 자원

```text
GimmickResourceState
  mega: available | reserved | consumed
  zmove: available | reserved | consumed
  dynamax: available | reserved | consumed
  terastallize: available | reserved | consumed
```

`reserved`는 명령 제출 후 행동 실행 전 상태다. 같은 턴의 더블·트리플
배틀에서 두 포켓몬이 같은 기믹을 동시에 요청하는 것을 이 단계에서 막는다.

### 4.3 포켓몬 상태

```text
PokemonGimmickState
  eligibleDefinitions
  activeTransformation
  transformationHistory
  megaFormId
  dynamax
    active
    turnsElapsed
    level
    gigantamax
    originalHp
    originalMaxHp
  tera
    active
    type
    originalTypes
    stellarBoostedTypes
```

Z파워는 지속 상태가 아니라 기술 행동에 붙는 `MoveAugmentation`으로 기록한다.

### 4.4 행동 명령

```json
{
  "kind": "move",
  "moveSlot": 2,
  "target": "foe:0",
  "gimmick": {
    "kind": "terastallize",
    "definitionId": "cobbleventure:standard_tera"
  }
}
```

클라이언트가 폼 능력치나 변환 위력을 직접 보내지는 않는다. 서버는
`definitionId`, 현재 포켓몬과 카탈로그를 이용해 결과를 다시 계산한다.

## 5. 턴 처리 순서

기믹은 단순히 `executeMove` 내부에서 켜면 안 된다. 메가진화로 바뀐 스피드와
특성이 같은 턴의 행동 순서에 영향을 줄 수 있기 때문이다.

```text
1. 양쪽 명령 수집
2. 기믹 자격과 진영 자원 검증
3. 기믹 자원 reserved
4. 메가진화·다이맥스·테라스탈 선행 발동
5. 폼·능력치·특성·타입 반영
6. 우선도와 스피드로 행동 순서 재계산
7. Z/Max/G-Max/테라 기술 변환
8. 기술 실행
9. 기믹 자원 consumed
10. 턴 종료 지속 시간과 종료 조건 처리
```

발동 전에 포켓몬이 기절하거나 필드를 떠나 명령이 무효화되면 `reserved`를
다시 `available`로 돌린다. 실제 발동 사건이 기록된 뒤 실패한 기술은 자원을
소비한다.

## 6. 메가진화

### 6.1 자격

- 포켓몬과 메가스톤의 대상 종이 일치해야 한다.
- Mega Showdown 데이터팩의 `showdown_id`, 대상 포켓몬과 적용 aspect를 읽는다.
- 메가리자몽 X/Y처럼 가능한 폼이 여럿이면 장착한 돌로 폼을 결정한다.
- 메가레쿠쟈처럼 특정 기술을 요구하는 예외는 별도 자격 정의로 표현한다.
- 이미 다른 변신 기믹을 사용한 포켓몬은 `cobbleventure_all`에서 사용할 수 없다.

### 6.2 발동

- 기술보다 먼저 폼을 변경한다.
- 메가 폼의 종족값, 타입, 특성과 무게를 카탈로그에서 다시 읽는다.
- 랭크 변화, 상태이상, 현재 HP와 기술 PP는 유지한다.
- 메가 폼은 교체해도 유지하며 전투 종료 시 Minecraft 외형을 원상 복구한다.
- 한 진영의 메가 자원은 실제 폼 변경 시점에 소비한다.

메가 폼은 임의의 10% 보정으로 만들지 않는다. `FormDescriptor`가 완전한
종족 데이터와 원복 정보를 가져야 한다.

### 6.3 필수 사건

- `gimmick_reserved`
- `mega_evolution`
- `form_changed`
- `ability_changed`
- `gimmick_consumed`
- `gimmick_rejected`

## 7. Z파워와 Z기술

### 7.1 자격

- 진영이 Z링 자격을 가져야 한다.
- 포켓몬이 호환되는 Z크리스탈을 장착해야 한다.
- 일반 Z기술은 원본 기술 타입과 크리스탈 타입이 일치해야 한다.
- 전용 Z기술은 종, 폼, 기술과 크리스탈 조건을 모두 검사한다.
- 원본 기술의 PP가 없거나 기술을 선택할 수 없는 상태면 발동할 수 없다.

### 7.2 공격 Z기술 위력표

원본이 다중 타격 기술이면 기준 위력 계산에서 원본 위력의 3배를 사용한 뒤
아래 구간표를 적용한다.

| 원본 기준 위력 | Z기술 위력 |
|---------------:|-----------:|
| 140 이상 | 200 |
| 130~139 | 195 |
| 120~129 | 190 |
| 110~119 | 185 |
| 100~109 | 180 |
| 90~99 | 175 |
| 80~89 | 160 |
| 70~79 | 140 |
| 60~69 | 120 |
| 59 이하 또는 위력 없음 | 100 |

- 원본 기술의 PP를 1 소비한다.
- 공격 Z기술은 일반 명중 판정을 하지 않는다.
- 방어 계열 기술을 관통하면 원래 피해의 25%를 적용한다.
- 원본 기술의 일부 속성을 이어받되 Z기술에서 금지된 부가 규칙은 변환기가
  제거한다.

### 7.3 변화 Z기술

변화기는 공통 피해 위력표를 사용하지 않는다.

```text
ZStatusDefinition
  sourceMoveId
  preEffect
  boost
  heal
  redirect
  resetStats
```

Z효과를 먼저 적용한 뒤 원본 변화기 효과를 실행한다. 기술별 Z효과는
Showdown 기술 메타데이터에서 추출해 카탈로그로 고정하며 임의 추정하지 않는다.

### 7.4 전용 Z기술

전용 기술은 다음을 모두 데이터로 가진다.

- 요구 종과 폼
- 요구 원본 기술
- 요구 Z크리스탈
- 변환 기술 ID
- 위력·타입·대상
- 전용 부가효과

## 8. 다이맥스와 거다이맥스

### 8.1 자격

- 진영이 다이맥스 밴드 자격을 가져야 한다.
- 규칙 또는 포켓몬 정의에서 다이맥스 금지 대상이 아니어야 한다.
- 거다이맥스는 G-Max 적합성, 폼 정의와 전용 기술 정의가 필요하다.
- 플레이어는 엔트리의 `dynamax` 값과 관계없이 일반 다이맥스를 선택할 수 있다.
- 컴퓨터 엔트리의 `dynamax: true`는 사용 자격이 아니라 해당 포켓몬이
  출전했을 때 AI가 다이맥스를 강제로 선택하라는 지시다.
- `gmax: true`인 컴퓨터 엔트리는 같은 강제 지시를 거다이맥스로 실행한다.
- `cobbleventure_all`에서는 이미 다른 변신을 사용한 포켓몬은 다이맥스할 수
  없다.

Mega Showdown 데이터팩의 G-Max 정의는 Showdown 포켓몬 ID와 G-Max 기술 ID를
연결하므로 어댑터가 이를 그대로 `GigantamaxDefinition`으로 정규화한다.

### 8.2 HP

다이맥스 레벨 `0~10`의 HP 배율은 다음 공식으로 계산한다.

```text
hpRatio = 1.5 + dynamaxLevel × 0.05
```

- 레벨 0은 1.5배, 레벨 10은 2배다.
- 현재 HP와 최대 HP를 같은 비율로 올린다.
- 껍질몬처럼 HP 변환 예외인 대상은 별도 규칙을 사용한다.
- 3턴 종료, 교체 또는 기절 시 원래 최대 HP로 복귀한다.
- 종료 시 현재 HP 환산은 한 함수에서 처리하고 Showdown 골든 테스트로
  반올림을 고정한다.

### 8.3 Max 기술 변환

- 공격기는 타입에 대응하는 Max 기술로 바뀐다.
- 변화기는 `Max Guard`로 바뀐다.
- 거다이맥스 대상은 호환 타입의 일반 Max 기술 대신 전용 G-Max 기술을 쓴다.

격투·독 타입의 Max 기술은 다음 위력표를 사용한다.

| 원본 위력 | Max 위력 |
|----------:|---------:|
| 150 이상 | 100 |
| 110~149 | 95 |
| 75~109 | 90 |
| 65~74 | 85 |
| 55~64 | 80 |
| 45~54 | 75 |
| 44 이하 | 70 |

그 외 타입은 다음 위력표를 사용한다.

| 원본 위력 | Max 위력 |
|----------:|---------:|
| 150 이상 | 150 |
| 110~149 | 140 |
| 75~109 | 130 |
| 65~74 | 120 |
| 55~64 | 110 |
| 45~54 | 100 |
| 44 이하 | 90 |

Showdown 기술 데이터에 명시된 `maxMove.basePower`가 있으면 구간표보다 우선한다.

### 8.4 지속과 면역

- 발동 턴을 포함해 3턴 유지한다.
- 교체하면 즉시 종료한다.
- 다이맥스 중에는 모든 기술 슬롯을 매 턴 Max/G-Max 기술로 투영한다.
- 강제 교체, 풀죽음, 일격기와 일부 무게 기반 기술의 다이맥스 예외를
  공통 면역 레지스트리로 처리한다.
- 다이맥스포, 거수참, 거수탄처럼 다이맥스 대상에게 피해가 증가하는 기술도
  대상 상태를 보고 처리한다.
- Max 기술의 날씨·필드·랭크·사이드 효과는 타입별 효과 레지스트리에 둔다.

## 9. 테라스탈

### 9.1 자격과 지속

- 진영이 테라오브 자격을 가져야 한다.
- 포켓몬의 테라 타입이 전투 시작 전에 확정돼 있어야 한다.
- 발동한 포켓몬이 교체해도 테라 상태는 유지한다.
- 포켓몬이 기절해도 진영의 테라 자원은 돌아오지 않는다.
- 오거폰과 테라파고스의 폼 변경은 별도 `FormDescriptor`로 실행한다.
- 오거폰은 가면에 따라 테라 타입과 태세변환 상승 능력치가 고정된다.
- 테라파고스는 등장 시 테라스탈폼, 스텔라 테라 시 스텔라폼으로 전환한다.

### 9.2 일반 테라 타입

방어 타입은 테라 타입 하나로 바뀌지만 공격 자속 판정을 위해 원래 타입을
별도로 보존한다.

| 기술 타입 | 자속 |
|-----------|-----:|
| 테라 타입이면서 원래 타입 | 2.0 |
| 테라 타입이지만 원래 타입 아님 | 1.5 |
| 원래 타입이지만 테라 타입 아님 | 1.5 |
| 어느 쪽도 아님 | 1.0 |

적응력 같은 자속 변경 특성은 별도 `ModifyStab` 단계에서 적용한다.

테라스탈한 포켓몬의 위력 60 미만 공격기는 공식 조건을 만족하면 위력 60으로
보정한다. 우선도 기술, 다중 타격 기술과 일부 동적 위력 기술은 제외한다.

### 9.3 스텔라 테라

- 방어 타입은 원래 타입을 유지한다.
- 원래 타입과 같은 공격은 해당 타입의 첫 1회에 2배 자속을 받는다.
- 원래 타입이 아닌 공격은 해당 타입의 첫 1회에 약 1.2배를 받는다.
- 사용한 타입은 `stellarBoostedTypes`에 기록한다.
- 소모된 원래 타입은 이후 일반 1.5배 자속으로 돌아간다.
- 소모된 비자속 타입은 이후 1배다.
- 테라파고스 스텔라는 타입별 강화가 소모되지 않는 예외를 가진다.

### 9.4 테라버스트와 전용 기술

- 테라스탈 전에는 노말 타입 위력 80이다.
- 테라스탈 후 테라 타입으로 바뀐다.
- 공격과 특수공격 중 높은 실능력치에 따라 물리·특수를 결정한다.
- 스텔라 테라버스트는 위력 100이며 명중 뒤 공격·특수공격이 각각 1랭크
  내려간다.
- 테라클러스터 등 전용 기술은 별도 기술 핸들러로 관리한다.

## 10. 기믹 간 충돌 규칙

| 조합 | `cobbleventure_all` 기본 처리 |
|------|-----------------------------|
| 메가 + Z | 장착 도구가 달라 같은 포켓몬은 불가 |
| 메가 + 다이맥스 | 같은 포켓몬은 불가 |
| 메가 + 테라 | 같은 포켓몬은 불가 |
| 다이맥스 + 테라 | 같은 포켓몬은 불가 |
| Z + 다이맥스 | 같은 턴 동시 사용 불가 |
| Z + 테라 | 장착·자격 정의가 허용하더라도 같은 턴 동시 발동은 금지 |

다른 팀원에게 사용한 기믹은 서로 막지 않는다. 예를 들어 1번 포켓몬이
메가진화하고, 2번이 Z기술을 쓰고, 3번이 다이맥스하고, 4번이 테라스탈하는
전투는 허용한다.

이 규칙은 다음 설정으로 표현하며 엔진 코드에 흩어진 조건문으로 만들지 않는다.

```json
{
  "profile": "cobbleventure_all",
  "usageLimitPerSide": {
    "mega": 1,
    "zmove": 1,
    "dynamax": 1,
    "terastallize": 1
  },
  "shareUsagePool": false,
  "oneTransformationPerPokemon": true,
  "allowMultipleActivationsInOneTurn": false
}
```

## 11. 데이터팩과 어댑터

Minecraft 어댑터는 다음을 스캔한다.

```text
data/*/mega_showdown/mega/*.json
data/*/mega_showdown/gmax/*.json
data/*/mega_showdown/z_crystal_item/*.json
data/mega_showdown/tags/item/mega_bracelet.json
data/mega_showdown/tags/item/z_ring.json
data/mega_showdown/tags/item/dynamax_band.json
data/mega_showdown/tags/item/tera_orb.json
data/mega_showdown/tags/item/omni_ring.json
```

스캔 결과는 버전과 원본 경로를 포함한 JSON 카탈로그로 내보낸다.

```text
GimmickCatalog
  schemaVersion
  cobblemonVersion
  megaShowdownVersion
  sourceDigest
  megaDefinitions
  zCrystalDefinitions
  maxMoveDefinitions
  gigantamaxDefinitions
  teraDefinitions
```

웹 실험실은 이 카탈로그만 읽는다. 모드 업데이트 후에는 카탈로그 생성기를
다시 실행하고 차이를 리뷰한다.

## 12. UI와 API

### 12.1 서버가 제공할 선택 정보

```text
GimmickOption
  kind
  available
  unavailableReason
  consumed
  targetForm
  transformedMove
  duration
  preview
```

UI는 장착 도구와 `usedGimmicks`만 보고 기믹 가능 여부를 추측하지 않는다.
항상 엔진이 만든 `GimmickOption`을 표시한다.

### 12.2 배틀 화면

- 사용할 수 없는 버튼에는 정확한 사유를 표시한다.
- Z기술과 Max 기술을 선택하면 기술명, 타입, 위력과 주요 효과를 미리 보여준다.
- 다이맥스는 남은 턴을 표시한다.
- 메가·거다이맥스는 변경된 폼 이미지를 사용한다.
- 테라스탈은 테라 타입과 원래 타입 자속을 함께 보여준다.
- 스텔라는 아직 소비하지 않은 강화 타입을 자세히 보기에서 표시한다.

### 12.3 사건 로그

최소 사건 집합은 다음과 같다.

```text
gimmick_reserved
gimmick_rejected
gimmick_activated
gimmick_consumed
gimmick_ended
form_changed
ability_changed
type_changed
move_transformed
max_turn_advanced
stellar_boost_consumed
```

각 사건은 `side`, `pokemon`, `gimmick`, `definitionId`, `reason`과 전후 상태를
포함한다. AI 판단 로그는 이 사건과 같은 ID를 참조한다.

## 13. AI 연결

기믹은 기술에 붙는 불리언이 아니라 별도의 행동 후보다.

```text
BattleActionCandidate
  move
  target
  gimmick
  immediateValue
  retainedResourceValue
  transformationValue
  risk
```

초기 AI는 다음 기준선만 사용한다.

- 확정 KO가 생기는 Z/Max 기술
- 다이맥스 HP 증가로 생존하는 경우
- 메가진화로 스피드 우위가 뒤집히는 경우
- 테라로 약점을 무효·반감하거나 자속 KO가 생기는 경우
- 남은 상대 에이스를 위해 기믹 자원을 보존하는 가치

심층 예측은 기믹 규칙의 차등 테스트가 끝난 뒤 추가한다.

## 14. 테스트 계획

### 14.1 단위 테스트

- 모든 Z·Max 위력 구간 경계
- 다이맥스 레벨 0과 10의 HP
- 다이맥스 3턴 종료와 교체 종료
- 메가 폼의 타입·능력치·특성 교체
- 일반 테라의 세 가지 자속 경우
- 스텔라의 타입별 1회 소비
- 테라버스트 물리·특수 선택
- 모든 자원 소비·복구·거부 상태

### 14.2 상호작용 테스트

- 기믹 발동 턴의 스피드 순서
- 구애 도구와 Z·Max 기술
- 방어 대 Z·Max 기술
- 다이맥스 대 무게·강제 교체·일격기
- 교체·기절·상태이상과 지속 시간
- 메가 폼 특성의 등장·퇴장 효과
- 테라 타입과 날씨·필드·흡수 특성
- 더블·트리플에서 같은 진영의 중복 요청

### 14.3 차등 테스트

- `official_gen7`: 메가와 Z기술을 Showdown 결과와 비교
- `official_gen8`: 다이맥스·G-Max를 Showdown 결과와 비교
- `official_gen9`: 테라와 스텔라를 Showdown 결과와 비교
- Mega Showdown이 설치된 Cobblemon 테스트 서버와 폼·도구·사건 로그 비교

같은 입력 JSON과 시드를 자체 엔진과 Showdown에 넣고 HP, 폼, 타입, 능력치,
기술 변환과 사건 순서를 비교한다.

## 15. 구현 순서

1. 공통 기믹 자원 상태와 `GimmickOption` 계약
2. 선행 발동 후 스피드를 다시 계산하는 2단계 턴 큐
3. 테라 자속과 스텔라 상태 완성
4. Z기술 위력표·Z변화기·전용 Z기술
5. 다이맥스 HP·3턴·Max 기술과 타입별 효과
6. 거다이맥스 폼·전용 G-Max 기술
7. 메가 폼 카탈로그와 정확한 폼 변환
8. Mega Showdown 데이터팩 카탈로그 생성기
9. 공식 세대별 Showdown 차등 테스트
10. Minecraft Cobblemon 어댑터와 외형 사건 연결

각 단계는 임시 배율을 하나씩 제거한다. 정의가 없는 폼이나 전용 기술을
추측해서 실행하지 않는다.

## 16. 구현 현황

자체 엔진 `0.9.6`에서 다음 기반 작업을 완료했다.

- 진영마다 네 기믹의 `available → reserved → consumed` 자원 상태
- 중복 사용과 잘못된 요청의 `gimmick_rejected` 사건
- 행동 불능으로 발동되지 않은 Z파워 예약 반환
- 메가진화·다이맥스·테라스탈의 기술 실행 전 발동
- 선행 발동 뒤 변경된 스피드로 행동 순서 재계산
- 기존 웹 UI용 `usedGimmicks`와 `gimmick` 사건 호환 유지
- 다이맥스의 3턴 카운트와 교체·기절 즉시 종료
- 다이맥스 종료 사유 사건
- 장착한 메가스톤의 대상 종과 현재 포켓몬 일치 검증
- 장착한 Z크리스탈의 타입·전용 원본 기술과 선택 기술 일치 검증
- `dynamax`와 `gigantamax` 런타임 모드 분리 및 사건 기록
- 엔트리에 미리 지정된 테라타입만 사용하며 명령의 임의 타입 변경 거부
- 웹 선택 화면에서 실제 자격이 있는 기믹만 제공
- 플레이어의 일반 다이맥스는 엔트리 설정과 무관하게 항상 선택 가능
- 자체 엔진 UI는 엔트리의 AI 강제 플래그가 아니라 플레이어의 실제 사용 이력으로 다이맥스 버튼을 잠금
- 이전 응답에 Max 기술 목록이 없더라도 자체 엔진의 원본 기술로 선택지를 복구
- 원본 기술 타입을 표준 Max 기술 18종과 변화기 `Max Guard`로 변환
- 거다이맥스 가능 종은 대응 타입 기술을 종별 G-Max 전용 기술로 변환
- 웹 화면의 Max·G-Max 기술 이름과 설명은 한국어 기술 카탈로그를 사용
- 엔트리의 `dynamax`·`gmax`를 컴퓨터용 강제 발동 지시로 분리
- 강제 다이맥스 선택 사유를 AI 판단 로그에 기록
- 가상전투에서 테라타입이 없는 엔트리는 전투 시드와 슬롯을 이용해 원래 타입
  중 하나를 결정론적으로 선택하며, 자체 엔진과 Showdown 어댑터가 같은 값을 사용
- `gimmicks.tera`, `teraType`, `tera_type` 및 포켓몬 속성 객체의 동명 값을
  테라타입 입력으로 정규화
- 실제 테스트 데이터에 `canTera`·`teraTarget` 지정 대상이 있으면 해당 포켓몬이
  살아 있는 동안 다른 포켓몬의 테라스탈을 보류하고, 지정 대상이 쓰러진 뒤에만
  남은 포켓몬의 대체 테라스탈을 허용

발동 거부 사유는 `mega_stone_required`, `mega_stone_incompatible`,
`z_crystal_required`, `z_crystal_incompatible`, `dynamax_unavailable`,
`tera_type_required`, `tera_type_mismatch`로 구분한다.

일반 테라의 원래 타입 자속 보존, 같은 타입 테라 자속, 저위력 기술의 위력 60
보정, 테라버스트의 타입·분류 전환, 스텔라의 방어 타입 유지와 타입별 1회 강화
소비는 자체 엔진과 AI 예상 피해 계산에 공통 적용한다.

AI는 테라 전후의 현재 상대 최대 예상 피해와 확정 KO 여부를 비교해 방어적
테라스탈 가치를 계산한다. 또한 살아 있는 상대 엔트리 각각의 알려진 기술과 타입을
대조해 남은 대면의 평균 피해가 개선되는지 확인한다. 현재 대면만 유리하거나 화력이
증가한다는 이유만으로 자원을 즉시 소비하지 않는다.

아직 메가 폼 카탈로그, 정식 Z·Max 위력표, 다이맥스 레벨은 후속 구현
범위다.

## 17. 참고 자료

- [Pokémon Showdown 전투 시뮬레이터](https://github.com/smogon/pokemon-showdown)
- [Showdown 기술 위력 변환 데이터](https://github.com/smogon/pokemon-showdown/blob/master/sim/dex-moves.ts)
- [Showdown 기믹 발동과 자속 처리](https://github.com/smogon/pokemon-showdown/blob/master/sim/battle-actions.ts)
- [Showdown 다이맥스 지속 상태](https://github.com/smogon/pokemon-showdown/blob/master/data/conditions.ts)
- [Cobblemon Mega Showdown](https://github.com/yajatkaul/CobblemonMegaShowdown)
- [Mega Showdown 커스텀 기믹 정의](https://github.com/yajatkaul/CobblemonMegaShowdown/wiki/Custom-Gimmicks)
- [Mega Showdown 기믹 도구 태그](https://github.com/yajatkaul/CobblemonMegaShowdown/wiki/Custom-Gimmick-Items)

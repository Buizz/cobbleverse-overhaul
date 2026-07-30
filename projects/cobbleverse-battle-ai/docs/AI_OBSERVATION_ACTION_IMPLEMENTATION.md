# AI 관측 모델과 행동 후보 평가 구현 문서

이 문서는 전투 AI 고도화의 첫 구현 단위를 추적한다. 목표는 AI를 더 똑똑하게 만들기 전에, Showdown 기준 엔진과 Cobbleverse 자체 엔진이 같은 관측 모델과 행동 후보 평가 로그를 AI에 제공하게 만드는 것이다.

첫 커밋 단위:

```text
feat: AI 공통 관측 모델과 행동 후보 평가 구조 추가
```

## 1. 핵심 방향

- 단순 점수 AI 위에 보너스를 계속 붙이지 않는다.
- 모든 판단은 공통 `AiBattleObservation`에서 시작한다.
- 기술, 교체, 기믹, 타겟 선택은 모두 `AiActionCandidate`로 정규화한다.
- 공개 정보와 치터 난이도 전용 정보를 계약 단계에서 분리한다.
- EvE 리포트에서 AI가 왜 그 행동을 골랐는지 사람이 읽을 수 있게 한다.

## 2. 현재 기준

| 영역 | 상태 |
|------|------|
| 전투 엔진 | PvE/EvE 실험이 가능한 기반이 있으며, Showdown 기준 엔진과 Cobbleverse 자체 엔진이 공존한다. |
| 공통 AI | `web-lab/lib/common-battle-ai.mjs`가 양쪽 엔진에서 일부 공유된다. |
| 현재 판단 | 피해량, 명중률, 우선도, KO 가능성, 간단한 교체 점수 중심이다. |
| 부족한 부분 | 역할 분석, 에이스 보존, 상대 에이스 견제, 랭크업 억제, 장기 승률 판단, 전략 성향 차이가 약하다. |

## 3. 1차 구현 범위

1차 구현은 후속 기능의 토대만 만든다. 팀 역할 분석, 위협 맵, 랭크업 억제, 1턴 시뮬레이션은 이 계약 위에 단계적으로 얹는다.

- [x] `AiBattleObservation` 공통 모델 정의
- [x] Showdown 실행 결과를 `AiBattleObservation`으로 변환
- [x] Cobbleverse 자체 엔진 상태를 `AiBattleObservation`으로 변환
- [ ] PvE와 EvE가 같은 관측 입력을 사용하게 정리
- [x] 싱글/더블/트리플 확장을 막지 않는 `battleType` 구조 확정
- [ ] 공개 정보와 난이도 권한 정보를 분리
- [x] `AiActionCandidate` 구조 정의
- [x] 기술 선택 후보를 `AiActionCandidate`로 생성
- [x] 교체 후보를 `AiActionCandidate`로 생성
- [ ] 기믹 후보를 `AiActionCandidate`로 생성
- [ ] 타겟 선택 정보를 행동 후보에 포함
- [x] 기존 `common-battle-ai.mjs` 선택 결과에 후보 점수와 `reasons`를 기록
- [ ] EvE 리포트에 선택 후보와 판단 이유를 연결
- [x] 회귀 테스트에 최소 관측 변환과 후보 로그 검증 추가

## 4. AiBattleObservation 초안

`AiBattleObservation`은 AI가 볼 수 있는 전투 스냅샷이다. 엔진별 원본 객체, UI 편의 필드, 미공개 상대 정보는 직접 넣지 않는다.

```ts
type AiBattleObservation = {
  schemaVersion: 1;
  battleId?: string;
  side: "p1" | "p2" | string;
  turn: number;
  battleType: AiBattleType;
  activePokemon: AiObservedPokemon[];
  benchPokemon: AiObservedPokemon[];
  opponentActivePokemon: AiObservedPokemon[];
  opponentBenchKnownInfo: AiObservedPokemon[];
  field: AiFieldObservation;
  weather?: AiWeatherObservation;
  terrain?: AiTerrainObservation;
  sideConditions: AiSideConditionsObservation;
  legalActions: AiLegalAction[];
  revealedInfo: AiRevealedInfo;
  history: AiBattleHistoryEntry[];
};
```

### 4.1 battleType

```ts
type AiBattleType = {
  mode: "single" | "double" | "triple";
  activeSlotsPerSide: 1 | 2 | 3;
  ruleset?: string[];
  engine: "showdown" | "cobbleverse-simple" | "cobblemon";
};
```

### 4.2 공개 정보 원칙

`AiBattleObservation`에는 해당 난이도와 전투 시점에서 합법적으로 알 수 있는 정보만 넣는다.

넣어도 되는 정보:

- 현재 출전 포켓몬의 공개된 종, HP 비율, 상태, 랭크, 타입, 공개된 기술
- 아군 파티의 전체 정보
- 상대가 이미 공개한 기술, 도구, 특성, 기믹 사용 여부
- 필드, 날씨, 설치물, 턴 히스토리
- 현재 선택 가능한 합법 행동

넣으면 안 되는 정보:

- 일반 난이도에서 아직 공개되지 않은 상대 벤치의 정확한 기술, 도구, 특성
- 이번 턴 플레이어가 잠근 행동
- 미래 명중, 급소, 피해 난수
- UI나 디버그용 원본 엔진 객체

치터 난이도의 이번 턴 행동 열람은 `AiBattleObservation`이 아니라 별도 권한 객체로 전달한다.

```ts
type PrivilegedOpponentKnowledge = {
  policy: "peek_locked_action";
  lockedActions: AiLockedOpponentAction[];
  commandHash: string;
};
```

## 5. AiActionCandidate 초안

모든 합법 행동은 같은 구조로 평가한다. 점수 계산이 아직 단순하더라도, 각 요소와 판단 이유를 먼저 남긴다.

```ts
type AiActionCandidate = {
  id: string;
  type: "move" | "switch" | "gimmick" | "item";
  legal: boolean;
  action: AiLegalAction;
  target?: AiActionTarget;
  expectedDamage?: AiExpectedDamage;
  koChance?: number;
  survivalRisk?: number;
  speedRisk?: number;
  statusValue?: number;
  setupRisk?: number;
  fieldValue?: number;
  roleValue?: number;
  resourceCost?: number;
  score: number;
  reasons: AiDecisionReason[];
};
```

판단 이유는 UI와 테스트에서 안정적으로 읽을 수 있게 코드와 메시지를 함께 둔다.

```ts
type AiDecisionReason = {
  code: string;
  label: string;
  value?: number | string | boolean;
  weight?: number;
  message: string;
};
```

예시:

```json
{
  "id": "move:p1a:thunderbolt:p2a",
  "type": "move",
  "legal": true,
  "expectedDamage": { "minPercent": 58, "maxPercent": 70 },
  "koChance": 0,
  "statusValue": 6,
  "score": 82.4,
  "reasons": [
    {
      "code": "damage.two_hit_ko",
      "label": "확정 2타",
      "value": "58~70%",
      "message": "상대에게 예상 피해 58~70%를 준다."
    },
    {
      "code": "secondary.paralysis",
      "label": "마비 부가효과",
      "value": true,
      "message": "마비 부가효과가 있어 다음 턴 속도 우위를 만들 수 있다."
    }
  ]
}
```

## 6. 구현 단계 체크리스트

### 6.1 관측 어댑터

- [x] `web-lab/lib/ai-api-bridge` 또는 그에 준하는 경계에서 관측 변환 책임을 모은다.
- [x] Showdown 상태에서 공통 관측으로 변환하는 함수를 만든다.
- [x] Cobbleverse 자체 엔진 상태에서 공통 관측으로 변환하는 함수를 만든다.
- [x] 양쪽 변환 결과가 같은 필드 이름과 같은 공개 정보 정책을 따르는지 테스트한다.
- [x] 관측 모델에 원본 엔진 객체 참조가 섞이지 않게 한다.

### 6.2 합법 행동 정규화

- [ ] 기술, 교체, 기믹, 아이템 후보를 같은 `legalActions` 배열에 넣는다.
- [ ] 타겟이 필요한 행동은 타겟 후보를 명시한다.
- [ ] 강제 교체 상태에서도 동일한 구조를 유지한다.
- [ ] PP 부족, 기절, 봉인, 기믹 사용 완료처럼 불가능한 행동은 후보 생성 단계에서 제외하거나 `legal: false` 사유를 남긴다.

### 6.3 후보 평가

- [x] 기존 피해량/명중률/우선도/KO 점수를 `AiActionCandidate` 필드로 옮긴다.
- [x] 교체 점수도 같은 후보 배열에서 비교한다.
- [x] 점수 조정마다 `AiDecisionReason`을 추가한다.
- [x] 이전 RunAndBunAI의 핵심 규칙 일부를 공통 후보 점수 보정으로 이식한다.
- [x] 최고 점수 후보 선택과 후보 전체 로그를 분리한다.
- [ ] 난이도별 실수 정책은 후보 생성이 아니라 선택 단계에서 적용한다.

### 6.4 리포트 연결

- [x] PvE AI 턴 로그에 선택 후보의 `score`와 `reasons`를 기록한다.
- [ ] EvE 리포트에 양쪽 AI의 후보 상위 N개를 표시한다.
- [ ] 같은 시드에서 전략만 바꿨을 때 후보 점수 차이를 비교할 수 있게 한다.
- [ ] 미구현 평가 항목은 숨기지 말고 `not_evaluated` 또는 생략 정책을 명확히 한다.

## 7. 후속 단계

### 7.1 TeamProfileAnalyzer

- [x] 전투 시작 전 아군 팀 역할을 분석한다.
  - 1차 구현: 기술 역할 카탈로그, 포켓몬 기본 역할 프리셋, 능력치, 선봉 슬롯을 기반으로 팀 멤버별 역할 점수와 근거를 산출한다.
  - EvE 리포트 대전 로그 시작 전에 양쪽 팀의 역할 분석 결과를 출력한다.
- [x] 일반적으로 용도가 정해진 포켓몬은 `data/ai/ai-pokemon-role-overrides.json`에서 부드러운 역할 보너스를 준다.
- [ ] 에이스, 막이, 피벗, 판깔이, 판 제거, 랭크업 스위퍼, 복수 처리 담당, 상대 에이스 견제 담당, 희생 가능 자원을 추론한다.
- [x] 기술 역할 분류를 후보 평가의 `roleValue`와 선택 이유 로그에 연결한다.
- [x] 이미 최대 층수까지 설치된 스텔스록/압정/독압정/끈적끈적네트는 실패 행동으로 보고 점수를 크게 낮춘다.
- [x] 트릭룸은 남은 느린 에이스 수, 팀 속도 역전 이득, 세터의 생존 가능성을 보고 전개 점수를 준다.
- [x] 트릭룸/날씨/필드가 이미 깔린 경우 해당 효과를 활용하는 포켓몬의 교체 점수를 높인다.
- [x] 자폭/대폭발류 기술은 사용자 기절 리스크, 유의미한 피해 여부, 현재 포켓몬의 남은 역할 가치를 함께 평가한다.
- [x] 전투 중 판 설치·제거 임무와 담당 위협의 생존 여부를 기준으로 역할 완료 상태를 추적한다.
  - 약하게 추론된 보조 역할은 완료 조건에서 제외하고, 주 역할과 의미 있는 부 역할만 추적한다.
  - 역할을 마친 비에이스 자원은 유의미한 자폭이나 희생 교체의 소모 비용을 완화한다.
  - 에이스 및 `ThreatCounterMap`의 유일 대응 자원은 역할 완료 여부와 관계없이 희생 가능 자원으로 처리하지 않는다.
- [ ] `TeamAnalysisReport`의 포켓몬별 역할을 후보 평가의 `roleValue`에 추가 연결한다.

### 7.2 ThreatCounterMap

- [x] 상대 포켓몬별 위협도를 계산한다.
- [x] 카운터, 소프트 체크, 복수 처리 자원, 반드시 보존해야 할 자원을 기록한다.
- [x] 유일 대응 자원을 위험한 교체나 자폭으로 희생하는 행동에 큰 페널티를 준다.
  - 공통 `buildThreatCounterMap`은 팀 역할, 현재 HP, 양방향 예상 피해, 행동 순서와 선공 KO 여부를 입력으로 받는다.
  - 자체 엔진은 상대 핵심 포켓몬별 대응 자원을 계산해 교체 후보의 `mustPreserveResource`, `mustPreserveFor`, `currentThreatClassification`에 연결한다.
  - 현재 상대가 보존 대상 위협이면 유일 카운터 투입 가치를 높이고, 다른 미래 위협을 위한 유일 카운터가 현재 대면에서 큰 피해를 받는 교체는 낮춘다.

### 7.3 SetupThreatEvaluation

- [x] 상대의 랭크업 가능성과 스윕 위험을 받을 수 있는 후보 보정 필드를 추가한다.
- [x] `SetupThreatEvaluation`에서 랭크업 가능성, 상승 폭, 에이스 성향, 랭크업 후 남는 대응 자원 수를 합쳐 스윕 위험을 계산한다.
- [x] 무료 턴을 주는 회복, 판 설치, 무의미한 교체에 페널티를 준다.
  - 긴급 회복은 무료 턴 페널티에서 제외하고, 랭크업 후 카운터나 즉시 KO 자원으로 기능하는 교체는 오히려 보정한다.
  - 후보 로그에 스윕 위험, 예상 대응 자원 수, 응징 수단과 `oneMoreTurnUnmanageable` 여부를 남긴다.
- [x] 도발, 흑안개, 클리어스모그, 강제교체, 확정 KO에 1차 보너스를 준다.
- [x] 앙코르와 고확률 화상·마비·수면을 랭크업 응징 수단으로 보정한다.
- [ ] 선공기 마무리 범위 만들기까지 보정한다.

### 7.4 전략 성향 8종

- [ ] 균형형, 공격형, 방어형, 에이스 견제형, 저돌적 에이스 사용형, 랭크업 전개형, 판 장악형, 템포/피벗형의 가중치 차이를 실제 점수에 반영한다.
- [ ] 같은 상태에서 전략별 후보 순위가 달라지는 장면 테스트를 만든다.

### 7.5 난이도

- [ ] 초급, 보통, 상급, 전문가, 치터의 허용 판단 기능과 실수 정책을 분리한다.
- [ ] 치터만 `PrivilegedOpponentKnowledge`를 받을 수 있게 한다.
- [ ] 난이도별 정보 누출 방지 테스트를 만든다.

### 7.6 1턴 얕은 시뮬레이션

- [x] 행동 실행 결과의 기대 상태 `s'`를 만들고 공통 `BattleStateValue`로 평가한다.
  - 1차 구현은 엔진을 복제 실행하지 않고 후보 계산 중 확보한 피해, KO 확률, 반격, 회복, 반동, 랭크, 상태, 설치물 자료를 기대값으로 투영한다.
  - 기술은 행동 전 기절 확률과 반격 가능성을, 교체는 진입 피해와 교체 턴 피격 및 기존 랭크 초기화를 포함한다.
- [x] `Q(s, a) = V(s')`와 `V(s') - V(s)`를 기술·교체 후보 점수에 결합하고 `simulation.one_turn_state_value` 이유를 남긴다.
  - 전문가 0.35, 치터 0.4, 상급 0.2의 보수적인 가중치를 사용하며 보통 이하에서는 계산을 생략한다.
- [x] 현재 상태와 행동 후 상태를 `0~1` 승률로 정규화한 설명 가능한 `WinEstimate` 기준선을 추가한다.
  - `heuristic-logistic-v2`는 현재 승률, 행동 후 승률, 변화량, 신뢰도와 주요 요인을 반환한다.
  - 에이스 후보 수가 다른 파티를 직접 비교하지 않고 각 파티 내 생존·체력 비율로 정규화한다.
- [x] 휴리스틱 선택과 승률 최상 후보를 같은 추적 로그에서 비교하고 2%p 이상 차이를 별도로 표시한다.
- [x] 실제 EvE 결과로 로지스틱 보정 계수를 학습하고 학습/홀드아웃 Brier score를 비교하는 도구를 추가한다.
- [x] 기존 `expert`를 보존하고 `expert_winrate`를 별도 선택 정책으로 분리한다.
  - `expert`는 기존 휴리스틱 선택을 그대로 사용한다.
  - `expert_winrate`는 동일한 후보와 안전 규칙을 사용하되, 행동 후 승률이 휴리스틱 선택보다 2%p 이상 높을 때만 선택을 변경한다.
- [x] 남은 포켓몬 수, HP 총합, 에이스 생존과 HP, 상태이상, 랭크, 필드·날씨·트릭룸 수혜, 설치물, 남은 기믹, 유일 카운터 생존 여부를 상태 가치에 반영한다.
- [x] 자체 엔진의 다이맥스·메가진화·테라스탈 후보도 변환 후 기술과 자원 소비를 하나의 `s'`로 평가한다.
  - 메가진화는 변환 폼의 타입·특성·능력치를, 다이맥스는 2배 HP와 변환 기술을, 테라스탈은 방어 타입과 변환 후 최선 기술을 다시 계산한다.
  - 테라스탈 AI 후보는 엔트리에 테라타입이 명시된 포켓몬만 생성하며, 단순 설정 여부가 아니라 변환 전후 행동 가치 차이로 사용 여부를 결정한다.
  - 테라버스트의 타입·물리/특수 분류, 원래 타입 자속, 같은 타입 테라 자속,
    저위력 기술 보정과 스텔라 타입별 강화 상태를 실제 엔진과 같은 계산 경로로 평가한다.
  - Z기술 후보와 Showdown 어댑터의 기믹 상태 투영은 후속 범위로 남긴다.
- [ ] Showdown 어댑터의 후보 평가도 같은 `BattleStateValue` 입력을 사용한다.

## 8. 완료 기준

1차 구현은 다음 조건을 만족하면 완료로 본다.

- Showdown PvE/EvE와 Cobbleverse 자체 엔진 PvE/EvE가 공통 관측 모델을 통해 AI를 호출한다.
- AI 선택 결과에 선택된 행동, 후보 점수, 상위 후보, 판단 이유가 남는다.
- 공개 정보와 치터 전용 정보가 타입 또는 호출 경계에서 분리된다.
- 기존 전투 실행 테스트가 통과한다.
- 관측 변환과 후보 로그를 검증하는 테스트가 추가된다.

# RunAndBunAI 로직 이식 체크리스트

기준 로컬 경로:

```text
G:\2026 MineCraft\Cobblemon-RunAndBunAI
```

이 문서는 이전 AI에서 가져올 판단 로직을 추적한다. 원본 구현을 그대로 복사하지 않고, 현재 Cobbleventure 공통 AI 구조에 맞게 관측 필드, 후보 점수, 이유 로그 형태로 재구현한다.

## 이식 원칙

- `AiBattleObservation`과 `AiActionCandidate`만 보고 판단하게 만든다.
- 후보 기본 점수에 독립 규칙 보정을 더하고, 모든 보정은 `reasons[]`에 남긴다.
- 원본 AI의 테스트 시나리오는 우리 EvE/단위 테스트로 재작성한다.
- 상대 미공개 정보나 이번 턴 확정 행동은 치터 난이도 전용 입력으로만 다룬다.

## 1차 이식 상태

| 원본 규칙 | 우리 구현 위치 | 상태 | 메모 |
|---|---|---|---|
| `RunBunDecisionEngine` | `web-lab/lib/common-battle-ai.mjs` | 진행 중 | 기본 점수 + 규칙 보정 + 이유 로그 구조로 재구성 |
| `ImmediateKoResponseRule` | `moveRuleAdjustments()` | 1차 구현 | 상대 랭크업 위협에 선공/우선도 확정 KO 보너스 |
| `EntryHazardPlacementRule` | `moveRuleAdjustments()` | 1차 구현 | 스텔스록/압정/독압정/끈적끈적네트 설치 가치 |
| `SetupOpportunityRule` | `moveRuleAdjustments()` | 1차 구현 | 낮은 피격량이면 랭크업 보너스, KO 위험이면 페널티 |
| `SetupDisruptionRule` | `moveRuleAdjustments()` | 1차 구현 | 흑안개/클리어스모그/강제교체/도발 보정 |
| `FreeSetupTurnRule` | `evaluateSetupThreat()`, 후보 점수 규칙 | 1차 구현 | 회복/설치/무의미한 교체가 만드는 상대 스윕 위험과 남은 대응 자원 수 반영 |
| `SwitchMatchupRule` | `switchRuleAdjustments()` | 1차 구현 | 피격 감소, 공격 개선, 체력, 상태, 랭크 손실 보정 |
| `ImmediateKoDominanceRule` | `moveRuleAdjustments()` | 1차 구현 | 안전한 확정 KO가 있으면 비공격 행동 억제 |
| `ImmediateKoAttackPreferenceRule` | `moveRuleAdjustments()` | 1차 구현 | 안전한 확정 KO가 있으면 비마무리 공격 억제 |
| `KoBeforeActionPenaltyRule` | `moveRuleAdjustments()` | 1차 구현 | 상대에게 후공 확정 KO를 당해 실행할 수 없는 공격기 억제 |
| `GuaranteedKoSwitchPenaltyRule` | `switchRuleAdjustments()` | 1차 구현 | 안전한 확정 KO를 포기하는 교체 억제 |
| `RecoveryMoveValueRule` | `moveRuleAdjustments()` | 1차 구현 | 저체력/예상 피격 후 저체력 회복 가치 보정 |
| `PartingShotPivotRule` | `moveRuleAdjustments()`, `switchRuleAdjustments()` | 1차 구현 | 안전 피벗 기술 보너스와 즉시 교체 페널티 |
| `RepeatedSwitchPenaltyRule` | `switchRuleAdjustments()` | 1차 구현 | 직전 턴 교체 후 재교체 억제 |
| `LethalSwitchInRule` | `switchRuleAdjustments()` | 1차 구현 | 예상 공격에 진입 즉시 쓰러지는 교체 후보 억제 |
| `DynamaxSwitchPenaltyRule` | `switchRuleAdjustments()` | 1차 구현 | 남은 다이맥스 턴 포기 비용 반영 |
| 다이맥스 활성화 점수화 | `scoreAiDynamaxCandidate()` | 1차 구현 | 생존/화력 가치와 안전 랭크업 기회비용을 비교 |
| `OneTurnSearchEngine` | `evaluateBattleStateValue()`, `evaluateOneTurnBattleState()` | 1차 구현 | 기술·교체와 메가진화·다이맥스·테라스탈 후 기대 HP, 생존, 에이스, 랭크, 상태, 설치물, 기믹, 유일 카운터 가치를 후보 점수와 이유에 연결 |

## 다음 이식 후보

- [ ] `LowDamageSwitchRule`: 현재 포켓몬의 유효 피해가 너무 낮으면 교체를 적극 고려한다.
- [ ] `EmergencyCounterSwitchRule`: 상대 위협에 대한 유일/주요 카운터를 보존하고 투입한다.
- [x] 다이맥스 사용 자체를 점수화한다.
- [ ] `DynamaxMoveValueRule`: 다이맥스 기술의 날씨/필드/랭크 부가효과와 불필요한 다이월을 더 세밀하게 평가한다.
- [x] `OneTurnSearchEngine`: 자체 엔진 기술·교체·메가진화·다이맥스·테라스탈 후보를 1턴 기대 상태로 투영해 점수를 보정한다.
- [ ] Z기술과 Showdown 엔진 후보도 동일한 기믹 상태 투영 경로에 연결한다.

## 필요한 관측 필드

- [ ] 후보별 `actsBeforeOpponent`
- [x] 후보별 `actionBeforeThreatProbability`, `opponentKnockoutBeforeActionProbability`
- [ ] 후보별 `incomingDamageRatio`, `outgoingDamageRatio`
- [x] 상대 전개 위협도 `setupThreatTier`, `sweepRiskAfterSetup`, `availableAnswersAfterSetup`
- [ ] 상대 남은 포켓몬 수 `livingOpponents`
- [ ] 현재/교체 후보 상태 이상
- [ ] 현재 포켓몬의 양수 랭크 합계
- [ ] 상대 필드 설치물 레이어
- [x] 다이맥스 후보의 랭크업 기회비용과 격투 공격기 보유 여부
- [ ] 치터 난이도 전용 상대 확정 행동

## 자발적 교체 점수

자체 엔진의 일반 턴에서도 기술 후보와 벤치 교체 후보를 함께 평가한다. 기절 후 강제 교체와 달리 자발적 교체는 한 턴을 소비하므로, 교체 점수가 현재 최선 기술보다 난이도별 기준치 이상 높을 때만 실행한다.

### 현재 반영한 평가 요소

- [x] 현재 포켓몬과 교체 후보가 받을 상대 최강 공격의 예상 피해 비교
- [x] 현재 포켓몬과 교체 후보의 최선 반격 피해 비교
- [x] 교체 후보의 남은 HP와 스피드/우선도 기반 선공 가능성
- [x] 현재 포켓몬의 상태 이상 해소 가치와 교체 후보의 상태 이상 감점
- [x] 교체 시 사라지는 양의 랭크 감점
- [x] 스텔스록과 압정의 교체 직후 피해 및 즉사 위험
- [x] 트릭룸, 날씨, 필드 수혜 포켓몬의 교체 점수
- [x] 현재 유효타가 거의 없을 때 유효타를 가진 후보로 탈출하는 보너스
- [x] 현재 포켓몬은 다음 공격에 쓰러지고 후보는 버티는 긴급 교체 보너스
- [x] 교체 턴 피격 후 HP로 전투 상태를 투영하고 기술 후보를 다시 평가
- [x] 다음 행동 전에 쓰러지는 희생 교체와 에이스 무행동 소모 억제
- [x] AI 전략 성향에 따른 교체 후보 에이스 보존 비용
- [x] 직전 강제 출전 후 다시 교체하는 행동과 연속 교체 감점
- [x] 다이맥스 남은 턴을 버리고 교체하는 기회비용
- [x] 안전한 확정 KO가 있으면 자발적 교체 금지
- [x] 역린/구르기 등 교체 불가 강제 행동과 포획 상태에서 후보 생성 금지
- [x] EvE AI 판단 상세에 기술과 교체 후보 점수 및 근거를 함께 출력
- [x] 교체 후보 투영에서 전체 AI 재평가를 제거하고 전용 경량 평가기를 사용

### RunAndBunAI에서 가져온 기준

| 기준 | 반영 방식 |
| --- | --- |
| Defensive Improvement | 현재 예상 피격 비율보다 후보의 예상 피격 비율이 낮을수록 가산 |
| Offensive Improvement | 후보의 다음 턴 반격 비율이 현재 포켓몬보다 높을수록 가산 |
| No Effective Move Escape | 현재 유효타가 15% 이하이고 후보가 의미 있는 유효타를 가지면 가산 |
| Emergency Counter Switch | 현재 포켓몬의 예상 KO를 피하면서 반격 가능한 후보에 큰 가산 |
| Switch-In Resistance | 교체 직후 중립/약점 공격과 설치물 피해를 생존 위험으로 감점 |
| Guaranteed KO Penalty | 현재 포켓몬의 안전한 확정 KO를 포기하는 교체를 금지 |
| Repeated Switch Penalty | 직전 출전 직후 다시 빠지는 행동을 감점 |
| Dynamax Switch Penalty | 남은 다이맥스 턴 수에 비례해 감점하되 즉사 회피 시 완화 |

### 다음 확장

- [ ] 상대가 이번 턴 사용할 가능성이 높은 기술별 확률을 반영한 교체 기대값
- [x] 위협-카운터 맵의 `mustPreserveResources`를 자체 엔진 교체 및 자폭 희생 판단에 연결
- [x] 판 설치·제거 및 담당 위협 기준의 역할 완료 상태를 희생 교체와 자폭 비용에 연결
- [ ] 독압정 흡수, 끈적끈적네트, 날씨 피해까지 포함한 교체 직후 상태 시뮬레이션
- [ ] Pursuit류 교체 추격 위험을 AI 관측 모델에 연결
- [ ] Showdown 일반 턴의 자발적 교체 후보를 동일 산식으로 연결

# RunAndBunAI 로직 이식 체크리스트

기준 로컬 경로:

```text
G:\2026 MineCraft\Cobblemon-RunAndBunAI
```

이 문서는 이전 AI에서 가져올 판단 로직을 추적한다. 원본 구현을 그대로 복사하지 않고, 현재 Cobbleverse 공통 AI 구조에 맞게 관측 필드, 후보 점수, 이유 로그 형태로 재구현한다.

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
| `SwitchMatchupRule` | `switchRuleAdjustments()` | 1차 구현 | 피격 감소, 공격 개선, 체력, 상태, 랭크 손실 보정 |
| `ImmediateKoDominanceRule` | `moveRuleAdjustments()` | 1차 구현 | 안전한 확정 KO가 있으면 비공격 행동 억제 |
| `ImmediateKoAttackPreferenceRule` | `moveRuleAdjustments()` | 1차 구현 | 안전한 확정 KO가 있으면 비마무리 공격 억제 |
| `GuaranteedKoSwitchPenaltyRule` | `switchRuleAdjustments()` | 1차 구현 | 안전한 확정 KO를 포기하는 교체 억제 |
| `RecoveryMoveValueRule` | `moveRuleAdjustments()` | 1차 구현 | 저체력/예상 피격 후 저체력 회복 가치 보정 |
| `PartingShotPivotRule` | `moveRuleAdjustments()`, `switchRuleAdjustments()` | 1차 구현 | 안전 피벗 기술 보너스와 즉시 교체 페널티 |
| `RepeatedSwitchPenaltyRule` | `switchRuleAdjustments()` | 1차 구현 | 직전 턴 교체 후 재교체 억제 |
| `LethalSwitchInRule` | `switchRuleAdjustments()` | 1차 구현 | 예상 공격에 진입 즉시 쓰러지는 교체 후보 억제 |
| `DynamaxSwitchPenaltyRule` | `switchRuleAdjustments()` | 1차 구현 | 남은 다이맥스 턴 포기 비용 반영 |
| 다이맥스 활성화 점수화 | `scoreAiDynamaxCandidate()` | 1차 구현 | 생존/화력 가치와 안전 랭크업 기회비용을 비교 |

## 다음 이식 후보

- [ ] `LowDamageSwitchRule`: 현재 포켓몬의 유효 피해가 너무 낮으면 교체를 적극 고려한다.
- [ ] `EmergencyCounterSwitchRule`: 상대 위협에 대한 유일/주요 카운터를 보존하고 투입한다.
- [x] 다이맥스 사용 자체를 점수화한다.
- [ ] `DynamaxMoveValueRule`: 다이맥스 기술의 날씨/필드/랭크 부가효과와 불필요한 다이월을 더 세밀하게 평가한다.
- [ ] `OneTurnSearchEngine`: 1턴 얕은 시뮬레이션으로 후보 점수를 보정한다.

## 필요한 관측 필드

- [ ] 후보별 `actsBeforeOpponent`
- [ ] 후보별 `incomingDamageRatio`, `outgoingDamageRatio`
- [ ] 상대 전개 위협도 `setupThreatTier`
- [ ] 상대 남은 포켓몬 수 `livingOpponents`
- [ ] 현재/교체 후보 상태 이상
- [ ] 현재 포켓몬의 양수 랭크 합계
- [ ] 상대 필드 설치물 레이어
- [x] 다이맥스 후보의 랭크업 기회비용과 격투 공격기 보유 여부
- [ ] 치터 난이도 전용 상대 확정 행동

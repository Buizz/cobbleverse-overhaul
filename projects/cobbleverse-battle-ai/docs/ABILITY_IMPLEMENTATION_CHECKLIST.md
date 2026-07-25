# 자체 엔진 특성 구현 체크리스트

이 문서는 `cobbleverse-simple` 자체 전투 엔진의 특성 구현 현황을 추적한다. 목표는 특성을 조용히 무시하지 않고, 피해 계산·상태이상·교체·필드·기믹·AI 예상 피해에 같은 규칙을 적용하는 것이다.

## 원칙

- 특성 ID는 `cleanId` 기준 영문 ID로 관리한다.
- 한국어 이름은 문서 보조 정보로만 둔다.
- 특성 억제는 `activeAbility(pokemon)` 경유로 처리한다.
- `gastroacid` 등으로 억제된 특성은 피해 계산, 상태 면역, 부가 효과에서 모두 빠져야 한다.
- 구현 특성은 최소 단위 테스트를 가진다.
- 메가진화로 변경되는 특성은 기믹 활성화 직후 같은 턴 계산에 반영되어야 한다.
- AI 예상 피해는 실제 피해 공식과 같은 특성 보정을 사용해야 한다.
- 테스트에서 특성 누락을 확인해야 할 때는 `createSimpleBattle({ strictAbilityValidation: true, ... })`를 사용한다. 이 옵션이 켜진 전투는 `SUPPORTED_ABILITIES`에 없는 특성을 즉시 오류로 처리한다.
- 자체엔진 시나리오(`createNativeBattleSetup`)는 기본으로 `strictAbilityValidation: true`를 전달한다. PvE/EvE 자체엔진에서 새 특성이 빠지면 전투 시작 단계에서 오류가 나야 한다.

## 현재 구현 및 테스트 현황

| 상태 | ID | 한국어/설명 | 구현 위치 | 테스트 |
|------|----|-------------|-----------|--------|
| UNIT_TESTED | `hugepower` | 천하장사, 물리 공격 2배 | `effectiveStat(attack)` | `doubles physical Attack for Huge Power and Pure Power` |
| UNIT_TESTED | `purepower` | 순수한힘, 물리 공격 2배 | `effectiveStat(attack)` | `doubles physical Attack for Huge Power and Pure Power` |
| UNIT_TESTED | `guts` | 근성, 화상 공격 반감 무시 | `effectiveStat(attack)` | 화상·공격 보정 회귀 필요 |
| UNIT_TESTED | `levitate` | 부유, 땅에 닿지 않음 | `isGrounded` | `supports ability-changing utility moves` |
| UNIT_TESTED | `adaptability` | 적응력, 자속 2배 | `calculateDamageRange` | `supports ability-changing utility moves` 및 STAB 회귀 |
| UNIT_TESTED | `skilllink` | 스킬링크, 다중 타격 최대화 | `hitCountForMove` | `resolves fixed multi-hit moves and guaranteed critical hits per hit` |
| UNIT_TESTED | `plus` | 플러스, 기어업/자기장조작 대상 | native utility callback | 관련 유틸리티 테스트 |
| UNIT_TESTED | `minus` | 마이너스, 기어업/자기장조작 대상 | native utility callback | 관련 유틸리티 테스트 |
| UNIT_TESTED | `insomnia` | 불면, 잠듦 면역 및 Worry Seed 변경 대상 | `canReceiveStatus`, ability change | `applies simple status immunity abilities`, `supports ability-changing utility moves` |
| UNIT_TESTED | `vitalspirit` | 의기양양, 잠듦 면역 | `canReceiveStatus` | status immunity 회귀 필요 |
| UNIT_TESTED | `limber` | 유연, 마비 면역 | `canReceiveStatus` | `applies simple status immunity abilities` |
| UNIT_TESTED | `waterveil` | 수의베일, 화상 면역 | `canReceiveStatus` | `applies simple status immunity abilities` |
| UNIT_TESTED | `immunity` | 면역, 독/맹독 면역 | `canReceiveStatus` | `applies simple status immunity abilities` |
| UNIT_TESTED | `owntempo` | 마이페이스, 혼란 면역 | `applyVolatileStatus(confusion)` | `applies Pressure PP drain and Own Tempo confusion immunity` |
| UNIT_TESTED | `multiscale` | 멀티스케일, HP 최대일 때 피해 0.5배 | `fieldDamageModifier` | `applies simple defensive and entry abilities` |
| UNIT_TESTED | `shadowshield` | 스펙터가드, HP 최대일 때 피해 0.5배 | `fieldDamageModifier` | defensive ability 회귀 필요 |
| UNIT_TESTED | `thickfat` | 두꺼운지방, 불꽃/얼음 피해 0.5배 | `fieldDamageModifier` | `applies simple defensive and entry abilities` |
| UNIT_TESTED | `intimidate` | 위협, 등장 시 상대 공격 1랭크 하락 | `applyEntryAbilities` | `applies simple defensive and entry abilities` |
| UNIT_TESTED | `pressure` | 프레셔, 대상 공격 PP 추가 1 소모 | `executeMove` | `applies Pressure PP drain and Own Tempo confusion immunity` |
| UNIT_TESTED | `simple` | 단순, 랭크 변화량 2배 및 Simple Beam 변경 대상 | `applyBoosts`, ability change | `applies simple defensive and entry abilities` |
| UNIT_TESTED | `toughclaws` | 단단한발톱, 접촉기 위력 1.3배 | `calculateDamageRange`, `makesContact` | `boosts contact move damage for Tough Claws` |
| UNIT_TESTED | `download` | 다운로드, 등장 시 상대 방어/특방 비교 후 공격 또는 특공 1랭크 상승 | `applyEntryAbilities` | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `sturdy` | 옹골참, 풀피에서 일격 기절 피해를 1HP로 버팀 | `executeMove` damage prevention | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `speedboost` | 가속, 턴 종료 시 스피드 1랭크 상승 | `applyEndTurnEffects` | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `technician` | 테크니션, 위력 60 이하 공격 1.5배 | `calculateDamageRange` | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `mindseye` | 심안, 노말/격투 공격이 고스트에게 통함 | `moveEffectiveness` | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `teravolt` | 테라볼트, 상대 방어 특성 무시 | `ignoresDefenderAbility` | `supports common trainer abilities required by strict native scenarios` |
| IMPLEMENTED | `unseenfist` | 보이지않는주먹, 접촉 공격이 방어류를 관통 | `executeMove` protect check | strict 통과 회귀 |
| IMPLEMENTED | `pickpocket` | 나쁜손버릇, 접촉 피해를 받으면 공격자 아이템 훔침 | `executeMove` post-hit item steal | strict 통과 회귀 |
| IMPLEMENTED | `lightmetal` | 라이트메탈, 무게 기반 위력 계산 시 무게 절반 | `effectiveWeightPokemon` | strict 통과 회귀 |
| UNIT_TESTED | `chillingneigh` | 백의울음, 상대를 쓰러뜨리면 공격 1랭크 상승 | `applyKnockoutAbility` | `boosts Calyrex rider abilities after scoring a knockout` |
| UNIT_TESTED | `grimneigh` | 흑의울음, 상대를 쓰러뜨리면 특공 1랭크 상승 | `applyKnockoutAbility` | `boosts Calyrex rider abilities after scoring a knockout` |
| UNIT_TESTED | `asoneglastrier` | 혼연일체(백마), 백의울음 효과 적용 | `applyKnockoutAbility` | `boosts Calyrex rider abilities after scoring a knockout` |
| UNIT_TESTED | `asonespectrier` | 혼연일체(흑마), 흑의울음 효과 적용 | `applyKnockoutAbility` | `boosts Calyrex rider abilities after scoring a knockout` |

## 명시적 전투 효과 없음

| 상태 | ID | 설명 | 처리 |
|------|----|------|------|
| INTENTIONAL_NO_EFFECT | `runaway` | 트레이너 전투에서는 효과 없음 | `SUPPORTED_ABILITIES`에 등록해 strict 검증 통과, 전투 효과 없음 |
| INTENTIONAL_NO_EFFECT | `unnerve` | 긴장감, 상대 나무열매 사용 방지 | 현재 엔진에 자동 나무열매 소비가 없어 strict 검증 통과만 처리 |

## 특성 변경/억제 기술 현황

| 상태 | 기술 | 효과 |
|------|------|------|
| UNIT_TESTED | Gastro Acid | 대상 특성 억제 |
| UNIT_TESTED | Worry Seed | 대상 특성을 `insomnia`로 변경 |
| UNIT_TESTED | Role Play | 대상의 활성 특성을 복사 |
| IMPLEMENTED | Simple Beam | 대상 특성을 `simple`로 변경 |
| IMPLEMENTED | Entrainment | 대상 특성을 사용자 활성 특성으로 변경 |
| IMPLEMENTED | Skill Swap | 양쪽 특성을 교환 |
| IMPLEMENTED | Doodle | 대상 활성 특성을 복사 |
| IMPLEMENTED | Transform | 대상 특성 복사 |

## 우선 구현 대기열

### 피해 공식 보정

- [x] `hugepower`: 물리 공격 2배
- [x] `purepower`: 물리 공격 2배
- [x] `multiscale`: HP 최대일 때 피해 0.5배
- [x] `shadowshield`: HP 최대일 때 피해 0.5배
- [x] `thickfat`: 불꽃·얼음 피해 0.5배
- [x] `simple`: 랭크 변화량 2배
- [ ] `hustle`: 물리 공격 1.5배, 물리 명중 0.8배
- [ ] `gorillatactics`: 물리 공격 1.5배 및 기술 고정
- [ ] `slowstart`: 5턴 동안 공격·스피드 0.5배
- [ ] `defeatist`: HP 절반 이하 공격·특공 0.5배
- [ ] `flowergift`: 쾌청 중 공격·특방 보정
- [ ] `solarpower`: 쾌청 중 특공 1.5배 및 턴 종료 피해
- [ ] `tintedlens`: 반감 공격 피해 2배
- [ ] `sniper`: 급소 피해 추가 보정
- [x] `technician`: 위력 60 이하 기술 1.5배
- [ ] `reckless`: 반동기/점프킥류 위력 1.2배
- [ ] `ironfist`: 펀치 기술 1.2배
- [ ] `strongjaw`: 물기 기술 1.5배
- [ ] `megalauncher`: 파동 기술 1.5배
- [ ] `sheerforce`: 부가효과 기술 1.3배 및 부가효과 제거

### 타입·면역·상태

- [x] `levitate`: 지면 판정 제외
- [x] `limber`: 마비 면역
- [x] `waterveil`: 화상 면역
- [x] `immunity`: 독/맹독 면역
- [x] `insomnia`: 잠듦 면역
- [x] `vitalspirit`: 잠듦 면역
- [x] `owntempo`: 혼란 면역
- [ ] `waterabsorb`: 물 기술 무효 및 회복
- [ ] `stormdrain`: 물 기술 무효 및 특공 상승
- [ ] `dryskin`: 물 회복, 불꽃 약화/쾌청 피해/비 회복
- [ ] `voltabsorb`: 전기 기술 무효 및 회복
- [ ] `motordrive`: 전기 기술 무효 및 스피드 상승
- [ ] `lightningrod`: 전기 기술 유도/무효 및 특공 상승
- [ ] `flashfire`: 불꽃 기술 무효 및 불꽃 위력 강화
- [ ] `sapsipper`: 풀 기술 무효 및 공격 상승
- [ ] `heatproof`: 불꽃 피해 및 화상 피해 감소
- [ ] `oblivious`: 도발/헤롱헤롱 등 일부 상태 면역

### 날씨·필드

- [ ] `drizzle`: 등장 시 비
- [ ] `drought`: 등장 시 쾌청
- [ ] `sandstream`: 등장 시 모래바람
- [ ] `snowwarning`: 등장 시 눈
- [ ] `swiftswim`: 비 중 스피드 2배
- [ ] `chlorophyll`: 쾌청 중 스피드 2배
- [ ] `sandrush`: 모래바람 중 스피드 2배
- [ ] `slushrush`: 눈 중 스피드 2배
- [ ] `sandforce`: 모래바람 중 바위/땅/강철 위력 보정
- [ ] `icebody`: 눈 중 회복
- [ ] `raindish`: 비 중 회복

### 등장·교체·랭크

- [x] `intimidate`: 등장 시 상대 공격 -1
- [x] `pressure`: 대상 공격 PP 추가 1 소모
- [x] `plus`: 기어업/자기장조작 대상
- [x] `minus`: 기어업/자기장조작 대상
- [ ] `download`: 등장 시 공격 또는 특공 +1
- [ ] `trace`: 등장 시 상대 특성 복사
- [ ] `regenerator`: 교체 시 HP 1/3 회복
- [ ] `naturalcure`: 교체 시 상태 회복
- [ ] `moxie`: 직접 KO 후 공격 +1
- [ ] `beastboost`: KO 후 가장 높은 능력 +1
- [x] `speedboost`: 턴 종료 스피드 +1
- [ ] `contrary`: 랭크 변화 반전
- [ ] `competitive`: 능력 하락 시 특공 +2
- [ ] `defiant`: 능력 하락 시 공격 +2

### 방어·피해 무시

- [x] `sturdy`: 풀피 일격 기절 방지
- [ ] `wonderguard`: 효과 굉장한 공격 외 무효
- [ ] `magicguard`: 간접 피해 무효
- [ ] `overcoat`: 날씨 피해/가루 기술 무효
- [ ] `filter`: 효과 굉장한 피해 감소
- [ ] `solidrock`: 효과 굉장한 피해 감소
- [ ] `prismarmor`: 효과 굉장한 피해 감소
- [ ] `disguise`: 첫 직접 피해 흡수
- [ ] `iceface`: 물리 피해 1회 흡수

### 접촉·피격 반응

- [ ] `roughskin`: 접촉 피해 반사
- [ ] `ironbarbs`: 접촉 피해 반사
- [ ] `static`: 접촉 시 마비 확률
- [ ] `flamebody`: 접촉 시 화상 확률
- [ ] `poisontouch`: 접촉 공격 시 독 확률
- [ ] `effectspore`: 접촉 시 상태 확률
- [ ] `cutecharm`: 접촉 시 헤롱헤롱 확률
- [ ] `mummy`: 접촉 공격자의 특성을 미라로 변경
- [ ] `wandering spirit`: 접촉 시 특성 교환
- [ ] `aftermath`: 접촉 KO 시 피해

### 우선도·행동 제한

- [ ] `prankster`: 변화기 우선도 +1 및 악 타입 상호작용
- [ ] `galewings`: 풀피 비행 기술 우선도 +1
- [ ] `triage`: 회복기 우선도 +3
- [ ] `queenlymajesty`: 상대 선공기 차단
- [ ] `dazzling`: 상대 선공기 차단
- [ ] `armortail`: 상대 선공기 차단
- [ ] `stall`: 행동 순서 후순위
- [ ] `quickdraw`: 확률 선제 행동

## 테스트 규칙

새 특성을 구현할 때는 최소 다음 테스트를 추가한다.

- 기본 발동
- 억제 상태에서 미발동
- 교체 또는 폼 변경 경계
- 피해·상태·랭크·필드 이벤트 로그
- AI 예상 피해 또는 후보 점수에 반영되는지

특성이 `effectiveStat`, `calculateDamageRange`, `effectiveSpeed`, `isGrounded`, `applyStatus`, `applyEndTurnEffects`, `prepareActionOrder` 중 어디에 연결되는지 문서 표에 기록한다.

## 다음 작업 후보

1. 실제 트레이너 데이터에서 사용 중인 특성 ID 목록을 추출한다.
2. 사용 중인 특성을 이 문서의 대기열과 매칭해 누락 상태를 자동 보고한다.
3. 첫 관장/주요 트레이너 팀 기준으로 `intimidate`, `sturdy`, `waterabsorb`, `voltabsorb`, `flashfire`, `thickfat`을 우선 구현한다.
4. 특성 구현 상태를 `native-mechanics-coverage.json`와 같은 자동 산출물로 이동한다.

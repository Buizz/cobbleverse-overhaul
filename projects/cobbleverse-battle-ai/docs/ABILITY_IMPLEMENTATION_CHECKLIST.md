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
| UNIT_TESTED | `static` | 정전기, 접촉 피해를 받은 뒤 30%로 공격자를 마비 | `executeMove` post-hit contact reaction | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `rockhead` | 돌머리, 기술 자체 반동 피해 무효 | `executeMove` recoil prevention | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `competitive` | 승기, 상대에 의해 능력치가 내려가면 특공 2랭크 상승 | `applyBoosts` opponent stat-drop reaction | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `overgrow` | 심록, HP 1/3 이하에서 풀 타입 기술 위력 1.5배 | `calculateDamageRange` | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `intrepidsword` | 불요의검, 등장 시 공격 1랭크 상승 | `applyEntryAbilities` | `supports common trainer abilities required by strict native scenarios` |
| UNIT_TESTED | `shedskin` | 탈피, 턴 종료 시 33% 확률로 주요 상태이상 회복 | `applyEndTurnEffects` | `Shed Skin cures status at end of turn and respects suppression` |
| UNIT_TESTED | `analytic` | 애널라이즈, 대상보다 나중에 행동하면 공격 피해 약 1.3배 | `calculateDamageRange`, AI 피해 미리보기 | `boosts slower attacks with Analytic in previews and turn resolution` |
| UNIT_TESTED | `cursedbody` | 저주받은바디, 피해를 받으면 30% 확률로 사용 기술을 4턴 봉인 | `executeMove` post-hit | `Cursed Body disables the damaging move and respects suppression` |
| UNIT_TESTED | `synchronize` | 싱크로, 상대가 유발한 화상·마비·독을 상대에게 반사 | `applyStatus` | `reflects opponent-inflicted major status with Synchronize` |
| UNIT_TESTED | `rivalry` | 투쟁심, 같은 성별 대상 피해 1.25배·다른 성별 대상 피해 0.75배 | `normalizePokemon`, `calculateDamageRange` | `applies Rivalry only when both Pokemon have known genders` |
| UNIT_TESTED | `poisontouch` | 독수, 접촉 공격 적중 시 30% 확률로 대상에게 독 | `executeMove` post-hit contact reaction | `Poison Touch poisons on contact and respects suppression` |
| UNIT_TESTED | `serenegrace` | 하늘의은총, 기술 부가효과 확률 2배(최대 100%) | `secondaryEffectChance`, 실제 부가효과·AI 후보 점수 | `Serene Grace doubles secondary chances in resolution and AI scoring` |
| UNIT_TESTED | `steadfast` | 불굴의마음, 풀죽음으로 행동하지 못하면 스피드 +1 | `canAct` | `Steadfast raises Speed after flinching and respects suppression` |
| UNIT_TESTED | `aftermath` | 유폭, 접촉 공격으로 쓰러질 때 공격자 최대 HP의 1/4 피해 | `executeMove` post-KO contact reaction | `Aftermath damages a contact attacker after a knockout and can be bypassed` |
| UNIT_TESTED | `disguise` | 탈, 첫 직접 공격 1타 흡수 후 최대 HP의 1/8 피해 | `executeMove`, `aiDamageOutcomeProfile` | `Disguise absorbs the first hit, takes chip damage, and informs AI damage` |

## 2026 실전 파티 특성 구현 묶음

이번에 추가한 SV9 싱글 및 포챔스 샘플에서 사용하는 특성을 자체 엔진에 연결했다.

| 상태 | ID | 적용 규칙 | 엔진 연결점 |
|------|----|-----------|-------------|
| UNIT_TESTED | `blaze` | HP 1/3 이하 불꽃 기술 1.5배 | `calculateDamageRange` |
| UNIT_TESTED | `purifyingsalt` | 주요 상태이상 면역, 고스트 피해 절반 | `canReceiveStatus`, `abilityDamageModifier` |
| UNIT_TESTED | `supremeoverlord` | 기절한 아군 수에 따라 공격 피해 최대 1.5배 | `calculateDamageRange` |
| UNIT_TESTED | `protosynthesis` | 쾌청 또는 부스트에너지에서 최고 능력 보정 | `effectiveStat`, `applyEntryAbilities` |
| UNIT_TESTED | `quarkdrive` | 일렉트릭필드 또는 부스트에너지에서 최고 능력 보정 | `effectiveStat`, `applyEntryAbilities` |
| UNIT_TESTED | `chlorophyll` | 쾌청 중 스피드 2배 | `effectiveSpeed` |
| UNIT_TESTED | `sandrush` | 모래바람 중 스피드 2배 | `effectiveSpeed` |
| UNIT_TESTED | `drizzle` | 등장 시 비 시작 | `applyEntryAbilities`, `setFieldEffect` |
| UNIT_TESTED | `drought` | 등장 시 쾌청 시작 | `applyEntryAbilities`, `setFieldEffect` |
| UNIT_TESTED | `sandstream` | 등장 시 모래바람 시작 | `applyEntryAbilities`, `setFieldEffect` |
| UNIT_TESTED | `electricsurge` | 등장 시 일렉트릭필드 시작 | `applyEntryAbilities`, `setFieldEffect` |
| UNIT_TESTED | `hadronengine` | 등장 시 일렉트릭필드 시작, 필드 중 특수공격 4/3배 | `applyEntryAbilities`, `effectiveStat` |
| UNIT_TESTED | `orichalcumpulse` | 등장 시 쾌청 시작, 쾌청 중 공격 4/3배 | `applyEntryAbilities`, `effectiveStat` |
| UNIT_TESTED | `beastboost` | 상대를 쓰러뜨리면 현재 원 능력치 중 가장 높은 능력 +1 | `applyKnockoutAbility` |
| UNIT_TESTED | `overcoat` | 모래바람 피해와 가루 기술 무효 | `applyEndTurnEffects`, `executeMove` |
| UNIT_TESTED | `regenerator` | 자발적/기술 교체 시 최대 HP 1/3 회복 | `switchActivePokemon` |
| UNIT_TESTED | `naturalcure` | 교체 시 주요 상태이상과 맹독 누적 초기화 | `switchActivePokemon`, `curePokemonStatus` |
| UNIT_TESTED | `magnetpull` | 상대 강철 타입의 자발적 교체 차단 | `buildActions`, `chooseSimpleAiDecision` |
| UNIT_TESTED | `dauntlessshield` | 전투 중 첫 등장에 한 번 방어 +1 | `applyEntryAbilities` |
| UNIT_TESTED | `hypercutter` | 상대가 유발한 공격 하락 차단 | `applyBoosts` |
| UNIT_TESTED | `roughskin` | 접촉 공격자에게 최대 HP 1/8 피해 | `executeMove` post-hit |
| UNIT_TESTED | `ironbarbs` | 접촉 공격자에게 최대 HP 1/8 피해 | `executeMove` post-hit |
| UNIT_TESTED | `unaware` | 공격·피격 시 상대 능력 랭크 변화 무시 | `damageBase`, `effectiveStat` |
| UNIT_TESTED | `flamebody` | 접촉 공격자에게 30% 화상 | `executeMove` post-hit |
| UNIT_TESTED | `stamina` | 피해를 받을 때마다 방어 +1 | `executeMove` post-hit |
| UNIT_TESTED | `toxicdebris` | 물리 피해를 받으면 상대 필드에 독압정 | `executeMove` post-hit |
| UNIT_TESTED | `lightningrod` | 전기 기술 무효 및 특공 +1 | `moveEffectiveness`, `executeMove` |
| UNIT_TESTED | `goodasgold` | 자신을 대상으로 하는 변화기 차단 | `executeMove` status gate |
| UNIT_TESTED | `magicbounce` | 상태·랭크·설치 변화기를 사용자에게 반사 | `executeMove`, `reflectStatusMove` |
| UNIT_TESTED | `baddreams` | 잠든 상대에게 턴 종료 최대 HP 1/8 피해 | `applyEndTurnEffects` |
| UNIT_TESTED | `galewings` | 풀 HP에서 비행 기술 우선도 +1 | `prepareActionOrder`, `aiDisplayMoveData` |
| UNIT_TESTED | `armortail` | 상대의 우선도 기술 차단 | `executeMove` priority gate |
| UNIT_TESTED | `liquidvoice` | 소리 기술을 물 타입으로 변환 | `abilityModifiedMove`, AI 피해 미리보기 |
| FORMAT_LIMITED | `hospitality` | 더블에서 아군 회복 | 현재 자체 엔진이 싱글 전용이므로 strict 검증은 통과하되 발동 대상 없음 |
| UNIT_TESTED | `innerfocus` | 풀죽음 및 위협에 의한 공격 하락 무효 | `applyVolatileStatus`, `applyBoosts` |
| UNIT_TESTED | `clearbody` | 상대가 유발한 능력 하락 무효 | `applyBoosts` |
| UNIT_TESTED | `whitesmoke` | 상대가 유발한 능력 하락 무효 | `applyBoosts` |
| UNIT_TESTED | `swiftswim` | 비/폭우 중 스피드 2배 | `effectiveSpeed` |
| UNIT_TESTED | `torrent` | HP 1/3 이하에서 물 기술 위력 1.5배 | `calculateDamageRange` |
| UNIT_TESTED | `battlearmor` | 급소 공격 무효 | 실제 급소 판정, AI 피해 예측 |
| UNIT_TESTED | `shellarmor` | 급소 공격 무효 | 실제 급소 판정, AI 피해 예측 |
| UNIT_TESTED | `ironfist` | 펀치 기술 위력 1.2배 | `calculateDamageRange` |
| UNIT_TESTED | `strongjaw` | 물기 기술 위력 1.5배 | `calculateDamageRange` |
| UNIT_TESTED | `sharpness` | 베기 기술 위력 1.5배 | `calculateDamageRange` |
| UNIT_TESTED | `tintedlens` | 반감 공격 피해 2배 | `calculateDamageRange` |
| UNIT_TESTED | `filter` | 효과 굉장한 피해 0.75배 | `calculateDamageRange` |
| UNIT_TESTED | `solidrock` | 효과 굉장한 피해 0.75배 | `calculateDamageRange` |
| UNIT_TESTED | `prismarmor` | 효과 굉장한 피해 0.75배 | `calculateDamageRange` |
| UNIT_TESTED | `moxie` | 직접 KO 후 공격 1랭크 상승 | `applyKnockoutAbility` |
| UNIT_TESTED | `snowwarning` | 등장 시 눈 시작 | `applyEntryAbilities`, `setFieldEffect` |
| UNIT_TESTED | `noguard` | 자신과 상대의 기술이 명중률·회피를 무시 | `effectiveAccuracy`, AI 후보 명중률 |
| UNIT_TESTED | `compoundeyes` | 기술 명중률 1.3배 | `effectiveAccuracy`, AI 후보 명중률 |
| UNIT_TESTED | `keeneye` | 명중률 하락 방지, 상대 회피 상승 무시 | `applyBoosts`, `effectiveAccuracy` |
| UNIT_TESTED | `sandveil` | 모래바람에서 상대 명중률 0.8배 | `effectiveAccuracy`, AI 후보 명중률 |
| UNIT_TESTED | `snowcloak` | 눈에서 상대 명중률 0.8배 | `effectiveAccuracy`, AI 후보 명중률 |
| UNIT_TESTED | `hustle` | 물리 위력 1.5배, 물리 명중률 0.8배 | `calculateDamageRange`, `effectiveAccuracy` |
| UNIT_TESTED | `scrappy` | 노말·격투 기술로 고스트를 공격 가능 | `moveEffectiveness` |
| UNIT_TESTED | `infiltrator` | 대타출동·리플렉터·빛의장막·오로라베일 관통 | `executeMove`, `fieldDamageModifier` |
| UNIT_TESTED | `wonderguard` | 효과가 굉장한 공격 외 직접 공격 무효 | `moveEffectiveness` |
| UNIT_TESTED | `voltabsorb` | 전기 기술 무효 및 최대 HP 1/4 회복 | `absorbingAbilityForMove`, `executeMove` |
| UNIT_TESTED | `stormdrain` | 물 기술 무효 및 특공 1랭크 상승 | `absorbingAbilityForMove`, `executeMove` |
| UNIT_TESTED | `dryskin` | 물 기술 회복, 불꽃 피해 증가, 비 회복·쾌청 피해 | 피해·턴 종료·AI 예상 피해 |
| UNIT_TESTED | `flashfire` | 불꽃 기술 무효 및 이후 불꽃 위력 1.5배 | 흡수 발동, `calculateDamageRange` |
| UNIT_TESTED | `sapsipper` | 풀 기술 무효 및 공격 1랭크 상승 | 흡수 발동, `applyBoosts` |
| UNIT_TESTED | `eartheater` | 땅 기술 무효 및 최대 HP 1/4 회복 | 흡수 발동 |
| UNIT_TESTED | `soundproof` | 소리 기술 무효 | `absorbingAbilityForMove` |
| UNIT_TESTED | `wellbakedbody` | 불꽃 기술 무효 및 방어 2랭크 상승 | 흡수 발동, `applyBoosts` |
| UNIT_TESTED | `fluffy` | 접촉 피해 절반, 불꽃 피해 2배 | `abilityDamageModifier` |
| UNIT_TESTED | `furcoat` | 물리 피해 절반 | `abilityDamageModifier` |
| UNIT_TESTED | `heatproof` | 불꽃 피해 절반 및 화상 피해 절반 | 피해·턴 종료·AI 예상 피해 |
| UNIT_TESTED | `sheerforce` | 부가효과 기술 위력 1.3배, 부가효과와 생명의구슬 반동 제거 | 피해 계산, 실제 후처리 |

## 명시적 전투 효과 없음

| 상태 | ID | 설명 | 처리 |
|------|----|------|------|
| INTENTIONAL_NO_EFFECT | `runaway` | 트레이너 전투에서는 효과 없음 | `SUPPORTED_ABILITIES`에 등록해 strict 검증 통과, 전투 효과 없음 |
| INTENTIONAL_NO_EFFECT | `unnerve` | 긴장감, 상대 나무열매 사용 방지 | 현재 엔진에 자동 나무열매 소비가 없어 strict 검증 통과만 처리 |
| FORMAT_LIMITED | `hospitality` | 대접, 등장 시 같은 편 아군 회복 | 현재 싱글 전용 자체 엔진에는 회복 대상이 없으므로 strict 검증 통과만 처리 |

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
- [x] `overgrow`: HP 1/3 이하 풀 타입 기술 1.5배
- [x] `blaze`: HP 1/3 이하 불꽃 타입 기술 1.5배
- [x] `purifyingsalt`: 고스트 피해 0.5배
- [x] `supremeoverlord`: 기절한 아군 수에 따른 피해 보정
- [x] `protosynthesis`: 쾌청/부스트에너지 최고 능력 보정
- [x] `quarkdrive`: 일렉트릭필드/부스트에너지 최고 능력 보정
- [x] `hustle`: 물리 공격 1.5배, 물리 명중 0.8배
- [ ] `gorillatactics`: 물리 공격 1.5배 및 기술 고정
- [ ] `slowstart`: 5턴 동안 공격·스피드 0.5배
- [ ] `defeatist`: HP 절반 이하 공격·특공 0.5배
- [ ] `flowergift`: 쾌청 중 공격·특방 보정
- [ ] `solarpower`: 쾌청 중 특공 1.5배 및 턴 종료 피해
- [x] `tintedlens`: 반감 공격 피해 2배
- [ ] `sniper`: 급소 피해 추가 보정
- [x] `technician`: 위력 60 이하 기술 1.5배
- [ ] `reckless`: 반동기/점프킥류 위력 1.2배
- [x] `ironfist`: 펀치 기술 1.2배
- [x] `strongjaw`: 물기 기술 1.5배
- [ ] `megalauncher`: 파동 기술 1.5배
- [x] `sheerforce`: 부가효과 기술 1.3배 및 부가효과 제거
- [x] `serenegrace`: 기술 부가효과 확률 2배(최대 100%)

### 타입·면역·상태

- [x] `levitate`: 지면 판정 제외
- [x] `limber`: 마비 면역
- [x] `waterveil`: 화상 면역
- [x] `immunity`: 독/맹독 면역
- [x] `insomnia`: 잠듦 면역
- [x] `vitalspirit`: 잠듦 면역
- [x] `owntempo`: 혼란 면역
- [x] `waterabsorb`: 물 기술 무효 및 회복
- [x] `stormdrain`: 물 기술 무효 및 특공 상승
- [x] `dryskin`: 물 회복, 불꽃 피해 증가/쾌청 피해/비 회복
- [x] `voltabsorb`: 전기 기술 무효 및 회복
- [ ] `motordrive`: 전기 기술 무효 및 스피드 상승
- [x] `lightningrod`: 전기 기술 무효 및 특공 상승. 더블의 대상 유도는 포맷 확장 시 추가
- [x] `flashfire`: 불꽃 기술 무효 및 불꽃 위력 강화
- [x] `sapsipper`: 풀 기술 무효 및 공격 상승
- [x] `heatproof`: 불꽃 피해 및 화상 피해 감소
- [ ] `oblivious`: 도발/헤롱헤롱 등 일부 상태 면역

### 날씨·필드

- [x] `drizzle`: 등장 시 비
- [x] `drought`: 등장 시 쾌청
- [x] `sandstream`: 등장 시 모래바람
- [x] `snowwarning`: 등장 시 눈
- [x] `swiftswim`: 비 중 스피드 2배
- [x] `chlorophyll`: 쾌청 중 스피드 2배
- [x] `sandrush`: 모래바람 중 스피드 2배
- [ ] `slushrush`: 눈 중 스피드 2배
- [ ] `sandforce`: 모래바람 중 바위/땅/강철 위력 보정
- [ ] `icebody`: 눈 중 회복
- [ ] `raindish`: 비 중 회복
- [x] `hydration`: 비/폭우 턴 종료 시 상태이상 피해 전에 주요 상태이상 회복 (`applyEndTurnEffects`)

### 폼·타입

- [x] `multitype`: 전투 상태 생성 시 플레이트에 맞춰 아르세우스의 타입과 원래 타입 변경 (`normalizePokemon`, `heldItemType`)

### 등장·교체·랭크

- [x] `intimidate`: 등장 시 상대 공격 -1
- [x] `pressure`: 대상 공격 PP 추가 1 소모
- [x] `plus`: 기어업/자기장조작 대상
- [x] `minus`: 기어업/자기장조작 대상
- [x] `download`: 등장 시 공격 또는 특공 +1
- [x] `intrepidsword`: 등장 시 공격 +1
- [ ] `trace`: 등장 시 상대 특성 복사
- [x] `regenerator`: 교체 시 HP 1/3 회복
- [x] `naturalcure`: 교체 시 상태 회복
- [x] `moxie`: 직접 KO 후 공격 +1
- [x] `beastboost`: KO 후 가장 높은 능력 +1
- [x] `speedboost`: 턴 종료 스피드 +1
- [x] `steadfast`: 풀죽음으로 행동하지 못하면 스피드 +1
- [ ] `contrary`: 랭크 변화 반전
- [x] `competitive`: 상대에 의한 능력 하락 시 특공 +2
- [ ] `defiant`: 능력 하락 시 공격 +2

### 방어·피해 무시

- [x] `vesselofruin`: 필드에 있는 동안 상대 특수공격 25% 감소 (`damageBase`)
- [x] `sturdy`: 풀피 일격 기절 방지
- [x] `unaware`: 공격·피격 시 상대 능력 랭크 변화 무시
- [x] `wonderguard`: 효과 굉장한 공격 외 무효
- [x] `rockhead`: 기술 반동 피해 무효
- [ ] `magicguard`: 간접 피해 무효
- [x] `overcoat`: 날씨 피해/가루 기술 무효
- [x] `filter`: 효과 굉장한 피해 감소
- [x] `solidrock`: 효과 굉장한 피해 감소
- [x] `prismarmor`: 효과 굉장한 피해 감소
- [x] `disguise`: 첫 직접 공격 1타 흡수 후 최대 HP 1/8 피해
- [ ] `iceface`: 물리 피해 1회 흡수

### 접촉·피격 반응

- [x] `roughskin`: 접촉 피해 반사
- [x] `ironbarbs`: 접촉 피해 반사
- [x] `static`: 접촉 피해를 준 공격자 마비 확률
- [x] `flamebody`: 접촉 시 화상 확률
- [x] `poisontouch`: 접촉 공격 시 독 확률
- [ ] `effectspore`: 접촉 시 상태 확률
- [ ] `cutecharm`: 접촉 시 헤롱헤롱 확률
- [ ] `mummy`: 접촉 공격자의 특성을 미라로 변경
- [ ] `wandering spirit`: 접촉 시 특성 교환
- [x] `aftermath`: 접촉 KO 시 피해

### 우선도·행동 제한

- [ ] `prankster`: 변화기 우선도 +1 및 악 타입 상호작용
- [x] `galewings`: 풀피 비행 기술 우선도 +1
- [ ] `triage`: 회복기 우선도 +3
- [ ] `queenlymajesty`: 상대 선공기 차단
- [ ] `dazzling`: 상대 선공기 차단
- [x] `armortail`: 상대 선공기 차단
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

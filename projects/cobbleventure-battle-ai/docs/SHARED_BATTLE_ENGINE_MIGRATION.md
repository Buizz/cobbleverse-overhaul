# 웹 전투 엔진 KMP 이전 기록

> 상태: 완료 (2026-08-19)
> 최종 구조, 검증 결과와 운영 인계는
> [공유 전투 AI 이관 완료 보고서](SHARED_BATTLE_AI_MIGRATION_COMPLETION.md)를 기준으로 한다.

## 현재 사실

현재 웹과 Minecraft는 승률 모델, 후보 점수 규칙, 1턴/2턴 탐색 알고리즘과 탐색용 한 턴
상태 전이를 공유한다. 실제 전투 실행기의 기술·특성·도구 연쇄 이벤트 중 피격·접촉 반응은
공통 코어가 순서 있는 명령으로 판정하며, 나머지 상태 이동형 이벤트는 웹 엔진과 Cobblemon 서버가 실행한다.

| 책임 | 웹 실험실 | Minecraft | 현재 단일 원천 여부 |
| --- | --- | --- | --- |
| 후보 점수 규칙 | `shared-ai-core` Kotlin/JS | `shared-ai-core` JVM | 예 |
| 승률·2턴 expectimax | `shared-ai-core` Kotlin/JS | `shared-ai-core` JVM | 예 |
| 후보 생성 | 웹 관측 어댑터 + KMP 정규화·합법성 | Cobblemon 관측 어댑터 + 같은 KMP 처리 | 일부(관측 추출 제외 공통) |
| 탐색용 한 턴 상태 전이 | `SharedSearchProjectionRuntime` Kotlin/JS | 같은 런타임 JVM | 예 |
| 피해 기본식·범위·롤 | `shared-ai-core` Kotlin/JS | `shared-ai-core` JVM | 예 |
| 피해 STAB·상성 판정 | `shared-ai-core` Kotlin/JS | `shared-ai-core` JVM | 예 |
| 피해 특성·도구·필드 배율 | `shared-ai-core` Kotlin/JS | `shared-ai-core` JVM | 예 |
| 유효 전투 능력치 투영 | `shared-ai-core` Kotlin/JS | `shared-ai-core` JVM | 예 |
| 피해 생존 판정·HP 결과·이벤트 | `shared-ai-core` Kotlin/JS 결과 패치 | `shared-ai-core` JVM | 예 |
| 속도 추가 배율 | KMP 행동 순서와 웹 미리보기 혼재 | KMP 행동 순서와 JVM 보정 | 일부 |
| 턴 종료·지속 효과 | 웹 자체 엔진 | 축약 JVM 상태 | 아니요 |

`web-lab/lib/cobbleventure-battle-engine.mjs`는 약 18,000줄이며 실제 웹 전투 상태기계를
소유한다. 기존 Java `ai-api`와 `ai-engine`은 계약·점수 선택 골격이고 전투 상태 전이를
구현하지 않는다. 따라서 Java 코드를 그대로 연결하는 방식으로는 현재 웹 동작을 재사용할
수 없다.

## 이전 단위

기술이나 기믹 이름별로 JVM에 규칙을 추가하지 않는다. 아래 계층을 순서대로 통째로 KMP로
이전한다.

1. 직렬화 가능한 전투 상태, 명령, 이벤트, RNG 상태
2. 합법 행동 생성과 명령 검증
3. 우선도·속도·교체를 포함한 행동 순서 결정
4. 피해 파이프라인과 기술 실행
5. 상태·랭크·필드·설치물·날씨의 일반 효과 처리
6. 턴 종료, 지속 시간, 기절, 강제 교체와 승패
7. 기술·특성·도구의 데이터 기반 효과 핸들러
8. AI 후보 생성과 상태 전이 런타임

각 계층을 옮길 때 웹의 해당 JavaScript 함수는 KMP 호출로 교체한다. JVM 전용 구현을 먼저
추가한 뒤 웹에 별도로 맞추는 작업은 금지한다.

## 진행 상태

- 1단계 공통 계약 도입 완료: `SharedBattleModel.kt`
  - 전투·필드·사이드·포켓몬·기술 상태
  - 기술·특성·도구의 동적 상태를 보존하는 확장 필드
  - 기술·교체·아이템 명령과 공통 전투 이벤트
  - 웹 기존 `xorshift32`와 같은 `SharedBattleRng`
- JVM/commonTest와 Kotlin/JS 웹 브리지에서 동일 상태 정규화 및 RNG 벡터를 검증한다.
- 웹 상태와 공통 상태 사이의 무손실 어댑터 도입 완료: `shared-battle-state-adapter.mjs`
  - 초기 상태와 실제 1턴 해결 후 상태를 JSON 기준으로 완전 왕복 검증한다.
  - 이벤트, RNG, 마지막 성공 기술, 동적 상태와 미승격 확장 필드를 보존한다.
  - 기술·교체·아이템·잠금 기술 명령을 공통 명령 계약으로 왕복한다.
- 기믹 활성화 이후 최종 행동 순서 결정의 KMP 이전 완료: `SharedActionOrderEvaluator.kt`
  - 기술·교체·아이템 우선도, 트릭룸과 유효 스피드
  - Prankster, Gale Wings, Grassy Glide, Thunderclap, Max Guard
  - 날씨 스피드 특성, Tailwind, Choice Scarf, 마비, Unburden, 패러독스 부스트
  - Quick Draw, Quick Claw, Custap Berry와 Pursuit 교체 가로채기
  - 순서 판정에 소비한 RNG 상태를 웹 전투 실행에 그대로 이어 준다.
- 웹의 기존 `sortActions`, `markPursuitIntercepts`, `effectiveMovePriority`는 제거되었고 실제
  `prepareActionOrder`가 Kotlin/JS 공통 평가 결과를 사용한다.
- 실제 명령을 행동으로 만드는 KMP 이전 완료: `SharedActionBuildEvaluator.kt`
  - 충전 기술과 Encore·난동·Choice·Gorilla Tactics 잠금
  - PP 소진 및 Disable 시 대체 기술 선택
  - 아이템·교체 명령, 자발 교체 대상 검증
  - 볼래틸 트랩, Shadow Tag, Arena Trap, Magnet Pull과 표준 예외
  - 유효하지 않은 Encore·잠금·충전 상태 정리 지시
- 웹 `buildActions`는 공통 결과를 기존 포켓몬/기술 객체에 연결만 한다. AI 후보 화면에 남은
  `lockedMoveSelection`과 `isPokemonTrapped` 조회는 이후 AI 후보 생성 계층 이전에서 제거한다.
- 피해 산술의 KMP 이전 완료: `SharedDamageCalculator.kt`
  - 레벨·위력·공격·방어로부터 기본 피해를 계산하는 공식
  - STAB·상성·도구·특성·필드 배율을 적용한 최소/최대 피해
  - 급소·난수 배율과 남은 HP 상한을 적용한 실제 피해 롤
  - 웹의 실제 공격, 미래 공격과 AI 피해 미리보기가 동일 Kotlin/JS 산술을 사용한다.
- 피해 타입 계층의 KMP 이전 완료: `SharedDamageTypeEvaluator.kt`
  - 일반 상성표와 STAB, Adaptability, 테라·스텔라 STAB
  - 타입 흡수 특성과 Mold Breaker·Teravolt 무시
  - Scrappy·Mind's Eye, Freeze-Dry, Flying Press, Wonder Guard
  - Tera Shell과 Delta Stream 약점 제거
  - 웹의 일반 피해 실행과 AI 미리보기가 최소 공통 요청 계약을 통해 같은 판정을 사용한다.
- 비타입 피해 배율의 KMP 이전 완료: `SharedDamageModifierEvaluator.kt`
  - Life Orb·타입 강화·Gem·Expert Belt·Metronome·Ogerpon Mask 등 공격 도구
  - Tough Claws·Technician·Sheer Force·저체력 특성·오라·Ruin 방어 등 공격/방어 특성
  - Multiscale·Fluffy·Thick Fat·Filter 계열 등 방어 배율
  - 날씨·지형·Helping Hand·Tar Shot·스크린·반감 열매
  - 웹의 기존 `abilityDamageModifier`, `fieldDamageModifier`와 `calculateDamageRange` 내부 배율
    분기는 제거되었다.
  - 타입·비타입 평가기는 KMP 내부의 단일 `SharedDamageFactorsEvaluator` 요청으로 연쇄 실행한다.
    웹 2턴 탐색은 동일한 직렬화 요청 결과만 제한 크기 캐시에 보관하며 규칙을 재구현하지 않는다.
- 유효 능력치와 피해 범위 파이프라인의 KMP 이전 완료: `SharedEffectiveStatEvaluator.kt`
  - 랭크, 급소 랭크 무시, Unaware와 Ruin 특성
  - Choice 계열·Light Ball·Eviolite·Assault Vest
  - 화상·Guts·Defeatist·Flower Gift·날씨/지형·패러독스 능력치
  - 웹 `effectiveStat`이 공통 평가기를 사용하며 Shell Side Arm·Photon Geyser·속도 미리보기에도 적용된다.
  - `SharedDamagePipelineEvaluator`가 공격/방어 능력치, 타입/비타입 배율, 기본 피해와 범위를
    단일 KMP 요청으로 계산한다.
- 피해 적용 파이프라인의 KMP 이전 완료: `SharedDamageApplicationEvaluator.kt`
  - False Swipe·Endure·Sturdy·Focus Sash·Focus Band의 생존 판정과 RNG 소비
  - 면역, Substitute, Disguise, 급소, HP·대타 HP 결과와 피해/특성/볼래틸 이벤트
  - 일반 간접 피해의 Magic Guard 판정, HP 상한, 기절 결과와 피해 이벤트
  - 웹의 실제 공격과 혼란 자해가 KMP 결과 패치를 적용하며, 미래 공격을 포함한 모든
    `applyDirectDamage` 호출도 같은 Kotlin/JS 평가기를 사용한다.
  - JVM/commonTest와 Kotlin/JS 전체 웹 회귀 테스트에서 기띠·대타·탈·면역·미래 공격 경로를 검증한다.
- 이로써 **피해 파이프라인 단계는 완료**되었다. 다음 큰 이전 단위는 명중 이후 기술 실행 효과
  (접촉 반응, 반동·흡수, 2차 효과와 기술별 후처리)를 KMP로 이동하는 작업이다.
- 명중 이후 기술 실행 결과의 KMP 이전 완료: `SharedPostHitEvaluator.kt`
  - Punching Glove·Protective Pads를 포함한 유효 접촉/접촉 반응 가능 여부
  - 흡수량과 Liquid Ooze 반전, Shell Bell 회복량, 반동·Life Orb·자폭성 HP 비용
  - Sparkling Aria·Wake-Up Slap·Smelling Salts의 상태 해제 결과
  - Sheer Force의 2차 효과 억제와 Life Orb 비용 제거, Shield Dust·Covert Cloak 차단
  - Serene Grace를 반영한 각 2차 효과의 순차 RNG 판정과 RNG 상태 인계
  - Fake Out, Salt Cure, Clear Smog, Rapid Spin, Stone Axe, Mortal Spin 등 기술별 명중 후 처리를
    공통 instruction 목록으로 반환한다. 웹은 instruction을 기존 범용 상태 적용기에 연결만 한다.
  - 실제 전투와 AI 미리보기의 접촉·2차 효과 확률/차단 판정이 모두 같은 Kotlin/JS 평가기를 사용한다.
- 이로써 **명중 이후 기술 효과 단계는 완료**되었다. 접촉 시 발동하는 개별 특성·도구 효과의
  상태 변경은 기술 후처리가 아니라 데이터 기반 효과 핸들러 단계에서 일반화한다.
- 상태·랭크·필드·설치물 범용 적용기의 KMP 이전 완료: `SharedEffectApplicationEvaluator.kt`
  - 화상·마비·독·맹독·수면·얼음의 타입/특성 면역, Safeguard, Misty/Electric Terrain,
    Flower Veil·Sweet Veil·Leaf Guard와 수면 턴 RNG
  - 볼래틸 중복 방지와 지속 시간, Aroma Veil·Own Tempo·Inner Focus·Oblivious 차단,
    Grip Claw 구속 턴과 Perish Song 카운트
  - 랭크 상하한, Contrary·Simple, Mirror Armor 반사, Flower Veil·Clear Amulet·Clear Body,
    Hyper Cutter·Keen Eye 등 랭크 하락 차단
  - 영구 날씨 교체 차단, 날씨 바위·Terrain Extender 지속 시간
  - Spikes·Toxic Spikes 레이어, Stealth Rock·Sticky Web 중복 방지, Light Clay와 일반
    사이드 조건 지속 시간
  - 웹의 `canReceiveStatus`, `applyStatus`, `applyVolatileStatus`, `applyBoosts`,
    `setFieldEffect`, `setSideCondition`은 KMP 결과 패치를 적용하는 어댑터가 되었다.
  - Synchronize·Poison Puppeteer·Competitive·Defiant 같은 연쇄 발동은 공통 기본 적용기가
    반환한 결과 뒤에 실행되며, 개별 특성의 데이터 기반 핸들러 통합 대상이다.
- 이로써 **상태·랭크·필드·설치물 범용 적용 단계는 완료**되었다.
- 턴 종료 범용 전이의 KMP 이전 완료: `SharedEndTurnEvaluator.kt`
  - 화상·독·맹독, Poison Heal·Heatproof·Magic Guard와 맹독 카운터
  - Dry Skin·Solar Power·Ice Body의 날씨 기반 피해/회복
  - Bad Dreams, 모래바람, Grassy Terrain, Ingrain, Aqua Ring, Speed Boost
  - Leech Seed, Curse, Nightmare, Salt Cure, Octolock, Perish Song, 구속 피해
  - Leftovers·Black Sludge의 잔여 회복 순서와 수치
  - 날씨·지형·의사 날씨·사이드 조건·볼래틸 지속 시간 감소와 Yawn 만료
  - 다이맥스 턴 감소와 종료 시 HP 비율 복원
  - 기절 후 교체 필요 여부·수동/자동 교체 구분·유효 교체 슬롯과 최종 승패 판정을
    공통 평가기가 소유한다.
  - 웹의 `applyEndTurnEffects`, `advanceTimedEffects`, `expireDynamax`,
    `advanceFaintedSides`, `replaceFaintedPokemon`은 KMP 연산 결과를 상태와 이벤트에 반영한다.
  - AI의 턴 종료 예상 피해도 같은 KMP 잔여 전이를 사용해 실제 전투와 별도 계산식을 갖지 않는다.
  - Shed Skin·Hydration·Dry Skin·Solar Power·Ice Body, Harvest·Pickup과 폼 갱신처럼
    런타임 연쇄 이벤트가 필요한 개별 핸들러는 공통 잔여 전이 앞뒤의 어댑터로 유지한다.
  - 자동 교체 대상의 전술적 점수 계산은 아직 웹 후보 생성기에 있으며 최종 후보 생성 이전에서
    공통화한다. 교체 필요 여부와 선택 가능 슬롯은 이미 KMP 판정을 사용한다.
- 이로써 **턴 종료·지속 시간·기절/교체 요구·승패의 범용 판정 단계는 완료**되었다.
- 탐색 상태 전이와 후보 합법성의 KMP 통합 완료: `SharedSearchProjectionRuntime.kt`
  - HP·최대 HP, 교체, 아이템 수량·회복, 기믹 소비, 랭크, 설치물, Yawn·수면,
    Salt Cure·Toxic 압박과 Baton Pass를 직렬화 가능한 단일 투영 상태로 처리한다.
  - 조건부 선공기의 성공 확률도 행동 계약에 포함해 웹과 JVM이 같은 예상 피해를 적용한다.
  - 웹 `sharedSearchPolicyRuntime.transitionCallback`은 더 이상 `simulateSimpleTurn`을 호출하지
    않고 Kotlin/JS 전이를 호출한다.
  - Minecraft `CobblemonBattleSearch.transitionState`의 수동 상태 변경 분기는 제거되었고 같은
    JVM 전이를 호출한다. 양쪽 후보 목록은 공통 `legalCandidates`를 통과한다.
- 탐색 후보 정규화의 KMP 통합 완료: `SharedSearchCandidateGenerator.kt`
  - 플랫폼이 관측한 기술·교체·아이템·기믹 후보를 단일 `SharedSearchCandidateObservation`으로 받는다.
  - 합법/비활성 필터, 유한 수치 정규화, 확률 범위 제한, 행동 ID 중복 제거와 점수 정렬을
    JVM과 Kotlin/JS의 같은 구현이 수행한다.
  - 웹 `simpleSearchPolicyCandidates`와 Minecraft `CobblemonBattleSearch.candidates`의 독립
    정규화 코드는 제거되었고, 같은 후보 벡터를 commonTest와 웹 브리지 테스트에서 검증한다.
  - 공통 브리지·상태 어댑터 테스트를 웹 기본 `npm test` 대상에 포함했다.
- 후보 점수 원시 입력 정규화와 트레이너 아이템 가치 평가의 KMP 통합 완료:
  `SharedCandidateEvaluator.kt`
  - `CandidateScoreFacts`가 원본 명중률, 예상 피해, 우선도, 전술·역할값과 조정 규칙을 받아
    기술·교체의 동일 `CandidateScoreInput`을 생성한다.
  - 웹과 Minecraft가 명중률 단위 변환과 유한값 처리를 별도로 구현하지 않고 같은 평가기를 호출한다.
  - 웹에만 있던 회복량, 상태 치료, 즉시 기절 방지, 공격 노출, 미래 역할, 아이템 자원 비용,
    사용 후 기절과 행동 기회비용 계산을 `TrainerItemCandidateFacts` 평가기로 이전했다.
  - Minecraft 아이템 후보도 같은 가치 평가기를 사용하므로 아이템 행동의 기준식이 플랫폼별로
    갈라지지 않는다.
- 교체 상성 원시 사실 파생의 KMP 통합 완료: `SharedSwitchMatchupEvaluator.kt`
  - 현재/교체 대상의 HP, 예상 피격·가해 비율, 진입 피해와 행동 도달 여부를 받아 방어·공격 개선,
    긴급 탈출, 유효타 부재 탈출과 최종 교체 상성값을 한 번만 계산한다.
  - 웹 자동 교체 후보에 내장되어 있던 독립 수식을 제거하고 Kotlin/JS 평가 결과를 사용한다.
  - Minecraft 교체 후보가 임시로 사용하던 상성값 `0`을 제거하고 같은 JVM 평가 결과와
    `emergencyEscape` 규칙 사실을 사용한다.
  - `SwitchMatchupObservation`이 양 플랫폼의 HP·최대 HP, 실제 예상 피해, 최고 피해 기술 우선도,
    유효 속도와 트릭룸 여부를 같은 직렬화 형태로 받고 비율과 행동 도달 여부를 공통 파생한다.
  - `EntryHazardObservation`이 Stealth Rock·Spikes 층수, 타입·특성·소지품에서 진입 피해를 계산한다.
    웹의 기존 설치물 피해 함수는 Kotlin/JS 호출로 교체했고 Minecraft의 임시 진입 피해 `0`도 제거했다.
  - 동일 원시 관측 픽스처가 commonTest와 생성된 JavaScript 브리지에서 같은 설치물 피해, 비율,
    행동 도달 여부와 최종 교체 점수를 만드는 차등 테스트를 추가했다.
  - 역할 보존·역할 진행도는 기존 공통 평가기를 계속 사용한다.
- 전장 지속 상태와 탐색 전이의 KMP 통합 완료: `SharedSearchProjectionRuntime.kt`,
  `SharedSearchFieldCombatEvaluator.kt`
  - `SharedSearchFieldState`가 날씨, 지형, Trick/Magic/Wonder Room·Gravity 같은 전역 효과와
    양측 Reflect·Light Screen·Aurora Veil·Tailwind·보호 효과의 남은 턴을 한 계약으로 소유한다.
  - `SharedSearchFieldMoveCatalog`가 날씨 5종, 지형 4종, 룸·중력, 스크린·Tailwind·보호 기술을
    한 번에 행동 투영 정보로 바꾸며 날씨 바위·Terrain Extender·Light Clay 지속 시간도 반영한다.
  - 웹 검색 상태 변환/복원이 필드 전체를 Kotlin/JS 전이에 전달하고, Minecraft는 Showdown 로그에서
    같은 상태를 재구성해 JVM 전이에 전달한다. 두 플랫폼 모두 탐색 턴마다 지속 시간을 공통 감소시킨다.
  - Minecraft 미래 턴의 피해와 속도 계산은 공통 평가기의 날씨·지형·스크린 배율과
    Chlorophyll·Swift Swim·Sand Rush·Slush Rush·Tailwind 배율 및 Trick Room 상태를 사용한다.
  - JVM commonTest, Java 로그 어댑터 테스트와 생성된 JavaScript 브리지에서 필드 전체 픽스처를 검증한다.
- 팀 역할·에이스·위협 카운터·보존 판정의 KMP 통합 완료: `SharedTeamRoleEvaluator.kt`,
  `SharedPreservationEvaluator.kt`, `SharedRoleProgressEvaluator.kt`
  - 기술 카탈로그 역할 점수·태그, 종족 역할 우선도, 능력치·레벨·특성·기믹을 플랫폼 중립
    `TeamRoleMemberInput`으로 직렬화한다.
  - 에이스/준에이스, 배턴터치 수혜, 막이·피벗·설치물 담당·복수 처리·전개 위협을
    JVM과 Kotlin/JS의 같은 평가기가 결정한다.
  - 웹 `analyzeTeamProfile`은 중앙 JSON 카탈로그를 관측값으로 바꾸는 어댑터만 수행하고
    역할 결정을 `analyzeSharedTeamProfileJson`에 위임한다.
  - Minecraft는 같은 중앙 JSON 두 파일을 빌드 리소스로 포함하고 `BattleAiRoleCatalog`에서
    동일 관측 계약을 만든다. 최대 HP만으로 에이스를 고르던 임시 판정은 제거했다.
  - Minecraft 기술·교체 후보의 `aceQualified`, `roleComplete`, `expendableResource`,
    `mustPreserveResource`가 실제 공통 결과를 사용하며, 승률 상태의 에이스/유일 카운터 특징도
    같은 팀 분석과 위협별 대면 평가에서 나온다.
- 피격·접촉 연쇄 반응의 KMP 통합 완료: `SharedHitReactionEvaluator.kt`
  - Weakness Policy·Maranga Berry·Rocky Helmet과 Gooey·Cotton Down·Justified·Cursed Body,
    Poison Point·Static·Cute Charm·Stamina·Thermal Exchange·Weak Armor·Toxic Debris,
    Rough Skin·Iron Barbs·Flame Body·Effect Spore·Poison Touch를 한 평가기가 순서대로 판정한다.
  - 실제 웹 실행기는 공통 결과의 상태·랭크·반동·설치물 명령과 갱신된 난수 상태만 적용한다.
    확률 효과도 웹에 별도 판정식을 두지 않는다.
  - 공통 기술 역할 JSON에 Showdown 기술 플래그를 함께 생성하여 JVM도 접촉 여부를 추측하지 않고
    웹과 같은 중앙 카탈로그에서 읽는다.
  - 웹과 Minecraft의 탐색 투영은 `resolveRandom=false`로 확정 반응만 같은 상태 전이에 포함한다.
- 피격 후 도구 이동·표시/폼 상태 전이의 KMP 통합 완료:
  `SharedHitReactionEvaluator.kt`, `SharedPostHitEvaluator.kt`, `SharedSearchProjectionRuntime.kt`
  - Pickpocket·Magician의 도구 이동, Illusion 해제, Gulp Missile의 저장 폼·반격을 공통 명령으로 판정한다.
  - Knock Off·Thief·Covet·Fling·Natural Gift·Bug Bite·Incinerate·Pluck의 기존 공통 사후 명령을
    보유 도구 및 Sticky Hold 상태까지 판정하는 계약으로 확장했다.
  - 매칭 타입 Gem과 약점·Maranga·반감 Berry가 먼저 소모된 뒤 도구 강탈이 판정되는 순서를 공통화했다.
  - 공통 탐색 상태가 각 슬롯의 보유 도구와 Illusion/Gulp Missile 상태를 보존하므로 웹 2턴 탐색과
    Minecraft JVM 탐색이 다음 턴에도 같은 상태를 사용한다.
- 퇴장·설치물·등장·강제 교체 단계의 KMP 통합 완료: `SharedSwitchPhaseEvaluator.kt`,
  `SharedSearchProjectionRuntime.kt`
  - Regenerator·Natural Cure와 교체 시 초기화, Healing Wish·Lunar Dance를 설치물보다 먼저 처리한다.
  - Heavy-Duty Boots, Stealth Rock·Spikes·Toxic Spikes·Sticky Web의 피해·흡수·상태·랭크 명령을
    타입·접지·면역 관측으로 한 번만 판정한다.
  - Intrepid Sword·Dauntless Shield·Embody Aspect, 날씨·지형, Illusion, Download·Intimidate와
    런타임 객체가 필요한 진입 특성 어댑터 명령을 같은 순서로 반환한다.
  - 자발 교체, 피벗, 강제 교체와 기절 교체가 같은 유효 슬롯·시드 선택기를 사용한다.
  - 웹 실제 실행기와 웹/JVM 2턴 탐색이 동일 `SharedSwitchPhaseResult`를 소비하며, 한 번만 발동하는
    특성 표식은 보존하고 교체 시 초기화되는 상태만 제거한다.
  - Trace 복사 가능 특성, Forewarn 최고 위력 기술, Anticipation 위협 기술, Frisk 도구 공개,
    Imposter 변신, Protosynthesis·Quark Drive의 필드/Booster Energy와 최고 능력치,
    Forecast 날씨 타입 및 Terapagos Tera Shift 폼 판정도 원시 관측값에서 KMP가 결정한다.
    웹에 있던 Trace 차단 목록과 Forewarn·Anticipation 독립 판정식은 제거했다.
  - 공통 탐색은 Booster Energy 소비와 Paradox·폼·공개·변신 표식을 보존하고, Imposter가 복사하는
    상대 랭크를 다음 상태에 반영한다.
- 변신·폼 변경 전투 프로필의 KMP 통합 완료: `SharedSearchCombatProfile`,
  `SharedSearchProjectionRuntime.kt`
  - 각 슬롯의 원본/현재 타입·특성·능력치와 기술 원본 side/slot을 공통 상태가 함께 소유한다.
    기술 정의를 플랫폼별로 복제하지 않고 웹 Move 객체와 Cobblemon Move 객체를 그대로 다시 조회한다.
  - Imposter는 상대의 현재 프로필·랭크·기술 원본을 복사하고, 교체하면 자기 원본 프로필로 돌아간다.
  - Trace의 복사 특성, Forecast의 날씨 타입, Tera Shift의 Terastal 폼이 현재 프로필에 적용되어
    웹과 JVM의 2턴째 후보 생성, 피해·속도·필드 상성 계산에 사용된다.
  - 웹은 KMP 다음 상태를 실제 웹 포켓몬 형태로 복원한 뒤 기존 후보/피해 계산기를 재사용하고,
    JVM은 현재 프로필의 기술 원본을 통해 후보를 다시 만들며 프로필 능력치·타입으로 피해를 투영한다.
- Cobblemon/Showdown 로그와 공통 투영 상태의 차등 검증 기반 완료:
  `SharedProjectionDifferentialEvaluator.kt`, `ShowdownBattleLogObservation.java`
  - 공통 코어가 현재 활성 HP·최대 HP, 설치물, Yawn·수면·Salt Cure·맹독 카운터, 랭크,
    기믹 잔여 여부, 등장 특성 어댑터 표식, 지속 필드와 사이드 조건의 관측 계약 및 경로별 차이 목록을 소유한다.
  - JVM은 `switch/drag/replace`, 피해·회복, 랭크, 상태, 날씨·지형·룸·스크린·설치물과
    메가/Z/다이맥스/테라 로그를 관측 계약으로 변환할 뿐 비교 규칙을 갖지 않는다.
  - 직접 피해·자기 랭크 상승, 교체·Stealth Rock·Intimidate, 날씨·Reflect·Yawn/Toxic·
    테라 사용 시나리오가 실제 로그 프로토콜 픽스처와 KMP 다음 상태에서 일치하는지 JVM 테스트로 검증한다.
  - 같은 비교기를 Kotlin/JS로 내보내 웹 어댑터도 동일 관측 스냅샷의 차이를 판정한다.
  - `-ability`, `-activate`, `-item`, `-transform`, `-formechange` 로그에서 Trace·Forewarn·Anticipation·
    Frisk·Imposter·Paradox·Forecast·Tera Shift 결과를 복원해 공통 표식과 비교한다.
- 장기 로그 코퍼스와 운영 수집 경로 완료:
  `ProjectionLogCorpusTest.java`, `BattleProjectionLogCapture.java`
  - 누적 프로토콜 3개 시나리오와 16개 체크포인트가 HP·설치물·압박·랭크·필드·기믹·
    등장 특성의 여러 턴 수명주기를 공통 차등 평가기로 검증한다.
  - `cobbleventure.ai.projectionLogCaptureDir` 시스템 속성을 명시한 개발 서버만 전투 ID별 누적
    Showdown 로그를 원자적으로 저장한다. 기본 실행에는 파일 I/O가 없다.
  - 새 운영 로그는 인덱스 기반 코퍼스 파일 하나와 체크포인트만 추가하면 전체 JVM 회귀에 포함된다.
    원본에 운영 정보가 포함될 수 있으므로 익명화 절차를 문서화했다.
  - 현재 저장소의 기존 `logs`에는 전투 원본이 없었으므로 초기 코퍼스는 실제 프로토콜 형식의
    장기 회귀 픽스처로 구성했다. 첫 실서버 플레이 로그는 캡처 옵션을 켠 통합 실행에서 추가한다.

## 완료 조건

- [x] 웹 `sharedSearchPolicyRuntime.transitionCallback`이 JavaScript의 `simulateSimpleTurn`을 직접
  호출하지 않고 KMP 상태 전이를 호출한다.
- [x] Minecraft `CobblemonBattleSearch.transitionState`의 기술·기믹별 상태 변경 코드가 제거되고
  동일 KMP 상태 전이를 호출한다.
- [x] 동일한 직렬화 상태·명령·시드가 JVM과 JavaScript에서 동일한 다음 상태를 만든다.
- [x] 웹 회귀 테스트는 KMP 엔진 픽스처와 생성된 Kotlin/JS 브리지를 함께 실행한다.
- [x] Cobblemon 실제 로그와 KMP 예측의 차이는 코퍼스 차등 테스트 결과로 관리하며 JVM 보정 규칙으로
  숨기지 않는다.

## 유지보수 경계

`CobblemonBattleSearch`는 Cobblemon 객체를 공통 투영 상태·행동으로 변환하는 어댑터만 소유한다.
새 전투 규칙이나 탐색 상태 변경은 이 클래스에 추가하지 않고 `shared-ai-core`와 공통 테스트에
추가한다.

# Cobbleventure Battle AI

Cobbleventure의 트레이너 전투 AI를 새로 설계하기 위한 Java 21 프로젝트다.

[Cobblemon-RunAndBunAI](https://github.com/Buizz/Cobblemon-RunAndBunAI)는 빌드 구성, 플랫폼 독립 코어, 행동 점수와 회귀 테스트 방식을 조사하는 참고 자료로만 사용한다. 이 프로젝트는 해당 AI의 소스 호환 포크가 아니며 런앤번 규칙을 그대로 재현하지 않는다.

> 참고 구현은 Cobblemon 1.7 계열이다. 차기 Cobblemon 버전이 아직 안정 버전으로 확정되지 않았으므로 게임 어댑터 개발은 보류하고, 정식 출시 후 대상 패치 버전을 고정한다.
>
> 현재 Java 모듈은 책임 경계를 검증하는 초기 골격이다. 정식 엔진은 JVM과 웹에서 같은 코드를 사용하도록 Kotlin Multiplatform으로 전환할 계획이다.

## 현재 모듈

| 모듈 | 역할 |
|------|------|
| `ai-api` | 플랫폼 독립 행동·관측·승률·전략 평가와 선택 계약 |
| `ai-engine` | 결정론적 후보 필터, 승률 기준선, 전략 카탈로그와 가중 선택 |
| `data` | AI·전투 엔진이 공통으로 사용하는 기술 역할 분류, 다국어 카탈로그 등 원천 데이터 |
| `tools` | 전역 `data`를 생성·갱신하는 개발 도구 |
| `web-lab` | 트레이너 JSON 정규화, PvE·EvE UI, Showdown 호환 실행기와 최소 자체 전투 엔진을 확인하는 테스트 화면 |

Cobblemon, RCT API, Fabric과 NeoForge 의존성은 아직 추가하지 않았다. 실제 게임 객체를 AI 모델로 변환하는 `cobblemon-adapter`와 선택된 행동을 실행하는 플랫폼 모듈은 대상 버전을 확정한 뒤 추가한다.

`ai-api`의 `AiRuntimeProfile`은 생성된 게임 프로필의 난이도·전략·치터 확률을
검증하고, `ai-engine`의 `ConfiguredDecisionEngine`은 휴리스틱·승률·2턴 탐색·
확정 행동 대응 엔진 중 실제 사용할 정책을 선택한다. 이 계약은 구현되어 있지만
Cobblemon/RCT 전투 객체를 관측과 명령으로 변환하는 마지막 플랫폼 어댑터는 아직
없으므로 현재 빌드를 인게임 AI 완성본으로 취급하지 않는다.

`web-lab`은 제품 코드가 아니라 관찰용 실험실이다. 웹 화면에서 전역 AI 데이터나 `ai-api`에 가까운 관측 모델이 필요할 때는 `web-lab/lib/ai-api-bridge`만 통과한다. 이 경계 밖의 UI·Next.js API·Showdown 디버깅 코드는 `ai-api`, `ai-engine`, `data`의 설계를 오염시키면 안 된다.

## 원칙

- AI 코어는 Minecraft와 Cobblemon 클래스를 참조하지 않는다.
- 후보 생성, 상태 예측, 결정, 실행을 서로 다른 책임으로 둔다.
- 기술, 교체, 아이템과 기믹을 모두 명시적 행동 후보로 표현한다.
- 공개 정보만 `BattleObservation`에 넣고, 최고 치터 난이도의 잠긴 상대 행동은 권한이 분리된 입력으로만 전달한다.
- Minecraft에서는 서버가 최종 행동 권위를 유지하고, 무거운 탐색은 메인 틱 밖의 AI 워커에서 실행한다.
- 플레이어 클라이언트 계산은 싱글플레이·웹 실험실·공개 정보 보조 기능으로 제한한다.
- 같은 입력과 설정은 항상 같은 선택과 판단 근거를 만든다.
- 실제 전투 로그와 가상 시뮬레이션이 같은 코어 계약을 사용한다.
- 웹 실험실은 공통 데이터와 AI 계약을 검증하기 위한 소비자일 뿐, AI 원천 데이터의 소유자가 아니다.

## 테스트

저장소에 Gradle Wrapper를 생성한 뒤 프로젝트 디렉터리에서 실행한다.

```text
gradlew.bat test
```

Java 테스트는 최고 효용 선택, 강제 교체 필터, 행동 부재 처리, 결정론적 동점 해소, 선형 승률 기준선과 전략 상위 K 가중 선택을 검증한다.

웹 실험실은 별도로 다음 명령으로 빌드, 서버 렌더링과 공식·컴퓨터 트레이너 JSON의 정규화를 검증한다.

```text
cd web-lab
npm ci
npm test
```

## 개선 계획

`PokeMathMax` 의존을 없애는 독립 전투 규칙 엔진, Kotlin/JVM·JavaScript 공통 빌드, 브라우저 가상 배틀과 AI 리그 테스트 계획은 [AI 엔진 개선 계획](docs/IMPROVEMENT_PLAN.md)에서 관리한다.

현재 자체 엔진의 지원 범위와 JSON 경계는 [최소 자체 전투 엔진](docs/SIMPLE_BATTLE_ENGINE.md)에 기록한다.

- [전투 규칙 완전성 및 특수 효과 테스트](docs/MECHANICS_COVERAGE_PLAN.md)
- [메가진화·Z파워·다이맥스·테라스탈 엔진 설계](docs/GIMMICK_ENGINE_DESIGN.md)
- [팀 역할·승리 조건 기반 전략 AI](docs/STRATEGIC_AI_PLAN.md)
- [전략 아키타입·팀 분석·상위 3개 전략 선택](docs/STRATEGY_ARCHETYPES_AND_TEAM_ANALYSIS.md)
- [AI 관측 모델과 행동 후보 평가 구현](docs/AI_OBSERVATION_ACTION_IMPLEMENTATION.md)
- [자체 엔진 특성 구현 체크리스트](docs/ABILITY_IMPLEMENTATION_CHECKLIST.md)
- [팀 프리뷰·전투 상태 승률 및 자기대전 가치 학습](docs/WIN_PROBABILITY_MODEL.md)
- [포켓몬 배틀 AI 선행 연구와 공개 구현 조사](docs/RESEARCH_REFERENCES.md)

첫 구현 마일스톤으로 플랫폼 독립 `WinProbabilityModel` 계약과 설명 가능한 `LinearWinProbabilityModel` 기준선을 추가했다. 현재 특징 가중치는 테스트용이며 실제 팀 프리뷰·전투 상태 특징과 학습 가중치는 후속 단계에서 확정한다.

두 번째 마일스톤으로 `StrategyArchetype`, `StrategyEvaluation`, `StrategySelection` 계약, 기본 8개 전략 힌트와 `SoftmaxStrategySelector`를 추가했다. 선택기는 평가 점수 상위 세 개를 기본 후보로 삼고, 온도와 `strategySeed`를 사용해 재현 가능한 가중 선택을 한다.

세 번째 마일스톤으로 RCT 트레이너 JSON을 읽는 웹용 어댑터와 `Cobbleventure Battle Lab`을 추가했다. PvE에서는 6마리 파티를 직접 편집하거나 준비된 JSON 파티를 선택할 수 있고, EvE에서는 두 트레이너 프리셋을 선택할 수 있다.

네 번째 마일스톤으로 웹 입력을 서버에서 재검증하고 재현 시드와 결정론적 ID가 포함된 `schemaVersion 1` 전투 시나리오를 생성하는 API를 추가했다. 생성 결과는 JSON으로 복사·다운로드할 수 있다. 아직 전투 실행을 가장하지 않으며 독립 전투 규칙 엔진은 이 시나리오 계약에 연결한다.

다섯 번째 마일스톤으로 고정 버전 `@pkmn/sim`을 서버 측 호환성 기준 엔진으로 연결했다. Generation 9 Custom Game 규칙 아래 PvE·EvE 시나리오를 실제로 끝까지 실행하고 승자, 턴 수, 호환성 경고, 행동 타임라인과 원시 로그를 반환한다. 현재 양쪽 컨트롤러는 결정론적 `random-baseline`이며, 프로젝트 전략 AI를 연결하기 전의 실행·회귀 기준선이다.

여섯 번째 마일스톤으로 Showdown에 의존하지 않는 `cobbleventure-simple` 전투 코어를 추가했다. 싱글 배틀의 HP·PP·명중·우선도·속도·물리/특수 피해·자속 보정·타입 상성·기절·자동 교체·승패를 같은 시드로 재현하며, 미구현 효과는 명시적인 이벤트로 보고한다.

## 다음 단계

1. 최소 자체 엔진의 JSON 계약을 Kotlin Multiplatform JVM·JS 공통 코어로 옮긴다.
2. 상태 이상, 랭크 변화, 특성, 도구와 기술별 특수 효과를 테스트 우선으로 확장한다.
3. `random-baseline`을 프로젝트의 전략 아키타입·행동 선택기로 교체한다.
4. 대량 EvE 실행, 승률·전략별 리포트와 재현 시드 내보내기를 추가한다.
5. 게임과 무관한 트레이너 팀·행동·결과 어댑터 계약을 확정한다.
6. Cobblemon 안정 버전 정식 출시 후 대상 버전과 트레이너 API를 확정한다.
7. Showdown·Cobblemon 결과와 자체 엔진의 차등 테스트를 연결한다.
8. Cobblemon 어댑터와 실제 게임 실행 계층을 추가한다.

자세한 조사 결과와 설계 경계는 [아키텍처 기준](docs/ARCHITECTURE.md)을 참고한다.

# Cobbleverse Battle AI

Cobbleverse Adventure의 트레이너 전투 AI를 새로 설계하기 위한 Java 21 프로젝트다.

[Cobblemon-RunAndBunAI](https://github.com/Buizz/Cobblemon-RunAndBunAI)는 빌드 구성, 플랫폼 독립 코어, 행동 점수와 회귀 테스트 방식을 조사하는 참고 자료로만 사용한다. 이 프로젝트는 해당 AI의 소스 호환 포크가 아니며 런앤번 규칙을 그대로 재현하지 않는다.

> 참고 구현은 Cobblemon 1.7 계열이다. 실제 Minecraft 연동 목표는 Cobblemon 1.8 계열이며 정확한 1.8.x 버전은 개발 착수 시 고정한다.
>
> 현재 Java 모듈은 책임 경계를 검증하는 초기 골격이다. 정식 엔진은 JVM과 웹에서 같은 코드를 사용하도록 Kotlin Multiplatform으로 전환할 계획이다.

## 현재 모듈

| 모듈 | 역할 |
|------|------|
| `ai-api` | 플랫폼 독립 행동, 관측, 후보, 결정 결과 계약 |
| `ai-engine` | 결정론적 후보 필터, 점수 규칙, 순위와 판단 근거 |

Cobblemon, RCT API, Fabric과 NeoForge 의존성은 아직 추가하지 않았다. 실제 게임 객체를 AI 모델로 변환하는 `cobblemon-adapter`와 선택된 행동을 실행하는 플랫폼 모듈은 대상 버전을 확정한 뒤 추가한다.

## 원칙

- AI 코어는 Minecraft와 Cobblemon 클래스를 참조하지 않는다.
- 후보 생성, 상태 예측, 결정, 실행을 서로 다른 책임으로 둔다.
- 기술, 교체, 아이템과 기믹을 모두 명시적 행동 후보로 표현한다.
- 공개 정보만 `BattleObservation`에 넣고, 최고 치터 난이도의 잠긴 상대 행동은 권한이 분리된 입력으로만 전달한다.
- Minecraft에서는 서버가 최종 행동 권위를 유지하고, 무거운 탐색은 메인 틱 밖의 AI 워커에서 실행한다.
- 플레이어 클라이언트 계산은 싱글플레이·웹 실험실·공개 정보 보조 기능으로 제한한다.
- 같은 입력과 설정은 항상 같은 선택과 판단 근거를 만든다.
- 실제 전투 로그와 가상 시뮬레이션이 같은 코어 계약을 사용한다.

## 테스트

저장소에 Gradle Wrapper를 생성한 뒤 프로젝트 디렉터리에서 실행한다.

```text
gradlew.bat test
```

현재 테스트는 최고 효용 선택, 강제 교체 필터, 행동 부재 처리, 결정론적 동점 해소와 선형 승률 기준선의 종단 상태·확률·신뢰도를 검증한다.

## 개선 계획

`PokeMathMax` 의존을 없애는 독립 전투 규칙 엔진, Kotlin/JVM·JavaScript 공통 빌드, 브라우저 가상 배틀과 AI 리그 테스트 계획은 [AI 엔진 개선 계획](docs/IMPROVEMENT_PLAN.md)에서 관리한다.

- [전투 규칙 완전성 및 특수 효과 테스트](docs/MECHANICS_COVERAGE_PLAN.md)
- [팀 역할·승리 조건 기반 전략 AI](docs/STRATEGIC_AI_PLAN.md)
- [전략 아키타입·팀 분석·상위 3개 전략 선택](docs/STRATEGY_ARCHETYPES_AND_TEAM_ANALYSIS.md)
- [팀 프리뷰·전투 상태 승률 및 자기대전 가치 학습](docs/WIN_PROBABILITY_MODEL.md)
- [포켓몬 배틀 AI 선행 연구와 공개 구현 조사](docs/RESEARCH_REFERENCES.md)

첫 구현 마일스톤으로 플랫폼 독립 `WinProbabilityModel` 계약과 설명 가능한 `LinearWinProbabilityModel` 기준선을 추가했다. 현재 특징 가중치는 테스트용이며 실제 팀 프리뷰·전투 상태 특징과 학습 가중치는 후속 단계에서 확정한다.

## 다음 단계

1. Cobblemon 1.8.x와 트레이너 API 대상 버전을 확정한다.
2. Kotlin Multiplatform JVM·JS 공통 프로젝트로 전환한다.
3. 독립 전투 규칙·피해·턴 진행 엔진을 구현한다.
4. 브라우저 전투 실험실과 대량 AI 평가기를 추가한다.
5. Cobblemon 1.8 어댑터와 실제 결과 차등 테스트를 연결한다.
6. 더블 배틀, 아이템과 메가진화 등 기믹을 단계적으로 추가한다.

자세한 조사 결과와 설계 경계는 [아키텍처 기준](docs/ARCHITECTURE.md)을 참고한다.

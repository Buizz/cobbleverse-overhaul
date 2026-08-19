# Cobbleventure Battle AI

Cobbleventure의 웹 실험실과 Minecraft가 함께 사용하는 전투 AI 프로젝트다. 승률 모델,
승률 기반 1턴 선택과 제한 빔 2턴 expectimax는 Kotlin Multiplatform `commonMain`의
단일 구현이며, 웹은 Kotlin/JS 산출물을, Minecraft는 같은 모듈의 JVM 산출물을 사용한다.

[Cobblemon-RunAndBunAI](https://github.com/Buizz/Cobblemon-RunAndBunAI)는 빌드 구성, 플랫폼 독립 코어, 행동 점수와 회귀 테스트 방식을 조사하는 참고 자료로만 사용한다. 이 프로젝트는 해당 AI의 소스 호환 포크가 아니며 런앤번 규칙을 그대로 재현하지 않는다.

루트 Gradle 프로젝트는 독립 NeoForge 모드 `cobbleventure_battle_ai`를 빌드한다. 이 모드가
서버 대상 Cobblemon/RCT 버전과 Minecraft 어댑터를 소유하고, 플랫폼별 코드는 전투 상태와
합법 행동을 공통 코어 계약으로 변환하는 역할만 담당한다.

## 현재 모듈

| 모듈 | 역할 |
|------|------|
| 루트 NeoForge 모드 | RCT `cobbleventure` AI 타입 등록, Cobblemon 관측·명령 어댑터, 운영 로그 캡처와 `shared-ai-core` jar-in-jar 배포 |
| `shared-ai-core` | `commonMain` 단일 소스로 빌드되는 후보 사실·점수·정규화·교체 상성·퇴장/설치물/등장/강제교체·전장 지속 상태, 승률 모델·1턴 승률 정책·2턴 expectimax·탐색 상태 전이 (`jvm`/`js`) |
| `ai-api` | 플랫폼 독립 행동·관측·승률·전략 평가와 선택 계약 |
| `ai-engine` | 공통 탐색 외의 기존 Java 전략 카탈로그와 가중 선택 |
| `data` | AI·전투 엔진이 공통으로 사용하는 기술 역할 분류, 다국어 카탈로그 등 원천 데이터 |
| `tools` | 전역 `data`를 생성·갱신하는 개발 도구 |
| `web-lab` | 트레이너 JSON 정규화, PvE·EvE UI, Showdown 호환 실행기와 최소 자체 전투 엔진을 확인하는 테스트 화면 |

Cobblemon/RCT 의존성은 루트 NeoForge 모드에만 둔다. 이 어댑터가 양 팀의 공개
HP·교체 후보·기술 피해·가방 수량·남은 기믹을 `shared-ai-core` 입력으로 바꾸며, 후보의
정규화·필터·중복 제거·정렬, 교체 상성, 날씨·지형·룸·스크린 상태와 탐색 전이는 공통 코어가 수행하고,
기술 역할 카탈로그 관측을 바탕으로 한 팀 역할·에이스·위협 카운터·보존 판정도 공통 코어가 수행하며,
피격·접촉으로 발생하는 도구·특성 반응도 공통 코어가 순서 있는 명령으로 판정하고,
피격 후 도구 이동과 Illusion·Gulp Missile 상태도 공통 탐색 상태에 보존하며,
퇴장 회복·설치물·등장 특성·강제 교체 대상과 시드도 공통 교체 단계가 판정하고,
Cobblemon/Showdown 로그의 관측 상태와 공통 투영 차이도 공통 평가기가 경로별로 보고하며,
선택 결과를 실제 기술·교체·아이템 응답으로 반환한다. 웹 실험실은 빌드 시 같은
`shared-ai-core`의 Kotlin/JS 배포물을 생성해 불러온다. 어느 쪽에도 별도 탐색 구현을
두지 않는다.

`ai-api`의 `AiRuntimeProfile`은 생성된 게임 프로필의 난이도·전략·치터 확률을
검증하고, `ai-engine`의 `ConfiguredDecisionEngine`은 휴리스틱·승률·2턴 탐색·
확정 행동 대응 엔진 중 실제 사용할 정책을 선택한다. 실제 게임에서는 독립
`cobbleventure_battle_ai` 모드가 RCT의 `cobbleventure` AI 타입을 등록한다. 생성된 난이도와
전략은 일반 난이도의 RCT 행동 평가 편향과 선택 오차에 반영된다. `expert_winrate`는
아군 상위 4개×상대 상위 2개를 평가하고 휴리스틱보다 승률이 2% 이상 좋아질 때만
행동을 바꾼다. `expert_search`와 `cheater`는 3×2 첫 턴 빔, 기대값 80%와 최악값
20%, 상위 격차 4% 이하의 2턴째 확장, 할인율 0.72를 사용하는 공통 expectimax를
실행한다. 콘텐츠의 메가진화·Z기술·다이맥스·테라스탈 허용 정책도 같은 어댑터에서
강제되며, 허용된 기믹은 일반 기술과 별도 후보로 탐색되어 실제 응답에 기믹 ID가 실린다.

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

`shared-ai-core`의 `commonTest`는 같은 테스트 소스를 JVM과 JavaScript 양쪽에서 실행해
승률과 탐색 결정을 검증한다. 기존 Java 테스트는 전략 카탈로그 등 JVM 전용 경계를
검증한다.

웹 실험실은 별도로 다음 명령으로 빌드, 서버 렌더링과 공식·컴퓨터 트레이너 JSON의 정규화를 검증한다.

```text
cd web-lab
npm ci
npm test
```

## 모드 JAR 빌드

```text
gradlew.bat build
```

산출물은 `build/libs/cobbleventure-battle-ai-0.1.0.jar`이다. `shared-ai-core` JVM 산출물은
이 JAR의 `META-INF/jarjar`에 포함되므로 Minecraft에는 별도 코어 JAR를 설치하지 않는다.
빌드가 성공하면 개발 모드팩의 `mods` 디렉터리에도 같은 파일을 원자적으로 갱신한다.

## 이관 상태와 관련 문서

웹 실험실의 승률 기반 선택과 제한 빔 2턴 expectimax를 Kotlin Multiplatform 단일 코어로
통합하는 작업은 완료됐다. 최종 책임 경계, 검증 결과, 운영 로그 편입 방법은
[공유 전투 AI 이관 완료 보고서](docs/SHARED_BATTLE_AI_MIGRATION_COMPLETION.md)를 기준으로 한다.
세부 작업 이력은 [웹 전투 엔진 KMP 이전 기록](docs/SHARED_BATTLE_ENGINE_MIGRATION.md)에 보존한다.

`PokeMathMax` 의존을 없애는 독립 전투 규칙 엔진과 AI 리그처럼 이번 공유 AI 이관 범위 밖의
개선은 [AI 엔진 개선 계획](docs/IMPROVEMENT_PLAN.md)에서 별도로 관리한다. 현재 자체 엔진의
지원 범위와 JSON 경계는 [최소 자체 전투 엔진](docs/SIMPLE_BATTLE_ENGINE.md)에 기록한다.

- [전투 규칙 완전성 및 특수 효과 테스트](docs/MECHANICS_COVERAGE_PLAN.md)
- [메가진화·Z파워·다이맥스·테라스탈 엔진 설계](docs/GIMMICK_ENGINE_DESIGN.md)
- [팀 역할·승리 조건 기반 전략 AI](docs/STRATEGIC_AI_PLAN.md)
- [전략 아키타입·팀 분석·상위 3개 전략 선택](docs/STRATEGY_ARCHETYPES_AND_TEAM_ANALYSIS.md)
- [AI 관측 모델과 행동 후보 평가 구현](docs/AI_OBSERVATION_ACTION_IMPLEMENTATION.md)
- [자체 엔진 특성 구현 체크리스트](docs/ABILITY_IMPLEMENTATION_CHECKLIST.md)
- [팀 프리뷰·전투 상태 승률 및 자기대전 가치 학습](docs/WIN_PROBABILITY_MODEL.md)
- [포켓몬 배틀 AI 선행 연구와 공개 구현 조사](docs/RESEARCH_REFERENCES.md)
- [아키텍처 기준](docs/ARCHITECTURE.md)

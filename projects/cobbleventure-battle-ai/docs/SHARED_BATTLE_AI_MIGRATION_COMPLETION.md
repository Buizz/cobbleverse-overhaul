# 공유 전투 AI 이관 완료 보고서

- 완료 기준일: 2026-08-19
- 대상: 웹 실험실의 승률 기반 선택 및 제한 빔 2턴 expectimax
- 결과: `shared-ai-core`의 Kotlin Multiplatform 단일 구현을 웹과 Minecraft가 함께 사용
- 배포: 독립 `cobbleventure-battle-ai-0.1.0.jar`가 Minecraft 어댑터와 JVM 코어를 함께 제공

## 완료 요약

웹 실험실과 Minecraft에 각각 존재하던 AI 판단·탐색 상태 전이 구현을 하나의
Kotlin Multiplatform 코어로 통합했다. 웹은 Kotlin/JS 산출물을, Minecraft는 동일 소스의
JVM 산출물을 사용한다. 플랫폼 코드는 전투 객체를 공통 계약으로 변환하고 선택 결과를
실제 명령으로 실행하는 어댑터만 소유한다.

따라서 승률·2턴 탐색 규칙을 수정할 때 플랫폼별 코드를 기계적으로 다시 옮기지 않는다.
`shared-ai-core`와 공통 테스트를 한 번 수정하면 웹 실험실과 실제 게임에 같은 변경이 반영된다.

## 최종 책임 경계

| 책임 | 단일 원천 | 웹 | Minecraft |
| --- | --- | --- | --- |
| 승률 모델과 후보 점수 | `shared-ai-core/commonMain` | Kotlin/JS 호출 | JVM 호출 |
| 1턴 선택과 2턴 expectimax | `shared-ai-core/commonMain` | Kotlin/JS 호출 | JVM 호출 |
| 탐색용 상태 전이 | `shared-ai-core/commonMain` | 웹 상태 어댑터 | Cobblemon 상태 어댑터 |
| 후보 정규화·교체 상성·팀 역할 | `shared-ai-core/commonMain` | 원시 사실 제공 | 원시 사실 제공 |
| 필드·상태·랭크·기믹 투영 | `shared-ai-core/commonMain` | 결과 복원 | 결과 복원 |
| Minecraft 연결·배포 | `cobbleventure_battle_ai` 모드 | 해당 없음 | RCT 등록·Cobblemon 어댑터 |
| 실제 전투 실행 | 플랫폼 전투 엔진 | 웹/Showdown 실행기 | Cobblemon 서버 |

실제 기술 효과를 처리하는 전투 실행기까지 하나로 교체한 것은 아니다. AI가 선택을 위해 사용하는
상태와 규칙은 공유하지만, 선택된 명령의 최종 합법성 및 실제 피해·효과 적용 권한은 각 플랫폼의
전투 실행기에 남는다.

## 이관된 범위

- 결정론적 상태·명령·RNG 계약
- 승률 기반 행동 평가와 설명 가능한 후보 점수
- 제한 빔 2턴 expectimax와 후보 확장·가지치기
- 기술·교체·아이템·메가진화·Z기술·다이맥스·테라스탈 후보
- 행동 순서, 유효 능력치, 피해·명중 후 효과, 턴 종료 투영
- 날씨·지형·Trick Room·장벽·설치물·상태·랭크·교체 상태
- 팀 역할·에이스·위협 카운터·보존 가치와 Baton Pass 판단
- Imposter·Trace·Forecast·Tera Shift·Paradox 등 전투 프로필 변화
- Cobblemon/Showdown 로그 관측과 공통 투영 상태의 경로별 차등 평가

세부 작업 이력과 클래스별 책임은
[웹 전투 엔진 KMP 이전 기준](SHARED_BATTLE_ENGINE_MIGRATION.md)에 보존한다.

## 검증 결과

완료 시점에 다음 검증을 통과했다.

- 웹 실험실 전체 회귀: 530개 중 530개 통과
- JVM 장기 투영 로그 코퍼스: 3개 시나리오, 16개 체크포인트 통과
- JVM 운영 로그 캡처의 비활성 기본값·필터·누적 교체·임시 파일 정리 테스트 통과
- 독립 Battle AI 모드 전체 Gradle 빌드와 JVM·Kotlin/JS 공통 테스트 통과
- AI 코드를 제거한 Adventure 전체 Gradle 빌드와 테스트 통과
- 산출물 검사에서 Adventure의 AI 항목 0개, Battle AI의 등록 진입점과 내장 코어 각 1개 확인
- 이번 변경 범위의 `git diff --check` 통과

## 운영 로그와 기믹 확인

저장소에 실제 서버 전투 원본이 없었기 때문에 운영 로그를 꾸며서 회귀 자료로 넣지 않았다. 대신 실제
Showdown 프로토콜 형식의 장기 코퍼스를 추가했고, 개발 서버에서 원본을 수집할 수 있는 opt-in 경로를
마련했다.

```text
-Dcobbleventure.ai.projectionLogCaptureDir=<전용 디렉터리 절대 경로>
```

이 옵션은 기본적으로 비활성이다. 활성화하면 전투 UUID별 최신 누적 로그를 원자적으로 저장한다.
플레이어 이름 등 운영 정보가 포함될 수 있으므로 실서버 로그는 익명화한 뒤
`cobbleventure-battle-ai/src/test/resources/battle-ai/projection-log-corpus`에 체크포인트와 함께 추가한다.

첫 실제 개발 서버 로그를 코퍼스에 추가하는 일은 운영 검증 자료의 확장이며 코드 이관의 전제 조건은
아니다. 이후 발견되는 기믹 차이는 플랫폼 어댑터의 보정 규칙으로 숨기지 않고 공통 차등 평가기의 실패
사례로 먼저 고정한다.

## 완료 판정

- [x] 웹과 Minecraft가 같은 승률·2턴 탐색 구현을 사용한다.
- [x] 동일 상태·명령·시드는 JVM과 JavaScript에서 동일한 탐색 결과를 만든다.
- [x] 플랫폼별 탐색 상태 변경 코드는 공통 코어 호출로 대체됐다.
- [x] 기믹과 장기 상태 변화는 공통 투영 및 로그 차등 테스트로 검증된다.
- [x] 새 운영 로그를 회귀 코퍼스로 편입하는 절차가 마련됐다.
- [x] Minecraft 어댑터는 Adventure와 분리된 독립 NeoForge 모드 JAR로 배포된다.

## 유지보수 규칙

1. AI 판단·승률·탐색·투영 규칙은 `shared-ai-core/commonMain`에서 수정한다.
2. 플랫폼 어댑터에는 객체 변환, 합법 행동 관측, 실제 명령 실행만 둔다.
3. 규칙 변경에는 `commonTest`를 먼저 추가하고 생성된 Kotlin/JS 브리지를 갱신한다.
4. 웹 전체 회귀와 JVM 어댑터·로그 코퍼스 회귀를 함께 실행한다.
5. 플랫폼 간 차이는 공통 관측 스냅샷과 차등 평가 결과로 기록한다.

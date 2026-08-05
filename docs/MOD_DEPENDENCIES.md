# Cobbleventure 의존 모드 관리표

> 상태: 기본 실행 의존성 확정, 애드온 후보 검토 중
>
> 버전 기준: Minecraft 1.21.1 / NeoForge 21.1.248 / Cobblemon 1.7.3

이 문서는 Cobbleventure 테스트팩과 공개 모드팩에 포함할 외부 모드의 역할과
선정 근거를 관리한다. 빌드가 사용하는 실제 버전과 CurseForge 식별자는
[`pack/dependencies.lock.json`](../pack/dependencies.lock.json)에 기록한다.

## 관리 규칙

- 필수, 선택, 개발 전용 의존성을 구분한다.
- Minecraft, NeoForge와 Cobblemon 버전을 함께 고정한다.
- CurseForge project ID, file ID, 배포 라이선스와 배포면을 확인한다.
- 모드를 추가할 때 대체 가능한 기능과 제거 시 영향을 함께 기록한다.
- 업데이트 후 NPC 생성, 대화, 트레이너전, AI와 전투 기믹 회귀 테스트를 수행한다.
- 문서와 Lock의 모드 ID가 다르면 빌드를 통과시키지 않는다.

## 현재 의존성 후보

| ID | 모드 | 구분 | 설치면 | 역할 | 현재 상태 |
|----|------|------|--------|------|-----------|
| `cobblemon` | Cobblemon | 필수 | 양쪽 | 포켓몬, 기술, 파티와 실제 배틀 | 1.7.3 / CF `687131:7553231` |
| `kotlin_for_forge` | Kotlin for Forge | 필수 | 양쪽 | Cobblemon NeoForge의 Kotlin 런타임 | 5.11.0 / CF `351264:7471280` |
| `rctapi` | Radical Cobblemon Trainers API | 필수 후보 | 양쪽 | 트레이너 데이터와 전투 연동 계약 | 대상 버전 확인 필요 |
| `rctmod` | Radical Cobblemon Trainers | 필수 후보 | 양쪽 | RCT JSON 로딩과 트레이너전 관리 | 자체 데이터 운용 범위 검증 필요 |
| `cobblemon_additions` | Brocraft Cobblemon Additions | 선택·참고 후보 | 양쪽 | 포켓몬 테마 마을, 센터, 마트와 백화점 | 현행 Fabric 모드를 NeoForge에 직접 포함하지 않음 |
| `mega_showdown` | Mega Showdown | 선택 | 양쪽 | 메가진화 등 추가 전투 기믹 | Cobblemon 1.7.3 및 RCT 연동 검증 필요 |
| `easy_npc` | Easy NPC Core | 선택 프로필 | 양쪽 | Easy NPC 호환 프로필의 NPC와 대화 | 자체 NPC 기본안과 비교 유지 |
| `easy_npc_config_ui` | Easy NPC Config UI | 선택 프로필 | 양쪽 | Easy NPC 게임 내 설정과 네트워크 | Easy NPC 프로필에서만 사용 |
| `tbcs` | Cobblemon Trainer Battle Commands | 선택 프로필 | 양쪽 | 명령 기반 NPC와 RCT API 전투 연결 | 자체 RCT Bridge와 비교 필요 |

## 자체 모듈

| 모듈 | 역할 | 외부 의존성 원칙 |
|------|------|------------------|
| Cobbleventure Core | 진행, 지역, 퀘스트와 데이터 계약 | Minecraft·Cobblemon 비의존 유지 |
| Cobbleventure NPC | NPC, 대화창, 조건과 행동 | NeoForge 어댑터에서만 게임 API 사용 |
| Cobbleventure RCT Bridge | 대화 행동을 RCT 전투 시작으로 변환 | RCT 관련 코드를 별도 모듈로 격리 |
| Cobbleventure Battle AI | RCT JSON의 `ai` 선택값으로 실행되는 AI | RCT 기본 AI를 대체하지 않음 |
| Content Manager | Excel/JSON 변환, 검증과 출력 | Python 도구, 게임 런타임 비의존 |

## 마을과 상업 시설 구조물 조사

기존 CreateMon/Cobbleverse 계열 팩의 실제 ZIP을 조사한 결과, 포켓몬 테마
마을의 핵심은 `cobblemon-additions-4.2.1.jar`였다. 이 파일 안에는 다음 구조물이
직접 들어 있다.

- 마을과 도로를 구성하는 여러 바이옴별 직소 구조물
- `pokecenter.nbt`
- `structure_pokemart.nbt`
- `center_department_store.nbt`
- 백화점 층별 상점 직원을 포함한 상점 NPC 구조물

따라서 기존 팩에서 본 마을, 포켓몬센터, 포켓마트와 백화점은 주로
**Brocraft Cobblemon Additions(BCA)**가 만든다. `COBBLEVERSE-DP`에도 같은
`bca` 네임스페이스 구조물이 복사·조정되어 있으므로, 팩 전용 데이터팩이
체육관과 랜드마크뿐 아니라 BCA 구조물 일부를 덮어쓰는 구성이다.

다만 확인한 4.2.1 JAR의 메타데이터는 Fabric Loader, Fabric API와 Fabric
Language Kotlin을 요구한다. 기존 NeoForge 팩은 Sinytra Connector와 Forgified
Fabric API까지 포함해 이 Fabric 모드를 구동한 사례다. 우리 프로젝트는
NeoForge 네이티브 구성을 우선하므로 다음 원칙을 적용한다.

1. BCA를 지금 필수 의존성으로 활성화하지 않는다.
2. BCA의 마을 직소 풀과 센터·마트·백화점 구성은 자체 월드 생성 구현의 참고로 쓴다.
3. 실제 채택 시에는 NeoForge 네이티브 배포본 또는 순수 데이터팩 배포본을 먼저 검증한다.
4. Fabric JAR을 쓰기 위해 Connector 계열 의존성을 늘리는 선택은 별도 호환 프로필로 격리한다.

`CobbleTowns: Continued`도 포켓몬 본가 마을을 바탕으로 센터와 마트를 추가하는
대안이지만, 백화점까지 포함해 기존 팩과 같은 구성을 만든 직접 항목은 BCA다.

## 아직 결정하지 않는 항목

- RCT와 RCT API의 최종 조합 및 버전
- 자체 NPC 기본팩과 Easy NPC 호환팩의 공개 배포 범위
- Mega Showdown에서 지원할 기믹과 RCT 전투별 활성화 방식
- 성능, 지도, 건축과 장식 모드 목록
- BCA 또는 다른 마을 데이터팩을 실제 배포 의존성으로 사용할지 여부

미확정 항목은 `0`, 빈 문자열 또는 임의 버전으로 채우지 않고 Lock 파일에서
`null`과 `draft` 상태로 유지한다.

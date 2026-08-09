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
| `sinytra_connector` | Sinytra Connector | 필수 | 양쪽 | NeoForge에서 BCA Fabric JAR 로딩 | 2.0.0 beta 14 / CF `890127:7634148` |
| `forgified_fabric_api` | Forgified Fabric API | 필수 | 양쪽 | Fabric API의 NeoForge 호환 구현 | 0.116.7+2.2.1 / CF `889079:7575945` |
| `fabric_language_kotlin` | Fabric Language Kotlin | 필수 | 양쪽 | BCA의 Fabric Kotlin 진입점 실행 | 1.13.8 / CF `308769:7340876` |
| `cobbledollars` | CobbleDollars | 필수 | 양쪽 | BCA 상점과 백화점 화폐·상인 기능 | 2.0.0 Beta-5.1 / CF `859232:6604564` |
| `architectury_api` | Architectury API | 필수 | 양쪽 | CobbleFurnies 공용 API | 13.0.8 / CF `419699:5786327` |
| `rctapi` | Radical Cobblemon Trainers API | 필수 | 양쪽 | 트레이너 데이터와 전투 연동 계약 | 0.15.2-beta / CF `1152792:7952419` |
| `rctmod` | Radical Cobblemon Trainers | 필수 | 양쪽 | RCT JSON 로딩과 트레이너전 관리 | 0.18.1-beta / CF `1009534:7913180` |
| `cobblefurnies` | CobbleFurnies | 필수 | 양쪽 | RGS 체육관·리그의 가구 블록 | 1.0 / CF `1188698:7302031` |
| `radical_gyms_structures` | Radical Gyms & Structures | 필수 | 양쪽 | 진입형 체육관 실내와 원본 리그 구조물 | 0.6 / CF `1402174:7330950` |
| `mega_showdown` | Mega Showdown | 선택 | 양쪽 | 메가진화 등 추가 전투 기믹 | Cobblemon 1.7.3 및 RCT 연동 검증 필요 |
| `easy_npc_bundle` | Easy NPC Bundle | 선택 프로필 | 양쪽 | Core와 Config UI 의존성을 선언하는 런처용 번들 | 7.0.1 / CF `559312:8420470` |
| `easy_npc` | Easy NPC Core | 선택 프로필 | 양쪽 | Easy NPC 호환 프로필의 NPC와 대화 | 7.0.1 / CF `1308987:8420476` |
| `easy_npc_config_ui` | Easy NPC Config UI | 선택 프로필 | 양쪽 | Easy NPC 게임 내 설정과 네트워크 | 7.0.1 / CF `1214728:8420458` |
| `tbcs` | Cobblemon Trainer Battle Commands | 선택 프로필 | 양쪽 | 명령 기반 NPC와 RCT API 전투 연결 | 자체 RCT Bridge와 비교 필요 |

## 콘텐츠팩 의존성

| ID | 콘텐츠팩 | 구분 | 선택 버전 | 배포 형식 | 패키징 상태 |
|----|----------|------|-----------|-----------|-------------|
| `cobblemon_additions` | Cobblemon Additions | 필수 후보 | 4.2.1 / Modrinth `W2pr9jyL:9PMzbD4o` | Fabric JAR로 포장된 데이터팩·모드 | ZIP 포함 및 NeoForge 호환 구성 완료 |

## 자체 모듈

| 모듈 | 역할 | 외부 의존성 원칙 |
|------|------|------------------|
| Cobbleventure Core | 진행, 지역, 퀘스트와 데이터 계약 | Minecraft·Cobblemon 비의존 유지 |
| Cobbleventure World Bootstrap | 첫 오버월드 시작점 근처에 BCA 테스트 마을 배치 | `lowcodefml` 데이터 모드로 격리, 정식 지역 플래너 구현 후 교체 |
| Cobbleventure Player Menu | 인벤토리 키 세로 메뉴와 가방·지도·도감 등 공통 진입점 | NeoForge 클라이언트 화면을 사용하고 기능별 게임 어댑터는 후속 연결 |
| Cobbleventure NPC | NPC, 대화창, 조건과 행동 | NeoForge 어댑터에서만 게임 API 사용 |
| Cobbleventure RCT Bridge | 대화 행동을 RCT 전투 시작으로 변환 | RCT 관련 코드를 별도 모듈로 격리 |
| Cobbleventure Battle AI | RCT JSON의 `ai` 선택값으로 실행되는 AI | RCT 기본 AI를 대체하지 않음 |
| Content Manager | Excel/JSON 변환, 검증과 출력 | Python 도구, 게임 런타임 비의존 |

## 마을과 상업 시설 구조물 조사

공식 Cobbleverse 구성표에는 **Cobblemon Additions가 모드가 아니라 데이터팩**으로
기재되어 있다. 따라서 CurseForge의 모드 목록에서 BCA라는 독립 모드가 보이지
않는 것이 정상이며, 외부 모드 의존성 Lock에도 등록하지 않는다.

참고용으로 제공받은 `CreateMon ver 8` ZIP은 원본 Cobbleverse 배포본이 아니라
이를 바탕으로 별도 구성한 팩이다. 이 ZIP에는 `cobblemon-additions-4.2.1.jar`가
`overrides/mods`에 수동 포함되어 있었지만, 이것만으로 원본 Cobbleverse도 같은
JAR을 모드로 사용한다고 판단할 수 없다.

CreateMon에 들어 있던 해당 파일과 `COBBLEVERSE-DP`의 `bca` 네임스페이스에는
다음 구조물이 확인된다.

- 마을과 도로를 구성하는 여러 바이옴별 직소 구조물
- `pokecenter.nbt`
- `structure_pokemart.nbt`
- `center_department_store.nbt`
- 백화점 층별 상점 직원을 포함한 상점 NPC 구조물

즉 Cobbleverse의 포켓몬 테마 마을과 상업 시설은 Cobblemon Additions 데이터팩
자산을 포함하거나 가공한 구성으로 볼 수 있다. 다만 실제 배포에서는
`COBBLEVERSE-DP`에 함께 포장될 수 있으므로, 이를 별도의 BCA 모드가 시설을
생성한다고 표현하지 않는다.

CreateMon에서 확인한 4.2.1 JAR의 메타데이터는 Fabric Loader, Fabric API와
Fabric Language Kotlin을 요구한다. 이 포장본을 NeoForge에서 그대로 사용하려면
Connector 계열 호환층이 필요하다. 우리 프로젝트는 NeoForge 네이티브 구성을
우선하므로 다음 원칙을 적용한다.

4.2.1 원본 JAR 내부에는 `CC0-1.0` 라이선스 전문이 포함되어 있다. 저장소에는
Modrinth 원본을 변경하지 않고 보관하며 SHA-1과 SHA-512를 Lock에서 검증한다.
현재 개발용 CurseForge ZIP에는 `overrides/mods/cobblemon-additions-4.2.1.jar`로
포함된다.

1. BCA를 외부 모드 목록이 아니라 콘텐츠팩 의존성으로 등록한다.
2. BCA의 마을 직소 풀과 센터·마트·백화점 구성은 자체 월드 생성 구현의 참고로 쓴다.
3. 원본 파일, 출처, 라이선스와 해시를 함께 고정한다.
4. Fabric JAR은 Sinytra Connector, Forgified Fabric API와 Fabric Language Kotlin을 통해 실행한다.

`CobbleTowns: Continued`도 포켓몬 본가 마을을 바탕으로 센터와 마트를 추가하는
대안이지만, 백화점까지 포함해 기존 팩과 같은 구성을 만든 직접 항목은 BCA다.

## 체육관과 리그 구조물

체육관 실내와 리그는 Radical Gyms & Structures 0.6으로 확정한다. 저장소에 원본
JAR이나 NBT를 복사하지 않고 CurseForge manifest의 `1402174:7330950` 참조로
설치한다. 체육관은 자체 공통 외관 안의 진입 지점에서 격리 좌표의 RGS 템플릿으로
이동하는 방식이며, 자체 외관은 타입별로 지붕 콘크리트와 입구 카펫 색만 바꾼다.
리그 `rgs:kanto_league`는 전용 지역에 원본 그대로 직접 배치한다.

RGS 페이지가 요구하는 RCT, RCT API와 CobbleFurnies를 필수 의존성으로 함께
고정했다. CobbleFurnies 1.0의 NeoForge 메타데이터가 요구하는 Architectury API
13.0.8도 명시적으로 포함한다. 상세 구조물 ID와 JSON 계약은
[`RADICAL_GYMS_INTEGRATION.md`](implementation/RADICAL_GYMS_INTEGRATION.md)를
기준으로 한다.

## 아직 결정하지 않는 항목

- 자체 NPC 기본팩과 Easy NPC 호환팩의 공개 배포 범위
- Mega Showdown에서 지원할 기믹과 RCT 전투별 활성화 방식
- 성능, 지도, 건축과 장식 모드 목록
- Cobblemon Additions 또는 다른 마을 데이터팩 자산을 실제 콘텐츠에 사용할지 여부

미확정 항목은 `0`, 빈 문자열 또는 임의 버전으로 채우지 않고 Lock 파일에서
`null`과 `draft` 상태로 유지한다.

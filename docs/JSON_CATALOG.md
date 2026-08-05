# JSON 데이터 카탈로그

이 문서는 저장소에 존재하거나 앞으로 추가할 JSON의 역할과 편집 기준을
정리한다. 같은 데이터를 여러 형식으로 출력하더라도 사람이 직접 관리하는
기준 원본은 하나만 둔다.

## 가장 중요한 구분

| 분류 | 의미 | 직접 편집 |
|------|------|-----------|
| 기준 원본 | 게임 콘텐츠의 의미를 정의하며 모든 출력기가 읽는 데이터 | 예 |
| 스키마 | JSON 구조와 타입을 정의하는 계약 | 구조를 바꿀 때만 |
| 외부 원본·참고자료 | RCT 등 외부 모드에서 가져온 원형 데이터 | 원칙적으로 아니요 |
| 프로젝트 데이터셋 | 전투 AI나 Web Lab 자체가 사용하는 데이터 | 담당 기능에서만 |
| 빌드 설정 | 의존성과 패키징 방식을 결정하는 기계 판독 설정 | 예 |
| 생성 결과 | 기준 원본을 RCT, Easy NPC, 모드 데이터 등으로 변환한 결과 | 아니요 |

생성 결과에서 문제를 발견하면 결과 파일을 고치지 않고 기준 원본이나 출력기를
수정한다.

## 콘텐츠 기준 원본

### 트레이너 콘텐츠 번들

| 항목 | 내용 |
|------|------|
| 경로 | `content/source/**/*.json` |
| 스키마 | `content/schemas/content-bundle.schema.json` |
| 예제 | `content/source/examples/ai_test.json`, `starter_town_leader.json` |
| 역할 | 한 트레이너 조우에 필요한 NPC, 전투, 대화, 진행 조건과 결과를 함께 정의 |
| 소비자 | Python 콘텐츠 관리 도구와 향후 RCT·NPC 출력기 |

현재 번들의 주요 영역은 다음과 같다.

| 영역 | 포함하는 내용 |
|------|---------------|
| 기본 정보 | 스키마 버전, 콘텐츠 ID, 활성 여부, 이름, 설명, 태그 |
| `npc` | 표시 이름, 스킨·모델·초상화, 상호작용 거리와 행동 방식 |
| `battle` | RCT 트레이너 ID, 배틀 형식, AI 프로필, 레벨 규칙, 아이템, 특수기믹, 팀 |
| `dialogue` | 시작 노드, 대사, 조건, 선택지와 실행 동작 |
| `progression` | 재대전 정책, 상태 플래그, 조건별 대화 진입점 |
| `outcomes` | 승리·패배·무승부 후 플래그, 보상, 퀘스트 등 실행 동작 |
| `export_overrides` | 특정 출력 대상에서만 필요한 최소 예외 설정 |

`battle.format`은 현재 `GEN_9_SINGLES`, `GEN_9_DOUBLES` 중 하나이며
`battle_type`의 싱글·더블과 일치해야 한다. `battle.ai`는 전투 엔진과 공유하는
`cobbleventure:ai/balanced`, `aggressive`, `defensive`, `ace_check`,
`reckless_ace`, `setup`, `hazard`, `tempo`, `unpredictable` 중 하나를 사용한다.

대사에는 한국어 `ko_kr`을 반드시 두고, 다른 언어는 같은 객체에 추가한다.
조건과 동작은 문자열 명령을 넣지 않고 `type`이 있는 구조화 객체로 작성한다.

팀 포켓몬의 일반 소지품은 `held_item`에 둔다. 메가진화와 Z기술은
`gimmick: { "type": "mega_evolution|z_move", "item": "namespace:item" }`으로
별도 보관하며 `held_item`과 동시에 설정할 수 없다. RCT 출력기는 `gimmick`이 있으면
그 `item`을 실제 소지품 필드로 변환하고, 없을 때만 `held_item`을 사용한다.

`tera_type`의 기본값은 `auto`다. RCT 출력 시 단일 타입 포켓몬은 그 타입으로,
복합 타입 포켓몬은 두 원래 타입 중 하나로 변환한다. 복합 타입 선택은 트레이너 ID와
팀 슬롯에 따라 결정해 같은 원본을 다시 빌드해도 결과가 변하지 않게 한다. 명시적인
테라 타입은 이 자동 계산보다 우선한다.

트레이너가 전투 중 사용하는 회복약과 능력치 아이템은 `battle.bag`에
`{ "item": "namespace:item", "quantity": 1 }` 형식으로 보관한다. 전투 전체의
아이템 사용 횟수를 제한하려면 `battle.rules.max_item_uses`에 0 이상의 정수를
지정한다. 이 값이 없으면 별도의 최대 횟수를 원본 데이터에서 강제하지 않는다.

처음부터 NPC, 대화, 전투를 여러 파일로 쪼개지는 않는다. 여러 NPC가 같은 대화나
퀘스트를 실제로 재사용하게 되면 그때 별도 라이브러리로 분리하고 리소스 ID로
참조한다.

### 지역 데이터

| 항목 | 내용 |
|------|------|
| 경로 | `content/regions/**/*.json` |
| 스키마 | `content/schemas/region.schema.json` |
| 역할 | 세대별 지역, 경계, 연결, 바이옴 구획과 주요 앵커 정의 |
| 소비자 | 플랫폼 독립 지역 코어와 향후 NeoForge 월드 어댑터 |

트레이너 번들은 장소와 독립된 재사용 가능한 원본이다. 어느 지역과 마을에 등장할지는
마을 데이터의 `npc_placement.trainer_slots`가 결정한다.

### 마을 데이터

| 항목 | 내용 |
|------|------|
| 경로 | `content/settlements/**/*.json` |
| 스키마 | `content/schemas/settlement.schema.json` |
| 역할 | 마을 이름·경계·중심·앵커와 일반 NPC·트레이너 배치 슬롯 정의 |
| 소비자 | Python 콘텐츠 관리 화면과 향후 NeoForge NPC 배치 어댑터 |

마을의 기본 정보와 트레이너 슬롯은 Python 관리 화면에서 폼으로 편집할 수 있다.
각 슬롯은 `trainer_id`, 절대 좌표, 회전, 생성 정책과 태그를 가지며, 저장소 검증기는
`trainer_id`가 실제 `content/source` 트레이너를 가리키는지도 검사한다. 트레이너를
다른 마을로 옮길 때는 트레이너 번들을 수정하지 않고 두 마을의 슬롯만 변경한다.
일반 NPC 구역은 아직 고급 JSON에서 관리한다.

## 앞으로 추가할 기준 원본

다음 경로와 파일 형식은 아직 구현되지 않은 설계 대상이다.

| 예정 종류 | 제안 경로 | 역할 |
|-----------|-----------|------|
| NPC 배치 집합 | `content/placements/**/*.json` | 많은 NPC와 오브젝트를 한 장소에 일괄 배치 |
| 공용 대화 | `content/dialogues/**/*.json` | 여러 NPC가 재사용하는 대화 그래프 |
| 퀘스트 | `content/quests/**/*.json` | 퀘스트 조건, 목표, 보상과 진행 상태 |
| 세대 이동 | `content/travel/**/*.json` | 차원·세대·지역 관문과 해금 조건 |
| 스폰 프로필 | `content/spawns/**/*.json` | 지역·바이옴별 Cobblemon 스폰 정책 |

실제 사용 사례가 생기기 전에는 빈 파일 종류를 미리 만들지 않는다. 새 종류를
추가할 때는 스키마, 최소 예제, Python 검증, 출력 대상과 이 카탈로그를 함께
갱신한다.

## 스키마

| 경로 | 검증 대상 |
|------|-----------|
| `content/schemas/content-bundle.schema.json` | 정규화된 트레이너 콘텐츠 번들 |
| `content/schemas/region.schema.json` | 플랫폼 독립 지역 데이터 |
| `content/schemas/settlement.schema.json` | 마을과 NPC 배치 기본 데이터 |
| `content/schemas/trainer-classes.schema.json` | 트레이너 클래스와 기본 외형 카탈로그 |
| `pack/schemas/dependencies-lock.schema.json` | 모드팩 의존성 Lock |

## 제작 카탈로그

| 경로 | 역할 |
|------|------|
| `content/catalogs/trainer-classes.json` | 본가식 트레이너 직업명, 이름 패턴, 태그와 RCT·자체 기본 외형 연결 |

트레이너 번들의 `npc.trainer_class`는 반드시 이 카탈로그에 있는 ID를 사용한다.

JSON Schema는 편집기 자동 완성과 구조 계약에 사용한다. Python 검증기는 리소스
ID 중복, 대화 대상 존재 여부, 전투 ID 일치, EV 합계처럼 파일 구조만으로 표현하기
어려운 의미 검증을 추가로 수행한다.

## 외부 원본과 참고자료

| 경로 | 성격 | 편집 원칙 |
|------|------|-----------|
| `trainer-data/entries/rct/*.json` | 외부 RCT 트레이너 원본 예제 | 원형과 오류까지 보존하고 직접 정규화하지 않음 |
| `trainer-data/entries/custom/*.json` | 전투 AI·팀 구성 실험 자료 | 실험 목적일 때만 수정 |
| `trainer-data/catalogs/*.json` | 아이템 등 RCT 자료를 해석하기 위한 카탈로그 | 출처와 생성 절차를 함께 관리 |

이 파일들은 새 콘텐츠의 기준 원본이 아니다. 필요한 값은 가져오기 어댑터를 통해
`content/source`의 번들 구조로 변환한다.

## 전투 AI와 Web Lab 데이터

| 경로 | 역할 |
|------|------|
| `projects/cobbleventure-battle-ai/data/ai/*.json` | 기술·포켓몬 역할 분류와 AI 보정 데이터 |
| `projects/cobbleventure-battle-ai/data/i18n/*.json` | 전투 실험용 이름 번역 데이터 |
| `projects/cobbleventure-battle-ai/data/samples/**/*.json` | 전투 요청과 팀 샘플 |
| `projects/cobbleventure-battle-ai/web-lab/public/data/**/*.json` | Web Lab이 브라우저에서 읽는 데이터 |

Web Lab의 `public/data`가 다른 원본에서 복사되도록 구성된 경우 복사본은 직접
고치지 않는다. 동기화 스크립트나 원본 데이터셋을 수정한다. 이 데이터는 게임
콘텐츠 번들과 목적이 다르므로 `content/source`로 합치지 않는다.

### 웹 편집기 엔트리 교환 JSON

Python 콘텐츠 관리 웹과 전투 Web Lab은 팀 전체를 다음 공통 클립보드 계약으로
복사하고 붙여넣는다.

| 항목 | 내용 |
|------|------|
| 스키마 식별자 | `cobbleventure:party-entry-clipboard` |
| 현재 버전 | `1` |
| 구현 | `projects/cobbleventure-battle-ai/web-lab/lib/pokemon-entry-clipboard.mjs` |
| 팀 배열 | 최상위 `pokemon` |

이 형식은 두 편집기 사이의 임시 교환 형식이며 콘텐츠 기준 원본은 아니다. 붙여넣기
호환을 위해 공통 형식 외에도 과거의 최상위 배열, `party`, `team`, `battle.team`
형식을 읽을 수 있다. 읽은 데이터는 각 편집기의 내부 구조로 정규화하며, 다시
복사하면 항상 현재 공통 형식으로 출력한다.

## 빌드와 패키징 설정

| 경로 | 역할 |
|------|------|
| `pack/dependencies.lock.json` | Minecraft, NeoForge와 포함 모드의 확정 버전·출처 |
| `pack/profiles/*.json` | smoke, 개발, 배포 등 패키징 프로필 |

Lock 파일은 [의존 모드 관리표](MOD_DEPENDENCIES.md)와 함께 관리하되, 빌드
스크립트는 Markdown 표가 아니라 JSON Lock을 읽는다.

## 생성 결과

향후 출력기는 정규화 번들을 다음 대상으로 변환한다.

- RCT 트레이너 JSON
- 자체 NPC·대화 데이터 또는 Easy NPC 데이터
- NeoForge 메인 모드가 읽는 지역·배치 데이터
- CurseForge 임포트 ZIP과 검사 보고서

생성물은 `build`, `staging`, `generated`, `dist` 같은 출력 디렉터리에 두고 Git에
커밋하지 않는다. 출력 위치가 새로 생기면 `.gitignore`와 이 문서를 함께 갱신한다.

## ID와 파일 이름 규칙

- 리소스 ID는 `namespace:path` 형식을 사용한다.
- 프로젝트 기본 namespace는 당분간 `cobbleventure`를 사용하되, 이름 변경 시
  변환 도구로 일괄 마이그레이션한다.
- 파일 이름은 소문자 `snake_case.json`으로 작성한다.
- 파일 이름보다 JSON 내부의 리소스 ID가 공식 식별자다.
- 대화 노드와 선택지 ID는 해당 번들 안에서 유일해야 한다.
- RCT 출력용 `battle.trainer_id`와 번들의 `id`는 의도적으로 같게 유지한다.

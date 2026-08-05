# Cobbleventure Content Manager

Python 표준 라이브러리만으로 실행되는 콘텐츠 제작·검증 도구다. CLI, 로컬 관리
화면과 Web API는 같은 검증 코드를 사용한다.

## 실행

저장소 루트에서 다음 중 하나를 사용한다.

```bat
build.bat validate
build.bat validate-pack
build.bat web
build.bat api
build.bat test
build.bat pack-smoke
build.bat pack
build.bat pack-release
```

### 명령 설명

| 명령 | 용도 | 성공 조건 |
|------|------|-----------|
| `build.bat validate` | 개발 중인 의존성 Lock과 `content/source`의 정규화 콘텐츠를 검사한다. | JSON 형식, ID, 전투·대화·진행 참조에 오류가 없음 |
| `build.bat validate-pack` | CurseForge ZIP을 만들 수 있을 정도로 모든 실행 버전과 CurseForge ID가 고정되었는지 엄격하게 검사한다. | Lock 상태와 Minecraft·NeoForge·활성 모드 정보가 모두 확정됨 |
| `build.bat web` | 콘텐츠 관리 화면과 외부 로컬 도구용 Web API를 함께 실행한다. | `127.0.0.1:8765`에서 서버가 시작됨 |
| `build.bat api` | 기존 자동화 호환을 위한 `web` 명령의 별칭이다. | `web`과 동일하게 실행됨 |
| `build.bat test` | 콘텐츠 검증기와 로컬 Web API의 회귀 테스트를 실행한다. | 모든 Python 단위 테스트가 통과함 |
| `build.bat pack-smoke` | 별도 팩 빌더로 최소 CurseForge 임포트 ZIP을 생성하고 검증한다. | `dist`에 ZIP과 SHA-256이 생성됨 |
| `build.bat pack` | 일반 검증 후 별도 팩 빌더로 임시 개발 ZIP을 생성한다. | `dist`에 개발 ZIP과 SHA-256이 생성됨 |
| `build.bat pack-release` | 정식 패키징 조건을 엄격 검증한다. | Lock이 `draft`인 현재는 실패하고 ZIP을 만들지 않음 |

#### `validate`

콘텐츠를 수정할 때 가장 자주 사용하는 명령이다. 다음 항목을 검사한다.

- 의존성 Lock의 필수 필드와 모드 ID 중복
- 정규화 콘텐츠 파일의 JSON 형식과 `schema_version`
- 콘텐츠, NPC, 지역, 마을, 앵커와 전투의 리소스 ID 형식
- NPC 외형·행동, 마을별 트레이너 배치 위치·생성 정책, 전투 규칙과 AI 프로필
- 포켓몬 레벨·기술·개체값·노력치와 팀 전체 데이터
- 대화 노드·선택지 중복, 조건·동작 형식과 이동 대상 존재 여부
- `start_battle`이 현재 콘텐츠의 `battle.trainer_id`를 가리키는지 여부
- 진행 경로와 승리·패배 결과 동작의 참조

Cobblemon 1.8과 관련 모드 버전이 확정되기 전에는 다음 `draft` 경고가 나오는
것이 정상이다. 경고만 있고 오류가 없으면 종료 코드 `0`으로 성공한다.

```text
[경고] 의존성이 draft 상태입니다. 일반 콘텐츠 개발은 가능하지만 테스트팩 패키징은 차단됩니다.
검증 성공: 오류 0개, 경고 1개
```

#### `validate-pack`

`validate`의 모든 검사에 다음 패키징 조건을 추가한다.

- Minecraft 버전 고정
- NeoForge 버전 고정
- 활성 외부 모드 버전 고정
- 활성 외부 모드의 CurseForge project ID와 file ID 고정

현재처럼 Lock이 `draft`이고 버전이 미정이면 실패하는 것이 정상이다. 이 명령이
성공하기 전에는 후속 `pack` 명령이 ZIP을 생성해서는 안 된다.

#### `web`과 `api`

로컬 관리 화면과 Web API를 실행하고 종료할 때까지 터미널을 점유한다. 종료하려면
`Ctrl+C`를 누른다. 기본 주소는 다음과 같다.

```text
http://127.0.0.1:8765
```

브라우저에서 기본 주소를 열면 다음 기능을 사용할 수 있다.

- 저장소 검증 현황과 트레이너·마을 수 확인
- 새 트레이너·마을의 유효한 기본 JSON 생성
- 트레이너 기본 정보, NPC 행동, 전투 설정과 최대 6마리 팀 편집
- 전투 가방의 회복·상태회복·능력치 아이템, 보유 수량과 전투당 최대 사용 횟수 편집
- 본가식 트레이너 클래스 선택과 RCT 개별·그룹 스킨 미리보기
- Battle Web Lab과 같은 포켓몬 슬롯·프로필·IV/EV·기술 집중 편집기
- 선택한 포켓몬을 좌우로 이동해 `battle.team`의 출전 순서 변경
- IV는 능력치별 `0~31`, EV는 능력치별 `0~252`와 전체 `510`에 맞춰 입력 즉시 자동 보정
- 포켓몬·폼·성격·특성·기술·지닌 도구를 검색해서 고르는 공용 다이얼로그
- 지역 폼과 특수 형태 선택, 카탈로그 외 Cobblemon `aspects` 직접 입력
- 대화·진행과 IV·EV 등 전체 트레이너 JSON 고급 편집
- 마을 이름, 지역, 차원, 중심, 경계와 기본 NPC 배치 수치 편집
- 마을에서 배치할 트레이너를 선택하고 슬롯 ID·좌표·회전·생성 정책·태그 편집
- 전체 마을 JSON 고급 편집
- 허용된 `build.bat` 검사·테스트·패키징 명령 실행과 결과 확인

저장 전 서버 검증에 실패하면 원본 파일을 변경하지 않는다. 관리 화면은 인증을
제공하지 않으므로 기본값인 로컬 주소를 유지하고 다른 PC가 접속할 수 있는 주소로
바인딩하지 않는다.

#### `test`

`tools/content-manager/tests`와 `tools/pack-builder/tests` 아래의 Python
`unittest`를 실행한다. 콘텐츠 검증 규칙, Web API나 팩 빌더를 변경한 뒤
실행하며, 테스트 실패 시 변경을 패키징하지 않는다.

### 종료 코드

- `0`: 명령 성공
- `1`: 검증 오류, 테스트 실패, 알 수 없는 명령 또는 Python 실행 환경 누락

BAT 파일을 인자 없이 실행하면 사용 가능한 명령 목록을 출력한다. 현재는 도움말
출력 후 종료 코드 `1`을 반환하므로 자동화에서는 반드시 명령을 지정한다.

`pack-smoke`의 프로필과 ZIP 구조는
[`tools/pack-builder/README.md`](../pack-builder/README.md)에서 관리한다.

Python 모듈을 직접 실행할 수도 있다.

```text
python tools/content-manager/content_manager.py validate --root .
python tools/content-manager/content_manager.py api --root .
```

`web`은 기본적으로 `127.0.0.1:8765`에서 실행된다. `api`는 기존 스크립트를 위한
호환용 별칭이며 동작은 같다.

## API

- `GET /health`: 프로세스 상태
- `GET /dependencies`: 현재 의존성 Lock
- `GET /validate`: 저장소 데이터 검증
- `GET /validate?strict_pack=true`: CurseForge 패키징 가능 상태까지 검증
- `POST /validate`: `GET /validate`와 동일
- `GET /api/dashboard`: 관리 화면 요약과 실행 가능한 빌드 명령
- `GET /api/trainers`, `GET /api/settlements`: 관리 문서 목록
- `GET /api/trainer-classes`: 트레이너 클래스와 기본 외형 카탈로그
- `GET /api/editor-catalog`: Battle Web Lab과 공유하는 포켓몬·폼·기술·특성·도구 및 트레이너 가방 아이템 카탈로그
- `GET /api/trainers?path=...`, `GET /api/settlements?path=...`: 단일 문서 조회
- `PUT /api/trainers?path=...`, `PUT /api/settlements?path=...`: 검증 후 문서 저장
- `POST /api/document-validation?category=...`: 저장하지 않고 문서 검증
- `POST /api/documents`: 유효한 기본값으로 새 트레이너 또는 마을 생성
- `POST /api/build`: 허용 목록에 있는 빌드 명령 실행

응답은 UTF-8 JSON이다. Web API는 로컬 제작 도구용이며 인증 없이 외부
인터페이스에 바인딩하지 않는다.

## 현재 검증 범위

- 의존성 Lock 필수 필드, 상태, 모드 ID와 CurseForge ID 중복
- `locked` 상태에서 Minecraft·NeoForge·활성 모드 버전 고정 여부
- 정규화 콘텐츠 ID 형식과 파일 간 중복
- NPC, 배치, 전투, 팀과 포켓몬 필드의 필수값·범위
- 트레이너 가방 아이템 ID·수량과 전투당 최대 사용 횟수
- 대화 노드·선택지 ID 중복과 조건·동작 형식
- `next_dialogue`, 진행 경로와 대화 진입점의 대상 존재 여부
- `start_battle`의 트레이너 ID 일치 여부
- 마을 ID·지역·차원, 경계와 중심 좌표, 앵커, 트레이너 슬롯과 NPC 구역

구조 계약은 `content/schemas/content-bundle.schema.json`, 실제 작성 예제는
`content/source/examples`에서 확인한다. 저장소 내 다른 JSON의 역할과 편집 여부는
[JSON 데이터 카탈로그](../../docs/JSON_CATALOG.md)에 정리되어 있다.

대화 그래프 전용 편집기, 마을 배치 슬롯 전용 폼, Excel 가져오기와 대상별
출력기는 다음 개발 단계에서 같은 도구에 추가한다.

포켓몬의 기본 종은 `species`, 카탈로그에서 선택한 지역 폼·특수 형태는 `form`,
카탈로그만으로 표현되지 않는 Cobblemon 상태는 `aspects` 문자열 배열에 보관한다.
메가진화와 Z기술은 일반 `held_item`에 기믹 아이템을 직접 입력하지 않는다.
포켓몬 설정의 체크박스를 켜면 호환되는 메가스톤 또는 Z크리스탈이 자동으로
선택되고 `gimmick.type`과 `gimmick.item`에 저장된다. 후보가 여러 개면 체크박스
아래의 전용 선택란에서 바꿀 수 있다. 메가스톤은 포켓몬 종과 대응하는 항목만,
Z크리스탈은 현재 기술 타입에 맞는 범용 항목과 조건을 만족한 전용 항목만 표시한다.

`held_item`과 `gimmick`은 동시에 사용할 수 없다. 기믹을 켜면 일반 소지품을 비우고,
일반 소지품을 고르면 기믹을 해제한다. 정규화 JSON은 이 사용 의도를 그대로
보관하며, 향후 RCT 출력기는 `gimmick.item`이 있으면 그것을 RCT의 실제 소지품으로
내보내고 그렇지 않으면 `held_item`을 내보낸다. 포켓몬별 기믹을 켜면 전투 전체의
`battle.mechanics.mega_evolution` 또는 `battle.mechanics.z_move`도 자동으로 허용된다.

테라 타입은 기본값 `auto`를 사용한다. 관리 화면에서는
`자동 (주속성 중 하나)`로 표시되며, RCT 출력 시 단일 타입 포켓몬은 그 타입을,
복합 타입 포켓몬은 두 원래 타입 중 하나를 실제 테라 타입으로 받는다. 같은 입력을
다시 빌드했을 때 결과가 바뀌지 않도록 RCT 출력기는 트레이너 ID와 팀 슬롯을 기준으로
복합 타입의 선택을 고정한다. 특정 타입을 직접 선택하면 자동 계산보다 우선한다.

### 엔트리 JSON 복사·붙여넣기

트레이너 화면의 `엔트리 JSON 복사`는 현재 `battle.team`을 공통 클립보드 JSON으로
복사한다. `엔트리 JSON 붙여넣기`는 현재 팀을 클립보드의 팀으로 교체한다. 같은
버튼이 전투 Web Lab의 사용자정의 엔트리 편집기에도 있으므로 두 웹 사이에서 바로
파티를 옮길 수 있다.

공통 형식은 `$schema: "cobbleventure:party-entry-clipboard"`와
`schema_version: 1`을 사용한다. 종·폼·aspects·성별·성격·특성·소지품·기믹·기술·
IV·EV·테라 타입·이로치·거다이맥스 정보를 보존한다. 붙여넣기는 이 공통 형식 외에도
포켓몬 배열, 전투 웹의 `party`/`team`, 관리 웹 트레이너 번들의 `battle.team`을
자동 판별한다. 브라우저 클립보드 읽기 권한을 사용할 수 없으면 JSON 직접 입력창으로
대체한다.

포켓몬 이미지는 기존 Cobbleverse Trainer Web Editor와 같은
`PokeAPI/sprites` GitHub 저장소를 사용한다. 전국도감 번호의 `official-artwork`를
먼저 표시하고 불러오지 못하면 같은 저장소의 기본 스프라이트로 전환한다. RCT 외형
미리보기와 포켓몬 이미지는 저장소나 모드팩에 복사되지 않으며, 네트워크가 없으면
이미지 없이 편집할 수 있다. 자세한 외형 매핑은
[트레이너 클래스와 외형 관리](../../docs/implementation/TRAINER_APPEARANCE.md)를
참고한다.

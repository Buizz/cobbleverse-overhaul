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
build.bat generate
build.bat pack-smoke
build.bat pack
build.bat pack-release
build.bat builder-world
```

상점 이름·카테고리와 EasyNPC 대사처럼 클라이언트의 마인크래프트 언어 설정을
직접 읽지 못하는 고정 텍스트는 빌드할 때 언어를 선택한다. 두 번째 인수에
`ko_kr`(기본값) 또는 `en_us`를 전달하거나, 관리 화면의 `빌드 및 검사`에서
내보내기 언어를 선택한다.

```bat
build.bat pack en_us
build.bat generate ko_kr
```

### 명령 설명

| 명령 | 용도 | 성공 조건 |
|------|------|-----------|
| `build.bat validate` | 개발 중인 의존성 Lock과 `content/source`의 정규화 콘텐츠를 검사한다. | JSON 형식, ID, 전투·대화·진행 참조에 오류가 없음 |
| `build.bat validate-pack` | CurseForge ZIP을 만들 수 있을 정도로 모든 실행 버전과 CurseForge ID가 고정되었는지 엄격하게 검사한다. | Lock 상태와 Minecraft·NeoForge·활성 모드 정보가 모두 확정됨 |
| `build.bat web` | 콘텐츠 관리 화면과 외부 로컬 도구용 Web API를 함께 실행한다. | `127.0.0.1:8765`에서 서버가 시작됨 |
| `build.bat api` | 기존 자동화 호환을 위한 `web` 명령의 별칭이다. | `web`과 동일하게 실행됨 |
| `build.bat test` | 콘텐츠 검증기와 로컬 Web API의 회귀 테스트를 실행한다. | 모든 Python 단위 테스트가 통과함 |
| `build.bat generate` | 트레이너 런타임 데이터와 CVES Runtime IR·NPC 바인딩을 생성한다. | 기존 출력과 `generated/cves/data` 데이터팩 산출물이 생성됨 |
| `build.bat pack-smoke` | 별도 팩 빌더로 최소 CurseForge 임포트 ZIP을 생성하고 검증한다. | `dist`에 ZIP과 SHA-256이 생성됨 |
| `build.bat pack` | 일반 검증 후 별도 팩 빌더로 임시 개발 ZIP을 생성한다. | `dist`에 개발 ZIP과 SHA-256이 생성됨 |
| `build.bat pack-release` | 정식 패키징 조건을 엄격 검증한다. | Lock이 `draft`인 현재는 실패하고 ZIP을 만들지 않음 |
| `build.bat builder-world` | 독립 건축 평지 월드와 경량 CurseForge ZIP을 생성한다. | 월드, 자체 JAR과 건축용 외부 모드가 포함된 ZIP 생성 |

#### `validate`

콘텐츠를 수정할 때 가장 자주 사용하는 명령이다. 다음 항목을 검사한다.

- 의존성 Lock의 필수 필드와 모드 ID 중복
- 정규화 콘텐츠 파일의 JSON 형식과 `schema_version`
- 콘텐츠, NPC, 지역, 마을, 앵커와 전투의 리소스 ID 형식
- NPC 외형·행동, 마을별 트레이너 배치 위치·생성 정책, 전투 규칙과 AI 난이도·전략 프로필
- 포켓몬 레벨·기술·개체값·노력치와 팀 전체 데이터
- 대화 노드·선택지 중복, 조건·동작 형식과 이동 대상 존재 여부
- `start_battle`이 현재 콘텐츠의 `battle.trainer_id`를 가리키는지 여부
- 진행 경로와 승리·패배 결과 동작의 참조
- `content/events/<namespace>/**/*.cves`의 구문·타입·프로젝트 리소스 참조
- `content/loot_tables/<namespace>/**/*.json`의 권위 ID, pool·roll·entry·function·condition
  구조와 활성 모드 아이템 카탈로그 참조
- `content/event-bindings`가 같은 프로젝트의 CVES script ID를 참조하는지 여부

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

`build.bat web` 또는 `build.bat api`를 다시 실행하면 같은 저장소의 이전 콘텐츠
관리자 Python 프로세스를 먼저 종료한다. 다른 Python 프로그램은 종료하지 않는다.

```text
http://127.0.0.1:8765
```

브라우저에서 기본 주소를 열면 다음 기능을 사용할 수 있다.

- `project.json`과 `content` 폴더로 구성된 코블벤처 프로젝트 불러오기
- 웹을 처음 열 때 Git에 포함된 `Cobbleventure Main` 프로젝트 자동 로드
- 저장소 검증 현황과 트레이너·마을 수 확인
- 새 트레이너·마을의 유효한 기본 JSON 생성
- 트레이너 기본 정보, NPC 행동, 전투 설정과 최대 6마리 팀 편집
- 휴리스틱·승률 기반·2턴 탐색 전문가 난이도와 치터 행동 열람 확률 편집
- 전투 가방의 회복·상태회복·능력치 아이템, 보유 수량과 전투당 최대 사용 횟수 편집
- 본가식 트레이너 클래스 선택과 RCT 개별·그룹 스킨 미리보기
- Battle Web Lab과 같은 포켓몬 슬롯·프로필·IV/EV·기술 집중 편집기
- 선택한 포켓몬을 좌우로 이동해 `battle.team`의 출전 순서 변경
- IV는 능력치별 `0~31`, EV는 능력치별 `0~252`와 전체 `510`에 맞춰 입력 즉시 자동 보정
- 포켓몬·폼·성격·특성·기술·지닌 도구를 검색해서 고르는 공용 다이얼로그
- 지역 폼과 특수 형태 선택, 카탈로그 외 Cobblemon `aspects` 직접 입력
- 대화·진행과 IV·EV 등 전체 트레이너 JSON 고급 편집
- 마을 이름, 지역, 차원, 중심, 경계와 기본 NPC 배치 수치 편집
- 마을 5개의 axial Q/R 위치와 `1→2→3→4`, `3→5` 같은 분기 동선 편집
- 마을 연결별 도로·자연·해상 통로와 바위오르기·파도타기 요구 조건 편집
- 12개 대표 서식지 프로필의 세대·온도·습도·날씨·시간·레어도 조건 편집과 출현 결과 테스트
- 바이옴 세트의 프로필 가중치와 모든 조건을 무시하는 강제 출현 포켓몬 편집
- 1,025마리 포켓몬 서식지 카탈로그 검색과 마을 바이옴 구역별 출현 미리보기
- 마을에서 배치할 트레이너를 선택하고 싱글은 EasyNPC 1명, 듀얼은 2명의 프로필·좌표·회전 편집
- 전체 마을 JSON 고급 편집
- 허용된 `build.bat` 검사·테스트·패키징 명령 실행과 결과 확인
- CurseForge 건축 프로필 인스턴스 경로를 로컬 설정에 저장
- 건축 팩 빌드, 기존 인스턴스의 건축 월드 갱신, `/cobbleventure_builder save all` 결과의 NBT 자동 가져오기
- 가져오기 성공 후 NBT 뷰어 목록 갱신, 화면 이동과 첫 3D 모델 자동 로드
- NBT 뷰어를 관리 원본 29개와 포켓몬센터·상점·백화점 3개로 제한
- NBT 건물 설정 화면에서 NPC 위치 라벨을 3D 마커로 확인하고 전역 NPC 콘텐츠 배정
- 각 NBT는 시민 수용 가능 여부만 관리하고, 실제 시민 수와 분산 배치는 마을 설정이 관리
- `카지노 설정`에서 Cobblemon Casino 2.0.0의 11개 설정을 전용 폼으로 편집하고, 희귀도별 가챠 보상과 NPC 상점 상품을 검색·추가·삭제
- 사이드바의 `이벤트 스크립트 V5`에서 CVES 원본 목록, 중첩 페이지·조건·선택지 트리와 노드 속성 편집
- CVES 트리의 대사·설명·조건·선택지·명령 추가/삭제/이동, 의미 검증과 결정적 formatter 저장
- 새 CVES 권위 원본 트리 생성과 트리거별 필수 target·선택 인자 편집
- 의미 검증기의 명령 계약에서 생성한 필수/선택 인자, 플래그, 속성, 결과 변수 전용 폼
- 아이템·전투·플래그 등 프로젝트 리소스 자동 완성과 상대/절대/마을/도로/공간/차원 이동 목적지·앵커 선택
- `not`/`all`/`any` 복합 조건 시각 조립, 하위 트리 접기와 같은 블록의 드래그 정렬
- 앞선 명령 결과 타입의 대사 변수·필드 삽입과 `${name|josa:을/를}` 한국어 조사 미리보기
- 페이지 우선순위·fallback·중첩 분기 실행 경로와 명시적 `AWAIT`/암시적 `WAIT` 완료 경계 표시
- 다국어 항목 추가·삭제와 언어별 조사 삽입, 기본 도구 상자에서 고급 흐름 명령 숨김
- 고급 CVES 텍스트를 lexer/parser로 공통 AST에 적용하고 파일·줄·열·문제 토큰 진단 확인

V5 편집기는 `content/events/<namespace>/**/*.cves`만 권위 원본으로 읽고 쓴다.
저장 직전 AST를 결정적으로 포맷하고 다시 parse·검증하며, 파일을 연 뒤 외부에서 원본이
바뀌었으면 SHA-256 충돌로 저장을 거부한다. 기존 V4 NPC JSON과 EasyNPC 프리셋은 이
화면의 저장 대상이 아니다. 일반·복합 조건은 중첩 시각 폼으로 만들며 표현 범위를
벗어난 식은 standalone expression parser가 공통 AST로 변환한다. 명령·트리거 폼은 Python 의미 계약을 직접
사용하므로 웹에 별도의 허용 명령이나 타입 목록을 중복 유지하지 않는다.

V4 item preset을 단계적으로 이전할 때는 `content/source/<path>.json`과 대응하는
`content/event-bindings/<namespace>/<path>.json`을 같은 상대 경로로 둔다. 프로젝트
컴파일러가 상호작용 범위, claimed 플래그, 최초/반복 대사와 지급 아이템·수량을 V5와
자동 비교한다. EasyNPC 생성기는 기존 V4 프리셋을 덮어쓰지 않고 `__v5.npc.snbt`
표현 프리셋을 추가하며, 이 프리셋에는 `cves_binding/<namespace>/<path>` 태그와 외형만
포함된다. 실제 배치 시 V4 프리셋과 V5 프리셋 중 하나만 선택해야 한다.

V4 배틀 이벤트 이전도 같은 상대 경로 규칙을 사용한다. 프로젝트 컴파일러는 V4의
`start_battle`을 발견하면 조건 대사와 선택지, battle 결과 승패 분기, 격파 플래그,
전리품 및 battle preset의 `money_reward`가 V5 트리와 같은지 검사한다. V5의
`has_item("namespace:item", count)` 조건은 타입 및 아이템 카탈로그 검사를 거치며,
EasyNPC `__v5` 표현 프리셋에는 배틀·상금·전리품 명령이 들어가지 않는다.

이동 문법과 프로젝트 위치 카탈로그의 통합 예제는
`tests/fixtures/movement_showcase.cves`에 있다. 상대 좌표, 절대 `position`, settlement
anchor와 route·dimension·space anchor, 독립 `anchor(event_anchor_id)`를 모두 사용하며
formatter canonical 형식과 IR의 안정 operation ID를 회귀 검사한다.
route·dimension·space 목적지는 명시적인 `anchor`가 필수지만 독립 `anchor(...)`에는
하위 `anchor` 속성을 붙이지 않는다. `movement_result.failure_reason`은 성공 시 빈
문자열이고 실패 시 사유 코드다.
`tests/fixtures/map_selection.cves`는 기존 월드맵에서 방문한 settlement를 선택해 typed
`location_ref`로 받은 뒤, 이를 별도 `teleport` await에 전달하는 선택·이동 분리 계약을
고정한다. V1은 임의 hex·cave·forest 선택을 허용하지 않는다.

건축 인스턴스 경로는 Git에 포함되지 않는
`tools/content-manager/settings.local.json`에 저장된다. 웹 화면의 `빌드 및 검사`에서
CurseForge 프로필 폴더를 지정한다. 이후 `건축 팩 빌드`는 독립 ZIP을 생성하고,
`건축 월드 갱신`은 Minecraft가 종료된 상태에서 기존 월드를 백업한 뒤 새 월드와
자체 건축 모드 JAR만 해당 인스턴스에 설치한다. CurseForge 프로필을 다시 임포트하거나
다른 모드와 설정을 교체하지 않는다.
`게임 NBT 자동 가져오기`는 해당 프로필의
`saves/Cobbleventure Structure Builder/generated` 아래 내보내기만 읽는다. 가져오기는
전체 관리 파일의 완전성과 크기를 검사하므로 실패할 때 저장소 NBT를 일부만 덮어쓰지 않는다.

저장 전 서버 검증에 실패하면 원본 파일을 변경하지 않는다. 관리 화면은 인증을
제공하지 않으므로 기본값인 로컬 주소를 유지하고 다른 PC가 접속할 수 있는 주소로
바인딩하지 않는다.

카지노 설정은 콘텐츠 프로젝트 폴더가 아니라 개발 배포팩의
`pack/overrides/development-placeholder/config/cobblemoncasino/` 아래에 저장된다.
모드가 사용하는 `general_config.json`, `machines/*.json`, `gachapon/*.json`,
`npc/*.json`만 허용하며 다른 상대 경로나 임의 파일은 API에서 거부한다. 저장된 설정은
다음 팩 빌드에 포함되고, 실행 중인 게임이나 서버에는 재시작 후 적용된다.
아이템 보상 176개, 포켓몬 보상 276개, Pokeblocks 인형 327개와 기본 NPC 거래는 모드 2.0.0
소스에서 추출한 전체 기본값을 제공한다. 모드 버전을 올릴 때는
`import_casino_defaults.py <Java config 폴더> casino_config_defaults.json`으로 상품
카탈로그를 다시 생성한 뒤 변경 내용을 검토한다. JSON 직접 편집은 고급 편집 영역에만
남겨 두며, 변경 반영 후 같은 서버 검증을 거친다.

### 콘텐츠 프로젝트

콘텐츠 관리자는 폴더 자체를 프로젝트로 취급한다. Git에서 관리하는 기본 프로젝트는
`content-projects/cobbleventure-main`이며, 웹 서버를 켜면 자동으로 불러온다. 다른
프로젝트는 상단의 현재 프로젝트 버튼에서 폴더를 선택해 불러온다. 프로젝트를 전환하면
이후 조회와 저장은 선택한 폴더의 `content` 아래에서 이루어진다.

프로젝트 폴더의 최소 구조는 다음과 같다.

```text
my-cobbleventure-project/
├─ project.json
└─ content/
```

`project.json` 형식은 다음과 같다.

```json
{
  "schema": "cobbleventure-content-project",
  "version": 1,
  "id": "my-project",
  "name": "내 코블벤처 프로젝트",
  "contentDirectory": "content"
}
```

`content`가 게임 콘텐츠의 본질이며 `project.json`은 그 콘텐츠를 식별하고 불러오기
위한 메타데이터다. 저장소 루트의 `tools`, `projects`, `build.bat`은 프로젝트들이 함께
사용하는 코블벤처 코어다. 로컬에서 추가한 프로젝트 폴더는 기본적으로 Git에서 제외되고,
기본 프로젝트만 코어 저장소와 함께 관리한다. 프로젝트별 빌드 출력 분리와 ZIP
가져오기·내보내기는 이 명세를 기준으로 확장한다.

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
python tools/content-manager/content_manager.py generate --root .
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
- `GET /api/project`: 현재 활성 코블벤처 프로젝트 조회
- `PUT /api/project`: `project.json`이 있는 프로젝트 폴더 불러오기
- `POST /api/project/pick`: Windows 프로젝트 폴더 선택창 열기
- `GET /api/cves/scripts`: 현재 프로젝트의 CVES 권위 원본 목록과 script ID 조회
- `GET /api/cves/script?path=<namespace/path.cves>`: CVES 원본, 공통 wire AST, 진단과 SHA-256 조회
- `GET /api/cves/editor-contract`: 명령·트리거 타입 계약, 프로젝트 리소스와 위치 앵커 조회
- `POST /api/cves/expression`: 단일 CVES 식을 위치 포함 진단과 canonical expression AST로 변환
- `POST /api/cves/validate`: `source` 또는 `ast`를 parse·타입·리소스 교차 검증하고 결정적 CVES 반환
- `PUT /api/cves/script`: wire AST와 `expected_digest`를 검증해 원자적으로 저장하며 외부 변경은 `409`로 거부
- `GET /api/trainers`, `GET /api/settlements`: 관리 문서 목록
- `GET /api/trainer-classes`: 트레이너 클래스와 기본 외형 카탈로그
- `GET /api/editor-catalog`: Battle Web Lab과 공유하는 포켓몬·폼·기술·특성·도구 및 트레이너 가방 아이템 카탈로그
- `GET /api/biome-catalog`, `PUT /api/biome-catalog`: 바이옴 프로필·세트 조회와 검증 후 저장
- `GET /api/world-layouts`: 작성된 세대 월드 목록 조회
- `GET /api/world-layout?generation=1`, `PUT /api/world-layout?generation=1`: 세대별 육각 타일·마을 배치 조회와 원자적 저장
- `GET /api/pokemon-habitats`: 1,025마리의 세대·속성·선호 환경·대표/보조 서식지 카탈로그
- `POST /api/biome-preview`: 프로필 또는 세트 조건에 따른 출현 포켓몬 판정
- `GET /api/trainers?path=...`, `GET /api/settlements?path=...`: 단일 문서 조회
- `PUT /api/trainers?path=...`, `PUT /api/settlements?path=...`: 검증 후 문서 저장
- `POST /api/document-validation?category=...`: 저장하지 않고 문서 검증
- `POST /api/documents`: 유효한 기본값으로 새 트레이너 또는 마을 생성
- `POST /api/build`: 허용 목록에 있는 빌드 명령 실행
- `GET /api/structure-builder`: 건축 인스턴스·월드·내보내기 NBT 상태 조회
- `GET /api/structure-viewer`: 관리 NBT 29개와 필수 BCA 시설 3개의 전용 미리보기 목록
- `PUT /api/structure-builder/settings`: 로컬 CurseForge 건축 인스턴스 경로 저장
- `POST /api/structure-builder/import`: 저장된 인스턴스 월드에서 NBT 검증 후 가져오기

응답은 UTF-8 JSON이다. Web API는 로컬 제작 도구용이며 인증 없이 외부
인터페이스에 바인딩하지 않는다.

마을 설정 화면의 **동선 저장 및 즉시 반영**은 검증을 통과한 내용을
`content/worlds/generation_1.json`에 즉시 저장한다. 이미 생성된 Minecraft 월드는
지도를 자동으로 다시 그리지 않으므로, 게임에서 확인할 때는 모드를 다시 빌드하고
지도 버전이 바뀐 새 테스트 월드를 사용한다.

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
- 바이옴 프로필·세트 참조, 구역별 출현 조건과 조건 무시 출현 포켓몬 ID

구조 계약은 `content/schemas/content-bundle.schema.json`, 실제 작성 예제는
`content/source/examples`에서 확인한다. 저장소 내 다른 JSON의 역할과 편집 여부는
[JSON 데이터 카탈로그](../../docs/JSON_CATALOG.md)에 정리되어 있다.

`build.bat generate`는 `content/source`를 검증한 뒤 RCT 트레이너와
Cobbleventure 게임 런타임 AI 프로필을 함께 생성한다. 생성 결과는
`generated/`에 있으며 Git에는 올리지 않는다. RCT 출력의 `ai.type`은
`cobbleventure`, `ai.data`에는 `difficulty`, `strategy`, 치터일 때
`cheatProbability`가 들어간다. 게임 어댑터는 같은 값의 런타임 프로필을 읽어
판단 엔진을 선택한다.

대화 그래프 전용 편집기, Excel 가져오기와 나머지 대상별 출력기는 다음 개발
단계에서 같은 도구에 추가한다.

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

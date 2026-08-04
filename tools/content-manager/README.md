# Cobbleventure Content Manager

Python 표준 라이브러리만으로 실행되는 콘텐츠·의존성 검증 도구의 첫 구현이다.
CLI와 로컬 Web API는 같은 검증 코드를 사용한다.

## 실행

저장소 루트에서 다음 중 하나를 사용한다.

```bat
build.bat validate
build.bat validate-pack
build.bat api
build.bat test
build.bat pack-smoke
```

### 명령 설명

| 명령 | 용도 | 성공 조건 |
|------|------|-----------|
| `build.bat validate` | 개발 중인 의존성 Lock과 `content/source`의 정규화 콘텐츠를 검사한다. | JSON 형식, ID, 대화 참조와 RCT 연결에 오류가 없음 |
| `build.bat validate-pack` | CurseForge ZIP을 만들 수 있을 정도로 모든 실행 버전과 CurseForge ID가 고정되었는지 엄격하게 검사한다. | Lock 상태와 Minecraft·NeoForge·활성 모드 정보가 모두 확정됨 |
| `build.bat api` | 콘텐츠 관리 화면이나 외부 로컬 도구에서 사용할 Web API를 실행한다. | `127.0.0.1:8765`에서 서버가 시작됨 |
| `build.bat test` | 콘텐츠 검증기와 로컬 Web API의 회귀 테스트를 실행한다. | 모든 Python 단위 테스트가 통과함 |
| `build.bat pack-smoke` | 별도 팩 빌더로 최소 CurseForge 임포트 ZIP을 생성하고 검증한다. | `dist`에 ZIP과 SHA-256이 생성됨 |

#### `validate`

콘텐츠를 수정할 때 가장 자주 사용하는 명령이다. 다음 항목을 검사한다.

- 의존성 Lock의 필수 필드와 모드 ID 중복
- 정규화 콘텐츠 파일의 JSON 형식과 `schema_version`
- 트레이너, NPC와 대화의 리소스 ID 형식 및 파일 간 중복
- NPC의 최초 대화와 `next_dialogue` 대상 존재 여부
- `start_rct_battle`이 현재 콘텐츠의 트레이너 ID를 가리키는지 여부

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

#### `api`

로컬 Web API를 실행하고 종료할 때까지 터미널을 점유한다. 종료하려면
`Ctrl+C`를 누른다. 기본 주소는 다음과 같다.

```text
http://127.0.0.1:8765
```

현재 API는 인증을 제공하지 않으므로 기본값인 로컬 주소를 유지한다. 다른 PC가
접속할 수 있는 주소로 바인딩하지 않는다.

#### `test`

`tools/content-manager/tests` 아래의 Python `unittest`를 실행한다. 콘텐츠
검증 규칙이나 Web API를 변경한 뒤 실행하며, 테스트 실패 시 변경을 패키징하지
않는다.

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

`api`는 기본적으로 `127.0.0.1:8765`에서 실행된다.

## API

- `GET /health`: 프로세스 상태
- `GET /dependencies`: 현재 의존성 Lock
- `GET /validate`: 저장소 데이터 검증
- `GET /validate?strict_pack=true`: CurseForge 패키징 가능 상태까지 검증
- `POST /validate`: `GET /validate`와 동일

응답은 UTF-8 JSON이다. Web API는 로컬 제작 도구용이며 인증 없이 외부
인터페이스에 바인딩하지 않는다.

## 현재 검증 범위

- 의존성 Lock 필수 필드, 상태, 모드 ID와 CurseForge ID 중복
- `locked` 상태에서 Minecraft·NeoForge·활성 모드 버전 고정 여부
- 정규화 콘텐츠 ID 형식과 파일 간 중복
- NPC의 최초 대화 참조
- 대화 노드와 선택지 ID 중복
- `next_dialogue` 대상 존재 여부
- `start_rct_battle`의 트레이너 ID 일치 여부

Excel 가져오기, 대상별 출력기와 CurseForge 패키징은 다음 개발 단계에서 같은
도구에 추가한다.

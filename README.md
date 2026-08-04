# Cobbleventure

`Cobbleventure(코블벤처)`는 세대별 어드벤처 월드와 PC 기반 개인 농장을 결합해 본가식 몬스터 수집 모험을 구현하는 독립 프로젝트입니다. `Cobble`과 `Adventure`를 합친 이름이며, 특정 모드팩에 종속되지 않습니다.

> 현재 상태: **기획 단계**
>
> 구현 목표 시기: **2027년 이후**
>
> 게임 연동 기준: **Cobblemon 안정 버전 정식 출시 후 별도 어댑터에서 확정**
>
> 현재 개발 기준: **Minecraft·NeoForge·Cobblemon 의존성이 없는 플랫폼 독립 코어 우선**

## 문서

- [프로젝트 기획서](docs/PROJECT_PLAN.md)
- [구현 설계 문서 안내](docs/implementation/README.md)
- [콘텐츠 제작 및 CurseForge 빌드 파이프라인](docs/implementation/CONTENT_BUILD_PIPELINE.md)
- [의존 모드 관리표](docs/MOD_DEPENDENCIES.md)
- [선택 아키텍처와 비교 기록](docs/implementation/WORLD_ARCHITECTURE_OPTIONS.md)
- [세대 월드 8지역 및 체육관 도시 생성](docs/implementation/WORLD_GENERATION.md)
- [도시 포맷과 세대별 특수 시설](docs/implementation/CITY_FACILITIES.md)
- [Cobbleventure Core](projects/cobbleventure-core/README.md)
- [Cobbleventure Battle AI 프로젝트](projects/cobbleventure-battle-ai/README.md)
- [트레이너 JSON 예제 데이터](trainer-data/README.md)

## 개발 환경

현재 개발 도구를 모두 실행하려면 다음 환경이 필요합니다.

| 도구 | 최소 기준 | 사용 영역 |
|------|-----------|-----------|
| Windows PowerShell 또는 명령 프롬프트 | Windows 10/11 | 루트 BAT와 Gradle Wrapper 실행 |
| Python | 3.10 이상 | 콘텐츠 검증과 데이터 관리 Web API |
| Java JDK | 21 | Cobbleventure Core와 Battle AI 빌드 |
| Node.js | 22.13.0 이상 | 전투 테스트 Web Lab |
| npm | 사용 중인 Node.js에 포함된 버전 | Web Lab 의존성 설치·빌드·실행 |

아래 명령은 특별한 설명이 없다면 저장소 루트에서 실행합니다.

```powershell
cd E:\Source\repos\Buizz\cobbleverse-overhaul
```

## 빠른 시작

### 콘텐츠와 의존성 검사

```bat
build.bat validate
```

현재 Cobblemon 1.8과 외부 모드 버전이 확정되지 않았으므로 `draft` 경고 1개가
나오는 것이 정상입니다. `검증 성공: 오류 0개`가 표시되면 콘텐츠 개발을 계속할
수 있습니다.

### 전투 테스트 Web Lab 실행

Windows에서는 Web Lab 폴더의 BAT 파일을 사용하는 방법이 가장 간단합니다.

```powershell
cd projects\cobbleventure-battle-ai\web-lab
.\start.bat
```

탐색기에서
`projects\cobbleventure-battle-ai\web-lab\start.bat`을 더블클릭해도 됩니다.
`start.bat`은 다음 작업을 자동으로 처리합니다.

- 최초 실행에서 `node_modules`가 없으면 `npm ci` 실행
- 트레이너·다국어 데이터 동기화와 전투 메커니즘 검사
- 개발 서버를 숨겨진 백그라운드 프로세스로 실행
- 서버 준비를 최대 90초 동안 확인
- 준비가 끝나면 기본 브라우저로 `http://localhost:3000` 열기

트레이너 동기화와 메커니즘 감사가 함께 실행되므로 PC 상태에 따라 시작에
수십 초가 걸릴 수 있습니다. 준비 확인이 끝날 때까지 `start.bat` 창을 닫지
않습니다.

실행 중에 `start.bat`을 다시 사용하면 서버를 중복으로 만들지 않고 기존 페이지를
엽니다. 포트 `3000`을 다른 프로그램이 사용 중이면 해당 프로그램을 먼저
종료해야 합니다.

서버 상태와 시작 오류는 Web Lab 폴더의 다음 로컬 로그에서 확인합니다.

```text
.local-server.log
.local-server-error.log
```

이 로그와 `.local-server.pid`는 로컬 실행 파일이므로 Git에 포함되지 않습니다.
브라우저를 자동으로 열지 않으려면 `COBBLEVERSE_NO_BROWSER=1` 환경 변수를 설정한
뒤 `start.bat`을 실행합니다.

준비가 끝나면 다음 주소를 사용할 수 있습니다.

- 전투 실험실: [http://localhost:3000](http://localhost:3000)
- EvE 대량 전투 리포트: [http://localhost:3000/eve-report](http://localhost:3000/eve-report)

`start.bat`으로 시작한 백그라운드 서버는 반드시 `stop.bat`으로 종료합니다.

```powershell
cd projects\cobbleventure-battle-ai\web-lab
.\stop.bat
```

`stop.bat`은 `.local-server.pid`에 기록된 서버와 그 하위 Node 프로세스를 함께
종료합니다. 이미 종료되어 있으면 오류 없이 종료된 상태라고 안내합니다.

서버 출력을 터미널에서 직접 확인하며 개발하려면 BAT 대신 다음 명령을 사용합니다.

```powershell
cd projects\cobbleventure-battle-ai\web-lab
npm ci
npm run dev
```

수동으로 실행한 서버는 `stop.bat`이 아니라 실행 중인 터미널에서 `Ctrl+C`로
종료합니다. `npm ci`는 최초 실행이나 `package-lock.json` 변경 후에만 다시
실행하면 됩니다.

### 데이터 관리 Python Web API 실행

저장소 루트에서 다음 명령을 실행합니다.

```bat
build.bat api
```

`Cobbleventure Content Manager API` 메시지가 출력되면 브라우저에서 다음 주소로
동작을 확인할 수 있습니다.

- 상태 확인: [http://127.0.0.1:8765/health](http://127.0.0.1:8765/health)
- 의존 모드 Lock 조회: [http://127.0.0.1:8765/dependencies](http://127.0.0.1:8765/dependencies)
- 콘텐츠 검증: [http://127.0.0.1:8765/validate](http://127.0.0.1:8765/validate)
- 패키징 준비 검증: [http://127.0.0.1:8765/validate?strict_pack=true](http://127.0.0.1:8765/validate?strict_pack=true)

현재 Python 서버는 관리 프로그램이 사용할 **JSON Web API**만 제공하며, 사람이
폼으로 데이터를 편집하는 시각적 웹 화면은 아직 구현되지 않았습니다. 기본
주소 `http://127.0.0.1:8765/` 자체는 API 경로가 아니므로 `404`가 정상입니다.
서버를 종료하려면 `Ctrl+C`를 누릅니다.

포트를 바꾸어 직접 실행할 수도 있습니다.

```powershell
py -3 tools\content-manager\content_manager.py api --root . --port 8766
```

인증 기능이 없는 로컬 제작 API이므로 `127.0.0.1` 이외의 주소로 공개하지
않습니다.

## 빌드와 테스트

### 루트 `build.bat` 명령

현재 `build.bat`은 콘텐츠 도구의 통합 진입점입니다. 아직 모든 Java 모드와
CurseForge ZIP을 한 번에 만드는 전체 빌드 스크립트는 아닙니다.

| 명령 | 설명 | 현재 예상 결과 |
|------|------|----------------|
| `build.bat validate` | 의존성 Lock, 정규화 콘텐츠와 대화·RCT 참조 검사 | `draft` 경고를 허용하고 오류가 없으면 성공 |
| `build.bat validate-pack` | Minecraft·NeoForge·활성 모드 버전과 CurseForge ID까지 엄격 검사 | 의존성 확정 전에는 실패가 정상 |
| `build.bat api` | 데이터 관리용 Python Web API 실행 | `127.0.0.1:8765`에서 종료할 때까지 실행 |
| `build.bat test` | Python 검증기와 Web API 회귀 테스트 | 모든 `unittest`가 통과해야 함 |

`build.bat`을 인자 없이 실행하면 사용 가능한 명령을 표시합니다. 자세한 검사
규칙과 종료 코드는 [Content Manager 사용법](tools/content-manager/README.md)을
참고합니다.

### 플랫폼 독립 Java 프로젝트 빌드

Battle AI를 빌드하고 테스트합니다.

```powershell
.\projects\cobbleventure-battle-ai\gradlew.bat `
  -p .\projects\cobbleventure-battle-ai clean build
```

같은 Gradle Wrapper를 사용해 Cobbleventure Core를 빌드하고 테스트합니다.

```powershell
.\projects\cobbleventure-battle-ai\gradlew.bat `
  -p .\projects\cobbleventure-core clean build
```

빌드 JAR은 각 하위 모듈의 `build/libs`에 생성됩니다. 현재 결과는 Minecraft에
직접 설치하는 NeoForge 모드 JAR이 아니라 플랫폼 독립 라이브러리입니다.

### 전투 Web Lab 빌드와 테스트

`start.bat`과 `stop.bat`은 개발 서버를 편하게 켜고 끄는 도구입니다. 프로덕션
빌드와 자동 테스트는 아래 npm 명령으로 별도 실행합니다.

```powershell
cd projects\cobbleventure-battle-ai\web-lab
npm ci
npm test
```

`npm test`는 데이터 동기화와 프로덕션 빌드를 수행한 뒤 렌더링, 트레이너 변환,
전투 시나리오, Showdown 호환 엔진, 자체 전투 엔진과 AI 테스트를 실행합니다.

프로덕션 형태로 직접 실행하려면 다음 명령을 사용합니다.

```powershell
npm run build
npm start
```

### 전투 테스트 Web API

전투 API는 별도 프로세스가 아니라 `npm run dev` 또는 `npm start`로 실행한 Web
Lab 서버에 포함됩니다. 기본 주소는 `http://localhost:3000/api/...`입니다.

| 메서드와 경로 | 역할 |
|---------------|------|
| `GET /api/battle-catalog` | 트레이너, 포켓몬과 편집 카탈로그 조회 |
| `POST /api/scenarios` | 입력 데이터를 검증해 재현 가능한 전투 시나리오 생성 |
| `POST /api/battles` | Showdown 호환 기준 엔진으로 전투 실행 |
| `POST /api/native-battles` | Cobbleventure 자체 엔진으로 전투 실행 |
| `POST /api/interactive-battles` | 턴 단위 상호작용 전투 세션 처리 |
| `POST /api/battle-sweep` | 여러 전투를 실행해 EvE 결과 집계 |
| `GET /api/pokemon-sprites` | Web Lab에서 사용하는 포켓몬 이미지 조회 |

전투 요청 JSON은 웹 화면에서 생성·검증할 수 있습니다. API 계약과 전투 엔진의
상세 범위는 [Battle AI 프로젝트 문서](projects/cobbleventure-battle-ai/README.md)를
참고합니다.

## CurseForge 테스트팩 상태

최종 목표는 다음 명령으로 외부 의존성, 생성 데이터와 자체 NeoForge JAR을
합쳐 CurseForge 임포트 ZIP을 생성하는 것입니다.

```bat
build.bat pack
```

하지만 `pack` 명령과 NeoForge 게임 어댑터는 아직 구현되지 않았습니다. 현재
`build.bat validate-pack`은 불완전한 의존성으로 ZIP이 만들어지는 것을 막는
준비 검사입니다. Cobblemon 1.8 안정판과 대상 모드 버전을 고정한 뒤 패키징
단계를 연결합니다.

## 현재 저장소 범위

현재는 기획 문서, 플랫폼 독립 지역 코어와 전투 AI, 트레이너 JSON 예제 데이터를 관리합니다. Minecraft·NeoForge·Cobblemon 연동 모드와 특정 트레이너 모드 어댑터는 대상 안정 버전을 확정한 뒤 추가합니다.

의존성 방향은 항상 `게임 어댑터 → 플랫폼 독립 코어`로 유지합니다. 플랫폼 독립 코어는 Minecraft, NeoForge, Cobblemon 또는 특정 트레이너 모드 클래스를 참조하지 않습니다.

향후 구현 단계에서는 필요에 따라 다음 영역을 추가할 예정입니다.

```text
projects/           플랫폼 독립 코어·전투 AI와 향후 게임 어댑터
content/            트레이너·진행·보상 등 데이터
trainer-data/       외부 트레이너 JSON 원본 예제
config-overrides/   기존 모드 설정 변경분
structures/         체육관 도시 구조물 원본
resources/          텍스처·번역·사운드 등 자체 리소스
docs/               기획·설계·결정 기록
```

## 저장소 원칙

- Cobbleverse를 포함한 타 모드팩이나 모드의 JAR 파일은 저장소에 넣지 않습니다.
- 마인크래프트 실행 폴더, 월드, 로그, 크래시 보고서와 빌드 결과물은 커밋하지 않습니다.
- 설정, JSON/TOML, 스크립트, 구조물 원본, 직접 제작한 리소스처럼 재현에 필요한 작업 파일을 관리합니다.
- 대용량 바이너리 작업물이 늘어나면 Git LFS 도입 여부를 별도로 결정합니다.
- 게임 어댑터 구현 착수 시점에 Cobblemon 안정 버전과 호환성 범위를 고정하고 문서화합니다.
- Cobbleverse는 필수 기반이 아니라 필요할 때 제공하는 선택 호환성 프로필로 취급합니다.

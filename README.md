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
- [JSON 데이터 카탈로그](docs/JSON_CATALOG.md)
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

### CurseForge 임포트 스모크 팩 생성

정식 모드가 없는 상태에서도 CurseForge의 프로필 임포트 흐름을 먼저 확인할 수
있습니다.

```bat
build.bat pack-smoke
```

성공하면 다음 파일이 생성됩니다.

```text
dist/cobbleventure-import-smoke-0.1.1-curseforge.zip
dist/cobbleventure-import-smoke-0.1.1-curseforge.zip.sha256
```

스모크 팩에는 다음 항목만 들어 있습니다.

- Minecraft 1.21.1
- NeoForge 21.1.248
- 외부 모드 0개
- 테스트용 정사각형 팩 아이콘
- overrides 임포트 확인용 텍스트와 팩 정보 JSON

이 버전은 CurseForge 임포트 파이프라인 확인용으로 별도 고정한 값이며, Cobblemon
1.8 정식팩의 버전을 확정한 것이 아닙니다.

CurseForge 앱에서 다음 순서로 가져옵니다.

1. Minecraft 화면에서 `Import`를 선택합니다.
2. `Import Profile .zip` 또는 `Choose .zip file`을 선택합니다.
3. `dist/cobbleventure-import-smoke-0.1.1-curseforge.zip`을 선택합니다.
4. `Cobbleventure Import Smoke Test` 프로필이 생성되는지 확인합니다.
5. 프로필 상세에서 Minecraft 1.21.1과 NeoForge가 선택되었는지 확인합니다.
6. 테스트 아이콘이 프로필 이미지로 적용되는지 확인합니다.
7. 가능하면 게임을 한 번 실행해 빈 NeoForge 프로필이 정상 시작되는지 확인합니다.

임포트된 인스턴스의 `config/cobbleventure-import-smoke.txt`가 존재하면
`overrides`도 정상 적용된 것입니다. `manifest.json`의 `image`는 ZIP 최상위의
`icon.png`를 상대 경로로 가리킵니다. CurseForge는 import 임시 폴더에서 이 파일을
찾아 프로필 이미지로 복사합니다. ZIP 안의 `overrides/icon.png`는 수동 지정용
사본입니다. CurseForge의 공식 임포트 화면과 오류 기준은
[Sharing Modpacks/Custom Profiles](https://support.curseforge.com/support/solutions/articles/9000197912)를
참고합니다.

### 임시 개발 팩 생성

스모크 테스트보다 한 단계 위인 개발용 패키징 흐름은 다음 명령으로 확인합니다.

```bat
build.bat pack
```

이 명령은 일반 콘텐츠 검증을 먼저 통과한 뒤 다음 파일을 생성합니다.

```text
dist/cobbleventure-development-0.1.1-curseforge.zip
dist/cobbleventure-development-0.1.1-curseforge.zip.sha256
```

현재 개발 팩에는 Cobblemon 1.7.3, BCA 실행 호환 계층과 CobbleDollars,
Cobblemon Additions 4.2.1 원본 JAR이 포함됩니다. `build.bat pack`은 먼저
무지하 전용 시작 바이옴·세대 차원을 생성하는 부트스트랩 Java 모드를 빌드하고 함께 넣습니다. RCT와
정식 Cobbleventure NeoForge 게임 어댑터는 아직 포함하지 않으며 ZIP 안의 팩
정보에는 `production_ready: false`를 기록합니다.

새 월드에 처음 입장하면 동굴·광맥·지하 구조물이 없는
`cobbleventure:generation_1` 차원으로 플레이어를 옮깁니다. 전용
`cobbleventure:starter_plains` 바이옴의 새 스폰에서 X/Z 각각 `+32` 블록 떨어진 지표면에
자체 체육관을 중심으로 BCA 도로와 건물이 확장되는 Cobbleventure 시작 마을을
배치합니다. 체육관은 공통 외관을 사용하고 관장 타입에 따라 지붕 색이 바뀌며,
현재 시작 체육관은 바위 타입의 회색 지붕입니다. 이 기능은 정식 지역 플래너 전의 테스트용이며
세부 동작과 저장 방식은
[World Bootstrap 문서](projects/cobbleventure-world-bootstrap/README.md)를 참고합니다.

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

### 데이터 관리 Python Web 실행

저장소 루트에서 다음 명령을 실행합니다.

```bat
build.bat web
```

`Cobbleventure Content Manager` 메시지가 출력되면 브라우저에서 다음 주소를
엽니다.

- 관리 화면: [http://127.0.0.1:8765/](http://127.0.0.1:8765/)
- 상태 확인 API: [http://127.0.0.1:8765/health](http://127.0.0.1:8765/health)
- 콘텐츠 검증 API: [http://127.0.0.1:8765/validate](http://127.0.0.1:8765/validate)

관리 화면에서는 프로젝트 검증 상태를 확인하고, 트레이너와 마을을 새로 만들 수
있습니다. 트레이너의 기본 정보·NPC 행동·마을 배치·AI·특수기믹·포켓몬 팀과
마을의 이름·지역·차원·중심·경계·기본 NPC 배치 수치를 폼으로 수정할 수 있습니다.
트레이너 클래스와 RCT 외형을 선택할 수 있고, 포켓몬 팀은 전투 Web Lab과 같은
슬롯·집중 편집 화면에서 구성합니다.
`validate`, `test`, `pack-smoke`, `pack`, `validate-pack`도 버튼으로 실행할 수
있습니다. 저장 요청은 서버에서 다시 검증하며 오류가 있으면 원본 파일을
덮어쓰지 않습니다.
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
| `build.bat validate` | 의존성 Lock과 정규화 콘텐츠의 전투·대화·진행 참조 검사 | `draft` 경고를 허용하고 오류가 없으면 성공 |
| `build.bat validate-pack` | Minecraft·NeoForge·활성 모드 버전과 CurseForge ID까지 엄격 검사 | 의존성 확정 전에는 실패가 정상 |
| `build.bat web` | 데이터 관리용 Python Web 화면과 API 실행 | `127.0.0.1:8765`에서 종료할 때까지 실행 |
| `build.bat api` | `build.bat web`의 호환용 별칭 | Web 화면과 API가 동일하게 실행됨 |
| `build.bat test` | 콘텐츠/Web/팩 회귀 테스트와 월드 부트스트랩 Java 컴파일 | Python `unittest`와 Gradle 테스트가 모두 통과해야 함 |
| `build.bat mod-bootstrap` | 무지하 시작 바이옴·세대 차원·BCA 테스트 마을용 NeoForge Java 모드 빌드 | 체육관 NBT 생성 후 개발 팩 `overrides/mods`에 JAR 생성 |
| `build.bat pack-smoke` | 최소 CurseForge 임포트 테스트 ZIP 생성·재검증 | `dist`에 ZIP과 SHA-256 생성 |
| `build.bat pack` | 일반 콘텐츠 검증 후 임시 개발 팩 생성 | 임포트 가능한 개발용 ZIP과 SHA-256 생성 |
| `build.bat pack-release` | 정식 의존성·배포 준비 상태를 엄격 검사 | 현재는 누락 항목을 출력하고 실패하는 것이 정상 |

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

## CurseForge 패키징 명령의 구분

| 명령 | 목적 | ZIP 생성 여부 |
|------|------|---------------|
| `pack-smoke` | CurseForge가 최소 manifest와 overrides를 받아들이는지 확인 | 항상 스모크 ZIP 생성 |
| `pack` | 콘텐츠 검증부터 개발 ZIP 생성까지의 작업 흐름 확인 | 현재 임시 개발 ZIP 생성 |
| `pack-release` | 정식 의존성과 공개 배포 준비가 완료됐는지 확인 | 현재는 차단되며 ZIP을 만들지 않음 |

`pack-smoke`와 임시 `pack`은 정식 `dependencies.lock.json`을 확정한 것으로
취급하지 않습니다. 둘 다 Minecraft 1.21.1과 NeoForge 21.1.248을 사용합니다.
`pack-smoke`는 외부 모드가 없는 임포트 구조 시험이고, 임시 `pack`은
CurseForge 외부 모드 6개와 직접 포함 JAR 2개(BCA 및 시작 마을
부트스트랩)를 포함하는 플레이 테스트 팩입니다.

```bat
build.bat pack-release
```

현재 `pack-release`는 내부적으로 `validate-pack`을 실행합니다. RCT API와
RCT의 버전·CurseForge project ID/file ID가 미정이므로 종료 코드
`1`로 실패하고 ZIP을 생성하지 않는 것이 정상입니다. 의존성 Lock이 완성된
뒤에는 이 명령에 외부 의존성 manifest 생성, 자체 JAR 취합, 라이선스와 공개
배포 검사를 연결합니다.

모든 ZIP 생성 명령은 결과를 다시 열어 `manifest.json`, `overrides/`, CRC,
내부 경로와 프로필 값이 올바른지 검사합니다. 자세한 구조는
[CurseForge Pack Builder 문서](tools/pack-builder/README.md)를 참고합니다.

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

# 빌드와 배포

## 명령을 실행하는 두 가지 방법

- 초보 사용자: Content Studio의 `빌드 작업` 탭에서 버튼을 누릅니다.
- 개발자: 저장소 루트 터미널에서 `build.bat <명령>`을 실행합니다.

두 방법은 같은 허용 명령을 사용합니다. 자세한 화면 사용법은 [빌드 작업 탭](pages/14-builds.md)을 참고하세요.

## 주요 명령

| 명령 | 용도 | 성공 결과 |
|---|---|---|
| `build.bat validate` | 콘텐츠와 참조 검사 | 오류 0개 |
| `build.bat validate-pack` | 정식 패키징 조건 엄격 검사 | 모든 버전과 CurseForge ID 확정 |
| `build.bat test` | Python 회귀 테스트와 Java 모듈 테스트 | 모든 테스트 통과 |
| `build.bat generate` | RCT 트레이너와 게임용 AI 데이터 생성 | `generated` 아래 결과 생성 |
| `build.bat spawns` | 엑셀 기반 Cobblemon 스폰 데이터 생성 | 개발 팩 overrides에 데이터와 보고서 생성 |
| `build.bat music` | 선택된 로컬 음원을 Paxi 리소스팩으로 생성 | 음악 리소스팩 생성 |
| `build.bat mod-bootstrap` | 시작 월드·마을 모드 빌드 | 개발 팩 mods에 JAR 생성 |
| `build.bat mod-menu` | 원형 플레이어 메뉴 모드 빌드 | 개발 팩 mods에 JAR 생성 |
| `build.bat pack-smoke` | 최소 CurseForge 임포트 테스트 팩 | `dist`에 ZIP과 SHA-256 생성 |
| `build.bat pack` | 현재 개발용 전체 팩 생성 | `dist`에 개발 ZIP과 SHA-256 생성 |
| `build.bat pack-release` | 정식 배포 준비 검사 | 현재 구현·의존성 확정 전에는 실패가 정상 |
| `build.bat builder-world` | 독립 NBT 건축 월드 패키지 생성 | 건축용 CurseForge ZIP 생성 |

## 개발 팩 만들기

처음에는 다음 순서를 권장합니다.

```bat
build.bat validate
build.bat test
build.bat pack
```

`pack`은 검증, 생성, 스폰·음악·데이터 모드 생성, Java 모드 빌드와 패키징을 순서대로 수행합니다. 중간 단계가 실패하면 이후 단계와 ZIP 생성이 중단됩니다.

성공 시 `dist` 폴더에 다음 형식의 파일이 생성됩니다.

```text
cobbleventure-development-<버전>-curseforge.zip
cobbleventure-development-<버전>-curseforge.zip.sha256
```

[사진: dist 폴더의 개발 팩 ZIP과 SHA-256 파일]

## Cobblemon 빌드 대상 전환

기본 빌드 대상은 기존과 같은 Cobblemon `1.7.3`입니다. 공식 Discord에서 받은 NeoForge 1.8 스냅샷 JAR을 `.tmp\cobblemon-1.8-snapshot`에 두면 다음처럼 현재 PowerShell 세션만 1.8 대상으로 전환할 수 있습니다.

```powershell
$env:COBBLEVENTURE_COBBLEMON_TARGET = "1.8"
.\build.bat test
.\build.bat mod-menu
.\build.bat mod-adventure
.\build.bat mod-bootstrap
.\build.bat mod-casino
```

해당 폴더에 여러 스냅샷이 있으면 가장 최근 파일을 자동으로 사용합니다. 다른 위치의 JAR을 쓰려면 절대 경로를 지정합니다.

```powershell
$env:COBBLEVENTURE_COBBLEMON_JAR = "D:\mods\Cobblemon-neoforge-1.8.0-snapshot.jar"
```

1.8 대상으로 빌드하면 커스텀 모드의 컴파일 의존성과 생성된 `neoforge.mods.toml` 범위가 `[1.8.0,1.9)`로 전환됩니다. 기본 1.7.3 대상은 `[1.7.3,1.8)`을 유지합니다.

현재 `pack\profiles\development-placeholder.json`과 외부 애드온 Lock은 아직 1.7.3 기준입니다. 따라서 1.8 상태의 `build.bat pack`은 서로 다른 버전이 섞인 ZIP을 만들지 않도록 의도적으로 중단됩니다. 먼저 위의 `test`와 `mod-*` 명령으로 자체 모드 호환성을 확인하고, 전체 팩 전환은 외부 애드온 검증과 별도 프로필 작업 후 진행합니다.

작업을 마치고 기본 대상으로 돌아오려면 환경 변수를 제거합니다.

```powershell
Remove-Item Env:COBBLEVENTURE_COBBLEMON_TARGET
Remove-Item Env:COBBLEVENTURE_COBBLEMON_JAR -ErrorAction SilentlyContinue
```

## CurseForge 임포트 확인

1. CurseForge 앱의 Minecraft 화면에서 `Import`를 선택합니다.
2. `Import Profile .zip` 또는 `Choose .zip file`을 선택합니다.
3. `dist`의 개발 팩 ZIP을 선택합니다.
4. 프로필 이름, Minecraft 버전, NeoForge 버전을 확인합니다.
5. 게임을 실행하고 새 월드에서 시작 차원과 시작 마을을 확인합니다.
6. NPC, 배틀, 음악, 스폰 등 이번 변경과 관련된 기능을 실제로 시험합니다.

[사진: CurseForge에서 개발 팩 ZIP을 선택하는 화면]

## 최소 임포트 테스트

전체 팩보다 먼저 CurseForge 임포트 흐름만 확인하려면 다음을 실행합니다.

```bat
build.bat pack-smoke
```

스모크 팩은 외부 모드 없이 프로필 생성, 아이콘, overrides 적용 여부를 확인하기 위한 패키지입니다. 실제 플레이용 팩이 아닙니다.

## 정식 배포 주의사항

현재 `pack-release`는 의존성 Lock과 배포 준비 조건이 끝나지 않은 경우 의도적으로 실패하며, 정식 배포 ZIP 내보내기도 아직 완성되지 않았습니다. `pack` 결과 역시 `production_ready: false`인 개발용일 수 있습니다. 따라서 개발 팩을 정식 릴리스라고 안내하거나 공개 배포하지 마세요.

정식 배포 전에 최소한 다음이 필요합니다.

- `validate-pack` 성공
- 모든 활성 모드 버전과 CurseForge project/file ID 고정
- `pack-release` 구현 및 성공
- SHA-256 체크섬 확인
- 깨끗한 환경에서 CurseForge 임포트와 게임 실행 테스트

## 실패했을 때

터미널 또는 빌드 작업 탭에서 **처음 발생한 오류**부터 해결합니다. 마지막 줄의 연쇄 실패만 보고 원인을 추측하지 마세요.

- Python을 찾지 못함: Python 3 설치와 `py -3 --version` 확인
- Java/Gradle 실패: JDK 21과 `JAVA_HOME` 확인
- npm 실패: Node.js 버전과 `package-lock.json` 기준 `npm ci` 확인
- 검증 실패: 출력된 프로젝트 상대 경로와 필드 이름 수정
- ZIP 미생성: 이전 단계가 실패했는지 확인
- 포트 충돌: 실행 중인 Content Studio 또는 Web Lab 종료


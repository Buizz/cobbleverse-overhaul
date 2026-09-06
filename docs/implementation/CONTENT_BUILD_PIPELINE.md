# 콘텐츠 제작 및 모드팩 빌드 파이프라인

> 상태: 기준 설계 · 1차 개발 착수
>
> 적용 범위: 트레이너, NPC, 대화, 퀘스트, 번역, 의존 모드, CurseForge 테스트팩

## 1. 목적

Cobbleventure의 콘텐츠는 사람이 읽고 편집하기 쉬운 Excel 또는 원본 JSON으로
작성한다. Python 콘텐츠 관리 도구가 입력을 하나의 정규화 JSON 형식으로 바꾼
뒤 RCT, Cobbleventure NPC, Easy NPC와 웹 도구가 요구하는 데이터로 내보낸다.

최종 빌드는 생성 데이터, 자체 NeoForge 모드 JAR, 고정된 외부 모드 의존성을
합쳐 CurseForge에서 프로필로 가져올 수 있는 ZIP을 만든다. 수작업으로 파일을
복사하거나 같은 트레이너를 여러 형식으로 중복 편집하지 않는 것이 핵심이다.

## 2. 단일 기준 데이터

Excel과 JSON을 동시에 동일한 권위의 원본으로 사용하지 않는다.
저장소에 있는 각 JSON의 권위와 편집 기준은
[JSON 데이터 카탈로그](../JSON_CATALOG.md)를 따른다.

| 계층 | 역할 | Git 관리 |
|------|------|----------|
| Excel·원본 JSON | 콘텐츠 제작자가 편집하는 입력 | 관리 |
| 정규화 JSON | 모든 출력기가 읽는 단일 기준 형식 | 원본 JSON은 관리, Excel 변환 결과는 정책에 따라 결정 |
| RCT·NPC·Easy NPC JSON | 대상 모드가 읽는 생성물 | 빌드 시 재생성 |
| NeoForge JAR·CurseForge ZIP | 실행·배포 산출물 | 관리하지 않음 |

Excel을 사용하면 `trainers`, `trainer_teams`, `dialogue_nodes`,
`dialogue_choices`, `conditions`, `actions`, `quests`, `quest_objectives`,
`translations`, `npc_spawns` 시트로 나눈다. 셀 하나에 중첩 JSON을 길게 넣는
방식은 피하고 행 ID로 관계를 표현한다.

## 3. 데이터 흐름

```text
Excel / source JSON
        |
        v
Python content-manager
  - import
  - normalize
  - validate
        |
        v
normalized content
  |-- RCT trainer JSON
  |-- Cobbleventure NPC/dialogue JSON
  |-- Easy NPC compatibility data
  |-- quest and translation data
  `-- web data
        |
        v
Gradle NeoForge builds
        |
        v
dependency lock + CurseForge manifest + overrides
        |
        v
dist/cobbleventure-<profile>-<version>-curseforge.zip
```

변환은 결정론적이어야 한다. 동일한 Git 커밋, 의존성 Lock과 입력 데이터로
실행한 빌드는 동일한 논리 콘텐츠를 생성해야 한다.

## 4. 출력 프로필

정규화 데이터는 출력기 어댑터를 통해 여러 대상 형식으로 변환한다.

- `CobbleventureNpcExporter`: 자체 NPC와 자체 대화창 데이터
- `EasyNpcExporter`: Easy NPC 비교·호환 프로필 데이터
- `RctTrainerExporter`: RCT 트레이너와 Cobbleventure AI 선택 데이터
- `WebExporter`: 관리 화면과 전투 실험실 데이터

최종 모드팩 프로필은 NPC 런타임 하나만 선택한다. 자체 NPC와 Easy NPC가 같은
역할의 NPC를 동시에 생성하지 않게 한다.

- 기본 프로필: Cobbleventure 자체 NPC·대화 시스템
- 호환 프로필: Easy NPC가 NPC·대화를 담당하고 RCT 전투 연결부를 사용

## 5. Python 콘텐츠 관리 도구

`tools/content-manager`는 CLI와 로컬 Web API가 동일한 검증·변환 코드를
공유한다. Web API는 기본적으로 `127.0.0.1`에만 바인딩하며 외부 관리 API로
공개하지 않는다.

1차 명령은 다음과 같다.

| 명령 | 현재 역할 | 사용 시점 |
|------|-----------|-----------|
| `build.bat validate` | 의존성 Lock과 정규화 콘텐츠의 형식·교차 참조 검사 | 콘텐츠를 편집한 뒤 반복 실행 |
| `build.bat validate-pack` | 버전과 CurseForge ID까지 포함한 엄격한 패키징 준비 검사 | ZIP 생성 직전 |
| `build.bat api` | Python 콘텐츠 관리 로컬 Web API 실행 | 웹 관리 화면이나 외부 제작 도구 연결 시 |
| `build.bat test` | 검증기와 API 회귀 테스트 실행 | 관리 도구 코드 변경 후 |
| `build.bat generate` | RCT 트레이너와 실제 게임용 AI 런타임 프로필 생성 | 정규화 콘텐츠 변경 후 |
| `build.bat pack-smoke` | Minecraft·NeoForge만 포함한 최소 CurseForge 임포트 ZIP 생성 | 패키징 파이프라인과 CurseForge 앱 임포트 확인 시 |
| `build.bat pack` | 일반 콘텐츠 검증 후 임시 개발 ZIP 생성 | 개발 자산 취합 흐름 확인 시 |
| `build.bat pack-server` | `server/both` 의존성과 서버 설정으로 NeoForge 서버 준비 ZIP 생성 | 전용 서버 배포 파일을 만들 때 |
| `build.bat pack-release` | 정식 의존성 Lock과 공개 패키징 준비 검사 | 배포 후보 생성 전 |

`validate`는 `draft` 의존성의 미확정 버전을 경고로 허용하지만,
`validate-pack`은 이를 오류로 처리한다. 따라서 Cobblemon 1.8 대상 버전이
확정되기 전에도 콘텐츠 개발은 진행할 수 있고 불완전한 CurseForge ZIP 생성은
차단된다. 각 명령의 상세 입출력과 종료 코드는
[`tools/content-manager/README.md`](../../tools/content-manager/README.md)에
기록한다.

`pack-smoke`는 정식 의존성 검증을 우회하는 일반 팩이 아니라 임포트 형식만
확인하는 별도 프로필이다. 외부 모드와 자체 JAR을 포함하지 않으며
`pack/profiles/import-smoke.json`에 임시 Minecraft·NeoForge 버전을 명시한다.
임시 `pack`은 `pack/profiles/development-placeholder.json`을 사용해 별도 개발
ZIP을 만들며, `pack-release`는 Lock이 확정될 때까지 ZIP 생성을 차단한다.

후속 단계에서 다음 명령을 같은 진입점에 추가한다.

```text
build.bat import
build.bat mods
build.bat all
```

검증 실패는 파일과 데이터 경로를 함께 출력하고, 오류가 하나라도 있으면 생성과
패키징을 중단한다.

## 6. 의존 모드 관리

의존성은 두 파일로 관리한다.

- `docs/MOD_DEPENDENCIES.md`: 사람이 읽는 선정 이유, 역할, 호환성, 라이선스
- `pack/dependencies.lock.json`: 빌드가 읽는 버전과 CurseForge 식별자

Markdown 표를 빌드 스크립트가 해석하지 않는다. Lock JSON이 기계 판독 가능한
단일 기준이며, 문서는 그 결정을 설명한다. Lock이 `draft`인 동안 일반 검증은
미확정 값을 허용하지만 CurseForge 패키징 검증은 실패해야 한다.

외부 모드는 CurseForge `manifest.json`의 project ID와 file ID로 참조한다.
CurseForge에 아직 등록하지 않은 자체 개발 JAR만 개발용 팩의
`overrides/mods`에 배치한다.

## 7. CurseForge ZIP

ZIP 최상위에는 중간 폴더 없이 다음 항목이 있어야 한다.

```text
manifest.json
icon.png                         런처 프로필 이미지 인식 테스트용
overrides/
|-- icon.png                    인스턴스에 복사할 수동 선택용 사본
|-- mods/
|-- config/
|-- defaultconfigs/
|-- resourcepacks/
`-- saves/Cobbleventure Test World/
```

팩 프로필의 아이콘 원본은 `pack/assets`에서 관리하며 400x400 이상의 정사각형
PNG를 사용한다. 사설 CurseForge ZIP은 manifest의 `image` 필드에 ZIP 최상위
기준 상대 경로 `icon.png`를 기록한다. 공개 모드팩의 ForgeCDN URL과 달리 별도
호스팅이 필요 없고 import 임시 폴더의 이미지가 프로필에 복사된다.
`overrides/icon.png`는 수동 선택용 사본이다.

패키징 전 최소 검증 항목은 다음과 같다.

- Minecraft와 NeoForge 버전이 고정되어 있는가
- 필수 외부 모드의 project ID와 file ID가 존재하는가
- 동일한 모드 ID나 CurseForge 파일이 중복되지 않았는가
- 로더·게임 버전·클라이언트/서버 설치 범위가 호환되는가
- 생성된 NPC, 대화와 RCT 트레이너 ID가 서로 연결되는가
- `ai` 선택 값이 Cobbleventure Battle AI 등록 값과 일치하는가
- 허가되지 않은 외부 JAR와 기존 Cobbleverse 전용 자산이 포함되지 않았는가
- ZIP 최상위 구조가 CurseForge 가져오기 형식과 일치하는가
- manifest의 `image`가 ZIP 최상위 `icon.png`를 정확히 가리키는가
- 팩 아이콘이 정사각형·최소 크기 조건을 만족하고 ZIP의 두 사본이 동일한가

현재 `build.bat pack-smoke`와 임시 `build.bat pack`이 위 구조 생성, ZIP CRC,
안전한 엔트리 경로와 manifest 일치 검사를 구현한다. 정식 개발 팩은 의존성
Lock이 확정된 뒤 같은 `pack` 진입점에 외부 모드와 자체 JAR 취합을 추가한다.
`pack-release`는 그 전까지 릴리스 ZIP을 만들지 않는다.

`pack-server`는 같은 Lock의 `side`를 기준으로 `server`와 `both` 의존성만
`server-manifest.json`에 기록한다. 개발 팩의 자체 JAR과 서버 설정은 ZIP에 직접
넣고, 클라이언트 전용 설정·셰이더·리소스팩은 제외한다. 외부 JAR와 NeoForge는
ZIP의 `setup-server.ps1`이 고정 ID와 버전으로 설치하며 EULA는 자동 동의하지 않는다.

## 8. 빌드 단계와 실패 정책

```text
environment check
-> import/normalize
-> schema and cross-reference validation
-> target exporters
-> Gradle builds
-> dependency lock validation
-> CurseForge staging
-> ZIP creation
-> archive validation and checksums
```

어느 단계든 실패하면 뒤 단계를 실행하지 않는다. 이전 성공 빌드와 실패한 새
빌드가 섞이지 않도록 staging 디렉터리는 빌드마다 새로 만든다. 배포 결과에는
빌드 커밋, 콘텐츠 스키마 버전, 의존성 Lock 해시와 자체 JAR 해시를 기록한다.

## 9. 구현 순서

1. 의존성 Lock, 정규화 콘텐츠 스키마와 교차 참조 검증
2. Excel 가져오기와 Web API 편집·검증 기능
3. RCT 트레이너 출력기 — AI 설정과 기본 팀·가방 변환 구현
4. Cobbleventure NPC·대화 출력기
5. Easy NPC 호환 출력기
6. NeoForge 어댑터와 RCT 전투 연결
7. Gradle 빌드 취합과 CurseForge ZIP 패키징
8. 테스트 월드, 회귀 테스트와 공개 배포 프로필

## 10. 변경 관리 원칙

- 콘텐츠 스키마를 바꾸면 `schema_version`과 마이그레이션을 함께 추가한다.
- 의존 모드 버전을 바꾸면 Lock, 의존성 문서와 호환성 테스트 결과를 갱신한다.
- 생성 파일을 직접 고치지 않고 원본 또는 출력기를 수정한다.
- 출력기마다 고정 입력·예상 출력 fixture를 두어 모드 업데이트 차이를 검출한다.
- 아직 확인하지 않은 외부 API 필드나 버전은 추측해 Lock하지 않는다.

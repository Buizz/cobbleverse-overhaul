# Cobbleventure CurseForge Pack Builder

CurseForge 프로필 ZIP을 결정론적으로 생성하고 다시 열어 구조를 검사하는 Python
도구다. Python 표준 라이브러리만 사용한다.

## 임포트 스모크 팩

저장소 루트에서 실행한다.

```bat
build.bat pack-smoke
```

출력:

```text
dist/cobbleventure-import-smoke-0.1.1-curseforge.zip
dist/cobbleventure-import-smoke-0.1.1-curseforge.zip.sha256
```

이 팩은 CurseForge 임포트 흐름만 확인하기 위해 Minecraft 1.21.1과 NeoForge를
선택하며 외부 모드와 Cobbleventure JAR을 포함하지 않는다. 스모크 프로필의
버전은 `pack/profiles/import-smoke.json`에서 별도로 관리하므로 정식
`dependencies.lock.json`을 확정한 것으로 취급하지 않는다.

## 임시 개발 팩

```bat
build.bat pack
```

출력:

```text
dist/cobbleventure-development-0.1.1-curseforge.zip
dist/cobbleventure-development-0.1.1-curseforge.zip.sha256
```

일반 콘텐츠 검증을 먼저 실행한 뒤 개발용 프로필을 패키징한다. 현재는 외부 모드와
자체 NeoForge JAR이 없어 스모크 팩과 실행 구성은 비슷하지만, 임포트 형식만
검사하는 스모크 팩과 이후 실제 개발 자산을 취합할 `pack` 진입점을 구분한다.

## 릴리스 팩 게이트

```bat
build.bat pack-release
```

현재 임시 구현은 `validate-pack`을 실행하여 Minecraft, NeoForge와 모든 활성
외부 모드의 버전·CurseForge 식별자가 고정됐는지 검사한다. Lock이 `draft`인
동안 종료 코드 `1`로 실패하며 릴리스 ZIP을 만들지 않는다. 개발 팩을 릴리스
결과로 복사하거나 이름만 바꾸는 동작은 하지 않는다.

## 독립 건축 팩

```bat
build.bat builder-world
```

메인 개발팩과 별개의 CurseForge ZIP을 만든다. Minecraft 1.21.1, NeoForge,
건축에 필요한 콘텐츠 제공 모드, WorldEdit와 자체 Structure Builder JAR만 포함하며
미리 생성한 `Cobbleventure Structure Builder` 평지 월드를 `overrides/saves`에 넣는다.

## 생성 ZIP

```text
manifest.json
icon.png
overrides/
├─ icon.png
├─ cobbleventure-pack-info.json
└─ config/<프로필 확인 파일>
```

프로필의 `icon` 경로는 400x400 이상의 정사각형 PNG여야 한다. 빌더는 같은
이미지를 ZIP 최상위와 `overrides`에 기록하고 두 파일의 내용까지 검사한다.
CurseForge 프로필 이미지는 `manifest.json`의 `image` 필드에 ZIP 최상위 기준
상대 경로인 `icon.png`로 기록한다. CurseForge import 서비스는 ZIP을 푼 임시
폴더와 이 값을 함께 이미지 경로 해석기에 전달한다. 공개된 CurseForge 모드팩은
ForgeCDN URL을 사용하기도 하지만, 사설 빌드에서는 ZIP에 포함된 상대 경로를
사용해야 별도 업로드 없이 재현할 수 있다. `overrides/icon.png`는 수동 선택용
사본이다.

빌더는 생성 직후 다음 항목을 검사한다.

- `manifest.json`과 `overrides/`가 ZIP 최상위에 있는가
- manifest의 `image`가 최상위 `icon.png`를 가리키는가
- 두 `icon.png`가 프로필 원본과 일치하고 최소 크기·정사각형 조건을 만족하는가
- ZIP 엔트리에 절대 경로, `..` 또는 Windows 구분자가 없는가
- Minecraft와 NeoForge 버전이 스모크 프로필과 일치하는가
- manifest 종류·버전·files·overrides 필드가 올바른가
- 최소 override 파일이 포함되었는가

## 직접 실행

```text
python tools/pack-builder/pack_builder.py build --root . --profile pack/profiles/import-smoke.json
python tools/pack-builder/pack_builder.py build --root . --profile pack/profiles/development-placeholder.json
python tools/pack-builder/pack_builder.py validate --root . --profile pack/profiles/import-smoke.json
```

`validate`는 기존 출력 ZIP만 검사한다. `build`는 임시 파일에 새 ZIP을 만든 뒤
검증을 통과한 경우에만 최종 출력과 SHA-256 파일을 교체한다.

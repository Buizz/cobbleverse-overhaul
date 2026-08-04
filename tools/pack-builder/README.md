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
dist/cobbleventure-import-smoke-0.1.0-curseforge.zip
dist/cobbleventure-import-smoke-0.1.0-curseforge.zip.sha256
```

이 팩은 CurseForge 임포트 흐름만 확인하기 위해 Minecraft 1.21.1과 NeoForge를
선택하며 외부 모드와 Cobbleventure JAR을 포함하지 않는다. 스모크 프로필의
버전은 `pack/profiles/import-smoke.json`에서 별도로 관리하므로 정식
`dependencies.lock.json`을 확정한 것으로 취급하지 않는다.

## 생성 ZIP

```text
manifest.json
overrides/
├─ cobbleventure-pack-info.json
└─ config/cobbleventure-import-smoke.txt
```

빌더는 생성 직후 다음 항목을 검사한다.

- `manifest.json`과 `overrides/`가 ZIP 최상위에 있는가
- ZIP 엔트리에 절대 경로, `..` 또는 Windows 구분자가 없는가
- Minecraft와 NeoForge 버전이 스모크 프로필과 일치하는가
- manifest 종류·버전·files·overrides 필드가 올바른가
- 최소 override 파일이 포함되었는가

## 직접 실행

```text
python tools/pack-builder/pack_builder.py build --root . --profile pack/profiles/import-smoke.json
python tools/pack-builder/pack_builder.py validate --root . --profile pack/profiles/import-smoke.json
```

`validate`는 기존 출력 ZIP만 검사한다. `build`는 임시 파일에 새 ZIP을 만든 뒤
검증을 통과한 경우에만 최종 출력과 SHA-256 파일을 교체한다.

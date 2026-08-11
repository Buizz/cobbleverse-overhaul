# Cobbleventure Structure Builder

메인 개발팩과 분리된 건축 NBT 편집용 NeoForge 모드다. `content/structures`의 원본을
독립 평지 월드에 배치하고, 편집된 부지를 월드의 `generated` 폴더에 Structure NBT로
저장한다.

## 패키지 생성

```bat
build.bat builder-world
```

생성된 `dist/cobbleventure-structure-builder-0.1.0-curseforge.zip`을 CurseForge에
임포트하고 `Cobbleventure Structure Builder` 월드를 연다.

## 명령

```mcfunction
/cobbleventure_builder status
/cobbleventure_builder tp <파일 이름 또는 리소스 ID>
/cobbleventure_builder save <파일 이름 또는 리소스 ID>
/cobbleventure_builder save all
/cobbleventure_builder load confirm
```

## 저장소로 가져오기

```bat
build.bat builder-import "<CurseForge 인스턴스>\saves\Cobbleventure Structure Builder"
```

상세 규격과 안전 규칙은
[독립 건축 구조물 제작 월드](../../docs/implementation/STRUCTURE_BUILDER_WORLD.md)를 따른다.

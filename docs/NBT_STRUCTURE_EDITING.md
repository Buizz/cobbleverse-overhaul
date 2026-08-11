# NBT 구조물 편집 가이드

Cobbleventure가 직접 관리하는 건축물 NBT의 원본 위치와 안전한 편집 절차를 정리합니다.
`src/generated/resources`는 빌드할 때 다시 만들어지는 출력물이므로 직접 수정하지 않습니다.

## 원본과 빌드 출력

| 구분 | 원본 위치 | 게임 리소스 ID | 빌드 출력 |
|---|---|---|---|
| 주택 원본 | `content/structures/houses/<골격>_<지붕형태>.nbt` | 빌드 시 색상별로 생성 | `data/cobbleventure/structure/houses/<골격>_<지붕형태>_<색상>.nbt` |
| 시설 | `content/structures/placeholder/<이름>.nbt` | `cobbleventure:placeholder/<이름>` | `data/cobbleventure/structure/placeholder/<이름>.nbt` |

빌더는 `content/structures`의 NBT를 우선 사용합니다. 시설은 그대로 복사하고, 주택은
9개의 원본에서 지붕 색상 10종을 치환해 기존 리소스 90종을 만듭니다. 등록된 원본이
없을 때만 Python으로 기본 구조물을 생성합니다. 따라서 실제 건물을 수정할 때는 반드시
`content/structures`의 파일을 변경하고, 생성된 출력물을 원본으로 취급하지 않습니다.

`placeholder`라는 이름은 현재 시설 배치 계약과의 호환성을 위해 유지한 리소스 경로입니다.
그 안의 파일은 임시 건물뿐 아니라 완성된 시설 원본으로도 사용할 수 있습니다.
등대·파워플랜트·멘션은 각각 `lighthouse.nbt`, `power_plant.nbt`, `mansion.nbt`로
관리합니다.

## 파일 이름 규칙

- 영문 소문자, 숫자, 밑줄만 사용합니다.
- 파일 이름과 리소스 ID의 마지막 부분을 동일하게 유지합니다.
- 주택 원본은 `<층수>_<지붕형태>.nbt` 형식을 사용합니다.
- 지원 지붕 형태는 `gable`(박공), `gambrel`(이중 경사), `shed`(외쪽 경사),
  `flat`(평지붕)입니다.
- 예: `one_story_gable.nbt` 한 개에서 `cobbleventure:houses/one_story_gable_red`,
  `cobbleventure:houses/one_story_gable_blue` 등의 리소스 10종이 생성됩니다.
- 주택 원본에서는 `minecraft:white_concrete`, `minecraft:white_wool`, `minecraft:cobblestone`을
  각각 지붕의 콘크리트·양털·석재 표식으로 사용합니다. 빌더가 팔레트의 이 블록들을
  색상별 재료로 바꾸므로 지붕 이외의 장소에는 사용하지 않습니다. 흰색 콘크리트는 필수이고,
  흰색 양털과 조약돌은 해당 재료를 섞고 싶을 때만 사용해도 됩니다.

지붕의 석재 표식은 다음 규칙으로 변환됩니다.

| 색상 계열 | 적용 색상 | 석재 |
|---|---|---|
| 붉은 계열 | 빨강, 주황, 갈색 | `minecraft:granite` |
| 어두운 계열 | 검정, 회색, 보라 | `minecraft:cobbled_deepslate` |
| 그 외 | 노랑, 초록, 파랑, 흰색 | `minecraft:cobblestone` |
- 기존 이름을 바꾸면 마을 생성 설정이 이전 리소스를 찾지 못할 수 있으므로 참조도 함께 수정합니다.

## 게임 안에서 편집하기

개발 모드팩과 편집용 평지 월드를 사용합니다. 대상 Minecraft·NeoForge·모드 버전은 실제
개발 팩과 같아야 합니다.

1. 저장소 루트에서 개발용 모드를 빌드합니다.

   ```bat
   build.bat mod-bootstrap
   ```

2. 게임을 완전히 재시작하고 치트가 허용된 편집 월드에 접속합니다. 데이터 팩의 NBT만
   바뀐 것이 아니므로 `/reload`만으로는 새 JAR가 반영되지 않습니다.
3. 구조물 블록을 받습니다.

   ```mcfunction
   /give @s minecraft:structure_block
   ```

4. 구조물 블록을 `LOAD` 모드로 놓고 편집할 리소스 ID를 입력해 불러옵니다.

   ```text
   cobbleventure:houses/one_story_gable_white
   cobbleventure:placeholder/laboratory
   ```

   명령으로 빠르게 확인하려면 다음처럼 배치할 수도 있습니다.

   ```mcfunction
   /place template cobbleventure:houses/one_story_gable_white
   ```

5. 원점과 크기를 기록한 뒤 건물을 수정합니다. 구조물의 정면 출입구는 현재 생성 규약상
   `Z=0` 쪽에 있으며, 최소 모서리 `(0, 0, 0)`을 기준으로 배치됩니다. 건물만 이동시키거나
   바닥 아래를 추가하면 실제 마을에서 어긋날 수 있습니다.
6. 구조물 블록을 `SAVE` 모드로 바꾸고 같은 리소스 ID, 원점, 크기를 사용해 저장합니다.
   몹이나 아이템 액자 같은 엔티티가 꼭 필요하지 않다면 `Include entities`는 끕니다.
7. 월드 폴더에서 저장된 파일을 찾습니다. 일반적인 위치는 다음과 같습니다.

   ```text
   <월드>/generated/cobbleventure/structures/houses/<이름>.nbt
   <월드>/generated/cobbleventure/structures/placeholder/<이름>.nbt
   ```

8. 시설은 저장된 NBT를 대응하는 `content/structures/placeholder` 파일에 덮어씁니다.
   주택은 반드시 흰색 리소스를 편집하고, 월드에 저장된 색상 접미사 파일을 원본 이름으로
   바꿔 복사합니다.

   ```text
   월드: generated/cobbleventure/structures/houses/one_story_gable_white.nbt
   원본: content/structures/houses/one_story_gable.nbt
   ```

   게임 월드에 남은 파일은 작업 사본일 뿐이며 Git에 포함할 원본은 `content` 쪽입니다.

구조물 블록의 편집 한계를 넘는 대형 시설은 Axiom·WorldEdit 같은 건축 도구로 작업할 수
있습니다. 다만 빌더가 받는 최종 파일은 GZip 압축된 바닐라 Structure NBT이고 루트 태그가
`TAG_Compound`여야 합니다. 사용한 도구가 다른 스키매틱 형식으로 저장한다면 그대로 넣지
말고 바닐라 Structure NBT로 변환하거나 게임에서 다시 저장합니다.

## 편집할 때 지킬 계약

- 원점, 외곽 크기, 정면 방향과 출입구 위치를 가능한 한 유지합니다.
- 구조물 범위를 벗어난 장식이나 지형은 저장되지 않으므로 크기 표시선을 확인합니다.
- 모드 블록을 썼다면 정식 모드팩에 항상 포함되는 블록인지 확인합니다. 누락된 블록 ID는
  월드 로딩 시 공기로 바뀌거나 구조물 배치를 실패하게 만들 수 있습니다.
- 상자 등 블록 엔티티를 복제할 때 시험용 아이템, 관리자 전용 데이터, 고정 UUID를 제거합니다.
- 자연 지형에 묻히는 바닥과 공기 블록의 포함 여부를 확인합니다. 공기 블록도 주변 블록을
  지울 수 있으므로 의도한 범위만 저장합니다.
- Pokémon, BCA 등 외부 프로젝트의 NBT는 라이선스나 제작자 허가가 확인되기 전에는 이
  디렉터리에 복사하지 않습니다. 참고해 직접 만든 구조물도 원본과 표현이 지나치게 같지
  않도록 설계와 디테일을 독자적으로 구성합니다.

## 검증 절차

NBT를 교체한 뒤 저장소 루트에서 다음을 실행합니다.

```bat
build.bat mod-bootstrap
```

빌더는 파일이 GZip NBT인지와 루트가 `TAG_Compound`인지 검사합니다. 성공하면 원본은 다음
출력으로 패키징됩니다.

```text
projects/cobbleventure-world-bootstrap/src/generated/resources/data/cobbleventure/structure/
```

마지막으로 게임을 재시작해 `/place template <리소스 ID>`로 방향, 바닥 높이, 블록 누락,
출입구를 확인합니다. 전체 개발 팩까지 검증할 때는 `build.bat pack`을 실행합니다.

## 문제 해결

- **빌드 후 수정이 사라짐**: `src/generated/resources`를 고친 경우입니다. 월드에서 다시
  저장하거나 남은 파일을 `content/structures`로 복사합니다.
- **GZip 구조물 오류**: `.schem`, Sponge schematic 또는 압축하지 않은 NBT를 확장자만
  바꾼 경우가 많습니다. 바닐라 구조물 블록으로 다시 저장합니다.
- **게임에서 구조물을 찾지 못함**: 네임스페이스가 `cobbleventure`인지, 폴더와 파일 이름이
  리소스 ID와 일치하는지 확인하고 게임을 완전히 재시작합니다.
- **배치 위치가 어긋남**: 저장 시 구조물 블록의 상대 위치, 크기와 기존 원점을 비교합니다.

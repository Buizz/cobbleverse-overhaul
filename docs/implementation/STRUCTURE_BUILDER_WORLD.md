# 독립 건축 구조물 제작 월드

> 적용 대상: `content/structures`의 Minecraft Structure NBT 원본
>
> 구현 프로젝트: `projects/cobbleventure-structure-builder`
>
> 상태: 1차 구현

## 1. 목적

메인 Cobbleventure 개발팩과 월드에서 완전히 분리된 CurseForge 프로필을 제공한다.
건축가는 별도의 평지 싱글플레이 월드에서 구조물을 수정하고, 게임 명령으로 각 부지를
Minecraft Structure NBT로 내보낸 뒤 저장소 원본에 반영한다.

```text
content/structures/*.nbt
  → build.bat builder-world
  → Cobbleventure Structure Builder CurseForge ZIP
  → 독립 평지 월드에서 편집
  → /cobbleventure_builder save all
  → 월드 generated 디렉터리에 NBT 생성
  → build.bat builder-import <월드 경로>
  → content/structures/*.nbt 갱신
```

메인 `cobbleventure-world-bootstrap` 모드와 세대 월드는 이 작업에 관여하지 않는다.

## 2. 건축용 CurseForge 프로필

프로필은 Minecraft 1.21.1과 NeoForge 21.1.248을 사용한다. NBT는 모드 JAR을 포함하지
않고 블록의 리소스 ID만 저장하므로, 해당 블록을 제공하는 모드는 건축 월드를 열 때
설치되어 있어야 한다.

현재 원본 28개는 모두 `minecraft` 블록만 사용하지만 앞으로 사용할 콘텐츠 블록을
보존하기 위해 다음 최소 모드 집합을 포함한다.

| 모드 | 포함 이유 |
|---|---|
| Cobblemon, Kotlin for Forge | 포켓몬 관련 블록과 필수 런타임 |
| Cobblemon Casino, Cloth Config | 카지노 시설 블록과 설정 의존성 |
| CobbleFurnies, Architectury API, Athena | 가구 블록과 모델 렌더링 |
| Radical Gyms & Structures | 체육관·리그 건축 리소스 |
| WorldEdit | 대형 건축 편집, 선택과 복사·붙여넣기 |
| Cobbleventure Structure Builder | 부지 배치, NBT 캡처와 내보내기 |

전투 UI, 포켓네비, 트레이너 AI, EasyNPC, TBCS, Mega Showdown, Paxi, 커스텀 스폰,
세대 월드 부트스트랩과 플레이어 메뉴는 넣지 않는다.

WorldEdit은 CurseForge 프로젝트 `225608`, Minecraft 1.21.1 NeoForge 파일
`5830452`를 사용한다.

## 3. 건축 월드 생성

저장소 루트에서 실행한다.

```bat
build.bat builder-world
```

명령은 다음 작업을 수행한다.

1. `content/structures`의 NBT와 크기를 검사한다.
2. 원본 NBT를 색상 치환 없이 독립 건축 모드 리소스로 복사한다.
3. 28개 구조물의 소스 경로, 리소스 ID, 크기와 SHA-256 카탈로그를 생성한다.
4. 크리에이티브·평화로움·정오 고정인 Y=64 평지 월드를 새로 만든다.
5. 자체 모드 JAR과 월드를 `pack/overrides/structure-builder`에 모은다.
6. CurseForge ZIP과 SHA-256을 생성하고 다시 열어 검증한다.

출력은 다음과 같다.

```text
dist/cobbleventure-structure-builder-0.1.0-curseforge.zip
dist/cobbleventure-structure-builder-0.1.0-curseforge.zip.sha256
```

CurseForge에서 ZIP을 새 프로필로 임포트한 뒤 월드 목록의
`Cobbleventure Structure Builder`를 연다. 월드는 다른 Cobbleventure 월드와 저장
데이터를 공유하지 않는다.

## 4. 자동 부지 배치

월드를 처음 열면 모든 콘텐츠 모드가 로드된 통합 서버에서 원본 NBT를 배치한다.
월드 생성용 헤드리스 서버에서는 NBT를 놓지 않으므로, 향후 원본에 모드 블록이 들어가도
바닐라 공기로 치환되지 않는다.

- 셀 크기: 80×80블록
- 보드: 8열×7행
- 구조물 셀: `(행 + 열) % 2 == 0`
- 구조물 수: 주택 12개 + 시설 16개
- 구조물 여백: 사방 최소 8블록
- 방향: 원본 회전 없이 로컬 `Z=0` 정면 유지
- 라벨: 파일 이름, 크기, 카테고리와 행·열 표시

건축물의 원점과 크기는 원본 NBT 계약으로 고정한다. 외형을 수정할 수 있지만 선언된
크기 밖의 블록은 내보내기에 포함되지 않는다. 크기를 변경하려면 카탈로그와 부지 계약을
별도로 변경해야 한다.

## 5. 게임 명령

명령은 독립 건축 모드 안에서만 등록되며 치트 권한 없이 사용할 수 있다.

```mcfunction
/cobbleventure_builder status
/cobbleventure_builder tp laboratory
/cobbleventure_builder save laboratory
/cobbleventure_builder save all
/cobbleventure_builder load confirm
```

| 명령 | 동작 |
|---|---|
| `status` | 구조물 수, 부지 생성 상태와 원본 카탈로그 변경 여부 확인 |
| `tp <이름>` | 해당 건축 부지 앞으로 이동 |
| `save <이름>` | 부지 하나를 NBT로 캡처 |
| `save all` | 28개 부지를 모두 NBT로 캡처 |
| `load confirm` | 편집 내용을 덮어쓰고 패키징된 원본 NBT를 모든 부지에 다시 배치 |

`load confirm`은 파괴적 명령이다. 저장하지 않은 편집 내용을 복구할 수 없으므로 원본을
다시 불러올 때만 사용한다.

## 6. NBT 내보내기와 저장소 반영

게임의 `save` 명령은 월드 내부에 GZip 압축된 바닐라 Structure NBT를 만든다.

```text
<월드>/generated/cobbleventure_builder/structures/export/houses/*.nbt
<월드>/generated/cobbleventure_builder/structures/export/placeholder/*.nbt
```

CurseForge 인스턴스의 월드 경로를 지정해 저장소 원본에 반영한다.

```bat
build.bat builder-import "C:\CurseForge\Minecraft\Instances\Cobbleventure Structure Builder\saves\Cobbleventure Structure Builder"
```

가져오기는 다음을 모두 검사한 뒤에만 파일을 교체한다.

- 현재 원본 28개에 대응하는 내보내기 파일이 모두 존재하는가?
- 모든 파일이 읽을 수 있는 Structure NBT인가?
- 내보낸 폭·높이·깊이가 원본 계약과 같은가?
- 누락이나 크기 불일치가 있을 때 원본을 하나도 덮어쓰지 않는가?

변경된 파일만 원자적으로 교체하며 Git이 복구 이력을 담당한다.

## 7. 모드 블록 취급

- NBT에는 `cobblemon:...`, 카지노와 가구 블록의 ID·상태·블록 엔티티 NBT가 저장된다.
- 모드 JAR은 NBT 안에 복사되지 않는다.
- 블록 제공 모드 없이 NBT를 불러오지 않는다.
- 건축 팩과 실제 배포팩의 모드 버전을 동일하게 유지한다.
- 모드를 제거하려면 먼저 모든 NBT 팔레트에서 해당 네임스페이스가 사라졌는지 검사한다.

## 8. 검수 기준

- [ ] `builder-world`가 독립 CurseForge ZIP과 평지 월드를 생성한다.
- [ ] 팩에 외부 모드 9개와 자체 JAR 1개만 포함된다.
- [ ] 처음 월드를 열 때 28개 구조물이 정확히 한 번 배치된다.
- [ ] 체크무늬 셀과 라벨이 모든 구조물에 일치한다.
- [ ] `save all` 후 내보내기 NBT 28개가 생성된다.
- [ ] `builder-import`가 완전한 내보내기만 저장소에 반영한다.
- [ ] 원본 크기 밖의 편집이 저장되지 않는다는 점을 건축가가 확인한다.
- [ ] Cobblemon·Casino·CobbleFurnies 블록을 시험 배치하고 재접속 후 유지되는지 확인한다.

## 9. 웹 Content Manager에서 실행

```bat
build.bat web
```

`http://127.0.0.1:8765`의 `빌드 및 검사` 화면에 독립 건축 월드 영역이 있다.

1. CurseForge에 임포트한 건축 프로필 폴더를 `인스턴스 경로`에 입력하고 저장한다.
2. `건축 팩 빌드`로 `builder-world`를 실행한다.
3. 게임에서 건축 후 `/cobbleventure_builder save all`을 실행한다.
4. 웹의 상태 새로고침에서 내보낸 NBT 수를 확인한다.
5. `게임 NBT 자동 가져오기`를 눌러 저장소 원본에 반영한다.

가져오기가 성공하면 웹은 NBT 카탈로그 캐시를 즉시 갱신하고 `NBT 건물` 화면으로
이동해 첫 구조물의 3D 모델을 자동으로 연다. NBT 뷰어에는 다음 31개만 표시한다.

- `content/structures`에서 관리하는 주택·시설 NBT 28개
- `bca:default/one_off/pokecenter`
- `bca:default/one_off/structure_pokemart`
- `bca:default/centers/center_department_store`

마을 생성기의 내부 크기 계산용 전체 구조물 카탈로그는 그대로 유지하되, NBT 뷰어
목록만 별도 API로 제한한다.

경로는 개인 PC 설정이므로 Git에 커밋되지 않는
`tools/content-manager/settings.local.json`에 저장한다. 서버는 브라우저 요청에 임의의
가져오기 경로를 받지 않고, 저장된 인스턴스의 고정된 월드 경로만 사용한다.

## 10. 관련 문서

- [NBT 구조물 편집 가이드](../NBT_STRUCTURE_EDITING.md)
- [마을 건축 리디자인 가이드](../town-redesign-guide.md)
- [콘텐츠 및 모드 의존성](../MOD_DEPENDENCIES.md)
- [Structure Builder 프로젝트 사용법](../../projects/cobbleventure-structure-builder/README.md)

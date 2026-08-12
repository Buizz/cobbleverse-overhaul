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

현재 관리 원본은 모두 `minecraft` 블록만 사용하지만 앞으로 사용할 콘텐츠 블록을
보존하기 위해 다음 최소 모드 집합을 포함한다.

| 모드 | 포함 이유 |
|---|---|
| Cobblemon, Kotlin for Forge | 포켓몬 관련 블록과 필수 런타임 |
| Cobblemon Casino, Cloth Config | 카지노 시설 블록과 설정 의존성 |
| CobbleFurnies, Architectury API, Athena | 가구 블록과 모델 렌더링 |
| 프로젝트에 가져온 체육관 NBT | `gyms/*.nbt` 외관을 체크무늬에서 직접 수정 |
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
3. 전체 관리 구조물의 소스 경로, 리소스 ID, 크기와 SHA-256 카탈로그를 생성한다.
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
- 보드: 8열, 구조물 수에 맞춰 행 자동 확장
- 구조물 셀: `(행 + 열) % 2 == 0`
- 구조물 수: 주택 12개 + 시설 17개(플레이어 집 포함)
- 구조물 여백: 사방 최소 8블록
- 방향: 원본 회전 없이 로컬 `Z=0` 정면 유지
- 라벨: 파일 이름, 크기, 카테고리와 행·열 표시

건축물의 원점과 크기는 원본 NBT 계약으로 고정한다. 외형을 수정할 수 있지만 선언된
크기 밖의 블록은 내보내기에 포함되지 않는다. 크기를 변경하려면 카탈로그와 부지 계약을
별도로 변경해야 한다.

플레이어 집은 `content/structures/placeholder/player_house.nbt`로 관리하며 건축 월드의
`player_house` 부지에서 다른 시설과 같은 방식으로 편집한다. 저장소에 가져오면 게임
리소스 `cobbleventure:placeholder/player_house`로 패키징된다. 웹 마을 편집기의 필수
시설 옵션에서 `플레이어 집`을 체크하면 실제 마을 배치와 도로 연결 대상에 포함된다.

### 4.1 출입구 편집 막대기

월드에 접속하면 이름이 `건축 출입구 편집 막대기`인 막대기를 한 번 지급한다. WorldEdit의
나무도끼와 충돌하지 않도록 별도 아이템 등록 대신 건축 월드 안의 모든 막대기를 편집
도구로 사용한다.

| 조작 | 동작 |
|---|---|
| 웅크리기 + 허공 우클릭 | entry, exit, teleport, spawn, npc, interaction, patrol, inspect 순환 |
| 대상 우클릭 | 현재 도구 모드 적용 |
| 웅크리기 + 대상 좌클릭 | 해당 위치의 출입구 또는 NPC 앵커 해제 |

위·아래 어느 문 블록을 클릭해도 아래쪽 문 좌표로 정규화한다. 클릭할 때 플레이어가 서
있는 쪽의 인접 칸을 안전한 도착 위치로 자동 계산하므로 별도의 spawn 앵커를 지정하지
않는다. 따라서 entry 모드의 외부 문은 건물 밖에서, exit 모드의 내부 문은 내부 공간
쪽에서 클릭해야 한다. teleport 모드는 같은 ID의 외부·내부 문 사이를 이동해 동선을
시험한다.

출입구는 월드 절대 좌표가 아니라 구조물 원점 기준 상대 좌표로 저장한다. NBT 자체에는
표시용 블록이나 엔티티를 넣지 않으며 `save`가 다음 메타데이터를 함께 내보낸다.

```text
<월드>/generated/cobbleventure_builder/structure_metadata/export/**/*.structure.json
```

메타데이터에는 문 좌표, 자동 도착 좌표, 문 방향, 안전 방향과 기본 입·퇴장 대화 ID가
들어간다. `builder-import`는 이를 원본 NBT 옆의 같은 이름 `.structure.json`으로
동기화하고, 다음 건축 월드를 생성할 때 다시 복원한다.

외부 건물에서 사용할 내부는 같은 메타데이터에 명시한다. 지붕 색상처럼 외부 리소스
이름에 변형 접미사가 붙어도 내부 ID는 바뀌지 않는다.

```json
{
  "interior_structure": "cobbleventure:interiors/one_story_shed"
}
```

메타데이터가 아직 없는 기본 주택은 데이터 모드 빌드가 정면 중앙 개구부를
`interior_entry`로 등록하고 위 공용 내부를 연결한다. 건축 월드에서 출입구를 직접
저장한 주택은 작성된 좌표와 명시적 내부 설정을 우선 사용한다.

가변 내부는 `/cobbleventure_builder interior create <id> <폭> <깊이> <층높이> <층수>`로
만든다. 폭·깊이 5~80, 층 높이 3~12, 1~8층과 전체 높이 80 이하를 지원한다. `save all`은
동적 내부도 `interiors/<id>.nbt`와 메타데이터로 함께 내보낸다. `/tool npc <라벨>`로
NPC 모드를 선택한 뒤 바닥을 클릭하면 라벨과 상대 위치만 같은 메타데이터에 저장한다.
EasyNPC 프리셋, 방향과 생성 정책은 실제 건물 배치 설정의 책임이다.
spawn 모드는 자동 도착점을 덮어쓰며 interaction과 patrol 모드는 오브젝트 상호작용 및
NPC 순찰 경로의 상대 좌표를 예약한다.

체육관 내부의 관장 위치는 `/cobbleventure_builder tool leader`로 바로 NPC 모드와
`leader` 라벨을 선택한 뒤 바닥을 클릭한다. 이 라벨은 체육관별 관장 EasyNPC의 실제
소환 위치로 사용되며 모듈 배치 좌표와 회전도 자동 반영된다.

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
| `save all` | 모든 관리 부지를 NBT로 캡처 |
| `load confirm` | 편집 내용을 덮어쓰고 패키징된 원본 NBT를 모든 부지에 다시 배치 |

`load confirm`은 파괴적 명령이다. 저장하지 않은 편집 내용을 복구할 수 없으므로 원본을
다시 불러올 때만 사용한다.

## 6. NBT 내보내기와 저장소 반영

게임의 `save` 명령은 월드 내부에 GZip 압축된 바닐라 Structure NBT를 만든다.

```text
<월드>/generated/cobbleventure_builder/structures/export/houses/*.nbt
<월드>/generated/cobbleventure_builder/structures/export/placeholder/*.nbt
<월드>/generated/cobbleventure_builder/structure_metadata/export/**/*.structure.json
```

CurseForge 인스턴스의 월드 경로를 지정해 저장소 원본에 반영한다.

```bat
build.bat builder-import "C:\CurseForge\Minecraft\Instances\Cobbleventure Structure Builder\saves\Cobbleventure Structure Builder"
```

가져오기는 다음을 모두 검사한 뒤에만 파일을 교체한다.

- 현재 모든 원본에 대응하는 내보내기 파일이 존재하는가?
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
- [ ] 처음 월드를 열 때 29개 구조물이 정확히 한 번 배치된다.
- [ ] 체크무늬 셀과 라벨이 모든 구조물에 일치한다.
- [ ] `save all` 후 내보내기 NBT 29개가 생성된다.
- [ ] `builder-import`가 완전한 내보내기만 저장소에 반영한다.
- [ ] 원본 크기 밖의 편집이 저장되지 않는다는 점을 건축가가 확인한다.
- [ ] 막대기로 지정한 입·퇴장문과 자동 도착 위치가 `.structure.json`으로 왕복된다.
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
이동해 첫 구조물의 3D 모델을 자동으로 연다. NBT 뷰어에는 다음 32개만 표시한다.

- `content/structures`에서 관리하는 주택·시설 NBT 29개
- `bca:default/one_off/pokecenter`
- `bca:default/one_off/structure_pokemart`
- `bca:default/centers/center_department_store`

마을 생성기의 내부 크기 계산용 전체 구조물 카탈로그는 그대로 유지하되, NBT 뷰어
목록만 별도 API로 제한한다.

경로는 개인 PC 설정이므로 Git에 커밋되지 않는
`tools/content-manager/settings.local.json`에 저장한다. 서버는 브라우저 요청에 임의의
가져오기 경로를 받지 않고, 저장된 인스턴스의 고정된 월드 경로만 사용한다.

## 10. 공용 내부공간과 공간 이동

내부공간은 특정 주택이나 체육관에 종속되지 않는 공용 NBT로 만든다.

```mcfunction
/cobbleventure_builder interior create large_arena 48 48 12 1
/cobbleventure_builder interior list
/cobbleventure_builder interior tp large_arena
/cobbleventure_builder interior save large_arena
/cobbleventure_builder interior delete large_arena confirm
```

`delete`는 에딧 월드에서 동적으로 만든 작업 공간만 제거한다. 저장소에 이미 등록된
NBT는 명령으로 삭제하지 않는다.

문과 도착 지점에는 물리적인 위치와 이름만 기록한다.

```mcfunction
/cobbleventure_builder tool door next_room
/cobbleventure_builder tool arrival entrance
```

첫 명령 후 실제 문을 우클릭하고, 두 번째 명령 후 플레이어가 도착할 바닥을
우클릭한다. 실제 목적지는 웹 `NBT 건물 설정`에서
`현재공간:next_room → 대상공간:entrance` 형태로 연결한다.

외부 공간도 조회하고 이동할 수 있다.

```mcfunction
/cobbleventure_builder exterior list
/cobbleventure_builder exterior tp base_gym
```

## 11. 관련 문서

- [NBT 구조물 편집 가이드](../NBT_STRUCTURE_EDITING.md)
- [마을 건축 리디자인 가이드](../town-redesign-guide.md)
- [콘텐츠 및 모드 의존성](../MOD_DEPENDENCIES.md)
- [Structure Builder 프로젝트 사용법](../../projects/cobbleventure-structure-builder/README.md)

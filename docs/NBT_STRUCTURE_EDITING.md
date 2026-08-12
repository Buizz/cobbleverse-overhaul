# NBT 구조물 편집 가이드

## 체육관: 마을은 종류 선택, 인원은 체육관에서 관리

체육관은 마을 JSON에 외관, 내부, NPC를 복제하지 않는다. `체육관 관리`에서 개수 제한
없이 체육관 정의를 만들고 관장과 기타 트레이너를 지정한다. 마을에서는 사용할 체육관
종류만 선택한다.

- 새 체육관은 공용 외관 `base_gym.nbt`와 공용 내부 `base_gym_interior.nbt`를
  재사용한다. 체육관마다 완성형 NBT를 복제하지 않는다.
- `gyms` 외관 템플릿은 건축 월드 체크무늬와 NBT 뷰어에 포함된다.
- 외관은 내부가 보이지 않는 봉인 껍데기로 수정한다.
- 관동 8개 체육관은 `content/structures/interiors/gyms/base_gym_interior.nbt`를 함께 참조한다.
- 웹의 외관 미리보기 버튼은 같은 NBT를 `NBT 건물 설정` 3D 뷰어에서 연다.
- 대형 리그 NBT는 체크 부지에 들어가지 않으므로 건축 체크무늬에서 제외된다.

세부 JSON과 인게임 흐름은
[체육관 외관·내부 모듈 통합 가이드](implementation/RADICAL_GYMS_INTEGRATION.md)를 따른다.

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

실제 월드에 외부 건물을 배치할 때는 NBT 로컬 `Y=0`의 공기를 배치하지 않는다.
따라서 건물 바닥의 공기 여백이 잔디·도로·기존 지형을 지우지 않는다. `Y=1` 이상의
공기는 작성한 건물 공간을 비우기 위해 기존처럼 정상 배치된다.

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

권장 방식은 메인 개발팩 대신 독립 건축 프로필을 사용하는 것이다.

```bat
build.bat builder-world
```

생성된 CurseForge ZIP을 임포트하고 `Cobbleventure Structure Builder` 월드에서
건축한 뒤 `/cobbleventure_builder save all`로 NBT를 내보낸다. 저장소 반영은
다음 명령을 사용한다.

```bat
build.bat builder-import "<CurseForge 인스턴스>\saves\Cobbleventure Structure Builder"
```

### 인게임 건축 편집 UI

건축 월드에 처음 접속하면 막대기를 지급한다. WorldEdit 나무도끼와
겹치지 않도록 이 월드에서는 막대기를 앵커 편집 도구로 사용한다. 명령어를
먼저 입력하거나 도구 모드를 순환할 필요는 없다.

| 조작 | 결과 |
|---|---|
| 막대기로 문 우클릭 | 문 이름을 입력하는 편집창 열기 |
| 막대기로 바닥·블록 우클릭 | NPC 위치, 관장 NPC(`leader`), 도착 위치 선택 |
| 웅크리기 + 대상 좌클릭 | 해당 위치의 문·NPC 앵커 제거 |
| `V` | 편집 도구 버튼 창 열기 |
| `G` | 모든 내부·외부 공간 목록을 열고 선택한 곳으로 이동 |
| `H` | 현재 동적 내부공간의 너비·깊이·층 높이·층수 편집 |

왼쪽 위 HUD에는 현재 공간의 표시 이름, key, 크기와 앵커 수가 항상
표시된다. 화면 아래의 단축키 안내를 보고 `V`를 누르면 마우스로 누를 수 있는
공간 이동·크기 변경 버튼이 나온다.

저장된 앵커는 월드 안에서도 바로 확인한다.

- 문: 금색 외곽선과 `DOOR · <이름>`
- NPC: 청록색 사람 형태 잔상과 `NPC · <라벨>`
- 도착 위치: 녹색 표식과 `ARRIVAL · <이름>`

각 NBT의 실제 X/Z 저장 범위는 원점 기준 한 칸 밖의 검정·노랑 체크무늬
지면 테두리로 표시된다. 테두리 안쪽만 NBT로 내보내며 테두리 블록은
저장되지 않는다. 96×96 셀 외곽선은 부지 구분용이고, 검정·노랑 테두리가
현재 NBT의 정확한 크기다. 동적 내부공간에도 같은 규칙을 적용한다.

기존 위치를 다시 우클릭하면 저장된 이름이 입력칸에 채워진다. 이름을
바꾸어 저장하거나 `위치 삭제`를 눌러 제거한다. 앵커는 표시용 블록이나
엔티티로 NBT에 저장되지 않고 `.structure.json`에만 기록된다.

#### 명령어 호환 기능

다음 기존 명령어는 반복 작업이나 문제 복구용으로 계속 제공한다. 일반 편집은
위 UI를 우선한다.

```mcfunction
/cobbleventure_builder tool mode entry
/cobbleventure_builder tool mode exit
/cobbleventure_builder tool mode teleport
/cobbleventure_builder tool mode spawn
/cobbleventure_builder tool mode npc
/cobbleventure_builder tool mode interaction
/cobbleventure_builder tool mode patrol
/cobbleventure_builder tool mode inspect
```

| 모드 | 클릭 대상 | 용도 |
|---|---|---|
| `entry` | 외부 건물의 문 | 내부로 들어갈 현관문 지정 |
| `exit` | 내부 공간의 문 | 외부로 나갈 현관문 지정 |
| `teleport` | 지정된 입·퇴장문 | 연결된 반대 공간으로 미리보기 이동 |
| `spawn` | 플레이어가 설 바닥 | 자동 도착 위치를 수동 위치로 덮어쓰기 |
| `npc` | NPC가 설 바닥 | 라벨이 붙은 NPC 위치를 블록 위 칸에 지정 |
| `interaction` | 문·카운터·장치 블록 | 스크립트가 사용할 상호작용 지점 지정 |
| `patrol` | NPC가 이동할 바닥 | `patrol_1`, `patrol_2` 순서로 순찰 지점 지정 |
| `inspect` | 구조물 내부 블록 | 크기, 출입문 수와 NPC 앵커 수 확인 |

문 위·아래 중 어디를 클릭해도 아래쪽 문 좌표로 정규화된다. `entry`는 반드시 건물
바깥쪽에 서서, `exit`는 내부 쪽에 서서 클릭한다. 플레이어가 서 있는 쪽의 인접 칸이
외부 또는 내부 도착 위치로 자동 계산되므로 spawn 지점을 별도로 입력할 필요가 없다.

### 가변 크기 내부 공간 만들기

내부 공간은 외부 NBT 크기와 독립적이다. 다음 명령의 순서는 `ID, 폭, 깊이, 층 높이,
층수`다.

```mcfunction
/cobbleventure_builder interior create player_house 32 32 5 2
```

위 예시는 `32×10×32`, 2층인 `player_house` 내부 작업실을 만들고 그곳으로 이동한다.

- 폭·깊이: 5~80블록
- 층 높이: 3~12블록
- 층수: 1~8층
- 전체 높이: 최대 80블록
- 실제 저장 높이: `층 높이 × 층수`

하늘색 테두리는 저장 영역 바깥에 만들어지므로 최종 NBT에는 들어가지 않는다. 각 층의
시작 높이를 표시할 뿐 바닥을 자동으로 채우지는 않는다. 계단, 천장 두께와 층간 구조는
표시선을 기준으로 직접 건축한다.

```mcfunction
/cobbleventure_builder interior list
/cobbleventure_builder interior tp player_house
/cobbleventure_builder interior save player_house
```

외부 건물의 파일명과 내부 ID가 같으면 자동으로 연결된다.

```text
외부: content/structures/placeholder/player_house.nbt
내부: content/structures/interiors/player_house.nbt
연결 ID: player_house
```

### 출입문 연결과 이동 시험

플레이어 집을 연결하는 전체 순서는 다음과 같다.

1. `/cobbleventure_builder tp player_house`로 외부 건물로 이동한다.
2. 막대기를 `entry` 모드로 바꾸고 집 밖에서 현관문을 우클릭한다.
3. `/cobbleventure_builder interior create player_house 32 32 5 2`를 실행한다.
4. 내부를 건축하고 내부 현관문을 설치한다.
5. 막대기를 `exit` 모드로 바꾸고 내부 쪽에 서서 문을 우클릭한다.
6. `teleport` 모드로 같은 문을 클릭해 외부로 나가는지 확인한다.
7. 외부 문에서도 클릭해 내부로 들어오는지 확인한다.

도착 문이 아직 지정되지 않았다면 내부 원점 근처의 안전한 기본 위치로 이동한다.
`teleport`는 건축 월드 미리보기 기능이다. 실제 게임에서는 이 메타데이터를 런타임이
읽고 EasyNPC 입장 대화와 인스턴스 내부 이동을 실행한다.

자동 도착 칸이 계단·가구와 겹치면 `spawn` 모드로 원하는 바닥을 클릭한다. 외부에서는
`exterior_spawn`, 내부에서는 `interior_spawn`으로 저장되며 teleport 모드도 이 수동
지점을 우선한다.

### NPC 위치와 라벨 지정

먼저 해당 위치를 식별할 라벨을 선택한다. 이 명령은 도구를 `npc` 모드로도 전환한다.
라벨은 NPC 종류가 아니라 건물 안에서의 역할 또는 자리 이름이다.

```mcfunction
/cobbleventure_builder tool npc resident
/cobbleventure_builder tool npc shop_clerk
/cobbleventure_builder tool npc receptionist
```

NPC가 설 바닥을 우클릭하면 바로 위 칸에 선택한 라벨로 위치가 기록된다. 같은 라벨을
다른 위치에 다시 찍으면 기존 위치가 새 위치로 교체된다. NPC 엔티티, 방향, EasyNPC
프리셋, UUID와 생성 정책은 NBT 단계에서 결정하지 않으며 다음 두 정보만 기록한다.

```json
{
  "label": "resident",
  "type": "npc_position",
  "position": [8, 1, 6]
}
```

마을이나 내부를 실제로 배치하는 설정에서 `resident` 라벨에 EasyNPC 프리셋, 방향,
대화, AI와 생성 정책을 연결한다. 따라서 동일한 내부 NBT를 사용하면서 마을·퀘스트 또는
인스턴스에 따라 다른 NPC를 놓을 수 있다.

상호작용 오브젝트와 순찰 경로도 같은 방식으로 예약할 수 있다. `interaction` 모드는
클릭한 블록 자체를, `patrol` 모드는 클릭한 바닥 위 칸을 기록한다. 순찰 순서는 생성된
번호를 따르며 모두 구조물 원점 기준 상대 좌표라 회전·대칭 배치에도 변환할 수 있다.

현재 서 있는 구조물의 모든 앵커 좌표를 확인하거나 입자로 표시할 수 있다.

```mcfunction
/cobbleventure_builder anchor list
/cobbleventure_builder anchor show
```

체육관 내부에서 관장 위치를 지정할 때는 전용 별칭을 사용할 수 있다.

```mcfunction
/cobbleventure_builder tool leader
```

명령 실행 후 관장이 설 블록의 바닥을 편집 막대기로 우클릭하면 `npc_position` 라벨
`leader`가 기록된다. 런타임은 이 좌표와 체육관 모듈의 배치·회전을 합산해 관장을
소환한다. 한 체육관 구성에는 `leader`가 정확히 하나 있어야 한다.

범용 내부공간의 문과 도착 지점에는 이름을 지정한다.

```mcfunction
/cobbleventure_builder tool door front_exit
/cobbleventure_builder tool arrival lobby_entrance
```

NBT에는 위치와 이름만 저장된다. 실제 이동 목적지는 웹 `NBT 건물 설정`의
`문 이동 설정`에서 선택한다. 같은 내부 NBT를 여러 건물에서 서로 다르게 연결할 수
있다.

### 내부 NBT와 앵커 가져오기

기존 외부와 새 내부를 모두 저장하려면 다음 명령을 사용한다.

```mcfunction
/cobbleventure_builder save all
```

새 내부 NBT는 다음 위치에 생성된다.

```text
<월드>/generated/cobbleventure_builder/structures/export/interiors/player_house.nbt
<월드>/generated/cobbleventure_builder/structure_metadata/export/interiors/player_house.structure.json
```

`builder-import` 또는 웹의 `게임 NBT 자동 가져오기`는 기존 NBT 전체를 먼저 검증한 뒤
새 내부를 다음 원본 위치에 추가한다.

```text
content/structures/interiors/player_house.nbt
content/structures/interiors/player_house.structure.json
```

메타데이터에는 내부 크기·층 계약, 문 상대 좌표, 자동 도착 위치와 NPC 라벨·위치가
들어간다. NBT와 선언 크기가 다르거나 잘못된 앵커 좌표·라벨이 있으면 어떤 원본도
덮어쓰지 않는다. 다음 `builder-world` 생성 시 이 정보도 다시 건축 월드에 복원된다.

### 웹에서 건물 NPC 배정하기

`build.bat web`으로 Content Studio를 열고 `NBT 건물 설정` 탭으로 이동한다. 별도의
건물 설정 목록을 만들지 않고, 이 화면이 관리 NBT와 `.structure.json`의
`npc_position`을 함께 읽어 건물 자체의 전역 NPC 설정을 편집한다.

- 왼쪽: 관리 건물 검색, 종류 필터와 NPC 라벨 배정 현황. 일반 건물·주택·체육관
  외관 템플릿·체육관 내부 모듈·일반 내부 모듈·리그·임시 구조물로 나뉜다.
- 가운데: 실제 NBT 3D 모델과 NPC 라벨 위치 마커
- 오른쪽: 선택한 NBT 건물의 라벨별 전역 NPC 콘텐츠 선택과 건물 종류 설정

미리보기에서 노란 마커는 아직 고정 NPC가 배정되지 않은 위치, 초록 마커는 배정된
위치다. 마커 위에 건축 월드에서 지정한 라벨이 표시된다. 건물을 회전하거나 확대해
카운터·문·가구와 위치가 겹치지 않는지 확인한다.

일반 시설의 라벨에는 `content/source`에서 관리하는 NPC 콘텐츠를 선택한다. 이 배정은
마을별 설정이 아니라 해당 NBT 건물이 어디에 배치되든 적용되는 전역 설정이다. 저장
결과는 다음 파일에 기록된다.

```text
content/catalogs/building-settings.json
```

```json
{
  "schema_version": 1,
  "buildings": {
    "cobbleventure:placeholder/laboratory": {
      "fixed_npcs": {
        "receptionist": "cobbleventure:npc/laboratory_receptionist"
      },
      "citizen_placement_allowed": false
    }
  }
}
```

각 NBT의 `시민을 수용할 수 있는 건물`은 확률 설정이 아니라 건물의 전역 용도 플래그다.
활성화된 건물은 고정 NPC를 배정하지 않고, NBT의 `npc_position`들을 시민이 들어갈 수
있는 빈 자리로 제공한다. 기본 주택은 활성화되고 연구소·상점 같은 시설은 비활성화된다.
필요하면 경로와 관계없이 NBT별로 바꿀 수 있다.

실제 시민 수와 선택 규칙은 건물이 아니라 마을 설정이 소유한다.

```text
마을 전체 시민 수 + 시민 NPC 풀
        ↓
배치된 건물 중 citizen_placement_allowed=true인 건물 수집
        ↓
모든 건물의 빈 npc_position에 목표 인원만큼 분산 배치
```

예를 들어 마을의 목표 시민 수가 8명이고 시민 수용 건물이 5채라면, 마을 생성기가 5채의
빈 라벨을 모은 뒤 총 8명만 골고루 분산한다. 특정 집마다 시민 수나 확률을 설정하지 않는다.
마을별 목표 인원과 NPC 풀 편집 UI는 후속 단계다.

### 실제 게임 월드에 적용되는 흐름

`build.bat mod-bootstrap`을 실행하면 다음 항목이 월드 부트스트랩 데이터 모드에 함께
패키징된다.

```text
content/catalogs/building-settings.json
  → data/cobbleventure/building_settings.json

content/structures/**/*.structure.json
  → data/cobbleventure/structure_metadata/**/*.structure.json

content/structures/interiors/*.nbt
  → data/cobbleventure/structure/interiors/*.nbt
```

주택은 실제 마을에서 지붕 색상 접미사가 붙은 NBT를 사용하므로, 원본 주택
`.structure.json`이 모든 색상 변형 리소스에 자동 복제된다. 따라서 색마다 NPC 위치를
다시 지정할 필요가 없다.

게임에서는 구조물 배치가 성공한 직후 다음 순서로 처리된다.

1. 구조물 원점과 회전을 기준으로 모든 앵커의 상대 좌표를 월드 좌표로 변환한다.
2. `건물 설정`에서 라벨에 배정한 NPC를 EasyNPC 프리셋으로 생성한다.
3. `interior_entry`가 있으면 같은 파일명의 `cobbleventure:interiors/<이름>` NBT를 전용
   `cobbleventure:building_interiors` 차원에 인스턴스로 한 번만 생성한다.
4. 외부 문은 내부 `interior_spawn`으로, 내부 `interior_exit`은 외부 문의
   `safe_spawn`으로 연결한다.
5. 서버 재시작 시 이미 생성된 마을의 위치를 다시 계산해 문 연결을 복구한다. NPC와 내부
   생성 기록은 월드 SavedData에 남기므로 중복 생성하지 않는다.

문 블록을 우클릭하면 현재는 예약된 대화 ID를 사용하는 건물 문 런타임이 이동을 처리하고
입장·퇴장 안내를 표시한다. EasyNPC의 실제 선택형 대화 내용은 문 앵커의 `dialogue` ID에
대응하는 대화 데이터가 추가되는 시점에 교체할 수 있으며, 좌표나 NBT를 다시 만들 필요는
없다.

고정 NPC는 `cobbleventure:npc/<id>`를 다음 EasyNPC 프리셋으로 변환해 생성한다.

```text
easy_npc:preset/encounter/<id>.npc.snbt
```

건물의 `citizen_placement_allowed`는 런타임까지 전달되지만, 마을별 목표 시민 수와 NPC
풀이 아직 없으므로 현재는 랜덤 시민을 생성하지 않는다. 빈 마을 설정에서 임의 NPC가
생기지 않는 것이 의도된 동작이다.

전체 독립 월드 절차는 [독립 건축 구조물 제작 월드](implementation/STRUCTURE_BUILDER_WORLD.md)를
참고한다. 아래 구조물 블록 절차는 개별 파일을 수동 편집할 때 사용하는 대안이다.

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
- **건축 월드 접속 시 `Invalid player data`와 `Index ... out of bounds`**: 오래된 건축
  모드에서 체크무늬의 마지막 빈 칸을 실제 NBT로 읽던 오류다. 최신 `builder-world` ZIP을
  다시 가져오거나 인스턴스의 `cobbleventure-structure-builder` JAR를 최신 빌드로
  교체한다. 기존 건축 월드를 계속 사용해도 되며 NBT 작업물을 삭제할 필요는 없다.
- **Cobblemon `.old File ... is corrupt or missing` 경고**: 위 레이아웃 예외와 별개의
  플레이어 데이터 복구 경고다. 실제 접속을 차단한 원인은 뒤이어 기록된 건축 모드의 배열
  예외다. 이미 유실됐다고 기록된 Cobblemon 파티·도감 데이터가 필요하면 해당 월드의
  별도 백업에서 복원해야 한다.

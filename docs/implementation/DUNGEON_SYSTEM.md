# 데이터 기반 던전 생성 및 도전 시스템

> 관련 기능: F-05, F-07, F-09, F-15, F-16
>
> 관련 결정: 던전은 기존 건물 NBT 입장과 분리된 `정의 → 생성 계획 → 실행 세션` 기능으로 구현한다.
>
> 설계 상태: 핵심 실행·웹 편집·NBT 조각 프로토타입 구현 / 고급 생성 규칙과 콘텐츠 제작 진행 중

## 0. 현재 결정사항 요약

지금까지 합의한 던전 시스템의 기준은 다음과 같다.

- 던전은 기존 건물 NBT 내부와 다른 `DungeonRun` 단위의 도전 콘텐츠다. 입장할 때 전용 슬롯을 준비하고, 기본적으로 나가거나 실패하면 내부 상태를 초기화한다.
- 입구는 일반 건물 내부 연결, 동굴·숲의 의미 마커, 일반 지형의 NBT 오브젝트, 마을의 예약 부지·기존 건물에 모두 둘 수 있다. 트리거가 작동하면 바로 이동하지 않고 던전 안내와 입장 확인을 먼저 표시한다.
- 전역 파티 시스템은 만들지 않는다. 다인 던전은 같은 입구에서 입장을 누른 사람끼리 자동 매칭하며 `solo`, 강제 협력형 `cooperative`, 같은 슬롯에서 따로 행동하는 `independent`를 지원한다.
- 지형은 완성된 세트 전체를 쓰는 `fixed_template`, NBT 조각 조립, 기존 Minecraft 동굴 생성 로직, 두 방식을 섞는 혼합형을 선택할 수 있다. 작은 발전소처럼 세트 전체를 설계하는 방식도 정식 던전 유형으로 유지한다.
- 같은 결의 시설 테마는 방의 역할·연결구·마커 계약을 공유하고 블록과 장식을 바꾸는 스킨처럼 제작한다. 별도 동선이나 특수 기믹이 필요하면 같은 역할의 변형 조각 또는 새 조각 타입을 추가한다.
- NBT 안에는 좌표를 직접 적는 대신 조우, 보상, 치료소, 게이트, 체크포인트, 시작·종료 지점 등의 의미 마커를 둔다. 생성기는 방 역할과 마커 조건에 맞는 후보를 골라 실행별로 배치한다.
- 경로 생성은 고정 배치, 주 통로와 곁가지, 미로, 방과 통로 방식을 지원한다. 현재 NBT 생성기는 연결구 기반의 제한된 무작위 탐색으로 시작→보스→출구 주 경로를 먼저 보장한 뒤 곁가지와 가능한 루프를 붙인다.
- NBT 방은 같은 역할의 여러 세트, 잠금·스위치·함정 같은 기믹 변형, 층간 계단·엘리베이터 변형을 가질 수 있다. 제작 도구에서는 방 그래프와 2D 평면을 층별로 보고 시드 재생성·고정·수정이 가능해야 한다.
- NPC 조우는 기존의 고정 배틀 프리셋 방식과 포켓몬 풀 기반 즉석 생성 방식을 함께 지원한다. 즉석 방식은 내부 레벨 범위에 맞춰 팀과 시작·종료 대사를 실행마다 결정한다.
- 야생 포켓몬은 일반 숲·동굴과 같은 스폰 프로필·레벨·가중치 규칙을 사용한다. 전투 도망·포획·아이템, 치료소, 전리품, 실패 시 일반 드롭 유지, 체크포인트, 반복 클리어와 보상 정책은 던전별 옵션이다.
- 전설 포켓몬은 반드시 직접 등장할 필요가 없다. 보스룸 직접 조우, 흔적 조사와 후속 퀘스트, 클리어 보상 중 콘텐츠 목적에 맞는 방식을 선택한다.
- 테스트 콘텐츠는 모두 레벨 1로 두고 발전소, 카지노 지하기지, 실프주식회사, 포켓몬 타워, 돌산터널 전설 던전으로 고정형·수평 다층·수직 상승·협력·독립행동 등 서로 다른 기능을 검증한다.

## 1. 목적

던전은 로켓단 기지 같은 빌런 시설, 야생 포켓몬이 출현하는 자연 동굴, 전설의 포켓몬이나 그 흔적이 기다리는 연출 지역을 하나의 데이터 기반 기능으로 제작하기 위한 시스템이다.

콘텐츠 제작자는 다음 항목을 코드 수정 없이 선택하고 미리 볼 수 있어야 한다.

- NBT 조각 조립, 절차 동굴, 고정 지역 중 지형 생성 방식을 선택한다.
- 완전 미로, 목적지 보장 경로와 곁가지, 고정 배치 중 길의 형태를 선택한다.
- 프리셋을 적용한 뒤 던전별 값만 덮어쓴다.
- 일반 적, 보스, 야생 조우, 치료소, 보상과 클리어 방을 배치 규칙으로 지정한다.
- 싱글 던전과 다인 던전의 입장·전투·실패 규칙을 지정한다.
- 생성 결과를 평면도와 실제 공간 미리보기로 확인하고 시드를 고정하거나 다시 생성한다.

## 2. 기존 건물과의 경계

| 구분 | 기존 건물 NBT 입장 | 던전 |
|------|--------------------|------|
| 주 용도 | 상점, 집, 고정 시설 내부 | 반복 도전, 탐험, 전투, 보스와 보상 |
| 공간 | 영구 슬롯 또는 고정 내부 | 실행마다 준비되는 임시 슬롯 |
| 생성 | 완성된 NBT 한 개 중심 | 고정 NBT, NBT 조각, 절차 지형 또는 혼합 |
| 상태 | 장기 유지 가능 | `DungeonRun` 단위로 격리 |
| 이탈 | 같은 내부로 재입장 가능 | 기본적으로 도전 종료 후 초기화 |
| NPC | 고정 시설 NPC | 실행 시 규칙에 따라 자동 배치 |
| 보상 | 상점·대화·퀘스트 중심 | 조우·목표·클리어 결과 중심 |

월드에 놓이는 동굴 입구, 엘리베이터, 폐건물 문, 균열이나 차원문은 던전 자체가 아니라 `dungeon_id`를 가리키는 진입 오브젝트다. 실제 던전은 [공용 인스턴스 차원](INSTANCED_SPACES.md)의 슬롯 할당과 안전한 귀환 절차를 재사용하되, 건물 내부와 다른 수명 주기 및 초기화 규칙을 가진다.

달맞이산처럼 모든 플레이어가 공유하는 장기 유지형 지역은 [던전 및 지하 차원](DUNGEON_DIMENSIONS.md)의 영역이다. 이 문서의 `DungeonRun`은 도전마다 격리·초기화되는 인스턴스형 던전을 우선 대상으로 한다.

### 2.1 공통 던전 진입점

던전 입구의 외형과 배치 장소는 달라도 입장 판정은 `DungeonEntrance` 하나로 통일한다.

```text
DungeonEntrance
  ├─ 건물 연결 그래프가 참조
  ├─ 동굴·숲 내부 장소 배치기가 참조
  ├─ 지상 오브젝트가 배치한 NBT의 연결 앵커가 참조
  └─ 마을 생성기가 예약한 시설·랜드마크 NBT가 참조
          ↓
    같은 조건 검사·다인 매칭·던전 생성·귀환 처리
```

`DungeonEntrance`는 다음 공통 정보를 가진다.

- 전역에서 유일한 `entrance_id`와 목적지 `dungeon_id`
- 던전 내부의 시작 지점 `destination_entry`
- 상호작용, 문 통과, 포탈 진입 또는 범위 진입 중 활성화 방식
- 발견 전 숨김, 항상 표시, 조건 충족 후 표시와 같은 가시성
- [공용 플레이어 조건](PLAYER_CONDITIONS.md), 잠김 메시지와 입장 연출
- 싱글 즉시 준비 또는 다인 입구 대기열을 여는 입장 흐름
- 입장 위치로 귀환, 지정 안전 지점 귀환 또는 클리어 전용 출구 정책

입구 외형 NBT, 회전, 지형 접합과 정확한 좌표는 `DungeonEntrance`에 넣지 않는다. 이를 실제 월드에 놓는 건물·장소·오브젝트 데이터가 소유하고 `entrance_id`만 참조한다. 같은 던전에 여러 입구를 만들 수 있지만 각 입구 ID는 실제 배치 한 곳에만 연결한다.

입구의 공통 조건은 던전 선행 진행·클리어 자격처럼 어느 위치에서 들어가도 같아야 하는 조건에 사용한다. 건물 연결이나 월드 오브젝트가 가진 지역 한정 조건은 추가 조건으로 허용하며, 공통 조건과 배치 조건을 모두 만족해야 입장할 수 있다. 같은 조건을 양쪽에 복제하지 않는다.

### 2.2 배치 방식 1: NBT 건물 내부

기존 건물의 `interior.connections`는 `외부 문 → 내부 모듈 문`뿐 아니라 `건물 모듈 문 → 던전 진입점`을 목적지로 선택할 수 있게 확장한다.

```json
{
  "from": "basement:sealed_door",
  "target": {
    "type": "dungeon",
    "entrance_id": "cobbleventure:entrance/rocket_hideout_backroom"
  }
}
```

- 건물 NBT에는 평소처럼 `sealed_door` 앵커만 둔다.
- 공간 연결 관계 편집기에서 목적지 종류를 `건물 모듈` 또는 `던전`으로 선택한다.
- 던전을 선택하면 사용 가능한 `DungeonEntrance` 목록과 입장 조건을 표시한다.
- 건물 내부가 스토리 단계에 따라 바뀌어도 현재 단계의 앵커가 존재하면 같은 입구를 연결할 수 있다.
- 던전에서 나오면 건물 외부가 아니라 진입했던 내부 문 앞 안전 앵커로 돌아온다.

건물 안에서 다시 던전에 들어가므로 귀환점 하나를 덮어쓰면 안 된다. 런타임은 `월드 → 건물 내부 → 던전`을 `ReturnFrame` 스택으로 저장하고 한 단계씩 되돌아간다. 초기 버전은 건물 내부에서 던전까지의 중첩만 허용하고 던전 안에서 다른 던전을 여는 연결은 금지한다.

### 2.3 배치 방식 2: 동굴·숲 내부 장소

기존 동굴·숲의 `entrances`는 해당 동굴·숲 자체의 출입구이므로 중첩 던전 입구에 재사용하지 않는다. 대신 두 생성기에 공통 `embedded_sites` 단계를 추가한다.

```json
{
  "id": "hidden_shrine_site",
  "type": "dungeon_entrance",
  "entrance_id": "cobbleventure:entrance/forest_hidden_shrine",
  "placement": {
    "mode": "rule_based",
    "surface": "clearing_edge",
    "route_role": "side_branch",
    "distance_from_main_entrance": [96, 240],
    "structure_pool": "cobbleventure:dungeon_entrances/forest_shrine"
  }
}
```

배치 모드는 다음을 지원한다.

| `placement.mode` | 동작 |
|------------------|------|
| `fixed_anchor` | 제작된 동굴·숲의 지정 앵커에 배치 |
| `marker` | NBT 조각 안의 `dungeon_entrance` 의미 마커를 교체 |
| `rule_based` | 생성된 지형에서 조건을 만족하는 후보를 찾아 배치 |

동굴 후보는 이동 가능한 바닥, 충분한 벽 두께·천장 높이, 입구 구조물 공간과 주 경로 연결성을 검사한다. 용암·깊은 물·낙하 구간, 기존 랜드마크, 동굴 자체 출입구와 너무 가까운 곳은 제외한다. 숲 후보는 공터 가장자리, 길의 곁가지, 언덕 사면 또는 대형 나무 주변처럼 구조물이 지형과 자연스럽게 접하는 장소를 사용하며 길과 수관을 막지 않아야 한다.

후보 선택은 동굴·숲 시드에 대해 결정적이어야 하며, 한 번 게시되거나 월드에 생성된 입구가 서버 재시작마다 이동하면 안 된다. 적합한 후보를 찾지 못하면 필수 입구는 해당 지역 생성을 실패시키고, 선택 입구는 생략하되 진단 경고를 남긴다.

### 2.4 배치 방식 3: 일반 지형의 월드맵 오브젝트

일반 지형 위에서는 [월드맵 오브젝트](WORLD_GATE_OBJECTS.md)가 특정 NBT를 배치하는 기존 흐름을 그대로 사용한다. 오브젝트 자체를 `dungeon_entrance`라는 특수 타입으로 만들지 않는다. NBT에 문·포탈·사다리 같은 연결 앵커를 넣고, 오브젝트의 공간 연결 관계에서 그 앵커의 목적지를 `DungeonEntrance`로 지정한다.

```json
{
  "id": "rocket_warehouse",
  "type": "structure",
  "anchor": { "q": 3, "r": -1 },
  "resource": "cobbleventure:villain_base/rocket_warehouse",
  "rotation": 2,
  "properties": {
    "terrain_fit": "foundation"
  },
  "connections": [
    {
      "from": "structure:dungeon_door",
      "target": {
        "type": "dungeon",
        "entrance_id": "cobbleventure:entrance/rocket_warehouse"
      }
    }
  ]
}
```

`structure`는 던전 기능을 모르는 일반 NBT 배치 오브젝트다. `villain_base`, `legendary_site` 같은 기존 예약 오브젝트도 동일하게 NBT 앵커와 `connections`만 추가하면 된다. 작은 동굴문, 폐건물, 사당과 빌런 기지는 외형과 규모만 다를 뿐 모두 같은 방식으로 연결한다.

연결 정보는 원본 NBT 블록 데이터가 아니라 오브젝트 인스턴스 데이터가 소유한다. 따라서 같은 사당 NBT를 여러 위치에 재사용하면서 각각 다른 던전이나 다른 입구로 연결할 수 있다. NBT 회전에 따라 연결 앵커와 안전 귀환 방향도 함께 회전해야 한다.

### 2.5 배치 방식 4: 마을 내부

마을 내부에도 던전 입구를 배치할 수 있다. 다만 초기화되는 던전 전체를 마을 월드 아래나 건물 사이에 직접 생성하지 않는다. 마을에는 외부 건물·폐허·맨홀·비밀문 같은 입구 NBT와 필요한 경우 짧은 현관만 놓고, 실제 던전은 `DungeonRun`의 인스턴스 슬롯에 생성한다.

마을 생성기는 주택을 무작위로 채운 뒤 남는 곳에 입구를 끼워 넣지 않는다. 주 도로 골격과 필수 시설을 배치한 다음, 일반 주택·장식 부지를 채우기 전에 `dungeon_site`를 예약한다.

```text
마을 경계·주 도로 결정
  → 포켓몬센터·상점·체육관 등 필수 시설 예약
  → 필수 스토리 던전·랜드마크 부지 예약
  → 선택 던전 부지 배치
  → 주택·장식 부지 채우기
  → 도로 접근·충돌·입구 연결 검증
```

배치 방식은 다음 세 가지를 지원한다.

| `settlement_site.mode` | 용도 | 예시 |
|------------------------|------|------|
| `existing_building` | 이미 배치될 건물의 내부 문·지하문을 던전에 연결 | 카지노 비밀 계단, 회사 지하 연구소 |
| `reserved_lot` | 던전 입구 전용 건물·폐허 NBT 부지를 먼저 예약 | 로켓단 창고, 폐저택, 도시 사당 |
| `micro_site` | 건물 한 채보다 작은 NBT를 골목·광장 가장자리 등에 배치 | 맨홀, 하수도 문, 균열, 비밀 엘리베이터 |

`existing_building`은 2.2의 건물 내부 연결을 그대로 사용한다. `reserved_lot`과 `micro_site`는 마을 생성기가 NBT를 배치한 뒤 그 NBT의 연결 앵커를 `DungeonEntrance`로 잇는다.

```json
{
  "id": "rocket_warehouse_site",
  "type": "dungeon_site",
  "placement": {
    "mode": "reserved_lot",
    "required": true,
    "districts": ["industrial", "backstreet"],
    "minimum_lot_size": [24, 20],
    "road_access": "required",
    "resource": "cobbleventure:villain_base/rocket_warehouse",
    "rotation": "face_road"
  },
  "connection": {
    "from": "structure:dungeon_door",
    "target": {
      "type": "dungeon",
      "entrance_id": "cobbleventure:entrance/rocket_warehouse"
    }
  }
}
```

마을 입구 부지는 다음 조건을 검사한다.

- 마을 경계와 지형 안전 기반층 안에 완전히 포함되는가?
- 건물·체육관·필수 시설·벽·도로와 충돌하지 않는가?
- 입구 앞 최소 보행 공간과 도로 또는 골목 연결이 있는가?
- NBT 회전 후 문·안전 귀환 앵커가 막히지 않는가?
- `micro_site`가 도로 통행, NPC 동선이나 광장 이벤트 구역을 막지 않는가?
- 같은 `entrance_id`가 다른 건물이나 부지에 중복 연결되지 않았는가?

스토리상 반드시 필요한 던전은 마을 ID와 부지를 명시하거나 `required: true`로 둔다. 1차 부지가 실패하면 지정한 대체 부지·소형 템플릿을 시도하고, 모두 실패하면 마을 생성을 `FAILED`로 처리한다. 선택 던전은 마을 태그·구역·세대 조건에 따라 시드 기반으로 뽑을 수 있으며, 후보가 없으면 경고 후 생략할 수 있다.

이미 생성된 마을에는 새 던전 부지를 자동으로 끼워 넣지 않는다. 새 월드 생성, 비어 있는 예약 부지 사용 또는 관리자가 충돌을 확인한 마이그레이션으로만 추가한다. 공유 마을의 외형은 플레이어마다 다르게 바꾸지 않고, 잠금·발견·클리어 상태는 입구 상호작용과 안내 화면으로 개인별 표현한다.

### 2.6 입구 안내 화면

문 통과, 포탈 접촉 또는 이동 감지 트리거는 플레이어를 즉시 이동시키지 않고 `DungeonGuide` 화면을 연다. 트리거 경계를 넘어가려던 플레이어는 입구 앞 안전 지점에 유지하고, 화면을 닫으면 아무 변화 없이 월드에 남는다.

안내 화면은 설정에 따라 다음 정보를 표시한다.

- 던전 이름·짧은 소개·테마 이미지 또는 미확인 장소 표기
- 권장 레벨과 실제 입장 제한 여부
- 싱글, 협력형 또는 독립행동형과 최소·최대 인원
- 싱글·더블·2인 협동 전투 형식
- 도망·포획·아이템·외부 탈출 제한
- 완전 초기화·체크포인트·실행 유지 방식과 실패 시 전리품 처리
- 반복 클리어·재입장·보상 수령 가능 여부
- 예상 규모·시간과 공개가 허용된 보상 정보
- 충족하지 못한 입장 조건과 참가자별 자격

`info_mode`는 `exact`면 이름과 규칙을 모두 공개하고, `summary`면 플레이에 필요한 규칙만 공개하며, `mystery`면 정체·보스·보상을 숨기고 위험도와 입장 규칙만 표시한다. 어떤 모드에서도 아이템 제한, 초기화, 다인 입장 강제와 같이 플레이어 선택에 영향을 주는 규칙은 숨기지 않는다.

하단 행동은 던전 설정에 따라 `혼자 입장`, `2인 입장`, `입장`, `취소`를 제공한다. 싱글 던전은 입장 버튼을 누른 뒤 바로 준비를 시작하고, 다인 던전은 같은 입구의 대기열에 등록한다. 먼저 누른 플레이어에게 `다른 도전자를 기다리는 중입니다`를 표시하고, 다른 플레이어가 같은 입구에서 입장을 누르면 자동 매칭한다. 안내 화면을 여는 것만으로 대기 요청, 슬롯이나 `DungeonRun`을 만들지 않는다.

이동 트리거 위에 계속 서 있는 동안 화면이 매 틱 다시 열리지 않게 한다. 기본적으로 플레이어가 트리거 영역을 완전히 벗어난 뒤 다시 들어와야 안내를 재표시한다.

### 2.7 공통 입장 흐름과 안전 규칙

```text
입구 활성화
  → DungeonGuide 안내 화면 표시
  → 플레이어가 싱글 또는 다인 입장 선택
  → 입구·던전·배치 참조 유효성 검사
  → 플레이어 조건·레벨·반복 클리어 자격 검사
  → 싱글 확인 또는 DungeonEntryQueue 자동 매칭 완료
  → 현재 위치와 상위 공간을 ReturnFrame에 저장
  → DungeonPlan 선택·생성 및 슬롯 준비
  → 준비 완료 후에만 참가자 이동
```

- 입구를 밟았다는 이유만으로 빈 슬롯에 먼저 이동시키지 않는다.
- 매칭된 요청은 중복 활성화를 잠그고 다른 `DungeonMatch` 실행과 분리한다.
- 정확한 출발 위치와 함께 배치가 제공한 안전 앵커를 저장한다.
- 건물·동굴·숲·월드 오브젝트가 제거되거나 버전이 바뀌어 원래 위치로 돌아갈 수 없으면 상위 공간의 검증된 대체 앵커를 사용한다.
- 던전 완료·실패·관리자 복구 모두 같은 귀환 스택을 사용한다.
- 입구가 잠겨 있거나 던전 생성에 실패하면 플레이어를 이동시키지 않고 이유를 표시한다.
- 하나의 입구가 가리키는 던전·내부 시작점이 없거나 두 배치가 같은 입구 ID를 사용하면 게시를 막는다.

## 3. 핵심 모델

던전 기능은 다음 일곱 계층으로 나눈다.

| 계층 | 역할 |
|------|------|
| `DungeonEntrance` | 배치 장소와 무관한 입장 조건, 목적 던전, 대기열과 귀환 정책 |
| `DungeonPreset` | 로켓단 기지, 자연 동굴처럼 여러 던전이 공유하는 기본값 |
| `DungeonDefinition` | 생성, 조우, 목표, 보상과 다인 규칙을 가진 콘텐츠 원본 |
| `DungeonPiece` | 연결구와 의미 마커가 포함된 NBT 방·통로 조각 |
| `DungeonPlan` | 시드로 생성되어 미리보기와 검증을 통과한 방 그래프·배치 계획 |
| `DungeonEntryRequest`·`DungeonMatch` | 입구별 대기 요청과 자동으로 묶인 실행 참가자 명단 |
| `DungeonRun` | 참가자, 슬롯, 진행 상태, 처치·보상 기록을 가진 실제 도전 |

프리셋과 던전 정의를 곧바로 월드에 적용하지 않는다. 먼저 결정적 시드로 `DungeonPlan`을 만들고, 연결 가능성·충돌·필수 방·NPC 수용량 검증을 통과한 계획만 실제 슬롯에 생성한다. 같은 데이터 버전과 시드는 같은 계획을 만들어야 한다.

### 3.1 한눈에 보는 전체 가용 옵션

아래 표를 제작 도구의 옵션 카탈로그와 스키마 체크리스트로 사용한다. `기본값`은 도전형 인스턴스 던전의 권고값이며 프리셋에서 변경할 수 있다.

| 분류 | 옵션 키 | 가용 값 또는 범위 | 기본값 |
|------|---------|-------------------|--------|
| 정체성 | `preset` | 자연·빌런·유적·전설·다인 프리셋 ID | 필수 선택 |
| 정체성 | `environment_profile` | 숲, 동굴, 해저, 화산, 시설 등 환경 프로필 ID | 프리셋 상속 |
| 입구 | `entrances[].entrance_id` | 전역 고유 ID, 한 배치만 참조 | 하나 이상 |
| 입구 | `entrances[].activation` | `interact`, `cross`, `portal`, `proximity` | `interact` |
| 입구 | `entrances[].visibility` | `always`, `discovered`, `conditioned`, `hidden` | `always` |
| 입구 | `entrances[].destination_entry` | 던전 내부 시작 앵커 | `main` |
| 입구 | `entrances[].return_policy` | 진입 위치, 배치 안전 앵커, 지정 출구 | 진입 위치 |
| 입구 안내 | `entry_ui.info_mode` | `exact`, `summary`, `mystery` | `summary` |
| 입구 안내 | `entry_ui.shown_fields` | 권장 레벨, 인원, 규칙, 초기화, 예상 시간·보상 등의 표시 여부 | 프리셋 상속 |
| 입구 안내 | `entry_ui.confirm_required` | 안내 후 입장 버튼을 눌러야 이동하는지 | `true` |
| 입구 안내 | `entry_ui.reopen_policy` | 닫은 뒤 경계를 벗어나야 재표시 또는 재표시 지연 | `leave_trigger_area` |
| 입구 배치 | `placement.type` | `building_connection`, `embedded_site`, `world_object_connection`, `settlement_site` | 배치 데이터에서 선택 |
| 마을 배치 | `settlement_site.mode` | `existing_building`, `reserved_lot`, `micro_site` | `reserved_lot` |
| 마을 배치 | `settlement_site.assignment` | 특정 마을 ID 또는 마을·구역 태그 후보 | 스토리는 특정 마을 |
| 마을 배치 | `settlement_site.required` | 배치 실패 시 마을 생성 실패 또는 선택적 생략 | 스토리 던전 `true` |
| 장소 배치 | `placement.mode` | `fixed_anchor`, `marker`, `rule_based` | 지역 종류에 따름 |
| 장소 배치 | `placement.required` | 후보가 없을 때 생성 실패 또는 선택적 생략 | `true` |
| 진입 | `eligibility.minimum_party_size` / `maximum_party_size` | 입장 가능한 포켓몬 파티 크기, 각각 1~6 | 1 / 6 |
| 진입 | `eligibility.require_usable_pokemon` | 기절하지 않은 포켓몬이 한 마리 이상 필요한지 | `true` |
| 진입 | `eligibility.level_measure` | 권장 레벨 비교 기준 `average`, `highest` | `average` |
| 진입 | `eligibility.recommended_level_policy` | `ignore`, `warn`, `enforce` | `warn` |
| 생성 시점 | `plan.mode` | `authored`, `runtime`, `authored_pool` | `runtime` |
| 생성 시점 | `plan.seed_policy` | `fixed`, `random_per_run`, `daily`, `weekly`, `match`, `player` | `random_per_run` |
| 생성 시점 | `plan.fallback` | `reject_entry`, `use_last_valid`, `use_fallback_plan` | `reject_entry` |
| 생성 시점 | `plan.generation_timeout_ms` | 런타임 계획 1회 생성 제한 시간 | 부하 테스트 후 결정 |
| 생성 시점 | `plan.max_attempts` | 유효 계획 재생성 횟수 | 부하 테스트 후 결정 |
| 공간 | `instance.scope` | `player`, `party`, `shared` | `party` |
| 공간 | `instance.bounds` | 가로·높이·세로 최대 크기 | 프리셋 상속 |
| 공간 | `instance.protection` | 탈출·워프, PvP 허용 여부. 블록 설치·파괴는 전역 월드 보호를 재사용 | 모두 제한 |
| 지형 | `terrain.mode` | `fixed_template`, `nbt_pieces`, `procedural_cave`, `hybrid` | 프리셋 상속 |
| 지형 | `terrain.cave_generator` | 현재 월드와 같은 Minecraft 동굴 생성기 | `minecraft_worldgen` |
| 경로 | `layout.mode` | `fixed`, `critical_path_branches`, `maze`, `rooms_and_corridors` | `critical_path_branches` |
| 경로 | `layout.size` | 방 수, 층수, 주 경로 길이, 전체 경계 | 프리셋 상속 |
| 경로 | `layout.randomness` | 가지·루프·막다른 길·합류 확률, 반복 제한 | 프리셋 상속 |
| 난이도 표시 | `difficulty.recommended_level` | UI에 표시할 권장 최소·최대 레벨 | 필수 |
| 내부 레벨 | `difficulty.level_mode` | `fixed`, `range`, `scale_to_party`, `scale_with_floor` | `range` |
| 내부 레벨 | `difficulty.trainer_levels` | 트레이너 최소·최대, 층·진행도 보정 | 필수 |
| 내부 레벨 | `difficulty.wild_levels` | 야생 최소·최대, 방·구역 보정 | 환경 프로필 상속 |
| 야생 | `wild.profile` | 기존 숲·동굴과 같은 야생 스폰 프로필 ID | 선택 |
| 야생 | `wild.budget` | 동시 개체 수, 방당 수, 재스폰, 희귀도 보정 | 프리셋 상속 |
| 야생 | `wild.encounter_mode` | 월드 스폰, 마커 스폰, 이벤트 조우, 혼합 | `marker_spawn` |
| NPC | `encounters.hostile_pool` | 세력·역할·등급별 트레이너 풀 | 선택 |
| NPC | `encounters.placement` | 필수 수, 선택 수, 주 경로·곁가지 비율, 순찰 | 자동 배치 |
| NPC | `encounters[].opponents` / `trainer_generation` | 고정 배틀 프리셋 또는 포켓몬 풀 기반 즉석 팀 생성 중 하나 | 고정 프리셋 |
| NPC 생성 | `trainer_generation.pokemon_pool` | 종과 선택 가중치 목록 | 즉석 생성 시 필수 |
| NPC 생성 | `trainer_generation.team_size` | 1~6마리의 최소·최대 팀 크기 | 즉석 생성 시 필수 |
| NPC 생성 | `trainer_generation.allow_duplicates` | 한 팀 안에서 같은 종의 중복 허용 여부 | `false` |
| NPC 대사 | `battle_start_lines` / `battle_end_lines` | 전투 시작·종료 시 무작위로 한 줄 선택할 목록 | 각각 한 줄 이상 |
| 보스 | `boss` | NPC·팀·AI·방·연출·전투 예외 | 선택 |
| 전투 전역 | `battle.field_format` | `singles`, `doubles` | `singles` |
| 전투 전역 | `battle.allow_flee` | 전투 도망 허용 여부 | `false` |
| 전투 전역 | `battle.allow_capture` | 야생 포획 허용 여부 | `true` |
| 전투 전역 | `battle.allow_items` | 가방 아이템 사용 허용 여부와 포켓별 제한 | `true` |
| 전투 전역 | `battle.allow_party_edit` | PC·파티 교체·외부 회복 허용 여부 | `false` |
| 전투 전역 | `battle.allow_escape_actions` | 귀환 아이템·명령·임의 워프 허용 여부 | `false` |
| 다인 | `multiplayer.mode` | `solo`, `cooperative`, `independent` | `solo` |
| 다인 | `multiplayer.size` | 최소·최대 인원, 중도 합류 허용 여부 | 프리셋 상속 |
| 입장 매칭 | `match.required_players` | 자동 매칭에 필요한 정확한 인원 | 초기 버전 2 |
| 입장 매칭 | `match.scope` | `same_entrance`, `same_dungeon` | `same_entrance` |
| 입장 매칭 | `match.timeout_seconds` | 최대 대기 시간 | 300초 |
| 입장 매칭 | `match.on_timeout` | `cancel`, `keep_waiting`, `allow_solo` | `cancel` |
| 협력 | `multiplayer.battle_join` | `summon_all`, `require_nearby`, `initiator_only` | `summon_all` |
| 협력 | `multiplayer.tether` | 경고 거리, 최대 거리, 강제 복귀·전투 이동 정책 | 활성화 |
| 독립행동 | `multiplayer.progress` | 목표·문·보스 상태의 개인·공유 범위 | 공유 |
| 독립행동 | `multiplayer.boss_join_policy` | `force_gather`, `ask_members`, `require_all_present`, `initiator_only` | `require_all_present` |
| 지원 | `support.healing_stations` | 수, 배치 구역, 회복 범위, 사용 횟수 | 선택·실행당 1회 |
| 지원 | `support.checkpoints` | 없음, 재시작 지점, 재접속 지점 | 없음 |
| 전리품 | `loot.tables` | 상자·방·구역별 전리품 테이블 ID와 가중치 | 선택 |
| 전리품 | `loot.ownership` | `per_player`, `run_shared`, `first_claim` | `per_player` |
| 전리품 | `loot.on_failure` | `keep_collected`, `remove_run_loot`, `grant_on_clear_only` | `keep_collected` |
| 목표 | `objectives` | 도달, 처치, 수집, 조사, 퍼즐, 생존, 포획 | 필수 목표 모두 완료 |
| 클리어 | `completion.repeat_mode` | `once`, `repeatable`, `cooldown`, `limited` | `repeatable` |
| 클리어 | `completion.limit` | 계정·캐릭터별 횟수와 일·주·시즌 초기화 | 제한 없음 |
| 클리어 | `completion.reentry_after_clear` | `deny`, `explore_only`, `new_run` | 반복 정책에 따름 |
| 보상 | `rewards.repeat_policy` | 최초 전용, 매회, 쿨다운, 횟수별 감쇠 | 최초+반복 테이블 분리 |
| 실패 | `lifecycle.on_exit` | 초기화, 제한 시간 유지, 체크포인트 유지 | `reset_run` |
| 실패 | `lifecycle.on_wipe` | 초기화, 체크포인트, 퇴장 | `reset_run` |
| 재개 | `lifecycle.resume_mode` | `full_reset`, `checkpoint`, `keep_until_timeout` | `full_reset` |
| 실패 | `lifecycle.reconnect_grace` | 재접속 유예 시간 | 120초 |
| 제작 | `preview.mode` | 웹 그래프·2D 평면, 서버 임시 공간 플레이 미리보기, 통계 일괄 생성 | 모두 제공 |
| 게시 | `publish.validation` | 오류 차단, 경고 허용, 데이터·조각 버전 고정 | 오류 시 게시 금지 |

## 4. 생성 옵션

### 4.1 지형 생성 방식

지형 방식과 경로 방식은 서로 독립된 옵션이다. 따라서 `NBT 조각 + 미로`, `NBT 조각 + 주 경로/곁가지`, `절차 동굴 + 주 경로/곁가지`를 모두 표현할 수 있다.

| `terrain.mode` | 설명 | 주 용도 |
|----------------|------|---------|
| `fixed_template` | 완성된 NBT 지역 하나를 그대로 사용 | 스토리 연출, 전설 조우, 정교한 보스방 |
| `nbt_pieces` | 연결구가 있는 방·통로 NBT를 그래프에 맞춰 조립 | 로켓단 기지, 연구소, 유적 |
| `procedural_cave` | 경로 골격 주변을 굴착·노이즈 규칙으로 형성 | 자연 동굴, 광산, 야생 서식지 |
| `hybrid` | 절차 지형 안에 NBT 랜드마크·방을 고정 삽입 | 자연 동굴 속 사당, 유적과 보스방 |

`procedural_cave`의 동굴 형태는 현재 일반 월드 동굴과 같은 Minecraft 월드 생성 로직을 사용한다. 별도의 슬롯 전용 굴착 알고리즘을 새로 만들지 않는다. 다만 일반 동굴 결과만으로 목적지 도달을 보장할 수 없으므로 생성 결과를 검사한 뒤 입구·목표 사이의 필수 연결을 보정하고 NBT 랜드마크를 합성한다. 입구, 출구, 보스방과 필수 랜드마크는 무작위 동굴 결과에 맡기지 않는다.

### 4.2 경로 형태

| `layout.mode` | 보장 사항 | 조절할 값 |
|---------------|-----------|-----------|
| `fixed` | 제작자가 지정한 방과 연결을 그대로 사용 | 방 좌표, 회전, 연결 |
| `critical_path_branches` | 시작점에서 목적지까지 주 경로가 항상 존재하고 곁가지가 붙음 | 주 경로 길이, 가지 수·깊이, 합류 확률 |
| `maze` | 높은 무작위성과 막다른 길을 가지되 목적지는 도달 가능 | 크기, 루프율, 막다른 길 비율, 최단 거리 |
| `rooms_and_corridors` | 방 예산과 통로 규칙으로 시설형 평면을 생성 | 방 수, 통로 길이, 구역별 방 풀 |

공통 제한 옵션은 최소·최대 방 수, 전체 경계, 층수, 수직 이동량, 반복 조각 제한, 루프 수, 시작-목표 최소 거리, 난이도 상승 곡선이다.

### 4.3 NBT 조각 계약

각 조각은 블록을 담은 NBT와 `content/dungeon_pieces/**/*.json` 메타데이터를
한 쌍으로 가진다. JSON은 `dungeon-piece.schema.json`을 사용하며 서버 시작 시
모두 읽어 ID 중복, 범위와 연결 방향을 검증한다.

- `piece_id`, `structure`, `size`, `weight`, `allow_rotation`
- `role`: `start`, `room`, `corridor`, `junction`, `dead_end`, `support`,
  `treasure`, `boss`, `exit`
- `tags`: 테마·구역·사용 제약을 표현하는 리소스 ID 집합
- `connectors`: 조각 경계에 있는 `id`, `position`, 수평 `facing`, `socket`, `tags`
- `markers`: `entry`, `exit`, `encounter`, `boss`, `loot`, `healing_station`,
  `gate`, `checkpoint`, `wild_spawn`, `objective`, `trace`
- `reference`: 던전 정의의 조우·상자·목표처럼 마커가 채울 구체 항목의 선택 ID

`start`, `boss`, `exit` 역할은 각각 정확히 하나의 `entry`, `boss`, `exit`
마커를 가져야 한다. 모든 조각은 하나 이상의 연결구를 가지며 연결구 좌표는
바라보는 방향의 실제 조각 경계에 있어야 한다. `socket`이 같고 양쪽 연결 태그가
호환되는 연결구만 조립 후보가 된다. 조각당 최소·최대 사용 횟수, 인접 금지 조합,
파괴·보호 영역은 계획 생성기 단계에서 이 기본 포맷을 확장한다.

연결되지 않은 출입구는 막음 조각으로 닫는다. 문이나 계단 앞, 연결구 내부와 플레이어 시작 안전 반경에는 NPC나 보상을 놓지 않는다.

### 4.4 던전 계획 생성 시점

지형을 어떻게 만드는지와 던전 계획을 언제 확정하는지도 서로 독립된 옵션이다.

| `plan.mode` | 동작 | 적합한 콘텐츠 |
|-------------|------|----------------|
| `authored` | 웹 제작 도구에서 시드를 선택·수정·검증한 `DungeonPlan` 하나를 게시 | 스토리 던전, 정교한 로켓단 기지, 전설 연출 |
| `authored_pool` | 웹에서 미리 검증한 여러 계획을 게시하고 입장 시 하나를 추첨 | 품질을 통제하면서 반복감을 줄이는 던전 |
| `runtime` | 인게임 입장 준비 중 설정과 시드로 새 계획을 자동 생성·검증 | 로그라이크, 반복 동굴, 주간 도전 |

`runtime`은 `fixed`, 매 실행 무작위, 일간·주간 공통, 매칭별 또는 플레이어별 시드 정책을 선택할 수 있다. 제한 시간 안에 유효한 계획을 만들지 못하면 입장을 거부하거나, 마지막 검증 계획 또는 지정한 안전 계획으로 대체한다. 실패한 계획을 그대로 생성해서는 안 된다.

`generation_timeout_ms`와 `max_attempts`는 지금 고정하지 않는다. 각 프리셋의 평균·상위 99% 생성 시간, 실패율과 동시 입장 부하를 프로토타입에서 측정한 뒤 결정하고 운영 지표에 따라 조정한다. 안전 대체 계획은 프리셋마다 최소 하나를 게시할 수 있다.

웹에서 만든 계획도 실제 블록 NBT 전체를 저장할 필요는 없다. 데이터·조각 버전, 시드, 방 그래프, 조각 배치와 마커 결정을 저장하고 서버가 동일한 결과를 재구성한다. 제작자가 방을 직접 이동하거나 교체했다면 수정된 계획 자체를 버전 관리한다.

## 5. 프리셋과 값 병합

프리셋은 완성된 던전이 아니라 서로 잘 어울리는 기본 옵션 묶음이다. 던전 정의는 필요한 값만 덮어쓴다.

```text
시스템 기본값
  → 테마 프리셋
  → 던전 정의
  → 난이도 프리셋
  → 다인 규칙
  → 실행 시드
```

배열은 항목별로 `replace` 또는 `append` 병합 방식을 명시하고, 알 수 없는 키와 서로 충돌하는 강제 규칙은 로딩 오류로 처리한다.

예상 지형·테마 프리셋은 다음 카탈로그로 관리한다. 프리셋은 초기 구현 대상을 뜻하지 않으며, 같은 생성기 위에 팔레트·조각 풀·스폰·NPC와 규칙을 묶는 단위다.

| 분류 | 프리셋 | 예상 지형과 콘텐츠 |
|------|--------|--------------------|
| 자연 | `natural_cave` | 일반 동굴, 좁은 통로와 큰 공동, 지하수, 광물, 동굴 야생 스폰 |
| 자연 | `forest_grove` | 숲길, 덤불 미로, 공터, 나무 다리, 숲 야생 스폰 |
| 자연 | `abandoned_mine` | 갱도, 레일, 수직 갱도, 붕괴 구간, 광물·기계실 |
| 자연 | `frozen_cave` | 얼음 통로, 미끄럼 퍼즐, 빙하 공동, 얼음 야생 스폰 |
| 자연 | `volcanic_cave` | 용암, 열기 구역, 현무암 통로, 불꽃 야생 스폰 |
| 자연 | `coastal_underwater` | 침수 통로, 수중·해안 동굴, 조류, 물 야생 스폰 |
| 유적 | `ancient_ruins` | NBT와 동굴 혼합, 함정, 퍼즐, 흔적 수집, 제단 |
| 유적 | `legendary_shrine` | 고정 랜드마크, 의식 공간, 전설의 흔적 또는 직접 조우 |
| 빌런 | `team_rocket_facility` | 사무실·창고·연구실·감방·경비 구역, 조무래기와 간부 |
| 빌런 | `team_aqua_base` | 해안·수중 기지, 부두, 침수 구역, 수문과 워프 패널 |
| 빌런 | `team_magma_base` | 화산 기지, 용암 설비, 지열 발전실, 고저차 통로 |
| 빌런 | `team_galactic_hq` | 금속 연구시설, 에너지 장치, 워프·잠금 퍼즐 |
| 빌런 | `team_plasma_base` | 성채·격납고·연구 구역, 선전 공간과 감금 구역 |
| 빌런 | `team_flare_laboratory` | 화려한 연구소, 비밀 엘리베이터, 병기·전력 시설 |
| 빌런 | `team_skull_hideout` | 점거된 도시 건물, 골목·옥상, 낙서와 임시 장벽 |
| 빌런 | `aether_facility` | 청정 연구시설, 보존 구역, 실험실과 울트라홀 연출 |
| 빌런 | `team_star_base` | 야외 바리케이드, 대규모 조무래기 구간, 보스 무대 |
| 도전 | `two_player_challenge` | 다른 테마 위에 병합하는 2인 규칙, 협력 또는 독립행동 모드 |

로켓단 건물은 `team_rocket_facility`에 방 풀, 경비 배치, 조명, 장식, 일반 적 등급 곡선을 넣고, 개별 기지는 보스·목표·특수 방만 바꾸는 방식으로 제작한다.

## 6. NPC와 조우 자동 배치

콘텐츠 제작자는 NPC의 정확한 좌표를 모두 지정하지 않아도 된다. 조각의 의미 마커와 방 태그를 기준으로 `EncounterDirector`가 실행 시 배치한다.

### 6.1 야생 포켓몬

던전의 야생 포켓몬은 별도의 종 목록을 매번 복사하지 않고 [포켓몬 스폰 프로필](POKEMON_SPAWNS.md)과 같은 환경 프로필을 참조한다. 따라서 일반 필드의 숲·동굴처럼 종, 출현 가중치, 시간·날씨·높이·블록·구역 조건과 레벨 범위를 지정할 수 있다.

- `wild.profile`로 `forest`, `cave`, `underwater` 같은 기존 프로필 또는 던전 전용 프로필을 선택한다.
- 던전 정의가 종·가중치·폼·희귀도·레벨만 추가하거나 덮어쓸 수 있다.
- `world_spawn`은 유효 구역 안에서 자연스럽게 스폰하고, `marker_spawn`은 제작자가 둔 마커 주변에만 생성한다.
- `event_encounter`는 상자, 흔적, 함정 또는 방 진입 이벤트가 정해진 조우를 시작한다.
- 동시 개체 수, 방당 예산, 처치·포획 후 재스폰 여부와 시간을 실행 단위로 관리한다.
- 야생 포획 허용 여부와 도망 가능 여부는 던전 전역 전투 규칙을 따른다.

고정 NBT 프로토타입은 우선 `random_encounters`에서 직접 출현 풀을 받는다. `additions`에 종·레벨·가중치를 지정하고, `spawn_bounds`는 NBT 원점 기준 최소·최대 좌표로 실제 생성 가능 구역을 제한한다. `max_active`와 `spawn_interval_ticks`는 실행 슬롯별 개체 수와 재생성 속도를 제어한다. 이후 프로필 참조를 추가해도 이 직접 목록은 던전별 덮어쓰기로 유지한다.

### 6.2 일반 적

- 트레이너 풀을 역할, 세력, 난이도 등급과 허용 방 태그로 필터링한다.
- 전체 수, 방당 수, 입구 안전 거리, 동일 트레이너 반복 제한을 적용한다.
- 주 경로에는 최소 전투 수를 보장하고 곁가지에는 선택 조우와 추가 보상을 배치할 수 있다.
- 고정 던전 조우는 `requires`에 선행 조우 ID를 선언할 수 있다. 게시 검증은 존재하지
  않는 ID, 자기 참조와 순환 의존성을 거부하며, 런타임은 모든 선행 조우가 승리 상태가
  될 때까지 후속 조우를 잠근다.
- `gates`는 닫힌 상태의 블록 범위와 선행 조우 목록을 선언한다. 실행 준비 시 해당 범위를
  잠금 블록으로 채우고, 요구 조우가 모두 승리 상태가 되는 순간 그 실행 슬롯의 게이트만
  제거한다.
- 시야 방향, 순찰 경로, 플레이어 감지 범위와 전투 후 상태를 설정할 수 있다.
- 일반 적대 몹은 야생 포켓몬과 분리된 스폰 풀과 개체 수 예산을 사용한다.

일반 트레이너 한 명을 정의하는 방식은 두 가지이며 같은 조우에서 동시에 사용할 수 없다.

| 방식 | 데이터 | 동작 | 적합한 용도 |
|------|--------|------|-------------|
| 고정 프리셋 | `opponents` | CVES V5 배틀 프리셋에 저장된 팀·AI·대사를 그대로 사용 | 보스, 간부, 스토리 NPC |
| 즉석 생성 | `trainer_generation` | 포켓몬 풀에서 팀을 만들고 대사 목록에서 한 줄씩 선택하여 실행 전용 트레이너를 등록 | 조무래기, 반복 던전, 무작위 일반전 |

즉석 생성도 월드에 보일 외형과 감지 범위가 필요하므로 `npcs`에는 EasyNPC 외형 프로필을 지정한다. 하지만 배틀 프리셋은 필요하지 않다. 팀은 `pokemon_pool`, `team_size`, 던전의 실제 `internal_level` 범위로 완성되고, 전투 시작 직전에 해당 실행에서만 유효한 RCT/TBCS 트레이너가 만들어진다. 전투 종료·취소·시간 초과·던전 정리 때 이 임시 등록을 반드시 제거한다.

```json
{
  "id": "random_rocket_grunt",
  "kind": "trainer",
  "display_name": "로켓단 조무래기",
  "npcs": ["cobbleventure:rocket_grunt_male"],
  "trainer_generation": {
    "pokemon_pool": [
      { "species": "cobblemon:rattata", "weight": 4 },
      { "species": "cobblemon:zubat", "weight": 3 },
      { "species": "cobblemon:ekans", "weight": 2 }
    ],
    "team_size": [2, 3],
    "allow_duplicates": false,
    "battle_start_lines": [
      "여기까지 들어오다니 간이 크군!",
      "로켓단의 계획을 방해하지 마라!"
    ],
    "battle_end_lines": [
      "이럴 수가...",
      "간부님께 보고해야겠어!"
    ]
  },
  "placement": { "mode": "marker", "marker": "grunt" }
}
```

선택 규칙은 다음과 같다.

- 종은 `weight`에 비례해 선택하고 `allow_duplicates: false`면 같은 팀에서 뽑힌 종을 후보에서 제거한다.
- 같은 종을 풀에 여러 번 적는 것은 금지하며 출현 빈도 차이는 `weight`로 표현한다.
- 팀 크기와 각 포켓몬 레벨은 허용 범위 안에서 결정한다. 현재 프로토타입은 던전의 `internal_level` 범위를 사용하며 이후 층·진행도·보스 보정을 같은 생성 입력으로 확장한다.
- 시작 대사와 종료 대사는 각각 비어 있지 않은 목록이어야 하며 목록에서 한 줄을 선택한다.
- 던전 실행 시드, 조우 ID와 NPC 순번을 결합해 결정적으로 생성한다. 같은 실행을 복구하면 같은 팀과 대사를 얻고 새 실행에서는 달라질 수 있다.
- 중복을 금지한 상태에서 포켓몬 풀의 고유 종 수보다 최대 팀 크기가 크면 게시를 막는다.
- 협력 던전에서 NPC가 둘 필요한 조우는 `npcs` 외형도 둘을 지정하며, 각 NPC는 같은 풀을 사용하되 순번이 다른 시드로 서로 다른 팀을 생성한다.
- 웹 편집기는 `고정 프리셋`과 `포켓몬 풀 즉석 생성`을 전투 구성 선택지로 표시하고, 풀은 `종 | 가중치`, 대사는 한 줄에 하나씩 입력하게 한다.

### 6.3 보스

보스는 `boss_encounter_id`, NPC 정의, 팀·AI, 전투 형식, 등장 연출과 승패 이벤트를 가진다. 보스방 마커가 없거나 필요한 NPC 수를 배치할 수 없으면 계획 검증을 실패시킨다.

### 6.4 배치 가능한 지원 요소

- 치료소: 완전 회복 또는 HP·PP·상태이상별 부분 회복, 1회 또는 반복 사용
- 체크포인트: 허용하는 던전만 재시작 위치로 사용
- 상자·드롭·자원 노드: 개인 또는 참가자 공유 소유권
- 퍼즐·열쇠·스위치·문: 목표 그래프와 연결
- 전설의 흔적: 조사 오브젝트, 컷신, 퀘스트 플래그 또는 후속 입장권
- 클리어 방: 출구, 보상 상자, NPC, 전설 조우와 귀환 오브젝트

고정 NBT 프로토타입의 치료소는 `support.healing_stations`에서 상대 좌표와 표시 블록을 배치한다. `uses_per_run`으로 실행당 사용 횟수를 제한하고, `restore_hp`, `restore_status`, `restore_pp`를 조합해 회복 범위를 정한다. 건강한 파티에는 횟수를 소비하지 않으며 전투 중 사용은 서버에서 거부한다.

## 7. 전투 규칙과 다인 던전

### 7.1 권장 레벨과 실제 내부 레벨

권장 레벨은 플레이어에게 난이도를 안내하는 표시값이고, 내부 레벨은 실제 트레이너와 야생 포켓몬을 생성하는 규칙이다. 두 값은 자동으로 같아지지 않는다.

- `recommended_level.min/max`: 입구·던전 목록·입장 대기 화면에 표시한다.
- `level_mode: fixed`: 모든 대상이 지정 레벨을 사용한다.
- `level_mode: range`: 트레이너·야생별 최소·최대 범위에서 정한다.
- `level_mode: scale_to_party`: 입장 시 고정한 참가자들의 포켓몬 파티 기준 레벨에 오프셋·하한·상한을 적용한다.
- `level_mode: scale_with_floor`: 시작 레벨에 방 깊이·층·보스 보정을 더한다.
- 트레이너, 야생, 보스는 서로 다른 범위와 보정을 가질 수 있다.

권장 범위 밖의 플레이어를 막을지는 `entry.level_gate`로 별도 설정한다. 기본적으로 권장 레벨은 안내만 하고 입장을 막지 않는다. 스케일링 기준 포켓몬 파티는 입장 순간의 스냅샷으로 고정해, 던전 안에서 포켓몬을 바꿔 난이도를 조작하지 못하게 한다.

### 7.2 던전 전역 전투 제한

각 던전은 모든 일반·보스·야생 전투에 적용할 전역 규칙을 가진다. 개별 보스 예외는 명시적으로 허용된 키만 덮어쓸 수 있다.

| 옵션 | 동작 |
|------|------|
| `allow_flee` | `false`이면 전투 UI와 서버 양쪽에서 도망치기를 거부한다. |
| `allow_capture` | 야생 포켓몬 포획 허용 여부를 정한다. |
| `allow_items` | 전투 아이템 전체 또는 가방 포켓·아이템 태그별 사용을 제한한다. |
| `allow_party_edit` | PC, 파티 교체 UI와 외부 저장소 접근을 제한한다. 전투 중 정상 교체와는 별개다. |
| `allow_escape_actions` | 귀환 아이템, 순간이동 명령, 외부 워프와 차원 이동을 제한한다. |
| `loss_policy` | 패배 시 같은 방 복귀, 체크포인트, 실행 실패 중 하나를 선택한다. |

클라이언트에서 버튼만 숨기지 않고 서버가 최종 거부해야 한다. 운영자 구조 명령, 사망 복구와 오류 탈출은 일반 플레이어의 탈출 제한과 분리된 안전 경로로 유지한다.

고정 NBT 프로토타입은 `battle.allow_flee`, `battle.allow_capture`, `battle.allow_items`를 먼저 구현한다. 도망·회복 아이템·전투 강화 아이템은 Cobblemon 행동 검증 단계에서 거부하고, 포획 제한은 몬스터볼 엔티티가 생성되거나 소모되기 전에 서버에서 거부한다.

### 7.3 전투 형태

전장에 동시에 나오는 포켓몬 수와 실제 참가자 구성을 별도 값으로 관리한다.

| 축 | 예시 값 | 의미 |
|----|---------|------|
| `field_format` | `singles`, `doubles` | 한 전투의 싱글·더블 규칙 |
| `participant_mode` | `solo`, `two_player_multi` | 한 플레이어 전투인지 두 플레이어 협동인지 |

### 7.4 다인 던전 유형

2인 던전이라는 정보만으로는 동작을 결정하지 않는다. 반드시 다음 유형 중 하나를 선택한다.

대기, FIFO 매칭, 입장 명단 잠금과 취소는 [던전 입구 대기열·자동 매칭 시스템](DUNGEON_ENTRY_MATCHMAKING.md)을 따른다. 이 문서는 던전 안에서 적용할 이동·전투 규칙을 정의한다.

| `multiplayer.mode` | 이동과 진행 | 전투 |
|--------------------|-------------|------|
| `solo` | 한 명만 입장 | 일반 싱글·더블 규칙 |
| `cooperative` | 같은 구역에서 함께 진행하며 핵심 목표를 공동 수행 | 지정 조우에 두 참가자를 강제 집결해 협동 전투 |
| `independent` | 같은 인스턴스 안에서 서로 다른 방과 곁가지를 자유롭게 탐색 | 조우한 플레이어가 개별 전투, 보스만 합류 요구 가능 |

협력형은 다음 방어 로직을 가진다.

- `warn_distance`를 넘으면 화면·나침반 경고와 동료 위치 안내를 표시한다.
- `max_distance`를 넘는 이동, 문 통과 또는 상호작용을 막거나 안전 지점으로 되돌린다.
- `battle_join: summon_all`이면 전투 잠금을 얻은 뒤 다른 참가자를 조우 방의 안전 마커로 이동시키고 전투를 시작한다.
- 다른 참가자가 이미 전투 중이거나, 순간이동 불가 상태이거나, 조우 방을 준비하지 못했으면 새 전투를 시작하지 않는다.
- 강제이동은 벽·용암·낙하 지점이 아닌 검증된 참가자 마커만 사용하고 이동 직전 위치를 복구점으로 기록한다.
- 보스방·엘리베이터·퍼즐 문은 전원 도착, 준비 확인 또는 강제 집결 중 선택할 수 있다.
- 한 명의 이탈·전멸을 참가자 전체 실패로 볼지 체크포인트로 모을지 정책으로 정한다.

독립행동형은 거리 제한과 일반 조우 강제이동을 사용하지 않는다. 대신 개인별 조우 잠금, 상자 소유권, 개인 목표와 공유 목표, 같은 NPC 중복 전투를 명시해야 한다. 한 명이 보스를 시작했을 때의 동작은 다음 옵션으로 정한다.

| `boss_join_policy` | 동작 |
|--------------------|------|
| `force_gather` | 다른 참가자를 안전 마커로 강제이동한 뒤 즉시 시작 |
| `ask_members` | 모든 참가자에게 참가 요청을 보내고 수락한 인원으로 시작하거나 취소 |
| `require_all_present` | 전원이 보스방에 직접 모이기 전에는 시작 불가 |
| `initiator_only` | 시작한 플레이어만 전투하고 나머지는 계속 탐험 |

협력형과 독립행동형 모두 같은 `DungeonRun`과 슬롯을 공유하지만 위치, 전투와 개인 획득 상태는 참가자 UUID별로 저장한다.

### 7.5 2인 챌린지 전투 규칙

`two_player_challenge`에 `multiplayer.mode: cooperative`를 적용한 던전은 다음 규칙을 기본으로 강제한다.

여기서 `GEN_9_MULTI`는 플레이어 두 명이 한 캐릭터를 같이 조작한다는 뜻이 아니다. `플레이어 A + 플레이어 B`가 한 편, `NPC 트레이너 A + NPC 트레이너 B`가 반대편이 되어 각 참가자가 자기 포켓몬 한 마리씩 내보내는 네 참가자 전투다. 화면에는 양쪽 두 마리씩 나오므로 더블 필드가 된다.

- 참가자를 정확히 두 명으로 고정한다.
- 지정된 모든 적대 트레이너 조우를 `doubles` 성격의 협동 조우로 만든다.
- `two_player_multi`에서는 플레이어 두 명과 상대 트레이너 두 명을 묶어 `GEN_9_MULTI`로 시작한다.
- 단독 NPC만 지정된 일반 조우에는 같은 등급의 파트너 풀을 사용해 상대 두 명을 자동 편성한다.
- 파트너를 편성할 수 없는 조우는 싱글로 조용히 낮추지 않고 게시 전 검증 오류로 표시한다.
- 보스가 다른 형식을 요구하면 던전 정의에 명시적 예외가 있어야 한다.

협력형의 일반 적 조우는 기본적으로 모두 이 `2인 플레이어 대 NPC 2명` 구성을 사용한다. `independent` 던전에서는 조우한 플레이어가 NPC 한 명과 별도의 더블배틀을 진행하며, 다른 플레이어를 끌어오지 않는다. 이 구분으로 별도의 모호한 2인 참여 방식을 추가하지 않는다.

세션 생성, UUID 소유권, 참가자 검증, 재접속과 네 액터 전투 구성은 [특수 던전 2인 협동 멀티배틀](COOPERATIVE_DUNGEON_BATTLES.md)을 따른다. 향후 3인 이상 동시 참가는 Cobblemon 전투 엔진 검증 전까지 허용하지 않는다.

## 8. 목표, 클리어 방과 보상

던전은 하나 이상의 목표를 순서 또는 논리식으로 조합한다.

- 목적지 도달
- 보스 처치
- 지정한 적 또는 모든 필수 적 처치
- 열쇠·단서·전설의 흔적 수집
- 오브젝트 조사 또는 퍼즐 완료
- 제한 시간이나 웨이브 생존
- 전설의 포켓몬 격파·포획·조우 후 귀환

목표가 충족되면 클리어 방의 잠금을 해제하거나, 클리어 방에 보상·NPC·전설 조우·출구를 생성한다. 보상은 `개인 지급`과 `참가자 공유`, 최초 클리어와 반복 클리어, 확정과 가중치 추첨을 구분한다. 지급 기록은 `run_id + player_uuid + reward_id`로 멱등 처리한다.

각 필수 조우는 플레이어에게 보여 줄 현지화 `display_name`을 가진다. 실행 중에는 던전 내부 액션바에 전체 필수 조우 진행도와 현재 선행 조건을 만족한 목표를 주기적으로 표시한다. 전투, 전리품, 치료소와 협력 거리 경고가 표시되는 동안에는 목표 안내를 잠시 미뤄 중요한 피드백을 덮지 않는다. 모든 목표를 마치면 같은 위치에 클리어 룸 귀환 안내를 표시한다.

전설 포켓몬은 던전마다 반드시 직접 등장할 필요가 없다. 같은 구조로 `흔적 발견 → 후속 퀘스트 또는 입장권 지급`과 `클리어 방에서 직접 조우`를 모두 지원한다.

### 8.1 반복 클리어 정책

던전 입장 가능 여부, 클리어 가능 횟수와 보상 반복 여부는 분리한다.

| `completion.repeat_mode` | 동작 |
|--------------------------|------|
| `once` | 한 번만 클리어 기록을 얻을 수 있다. 이후 입장 가능 여부는 별도 옵션이다. |
| `repeatable` | 제한 없이 다시 도전하고 클리어할 수 있다. |
| `cooldown` | 클리어 후 지정한 시간·일간·주간 주기가 지나면 다시 클리어할 수 있다. |
| `limited` | 계정·캐릭터·시즌별 지정 횟수까지만 클리어할 수 있다. |

`completion.reentry_after_clear`는 클리어 후 입장을 막거나, 보상 없는 탐험만 허용하거나, 새 실행으로 입장하게 한다. 반복 가능한 던전도 최초 보상, 반복 보상과 전설 포켓몬 재등장을 각각 다르게 설정할 수 있다. 자동 매칭은 기본적으로 모든 참가자가 같은 클리어·보상 자격을 가져야 성립하며, 자격이 다른 요청은 같은 매칭 풀에 넣지 않는다.

고정 던전 프로토타입에서는 `completion.victory_flag`를 현재 실행의 완료 신호로 사용하고, 영구 클리어 이력은 플레이어별 던전 ID 누적 횟수로 별도 저장한다. 반복 입장 시 완료 신호만 초기화하므로 실패해도 이전 클리어 이력은 사라지지 않으며, `repeatable: false`의 재입장 판정도 이 영구 기록을 사용한다. 기존 버전의 승리 플래그만 가진 플레이어는 최초 확인 시 1회 클리어 기록으로 이관한다.

프로토타입의 최종 귀환은 `completion.return_trigger`로 선택한다. `automatic`은 승리 플래그가 켜지는 즉시 보상 지급과 귀환을 실행한다. `clear_exit`은 `clear_exit_position`에 `clear_exit_block`을 배치하고, 승리 후 참가자가 그 장치에 접근해야 클리어를 확정한다. 이 방식에서는 보스전 뒤 클리어 방과 상자를 살펴볼 시간이 보장된다. 목표를 달성한 뒤 입구 쪽 기존 출구로 되돌아가도 성공 귀환으로 처리하며, 목표 달성 전 사용하면 도전 포기로 처리한다.

직접 지급 보상은 `rewards.first_clear_table`과 `rewards.repeat_table`로 분리한다. `first_clear_field_moves`는 최초 클리어에서만 지급하며, 아이템은 보상 테이블을 던전 내부에서 확정한 뒤 안전 귀환 후 인벤토리에 넣는다. 공간이 부족한 나머지는 귀환 지점에 소유자 우선 드롭해 슬롯 초기화로 사라지지 않게 한다. 반복 가능한 던전은 반복 테이블이 필수이고, 반복 불가 던전은 생략할 수 있다.

### 8.2 전리품 테이블과 상자

클리어 보상과 탐험 중 전리품은 분리한다. 방 조각에 배치된 `loot` 마커나 절차 생성기가 만든 상자는 좌표에 아이템을 직접 저장하지 않고 전리품 테이블을 참조한다.

- `loot.tables`는 일반 상자, 희귀 상자, 숨겨진 상자, 보스방, 테마·구역·층별 테이블을 지정한다.
- 각 테이블은 추첨 횟수, 빈 결과 허용, 항목 가중치, 최소·최대 수량과 조건을 가진다.
- 방 깊이, 난이도, 곁가지 끝, 잠긴 방과 최초 클리어 여부로 테이블 또는 가중치를 보정할 수 있다.
- `per_player`는 같은 상자에서 플레이어별 결과와 수령 상태를 만들고, `run_shared`는 한 번만 추첨해 실행 참가자들이 공유한다.
- `first_claim`은 먼저 연 사람이 소유하므로 경쟁을 의도한 던전에서만 사용한다.
- 실행 시 상자마다 결정된 전리품 시드와 수령 UUID를 저장해 재접속·재오픈으로 재추첨하지 못하게 한다.
- `loot.containers[].requires_completion`이 참인 상자는 현재 실행의 클리어 조건을 달성할 때까지 상호작용을 차단한다. 클리어 방 상자와 보스 보관함에 사용하고, 탐색 중 발견하는 일반 보급 상자는 거짓으로 둔다.
- `loot.containers[].loot_table`을 지정하면 던전 공통 `loot.loot_table` 대신 해당 상자 전용 풀을 사용한다. 생략하면 공통 풀을 상속하므로 일반 상자는 간결하게 유지하고 보스방·희귀·숨겨진 상자만 별도 테이블로 분리할 수 있다.
- 실패 시 획득품 유지 여부는 `loot.on_failure`로 정하며, 클리어 방 보상은 목표 완료 전 열 수 없다.

고정 던전 프로토타입은 `loot.ownership`의 세 모드를 모두 구분한다. `per_player`와
`first_claim`은 서버가 상호작용을 가로채 직접 지급하며, 가방 공간이 부족하면 수령
상태를 되돌려 같은 상자에서 다시 시도할 수 있다. `run_shared`는 월드 상자의 단일
인벤토리와 전리품 시드를 그대로 공유하므로 `keep_collected`만 지원한다. 실패 회수형은
지급한 아이템의 컴포넌트와 수량을 실행 원장에 기록하고, 클리어 지급형은 같은 원장에
임시 보관했다가 클리어 보상과 함께 지급한다.

| `loot.on_failure` | 동작 |
|-------------------|------|
| `keep_collected` | 실패 전 인벤토리에 넣은 일반 전리품을 유지 |
| `remove_run_loot` | 이번 실행에서 획득한 던전 전리품을 실패 시 회수 |
| `grant_on_clear_only` | 상자 결과를 임시 보관하고 클리어할 때만 실제 지급 |

전리품 테이블은 던전 테마 프리셋이 기본값을 제공하고 개별 던전이 교체하거나 추가한다. 예를 들어 아쿠아단 기지는 물·잠수 관련 소모품, 로켓단 연구실은 기술 장치·회복 아이템 테이블을 사용할 수 있다.

## 9. 실행 수명 주기와 초기화

```text
LOBBY
  → PLANNING
  → VALIDATING
  → BUILDING
  → ACTIVE
  → CLEARED | FAILED | ABORTED
  → RETURNING
  → RESETTING
  → CLOSED
```

기본 정책은 `reset_on_exit`이다.

- 싱글 던전에서 플레이어가 정상 출구로 나가거나 도전을 포기하면 귀환 후 실행을 폐기한다.
- 협력형 다인 던전에서 한 명이 정상적으로 이탈하면 기본적으로 참가자 전체를 실패 처리하고 모두 귀환시킨 뒤 초기화한다.
- 연결 끊김은 이탈과 구분해 짧은 재접속 유예 시간을 제공한다.
- 전멸, 제한 시간 종료, 관리자 중단과 복구 불가능 오류도 안전 귀환 후 초기화한다.
- 초기화 대상에는 변경 블록, 문·퍼즐 상태, NPC, 야생 스폰, 상자, 치료소 사용 횟수와 실행 내 목표가 포함된다.
- 최초 클리어 플래그, 퀘스트 진행과 지급 완료 기록은 실행 외부에 저장해 초기화하지 않는다.

재입장·재개 방식은 던전 태그를 추론하지 않고 `lifecycle.resume_mode`로 직접 정한다.

| `resume_mode` | 동작 |
|---------------|------|
| `full_reset` | 나가면 실행을 종료하고 다음 입장 때 처음부터 새로 생성 |
| `checkpoint` | 마지막으로 활성화한 체크포인트의 참가자·퍼즐·조우 상태 규칙에 따라 재개 |
| `keep_until_timeout` | 참가자가 없어도 지정 시간 동안 실행 전체를 유지하고 같은 슬롯으로 복귀 |

재접속 유예는 위 옵션과 별개로 비정상 연결 종료를 잠시 보호한다. 체크포인트와 유지 실행은 같은 `DungeonRun`에만 복귀시키며, 다른 매칭 참가자가 이어받거나 보상·상자를 재추첨할 수 없다.

## 10. 제작 도구와 미리보기

제작 화면은 최소한 다음 탭을 가진다.

1. 기본 정보·프리셋·입구와 건물/지역/오브젝트 배치 역참조
2. 생성 시점·시드·지형·경로·방 조각
3. 권장·내부 레벨, NPC·야생 조우·보스
4. 목표·퍼즐·클리어 방·전리품·보상·반복 클리어
5. 다인 유형·전투 제한·치료·실패·초기화 규칙
6. 미리보기·검증·게시

미리보기는 원본 설정이 아니라 생성된 `DungeonPlan`을 보여 준다.

- 방 그래프와 위에서 본 평면도
- 각 입구의 배치 종류, 출발 안전 앵커와 던전 내부 시작점
- 시작점, 주 경로, 곁가지, 막다른 길과 최단 거리
- 조각 ID, 회전, 연결구, 층과 경계 상자
- 일반 적, 보스, 야생 스폰, 치료소, 보상과 목표 마커
- 상자별 전리품 테이블, 예상 등급·추첨 수와 소유권
- 권장 레벨, 구역별 실제 레벨 범위와 난이도 상승 곡선
- 협력형 거리 경계, 전투 집결 위치와 독립행동형 개인 진행 영역
- 도달 불가능 영역, 조각 충돌, 열린 연결구와 배치 부족 경고
- 같은 시드 다시 생성, 새 시드 추첨, 승인한 시드 고정
- `플레이 미리보기`를 누르면 서버 임시 슬롯에 실제 블록으로 생성하고 제작자를 이동시켜 직접 걸어보게 한다.

초기 버전의 웹 화면은 방 그래프와 2D 평면도를 표시한다. 브라우저 안에서 Minecraft 블록을 3D로 렌더링하거나 서버 화면을 영상처럼 전송하는 기능은 범위에 넣지 않는다. 정확한 모습은 서버 임시 슬롯에 생성된 던전에 직접 들어가 확인한다.

제작자는 여러 시드를 일괄 생성해 방 수, 예상 전투 수, 최단 경로, 가지 수와 실패율 통계를 비교할 수 있어야 한다. 승인 결과는 하나의 `authored` 계획 또는 여러 개의 `authored_pool`로 게시할 수 있고, `runtime`은 같은 검증기를 입장 시 사용한다. 게시된 던전은 데이터 버전과 조각 버전을 고정해 업데이트 후에도 진행 중 실행을 복구할 수 있게 한다.

## 11. 제안 데이터 예시

다음은 구현 계약을 논의하기 위한 예시이며 아직 확정 스키마가 아니다.

```json
{
  "dungeon_id": "cobbleventure:rocket_hideout_alpha",
  "preset": "cobbleventure:team_rocket_facility",
  "environment_profile": "cobbleventure:industrial_interior",
  "entrances": [
    {
      "entrance_id": "cobbleventure:entrance/rocket_warehouse",
      "destination_entry": "main",
      "activation": "interact",
      "visibility": "conditioned",
      "return_policy": "source_safe_anchor"
    }
  ],
  "entry_ui": {
    "info_mode": "summary",
    "confirm_required": true,
    "reopen_policy": "leave_trigger_area"
  },
  "plan": {
    "mode": "authored_pool",
    "plan_ids": ["rocket_alpha_a", "rocket_alpha_b", "rocket_alpha_c"],
    "seed_policy": "match",
    "fallback": "use_fallback_plan"
  },
  "terrain": {
    "mode": "nbt_pieces",
    "piece_pool": "cobbleventure:rocket_hideout",
    "bounds": [160, 48, 160]
  },
  "layout": {
    "mode": "critical_path_branches",
    "critical_path_rooms": [8, 12],
    "branch_count": [2, 5],
    "branch_depth": [1, 3],
    "loop_chance": 0.15
  },
  "difficulty": {
    "recommended_level": [35, 40],
    "level_mode": "range",
    "trainer_levels": [36, 41],
    "wild_levels": [33, 38],
    "boss_level_bonus": 3
  },
  "wild": {
    "profile": "cobbleventure:urban_underground",
    "encounter_mode": "marker_spawn",
    "max_alive": 8,
    "respawn": "never_per_run"
  },
  "encounters": {
    "hostile_pool": "cobbleventure:rocket_grunts_tier_2",
    "required_on_critical_path": 4,
    "optional_per_branch": [0, 2]
  },
  "boss": {
    "encounter_id": "cobbleventure:rocket_admin_alpha",
    "room_tag": "boss",
    "required": true
  },
  "support": {
    "healing_stations": {
      "count": [0, 1],
      "uses_per_run": 1
    }
  },
  "battle": {
    "field_format": "doubles",
    "allow_flee": false,
    "allow_capture": true,
    "allow_items": true,
    "allow_party_edit": false,
    "allow_escape_actions": false
  },
  "objectives": [
    { "type": "defeat_boss", "encounter_id": "cobbleventure:rocket_admin_alpha" }
  ],
  "clear_room": {
    "room_tag": "clear",
    "placements": ["reward_chest", "return_portal"]
  },
  "rewards": {
    "mode": "per_player",
    "first_clear_table": "cobbleventure:rocket_hideout_first_clear",
    "repeat_table": "cobbleventure:rocket_hideout_repeat"
  },
  "loot": {
    "ownership": "per_player",
    "tables": {
      "common": "cobbleventure:rocket_common_chest",
      "rare": "cobbleventure:rocket_lab_rare"
    },
    "on_failure": "keep_collected"
  },
  "completion": {
    "repeat_mode": "cooldown",
    "cooldown": "P1D",
    "reentry_after_clear": "new_run",
    "legendary_repeat": false
  },
  "multiplayer": {
    "mode": "cooperative",
    "min_size": 2,
    "max_size": 2,
    "battle_join": "summon_all",
    "participant_mode": "two_player_multi",
    "force_hostile_field_format": "doubles",
    "tether": {
      "warn_distance": 32,
      "max_distance": 48,
      "on_exceed": "return_to_partner"
    },
    "member_exit_policy": "fail_run"
  },
  "match": {
    "required_players": 2,
    "scope": "same_entrance",
    "order": "fifo",
    "timeout_seconds": 300,
    "on_timeout": "cancel",
    "stay_radius": 8
  },
  "lifecycle": {
    "on_exit": "reset_run",
    "on_wipe": "reset_run",
    "resume_mode": "full_reset",
    "reconnect_grace_seconds": 120
  }
}
```

## 12. 게시 전 검증

다음 조건을 모두 통과해야 던전을 게시할 수 있다.

- 입구에서 모든 필수 목표와 출구까지 이동 가능한 경로가 있다.
- 모든 `entrance_id`가 유일하고 정확히 한 건물 연결·내부 장소·월드 오브젝트 NBT 연결·마을 부지에 배치되어 있다.
- 입구의 `dungeon_id`, 내부 시작 앵커, 활성화 영역과 출발지 안전 귀환 앵커가 모두 존재한다.
- 동굴·숲의 필수 내부 입구는 여러 검증 시드에서 안전한 후보를 찾고 주 이동 영역에서 접근할 수 있다.
- 마을의 필수 던전 부지가 여러 검증 시드에서 시설·도로·벽과 충돌하지 않고 보행 경로에 연결된다.
- 주 경로 길이, 방 수, 층수와 전체 경계가 설정 범위 안이다.
- `authored`·`authored_pool`의 모든 계획이 유효하고 `runtime`의 실패 대체 계획이 존재한다.
- NBT 조각이 겹치지 않고 모든 연결구가 연결되거나 닫혀 있다.
- 입구 안전 반경과 필수 상호작용 공간이 비어 있다.
- 필수 보스·클리어·치료·목표 마커를 요구한 수만큼 배치할 수 있다.
- NPC 풀과 방 마커가 요구된 최소 조우 수를 수용한다.
- 야생 스폰 프로필, 구역 조건, 개체 수 예산과 실제 레벨 범위가 유효하다.
- 권장 레벨과 내부 레벨 범위가 표시되며 스케일링 하한·상한이 뒤집히지 않았다.
- 자동 매칭 인원이 다인 최소·최대 인원 및 전투 형식과 일치한다.
- `allow_solo` 시간 초과 정책은 실제 싱글 진행 경로가 있는 던전에서만 사용한다.
- 다인 던전의 모든 적대 조우가 필요한 상대 참가자 수와 더블 규칙을 충족한다.
- 협력형은 안전한 집결 마커와 거리 제한을 가지며, 독립행동형은 개인·공유 진행 범위와 보스 합류 정책이 명시되어 있다.
- 도망·포획·아이템·외부 탈출 제한을 서버에서 집행할 수 없는 조우가 없다.
- 반복 클리어 자격, 최초·반복 보상과 전설 재등장 정책이 서로 모순되지 않는다.
- 모든 상자 마커가 유효한 전리품 테이블과 소유권·실패 정책을 가진다.
- `checkpoint` 재개를 선택한 던전은 도달 가능한 체크포인트와 복원 상태 범위를 가진다.
- 보상, 팀, NPC, 조각, 스폰 프로필과 이벤트 ID가 실제 데이터에 존재한다.
- 동일 시드 재생성 결과의 계획 해시가 같다.
- 실패·이탈·재접속 경로에서 플레이어 귀환점과 슬롯 정리가 보장된다.
- 건물 내부나 동굴에서 진입한 실행은 `ReturnFrame`을 덮어쓰지 않고 원래 상위 공간으로 복귀한다.

## 13. 구현 순서

### 1단계: 고정 던전 수직 프로토타입

- `DungeonDefinition`, `DungeonPlan`, `DungeonRun` 최소 계약을 만든다.
- `DungeonEntrance`, 배치 참조와 중첩 가능한 `ReturnFrame` 계약을 만든다.
- `DungeonGuide` 안내·확인과 싱글 입장 취소 흐름을 구현한다.
- 권장·내부 레벨, 전역 전투 제한, 반복 클리어와 전리품 테이블 계약을 포함한다.
- 기존 월드맵 오브젝트가 배치한 NBT 앵커를 공간 연결 그래프의 출발점으로 사용할 수 있게 한다.
- 고정 NBT 전투방 하나를 슬롯에 생성하고 입장·보스·보상·귀환·초기화를 연결한다.
- `lifecycle.on_wipe: reset_run`으로 파티 전멸 시 실행을 폐기한다. `wipe_return`은 `source_entrance`와 `pokemon_center`를 지원하고, 입구 복귀형은 `heal_on_wipe`로 재도전 전 회복 여부를 정한다.
- 종료와 오류 상황에서 중복 보상 및 슬롯 누수가 없는지 검증한다.

### 2단계: NBT 조각 생성과 미리보기

- 조각 메타데이터, 연결구와 의미 마커 포맷을 만든다.
- 기존 건물 연결 그래프가 던전 진입점을 목적지로 선택할 수 있게 확장한다.
- 건물 내부 진입·던전 귀환·건물 외부 귀환의 스택 순서를 검증한다.
- 마을 생성기의 필수 시설과 주택 사이에 `dungeon_site` 예약·배치 단계를 추가한다.
- 기존 건물 연결, 전용 부지와 소형 장소의 세 마을 배치 모드를 검증한다.
- `critical_path_branches` 생성기와 결정적 시드를 구현한다.
- 평면도, 주 경로, 마커와 검증 오류를 보여 주는 미리보기를 구현한다.
- 웹에서 `authored`·`authored_pool`을 게시하고 인게임에서 `runtime` 계획을 생성하는 두 경로를 같은 검증기에 연결한다.
- 로켓단 시설 프리셋과 작은 기지 샘플을 만든다.

### 3단계: 조우 자동 배치와 다인 규칙

- 같은 입구의 `DungeonEntryRequest` 두 개를 FIFO로 묶는 `DungeonMatch`를 구현한다.
- 매칭 후 자격 재검사와 전원 동시 입장 트랜잭션을 연결한다.
- 일반 적·야생 포켓몬·보스·치료소·상자·보상 배치 디렉터를 구현한다.
- 모든 적대 조우에 다인 전투 규칙을 강제하고 상대 트레이너 쌍을 편성한다.
- 협력형 집결·거리 제한과 독립행동형 개인 조우 잠금을 구현한다.
- 기존 2인 `GEN_9_MULTI` 어댑터와 재접속·실패 처리를 연결한다.

### 4단계: 생성 방식 확장

- `maze`, `rooms_and_corridors` 경로 생성기를 추가한다.
- 현재 Minecraft 동굴 월드 생성기를 `procedural_cave`와 `hybrid`에 연결하고 필수 경로 보정기를 추가한다.
- 동굴·숲 생성기에 `embedded_sites` 후보 탐색과 입구 NBT 지형 접합을 추가한다.
- 여러 시드의 도달 가능성, 생성 시간, 청크 경계와 서버 부하를 자동 시험한다.

### 5단계: 제작 경험 완성

- 프리셋 상속·덮어쓰기 UI와 서버 임시 슬롯의 플레이 미리보기를 제공한다.
- 공간 연결, 동굴·숲, 월드맵 화면에서 같은 `entrance_id`의 목적지와 역참조를 확인하게 한다.
- 목표 조합, 퍼즐, 전설의 흔적·직접 조우와 고급 보상 정책을 추가한다.
- 버전 고정, 실행 복구, 관리자 진단과 운영 지표를 완성한다.

## 14. 첫 프로토타입 합격 기준

첫 프로토타입은 `team_rocket_facility`를 사용한 2인용 작은 기지로 한다.

1. 같은 설정과 시드가 항상 같은 주 경로·곁가지·방 배치를 만든다.
2. 입구에서 보스방과 클리어 방까지 반드시 도달할 수 있다.
3. 건물 내부, 생성된 동굴·숲 내부와 지상 오브젝트에서 같은 입장 흐름으로 실행을 준비할 수 있다.
4. 각 배치에서 들어온 플레이어가 완료·실패 후 정확히 그 배치의 안전 앵커로 돌아온다.
5. 제작자가 미리보기에서 모든 방, 연결, NPC, 치료소와 보상을 확인할 수 있다.
6. 권장 레벨과 실제 트레이너·야생·보스 레벨이 각각 설정대로 표시·생성된다.
7. 적대 트레이너와 야생 포켓몬이 마커에 자동 배치되고 모두 설정된 전투 제한을 따른다.
8. 협력형에서 두 참가자가 멀어지지 못하고 한 명의 조우 시 안전하게 집결해 전투를 시작한다.
9. 상자가 지정 전리품 테이블에서 플레이어별로 한 번만 추첨된다.
10. 보스 승리 후 두 참가자에게 개인 보상이 정확히 한 번 지급되고 반복 정책이 적용된다.
11. 정상 이탈, 포기, 전멸과 재접속 유예 만료 시 모두 안전하게 귀환한다.
12. 실행 종료 뒤 변경 블록, NPC, 상자, 치료소와 목표 상태가 남지 않는다.
13. 동시에 실행한 다른 `DungeonMatch`와 슬롯, NPC, 전투, 보상이 섞이지 않는다.
14. 마을 던전 입구가 필수 시설·도로·NPC 동선과 충돌하지 않고 실제 던전은 마을 밖 인스턴스에 생성된다.

## 15. 이번에 확정한 결정과 테스트 항목

- 전역 파티·사전 초대·파티장은 만들지 않고, 던전 입장 버튼을 누른 요청끼리 입구에서 자동 매칭한다.
- 첫 구현은 같은 입구의 FIFO 대기열에서 정확히 두 명을 연결하고, 먼저 누른 플레이어에게 다른 도전자를 기다린다는 화면을 표시한다.
- 두 번째 요청이 들어오면 양쪽 자격을 다시 검사하고 준비가 모두 성공한 뒤 함께 이동한다.
- 모든 던전 입장은 공통 `DungeonEntrance`를 사용하고 건물 연결, 동굴·숲 내부 장소, 지상 오브젝트 NBT의 연결 앵커가 이를 참조한다.
- 마을 내부는 기존 건물 연결, 예약 부지, 소형 장소 중 하나로 입구만 배치하며 초기화되는 본 던전은 인스턴스에 유지한다.
- 건물 내부 입구는 기존 공간 연결 그래프를 확장한다. 일반 지형은 기존 월드맵 오브젝트가 NBT만 배치하고 그 NBT 앵커를 같은 연결 그래프에서 던전으로 잇는다.
- 동굴·숲 내부에는 별도 `embedded_sites` 배치 단계를 추가하며 고정 앵커·NBT 마커·규칙 기반 후보 배치를 지원한다.
- 웹에서는 방 그래프와 2D 평면도를 편집한다. 실제 모습은 서버 임시 슬롯에 생성해 제작자가 직접 들어가 확인한다.
- `runtime` 생성 제한 시간과 재시도 횟수는 프리셋별 부하 테스트 후 결정한다. 생성 실패 시 사용할 검증된 안전 계획은 미리 준비할 수 있다.
- 협력형 일반 조우는 플레이어 두 명 대 NPC 두 명의 `GEN_9_MULTI`로 구성한다.
- 독립행동형 일반 조우는 조우한 플레이어가 NPC와 별도 더블배틀을 하며 다른 참가자를 강제이동하지 않는다.
- 독립행동형 보스 시작은 `force_gather`, `ask_members`, `require_all_present`, `initiator_only` 중 선택한다.
- 재개 방식은 `full_reset`, `checkpoint`, `keep_until_timeout` 중 선택한다.
- 실패 시 전리품은 `keep_collected`, `remove_run_loot`, `grant_on_clear_only` 중 선택한다.
- 절차 동굴은 현재 일반 월드와 같은 Minecraft 동굴 생성 로직을 사용하고 던전 필수 경로만 추가로 검증·보정한다.

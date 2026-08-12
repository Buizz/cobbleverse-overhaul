# Radical Gyms & Structures 통합 기준

> 결정: BCA는 마을 길과 주택을 생성하고, 체육관 외관은 Radical Gyms &
> Structures(RGS) 0.6 구조물을 정리해 사용한다. 실제 체육관 내부는
> `cobbleventure:gym_interiors` 차원에 자동 배치한다.

## 역할 분리

- `tools/mod-builder/starter_gym.py`는 더 이상 체육관 외관을 만들지 않는다. BCA의
  길·주택 Jigsaw를 시작하는 작은 중립 도로 허브만 생성한다.
- 실제 체육관 외관은 마을의 `gym_building` 방향을 기준으로 `rgs:*_gym` NBT를
  배치하되, 명령 블록과 모든 발판은 배치 직후 제거한다.
- 정문에는 철문을 설치한다. 플레이어가 문을 우클릭하면 조건을 검사하고, 통과한
  플레이어만 격리된 내부 인스턴스로 이동한다.
- 내부에도 정리된 RGS 템플릿을 자동 배치한다. `leader_trainer_id`가 설정되어
  있으면 같은 슬러그의 생성된 EasyNPC 조우 프리셋을 관장 위치에 한 번 생성한다.
  `interior.leader_npc`로 다른 프리셋을 명시할 수도 있다.
- 도로 허브의 동쪽 연결은 비워 RGS 체육관과 BCA 주택이 겹칠 가능성을 줄인다.
- 체육관 Y는 JSON의 과거 고정 높이를 사용하지 않고, 지형을 모두 그린 뒤 앵커
  X/Z의 실제 지표면에서 다시 계산한다.
- RGS 체육관 NBT는 수정하거나 재배포용 파일로 저장소에 복사하지 않는다.
  CurseForge manifest가 원본 파일을 설치하고 런타임에서 `rgs:*` 템플릿을 읽는다.
- 리그는 외관 교체나 블록 치환 없이 `rgs:kanto_league`를 그대로 배치한다.

이 분리는 BCA 마을 생성과 RGS 체육관 건물을 독립시킨다. RGS NBT에는 BCA 도로용
Jigsaw 연결점이 없으므로 RGS 체육관을 마을의 시작 조각으로 직접 사용하지 않는다.

## 고정 버전과 구조물 ID

| 항목 | 값 |
|------|----|
| Radical Gyms & Structures | 0.6 / CurseForge `1402174:7330950` |
| Radical Cobblemon Trainers API | 0.15.2-beta / `1152792:7952419` |
| Radical Cobblemon Trainers | 0.18.1-beta / `1009534:7913180` |
| CobbleFurnies | 1.0 / `1188698:7302031` |
| Athena | 4.0.6 / `841890:8061947` |
| Architectury API | 13.0.8 / `419699:5786327` |

CobbleFurnies 1.0은 `athena:carpet_ctm` 모델 로더를 사용하지만 자체
`neoforge.mods.toml`에는 Athena 의존성을 선언하지 않는다. Athena가 없으면 포켓볼
양탄자의 기본 모델인 `minecraft:block/air`만 남아 월드에서 투명하게 보이므로,
모드팩 lock에서 Athena를 명시적인 클라이언트 필수 의존성으로 관리한다.

RGS 0.6에서 확인한 체육관 템플릿은 다음과 같다.

- `rgs:pewter_gym`
- `rgs:cerulean_gym`
- `rgs:vermilion_gym`
- `rgs:celadon_gym`
- `rgs:fuchsia_gym`
- `rgs:saffron_gym`
- `rgs:cinnabar_gym`
- `rgs:blackthorn_gym`
- 리그: `rgs:kanto_league`

체육관은 약 25×13×26 블록이지만 리그는 약 73×170×78 블록이므로, 리그는
일반 마을의 체육관 인스턴스 좌표가 아니라 전용 리그 지역에 충분한 간격을 두고
배치한다. RGS가 제공하지 않는 벌레 타입 등의 체육관은 임의의 관장전을 연결하지
않고, 대응 실내를 정한 뒤 명시적으로 JSON에 추가한다.

RGS 0.6 체육관의 도로 연결 기준은 서쪽 면의 로컬 좌표
`(x=2, y=3, z=10)`이고, 플레이어가 사용하는 공용 정문은 북쪽 면의 로컬 좌표
`(x=12, y=3, z=3)`이다. 순환도로의 진입로는 구조물 중심이 아니라 도로 연결
기준점까지 이어지고, 우클릭 이동용 철문은 공용 정문 위치에 설치한다.

## 마을 JSON 계약

체육관 외관과 내부는 `structure_profile.gym`에 선언한다.

```json
{
  "enabled": true,
  "structure": "rgs:pewter_gym",
  "theme": "rock",
  "anchor": "gym_building",
  "leader_trainer_id": "cobbleventure:trainer/starter_town_leader",
  "entrance": {
    "door_offset": { "x": 12, "y": 3, "z": 3 },
    "outside_offset": { "x": 12, "y": 4, "z": 1 },
    "facing": "north",
    "condition_mode": "all",
    "conditions": [
      {
        "type": "variable",
        "source": "scoreboard",
        "key": "previous_badge",
        "operator": ">=",
        "value": 1
      }
    ],
    "locked_dialogue": ["문이 잠겨 있다.", "먼저 이전 체육관을 공략하자."],
    "enter_dialogue": ["체육관 문이 열렸다."]
  },
  "interior": {
    "structure": "rgs:pewter_gym",
    "entry_offset": { "x": 12, "y": 4, "z": 5 },
    "exit_door_offset": { "x": 12, "y": 3, "z": 3 },
    "leader_offset": { "x": 12, "y": 3, "z": 21 },
    "leader_npc": "easy_npc:preset/encounter/starter_town_leader.npc.snbt"
  }
}
```

- `required_facilities.village_hub`: BCA 도로를 시작하는 비건축 허브 NBT
- `gym_building`: 마을 중심에서 체육관을 배치할 방향을 나타내는 원점 앵커
- 런타임은 RGS 체육관의 실제 템플릿 크기를 읽은 뒤 BCA 직소 최대 반경
  바깥에 예약 영역을 잡고, 해당 지표면 Y를 계산해 `/place template`로 배치
- 체육관은 마을 중심 동쪽에 건물 시작점 기준 132블록 떨어뜨린다. 25×26 건물과
  6블록 순환도로가 마을 평탄 영역 안에 들어가도록 평탄 반경은 176블록을 사용한다.

리그처럼 원본 구조물을 지역 앵커에 그대로 놓을 때는 다음 형식을 사용한다.

```json
{
  "id": "kanto_league",
  "mode": "direct_template",
  "structure": "rgs:kanto_league",
  "anchor": "league_origin"
}
```

## 배치와 운영 규칙

1. 새 월드 초기화 시 BCA 도로 허브와 마을을 먼저 배치한다.
2. BCA 마을 배치가 끝난 뒤 체육관 외관을 예약 영역에 배치하고, 명령 블록과
   발판을 제거한 다음 정문 철문을 설치한다.
3. 실제 출입구 `(2, 3, 10)`을 기준으로 체육관 순환도로와 마을 중앙 도로를 잇는다.
   체육관 배치가 끝나면 서쪽 정문 앞에서 순환도로까지 3칸 폭의 고정 진입로를
   다시 그려, 지역 간 연결로가 우연히 옆을 지나가더라도 체육관 정문이 끊기지 않게 한다.
4. 하나라도 배치에 실패하면 지도 완료 상태를 저장하지 않아 다음 접속에서
   재시도할 수 있게 한다.
5. `gym_interiors`의 슬롯은 정착지 ID 해시를 기준으로 256블록 간격으로 안정적으로
   자동 할당한다. 체육관 목록이 늘어나도 기존 내부 좌표는 밀리지 않는다.
6. 현재 매핑은 시작=Pewter, 초록길=Celadon, 황무지=Cinnabar,
   섬=Cerulean, 산악=Blackthorn이다.
7. 지도 계약이 바뀌었으므로 기존 테스트 월드 대신 새 월드에서 검증한다.

## 검증 체크리스트

- 개발 팩 manifest에 RGS와 네 가지 필수 의존성이 모두 들어간다.
- 자체 콘크리트 체육관 외관이 생성되지 않는다.
- 다섯 마을에 지정된 RGS 체육관 외관과 격리 내부가 각각 생성된다.
- 외관과 내부에 명령 블록 및 발판이 남지 않는다.
- 잠긴 문은 대사를 출력하고 이동시키지 않으며, 조건 달성 후에는 이동시킨다.
- 내부 퇴장 문은 플레이어를 해당 체육관 정문 앞으로 돌려보낸다.
- `leader_trainer_id` 또는 `leader_npc`를 지정한 체육관에는 EasyNPC 관장이 한 번만 생성된다.
- 체육관과 BCA 주택·도로가 겹치지 않는다.
- 재접속 후에도 구조물이 중복 배치되지 않는다.
- 리그 전용 마을 데이터를 추가했을 때 원본 팔레트가 바뀌지 않는다.

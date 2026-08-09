# 전용 시작 마을 레이아웃

시작 마을은 BCA 일반 마을 프리셋에서 상업 시설만 제거하는 방식이 아니라,
Cobbleventure가 주요 동선을 고정하는 authored 레이아웃으로 생성한다.

## 기본 구성

- 중심 건물: `bca:default/centers/center_the_academy`
  - 49×60×73 규모의 BCA 아카데미를 초기 연구소 외형으로 사용한다.
  - 원본 템플릿의 바닥 기준이 한 칸 높으므로 시작 마을 배치 중 연구소 조각만
    한 칸 낮춘다. 주변 도로와 주택의 직소 연결 높이는 변경하지 않는다.
  - 추후 자체 연구소 NBT가 완성되면 관리 웹의 구조물 ID만 교체한다.
- 플레이어 집: `bca:default/structures/the_cozy_cranny`
  - 연구소 서쪽 도로에 출입구가 맞도록 고정 앵커에 배치한다.
  - 배치 전 22×17 부지를 정리해 다른 Jigsaw 장식과 겹치지 않게 한다.
- 주변 마을: BCA 기본 도로와 주택을 Jigsaw 깊이 2까지만 확장한다.
- 상업 시설: `commercial_center: none`
  - `bca:default/one_off` 요청을 `minecraft:empty`로 치환한다.
  - 포켓몬센터와 포켓몬 상점은 시작 마을에 생성되지 않는다.
- 체육관: 기존 설정을 유지하며 켜고 끌 수 있다.

## 데이터 예시

```json
{
  "village_preset": "cobbleventure_starter",
  "commercial_center": "none",
  "starter_layout": {
    "laboratory_structure": "bca:default/centers/center_the_academy",
    "jigsaw_depth": 2
  },
  "special_district": {
    "enabled": true,
    "anchor": "player_home",
    "footprint": { "width": 22, "depth": 17 },
    "clearance": 0,
    "entrance_direction": "east",
    "building": {
      "enabled": true,
      "id": "player_home",
      "structure": "bca:default/structures/the_cozy_cranny"
    }
  }
}
```

관리 웹의 `마을 프리셋`에서 `시작 마을 · 연구소 중심 고정 배치`를 선택하면
연구소 구조물과 주변 Jigsaw 확장 깊이를 편집할 수 있다. 전용 시작 마을에서는
상업 중심 시설이 자동으로 `없음`으로 고정된다. 플레이어 집은 특별 구역의
건축물과 앵커로 교체할 수 있다.

## 교체 원칙

연구소나 플레이어 집을 자체 건축물로 바꿀 때 Java 코드를 수정하지 않는다.
새 NBT를 데이터 모드에 추가한 뒤 각각 `laboratory_structure`와
`special_district.building.structure` 리소스 ID만 변경한다.

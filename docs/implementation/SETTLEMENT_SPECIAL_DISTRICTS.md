# 마을 특별 구역과 선택형 체육관

각 마을은 일반 BCA 주거 구역과 별도로 하나의 `special_district`를 예약할 수 있다.
특별 구역은 당장 건물이 없어도 위치와 부지 크기를 먼저 정할 수 있으며, 이후
라디오타워·연구소·회사 본사·리그 관련 시설처럼 큰 구조물을 연결한다.

```json
"special_district": {
  "enabled": true,
  "anchor": "special_district",
  "footprint": { "width": 64, "depth": 48 },
  "clearance": 8,
  "entrance_direction": "south",
  "building": {
    "enabled": true,
    "id": "radio_tower",
    "structure": "cobbleventure:facilities/radio_tower"
  }
}
```

- `anchor`: 마을 `anchors`에 등록된 구조물 원점이다.
- `footprint`: 구조물과 무관하게 보장할 최소 부지 크기다. 실제 NBT가 더 크면 런타임에서 NBT 크기를 우선한다.
- `clearance`: 건물 외곽에 추가로 정리할 여유 공간이다.
- `entrance_direction`: 이후 마을 도로와 입구를 연결할 때 사용할 기준 방향이다.
- `building.enabled`: 끄면 부지 설정은 남기고 건물만 배치하지 않는다.

특별 건물이 활성화되면 월드 부트스트랩은 템플릿을 배치하기 전에 실제 구조물
크기와 설정된 최소 부지 중 큰 값을 사용해 지형을 평탄화하고, 나무와 장애물을
제거한다. 따라서 기존 소형 시설보다 큰 건물도 같은 데이터 형식으로 처리할 수 있다.

## 선택형 체육관

```json
"gym": {
  "enabled": true,
  "gym_id": "cobbleventure:gym/pewter",
  "structure": "cobbleventure:gyms/base_gym",
  "theme": "rock",
  "anchor": "gym_building"
}
```

마을 프리셋은 체육관 종류와 외부 배치만 저장한다. 관장과 기타 트레이너는 체육관
카탈로그의 `staff`에서 관리하며, 체육관 내부 NBT의 NPC 라벨 위치에 생성된다.

새로 만드는 마을은 특별 구역만 48×48 크기로 예약하고, 특별 건물과 체육관은 꺼진
상태로 시작한다. 기존 5개 마을은 특별 구역을 예약했으며 현재 체육관 설정은 유지한다.

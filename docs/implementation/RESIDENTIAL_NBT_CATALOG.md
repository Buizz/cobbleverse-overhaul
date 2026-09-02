# NBT 주택 자동 배치 카탈로그

## 웹 사용 순서

1. **NBT 건물 설정**에서 원하는 외부 건물 NBT를 선택한다.
2. **주택 자동 배치 카탈로그 → 주택 자동 배치에 사용**을 켠다.
3. 표시 이름과 선택 가중치(1~1000 정수)를 입력하고 **NBT 건물 설정 저장**을 누른다.
4. **마을 관리 → 주택 구성 → 주택 선택 방식**을 **NBT 주택 카탈로그**로 바꾼다.
5. 공통 후보 전체를 사용하거나, 전체 사용을 해제하고 이름·NBT ID로 검색하여 해당 마을의 후보를 선택한다.
6. 미리보기를 확인하고 **마을 저장** 후 팩을 다시 빌드한다.

새 마을은 카탈로그 방식을 기본으로 사용한다. 기존 마을은 명시적으로 전환하기 전까지
층수·지붕·색상 조합을 유지한다. 기존 12개 주택 외관은 가중치 1로 등록되어 있으며,
플레이어 집·이수재 집과 같은 고유 시설은 임의로 공통 후보에 추가하지 않는다.

## 데이터 계약

공통 정의는 별도 중복 목록 대신 `content/catalogs/building-settings.json`의
각 NBT 항목에 저장한다.

```json
"cobbleventure:custom/cottage": {
  "residential_placement": {
    "enabled": true,
    "weight": 3,
    "label": "작은 별장"
  }
}
```

기존 `fixed_npcs`, `citizen_placement_allowed`, `interiors`, `door_routes` 등은 그대로 사용한다.
위 예시는 추가 속성만 나타낸 것이다. 내부공간 NBT는 주택 외관 후보로 등록할 수 없다.

마을의 `structure_profile.generation_profile`:

```json
{
  "residential_buildings_enabled": true,
  "residential_source": "catalog",
  "residential_structures": ["cobbleventure:custom/cottage"]
}
```

- `residential_source` 생략 또는 `legacy`: 기존 `house_palette` 조합을 사용한다.
- `catalog`: `residential_placement.enabled`인 원본 NBT를 가중치로 추첨한다.
- `residential_structures` 생략: 활성화된 공통 후보 전체를 사용한다. 이후 등록한 NBT도 포함된다.
- 배열 지정: 해당 마을에서 사용할 후보를 제한한다. 삭제·비활성화된 후보는 자동 대체하지 않고 오류로 알린다.
- 자동 주택이 활성화되었는데 후보가 없으면 생성 오류로 알린다. 주택이 필요 없는 지역은 `residential_buildings_enabled: false`를 사용한다.

카탈로그 방식에서는 층수와 지붕 종류를 코드에 등록할 필요가 없고, 원본 NBT의 색상을 그대로 사용한다.
실제 NBT 크기·점유 범위를 읽고 `door` 앵커 방향을 기준으로 회전한다.
문 앵커의 Y좌표는 건물 배치 높이로 사용하지 않는다. 기존 Y 배치 보정 설정을 유지한다.
문/안전 이동 위치는 진입로 연결에 사용하고, 내부 NPC 수용량은 정확한 NBT ID의 설정을 조회한다.

주택 수 목표와 도로·부지 배치 알고리즘은 기존 마을 크기·밀도·깊이 규칙을 유지한다.
가중치는 추첨 비율이며, 실제 배치 수는 부지 확보 가능 여부에 영향을 받는다.

## 확인

- 카탈로그 목록 API는 JSON과 파일 목록만 읽으며 모든 NBT의 모델을 해석하지 않는다.
- 웹 미리보기와 팩 빌드는 동일한 Python 배치기를 사용한다.
- 기존 수동 배치와 저장된 마을은 카탈로그 등록만으로 재생성하지 않는다.
- 공간 연결 저장 시에도 주택 자동 배치 속성을 유지한다.

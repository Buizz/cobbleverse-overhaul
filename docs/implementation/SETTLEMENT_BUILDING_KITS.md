# 마을별 건축 디자인과 건축 키트

## 목적

모든 마을이 같은 BCA 마을 조각을 반복하지 않도록, 마을마다 건물 형태·재료·지붕색·시설 구조물을 선택하는 건축 키트를 만든다.

- 관련 기능: `SETTLEMENT-BUILDING-01`
- 결정: `DEC-WORLD-016` — 마을은 하나의 완성 구조물이 아니라 역할별 건축물과 팔레트로 구성한다.
- 상태: 구현 예정

## 건축 키트 구성

새 카탈로그 `content/catalogs/building-kits.json`을 만든다.

```json
{
  "id": "cobbleventure:building_kit/starter_plains",
  "palette": "cobbleventure:palette/starter_plains",
  "road_profile": "cobbleventure:road/starter_plains",
  "wall_profile": "cobbleventure:boundary/starter_plains_wall",
  "buildings": {
    "house_small": ["cobbleventure:starter_plains/house_small_01"],
    "house_large": ["cobbleventure:starter_plains/house_large_01"],
    "pokemon_center": ["cobbleventure:starter_plains/pokemon_center_01"],
    "shop": ["cobbleventure:starter_plains/shop_01"],
    "gym": ["cobbleventure:starter_plains/gym_rock_01"]
  }
}
```

## 구조물 역할

최소 역할은 다음과 같다.

- 소형·중형·대형 주택
- 포켓몬센터
- 상점
- 체육관 외관과 입구
- 마을 회관 또는 광장 핵심물
- 장식, 가로등, 표지판, 나무와 화단
- 관문과 경비 초소

각 역할에는 여러 변형을 둘 수 있다. 마을의 필수 시설은 정확히 하나 배치하고, 주택과 장식은 시드 기반으로 선택한다.

## 디자인 규칙

- 지붕색은 마을 또는 체육관 테마를 즉시 알아볼 수 있게 한다.
- 바닥·벽·기둥·지붕·창문을 팔레트 키로 나눠 블록 교체가 가능해야 한다.
- 체육관 외관과 내부 전투장은 별도 구조물로 유지할 수 있다. 입구 상호작용으로 전용 내부 공간으로 이동하는 방식도 지원한다.
- 건물 출입구는 도로 높이와 맞고, 문 앞 최소 3×3 영역을 비운다.
- 템플릿 원점, 회전, 연결점을 메타데이터로 검증한다.

## BCA와의 관계

BCA 구조물은 초기 참고·호환 키트로 사용할 수 있지만 기본 설계를 BCA 리소스 ID에 고정하지 않는다.

- `bca_compat` 키트: BCA 구조물을 선택적으로 매핑
- `starter_plains` 키트: 프로젝트가 직접 소유하는 첫 마을 구조물
- 외부 리소스를 복제할 때는 라이선스와 출처를 기록
- 의존 모드가 없어도 자체 키트의 스키마 검증과 빌드가 가능해야 함

## 제작 순서

1. 현재 테스트 마을의 건물 역할과 크기를 목록화한다.
2. 시작 마을용 도로·외벽·지붕 팔레트를 확정한다.
3. 필수 시설 4종과 주택 3종의 템플릿을 만든다.
4. 구조물 메타데이터와 건축 키트 카탈로그를 연결한다.
5. 회전별 출입구, 도로 연결, 충돌을 자동 검사한다.
6. 게임에서 낮·밤, 비, 원거리 렌더링을 시각 검수한다.

## 완료 기준

- 마을 JSON의 `building_kit`만 바꿔 전체 디자인을 교체할 수 있다.
- 시작 마을이 자체 구조물만으로 필수 시설과 주택을 구성한다.
- 동일 역할의 변형이 겹치거나 도로를 막지 않는다.
- 필수 시설 누락, 잘못된 구조물 ID, 라이선스 미기록 외부 리소스가 빌드에서 검출된다.

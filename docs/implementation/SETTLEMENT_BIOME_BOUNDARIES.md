# 마을별 바이옴 범위와 경계 벽

## 목적

마을 JSON에서 내부 바이옴과 주변 바이옴을 고르면 그 바이옴만 마을 주변에 생성하고, 범위 끝에는 통과 불가능한 벽을 만든다. 다음 지역으로 가는 길은 명시된 관문만 연다.

- 관련 기능: `WORLD-BOUNDARY-01`, `SETTLEMENT-BIOME-01`
- 결정: `DEC-WORLD-015` — 경계는 바이옴 판정과 물리 벽을 같은 프로필에서 생성한다.
- 상태: 구현 예정

## 마을 JSON 확장 초안

```json
{
  "biome_layout": {
    "arrangement": "organic_patches",
    "transition_width": 12,
    "zones": [
      { "id": "plains", "biome": "cobbleventure:starter_plains", "size_blocks": 320, "placement": "center", "weight": 5 },
      { "id": "forest", "biome": "minecraft:forest", "size_blocks": 192, "placement": "outer", "weight": 3 },
      { "id": "river", "biome": "minecraft:river", "size_blocks": 96, "placement": "middle", "weight": 1 }
    ],
    "boundary": {
      "profile": "cobbleventure:boundary/starter_region_wall",
      "width": 16,
      "wall_height": 12,
      "wall_thickness": 5
    }
  },
  "connections": [
    {
      "id": "next_town_gate",
      "target_settlement": "cobbleventure:settlement/route_01_town",
      "placement": { "mode": "toward_target", "preferred_side": "east", "offset": 0 },
      "gate_width": 9,
      "path_width": 7
    }
  ]
}
```

마을 `bounds`는 건물과 도로가 놓이는 영역이다. 바이옴은 1~3개까지 지정하며
`size_blocks`는 목표 지름, `placement`는 중심으로부터의 상대 위치다. 생성기는
가중치와 월드 시드를 사용해 구역을 자연스럽고 결정적으로 배치한다. 모든 구역의
합집합 바깥에는 경계 띠와 벽을 놓는다.

## 경계 프로필

새 카탈로그 `content/catalogs/boundary-profiles.json`에 다음을 둔다.

- 벽 높이와 두께
- 파괴 불가능한 내부 재료와 외장 팔레트
- 기초가 묻히는 깊이
- 상단 장식과 조명 간격
- 경계 띠 바이옴
- 관문 구조물과 통로 폭
- 텔레포트·비행·굴착 우회 방지 정책

초기 벽은 최소 두께 5블록, 지표 위 12블록으로 한다. 중앙에는 기반암 또는 동등한 보호 코어를 두고, 마을 테마 블록은 외장으로 사용한다. 지형 틈으로 빠지지 않게 벽 기초는 안전 기반층까지 논리적으로 연결하거나 보호 볼륨으로 막는다.

## 좌표 판정

마을 기준으로 다음 순서로 판정한다.

1. `center`와 `bounds`를 기준으로 중앙 바이옴을 고정한다.
2. 나머지 구역을 `placement`와 `size_blocks`에 맞춰 충돌을 최소화해 배치한다.
3. 전환 폭에서는 두 구역의 경계를 노이즈로 완화한다.
4. 전체 바이옴 구역 외곽에 경계 바이옴과 벽을 만든다.
5. 다음 마을 중심 방향과 외곽의 교점에는 벽을 생략하고 관문·통로를 만든다.
6. 다음 마을 데이터가 없으면 `preferred_side`와 `offset`으로 관문을 배치한다.
7. 그 너머는 다음 지역 또는 아직 할당되지 않은 외부 영역이다.

바이옴이 하나면 전 범위가 그 바이옴이다. 여러 개면 크기와 가중치를 함께 사용하며,
목록 순서에 암묵적으로 의존하지 않는다.

## 생성과 갱신

- 바이옴은 `BiomeSource`에서 계산한다.
- 벽과 관문은 청크 생성 중 결정적으로 생성한다.
- 구조물 템플릿은 장식에만 사용하고 충돌 판정은 경계 프로필을 기준으로 한다.
- 이미 생성된 청크에 경계를 바꾸는 자동 덮어쓰기는 하지 않는다.
- 경계나 마을 크기가 바뀌면 생성 버전을 올리고 새 테스트 월드를 만든다.

## 검증 항목

- 마을 중심에서 네 방향으로 이동하며 허용되지 않은 바이옴이 없는지 검사
- 네 모서리와 청크 경계에서 벽이 끊기지 않는지 검사
- 관문 폭 전체가 열리고 그 외 구간은 닫히는지 검사
- 벽 아래, 위, 코러스·포탈·탑승 이동 우회 정책 검사
- 서로 다른 마을의 영향 범위가 겹치면 빌드 단계에서 오류 처리

## 완료 기준

- 마을 JSON만 바꿔 내부·주변 바이옴과 벽 외장을 변경할 수 있다.
- 관문 이외의 경계가 전 구간 물리적으로 닫혀 있다.
- 경계 청크를 다시 불러와도 중복 벽이나 빈 틈이 생기지 않는다.
- 경계 밖 미할당 영역이 마을 바이옴으로 무한히 이어지지 않는다.

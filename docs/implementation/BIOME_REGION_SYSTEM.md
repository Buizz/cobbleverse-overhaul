# 바이옴 카탈로그와 지역 생성 체계

## 목적

마을마다 지정한 바이옴만 정해진 범위에 생성하고, 지역·마을 JSON이 실제 월드 생성의 단일 기준이 되게 한다. 현재 차원 전체를 `starter_plains` 하나로 채우는 임시 구현을 교체한다.

- 관련 기능: `WORLD-BIOME-01`, `WORLD-REGION-01`
- 결정: `DEC-WORLD-014` — 세대 차원은 데이터 기반 지역 계획과 전용 `BiomeSource`를 사용한다.
- 상태: 구현 예정

## 데이터 책임 분리

| 데이터 | 책임 |
|---|---|
| 바이옴 카탈로그 | 바이옴 ID, 실제 Minecraft 바이옴, 지표·장식·날씨·스폰 프로필 |
| 지역 JSON | 지역의 절대 경계, 허용 바이옴, 이웃 지역과 관문 |
| 마을 JSON | 마을 중심, 마을 내부·주변 바이옴, 벽과 건축 키트 |
| 생성 상태 | 월드별 데이터 버전, 생성 단계, 배치·검증 결과 |

마을 좌표를 Java 코드와 JSON 양쪽에 중복 선언하지 않는다. `region`과 `settlement` JSON을 읽어 계산한 좌표만 사용한다.

## 바이옴 카탈로그 초안

새 파일 `content/catalogs/biomes.json`과 `biomes.schema.json`을 추가한다.

```json
{
  "schema_version": 1,
  "biomes": [
    {
      "id": "cobbleventure:biome_profile/starter_plains",
      "minecraft_biome": "cobbleventure:starter_plains",
      "surface_profile": "cobbleventure:surface/safe_grass",
      "feature_profile": "cobbleventure:features/starter_plains",
      "spawn_profile": "cobbleventure:spawn/starter_plains",
      "tags": ["plains", "starter", "generation_1"]
    }
  ]
}
```

`minecraft_biome`은 시각·날씨·환경 효과를 담당하고, `surface_profile`은 블록 층, `feature_profile`은 나무·꽃·장식, `spawn_profile`은 코블몬 출현 데이터를 담당한다.

## 생성 판정 순서

1. 블록 또는 바이옴 좌표로 현재 지역을 찾는다.
2. 해당 지역에서 마을 영향 범위와 관문 통로를 찾는다.
3. 마을 내부, 주변 완충 지대, 경계 띠, 지역 일반 필드 중 하나로 분류한다.
4. 분류 결과의 바이옴 프로필을 실제 바이옴 Holder로 변환한다.
5. 같은 월드 시드와 콘텐츠 버전에는 항상 같은 결과를 반환한다.

우선순위는 `관문 통로 > 경계 > 마을 내부 > 마을 주변 > 지역 기본 바이옴`이다. 관문이 경계에 막히지 않게 하기 위함이다.

## 구현 구성

- `CobbleventureBiomeSource`: 지역·마을 바이옴 판정
- `CobbleventureChunkGenerator`: 평탄 지형, 안전 기반층, 경계 지형 생성
- `RegionPlanRegistry`: 정규화 JSON을 불변 런타임 모델로 적재
- `RegionLocator`: 좌표에서 지역과 마을을 빠르게 검색
- `WorldGenerationVersion`: 저장 월드와 콘텐츠 버전 불일치 감지

초기에는 사각형 범위를 구현하고, 데이터에는 `rectangle`, `circle`, `polygon` 확장 지점을 둔다. 공간 검색은 지역 수가 적을 때 단순 목록으로 시작하고 필요 시 청크 인덱스로 바꾼다.

## 기존 데이터 정리

현재 시작 마을은 런타임 배치 좌표와 `starter_town.json`의 중심 좌표가 다르다. 첫 구현에서 다음을 함께 처리한다.

- 시작 마을의 중심·경계·앵커를 JSON 기준으로 통일
- 부트스트랩 코드의 `(32, 69, 32)` 같은 하드코딩 제거
- 구조물 배치, 플레이어 시작점, 체육관 입구가 같은 앵커를 참조
- 기존 테스트 월드는 마이그레이션하지 않고 새 생성 버전의 월드로 검증

## 완료 기준

- 바이옴 카탈로그와 모든 참조가 스키마 검증을 통과한다.
- 시작 마을 내부와 주변에서 지정한 바이옴만 관찰된다.
- 동일 시드로 재생성했을 때 경계와 관문 위치가 같다.
- Java 코드에 시작 마을 전용 좌표가 남지 않는다.
- 등록되지 않은 바이옴·지역 참조가 있으면 빌드가 실패한다.

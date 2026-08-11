# 높이 지형과 필드 기술 접근 제한

## 구현 범위

- 일반 주변 지형은 저주파 노이즈로 1~3블록 높낮이를 만든다.
- 마을 중심은 구조물 배치를 위해 평탄하게 유지한다.
- `starter_highlands`는 기본 지표보다 최소 6블록 높고 희귀 출현 비중이 높다.
- `tidehaven_town`은 해수면 Y=69, 해저 Y=65의 바다에 둘러싸인 섬 마을이다.
- `skyreach_town`은 기본 지표보다 8블록 높은 산악 분기 마을이다.
- 고지대는 `rock_climb`, 바다와 섬 마을은 `surf` 해금이 없으면 진입할 수 없다.

## 데이터 계약

```json
{
  "terrain_profile": {
    "base_height_offset": 8,
    "height_variation": 2,
    "noise_scale_blocks": 88
  },
  "access_requirement": "cobbleventure:field_move/rock_climb"
}
```

`surface_style`은 연결 통로에서 `road`, `natural`, `water` 중 하나를 사용한다.
`water` 통로에는 조약돌 길을 만들지 않고 해저부터 해수면까지 물을 채운다.

## 런타임 판정

서버는 전용 차원에 있는 플레이어의 현재 연속 지형 샘플을 매 틱 검사한다.
필드 기술이 없으면 마지막으로 확인된 안전 위치로 되돌리고 필요한 기술을 안내한다.
크리에이티브와 관전 모드는 제작·검증을 위해 제한을 우회한다.

현재 관리 및 퀘스트 연동 명령은 다음과 같다.

```text
/cobbleventure_field_move rock_climb on
/cobbleventure_field_move surf on
/cobbleventure_field_move fly on
/cobbleventure_field_move <move> off
```

기존 `grant <move>`와 `revoke <move>` 형식도 호환을 위해 계속 지원한다.

## 후속 연동

현재 명령과 영속 데이터까지 구현되어 있으며 코블몬 파티 기술 자동 감지는 아직
직접 연결하지 않았다. Cobblemon 1.7.3 API 어댑터에서는 파티 변경, 기술 변경과
포켓몬 탑승 상태를 관찰해 같은 `cobbleventureFieldMove.<move>` 값을 갱신한다.

현재 `fillbiome` 부트스트랩으로 5개 마을, 4개 통로와 62셀을 최초 렌더링한다.
테스트 PC 기준 렌더링에는 약 123초가 걸린다. 싱글플레이 통합 서버에는 watchdog 제한이 없지만 전용
서버에서 이 프로토타입을 시험할 때는 최초 생성 동안 `max-tick-time=-1`이 필요하다.
정식 구현에서는 연속 마스크 판정을 `BiomeSource`/`ChunkGenerator`로 옮겨 청크별로
분산 생성하고 이 제한을 제거한다.

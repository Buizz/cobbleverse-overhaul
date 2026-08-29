# Cobbleventure 오브젝트 모델 작업장

이 폴더는 `src/main/resources` 밖에 있는 외부 오브젝트 작업장입니다. JSON과 텍스처는
참고용 복사본이 아니라 빌드 시 모드 JAR로 복사되는 실제 게임 리소스입니다. 원본
스프라이트와 생성형 컨셉 이미지는 모델 폴더에 함께 보관하지만 JAR에는 넣지 않습니다.

Blockbench 뷰포트에 직접 띄울 참고 이미지는 `reference-images` 폴더에 별도로 정리합니다.
이 폴더 역시 JAR에는 포함되지 않습니다.

`assets/cobbleventure_theme_blocks` 아래를 표준 Minecraft 리소스 구조로 구성했으므로,
Blockbench가 모델 JSON의 네임스페이스 텍스처를 같은 작업장 안에서 찾을 수 있습니다.

## 참고 우선순위

1. `original_*` 원본 스프라이트
2. `in_world_reference.png` 또는 `shape_reference_photo_*`
3. `generated_concept.png` 생성형 보조 초안

생성형 컨셉은 원본을 임의로 해석한 부분이 있으므로 최종 형태의 기준으로 사용하지 않습니다.

## Blockbench에서 수정하기

1. `assets/cobbleventure_theme_blocks/models/block/workshop` 아래에서 수정할 모델을 엽니다.
   침대와 연구 장치는 `.bbmodel`, 그 외 오브젝트는 각 폴더에서 오브젝트 이름과 같은
   단일 `.json` 모델을 사용합니다. 연결형 책장은 연결 상태 표현 때문에 세 JSON으로 유지합니다.
2. Java Block/Item 모델로 불러옵니다.
3. 각 멀티블록 파트의 좌표는 원칙적으로 `0..16` 범위 안에서 편집합니다.
4. 파일 이름과 폴더 위치를 바꾸지 않고 같은 `.bbmodel`에 저장합니다.
5. 저장소 루트의 `apply-object-workshop.bat`을 실행합니다.
6. 게임을 완전히 종료한 뒤 갱신된 JAR가 설치된 모드팩으로 다시 실행합니다.

현재 `large_single_iron_bed.bbmodel`, `sky_view_glow_window.bbmodel`,
`bright_double_glow_window.bbmodel`, `blue_panel_glow_window.bbmodel`은 빌드 직전에
자동 변환됩니다. Blockbench 안에서 모델 또는 내장 텍스처를 수정하고 저장한 뒤 바로 빌드하면
게임용 JSON과 PNG가 갱신됩니다. 외부 이미지 편집기를 사용할 때는 다음 PNG를 수정합니다.

- 침대: `textures/block/bed_single_texture.png`
- 하늘 창문: `textures/block/windows/sky_view_glow_window_texture.png`
- 밝은 이중 창문: `textures/block/windows/bright_double_glow_window_texture.png`
- 파란 패널 창문: `textures/block/windows/blue_panel_glow_window_texture.png`

`.bbmodel`과 외부 PNG를 모두 수정한 경우 마지막으로 저장한 쪽을 텍스처 원본으로
사용합니다. 모델 형상과 UV는 항상 `.bbmodel`에서 가져옵니다.
창문 크기를 바꾸는 도중 BBModel UV 크기와 외부 PNG 크기가 일시적으로 다르면 해당
창문만 자동 변환을 건너뛰고 마지막 정상 게임 리소스를 유지합니다.

Gradle은 자동 동기화를 먼저 실행한 다음 게임용 모델 `.json`과 텍스처 경로의 `.png`만
JAR로 복사합니다. `original_*`, `generated_concept.png`, 실사 참고 이미지는 설계 자료로만
남고 빌드 결과에는 들어가지 않습니다.

02~06번 오브젝트는 분할 편집 파일과 OBJ 중간 산출물을 없앴습니다. 각 다중 블록의
기준 파트 하나가 폴더 안의 단일 JSON 전체를 렌더링하고 나머지 파트는 설치·충돌·철거만
담당하므로, 해당 JSON 하나만 수정하면 게임에도 그대로 반영됩니다.

자동 동기화 대상이 아닌 완성형 단일 텍스처 `.bbmodel`의 빈 공간을 수동으로 줄이려면
`pack-bbmodel-texture.py <모델.bbmodel> --output-java-model <게임모델.json>`을 사용합니다.
원본은 수정하지 않고 `*_packed.bbmodel`, 2의 거듭제곱 크기 PNG와 게임 JSON을 생성하며,
겹치는 UV 영역과 면 회전은 유지합니다. 생성된 패킹본을 Blockbench에서 먼저 확인한 뒤
게임에 적용합니다.

검수가 끝난 패킹본을 최종 편집본과 게임 리소스로 확정할 때는
`finalize-bbmodel.py`를 사용합니다. 최종 `.bbmodel`의 내장 PNG를 외부 텍스처로 추출하고,
Java 블록 모델의 UV 좌표계로 변환한 JSON을 함께 생성합니다.

## 실제 모델 파일

| 폴더 | 실제 사용 모델 |
| --- | --- |
| `01_pokemon_tower_grave` | `pokemon_tower_grave.json` |
| `02_double_display_case` | `double_display_case.json` |
| `03_double_glass_display_counter` | `double_glass_display_counter.json` |
| `04_rocket_base_machine_1` | `rocket_base_machine_1.json` |
| `05_rocket_base_machine_2` | `rocket_base_machine_2.json` |
| `06_rocket_base_machine_3` | `rocket_base_machine_3.json` |
| `07_research_device` | 원본: `research_device.bbmodel`, 자동 생성: `research_device_packed.bbmodel`, 게임: `research_device_1.json` + 단일 패킹 텍스처 |
| `08_white_connecting_bookshelf` | 하얀색 연결형 책장. `*_core.json`, `*_left_end.json`, `*_right_end.json` |
| `09_large_single_iron_bed` | 편집: `large_single_iron_bed.bbmodel`, 게임: `large_single_iron_bed.json` |
| `10_sky_view_glow_window` | 편집 원본: `sky_view_glow_window.bbmodel`, 게임: `sky_view_glow_window.json` |
| `11_bright_double_glow_window` | 편집 원본: `bright_double_glow_window.bbmodel`, 게임: 2칸 자동 설치용 `bright_double_glow_window.json` |
| `12_blue_panel_glow_window` | 편집 원본: `blue_panel_glow_window.bbmodel`, 게임: `blue_panel_glow_window.json` |
| `13_green_connecting_bookshelf` | 초록색 연결형 책장 작업장. 현재 참고 이미지 보관 |
| `14_glass_storage_cabinet` | 유리 수납장 임시 모델과 참고 이미지 |
| `15_narrow_drawer_cabinet` | 좁은 서랍장 임시 모델과 참고 이미지 |

`02_double_display_case/double_display_case.json`은 `textures/block/double_display_case.png`
한 장만 사용합니다. 현재 텍스처는 `128×128`이며 234개 면의 픽셀 일치 검사를 마친
패킹본입니다. Java 모델의 단일 텍스처를 다시 정리할 때는
`pack-java-model-texture.py`를 사용하고, 출력본의 면별 픽셀 일치를 확인한 뒤 원본을
교체합니다.

유리 진열 판매대의 `dobule_glass_counter_blue.png`, `dobule_glass_counter_basic.png`,
`dobule_glass_counter_goods_bottom.png`, `dobule_glass_counter_glass.png`은 각각 대표색으로
전체를 채운 뒤 ±4 RGB 범위의 결정성 있는 16×16 반복 미세 노이즈를 적용합니다. 원본은
`recovery/double_glass_counter/pre-filled-noise-20260829`에 보관하며,
`apply-glass-counter-base-noise.py`를 다시 실행해 같은 결과를 재생성할 수 있습니다.
파란색 텍스처만 참고 이미지의 평균색 `#4576C8`에 맞추고 큰 픽셀 덩어리가 보이도록
16×16 패턴을 8배 확대해 `128×128` 전체에 배치합니다. 파랑은 채널별 변화 폭을 달리해
낮은 대비 안에서도 색 덩어리가 보이게 하며, 나머지 세 텍스처는 1픽셀 단위 패턴을 사용합니다.

## 돌출 벽 타일 작업 목록

다음 블록은 정육면체 텍스처의 일부를 전면으로 돌출시키는 방향성 벽 타일 대상입니다.

- `underground_blue_band` — 적용 완료: 하단 4픽셀을 전면으로 1픽셀 돌출
- `underground_cracked_wall` — 적용 완료: 녹색 꺾임 몰딩을 전면으로 1픽셀 돌출
- `underground_olive_band` — 적용 완료: 어두운 나무결 2줄을 여섯 면 모두 1픽셀 안쪽으로 홈 처리
- `house_mint_band_wall` — 적용 완료: 이미지 10~13행의 짙은 띠를 전면으로 1픽셀 돌출
- `house_blue_band_wall` — 적용 완료: 이미지 10~13행의 짙은 띠를 전면으로 1픽셀 돌출
- `house_beige_panel_wall` — 적용 완료: 어두운 나무결 4줄을 여섯 면 모두 1픽셀 안쪽으로 홈 처리
- `casino_coral_band` — 적용 완료: 하단 4픽셀을 전면으로 1픽셀 돌출
- `casino_sky_chevron_wall` — 적용 완료: 밝은 V자 몰딩을 전면으로 1픽셀 돌출

## 입체 설비 타일

- `rocket_base_olive_vent` — 어두운 벤트 슬롯 3개를 1픽셀 안쪽으로 홈 처리
- `rocket_base_yellow_light_panel` — 밝은 15×15 보호판을 전면으로 1픽셀 돌출

## 발광 창문

창문은 하나의 `.bbmodel` 파일 안에서 창틀 요소를 `window_body`, 발광판을
`luminous_panel`로 구분합니다. 세 창문 모두 편집 원본과 게임 리소스 변환 대상으로
확정되어 있으며, 하늘 풍경 창문과 파란 패널 창문은 1×2블록, 밝은 이중 창문은
2×2블록으로 자동 설치됩니다. 커튼은
모델과 텍스처에서 제외했고 패널에는 Blockbench 확인용 `light_emission: 15`가 설정되어
있습니다. `pack-bbmodel-texture.py`와
`finalize-bbmodel.py`는 발광값이 있는 큐브에만 Minecraft 1.21.1용 NeoForge
`neoforge_data`를 자동으로 추가합니다. 따라서 `light_emission: 0`인 프레임은 자체
발광하지 않습니다. 실제로 주변 블록을 밝히는 광량은 최종 게임 블록을 등록할 때 블록
속성으로 별도 설정해야 합니다.

`create-window-drafts.ps1`은 최초 초안을 다시 만드는 용도입니다. 직접 수정한 `.bbmodel`과
창문 텍스처를 덮어쓰므로 수작업을 시작한 뒤에는 실행하지 않습니다.

## 기본 블록의 미세 질감

`apply-subtle-block-noise.py`는 단색 벽 블록의 기준색에만 ±2~4 RGB 단계의 타일형 미세
노이즈를 적용합니다. 띠, 균열과 셰브론 등 다른 색으로 그려진 무늬는 수정하지 않으며,
같은 기준색을 공유하는 블록에는 같은 노이즈 패턴을 사용합니다. 최초 실행 당시 원본은
`recovery/block-textures/pre-subtle-noise-20260829`에 보관됩니다. 스크립트는 항상 이
백업에서 결과를 다시 만들기 때문에 반복 실행해도 노이즈가 누적되지 않습니다.

02~06번 오브젝트와 침대는 각각 기준 블록 하나에서 단일 전체 모델을 직접 렌더링합니다.

침대 재질은 `textures/block/bed_single_texture.png` 한 장만 사용합니다. `64×32` 이미지의
16×16 슬롯은 왼쪽 위부터 흰 금속, 어두운 금속, 흰 천, 파란 이불 순서이며, 왼쪽 아래
첫 슬롯은 초록색 강조 띠입니다. 모델의 각 면은 이 이미지 안의 해당 UV 영역을 봅니다.

## 목표 규격

| 오브젝트 | 목표 크기/동작 |
| --- | --- |
| 포켓몬타워 묘비 | 1블록 장식 |
| 2칸 진열대 | 가로 2 × 높이 2 |
| 2칸 유리 진열 판매대 | 가로 2 × 깊이 1, 일반 상품용 |
| 로켓단 기계 1 | 가로 1 × 높이 2 |
| 로켓단 기계 2 | 가로 1 × 높이 2 |
| 로켓단 기계 3 | 가로 2 × 높이 3 |
| 오박사 연구소 기계 | 바닥 2 × 2 × 높이 2, 중앙은 곧은 원통 |
| 연결형 책장 | 1칸 단독 및 가로 자동 연결 |
| 1인용 철제 침대 | 바닥 2 × 2 |

## 주의 사항

- 유리 진열 판매대의 실사 사진은 낮은 유리 상판 구조만 참고합니다. 아이스크림 냉동고를 만드는 것이 아닙니다.
- 좌우 멀티블록의 중앙에는 외곽 프레임이나 옆면을 중복 배치하지 않습니다.
- 모델에서 사용하는 텍스처는 이 작업장 안의
  `assets/cobbleventure_theme_blocks/textures/block`에 있습니다.
- 모델 파일을 새 이름으로 분리하면 대응하는 `blockstates/*.json` 경로도 수정해야 합니다.

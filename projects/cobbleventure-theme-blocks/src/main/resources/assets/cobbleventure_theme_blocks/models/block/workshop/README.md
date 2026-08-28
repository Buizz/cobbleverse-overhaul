# Cobbleventure 오브젝트 모델 작업장

이 폴더의 JSON은 참고용 복사본이 아니라 게임이 직접 사용하는 실제 블록 모델입니다.
같은 오브젝트 폴더에 원본 스프라이트와 생성형 컨셉 이미지도 함께 있습니다.

## 참고 우선순위

1. `original_*` 원본 스프라이트
2. `in_world_reference.png` 또는 `shape_reference_photo_*`
3. `generated_concept.png` 생성형 보조 초안

생성형 컨셉은 원본을 임의로 해석한 부분이 있으므로 최종 형태의 기준으로 사용하지 않습니다.

## Blockbench에서 수정하기

1. 수정할 오브젝트 폴더에서 실제로 사용되는 `.json`을 Blockbench로 엽니다.
2. Java Block/Item 모델로 불러옵니다.
3. 각 멀티블록 파트의 좌표는 원칙적으로 `0..16` 범위 안에서 편집합니다.
4. 파일 이름과 폴더 위치를 바꾸지 않고 같은 JSON에 저장합니다.
5. 저장소 루트의 `apply-object-workshop.bat`을 실행합니다.
6. 게임을 완전히 종료한 뒤 새 JAR가 설치된 모드팩으로 다시 실행합니다.

리소스 개발용 실행 환경에서는 모델 저장 후 리소스 다시 불러오기를 사용할 수 있지만,
일반 CurseForge 인스턴스는 실행 중인 모드 JAR를 교체할 수 없으므로 재시작이 필요합니다.

## 실제 모델 파일

| 폴더 | 실제 사용 모델 |
| --- | --- |
| `01_pokemon_tower_grave` | `pokemon_tower_grave.json` |
| `02_double_display_case` | `double_display_case_lower*.json`, `double_display_case_upper*.json` |
| `03_double_glass_display_counter` | `double_glass_display_counter_*.json` |
| `04_rocket_base_machine_1` | 배치 모델은 `*_lower.json`, `*_upper.json` |
| `05_rocket_base_machine_2` | 배치 모델은 `*_lower.json`, `*_upper.json` |
| `06_rocket_base_machine_3` | 배치 모델은 `*_lower.json`, `*_middle.json`, `*_upper.json` |
| `07_professor_lab_research_device` | `*_lower_quadrant.json`, `*_upper_quadrant.json`, `*_connector.json` |
| `08_professor_lab_connecting_bookshelf` | `*_core.json`, `*_left_end.json`, `*_right_end.json` |
| `09_large_single_iron_bed` | `large_bed_foot.json`, `middle.json`, `head.json`, 좌우 측면 모델 |

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
| 1인용 철제 침대 | 바닥 2 × 3 |

## 주의 사항

- 유리 진열 판매대의 실사 사진은 낮은 유리 상판 구조만 참고합니다. 아이스크림 냉동고를 만드는 것이 아닙니다.
- 좌우 멀티블록의 중앙에는 외곽 프레임이나 옆면을 중복 배치하지 않습니다.
- 모델에서 사용하는 텍스처는 `assets/cobbleventure_theme_blocks/textures/block`에 있습니다.
- 모델 파일을 새 이름으로 분리하면 대응하는 `blockstates/*.json` 경로도 수정해야 합니다.

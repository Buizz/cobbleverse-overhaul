# 던전 조각 스킨 계약

던전 조각은 논리 형태와 테마 외형을 분리한다. `standard_16` 키트의 모든 스킨은
`16×8×16` 크기, 커넥터 ID·좌표·방향, 마커 슬롯을 동일하게 유지한다. 따라서 같은
배치 계획에서 스킨별 `piece_id`만 대응하는 형태로 교체할 수 있다.

- `cobbleventure:dungeon_kit/standard_16`: 공통 크기와 연결 규격
- `cobbleventure:dungeon_shape/<shape>`: 시작방·통로·계단·보스방 등의 논리 형태
- `cobbleventure:dungeon_theme/<theme>`: 로켓단·아쿠아단·자연동굴 등의 외형
- `cobbleventure:dungeon_pool/<theme>_test`: 런타임이 선택할 테마별 조각 풀

첫 번째 `rocket` 스킨은 테스트용 블록 외형이다. 원본 NBT는
`content/structures/dungeon_pieces/rocket`에 있으며 구조물 빌더의 `dungeon_pieces`
구역에 자동 등록된다. 에딧월드에서 수정할 때 외곽 크기와 커넥터 문 위치를 바꾸지 않고
같은 리소스 ID로 저장한 뒤 일반 구조물 가져오기 절차를 사용한다.

새 테마는 `tools/structure-builder/generate_dungeon_piece_skins.py`의 `SKINS`에 팔레트를
추가해 기본 스킨을 만든 다음 에딧월드에서 외형을 다듬는다. 형태 자체를 추가할 때만
`SHAPES`와 모든 테마 NBT를 함께 갱신한다.

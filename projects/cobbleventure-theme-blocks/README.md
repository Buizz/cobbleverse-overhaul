# Cobbleventure Theme Blocks

지하통로, 포켓몬타워, 로켓단 기지용 16×16 건축 블록을 제공하는 독립 NeoForge 모드입니다.

## 빌드

저장소 루트에서 다음 명령을 실행합니다.

```bat
projects\cobbleventure-battle-ai\gradlew.bat -p projects\cobbleventure-theme-blocks build
```

완성된 JAR는 `build/libs/cobbleventure-theme-blocks-0.11.0.jar`에 생성됩니다. Minecraft
1.21.1과 NeoForge 21.1.248 이상을 사용하는 모드팩의 `mods` 폴더에 JAR를 넣어
활성화하고, 빼서 비활성화할 수 있습니다. 다른 Cobbleventure 모드를 요구하지 않습니다.

블록은 크리에이티브 모드의 `건축 블록` 탭에서 찾을 수 있으며, 현재 조합법은 없습니다.
지하통로, 포켓몬타워, 로켓단 기지, 카지노와 일반 주택 테마를 제공합니다.

`포켓몬타워 보랏빛 묘비`는 한 블록 안에 다섯 개의 직육면체 요소를 조합한 3D
장식 블록입니다. 설치 방향에 따라 네 방향으로 회전하며 충돌 영역도 모델 방향을
따라 회전합니다. 설계 참고본은 `docs/concepts/pokemon_tower_grave_reference.png`에
보관합니다.

`2칸 진열대`는 아이템 하나로 가로 2블록 × 세로 2블록의 네 파트를 함께 설치하는
3D 장식입니다. 설치 방향을 따라 회전하며 어느 파트를 파괴해도 나머지 파트가 함께
철거됩니다. 설계 참고본은 `docs/concepts/double_display_case_reference.png`에 보관합니다.

`2칸 유리 진열 판매대`는 냉동고와 비슷한 낮은 유리 상판 형태만 차용한 일반 상품용
장식 판매대입니다. 가로 2블록 × 세로 1블록이며 아이스크림·냉동 기능·수납 GUI는
포함하지 않습니다. 설계 참고본은
`docs/concepts/double_glass_display_counter_reference.png`에 보관합니다.

`로켓단 기지 기계 1`은 세로형 제어 캐비닛, `로켓단 기지 기계 2`는 낮은 조작대와
후면 모니터가 결합된 콘솔입니다. 둘 다 1블록 안에 들어가는 회전형 장식이며 실제
기능이나 GUI는 없습니다. 설계 참고본은 `docs/concepts/rocket_base_machine_1_reference.png`와
`docs/concepts/rocket_base_machine_2_reference.png`에 보관합니다.

`로켓단 기지 기계 3`은 빨간 표시등이 있는 계단식 원통 장치와 청록색 화면이 있는
조작 콘솔이 연결된 가로 2블록 × 세로 1블록 장식입니다. 아이템 하나로 두 파트가 함께
설치되며 어느 쪽을 철거해도 다른 쪽이 함께 제거됩니다. 기능이나 GUI는 없고 설계
참고본은 `docs/concepts/rocket_base_machine_3_reference.png`에 보관합니다.

`박사 연구소 연구 장치 1`은 빨간 유리 접시형 상판, 파란 발광 띠, 네 개의 짧은
지지대가 결합된 1블록 회전형 장식입니다. 기능이나 GUI는 없으며 설계 참고본은
`docs/concepts/professor_lab_research_device_1_reference.png`에 보관합니다.

`박사 연구소 연결형 책장`은 한 칸만 설치하면 양쪽 끝판이 있는 독립 책장으로 보이고,
같은 방향의 책장을 옆에 붙이면 맞닿는 끝판을 자동으로 숨겨 연속된 책장으로 합쳐집니다.
두 칸뿐 아니라 세 칸 이상도 이어지며 설계 참고본은
`docs/concepts/professor_lab_connecting_bookshelf_reference.png`에 보관합니다.

`build.bat mod-theme-blocks`로 빌드하면 완성된 JAR가 다음 환경에 함께 설치됩니다.

- 실제 플레이용 개발 팩
- Cobbleventure Structure Builder 편집 팩
- Cobbleventure Live NBT Editor 편집 팩

`build.bat pack`, `builder-world`, `builder-sync`, `live-editor-world`,
`live-editor-sync`, `mod-bootstrap`도 테마 블록을 먼저 빌드하도록 연결되어 있습니다.
따라서 편집 월드에서 저장한 블록 ID를 플레이 월드에서도 동일하게 불러올 수 있습니다.

# Cobbleventure Structure Builder

메인 개발팩과 분리된 건축 NBT 편집용 NeoForge 모드다. `content/structures`의 원본을
독립 평지 월드에 배치하고, 편집된 부지를 월드의 `generated` 폴더에 Structure NBT로
저장한다.

## 패키지 생성

```bat
build.bat builder-world
```

생성된 `dist/cobbleventure-structure-builder-0.1.0-curseforge.zip`을 CurseForge에
임포트하고 `Cobbleventure Structure Builder` 월드를 연다.

## 명령

```mcfunction
/cobbleventure_builder status
/cobbleventure_builder tp <파일 이름 또는 리소스 ID>
/cobbleventure_builder save <파일 이름 또는 리소스 ID>
/cobbleventure_builder save all
/cobbleventure_builder load confirm
```

## 출입구 편집 막대기

접속 시 지급되는 막대기로 문과 블록을 클릭한다.

- 웅크리기 + 허공 우클릭: 모드 전환
- 실제 문 우클릭: 우클릭 이동용 `door` 앵커 편집
- 배리어 우클릭: 연결된 배리어 전체를 접촉 이동용 `transition` 앵커로 편집
- 일반 블록 우클릭: NPC 위치 등 선택한 앵커 종류 적용
- 웅크리기 + 대상 좌클릭: 지정 해제

`door`는 플레이어가 서 있는 문 쪽의 인접 칸을, `transition`은 편집 시 플레이어가
서 있던 칸을 안전 도착 위치로 자동 기록한다. 두 종류 모두 웹의 건물 출입구 연결에서
같은 `door_routes`로 연결하므로 지하통로뿐 아니라 일반 건물에도 재사용할 수 있다.
WorldEdit 선택
도구와 충돌하므로 나무도끼는 사용하지 않는다. `save`는 NBT와 출입구
`.structure.json`을 함께 내보낸다.

## 굴착 공기 마커

에딧월드 접속 시 `cobbleventure_bootstrap:excavation_marker` 64개를 지급한다.
이 마커가 하나라도 들어간 NBT는 일반 `air`로 기존 지형을 지우지 않는다. 실제 월드
배치 시 마커 위치만 공기로 치환되므로, 흙이나 산을 파낼 계단실·통로 공간을 마커로
채운다. 베리어는 굴착 용도로 사용하지 않고 `transition` 접촉 이동 영역으로 남긴다.

## 엔티티 내보내기

구조 저장은 일반 몹·NPC·아이템 엔티티를 제외한다. 다음 장식·설비 엔티티만 저장
범위 안에서 NBT의 `entities` 목록으로 별도 보존하고, 저장 전후 개수를 검증한다.

- Create 엘리베이터 객실과 Super Glue
- `playingcards` 네임스페이스의 카드, 카드 덱, 포커 칩 엔티티

NPC 링커 표시는 엔티티가 아니라 `.structure.json` 앵커로 저장된다.

```mcfunction
/cobbleventure_builder interior create <id> <폭> <깊이> <층높이> <층수>
/cobbleventure_builder interior list
/cobbleventure_builder interior tp <id>
/cobbleventure_builder interior save <id>
/cobbleventure_builder tool npc <NPC 위치 라벨>
/cobbleventure_builder anchor list
/cobbleventure_builder anchor show
```

## 지하통로 조각 NBT 편집

지하통로는 재사용 가능한 직선·코너·교차로·방 조각을 직접 건축한 뒤 웹에서 조립한다.

```mcfunction
/cobbleventure_builder underground module create <id> <폭> <높이> <깊이>
/cobbleventure_builder underground module list
/cobbleventure_builder underground module tp <id>
/cobbleventure_builder underground connector <커넥터_태그> [facing|north|east|south|west|up|down]
/cobbleventure_builder underground module save <id>
```

다른 조각과 맞물릴 블록 위에 서서 조각 바깥쪽을 바라본 뒤 `underground connector`를
실행한다. 기존 바닥은 직소의 `final_state`로 보존되고, 직소 이름은
`cobbleventure:underground_connector/<태그>`로 저장된다. 커넥터가 하나 이상
있어야 저장 및 가져오기가 가능하다. `builder-import`는 결과를
`content/structures/underground_road_modules/<id>.nbt`로 가져온다.
지상 입출구로 사용할 계단 꼭대기는 방향을 `up`으로 지정한다. 웹에서는 연결되지
않은 `up` 커넥터를 연결 가능 지점으로 표시한다. 지상 입구의 이동 영역과 실제
`조각 ID/커넥터 태그` 연결은 지하통로 문서가 아니라 월드맵 입구 배치에서 정한다.

기본 플레이스홀더 조각은 `straight_16`, `straight_32`, `corner_16`,
`t_junction`, `cross_junction`, `dead_end`, `entrance_room`, `small_room`,
`large_room`, `stairs_up`, `stairs_down`이다. 심층암 통로 지형과 직소가 들어 있어
즉시 조립할 수 있으며, 아래 명령으로 같은 NBT를 다시 만들 수 있다.

```bat
py -3 tools\structure-builder\generate_underground_road_modules.py
```

지상 출입구는 `underground_entrance/underground_passage.nbt` 건물을 사용한다.
크기는 24×16×20이며, 정문의 `cobbleventure:road_anchor` 직소가 길 높이와 방향을
정하는 배치 기준이다. 건물 내부 하행 계단의 연결된 배리어 벽은
`underground_entry` transition 앵커이며, 플레이어가 닿으면 실제 이동이 발동한다.

동굴 입구 5종도 같은 규칙을 사용한다. 테이퍼 통로 내부는 굴착 공기 마커로 저장되고,
`cave_entry`로 지정한 연결 베리어에 닿을 때 동굴 차원으로 이동한다.

```bat
py -3 tools\structure-builder\generate_underground_entrance.py
```

## 저장소로 가져오기

```bat
build.bat builder-import "<CurseForge 인스턴스>\saves\Cobbleventure Structure Builder"
```

상세 규격과 안전 규칙은
[독립 건축 구조물 제작 월드](../../docs/implementation/STRUCTURE_BUILDER_WORLD.md)를 따른다.

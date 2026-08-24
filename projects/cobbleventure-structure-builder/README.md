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
- 대상 우클릭: entry, exit, teleport, spawn, npc, interaction, patrol 또는 inspect 적용
- 웅크리기 + 대상 좌클릭: 지정 해제

플레이어가 서 있는 문 쪽의 인접 칸이 도착 위치로 자동 기록된다. WorldEdit 선택
도구와 충돌하므로 나무도끼는 사용하지 않는다. `save`는 NBT와 출입구
`.structure.json`을 함께 내보낸다.

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
않은 `up` 커넥터만 외부 포트로 선택할 수 있다.

기본 플레이스홀더 조각은 `straight_16`, `straight_32`, `corner_16`,
`t_junction`, `cross_junction`, `dead_end`, `entrance_room`, `small_room`,
`large_room`, `stairs_up`, `stairs_down`이다. 심층암 통로 지형과 직소가 들어 있어
즉시 조립할 수 있으며, 아래 명령으로 같은 NBT를 다시 만들 수 있다.

```bat
py -3 tools\structure-builder\generate_underground_road_modules.py
```

지상 출입구는 `underground_entrance/underground_passage.nbt` 건물을 사용한다.
크기는 24×16×20이며, 정문의 `cobbleventure:road_anchor` 직소가 길 높이와 방향을
정하는 배치 기준이다. 건물 내부 하행 계단의
`cobbleventure:underground_entry` 직소가 실제 지하 이동 감지 위치다.

```bat
py -3 tools\structure-builder\generate_underground_entrance.py
```

## 저장소로 가져오기

```bat
build.bat builder-import "<CurseForge 인스턴스>\saves\Cobbleventure Structure Builder"
```

상세 규격과 안전 규칙은
[독립 건축 구조물 제작 월드](../../docs/implementation/STRUCTURE_BUILDER_WORLD.md)를 따른다.

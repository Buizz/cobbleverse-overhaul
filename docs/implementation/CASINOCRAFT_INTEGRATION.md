# Cobblemon Casino 카지노 연동

## 현재 단계

`Cobblemon Casino` 2.0.0을 게임·경제 구현으로, `Playing Cards & Chips` 2.0.1을
블랙잭 테이블 외형으로 채택해 Lock과 개발팩에 등록했다. 기존 `CasinoCraft`는
Minecraft 1.21.1 NeoForge 배포본이 없어 후보에서 제외한다.

| 항목 | 확인 내용 |
|------|-----------|
| 대상 게임 | Minecraft 1.21.1 |
| 로더 | NeoForge 21.1.200 이상 |
| 후보 파일 | `cobblemoncasino-neoforge-2.0.0.jar` |
| CurseForge | 프로젝트 `1572769`, 파일 `8235485` |
| 라이선스 | MIT |
| 필수 의존성 | Cobblemon, Cloth Config API |
| 선택 연동 | CobbleDollars, Pokeblocks |
| 테이블 외형 | Playing Cards & Chips 2.0.1 / CF `1162591:7219926` |
| 현재 상태 | Lock·개발팩·런타임 외형 치환 등록 완료, 게임 내 상호작용 검증 대기 |

관리 웹의 기본 시설 목록에 `casino`를 제공한다. 선택하면 다음 계약으로 마을
레이아웃에 48×48×20 부지를 예약한다.

```json
{
  "id": "facility_casino_1",
  "facility_type": "casino",
  "mode": "direct_template",
  "structure": "cobbleventure:placeholder/casino",
  "anchor": "facility_casino_1",
  "label": "카지노",
  "footprint": {
    "width": 48,
    "depth": 48,
    "height": 20
  },
  "clearance": 2
}
```

빌드 시 `content/structures/placeholder/casino.nbt`가 있으면 이를 사용하고, 아직
없으면 바닐라 블록으로 된 교체용 카지노 외피를 자동 생성한다. 따라서 Cobblemon Casino
의존성이 없어도 데이터와 마을 배치 기능을 먼저 테스트할 수 있다.

## Cobblemon Casino 적용 절차

1. Cobblemon Casino 2.0.0과 Cloth Config API가 개발팩에 함께 설치되는지 확인한다.
2. 서바이벌 보호 차원에서 슬롯머신·블랙잭·가챠·칩 교환대의 우클릭 메뉴가 정상적으로
   열리고, 블록 파괴·설치는 계속 차단되는지 확인한다.
3. CobbleDollars 연동 사용 여부와 카지노 칩의 발행·교환·회수 정책을 결정한다.
4. 편집 월드에서는 Cobblemon Casino 블랙잭 블록으로 카지노 내부를 제작해 구조물로 저장한다.
5. 구조물 사이드카의 `runtime_replacements`에 같은 Y 높이의 `min`·`max` 영역을 기록한다.
   현재 `interiors/casino`의 2×3 영역은 `[16,1,3]`부터 `[17,1,5]`까지다.
6. 저장한 NBT를 `content/structures/placeholder/casino.nbt`에 둔다.
7. `build.bat mod-bootstrap`과 테스트팩 빌드를 실행해 플레이스홀더가 실제 카지노
   NBT로 교체되는지 확인한다.

실제 배치 시 지정 영역은 `playingcards:poker_table`로 치환되고, 원본 블랙잭 블록
한 개는 `backend_depth`만큼 아래에 숨겨진다. 외형 블록 우클릭은 반경 8블록 안의
가장 가까운 원본 블록에 전달되므로 Cobblemon Casino의 메뉴·잠금·게임 로직을 그대로
사용한다. 사이드카에 영역이 없거나 영역 안에 원본 블록이 없으면 치환하지 않는다.

실제 구조물 제작 전에는 Cobblemon Casino 블록 ID와 모드 ID `cobblemoncasino`을
개발 인스턴스에서 확인한다. 모드가 빠진 팩에서 해당 블록이 유실되는 문제, 가챠
보상의 경제 영향과 서버 권위의 당첨 판정도 함께 검증한다.

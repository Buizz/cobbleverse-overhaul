# CasinoCraft 카지노 연동

## 현재 단계

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
없으면 바닐라 블록으로 된 교체용 카지노 외피를 자동 생성한다. 따라서 CasinoCraft
의존성이 없어도 데이터와 마을 배치 기능을 먼저 테스트할 수 있다.

## CasinoCraft 적용 절차

1. Minecraft 1.21.1과 NeoForge 21.1 계열에서 실제로 동작하는 CasinoCraft 파일과
   필수 의존성을 확인한다.
2. 확인된 버전, 배포 출처, 파일 ID와 라이선스를 `pack/dependencies.lock.json` 및
   `docs/MOD_DEPENDENCIES.md`에 함께 기록한다.
3. CasinoCraft 블록으로 카지노 내부를 제작해 구조물 블록으로 저장한다.
4. 저장한 NBT를 `content/structures/placeholder/casino.nbt`에 둔다.
5. `build.bat mod-bootstrap`과 테스트팩 빌드를 실행해 플레이스홀더가 실제 카지노
   NBT로 교체되는지 확인한다.

CasinoCraft 버전이 확정되기 전에는 임의의 모드 파일이나 블록 ID를 JSON과 NBT에
기록하지 않는다. 모드가 빠진 팩에서 해당 블록이 유실되는 문제도 이 단계에서 함께
검증한다.

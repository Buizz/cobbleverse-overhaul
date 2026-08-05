# Cobbleventure World Bootstrap

정식 지역 플래너가 구현되기 전에 평원 시작 지점과 시작 마을 배치를 검증하는
NeoForge Java 모드다.

## 현재 동작

- 새 월드에 첫 플레이어가 들어오면 기존 월드 스폰 반경 8,192블록에서
  `minecraft:plains` 또는 `minecraft:sunflower_plains`를 찾는다.
- 찾은 평원의 지표면을 월드 스폰으로 확정하고 첫 플레이어를 그곳으로 옮긴다.
- 새 스폰 기준 X/Z 각각 `+32` 블록 떨어진 지표면에
  `cobbleventure:starter_town/village`를 배치한다.
- 자체 체육관을 시작 조각으로 확정하고 네 방향의 BCA 도로 풀에서 주택과
  마을 시설을 확장하므로 체육관은 무작위로 누락되지 않는다.
- 오버월드 SavedData `cobbleventure_world_bootstrap.dat`에 스폰·마을 좌표와
  성공 여부를 기록해
  재접속이나 서버 재시작으로 마을이 중복 생성되지 않게 한다.
- 평원 탐색 또는 마을 배치에 실패하면 완료 상태를 저장하지 않고 채팅으로
  원인을 알린다. 다음 재접속 때 다시 시도한다.

체육관 NBT는 `tools/mod-builder/starter_gym.py`에서 생성한다. 모든 체육관은
같은 소형 외관과 포켓볼 표식을 사용하고, `starter_town.json`의 `gym_theme`에
따라 지붕과 입구 카펫 색만 바뀐다. 시작 체육관은 바위 타입이므로 회색 지붕을
사용한다. 내부는 작은 로비만 두며 입구 오프셋 `(12, 1, 4)`를 향후 공용
인스턴스 차원의 관장별 실내로 연결한다.

이 모듈은 프로토타입이다. 기존 월드에는 이미 생성한 마을과 충돌할 수 있으므로
평원 시작 동작은 새 테스트 월드에서 확인한다. 이후에는
`content/settlements`의 좌표·시설 설정을 읽는 정식 월드 초기화 상태 머신으로
확장한다.

## 빌드

```bat
build.bat mod-bootstrap
```

명령은 체육관 NBT를 생성한 뒤 NeoForge Java 소스를 컴파일하고, 생성된 JAR을
CurseForge 개발 팩의 `overrides/mods`에 복사한다. 일반 `build.bat pack`도
같은 작업을 먼저 수행한다.

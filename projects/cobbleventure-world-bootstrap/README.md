# Cobbleventure World Bootstrap

정식 지역 플래너가 구현되기 전에 전용 시작 바이옴·세대 차원과 시작 마을
배치를 검증하는 NeoForge Java 모드다.

## 현재 동작

- `cobbleventure:generation_1` 전용 차원을 생성한다.
- 차원 전체를 `cobbleventure:starter_plains` 바이옴으로 생성한다. 이 바이옴은
  온도·강수·하늘·물·잔디·나뭇잎 색, 표면 식생과 스폰 목록을 독립 JSON으로
  관리한다.
- 지형은 Y=0~63의 공기층 위에 Y=64 기반암, 흙 3블록과 잔디 1블록만
  생성한다. 기반암 아래는 빈 공간이므로 채굴 가능한 지하가 없고
  동굴·협곡·광맥·지하 호수·던전도 생성하지 않는다.
- 최초 입장 플레이어를 전용 차원의 `(0, 69, 0)` 부근으로 옮기고 해당 위치를
  리스폰 지점으로 지정한다.
- 새 스폰 기준 X/Z 각각 `+32` 블록 떨어진 지표면에
  `cobbleventure:starter_town/village`를 배치한다.
- 자체 체육관을 시작 조각으로 확정하고 네 방향의 BCA 도로 풀에서 주택과
  마을 시설을 확장하므로 체육관은 무작위로 누락되지 않는다.
- 오버월드 SavedData `cobbleventure_world_bootstrap.dat`에 스폰·마을 좌표와
  성공 여부를 기록해
  재접속이나 서버 재시작으로 마을이 중복 생성되지 않게 한다.
- 전용 차원 로딩 또는 마을 배치에 실패하면 완료 상태를 저장하지 않고 채팅으로
  원인을 알린다. 다음 재접속 때 다시 시도한다.

체육관 NBT는 `tools/mod-builder/starter_gym.py`에서 생성한다. 모든 체육관은
같은 소형 외관과 포켓볼 표식을 사용하고, `starter_town.json`의 `gym_theme`에
따라 지붕과 입구 카펫 색만 바뀐다. 시작 체육관은 바위 타입이므로 회색 지붕을
사용한다. 내부는 작은 로비만 두며 입구 오프셋 `(12, 1, 4)`를 향후 공용
인스턴스 차원의 관장별 실내로 연결한다.

이 모듈은 프로토타입이다. 월드 생성 레지스트리가 달라졌으므로 반드시 새 테스트
월드에서 확인한다. 현재는 평평한 단일 시작 지역이며, 이후에는 커스텀
`BiomeSource`와 지역 플래너를 연결해 지하 없이 여러 지표 바이옴·경계·통로를
결정론적으로 생성한다.

## 조정 가능한 월드 데이터

| 파일 | 역할 |
|------|------|
| `data/cobbleventure/worldgen/biome/starter_plains.json` | 시작 바이옴의 색·날씨·식생·몹 |
| `data/cobbleventure/dimension/generation_1.json` | 지표 블록 층과 바이옴, 호수·구조물 생성 여부 |
| `data/cobbleventure/dimension_type/generation_world.json` | 세대 차원의 높이·채광·침대·시간 성격 |

## 빌드

```bat
build.bat mod-bootstrap
```

명령은 체육관 NBT를 생성한 뒤 NeoForge Java 소스를 컴파일하고, 생성된 JAR을
CurseForge 개발 팩의 `overrides/mods`에 복사한다. 일반 `build.bat pack`도
같은 작업을 먼저 수행한다.

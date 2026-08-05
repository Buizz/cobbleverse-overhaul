# Cobbleventure World Bootstrap

정식 지역 플래너가 구현되기 전에 시작 마을 배치를 검증하는 NeoForge 데이터
모드다. Java 진입점 없이 `lowcodefml`과 내장 서버 데이터만 사용한다.

## 현재 동작

- 새 월드에서 첫 플레이어를 감지하고 주변 청크가 준비되도록 5초 기다린다.
- 예약 시점에 오버월드 플레이어가 없으면 다음 입장에서 다시 예약한다.
- 플레이어 기준 X/Z 각각 `+32` 블록 떨어진 지표면에
  `cobbleventure:starter_town/village`를 배치한다.
- 자체 체육관을 시작 조각으로 확정하고 네 방향의 BCA 도로 풀에서 주택과
  마을 시설을 확장하므로 체육관은 무작위로 누락되지 않는다.
- 월드 저장소 `cobbleventure_bootstrap:state`에 시도·성공 여부를 기록해
  재접속이나 서버 재시작으로 마을이 중복 생성되지 않게 한다.
- 배치에 실패하면 채팅으로 관리자 재시도 함수를 안내한다.

바이옴이나 지형 조건 때문에 자동 배치가 실패했을 때 적당한 위치로 이동한 뒤
다음 명령을 실행한다.

```mcfunction
/function cobbleventure_bootstrap:retry_starter_town
```

체육관 NBT는 `tools/mod-builder/starter_gym.py`에서 생성한다. 현재는 바닐라
블록으로 만든 배틀 코트형 샘플이며, 실제 건축 NBT가 준비되면 같은 리소스 ID로
교체할 수 있다.

이 모듈은 프로토타입이다. 이후에는 `content/settlements`의 좌표·시설 설정과
정식 월드 초기화 상태 머신을 읽는 Java 기반 NeoForge 어댑터로 교체한다.

## 빌드

```bat
build.bat mod-bootstrap
```

일반 `build.bat pack`도 이 작업을 먼저 수행한 뒤 생성된 JAR을 CurseForge
개발 팩의 `overrides/mods`에 포함한다.

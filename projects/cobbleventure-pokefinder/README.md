# Cobbleventure Pokefinder

CobbleNav 2.3.3 포켓파인더 HUD 위에 Cobbleventure 탐색 마커를 렌더링하는 전용 NeoForge 모듈이다.

현재 구현 범위는 1단계 호환성 골격이다.

- CobbleNav와 독립적인 범용 `RadarMarker` 모델
- 원자적으로 교체되는 클라이언트 마커 스냅샷
- CobbleNav 2.3.3의 패널 크기, 축척, 오프셋과 손 배치를 재현하는 어댑터
- 기존 CobbleNav HUD 이후에 동작하는 `Gui.renderTitle` 후처리 Mixin
- 로컬 범위와 256블록 임시 상한, 가장자리 고정 좌표 변환

## 빌드와 테스트

저장소 루트에서 다음 명령을 사용한다.

```bat
build.bat mod-pokefinder
```

좌표 변환 테스트만 실행하려면 공유 Gradle wrapper를 사용한다.

```bat
projects\cobbleventure-battle-ai\gradlew.bat -p projects\cobbleventure-pokefinder test
```

## 시각 확인용 마커

개발 클라이언트 JVM에 아래 속성을 추가하면 플레이어 기준 동쪽 96블록의 테스트 목표가 레이더 가장자리에 표시된다. 기본값은 비활성화다.

```text
-Dcobbleventure.pokefinder.testMarker=true
```

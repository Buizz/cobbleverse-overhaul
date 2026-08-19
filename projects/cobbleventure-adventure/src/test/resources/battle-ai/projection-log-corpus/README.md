# Projection log corpus

`index.txt`에 등록된 `.battlelog` 파일을 `ProjectionLogCorpusTest`가 모두 읽는다.
각 파일은 누적 Showdown 프로토콜과 그 시점의 KMP 공통 투영 체크포인트를 번갈아 기록한다.

```text
@@log
|turn|1
|switch|p1a: User|User, L50|100/100
|switch|p2a: Target|Target, L50|100/100
@@expect opening
turn=1
p1.hp=100
p1.maxHp=100
p2.hp=100
p2.maxHp=100
```

다음 `@@log` 블록은 이전 로그에 누적된다. 지원되는 기대값은 HP/최대 HP, 설치물 4종,
Yawn·Salt Cure·맹독·수면, 랭크 5종, 기믹 잔여 여부, 등장 특성 표식, 날씨·지형·전역 효과,
양측 지속 조건이다. 지속 효과 값은 `id:turns:persistent` 형식을 사용한다.

운영 서버 원본은 JVM 인수에 아래 속성을 지정해 수집한다.

```text
-Dcobbleventure.ai.projectionLogCaptureDir=<전용 디렉터리 절대 경로>
```

캡처는 기본 비활성이고 전투 ID별 `<uuid>.showdown.log`를 누적 최신본으로 원자 교체한다.
로그에는 플레이어 이름 등 운영 정보가 포함될 수 있으므로 검토·익명화 후 코퍼스로 옮긴다.
관측 로그에 맞춰 기대값을 고치는 방식으로 차이를 숨기지 말고, KMP 전이 또는 플랫폼 관측
어댑터의 원인을 수정한 다음 체크포인트를 추가한다.

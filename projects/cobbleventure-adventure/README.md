# Cobbleventure Adventure

코블벤처의 월드 생성과 분리된 플레이 규칙 모드다.

- 트레이너 전투 상태와 승리 보상
- 패배 비용과 포켓몬센터 귀환
- 지역별 야생 포켓몬 레벨과 전투 날씨
- 필드 기술 해금과 탑승 제한
- CVES Runtime IR 검증과 이벤트 세션·await 실행 코어
- CVES 영구 세션의 안정 ID 재배치와 운영자 audit·안전 승격·명시적 폐기 도구
- CVES `player.name`, `flag()`, `money()`, `level_cap()` 서버 표현식과 멱등 상태 명령 어댑터
- CVES `say`·`narrate` gateway await와 인증된 callback 세션 재개 코어
- CVES 현지화 템플릿·한국어 조사 renderer, 실제 NPC 3D 모델, 페이지 분할과
  건너뛸 수 있는 타이핑 연출을 제공하는 네트워크 대화·선택지 화면
- CVES 중첩 `choice`의 서버 권위 분기, 현지화 선택 화면과 취소·재접속 복구
- 표현 계층 독립 V5 NPC 바인딩 데이터팩과 엔티티 상호작용 세션 시작
- V5 NPC `proximity_enter/exit` 경계 감지와 영구 once·cooldown 실행 제한
- World Bootstrap 인덱스를 사용하는 region·anchor·building·dimension 전이 실행
- flag 변경·아이템 사용 완료·연동 배틀 종료 서버 신호 트리거
- 공통 await token 기반 스타터 룰렛 결과·취소·재접속 복구 어댑터
- 기존 BattleIntro·TBCS를 재사용하는 CVES 트레이너 배틀 승패·포기 await 어댑터
- CVES 상대·절대 위치의 서버 권위 안전 teleport await와 타입 이동 결과
- Player Menu 월드맵의 `map_selection` await 결과를 typed settlement 위치로 저장하고,
  후속 teleport가 같은 `location_ref` 변수를 소비하는 중단·재개 어댑터
- CVES 플레이어·NPC 상대 걷기의 충돌·낙하·시간 초과와 client 입력 잠금
- CVES `face`와 공통 fade·wait·sound·particle presentation await
- World Bootstrap의 변환 완료 settlement와 명시적 event anchor를 사용하는 CVES 위치 provider 경계
- Cave·Forest 입구와 수동 공간 앵커를 사용하는 CVES `enter_space` await
월드 모드는 `AdventureWorldContext`를 등록해 지역별 권장 레벨과 날씨를 제공한다.

강화 전투 AI는 Adventure에 포함되지 않으며 독립
[`cobbleventure_battle_ai`](../cobbleventure-battle-ai/README.md) 모드가 제공한다. Adventure의
개발 런타임에는 복합 Gradle 빌드를 통해 해당 모드가 연결되지만, 양쪽 JAR는 독립적으로 배포된다.

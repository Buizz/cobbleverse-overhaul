# V5 트레이너 더블배틀

## 참가 인원과 빈 출전 칸

트레이너 더블배틀은 플레이어가 싸울 수 있는 포켓몬을 한 마리만 가지고 있어도
1대2로 진행한다. 두 번째 출전 칸은 비어 있으며 가짜 포켓몬을 파티·PC에 추가하지 않는다.
0마리는 허용하지 않는다. 싱글배틀, PvP, 야생 배틀, 협동 멀티배틀의 검증은 변경하지 않는다.

`TrainerDoublesValidationMixin`은 진행 중인 CVES 요청과 격리된 트레이너 프록시가 있는
1인/2슬롯 포맷에서만 플레이어의 `InsufficientPokemonError`를 제거한다.
다른 참가 조건 오류와 상대 팀의 인원 검증은 그대로 유지한다.

Cobblemon 1.7.3에 포함된 Showdown은 빈 `side.active`의 null 처리에 누락이 있다.
`ShowdownEmptySlotsMixin`은 엔진 압축 해제 직후, JS 엔진 로딩 전에
`showdown-empty-slots.json`의 최소 null 검사 수정을 생성 캐시에 적용한다.
파티 데이터와 원본 모드 JAR는 변경하지 않는다. 수정은 멱등이며 일치하지 않는
엔진 코드에는 임의 적용하지 않고 명시적인 호환 오류를 낸다.
외부 Socket Showdown 서버는 이 로컬 캐시 패치의 대상이 아니다.

시작 시 파티에 없는 슬롯은 `switchIn`을 호출하지 않는다. 엔진 내부의 빈 슬롯은
null로 유지하지만 `sideupdate`의 요청 JSON을 보낼 때만 `active` 배열의 빈 기술 목록을
`{"moves":[]}`로 바꾼다. Cobblemon 1.7.3의 `ShowdownActionRequest.sanitize`와
패킷 코덱은 null 기술 목록을 지원하지 않기 때문이다. 배열의 길이와 인덱스는 유지하고,
클라이언트는 실제 포켓몬이 없는 슬롯에 기존 `PassActionResponse`를 사용한다.
`wait`, `forceSwitch`, 실제 포켓몬의 기술·기믹 정보와 엔진 내부 요청은 변경하지 않는다.

실제 추출된 코블몬 엔진 검증:

```powershell
node projects/cobbleventure-adventure/src/test/js/underfilled-doubles.cjs
```

다른 추출 디렉터리는 `COBBLEVENTURE_SHOWDOWN_PATH`로 지정한다.
검증은 JS 로더에서 같은 패치 규칙을 적용하며 실행 중인 게임의 캐시 파일을 수정하지 않는다.
JS 검증은 요청 JSON 수신과 명시적 `move ..., pass` 응답, 1대2 승패·일반 2대2 종료를
포함한다. Java의 `ShowdownEmptySlotRequestTest`는 원래 null 오류를 재현하고 빈 기술
목록이 실제 Cobblemon 패킷 코덱을 왕복하는지 검증한다. 이는 인게임 화면 조작 검증과는 별개다.

## 두 NPC 배치와 단일 이벤트

NPC의 기존 `npc.double_battle` 설정을 배포용 NPC 배치 프로필의 `runtime.double_battle`로
전달한다. 대표 NPC를 배치할 때 파트너도 시선 기준 옆 2블록에 배치한다.
한쪽이 막혀 있으면 반대편을 시도하며, 안전한 자리가 없으면 겹쳐 놓지 않고 배치 실패를 기록한다.
기존 대표 NPC가 있더라도 파트너 배치를 확인하므로 지역 NPC 재배치 시 누락을 보완한다.

파트너는 `cves_partner_owner/<대표 UUID>` 태그로 같은 배치의 대표에게 연결한다.
파트너 클릭도 대표 NPC 세션으로 전달하며 근접 발동은 대표만 수행한다.
대표가 로드되지 않았으면 파트너가 독자적인 배틀·보상을 실행하지 않는다.
기존 플레이어별 세션·승리 플래그·상금 처리 경로를 사용한다.

레이·타이라는 기존 커플 ID를 대표 타이라로 유지하고 남성 외형 레이를 파트너로 지정한다.
두 바인딩은 기존 하나의 CVES를 참조한다. 파트너는 독립 자동 배치 후보에서 제외한다.
웹 콘텐츠 재생성 및 World Bootstrap·Adventure JAR를 함께 갱신해야 적용된다.

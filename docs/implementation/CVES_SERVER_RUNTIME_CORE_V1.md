# CVES 서버 런타임 코어 V1

> 상태: `cobbleventure-adventure` Java 코어와 NeoForge 저장·reload 연결 구현
>
> 범위: Runtime IR 로딩, 세션 상태, 표현식·제어 흐름, await 계약과 명령 어댑터 경계

이 코어는 [CVES Runtime IR V1](CVES_RUNTIME_IR_V1.md)을 서버에서 소비하기 위한
첫 계층이다. 실행 상태 코어는 Minecraft 엔티티, 인벤토리와 화면 API를 직접 참조하지
않는다. 서버 연결 계층은 데이터팩 스크립트 snapshot과 세션 NBT 저장만 담당하며,
실제 대화·보상 구현은 명령 어댑터 뒤에 남긴다.

## 구성

- `EventScriptLoader`: IR 버전, script ID, digest, 연속 주소, 제어 흐름 대상,
  instruction ID, operation ID와 source map 대응을 검사한다.
- `EventScriptRepository`: `data/<namespace>/event_script/<path>.json`을 reload하고
  내부 `<namespace>:event_script/<path>` ID와 경로 일치를 확인한다. 모든 문서를 먼저
  검증한 뒤 immutable snapshot을 한 번에 교체한다.
- `EventNpcBindingRepository`: `data/<namespace>/npc_event_binding/<path>.json`을
  reload하고 일반 엔티티 태그 `cves_binding/<namespace>/<path>`를 script ID에
  연결한다. 이 계약은 EasyNPC의 SNBT·event 구조를 참조하지 않는다.
- `EventNpcInteractionHandler`: V5 바인딩 태그가 붙은 엔티티의 서버 주손 상호작용을
  소유하고, 유일한 `interact` 이벤트와 선언 range를 검증한 뒤 저장 세션을 시작한다.
- `EventSession`: 프로그램 카운터, 지역 변수, 호출 스택, 대기 작업, 완료 작업과
  소비한 callback token을 보관하고 JSON으로 왕복한다.
- `EventSessionStore`: 동일한 플레이어·NPC·스크립트·트리거 세션을 하나만 유지하는
  저장소 경계다.
- `SavedEventSessionStore`: 오버월드 `cobbleventure_event_sessions` SavedData에 세션
  JSON을 NBT로 저장한다. 잘못됐거나 지원하지 않는 저장 버전은 실행하지 않고 격리한다.
- `EventCommandAdapter`: 대화, 보상, 배틀, UI와 이동 구현이 공통으로 반환할
  `Completed`, `Waiting`, `Failed`, `Cancelled` 결과 계약이다.
- `EventExecution`: 어댑터 명령에 일회성 작업 건너뛰기와 즉시 완료 또는 await 전이를
  동일하게 적용한다.
- `EventExpressionEvaluator`: `literal`, `name`, `member`, 내장 함수 호출과 단항·이항
  연산자를 임의 코드 실행 없이 평가한다. `EventExpressionEnvironment`가 플레이어
  상태와 `flag`, `money`, `level_cap` 등의 서버 함수 경계를 제공한다.
- `EventInterpreter`: 위에서 아래로 첫 참인 page를 선택하고 `branch`, `choice`,
  `repeat`, `jump`, `call`, `return`을 실행하며 await 또는 종료까지 진행한다.
  호출자가 지정한 step budget에 도달하면 `STEP_LIMIT`으로 제어를 돌려준다.
- `EventStateExpressionEnvironment`: `player.name`, `flag()`, `money()`, `level_cap()`을
  타입을 검증하여 제공한다. Runtime IR `int`를 넘는 잔액은 잘라내지 않고
  실행 오류로 보고한다.
- `ServerPlayerEventState`: 실제 `ServerPlayer`를 플래그 scoreboard, CobbleDollars,
  progression persistent data에 연결하는 저장 어댑터다.
- `StateEventCommandAdapter`: `set_flag`, `set_player_variable`, `unlock_feature`,
  `set_level_cap`을 표현식 평가 후 즉시 실행하고 나머지 명령을 다음
  어댑터로 위임한다.
- `DialogueEventCommandAdapter`: `say`, `narrate`의 텍스트 IR과 지역 변수
  snapshot을 `EventDialogueGateway`에 전달하고 opaque token을 받아 공통 await
  상태로 전이한다.
- `ChoiceEventCommandAdapter`: `choice`의 prompt와 순서가 고정된 option text를
  `EventChoiceGateway`에 전달한다. 클라이언트는 0-based index만 반환하고 실제 분기
  target은 저장된 서버 세션에서 결정한다.
- `StarterRouletteEventCommandAdapter`: `starter_roulette`를 Player Menu가 소유한
  Cobblemon 스타터 선택 서비스에 opaque token으로 위임한다. 성공 결과는
  `species_id`, `form`, `level`의 `pokemon_selection` 값으로 저장한다.
- `EventStarterRouletteBridge`: 권한 4의 내부 명령 경계로 Player Menu와 callback을
  교환한다. NPC UUID나 EasyNPC 후속 label은 전달하지 않으며 player와 await token을
  저장 세션에 다시 결합한다.
- `GiveItemEventCommandAdapter`: `give_item`의 item resource ID, 수량과 획득 알림
  flag를 평가하고 Player Menu의 멱등 보상 서비스에 위임한다. 결과는
  `requested_count`, `granted_count`, `remaining_count`의 `item_result`로 저장한다.
- `EventItemGrantBridge`: opaque callback token과 안정적인 operation ID만 Player Menu에
  전달하며, 성공과 가방 부족 결과를 공통 await 재개 계약으로 되돌린다.
- `GiveLootEventCommandAdapter`와 `EventLootGrantBridge`: `give_loot`의 loot table ID와
  추첨 횟수를 Player Menu에 전달하고 실제 생성 아이템 수의 `item_result`로 재개한다.
- `EventBattlePresetRepository`: 데이터 모드의 `data/cobbleventure/battles`를 reload하고
  CVES가 필요한 trainer ID, format, level mode와 battle rule 실행 투영을 원자적으로
  교체한다.
- `BattleEventCommandAdapter`와 `EventBattleBridge`: `battle` resource ID를 기존
  BattleIntro·TBCS 실행 경로에 연결하고 Cobblemon 시작·승리·도주 이벤트를 opaque
  await token의 `battle_result`로 되돌린다.
- `EventLocationRef`와 `TeleportEventCommandAdapter`: `relative`, `position`,
  `anchor`, `settlement`, `route`, `dimension`, `space` 호출을 텍스트 명령으로
  풀지 않고 닫힌 위치 union과 이동 옵션으로 디코딩한다.
- `EventMovementBridge`와 `EventSafeTeleport`: interpreter가 WAITING을 저장한 다음
  서버 tick에 플레이어의 상대·절대 위치를 해석하고, 청크·월드 경계·충돌 공간·
  지지 바닥·fluid·barrier를 검사한 뒤 공통 token으로 재개한다.
- `EventLocationResolverRegistry`: Adventure가 위치 제공자 SPI를 소유하고 World
  Bootstrap이 hex world translation을 마친 settlement snapshot과 생성에 사용한
  cave·forest space snapshot의 실제 앵커 좌표를 등록한다. 원본 좌표를 별도
  repository에서 중복 해석하지 않는다.
- `EventAwaitCompletionService`: 콜백의 인증된 player ID, session key, token,
  script digest를 검증하고 재개 상태를 저장한 뒤 다음 await 또는 종료까지
  실행기를 계속 돌린다.
- `EventTextRenderer`: localized text의 언어 선택과 `${path|filter}`를 임의
  표현식 실행 없이 처리한다. `name`, `number`, `fallback`, 한국어
  `josa`를 지원한다.
- `EventDialogueNetwork`: text IR·변수 snapshot을 clientbound payload로 보내고,
  완료·취소 token을 인증된 네트워크 player와 결합한다.
- `EventDialogueScreen`: 라인별 자동 줄바꿈, 발화자와 내레이션 구분, 클릭·
  Enter 완료와 Escape 취소를 제공하는 비일시정지 화면이다.
- `EventChoiceScreen`: prompt와 선택지 목록을 현재 언어로 렌더링하고 마우스,
  방향키·숫자키와 Enter 선택, Escape 취소를 제공한다. 화면을 잃은 재접속 세션은
  다음 NPC 상호작용에서 새 token으로 복구한다.

## 세션과 await 전이

```text
READY ─start→ RUNNING ─dispatch→ WAITING ─callback→ RUNNING
                    │                    ├ failure result → RUNNING
                    │                    └ unhandled failure → FAILED/CANCELLED
                    └ page end → COMPLETED
```

대기 상태는 명령 종류, session token, operation ID, 재개 주소, 결과 변수와 만료 시각을
저장한다. 선택지는 고정 재개 주소 대신 허용된 option target 목록을 저장하고,
콜백의 0-based index를 범위 검증한 후 해당 분기로 재개한다. 성공 callback에서만
operation ID를 완료 저널에 기록한다. 같은 token이 다시
오면 `DUPLICATE`, 현재 대기와 관계없는 token이면 `STALE`을 반환하므로 프로그램
카운터와 보상이 두 번 진행되지 않는다.

영구 세션 schema v2는 현재 프로그램 카운터, 호출 스택, await 재개 주소와 선택지
target 각각에 숫자 주소와 `instruction_id`를 함께 저장한다. 숫자 주소는 같은 digest의
빠른 실행에 사용하고, 안정 ID는 데이터팩 reload로 `source_digest`가 바뀌었을 때 새
주소를 찾는 anchor다. event는 저장된 trigger 이름과 호출자가 제시한 event index를
우선 사용하며, 일치하지 않으면 모든 event 중 정확히 하나만 anchor 전체를 만족해야
한다. 후보가 없거나 여러 개이면 재개하지 않는다.

재배치는 프로그램 카운터와 호출 스택뿐 아니라 await 재개점과 모든 choice target을
한 번에 계산한 후 검증이 끝났을 때만 세션에 반영한다. await 중인 instruction의 명령
종류 또는 operation ID가 바뀌었거나 `$repeat:<instruction_id>` anchor가 사라진 경우도
callback을 소비하지 않고 `SCRIPT_MISMATCH`로 거부한다. 따라서 안정 ID는 명령의 의미를
바꾼 새 작업에 재사용하면 안 된다.

완료·실패·취소된 세션에 다시 상호작용하면 현재 page 조건을 다시 평가해 새 진입점에서
시작한다. 지역 변수, 호출 스택과 소비한 callback token은 초기화하지만 안정적인
operation ID 완료 저널은 보존하므로 같은 NPC와 다시 대화해도 일회성 부작용을
반복하지 않는다. 실행 중이거나 대기 중인 세션은 새로 만들지 않고, digest가 달라졌다면
schema v2 anchor로 먼저 재배치한다. 종료 세션은 현재 page 조건을 기준으로 새 digest의
진입점에서 재시작하되 완료 operation 저널은 보존한다.

schema v1 저장 세션에는 안정 anchor가 없다. 같은 digest로 한 번 로드되면 현재 주소에서
v2 anchor를 채워 저장할 수 있지만, 이미 digest가 달라진 v1 세션은 주소를 추측하지 않고
안전하게 거부한다. 이 제한은 잘못된 대사 분기나 보상 재실행보다 명시적 복구를 택한
호환성 경계다.

permission level 4 운영자는 다음 명령으로 영구 세션을 점검한다.

```text
/cobbleventure_event session audit
/cobbleventure_event session upgrade_safe
/cobbleventure_event session discard <player_uuid> <npc_uuid> <script_id> <trigger> confirm
```

`audit`은 현재 일치, 같은 digest에서 승격 가능, 안정 ID 재배치 가능, 종료 후 재시작
가능, 구형 digest 불일치, 안정 ID 비호환과 script 누락을 구분한다. 진단은 복제한 세션에
재배치를 시험하므로 원본 세션과 callback token을 변경하지 않는다. `upgrade_safe`는 같은
digest의 구형 세션과 안정 ID가 완전히 일치하는 세션만 저장하고 나머지는 차단 집계에
남긴다. `discard`는 네 필드가 모두 일치하는 세션 하나만 `confirm` 뒤 삭제하며, 완료된
operation 저널도 함께 없어지므로 운영자가 해당 NPC 이벤트를 처음부터 다시 실행해도
안전하다고 판단한 경우에만 사용한다.

완료 작업은 ID뿐 아니라 타입 결과 snapshot도 함께 보존한다. 종료 세션이 같은
operation ID를 다시 만나 실행을 건너뛸 때 결과 변수가 있으면 snapshot을 복원하므로
`battle.outcome`이나 `item_result` 후속 분기가 재시작 뒤 사라지지 않는다. 결과 필드가
없는 기존 저장 세션도 계속 읽으며 이 경우에는 이전 동작처럼 ID만 건너뛴다.

실패·취소 결과를 받을 지역 변수가 있으면 결과를 저장하고 다음 주소에서 재개한다.
결과 변수가 없다면 세션을 안전하게 `FAILED` 또는 `CANCELLED`로 종료한다.

## 저장 원자성 경계

`EventSession.completeInstruction`과 `completeAwait`는 지역 변수, 완료 작업 저널과
프로그램 카운터를 한 메서드에서 함께 변경한다. NeoForge `SavedData` 어댑터는 이 전이
직후 하나의 dirty 저장으로 반영해야 한다. 보상 어댑터는 실제 지급 성공 전에 완료
저널을 기록해서는 안 된다.

repository reload도 같은 원칙을 따른다. JSON 구문, IR 구조 또는 리소스 경로와 script
ID 중 하나라도 잘못되면 새 map을 공개하지 않으므로 실행 중인 서버는 직전의 정상
snapshot을 계속 사용한다.

## V4 상태 호환 경계

CVES는 scoreboard objective나 player NBT key를 AST와 IR에 저장하지 않는다.
`ServerPlayerEventState`만 다음 기존 저장 규칙을 호환 어댑터로 알고 있다.

- 일반 flag resource ID는 `cvf_` + SHA-1 앞 12자리의 scoreboard objective로 매핑한다.
- NPC instance 완료와 starter 수령 flag는 V4의 기존 특수 objective를 그대로 사용한다.
- player variable은 `cobbleventureVariable.<resource_id>` persistent data에 JSON 값으로
  보존한다.
- feature와 level cap은 player-menu의 권위 `cobbleventure_progress` 서버 명령을
  내부 bridge로 호출한다. 이 경로를 통해 persistent data와 client snapshot이
  함께 갱신된다. 명령은 CVES·AST·IR에 노출되지 않는다.

이 규칙으로 V4와 V5가 같은 플래그를 읽는 전환 기간에도 상태가 갈라지지 않는다.
EasyNPC 명령·SNBT·대화 라벨은 이 어댑터 계약에 포함되지 않는다.

## V5 NPC 바인딩 계약

바인딩 리소스의 최소 형식은 다음과 같다.

```json
{
  "schema_version": 1,
  "script_id": "cobbleventure:event_script/story/professor_oak"
}
```

파일이 `data/cobbleventure/npc_event_binding/story/professor_oak.json`이면 엔티티에
`cves_binding/cobbleventure/story/professor_oak` 태그를 붙인다. 같은 엔티티에 알려진
V5 바인딩 태그가 둘 이상 있거나, 스크립트에 `interact` 이벤트가 없거나 둘 이상이면
런타임 오류로 중단한다. `interact(range: N)`의 range는 공통 표현식 평가 결과가
양의 유한수여야 하며 생략 시 4블록이다. V5 태그가 없는 엔티티는 이 핸들러가
건드리지 않으므로 기존 V4
EasyNPC 프리셋은 전환 기간 동안 그대로 작동한다.

권위 `.cves`와 바인딩 원본은 콘텐츠 프로젝트의 `content/events`와
`content/event-bindings`에 두며, content-manager가 `generated/cves/data` 아래의 두
런타임 리소스로 결정적으로 컴파일한다. mod-builder는 이를 데이터 모드에 패키징한다.
현재 오박사 V5 스크립트와 바인딩이 첫 실제 산출 fixture이며, 기존 오박사 V4 JSON과
EasyNPC 프리셋에는 V5 태그를 아직 추가하지 않는다.

### NPC 승리 상금 연결

NPC의 `npc.battle_rewards.money`가 V5 승리 상금의 NPC별 권위 설정이다.
프로젝트 컴파일러는 이를 **배포용** `npc_event_binding`의 선택 필드
`money_reward`로 복사한다. `content/event-bindings` 원본에는 이 필드를
직접 쓰지 않으며, 웹 NPC 설정에서 수정한 값은 다음 빌드에 반영된다.
CVES 대사나 행동 프리셋을 재생성하지 않아도 상금 변경이 전달된다.

- 해당 NPC 바인딩과 실행 script ID가 일치하면 NPC 설정을 우선한다.
  `enabled: false`와 고정 금액 0도 명시적인 설정이며 배틀 프리셋으로 대체하지 않는다.
- NPC 설정이 없으면 기존 `battle.money_reward`를 사용한다. 두 설정을 합산하지 않는다.
  양쪽 모두 없으면 자동 상금은 없다. 임의의 전투에 전역 기본 상금을 부여하지 않는다.
- 일반 배틀 NPC의 누락된 설정은 `regional_level`, `per_level: 20`, `offset: 100`으로
  콘텐츠에 명시한다. 지역 레벨 조회 실패 시 NPC 예상 레벨을 사용하며,
  예상 레벨이 자리표시자 1인 던전 NPC는 참조 배틀의 팀 최고 레벨을 사용한다.
  `cobblemon:amulet_coin` 참여 보너스 2배는 기존 공통 지급 로직을 재사용한다.
- 관장 8명의 상금은 기존 CVES `give_money`가 담당하므로 자동 상금을 추가하지 않는다.
  사용자 정의 이벤트에서도 자동 상금과 `give_money`로 같은 보상을 중복 작성하지 않는다.
- `conditions`는 `flag_equals` 배열(AND)이며 전투 시작 시 해당 플레이어의 플래그로
  평가한다. 원본의 boolean 또는 0/1은 배포 시 boolean으로 정규화한다.
  다른 조건 종류는 무시하지 않고 빌드 오류로 알린다.
- 런타임은 전투 전에 기존 `TrainerMoneyRewards`에 한 번 준비하고,
  동일 플레이어·배틀 UUID의 승리에서 지급한다. 패배·포기는 기존 준비 취소 경로를 쓴다.

이 필드를 포함한 데이터 모드는 이를 읽는 Adventure 모듈과 함께 업데이트해야 한다.

## 보상 원자성 경계

`set_flag`, `set_player_variable`, `unlock_feature`, `set_level_cap`은 같은 값을
재적용해도 결과가 같은 멱등 명령이다. `give_item`은 Player Menu가 확장 가방과 같은
플레이어 persisted NBT compound에 operation ID, item ID와 수량을 함께 기록한다.
가방 갱신과 완료 저널을 한 번에 저장하고, 같은 operation ID 재요청은 저장된 결과만
반환하므로 Adventure SavedData callback이 늦거나 서버 재시작으로 유실되어도 아이템을
중복 지급하지 않는다. 같은 operation ID를 다른 item 또는 수량으로 재사용하면 지급을
거부한다. 가방이 부족하면 아무것도 넣거나 완료 기록하지 않고 `remaining_count`가 있는
실패 결과로 스크립트를 재개한다.

`give_loot`은 `count`회 추첨한 전체 ItemStack payload를 operation journal에 함께
저장한다. 모든 스택이 들어갈 때만 가방 갱신과 완료 상태를 함께 저장하며, 공간이
부족하면 어느 스택도 넣지 않고 생성 payload를 pending 상태로 보존한다. 따라서 같은
operation ID의 재접속·재시도는 loot table을 다시 추첨하지 않는다. 같은 ID를 다른
loot table 또는 추첨 횟수로 재사용하면 지급을 거부한다. `item_result`의 세 count는
추첨 횟수가 아니라 실제 생성된 아이템 개수를 뜻하며, 빈 결과도 성공한 0개 지급이다.
실패 시 선택적인 `failure_reason`은 `bag_full`, `operation_conflict`,
`loot_table_not_found`, `loot_generation_failed`, `invalid_resource_id` 중 런타임 원인을
담는다. 카탈로그에 선언된 ID가 서버 리소스에 실제로 없으면 빈 loot 성공으로 처리하지
않고 `loot_table_not_found`로 재개한다.

`give_money`, `take_money`는 Player Menu가 플레이어 persisted NBT에 operation ID,
부호 있는 delta, 실행 전·후 잔액과 성공 여부를 기록한 뒤 CobbleDollars 잔액을 바꾼다.
같은 operation ID와 delta의 재요청은 기록된 성공 여부만 반환하고 잔액을 다시 변경하지
않는다. 같은 ID를 다른 delta 또는 부채 정책으로 재사용하면 거부한다. 기본
`take_money amount`는 잔액이 부족하면 변경 없이 `false`를 반환한다.
`take_money amount allow_debt`는 전체 금액을 차감하여 음수 CobbleDollars 부채를
허용한다. `money()`는 음수 잔액을 0으로 보정하지 않고 그대로 반환한다. 두 명령의
amount 인자 자체는 양수만 받아 연산 방향이 뒤집히지 않으며,
`give_money ... notify` 성공 시 현재 변경량을 action bar에 표시한다.

`grant_badge`는 기존 Player Menu 배지 저장·동기화 명령을 사용하고,
`grant_field_move`는 Adventure의 공통 필드 이동 플래그에 같은 값을 설정한다. 둘 다 이미
보유한 보상을 다시 적용해도 상태가 달라지지 않는 자연 멱등 명령이며 결과는 성공 `true`다.

## 대화 gateway와 callback 인증

`say`와 `narrate`는 EasyNPC dialogue label을 실행하지 않는다. gateway request는
세션 key, source digest, instruction ID, 발화자 구분, 현지화 text IR과 지역 변수
snapshot만 가진다. 현재 언어 선택, resource ID 현지화와 조사 처리는 후속
클라이언트 renderer가 이 request를 사용해 수행한다. 서버는 현지화 문구를
미리 고정하지 않고, renderer는 현재 resource pack에서 Pokémon·아이템 표시
이름을 얻은 뒤 조사를 계산한다.

한 이벤트 실행에서 첫 NPC `say`를 열 때 서버는 플레이어의 현재 yaw·pitch와 NPC 눈 방향을 비교한다.
NPC가 보수적인 화면 범위(좌우 60도, 상하 45도) 안에 있으면 플레이어 시선을
그대로 유지한다. 화면 밖에 있을 때만 플레이어의 위치를 바꾸지 않고 NPC 눈높이를
바라보도록 회전시킨다. 이 자동 보정은 `narrate`에는 적용하지 않으며, CVES에 명시한
`face` 명령의 의미도 바꾸지 않는다. NPC가 언로드됐거나 다른 차원에 있으면 대화는
시선 보정 없이 계속한다. 같은 대화의 다음 줄에서는 플레이어가 돌린 시선을 다시 강제로
되돌리지 않으며, 다음 상호작용에서 새 대화가 시작되면 다시 한 번 판정한다.

클라이언트는 서버가 발급한 opaque token만 반환한다. callback 처리기는 패킷의
player ID를 믿지 않고 인증된 네트워크 player와 세션 key의 player ID가 같은지
먼저 검증한다. 다른 플레이어의 callback, stale token과 이미 소비된 token은
세션을 진행시키지 않는다.
대기 만료 시각을 지난 token도 실행을 재개하지 않고 세션을 `FAILED`로
종료한다.

## 구조화된 choice await

`choice`는 option text와 자식 블록 target을 Runtime IR에서 함께 가지지만, 네트워크에는
prompt, option text와 opaque token만 보낸다. callback은 원본 선택지 순서의 정수
index만 반환한다. 서버는 세션에 저장한 `option_targets` 범위 안인지 검증한 뒤 target을
선택하므로 클라이언트가 임의 주소나 라벨로 이동시킬 수 없다. 결과 변수가 있으면 같은
0-based index를 저장한다.

Escape 취소는 성공 분기로 진입하지 않고 세션을 `CANCELLED`로 종료한다. 연결 종료로
클라이언트 화면만 사라진 WAITING 세션은 NPC 재상호작용 시 취소한 뒤 현재 page를 다시
평가하고 새 choice 화면을 연다. 소비된 이전 token과 범위를 벗어난 index는 진행을
변경하지 않는다.

## 트레이너 battle await

CVES `await battle "namespace:battle/path" -> result`는 raw TBCS 명령이나 EasyNPC
callback label을 저장하지 않는다. 런타임 battle preset repository가 resource ID에서
trainer와 format을 해석하고 기존 VS 인트로, 고정 레벨 또는 지도 레벨 스케일링 wrapper,
TBCS trainer resource를 재사용한다. `battle_result`는 다음 형태다.

```json
{
  "outcome": "win",
  "opponent": "cobbleventure:trainer/ai_test"
}
```

`outcome`은 `win`, `loss`, `cancelled` 중 하나다. 승리만 안정 operation ID를 완료하고
결과 snapshot을 기록한다. 패배는 실패 결과, 도주는 취소 결과로 다음 명령에 전달되어
스크립트가 후속 대사를 분기할 수 있지만 같은 NPC 재상호작용의 재도전은 막지 않는다.
배틀 시작 이벤트가 제한 시간 안에 오지 않아도 `cancelled` 실패 결과로 안전하게
재개한다. 서버 재시작이나 접속 종료로 callback을 잃은 WAITING은 다음 상호작용에서
현재 page를 다시 평가해 복구한다.

## 스타터 룰렛 await

Player Menu는 스타터 풀, 무작위 순서, Cobblemon 지급과 기존 룰렛 화면의 권위 구현을
계속 소유한다. CVES는 이 구현을 복제하지 않고 callback token만 전달한다. 지급 성공
후 다음 형태를 세션 결과 변수에 기록하고 일반 인터프리터가 다음 대사를 실행한다.

```json
{
  "species_id": "cobblemon:bulbasaur",
  "form": null,
  "level": 5
}
```

현재 언어의 포켓몬 이름과 한국어 조사는 저장하지 않은 `species_id`에서 대사 렌더링
시점에 계산한다. 클라이언트가 화면을 닫거나 스타터가 잠겼거나 풀이 비었거나 지급이
실패하면 성공 주소로 진행하지 않고 세션을 `CANCELLED` 또는 `FAILED`로 종료한다.
callback token은 서버 권한 명령과 인증된 player에 결합되며 중복 callback은 소비된
token 저널에서 차단한다.

룰렛 UI 세션은 메모리에만 존재하므로 접속 종료 후 NPC에게 다시 상호작용하면 남아
있는 `starter_roulette` WAITING을 취소하고 현재 page 조건을 다시 평가해 재시작한다.
이미 지급됐다면 Player Menu가 V4 호환 `cv_starter_recv` 상태를 먼저 동기화하므로
재시작한 이벤트는 수령 완료 page를 선택한다.

## 직접 위치 teleport await

`await teleport player relative(...) -> movement`와
`await teleport player position(...) -> movement`는 명령 실행 중 좌표 문자열이나
Minecraft 명령을 만들지 않는다. Runtime IR의 호출 식을 `EventLocationRef`로 읽고,
세션이 WAITING으로 저장된 다음 서버 tick에서 이동한다. 이 순서 때문에 아주 빠른
이동도 callback이 세션 저장보다 먼저 도착하지 않는다.

기본 정책은 `safe_landing: required`, `preload_chunks: true`, `fade: none`이다.
안전 도착 검사는 월드 높이와 경계, 두 블록의 충돌·fluid, 아래 블록의 지지면과
barrier 제외를 적용하고 제한된 인접 후보만 결정적인 순서로 찾는다. 결과는 다음처럼
원본의 타입 destination을 그대로 보존한다.

```json
{
  "arrived": true,
  "failure_reason": null,
  "destination": {"kind": "relative", "x": 0, "y": 0, "z": -4}
}
```

성공만 operation ID와 결과 snapshot을 완료 저널에 기록한다. 안전 위치나 대상 차원을
찾지 못한 실패는 `arrived: false`와 안정적인 `failure_reason`을 결과 변수에 넣고 다음
명령으로 재개하여 CVES 중첩 조건으로 처리할 수 있다. teleport의 `npc` 대상은 아직
지원하지 않으며 값을 무시하거나 임의 좌표를 합성하지 않고 명시적인 실패 결과를
반환한다. 유실된 teleport WAITING은 다음 NPC
상호작용에서 취소하고 현재 page를 다시 평가한다.

## 상대 걷기 await

`await move player|npc relative(...) -> movement`는 세션 WAITING 저장 다음 tick부터
20 TPS의 결정적 상태 머신으로 실행한다. 기본 `speed: 0.9`는 초당 블록 수이며 매 tick
남은 거리보다 크지 않은 보폭으로 이동한다. 플레이어 대상의 `lock_input: true`는
투명한 비일시정지 client screen과 서버 위치 보정으로 입력을 막으며 성공, 충돌, 낙하
위험, 시간 초과와 명시적 취소 모든 종료 경로에서 해제한다. NPC 대상은 세션 key의
NPC UUID를 현재 서버 차원들에서 찾고, 언로드 중이면 제한 시간까지 기다린다.

`collision: stop`은 다음 entity bounding box가 월드와 충돌하면 이동 전 `collision`로
실패한다. `collision: ignore`는 해당 검사만 생략하고 다음 발 위치의 단단한 윗면,
fluid와 barrier를 계속 검사하므로 낙하 위험은 `fall_risk`로 중단한다. 제한 시간은
`movement_timeout`, 대상이 끝까지 로드되지 않으면 `movement_subject_unavailable`이다.
결과는 teleport와 같은 `movement_result`이고 성공한 경우에만 안정 operation 저널을
완료한다. 재상호작용 복구는 남은 서버 이동 작업을 먼저 취소해 입력 잠금을 해제한 뒤
WAITING 세션을 취소한다.

## 방향과 공통 연출

`face player|npc north|south|east|west|player|npc`는 현재 서버 엔티티 회전을 즉시
적용한다. 플레이어 회전은 위치를 바꾸지 않는 서버 teleport 동기화를 함께 보내며,
NPC는 body와 head yaw를 같이 바꾼다. subject 자신을 바라보거나 NPC가 언로드된 경우는
임의 방향을 택하지 않고 실행 오류로 세션 진행을 멈춘다.

`fade`, `wait`, `sound`, `effect`는 공통 presentation gateway와 await token을 사용한다.
세션 WAITING 저장 다음 tick에 시작하며 성공한 경우에만 operation ID와 bool 결과를
저널에 기록한다.

- `fade black|white`: client 화면에서 0.25초 fade-out과 0.25초 fade-in을 수행하고
  화면을 닫은 뒤 재개한다. teleport의 `fade` 옵션도 같은 화면과 순서를 사용해 중앙의
  완전 불투명 시점 뒤 서버 이동을 수행한다.
- `wait`: 0~3600초를 서버 game tick으로 환산하고 최소 다음 tick에서 재개한다.
- `sound`: 지정 sound event ID를 플레이어 위치의 master source로 한 번 재생한다.
- `effect`: registry의 `SimpleParticleType`만 플레이어 주변에 발생시킨다. 추가 매개변수가
  필요한 particle은 추측하지 않고 `false` 실패 결과로 처리한다.

접속 종료나 서버 재시작으로 presentation 작업을 잃은 WAITING은 다음 NPC 상호작용에서
취소한다. 진행 중 fade가 메모리에 남아 있다면 client 화면을 먼저 닫고 세션을 취소한다.

### 전역 Anchor 위치 제공자

`anchor("namespace:event_anchor/path")`는 World Bootstrap이 읽은
`content/catalogs/event-boundaries.json`의 명시적 anchor를 사용한다. 같은 ID는
`anchor_step(target: ...)`의 진입 영역이면서, 제작자가 `anchor(...)`를 명시했을 때만
이동 목적지가 된다. 마을·도로·차원·공간 anchor를 이 목록으로 자동 변환하지 않는다.

도착점은 정수 box의 X/Y/Z 결정적 중심이며 X/Z에는 블록 중심 보정 0.5를 적용한 뒤
공통 안전 착지 검사를 수행한다. `anchor(...)` 자체가 최종 위치 ID이므로 이동 명령의
하위 `anchor` 속성은 허용하지 않는다. 카탈로그 준비 전과 누락 ID는 각각
`world_not_ready`, `destination_not_found`를 반환한다.

### Settlement 위치 제공자

`settlement("namespace:settlement/path")`는 World Bootstrap의 현재 runtime
settlement snapshot에서 해석한다. 이 snapshot은 authored `center`와 모든 anchor를
실제 hex town cell 중심으로 평행이동한 뒤 공개된 값이다. 따라서 데이터 모드의 원본
좌표를 Adventure가 별도로 reload해서 잘못된 위치로 이동시키지 않는다.

`anchor` 속성을 생략하면 해당 settlement의 `player_spawn`, 지정하면 번역 완료된
`anchors[anchor]`를 사용한다. 아직 월드 snapshot이 준비되지 않았거나 settlement·
anchor가 없으면 각각 `world_not_ready`, `destination_not_found`, `anchor_not_found`를
반환한다. World Bootstrap이 설치되지 않은 Adventure 단독 서버에서는
`location_provider_unavailable`을 반환한다. 모든 경우 결과의 `destination`에는 해석된
절대 좌표가 아니라 CVES가 작성한 settlement ID와 anchor가 남는다.

### Cave·Forest space 위치 제공자

`await enter_space player space("namespace:cave/path") { anchor: "west" }`와
`teleport ... space(...)`는 World Bootstrap이 현재 월드에 활성화한 cave·forest
문서에서 해석한다. `space`에는 명시적 `anchor`가 필수이며 생략하면
`anchor_required`를 반환한다.

- Cave entrance ID는 실제 관문 이동에서도 사용하는 `fallback_anchor`에 도착한다.
- Cave `generator.manual_layout.anchors[].id`는 생성된 방 바닥의 한 블록 위에 도착한다.
- Forest entrance ID는 기존 숲 관문과 같은 계산을 공유해 portal에서 숲 중심 쪽으로
  6블록 들어간 위치에 도착한다.

공간의 `dimension.id`와 anchor 좌표는 `ResolvedLocation`을 거쳐 기존 청크 준비와 안전
착지 검사를 그대로 사용한다. 존재하지 않는 공간과 anchor는 각각
`destination_not_found`, `anchor_not_found`로 재개된다. 여러 cave가 하나의 dimension을
공유할 수 있으므로 `dimension(id) + anchor`를 공간 ID 없이 추측하지 않는다.

### Route 위치 제공자

`route("namespace:route/path")`는 World Bootstrap의 활성 `ConnectionPath` 중심선에서
해석한다. `anchor`는 필수이며 `start`, `middle`, `end`만 허용한다. 세 값은 authored
world connection의 `from → to` 방향을 기준으로 각각 중심선 거리의 0%, 50%, 100%다.
따라서 중심선 점 개수가 달라도 `middle`은 배열 중앙이 아니라 실제 경로 거리의 절반이다.

리졸버는 현재 generation 1 서버 레벨의 `MOTION_BLOCKING_NO_LEAVES` heightmap으로
도착 Y를 계산하고 중심선 접선 방향을 yaw로 사용한 뒤 공통 안전 착지 검사를 수행한다.
월드·경로·앵커·차원을 찾지 못하면 각각 `world_not_ready`, `destination_not_found`,
`anchor_not_found`, `destination_unavailable`을 반환한다. 별도 authored 체크포인트는
route 스키마에 아직 없으므로 임의 이름을 허용하지 않는다.

### Building space 위치 제공자

authored 시설 건물은 `<namespace>:building/<settlement-path>/<facility-id>`를 공개
space ID로 사용한다. 이는 구조물 종류나 배치 좌표가 아니라 settlement와
`facility_placements[].id`에서 만들어지므로 건물이 이동하거나 같은 구조물이 재사용돼도
동일하다. 자동 생성 일반 주택은 이 계약에 포함하지 않는다.

anchor는 `room_1/door`, `room_1/professor_oak`, `exterior/door`처럼
`<space-key>/<structure-anchor>` 형식이다. 런타임은 실제 할당된 building interior
instance origin과 구조물 회전을 적용하고 `safe_spawn`이 있으면 이를 우선한다. 공개
space ID는 기존 건물 runtime 저장 데이터에도 기록하며, ID가 없던 6필드 저장 형식도
그대로 읽는다. 재시작 후 시설 재등록 시 기존 인테리어 슬롯을 재사용한다.

### Dimension 위치 제공자

`dimension("namespace:path")`는
`content/catalogs/dimension-anchors.json`에 명시된 차원 전역 도착 앵커만 해석한다.
카탈로그는 `dimension ID -> anchor ID -> block position` 구조이며 yaw와 pitch를
선택적으로 저장한다. block position의 X/Z 중앙을 도착 좌표로 사용하고, 이후에는 다른
위치 제공자와 같은 청크 준비·안전 착지 검사를 거친다.

`anchor`는 필수다. 월드 grid origin, 지역·마을 앵커 또는 같은 차원을 공유하는 cave와
forest의 공간 앵커를 대신 사용하지 않는다. 아직 카탈로그가 로드되지 않았거나 차원·
앵커가 없으면 각각 `world_not_ready`, `destination_not_found`, `anchor_not_found`를
반환한다. 중복 차원 ID, 잘못된 좌표와 yaw/pitch 범위는 콘텐츠 검증 또는 서버 시작
시점에 거부한다.

## NPC 및 인덱스 경계 트리거

V5 바인딩 태그를 가진 로드된 NPC에 대해 `proximity_enter`와 `proximity_exit`를 서버가
5 tick 간격으로 판정한다. 스크립트마다 임의 이동 callback을 등록하지 않고, 로드된
바인딩 NPC와 플레이어의 거리 경계가 실제로 바뀔 때만 공통 트리거 실행기에 전달한다.

- `range` 기본값은 4블록이며 양의 유한수 표현식만 허용한다.
- 최초 관측이 범위 내부면 `proximity_enter`가 한 번 발생한다.
- 최초 관측이 범위 밖인 경우 `proximity_exit`를 만들어내지 않는다.
- 같은 상태가 유지되는 동안 반복 발동하지 않고, 이탈 후 재진입해야 다시 발생한다.
- 언로드된 NPC는 추적 상태에서 제거하며 존재하지 않는 NPC의 exit를 추측하지 않는다.
- `once`와 `cooldown`은 플레이어 persistent data에 NPC UUID·script ID·event index별로
  저장하므로 재접속 후에도 유지된다.
- 현재 서버 실행 범위는 `scope: player`다. `world`, `party`, `instance` scope는 해당
  소유권 저장소가 추가될 때까지 명시적 실행 오류로 거부한다.

`group`, `stage`, `after`를 사용하지 않는 일반 proximity 이벤트에는 위 규칙이 그대로
적용된다. 강제 트레이너 조우처럼 단계화된 그룹은 다음 규칙을 추가로 적용한다.

- `stage` 이벤트는 플레이어가 먼저 바깥에서 관측된 뒤 범위에 들어와야 발동한다.
  월드 로드나 순간이동으로 이미 안쪽에 나타난 플레이어에게 즉시 전투를 걸지 않는다.
- `after` 이벤트는 같은 NPC·script·group의 선행 단계가 성공한 다음 스캔부터 발동한다.
- 후속 이벤트가 실행되면 해당 그룹을 소비하고 경고 오버레이와 조우 음악을 닫는다.
- 바깥 범위를 벗어나면 단계와 소비 상태를 초기화하므로 다음 정상 재진입이 가능하다.
- 이 동작은 `cves_trigger/proximity` 표현 태그가 있는 V5 NPC에만 적용한다. V4의 기존
  EasyNPC proximity 액션은 전환 기간 동안 그대로 보존한다.
- `cves_trigger/proximity`는 최초 조우 방식이며 영구적인 클릭 금지 태그가 아니다.
  해당 플레이어에게 선택 가능한 proximity 페이지가 남아 있으면 클릭으로 강제 조우를
  우회할 수 없다. 승리 플래그 등으로 모든 proximity 페이지 조건이 해제되고 `interact`
  페이지가 선택되면 클릭 후속 대화를 허용한다. 조건은 기존 page 선택기로 평가하며
  공유 NPC의 태그를 제거하지 않으므로 다른 플레이어의 조우 상태에는 영향을 주지 않는다.

상호작용과 proximity는 같은 `EventTriggerExecutor`를 사용하므로 page 선택, 영구 세션,
await 복구, operation journal과 명령 어댑터 동작이 동일하다. trigger instance는
`proximity_enter:<event-index>` 형식으로 분리되어 서로 다른 proximity event가 같은
세션을 잘못 공유하지 않는다.

World Bootstrap은 `content/catalogs/event-boundaries.json`의 명시적 3차원 정수 box를
읽어 현재 플레이어가 포함된 region ID와 anchor ID 집합만 Adventure에 제공한다.
Adventure는 이전 집합과 현재 집합을 비교해 다음 전이를 실행한다.

- `region_enter(target: ...)`: region box 외부에서 내부로 바뀔 때
- `region_exit(target: ...)`: 한 번 내부로 관측된 뒤 외부로 바뀔 때
- `anchor_step(target: ...)`: anchor box 외부에서 내부로 바뀔 때

box 경계는 min/max를 모두 포함하며 차원 ID가 같은 경우에만 일치한다. provider가 아직
준비되지 않았거나 오류를 반환하면 기존 상태에서 exit를 추측하지 않고 해당 관측을
버린다. target은 각각 `event_region`, `event_anchor` 카탈로그와 컴파일 시 교차 검증한다.
마을 경계, route 중심선, 이동용 dimension/space anchor는 명시적 event boundary 항목이
없으면 트리거 대상으로 간주하지 않는다.

같은 스냅샷은 authored 시설 건물과 현재 차원도 제공한다.

- `building_enter/exit(target: ...)`는 공개 building space ID에 등록된 실제 내부 구조물
  template box 진입·이탈을 감지한다. exterior 구조물과 자동 생성 일반 주택은 건물
  내부로 간주하지 않으며, 같은 시설의 내부 방 사이 이동은 이탈로 처리하지 않는다.
- `dimension_enter/exit(target: ...)`는 플레이어의 현재 서버 차원 ID 전이를 감지한다.

건물 box는 배치 origin을 포함하고 `origin + template size`는 포함하지 않는다. 따라서
공유 `building_interiors` 차원의 인접 슬롯은 서로 겹치지 않는다. 첫 관측부터 target
내부라면 다른 enter 계열과 마찬가지로 enter가 한 번 발생하며, exit는 이전 내부 관측이
있을 때만 발생한다. target은 각각 `building`, `dimension` 권위 카탈로그와 교차 검증한다.

## 서버 상태 신호 트리거

공간 전이와 같은 `EventTriggerExecutor` 및 플레이어 영구 once/cooldown 원장을 사용해
다음 서버 권위 신호를 실행한다.

- `flag_changed(target: ...)`: 로드된 V5 NPC 스크립트가 구독한 flag만 5 tick 간격으로
  scoreboard 호환 저장소에서 비교한다. 첫 관측은 기준값일 뿐 발동하지 않으며 false→true와
  true→false를 모두 변경으로 처리한다. 따라서 CVES 밖의 기존 명령이나 V4 시스템 변경도
  같은 저장소를 사용하면 감지된다.
- `item_used(target: ...)`: 서버의 `LivingEntityUseItemEvent.Finish`가 확정한 사용 완료만
  전달한다. 클라이언트 미리보기, 우클릭 시도, 취소된 사용은 발동하지 않는다. 별도 서버
  아이템 시스템은 `EventServerSignalDispatcher.itemUsed` 호환 진입점을 호출할 수 있다.
- `battle_finished(target: ...)`: CVES `await battle`로 연결된 authored battle preset의
  승리·패배·포기 결과가 확정되고 기존 await 결과가 재개된 뒤 전달한다.

세 trigger의 target은 각각 `flag`, `item`, `battle` 권위 카탈로그와 교차 검증하며 현재
scope는 다른 서버 trigger와 동일하게 `player`만 지원한다. 신호 payload를 암시적 전역
변수로 노출하지 않는다. 배틀 명령 자체의 결과는 기존 `battle_result` 타입 변수로 받는다.

## 현재 경계

`map_selection`은 Adventure의 공통 영구 await와 Player Menu의 기존 월드맵 사이를
명령 bridge로 연결한다. 서버는 player별 단일 token을 5분간 유지하고, 선택 좌표를
현재 `MapContent`의 settlement로 다시 해석해 방문 여부를 권위 검증한다. 일반 플레이어는
방문한 settlement만 선택할 수 있고 관리자·creative 플레이어는 방문하지 않은
settlement도 선택할 수 있다. 성공 callback은 settlement 리소스 ID를 가진
`location_ref`를 결과 변수에 기록한 뒤 다음 IR 주소에서 재개한다. 선택 자체는 이동을
수행하지 않으며 후속 teleport가 별도 movement await를 연다. 임의 hex·cave·forest는
V1 결과 대상이 아니다.

클라이언트 취소는 세션을 `CANCELLED`, timeout은 `FAILED`로 종료하며 동일 token callback은
중복 완료로 처리한다. 재상호작용 복구는 `map_selection` await에만 적용된다.

현재 구현은 데이터팩 IR과 V5 NPC 바인딩을 검증하고, 엔티티 상호작용에서 세션을
시작해 자체 대화 화면까지 실행한다. 세션을 영구 저장하며 표현식, page, 구조화된
제어 흐름과 외부 명령의 실행·재개를 관리한다. RPG Maker XP는 웹의 페이지·명령 트리
편집 UX에만 적용하는 참조 모델이다. 클라이언트 대화 화면은 서버가 보낸 최종 현지화
template과 세션 locals를 현재 언어로 렌더링한 뒤 페이지를 나누고 타이핑한다. 첫 입력은
현재 페이지를 즉시 표시하고, 완전히 표시된 뒤의 입력만 서버 token을 재개한다. 로드된
NPC UUID가 `LivingEntity`이면 기존 inventory entity renderer로 실제 3D 모델을 표시하며,
언로드된 경우 모델 없이 계속한다. RPG Maker풍 테마 복제와 별도 2D 초상화 자산 시스템은
의도적으로 제외하며 CVES V2 완료 조건에 포함하지 않는다.

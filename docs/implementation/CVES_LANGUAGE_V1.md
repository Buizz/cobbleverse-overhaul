# CVES 1차 문법 및 AST 타입 계약

> 상태: NPC 이벤트 스크립트 V2 도구 코어 계약
>
> 범위: lexer, parser, tree AST, formatter와 1차 의미 검증. 실행 IR은 별도 계약을 따른다.

CVES(CobbleVenture Event Script)는 Git에서 관리하는 NPC 이벤트의 권위 원본이다.
웹 편집기와 런타임 컴파일러는 텍스트 토큰이 아니라 이 문서의 트리 AST를 사용한다.
EasyNPC 라벨, SNBT, NPC UUID와 후속 대화 연결은 CVES 원본과 AST에 포함하지 않는다.

## 1. 문자와 파일 계약

- 파일 인코딩은 BOM 없는 UTF-8, 확장자는 `.cves`다.
- 식별자는 `[A-Za-z_][A-Za-z0-9_]*`다. 키워드도 lexer 단계에서는 식별자 토큰이다.
- 줄 주석은 `#`부터 줄 끝까지다.
- 문자열은 JSON과 같은 큰따옴표 문자열이며 `\"`, `\\`, `\n`, `\r`, `\t`,
  `\uFFFF`를 지원한다.
- 정수와 10진수는 각각 `int`, `decimal` 리터럴이다. 음수는 단항 `-` 식이다.
- 리소스 ID는 별도 lexer 토큰이 아니다. 따옴표 문자열로 기록하고 명령의 매개변수
  계약에 따라 `resource_id`로 타입 검사한다. 이 선택은 대사 안의 콜론과
  `namespace:path`를 문법적으로 혼동하지 않게 한다.

## 2. EBNF

아래 EBNF에서 `NL`은 하나 이상의 줄바꿈, `IDENT`와 `STRING`은 위 문자 계약의
토큰이다. 블록의 마지막 문장 뒤 줄바꿈은 생략할 수 있다.

```ebnf
program          = NL*, event, { NL+, event }, NL*, EOF ;
event            = "event", IDENT, [ arguments ], block-pages ;
arguments        = "(", [ argument, { ",", argument } ], ")" ;
argument         = [ IDENT, ":" ], expression ;

block-pages      = "{", NL*, page, { NL*, page }, NL*, "}" ;
page             = "page", ( "when", expression | "default" ), command-block ;
command-block    = "{", NL*, [ statement, { NL+, statement }, NL* ], "}" ;

statement        = [ stable-id ], ( say | narrate | let | if | choice | repeat | command ) ;
stable-id        = "id", STRING ;
say              = "say", IDENT, text ;
narrate          = "narrate", text ;
let              = "let", IDENT, "=", expression ;
if               = "if", expression, command-block,
                   [ NL*, "else", command-block ] ;
choice           = "choice", text, "{", NL*, choice-option,
                   { NL*, choice-option }, NL*, "}", [ result-binding ] ;
choice-option    = text, command-block ;
repeat           = "repeat", expression, command-block ;

command          = [ "await" ], command-name, { command-argument },
                   [ property-block ], [ result-binding ] ;
command-argument = expression | IDENT, expression | IDENT ;
property-block   = "{", NL*, property, { NL+, property }, NL*, "}" ;
property         = IDENT, ":", expression ;
result-binding   = "->", IDENT ;

text             = STRING | localized-text ;
localized-text   = "{", NL*, locale-entry,
                   { ( NL+ | "," ), locale-entry }, NL*, "}" ;
locale-entry     = IDENT, ":", STRING ;

expression       = logical-or ;
logical-or       = logical-and, { "||", logical-and } ;
logical-and      = equality, { "&&", equality } ;
equality         = comparison, { ( "==" | "!=" ), comparison } ;
comparison       = term, { ( "<" | "<=" | ">" | ">=" ), term } ;
term             = factor, { ( "+" | "-" ), factor } ;
factor           = unary, { ( "*" | "/" | "%" ), unary } ;
unary            = { "!" | "-" }, postfix ;
postfix          = primary, { ".", IDENT | arguments } ;
primary          = INTEGER | DECIMAL | STRING | "true" | "false" |
                   IDENT | "(", expression, ")" ;
```

명령 인자의 `IDENT expression`과 값 없는 `IDENT`는 명령별 계약으로 해석한다.
1차 코어에서 이름 있는 인자는 `give_item ... count 1`, 플래그 인자는 `notify`다.
트리거와 함수 호출의 이름 있는 인자는 항상 `name: value` 형식이므로 모호하지 않다.

`id "..."`는 문장의 공통 메타데이터이며 같은 줄의 문장 하나에 적용된다. 안정 ID는
`lowercase/path.segment` 형태이고 스크립트 전체에서 유일해야 한다. GUI는 새 비동기·
부작용 명령을 만들 때 ID를 자동 생성하고 기본 트리 화면에서는 숨길 수 있다.

## 3. 트리 AST 계약

퀘스트 수락·목표 달성·완료 훅은 인수 없는 `event quest`를 사용한다.
일반 EBNF의 `IDENT` 트리거를 그대로 사용하므로 lexer/AST/IR 버전을 바꾸지 않는다.
`range`, `once`, `scope` 등 인수는 받지 않고 퀘스트의 플레이어별 저널에서 발동과 중복을 관리한다.
연결되는 원본에는 quest 진입점이 정확히 하나 필요하다. 상세 작성/실행 계약은
`EVENT_AUTHORING_INTEGRATION.md`의 2차 퀘스트 실행 훅 절을 따른다.

모든 노드는 원본 파일, 시작/끝 offset, 1부터 시작하는 줄과 열을 가진 `SourceSpan`을
보존한다. 위치는 진단과 source map용 메타데이터이며 AST 의미 동등성 비교에서는
제외한다.

```text
Program
└─ Event(trigger: Trigger, pages: Page[])
   └─ Page(condition: Expression | default, block: Block)
      └─ Block(statements: Statement[])
```

`Statement`는 `Say`, `Narrate`, `Let`, `If`, `Choice`, `Repeat`, `Command`의 닫힌
합집합이다. `If`는 then/else 블록을, `Choice`는 선택지별 블록을, `Repeat`는 본문
블록을 직접 소유한다. 잎 명령은 문자열 dict가 아니라 `CommandKind` enum,
구조화된 인자·속성, `awaited`, 결과 변수로 구성한다. 향후 GUI는 이 자식 관계를
직접 편집하며 라벨 그래프를 정상 분기의 내부 표현으로 만들지 않는다.

현지화 대사의 각 locale entry도 독립 노드와 `SourceSpan`을 가져 해당 언어 문자열의
의미 오류가 블록 시작이 아닌 실제 항목 줄을 가리킨다.

식 노드는 `Literal`, `Name`, `Member`, `Call`, `Unary`, `Binary`의 닫힌 합집합이다.
명령·식·블록 컬렉션은 순서를 보존하는 불변 tuple이다.

## 4. 값과 결과 타입

1차 값 타입은 다음으로 고정한다.

| 타입 | 저장 계약 |
|---|---|
| `bool` | `true` 또는 `false` |
| `int` | 부호 있는 정수 |
| `decimal` | 10진 리터럴을 손실 없이 보존 |
| `string` | 직접 문자열 |
| `resource_id` | 문자열 구문 + `namespace:path` 의미 검증 |
| `position` | dimension, x/y/z, 선택적 yaw/pitch 구조 값 |
| `location_ref` | `relative`, `position`, `anchor`, `settlement`, `route`, `dimension`, `space` 호출 값 |
| `pokemon_selection` | `species_id: resource_id`, form, level, 현지화 `name` 뷰 |
| `battle_result` | outcome(`win/loss/cancelled`), opponent trainer `resource_id` |
| `item_result` | requested_count, granted_count, remaining_count, 선택적 failure_reason |
| `movement_result` | arrived, failure_reason, destination |
| `healing_result` | healed, failure_reason |

지역 변수 범위는 이벤트 실행 세션이다. `let`과 명령의 `->`가 지역 변수를 만들며,
영구 값은 `set_flag` 또는 `set_player_variable`만 변경한다. 지역 변수의 재선언,
필드 존재 여부와 연산자 타입은 후속 semantic validator가 검사한다.

내장 식의 1차 시그니처는 `flag(resource_id) -> bool`, `money() -> int`,
`level_cap() -> int`다. `player.name`은 `string`이다. `pokemon_selection.name`은
저장 문자열이 아니라 `species_id`를 현재 언어로 해석하는 현지화 name 뷰다.
`movement_result.arrived`는 `bool`이고 `movement_result.failure_reason`은 항상 `string`이다.
성공 시에는 빈 문자열, 실패 시에는 아래의 안정적인 snake_case 코드를 저장한다.

| 실패 코드 | 의미 |
|---|---|
| `anchor_required`, `anchor_not_found` | 필수 앵커 누락 또는 등록되지 않은 앵커 |
| `destination_not_found`, `destination_disabled` | 콘텐츠 목적지 누락 또는 비활성화 |
| `destination_unavailable`, `world_not_ready` | 대상 차원·월드가 아직 사용 불가 |
| `location_provider_unavailable`, `location_resolution_failed` | 위치 provider 누락 또는 예외 |
| `unsafe_landing`, `teleport_failed`, `fade_unavailable` | 안전 도착·텔레포트·페이드 실패 |
| `collision`, `fall_risk`, `movement_timeout`, `movement_failed` | 걷기 충돌·낙하 위험·시간 초과·실행 실패 |
| `movement_subject_unavailable`, `npc_subject_unavailable` | 이동 대상 엔티티를 사용할 수 없음 |

템플릿 `${path|filter}`의 path는 현재 지점에 정의된 변수와 공개 필드만 참조한다.
필터 계약은 `name(resource_id 또는 현지화 name 뷰) -> string`,
`number(int 또는 decimal) -> string`, `josa:은/는·이/가·을/를·과/와(string) -> string`,
`fallback:string(nullable T) -> T|string`이다. 템플릿은 식 평가, 함수 호출,
Minecraft 선택자와 명령 실행을 허용하지 않는다. 언어별 텍스트는 같은 변수 path
집합을 사용해야 하며 필터는 언어별로 달라도 된다.

## 5. 명령 및 await 계약

| 명령 | 핵심 입력 | 결과 타입 | await 표기 |
|---|---|---|---|
| `battle` | battle `resource_id` | `battle_result` | 필수 |
| `starter_roulette` | 선택 정책 | `pokemon_selection` | 필수 |
| `map_selection` | 지도/목적지 정책 | `location_ref` | 필수 |
| `heal_party` | NPC 근처 치료기, 선택적 `fallback`으로 치료기 없을 때 직접 회복 | `healing_result` | 필수 |
| `move`, `teleport`, `enter_space` | 대상 + `location_ref` + 속성 | `movement_result` | 필수 |
| `give_item` | item `resource_id`, `count`, `notify` | `item_result` | 암시적 대기 가능 |
| `give_loot` | loot `resource_id`, 추첨 횟수 `count`(1..1024), `notify` | `item_result` | 암시적 대기 가능 |
| `give_money` | 양의 금액, 선택적 `notify` | `bool` | 표기 없음 |
| `take_money` | 양의 차감액, 선택적 `allow_debt` | `bool` | 표기 없음 |
| `grant_badge`, `grant_field_move` | 권위 ID 또는 닫힌 이동 이름 | `bool` | 표기 없음 |
| `say`, `narrate` | 텍스트 | 없음 | 런타임이 입력 대기 |
| `choice` | 프롬프트 + 중첩 선택지 블록 | `int`(선택한 0-based index) | 런타임이 입력 대기 |
| `show_choices` | 선택지 명령 인자 | `int`(선택한 0-based index) | 명령 계약에 따라 대기 |
| `fade`, `wait`, `sound`, `effect` | 연출 값 | 필요 시 `bool` | 명령 자체 완료 경계 |
| 상태·돈·배지·기능·방향 명령 | 명령별 값 | 필요 시 `bool` | 표기 없음 |
| `label`, `jump`, `call`, `return` | 식별자 | 없음 | 표기 없음, 고급 기능 |

명시적 비동기 명령은 `await`가 없으면 문법 오류다. 콜백은 후속 라벨이 아니라
세션 토큰과 타입 결과만 반환한다. `give_item`처럼 획득 연출을 기다릴 수 있는 명령은
실행 IR lowering이 암시적 대기 경계를 삽입하므로 CVES에서 `await`를 붙이지 않는다.
`give_loot`의 `count`는 생성될 아이템 수가 아니라 loot table 추첨 횟수다. 반환되는
`item_result`의 count 필드는 추첨 후 실제 생성된 전체 아이템 수를 나타낸다.
`choice` 및 `show_choices`의 결과는 현재 언어의 선택지 문구가 아닌 원본 선택지 순서의
0부터 시작하는 `int` index다. 이로써 현지화 문구 변경이 세션 결과와 분기 대상을
바꾸지 않는다.

`map_selection` V1은 인자와 속성을 받지 않으며 기존 월드맵을 목적지 선택 모드로 연다.
일반 플레이어는 방문한 settlement만 선택할 수 있고, 관리자 또는 creative 플레이어는
방문하지 않은 settlement도 선택할 수 있다. 임의 hex, cave, forest는 V1 선택 결과가
아니다. 성공 결과는 settlement 리소스 ID를 가진 `location_ref`이며 직접 이동시키지
않는다. 실제 이동은 결과 변수를 후속 `teleport`에 전달해 별도의 `movement_result`로
확인한다.

```cves
id "travel/select" await map_selection -> destination
id "travel/go" await teleport player destination {
  safe_landing: required
  preload_chunks: true
  fade: black
} -> movement
```

리소스 기반 `location_ref`는 읽기 전용 현지화 뷰인 `name` 필드를 공개한다. AST와
세션에는 계속 안정적인 `resource_id`만 저장하며, `${destination.name}`을 출력하는
시점에 클라이언트의 현재 언어로 표시명을 결정한다. 따라서 다음처럼 조사 필터와 함께
사용할 수 있다.

```cves
say npc "${destination.name|josa:을/를} 목적지로 선택했어."
```

`relative`와 `position`처럼 이름을 가진 콘텐츠 리소스가 아닌 위치에는 `name`을
사용하지 않는다. 현재 이름 카탈로그의 첫 적용 범위는 `map_selection`이 반환하는
settlement이며, 알 수 없는 ID는 원본 리소스 ID로 결정적으로 대체한다.

1차 `CommandKind`의 닫힌 목록은 다음과 같다. 새 문자열을 AST에 임의로 넣을 수 없고,
언어 버전 변경과 함께 enum 및 의미 계약을 추가해야 한다.

```text
stop, show_choices,
set_flag, set_variable, set_player_variable, unlock_feature, set_level_cap,
give_item, give_loot, give_money, take_money, grant_badge, grant_field_move,
battle, starter_roulette, map_selection, heal_party,
move, teleport, enter_space, face,
fade, wait, sound, effect,
label, jump, call, return
```

위치 생성 식도 닫힌 목록이다. `relative(x, y, z)`는 상대 오프셋,
`position(dimension, x, y, z, yaw?, pitch?)`은 절대 좌표다. `anchor(id)`,
`settlement(id)`, `route(id)`, `dimension(id)`, `space(id)`는 `location_ref`를 만든다.
`anchor(id)`는 `event-boundaries.json`에 등록된 전역 앵커 상자의 결정적 중심점을
뜻하므로 별도의 하위 `anchor` 속성을 허용하지 않는다. 나머지 콘텐츠 위치의 세부
도착점은 이동 명령의 `anchor` 속성으로 지정한다. 리소스 기반 목적지는 빌드 시
카탈로그와 교차 검증하며, 좌표 문자열이나 EasyNPC 텔레포트 명령을 AST에 저장하지 않는다.
`teleport`의 생략 옵션 기본값은 `safe_landing: required`,
`preload_chunks: true`, `fade: none`이다.
`move`는 `relative(...)` 목적지만 받으며 생략 옵션은 `mode: walk`, `speed: 0.9`,
`lock_input: true`, `collision: stop`이다. `mode`는 `walk`와 `offset_teleport`만 허용한다.
`speed`, `lock_input`, `collision`은 `move` 전용이고, `anchor`와 `fade`는 teleport 계열
전용이다. `collision: ignore`도 발밑 지지면 검사를 끄지 않으므로 허공으로 이동하지
않는다.
`face`는 `player` 또는 `npc`를 즉시 `north`, `south`, `east`, `west`나 상대
`player`/`npc` 방향으로 회전한다. await와 operation ID를 만들지 않는다.
`fade black|white`는 고정 0.5초 fade-out/in, `wait duration`은 초 단위 0~3600 값이다.
`sound`는 sound event 리소스 ID, `effect`는 인자 없는 단순 particle type 리소스 ID를
사용한다. 네 연출 명령은 CVES에 `await`를 쓰지 않지만 IR에서 안정 operation ID와
암시적 await 경계를 가지며 선택적 bool 결과 변수로 성공 여부를 받을 수 있다.
`settlement(id)`에서 이동 명령의 `anchor` 속성을 생략하면 `player_spawn`을 사용한다.
지정한 settlement anchor와 기본 도착점은 authored 절대 좌표가 아니라 현재 월드
플랜이 확정한 runtime 좌표로 해석한다.
`heal_party`는 대화 중인 NPC 주변 8블록의 가장 가까운 포켓몬 치료기를 작동시키고
기계 연출이 끝날 때까지 공통 await 입력 잠금을 유지한다. 결과의 `healed`는 실제 치료
완료 여부이며 `failure_reason`은 성공 시 빈 문자열, 실패 시
`healing_machine_not_found`, `healing_unavailable`, `healing_interrupted`,
`healing_timeout` 중 하나다. 포켓몬센터는 반복 이용 서비스이므로 안정 ID는 세션 복구
anchor로 사용하지만 완료 작업 저널에는 기록하지 않아 다음 방문에도 다시 실행한다.
`await heal_party fallback -> healing`은 치료기를 **찾지 못했을 때만** Cobblemon의
파티 전체 회복을 대체 실행한다. 치료기가 있으면 기존 기계 연출을 우선하며,
사용 중·치료 불가·중단·시간 초과는 대체 회복하지 않는다. 옵션을 생략한 간호순은 기존 동작을 유지한다.
대체 회복도 같은 await 토큰을 저장한 뒤 다음 서버 틱에 재개하며, 빈 파티는 `healing_unavailable`이다.
오박사는 도감 수령 완료 플래그가 있는 재방문에서만 이 옵션으로 회복한다.
스타팅 선택 직후와 가방 공간 부족에 따른 도감 수령 재시도에서는 회복을 끼워 넣지 않는다.
재방문은 회복 안내 → 회복 → 성공/실패 대사로 끝내며 첫 포켓몬 선택 대사를 반복하지 않는다.
V4/V5 이관 계약은 스타팅·도감 지급 이야기와 상태를 보존하고, 재방문 서비스 대사는
동일 문구 비교에서 제외하여 V5 콘텐츠 회귀 테스트로 검증한다.
`enter_space`의 destination은 반드시 `space(id)`여야 한다. `space(id)`는
cave·forest처럼 자체 공간 ID를 가진 대상이며 이동 명령의 `anchor`
속성이 필수다. Cave entrance와 manual layout anchor, forest entrance ID가 권위 있는
anchor 목록이다. 여러 공간이 같은 차원을 사용할 수 있으므로 `dimension(id)`의
anchor를 임의의 space anchor로 추론하지 않는다.

## 6. 페이지와 포매팅

- page는 위에서 아래로 평가해 처음 참인 하나만 실행한다.
- `page default`는 선택 사항이지만 존재한다면 이벤트의 마지막 page여야 한다.
- formatter는 들여쓰기 2칸, LF 줄바꿈, event/page 사이의 고정 빈 줄, JSON 문자열
  escaping을 사용한다.
- 원본의 주석과 공백은 의미 AST에 포함하지 않는다. 따라서 “손실 없는 왕복”은
  실행 의미와 작성 데이터의 손실이 없다는 뜻이며, trivia의 바이트 동일 보존을
  뜻하지 않는다.
- formatter 결과를 다시 format해도 바이트가 같고, parse → format → parse의 AST는
  위치 메타데이터를 제외하고 동등해야 한다.

## 7. 의미 검증과 후속 단계의 경계

parser는 문장 구조, 알려진 명령 종류, 필수 `await`, default page 순서와 중복
현지화 키를 검사한다. semantic validator는 명령별 인자 개수·타입, 지역 변수 범위,
결과 객체 필드, 템플릿 변수·필터, resource ID 형식과 위치 생성 식을 검사하고 여러
진단을 한 번에 반환한다. 실제 리소스와 앵커의 존재 여부는 프로젝트 카탈로그를 받는
교차 참조 검증 단계에서 추가한다. 검증된 AST는 Runtime IR V1로 컴파일할 수 있다.
콘텐츠 관리 웹은 같은 versioned wire AST와 standalone expression envelope로 `.cves`를
편집한다. V4 JSON 자동 변환은 하지 않으며 EasyNPC 출력은 V5 호환 어댑터 경계에서만
수행한다.

## 8. 프로젝트 카탈로그 교차 검증

`ResourceCatalog`는 리소스 집합과 함께 해당 종류가 권위 있는 전체 목록인지
`complete_kinds`로 선언한다. 의미 검증기는 완전하다고 선언된 종류에서만 누락을
오류로 처리한다. 외부 모드나 후속 시스템이 소유해 현재 프로젝트가 전체 목록을 알 수
없는 종류는 형식과 타입만 검사하여 거짓 오류를 만들지 않는다.

현재 프로젝트 로더의 계약은 다음과 같다.

| 종류 | 원본 | 누락 판정 |
|---|---|---|
| item | 명시적으로 전달한 Cobblemon item catalog | 전달된 경우 권위 있음 |
| battle | `content/battles/**/*.json` | 디렉터리가 있으면 권위 있음 |
| badge | `content/catalogs/badges.json` | 파일이 있으면 권위 있음 |
| loot | `content/loot_tables/<namespace>/**/*.json` | 디렉터리가 있으면 파일 경로가 권위 ID |
| flag/variable | `content/catalogs/game-definitions.json` | 파일이 있으면 권위 있음 |
| settlement | `content/settlements/**/*.json` | ID와 로컬 anchor 모두 권위 있음 |
| route | `content/routes/**/*.json` | ID와 내장 anchor가 권위 있음 |
| dimension | world/cave/forest 문서와 `catalogs/dimension-anchors.json` | 발견 ID와 등록 anchor가 권위 있음 |
| space | cave/forest 및 authored 시설 배치·건물 메타데이터 | 발견된 ID와 anchor가 권위 있음 |
| building | authored 시설 배치와 건물 메타데이터 | 공개 building space ID가 권위 있음 |
| event_region | `content/catalogs/event-boundaries.json`의 `regions` | 파일이 있으면 권위 있음 |
| event_anchor | `content/catalogs/event-boundaries.json`의 `anchors` | 파일이 있으면 권위 있음 |

loot table 검증은 모드가 추가한 entry·function 종류를 허용하면서 공통 resource ID와
pool, number provider, condition, function 구조를 검사한다. 바닐라 `minecraft:item`,
composite entry, `set_count`, `set_item`, `set_contents`는 내부 필드까지 재귀적으로
검사하며, item ID는 전달된 활성 모드 item catalog와 교차 확인한다.

route는 월드 연결의 authored `from → to` 방향과 생성된 중심선을 공통 계약으로 사용한다.
모든 route에 `start`, `middle`, `end` 내장 anchor가 있으며 각각 중심선 진행률
0%, 50%, 100%를 뜻한다. route, dimension, space 목적지에는 `anchor` 속성이 필수이고 프로젝트
카탈로그는 다른 route anchor를 오류로 판정한다.

`region_enter`, `region_exit`, `anchor_step`, `building_enter/exit`,
`dimension_enter/exit`, `flag_changed`, `item_used`, `battle_finished`에는
`target: resource_id`가 필수다. region과 anchor는 이름만 비슷한 마을·도로·이동
anchor를 자동으로 사용하지 않고 각각 `event_region`, `event_anchor`와 교차 검증한다.
building과 dimension은 해당 권위 ID만 허용하며, 서버 신호 trigger는 각각 `flag`,
`item`, `battle` 카탈로그와 교차 검증한다.

건물은 구조물 ID만으로 식별하지 않는다. 같은 구조물이 여러 곳에 배치될 수 있으므로
정착지 ID와 authored `facility_placements[].id`를 결합한
`<namespace>:building/<settlement-path>/<facility-id>`가 안정적인 건물 space ID다.
anchor는 `<interior-key>/<structure-anchor>` 형식이며 외부는 `exterior/<anchor>`를 쓴다.
예를 들어 오박사 연구소 입구는 다음과 같다.

```cves
await enter_space player space("cobbleventure:building/starter_town/facility_laboratory_1") {
  anchor: "room_1/door"
}
```

프로젝트 카탈로그는 settlement → facility structure → building settings → structure
metadata를 따라가 이 ID와 anchor를 검증한다. authored facility ID가 없는 자동 생성
일반 주택은 아직 공개 event space로 취급하지 않는다.

```python
catalog = load_project_catalog(project_root, item_catalog=item_catalog_path)
diagnostics = validate(program, catalog)
```

## 9. 공통 AST JSON wire 형식

웹 편집기와 Python 도구 사이에서는 Python 클래스 이름이나 `__dict__`를 직접
직렬화하지 않고 버전이 있는 wire envelope를 사용한다.

```json
{
  "wire_version": 1,
  "root": {
    "node": "program",
    "events": [],
    "span": null
  }
}
```

- 모든 합집합 노드는 고정된 `node` discriminator를 갖는다.
- enum은 계약에 정의된 문자열 값으로 저장한다.
- 순서가 의미인 tuple은 JSON 배열로 저장하며 순서를 바꾸지 않는다.
- `SourceSpan`은 포함하거나 생략할 수 있다. GUI가 새로 만든 노드는 `span: null`이며
  CVES로 저장하고 다시 파싱한 뒤 실제 위치를 얻는다.
- decoder는 알 수 없는 wire 버전·노드·필드·enum, 누락된 필드와 잘못된 자식 노드
  타입을 JSON path와 함께 거부한다.
- `decode(encode(AST))`는 span 포함 여부와 무관하게 의미 AST가 같아야 한다.

웹의 조건·명령 인자 필드는 같은 wire envelope의 expression 루트도 사용한다.
`parse_expression`은 정확히 한 식과 EOF만 허용하고 `format_expression`은 program
formatter와 같은 우선순위·문자열 escaping을 사용한다. 따라서 GUI에서 수정한 복합
조건도 임의 문자열로 AST에 삽입되지 않고 lexer → Pratt parser → expression wire를
거쳐야 한다.

span 없는 GUI AST를 의미 검증할 때 위치가 없는 진단은 `<ast>:1:1`을 사용한다.
parser가 이미 확인하는 default page 순서, 필수 await, 비어 있지 않은 choice와 현지화
블록도 semantic validator가 다시 확인하므로 텍스트를 거치지 않은 AST가 검증을
우회할 수 없다.

## 10. CVES CLI

CLI는 기본적으로 파일을 변경하지 않는다. `format --write`만 명시적인 쓰기 작업이며
UTF-8 BOM 없음과 LF 줄바꿈으로 기록한다.

```text
python tools/content-manager/cves_tool.py check <file-or-directory>
  [--project-root <project>] [--item-catalog <items.json>]
python tools/content-manager/cves_tool.py format <file-or-directory> --check
python tools/content-manager/cves_tool.py format <file-or-directory> --write
python tools/content-manager/cves_tool.py ast <file> [--no-spans]
python tools/content-manager/cves_tool.py compile <file> --script-id <id>
  [--project-root <project>] [--item-catalog <items.json>] [--output <ir.json>]
```

`check`는 여러 파일의 문법·의미·교차 참조 오류를 모두 출력하고 하나라도 실패하면
exit code 1을 반환한다. `format --check`는 파일을 바꾸지 않고 canonical 포맷과 다른
파일이 있으면 exit code 1을 반환한다. `ast`는 wire version envelope를 JSON으로
출력한다.

`compile`은 의미 검증을 통과한 AST를 Runtime IR V1으로 lowering한다. `--output`을
생략하면 stdout에만 출력하며, 명시한 경우에만 부모 디렉터리를 만들고 UTF-8/LF JSON을
기록한다. IR의 상세 계약은 [CVES Runtime IR V1](CVES_RUNTIME_IR_V1.md)을 따른다.

## 11. 콘텐츠 프로젝트 배치와 생성

Git에서 관리하는 권위 원본은 다음 경로에 둔다.

```text
content/events/<namespace>/<path>.cves
content/event-bindings/<namespace>/<path>.json
```

CVES 경로는 별도 설정 없이 `<namespace>:event_script/<path>` script ID가 된다.
바인딩 JSON은 `schema_version`과 `script_id`만 가지며 같은 프로젝트에서 컴파일되는
스크립트만 참조할 수 있다. `build.bat validate`는 모든 CVES의 구문·의미·프로젝트
리소스와 바인딩 참조를 함께 검사한다.

`build.bat generate`는 다음 결정적 산출물을 만든다.

```text
generated/cves/data/<namespace>/event_script/<path>.json
generated/cves/data/<namespace>/npc_event_binding/<path>.json
```

mod-builder는 이 `data` 트리를 데이터 모드에 그대로 합친다. 생성기는 기존
`content/source` V4 JSON이나 `data/easy_npc/preset` 파일을 변환·수정하지 않는다.

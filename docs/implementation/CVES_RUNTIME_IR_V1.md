# CVES Runtime IR V1 계약

> 상태: Python compiler 출력 계약
>
> 범위: 주소 기반 제어 흐름, await 재개 주소, 일회성 작업 ID와 source map

Runtime IR은 `.cves`를 게임 런타임이 직접 파싱하지 않도록 만드는 결정적 JSON
산출물이다. 사람이 편집하는 원본이 아니며 같은 canonical AST와 script ID는 같은
명령·작업 ID와 `source_digest`를 생성한다.

## 1. 최상위 구조

```json
{
  "schema_version": 1,
  "script_id": "cobbleventure:event_script/story/professor_oak",
  "source_digest": "sha256...",
  "events": []
}
```

script ID는 `namespace:event_script/path` 형식이다. digest는 원본 공백이 아니라
canonical formatter 결과의 SHA-256이므로 의미가 같은 포맷 변경에는 영향을 받지
않는다.

각 event는 trigger, 우선순위 순서의 page, 0부터 연속된 instruction 배열과 source
map을 가진다. page의 `entry`가 해당 페이지의 첫 명령 주소이며 condition이 `null`인
페이지가 default다.

## 2. 안정 ID와 작업 ID

CVES 문장은 다음처럼 선택적인 안정 ID를 가질 수 있다.

```cves
id "first/give_pokedex" give_item "cobblemon:pokedex_red" count 1 notify
```

- 형식은 소문자 segment를 `/`로 연결한 값이다.
- 한 스크립트에서 중복할 수 없다.
- 보상·영구 상태 변경·비동기 명령은 IR 컴파일 전에 안정 ID가 반드시 필요하다.
- 일반 대사와 순수 제어 명령은 구조 경로 기반 instruction ID를 사용할 수 있다.
- 일회성 작업 ID는 `<script_id>/<stable_id>`이며 원본 줄이나 현재 주소에 의존하지 않는다.

주소는 현재 컴파일 버전에서만 유효하다. 영구 세션은 프로그램 카운터, 호출 스택,
await 재개점과 choice target에 현재 주소와 `instruction_id`를 함께 저장한다. digest가
바뀌면 동일 event trigger 안에서 안정 ID를 새 주소로 원자적으로 재배치하고, ID가
없거나 중복 후보가 생기면 실행을 거부한다. 완료된 부작용과 타입 결과 복원은 별도의
`operation_id` 저널이 담당하며, source map은 주소와 안정 ID를 원본 위치에 연결한다.

await 중인 instruction은 같은 `instruction_id`뿐 아니라 명령 종류와 `operation_id`도
유지해야 한다. 의미가 바뀐 명령에 기존 안정 ID를 재사용하면 저장 callback은
`SCRIPT_MISMATCH`로 거부된다.

## 3. 명령과 제어 흐름

모든 instruction은 `address`, `instruction_id`, `op`를 가진다.

| op | 제어 필드 |
|---|---|
| `say`, `narrate` | `next`, `await: true`, `resume` |
| `let` | `name`, `value`, `next` |
| `command` | `command`, arguments, properties, result, `next` |
| `branch` | condition, `then`, `else` |
| `choice` | prompt, option별 `target`, result, `await: true` |
| `repeat_begin` | count, `body`, `exit` |
| `repeat_next` | 반복 시작 `target` |
| `jump` | 절대 `target` |
| `call` | `target`, `return_address` |
| `return` | 런타임 호출 스택에서 복귀 |
| `label` | 고급 흐름 진단 및 source map용 no-op |
| `page_end` | 페이지 정상 종료 |

명시적 `await`와 아이템 획득·연출처럼 compiler가 자동 대기시키는 명령은 모두
`await: true`와 `resume` 주소를 갖는다. 원본에 await가 쓰였는지는
`await_explicit`으로 구분한다. 콜백에는 다음 대화 라벨이 아니라 세션 토큰,
operation ID와 결과 값만 전달한다.

`choice`는 하나의 고정 `resume` 주소 대신 각 option의 `target`을 대기 상태에
보존한다. 콜백은 0-based option index를 반환하며, 런타임은 범위를 검증한 뒤
해당 `target`에서 재개하고 `result`가 있으면 같은 index를 `int` 지역 변수로
저장한다.

## 4. 식과 텍스트

식은 `literal`, `name`, `member`, `call`, `unary`, `binary`의 닫힌 JSON 합집합이다.
리터럴은 타입을 포함하고 함수 인자는 이름과 값 구조를 보존한다. 현지화 텍스트는
locale entry 배열로 저장하여 작성 순서와 각 언어 템플릿을 유지한다.

## 5. Source map

각 instruction과 같은 주소의 source map 항목은 다음을 가진다.

```json
{
  "address": 12,
  "instruction_id": "first/give_pokedex",
  "stable_id": "first/give_pokedex",
  "span": {
    "source": "professor_oak.cves",
    "start": { "offset": 100, "line": 20, "column": 5 },
    "end": { "offset": 180, "line": 20, "column": 85 }
  }
}
```

span이 없는 GUI AST도 컴파일할 수 있으며 이때 span은 `null`이다. source map은
진단과 개발 도구용이고 실행 의미나 digest 계산에는 포함되지 않는다.

## 6. 현재 경계

Python compiler는 페이지, 중첩 분기, 선택지, 반복, label/jump/call/return과 await
주소를 lowering한다. Java 런타임은 IR loader, 영구 세션 저장, 닫힌 표현식 합집합,
페이지 선택과 제어 흐름을 실행한다. 실제 서버 상태와 대화·보상·배틀·이동은
환경 및 명령 어댑터를 통해 후속 연결한다. 현재 Java 런타임의 경계는
[CVES 서버 런타임 코어 V1](CVES_SERVER_RUNTIME_CORE_V1.md)에 기록한다.

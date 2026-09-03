# 퀘스트 시스템

> 상태: 서버 런타임, V5 연동 및 메인 퀘스트 전역 발동

퀘스트 원본은 기존 콘텐츠 관리 웹에서 작성하고 게임 서버가 플레이어별 진행
상태를 관리한다. 별도 웹 서비스나 별도 실행 프로그램을 만들지 않는다.

## 원본과 런타임

- 원본: `content/quests/<namespace>/<path>.json`
- 스키마: `content/schemas/quest.schema.json`
- 게임 리소스: `data/<namespace>/quest/<path>.json`
- 플레이어 상태: `cobbleventureQuestProgress` 영속 NBT

NPC는 퀘스트 상태를 직접 저장하지 않는다. 서로 다른 V5 NPC도 같은 플레이어의
퀘스트 ID를 사용하면 같은 상태를 읽고 변경한다.

## 상태

| 상태 | 의미 |
| --- | --- |
| `not_started` | 부여되지 않음 |
| `active` | 진행 중 |
| `ready` | 모든 목표 조건을 만족함 |
| `completed` | 완료 처리됨 |

`quest_check`는 현재 공용 플레이어 조건을 다시 평가해 `active` 또는 `ready`를
반환한다. `quest_complete`도 조건을 다시 검사하므로 클라이언트나 NPC 대사만으로
완료 상태를 위조할 수 없다.

서버는 전역 발동과 활성 퀘스트를 20틱마다 재평가한다. 따라서 다른 차원에서 아이템,
배지, 플래그나 파티 상태가 바뀌어도 퀘스트 부여, 완료 가능 상태와 `automatic`
완료가 반영된다. 후속 단계에서는 각 조건 공급자의 변경 이벤트를 연결해 이 주기
검사를 대체할 수 있다.

## 메인 퀘스트 전역 발동

기존 관리 웹의 `게임 시스템 > 메인 퀘스트 전역 발동` 화면에서 설정한다. 별도
프로그램이나 서버는 없으며, 해당 화면은 선택한 메인 퀘스트 문서의
`global_activation`만 편집한다.

```json
{
  "global_activation": {
    "enabled": true,
    "conditions": {
      "condition_mode": "all",
      "conditions": [
        {
          "type": "flag",
          "key": "cobbleventure:flag/story/arrived_cerulean",
          "value": true
        }
      ]
    }
  }
}
```

서버는 플레이어가 다음 조건을 모두 만족할 때 해당 퀘스트를 자동 부여한다.

1. 퀘스트가 `enabled`인 메인 퀘스트이다.
2. 현재 상태가 `not_started`이다.
3. `global_activation.conditions`가 참이다.
4. 퀘스트 공통 `accept_conditions`도 참이다.

전역 발동 조건은 하나 이상 필요하다. 따라서 설정 실수로 모든 플레이어에게 메인
퀘스트가 즉시 부여되는 것을 방지한다. NPC와 V5의 `quest_grant` 방식은 그대로 함께
사용할 수 있다.

## 메인 퀘스트 진행 순서와 체육관 기본값

`게임 시스템 > 메인 퀘스트 진행 순서`에서 메인 퀘스트와 진행 NPC를 순서대로
연결한다. 원본은 `content/catalogs/main-quest-progression.json`, 런타임 리소스는
`data/cobbleventure/main_quest/progression.json`이다.

진행 선택 우선순위는 다음과 같다.

1. 진행 문서에서 완료되지 않은 첫 단계만 전역 발동 후보가 된다.
2. 그 단계의 퀘스트가 `active` 또는 `ready`이면 현재 메인 퀘스트로 선택한다.
3. 활성화된 문서 퀘스트가 없으면 다음 미클리어 체육관이 기본 메인 퀘스트가 된다.
4. 현재 단계를 완료하면 문서의 다음 NPC 단계가 후보가 된다.

이 규칙으로 체육관 사이에 스토리 NPC 퀘스트를 삽입할 수 있다. 예를 들어 두 번째
단계의 `global_activation`에 돌배지 보유 조건을 넣으면, 첫 체육관 전까지는 체육관이
기본 목표가 되고 돌배지를 얻은 직후 해당 NPC 퀘스트가 우선 목표가 된다.

첫 기본 문서는 `오박사에게 첫 포켓몬 받기` 퀘스트를 포함한다. 새 플레이어에게
자동 부여되며 `cobbleventure:flag/story/pokedex_received`가 기록되면 자동 완료된다.

### 관동의 오박사 이후 진행

`HM_TRAVERSAL.md` 9절과 `NPC_REWARD_CATALOG.md`의 확정 획득 동선을 따른다.
일반 체육관 도전은 기본 안내로 남기고, 아래 단계만 메인퀘스트로 우선 안내한다.

| 단계 | 발동 시점 (앞 단계 완료 후) | 완료 조건 |
|------|-----------------------------|-----------|
| 민호에게 포켓내비 받기 | 첫 파트너 수령 | 포켓내비 수령 플래그 |
| 상록시티에서 마을지도 받기 | 포켓내비 수령 | 지도 해금 플래그 |
| 달맞이산 너머 순간이동 배우기 | 회색배지 | 블루시티 입구 초능력자의 순간이동 해금 |
| 이수재의 포켓몬 전송망에 등록하기 | 회색배지·앞 단계 완료 | 25번도로 이수재의 PC 해금 |
| 발전소를 되찾고 플래시 얻기 | 오렌지배지 | 발전소 첫 클리어 보상인 플래시 권한 |
| 독수에게 도전하고 파도타기 얻기 | 무지개배지 | 핑크배지와 파도타기 권한 |
| 비주기를 이기고 락클라임 얻기 | 핑크배지 | 그린배지와 락클라임 권한 |
| 석영고원에 올라 공중날기 배우기 | 관동 8배지와 락클라임 권한 | 도착 길목 NPC의 공중날기 권한 |

모두 `automatic` 완료이며 보상을 다시 지급하지 않는다. 기존 NPC·관장·던전의
지급 처리가 유일한 보상 원본이다. 비전머신은 `variable / persistent_data` 조건으로
실제 영구 권한 키 `cobbleventureFieldMove.<move>`를 확인한다. 던전·관장 보상은
`flag/rewards/field_move/...`를 기록하지 않으므로 그 플래그를 완료 조건으로 쓰지 않는다.
이미 보상을 받은 플레이어도 기존 권한으로 완료되어 다음 단계로 진행한다.

노랑시티의 지정 6배지 조건에는 그린배지가 포함된다. 이를 만족하려면 체육관 입장
선행 조건이 `연분홍 → 상록 → 노랑 → 홍련`이어야 한다. 상록 입장에 크림슨배지를
요구하면 `노랑 → 홍련 → 상록 → 노랑` 순환이 생기므로, `gyms.json`의 상록 선행
배지는 핑크배지, 노랑 선행 배지는 그린배지로 맞춘다. 리그의 관장 번호·레벨 상한은
바꾸지 않는다. 상록 완료 후에는 노랑·홍련 체육관 기본 안내가 이어지고, 8배지 전에는
석영고원 퀘스트가 발동하지 않는다.

독수와 비주기는 `content/source` NPC가 아닌 리그 카탈로그가 생성하는 관장이다.
진행 화면의 NPC 목록과 참조 검증은 리그의 실제 배틀 문서 `trainer_id`도 포함한다.
미배치 비전머신 교관을 대신 목표로 지정하거나 관장 원본을 중복 생성하지 않는다.

포켓파인더도 같은 현재 메인 퀘스트 선택 결과를 사용한다. 문서 퀘스트가 활성화되면
해당 단계의 NPC를 `OBJECTIVE/PRIMARY`로 표시하고, 문서 퀘스트가 없을 때는 기존 다음
체육관 목표를 표시한다. 활성 문서 퀘스트의 NPC가 아직 로드되지 않았을 때 다른
체육관을 잘못 주 목표로 표시하지 않는다.

### 메뉴 퀘스트 안내의 축약 표시

메뉴 왼쪽 여유 공간에 따라 `QuestSummaryLayout`이 상세형(최대 330×142),
축약형(폭 112~219, 최대 높이 96), 정보 패널 내 표시로 전환한다. 별도 패널을
놓을 폭이 없으면 선택한 메뉴 항목과 관계없이 기존 정보 패널의 설명 영역에
현재 퀘스트 제목과 핵심 목표를 표시한다. 상세 퀘스트 창은 기존 퀘스트 메뉴로 연다.

축약 시 설명·종류·상태·헤더 순으로 부가 정보를 줄이고 제목과 목표를 우선한다.
폰트는 전역 테마의 `HEADING`, `LABEL`, `CAPTION`을 사용하며 실제 줄 높이로
표시 줄 수를 제한한다. 하단 키 안내가 정보 패널을 덮게 되는 화면에서는 키 안내를
생략하고 메뉴 헤더의 공통 뒤로 버튼은 유지한다.

## CVES V5

```cves
event interact(range: 4) {
  page when quest_state("cobbleventure:quest/main/get_cut") == "completed" {
    say npc "이미 필요한 준비를 마쳤구나."
  }

  page default {
    id "quest/grant" quest_grant "cobbleventure:quest/main/get_cut" -> granted
    quest_check "cobbleventure:quest/main/get_cut" -> checked
    if checked.ready {
      id "quest/complete" quest_complete "cobbleventure:quest/main/get_cut" -> completed
    }
  }
}
```

세 명령은 `quest_result`를 반환한다. 주요 필드는 `state`, `granted`, `ready`,
`completed`, `failure_reason`이다. 부여와 완료는 퀘스트 ID 기준으로 멱등하다.

## 조건

수락 조건과 각 목표 조건은 `PLAYER_CONDITIONS.md`의 공용 규격을 사용한다. 서버는
플레이어 메뉴 모듈의 `PlayerConditions` 판정기를 호출하므로 관문, 건물, NPC와
퀘스트가 같은 아이템·배지·플래그·파티 조건 의미를 공유한다.

비전머신 등 필수 도구는 `guidance.required_tools`에 안내 정보로 기록한다. 실제
사용 권한은 기존 필드 이동 시스템이 계속 소유한다.

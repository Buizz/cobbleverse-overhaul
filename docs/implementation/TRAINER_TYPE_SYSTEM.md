# 트레이너 타입 분류 체계

## 목적

트레이너의 외형, 전투 방식, 난이도, 마을 내 역할을 한 개의 `type` 문자열에 섞지 않고 독립된 카탈로그로 관리한다. 실제 NPC 스폰은 다음 구현 묶음에서 진행하지만, 이번 묶음에서 데이터 계약과 검증을 확정한다.

- 관련 기능: `TRAINER-TYPE-01`, `TRAINER-SPAWN-PREP-01`
- 결정: `DEC-TRAINER-009` — 트레이너 분류를 4개 축으로 분리한다.
- 상태: 데이터 계약 구현 예정, NPC 스폰은 후속 작업

## 네 가지 분류 축

| 축 | 예 | 담당 내용 |
|---|---|---|
| `trainer_class` | 반바지 꼬마, 곤충채집소년, 체육관 관장 | 호칭, 기본 외형, 초상화 |
| `battle_archetype` | 균형, 공격, 교체, 상태이상 | 팀 구성 규칙과 AI 프로필 |
| `difficulty` | 초보, 일반, 상급, 전문가 변형, 치터 | RCT/자체 AI 난이도와 행동 확률 |
| `settlement_role` | 관장, 고정 도전자, 순찰, 주민 | 배치 위치, 이동, 재생성 정책 |

기존 `content/catalogs/trainer-classes.json`은 `trainer_class`의 기준으로 유지한다. 난이도와 전투 전략은 별도 카탈로그를 참조한다.

## 트레이너 데이터 예시

```json
{
  "npc": {
    "trainer_class": "cobbleventure:trainer_class/bug_catcher"
  },
  "battle": {
    "archetype": "cobbleventure:battle_archetype/status_control",
    "difficulty": "cobbleventure:difficulty/expert_switch",
    "cheater": {
      "enabled": false,
      "activation_chance": 0.0
    }
  },
  "placement": {
    "role": "cobbleventure:settlement_role/route_challenger",
    "tags": ["starter_town", "daytime"]
  }
}
```

## 카탈로그 계획

- `trainer-classes.json`: 이름 형식과 외형
- `battle-archetypes.json`: AI 프로필, 팀 태그와 선호 전술
- `difficulty-profiles.json`: 전문가 세부 유형과 치터 확률을 포함한 실제 런타임 수치
- `settlement-roles.json`: 고정/배회, 구역, 상호작용 거리, 스폰 정책

전문가를 하나로 축약하지 않는다. RCT가 제공하는 전문가 변형을 각각 안정적인 내부 ID로 매핑한다. 치터 모드는 `enabled`와 0~1의 확률을 모두 보존하며, 빌드 시 대상 RCT JSON 필드로 변환한다.

## 마을과의 연결

마을은 트레이너 자체를 소유하지 않고 배치 슬롯과 허용 풀을 소유한다.

```json
{
  "trainer_population": {
    "budget": 18,
    "class_pool": [
      { "trainer_class": "cobbleventure:trainer_class/youngster", "weight": 4 },
      { "trainer_class": "cobbleventure:trainer_class/bug_catcher", "weight": 2 }
    ],
    "required_slots": ["gym_leader", "rival_intro"]
  }
}
```

실제 배치 시에는 마을의 구역·슬롯이 트레이너 ID 또는 풀을 선택한다. 이렇게 하면 트레이너 편집 화면에서 마을 좌표를 직접 관리하지 않아도 된다.

## 완료 기준

- 네 분류 축의 ID가 겹치거나 의미가 혼합되지 않는다.
- 기존 트레이너 JSON을 새 구조로 손실 없이 변환할 수 있다.
- 전문가 변형과 치터 확률이 정규화 JSON과 RCT 출력에서 일치한다.
- 마을 데이터가 존재하지 않는 트레이너·클래스·역할을 참조하면 빌드가 실패한다.
- 후속 NPC 스포너가 사용할 슬롯·구역·인구 예산 계약이 문서와 스키마에 고정된다.

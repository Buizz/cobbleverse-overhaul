# 트레이너 클래스와 외형 관리

## RCT 조사 결과

RCT는 전투 팀 정의와 NPC 외형을 한 JSON에서 관리하지 않는다.

- `data/rctmod/trainers/*.json`: 이름, 포켓몬 팀과 전투 데이터
- `data/rctmod/mobs/trainers/**/*.json`: 트레이너 NPC의 전투 유형, 스폰과 진행 설정
- `assets/rctmod/textures/trainers/**/*.png`: 플레이어 스킨 형식의 실제 외형
- `data/rctmod/trainer_types/*.json`: Trainer Card의 탭, 표시 이름, 기호와 색상

RCT Mob의 `type`은 외형 클래스가 아니라 RCT 내부 트레이너 유형이다. 본가식
`반바지 꼬마`, `곤충채집소년`, `학원 끝난 아이` 같은 분류와 혼용하지 않는다.

참고 자료:

- [RCT Mobs 문서](https://srcmc.gitlab.io/rct/docs/latest/configuration/data_pack/mobs/)
- [RCT Textures 문서](https://srcmc.gitlab.io/rct/docs/latest/configuration/resource_pack/textures/)
- [RCT Trainer Types 문서](https://srcmc.gitlab.io/rct/docs/latest/configuration/data_pack/trainer_types/)

## Cobbleventure 기준 구조

트레이너 번들의 `npc`는 다음 두 정보를 분리해서 관리한다.

```json
{
  "trainer_class": "cobbleventure:trainer_class/bug_catcher",
  "appearance": {
    "source": "rct_single",
    "type": "skin",
    "resource": "rctmod:trainers/single/bug_catcher_rick_0066"
  }
}
```

| 필드 | 의미 |
|------|------|
| `trainer_class` | 대사에서 사용할 본가식 직업·역할과 기본 외형 프리셋 |
| `appearance.source` | `custom`, `rct_single`, `rct_group` 중 외형의 출처 |
| `appearance.type` | 플레이어 스킨 또는 별도 모델 |
| `appearance.resource` | 빌드 출력기가 해석할 리소스 ID |

클래스 목록과 `직업명 + 이름` 패턴은
`content/catalogs/trainer-classes.json`에서 관리한다. 개별 트레이너는 클래스의
기본 외형을 그대로 사용하거나 외형 리소스만 덮어쓸 수 있다.

## RCT 출력 매핑

| 원본 설정 | RCT 리소스 경로 |
|-----------|-----------------|
| `rct_single` + `rctmod:trainers/single/example` | `assets/rctmod/textures/trainers/single/example.png` |
| `rct_group` + `rctmod:trainers/groups/example` | `assets/rctmod/textures/trainers/groups/example.png` |
| `custom` | Cobbleventure 자체 NPC 또는 선택한 NPC 어댑터 리소스로 출력 |

현재 관리 화면의 RCT 미리보기는 조사 기준인 RCT `1.21.1` 브랜치의 이미지를
참조한다. 실제 빌드 출력기는 `pack/dependencies.lock.json`에서 확정된 RCT 버전을
기준으로 매핑해야 한다.

## 포켓몬 이미지

관리 화면의 포켓몬 엔트리 이미지는 Pokémon Showdown 서버가 아니라
[PokéAPI sprites 저장소](https://github.com/PokeAPI/sprites)의 HOME PNG를 사용한다.
종 ID로 PokéAPI를 조회해 `sprites.other.home.front_default`를 우선 사용하고,
없으면 official artwork로 대체한다.

이 이미지는 제작 화면의 원격 미리보기에만 사용하며 모드팩에 복사하지 않는다.
인터넷 연결이 없거나 Cobblemon 전용 종이라 PokéAPI에 없으면 이미지 없는 편집
카드로 계속 작업할 수 있다.


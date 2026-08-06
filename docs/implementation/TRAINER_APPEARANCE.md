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

클래스 카탈로그는 외형과 함께 Minecraft 체형을 정의한다.

```json
{
  "category": "children",
  "body": {
    "age_group": "child",
    "height_scale": 0.78,
    "arm_model": "classic"
  }
}
```

`height_scale`은 웹 미리보기와 향후 게임 NPC 어댑터가 공통으로 사용하는 키
배율이다. 어린이 클래스는 `0.66`~`0.80`, 청소년은 `0.88`~`0.94`, 성인은
기본적으로 `1.0`을 사용한다. 게임 어댑터는 렌더 크기뿐 아니라 충돌 상자와
시점 높이에도 같은 배율을 적용해야 한다.

| 필드 | 의미 |
|------|------|
| `trainer_class` | 대사에서 사용할 본가식 직업·역할과 기본 외형 프리셋 |
| `appearance.source` | `custom`, `rct_single`, `rct_group` 중 외형의 출처 |
| `appearance.type` | 플레이어 스킨 또는 별도 모델 |
| `appearance.resource` | 빌드 출력기가 해석할 리소스 ID |

`default_appearance.implementation_status`는 `ready` 또는 `placeholder`이다.
미완성 클래스도 선택과 인게임 시험을 막지 않도록
`cobbleventure:trainer_skin/unimplemented`에 연결한다. 이 리소스는 보라색
체크무늬와 물음표가 있는 유효한 64×64 Minecraft 스킨이며 다음 경로에 있다.

```text
projects/cobbleventure-world-bootstrap/src/main/resources/assets/cobbleventure/textures/entity/trainer/unimplemented.png
```

원본은 `tools/content-manager/generate_placeholder_skin.py`로 재현할 수 있다.

클래스 목록과 `직업명 + 이름` 패턴은
`content/catalogs/trainer-classes.json`에서 관리한다. 개별 트레이너는 클래스의
기본 외형을 그대로 사용하거나 외형 리소스만 덮어쓸 수 있다.

## RCT 출력 매핑

| 원본 설정 | RCT 리소스 경로 |
|-----------|-----------------|
| `rct_single` + `rctmod:trainers/single/example` | `assets/rctmod/textures/trainers/single/example.png` |
| `rct_group` + `rctmod:trainers/groups/example` | `assets/rctmod/textures/trainers/groups/example.png` |
| `custom` | Cobbleventure 자체 NPC 또는 선택한 NPC 어댑터 리소스로 출력 |

현재 관리 화면은 64×64 스킨의 머리·몸·팔·다리 여섯 면을 Minecraft 큐브에
매핑해 입체로 표시한다. RCT 외형은 조사 기준인 RCT `1.21.1` 브랜치 이미지를
참조하며, 불러오지 못하면 로컬 미구현 스킨이 보인다. 자체 외형은 로컬
`/api/trainer-skin`을 통해 같은 3D 미리보기에 표시된다. 선택한 클래스의
`height_scale`과 `arm_model`도 미리보기에 반영된다.

RCT의 개별 NPC 스킨은 본가 트레이너 클래스의 외형을 재현하기 위한 전용 스킨이
아니다. 따라서 관리 화면은 현재 Minecraft 외형 옆에 세대가 명시된 본가 전투
스프라이트를 디자인 기준으로 함께 표시한다. 참조 이미지는 관리 화면에서만
Pokémon Showdown의 트레이너 스프라이트 미러를 원격 조회하며 저장소나 빌드
결과물에 복사하지 않는다. 해당 미러에 일부 팬 제작 이미지가 포함될 수 있으므로
가능하면 `*-gen3`, `*-gen4`처럼 본가 세대가 명시된 파일을 우선한다. 정확한
클래스 이미지가 없거나 조회에 실패하면 다른 인물을 대신 표시하지 않고
`참조 이미지 준비 중`으로 남긴다.

실제 빌드 출력기는 `pack/dependencies.lock.json`에서 확정된 RCT 버전을 기준으로
매핑해야 한다.

## 포켓몬 이미지

관리 화면의 포켓몬 엔트리 이미지는 Pokémon Showdown 서버가 아니라
[PokéAPI sprites 저장소](https://github.com/PokeAPI/sprites)의 HOME PNG를 사용한다.
종 ID로 PokéAPI를 조회해 `sprites.other.home.front_default`를 우선 사용하고,
없으면 official artwork로 대체한다.

이 이미지는 제작 화면의 원격 미리보기에만 사용하며 모드팩에 복사하지 않는다.
인터넷 연결이 없거나 Cobblemon 전용 종이라 PokéAPI에 없으면 이미지 없는 편집
카드로 계속 작업할 수 있다.

# Radical Gyms & Structures 통합 기준

> 결정: 체육관 외관은 Cobbleventure가 생성하고, 내부와 리그는 Radical Gyms &
> Structures(RGS) 0.6의 원본 구조물을 사용한다.

## 역할 분리

- 마을에 보이는 체육관은 `tools/mod-builder/starter_gym.py`가 만드는 공통 외관이다.
- 외관의 형태와 포켓볼 표식은 모든 타입에서 같고, 지붕 콘크리트와 입구 카펫의
  색만 마을 JSON의 `gym_theme`에 맞게 바꾼다.
- 외관 안쪽 로비의 진입 지점에 닿으면 같은 세대 차원의 격리된 좌표에 배치한
  RGS 체육관 내부로 이동한다.
- RGS 체육관 NBT는 수정하거나 재배포용 파일로 저장소에 복사하지 않는다.
  CurseForge manifest가 원본 파일을 설치하고 런타임에서 `rgs:*` 템플릿을 읽는다.
- 리그는 외관 교체나 블록 치환 없이 `rgs:kanto_league`를 그대로 배치한다.

이 분리는 마을의 시각적 테마와 관장전 콘텐츠를 독립시킨다. 체육관 타입이
늘어나도 외관 생성기는 색상만 추가하면 되고, 실내를 교체해도 마을 구조를 다시
설계할 필요가 없다.

## 고정 버전과 구조물 ID

| 항목 | 값 |
|------|----|
| Radical Gyms & Structures | 0.6 / CurseForge `1402174:7330950` |
| Radical Cobblemon Trainers API | 0.15.2-beta / `1152792:7952419` |
| Radical Cobblemon Trainers | 0.18.1-beta / `1009534:7913180` |
| CobbleFurnies | 1.0 / `1188698:7302031` |
| Architectury API | 13.0.8 / `419699:5786327` |

RGS 0.6에서 확인한 체육관 템플릿은 다음과 같다.

- `rgs:pewter_gym`
- `rgs:cerulean_gym`
- `rgs:vermilion_gym`
- `rgs:celadon_gym`
- `rgs:fuchsia_gym`
- `rgs:saffron_gym`
- `rgs:cinnabar_gym`
- `rgs:blackthorn_gym`
- 리그: `rgs:kanto_league`

체육관은 약 25×13×26 블록이지만 리그는 약 73×170×78 블록이므로, 리그는
일반 마을의 체육관 인스턴스 좌표가 아니라 전용 리그 지역에 충분한 간격을 두고
배치한다. RGS가 제공하지 않는 벌레 타입 등의 체육관은 임의의 관장전을 연결하지
않고, 대응 실내를 정한 뒤 명시적으로 JSON에 추가한다.

## 마을 JSON 계약

진입형 체육관은 `structure_profile.facility_placements`에 선언한다.

```json
{
  "id": "gym_interior",
  "mode": "instanced_entry",
  "structure": "rgs:pewter_gym",
  "entry_anchor": "gym_entrance",
  "return_anchor": "gym_return",
  "instance_origin": { "x": 2048, "y": 69, "z": 0 },
  "instance_entry_offset": { "x": 12, "y": 4, "z": 4 },
  "instance_exit_offset": { "x": 12, "y": 4, "z": 1 },
  "trigger_radius": 1.75
}
```

- `entry_anchor`: 마을 외관 로비 안의 감지 지점
- `return_anchor`: 실내에서 나온 플레이어가 돌아올 외관 앞 지점
- `instance_origin`: RGS 템플릿을 배치할 절대 좌표
- `instance_entry_offset`, `instance_exit_offset`: RGS 템플릿 원점 기준 이동 지점
- 재진입 반복을 막기 위해 이동 후 40틱 쿨다운을 적용한다.

리그처럼 원본 구조물을 지역 앵커에 그대로 놓을 때는 다음 형식을 사용한다.

```json
{
  "id": "kanto_league",
  "mode": "direct_template",
  "structure": "rgs:kanto_league",
  "anchor": "league_origin"
}
```

## 배치와 운영 규칙

1. 새 월드 초기화 시 마을 외관을 먼저 배치한다.
2. `instanced_entry`는 `instance_origin`에, `direct_template`은 지정 앵커에
   `/place template`으로 RGS 구조물을 배치한다.
3. 하나라도 배치에 실패하면 지도 완료 상태를 저장하지 않아 다음 접속에서
   재시도할 수 있게 한다.
4. RGS 구조물의 명령 블록 동작을 사용하는 서버는 `enable-command-block=true`로
   실행한다.
5. 시작 마을은 바위 타입 외관과 `rgs:pewter_gym`을 연결한다. 두 번째 벌레 테마
   테스트 마을은 현재 외관 색상만 생성하며 대응 RGS 실내를 억지로 연결하지 않는다.
6. 지도 계약이 바뀌었으므로 기존 테스트 월드 대신 새 월드에서 검증한다.

## 검증 체크리스트

- 개발 팩 manifest에 RGS와 네 가지 필수 의존성이 모두 들어간다.
- 시작 마을 외관 지붕은 회색이며 두 번째 마을 지붕은 연두색이다.
- 시작 마을 로비 진입 시 Pewter 체육관 내부로 이동한다.
- 내부 출구 지점에서 외관 앞 `gym_return`으로 돌아온다.
- 재접속 후에도 포탈이 동작하고 구조물이 중복 배치되지 않는다.
- 리그 전용 마을 데이터를 추가했을 때 원본 팔레트가 바뀌지 않는다.


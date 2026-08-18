# 메뉴·비전머신 권한 커맨드

플레이어별 메뉴 잠금과 비전머신 권한을 운영·테스트할 때 사용하는 커맨드 목록이다.
권한 변경값은 플레이어 영속 데이터에 저장되므로 재접속과 서버 재시작 후에도 유지된다.

## 공통 규칙

- 권한을 지급하거나 회수하는 명령은 권한 레벨 2 이상이 필요하다.
- `<players>`에는 플레이어 이름 또는 `@s`, `@a`, `@p` 같은 선택자를 사용한다.
- 명령 인수의 메뉴 ID와 비전머신 ID는 자동완성된다.
- `on`은 권한 지급, `off`는 권한 회수를 뜻한다.

## 메뉴 권한

### 문법

```mcfunction
/cobbleventure_progress on <players> <feature>
/cobbleventure_progress off <players> <feature>
```

### 기능 ID

| ID | 기능 | OFF 상태 |
|---|---|---|
| `map` | 월드맵 | 지도 메뉴 비활성화 |
| `settlement_teleport` | 발견한 마을로 순간이동 | 월드맵의 마을 순간이동 비활성화 |
| `pc` | 플레이어 메뉴의 포켓몬 PC | PC 메뉴 비활성화 |

### 예시

```mcfunction
# 자신의 월드맵 해금
/cobbleventure_progress on @s map

# 모든 플레이어의 마을 순간이동 해금
/cobbleventure_progress on @a settlement_teleport

# Steve의 포켓몬 PC 잠금
/cobbleventure_progress off Steve pc
```

기존 스크립트 호환을 위해 다음 문법도 유지한다.

```mcfunction
/cobbleventure_progress unlock <players> <feature>
/cobbleventure_progress lock <players> <feature>
```

레벨캡은 같은 명령 루트에서 별도로 설정한다.

```mcfunction
/cobbleventure_progress level_cap <players> <1..100>
```

## 비전머신 보유 권한

### 문법

```mcfunction
/cobbleventure_field_move on <players> <move>
/cobbleventure_field_move off <players> <move>
```

### 지원 ID

| ID | 표시 이름 | 사용 방식 |
|---|---|---|
| `surf` | 파도타기 | 보유 시 수상 탑승 허용 |
| `fly` | 공중날기 | 보유 시 비행 탑승 허용 |
| `flash` | 플래쉬 | 보유 후 활성 상태 ON/OFF |
| `defog` | 안개제거 | 보유 권한 검사 |
| `rock_climb` | 락클레임 | 보유 후 활성 상태 ON/OFF |
| `whirlpool` | 바다회오리 | 보유 권한 검사 |
| `strength` | 괴력 | 보유 후 활성 상태 ON/OFF |
| `rock_smash` | 바위깨기 | 보유 후 활성 상태 ON/OFF |

`cut`과 `waterfall`은 현재 지원 목록에 포함되지 않는다.

### 예시

```mcfunction
# 자신의 파도타기 권한 지급
/cobbleventure_field_move on @s surf

# 모든 플레이어에게 플래쉬 권한 지급
/cobbleventure_field_move on @a flash

# Steve에게서 공중날기 권한 회수
/cobbleventure_field_move off Steve fly
```

기존 스크립트 호환을 위해 다음 문법도 유지한다.

```mcfunction
/cobbleventure_field_move grant <move>
/cobbleventure_field_move grant <players> <move>
/cobbleventure_field_move revoke <move>
/cobbleventure_field_move revoke <players> <move>
```

## 비전머신 활성 상태

보유 권한과 활성 상태는 서로 다르다. 관리자용 권한 명령은 `on`이 먼저 나오고,
플레이어용 활성 명령은 비전머신 ID가 먼저 나온다.

```mcfunction
# 권한 지급: 관리자용
/cobbleventure_field_move on @s flash

# 보유한 기술 활성화: 플레이어용
/cobbleventure_field_move flash on
/cobbleventure_field_move flash off
/cobbleventure_field_move flash toggle
```

활성 상태를 바꿀 수 있는 기술은 다음 네 가지다.

- `flash`
- `rock_climb`
- `strength`
- `rock_smash`

보유하지 않은 기술은 활성화할 수 없다. 권한을 `off`로 회수하면 해당 기술의 활성
상태도 함께 OFF로 초기화된다.

## 빠른 테스트

```mcfunction
# 메뉴 기능 전체 해금
/cobbleventure_progress on @s map
/cobbleventure_progress on @s settlement_teleport
/cobbleventure_progress on @s pc

# 비전머신 권한 전체 해금
/cobbleventure_field_move on @s surf
/cobbleventure_field_move on @s fly
/cobbleventure_field_move on @s flash
/cobbleventure_field_move on @s defog
/cobbleventure_field_move on @s rock_climb
/cobbleventure_field_move on @s whirlpool
/cobbleventure_field_move on @s strength
/cobbleventure_field_move on @s rock_smash
```

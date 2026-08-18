# Cobbleventure World Bootstrap

이 모드는 세대 월드와 구조물 배치를 담당한다. 전투 보상, 패배 경제, 포켓몬센터
귀환, 야생 레벨과 필드 기술 규칙은 `cobbleventure-adventure` 모드로 분리되어 있다.
음악, 전투 인트로와 지역 안내 렌더링은 `cobbleventure-player-menu` 모드가 담당하며,
이 모듈은 계산한 육각 좌표와 지역 판정 결과만 전달한다.
기존 `cobbleventure_bootstrap` 모드 ID와 저장 데이터 키는 월드 호환성을 위해 유지한다.

전용 세대 차원과 마을 JSON 기반 지역 지도를 생성하는 NeoForge Java 모드다.

## 현재 동작

월드 플랜 기반 코드는 다음 경계로 분리되어 있다.

- `WorldPlanRepository`: 패키징된 월드·마을·경계 JSON 로딩과 시드별 불변 플랜 캐시
- `WorldPlanParser`: 월드 JSON 역직렬화와 입력값·참조 유효성 검사
- `WorldPlanModels`: 육각 월드 계획과 지형 샘플을 표현하는 불변 데이터 모델
- `HexGeometry`: 월드 좌표와 육각 좌표 변환 및 전체 경계 계산
- `TerrainSampler`: 블록 중심 지형 샘플과 왜곡 좌표 캐시
- `CobbleventureBootstrap`: 서버 생명주기와 월드 계획·생성 작업 조율

- `cobbleventure:generation_1` 전용 차원을 생성한다.
- `cobbleventure:hex_map` `BiomeSource`와 `ChunkGenerator`가 패키징된
  `data/cobbleventure/hex_worlds/generation_1.json`을 청크 생성 단계에서 직접
  읽는다. Civilization식 육각 셀 계획은 연속 영향 마스크로 변환되어 실제 화면에는
  육각 윤곽이 드러나지 않는다.
- `fillbiome`과 최초 입장 시 전체 지도 블록 덮어쓰기를 사용하지 않는다. 플레이어·구조물
  때문에 요청된 청크만 생성되며, 지형 계산은 Minecraft 월드젠 워커에서 수행된다.
- 각 청크는 Y=0~9에 기반암 10층을 만들고 그 위에 JSON 지형 높이, 해수면과 표면
  재질을 직접 생성한다. 동굴·협곡·광맥·지하 호수·던전은 생성하지 않는다.
- 최초 입장 플레이어를 `cobbleventure:settlement/starter_town`의 `center`로
  옮기고 해당 위치를 리스폰 지점으로 지정한다.
- 생성된 포켓몬센터 건물에 들어가면 안전한 입구를 플레이어별 최근 센터로 저장한다.
  전투 패배 후 보유 파티가 모두 기절했다면 전투가 완전히 종료된 뒤 해당 센터로
  강제 귀환시키고 파티를 회복한다. 아직 센터를 방문하지 않았다면 시작 지점을
  대체 귀환점으로 사용한다.
- 월드맵의 `level_overrides`가 칠해진 육각 셀에서는 Cobblemon 자연 스폰 포켓몬의
  레벨을 지정 평균의 ±2 범위로 조정한다. 칠하지 않은 셀, 다른 차원, 명령이나
  트레이너가 생성한 포켓몬은 기존 레벨을 유지한다.
- 길의 `pokemon_spawns`는 실제 도로 회랑 안의 Cobblemon 자연 스폰에 적용된다.
  `inherit_biome=false`이면 직접 추가 목록으로만 종을 교체하고 목록이 비어 있으면
  스폰을 취소한다. 바이옴을 상속할 때는 제외 종을 차단하며, 직접 추가 종은 기존
  후보와 함께 섞인다. 직접 추가 종은 각 항목의 `min_level`~`max_level` 범위를 쓴다.
  플레이어 소유·트레이너·명령 소환 포켓몬에는 이 규칙을 적용하지 않는다.
- 마을 앵커의 `town_radius_cells`를 먼저 예약하고 A*로 마을 사이 통로 셀을
  만든 뒤, 각 주변 바이옴에 지정한 `tile_count`만큼 빈 셀을 배정한다. 실제 도로
  중심선은 셀 중심 직선을 그대로 쓰지 않고 저주파 노이즈로 완만하게 굽히며,
  평원·사바나·황무지·설원·습지·해안에 맞는 노면 블록을 섞어 사용한다.
- 파도타기 수로는 물 위에 도로를 만들지 않는다. 양쪽 마을 중심에서 수로와 만나는
  첫 해안까지 각각 육상 진입로를 만들고, 해안 끝에는 조금 넓은 승선 지점을 둔다.
- 연속 플레이 영역과 미할당 외부 사이에만 경계를 만들며, 마을·주변 바이옴·통로
  사이는 열어 둔다. 외곽에 닿은 각 바이옴이 자체 경계 프로필을 선택한다.
- 경계는 JSON 프로필에 따라 석재 벽, 흙 토루 또는 숨은 차단층을 포함한 수목선으로
  생성한다.
- 주변 바이옴은 JSON의 높이 프로필과 저주파 노이즈로 1~3블록의 완만한 굴곡을
  만들며, 희귀 고지대는 기본 지표보다 최소 6블록 높게 생성한다.
- `rock_climb` 또는 `surf`가 필요한 지역은 해금되지 않은 플레이어를 마지막 안전
  지점으로 되돌린다.
- 바다 셀과 접근 불가 외곽이 맞닿는 곳은 육지용 완만한 경사를 사용하지 않는다.
  높이뿐 아니라 수직 면에도 노이즈를 적용해 돌출 선반과 얕은 함몰이 생기는 해안
  절벽을 세운다. 표면은 돌·안산암·응회암·조약돌·방해석을 층과 얼룩으로 섞으며,
  마을 사이의 파도타기 수로처럼 월드 데이터에 포함된 수역은 절벽 대상에서 제외한다.
- 육각 셀 계획을 완료한 뒤 마을 중심과 기존 절대 앵커를 계산된 마을 셀 중심으로
  함께 평행이동한다.
- 마을 본체는 BCA village 구조물을 호출하지 않는다. `layout_shape`에 따라 광장과
  선형·분기형·방사형·고리형·계단형 도로 골격을 직접 그리고, `road_profile`의 폭과
  노면을 적용한 뒤 `facility_placements`의 NBT 시설을 배치한다.
- 체육관 배치 후에는 실제 템플릿 크기를 읽어 건물 외곽에
  4블록 폭의 순환도로와 마을 중심 방향 진입로를 만든다. 진입로는 기존 건물과
  충돌하는 칼럼을 건너뛰어 주택을 파괴하지 않는다.
- 마을과 체육관 배치가 끝나면 비어 있는 자연 지표를 다시 검사해 바이옴별 조경을
  추가한다. 평원은 참나무·자작나무와 들꽃, 숲은 밀도 높은 혼합림과 양치식물,
  배드랜드는 성긴 아카시아와 마른 관목, 해변은 야자수형 정글나무와 물가 사탕수수,
  돌산은 가문비나무와 산지 풀을 사용한다. 건물·도로·스폰 및 시설 앵커 주변에는
  조경을 배치하지 않는다.
- 멀리 떨어진 마을의 도로와 시설을 배치하기 전에 필요한 주변 청크를 선로딩한다.
  시설 템플릿은 실제 템플릿 크기에 해당하는 청크만 선로딩한다.
- 시작 마을, 숲 테마의 `route_01_town`, 불꽃·황무지 테마의 `crimson_town`,
  파도타기로만 접근하는 섬 마을 `tidehaven_town`, 바위오르기로 진입하는 산악
  `skyreach_town`까지 5개 마을을 연결한다. 기본 동선은 `1→2→3→4`이며
  `3→5` 산악 분기가 추가된다.
- 시작 마을 구조물의 허용 바이옴은 `cobbleventure:starter_plains`로 고정한다.
  BCA의 바닐라 마을용 `#bca:villages` 태그에는 의존하지 않는다.
- 도로 골격과 시설 연결로는 Cobbleventure가 직접 생성한다. 연구소·호텔·라디오
  타워 등은 교체 가능한 NBT를 지정 앵커에 배치하고, 실제 체육관은 별도 앵커에
  RGS 원본 템플릿을 지표면 높이로 직접 배치한다.
- 오버월드 SavedData `cobbleventure_world_bootstrap.dat`에 지도 버전, 스폰·마을
  좌표와 성공 여부를 기록해
  재접속이나 서버 재시작으로 마을이 중복 생성되지 않게 한다.
- 기존 지도 버전을 업그레이드할 때는 새 지형을 그리기 전에 지도 영역의 이전 비플레이어
  엔티티와 블록 엔티티를 정리한 뒤 마을·시설을 다시 배치한다.
- 전용 차원 로딩 또는 마을 배치에 실패하면 완료 상태를 저장하지 않고 채팅으로
  원인을 알린다. 다음 재접속 때 다시 시도한다.

`tools/mod-builder/starter_gym.py`는 이름과 달리 호환성을 위해 남은 빌더 모듈이며,
현재는 시설 플레이스홀더 NBT와 이전 데이터 호환 리소스를 생성한다. `facility_placements`의
`direct_template`은 `content/structures/gyms`에서 가져온 프로젝트 체육관 외관을
마을 지상에 배치한다. 내부는 체육관 카탈로그의 모듈을 별도 차원에 조립한다.
리그는 `cobbleventure:league/kanto_league`를 전용 지역 앵커에 배치한다. 상세 계약은
`docs/implementation/RADICAL_GYMS_INTEGRATION.md`에 기록한다.

월드 생성 레지스트리가 flat 프로토타입에서 네이티브 생성기로 변경되었으므로 반드시
새 테스트 월드에서 확인한다. 기존 flat 월드는 호환 경로로 열 수 있지만 이미 생성된
청크가 자동으로 네이티브 지형으로 바뀌지는 않는다. 2026-08-10 성능 스모크 테스트에서
새 서버 준비는 약 4초, 시작 지역 반경 192블록의 625청크 강제 생성은 약 31.9초였다.
일반 플레이는 이 반경을 미리 생성하지 않고 접근한 청크만 생성한다.

## 조정 가능한 월드 데이터

| 파일 | 역할 |
|------|------|
| `data/cobbleventure/worldgen/biome/starter_plains.json` | 시작 바이옴의 색·날씨·식생·몹 |
| `data/cobbleventure/dimension/generation_1.json` | 네이티브 생성기, 지도 시드와 지원 바이옴 목록 |
| `data/cobbleventure/dimension_type/generation_world.json` | 세대 차원의 높이·채광·침대·시간 성격 |
| `content/worlds/generation_1.json` | 육각 계획 격자, 지역별 포켓몬 평균 레벨, 5개 마을과 분기 동선, 바이옴 영향 반경·굴곡·경계와 A* 통로 폭 |
| `content/catalogs/boundary-profiles.json` | 벽·토루·수목 경계의 크기, 재료와 충돌 방식 |
| `content/settlements/generation_1/*.json` | 마을 건물, NPC, 체육관과 콘텐츠 설정 |

## 진행 기능과 필드 기술 테스트

현재 해금 상태는 플레이어 영속 데이터에 저장한다. 운영자 또는 퀘스트 명령에서
다음 명령을 호출할 수 있다.

```mcfunction
/cobbleventure_progress on @s map
/cobbleventure_progress on @s settlement_teleport
/cobbleventure_progress off @s pc

/cobbleventure_field_move on @s rock_climb
/cobbleventure_field_move on @s surf
/cobbleventure_field_move off @s surf
```

메뉴 기능은 `map`, `settlement_teleport`, `pc`를 지원한다. 비전머신은 `surf`,
`fly`, `flash`, `defog`, `rock_climb`, `whirlpool`, `strength`, `rock_smash`를
지원하며 명령 입력 중 자동완성된다. 기존 `unlock/lock`, `grant/revoke` 문법도
호환을 위해 유지한다.

`/cobbleventure_field_move <move> on|off|toggle`은 권한 지급 명령이 아니라,
이미 보유한 플래시·괴력·바위깨기·락클레임의 사용 상태를 바꾸는 플레이어용
명령이다.

코블몬 파티가 실제로 해당 기술을 배웠는지 자동 판정하는 부분은 코블몬 API 어댑터가
추가되면 같은 해금 값을 갱신하도록 연결한다.

## 설정형 마을 인게임 생성 테스트

현재 플레이어 위치를 마을 중심으로 삼아 BCA village 없이 도로·광장·NBT 시설과
체육관을 함께 배치하려면 `generation_1` 차원에서 다음 명령을 사용한다.

```mcfunction
/cobbleventure_generate_town starter_town
/cobbleventure_generate_town cobbleventure:settlement/vermilion_city
```

명령은 기존 블록을 자동 복구하지 않으므로 충분히 떨어진 빈 장소에서 실행한다.
기존 `/cobbleventure_place_structure`는 개별 worldgen 구조물과 지형 높이 보정만
검사하는 저수준 진단 명령이며 전체 마을 생성 테스트에는 사용하지 않는다.

## 빌드

```bat
build.bat mod-bootstrap
```

명령은 체육관 NBT를 생성한 뒤 NeoForge Java 소스를 컴파일하고, 생성된 JAR을
CurseForge 개발 팩의 `overrides/mods`에 복사한다. 일반 `build.bat pack`도
같은 작업을 먼저 수행한다.

실제 플레이 팩의 모드 JAR을 함께 불러와 시작 마을 배치까지 검사하려면 다음
개발용 통합 실행을 사용한다. `integration_mods_dir`에는 테스트 인스턴스의
`mods` 폴더를 지정한다. 기존 부트스트랩 JAR은 자동으로 제외된다.

```bat
projects\cobbleventure-battle-ai\gradlew.bat ^
  -p projects\cobbleventure-world-bootstrap ^
  -Pintegration_mods_dir="C:\path\to\instance\mods" ^
  runIntegrationServer
```

연속 바이옴·지표·외곽 경계를 빈 서버 월드에 그리고 모든 마을·통로·주변 지역이
실제로 보이는지 자동 검사하려면 다음 전용 실행을 사용한다. 성공 후 서버는 자동 종료된다.

```bat
projects\cobbleventure-battle-ai\gradlew.bat ^
  -p projects\cobbleventure-world-bootstrap ^
  runHexWorldServer
```

지형 코드를 반복 수정할 때는 전체 지도를 매번 만들지 말고 시작 마을을 중심으로
반경 192블록만 생성하는 성능 스모크 테스트를 먼저 사용한다. 실제 인스턴스의 의존
모드와 BCA 데이터도 함께 로드하며, 서버 감시 타이머는 이 작업에 한해 자동으로
비활성화된다. 이 실행은 지형 생성 단계만 검증하므로 최종 확인에서는 위의
`runHexWorldServer` 전체 테스트도 수행해야 한다.

```bat
projects\cobbleventure-battle-ai\gradlew.bat ^
  -p projects\cobbleventure-world-bootstrap ^
  -Pintegration_mods_dir="C:\path\to\instance\mods" ^
  runWorldPerformanceServer --no-configuration-cache
```

완료 로그의 `Native worldgen test chunks ready`에는 요청한 청크 수와 월드젠 완료
시간이 기록된다. 이 경로에서 `fillbiome`과 사후 지형 블록 덮어쓰기는 실행되지 않는다.

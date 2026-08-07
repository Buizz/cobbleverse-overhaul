# Cobbleventure World Bootstrap

정식 커스텀 `BiomeSource`가 구현되기 전에 전용 세대 차원과 마을 JSON 기반
지역 지도를 검증하는 NeoForge Java 모드다.

## 현재 동작

- `cobbleventure:generation_1` 전용 차원을 생성한다.
- 기본 차원은 `cobbleventure:starter_plains`로 생성한 뒤, 패키징된
  `data/cobbleventure/hex_worlds/generation_1.json`을 읽어 Civilization식
  육각 셀로 마을·통로·주변 바이옴을 계획한 뒤 연속 영향 마스크로 실제 바이옴과
  지표를 다시 그린다. 셀 외곽에는 저주파 노이즈가 적용되어 육각 윤곽이 보이지 않는다.
- 외부 모드의 바이옴 장식 feature는 전용 평면 차원에서 비활성화한다. 지하가 없는
  월드에 Loot Ball·액자형 엔티티 등이 잘못 생성되거나 지형 재도색과 충돌하는 것을 막는다.
- 지형은 Y=0~54의 공기층 위에 Y=55~64 기반암 10층, 흙 3블록과 잔디 1블록만
  생성한다. 기반암 아래는 빈 공간이므로 채굴 가능한 지하가 없고
  동굴·협곡·광맥·지하 호수·던전도 생성하지 않는다.
- 최초 입장 플레이어를 `cobbleventure:settlement/starter_town`의 `center`로
  옮기고 해당 위치를 리스폰 지점으로 지정한다.
- 마을 앵커의 `town_radius_cells`를 먼저 예약하고 A*로 마을 사이 통로 셀을
  만든 뒤, 각 주변 바이옴에 지정한 `tile_count`만큼 빈 셀을 배정한다.
- 연속 플레이 영역과 미할당 외부 사이에만 경계를 만들며, 마을·주변 바이옴·통로
  사이는 열어 둔다. 외곽에 닿은 각 바이옴이 자체 경계 프로필을 선택한다.
- 경계는 JSON 프로필에 따라 석재 벽, 흙 토루 또는 숨은 차단층을 포함한 수목선으로
  생성한다.
- 주변 바이옴은 JSON의 높이 프로필과 저주파 노이즈로 1~3블록의 완만한 굴곡을
  만들며, 희귀 고지대는 기본 지표보다 최소 6블록 높게 생성한다.
- `rock_climb` 또는 `surf`가 필요한 지역은 해금되지 않은 플레이어를 마지막 안전
  지점으로 되돌린다.
- 육각 셀 계획을 완료한 뒤 `structure_profile.structure`을 계산된 마을 셀 중심에
  배치한다. 기존 절대 앵커는 셀 중심 이동량만큼 함께 평행이동한다.
- 멀리 떨어진 마을의 Jigsaw 구조물을 배치하기 전 최대 확장 거리까지 주변 청크를
  선로딩한다. `place structure`가 섬·산악 분기에서 `That position is not loaded`로
  실패하는 것을 방지한다. 시설 템플릿은 실제 템플릿 크기에 해당하는 청크만 선로딩한다.
- 시작 마을, 숲 테마의 `route_01_town`, 불꽃·황무지 테마의 `crimson_town`,
  파도타기로만 접근하는 섬 마을 `tidehaven_town`, 바위오르기로 진입하는 산악
  `skyreach_town`까지 5개 마을을 연결한다. 기본 동선은 `1→2→3→4`이며
  `3→5` 산악 분기가 추가된다.
- 시작 마을 구조물의 허용 바이옴은 `cobbleventure:starter_plains`로 고정한다.
  BCA의 바닐라 마을용 `#bca:villages` 태그에는 의존하지 않는다.
- 자체 체육관을 시작 조각으로 확정하고 네 방향의 BCA 도로 풀에서 주택과
  마을 시설을 확장하므로 체육관은 무작위로 누락되지 않는다.
- 오버월드 SavedData `cobbleventure_world_bootstrap.dat`에 지도 버전, 스폰·마을
  좌표와 성공 여부를 기록해
  재접속이나 서버 재시작으로 마을이 중복 생성되지 않게 한다.
- 기존 지도 버전을 업그레이드할 때는 새 지형을 그리기 전에 지도 영역의 이전 비플레이어
  엔티티와 블록 엔티티를 정리한 뒤 마을·시설을 다시 배치한다.
- 전용 차원 로딩 또는 마을 배치에 실패하면 완료 상태를 저장하지 않고 채팅으로
  원인을 알린다. 다음 재접속 때 다시 시도한다.

체육관 NBT는 `tools/mod-builder/starter_gym.py`에서 마을마다 생성한다. 모든
체육관은 같은 소형 외관과 포켓볼 표식을 사용하고, 각 마을의 `gym_theme`에 따라
지붕 콘크리트와 입구 카펫 색만 바뀐다. 내부는 작은 로비이며
`facility_placements`의 `instanced_entry`가 격리 좌표에 원본 그대로 배치한 RGS
체육관으로 플레이어를 이동시킨다. 시작 마을은 `rgs:pewter_gym`을 사용한다.
리그는 `direct_template` 배치 방식으로 `rgs:kanto_league`를 전용 지역 앵커에
그대로 놓으며 팔레트나 외관을 변경하지 않는다. 상세 계약은
`docs/implementation/RADICAL_GYMS_INTEGRATION.md`에 기록한다.

이 모듈은 프로토타입이다. 월드 생성 레지스트리와 지도 버전이 달라졌으므로 반드시
새 테스트 월드에서 확인한다. 현재 구현은 최초 입장 전에 `fillbiome`과 블록 배치로
지도를 그린다. 이 동작이 검증되면 같은 마을 데이터 계약을 커스텀 `BiomeSource`와
`ChunkGenerator`로 옮겨 청크 자체가 처음부터 올바르게 생성되게 한다.
현재 샘플의 5개 마을·4개 통로·62셀은 테스트 PC에서 최초 렌더링에 약 123초가
걸리므로 전용 서버 프로토타입
검증 시에는 `server.properties`의 `max-tick-time=-1`이 필요하다.

## 조정 가능한 월드 데이터

| 파일 | 역할 |
|------|------|
| `data/cobbleventure/worldgen/biome/starter_plains.json` | 시작 바이옴의 색·날씨·식생·몹 |
| `data/cobbleventure/dimension/generation_1.json` | 지표 블록 층과 바이옴, 호수·구조물 생성 여부 |
| `data/cobbleventure/dimension_type/generation_world.json` | 세대 차원의 높이·채광·침대·시간 성격 |
| `content/worlds/generation_1.json` | 육각 계획 격자, 5개 마을과 분기 동선, 바이옴 영향 반경·굴곡·경계와 A* 통로 폭 |
| `content/catalogs/boundary-profiles.json` | 벽·토루·수목 경계의 크기, 재료와 충돌 방식 |
| `content/settlements/generation_1/*.json` | 마을 건물, NPC, 체육관과 콘텐츠 설정 |

## 필드 기술 테스트

현재 해금 상태는 플레이어 영속 데이터에 저장한다. 운영자 또는 퀘스트 명령에서
다음 명령을 호출할 수 있다.

```mcfunction
/cobbleventure_field_move grant rock_climb
/cobbleventure_field_move grant surf
/cobbleventure_field_move revoke surf
```

코블몬 파티가 실제로 해당 기술을 배웠는지 자동 판정하는 부분은 코블몬 API 어댑터가
추가되면 같은 해금 값을 갱신하도록 연결한다.

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

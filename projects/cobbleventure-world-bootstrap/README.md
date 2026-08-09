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
- 육각 셀 계획을 완료한 뒤 `structure_profile.structure`을 계산된 마을 셀 중심에
  배치한다. 기존 절대 앵커는 셀 중심 이동량만큼 함께 평행이동한다.
- `structure_profile.village_preset`은 BCA 도로 계열을 선택하고,
  `structure_profile.house_style`은 도로에 연결할 주택 Jigsaw 풀을 선택한다. 주택
  풀은 자체 제작 리소스 ID로 교체할 수 있다.
- 생성 허브는 BCA 프리셋별 실제 도로 Jigsaw 연결 이름을 사용하고 네 방향으로
  최대 6단계까지 확장한다. 체육관 배치 후에는 실제 템플릿 크기를 읽어 건물 외곽에
  4블록 폭의 순환도로와 마을 중심 방향 진입로를 만든다. 진입로는 기존 건물과
  충돌하는 칼럼을 건너뛰어 주택을 파괴하지 않는다.
- 마을과 체육관 배치가 끝나면 비어 있는 자연 지표를 다시 검사해 바이옴별 조경을
  추가한다. 평원은 참나무·자작나무와 들꽃, 숲은 밀도 높은 혼합림과 양치식물,
  배드랜드는 성긴 아카시아와 마른 관목, 해변은 야자수형 정글나무와 물가 사탕수수,
  돌산은 가문비나무와 산지 풀을 사용한다. 건물·도로·스폰 및 시설 앵커 주변에는
  조경을 배치하지 않는다.
- BCA 주택·가로등·장식 하위 템플릿은 기준점 차이로 한 블록 뜨는 현상을 마을 배치
  중에만 Y -1로 보정한다. 도로·가로등·장식은 템플릿의 공기 블록이나 장식 연결용
  Jigsaw가 기존 지표 이하의 잔디·흙을 파내지 않도록 보존한다. 지표보다 위의 공기는
  그대로 적용하므로 도로 위 식생은 정상적으로 정리된다.
- 멀리 떨어진 마을의 Jigsaw 구조물을 배치하기 전 최대 확장 거리까지 주변 청크를
  선로딩한다. `place structure`가 섬·산악 분기에서 `That position is not loaded`로
  실패하는 것을 방지한다. 시설 템플릿은 실제 템플릿 크기에 해당하는 청크만 선로딩한다.
- 시작 마을, 숲 테마의 `route_01_town`, 불꽃·황무지 테마의 `crimson_town`,
  파도타기로만 접근하는 섬 마을 `tidehaven_town`, 바위오르기로 진입하는 산악
  `skyreach_town`까지 5개 마을을 연결한다. 기본 동선은 `1→2→3→4`이며
  `3→5` 산악 분기가 추가된다.
- 시작 마을 구조물의 허용 바이옴은 `cobbleventure:starter_plains`로 고정한다.
  BCA의 바닐라 마을용 `#bca:villages` 태그에는 의존하지 않는다.
- 자체 건물 대신 중립 도로 허브를 시작 조각으로 사용하고 세 방향의 BCA 도로
  풀에서 주택과 마을 시설을 확장한다. 실제 체육관은 별도 앵커에 RGS 원본
  템플릿을 지표면 높이로 직접 배치한다.
- 오버월드 SavedData `cobbleventure_world_bootstrap.dat`에 지도 버전, 스폰·마을
  좌표와 성공 여부를 기록해
  재접속이나 서버 재시작으로 마을이 중복 생성되지 않게 한다.
- 기존 지도 버전을 업그레이드할 때는 새 지형을 그리기 전에 지도 영역의 이전 비플레이어
  엔티티와 블록 엔티티를 정리한 뒤 마을·시설을 다시 배치한다.
- 전용 차원 로딩 또는 마을 배치에 실패하면 완료 상태를 저장하지 않고 채팅으로
  원인을 알린다. 다음 재접속 때 다시 시도한다.

`tools/mod-builder/starter_gym.py`는 이름과 달리 호환성을 위해 남은 빌더 모듈이며,
현재는 건물이 없는 BCA 도로 허브만 생성한다. `facility_placements`의
`direct_template`이 RGS 체육관을 마을 지상에 원본 그대로 배치한다. 시작 마을은
`rgs:pewter_gym`을 사용한다.
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

## 지형 높이 보정 구조물 테스트

바닐라 `/place structure` 명령은 Cobbleventure가 그린 지형 높이를 Jigsaw 생성기에
전달하지 않는다. 마을 구조물을 수동으로 비교할 때는 `generation_1` 차원에서 다음
전용 명령을 사용한다.

```mcfunction
/cobbleventure_place_structure bca:village/default_large
```

명령은 현재 플레이어 위치에 구조물을 놓고, 결과 메시지에 Jigsaw가 조회한 지형 높이
횟수와 Y 범위를 표시한다. 조회가 여러 번이고 Y 범위가 달라지면 길 조각에 지형의
높낮이가 전달된 것이다. 조회가 한 번뿐이면 해당 구조물의 하위 Jigsaw 풀이 로드되지
않았거나 지형 추종 조각이 없는지 확인한다.

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

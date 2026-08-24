# Cobbleventure 의존 모드 관리표

> 상태: 기본 실행 의존성 확정, 애드온 후보 검토 중
>
> 버전 기준: Minecraft 1.21.1 / NeoForge 21.1.248 / Cobblemon 1.7.3

이 문서는 Cobbleventure 테스트팩과 공개 모드팩에 포함할 외부 모드의 역할과
선정 근거를 관리한다. 빌드가 사용하는 실제 버전과 CurseForge 식별자는
[`pack/dependencies.lock.json`](../pack/dependencies.lock.json)에 기록한다.

## 관리 규칙

- 필수, 선택, 개발 전용 의존성을 구분한다.
- Minecraft, NeoForge와 Cobblemon 버전을 함께 고정한다.
- CurseForge project ID, file ID, 배포 라이선스와 배포면을 확인한다.
- 모드를 추가할 때 대체 가능한 기능과 제거 시 영향을 함께 기록한다.
- 업데이트 후 NPC 생성, 대화, 트레이너전, AI와 전투 기믹 회귀 테스트를 수행한다.
- 문서와 Lock의 모드 ID가 다르면 빌드를 통과시키지 않는다.

## 현재 의존성 후보

| ID | 모드 | 구분 | 설치면 | 역할 | 현재 상태 |
|----|------|------|--------|------|-----------|
| `cobblemon` | Cobblemon | 필수 | 양쪽 | 포켓몬, 기술, 파티와 실제 배틀 | 1.7.3 / CF `687131:7553231` |
| `cobblemon_exp_bar` | Cobblemon EXP Bar | 필수 | 양쪽 | 포켓몬 게임 스타일 경험치 바와 전투 중 경험치·레벨업 로그; 자체 클라이언트 패치로 이름과 체력 바 사이에 배치 | 1.0.5 / CF `1418364:8397752` |
| `fix_cobblemon_pokemon_experience` | Fix Cobblemon Pokemon Experience | 필수 | 서버 | RCT 트레이너전에서 상대 포켓몬 KO 직후 경험치 지급 | 1.1.1 / CF `1435842:7533327` |
| `cobblemon_tim_core` | Cobblemon Tim Core | 필수 | 양쪽 | Capture XP 공용 이벤트·경험치 처리 라이브러리 | 1.7.3-1.32.0 / CF `1295910:7938283` |
| `cobblemon_capture_xp` | Cobblemon Capture XP | 필수 | 양쪽 | 야생 포켓몬 포획 시 파티 경험치 지급 | 1.7.3-1.3.0 / CF `901059:7568508` |
| `more_cobblemon_tweaks` | MoreCobblemonTweaks | 필수 | 클라이언트 | Cobblemon 도구 설명과 UI 편의 기능 개선 | 1.3.3 / CF `1082538:7593359` |
| `sodium` | Sodium | 필수 | 클라이언트 | 청크·월드 렌더링과 마이크로 스터터 최적화 | 0.6.13 / CF `394468:6382651` |
| `iris` | Iris Shaders | 필수 | 클라이언트 | Sodium 기반 셰이더팩 로딩 | 1.8.8 / CF `455508:6213632` |
| `lithium` | Lithium | 필수 | 양쪽 | 게임 틱과 엔티티·블록 처리 최적화 | 0.15.3 / CF `360438:7740400` |
| `ferritecore` | FerriteCore | 필수 | 양쪽 | 블록 상태와 데이터 컴포넌트 메모리 최적화 | 7.0.3 / CF `429235:7524151` |
| `immediatelyfast` | ImmediatelyFast | 필수 | 클라이언트 | GUI·텍스트·엔티티 즉시 모드 렌더링 최적화 | 1.6.10 / CF `686911:7537795` |
| `entity_culling` | Entity Culling | 필수 | 클라이언트 | 보이지 않는 엔티티와 블록 엔티티 렌더링 생략 | 1.9.5 / CF `448233:7396695` |
| `complementary_reimagined` | Complementary Shaders - Reimagined | 필수 | 클라이언트 | Iris용 기본 셰이더 및 Euphoria 기반팩 | r5.3 / CF `627557:5874236` |
| `euphoria_patches` | Euphoria Patches | 필수 | 클라이언트 | Complementary r5.3 확장 그래픽 설정 패처 | 1.4.3-r5.3 / CF `915902:5876050` |
| `badmobs` | Bad Mobs | 필수 | 양쪽 | 바닐라 동물·몬스터의 모든 소환 경로 차단 | 21.1.1 / CF `233258:7055133` |
| `cobblenav` | Cobblemon Pokenav | 필수 | 양쪽 | 포켓네비와 현재 지역 포켓몬 출현 정보 | 2.3.3 / CF `976014:7940651` |
| `cloth_config` | Cloth Config API | 필수 | 양쪽 | Cobblemon Casino 설정 화면 API | 15.0.140 / CF `348521:5729127` |
| `kotlin_for_forge` | Kotlin for Forge | 필수 | 양쪽 | Cobblemon NeoForge의 Kotlin 런타임 | 5.12.0 / CF `351264:8335665` |
| `sinytra_connector` | Sinytra Connector | 필수 | 양쪽 | NeoForge에서 BCA Fabric JAR 로딩 | 2.0.0 beta 16 / CF `890127:8546239` |
| `forgified_fabric_api` | Forgified Fabric API | 필수 | 양쪽 | Fabric API의 NeoForge 호환 구현 | 0.116.15+2.3.1 / CF `889079:8539754` |
| `fabric_language_kotlin` | Fabric Language Kotlin | 필수 | 양쪽 | BCA의 Fabric Kotlin 진입점 실행 | 1.13.8 / CF `308769:7340876` |
| `cobbledollars` | CobbleDollars | 필수 | 양쪽 | BCA 상점과 백화점 화폐·상인 기능 | 2.0.0 Beta-6.1 / CF `859232:8484919` |
| `cobblemon_casino` | Cobblemon Casino | 필수 | 양쪽 | 슬롯머신, 블랙잭, 가챠와 카지노 칩 경제 | 2.0.0 / CF `1572769:8235485` |
| `playingcards` | Playing Cards & Chips | 필수 | 양쪽 | 블랙잭 테이블의 연결식 마인크래프트 외형 | 2.0.1-neoforge / CF `1162591:7219926` |
| `architectury_api` | Architectury API | 필수 | 양쪽 | CobbleFurnies 공용 API | 13.0.11 / CF `419699:8492726` |
| `create` | Create | 필수 | 양쪽 | 체육관 벨트·선풍기·이동 장치와 기계 장식 | 6.0.10 / CF `328085:7963363` |
| `copycats` | Create: Copycats+ | 필수 | 양쪽 | 재질을 입힐 수 있는 카피캣 블록과 문·패널·계단 확장 | 3.0.4 / CF `968398:7251823` |
| `rctapi` | Radical Cobblemon Trainers API | 필수 | 양쪽 | 트레이너 데이터와 전투 연동 계약 | 0.15.2-beta / CF `1152792:7952419` |
| `rctmod` | Radical Cobblemon Trainers | 필수 | 양쪽 | RCT JSON 로딩과 트레이너전 관리 | 0.18.1-beta / CF `1009534:7913180` |
| `cobblefurnies` | CobbleFurnies | 필수 | 양쪽 | 프로젝트 체육관·리그 NBT의 가구 블록 | 1.2 / CF `1188698:8340192` |
| `athena` | Athena | 필수 | 클라이언트 | CobbleFurnies 포켓볼 양탄자·연결 텍스처 렌더링 | 4.0.6 / CF `841890:8061947` |
| `accessories` | Accessories | 필수 | 양쪽 | Mega Showdown 장신구 슬롯과 렌더링 | 1.1.0-beta.53 / CF `938917:7583320` |
| `owo_lib` | oωo (owo-lib) | 필수 | 양쪽 | Mega Showdown GUI·설정·네트워크 라이브러리 | 0.12.15.5-beta.1 / CF `532610:6785734` |
| `mega_showdown` | Cobblemon: Mega Showdown | 필수 | 양쪽 | 메가진화·Z기술·테라스탈·다이맥스·울트라버스트 | 1.9.3 / CF `1189523:8519042` |
| `paxi_neoforge` | Paxi (NeoForge) | 필수 | 양쪽 | CCCC와 ZA 보정팩을 모든 월드에서 자동 로드 | 5.1.3 / CF `1015157:6485740` |
| `yungs_api_neoforge` | YUNG's API (NeoForge) | 필수 | 양쪽 | Paxi 필수 공용 API | 5.1.6 / CF `1015100:6715463` |
| `easy_npc_bundle` | Easy NPC Bundle | 필수 | 양쪽 | Core와 Config UI 의존성을 선언하는 런처용 번들 | 7.0.1 / CF `559312:8420470` |
| `easy_npc` | Easy NPC Core | 필수 | 양쪽 | NPC 외형, 대화와 상호작용 | 7.0.1 / CF `1308987:8420476` |
| `easy_npc_config_ui` | Easy NPC Config UI | 필수 | 양쪽 | Easy NPC 게임 내 설정과 네트워크 | 7.0.1 / CF `1214728:8420458` |
| `tbcs` | Cobblemon Trainer Battle Commands | 필수 | 양쪽 | EasyNPC와 RCT API의 명령 기반 전투 연결 | 0.14.1-beta / CF `1172731:7858400` |

Easy NPC 7.6.0 계열은 `easy_model_entities`라는 새 필수 의존성을 추가하고
업그레이드 절차를 안내하고 있어 자동 업데이트에서 제외한다. NPC 프리셋과 TBCS
연동을 별도 런타임 검증한 뒤 Bundle, Core, Config UI를 함께 올린다.

## 성능과 셰이더 기준 구성

클라이언트 렌더링은 NeoForge 네이티브 Sodium 0.6.13과 Iris 1.8.8 조합으로
고정한다. Sodium은 월드 렌더링, ImmediatelyFast는 GUI와 즉시 모드 렌더링,
Entity Culling은 가려진 개체 렌더링을 각각 담당한다. Lithium과 FerriteCore는
클라이언트와 서버 양쪽에서 틱 처리와 메모리 사용량을 줄인다.

셰이더는 Complementary Reimagined r5.3과 정확히 대응하는 Euphoria Patcher
1.4.3-r5.3 NeoForge 파일을 함께 설치한다. Euphoria Patches는 단독 셰이더팩이
아니라 Complementary를 확장하는 패처이므로 두 항목을 모두 필수로 유지한다.
현재 개발팩은 Euphoria 패처가 생성하는
`ComplementaryReimagined_r5.3 + EuphoriaPatches_1.4.3`을 Iris 기본 셰이더로
선택하고 셰이더 렌더링도 활성화한다. 실제 릴리스 빌드에서는 성능 등급별 기본값을
정한 뒤 이 개발용 강제 활성화 설정을 제거하거나 별도 프로필로 분리한다.
Iris의 기본 `R` 셰이더 새로고침 단축키는 플레이어 메뉴 클라이언트 초기화 시
`key.keyboard.unknown`으로 마이그레이션한다. 모드팩은 전역 `options.txt`를
덮어쓰지 않으므로 언어와 사용자의 다른 키 설정을 보존한다.

`Cobblemon Casino` 2.0.0을 카지노 기본 시설의 정식 의존성으로 사용한다. Minecraft
1.21.1·NeoForge용 파일과 필수 Cloth Config API 15.0.140을 Lock과 개발팩에 함께
등록했다. 슬롯머신, 블랙잭, 아이템·포켓몬 가챠, 카지노 칩 경제와 장식 블록을
제공하며 기존 필수 모드인 CobbleDollars를 선택적으로 연동한다. 보호 차원의 실제
상호작용과 경제 설정 검증 절차는
[`CASINOCRAFT_INTEGRATION.md`](implementation/CASINOCRAFT_INTEGRATION.md)를 따른다.

`Playing Cards & Chips`의 `playingcards:poker_table`은 블랙잭 외형 전용으로 사용한다.
게임과 메뉴는 숨겨 둔 `cobblemoncasino:blackjack_table`이 계속 담당하며, 카지노
애드온이 포커 테이블 우클릭을 반경 8블록 안의 가장 가까운 블랙잭 테이블로 전달한다.

## 콘텐츠팩 의존성

| ID | 콘텐츠팩 | 구분 | 선택 버전 | 배포 형식 | 패키징 상태 |
|----|----------|------|-----------|-----------|-------------|
| `cobblemon_additions` | Cobblemon Additions | 필수 | 4.2.1 / Modrinth `W2pr9jyL:9PMzbD4o` | Fabric JAR로 포장된 데이터팩·모드 | ZIP 포함 및 NeoForge 호환 구성 완료 |
| `complete_cobblemon_collection` | Complete Cobblemon Collection: Myths and Legends Compat | 필수 로컬 콘텐츠 | CCCC 1.7.2 / SHA-1 `b37e878f7e5539bfd145ca0fe9d63bcfef0a128c` | ZIP 데이터팩·리소스팩 | 실제 NeoForge 서버 파일을 저장소 Paxi 양쪽 경로에 포함 |
| `za_mega_staraptor_contrary_fix` | ZA Mega Staraptor Contrary Fix | 필수 로컬 보정 | SHA-1 `9d20719aea859c9f20dfffccf3c30b756a419581` | ZIP 데이터팩·리소스팩 | 저장소 Paxi 양쪽 경로에 직접 포함 |

## 전국도감과 전투 기믹

기본 Cobblemon 1.7.3은 1~9세대 종 데이터와 전투는 제공하지만, 모델이 없는 종은
대체 인형으로 표시된다. 전국도감 보완은 추정한 Modrinth판 구성이 아니라 실제
`G:\2026 MineCraft\Cobbleverse Server\Server\datapacks`에서 확인한
**Complete Cobblemon Collection `CCCC-1.7.2.zip`**을 기준으로 한다. 모델 자산과
종 데이터를 함께 사용하기 위해 동일 ZIP을 Paxi의 데이터팩·리소스팩 양쪽에
포함한다.

CCCC 내부의 `LICENSE`, `Credits.txt`, 권한 증빙 파일을 원본 그대로 유지한다.
비수익 사용·수정·재배포는 허용되지만 서버나 모드팩을 수익화하려면 저자
Xcavalier의 명시적 서면 허가가 필요하다. 문서와 배포 설명에는
`Complete Cobblemon Collection: Myths and Legends Compat` 명칭을 표시해야 한다.

`ZA-Mega-Staraptor-Contrary-Fix.zip`은 Pokémon Champions 기준 ZA 메가진화 종
데이터와 Showdown 변환 스크립트를 덮어쓰며, 메가 스타랩터에 격투/비행 타입,
Contrary 특성과 보정 능력치를 적용한다. 데이터 정의와 한국어 번역을 함께
사용하기 위해 Paxi의 `datapacks`와 `resourcepacks` 양쪽에 같은 ZIP을 넣는다.
파일명이 `CCCC-1.7.2.zip`보다 뒤에 정렬되는 `ZA-`로 시작하므로 보정팩이 나중에
로드된다. ZIP 안에는 별도 라이선스 문서가 없으므로 외부 공개 배포 전에는
제공자에게 재배포 권한을 확인한다.

전투 기믹은 Cobblemon 1.7.3용 **Mega Showdown 1.9.3**으로 고정한다. 공식
의존 관계에 따라 Accessories, Architectury API, Cobblemon, Fabric API 계층과
owo-lib를 함께 포함한다. NeoForge에서는 기존 Forgified Fabric API를 Fabric API
호환 계층으로 사용한다.

## 자체 모듈

| 모듈 | 역할 | 외부 의존성 원칙 |
|------|------|------------------|
| Cobbleventure Core | 진행, 지역, 퀘스트와 데이터 계약 | Minecraft·Cobblemon 비의존 유지 |
| Cobbleventure Adventure | 전투 보상·패배 경제·센터 귀환·야생 레벨·필드 기술 | 월드 구현을 직접 참조하지 않고 `AdventureWorldContext`로 지역 정보 조회 |
| Cobbleventure World Bootstrap | 세대 월드·마을·동굴·구조물과 지역 환경 | Adventure에 월드 조회 구현을 등록하고 Player Menu에는 위치 판정 값만 전달 |
| Cobbleventure Player Menu | 공통 메뉴·가방·지도·도감과 음악·전투 인트로·지역 안내 | 플레이어에게 보이는 화면·소리·전환 연출을 소유 |
| Cobbleventure NPC | NPC, 대화창, 조건과 행동 | NeoForge 어댑터에서만 게임 API 사용 |
| Cobbleventure RCT Bridge | 대화 행동을 RCT 전투 시작으로 변환 | RCT 관련 코드를 별도 모듈로 격리 |
| Cobbleventure Battle AI | RCT JSON의 `ai` 선택값으로 실행되는 독립 NeoForge 모드 | Adventure와 분리 배포하고 공통 KMP 코어를 JAR 내부에 포함하며 RCT 기본 AI를 대체하지 않음 |
| Content Manager | Excel/JSON 변환, 검증과 출력 | Python 도구, 게임 런타임 비의존 |

## 마을과 상업 시설 구조물 조사

공식 Cobbleverse 구성표에는 **Cobblemon Additions가 모드가 아니라 데이터팩**으로
기재되어 있다. 따라서 CurseForge의 모드 목록에서 BCA라는 독립 모드가 보이지
않는 것이 정상이며, 외부 모드 의존성 Lock에도 등록하지 않는다.

참고용으로 제공받은 `CreateMon ver 8` ZIP은 원본 Cobbleverse 배포본이 아니라
이를 바탕으로 별도 구성한 팩이다. 이 ZIP에는 `cobblemon-additions-4.2.1.jar`가
`overrides/mods`에 수동 포함되어 있었지만, 이것만으로 원본 Cobbleverse도 같은
JAR을 모드로 사용한다고 판단할 수 없다.

CreateMon에 들어 있던 해당 파일과 `COBBLEVERSE-DP`의 `bca` 네임스페이스에는
다음 구조물이 확인된다.

- 마을과 도로를 구성하는 여러 바이옴별 직소 구조물
- `pokecenter.nbt`
- `structure_pokemart.nbt`
- `center_department_store.nbt`
- 백화점 층별 상점 직원을 포함한 상점 NPC 구조물

즉 Cobbleverse의 포켓몬 테마 마을과 상업 시설은 Cobblemon Additions 데이터팩
자산을 포함하거나 가공한 구성으로 볼 수 있다. 다만 실제 배포에서는
`COBBLEVERSE-DP`에 함께 포장될 수 있으므로, 이를 별도의 BCA 모드가 시설을
생성한다고 표현하지 않는다.

CreateMon에서 확인한 4.2.1 JAR의 메타데이터는 Fabric Loader, Fabric API와
Fabric Language Kotlin을 요구한다. 이 포장본을 NeoForge에서 그대로 사용하려면
Connector 계열 호환층이 필요하다. 우리 프로젝트는 NeoForge 네이티브 구성을
우선하므로 다음 원칙을 적용한다.

4.2.1 원본 JAR 내부에는 `CC0-1.0` 라이선스 전문이 포함되어 있다. 저장소에는
Modrinth 원본을 변경하지 않고 보관하며 SHA-1과 SHA-512를 Lock에서 검증한다.
현재 개발용 CurseForge ZIP에는 `overrides/mods/cobblemon-additions-4.2.1.jar`로
포함된다.

1. BCA를 외부 모드 목록이 아니라 콘텐츠팩 의존성으로 등록한다.
2. BCA의 마을 직소 풀과 센터·마트·백화점 구성은 자체 월드 생성 구현의 참고로 쓴다.
3. 원본 파일, 출처, 라이선스와 해시를 함께 고정한다.
4. Fabric JAR은 Sinytra Connector, Forgified Fabric API와 Fabric Language Kotlin을 통해 실행한다.

`CobbleTowns: Continued`도 포켓몬 본가 마을을 바탕으로 센터와 마트를 추가하는
대안이지만, 백화점까지 포함해 기존 팩과 같은 구성을 만든 직접 항목은 BCA다.

## 체육관과 리그 구조물

체육관과 리그는 RGS 0.6의 MIT 구조물 NBT를 프로젝트 편집 원본으로 가져와
`cobbleventure:*` 리소스로 패키징한다. RGS 실행 JAR은 manifest와 lock에 포함하지
않는다. 외관은 봉인된 껍데기로 직접 수정하고, 내부는 별도 차원의 모듈 NBT로
확장한다. 리그는 `cobbleventure:league/kanto_league`를 사용한다.

관장 전투에 사용하는 RCT와 RCT API, NBT 블록에 필요한 CobbleFurnies를 필수
의존성으로 고정했다. CobbleFurnies 1.2의 NeoForge 메타데이터가 요구하는 Architectury API
13.0.11도 명시적으로 포함한다. 상세 구조물 ID와 JSON 계약은
[`RADICAL_GYMS_INTEGRATION.md`](implementation/RADICAL_GYMS_INTEGRATION.md)를
기준으로 한다.

## 추후 설계 항목

- 자체 NPC 기본팩과 Easy NPC 호환팩의 공개 배포 범위
- Mega Showdown 기믹을 RCT 트레이너별로 제한하는 런타임 어댑터 상세
- 성능, 지도, 건축과 장식 모드 목록

위 항목은 현재 확정한 런타임 의존성을 바꾸지 않는 후속 설계 범위다. 새로운
의존성 후보가 생기면 검증 전까지 `0`, 빈 문자열 또는 임의 버전으로 채우지 않고
별도 후보로 관리하며, 정식 채택할 때 Lock과 개발팩 프로필을 함께 갱신한다.

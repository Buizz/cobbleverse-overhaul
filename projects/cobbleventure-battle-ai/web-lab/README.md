# Cobbleventure Battle Lab

트레이너 JSON과 직접 편집한 파티로 Cobbleventure Battle AI 테스트 구성을 만드는 웹 실험실이다.

## 현재 기능

- PvE: 플레이어 파티를 6슬롯으로 직접 편집하거나 준비된 트레이너 JSON에서 선택
- PvE: 준비된 JSON에서 상대 AI 트레이너 검색·선택
- EvE: 서로 다른 두 AI 트레이너의 파티 선택 및 비교
- 공식 플레이어 엔트리와 원본 RCT 트레이너 210개를 웹 공통 스키마로 정규화
- Showdown과 분리된 최소 자체 전투 엔진 및 `POST /api/native-battles` 실행 API
- 실제 Cobblemon·연동 모드 카탈로그를 기준으로 도구 ID를 정식 namespace ID로 정규화
- 양쪽 파티를 서버에서 다시 검증하고 재현 시드가 포함된 전투 시나리오 생성
- 생성한 엔진 입력 JSON 복사 및 파일 다운로드
- 사용자정의 엔트리 JSON을 관리 웹과 공통 형식으로 복사·붙여넣기
- Pokémon Showdown 기준 엔진으로 PvE·EvE 자동 대전 실행
- PvE에서 플레이어가 직접 기술과 교체 대상을 선택하는 턴제 전투 실행
- Cobblemon 한국어 리소스를 기준으로 포켓몬·기술 이름과 기술 설명 표시
- 기술 선택 화면에 타입, 분류, 위력, 명중률, 우선도와 현재 PP 표시
- 포켓몬·기술 타입 아이콘과 현재 상대 기준 기본 타입 상성 표시
- 급소, 상성, 명중 실패, 능력 변화, 특성·도구 발동을 포함한 턴별 평문 배틀 로그
- 현재 포켓몬의 공격·방어·특수공격·특수방어·스피드·명중률·회피율 랭크 표시
- 상대 기술 후보와 이번 턴 AI 선택을 필요할 때 공개하는 AI 제어용 옵션
- AI 후보 행동, 선택 결과와 판단 근거를 확인하는 AI 로그 탭
- 첫 실행 시 로컬 Cobbleventure 작업공간을 지정하고 이후 실행을 위해 경로 저장
- 저장한 `mods` 폴더의 Cobblemon JAR에서 한국어 데이터와 타입 보석 이미지 자동 갱신
- 쓰러진 포켓몬의 강제 교체, HP·PP 갱신, 기권과 승패 종료 처리
- 승자, 턴 수, 기술·교체·피해·기절 타임라인과 원시 로그 반환
- 지원하지 않는 Cobblemon 기술·특성·도구의 호환성 경고

현재 자동 대전은 `@pkmn/sim` 0.10.11의 Generation 9 Custom Game 규칙과 결정론적 `random-baseline` 컨트롤러를 사용한다. 클라이언트가 보낸 프리셋 데이터를 신뢰하지 않고 트레이너 ID를 원본 인덱스에서 다시 조회한 뒤 실행한다.

`random-baseline`은 최종 Cobbleventure AI가 아니다. 실제 전투 실행 경로와 로그 계약을 먼저 검증하고, 이후 프로젝트의 전략 선택기와 독립 전투 엔진을 동일 시나리오에 연결해 결과를 비교하기 위한 기준선이다.

## 데이터 흐름

```text
mods/*.jar
        ├─ Export-CobblemonItemCatalog.ps1
trainer-data/catalogs/cobblemon-items.json
        └─ npm run sync:localization -- <Cobblemon JAR>
public/data/cobblemon-ko-kr.json
        ↓
trainer-data/rctmod-v16-ver22/trainers/*.json
        ↓ npm run sync:trainers
public/data/trainers.json
        ↓ browser fetch
PvE / EvE 파티 선택 화면
        ↓ POST /api/scenarios
검증된 전투 시나리오 JSON
        ├─ POST /api/battles → Showdown 기준 자동 대전 결과와 행동 타임라인
        └─ POST /api/interactive-battles
              ↓
           플레이어 행동 선택 → 동일 시드 재생 → 다음 턴 요청
```

`scripts/sync-trainers.mjs`는 원본을 수정하지 않고 이름, 파티, 기술, 특성,
도구와 IV/EV 키 별칭을 웹용 공통 형식으로 바꾼다. `leftovers` 같은 짧은
도구 ID는 실제 모드 카탈로그에서 유일한 항목을 찾아
`cobblemon:leftovers`로 바꾼다. 원본의 배열형 도구는 fallback 후보 전체를
`heldItemOptions`에 보존한다.

`scripts/sync-cobblemon-localization.mjs`는 Cobblemon JAR의
`assets/cobblemon/lang/ko_kr.json`에서 포켓몬 이름·도감 설명과 기술
이름·설명을 추출한다. `textures/item/type_gem`의 타입 아이콘과
`textures/gui/categories.png`의 물리·특수·변화 기술 분류 아이콘도 함께
복사한다. 현재 생성 데이터는 Cobblemon 1.7.1 기준이며, 웹 상단의
`MOD SOURCE`에서 작업공간을 변경하면 자동으로 다시 생성한다. CLI에서는
다음 명령을 사용할 수 있다.

```text
npm run sync:localization -- "G:\경로\Cobblemon-neoforge-1.8.x.jar"
```

선택한 로컬 경로는 Git에 포함되지 않는 `.local-workspace.json`에 저장된다.
이 설정 API는 로컬 파일 경로가 외부 서버에 노출되지 않도록
`localhost` 요청에서만 동작한다.

`lib/battle-scenario.mjs`는 파티 크기, 포켓몬 ID, 레벨, 기술, 프리셋 존재 여부와 EvE 중복 선택을 검증한다. 동일한 입력과 테스트 시드는 동일한 `scenarioId`를 생성한다.

`lib/showdown-battle-runner.mjs`는 시나리오를 Showdown 팀으로 변환하고 같은 시드에서 같은 결과가 나오도록 전투 RNG와 양쪽 기준선 컨트롤러 RNG를 분리한다. 최대 200턴 또는 10초가 지나면 안전하게 무승부로 종료한다.

`lib/cobbleventure-battle-engine.mjs`는 외부 전투 라이브러리 없이 최소 전투 규칙을 실행한다. 현재는 싱글 배틀의 기본 공격·타입 상성·교체·승패까지 지원하며, 상태 이상이나 기술별 특수 효과는 `unsupported_effect` 이벤트로 반환한다. 웹이나 테스트 도구는 `POST /api/native-battles`에 자체 엔진 입력 JSON을 보내 전체 전투 결과와 이벤트 타임라인을 받을 수 있다.

`lib/interactive-battle-session.mjs`는 PvE에서 플레이어의 기술·교체 선택 기록을
보관한다. 각 HTTP 요청은 시나리오와 이전 행동을 동일 시드로 재생하여 다음 선택
시점까지 계산하므로, 요청 사이에 실행 중인 전투 Promise를 유지할 수 없는
Vinext/Cloudflare 환경에서도 같은 전투 상태를 재현한다. 현재는 싱글 배틀의 일반
기술과 교체를 지원하며 세션은 마지막 행동으로부터 30분 동안 유지된다. PvE의
기준선 상대 AI는 선택 요청마다 사용 가능한 기술 후보와 선택 결과를 추적하여
전투 화면과 AI 판단 로그에 함께 반환한다.

외부 엔진의 버전과 라이선스는 [Third-party notices](THIRD_PARTY_NOTICES.md)에서 관리한다.

## 실행

Node.js 22.13 이상이 필요하다.

### Windows에서 더블클릭

- `start.bat`: 개발 서버를 백그라운드에서 시작하고 브라우저로 `http://localhost:3000`을 연다.
- `stop.bat`: `start.bat`이 시작한 프로세스와 하위 Node 프로세스를 함께 종료한다.

실행 중인 서버에 `start.bat`을 다시 실행하면 새 서버를 중복 생성하지 않고 기존 페이지를 연다. 서버 상태와 오류는 `.local-server.log`, `.local-server-error.log`에서 확인할 수 있으며 Git에는 포함하지 않는다.

웹페이지의 버튼으로 서버를 종료하는 기능은 넣지 않는다. 브라우저 요청으로 호스트 프로세스를 종료하는 방식은 외부 요청 악용과 실행 환경별 오동작 위험이 있기 때문이다.

### 터미널에서 실행

```text
npm ci
npm run dev
```

검증:

```text
npm test
```

`npm run build`와 `npm test`는 트레이너 인덱스를 자동으로 다시 생성한다.
`npm run dev`, `npm start`, `start.bat`도 서버가 시작되기 전에
`public/data/trainers.json`과 `public/data/cobblemon-battle-items.json`을 먼저
생성한다. 생성 파일이 없는 상태에서 개발 서버를 먼저 띄우면 Vite가 누락 상태를
캐시할 수 있으므로, 시작 명령이 실패했다면 실행 중인 서버를 종료하고 다시
시작한다.

모드 구성이 변경되었을 때는 먼저 저장소 최상위의
[`tools/cobblemon-item-catalog`](../../../tools/cobblemon-item-catalog/README.md)
생성기를 실행해야 한다.

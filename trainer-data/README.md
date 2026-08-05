# 트레이너 데이터

이 폴더는 전투 AI 개발, 팀 분석과 가상 대전에서 사용하는 트레이너 원본 데이터를 보관한다.

## 원칙

- 트레이너 데이터는 `entries/<그룹>` 하위 폴더에 분리한다.
- 각 그룹 폴더는 개별 JSON과 JSON을 포함한 ZIP을 함께 사용할 수 있다.
- 원본의 필드명·값·오탈자를 임의로 고치지 않는다.
- AI가 직접 사용하는 정규화 데이터는 후속 변환 단계에서 별도로 생성한다.
- 모드 JAR와 실행 환경 전체는 이 폴더에 복사하지 않는다.
- 데이터 사용·재배포 전에 원본 라이선스와 모드팩 배포 조건을 확인한다.

## 엔트리 구조

- [`entries/rct`](entries/rct): RCT 데이터팩의 트레이너 정의
- [`entries/custom`](entries/custom): DBingsu 등 프로젝트에서 직접 관리하는 엔트리
- [`catalogs/cobblemon-items.csv`](catalogs/cobblemon-items.csv): 현재 활성화된
  Cobblemon·연동 모드 JAR에서 생성한 전체 아이템 검토표
- [`catalogs/cobblemon-items.json`](catalogs/cobblemon-items.json): 변환기와 웹
  전투 실험실이 사용하는 아이템·태그·출처 카탈로그

카탈로그 갱신 방법과 전투 도구 판정 태그는
[`tools/cobblemon-item-catalog`](../tools/cobblemon-item-catalog/README.md)에
기록한다.

`npm run sync:trainers`는 `entries` 아래를 재귀 탐색한다. ZIP은 압축을
풀지 않고 내부의 모든 JSON을 읽으며, 최상위 폴더 이름을 `sourceGroup`으로
기록한다. 서로 다른 파일에 같은 트레이너 ID가 있으면 동기화를 중단하고
충돌한 경로를 출력한다.

RCT의 `ai.data.canTera`와 `ai.data.teraTarget`은 각각 인게임 트레이너의
테라스탈 허용 여부와 지정 대상을 뜻하므로 원본과 정규화 JSON에 보존한다.
현재 웹 가상전투는 `canTera`를 허가 조건으로 요구하지 않으며 전투 규칙상
테라스탈이 가능하면 AI가 사용 여부를 점수로 판단한다. `teraTarget`이 있으면
가상전투에서도 일치하는 포켓몬만 지정 후보가 된다. 지정 대상의
`gimmicks.tera`가 없으면 원래 첫 번째 타입을 기본 테라타입으로 사용한다.

가상전투 시나리오 JSON에는 `aiDifficulty`와 진영별 `aiProfiles`가 기록된다.
콘텐츠 관리 도구의 스키마 2는 `battle.ai`에서 난이도·전략·치터 확률을 편집하며,
`build.bat generate`가 이를 RCT의 `ai.type`·`ai.data`와 실제 게임용 런타임
프로필로 함께 변환한다. 대상 Cobblemon/RCT 버전이 확정되면 게임 어댑터가 이
런타임 프로필을 읽고 서버 전투 세션에 적용한다.

`bag`과 `battleRules.maxItemUses`는 생성된 전투 시나리오에 보존한다. 포켓몬의
`heldItem`은 자체 엔진까지 전달되지만, 트레이너가 턴을 소비해 사용하는 가방
아이템 행동은 아직 미지원이며 해당 가방이 있는 전투에는 명시적 경고를 남긴다.

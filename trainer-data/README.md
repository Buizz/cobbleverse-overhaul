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

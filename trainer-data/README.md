# 트레이너 데이터

이 폴더는 전투 AI 개발, 팀 분석과 가상 대전에서 사용하는 트레이너 JSON 원본 예제를 보관한다.

## 원칙

- 가져온 원본 데이터는 출처별 하위 폴더에 분리한다.
- 원본의 필드명·값·오탈자를 임의로 고치지 않는다.
- AI가 직접 사용하는 정규화 데이터는 후속 변환 단계에서 별도로 생성한다.
- 원본 ZIP, 모드 JAR와 실행 환경 전체를 이 폴더에 복사하지 않는다.
- 데이터 사용·재배포 전에 원본 라이선스와 모드팩 배포 조건을 확인한다.

## 현재 자료

- [`rctmod-v16-ver22`](rctmod-v16-ver22/README.md): RCT 데이터팩의 트레이너 정의 210개
- [`catalogs/cobblemon-items.csv`](catalogs/cobblemon-items.csv): 현재 활성화된
  Cobblemon·연동 모드 JAR에서 생성한 전체 아이템 검토표
- [`catalogs/cobblemon-items.json`](catalogs/cobblemon-items.json): 변환기와 웹
  전투 실험실이 사용하는 아이템·태그·출처 카탈로그

카탈로그 갱신 방법과 전투 도구 판정 태그는
[`tools/cobblemon-item-catalog`](../tools/cobblemon-item-catalog/README.md)에
기록한다.

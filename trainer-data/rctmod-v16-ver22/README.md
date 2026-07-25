# RCT Mod v16 ver22 트레이너 예제

## 출처

- 원본 파일: 저장소 최상위의 `COBBLEVERSE-RCT-DP-v16 ver22 마지막 버전 6.zip`
- 원본 내부 경로: `data/rctmod/trainers/*.json`
- 추출일: 2026-07-24
- 추출 파일 수: 210개

`trainers/`에는 트레이너 이름, 전투 규칙, 가방, 포켓몬 팀, AI 타입과 AI별 데이터를 가진 JSON 원본을 보관한다.

## 이번 추출에서 제외한 자료

압축에 함께 포함된 다음 자료는 트레이너 본체 정의가 아니므로 우선 제외했다.

- `data/rctmod/dialogs/trainers`: 대화 정의
- `data/rctmod/mobs/trainers`: NPC 외형·몹 정의
- `data/rctmod/loot_table/trainers`: 트레이너 보상표
- `data/rctmod/trainer_types`: 트레이너 유형
- `data/rctmod/series`: 시리즈 정의
- 범용 전리품, `pack.mcmeta`, 이미지와 라이선스 파일

AI 또는 콘텐츠 연동에서 필요해지면 원본 ZIP의 경로를 보존한 별도 하위 폴더로 추가한다.

## 사용 시 주의사항

이 자료는 현재 AI 입력 스키마가 아니라 **외부 예제 원본**이다. 파일에 따라 다음 차이가 있을 수 있다.

- 능력치 키가 `atk`/`attack`, `def`/`defence`처럼 서로 다를 수 있음
- 아이템·특성·기술 ID의 네임스페이스 또는 철자가 다를 수 있음
- 팀 크기, 가방, 전투 규칙과 AI 데이터가 생략될 수 있음
- 현재 개발 목표인 Cobblemon 1.8과 데이터 의미가 달라질 수 있음

후속 구현에서는 원본을 직접 수정하지 않고 `TrainerDefinitionAdapter`가 정규화·검증 오류를 보고하도록 한다.

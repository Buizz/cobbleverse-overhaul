# Cobbleventure Casino

Cobblemon Casino 2.0.0의 화폐를 사용하면서, Content Studio에서 기계별 보상과 외형을 관리하는 NeoForge 애드온입니다.

## 콘텐츠 흐름

1. Content Studio의 `카지노 설정 > 커스텀 가챠 기계`에서 프로필을 편집합니다.
2. 설정은 `content-projects/cobbleventure-main/content/catalogs/gacha-machines.json`에 저장됩니다.
3. `build.bat mod-casino` 또는 `build.bat pack`이 카탈로그를 애드온 JAR의 `data/cobbleventure_casino/gacha/machines.json`으로 포함합니다.
4. 서버 재시작 후 운영자가 기계를 배치합니다.

## 운영 명령

- `/cvgacha place <profile> <x y z>`: 프로필 외형으로 파괴 불가 기계를 배치합니다.
- `/cvgacha remove <x y z>`: 해당 앵커의 기계를 제거합니다.
- `/cvgacha reload`: 카탈로그를 다시 읽고 로드된 기계 외형을 갱신합니다.
- `/cvgacha status <profile>`: 자신의 확정·선택 천장 진행도를 확인합니다.
- `/cvgacha select <profile> <reward>`: 선택 천장 포인트를 사용해 원하는 보상을 받습니다.

기계는 일반 설치 아이템이나 블록이 아니라, 무적 Block Display와 Interaction 엔티티로 구성됩니다. 따라서 플레이어가 설치하거나 파괴할 수 없고 웹에서 지정한 블록·크기·높이·회전을 그대로 표현할 수 있습니다.

## 천장 동작

- 소프트 천장: 설정한 시작 횟수부터 목표 희귀도의 최종 확률을 선형 보정합니다.
- 확정 천장: 지정 횟수에 목표 희귀도를 강제로 선택합니다.
- 선택 천장: 뽑을 때 포인트를 쌓고 `selectable` 보상을 직접 교환합니다.
- `pity_group`이 같은 기계는 플레이어별 천장 진행도를 공유합니다.

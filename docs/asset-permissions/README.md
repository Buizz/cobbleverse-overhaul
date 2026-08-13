# 뱃지 그래픽 출처 및 사용 기록

이 폴더는 트레이너 카드 뱃지 그래픽의 출처와 사용 근거를 보존합니다. 포켓몬 관련 명칭과 디자인의 권리는 각 권리자에게 있으며, 아래 허가는 팬아트 제작자가 기여한 픽셀 그래픽에 대한 기록입니다.

## 1~6세대

- 제작자: JcFerggy
- 원본: [16x16 Pokemon Badge Sprites: Gen 1-6](https://www.deviantart.com/jcferggy/art/16x16-Pokemon-Badge-Sprites-Gen-1-6-544204402)
- 추가 크레딧: 원본 설명에 따라 하나지방 뱃지의 기반 작업은 SoaringSkies0에게 크레딧합니다.
- 허가 기록: Cobblemon 모드 프로젝트에서 크레딧을 표기해 사용해도 되는지 질문했고, JcFerggy가 원하는 대로 사용해도 된다고 답했습니다. 댓글 캡처는 [jcferggy-badge-permission.png](jcferggy-badge-permission.png)에 보존합니다.
- 가공: 원본 시트의 16×16 셀을 추출하여 nearest-neighbour로 32×32 확대합니다.

## 8세대 가라르

- 제작자: Cobbleverse Overhaul 프로젝트
- 편집 원본: `tools/content-manager/assets/badges/galar-custom.png`
- 배열: 5열 × 2행, 각 셀 32×32이며 카탈로그의 가라르 관장 순서입니다.
- 이 파일을 직접 수정한 뒤 `python tools/content-manager/build_badge_atlas.py`를 실행하면 전역 아틀라스에 반영됩니다.

## 9세대 팔데아

- 제작자: ProfessorMorDBG
- 원본: [Paldea Badges demake large](https://www.deviantart.com/professormordbg/art/Paldea-Badges-demake-large-1142694862)
- 사용 조건: 게시자가 자유 사용 가능하다고 표시한 자료를 사용합니다.
- 가공: 전체 18개 중 체육관 뱃지 8개만 추출하여 nearest-neighbour로 32×32 축소합니다.

게임에 포함되는 최종 파일은 `projects/cobbleventure-player-menu/src/main/resources/assets/cobbleventure_player_menu/textures/gui/badges.png`이며, 세부 셀 좌표와 변환 방식은 `tools/content-manager/badge-image-sources.json`에 기록됩니다.

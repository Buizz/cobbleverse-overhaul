# 관동 관장 스킨 교체

2026-09-02에 사용자가 제공한 PNG를 원본 그대로 등록했다. 모두 64×64,
Classic(Steve) 모델이며 리사이즈나 색상 변경을 하지 않았다.

| 관장 | 제공 파일 (`local-assets/skins/`) | 등록 리소스 |
| --- | --- | --- |
| 민화 | Erika-HGSS-on-planetminecraft-com.png | `cobbleventure:trainer_skin/erika` |
| 강연 | Blaine-HGSS-on-planetminecraft-com.png | `cobbleventure:trainer_skin/blaine` |
| 독수 | Koga-HGSS-on-planetminecraft-com.png | `cobbleventure:trainer_skin/koga` |
| 초련 | Sabrina-HGSS-on-planetminecraft-com.png | `cobbleventure:trainer_skin/sabrina` |
| 비주기(관장) | 기존 레인보우로켓단 비주기 스킨 재사용 | `cobbleventure:trainer_skin/rainbow_rocket_giovanni` |

관장 전투·트레이너 카드의 외형은 `league-progression.json`의
`encounter.appearance`, 캐릭터 목록은 `trainer-roster.json`의
`league_characters[].appearance`에 동일하게 연결한다.
별도 파일을 관장 JSON에 복제하거나 포켓몬 라인업·진행 순서를 바꾸지 않는다.

원본 제공 파일이 없어도 빌드할 수 있도록 실제 텍스처는
`projects/cobbleventure-world-bootstrap/src/main/resources/assets/cobbleventure/textures/entity/trainer/`
에 포함한다. EasyNPC용 스킨은 기존 프리셋 생성기로 다시 생성한다.

제공된 네 파일은 파일명으로만 PlanetMinecraft 출처를 확인했다.
개별 게시물 URL·제작자·재배포 허가 조건은 아직 확인하지 않았으므로
공개 배포 전에 별도로 확인해야 한다.

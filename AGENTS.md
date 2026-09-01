# 저장소 작업 지침

## 포켓몬 서식지·출현 설정

포켓몬 서식지, 자연 출현, 파도타기, 낚시, 박치기, 숲·동굴·던전 조우를
수정하기 전에 반드시
`docs/implementation/POKEMON_ENCOUNTER_AUTHORING.md`를 먼저 읽고 그 체크리스트를
따른다.

- 실제 출현 위치와 출현 풀을 따로 확인한다. 길 프리셋의 풀만 수정하고
  `worlds/generation_1.json`의 `cells` 또는 `encounter_cells`를 빠뜨리지 않는다.
- 일반 육상, 파도타기, 낡은·좋은·대단한낚싯대, 박치기는 서로 독립된 풀이다.
- 본가처럼 고정된 지역 풀은 `inherit_biome: false`로 작성한다. 이를 생략하거나
  `true`로 두면 바이옴 기본 포켓몬이 섞이는 것이 정상 동작이다.
- 길·숲·동굴에서 종별 레벨을 고정할 때는 `additions`와
  `level_overrides`를 함께 작성한다. `additions.min_level/max_level`만 작성하고
  완료로 판단하지 않는다.
- 던전은 길 풀이 아니라 던전 JSON의 `random_encounters`를 수정한다.
- 변경 후 콘텐츠 전체 검증과 해당 출현 회귀 테스트를 실행한다.

## 인게임 UI 테마·타이포그래피

새 화면, 오버레이, 대화상자, 버튼을 추가하거나 기존 UI를 수정할 때는
`content-projects/cobbleventure-main/content/catalogs/dialogue-theme.json`과
`projects/cobbleventure-player-menu/src/main/java/dev/buizz/cobbleventure/playermenu/client/MenuTheme.java`를
단일 UI 테마 소스로 사용한다.

- 화면 클래스에 일반 패널색, 글자색, 비활성색, 호버색, 테두리색을 다시
  하드코딩하지 않는다. 필요한 의미 토큰이 없다면 먼저 전역 테마, 스키마,
  콘텐츠 관리 편집기를 함께 확장한다.
- 폰트 크기와 그림자 여부를 화면에서 숫자나 불리언으로 직접 지정하지 않는다.
  `TITLE`, `HEADING`, `BODY`, `LABEL`, `CAPTION`과 같은 의미 기반
  `MenuTheme.TextRole`을 사용한다.
- 버튼은 화면마다 상태별 스타일을 다시 만들지 않는다. 용도에 맞는
  `MenuTheme.ButtonVariant`와 공통 `normal`, `hover`, `selected`, `disabled`
  상태 토큰을 사용한다.
- 사용자가 임의로 이탈할 수 있는 메뉴 화면에는 키보드 ESC 동작과 별개로 항상
  보이는 `MenuBackButton`을 배치한다. 필수 선택·연출 화면만 명시적인 예외로 둔다.
- 공통 패널은 `ThemedOverlayPanel`을 사용한다. 다른 모듈에서 별도의 테마 파서나
  동일한 둥근 패널 렌더러를 복제하지 않는다.
- HP, 경험치, 타입, 지도 지형처럼 색 자체가 게임 정보를 전달하는 도메인 색상과
  고유 아트 팔레트는 예외로 둘 수 있다. 단순 장식·레이아웃 색상은 예외가 아니다.
- 새 모듈이 공통 UI를 사용하면 `cobbleventure-player-menu` 의존성과 모드 메타데이터를
  명시하고, 모듈 내부에 축소판 테마 클래스를 만들지 않는다.
- 테마 키를 추가·변경할 때는 `dialogue-theme.schema.json`,
  `tools/content-manager/content_manager.py`, 콘텐츠 관리 웹 편집기의 기본값과
  입력 UI도 함께 갱신한다.
- 변경 후 관련 모듈의 빌드·테스트와 테마 콘텐츠 계약 테스트를 실행한다.

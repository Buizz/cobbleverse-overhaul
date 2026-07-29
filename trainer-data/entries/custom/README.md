# 커스텀 엔트리

Cobbleverse에서 직접 관리하는 플레이어 엔트리와 전투 AI 검증용 팀을
보관한다. 이 폴더의 JSON과 ZIP은 `npm run sync:trainers` 실행 시
`custom` 그룹으로 읽힌다.

## SV9 실전 테스트 팀

- `sv9-hazard-balance.json`: 스텔스록, 제거, 피벗, 용춤 마무리를 함께
  시험하는 밸런스 팀
- `sv9-speed-offense.json`: 선봉 설치, 고속 랭크업, 선공기와 스카프
  마무리를 시험하는 공격 팀
- `sv9-trick-room-balance.json`: 복수의 트릭룸 전개자와 저속
  브레이커의 교체 판단을 시험하는 팀
- `sv9-screens-offense.json`: 스크린 전개 후 연속 랭크업과 에이스 보존을
  시험하는 현대 OU 공격 팀
- `sv9-modern-bulky-offense.json`: 소금절이, 매직미러, 재생력 피벗과
  고속 에이스를 함께 시험하는 벌키 오펜스
- `sv9-sand-balance.json`: 모래날림과 모래헤치기 연계, 날씨 수혜 교체를
  시험하는 모래바람 밸런스

SV 팀은 2026년 7월 13일 갱신된 SV OU 샘플과 2026년 SPL XVII 공개
팀 덤프에서 확인한 역할 조합을 참고해 프로젝트에서 새로 구성했다. 특정
선수의 대회 엔트리를 그대로 복제한 자료는 아니다.

## Pokémon Champions 공식 샘플

- `champions-mega-mawile-dual-mode.json`: 2026-07-21 공식 메가입치트
  팀. 트릭룸과 메가리자몽 Y 쾌속 모드를 함께 사용한다.
- `champions-mega-raichu-x-rain.json`: 2026-07-14 공식 메가라이츄 X
  팀. 비, 일렉트릭필드, 순풍과 브리두라스 전개를 사용한다.
- `champions-mega-emboar-setup.json`: 2026-05-13 공식 메가염무왕
  팀. 벌크업/명상 전개를 분노가루와 속도 제어로 지원한다.

포챔스 원문의 Stat Points는 각 포켓몬의 `champions.statPoints`에
보존했다. 현재 엔진에서 계산할 수 있도록 동일한 투자 비율의 EV 값도 함께
기록했다. 이 엔트리들은 더블배틀을 전제로 한다.

참고 자료:

- https://www.smogon.com/forums/threads/sv-ou-sample-teams-new-samples-added-post-wcop.3712513/
- https://www.smogon.com/forums/threads/spl-xvii-ou-discussion-thread.3775874/
- https://www.pokemon.com/br/features/pokemon-champions-como-montar-uma-equipe-com-mega-mawile
- https://www.pokemon.com/us/features/pokemon-champions-how-to-build-a-mega-raichu-x-team
- https://www.pokemon.com/us/features/pokemon-champions-how-to-build-a-mega-emboar-team

실전 구성을 우선 보존하므로 아직 자체 엔진에서 구현되지 않은 특성도
포함한다. 엄격 특성 검증에서 발견되는 항목은 후속 특성 구현 체크리스트의
입력으로 사용한다.

현재 추가 구현이 필요한 대표 특성:

- 정화의소금(`purifyingsalt`)
- 자력(`magnetpull`)
- 재생력(`regenerator`)
- 쿼크차지(`quarkdrive`)
- 고대활성(`protosynthesis`)
- 독치장(`toxicdebris`)
- 총대장(`supremeoverlord`)
- 매직미러(`magicbounce`)
- 나이트메어(`baddreams`)
- 모래날림(`sandstream`)
- 모래헤치기(`sandrush`)
- 불꽃몸(`flamebody`)
- 불굴의방패(`dauntlessshield`)

포챔스 샘플로 추가된 후속 구현 대상:

- 괴력집게(`hypercutter`)
- 맹화(`blaze`)
- 테일아머(`armortail`)
- 까칠한피부(`roughskin`)
- 엽록소(`chlorophyll`)
- 피뢰침(`lightningrod`)
- 지구력(`stamina`)
- 잔비(`drizzle`)
- 황금몸(`goodasgold`)
- 대접(`hospitality`)
- 질풍날개(`galewings`)
- 촉촉보이스(`liquidvoice`)
- 메가염무왕과 메가라이츄 X의 폼, 종족값, 타입과 메가 특성

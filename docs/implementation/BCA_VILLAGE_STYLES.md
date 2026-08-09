# BCA 마을과 주택 스타일

Cobbleventure의 마을 외형은 BCA의 원본 마을 프리셋과 주택 Jigsaw 풀을 분리해 관리한다.
Python 관리 웹의 마을 화면에서 두 값을 각각 선택할 수 있다.

아래 목록은 팩에 포함한 BCA 4.2.1 JAR의 `worldgen/structure/village`와 시작
템플릿 풀을 직접 확인한 결과다. `witch_hut`은 마을 프리셋이 아니므로 관리 웹의
마을 목록에서는 제외한다.

| 마을 종류 (`village_preset`) | 원본 시작 풀 | Jigsaw 깊이 | 중심 조각/시설 |
| --- | --- | ---: | --- |
| `default_small` | `bca:default/small` | 2 | 소형 배틀패드 도로 |
| `default_mid` | `bca:default/mid` | 3 | 배틀패드·로지·아카데미 중 하나 |
| `default_large` | `bca:default/large` | 4 | 백화점 고정 |
| `fighting_small` | `bca:fighting/small` | 4 | 격투 소형 중심지 중 하나 |
| `fighting_mid` | `bca:fighting/mid` | 4 | 격투 농산물 시장 |
| `fighting_large` | `bca:fighting/large` | 6 | Wyrm's Rest |
| `dark_small` | `bca:dark/small` | 2 | 악 테마 마을 도로 |
| `dark_mid` | `bca:dark/mid` | 3 | 농산물 시장·Marshlight Tavern 중 하나 |
| `ice_small` | `bca:ice/small` | 4 | 아이스링크·설원 도로 중 하나 |
| `ice_mid` | `bca:ice/mid` | 4 | 중형 이글루 길 |
| `ice_large` | `bca:ice/large` | 4 | Silverpine Lodge Large |

`default` 계열의 도로는 `bca:default/one_off` 풀을 통해
`bca:default/one_off/pokecenter`와
`bca:default/one_off/structure_pokemart`를 생성할 수 있다. 두 시설은 도로 확장
중 선택되는 one-off 조각이므로 매번 둘 다 나온다고 보장되지는 않는다.
`default_large`의 백화점만 시작 조각이므로 항상 생성된다. 격투·악·얼음 계열의
one-off 풀에는 포켓몬센터와 포켓몬 상점이 없다.

`village_preset`은 BCA 원본 시작 풀과 생성 깊이를 정한다. 이 시작 풀을 사용해야
BCA 중심 시설을 건너뛰지 않는다. `house_style`은 BCA 도로가 집을 요청할 때
실제로 선택되는 주택 풀을 정한다. 단, BCA의 기본·격투·악·얼음 풀은 서로 다른
Jigsaw `name`/`target` 계약을 사용하므로 테마를 가로질러 주택 풀만 바꿀 수 없다.
호환되지 않는 BCA 풀을 지정하면 런타임은 집이 전부 사라지는 대신 도로가 원래
요청한 주택 풀을 유지한다.

`commercial_center`는 마을의 상업 중심 시설을 정확히 한 번 보장한다.

- `pokemart`: `bca:default/one_off/structure_pokemart`를 시작 조각으로 사용한다.
- `department_store`: `bca:default/centers/center_department_store`를 시작 조각으로 사용한다.
- `preset`: 선택한 BCA 프리셋의 원래 중심 조각을 그대로 사용한다.
- `none`: 상업 중심 시설을 만들지 않고 BCA one-off 상업 풀도 차단한다.

1세대 기본 구성은 Crimson Town 한 곳만 `department_store`이며, 나머지 네 마을은
`default_mid`와 `pokemart`를 사용한다. 따라서 백화점은 한 곳에만 생기고 일반
마을에는 기본 포켓몬 상점이 확률과 무관하게 생성된다.

지역 연결 도로는 BCA 기본 도로와 동일한 돌벽돌·조약돌·안산암·돌·이끼 낀
돌벽돌 팔레트를 사용한다. 도로 폭은 3블록으로 유지해 마을 내부 길과 이어질 때
재질과 폭이 갑자기 달라 보이지 않게 한다.

`cobbleventure_starter`는 BCA 원본 11종과 별개의 전용 프리셋이다. 연구소를
고정 중심 조각으로 사용하고 주변 도로·주택만 제한적으로 조립한다. 자세한 구성은
`docs/implementation/STARTER_TOWN_LAYOUT.md`를 참고한다.

## 주택을 자체 형태로 교체하기

1. 새 주택 구조물 NBT를 데이터 모드에 추가한다.
2. 주택들을 묶는 Jigsaw 템플릿 풀 JSON을 만든다.
3. 관리 웹에서 해당 마을의 `주택 형태 · Jigsaw 풀 ID`에 새 풀 ID를 입력한다.
4. 데이터 모드와 CurseForge 팩을 다시 빌드한다.

마을 생성 코드는 커넥터 계약이 호환되는 사용자 제작 풀을 `house_style`로
치환할 수 있다. BCA 기본 테마 사이를 교체하려면 중심 조각·도로·주택 풀을 같은
테마 세트로 바꿔야 한다. BCA 주택·가로등·장식의 기준점이 한 블록 높은 문제는 Cobbleventure가
마을을 배치하는 동안에만 해당 하위 템플릿을 한 칸 내리는 보정으로 처리한다.
도로·장식·가로등에 포함된 공기 블록과 장식 연결용 Jigsaw의 빈 최종 상태는 생성
지표 이하의 블록을 제거하지 않게 처리한다. 지표보다 위쪽의 공기는 그대로 적용해
식생을 정리하면서도 주변이 한 칸 파이거나 장식물이 땅속에 묻히지 않도록 한다.

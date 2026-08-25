# Cobbleventure Casino

Cobblemon Casino 2.0.0의 화폐를 사용하면서, Content Studio에서 기계별 보상과 외형을 관리하고 Playing Cards & Chips 테이블을 블랙잭 게임에 연결하는 NeoForge 애드온입니다.

## 블랙잭 테이블 외형

- 편집 월드에는 `playingcards:poker_table`을 원하는 모양으로 직접 배치합니다.
- 딜러가 설 위치에는 `npc_position` 앵커를 두고, 고정 NPC로 블랙잭 딜러 프리셋을 연결합니다.
- 딜러가 처음 로드되면 반경 6블록 안의 가장 가까운 테이블 아래에 원본
  `cobblemoncasino:blackjack_table`을 숨겨 연결합니다.
- 외형 테이블은 기능이 없으며, `cobbleventure_npc_function_blackjack` 태그가 붙은
  딜러를 우클릭할 때만 숨겨진 원본 테이블의 블랙잭을 시작합니다.
- 플레이어가 테이블에 접근하면 각 상판에 카드 덱·뒷면 카드·색상 칩 장식이 비상호작용 Item Display로 자동 생성됩니다.
- 실제 카지노에서는 Playing Cards 테이블·카드·칩·주사위 엔티티를 무적·무중력
  장식으로 잠급니다. 우클릭, 소유권 지정, 칩 쌓기·회수, 공격·파괴 및 새 장식 배치는
  모두 취소되며 포켓몬 카지노의 화폐나 베팅 기능에는 사용되지 않습니다.
- Structure Builder에는 이 잠금 애드온을 넣지 않으므로 건축가는 카드와 칩을 정상적으로
  배치하고 위치를 조정할 수 있습니다.

```json
{
  "anchors": [
    {
      "id": "blackjack_dealer_1",
      "type": "npc_position",
      "position": [17, 1, 4],
      "facing": "west"
    }
  ]
}
```

NPC 링커 ID는 `blackjack_dealer_1`, `blackjack_dealer_2`처럼 고유한 번호를
붙입니다. 건물 설정에는 `blackjack_dealer_*`를, 동적 내부 공간을 연결하는 외부
건물에는 `room_1:blackjack_dealer_*`를 지정하면 모든 번호가
`cobbleventure:npc/blackjack_dealer` 프리셋으로 자동 배정됩니다. 구조물 회전 시
앵커 위치와 딜러 방향은 기존 NPC 링커가 자동 반영합니다.

## 콘텐츠 흐름

1. Content Studio의 `카지노 설정 > 카지노별 가챠 세트`에서 카지노 세트와 3개 기계를 편집합니다.
2. 각 테마의 희귀도 풀에서 포켓몬이나 아이템을 바로 선택합니다. 포켓몬은 레벨과 확률 가중치를, 아이템은 수량과 확률 가중치를 함께 저장합니다.
3. 코인케이스와 포켓몬·아이템·기술머신 티켓 PNG는 같은 화면에서 미리 보고 각각 교체할 수 있습니다.
4. 설정은 `content-projects/cobbleventure-main/content/catalogs/gacha-machines.json`에 저장됩니다.
5. `build.bat mod-casino` 또는 `build.bat pack`이 카탈로그를 애드온 JAR의 `data/cobbleventure_casino/gacha/machines.json`으로 포함합니다.
6. 서버 재시작 후 운영자가 기계를 배치합니다.

## NPC 카지노 잔액 충전

플레이어는 칩 아이템을 만들거나 게임 화면에서 입금·출금하지 않습니다. scoreboard
태그 'cobbleventure_casino_cashier'가 붙은 NPC를 우클릭하면 코블달러를 Cobblemon
Casino의 내부 잔액으로 바로 충전합니다. 이 잔액은 슬롯과 블랙잭에서 즉시 공유해
사용하며, 코블달러 또는 칩 아이템으로 역환전할 수 없습니다.

환전 NPC는 'cobbleventure_casino:coin_case'를 기본 인벤토리 또는 Cobbleventure
확장 가방에 가진 플레이어만 사용할 수 있습니다. EasyNPC 프리셋에는
Tags:["cobbleventure_casino_cashier"] 태그를 추가합니다.

칩 교환대, 카지노 지갑, 게임 내 칩 입금·출금 화면은 모두 차단되어 확장 가방 대신
기본 Minecraft 인벤토리가 열리는 일이 없습니다. 배포팩에 포함된 원본 모드 호환
설정도 모든 칩 교환 방향을 비활성화합니다. 이 원본 설정은 Content Studio에 노출하지
않고 커스텀 카지노 콘텐츠만 편집하도록 구성합니다.

## 카지노 세트와 공통 물리 티켓

카지노 세트 하나는 포켓몬·아이템·기술머신 기계 각 1대, 총 3대로 구성됩니다. 세트마다
기계 외형, 보상 풀과 천장을 독립적으로 설정하므로 카지노별 당첨 상품을 다르게 만들 수
있습니다.

가챠는 기존 확률 보정 주화를 사용하지 않습니다. `cobbleventure_casino:gacha_ticket`
아이템에는 기계 프로필 ID 대신 `pokemon`, `item`, `technical_machine` 티켓 종류를
저장합니다. 따라서 한 카지노에서 얻은 포켓몬 티켓은 다른 카지노의 포켓몬 기계에서도
그대로 사용할 수 있습니다. 기존 기본 3종 기계용으로 지급된 티켓도 종류를 추론해
호환합니다.

각 물리 기계는 개수 제한 없는 `themes` 목록을 가집니다. 플레이어는 기계를 열어 일반·스타팅·전설
등의 테마를 먼저 선택하며, 테마별 `ticket_cost`만큼 같은 종류의 티켓을 소모합니다. 보상 풀과
소프트·확정·선택 천장 진행도는 테마별 `pity_group`으로 서로 분리됩니다. Content Studio에서 테마
이름·소모량·순서·보상·천장을 각각 편집할 수 있습니다.

카지노의 `npc2`에는 V5 가챠 티켓 교환상이 배치됩니다. 동전케이스를 가진 플레이어가
공통 티켓 종류와 수량을 입력하면, 웹 카탈로그의 종류별 장당 가격과 최소·최대 구매
수량을 서버가 검증한 뒤 카지노 잔액을 차감하고 확장 가방에 지급합니다.
## 운영 명령

- `/cvgacha place <profile> <x y z>`: 프로필 외형으로 파괴 불가 기계를 배치합니다.
- `/cvgacha remove <x y z>`: 해당 앵커의 기계를 제거합니다.
- `/cvgacha reload`: 카탈로그를 다시 읽고 로드된 기계 외형을 갱신합니다.
- `/cvgacha ticket give <players> <profile> <amount>`: 맵 보상·운영용으로 해당 프로필 종류의 공통 물리 티켓을 지급합니다.
- `/cvgacha ticket buy <profile> <amount>`: 티켓 교환상 NPC가 호출하며, NPC 근처·동전케이스·수량·잔액을 서버에서 검증합니다.
- `/cvgacha status <profile>`: 자신의 확정·선택 천장 진행도를 확인합니다.
- `/cvgacha select <profile> <reward>`: 선택 천장 포인트를 사용해 원하는 보상을 받습니다.

기계는 일반 설치 아이템이나 블록이 아니라, 무적 Block Display와 Interaction 엔티티로 구성됩니다. 따라서 플레이어가 설치하거나 파괴할 수 없고 웹에서 지정한 블록·크기·높이·회전을 그대로 표현할 수 있습니다.

## 천장 동작

- 소프트 천장: 설정한 시작 횟수부터 목표 희귀도의 최종 확률을 선형 보정합니다.
- 확정 천장: 지정 횟수에 목표 희귀도를 강제로 선택합니다.
- 선택 천장: 뽑을 때 포인트를 쌓고 `selectable` 보상을 직접 교환합니다.
- `pity_group`이 같은 기계는 플레이어별 천장 진행도를 공유합니다.

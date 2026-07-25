# Cobblemon 아이템 카탈로그 생성기

Cobblemon과 연동 모드의 활성화된 JAR을 직접 읽어 아이템 목록을 CSV와 JSON으로
갱신한다. Pokémon Showdown의 아이템 목록이 아니라 실제 모드 리소스가 기준이다.

## 갱신

저장소 최상위에서 다음 명령을 실행한다.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\cobblemon-item-catalog\Export-CobblemonItemCatalog.ps1 `
  -ModsDirectory "G:\2026 MineCraft\코블버스\호연엔트리\CobbleverseTrainerWebEditorWorkspace\mods"
```

생성 파일:

- `trainer-data/catalogs/cobblemon-items.json`: 웹과 변환기가 읽는 기계용 카탈로그
- `trainer-data/catalogs/cobblemon-items.csv`: 사람이 검토하는 표
- `trainer-data/catalogs/cobblemon-item-sources.json`: 반영한 JAR과 생성 상태

`.jar.disabled`는 읽지 않는다. 모드 구성이 바뀌면 같은 명령을 다시 실행한다.

## 전투 도구 판정 기준

에디터와 동일하게 다음 Minecraft item tag를 재귀적으로 해석한다.

- `cobblemon:held/is_held_item`
- `cobblemon:berries` (`cobblemon:berries/non_battle` 제외)
- `cobblemon:type_gems`
- `mega_showdown:mega_stone`
- `mega_showdown:z_crystal`

번역 키만 존재하고 위 태그에 들어 있지 않은 아이템은 카탈로그에는 포함하지만
`battleUsable=false`, `battleCategory=unverified`로 기록한다.

## 한계와 런타임 검증

이 도구는 JAR 안의 정적 리소스를 읽는다. KubeJS 스크립트가 런타임에 새 아이템을
등록하거나 데이터팩이 태그를 덮어쓰는 경우에는 실행 중인 Minecraft 레지스트리가
최종 기준이다. 그런 항목은 추후 레지스트리 덤프를 가져오는 단계에서 합쳐야 한다.

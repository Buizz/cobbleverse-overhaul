# Cobbleventure 커스텀 스폰 생성기

`코블몬_바이옴_스폰_정리.xlsx`의 `스폰_편집` 시트를 읽어 Cobblemon 1.7.3용
Paxi 데이터팩을 생성한다. 생성 ZIP은 빌드 산출물이므로 Git에 커밋하지 않는다.

```powershell
python tools/cobblemon-custom-spawns/build_custom_spawns.py --root .
```

기본 출력은 다음과 같다.

- `pack/overrides/development-placeholder/config/paxi/datapacks/zzz-cobbleventure-spawns.zip`
- `outputs/cobbleventure-custom-spawns-report.json`

## 편집 규칙

- `적용여부_편집`: `사용`만 데이터팩에 포함한다.
- `허용세대월드_편집`: `condition.dimensions`로 변환한다.
- `바이옴_편집`: 비어 있으면 원본 바이옴을 유지하고, 값이 있으면 `condition.biomes`를 교체한다.
- `가중치배율_편집`: 원본 가중치에 곱한다. `1`은 원본 유지다.
- `레벨_편집`: 비어 있으면 원본 레벨을 유지한다.

여러 값은 세미콜론(`;`)으로 구분한다. `지역배정_편집`은 지역별 실제 바이옴이
확정되기 전에는 Cobblemon 조건으로 안전하게 변환할 수 없으므로, 값이 있으면 생성이
실패한다. 지역을 확정한 뒤 해당 행의 `바이옴_편집`에 실제 바이옴 또는 바이옴 태그를
입력한다.

생성 데이터팩은 원본 스폰 파일과 같은 `data/cobblemon/spawn_pool_world` 경로를
사용해 파일 단위로 덮어쓴다. 원본의 시간, 날씨, 광량, 위치 유형, 블록, 구조물,
가중치 보정과 기타 조건은 유지한다.
모든 행이 `제외`된 원본 파일도 비활성 빈 파일로 출력하므로 하위 데이터팩의 원본
스폰이 다시 살아나지 않는다.

원본 편집표 안에서 같은 파일의 스폰 ID가 중복되면 첫 ID는 유지하고 뒤 항목에
`-cobbleventure-2` 형식의 접미사를 붙인다. 보정 건수는 생성 보고서에서 확인한다.

이 생성물은 현재 내부 개발 팩용이다. 워크북이 참고한 외부 데이터와 Cobblemon 원본의
배포 조건을 확인하기 전에는 공개 배포하지 않는다.

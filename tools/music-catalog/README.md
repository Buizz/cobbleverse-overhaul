# 음악 카탈로그

어나더레드 BGM 원본을 저장소에 포함하지 않고, 검토를 통과한 곡의 안정적인
사운드 ID와 원본 파일명만 관리합니다. 코드와 리소스팩 생성 과정은 반드시
`content/catalogs/music-tracks.json`의 `tracks`만 사용합니다.

`review_candidates`는 어나더레드 고유곡일 가능성이 있거나 출처·사용 장면을
확인해야 하는 후보입니다. 이 항목은 `sounds.json`에 포함되지 않습니다.

## 검증

외부 BGM 폴더에 활성 목록의 28곡이 모두 있는지 확인합니다. 파일을 복사하거나
변경하지 않습니다.

```powershell
py -3 tools\music-catalog\music_catalog.py `
  --check-source "G:\포켓몬어나더레드\Pokemon Another Red_PWT_250821_2\Pokemon Another Red_PWT_250821\Audio\BGM"
```

## 리소스팩 목록 생성

음원이 없는 `sounds.json`만 생성합니다. 출력 경로는 생성물 디렉터리처럼 Git에서
제외된 위치를 사용합니다.

```powershell
py -3 tools\music-catalog\music_catalog.py `
  --output generated\music-resourcepack\assets\cobbleventure_music\sounds.json
```

생성되는 이벤트는 `cobbleventure_music:music.kanto.pallet_town` 같은 형식입니다.
실제 음원 조립은 별도 로컬 단계로 둡니다.

## 데이터팩 경계

데이터팩은 필수가 아닙니다. 모드 코드가 카탈로그의 이벤트 ID를 직접 재생할 수
있고, 명령 함수나 맵 제작용 트리거가 필요한 배포 구성에서만 선택적으로 데이터팩을
추가합니다. 데이터팩을 추가하더라도 곡 목록을 복제하지 않고 이 카탈로그의 이벤트
ID만 참조합니다.

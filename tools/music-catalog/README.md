# 음악 카탈로그

어나더레드 BGM 원본은 Git에서 제외된 `local-assets/music/another-red-bgm`에
보관합니다. 웹 에디터가 이 폴더의 OGG를 감지해 안정적인 사운드 ID와 원본
파일명을 `content-projects/cobbleventure-main/content/catalogs/music-tracks.json`의
`tracks`에 자동 등록합니다. MIDI는 자동 등록하지 않습니다.

`review_candidates`는 어나더레드 고유곡일 가능성이 있거나 출처·사용 장면을
확인해야 하는 후보입니다. 이 항목은 `sounds.json`에 포함되지 않습니다.

## 검증

로컬 BGM 폴더에 카탈로그의 음원이 모두 있는지 확인합니다. 파일을 복사하거나
변경하지 않습니다.

```powershell
py -3 tools\music-catalog\music_catalog.py `
  --check-source local-assets\music\another-red-bgm
```

## 리소스팩 생성

상황별 기본값과 월드·마을·도로·배틀·체육관에서 실제 참조하는 곡만 복사해 Paxi가
읽는 리소스팩 ZIP을 생성합니다. 카탈로그에 등록돼 있어도 사용하지 않은 곡은 ZIP에
포함되지 않습니다.

```powershell
build.bat music
```

출력 파일은
`pack/overrides/development-placeholder/config/paxi/resourcepacks/Cobbleventure-Music.zip`
입니다. ZIP에는 Minecraft 1.21.1 리소스팩 형식 34의 `pack.mcmeta`, `sounds.json`,
선택한 OGG 파일과 `assets/musicnotification/musics.json`이 들어갑니다. 생성되는
이벤트는 `cobbleventure_music:music.kanto.pallet_town` 같은 형식입니다.

`build.bat pack`도 리소스팩을 먼저 생성합니다. 로컬 음원 폴더가 없거나 선택한
파일이 누락되면 오래된 ZIP을 사용하지 않고 빌드가 실패합니다.

Music Notification은 재생 엔진이 아니라 곡 제목·저자·앨범 알림과 주크박스 UI를
제공합니다. 실제 재생은 위의 표준 사운드 이벤트를 사용합니다.

## 데이터팩 경계

데이터팩은 필수가 아닙니다. 모드 코드가 카탈로그의 이벤트 ID를 직접 재생할 수
있고, 명령 함수나 맵 제작용 트리거가 필요한 배포 구성에서만 선택적으로 데이터팩을
추가합니다. 데이터팩을 추가하더라도 곡 목록을 복제하지 않고 이 카탈로그의 이벤트
ID만 참조합니다.

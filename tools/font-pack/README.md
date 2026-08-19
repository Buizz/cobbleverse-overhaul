# Minecraft 비트맵 폰트 생성기

FontStruct에서 받은 원본 ZIP의 TTF 글리프를 원본 16×16 그리드 그대로 고정 픽셀
PNG 아틀라스로 변환하고, Minecraft 1.21.1용 `bitmap` font provider가 포함된
리소스팩을 결정적으로 생성한다. 리소스팩은 `minecraft:default`를 교체하므로
마인크래프트 메뉴와 모드 UI를 포함한 게임 전역에 같은 폰트가 적용된다.

아틀라스는 안티앨리어싱 없는 1비트 알파로 생성된다. 게임은 결과물에서 TTF를
실시간 축소하지 않으므로 한글의 1픽셀 획이 흐려지거나 불규칙하게 사라지지 않는다.
원본에 없는 문자와 기호는 `minecraft:uniform`으로 대체된다.

## 사용법

Python 3.10 이상에서 의존성을 설치한 뒤 저장소 루트에서 실행한다.

```powershell
python -m pip install -r tools/font-pack/requirements.txt
python tools/font-pack/build_minecraft_bitmap_font.py `
  local-assets/PokemonBW.zip `
  local-assets/PokemonBW-Minecraft.zip
```

기본값은 폰트의 원본 설계 격자와 같은 16픽셀 렌더링, 아틀라스 행당 80글자다.
원본 FontStruct 라이선스와 안내문은 결과 리소스팩의 `LICENSES`에 보존된다.

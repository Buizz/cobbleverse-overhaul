# Minecraft 폰트 팩 생성기

## Caxton MSDF 팩

기본 개발팩은 Caxton을 사용해 Pokemon BW 원본 TTF를 MSDF로 렌더링한다.
고정 크기 PNG로 미리 축소하지 않으므로 Minecraft GUI 배율에 맞춰 선명하게
표시된다. Caxton이 로드되지 못한 경우에는 `minecraft:uniform`으로 대체된다.

```powershell
python tools/font-pack/build_caxton_font_pack.py `
  local-assets/PokemonBW.zip `
  local-assets/PokemonBW-Caxton.zip
```

Caxton의 MSDF 월드 글자는 Iris 셰이더와 호환되지 않을 수 있으므로 표지판,
NPC 이름표 등 월드 공간 글자는 실제 클라이언트에서 별도로 확인해야 한다.

## 비트맵 fallback 생성기

FontStruct에서 받은 원본 ZIP의 TTF 글리프를 원본 16×16 그리드 그대로 고정 픽셀
PNG 아틀라스로 변환하고, Minecraft 1.21.1용 `bitmap` font provider가 포함된
리소스팩을 결정적으로 생성한다. 리소스팩은 `minecraft:default`를 교체해
개발팩의 기본 폰트로 사용된다.

원본 글리프는 선명도를 위해 16픽셀로 굽지만 provider의 기준 높이는 Minecraft
기본 UI 메트릭에 맞춘 9픽셀(8픽셀 ascent)이다. 화면별 최종 크기는 강제하지 않고
Minecraft와 각 모드가 요청하는 렌더링 배율을 그대로 따른다.

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

기본값은 폰트의 원본 설계 격자와 같은 16픽셀 렌더링, 표시 높이 9픽셀,
아틀라스 행당 80글자다. 표시 기준은 `--display-height`와 `--display-ascent`로
조정할 수 있다.
원본 FontStruct 라이선스와 안내문은 결과 리소스팩의 `LICENSES`에 보존된다.

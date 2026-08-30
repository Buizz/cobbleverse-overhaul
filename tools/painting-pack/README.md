# Cobbleventure Pokemon Paintings

Minecraft 1.21.1의 바닐라 회화 중 1x1, 1x2, 2x1 및 2x2 그림을 포켓몬 테마로 교체하는 리소스팩 빌더입니다.

## 교체 범위

- 1x1: `alban`, `aztec`, `aztec2`, `bomb`, `kebab`, `meditative`, `plant`, `wasteland`
- 1x2: `graham`, `prairie_ride`, `wanderer`
- 2x1: `courbet`(관동 지도), `creebet`(성도 지도), `pool`, `sea`, `sunset`
- 2x2: `baroque`, `bust`, `earth`, `fire`, `humble`, `match`, `skull_and_roses`, `stage`, `void`, `water`, `wind`, `wither`

## 빌드

저장소 루트에서 다음 명령을 실행합니다.

```powershell
python tools/painting-pack/build_painting_pack.py
```

결과물은 `local-assets/Cobbleventure-Pokemon-Paintings.zip`에 생성되며, 미리보기는 `tools/painting-pack/generated/preview.png`에 생성됩니다.
최초 실행 시 PokeAPI 공개 저장소에서 필요한 포켓몬 크리스탈 스프라이트를 내려받습니다. 내려받은 원본과 생성 디렉터리, ZIP 결과물은 Git에 포함되지 않습니다.
관동·성도 지도 원본은 `map-sources`에 보관하며, 빌드 시 64x32 픽셀/12색으로 단순화하여 각 2x1 회화 텍스처에 적용합니다.

# 트레이너 스킨 UV 파이프라인

AI는 본가 트레이너 이미지를 참고한 고해상도 부위별 콘셉트 시트만 생성합니다. `assemble_skin.py`가 배경 제거, 팔레트 축소, 픽셀화, Minecraft 64×64 UV 배치와 2차 레이어 분리를 결정론적으로 수행합니다.

```powershell
python tools/content-manager/skin-pipeline/assemble_skin.py tools/content-manager/skin-pipeline/work/youngster/manifest.json
```

각 트레이너 작업 폴더에는 다음 파일을 둡니다.

- `reference/`: 디자인 기준 이미지
- `concept.png`: 부위별 정면·측면·후면 AI 콘셉트 시트
- `manifest.json`: 크롭 좌표, 팔레트, 모델과 출력 경로

최종 스킨은 `projects/cobbleventure-world-bootstrap/src/main/resources/assets/cobbleventure/textures/entity/trainer/`에 저장합니다. 생성물의 머리 모자와 몸통 가방끈처럼 돌출되어야 하는 색상은 `overlay_colors`에 지정하며, 나머지는 불투명 기본 레이어로 합성합니다.

`equipment_outputs`가 있으면 파이프라인은 다음 어댑터 리소스도 함께 만듭니다.

- 모자 UV를 제거한 EasyNPC 장비용 기본 스킨
- 실제 머리 슬롯에 장착되는 방어구 레이어 텍스처
- 플레이어 인벤토리에서 보이는 16×16 아이템 아이콘

그다음 아래 명령으로 `trainer-outfits.json`을 EasyNPC 데이터 프리셋과 클라이언트 커스텀 스킨 폴더로 변환합니다.

```powershell
python tools/content-manager/generate_easy_npc_presets.py
```

어린이 체형은 프리셋의 `ModelData.Root.Scale`로 출력됩니다. 이 값은 EasyNPC에서 모델과 충돌박스를 함께 축소합니다. `build.bat generate`, `mod-bootstrap`, `pack` 명령은 두 생성기를 자동으로 실행합니다.

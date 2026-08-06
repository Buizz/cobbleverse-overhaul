# 트레이너 스킨 UV 파이프라인

AI는 본가 트레이너 이미지를 참고한 고해상도 부위별 콘셉트 시트만 생성합니다. `assemble_skin.py`가 배경 제거, 팔레트 축소, 픽셀화, Minecraft 64×64 UV 배치와 2차 레이어 분리를 결정론적으로 수행합니다.

```powershell
python tools/content-manager/skin-pipeline/assemble_skin.py tools/content-manager/skin-pipeline/work/youngster/manifest.json
```

각 트레이너 작업 폴더에는 다음 파일을 둡니다.

- `reference/`: 디자인 기준 이미지
- `concept.png`: 부위별 정면·측면·후면 AI 콘셉트 시트
- `manifest.json`: 크롭 좌표, 팔레트, 모델과 출력 경로

부위가 마젠타 배경 위에 4~6개 가로 띠로 분리된 시트는 `parts` 좌표 대신
`"auto_layout": "four_row_atlas_v1"`을 지정할 수 있습니다. 합성기가 연결된 픽셀
영역을 감지해 머리·몸·양팔·양다리의 UV 면을 자동으로 배정합니다.

UV 면 축소에는 최근접 이웃 필터만 사용합니다. 생성 이미지의 둥근 외곽과 검은
윤곽선은 제거하고, 남은 내부 가장자리 픽셀을 투명 모서리까지 확장하여 Minecraft의
사각형 머리·몸통·팔다리에 빈 모서리나 인접 면 색 번짐이 생기지 않게 합니다.
마젠타 크로마키의 안티앨리어싱 프린지도 색상 비율로 감지해 확장 전에 제거합니다.
`model`이 `slim`이면 양팔의 정면·후면을 3픽셀, 옆면을 4픽셀인 Alex UV로
배치합니다. 머리 좌우 옆면의 앞쪽 영역은 인접 텍스처 열로 정리하여 눈·눈썹·코·입이
옆면에 남지 않도록 합니다.

최종 스킨은 `projects/cobbleventure-world-bootstrap/src/main/resources/assets/cobbleventure/textures/entity/trainer/`에 저장합니다. 생성물의 머리 모자와 몸통 가방끈처럼 돌출되어야 하는 색상은 `overlay_colors`에 지정하며, 나머지는 불투명 기본 레이어로 합성합니다.

자동 생성한 1차 64×64 결과는 `retouch/generated/`에도 저장됩니다. 직접 수정할
파일은 같은 이름으로 `retouch/manual/`에 저장합니다. `manual/` 파일은 자동 생성기가
덮어쓰지 않으며, 존재할 경우 웹 미리보기와 최종 리소스 출력에서 자동 생성본보다
우선 사용됩니다. 자세한 작업 순서는 `retouch/README.md`를 참고합니다.

조직 색상만 입힌 공통 템플릿은 사용하지 않습니다. 각 인물은 본가 스프라이트를
참조해 별도의 24면 콘셉트 아틀라스를 만든 뒤 아래 명령으로 등록합니다.

```powershell
python tools/content-manager/register_ai_trainer_concept.py <slug> <concept.png> <reference.png> --root .
```

등록 도구는 원본 참조와 콘셉트를 캐릭터별 `work/<slug>/` 폴더에 보관하고,
64×64 slim UV 합성, 정면 외 얼굴 특징 제거, `retouch/generated/` 보관 및 최종 게임
리소스 출력을 한 번에 수행합니다. 같은 이름의 `retouch/manual/` 파일이 있으면 수동
리터치본이 최종 출력에서 계속 우선합니다.

`equipment_outputs`가 있으면 파이프라인은 다음 어댑터 리소스도 함께 만듭니다.

- 모자 UV를 제거한 EasyNPC 장비용 기본 스킨
- 실제 머리 슬롯에 장착되는 방어구 레이어 텍스처
- 플레이어 인벤토리에서 보이는 16×16 아이템 아이콘

그다음 아래 명령으로 `trainer-outfits.json`을 EasyNPC 데이터 프리셋과 클라이언트 커스텀 스킨 폴더로 변환합니다.

```powershell
python tools/content-manager/generate_easy_npc_presets.py
```

어린이 체형은 프리셋의 `ModelData.Root.Scale`로 출력됩니다. 이 값은 EasyNPC에서 모델과 충돌박스를 함께 축소합니다. `build.bat generate`, `mod-bootstrap`, `pack` 명령은 두 생성기를 자동으로 실행합니다.

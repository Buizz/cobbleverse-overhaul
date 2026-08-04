# Cobbleventure

`Cobbleventure(코블벤처)`는 세대별 어드벤처 월드와 PC 기반 개인 농장을 결합해 본가식 몬스터 수집 모험을 구현하는 독립 프로젝트입니다. `Cobble`과 `Adventure`를 합친 이름이며, 특정 모드팩에 종속되지 않습니다.

> 현재 상태: **기획 단계**
>
> 구현 목표 시기: **2027년 이후**
>
> 게임 연동 기준: **Cobblemon 안정 버전 정식 출시 후 별도 어댑터에서 확정**
>
> 현재 개발 기준: **Minecraft·Fabric·Cobblemon 의존성이 없는 플랫폼 독립 코어 우선**

## 문서

- [프로젝트 기획서](docs/PROJECT_PLAN.md)
- [구현 설계 문서 안내](docs/implementation/README.md)
- [선택 아키텍처와 비교 기록](docs/implementation/WORLD_ARCHITECTURE_OPTIONS.md)
- [세대 월드 8지역 및 체육관 도시 생성](docs/implementation/WORLD_GENERATION.md)
- [도시 포맷과 세대별 특수 시설](docs/implementation/CITY_FACILITIES.md)
- [Cobbleventure Core](projects/cobbleventure-core/README.md)
- [Cobbleventure Battle AI 프로젝트](projects/cobbleventure-battle-ai/README.md)
- [트레이너 JSON 예제 데이터](trainer-data/README.md)

## 현재 저장소 범위

현재는 기획 문서, 플랫폼 독립 지역 코어와 전투 AI, 트레이너 JSON 예제 데이터를 관리합니다. Minecraft·Fabric·Cobblemon 연동 모드와 특정 트레이너 모드 어댑터는 대상 안정 버전을 확정한 뒤 추가합니다.

의존성 방향은 항상 `게임 어댑터 → 플랫폼 독립 코어`로 유지합니다. 플랫폼 독립 코어는 Minecraft, Fabric, Cobblemon 또는 특정 트레이너 모드 클래스를 참조하지 않습니다.

향후 구현 단계에서는 필요에 따라 다음 영역을 추가할 예정입니다.

```text
projects/           플랫폼 독립 코어·전투 AI와 향후 게임 어댑터
content/            트레이너·진행·보상 등 데이터
trainer-data/       외부 트레이너 JSON 원본 예제
config-overrides/   기존 모드 설정 변경분
structures/         체육관 도시 구조물 원본
resources/          텍스처·번역·사운드 등 자체 리소스
docs/               기획·설계·결정 기록
```

## Git 저장소 시작하기

GitHub 등에서 저장소를 만들 때 README, `.gitignore`, 라이선스를 자동 생성하지 않은 **빈 저장소**로 만든 뒤 아래 명령을 실행합니다.

```bash
git init -b main
git add .
git commit -m "docs: add initial project plan"
git remote add origin <REMOTE_URL>
git push -u origin main
```

Git은 빈 디렉터리를 추적하지 않으므로, 구현용 디렉터리는 실제 파일이 생길 때 추가합니다.

## 저장소 원칙

- Cobbleverse를 포함한 타 모드팩이나 모드의 JAR 파일은 저장소에 넣지 않습니다.
- 마인크래프트 실행 폴더, 월드, 로그, 크래시 보고서와 빌드 결과물은 커밋하지 않습니다.
- 설정, JSON/TOML, 스크립트, 구조물 원본, 직접 제작한 리소스처럼 재현에 필요한 작업 파일을 관리합니다.
- 대용량 바이너리 작업물이 늘어나면 Git LFS 도입 여부를 별도로 결정합니다.
- 게임 어댑터 구현 착수 시점에 Cobblemon 안정 버전과 호환성 범위를 고정하고 문서화합니다.
- Cobbleverse는 필수 기반이 아니라 필요할 때 제공하는 선택 호환성 프로필로 취급합니다.

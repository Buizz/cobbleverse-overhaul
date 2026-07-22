# Cobbleverse Expansion

코블버스의 지역 탐험, 체육관 진행, 경제, 트레이너 보상 및 멀티플레이 챔피언 시스템을 확장하기 위한 프로젝트입니다.

> 현재 상태: **기획 단계**
>
> 구현 목표 시기: **2027년 이후**
>
> 기반 코블버스 및 관련 모드 버전: **개발 착수 시 확정**

## 문서

- [프로젝트 기획서](docs/PROJECT_PLAN.md)
- [구현 설계 문서 안내](docs/implementation/README.md)
- [월드 생성 및 체육관 도시 배치](docs/implementation/WORLD_GENERATION.md)

## 현재 저장소 범위

현재는 기획 문서만 관리합니다. 구현을 시작하기 전까지 모드 소스, 설정 오버레이, 구조물 및 리소스 디렉터리는 만들지 않습니다.

향후 구현 단계에서는 필요에 따라 다음 영역을 추가할 예정입니다.

```text
core/               확장 코어 모드 소스
content/            트레이너·진행·보상 등 데이터
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

- 원본 코블버스나 타 모드의 JAR 파일은 저장소에 넣지 않습니다.
- 마인크래프트 실행 폴더, 월드, 로그, 크래시 보고서와 빌드 결과물은 커밋하지 않습니다.
- 설정, JSON/TOML, 스크립트, 구조물 원본, 직접 제작한 리소스처럼 재현에 필요한 작업 파일을 관리합니다.
- 대용량 바이너리 작업물이 늘어나면 Git LFS 도입 여부를 별도로 결정합니다.
- 구현 착수 시점에 기반 팩 버전과 호환성 범위를 고정하고 문서화합니다.

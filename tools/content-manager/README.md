# Cobbleventure Content Manager

Python 표준 라이브러리만으로 실행되는 콘텐츠·의존성 검증 도구의 첫 구현이다.
CLI와 로컬 Web API는 같은 검증 코드를 사용한다.

## 실행

저장소 루트에서 다음 중 하나를 사용한다.

```bat
build.bat validate
build.bat api
```

Python 모듈을 직접 실행할 수도 있다.

```text
python tools/content-manager/content_manager.py validate --root .
python tools/content-manager/content_manager.py api --root .
```

`api`는 기본적으로 `127.0.0.1:8765`에서 실행된다.

## API

- `GET /health`: 프로세스 상태
- `GET /dependencies`: 현재 의존성 Lock
- `GET /validate`: 저장소 데이터 검증
- `GET /validate?strict_pack=true`: CurseForge 패키징 가능 상태까지 검증
- `POST /validate`: `GET /validate`와 동일

응답은 UTF-8 JSON이다. Web API는 로컬 제작 도구용이며 인증 없이 외부
인터페이스에 바인딩하지 않는다.

## 현재 검증 범위

- 의존성 Lock 필수 필드, 상태, 모드 ID와 CurseForge ID 중복
- `locked` 상태에서 Minecraft·NeoForge·활성 모드 버전 고정 여부
- 정규화 콘텐츠 ID 형식과 파일 간 중복
- NPC의 최초 대화 참조
- 대화 노드와 선택지 ID 중복
- `next_dialogue` 대상 존재 여부
- `start_rct_battle`의 트레이너 ID 일치 여부

Excel 가져오기, 대상별 출력기와 CurseForge 패키징은 다음 개발 단계에서 같은
도구에 추가한다.

# Cobblemon 1.8 호환성 검증 기록

> 검증일: 2026-08-22  
> 상태: 자체 모드 컴파일 통과 / 전체 개발 팩 전환 대기

## 검증 대상

Cobblemon 1.8은 아직 정식 배포되지 않았으므로 공식 GitLab CI가 만든 다음
NeoForge 스냅샷을 사용했다.

- 저장소: `cable-mc/cobblemon`
- 커밋: `1f9c63288349399d09c2aea4f6d79c04201cbf52`
- 파이프라인: `16120`
- 원본 파일: `Cobblemon-neoforge-1.8.0b16120+1.21.1-HEAD-1f9c632.jar`
- 내부 버전: `1.8.0+1.21.1-HEAD-1f9c632`
- SHA-256: `676A12241BC3C274A1C7F9B934D951E4B9A8E44F237A0F4C07BEF07787A26E90`
- 로컬 보관 위치: `.tmp/cobblemon-1.8-snapshot/`

스냅샷은 재현 가능한 호환성 검사 입력일 뿐 배포용 안정 버전으로 간주하지
않는다. 정식 1.8이 나오면 이 기록을 새 파일 해시와 API 차이로 갱신한다.

## 자체 모드 결과

다음 모듈은 `COBBLEVENTURE_COBBLEMON_TARGET=1.8`과 위 JAR을 사용한 Gradle
`build`가 성공했다. 생성된 `neoforge.mods.toml`의 Cobblemon 요구 범위도 모두
`[1.8.0,1.9)`로 확인했다.

| 모듈 | 결과 | 비고 |
|------|------|------|
| `cobbleventure-adventure` | 통과 | 테스트 포함, deprecated API 경고만 존재 |
| `cobbleventure-battle-ai` | 통과 | JVM 및 Kotlin/JS 공유 코어 테스트 포함 |
| `cobbleventure-player-menu` | 통과 | 1.8 호환 리플렉션 계층 포함 |
| `cobbleventure-world-bootstrap` | 통과 | 체크인된 리소스로 직접 Gradle 빌드 |
| `cobbleventure-casino` | 통과 | 테스트 소스 없음 |

`build.bat mod-bootstrap`의 선행 콘텐츠 생성은 Brock EasyNPC 외형 원본
`rctmod:trainers/single/leader_brock_019e`를 찾지 못해 중단됐다. Java/Cobblemon
호환성과는 별개이며, 전체 팩 검증 전에 콘텐츠 생성 입력을 복구해야 한다.

전체 `build.bat test`는 Content Manager에서 414개 중 실패 32개, 오류 6개로
중단됐다. 이 단계는 Cobblemon Java 컴파일 전에 실행되며 현재 작업 트리와
체크인 콘텐츠 사이의 기존 불일치가 원인이다. 1.8 호환성 판정에는 위 모듈별
Gradle 결과를 사용한다.

## 전체 팩 전환 차단 조건

정식 Cobblemon 1.8 파일이 CurseForge와 Modrinth에 아직 없으므로 기존
CurseForge 프로필의 Cobblemon 항목을 유효한 1.8 파일 ID로 교체할 수 없다.
또한 다음 직접 연동 애드온의 현재 공개 파일은 1.7.x 시기에 만들어졌거나
1.8 호환성을 명시하지 않는다.

- Cobblemon Battle Extras
- Cobblemon EXP Bar
- Cobblemon Tim Core와 Cobblemon Capture XP
- Fix Cobblemon Pokemon Experience
- MoreCobblemonTweaks
- CobbleNav
- Cobblemon Casino
- Radical Cobblemon Trainers API와 Radical Cobblemon Trainers
- Cobblemon Trainer Battle Commands
- Cobblemon: Mega Showdown
- Cobblemon Additions와 CobbleDollars 호환 계층

따라서 `pack/profiles/development-placeholder.json`과
`pack/dependencies.lock.json`은 계속 1.7.3 기준으로 유지하며, 1.8 스냅샷 상태의
`build.bat pack` 차단도 유지한다.

## 다음 합격 조건

1. Cobblemon 1.8 정식 NeoForge 파일과 배포 파일 ID를 고정한다.
2. 위 직접 연동 애드온마다 1.8 명시 버전 또는 실제 클라이언트·서버 기동 결과를
   기록한다.
3. Brock 외형 원본 누락과 Content Manager 기준선 실패를 해소한다.
4. 별도 1.8 Lock과 프로필을 만들고 기존 1.7.3 프로필과 섞이지 않게 한다.
5. 클라이언트 기동, 전용 서버 기동, 월드 생성, 포켓몬 전투, 트레이너 전투,
   플레이어 메뉴와 카지노 스모크 테스트를 통과한다.


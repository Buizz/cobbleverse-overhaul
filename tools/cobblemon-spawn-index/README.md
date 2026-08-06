# Cobblemon 스폰 인덱스 생성기

Cobblemon JAR 또는 소스의 `data/cobblemon/spawn_pool_world`를 읽어 원본 규칙을
손실 없이 종별로 조회할 수 있는 인덱스를 만든다. 이 파일은 생성 결과이므로 직접
편집하지 않는다.

```powershell
python tools/cobblemon-spawn-index/build_spawn_index.py `
  --source .tmp/cobblemon-1.7.3-source
```

기본 출력은 다음과 같다.

- `generated/cobbleventure/cobblemon-spawn-index.json`: 런타임 어댑터와 지도 UI 입력
- `outputs/cobblemon-spawn-reconciliation.json`: 서식지 카탈로그와 원본 규칙 대조 결과

실제 배포 빌드에서는 개발용 소스 폴더 대신 모드팩에 설치되는 Cobblemon JAR을
`--source`로 지정한다. 인덱스는 원본 `condition`, `anticondition`, 동적 가중치와
전체 `raw` 규칙을 보존한다.

# web-lab ↔ ai-api bridge

`web-lab`은 Cobbleverse Battle AI의 제품 코드가 아니라 시각화·디버깅·회귀 테스트용 화면이다.

이 폴더만 웹 전용 상태를 `ai-api`가 이해할 수 있는 관측 모델에 가깝게 변환한다. 반대로 `ai-api`, `ai-engine`, `data`는 `web-lab`의 React 컴포넌트, Next.js API, Showdown 실행기 구조를 알면 안 된다.

## 경계 규칙

- 전역 AI 데이터는 `projects/cobbleverse-battle-ai/data` 아래에 둔다.
- 전역 AI 데이터를 웹에서 읽어야 하면 이 bridge를 통해 읽는다.
- 웹 화면 편의를 위한 타입·라벨·요약은 bridge 밖 UI 계층에서 만든다.
- Minecraft/Cobblemon 연동 어댑터는 추후 별도 모듈로 만들고, 이 bridge를 재사용 대상으로 삼지 않는다. 이 bridge는 웹 실험실 전용이다.

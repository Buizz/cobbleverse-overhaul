# 포켓몬 배틀 AI 선행 연구 조사

> 조사일: 2026-07-24
>
> 목적: Cobbleventure Battle AI의 첫 구현 방식과 장기 학습 방향을 선행 연구·공개 구현에 근거해 결정한다.

## 1. 결론

첫 구현은 다음 순서로 진행한다.

1. 완전한 전투 상태와 공개 정보 계약을 만든다.
2. 설명 가능한 휴리스틱 승률 모델을 만든다.
3. 동시 행동과 확률 결과를 제한 시간 안에서 탐색한다.
4. 미공개 정보는 하나의 확정 상태로 만들지 않고 가능한 정보 집합으로 다룬다.
5. 장면 테스트와 자기대전 로그를 축적한다.
6. 축적된 자료로 작은 가치 모델을 학습해 휴리스틱 모델과 교체·혼합한다.
7. 대규모 로그와 운영 근거가 생긴 뒤에만 Transformer·LLM 계열을 실험한다.

현재 프로젝트에는 LLM이나 1억 단위 파라미터 모델을 런타임 필수 요소로 넣지 않는다. Minecraft 서버에서 재현 가능하고 빠르게 실행되는 작은 모델과 탐색기를 우선한다.

## 2. 주요 연구와 공개 구현

### 2.1 Foul Play와 poke-engine

- [Foul Play 기술 설명](https://pmariglia.github.io/posts/foul-play/)
- [Foul Play 저장소](https://github.com/pmariglia/foul-play)
- [poke-engine 저장소](https://github.com/pmariglia/poke-engine)

Foul Play는 별도 전투 엔진과 root-parallelized MCTS를 사용한다. 포켓몬의 동시 행동을 다루기 위해 DUCT 계열 선택을 사용하고, 미공개 능력치·기술·도구를 전투 중 공개된 정보로 좁힌다. 이전 expectiminimax 방식은 분기 폭 때문에 깊은 탐색에서 시간 제한 문제가 있었다고 설명한다.

**적용할 점**

- 실제 게임 객체와 분리된 고속 전투 엔진
- 양측 행동을 순차 선택으로 잘못 모델링하지 않는 동시 행동 탐색
- 시간 예산 기반 탐색과 병렬 루트 평가
- 전투 중 공개된 피해·속도·도구 정보로 상대 가설 갱신

**주의할 점**

- Foul Play는 GPL-3.0이므로 소스를 복사하지 않고 설계 아이디어만 참고한다.
- poke-engine은 MIT지만 싱글 배틀 중심의 불완전한 엔진임을 명시한다. Cobblemon 규칙 엔진의 대체물로 그대로 사용하지 않는다.

### 2.2 Information Set MCTS

- [Implementation and Evaluation of Information Set Monte Carlo Tree Search for Pokémon](https://eprints.lib.hokudai.ac.jp/dspace/bitstream/2115/72345/1/ihara-smc2018.pdf)
- [Hokkaido University 논문 정보](https://hdl.handle.net/2115/72345)

2018년 연구는 불완전정보를 매 탐색에서 하나의 실제 상태처럼 확정하는 일반 determinization MCTS와 ISMCTS를 비교했고, 포켓몬 실험에서 ISMCTS가 더 나은 결과를 보였다고 보고한다. 핵심 문제는 서로 다른 숨은 상태에서 얻은 전략을 한 플레이어가 동시에 알고 있는 것처럼 섞는 `strategy fusion`이다.

**적용할 점**

- 미공개 기술·도구·특성을 하나의 추측으로 고정하지 않는다.
- 공개 정보가 같은 상태들을 `InformationSet`으로 묶는 계약을 준비한다.
- 첫 버전의 단순 가설 샘플링도 나중에 ISMCTS로 교체할 수 있게 탐색기와 정보 모델을 분리한다.

### 2.3 자기대전 PPO

- [A Self-Play Policy Optimization Approach to Battling Pokémon](https://ieee-cog.org/2020/papers2019/paper_175.pdf)

2019년 연구는 Pokémon Showdown 환경에서 자기대전 기반 정책 최적화를 사용해 탐색 기반 AI 및 인간 플레이와 비교했다. 별도 완전 시뮬레이터 없이 학습하는 접근과 다른 환경으로의 전이 가능성을 다뤘다.

**적용할 점**

- 기준 AI와 현재 정책을 섞은 자기대전
- 하나의 상대·팀 구성에만 맞춘 학습을 피하기 위한 환경 다양화
- 모델 승격 시 이전 버전, 휴리스틱 AI와 고정 팀을 모두 상대하는 평가

**주의할 점**

- 특정 Pokémon Showdown 형식에서 얻은 성능을 Cobblemon 규칙으로 그대로 일반화하지 않는다.
- 온라인 자기대전 학습을 Minecraft 운영 서버 안에서 수행하지 않는다.

### 2.4 Metamon

- [Human-Level Competitive Pokémon via Scalable Offline Reinforcement Learning with Transformers](https://rlj.cs.umass.edu/2025/papers/Paper340.html)
- [논문 PDF](https://rlj.cs.umass.edu/2025/papers/RLJ_RLC_2025_340.pdf)
- [Metamon 프로젝트](https://metamon.tech/)

Metamon은 관전자 시점 리플레이를 플레이어의 부분 관측 시점으로 복원하고, 사람 대전·모방학습·오프라인 강화학습·합성 자기대전 데이터를 사용한다. 최대 200M 파라미터 Transformer를 평가했으며, 최종 에이전트는 실제 래더에서 활동 플레이어 상위 10% 수준에 도달했다고 보고한다.

논문은 학습 보상에 HP, 상태, 기절과 최종 승패를 함께 사용한다. 동시에 shaping 보상을 악용해 이미 진 상태에서 회복을 반복하며 패배만 늦추는 행동도 관찰했다고 밝힌다.

**적용할 점**

- 관전자 로그를 학습에 쓰기 전에 당시 플레이어가 실제로 알 수 있던 정보로 복원
- 사람 로그 → 모방학습 기준선 → 오프라인 RL → 합성 자기대전의 단계적 순서
- 팀·상대·세대가 다양한 평가 세트
- 최종 승패가 중간 shaping 보상보다 지배적이어야 한다는 원칙

**당장 적용하지 않을 점**

- 15M~200M Transformer는 초기 Minecraft 서버용 모델로 무겁다.
- 충분한 Cobblemon 전투 로그와 상태 복원기가 없는 상태에서 종단간 정책부터 학습하지 않는다.

### 2.5 PokéChamp

- [PokéChamp: an Expert-level Minimax Language Agent](https://proceedings.mlr.press/v267/karten25a.html)
- [논문 PDF](https://raw.githubusercontent.com/mlresearch/v267/main/assets/karten25a/karten25a.pdf)

PokéChamp는 LLM을 행동 샘플링, 상대 모델링과 가치 평가에 사용해 minimax 탐색 공간을 줄인다. 논문은 실제 플레이어 로그를 이용한 전투 퍼즐과 능력별 평가도 제공한다.

**적용할 점**

- 행동 생성, 상대 예측, 가치 평가를 독립 모듈로 분리
- 전체 승률 외에 교체, 장기 계획, 미공개 정보 처리 같은 능력별 퍼즐 평가

**당장 적용하지 않을 점**

- 외부 LLM 호출은 지연, 비용, 재현성, 서버 운영과 정보 노출 문제가 있다.
- LLM은 핵심 전투 규칙이나 최종 권위가 아니라 후속 실험용 전략 제안기로만 검토한다.

### 2.6 poke-env

- [poke-env 저장소](https://github.com/hsahovic/poke-env)
- [poke-env 문서](https://poke-env.readthedocs.io/)

poke-env는 Pokémon Showdown 봇, 자기대전과 강화학습 실험을 위한 Python 인터페이스다. 로컬 Showdown 서버에서 다수의 병렬 전투를 실행하는 기준 도구로 활용할 수 있다.

**적용할 점**

- Cobbleventure 엔진을 직접 의존시키지 않고 외부 비교·데이터 실험 도구로 사용
- Python 학습 파이프라인의 환경 API와 배치 실행 방식을 참고
- 랜덤·휴리스틱 기준 AI를 먼저 준비하는 평가 방식

## 3. Cobbleventure에 적용할 초기 알고리즘

```text
공개 BattleObservation
  → 상대 정보 가설 집합
  → 합법 행동과 동시 상대 행동 후보
  → 제한 시간 확률 상태 전이
  → 설명 가능한 V(s) 기준선
  → 행동별 Q(s, a)
  → 전략 안전 제약
  → 최종 행동
```

첫 탐색기는 완전한 ISMCTS부터 시작하지 않는다. 먼저 다음을 구현한다.

- 선형·로지스틱 형태의 설명 가능한 승률 모델
- 행동과 상대 행동의 동시 조합 계약
- 확률 결과의 기대 승률 계산
- 탐색 시간·노드 예산과 결정론적 폴백
- 정보 가설을 교체할 수 있는 인터페이스

전투 규칙과 테스트가 충분해지면 DUCT/ISMCTS 탐색기를 추가하고, 자기대전으로 학습한 작은 가치 모델을 휴리스틱 모델과 비교한다.

## 4. 평가 원칙

- 단일 래더 점수만 AI 품질로 사용하지 않는다.
- 승률에는 신뢰 구간을 함께 기록한다.
- 선발 위치와 진영을 바꾼 쌍대 평가를 한다.
- 팀 프리뷰 승률은 Brier score와 ECE로 보정한다.
- 불법 행동, 정보 누출과 시간 초과를 별도 실패로 집계한다.
- 에이스 보존, 무료 랭크업 차단, 교체 반복과 패배 지연을 전용 장면으로 검사한다.
- 새 모델은 이전 모델, 휴리스틱, 랜덤, 고정 악용 스크립트를 모두 이겨야 승격한다.

## 5. 라이선스와 데이터 주의사항

- 논문 아이디어와 알고리즘은 참고하되 공개 저장소 코드를 복사할 때는 각 라이선스를 별도 검토한다.
- Foul Play의 GPL-3.0 코드는 현재 프로젝트에 직접 이식하지 않는다.
- Pokémon Showdown 리플레이를 학습 자료로 사용할 때 서비스 정책, 개인정보, 재배포 조건과 익명화 절차를 검토한다.
- Cobblemon 실제 전투 로그는 기본 수집하지 않으며, 사용자 동의와 익명화 정책을 먼저 마련한다.
- Pokémon 명칭·데이터·에셋의 배포 범위는 코드 라이선스와 별개로 검토한다.

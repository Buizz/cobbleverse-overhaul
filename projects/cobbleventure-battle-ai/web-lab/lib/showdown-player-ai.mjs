import { BattleStreams, Dex } from "@pkmn/sim";

import {
  createAiMoveTrace,
  createAiRng,
  scoreAiMoveCandidate,
  selectAiGimmick,
  selectAiMoveCandidate,
  selectAiSwitchCandidate,
  selectAiTargetSuffix,
} from "./common-battle-ai.mjs";

class SinglesConfiguredPlayerAI extends BattleStreams.BattlePlayer {
  constructor(
    stream,
    seed,
    difficulty = "standard",
    configuredTeam = [],
    options = {},
  ) {
    super(stream);
    this.rng = createAiRng(seed, options.side ?? 0);
    this.difficulty = difficulty;
    this.configuredTeam = configuredTeam;
    this.strategy = options.strategy ?? "balanced";
    this.trace = options.trace ?? [];
    this.side = options.side ?? 0;
    this.sideName = options.sideName ?? `AI ${this.side + 1}`;
    this.decision = 0;
  }

  nextIndex(length) {
    return this.rng.nextIndex(length);
  }

  moveScore(candidate) {
    return scoreAiMoveCandidate(
      this.hydrateMoveCandidate(candidate),
      this.difficulty,
      this.strategy,
    );
  }

  hydrateMoveCandidate(candidate) {
    const move = Dex.moves.get(candidate.id);
    return {
      ...candidate,
      name: move.exists ? move.name : candidate.id,
      type: move.exists ? move.type : "Normal",
      category: move.exists ? move.category : "Status",
      power: move.exists ? move.basePower : 0,
      accuracy: move.exists ? move.accuracy : true,
      priority: move.exists ? move.priority : 0,
    };
  }

  chooseMove(available) {
    return selectAiMoveCandidate(
      available.map((candidate) => this.hydrateMoveCandidate(candidate)),
      {
        difficulty: this.difficulty,
        strategy: this.strategy,
        rng: this.rng,
      },
    );
  }

  recordCommonMoveDecision(species, available, selected, gimmick = "") {
    this.decision += 1;
    this.trace.push(
      createAiMoveTrace({
        turn: this.decision,
        side: this.side,
        sideName: this.sideName,
        species,
        difficulty: this.difficulty,
        strategy: this.strategy,
        candidates: available.map((candidate) =>
          this.hydrateMoveCandidate(candidate),
        ),
        selected,
        gimmick,
      }),
    );
  }

  recordMoveDecision(species, available, selected, gimmick = "") {
    this.decision += 1;
    const candidates = available.map((candidate) => {
      const move = Dex.moves.get(candidate.id);
      return {
        slot: candidate.slot,
        id: candidate.id,
        name: move.exists ? move.name : candidate.id,
        type: move.exists ? move.type : "Normal",
        category: move.exists ? move.category : "Status",
        power: move.exists ? move.basePower : 0,
        accuracy: move.exists ? move.accuracy : true,
        priority: move.exists ? move.priority : 0,
        score: Math.round(this.moveScore(candidate) * 100) / 100,
        selected: candidate.slot === selected.slot,
      };
    });
    const chosen = candidates.find((candidate) => candidate.selected);
    this.trace.push({
      turn: this.decision,
      side: this.side,
      sideName: this.sideName,
      species,
      kind: "move",
      difficulty: this.difficulty,
      strategy: this.strategy,
      chosenAction: chosen?.name ?? selected.id,
      gimmick: gimmick.trim(),
      reason:
        this.strategy === "aggressive"
          ? "공격 성향이 기술의 기대 위력과 우선도를 높게 평가했습니다."
          : this.strategy === "defensive"
            ? "방어 성향이 명중 안정성과 변화기의 전술 가치를 함께 평가했습니다."
            : this.strategy === "unpredictable"
              ? "예측 방지 성향이 상위 후보 안에서 시드 기반으로 행동을 분산했습니다."
              : "균형 성향이 기대 위력, 명중률, 우선도와 변화기 가치를 종합했습니다.",
      candidates,
    });
  }

  switchCandidateFromPokemon(pokemon, slot) {
    const condition = String(pokemon.condition ?? "");
    const hpText = condition.split(" ")[0];
    const [hp, maxHp] = hpText.split("/").map(Number);
    return {
      pokemon,
      slot,
      name: String(pokemon.details ?? "").split(",")[0],
      hpPercent:
        Number.isFinite(hp) && Number.isFinite(maxHp) && maxHp > 0
          ? hp / maxHp
          : 1,
    };
  }

  gimmickSuffix(active, slot, teamSlot) {
    const configured = this.configuredTeam[teamSlot - 1];
    return selectAiGimmick({
      active,
      configured,
      moveSlot: slot,
    }).showdownSuffix;
  }

  receiveRequest(request) {
    if (request.wait) return;
    if (request.teamPreview) {
      this.choose("default");
      return;
    }
    const team = Array.isArray(request.side?.pokemon)
      ? request.side.pokemon
      : [];
    const forceSwitch = Array.isArray(request.forceSwitch)
      ? request.forceSwitch.some(Boolean)
      : false;
    if (forceSwitch) {
      const switches = team
        .map((pokemon, index) => ({ pokemon, slot: index + 1 }))
        .filter(
          ({ pokemon }) =>
            !pokemon.active && !String(pokemon.condition).endsWith(" fnt"),
        )
        .map(({ pokemon, slot }) =>
          this.switchCandidateFromPokemon(pokemon, slot),
        );
      const selected = selectAiSwitchCandidate(switches, {
        difficulty: this.difficulty,
        strategy: this.strategy,
        rng: this.rng,
      });
      this.decision += 1;
      this.trace.push({
        turn: this.decision,
        side: this.side,
        sideName: this.sideName,
        species:
          String(team.find((pokemon) => pokemon.active)?.details ?? "").split(",")[0],
        kind: "switch",
        difficulty: this.difficulty,
        strategy: this.strategy,
        chosenAction: selected ? `슬롯 ${selected.slot} 교체` : "기본 교체",
        reason: "현재 포켓몬이 쓰러져 전투 가능한 벤치 포켓몬을 선택했습니다.",
        candidates: switches.map(({ name, slot }) => ({
          slot,
          name,
          selected: slot === selected?.slot,
        })),
      });
      this.choose(selected ? `switch ${selected.slot}` : "default");
      return;
    }
    const active = request.active?.[0];
    const moves = Array.isArray(active?.moves)
      ? active.moves
      : [];
    const available = moves
      .map((move, index) => ({
        id: String(move.id ?? ""),
        slot: index + 1,
        pp: Number(move.pp ?? 0),
        disabled: move.disabled === true,
      }))
      .filter((move) => !move.disabled && move.pp > 0);
    if (available.length === 0) {
      this.choose("default");
      return;
    }
    const selected = this.chooseMove(available);
    const activeSlot = team.findIndex((pokemon) => pokemon.active) + 1;
    const gimmick = this.gimmickSuffix(active, selected.slot, activeSlot);
    const species =
      String(team.find((pokemon) => pokemon.active)?.details ?? "").split(",")[0];
    this.recordCommonMoveDecision(species, available, selected, gimmick);
    this.choose(`move ${selected.slot}${gimmick}`);
  }
}

class MultiConfiguredPlayerAI extends SinglesConfiguredPlayerAI {
  targetSuffix(move, activeIndex, activeCount, team) {
    return selectAiTargetSuffix(move, activeIndex, activeCount, team);
  }

  receiveRequest(request) {
    if (request.wait) return;
    if (request.teamPreview) {
      this.choose("default");
      return;
    }

    const team = Array.isArray(request.side?.pokemon)
      ? request.side.pokemon
      : [];
    if (Array.isArray(request.forceSwitch)) {
      const chosen = [];
      const choices = request.forceSwitch.map((mustSwitch) => {
        if (!mustSwitch) return "pass";
        const available = team
          .map((pokemon, index) => ({ pokemon, slot: index + 1 }))
          .filter(
            ({ pokemon, slot }) =>
              !pokemon.active &&
              !chosen.includes(slot) &&
              !String(pokemon.condition).endsWith(" fnt"),
          )
          .map(({ pokemon, slot }) =>
            this.switchCandidateFromPokemon(pokemon, slot),
          );
        const selected = selectAiSwitchCandidate(available, {
          difficulty: this.difficulty,
          strategy: this.strategy,
          rng: this.rng,
        });
        if (!selected) return "pass";
        chosen.push(selected.slot);
        return `switch ${selected.slot}`;
      });
      this.choose(choices.join(", "));
      return;
    }

    if (!Array.isArray(request.active)) {
      this.choose("default");
      return;
    }
    let gimmickChosen = false;
    const choices = request.active.map((active, activeIndex) => {
      if (
        !active ||
        String(team[activeIndex]?.condition).endsWith(" fnt") ||
        team[activeIndex]?.commanding
      ) {
        return "pass";
      }
      const available = (Array.isArray(active.moves) ? active.moves : [])
        .map((move, index) => ({
          id: String(move.id ?? ""),
          slot: index + 1,
          pp: Number(move.pp ?? 0),
          disabled: move.disabled === true,
          target: String(move.target ?? ""),
        }))
        .filter((move) => !move.disabled && move.pp > 0);
      if (available.length === 0) return "default";
      const selected = this.chooseMove(available);
      const teamSlot =
        team.findIndex(
          (pokemon) =>
            pokemon.active &&
            String(pokemon.ident ?? "") ===
              String(team[activeIndex]?.ident ?? ""),
        ) + 1;
      const gimmick = gimmickChosen
        ? ""
        : this.gimmickSuffix(active, selected.slot, teamSlot);
      if (gimmick) gimmickChosen = true;
      const activePokemon = team.filter((pokemon) => pokemon.active)[activeIndex];
      const species = String(activePokemon?.details ?? "").split(",")[0];
      this.recordCommonMoveDecision(species, available, selected, gimmick);
      return `move ${selected.slot}${gimmick}${this.targetSuffix(
        selected,
        activeIndex,
        request.active.length,
        team,
      )}`;
    });
    this.choose(choices.join(", "));
  }
}

export function createShowdownPlayerAI(
  stream,
  {
    seed,
    difficulty = "standard",
    battleType = "single",
    team = [],
    strategy = "balanced",
    trace = [],
    side = 0,
    sideName = "",
  },
) {
  const options = { strategy, trace, side, sideName };
  if (battleType === "single") {
    return new SinglesConfiguredPlayerAI(
      stream,
      seed,
      difficulty,
      team,
      options,
    );
  }
  return new MultiConfiguredPlayerAI(stream, seed, difficulty, team, options);
}

export function showdownControllerId(battleType, difficulty) {
  return battleType === "single"
    ? `${difficulty}-single-baseline`
    : `${difficulty}-${battleType}-multi-baseline`;
}

import { BattleStreams, Dex } from "@pkmn/sim";

class SinglesConfiguredPlayerAI extends BattleStreams.BattlePlayer {
  constructor(
    stream,
    seed,
    difficulty = "standard",
    configuredTeam = [],
    options = {},
  ) {
    super(stream);
    this.state = (Number(seed) ^ 0x9e3779b9) >>> 0;
    this.difficulty = difficulty;
    this.configuredTeam = configuredTeam;
    this.strategy = options.strategy ?? "balanced";
    this.trace = options.trace ?? [];
    this.side = options.side ?? 0;
    this.sideName = options.sideName ?? `AI ${this.side + 1}`;
    this.decision = 0;
  }

  nextIndex(length) {
    this.state ^= this.state << 13;
    this.state ^= this.state >>> 17;
    this.state ^= this.state << 5;
    this.state >>>= 0;
    return length > 0 ? this.state % length : 0;
  }

  moveScore(candidate) {
    const move = Dex.moves.get(candidate.id);
    if (!move.exists) return 0;
    const accuracy = move.accuracy === true ? 1 : move.accuracy / 100;
    const priorityWeight =
      this.difficulty === "expert" || this.difficulty === "cheater" ? 12 : 5;
    const statusValue =
      move.category === "Status"
        ? this.strategy === "defensive"
          ? 38
          : this.strategy === "balanced"
            ? 12
            : 4
        : 0;
    const powerWeight =
      this.strategy === "aggressive"
        ? 1.2
        : this.strategy === "defensive"
          ? 0.82
          : 1;
    const accuracyWeight = this.strategy === "defensive" ? accuracy * accuracy : accuracy;
    return (
      move.basePower * powerWeight * accuracyWeight +
      move.priority * priorityWeight +
      statusValue
    );
  }

  chooseMove(available) {
    if (this.difficulty === "novice") {
      return available[this.nextIndex(available.length)];
    }
    const ranked = [...available].sort(
      (left, right) =>
        this.moveScore(right) - this.moveScore(left) || left.slot - right.slot,
    );
    if (this.strategy === "unpredictable" && ranked.length > 1) {
      return ranked[this.nextIndex(Math.min(3, ranked.length))];
    }
    if (this.difficulty === "standard" && ranked.length > 1) {
      return ranked[this.nextIndex(4) === 0 ? 1 : 0];
    }
    return ranked[0];
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

  gimmickSuffix(active, slot, teamSlot) {
    const configured = this.configuredTeam[teamSlot - 1];
    if (active.canMegaEvo) return " mega";
    if (active.canMegaEvoX) return " megax";
    if (active.canMegaEvoY) return " megay";
    if (active.canZMove?.[slot - 1]) return " zmove";
    if (active.canDynamax && configured?.gimmicks?.dynamax) {
      return " dynamax";
    }
    if (active.canTerastallize && configured?.gimmicks?.tera) {
      return " terastallize";
    }
    return "";
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
        );
      const selected = switches[this.nextIndex(switches.length)];
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
        candidates: switches.map(({ pokemon, slot }) => ({
          slot,
          name: String(pokemon.details ?? "").split(",")[0],
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
    this.recordMoveDecision(species, available, selected, gimmick);
    this.choose(`move ${selected.slot}${gimmick}`);
  }
}

class MultiConfiguredPlayerAI extends SinglesConfiguredPlayerAI {
  targetSuffix(move, activeIndex, activeCount, team) {
    if (activeCount < 2) return "";
    if (["normal", "any", "adjacentFoe"].includes(move.target)) {
      return ` ${activeCount === 3 ? 2 : 1}`;
    }
    if (move.target === "adjacentAlly") {
      const allyIndex = team.findIndex(
        (pokemon, index) =>
          index !== activeIndex &&
          pokemon.active &&
          !String(pokemon.condition).endsWith(" fnt"),
      );
      return allyIndex >= 0 ? ` -${allyIndex + 1}` : "";
    }
    if (move.target === "adjacentAllyOrSelf") {
      return ` -${activeIndex + 1}`;
    }
    return "";
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
          );
        const selected = available[this.nextIndex(available.length)];
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
      this.recordMoveDecision(species, available, selected, gimmick);
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

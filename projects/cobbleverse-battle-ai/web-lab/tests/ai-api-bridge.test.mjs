import assert from "node:assert/strict";
import test from "node:test";

import {
  getMoveRoleEntry,
  getMoveRoleScore,
  loadMoveRoleCatalog,
} from "../lib/ai-api-bridge/move-role-catalog.mjs";
import {
  showdownRequestToAiBattleObservation,
  simpleStateToAiBattleObservation,
  toAiBattleObservation,
  toAiApiObservationDraft,
} from "../lib/ai-api-bridge/observation-adapter.mjs";
import {
  analyzeTeamProfile,
  createAiMoveTrace,
  createAiSwitchTrace,
  scoreAiDynamaxCandidate,
  scoreAiSwitchCandidate,
  moveRoleValue,
  scoreAiMoveCandidate,
  selectAiMoveCandidate,
  teamRoleLabel,
} from "../lib/common-battle-ai.mjs";

test("loads shared AI move role catalog through the web bridge", async () => {
  const catalog = await loadMoveRoleCatalog();
  assert.ok(getMoveRoleEntry(catalog, "Stealth Rock").tags.includes("HAZARD_SET"));
  assert.ok(getMoveRoleScore(catalog, "Swords Dance", "setupSweeper") >= 4);
});

test("analyzes team member roles from moves and stats", () => {
  const report = analyzeTeamProfile([
    {
      slot: 1,
      species: "Garchomp",
      stats: { attack: 130, speed: 102, hp: 183, defense: 115, specialDefense: 105 },
      moves: ["Swords Dance", "Earthquake", "Stealth Rock", "Dragon Claw"],
    },
    {
      slot: 2,
      species: "Corviknight",
      stats: { hp: 205, defense: 150, specialDefense: 105 },
      moves: ["Roost", "U-turn", "Defog", "Body Press"],
    },
    {
      slot: 3,
      species: "Scizor",
      stats: { attack: 150, speed: 85 },
      moves: ["Bullet Punch", "U-turn", "Swords Dance"],
    },
  ]);

  const chomp = report.roles.find((entry) => entry.species === "Garchomp");
  const corviknight = report.roles.find((entry) => entry.species === "Corviknight");
  const scizor = report.roles.find((entry) => entry.species === "Scizor");
  assert.ok(chomp.roles.some((role) => role.role === "setupSweeper"));
  assert.ok(report.hazardPlan.setters.some((entry) => entry.species === "Garchomp"));
  assert.ok(corviknight.roles.some((role) => role.role === "wall"));
  assert.ok(corviknight.roles.some((role) => role.role === "pivot"));
  assert.ok(scizor.roles.some((role) => role.role === "revengeKiller"));
  assert.equal(teamRoleLabel("ace"), "에이스");
});

test("uses species role priors as soft bonuses in team analysis", () => {
  const report = analyzeTeamProfile([
    {
      slot: 1,
      species: "Porygon2",
      moves: ["Tackle"],
    },
    {
      slot: 2,
      species: "Aerodactyl",
      moves: ["Wing Attack"],
    },
  ]);

  const porygon2 = report.roles.find((entry) => entry.species === "Porygon2");
  const aerodactyl = report.roles.find((entry) => entry.species === "Aerodactyl");
  assert.ok(porygon2.roles.some((role) => role.role === "wall"));
  assert.ok(aerodactyl.roles.some((role) => role.role === "lead"));
  assert.ok(
    porygon2.reasons.some((reason) => reason.startsWith("포켓몬 기본 역할:")),
  );
});

test("separates true ace candidates from broad offensive role scores", () => {
  const report = analyzeTeamProfile([
    {
      slot: 1,
      species: "Porygon2",
      level: 100,
      item: "Eviolite",
      moves: ["Ice Beam", "Thunderbolt", "Trick Room", "Recover"],
    },
    {
      slot: 2,
      species: "Garganacl",
      level: 100,
      moves: ["Stealth Rock", "Salt Cure", "Earthquake", "Explosion"],
    },
    {
      slot: 3,
      species: "Blaziken",
      level: 100,
      moves: ["Swords Dance", "Close Combat", "Flare Blitz", "Protect"],
    },
    {
      slot: 4,
      species: "Mawile",
      level: 100,
      item: "Mawilite",
      moves: ["Swords Dance", "Play Rough", "Sucker Punch", "Iron Head"],
    },
  ]);

  const porygon2 = report.roles.find((entry) => entry.species === "Porygon2");
  const garganacl = report.roles.find((entry) => entry.species === "Garganacl");
  assert.equal(porygon2.aceProfile.qualifies, false);
  assert.equal(garganacl.aceProfile.qualifies, false);
  assert.equal(porygon2.roles.some((role) => role.role === "ace"), false);
  assert.equal(garganacl.roles.some((role) => role.role === "ace"), false);
  assert.deepEqual(
    report.aceCandidates.map((entry) => entry.species),
    ["Mawile", "Blaziken"],
  );
});

test("honors manually selected ace roles before inferred ace scoring", () => {
  const report = analyzeTeamProfile([
    {
      slot: 1,
      species: "Porygon2",
      level: 100,
      aiRole: "ace",
      moves: ["Ice Beam", "Recover"],
    },
    {
      slot: 2,
      species: "Blaziken",
      level: 100,
      moves: ["Swords Dance", "Close Combat"],
    },
  ]);

  assert.equal(report.aceCandidates[0].species, "Porygon2");
  assert.equal(report.aceCandidates[0].aceProfile.manual.forced, true);
  assert.ok(report.aceCandidates[0].roles.some((role) => role.role === "ace"));
});

test("analyzes team roles from scenario moveset fields", () => {
  const report = analyzeTeamProfile([
    {
      slot: 1,
      species: "Garganacl",
      moveset: ["stealthrock", "saltcure", "earthquake", "explosion"],
    },
    {
      slot: 2,
      species: "Zekrom",
      moveset: ["dragondance", "boltstrike", "outrage"],
    },
    {
      slot: 3,
      species: "Calyrex-Shadow",
      moveset: ["nastyplot", "astralbarrage"],
    },
  ]);

  const garganacl = report.roles.find((entry) => entry.species === "Garganacl");
  const zekrom = report.roles.find((entry) => entry.species === "Zekrom");
  const calyrex = report.roles.find((entry) => entry.species === "Calyrex-Shadow");
  assert.ok(garganacl.roles.some((role) => role.role === "hazardControl"));
  assert.ok(zekrom.roles.some((role) => role.role === "ace"));
  assert.ok(calyrex.roles.some((role) => role.role === "revengeKiller"));
  assert.equal(zekrom.warnings.includes("기술 정보 없음"), false);
});

test("converts web battle state into an ai-api-like observation draft", () => {
  const observation = toAiApiObservationDraft({
    battleId: "demo",
    seed: "seed-1",
    format: "singles",
    engine: "cobbleverse",
    side: "player",
    turn: 3,
    activePokemon: {
      species: "Porygon2",
      hp: 120,
      maxHp: 192,
      types: ["Normal"],
      boosts: { spa: 1 },
      item: "eviolite",
      ability: "download",
    },
    opponentPokemon: {
      species: "Mawile",
      hp: 88,
      maxHp: 140,
      types: ["Steel", "Fairy"],
    },
    candidates: [{ type: "move", moveId: "triattack", name: "트라이어택" }],
  });

  assert.equal(observation.source, "web-lab");
  assert.equal(observation.activePokemon.item, "eviolite");
  assert.equal(observation.candidates[0].id, "triattack");
  assert.equal(observation.battleType.mode, "single");
  assert.equal(observation.legalActions[0].id, "triattack");
});

test("builds a common AI battle observation with public legal actions", () => {
  const observation = toAiBattleObservation({
    battleId: "eve-1",
    format: "doubles",
    engine: "showdown",
    side: "p2",
    turn: 7,
    activePokemon: [{ species: "Raichu", hp: 80, maxHp: 120 }],
    benchPokemon: [{ species: "Snorlax", hp: 210, maxHp: 260 }],
    opponentActivePokemon: [{ species: "Gyarados", hp: 130, maxHp: 170 }],
    opponentBenchKnownInfo: [{ species: "Mawile" }],
    legalActions: [
      { type: "move", moveId: "thunderbolt", name: "Thunderbolt", slot: 1 },
      { type: "switch", switchId: "snorlax", name: "Snorlax", slot: 2 },
    ],
    revealedInfo: {
      opponentSpecies: ["Gyarados", "Mawile"],
      opponentMoves: { gyarados: ["dragondance"] },
    },
  });

  assert.equal(observation.battleType.mode, "double");
  assert.equal(observation.battleType.activeSlotsPerSide, 2);
  assert.equal(observation.activePokemon[0].name, "Raichu");
  assert.equal(observation.legalActions[0].type, "move");
  assert.equal(observation.legalActions[1].type, "switch");
  assert.deepEqual(observation.revealedInfo.opponentSpecies, ["Gyarados", "Mawile"]);
});

test("records normalized AI action candidates with decision reasons", () => {
  const trace = createAiMoveTrace({
    turn: 1,
    side: 0,
    sideName: "AI",
    species: "Raichu",
    difficulty: "expert",
    strategy: "aggressive",
    selected: { slot: 1 },
    candidates: [
      {
        slot: 1,
        id: "thunderbolt",
        name: "Thunderbolt",
        category: "Special",
        power: 90,
        accuracy: 100,
        priority: 0,
        expectedDamage: 96,
        koChance: "possible",
      },
      {
        slot: 2,
        id: "nuzzle",
        name: "Nuzzle",
        category: "Physical",
        power: 20,
        accuracy: 100,
        priority: 0,
      },
    ],
  });

  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.equal(selected.type, "move");
  assert.equal(selected.legal, true);
  assert.equal(selected.expectedDamage.value, 96);
  assert.ok(selected.reasons.some((reason) => reason.code === "damage.expected"));
  assert.ok(selected.reasons.some((reason) => reason.code === "ko.possible"));
});

test("weights move role classification according to AI strategy", () => {
  const swordsDance = {
    slot: 1,
    id: "swordsdance",
    name: "Swords Dance",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 20,
  };
  const tackle = {
    slot: 2,
    id: "tackle",
    name: "Tackle",
    category: "Physical",
    power: 20,
    accuracy: 100,
    pp: 35,
  };
  const stealthRock = {
    slot: 3,
    id: "stealthrock",
    name: "Stealth Rock",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 20,
  };

  assert.ok(moveRoleValue(swordsDance, "setup") > moveRoleValue(tackle, "setup"));
  assert.ok(moveRoleValue(stealthRock, "hazard") > moveRoleValue(swordsDance, "hazard"));
  assert.ok(
    scoreAiMoveCandidate(swordsDance, "expert", "setup") >
      scoreAiMoveCandidate(tackle, "expert", "setup"),
  );
  assert.ok(
    scoreAiMoveCandidate(
      {
        ...swordsDance,
        opponentHp: 404,
        incomingDamageRatio: 0.23,
        setupCurrentBestDamage: 210,
        setupBoostedBestDamage: 430,
        setupDamageImprovement: 220,
        setupKoBeforeBoost: false,
        setupKoAfterBoost: true,
      },
      "expert",
      "tempo",
    ) >
      scoreAiMoveCandidate(
        {
          slot: 3,
          id: "closecombat",
          name: "Close Combat",
          category: "Physical",
          power: 120,
          accuracy: 100,
          expectedDamage: 283,
          opponentHp: 404,
          tacticalValue: -30,
        },
        "expert",
        "tempo",
      ),
  );
  const safeFinisher = {
    slot: 2,
    id: "surgingstrikes",
    name: "Surging Strikes",
    category: "Physical",
    power: 25,
    accuracy: 100,
    expectedDamage: 549,
    opponentHp: 404,
    koChance: "guaranteed",
  };
  const selfDropFinisher = {
    slot: 3,
    id: "closecombat",
    name: "Close Combat",
    category: "Physical",
    power: 120,
    accuracy: 100,
    expectedDamage: 563,
    opponentHp: 404,
    koChance: "guaranteed",
    tacticalValue: -30,
    selfBoosts: { def: -1, spd: -1 },
    selfDropTotal: 2,
    safeNoDropKoAvailable: true,
  };
  assert.ok(
    scoreAiMoveCandidate(safeFinisher, "expert", "tempo") >
      scoreAiMoveCandidate(selfDropFinisher, "expert", "tempo"),
  );
  assert.equal(
    selectAiMoveCandidate([tackle, swordsDance], {
      difficulty: "expert",
      strategy: "setup",
    }).id,
    "swordsdance",
  );

  const trace = createAiMoveTrace({
    turn: 1,
    side: 0,
    sideName: "AI",
    species: "Scizor",
    difficulty: "expert",
    strategy: "setup",
    selected: { slot: 1 },
    candidates: [swordsDance, tackle],
  });
  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.ok(selected.roleValue > 0);
  assert.ok(selected.reasons.some((reason) => reason.code === "role.strategy_fit"));
});

test("applies RunAndBun-inspired setup and hazard scoring rules", () => {
  const haze = {
    slot: 1,
    id: "haze",
    name: "Haze",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 30,
    setupThreatTier: "tier_3",
  };
  const recover = {
    slot: 2,
    id: "recover",
    name: "Recover",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 10,
    setupThreatTier: "tier_3",
  };
  const stealthRock = {
    slot: 3,
    id: "stealthrock",
    name: "Stealth Rock",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 20,
    actsBeforeOpponent: true,
    livingOpponents: 5,
  };

  assert.ok(
    scoreAiMoveCandidate(haze, "expert", "ace_check") >
      scoreAiMoveCandidate(recover, "expert", "ace_check"),
  );
  assert.ok(
    scoreAiMoveCandidate(stealthRock, "expert", "hazard") >
      scoreAiMoveCandidate(recover, "expert", "hazard"),
  );
  assert.ok(
    scoreAiMoveCandidate(
      { ...stealthRock, opponentHazards: { stealthrock: 1 } },
      "expert",
      "hazard",
    ) < scoreAiMoveCandidate(recover, "expert", "hazard"),
  );

  const selected = selectAiMoveCandidate([recover, haze], {
    difficulty: "expert",
    strategy: "ace_check",
  });
  assert.equal(selected.id, "haze");

  const trace = createAiMoveTrace({
    turn: 4,
    side: 1,
    sideName: "AI",
    species: "Toxapex",
    difficulty: "expert",
    strategy: "ace_check",
    selected: { slot: 1 },
    candidates: [haze, recover],
  });
  const chosen = trace.candidates.find((candidate) => candidate.selected);
  assert.ok(
    chosen.reasons.some((reason) => reason.code === "rule.setup_disruption.boost_reset"),
  );
});

test("values early Stealth Rock above first Salt Cure pressure", () => {
  const stealthRock = {
    slot: 1,
    id: "stealthrock",
    name: "Stealth Rock",
    category: "Status",
    power: 0,
    accuracy: true,
    pp: 20,
    actsBeforeOpponent: true,
    livingOpponents: 6,
    turn: 1,
  };
  const saltCure = {
    slot: 2,
    id: "saltcure",
    name: "Salt Cure",
    category: "Physical",
    power: 40,
    accuracy: 100,
    expectedDamage: 52.5,
    opponentHp: 374,
    opponentVolatiles: {},
    opponentHazards: {},
    livingOpponents: 6,
    turn: 1,
    saltCureResidualDamage: 46,
    expectedSurvivalTurns: 2,
  };
  const curedSaltCure = {
    ...saltCure,
    opponentVolatiles: { saltcure: { id: "saltcure" } },
  };
  const porygonSaltCure = {
    ...saltCure,
    expectedDamage: 52.5,
    opponentHp: 374,
    opponentMaxHp: 374,
    opponentPrimaryRole: "ace",
    opponentAceScore: 1.4,
    opponentAceQualified: false,
  };
  const earthquake = {
    slot: 3,
    id: "earthquake",
    name: "Earthquake",
    category: "Physical",
    power: 100,
    accuracy: 100,
    expectedDamage: 86,
    opponentHp: 374,
  };
  const saltCureAfterRocks = {
    ...saltCure,
    opponentHazards: { stealthrock: 1 },
    turn: 2,
  };
  const lateStealthRock = {
    ...stealthRock,
    turn: 3,
    livingOpponents: 5,
  };
  const lateStealthRockIntoFinisher = {
    ...stealthRock,
    turn: 2,
    livingOpponents: 5,
    immediateKoAvailable: true,
    safeImmediateKoAvailable: true,
    opponentHazards: {},
    hpPercent: 0.49,
    incomingDamageRatio: 0.2,
  };
  const lateEarthquake = {
    ...earthquake,
    turn: 3,
    expectedDamage: 86,
    opponentHp: 374,
  };
  const desperateSaltCure = {
    ...saltCureAfterRocks,
    expectedSurvivalTurns: 1,
    incomingDamageRatio: 0.8,
    opponentPrimaryRole: "ace",
  };
  const boostedThreatSaltCure = {
    ...saltCure,
    turn: 1,
    expectedDamage: 24,
    opponentHp: 343,
    opponentMaxHp: 343,
    saltCureResidualDamage: 43,
    incomingDamageRatio: 0.23,
    opponentBoosts: { attack: 2 },
    opponentPositiveBoosts: 2,
  };
  const likelySetupSaltCure = {
    ...saltCure,
    turn: 1,
    expectedDamage: 24,
    opponentHp: 343,
    opponentMaxHp: 343,
    saltCureResidualDamage: 43,
    incomingDamageRatio: 0.23,
    opponentSetupMoveCount: 1,
    opponentSetupMoveIds: ["swordsdance"],
    opponentSetupFirstTurnLikelihood: 0.82,
    opponentLikelyFirstTurnSetup: true,
    opponentSetupThreatTier: 3,
  };
  const boostedThreatEarthquake = {
    ...earthquake,
    turn: 1,
    expectedDamage: 79.5,
    opponentHp: 343,
    opponentMaxHp: 343,
    incomingDamageRatio: 0.23,
    opponentBoosts: { attack: 2 },
    opponentPositiveBoosts: 2,
  };

  assert.ok(
    scoreAiMoveCandidate(saltCure, "expert", "balanced") >
      scoreAiMoveCandidate({ ...saltCure, id: "rockthrow" }, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(stealthRock, "expert", "balanced") >
      scoreAiMoveCandidate(saltCure, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(stealthRock, "expert", "aggressive") >
      scoreAiMoveCandidate(porygonSaltCure, "expert", "aggressive"),
  );
  assert.ok(
    scoreAiMoveCandidate(saltCureAfterRocks, "expert", "balanced") >
      scoreAiMoveCandidate(earthquake, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(lateStealthRock, "expert", "balanced") >
      scoreAiMoveCandidate(lateEarthquake, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(lateStealthRockIntoFinisher, "expert", "balanced") > 60,
  );
  assert.ok(
    scoreAiMoveCandidate(desperateSaltCure, "expert", "balanced") >
      scoreAiMoveCandidate(earthquake, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(boostedThreatSaltCure, "expert", "aggressive") >
      scoreAiMoveCandidate(boostedThreatEarthquake, "expert", "aggressive"),
  );
  assert.ok(
    scoreAiMoveCandidate(likelySetupSaltCure, "expert", "balanced") >
      scoreAiMoveCandidate(stealthRock, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(curedSaltCure, "expert", "balanced") <
      scoreAiMoveCandidate(saltCure, "expert", "balanced"),
  );

  const trace = createAiMoveTrace({
    turn: 1,
    side: 0,
    sideName: "AI",
    species: "Garganacl",
    difficulty: "expert",
    strategy: "balanced",
    selected: stealthRock,
    candidates: [stealthRock, saltCure],
  });
  const selected = trace.candidates.find((candidate) => candidate.selected);
  const salt = trace.candidates.find((candidate) => candidate.id === "saltcure");
  assert.ok(
    selected.reasons.some(
      (reason) => reason.code === "rule.entry_hazard.stealth_rock_pressure",
    ),
  );
  assert.ok(
    salt.reasons.some(
      (reason) => reason.code === "rule.salt_cure.persistent_pressure",
    ),
  );
});

test("scores poison and burn residual damage by survival turns and status chance", () => {
  const toxic = {
    slot: 1,
    id: "toxic",
    name: "Toxic",
    category: "Status",
    power: 0,
    accuracy: 90,
    status: "tox",
    opponentHp: 300,
    opponentMaxHp: 300,
    expectedSurvivalTurns: 4,
  };
  const shortLivedToxic = {
    ...toxic,
    expectedSurvivalTurns: 1,
  };
  const poisonedTarget = {
    ...toxic,
    opponentStatus: "psn",
  };
  const scald = {
    slot: 2,
    id: "scald",
    name: "Scald",
    category: "Special",
    power: 80,
    accuracy: 100,
    expectedDamage: 62,
    opponentHp: 300,
    opponentMaxHp: 300,
    expectedSurvivalTurns: 3,
    secondaries: [{ chance: 30, status: "brn" }],
  };
  const surf = {
    slot: 3,
    id: "surf",
    name: "Surf",
    category: "Special",
    power: 90,
    accuracy: 100,
    expectedDamage: 70,
    opponentHp: 300,
    opponentMaxHp: 300,
    expectedSurvivalTurns: 3,
  };

  assert.ok(
    scoreAiMoveCandidate(toxic, "expert", "balanced") >
      scoreAiMoveCandidate(shortLivedToxic, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(poisonedTarget, "expert", "balanced") <
      scoreAiMoveCandidate(toxic, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(scald, "expert", "balanced") >
      scoreAiMoveCandidate(surf, "expert", "balanced") - 8,
  );

  const trace = createAiMoveTrace({
    turn: 3,
    side: 0,
    sideName: "AI",
    species: "Toxapex",
    difficulty: "expert",
    strategy: "balanced",
    selected: toxic,
    candidates: [toxic, scald],
  });
  assert.ok(
    trace.candidates[0].reasons.some(
      (reason) => reason.code === "rule.status_residual.expected_value",
    ),
  );
  assert.ok(
    trace.candidates[1].reasons.some(
      (reason) => reason.code === "rule.status_residual.expected_value",
    ),
  );
});

test("scores Trick Room for surviving setters with slow ace support", () => {
  const trickRoom = {
    slot: 1,
    id: "trickroom",
    name: "Trick Room",
    category: "Status",
    power: 0,
    accuracy: true,
    priority: -7,
    hpPercent: 0.35,
    incomingDamageRatio: 0.55,
    trickRoomAdvantage: 4,
    slowAceCount: 2,
    canSurviveToSetRoom: true,
  };
  const iceBeam = {
    slot: 2,
    id: "icebeam",
    name: "Ice Beam",
    category: "Special",
    power: 90,
    accuracy: 100,
    expectedDamage: 42,
  };

  assert.ok(
    scoreAiMoveCandidate(trickRoom, "expert", "balanced") >
      scoreAiMoveCandidate(iceBeam, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(
      { ...trickRoom, trickRoomActive: true },
      "expert",
      "balanced",
    ) < scoreAiMoveCandidate(iceBeam, "expert", "balanced"),
  );

  const trace = createAiMoveTrace({
    turn: 2,
    side: 1,
    sideName: "AI",
    species: "Porygon2",
    difficulty: "expert",
    strategy: "balanced",
    selected: trickRoom,
    candidates: [trickRoom, iceBeam],
  });
  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.ok(
    selected.reasons.some((reason) => reason.code === "rule.trick_room.slow_ace_plan"),
  );
});

test("does not turn Trick Room slow ace plan into a negative bonus", () => {
  const trickRoom = {
    slot: 1,
    id: "trickroom",
    name: "Trick Room",
    category: "Status",
    power: 0,
    accuracy: true,
    priority: -7,
    hpPercent: 0.35,
    incomingDamageRatio: 0.55,
    trickRoomAdvantage: -20,
    slowAceCount: 2,
    canSurviveToSetRoom: true,
  };

  const trace = createAiMoveTrace({
    turn: 2,
    side: 1,
    sideName: "AI",
    species: "Porygon2",
    difficulty: "expert",
    strategy: "balanced",
    selected: trickRoom,
    candidates: [trickRoom],
  });
  const reason = trace.candidates[0].reasons.find(
    (entry) => entry.code === "rule.trick_room.slow_ace_plan",
  );

  assert.ok(reason);
  assert.ok(reason.weight > 0);
});

test("applies RunAndBun-inspired switch matchup scoring rules", () => {
  const boostedCurrentMonSwitch = {
    slot: 2,
    name: "Slow wall",
    hpPercent: 0.8,
    expectedDamage: 10,
    currentIncomingDamageRatio: 0.3,
    targetIncomingDamageRatio: 0.25,
    currentOutgoingDamageRatio: 0.55,
    targetOutgoingDamageRatio: 0.3,
    currentPositiveBoosts: 2,
  };
  const safeCounter = {
    slot: 3,
    name: "Priority check",
    hpPercent: 0.8,
    expectedDamage: 10,
    currentIncomingDamageRatio: 0.9,
    targetIncomingDamageRatio: 0.4,
    currentOutgoingDamageRatio: 0.2,
    targetOutgoingDamageRatio: 0.95,
    speedAdvantage: true,
    currentStatus: "par",
  };

  assert.ok(
    scoreAiSwitchCandidate(safeCounter) >
      scoreAiSwitchCandidate(boostedCurrentMonSwitch),
  );

  const trace = createAiSwitchTrace({
    turn: 6,
    side: 1,
    sideName: "AI",
    species: "Raichu",
    selected: { slot: 3 },
    candidates: [boostedCurrentMonSwitch, safeCounter],
  });
  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.ok(
    selected.reasons.some((reason) => reason.code === "rule.switch.defensive_improvement"),
  );
  assert.ok(
    selected.reasons.some((reason) => reason.code === "rule.switch.safe_counter_ko"),
  );
});

test("applies RunAndBun-inspired recovery, pivot, and immediate KO rules", () => {
  const finishingMove = {
    slot: 1,
    id: "quickattack",
    name: "Quick Attack",
    category: "Physical",
    power: 40,
    accuracy: 100,
    priority: 1,
    expectedDamage: 40,
    koChance: "guaranteed",
    safeImmediateKoAvailable: true,
  };
  const recover = {
    slot: 2,
    id: "recover",
    name: "Recover",
    category: "Status",
    power: 0,
    accuracy: true,
    hpPercent: 0.8,
    safeImmediateKoAvailable: true,
  };
  const partingShot = {
    slot: 3,
    id: "partingshot",
    name: "Parting Shot",
    category: "Status",
    power: 0,
    accuracy: 100,
    actsBeforeOpponent: true,
    hasLivingBench: true,
  };

  assert.ok(
    scoreAiMoveCandidate(finishingMove, "expert", "aggressive") >
      scoreAiMoveCandidate(recover, "expert", "defensive"),
  );
  assert.ok(scoreAiMoveCandidate(partingShot, "expert", "tempo") > 0);

  const trace = createAiMoveTrace({
    turn: 5,
    side: 1,
    sideName: "AI",
    species: "Incineroar",
    difficulty: "expert",
    strategy: "tempo",
    selected: { slot: 3 },
    candidates: [finishingMove, recover, partingShot],
  });
  const pivot = trace.candidates.find((candidate) => candidate.slot === 3);
  const recovery = trace.candidates.find((candidate) => candidate.slot === 2);
  assert.ok(pivot.reasons.some((reason) => reason.code === "rule.pivot.safe_pivot"));
  assert.ok(
    recovery.reasons.some((reason) => reason.code === "rule.immediate_ko_dominance"),
  );
});

test("penalizes self-sacrifice moves unless damage and expendable role justify them", () => {
  const explosionFromAce = {
    slot: 1,
    id: "explosion",
    name: "Explosion",
    category: "Physical",
    power: 250,
    accuracy: 100,
    expectedDamage: 180,
    opponentHp: 300,
    activeRoleScore: 11,
    koChance: "none",
  };
  const bodySlam = {
    slot: 2,
    id: "bodyslam",
    name: "Body Slam",
    category: "Physical",
    power: 85,
    accuracy: 100,
    expectedDamage: 85,
    opponentHp: 300,
    activeRoleScore: 11,
    koChance: "none",
  };
  const finishingExplosion = {
    ...explosionFromAce,
    expectedDamage: 120,
    opponentHp: 100,
    activeRoleScore: 0,
    koChance: "guaranteed",
  };

  assert.ok(
    scoreAiMoveCandidate(bodySlam, "expert", "balanced") >
      scoreAiMoveCandidate(explosionFromAce, "expert", "balanced"),
  );
  assert.ok(
    scoreAiMoveCandidate(finishingExplosion, "expert", "balanced") >
      scoreAiMoveCandidate(bodySlam, "expert", "balanced"),
  );

  const trace = createAiMoveTrace({
    turn: 3,
    side: 0,
    sideName: "AI",
    species: "Electrode",
    difficulty: "expert",
    strategy: "balanced",
    selected: bodySlam,
    candidates: [explosionFromAce, bodySlam],
  });
  const explosion = trace.candidates.find((candidate) => candidate.id === "explosion");
  assert.ok(
    explosion.reasons.some(
      (reason) => reason.code === "rule.self_sacrifice.resource_cost",
    ),
  );
});

test("does not treat risky high-power attacks as self-sacrifice moves", () => {
  const earthquake = {
    slot: 1,
    id: "earthquake",
    name: "Earthquake",
    category: "Physical",
    power: 100,
    accuracy: 100,
    expectedDamage: 86,
    opponentHp: 374,
  };
  const trace = createAiMoveTrace({
    turn: 1,
    side: 0,
    sideName: "AI",
    species: "Garganacl",
    difficulty: "expert",
    strategy: "balanced",
    selected: earthquake,
    candidates: [earthquake],
  });
  const candidate = trace.candidates[0];

  assert.equal(
    candidate.reasons.some(
      (reason) => reason.code === "rule.self_sacrifice.resource_cost",
    ),
    false,
  );
  assert.ok(scoreAiMoveCandidate(earthquake, "expert", "balanced") > 0);
});

test("applies RunAndBun-inspired lethal, repeated, and Dynamax switch penalties", () => {
  const riskySwitch = {
    slot: 2,
    name: "Glass counter",
    hpPercent: 0.45,
    expectedDamage: 20,
    targetIncomingDamageRatio: 0.7,
    switchedLastTurn: true,
    immediateReturn: true,
    dynamaxActive: true,
    dynamaxRemainingTurns: 2,
    safeImmediateKoAvailable: true,
  };
  const stableSwitch = {
    slot: 3,
    name: "Stable wall",
    hpPercent: 0.9,
    expectedDamage: 10,
    targetIncomingDamageRatio: 0.2,
  };

  assert.ok(scoreAiSwitchCandidate(stableSwitch) > scoreAiSwitchCandidate(riskySwitch));

  const trace = createAiSwitchTrace({
    turn: 7,
    side: 1,
    sideName: "AI",
    species: "Dragonite",
    selected: { slot: 2 },
    candidates: [riskySwitch, stableSwitch],
  });
  const risky = trace.candidates.find((candidate) => candidate.slot === 2);
  assert.ok(risky.reasons.some((reason) => reason.code === "rule.switch.lethal_switch_in"));
  assert.ok(risky.reasons.some((reason) => reason.code === "rule.switch.repeated_switch"));
  assert.ok(risky.reasons.some((reason) => reason.code === "rule.switch.dynamax_turn_cost"));
  assert.ok(risky.reasons.some((reason) => reason.code === "rule.switch.guaranteed_ko_penalty"));
});

test("adds switch score for field and weather synergy", () => {
  const rainAbuser = {
    slot: 2,
    name: "Swift Swim attacker",
    hpPercent: 0.8,
    expectedDamage: 45,
    fieldSynergyValue: 42,
    fieldSynergyLabel: "raindance",
    fieldSynergyReason: "비에서 쓱쓱으로 스피드가 크게 올라갑니다.",
  };
  const neutralAttacker = {
    slot: 3,
    name: "Neutral attacker",
    hpPercent: 0.8,
    expectedDamage: 70,
  };

  assert.ok(scoreAiSwitchCandidate(rainAbuser) > scoreAiSwitchCandidate(neutralAttacker));

  const trace = createAiSwitchTrace({
    turn: 8,
    side: 1,
    sideName: "AI",
    species: "Porygon2",
    selected: { slot: 2 },
    candidates: [rainAbuser, neutralAttacker],
  });
  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.ok(selected.reasons.some((reason) => reason.code === "rule.switch.field_synergy"));
});

test("scores Dynamax activation against setup opportunity cost", () => {
  const setupMove = {
    slot: 1,
    id: "swordsdance",
    name: "Swords Dance",
    category: "Status",
    hpPercent: 0.9,
    incomingDamageRatio: 0.12,
  };
  const normalAttack = {
    slot: 2,
    id: "slash",
    name: "Slash",
    type: "Normal",
    category: "Physical",
    power: 70,
    expectedDamage: 35,
  };
  const delayed = scoreAiDynamaxCandidate({
    active: { canDynamax: true, hpPercent: 0.9, incomingDamageRatio: 0.12 },
    configured: { gimmicks: { dynamax: true } },
    selectedMove: setupMove,
    moveCandidates: [setupMove, normalAttack],
    forceDynamax: true,
  });
  assert.ok(delayed.score < 12);
  assert.ok(
    delayed.reasons.some((reason) => reason.code === "gimmick.dynamax.delay_for_setup"),
  );

  const withMaxKnuckle = scoreAiDynamaxCandidate({
    active: { canDynamax: true, hpPercent: 0.9, incomingDamageRatio: 0.12 },
    configured: { gimmicks: { dynamax: true } },
    selectedMove: normalAttack,
    moveCandidates: [
      setupMove,
      { ...normalAttack, id: "brickbreak", type: "Fighting" },
    ],
    forceDynamax: true,
  });
  assert.ok(withMaxKnuckle.score >= 12);
  assert.ok(
    withMaxKnuckle.reasons.some((reason) => reason.code === "gimmick.dynamax.max_knuckle"),
  );
});

test("records normalized AI switch candidates with decision reasons", () => {
  const trace = createAiSwitchTrace({
    turn: 2,
    side: 1,
    sideName: "AI",
    species: "Raichu",
    selected: { slot: 3 },
    candidates: [
      { slot: 2, name: "Porygon2", hpPercent: 0.4, matchupValue: 12 },
      { slot: 3, name: "Snorlax", hpPercent: 0.9, expectedDamage: 30 },
    ],
  });

  const selected = trace.candidates.find((candidate) => candidate.selected);
  assert.equal(selected.type, "switch");
  assert.equal(selected.action.type, "switch");
  assert.ok(selected.reasons.some((reason) => reason.code === "switch.hp_remaining"));
});

test("converts a Showdown request into the common AI observation model", () => {
  const observation = showdownRequestToAiBattleObservation({
    battleId: "showdown-request",
    seed: 42,
    side: "p1",
    turn: 2,
    request: {
      active: [
        {
          moves: [
            { id: "thunderbolt", move: "Thunderbolt", pp: 15, target: "normal" },
            { id: "quickattack", move: "Quick Attack", pp: 30, target: "normal" },
          ],
        },
      ],
      side: {
        pokemon: [
          {
            ident: "p1a: Pikachu",
            details: "Pikachu, L50, M",
            condition: "88/120 par",
            active: true,
            moves: ["thunderbolt", "quickattack"],
          },
          {
            ident: "p1: Snorlax",
            details: "Snorlax, L50, M",
            condition: "220/260",
            active: false,
          },
        ],
      },
    },
  });

  assert.equal(observation.battleType.engine, "showdown");
  assert.equal(observation.activePokemon[0].name, "Pikachu");
  assert.equal(observation.activePokemon[0].status, "par");
  assert.equal(observation.benchPokemon[0].name, "Snorlax");
  assert.deepEqual(
    observation.legalActions.map((action) => action.id),
    ["thunderbolt", "quickattack"],
  );
});

test("converts a Cobbleverse simple state into the common AI observation model", () => {
  const observation = simpleStateToAiBattleObservation({
    sideIndex: 0,
    state: {
      seed: 7,
      turn: 4,
      weather: "RainDance",
      sides: [
        {
          name: "Red",
          active: 0,
          sideConditions: { reflect: { turns: 3 } },
          team: [
            {
              id: "pikachu",
              name: "Pikachu",
              hp: 90,
              stats: { hp: 120 },
              types: ["Electric"],
              moves: [
                { id: "thunderbolt", name: "Thunderbolt", pp: 15, priority: 0 },
              ],
            },
            {
              id: "snorlax",
              name: "Snorlax",
              hp: 220,
              stats: { hp: 260 },
              types: ["Normal"],
              moves: [],
            },
          ],
        },
        {
          name: "Blue",
          active: 0,
          sideConditions: {},
          team: [
            {
              id: "squirtle",
              name: "Squirtle",
              hp: 80,
              stats: { hp: 110 },
              types: ["Water"],
              moves: [],
            },
          ],
        },
      ],
      events: [{ turn: 4, type: "turn" }],
    },
  });

  assert.equal(observation.battleType.engine, "cobbleverse-simple");
  assert.equal(observation.side, "p1");
  assert.equal(observation.weather.id, "RainDance");
  assert.equal(observation.activePokemon[0].active, true);
  assert.equal(observation.opponentActivePokemon[0].name, "Squirtle");
  assert.deepEqual(
    observation.legalActions.map((action) => action.type),
    ["move", "switch"],
  );
});

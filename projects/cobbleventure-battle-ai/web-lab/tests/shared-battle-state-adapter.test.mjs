import assert from "node:assert/strict";
import test from "node:test";

import {
  createSimpleBattle,
  resolveSimpleTurn,
} from "../lib/cobbleventure-battle-engine.mjs";
import {
  fromSharedTurnCommands,
  roundTripWebBattleState,
  toSharedBattleState,
  toSharedTurnCommands,
} from "../lib/shared-battle-state-adapter.mjs";

const jsonValue = (value) => JSON.parse(JSON.stringify(value));

function member(name, overrides = {}) {
  return {
    id: name.toLowerCase(),
    name,
    level: 50,
    types: ["Normal"],
    ability: "pressure",
    role: "adapter-fixture",
    stats: {
      hp: 120,
      attack: 100,
      defence: 100,
      specialAttack: 100,
      specialDefence: 100,
      speed: 100,
    },
    moves: [{
      id: "tackle",
      name: "Tackle",
      type: "Normal",
      category: "Physical",
      power: 40,
      accuracy: 100,
      pp: 35,
      secondary: { chance: 100, boosts: { defence: -1 } },
    }],
    ...overrides,
  };
}

function fixtureState() {
  const state = createSimpleBattle({
    seed: 42,
    sides: [
      { name: "A", team: [member("Alpha"), member("AlphaBench")] },
      { name: "B", team: [member("Beta"), member("BetaBench")] },
    ],
  });
  state.field.weather = { id: "raindance", turns: 4, source: "drizzle" };
  state.field.pseudoWeather.trickroom = { id: "trickroom", turns: 3 };
  state.sides[0].conditions.spikes = { layers: 2, source: "Alpha" };
  state.sides[0].team[0].volatiles.taunt = { id: "taunt", turns: 2, source: "Beta" };
  state.sides[0].team[0].abilityState.customCounter = 7;
  state.aiTrace.push({ side: 0, nested: { retained: true } });
  state.events.push({
    turn: 0,
    type: "adapter_fixture",
    side: 0,
    customPayload: { retained: true },
  });
  return state;
}

test("round-trips the complete normalized web battle state through the KMP contract", () => {
  const state = fixtureState();
  const restored = roundTripWebBattleState(state);

  assert.deepEqual(jsonValue(restored), jsonValue(state));
});

test("round-trips a resolved turn including events, RNG, and the last successful move", () => {
  const state = resolveSimpleTurn(fixtureState(), [{ move: 1 }, { move: 1 }]);
  const restored = roundTripWebBattleState(state);

  assert.deepEqual(jsonValue(restored), jsonValue(state));
});

test("round-trips web move and switch commands through the shared command contract", () => {
  const state = fixtureState();
  const sharedState = toSharedBattleState(state);
  const commands = [{ move: 1, gimmick: "dynamax" }, { switch: 2 }];
  const restored = fromSharedTurnCommands(toSharedTurnCommands(commands, sharedState));

  assert.deepEqual(restored, commands);
});

test("round-trips item targets and a locked move command without a move slot", () => {
  const sharedState = toSharedBattleState(fixtureState());
  const commands = [{ item: "potion", itemTarget: 2 }, { gimmick: "" }];
  const restored = fromSharedTurnCommands(toSharedTurnCommands(commands, sharedState));

  assert.deepEqual(restored, commands);
});

import assert from "node:assert/strict";
import test from "node:test";

import { Dex } from "@pkmn/sim";

import {
  BATTLE_FORMATS,
  battleFormat,
  requireBattleFormat,
  showdownFormatId,
} from "../lib/battle-formats.mjs";

test("maps one, two, and three active slots to separate Showdown formats", () => {
  assert.deepEqual(
    Object.values(BATTLE_FORMATS).map((format) => [
      format.id,
      format.activeSlots,
      Dex.formats.get(format.showdownFormatId).gameType,
    ]),
    [
      ["single", 1, "singles"],
      ["double", 2, "doubles"],
      ["triple", 3, "triples"],
    ],
  );
});

test("keeps engine support flags explicit for each battle type", () => {
  assert.equal(battleFormat("single").supportsInteractive, true);
  assert.equal(battleFormat("double").supportsInteractive, true);
  assert.equal(battleFormat("triple").supportsInteractive, true);
  assert.equal(battleFormat("triple").supportsCobbleverse, false);
  assert.throws(() => requireBattleFormat("rotation"), /지원하지 않는 대결 타입/);
});

test("selects generation-specific Showdown formats for battle gimmicks", () => {
  assert.equal(showdownFormatId(battleFormat("single"), "gen8"), "gen8customgame");
  assert.equal(
    showdownFormatId(battleFormat("double"), "gen8"),
    "gen8doublescustomgame",
  );
  assert.equal(showdownFormatId(battleFormat("single"), "gen9"), "gen9customgame");
});

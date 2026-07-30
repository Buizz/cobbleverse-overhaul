import assert from "node:assert/strict";
import test from "node:test";

import { formatBattleDialogue } from "../lib/battle-dialogue.ts";

const speciesNames = {
  Porygon2: "폴리곤2",
  Magnezone: "자포코일",
  Scizor: "핫삼",
};

function speciesName(value) {
  return speciesNames[value] ?? value;
}

test("uses the same faint replacement message in PvE and EvE contexts", () => {
  const event = {
    turn: 2,
    type: "switch",
    actor: "p1a: Magnezone",
    fromActor: "p1a: Porygon2",
    automatic: true,
    forced: true,
    selection: "matchup_score",
    condition: "323/344",
  };

  assert.equal(
    formatBattleDialogue(event, {
      speciesName,
      sideLabels: { p1: "", p2: "상대 " },
    }),
    "폴리곤2가 쓰러져 자포코일이 대신 출전했다! (323/344)",
  );
  assert.equal(
    formatBattleDialogue(event, {
      speciesName,
      sideLabels: { p1: "1P ", p2: "2P " },
    }),
    "1P 폴리곤2가 쓰러져 자포코일이 대신 출전했다! (323/344)",
  );
});

test("distinguishes voluntary, self, and forced switches", () => {
  const base = {
    turn: 3,
    type: "switch",
    actor: "p2a: Scizor",
    fromActor: "p2a: Porygon2",
    condition: "344/344",
  };
  const context = {
    speciesName,
    sideLabels: { p1: "1P ", p2: "2P " },
  };

  assert.equal(
    formatBattleDialogue(
      { ...base, selection: "manual_switch", forced: false },
      context,
    ),
    "2P 포켓몬 교체: 폴리곤2 → 핫삼 (344/344)",
  );
  assert.equal(
    formatBattleDialogue(
      { ...base, selection: "self_switch", forced: true },
      context,
    ),
    "2P 폴리곤2는 돌아오고 핫삼이 나왔다! (344/344)",
  );
  assert.equal(
    formatBattleDialogue(
      { ...base, selection: "force_switch", forced: true },
      context,
    ),
    "2P 폴리곤2가 강제로 돌아가고 핫삼이 끌려 나왔다! (344/344)",
  );
});

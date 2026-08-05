import assert from "node:assert/strict";
import test from "node:test";

import {
  POKEMON_ENTRY_CLIPBOARD_SCHEMA,
  createPartyClipboardEntry,
  parsePartyClipboardText,
  toBattleLabParty,
  toContentManagerParty,
} from "../lib/pokemon-entry-clipboard.mjs";

const items = [
  { id: "mega_showdown:charizardite_x", shortId: "charizardite_x", category: "mega" },
  { id: "mega_showdown:normalium_z", shortId: "normalium_z", category: "z" },
  { id: "cobblemon:choice_band", shortId: "choice_band", category: "held" },
];

const species = [
  { id: "charizard", number: 6, forme: "" },
  { id: "charizardmegax", number: 6, forme: "Mega-X" },
];

test("converts a content manager party into the shared clipboard contract", () => {
  const entry = createPartyClipboardEntry(
    [
      {
        species: "cobblemon:charizard",
        form: "charizardmegax",
        aspects: ["male"],
        level: 75,
        gender: "male",
        nature: "jolly",
        ability: "blaze",
        held_item: null,
        gimmick: {
          type: "mega_evolution",
          item: "mega_showdown:charizardite_x",
        },
        moves: ["flareblitz", "dragonclaw"],
        ivs: { attack: 31, speed: 30 },
        evs: { attack: 252, speed: 252 },
        tera_type: "auto",
        shiny: true,
        gigantamax_factor: false,
      },
    ],
    { items, species },
  );

  assert.equal(entry.$schema, POKEMON_ENTRY_CLIPBOARD_SCHEMA);
  assert.equal(entry.pokemon[0].species, "cobblemon:charizard");
  assert.equal(entry.pokemon[0].form, "charizardmegax");
  assert.equal(entry.pokemon[0].held_item, null);
  assert.deepEqual(entry.pokemon[0].gimmick, {
    type: "mega_evolution",
    item: "mega_showdown:charizardite_x",
  });
  assert.equal(entry.pokemon[0].tera_type, "auto");
});

test("round-trips shared data through the battle lab representation", () => {
  const shared = createPartyClipboardEntry(
    [
      {
        species: "cobblemon:charizard",
        form: "charizardmegax",
        level: 75,
        gender: "female",
        nature: "jolly",
        ability: "blaze",
        gimmick: { type: "mega_evolution", item: "mega_showdown:charizardite_x" },
        moves: ["flareblitz", "dragonclaw"],
        ivs: { attack: 31, speed: 30 },
        evs: { attack: 252, speed: 252 },
        tera_type: "fire",
        shiny: true,
      },
    ],
    { items, species },
  );
  const battleParty = toBattleLabParty(shared, { items, species });
  assert.equal(battleParty[0].species, "charizardmegax");
  assert.equal(battleParty[0].heldItem, "mega_showdown:charizardite_x");
  assert.equal(battleParty[0].ivs.atk, 31);
  assert.equal(battleParty[0].gender, "female");
  assert.equal(battleParty[0].shiny, true);

  const restored = toContentManagerParty(
    createPartyClipboardEntry(battleParty, { items, species }),
    { items, species },
  );
  assert.equal(restored[0].species, "cobblemon:charizard");
  assert.equal(restored[0].form, "charizardmegax");
  assert.equal(restored[0].held_item, null);
  assert.equal(restored[0].gimmick.type, "mega_evolution");
  assert.equal(restored[0].tera_type, "fire");
  assert.equal(restored[0].gender, "female");
  assert.equal(restored[0].shiny, true);
});

test("accepts legacy battle and content manager JSON shapes", () => {
  const battle = parsePartyClipboardText(
    JSON.stringify({
      party: [
        {
          species: "charizard",
          heldItem: "charizardite_x",
          moves: ["flamethrower"],
          tera: "",
        },
      ],
    }),
    { items, species },
  );
  assert.equal(battle.pokemon[0].gimmick.type, "mega_evolution");
  assert.equal(battle.pokemon[0].tera_type, "auto");

  const manager = parsePartyClipboardText(
    JSON.stringify({
      battle: {
        team: [
          {
            species: "cobblemon:charizard",
            held_item: "cobblemon:choice_band",
            moves: ["flamethrower"],
            tera_type: "fire",
          },
        ],
      },
    }),
    { items, species },
  );
  assert.equal(manager.pokemon[0].held_item, "cobblemon:choice_band");
  assert.equal(manager.pokemon[0].gimmick, null);
});

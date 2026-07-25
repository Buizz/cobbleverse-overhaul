import assert from "node:assert/strict";
import test from "node:test";

import {
  buildTrainerIndex,
  normalizeStats,
  normalizeTrainer,
} from "../scripts/sync-trainers.mjs";
import {
  createCobblemonItemResolver,
  normalizeHeldItem,
} from "../lib/cobblemon-item-catalog.mjs";

test("normalizes short and long stat keys", () => {
  assert.deepEqual(
    normalizeStats({ atk: 10, defence: 20, spa: 30, speed: 40 }),
    {
      attack: 10,
      defence: 20,
      specialAttack: 30,
      speed: 40,
    },
  );
});

test("normalizes a trainer team without mutating the source shape", () => {
  const trainer = normalizeTrainer(
    {
      name: "Test",
      team: [
        {
          species: "pikachu",
          level: 25,
          moveset: ["thunderbolt"],
          ivs: { atk: 5 },
        },
      ],
    },
    "test.json",
  );

  assert.equal(trainer.id, "test");
  assert.equal(trainer.team[0].slot, 1);
  assert.equal(trainer.team[0].ivs.attack, 5);
});

test("promotes Cobblemon aspects to a resolved Showdown form", () => {
  const trainer = normalizeTrainer(
    {
      name: "Form Test",
      team: [
        {
          species: "articuno",
          aspects: ["galarian"],
          level: 90,
          moveset: ["freezingglare"],
        },
      ],
    },
    "form-test.json",
  );

  assert.equal(trainer.team[0].species, "articuno");
  assert.deepEqual(trainer.team[0].aspects, ["galarian"]);
  assert.equal(trainer.team[0].resolvedSpecies, "Articuno-Galar");
});

test("normalizes short item IDs to authoritative Cobblemon registry IDs", () => {
  const resolver = createCobblemonItemResolver({
    schemaVersion: 1,
    items: [
      {
        id: "cobblemon:leftovers",
        namespace: "cobblemon",
        path: "leftovers",
        battleCategory: "held",
        battleUsable: true,
      },
      {
        id: "mega_showdown:houndoominite",
        namespace: "mega_showdown",
        path: "houndoominite",
        battleCategory: "mega",
        battleUsable: true,
      },
      {
        id: "cobblemon:berry_juice",
        namespace: "cobblemon",
        path: "berry_juice",
        battleCategory: "unverified",
        battleUsable: false,
      },
    ],
  });

  assert.deepEqual(normalizeHeldItem("leftovers", resolver).heldItemOptions, [
    "cobblemon:leftovers",
  ]);
  assert.equal(
    normalizeHeldItem("mega_showdown:houndoominite", resolver).heldItem,
    "mega_showdown:houndoominite",
  );
  assert.equal(
    normalizeHeldItem("berry_juice", resolver).heldItem,
    "cobblemon:berry_juice",
  );
  assert.equal(
    normalizeHeldItem("berry_juice", resolver).heldItemResolution[0].status,
    "not_battle_item",
  );
  assert.equal(normalizeHeldItem("missing_item", resolver).heldItemResolution[0].status, "unknown");
});

test("builds the prioritized official and RCT trainer index", async () => {
  const payload = await buildTrainerIndex();

  assert.equal(payload.schemaVersion, 2);
  assert.equal(payload.trainerCount, 211);
  assert.equal(payload.trainers.length, 211);
  assert.equal(payload.trainers[0].id, "dbingsu-server-party");
  assert.equal(payload.trainers[0].entry.type, "official-player");
  assert.equal(payload.trainers[0].entry.priority, 1000);
  assert.ok(payload.trainers.every((trainer) => trainer.team.length > 0));
  assert.ok(payload.itemCatalog.itemCount > 0);
  assert.equal(
    payload.trainers
      .find((trainer) => trainer.id === "kanto_brock")
      .team.find((member) => member.heldItem)
      .heldItem.startsWith("cobblemon:"),
    true,
  );
  const corrado = payload.trainers.find(
    (trainer) => trainer.id === "sinnoh_corrado",
  );
  assert.equal(corrado.team[3].species, "articuno");
  assert.deepEqual(corrado.team[3].aspects, ["galarian"]);
  assert.equal(corrado.team[3].resolvedSpecies, "Articuno-Galar");
});

import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { strToU8, zipSync } from "fflate";

import {
  buildTrainerIndex,
  normalizeBattleItemCatalogEntry,
  normalizeStats,
  normalizeTrainer,
  readTrainerDocuments,
} from "../scripts/sync-trainers.mjs";
import {
  createCobblemonItemResolver,
  normalizeHeldItem,
} from "../lib/cobblemon-item-catalog.mjs";

test("promotes Z-A Mega stones into the searchable battle item catalog", () => {
  const dragalgite = normalizeBattleItemCatalogEntry({
    id: "zamega:dragalgite",
    namespace: "zamega",
    path: "dragalgite",
    englishName: "Dragalgite",
    koreanName: "",
    battleCategory: "unverified",
    battleUsable: false,
  });

  assert.equal(dragalgite.battleUsable, true);
  assert.equal(dragalgite.battleCategory, "mega");
  assert.equal(dragalgite.koreanName, "드래캄나이트");
});

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
  assert.equal(trainer.sourceGroup, "ungrouped");
  assert.equal(trainer.team[0].slot, 1);
  assert.equal(trainer.team[0].ivs.attack, 5);
});

test("reads grouped JSON and ZIP trainer sources recursively", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "cobbleverse-trainers-"));
  try {
    await mkdir(path.join(directory, "rct"), { recursive: true });
    await mkdir(path.join(directory, "custom"), { recursive: true });
    await writeFile(
      path.join(directory, "rct", "brock.json"),
      JSON.stringify({ name: "Brock", team: [{ species: "onix" }] }),
      "utf8",
    );
    await writeFile(
      path.join(directory, "custom", "entries.zip"),
      zipSync({
        "dbingsu.json": strToU8(
          JSON.stringify({
            name: "DBingsu",
            team: [{ species: "porygon2" }],
          }),
        ),
      }),
    );

    const documents = await readTrainerDocuments(directory);
    assert.deepEqual(
      documents.map((document) => ({
        group: document.sourceGroup,
        source: document.sourceFile,
      })),
      [
        {
          group: "custom",
          source: "entries/custom/entries.zip!/dbingsu.json",
        },
        { group: "rct", source: "entries/rct/brock.json" },
      ],
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
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

test("builds the prioritized custom and RCT trainer index", async () => {
  const payload = await buildTrainerIndex();

  assert.equal(payload.schemaVersion, 3);
  assert.equal(payload.trainerCount, 224);
  assert.equal(payload.trainers.length, 224);
  assert.deepEqual(payload.sourceGroups, ["custom", "rct"]);
  assert.equal(payload.trainers[0].id, "dbingsu-server-party");
  assert.equal(payload.trainers[0].sourceGroup, "custom");
  assert.equal(
    payload.trainers.find((trainer) => trainer.id === "kanto_brock").sourceGroup,
    "rct",
  );
  assert.equal(payload.trainers[0].entry.type, "official-player");
  assert.equal(payload.trainers[0].entry.priority, 1000);
  const dbingsuUrshifu = payload.trainers[0].team.find(
    (member) => member.species === "urshifu-rapidstrike",
  );
  assert.deepEqual(dbingsuUrshifu.gimmicks, {
    dynamax: true,
    gmax: true,
  });
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
  const regeneratorStall = payload.trainers.find(
    (trainer) => trainer.id === "legendary-regenerator-stall",
  );
  assert.equal(regeneratorStall.sourceGroup, "custom");
  assert.equal(regeneratorStall.ai.data.strategy, "defensive");
  assert.deepEqual(
    regeneratorStall.team.slice(0, 3).map((member) => [
      member.resolvedSpecies,
      member.ability,
    ]),
    [
      ["Ho-Oh", "regenerator"],
      ["Ferrothorn", "ironbarbs"],
      ["Toxapex", "regenerator"],
    ],
  );
  assert.deepEqual(
    [
      regeneratorStall.team[3].resolvedSpecies,
      regeneratorStall.team[3].ability,
      regeneratorStall.team[3].heldItem,
    ],
    ["Dragalge", "adaptability", "zamega:dragalgite"],
  );
  const annihilapeBatonPass = payload.trainers.find(
    (trainer) => trainer.id === "annihilape-baton-pass-offense",
  );
  assert.equal(annihilapeBatonPass.sourceGroup, "custom");
  assert.equal(annihilapeBatonPass.ai.data.strategy, "reckless_ace");
  assert.deepEqual(
    annihilapeBatonPass.team.slice(0, 3).map((member) => [
      member.resolvedSpecies,
      member.ability,
      member.moveset.includes("batonpass"),
    ]),
    [
      ["Scolipede", "speedboost", true],
      ["Smeargle", "owntempo", true],
      ["Annihilape", "defiant", false],
    ],
  );
  const manaphyArceusBatonPass = payload.trainers.find(
    (trainer) => trainer.id === "manaphy-arceus-baton-pass-offense",
  );
  assert.equal(manaphyArceusBatonPass.sourceGroup, "custom");
  assert.equal(manaphyArceusBatonPass.ai.data.strategy, "reckless_ace");
  assert.deepEqual(
    manaphyArceusBatonPass.team.slice(0, 2).map((member) => [
      member.resolvedSpecies,
      member.ability,
      member.moveset.includes("batonpass"),
    ]),
    [
      ["Manaphy", "hydration", true],
      ["Arceus", "multitype", false],
    ],
  );
  const tingLuHippowdonStall = payload.trainers.find(
    (trainer) => trainer.id === "ting-lu-hippowdon-stall",
  );
  assert.equal(tingLuHippowdonStall.sourceGroup, "custom");
  assert.equal(tingLuHippowdonStall.ai.data.strategy, "defensive");
  assert.deepEqual(
    tingLuHippowdonStall.team.slice(0, 2).map((member) => [
      member.resolvedSpecies,
      member.ability,
    ]),
    [
      ["Ting-Lu", "vesselofruin"],
      ["Hippowdon", "sandstream"],
    ],
  );
});

import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { createEditorCatalog } from "../lib/editor-catalog.mjs";

const localization = JSON.parse(
  await readFile(
    new URL("../public/data/cobblemon-ko-kr.json", import.meta.url),
    "utf8",
  ),
);
const itemCatalog = JSON.parse(
  await readFile(
    new URL("../public/data/cobblemon-battle-items.json", import.meta.url),
    "utf8",
  ),
);

test("builds searchable editor metadata from Showdown and Cobblemon data", () => {
  const catalog = createEditorCatalog(localization, itemCatalog);
  const pikachu = catalog.species.find((entry) => entry.id === "pikachu");
  const thunderbolt = catalog.moves.find((entry) => entry.id === "thunderbolt");
  const leftovers = catalog.items.find(
    (entry) => entry.id === "cobblemon:leftovers",
  );

  assert.equal(pikachu.name, "피카츄");
  assert.deepEqual(pikachu.types, ["Electric"]);
  assert.ok(pikachu.abilities.includes("static"));
  assert.equal(thunderbolt.category, "Special");
  assert.equal(thunderbolt.power, 90);
  assert.equal(leftovers.battleUsable, true);
  assert.ok(catalog.abilities.some((entry) => entry.id === "static"));
});

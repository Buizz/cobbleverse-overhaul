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
const i18nCatalog = JSON.parse(
  await readFile(
    new URL("../public/data/pokemon-i18n-ko.json", import.meta.url),
    "utf8",
  ),
);

test("builds searchable editor metadata from Showdown and Cobblemon data", () => {
  const catalog = createEditorCatalog(localization, itemCatalog, i18nCatalog);
  const pikachu = catalog.species.find((entry) => entry.id === "pikachu");
  const thunderbolt = catalog.moves.find((entry) => entry.id === "thunderbolt");
  const leftovers = catalog.items.find(
    (entry) => entry.id === "cobblemon:leftovers",
  );
  const assaultVest = catalog.items.find(
    (entry) => entry.id === "cobblemon:assault_vest",
  );
  const download = catalog.abilities.find((entry) => entry.id === "download");
  const sturdy = catalog.abilities.find((entry) => entry.id === "sturdy");
  const mindsEye = catalog.abilities.find((entry) => entry.id === "mindseye");

  assert.equal(pikachu.name, "피카츄");
  assert.deepEqual(pikachu.types, ["Electric"]);
  assert.ok(pikachu.abilities.includes("static"));
  assert.equal(thunderbolt.category, "Special");
  assert.equal(thunderbolt.name, "10만볼트");
  assert.equal(thunderbolt.power, 90);
  assert.equal(leftovers.battleUsable, true);
  assert.equal(leftovers.name, "먹다남은음식");
  assert.match(leftovers.description, /회복/);
  assert.equal(assaultVest.name, "돌격조끼");
  assert.match(assaultVest.description, /특수방어/);
  assert.equal(download.name, "다운로드");
  assert.match(download.description, /방어/);
  assert.equal(sturdy.name, "옹골참");
  assert.match(sturdy.description, /일격/);
  assert.equal(mindsEye.name, "심안");
  assert.match(mindsEye.description, /고스트/);
});

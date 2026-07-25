import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { localizedSpeciesName } from "../lib/species-localization.mjs";

const catalogPath = new URL(
  "../public/data/cobblemon-ko-kr.json",
  import.meta.url,
);

test("contains Cobblemon Korean species and move localization", async () => {
  const catalog = JSON.parse(await readFile(catalogPath, "utf8"));

  assert.equal(catalog.locale, "ko-KR");
  assert.equal(catalog.species.pikachu.name, "피카츄");
  assert.equal(catalog.moves.thunderbolt.name, "10만볼트");
  assert.match(catalog.moves.thunderbolt.description, /전격/);
  assert.ok(Object.keys(catalog.species).length >= 1_000);
  assert.ok(Object.keys(catalog.moves).length >= 800);
  assert.equal(catalog.typeIcons.length, 18);

  const fireIcon = await readFile(
    new URL("../public/assets/cobblemon/type-icons/fire.png", import.meta.url),
  );
  assert.deepEqual(
    [...fireIcon.subarray(0, 8)],
    [137, 80, 78, 71, 13, 10, 26, 10],
  );

  const moveCategories = await readFile(
    new URL(
      "../public/assets/cobblemon/gui/move-categories.png",
      import.meta.url,
    ),
  );
  assert.deepEqual(
    [...moveCategories.subarray(0, 8)],
    [137, 80, 78, 71, 13, 10, 26, 10],
  );
  assert.equal(moveCategories.readUInt32BE(16), 24);
  assert.equal(moveCategories.readUInt32BE(20), 48);
});

test("localizes Showdown form names using their base species", async () => {
  const catalog = JSON.parse(await readFile(catalogPath, "utf8"));

  assert.equal(
    localizedSpeciesName(catalog, "Urshifu-Rapid-Strike"),
    "우라오스 (연격의 태세)",
  );
  assert.equal(
    localizedSpeciesName(catalog, "Ursaluna-Bloodmoon"),
    "다투곰 (붉은 달)",
  );
  assert.equal(
    localizedSpeciesName(catalog, "Groudon-Primal"),
    "그란돈 (원시회귀)",
  );
  assert.equal(
    localizedSpeciesName(catalog, "Articuno-Galar"),
    "프리져 (가라르의 모습)",
  );
  assert.equal(localizedSpeciesName(catalog, "Pikachu"), "피카츄");
});

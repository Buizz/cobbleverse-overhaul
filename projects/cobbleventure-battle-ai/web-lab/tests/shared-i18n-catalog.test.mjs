import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { createEditorCatalog } from "../lib/editor-catalog.mjs";
import { readSharedPokemonI18nCatalog } from "../lib/shared-i18n-catalog.mjs";

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

test("loads shared i18n catalog from the web-lab working directory", async () => {
  const i18nCatalog = await readSharedPokemonI18nCatalog();
  const catalog = createEditorCatalog(localization, itemCatalog, i18nCatalog);

  const download = catalog.abilities.find((entry) => entry.id === "download");
  const teravolt = catalog.abilities.find((entry) => entry.id === "teravolt");
  const eviolite = catalog.items.find((entry) => entry.shortId === "eviolite");
  const lifeOrb = catalog.items.find(
    (entry) => entry.id === "cobblemon:life_orb",
  );

  assert.equal(download?.name, "다운로드");
  assert.match(download?.description ?? "", /방어/);
  assert.equal(teravolt?.name, "테라볼티지");
  assert.match(teravolt?.description ?? "", /상대/);
  assert.equal(eviolite?.name, "진화의휘석");
  assert.match(eviolite?.description ?? "", /특수방어|방어/);
  assert.equal(lifeOrb?.name, "생명의구슬");
  assert.match(lifeOrb?.description ?? "", /데미지|위력|HP/);
});

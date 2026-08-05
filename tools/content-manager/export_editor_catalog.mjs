import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const repositoryRoot = resolve(process.argv[2] || process.cwd());
const webLabRoot = resolve(
  repositoryRoot,
  "projects/cobbleventure-battle-ai/web-lab",
);

const readJson = async (relativePath) =>
  JSON.parse(await readFile(resolve(webLabRoot, relativePath), "utf8"));

const [{ createEditorCatalog }, localization, items, i18n, rawItems] = await Promise.all([
  import(pathToFileURL(resolve(webLabRoot, "lib/editor-catalog.mjs")).href),
  readJson("public/data/cobblemon-ko-kr.json"),
  readJson("public/data/cobblemon-battle-items.json"),
  readJson("public/data/pokemon-i18n-ko.json"),
  JSON.parse(
    await readFile(
      resolve(repositoryRoot, "trainer-data/catalogs/cobblemon-items.json"),
      "utf8",
    ),
  ),
]);

const bagTagCategories = [
  ["cobblemon:potions", "potion"],
  ["cobblemon:restores", "status"],
  ["cobblemon:revives", "revive"],
  ["cobblemon:battle_items", "battle"],
];
const bagCategoryNames = {
  potion: "HP 회복",
  status: "상태 회복",
  revive: "기절 회복",
  battle: "능력치 강화",
};
const bagItems = (rawItems.items ?? [])
  .map((item) => {
    const tags = Array.isArray(item.tags) ? item.tags : [];
    const match = bagTagCategories.find(([tag]) => tags.includes(tag));
    if (!match || item.namespace !== "cobblemon") return null;
    const category = match[1];
    return {
      id: item.id,
      shortId: item.path,
      name: item.koreanName || item.englishName || item.path,
      englishName: item.englishName || item.path,
      description: `${bagCategoryNames[category]} 아이템`,
      namespace: item.namespace,
      category,
    };
  })
  .filter(Boolean)
  .filter((item, index, entries) => entries.findIndex((entry) => entry.id === item.id) === index)
  .sort((left, right) => left.name.localeCompare(right.name, "ko"));

const catalog = createEditorCatalog(localization, items, i18n);
catalog.bagItems = bagItems;
process.stdout.write(JSON.stringify(catalog));

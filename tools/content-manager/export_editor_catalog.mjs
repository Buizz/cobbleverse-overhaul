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

const [{ createEditorCatalog }, localization, items, i18n] = await Promise.all([
  import(pathToFileURL(resolve(webLabRoot, "lib/editor-catalog.mjs")).href),
  readJson("public/data/cobblemon-ko-kr.json"),
  readJson("public/data/cobblemon-battle-items.json"),
  readJson("public/data/pokemon-i18n-ko.json"),
]);

process.stdout.write(
  JSON.stringify(createEditorCatalog(localization, items, i18n)),
);

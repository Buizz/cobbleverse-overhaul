import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const I18N_RELATIVE_PATH = join("data", "i18n", "pokemon-i18n-ko.json");
const moduleDir = dirname(fileURLToPath(import.meta.url));

export async function readSharedPokemonI18nCatalog(cwd = process.cwd()) {
  const candidates = [
    join(moduleDir, "..", "..", I18N_RELATIVE_PATH),
    join(moduleDir, "..", "public", "data", "pokemon-i18n-ko.json"),
    join(cwd, "..", I18N_RELATIVE_PATH),
    join(cwd, I18N_RELATIVE_PATH),
    join(cwd, "projects", "cobbleventure-battle-ai", I18N_RELATIVE_PATH),
  ];

  const failures = [];
  for (const filePath of candidates) {
    try {
      return JSON.parse(await readFile(filePath, "utf8"));
    } catch (error) {
      failures.push(`${filePath}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  throw new Error(
    `Unable to read shared Pokemon i18n catalog. Tried:\n${failures.join("\n")}`,
  );
}

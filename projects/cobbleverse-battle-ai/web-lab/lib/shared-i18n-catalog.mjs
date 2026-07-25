import { readFile } from "node:fs/promises";
import { join } from "node:path";

const I18N_RELATIVE_PATH = join("data", "i18n", "pokemon-i18n-ko.json");

export async function readSharedPokemonI18nCatalog(cwd = process.cwd()) {
  const candidates = [
    join(cwd, "..", I18N_RELATIVE_PATH),
    join(cwd, I18N_RELATIVE_PATH),
    join(cwd, "projects", "cobbleverse-battle-ai", I18N_RELATIVE_PATH),
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

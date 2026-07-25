import { readFile } from "node:fs/promises";

import localization from "../../../public/data/cobblemon-ko-kr.json";
import itemCatalog from "../../../public/data/cobblemon-battle-items.json";
import { createEditorCatalog } from "../../../lib/editor-catalog.mjs";

async function readLatestI18nCatalog() {
  return JSON.parse(
    await readFile(
      new URL("../../../../data/i18n/pokemon-i18n-ko.json", import.meta.url),
      "utf8",
    ),
  );
}

export async function GET() {
  const catalog = createEditorCatalog(
    localization,
    itemCatalog,
    await readLatestI18nCatalog(),
  );
  return Response.json(catalog, {
    headers: {
      "cache-control": "no-store",
    },
  });
}

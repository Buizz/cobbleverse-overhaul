import { readFile } from "node:fs/promises";

import localization from "../../../public/data/cobblemon-ko-kr.json";
import itemCatalog from "../../../public/data/cobblemon-battle-items.json";
import i18nCatalog from "../../../public/data/pokemon-i18n-ko.json";
import { createEditorCatalog } from "../../../lib/editor-catalog.mjs";

async function readLatestI18nCatalog() {
  try {
    return JSON.parse(
      await readFile(
        new URL("../../../public/data/pokemon-i18n-ko.json", import.meta.url),
        "utf8",
      ),
    );
  } catch {
    return i18nCatalog;
  }
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

import localization from "../../../public/data/cobblemon-ko-kr.json";
import itemCatalog from "../../../public/data/cobblemon-battle-items.json";
import { createEditorCatalog } from "../../../lib/editor-catalog.mjs";
import { readSharedPokemonI18nCatalog } from "../../../lib/shared-i18n-catalog.mjs";

export async function GET() {
  const catalog = createEditorCatalog(
    localization,
    itemCatalog,
    await readSharedPokemonI18nCatalog(),
  );
  return Response.json(catalog, {
    headers: {
      "cache-control": "no-store",
    },
  });
}

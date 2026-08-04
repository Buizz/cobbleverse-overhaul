import localization from "../../../public/data/cobblemon-ko-kr.json";
import itemCatalog from "../../../public/data/cobblemon-battle-items.json";
import pokemonI18nCatalog from "../../../public/data/pokemon-i18n-ko.json";
import { createEditorCatalog } from "../../../lib/editor-catalog.mjs";

export async function GET() {
  const catalog = createEditorCatalog(
    localization,
    itemCatalog,
    pokemonI18nCatalog,
  );
  return Response.json(catalog, {
    headers: {
      "cache-control": "no-store",
    },
  });
}

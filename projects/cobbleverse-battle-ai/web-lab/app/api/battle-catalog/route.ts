import localization from "../../../public/data/cobblemon-ko-kr.json";
import itemCatalog from "../../../public/data/cobblemon-battle-items.json";
import { createEditorCatalog } from "../../../lib/editor-catalog.mjs";

const catalog = createEditorCatalog(localization, itemCatalog);

export async function GET() {
  return Response.json(catalog, {
    headers: {
      "cache-control": "public, max-age=300, stale-while-revalidate=3600",
    },
  });
}

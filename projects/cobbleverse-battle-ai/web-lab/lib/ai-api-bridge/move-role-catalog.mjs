import { readFile } from "node:fs/promises";

const defaultCatalogUrl = new URL(
  "../../../data/ai/ai-move-role-classification.json",
  import.meta.url,
);

export async function loadMoveRoleCatalog(catalogUrl = defaultCatalogUrl) {
  return JSON.parse(await readFile(catalogUrl, "utf8"));
}

export function getMoveRoleEntry(catalog, moveId) {
  const normalizedId = String(moveId ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "");
  return catalog?.moves?.[normalizedId] ?? null;
}

export function getMoveRoleScore(catalog, moveId, role) {
  return getMoveRoleEntry(catalog, moveId)?.roleScores?.[role] ?? 0;
}

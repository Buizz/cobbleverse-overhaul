function cleanItemId(value) {
  return typeof value === "string" ? value.trim().toLowerCase() : "";
}

export function createCobblemonItemResolver(catalog) {
  const items = Array.isArray(catalog?.items) ? catalog.items : [];
  const byId = new Map();
  const battleByPath = new Map();

  for (const item of items) {
    const id = cleanItemId(item?.id);
    const itemPath = cleanItemId(item?.path);
    if (!id || !itemPath) continue;

    const normalized = {
      id,
      namespace: cleanItemId(item.namespace),
      path: itemPath,
      englishName: String(item.englishName ?? ""),
      koreanName: String(item.koreanName ?? ""),
      battleCategory: String(item.battleCategory ?? "unverified"),
      battleUsable: item.battleUsable === true,
    };
    byId.set(id, normalized);

    if (normalized.battleUsable) {
      const matches = battleByPath.get(itemPath) ?? [];
      matches.push(normalized);
      battleByPath.set(itemPath, matches);
    }
  }

  return {
    catalogVersion: Number(catalog?.schemaVersion ?? 0),
    itemCount: byId.size,
    resolve(value) {
      const input = cleanItemId(value);
      if (!input) {
        return {
          input,
          canonicalId: null,
          status: "empty",
          candidates: [],
        };
      }

      if (input.includes(":")) {
        const exact = byId.get(input);
        if (!exact) {
          return {
            input,
            canonicalId: input,
            status: "unknown",
            candidates: [],
          };
        }
        return {
          input,
          canonicalId: exact.id,
          status: exact.battleUsable ? "resolved" : "not_battle_item",
          candidates: [exact.id],
          item: exact,
        };
      }

      const candidates = battleByPath.get(input) ?? [];
      if (candidates.length === 1) {
        return {
          input,
          canonicalId: candidates[0].id,
          status: "resolved",
          candidates: [candidates[0].id],
          item: candidates[0],
        };
      }
      if (candidates.length > 1) {
        return {
          input,
          canonicalId: input,
          status: "ambiguous",
          candidates: candidates.map((item) => item.id).sort(),
        };
      }

      const exactUnverified = [...byId.values()].filter((item) => item.path === input);
      if (exactUnverified.length === 1) {
        return {
          input,
          canonicalId: exactUnverified[0].id,
          status: "not_battle_item",
          candidates: [exactUnverified[0].id],
          item: exactUnverified[0],
        };
      }
      return {
        input,
        canonicalId: input,
        status: exactUnverified.length > 1 ? "ambiguous" : "unknown",
        candidates: exactUnverified.map((item) => item.id).sort(),
      };
    },
  };
}

export function normalizeHeldItem(value, resolver) {
  const rawValues = Array.isArray(value) ? value : value == null || value === "" ? [] : [value];
  const resolutions = rawValues
    .map((entry) =>
      resolver
        ? resolver.resolve(entry)
        : {
            input: cleanItemId(entry),
            canonicalId: cleanItemId(entry) || null,
            status: cleanItemId(entry) ? "not_checked" : "empty",
            candidates: [],
          },
    )
    .filter((entry) => entry.status !== "empty");
  const options = resolutions.map((entry) => entry.canonicalId).filter(Boolean);

  return {
    heldItem: options[0] ?? null,
    heldItemOptions: options,
    heldItemResolution: resolutions,
  };
}

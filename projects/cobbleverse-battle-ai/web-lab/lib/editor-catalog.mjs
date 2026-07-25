import { Dex } from "@pkmn/sim";

function localizedEntry(catalog, group, id) {
  return catalog?.[group]?.[id] ?? {};
}

function generationForNumber(number) {
  if (number <= 151) return 1;
  if (number <= 251) return 2;
  if (number <= 386) return 3;
  if (number <= 493) return 4;
  if (number <= 649) return 5;
  if (number <= 721) return 6;
  if (number <= 809) return 7;
  if (number <= 905) return 8;
  return 9;
}

export function createEditorCatalog(localization, itemCatalog) {
  const species = [];
  const seenSpecies = new Set();
  for (const localizedId of Object.keys(localization?.species ?? {})) {
    const entry = Dex.species.get(localizedId);
    if (!entry.exists || entry.num <= 0 || seenSpecies.has(entry.id)) continue;
    seenSpecies.add(entry.id);
    const localized = localizedEntry(localization, "species", localizedId);
    species.push({
      id: entry.id,
      name: localized.name || entry.name,
      englishName: entry.name,
      description: localized.description || "",
      number: entry.num,
      generation: entry.gen || generationForNumber(entry.num),
      types: entry.types,
      baseStats: entry.baseStats,
      abilities: Object.values(entry.abilities ?? {})
        .map((ability) => Dex.toID(ability))
        .filter(Boolean),
    });
  }

  const moves = [];
  for (const [localizedId, localized] of Object.entries(
    localization?.moves ?? {},
  )) {
    const entry = Dex.moves.get(localizedId);
    if (!entry.exists || entry.isNonstandard === "CAP") continue;
    moves.push({
      id: entry.id,
      name: localized.name || entry.name,
      englishName: entry.name,
      description: localized.description || entry.shortDesc || entry.desc || "",
      type: entry.type,
      category: entry.category,
      power: entry.basePower,
      accuracy: entry.accuracy,
      pp: entry.pp,
      priority: entry.priority,
      target: entry.target,
    });
  }

  const abilities = Dex.abilities
    .all()
    .filter((entry) => entry.exists && !entry.isNonstandard)
    .map((entry) => ({
      id: entry.id,
      name: entry.name,
      description: entry.shortDesc || entry.desc || "",
      generation: entry.gen,
    }));

  const items = (itemCatalog?.items ?? []).map((item) => {
    const showdown = Dex.items.get(item.path);
    return {
      id: item.id,
      shortId: item.path,
      name: item.koreanName || item.englishName || showdown.name || item.path,
      englishName: item.englishName || showdown.name || item.path,
      description: showdown.shortDesc || showdown.desc || "",
      namespace: item.namespace,
      category: item.battleCategory,
      battleUsable: item.battleUsable === true,
    };
  });

  const byName = (left, right) =>
    String(left.name).localeCompare(String(right.name), "ko");
  species.sort((left, right) => left.number - right.number || byName(left, right));
  moves.sort(byName);
  abilities.sort(byName);
  items.sort(byName);

  return {
    schemaVersion: 1,
    species,
    moves,
    abilities,
    items,
  };
}

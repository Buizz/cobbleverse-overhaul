import { Dex } from "@pkmn/sim";
import { Learnsets } from "../node_modules/@pkmn/sim/build/esm/data/learnsets.mjs";

const LEARN_SOURCE_METHODS = {
  L: "level",
  M: "machine",
  T: "tutor",
  E: "egg",
  S: "event",
  D: "dream",
  V: "transfer",
  R: "reminder",
};

function localizedEntry(catalog, group, id) {
  return catalog?.[group]?.[id] ?? {};
}

function i18nEntry(catalog, group, id) {
  const key = Dex.toID(id);
  return (
    catalog?.[group]?.[key] ??
    catalog?.[group]?.[id] ??
    Object.entries(catalog?.[group] ?? {}).find(
      ([entryId]) => Dex.toID(entryId) === key,
    )?.[1] ??
    {}
  );
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

export function parseLearnSourceCode(source) {
  const match = String(source ?? "").match(/^(\d+)([A-Z])(\d+)?/);
  if (!match) {
    return {
      source: String(source ?? ""),
      generation: null,
      method: "other",
      level: null,
    };
  }
  return {
    source,
    generation: Number(match[1]),
    method: LEARN_SOURCE_METHODS[match[2]] ?? "other",
    level: match[2] === "L" && match[3] ? Number(match[3]) : null,
  };
}

function summarizedLearnSources(sources) {
  const bestByKey = new Map();
  for (const source of sources ?? []) {
    const parsed = parseLearnSourceCode(source);
    const key = `${parsed.method}:${parsed.level ?? ""}`;
    const current = bestByKey.get(key);
    if (!current || Number(parsed.generation ?? 0) > Number(current.generation ?? 0)) {
      bestByKey.set(key, parsed);
    }
  }
  return [...bestByKey.values()].sort((left, right) => {
    const methodOrder = ["level", "machine", "tutor", "egg", "event", "reminder", "transfer", "dream", "other"];
    const methodDiff =
      methodOrder.indexOf(left.method) - methodOrder.indexOf(right.method);
    if (methodDiff !== 0) return methodDiff;
    const generationDiff = Number(right.generation ?? 0) - Number(left.generation ?? 0);
    if (generationDiff !== 0) return generationDiff;
    return Number(left.level ?? 999) - Number(right.level ?? 999);
  });
}

function learnsetForSpecies(species) {
  const direct = Learnsets[species.id]?.learnset;
  const base = species.baseSpecies ? Learnsets[Dex.toID(species.baseSpecies)]?.learnset : null;
  const learnset = direct ?? base;
  if (!learnset) return {};
  return Object.fromEntries(
    Object.entries(learnset)
      .map(([moveId, sources]) => [moveId, summarizedLearnSources(sources)])
      .filter(([, methods]) => methods.length > 0),
  );
}

export function createEditorCatalog(localization, itemCatalog, i18nCatalog = null) {
  const species = [];
  const learnsets = {};
  const seenSpecies = new Set();

  const addSpecies = (entry, localizedId = entry.id) => {
    if (
      !entry.exists ||
      entry.num <= 0 ||
      entry.isNonstandard === "CAP" ||
      seenSpecies.has(entry.id)
    ) {
      return;
    }
    seenSpecies.add(entry.id);
    const localized = localizedEntry(localization, "species", localizedId);
    const i18n = i18nEntry(i18nCatalog, "species", entry.id);
    species.push({
      id: entry.id,
      name: i18n.name || localized.name || entry.name,
      englishName: entry.name,
      baseSpecies: entry.baseSpecies || entry.name,
      forme: entry.forme || "",
      description: i18n.description || localized.description || "",
      number: entry.num,
      generation: entry.gen || generationForNumber(entry.num),
      types: entry.types,
      baseStats: entry.baseStats,
      abilities: Object.values(entry.abilities ?? {})
        .map((ability) => Dex.toID(ability))
        .filter(Boolean),
    });
    const learnset = learnsetForSpecies(entry);
    if (Object.keys(learnset).length > 0) {
      learnsets[entry.id] = learnset;
    }
  };

  for (const localizedId of Object.keys(localization?.species ?? {})) {
    addSpecies(Dex.species.get(localizedId), localizedId);
  }

  for (const entry of Dex.species.all()) {
    addSpecies(entry);
  }

  const moves = [];
  for (const [localizedId, localized] of Object.entries(
    localization?.moves ?? {},
  )) {
    const entry = Dex.moves.get(localizedId);
    if (!entry.exists || entry.isNonstandard === "CAP") continue;
    const i18n = i18nEntry(i18nCatalog, "moves", entry.id);
    moves.push({
      id: entry.id,
      name: i18n.name || localized.name || entry.name,
      englishName: entry.name,
      description:
        i18n.description || localized.description || entry.shortDesc || entry.desc || "",
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
    .map((entry) => {
      const i18n = i18nEntry(i18nCatalog, "abilities", entry.id);
      return {
        id: entry.id,
        name: i18n.name || entry.name,
        englishName: entry.name,
        description: i18n.description || entry.shortDesc || entry.desc || "",
        generation: entry.gen,
      };
    });

  const items = (itemCatalog?.items ?? []).map((item) => {
    const showdown = Dex.items.get(item.path);
    const i18n =
      i18nCatalog?.items?.[item.id] ??
      i18nCatalog?.items?.[item.path] ??
      i18nEntry(i18nCatalog, "items", item.path);
    return {
      id: item.id,
      shortId: item.path,
      name:
        i18n.name ||
        item.koreanName ||
        item.englishName ||
        showdown.name ||
        item.path,
      englishName: item.englishName || showdown.name || item.path,
      description: i18n.description || showdown.shortDesc || showdown.desc || "",
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
    learnsets,
  };
}

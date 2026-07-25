import { mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  createCobblemonItemResolver,
  normalizeHeldItem,
} from "../lib/cobblemon-item-catalog.mjs";
import { resolveShowdownMemberSpecies } from "../lib/showdown-species.mjs";

const scriptFile = fileURLToPath(import.meta.url);
const siteRoot = path.resolve(path.dirname(scriptFile), "..");
const repositoryRoot = path.resolve(siteRoot, "..", "..", "..");
const trainerSource = path.join(
  repositoryRoot,
  "trainer-data",
  "rctmod-v16-ver22",
  "trainers",
);
const officialTrainerSource = path.join(
  repositoryRoot,
  "trainer-data",
  "official-entries",
);
const outputFile = path.join(siteRoot, "public", "data", "trainers.json");
const itemCatalogSource = path.join(
  repositoryRoot,
  "trainer-data",
  "catalogs",
  "cobblemon-items.json",
);
const itemCatalogOutput = path.join(
  siteRoot,
  "public",
  "data",
  "cobblemon-battle-items.json",
);

const statAliases = {
  hp: "hp",
  atk: "attack",
  attack: "attack",
  def: "defence",
  defence: "defence",
  defense: "defence",
  spa: "specialAttack",
  special_attack: "specialAttack",
  spd: "specialDefence",
  special_defence: "specialDefence",
  special_defense: "specialDefence",
  spe: "speed",
  speed: "speed",
};

export function normalizeStats(stats) {
  if (!stats || typeof stats !== "object" || Array.isArray(stats)) {
    return {};
  }

  return Object.fromEntries(
    Object.entries(stats)
      .map(([key, value]) => [statAliases[key] ?? key, Number(value)])
      .filter(([, value]) => Number.isFinite(value)),
  );
}

export function normalizeTrainer(raw, sourceFile, itemResolver = null) {
  const id = path.basename(sourceFile, ".json");
  const team = Array.isArray(raw.team)
    ? raw.team.map((member, index) => {
        const normalizedItem = normalizeHeldItem(member.heldItem, itemResolver);
        const aspects = [
          ...(Array.isArray(member.aspects) ? member.aspects : []),
          ...(member.aspect ? [member.aspect] : []),
        ].map(String);
        const species = String(member.species ?? "unknown");
        const resolvedSpecies = resolveShowdownMemberSpecies({
          species,
          aspects,
        });
        return {
          slot: index + 1,
          species,
          resolvedSpecies: resolvedSpecies.exists
            ? resolvedSpecies.showdownName
            : species,
          aspects,
          gimmicks:
            member.gimmicks &&
            typeof member.gimmicks === "object" &&
            !Array.isArray(member.gimmicks)
              ? member.gimmicks
              : {},
          level: Number.isFinite(Number(member.level)) ? Number(member.level) : 1,
          gender: member.gender ? String(member.gender) : null,
          nature: member.nature ? String(member.nature) : null,
          ability: member.ability ? String(member.ability) : null,
          ...normalizedItem,
          moveset: Array.isArray(member.moveset)
            ? member.moveset.map(String).slice(0, 4)
            : [],
          ivs: normalizeStats(member.ivs),
          evs: normalizeStats(member.evs),
        };
      })
    : [];

  return {
    id,
    sourceFile,
    name: String(raw.name ?? id),
    entry: {
      type: String(raw.entry?.type ?? "computer"),
      priority: Number.isFinite(Number(raw.entry?.priority))
        ? Number(raw.entry.priority)
        : 0,
      label: raw.entry?.label ? String(raw.entry.label) : null,
      source: raw.entry?.source ? String(raw.entry.source) : null,
      owner: raw.entry?.owner ? String(raw.entry.owner) : null,
      snapshotDate: raw.entry?.snapshotDate
        ? String(raw.entry.snapshotDate)
        : null,
    },
    battleRules:
      raw.battleRules && typeof raw.battleRules === "object"
        ? raw.battleRules
        : {},
    bag: Array.isArray(raw.bag) ? raw.bag : [],
    team,
    ai: raw.ai && typeof raw.ai === "object" ? raw.ai : null,
  };
}

export async function buildTrainerIndex() {
  const itemCatalog = JSON.parse(await readFile(itemCatalogSource, "utf8"));
  const itemResolver = createCobblemonItemResolver(itemCatalog);
  const sourceGroups = [
    {
      directory: officialTrainerSource,
      sourcePrefix: "official-entries",
    },
    {
      directory: trainerSource,
      sourcePrefix: "rctmod-v16-ver22/trainers",
    },
  ];
  const trainers = [];

  for (const sourceGroup of sourceGroups) {
    const sourceFiles = (await readdir(sourceGroup.directory))
      .filter((name) => name.toLowerCase().endsWith(".json"))
      .sort();
    for (const sourceFile of sourceFiles) {
      const raw = JSON.parse(
        await readFile(path.join(sourceGroup.directory, sourceFile), "utf8"),
      );
      trainers.push(
        normalizeTrainer(
          raw,
          `${sourceGroup.sourcePrefix}/${sourceFile}`,
          itemResolver,
        ),
      );
    }
  }
  trainers.sort(
    (left, right) =>
      right.entry.priority - left.entry.priority ||
      left.name.localeCompare(right.name, "ko"),
  );

  const itemResolutions = trainers
    .flatMap((trainer) => trainer.team)
    .flatMap((member) => member.heldItemResolution);
  const resolutionCounts = Object.fromEntries(
    [...new Set(itemResolutions.map((entry) => entry.status))]
      .sort()
      .map((status) => [
        status,
        itemResolutions.filter((entry) => entry.status === status).length,
      ]),
  );
  const payload = {
    schemaVersion: 2,
    source: "Cobbleverse official entries + RCT Mod v16 ver22",
    generatedFrom: [
      "trainer-data/official-entries",
      "trainer-data/rctmod-v16-ver22/trainers",
    ],
    itemCatalog: {
      source: "trainer-data/catalogs/cobblemon-items.json",
      schemaVersion: itemResolver.catalogVersion,
      itemCount: itemResolver.itemCount,
      resolutionCounts,
    },
    trainerCount: trainers.length,
    trainers,
  };

  await mkdir(path.dirname(outputFile), { recursive: true });
  await writeFile(outputFile, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  const battleItems = {
    schemaVersion: itemCatalog.schemaVersion,
    generatedAt: itemCatalog.generatedAt,
    source: "trainer-data/catalogs/cobblemon-items.json",
    itemCount: itemCatalog.items.filter((item) => item.battleUsable).length,
    items: itemCatalog.items.filter((item) => item.battleUsable),
  };
  await writeFile(
    itemCatalogOutput,
    `${JSON.stringify(battleItems, null, 2)}\n`,
    "utf8",
  );
  return payload;
}

if (process.argv[1] && path.resolve(process.argv[1]) === scriptFile) {
  const payload = await buildTrainerIndex();
  console.log(`Synced ${payload.trainerCount} trainer definitions.`);
}

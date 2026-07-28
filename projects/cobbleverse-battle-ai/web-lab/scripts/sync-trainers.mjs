import { mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { strFromU8, unzipSync } from "fflate";
import {
  createCobblemonItemResolver,
  normalizeHeldItem,
} from "../lib/cobblemon-item-catalog.mjs";
import { resolveShowdownMemberSpecies } from "../lib/showdown-species.mjs";

const scriptFile = fileURLToPath(import.meta.url);
const siteRoot = path.resolve(path.dirname(scriptFile), "..");
const repositoryRoot = path.resolve(siteRoot, "..", "..", "..");
const trainerEntriesSource = path.join(repositoryRoot, "trainer-data", "entries");
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

function sourcePath(...parts) {
  return parts
    .flatMap((part) => String(part).replaceAll("\\", "/").split("/"))
    .filter(Boolean)
    .join("/");
}

function trainerIdFromSource(sourceFile) {
  const documentPath = sourceFile.split("!/").at(-1).split("#", 1)[0];
  return path.posix.basename(documentPath, ".json");
}

function sourceGroupFromPath(relativePath, archiveEntry = "") {
  const fileParts = sourcePath(relativePath).split("/");
  if (fileParts.length > 1) return fileParts[0];
  const archiveParts = sourcePath(archiveEntry).split("/");
  return archiveParts.length > 1 ? archiveParts[0] : "ungrouped";
}

async function listTrainerDataFiles(directory, relativeDirectory = "") {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries.sort((left, right) =>
    left.name.localeCompare(right.name, "en"),
  )) {
    const relativePath = sourcePath(relativeDirectory, entry.name);
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await listTrainerDataFiles(absolutePath, relativePath)));
    } else if (/\.(json|zip)$/i.test(entry.name)) {
      files.push({ absolutePath, relativePath });
    }
  }
  return files;
}

function trainerDocumentsFromJson(raw, sourceFile, sourceGroup) {
  const trainers = Array.isArray(raw)
    ? raw
    : Array.isArray(raw?.trainers)
      ? raw.trainers
      : [raw];
  return trainers.map((trainer, index) => {
    if (!trainer || typeof trainer !== "object" || Array.isArray(trainer)) {
      throw new Error(`Invalid trainer JSON object: ${sourceFile}#${index + 1}`);
    }
    return {
      raw: trainer,
      sourceFile:
        trainers.length === 1
          ? sourceFile
          : `${sourceFile}#${String(trainer.id ?? index + 1)}`,
      sourceGroup,
      id: trainer.id ? String(trainer.id) : null,
    };
  });
}

export async function readTrainerDocuments(directory = trainerEntriesSource) {
  const files = await listTrainerDataFiles(directory);
  const documents = [];
  for (const file of files) {
    const group = sourceGroupFromPath(file.relativePath);
    if (file.relativePath.toLowerCase().endsWith(".json")) {
      const raw = JSON.parse(await readFile(file.absolutePath, "utf8"));
      documents.push(
        ...trainerDocumentsFromJson(
          raw,
          sourcePath("entries", file.relativePath),
          group,
        ),
      );
      continue;
    }

    const archive = unzipSync(new Uint8Array(await readFile(file.absolutePath)));
    const archiveEntries = Object.entries(archive)
      .filter(([name]) => name.toLowerCase().endsWith(".json"))
      .sort(([left], [right]) => left.localeCompare(right, "en"));
    for (const [archiveEntry, contents] of archiveEntries) {
      const raw = JSON.parse(strFromU8(contents));
      const archiveGroup =
        group === "ungrouped"
          ? sourceGroupFromPath(file.relativePath, archiveEntry)
          : group;
      documents.push(
        ...trainerDocumentsFromJson(
          raw,
          `${sourcePath("entries", file.relativePath)}!/${sourcePath(archiveEntry)}`,
          archiveGroup,
        ),
      );
    }
  }
  return documents;
}

export function normalizeTrainer(
  raw,
  sourceFile,
  itemResolver = null,
  metadata = {},
) {
  const id = metadata.id ?? trainerIdFromSource(sourceFile);
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
    sourceGroup:
      metadata.sourceGroup ??
      sourceGroupFromPath(sourceFile.replace(/^entries\//, "")),
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
  const documents = await readTrainerDocuments();
  const trainers = documents.map((document) =>
    normalizeTrainer(document.raw, document.sourceFile, itemResolver, {
      id: document.id,
      sourceGroup: document.sourceGroup,
    }),
  );
  const duplicateIds = [...new Set(
    trainers
      .filter(
        (trainer, index) =>
          trainers.findIndex((candidate) => candidate.id === trainer.id) !== index,
      )
      .map((trainer) => trainer.id),
  )];
  if (duplicateIds.length) {
    const details = duplicateIds
      .map(
        (id) =>
          `${id}: ${trainers
            .filter((trainer) => trainer.id === id)
            .map((trainer) => trainer.sourceFile)
            .join(", ")}`,
      )
      .join("; ");
    throw new Error(`Duplicate trainer IDs in trainer-data/entries: ${details}`);
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
    schemaVersion: 3,
    source: "Cobbleverse grouped trainer entries",
    generatedFrom: ["trainer-data/entries"],
    sourceGroups: [...new Set(trainers.map((trainer) => trainer.sourceGroup))].sort(),
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

import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { spawnSync } from "node:child_process";

const projectRoot = new URL("..", import.meta.url).pathname.replace(/^\/([A-Za-z]:\/)/, "$1");
const namedRoots = {
  server: "G:\\2026 MineCraft\\Cobbleverse Server\\Server",
  editor: "G:\\2026 MineCraft\\코블버스\\호연엔트리",
};
const defaultRoots = [namedRoots.server, namedRoots.editor];
const outputPath = join(projectRoot, "public", "data", "pokemon-i18n-ko.json");
const overridePath = join(projectRoot, "trainer-data", "catalogs", "pokemon-i18n-ko-overrides.csv");

const args = process.argv.slice(2);
const roots = args.length
  ? args.map((value) => namedRoots[value] ?? value)
  : defaultRoots;

function dexId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "");
}

function walk(directory, results = []) {
  let entries = [];
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch {
    return results;
  }
  for (const entry of entries) {
    const fullPath = join(directory, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "libraries" || entry.name === "backups") continue;
      walk(fullPath, results);
    } else if (/\.(jar|zip)$/i.test(entry.name)) {
      results.push(fullPath);
    } else if (/ko_kr\.json$/i.test(entry.name)) {
      results.push(fullPath);
    }
  }
  return results;
}

function listArchiveEntries(archive) {
  const result = spawnSync("tar", ["-tf", archive], {
    encoding: "utf8",
    windowsHide: true,
  });
  return result.stdout
    .split(/\r?\n/)
    .filter((line) => /(^|\/)assets\/[^/]+\/lang\/ko_kr\.json$/i.test(line));
}

function readArchiveEntry(archive, entry) {
  const result = spawnSync("tar", ["-xOf", archive, entry], {
    encoding: "utf8",
    windowsHide: true,
  });
  if (!result.stdout.trim()) return null;
  try {
    return JSON.parse(result.stdout);
  } catch {
    return null;
  }
}

function parseLangJson(source, entries, target) {
  for (const [key, value] of Object.entries(entries)) {
    if (typeof value !== "string") continue;

    const ability = key.match(/^cobblemon\.ability\.([^.]+)(?:\.(desc))?$/);
    if (ability) {
      const id = dexId(ability[1]);
      target.abilities[id] ??= {};
      if (ability[2]) target.abilities[id].description = value;
      else target.abilities[id].name = value;
      target.rawKeys.abilities[id] ??= {};
      target.rawKeys.abilities[id][ability[2] ? "description" : "name"] = key;
      continue;
    }

    const move = key.match(/^cobblemon\.move\.([^.]+)(?:\.(desc))?$/);
    if (move) {
      const id = dexId(move[1]);
      target.moves[id] ??= {};
      if (move[2]) target.moves[id].description = value;
      else target.moves[id].name = value;
      target.rawKeys.moves[id] ??= {};
      target.rawKeys.moves[id][move[2] ? "description" : "name"] = key;
      continue;
    }

    const species = key.match(/^cobblemon\.species\.([^.]+)(?:\.(desc))?$/);
    if (species) {
      const id = dexId(species[1]);
      target.species[id] ??= {};
      if (species[2]) target.species[id].description = value;
      else target.species[id].name = value;
      target.rawKeys.species[id] ??= {};
      target.rawKeys.species[id][species[2] ? "description" : "name"] = key;
      continue;
    }

    const item = key.match(/^item\.([a-z0-9_]+)\.([^.]+)(?:\.(desc|tooltip|tooltip\.\d+))?$/);
    if (item) {
      const namespace = item[1];
      const shortId = dexId(item[2]);
      const fullId = `${namespace}:${item[2]}`;
      target.items[shortId] ??= { namespace };
      target.items[fullId] ??= { namespace, shortId };
      const field = item[3] ? "description" : "name";
      for (const id of [shortId, fullId]) {
        if (field === "description" && target.items[id].description) {
          target.items[id].description = joinUniqueText(
            target.items[id].description,
            value,
          );
        } else {
          target.items[id][field] = value;
        }
        target.rawKeys.items[id] ??= {};
        target.rawKeys.items[id][field] = key;
      }
    }
  }
  target.sources[source] = Object.keys(entries).length;
}

function joinUniqueText(left, right) {
  return [...new Set([left, right].filter(Boolean))].join("\n");
}

function ensureOverrideTemplate() {
  if (existsSync(overridePath)) return;
  mkdirSync(dirname(overridePath), { recursive: true });
  const rows = [
    ["kind", "id", "name", "description", "source_note"],
    ["ability", "download", "", "", "수동 보강이 필요할 때만 작성"],
    ["item", "leftovers", "", "턴 종료 시 HP를 조금 회복합니다.", "코블몬 lang에 설명이 없을 때 보강"],
    ["item", "eviolite", "", "진화할 수 있는 포켓몬이 지니면 방어와 특수방어가 올라갑니다.", "코블몬 lang에 설명이 없을 때 보강"],
  ];
  writeFileSync(
    overridePath,
    `${rows.map((row) => row.map(csvCell).join(",")).join("\n")}\n`,
    "utf8",
  );
}

function csvCell(value) {
  const text = String(value ?? "");
  return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function readOverrides() {
  if (!existsSync(overridePath)) return [];
  const text = readFileSync(overridePath, "utf8").replace(/^\uFEFF/, "");
  const rows = [];
  let row = [];
  let cell = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        cell += '"';
        index += 1;
      } else if (char === '"') {
        quoted = false;
      } else {
        cell += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ",") {
      row.push(cell);
      cell = "";
    } else if (char === "\n") {
      row.push(cell.replace(/\r$/, ""));
      rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += char;
    }
  }
  if (cell || row.length) rows.push([...row, cell]);
  const [headers = [], ...body] = rows;
  return body
    .map((values) => Object.fromEntries(headers.map((header, index) => [header, values[index] ?? ""])))
    .filter((entry) => entry.kind && entry.id);
}

function applyOverrides(target) {
  const kindMap = {
    species: "species",
    move: "moves",
    ability: "abilities",
    item: "items",
  };
  for (const entry of readOverrides()) {
    const kind = kindMap[entry.kind];
    if (!kind) continue;
    const id = dexId(entry.id);
    if (!target[kind]?.[id]) target[kind][id] = {};
    if (entry.name) target[kind][id].name = entry.name;
    if (entry.description) target[kind][id].description = entry.description;
    target.rawKeys[kind][id] ??= {};
    target.rawKeys[kind][id].override = "trainer-data/catalogs/pokemon-i18n-ko-overrides.csv";
  }
}

const catalog = {
  schemaVersion: 1,
  locale: "ko-KR",
  generatedAt: new Date().toISOString(),
  sources: {},
  species: {},
  moves: {},
  abilities: {},
  items: {},
  rawKeys: {
    species: {},
    moves: {},
    abilities: {},
    items: {},
  },
};

ensureOverrideTemplate();

for (const root of roots) {
  if (!root || !existsSync(root)) continue;
  for (const file of walk(root)) {
    if (/ko_kr\.json$/i.test(file)) {
      try {
        parseLangJson(file, JSON.parse(readFileSync(file, "utf8")), catalog);
      } catch {
        // Ignore malformed third-party lang files.
      }
      continue;
    }
    for (const entry of listArchiveEntries(file)) {
      const json = readArchiveEntry(file, entry);
      if (json) parseLangJson(`${basename(file)}:${entry}`, json, catalog);
    }
  }
}

applyOverrides(catalog);

if (Object.keys(catalog.sources).length === 0 && existsSync(outputPath)) {
  console.log(
    JSON.stringify(
      {
        output: outputPath,
        skipped: true,
        reason: "No local Cobblemon lang sources were found; kept existing catalog.",
      },
      null,
      2,
    ),
  );
  process.exit(0);
}

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(catalog, null, 2)}\n`, "utf8");

console.log(
  JSON.stringify(
    {
      output: outputPath,
      overrides: overridePath,
      sources: Object.keys(catalog.sources).length,
      species: Object.keys(catalog.species).length,
      moves: Object.keys(catalog.moves).length,
      abilities: Object.keys(catalog.abilities).length,
      items: Object.keys(catalog.items).length,
    },
    null,
    2,
  ),
);

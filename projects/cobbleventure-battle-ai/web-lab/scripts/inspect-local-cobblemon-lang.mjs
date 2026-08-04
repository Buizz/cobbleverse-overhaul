import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join, basename } from "node:path";
import { spawnSync } from "node:child_process";

const defaultRoot = "G:\\2026 MineCraft\\코블버스\\호연엔트리";
const namedRoots = {
  editor: defaultRoot,
  server: "G:\\2026 MineCraft\\Cobbleverse Server\\Server",
};
const root =
  process.argv[2] && namedRoots[process.argv[2]]
    ? namedRoots[process.argv[2]]
    : process.argv[2] && !/^\d+$/.test(process.argv[2])
      ? process.argv[2]
    : defaultRoot;

const maxFiles = Number(
  /^\d+$/.test(process.argv[2] ?? "")
    ? process.argv[2]
    : /^\d+$/.test(process.argv[3] ?? "")
      ? process.argv[3]
      : (process.argv[4] ?? 80),
);
const filter = String(
  /^\d+$/.test(process.argv[2] ?? "")
    ? (process.argv[3] ?? "")
    : /^\d+$/.test(process.argv[3] ?? "")
      ? (process.argv[4] ?? "")
      : (process.argv[5] ?? ""),
).toLowerCase();
const namesOnly = filter === "names";

function walk(directory, results = []) {
  if (results.length >= maxFiles) return results;
  let entries = [];
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch {
    return results;
  }

  for (const entry of entries) {
    if (results.length >= maxFiles) break;
    const fullPath = join(directory, entry.name);
    if (entry.isDirectory()) {
      walk(fullPath, results);
    } else {
      const lower = entry.name.toLowerCase();
      if (
        lower.endsWith(".jar") ||
        lower.endsWith(".zip") ||
        lower === "ko_kr.json" ||
        lower === "en_us.json"
      ) {
        results.push(fullPath);
      }
    }
  }
  return results;
}

function readZipEntry(archive, entryName) {
  const result = spawnSync("tar", ["-xOf", archive, entryName], {
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.status !== 0) return null;
  return result.stdout;
}

function listZipEntries(archive) {
  const result = spawnSync("tar", ["-tf", archive], {
    encoding: "utf8",
    windowsHide: true,
  });
  const entries = result.stdout
    .split(/\r?\n/)
    .filter((line) => /\/lang\/(ko_kr|en_us)\.json$/i.test(line));
  if (result.status !== 0 && entries.length === 0) {
    return { entries: [], error: result.stderr || result.stdout || `status ${result.status}` };
  }
  return {
    entries,
    error: result.status === 0 ? "" : (result.stderr || `status ${result.status}`),
  };
}

function summarizeJson(text) {
  try {
    const json = JSON.parse(text);
    const keys = Object.keys(json);
    const importantKeys = [
      "cobblemon.ability.download",
      "cobblemon.ability.download.desc",
      "cobblemon.ability.intimidate",
      "cobblemon.ability.intimidate.desc",
      "item.cobblemon.leftovers",
      "item.cobblemon.leftovers.desc",
      "item.cobblemon.eviolite",
      "item.cobblemon.eviolite.desc",
      "cobblemon.move.thunderbolt",
      "cobblemon.move.thunderbolt.desc",
    ];
    return {
      total: keys.length,
      pokemon: keys.filter((key) => key.includes("pokemon")).length,
      move: keys.filter((key) => key.includes("move")).length,
      ability: keys.filter((key) => key.includes("ability")).length,
      item: keys.filter((key) => key.includes("item")).length,
      effect: keys.filter((key) => key.includes("effect")).length,
      sample: keys.slice(0, 8).map((key) => [key, json[key]]),
      importantSample: importantKeys
        .filter((key) => json[key])
        .map((key) => [key, json[key]]),
    };
  } catch {
    return null;
  }
}

console.log(`ROOT\t${root}`);
console.log(`EXISTS\t${existsSync(root)}`);
if (!existsSync(root)) process.exit(0);

const candidates = walk(root);
const filteredCandidates = filter
  ? candidates.filter((file) => file.toLowerCase().includes(filter))
  : candidates;
console.log(`CANDIDATES\t${candidates.length}`);
console.log(`FILTERED\t${filteredCandidates.length}\t${filter || "*"}`);

if (namesOnly) {
  for (const file of candidates) console.log(`CANDIDATE\t${file}`);
  process.exit(0);
}

for (const file of filteredCandidates) {
  const lower = file.toLowerCase();
  if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
    const { entries, error } = listZipEntries(file);
    const langEntries = entries.filter((entry) => entry.endsWith("/ko_kr.json"));
    console.log(`ARCHIVE\t${file}`);
    if (entries.length === 0) {
      console.log(`NO_LANG_ENTRIES\t${error || "no matching lang files"}`);
      continue;
    }
    console.log(`LANG_ENTRIES\t${entries.join(" | ")}`);
    for (const entry of langEntries) {
      const text = readZipEntry(file, entry);
      const summary = text ? summarizeJson(text) : null;
      console.log(`KO_SUMMARY\t${entry}\t${JSON.stringify(summary)}`);
    }
  } else if (basename(lower) === "ko_kr.json" || basename(lower) === "en_us.json") {
    const summary = summarizeJson(readFileSync(file, "utf8"));
    console.log(`LANG_FILE\t${file}\t${JSON.stringify(summary)}`);
  }
}

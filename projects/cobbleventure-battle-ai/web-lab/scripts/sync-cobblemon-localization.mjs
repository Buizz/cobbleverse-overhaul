import { execFileSync } from "node:child_process";
import { basename, resolve } from "node:path";
import { mkdir, writeFile } from "node:fs/promises";

const jarPath = process.argv[2] || process.env.COBBLEMON_JAR;

if (!jarPath) {
  console.error(
    "Usage: node scripts/sync-cobblemon-localization.mjs <Cobblemon JAR path>",
  );
  process.exit(1);
}

const languagePath = "assets/cobblemon/lang/ko_kr.json";
const moveCategoriesPath = "assets/cobblemon/textures/gui/categories.png";
const rawLanguage = execFileSync(
  "tar",
  ["-xOf", resolve(jarPath), languagePath],
  {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  },
);
const language = JSON.parse(rawLanguage);
const species = {};
const moves = {};

for (const [key, value] of Object.entries(language)) {
  const speciesMatch = key.match(
    /^cobblemon\.species\.([^.]+)\.(name|desc)$/,
  );
  if (speciesMatch) {
    const [, id, field] = speciesMatch;
    species[id] ??= {};
    species[id][field === "name" ? "name" : "description"] = value;
    continue;
  }

  const moveMatch = key.match(/^cobblemon\.move\.([^.]+)(?:\.(desc))?$/);
  if (moveMatch && moveMatch[1] !== "category") {
    const [, id, descriptionField] = moveMatch;
    moves[id] ??= {};
    moves[id][descriptionField ? "description" : "name"] = value;
  }
}

const sortEntries = (entries) =>
  Object.fromEntries(
    Object.entries(entries).sort(([left], [right]) =>
      left.localeCompare(right),
    ),
  );

const jarEntries = execFileSync("tar", ["-tf", resolve(jarPath)], {
  encoding: "utf8",
  maxBuffer: 32 * 1024 * 1024,
})
  .split(/\r?\n/)
  .filter((entry) =>
    /^assets\/cobblemon\/textures\/item\/type_gem\/[a-z]+_gem\.png$/.test(
      entry,
    ),
  );
const typeIconDirectory = resolve("public/assets/cobblemon/type-icons");
const guiAssetDirectory = resolve("public/assets/cobblemon/gui");
const typeIcons = [];

await mkdir(typeIconDirectory, { recursive: true });
await mkdir(guiAssetDirectory, { recursive: true });
for (const entry of jarEntries) {
  const type = basename(entry).replace(/_gem\.png$/, "");
  const image = execFileSync("tar", ["-xOf", resolve(jarPath), entry], {
    maxBuffer: 1024 * 1024,
  });
  await writeFile(resolve(typeIconDirectory, `${type}.png`), image);
  typeIcons.push(type);
}

const moveCategoriesImage = execFileSync(
  "tar",
  ["-xOf", resolve(jarPath), moveCategoriesPath],
  { maxBuffer: 1024 * 1024 },
);
await writeFile(
  resolve(guiAssetDirectory, "move-categories.png"),
  moveCategoriesImage,
);

const catalog = {
  schemaVersion: 1,
  locale: "ko-KR",
  source: basename(jarPath),
  generatedAt: new Date().toISOString(),
  typeIcons: typeIcons.sort(),
  species: sortEntries(species),
  moves: sortEntries(moves),
};
const outputPath = resolve("public/data/cobblemon-ko-kr.json");

await writeFile(outputPath, `${JSON.stringify(catalog, null, 2)}\n`, "utf8");
console.log(
  `Synced ${Object.keys(species).length} species, ${Object.keys(moves).length} moves, and ${typeIcons.length} type icons from ${catalog.source}.`,
);

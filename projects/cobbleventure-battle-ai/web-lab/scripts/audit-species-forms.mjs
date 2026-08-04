import { readFile } from "node:fs/promises";
import { Dex } from "@pkmn/sim";
import { resolveShowdownMemberSpecies } from "../lib/showdown-species.mjs";

const trainerIndex = JSON.parse(
  await readFile(new URL("../public/data/trainers.json", import.meta.url), "utf8"),
);
const uniqueMembers = new Map();
for (const trainer of trainerIndex.trainers) {
  for (const member of trainer.team) {
    const source = String(member.species ?? "").trim();
    if (!source) continue;
    const aspects = Array.isArray(member.aspects) ? member.aspects : [];
    uniqueMembers.set(`${source}|${aspects.join("|")}`, member);
  }
}

const records = [...uniqueMembers.values()].map((member) => {
  const species = resolveShowdownMemberSpecies(member);
  return {
    source: member.species,
    aspects: member.aspects,
    recognized: species.exists,
    showdownId: species.showdownId,
    name: species.showdownName,
    baseSpecies: species.exists ? species.baseSpecies : null,
    forme: species.exists ? species.forme : null,
    spriteId: species.spriteId,
  };
});
const forms = records.filter(
  (record) =>
    record.recognized &&
    (record.forme || record.name !== record.baseSpecies),
);
const unresolvedAspectSets = records.filter(
  (record) =>
    record.recognized &&
    record.aspects.length > 0 &&
    !record.forme &&
    record.name === record.baseSpecies,
);
const unknown = records.filter((record) => !record.recognized);
const showdownForms = Dex.species
  .all()
  .filter((species) => species.exists && species.forme);
const missingSpriteIds = showdownForms
  .filter((species) => !species.spriteid)
  .map((species) => species.name);

console.log(
  JSON.stringify(
    {
      summary: {
        uniqueSpeciesAndAspectSets: records.length,
        recognized: records.length - unknown.length,
        forms: forms.length,
        unresolvedAspectSets: unresolvedAspectSets.length,
        unknown: unknown.length,
        showdownCatalogForms: showdownForms.length,
        showdownFormsWithoutSpriteId: missingSpriteIds.length,
      },
      forms,
      unresolvedAspectSets,
      unknown,
      missingSpriteIds,
    },
    null,
    2,
  ),
);

if (unknown.length > 0) process.exitCode = 1;

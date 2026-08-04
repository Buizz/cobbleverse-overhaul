import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

import { isSimpleAbilitySupported } from "../lib/cobbleventure-battle-engine.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const webLabDirectory = path.resolve(scriptDirectory, "..");
const projectDirectory = path.resolve(webLabDirectory, "..");
const trainerPath = path.join(
  webLabDirectory,
  "public",
  "data",
  "trainers.json",
);
const jsonOutputPath = path.join(
  webLabDirectory,
  "public",
  "data",
  "native-ability-coverage.json",
);
const markdownOutputPath = path.join(
  projectDirectory,
  "docs",
  "ABILITY_COVERAGE_REPORT.md",
);

function cleanId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/^.*:/, "")
    .replace(/[^a-z0-9]+/g, "");
}

function escapeMarkdown(value) {
  return String(value ?? "").replaceAll("|", "\\|");
}

const trainerCatalog = JSON.parse(await readFile(trainerPath, "utf8"));
const usageByAbility = new Map();

for (const trainer of trainerCatalog.trainers ?? []) {
  for (const member of trainer.team ?? []) {
    const ability = cleanId(member.ability);
    if (!ability) continue;
    const usage = usageByAbility.get(ability) ?? {
      id: ability,
      usageCount: 0,
      examples: [],
    };
    usage.usageCount += 1;
    if (usage.examples.length < 3) {
      usage.examples.push({
        pokemon: member.resolvedSpecies ?? member.species,
        trainer: trainer.name,
        sourceGroup: trainer.sourceGroup,
      });
    }
    usageByAbility.set(ability, usage);
  }
}

const abilities = [...usageByAbility.values()]
  .map((ability) => ({
    ...ability,
    status: isSimpleAbilitySupported(ability.id)
      ? "SUPPORTED"
      : "UNSUPPORTED",
  }))
  .sort(
    (left, right) =>
      right.usageCount - left.usageCount || left.id.localeCompare(right.id),
  );
const unsupported = abilities.filter(
  (ability) => ability.status === "UNSUPPORTED",
);
const report = {
  schemaVersion: 1,
  generatedFrom: "public/data/trainers.json",
  trainerCount: trainerCatalog.trainerCount ?? trainerCatalog.trainers?.length ?? 0,
  uniqueAbilityCount: abilities.length,
  supportedAbilityCount: abilities.length - unsupported.length,
  unsupportedAbilityCount: unsupported.length,
  abilities,
};

const lines = [
  "# 자체 엔진 특성 커버리지",
  "",
  "> 이 문서는 `npm run audit:abilities`로 생성한다. 특성 구현 여부를 확인할 때",
  "> 엔진 코드를 직접 훑기 전에 이 문서와 `ABILITY_IMPLEMENTATION_CHECKLIST.md`를 본다.",
  "",
  `- 트레이너 수: ${report.trainerCount}`,
  `- 실제 사용 고유 특성: ${report.uniqueAbilityCount}`,
  `- 지원: ${report.supportedAbilityCount}`,
  `- 미지원: ${report.unsupportedAbilityCount}`,
  "",
  "## 미지원 특성",
  "",
  "| 우선순위 | ID | 사용 수 | 사용 예시 |",
  "|---:|---|---:|---|",
  ...unsupported.map(
    (ability, index) =>
      `| ${index + 1} | \`${ability.id}\` | ${ability.usageCount} | ${escapeMarkdown(
        ability.examples
          .map((example) => `${example.pokemon} (${example.trainer})`)
          .join(", "),
      )} |`,
  ),
  "",
  "## 지원 특성",
  "",
  "| ID | 사용 수 |",
  "|---|---:|",
  ...abilities
    .filter((ability) => ability.status === "SUPPORTED")
    .map((ability) => `| \`${ability.id}\` | ${ability.usageCount} |`),
  "",
];

await writeFile(jsonOutputPath, `${JSON.stringify(report, null, 2)}\n`);
await writeFile(markdownOutputPath, `${lines.join("\n")}\n`);

console.log(
  `Ability coverage: ${report.supportedAbilityCount} supported, ${report.unsupportedAbilityCount} unsupported`,
);

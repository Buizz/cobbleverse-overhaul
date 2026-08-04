import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

import { Dex } from "@pkmn/sim";
import {
  SUPPORTED_DYNAMIC_POWER_MOVES,
  SUPPORTED_MOVE_CALLBACKS,
  SUPPORTED_MOVE_REQUIREMENTS,
} from "../lib/native-dynamic-power.mjs";

const root = fileURLToPath(new URL("..", import.meta.url));
const trainerPath = fileURLToPath(
  new URL("../public/data/trainers.json", import.meta.url),
);
const outputPath = fileURLToPath(
  new URL("../public/data/native-mechanics-coverage.json", import.meta.url),
);

const FEATURE_SUPPORT = {
  weather: true,
  terrain: true,
  pseudoWeather: true,
  sideCondition: true,
  multihit: true,
  multiaccuracy: true,
  willCrit: true,
  volatileStatus: false,
  slotCondition: false,
  selfSwitch: false,
  forceSwitch: false,
  ohko: false,
  fixedDamage: false,
  dynamicDamage: false,
  dynamicPower: false,
};

const IGNORED_CALLBACKS = new Set([
  "onBasePowerPriority",
  "onDamagePriority",
  "onModifyMovePriority",
  "onModifyTypePriority",
  "onTryHitPriority",
]);

function moveRequirements(move) {
  const requirements = [];
  for (const key of [
    "weather",
    "terrain",
    "pseudoWeather",
    "sideCondition",
    "volatileStatus",
    "slotCondition",
    "multihit",
    "multiaccuracy",
    "willCrit",
    "selfSwitch",
    "forceSwitch",
    "ohko",
  ]) {
    if (move[key]) requirements.push(key);
  }
  if (move.damage) requirements.push("fixedDamage");
  if (move.damageCallback) requirements.push("dynamicDamage");
  if (move.basePowerCallback) requirements.push("dynamicPower");
  return [...new Set(requirements)];
}

function callbackNames(move) {
  const supported = SUPPORTED_MOVE_CALLBACKS.get(move.id) ?? new Set();
  return Object.entries(move)
    .filter(
      ([key, value]) =>
        typeof value === "function" &&
        !["basePowerCallback", "damageCallback"].includes(key) &&
        !IGNORED_CALLBACKS.has(key) &&
        !supported.has(key),
    )
    .map(([key]) => key)
    .sort();
}

function requirementIsSupported(requirement, moveId) {
  if (SUPPORTED_MOVE_REQUIREMENTS.get(moveId)?.has(requirement)) {
    return true;
  }
  if (requirement === "dynamicPower") {
    return SUPPORTED_DYNAMIC_POWER_MOVES.has(moveId);
  }
  return FEATURE_SUPPORT[requirement];
}

function supportStatus(requirements, callbacks, moveId) {
  const supported = requirements.filter((requirement) =>
    requirementIsSupported(requirement, moveId),
  );
  const unsupported = requirements.filter(
    (requirement) => !requirementIsSupported(requirement, moveId),
  );
  if (callbacks.length > 0) unsupported.push("customCallback");
  if (unsupported.length === 0) return "SUPPORTED";
  if (supported.length > 0) return "PARTIAL";
  return "UNSUPPORTED";
}

const catalog = JSON.parse(await readFile(trainerPath, "utf8"));
const trainerMoveUsage = new Map();
for (const trainer of catalog.trainers) {
  for (const pokemon of trainer.team) {
    for (const moveId of pokemon.moveset ?? []) {
      const canonicalId = Dex.moves.get(moveId).id || moveId;
      trainerMoveUsage.set(
        canonicalId,
        (trainerMoveUsage.get(canonicalId) ?? 0) + 1,
      );
    }
  }
}
const moves = [
  ...new Map(
    Dex.moves
      .all()
      .filter((move) => move.exists)
      .map((move) => [move.id, move]),
  ).values(),
]
  .sort((left, right) => left.id.localeCompare(right.id))
  .map((move) => {
  const requirements = moveRequirements(move);
  const callbacks = callbackNames(move);
  return {
    id: move.id,
    name: move.name || move.id,
    exists: move.exists,
    availability: move.isNonstandard ?? "Standard",
    usedByTrainers: trainerMoveUsage.has(move.id),
    trainerUsageCount: trainerMoveUsage.get(move.id) ?? 0,
    requirements,
    callbacks,
    status: supportStatus(requirements, callbacks, move.id),
  };
  });

const statusCounts = Object.fromEntries(
  ["SUPPORTED", "PARTIAL", "UNSUPPORTED", "UNKNOWN"].map((status) => [
    status,
    moves.filter((move) => move.status === status).length,
  ]),
);
const trainerMoves = moves.filter((move) => move.usedByTrainers);
const trainerStatusCounts = Object.fromEntries(
  ["SUPPORTED", "PARTIAL", "UNSUPPORTED", "UNKNOWN"].map((status) => [
    status,
    trainerMoves.filter((move) => move.status === status).length,
  ]),
);
const requirementCounts = Object.fromEntries(
  Object.keys(FEATURE_SUPPORT).map((requirement) => [
    requirement,
    moves.filter((move) => move.requirements.includes(requirement)).length,
  ]),
);
const availabilityCounts = Object.fromEntries(
  [...new Set(moves.map((move) => move.availability))].map((availability) => [
    availability,
    moves.filter((move) => move.availability === availability).length,
  ]),
);
const report = {
  schemaVersion: 1,
  catalogPolicy:
    "Audit every move exposed by the pinned Pokemon Showdown Dex; keep availability and trainer usage as separate filters.",
  sourceReferences: [
    {
      id: "showdown-dex",
      url: "https://github.com/smogon/pokemon-showdown",
      role: "machine-readable full move catalog and behavior metadata",
    },
    {
      id: "smogon-champions-moves",
      url: "https://www.smogon.com/dex/champions/moves/",
      role: "human-readable Pokemon Champions cross-reference",
    },
  ],
  generatedFrom: trainerPath.slice(root.length).replaceAll("\\", "/"),
  trainerCount: catalog.trainers.length,
  totalMoveCount: moves.length,
  currentStandardMoveCount: moves.filter(
    (move) => move.availability === "Standard",
  ).length,
  trainerUsedMoveCount: trainerMoves.length,
  availabilityCounts,
  statusCounts,
  trainerStatusCounts,
  requirementCounts,
  featureSupport: FEATURE_SUPPORT,
  moves,
};

await writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
console.log(
  JSON.stringify(
    {
      trainerCount: report.trainerCount,
      totalMoveCount: report.totalMoveCount,
      currentStandardMoveCount: report.currentStandardMoveCount,
      trainerUsedMoveCount: report.trainerUsedMoveCount,
      availabilityCounts,
      statusCounts,
      trainerStatusCounts,
      requirementCounts,
      output: outputPath,
    },
    null,
    2,
  ),
);

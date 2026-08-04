import { createRequire } from "node:module";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

const require = createRequire(new URL("../web-lab/package.json", import.meta.url));
const { Dex } = require("@pkmn/sim");

const projectRoot = new URL("..", import.meta.url).pathname.replace(/^\/([A-Za-z]:\/)/, "$1");
const i18nPath = join(projectRoot, "data", "i18n", "pokemon-i18n-ko.json");
const csvOutputPath = join(
  projectRoot,
  "data",
  "ai",
  "ai-move-role-classification.csv",
);
const jsonOutputPath = join(
  projectRoot,
  "data",
  "ai",
  "ai-move-role-classification.json",
);

const i18n = JSON.parse(readFileSync(i18nPath, "utf8"));

const ROLE_COLUMNS = [
  "lead",
  "ace",
  "setupSweeper",
  "wall",
  "pivot",
  "hazardControl",
  "revengeKiller",
  "disruptor",
  "support",
];

const MOVE_TAGS = {
  ACE_DAMAGE: "에이스 주력 공격",
  ACE_COVERAGE: "에이스 견제폭",
  SETUP_BOOST: "랭크업/전개",
  HAZARD_SET: "판 설치",
  HAZARD_REMOVE: "판 제거",
  PIVOT: "피벗/교체 연결",
  RECOVERY: "회복",
  STATUS_SPREAD: "상태이상",
  SPEED_CONTROL: "속도 제어",
  DISRUPT: "방해",
  FORCE_SWITCH: "강제 교체",
  PROTECT_SCOUT: "방어/정찰",
  FIELD_CONTROL: "날씨/필드/룸",
  SCREEN_SUPPORT: "벽/장막",
  TEAM_SUPPORT: "팀 지원",
  PRIORITY: "선공기",
  FINISHER: "마무리",
  RISKY_NUKE: "고위험 고화력",
  LOCK_IN: "강제 연속/구속",
};

function clampScore(value) {
  return Math.max(-5, Math.min(5, Math.round(value * 10) / 10));
}

function localizedMove(id, move) {
  const entry = i18n.moves?.[id] ?? {};
  return {
    name: entry.name || move.name,
    description: entry.description || move.shortDesc || move.desc || "",
  };
}

function addTag(tags, tag) {
  if (MOVE_TAGS[tag]) tags.add(tag);
}

function classifyMove(move) {
  const tags = new Set();
  const reasons = [];
  const scores = Object.fromEntries(ROLE_COLUMNS.map((role) => [role, 0]));
  const desc = `${move.shortDesc ?? ""} ${move.desc ?? ""}`.toLowerCase();
  const boosts = move.boosts ?? {};
  const selfBoosts = move.self?.boosts ?? {};
  const hasPositiveSelfBoost = Object.values(selfBoosts).some((value) => Number(value) > 0);
  const hasNegativeTargetBoost = Object.values(boosts).some((value) => Number(value) < 0);

  if (move.category !== "Status" && move.basePower > 0) {
    addTag(tags, "ACE_DAMAGE");
    scores.ace += Math.min(2.2, move.basePower / 60);
    scores.setupSweeper += Math.min(1.5, move.basePower / 80);
    reasons.push("공격 기술이라 에이스/스위퍼의 직접 피해 후보입니다.");
    if (move.basePower >= 100) {
      addTag(tags, "RISKY_NUKE");
      scores.ace += 0.7;
      reasons.push("고위력 기술이라 돌파 가치가 높습니다.");
    }
    if (move.type && move.type !== "Normal") {
      addTag(tags, "ACE_COVERAGE");
      scores.ace += 0.3;
    }
  }

  if (move.priority > 0) {
    addTag(tags, "PRIORITY");
    addTag(tags, "FINISHER");
    scores.revengeKiller += 2.8 + move.priority * 0.4;
    scores.ace += 0.8;
    reasons.push("선공기라 복수 처리와 마무리 가치가 큽니다.");
  }

  if (move.id === "stealthrock" || move.sideCondition === "stealthrock") {
    addTag(tags, "HAZARD_SET");
    scores.lead += 3.5;
    scores.hazardControl += 4.5;
    reasons.push("스텔스록 설치 기술입니다.");
  }
  if (
    ["spikes", "toxicspikes", "stickyweb", "ceaselessedge", "stoneaxe"].includes(move.id) ||
    ["spikes", "toxicspikes", "stickyweb"].includes(move.sideCondition)
  ) {
    addTag(tags, "HAZARD_SET");
    scores.lead += 3;
    scores.hazardControl += 4;
    reasons.push("설치물로 교체 누적 피해/압박을 만듭니다.");
  }

  if (
    ["rapidspin", "defog", "tidyup", "mortalspin", "courtchange"].includes(move.id) ||
    desc.includes("hazard") ||
    desc.includes("entry hazard")
  ) {
    addTag(tags, "HAZARD_REMOVE");
    scores.hazardControl += 4.5;
    scores.support += 2;
    scores.wall += 0.8;
    reasons.push("설치물 제거 또는 필드 정리 가치가 있습니다.");
  }

  if (move.selfSwitch || ["uturn", "voltswitch", "flipturn", "partingshot", "teleport", "batonpass", "chillyreception"].includes(move.id)) {
    addTag(tags, "PIVOT");
    scores.pivot += 4.5;
    scores.lead += 1.5;
    scores.ace += 0.4;
    reasons.push("공격/보조 후 유리 대면으로 연결하는 피벗 기술입니다.");
  }

  if (move.heal || ["recover", "roost", "softboiled", "milkdrink", "slackoff", "synthesis", "moonlight", "morningsun", "shoreup", "wish", "lifedew", "healorder", "rest"].includes(move.id)) {
    addTag(tags, "RECOVERY");
    scores.wall += 4;
    scores.support += 1.5;
    scores.ace -= 0.4;
    reasons.push("체력 회복으로 막이/장기전 자원 가치가 높습니다.");
  }

  if (move.status || ["willowisp", "toxic", "thunderwave", "spore", "sleeppowder", "glare", "stunspore", "yawn", "nuzzle"].includes(move.id)) {
    addTag(tags, "STATUS_SPREAD");
    scores.wall += 2.2;
    scores.disruptor += 2.4;
    scores.support += 1.8;
    reasons.push("상태이상으로 상대 기능을 낮추거나 교체를 압박합니다.");
  }

  if (
    hasPositiveSelfBoost ||
    ["dragondance", "swordsdance", "nastyplot", "calmmind", "bulkup", "quiverdance", "shellsmash", "coil", "curse", "workup", "growth", "victorydance"].includes(move.id)
  ) {
    addTag(tags, "SETUP_BOOST");
    scores.setupSweeper += 4;
    scores.ace += 2;
    scores.wall += move.id === "calmmind" || move.id === "bulkup" || move.id === "curse" ? 1 : 0;
    reasons.push("랭크업으로 에이스 전개 또는 장기 돌파 조건을 만듭니다.");
  }

  if (hasNegativeTargetBoost) {
    addTag(tags, "DISRUPT");
    scores.disruptor += 1.8;
    scores.wall += 0.8;
    reasons.push("상대 능력치를 낮춰 현재 대면 또는 후속 대면을 안정화합니다.");
  }

  if (["taunt", "encore", "disable", "torment", "trick", "switcheroo", "haze", "clearsmog", "topsyturvy", "imprison"].includes(move.id)) {
    addTag(tags, "DISRUPT");
    scores.disruptor += 4;
    scores.lead += 1.2;
    scores.wall += 1;
    reasons.push("상대 전개/회복/보조 기술을 방해하는 핵심 기술입니다.");
  }

  if (["roar", "whirlwind", "dragontail", "circlethrow"].includes(move.id) || move.forceSwitch) {
    addTag(tags, "FORCE_SWITCH");
    scores.disruptor += 3.8;
    scores.wall += 2;
    scores.hazardControl += 1.5;
    reasons.push("강제 교체로 랭크업을 끊고 설치물 피해를 누적시킵니다.");
  }

  if (["protect", "detect", "spikyshield", "kingsshield", "banefulbunker", "silktrap", "wideguard", "quickguard"].includes(move.id)) {
    addTag(tags, "PROTECT_SCOUT");
    scores.wall += 2;
    scores.support += 1.4;
    scores.disruptor += 0.8;
    reasons.push("방어/정찰로 턴 종료 효과와 상대 행동 확인 가치가 있습니다.");
  }

  if (
    move.weather ||
    move.terrain ||
    move.pseudoWeather ||
    ["trickroom", "wonderroom", "magicroom", "tailwind", "raindance", "sunnyday", "sandstorm", "snowscape", "electricterrain", "grassyterrain", "mistyterrain", "psychicterrain"].includes(move.id)
  ) {
    addTag(tags, "FIELD_CONTROL");
    scores.support += 3;
    scores.lead += 1.8;
    scores.hazardControl += 0.8;
    reasons.push("날씨/필드/룸/순풍 등 전장 조건을 바꿉니다.");
  }

  if (["reflect", "lightscreen", "auroraveil", "safeguard", "mist"].includes(move.id)) {
    addTag(tags, "SCREEN_SUPPORT");
    scores.support += 3.5;
    scores.lead += 2.2;
    scores.wall += 1.5;
    reasons.push("팀 전체 생존 또는 상태 방어를 지원합니다.");
  }

  if (["helpinghand", "followme", "ragepowder", "allyswitch", "healbell", "aromatherapy", "wish", "lifedew"].includes(move.id)) {
    addTag(tags, "TEAM_SUPPORT");
    scores.support += 3.5;
    scores.wall += 1;
    reasons.push("아군 지원 가치가 높은 기술입니다.");
  }

  if (
    ["icywind", "electroweb", "stringshot", "scaryface", "tailwind", "thunderwave", "glare", "rocktomb", "bulldoze"].includes(move.id) ||
    boosts.spe < 0 ||
    selfBoosts.spe > 0
  ) {
    addTag(tags, "SPEED_CONTROL");
    scores.support += 2.3;
    scores.disruptor += 1.8;
    scores.revengeKiller += 1.2;
    reasons.push("속도 제어로 선공권과 마무리 구도를 만듭니다.");
  }

  if (["rollout", "iceball", "outrage", "thrash", "petaldance", "ragingfury"].includes(move.id)) {
    addTag(tags, "LOCK_IN");
    scores.ace += 0.5;
    scores.wall -= 1;
    reasons.push("연속 사용/행동 고정 리스크가 있어 안정성 평가는 낮게 봅니다.");
  }

  if (move.category === "Status" && tags.size === 0) {
    scores.support += 0.8;
    reasons.push("변화기이므로 세부 효과 미분류 상태에서는 지원 후보로 보류합니다.");
  }

  for (const role of ROLE_COLUMNS) scores[role] = clampScore(scores[role]);

  return {
    tags: [...tags],
    tagLabels: [...tags].map((tag) => MOVE_TAGS[tag]),
    scores,
    reasons,
  };
}

function csvCell(value) {
  const text = Array.isArray(value)
    ? value.join("|")
    : typeof value === "object" && value !== null
      ? JSON.stringify(value)
      : String(value ?? "");
  return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

const moveIds = new Set([
  ...Object.keys(i18n.moves ?? {}),
  ...Dex.moves
    .all()
    .filter((move) => move.exists && !["CAP", "LGPE"].includes(move.isNonstandard))
    .map((move) => move.id),
]);

const rows = [];
const jsonMoves = {};

for (const id of [...moveIds].sort()) {
  const move = Dex.moves.get(id);
  if (!move.exists || move.isNonstandard === "CAP") continue;
  const localized = localizedMove(id, move);
  const classification = classifyMove(move);
  const row = {
    id: move.id,
    nameKo: localized.name,
    nameEn: move.name,
    type: move.type,
    category: move.category,
    power: move.basePower || "",
    accuracy: move.accuracy === true ? "필중" : move.accuracy || "",
    priority: move.priority || 0,
    target: move.target,
    tags: classification.tags.join("|"),
    tagLabels: classification.tagLabels.join("|"),
    ...classification.scores,
    descriptionKo: localized.description,
    reason: classification.reasons.join(" / "),
  };
  rows.push(row);
  jsonMoves[move.id] = {
    id: move.id,
    nameKo: localized.name,
    nameEn: move.name,
    type: move.type,
    category: move.category,
    power: move.basePower,
    accuracy: move.accuracy,
    priority: move.priority,
    target: move.target,
    tags: classification.tags,
    tagLabels: classification.tagLabels,
    roleScores: classification.scores,
    descriptionKo: localized.description,
    reasons: classification.reasons,
  };
}

const headers = [
  "id",
  "nameKo",
  "nameEn",
  "type",
  "category",
  "power",
  "accuracy",
  "priority",
  "target",
  "tags",
  "tagLabels",
  ...ROLE_COLUMNS,
  "descriptionKo",
  "reason",
];

mkdirSync(dirname(csvOutputPath), { recursive: true });
mkdirSync(dirname(jsonOutputPath), { recursive: true });

writeFileSync(
  csvOutputPath,
  `${headers.join(",")}\n${rows
    .map((row) => headers.map((header) => csvCell(row[header])).join(","))
    .join("\n")}\n`,
  "utf8",
);

const jsonText = `${JSON.stringify(
  {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    source: "projects/cobbleventure-battle-ai/data/ai/ai-move-role-classification.csv",
    roles: ROLE_COLUMNS,
    tags: MOVE_TAGS,
    count: Object.keys(jsonMoves).length,
    moves: jsonMoves,
  },
  null,
  2,
)}\n`;

writeFileSync(jsonOutputPath, jsonText, "utf8");

console.log(
  JSON.stringify(
    {
      csv: csvOutputPath,
      json: jsonOutputPath,
      moves: rows.length,
    },
    null,
    2,
  ),
);

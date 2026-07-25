"use client";

/* eslint-disable @next/next/no-img-element */

import { type FormEvent, useEffect, useMemo, useRef, useState } from "react";
import {
  BATTLE_STATUSES,
  healthFromCondition,
  normalizeBattleStatus,
  statusByPokemon,
  statusFromCondition,
} from "../lib/battle-status.mjs";
import { localizedSpeciesName } from "../lib/species-localization.mjs";

type BattleMode = "pve" | "eve";
type PartySource = "custom" | "preset";

type Pokemon = {
  slot: number;
  species: string;
  resolvedSpecies?: string;
  level: number;
  gender: string | null;
  nature: string | null;
  ability: string | null;
  heldItem: string | null;
  aspects?: string[];
  gimmicks?: {
    mega?: boolean;
    dynamax?: boolean;
    gmax?: boolean;
    tera?: string | null;
  };
  moveset: string[];
  ivs?: Record<string, number>;
  evs?: Record<string, number>;
};

type Trainer = {
  id: string;
  sourceFile: string;
  name: string;
  entry: {
    type: "official-player" | "computer" | string;
    priority: number;
    label: string | null;
    source: string | null;
    owner: string | null;
    snapshotDate: string | null;
  };
  team: Pokemon[];
};

type TrainerIndex = {
  schemaVersion: number;
  source: string;
  trainerCount: number;
  trainers: Trainer[];
};

type LocalizationEntry = {
  name?: string;
  description?: string;
};

type LocalizationCatalog = {
  schemaVersion: number;
  locale: "ko-KR";
  source: string;
  species: Record<string, LocalizationEntry>;
  moves: Record<string, LocalizationEntry>;
};

type SharedI18nCatalog = {
  schemaVersion: number;
  locale: "ko-KR";
  species: Record<string, LocalizationEntry>;
  moves: Record<string, LocalizationEntry>;
  abilities: Record<string, LocalizationEntry>;
  items: Record<string, LocalizationEntry & { shortId?: string }>;
};

type CatalogSpecies = {
  id: string;
  name: string;
  englishName: string;
  description: string;
  number: number;
  generation: number;
  types: string[];
  baseStats: Record<string, number>;
  abilities: string[];
};

type CatalogMove = {
  id: string;
  name: string;
  englishName: string;
  description: string;
  type: string;
  category: "Physical" | "Special" | "Status";
  power: number;
  accuracy: number | true;
  pp: number;
  priority: number;
  target: string;
};

type CatalogAbility = {
  id: string;
  name: string;
  englishName?: string;
  description: string;
  generation: number;
};

type CatalogItem = {
  id: string;
  shortId: string;
  name: string;
  englishName: string;
  description: string;
  namespace: string;
  category: string;
  battleUsable: boolean;
};

type BattleCatalog = {
  schemaVersion: number;
  species: CatalogSpecies[];
  moves: CatalogMove[];
  abilities: CatalogAbility[];
  items: CatalogItem[];
};

type CatalogChoice =
  | CatalogSpecies
  | CatalogMove
  | CatalogAbility
  | CatalogItem;

function isCatalogSpecies(entry: CatalogChoice): entry is CatalogSpecies {
  return "baseStats" in entry && "number" in entry;
}

function isCatalogMove(entry: CatalogChoice): entry is CatalogMove {
  return "power" in entry && "category" in entry;
}

type LocalWorkspaceSettings = {
  workspacePath: string;
  modsPath: string;
  cobblemonJar: string;
  cobblemonVersion: string;
  modCount: number;
  savedAt: string;
};

type LocalWorkspaceResponse = {
  ok: boolean;
  configured?: boolean;
  settings?: LocalWorkspaceSettings;
  previousPath?: string;
  message?: string;
};

type ScenarioIssue = {
  path: string;
  code: string;
  message: string;
};

type LevelMode = "original" | "level-50" | "level-100";
type BattleEngineChoice = "showdown" | "cobbleverse";
type BattleType = "single" | "double" | "triple";
type BattleGimmickRules = "gen8" | "gen9" | "all";
type AiDifficulty = "novice" | "standard" | "advanced" | "expert" | "cheater";
type AiStrategy = "balanced" | "aggressive" | "defensive" | "unpredictable";
type AiProfile = { difficulty: AiDifficulty; strategy: AiStrategy };

type BattleScenario = {
  scenarioId: string;
  schemaVersion: number;
  mode: BattleMode;
  seed: number;
  levelMode: LevelMode;
  battleType: BattleType;
  battleEngine: BattleEngineChoice;
  gimmickRules: BattleGimmickRules;
  aiDifficulty: AiDifficulty;
  aiProfiles: AiProfile[];
  sides: Array<{
    source: PartySource;
    trainerId: string | null;
    name: string;
    team: Pokemon[];
  }>;
};

type ScenarioResponse =
  | { ok: true; scenario: BattleScenario }
  | { ok: false; issues: ScenarioIssue[] };

type BattleEvent = {
  turn: number;
  type: string;
  label?: string;
  actor?: string;
  detail?: string;
  target?: string;
  condition?: string;
  source?: string;
  sourceActor?: string;
};

type BattlePlaybackMode = "instant" | "fast" | "normal";
type BattleGimmick = "mega" | "zmove" | "dynamax" | "terastallize";

type BattleActionNotice = {
  event: BattleEvent;
  step: number;
  total: number;
  events?: BattleEvent[];
};

type BattleHpPreview = {
  p1?: string;
  p2?: string;
};

type BattleResult = {
  battleId: string;
  scenarioId: string;
  engine?: {
    id: string;
    version: string;
    format: string;
    controller: string;
  };
  settings?: {
    battleEngine: BattleEngineChoice;
    aiDifficulty: AiDifficulty;
    battleType?: BattleType;
    gimmickRules?: BattleGimmickRules;
    aiProfiles?: AiProfile[];
  };
  seed: number;
  status: "completed" | "tie" | "turn_limit" | "timeout";
  winner: string | null;
  turns: number;
  durationMs: number;
  warnings: ScenarioIssue[];
  events: BattleEvent[];
  log: string[];
  aiTrace?: AiTraceEntry[];
};

type BattleResponse =
  | { ok: true; battle: BattleResult }
  | { ok: false; issues: ScenarioIssue[] };

type InteractivePokemon = {
  slot: number;
  ident: string;
  species: string;
  ability?: string | null;
  item?: string | null;
  heldItem?: string | null;
  stats?: Record<string, number> | null;
  types: string[];
  teraType: string;
  terastallized: string;
  details: string;
  condition: {
    text: string;
    current: number | null;
    maximum: number | null;
    percent: number | null;
    status: string | null;
    fainted: boolean;
  };
  active: boolean;
};

type AiTraceEntry = {
  turn: number;
  actor: string;
  species?: string;
  kind?: "move" | "switch";
  strategy?: string;
  chosenAction?: string;
  gimmick?: string;
  reason?: string;
  score?: number;
  candidates?: Array<{
    slot: number;
    id: string;
    name: string;
    pp: number;
    maxPp: number;
    disabled: boolean;
    type: string;
    category: "Physical" | "Special" | "Status";
    power: number;
    accuracy: number | true;
    priority: number;
    selected: boolean;
  }>;
};

type InteractiveMove = {
  slot: number;
  id: string;
  name: string;
  pp: number;
  maxPp: number;
  target: string;
  disabled: boolean;
  type: string;
  category: "Physical" | "Special" | "Status";
  power: number;
  accuracy: number | true;
  priority: number;
  effectiveness:
    | "super"
    | "neutral"
    | "resisted"
    | "immune"
    | "not_applicable"
    | "unknown";
};

type InteractiveGimmicks = {
  canMegaEvo: boolean;
  megaVariant: "mega" | "megax" | "megay";
  zMoves: Array<{ move: string; target: string } | null>;
  canDynamax: boolean;
  maxMoves: Array<{ id: string; move: string; target: string }>;
  gigantamax: string;
  canTerastallize: string;
};

type InteractiveSlotAction = {
  type: "move" | "switch";
  slot: number;
  target?: number;
  gimmick?: BattleGimmick;
};

type InteractiveAction =
  | InteractiveSlotAction
  | { type: "multi"; actions: InteractiveSlotAction[] };

type InteractiveBattle = {
  sessionId: string;
  scenarioId: string;
  engine: {
    id: string;
    version?: string;
  };
  settings?: {
    battleEngine?: BattleEngineChoice;
    battleType?: BattleType;
    gimmickRules?: BattleGimmickRules;
  };
  status: "awaiting_choice" | "completed" | "tie";
  winner: string | null;
  turns: number;
  aiTrace: AiTraceEntry[];
  sides: Array<{
    name: string;
    team: Array<
      Pokemon & {
        ident?: string;
        types?: string[];
        item?: string | null;
        stats?: Record<string, number> | null;
        condition?: InteractivePokemon["condition"];
        active?: boolean;
      }
    >;
  }>;
  request: null | {
    requestId: number;
    kind: "move" | "force_switch";
    active: InteractivePokemon | null;
    activeSlots?: Array<{
      position: number;
      active: InteractivePokemon | null;
      moves: InteractiveMove[];
      gimmicks: InteractiveGimmicks;
      trapped: boolean;
    }>;
    forceSwitch?: boolean[];
    team: InteractivePokemon[];
    moves: InteractiveMove[];
    gimmicks: InteractiveGimmicks;
    switches: InteractivePokemon[];
    trapped: boolean;
    opponents?: Array<{
      position: number;
      species: string;
      types: string[];
    } & Partial<Pokemon> & {
      item?: string | null;
      stats?: Record<string, number> | null;
    }>;
    opponent: null | {
      species: string;
      types: string[];
      ability?: string | null;
      item?: string | null;
      heldItem?: string | null;
      stats?: Record<string, number> | null;
      moves: NonNullable<AiTraceEntry["candidates"]>;
      decision: null | {
        strategy?: string;
        chosenAction?: string;
        reason?: string;
      };
    };
  };
  warnings: ScenarioIssue[];
  error: string | null;
  events: BattleEvent[];
  log: string[];
};

type InteractiveResponse =
  | { ok: true; battle: InteractiveBattle }
  | { ok: false; issues: ScenarioIssue[] };

type CustomPokemon = {
  species: string;
  level: number;
  ability: string;
  heldItem: string;
  dynamax: boolean;
  gmax: boolean;
  tera: string;
  moves: string[];
};

type ChoiceTarget =
  | { kind: "pokemon" | "ability" | "item"; pokemonIndex: number }
  | { kind: "move"; pokemonIndex: number; moveIndex: number };

type StoredBattleView =
  | {
      schemaVersion: 1;
      savedAt: string;
      kind: "automatic";
      scenario: BattleScenario;
      battle: BattleResult;
    }
  | {
      schemaVersion: 1;
      savedAt: string;
      kind: "interactive";
      scenario: BattleScenario;
      battle: InteractiveBattle;
    };

const emptyPokemon = (): CustomPokemon => ({
  species: "",
  level: 50,
  ability: "",
  heldItem: "",
  dynamax: false,
  gmax: false,
  tera: "",
  moves: ["", "", "", ""],
});

const initialParty = Array.from({ length: 6 }, emptyPokemon);
const RECENT_TRAINERS_KEY = "cobbleverse-battle-lab:recent-trainers";
const LAST_BATTLE_KEY = "cobbleverse-battle-lab:last-battle";
const EVE_REPORT_KEY = "cobbleverse-battle-lab:eve-report";
const PARTY_ORDERS_KEY = "cobbleverse-battle-lab:party-orders";

const pokemonTypeNames: Record<string, string> = {
  Normal: "노말",
  Fire: "불꽃",
  Water: "물",
  Electric: "전기",
  Grass: "풀",
  Ice: "얼음",
  Fighting: "격투",
  Poison: "독",
  Ground: "땅",
  Flying: "비행",
  Psychic: "에스퍼",
  Bug: "벌레",
  Rock: "바위",
  Ghost: "고스트",
  Dragon: "드래곤",
  Dark: "악",
  Steel: "강철",
  Fairy: "페어리",
  Stellar: "스텔라",
};

const moveCategoryNames = {
  Physical: "물리",
  Special: "특수",
  Status: "변화",
} as const;

const pokemonTypeGlyphs: Record<string, string> = {
  Normal: "노",
  Fire: "불",
  Water: "물",
  Electric: "전",
  Grass: "풀",
  Ice: "얼",
  Fighting: "격",
  Poison: "독",
  Ground: "땅",
  Flying: "비",
  Psychic: "에",
  Bug: "벌",
  Rock: "바",
  Ghost: "고",
  Dragon: "드",
  Dark: "악",
  Steel: "강",
  Fairy: "페",
  Stellar: "별",
};

const effectivenessLabels = {
  super: "효과가 굉장함",
  neutral: "보통 효과",
  resisted: "효과가 별로임",
  immune: "효과 없음",
  not_applicable: "상성 미적용",
  unknown: "상성 확인 불가",
} as const;

const battleEventNames: Record<string, string> = {
  turn: "턴 시작",
  switch: "교체",
  move: "기술 사용",
  damage: "피해",
  heal: "회복",
  faint: "쓰러짐",
  status: "상태 이상",
  status_cured: "상태 회복",
  super_effective: "효과가 굉장했다",
  resisted: "효과가 별로였다",
  immune: "효과가 없었다",
  critical: "급소에 맞았다",
  miss: "공격이 빗나갔다",
  failed: "기술 실패",
  stat_up: "능력 상승",
  stat_down: "능력 하락",
  stat_set: "능력 변화",
  ability: "특성 발동",
  item: "도구 발동",
  item_consumed: "도구 소모",
  activated: "효과 발동",
  cannot_move: "행동 불가",
  weather: "날씨 변화",
  field_started: "필드 효과 시작",
  field_ended: "필드 효과 종료",
  mega_evolution: "메가진화",
  z_power: "Z파워",
  dynamax_started: "다이맥스",
  dynamax_ended: "다이맥스 종료",
  terastallized: "테라스탈",
  win: "승리",
  tie: "무승부",
};

const playbackEventTypes = new Set([
  "switch",
  "move",
  "damage",
  "heal",
  "faint",
  "status",
  "status_cured",
  "super_effective",
  "resisted",
  "immune",
  "critical",
  "miss",
  "failed",
  "stat_up",
  "stat_down",
  "stat_set",
  "ability",
  "item",
  "item_consumed",
  "activated",
  "cannot_move",
  "weather",
  "field_started",
  "field_ended",
  "mega_evolution",
  "z_power",
  "dynamax_started",
  "dynamax_ended",
  "terastallized",
  "win",
  "tie",
]);

function playbackDelay(mode: BattlePlaybackMode) {
  if (mode === "fast") return 450;
  if (mode === "normal") return 900;
  return 0;
}

function newPlaybackEvents(
  previousBattle: InteractiveBattle,
  nextBattle: InteractiveBattle,
) {
  return nextBattle.events
    .slice(previousBattle.events.length)
    .filter((event) => playbackEventTypes.has(event.type));
}

function wait(milliseconds: number) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function TypeIcon({ type, withLabel = false }: { type: string; withLabel?: boolean }) {
  const normalizedType = type.toLowerCase();
  return (
    <span
      className={`type-icon type-icon-${normalizedType}`}
      title={pokemonTypeNames[type] ?? type}
      aria-label={`${pokemonTypeNames[type] ?? type} 타입`}
    >
      <span className="type-icon-art" aria-hidden="true">
        {/* Cobblemon type-gem textures are local pixel assets and skip image optimization. */}
        <img
          src={`/assets/cobblemon/type-icons/${normalizedType}.png`}
          alt=""
          onError={(event) => {
            event.currentTarget.hidden = true;
          }}
        />
        <i>{pokemonTypeGlyphs[type] ?? "?"}</i>
      </span>
      {withLabel ? <b>{pokemonTypeNames[type] ?? type}</b> : null}
    </span>
  );
}

function StatusBadge({
  status,
  compact = false,
}: {
  status: string | null | undefined;
  compact?: boolean;
}) {
  const normalized = normalizeBattleStatus(status);
  if (!normalized) return null;
  const metadata = BATTLE_STATUSES[normalized];
  return (
    <b
      className={`status-badge status-${normalized} ${compact ? "compact" : ""}`}
      title={metadata.label}
      aria-label={`상태이상: ${metadata.label}`}
    >
      {compact ? metadata.shortLabel : metadata.label}
    </b>
  );
}

function MoveCategoryIcon({
  category,
}: {
  category: "Physical" | "Special" | "Status";
}) {
  const normalizedCategory = category.toLowerCase();
  return (
    <span
      className={`move-category-icon move-category-${normalizedCategory}`}
      title={`${moveCategoryNames[category]} 기술`}
      aria-label={`${moveCategoryNames[category]} 기술`}
    >
      <i aria-hidden="true" />
    </span>
  );
}

function dexId(value: string | null | undefined) {
  return String(value ?? "")
    .toLowerCase()
    .replaceAll("♀", "f")
    .replaceAll("♂", "m")
    .replace(/[^a-z0-9]/g, "");
}

function localizedSpecies(
  catalog: LocalizationCatalog | null,
  value: string | null | undefined,
) {
  return localizedSpeciesName(catalog, value);
}

function localizedMove(
  catalog: LocalizationCatalog | null,
  value: string | null | undefined,
  fallback?: string,
) {
  return catalog?.moves[dexId(value)]?.name ?? fallback ?? displayId(value);
}

function dynamaxMoveDescription(
  move: InteractiveMove,
  localization: LocalizationCatalog | null,
  gigantamax: boolean,
) {
  const originalName = localizedMove(localization, move.id, move.name);
  const transformation = gigantamax ? "거다이맥스 기술" : "다이맥스 기술";
  if (move.category === "Status") {
    return `${originalName}에서 변환된 ${transformation}이다. 현재 자체 엔진에서는 원래 변화 기술의 효과를 그대로 사용한다.`;
  }
  const typeName = pokemonTypeNames[move.type] ?? move.type;
  return `${originalName}에서 변환된 ${typeName}타입 ${transformation}이다. 다이맥스의 힘으로 위력이 강화되며 원래 기술의 부가 효과도 함께 적용된다.`;
}

function localizedEventDetail(
  catalog: LocalizationCatalog | null,
  event: BattleEvent,
) {
  if (!event.detail) return "";
  if (event.type === "status" || event.type === "status_cured") {
    const status = normalizeBattleStatus(event.detail);
    return status ? BATTLE_STATUSES[status].label : event.detail;
  }
  if (event.type === "move") {
    return localizedMove(catalog, event.detail, event.detail);
  }
  if (event.type === "switch") {
    return localizedSpecies(catalog, event.detail);
  }
  if (
    event.type === "stat_up" ||
    event.type === "stat_down" ||
    event.type === "stat_set"
  ) {
    const statNames: Record<string, string> = {
      atk: "공격",
      def: "방어",
      spa: "특수공격",
      spd: "특수방어",
      spe: "스피드",
      accuracy: "명중률",
      evasion: "회피율",
    };
    return `${statNames[event.detail] ?? event.detail} ${event.condition}`.trim();
  }
  return event.detail;
}

function battleEventHeading(
  catalog: LocalizationCatalog | null,
  event: BattleEvent,
) {
  if (event.type === "turn") return `${event.turn}턴 시작`;
  if (event.type === "win") return `${event.actor} 승리`;
  const actor = localizedSpecies(catalog, actorName(event.actor));
  const label = battleEventNames[event.type] ?? event.type;
  return actor ? `${actor} · ${label}` : label;
}

function actionNoticeCopy(
  localization: LocalizationCatalog | null,
  event: BattleEvent,
) {
  const actor = localizedSpecies(localization, actorName(event.actor));
  const detail = localizedEventDetail(localization, event);
  const subject = actor ? `${actor}${koreanParticle(actor, "이", "가")}` : "포켓몬이";
  const move = detail
    ? `${detail}${koreanParticle(detail, "을", "를")}`
    : "기술을";
  switch (event.type) {
    case "move":
      return {
        title: `${subject} ${move} 사용했습니다.`,
        detail: event.target
          ? `${localizedSpecies(
              localization,
              actorName(event.target),
            )}${koreanParticle(
              localizedSpecies(localization, actorName(event.target)),
              "을",
              "를",
            )} 향한 공격입니다.`
          : "공격 결과를 확인합니다.",
      };
    case "switch":
      return {
        title: `${detail || actor || "새 포켓몬"}${koreanParticle(
          detail || actor,
          "이",
          "가",
        )} 전투에 나왔습니다.`,
        detail: "다음 행동을 준비합니다.",
      };
    case "damage":
      return {
        title: damageCause(localization, event)
          ? `${subject} ${damageCause(localization, event)} 체력이 감소했습니다.`
          : `${subject} 공격을 받아 체력이 감소했습니다.`,
        detail: event.condition
          ? `남은 체력은 ${event.condition}입니다.`
          : "체력이 감소했습니다.",
      };
    case "heal":
      return {
        title: healCause(localization, event)
          ? `${subject} ${healCause(localization, event)} 체력을 회복했습니다.`
          : `${subject} 체력을 회복했습니다.`,
        detail: event.condition
          ? `현재 체력은 ${event.condition}입니다.`
          : "회복 효과가 적용되었습니다.",
      };
    case "faint":
      return {
        title: `${subject} 힘이 다해 쓰러졌습니다.`,
        detail: "다음 포켓몬을 준비해야 합니다.",
      };
    case "super_effective":
      return {
        title: `${actor || "상대"}에게 효과가 굉장했습니다!`,
        detail: "타입 상성을 제대로 공략했습니다.",
      };
    case "resisted":
      return {
        title: `${actor || "상대"}에게는 효과가 별로였습니다.`,
        detail: "타입 상성 때문에 피해가 줄었습니다.",
      };
    case "immune":
      return {
        title: `${actor || "상대"}에게는 효과가 없었습니다.`,
        detail: "공격이 통하지 않았습니다.",
      };
    case "critical":
      return {
        title: `${actor || "상대"}의 급소에 맞았습니다!`,
        detail: "평소보다 큰 피해를 입었습니다.",
      };
    case "miss":
      return {
        title: `${actor || "포켓몬"}의 공격이 빗나갔습니다.`,
        detail: "이번 공격은 피해를 주지 못했습니다.",
      };
    case "failed":
      return {
        title: `${subject} 기술 사용에 실패했습니다.`,
        detail: "이번 행동은 효과를 내지 못했습니다.",
      };
    case "stat_up":
      return {
        title: `${subject} ${detail || "능력"}을 높였습니다.`,
        detail: "능력 변화가 전투에 적용되었습니다.",
      };
    case "stat_down":
      return {
        title: `${actor || "포켓몬"}의 ${detail || "능력"}이 낮아졌습니다.`,
        detail: "능력 변화가 전투에 적용되었습니다.",
      };
    case "ability":
      return {
        title: `${actor || "포켓몬"}의 특성 ${detail || "효과"}${koreanParticle(
          detail,
          "이",
          "가",
        )} 발동했습니다.`,
        detail: "특성 효과가 전투에 적용되었습니다.",
      };
    case "item":
      return {
        title: `${subject} ${detail || "도구"}${koreanParticle(
          detail,
          "을",
          "를",
        )} 사용했습니다.`,
        detail: "도구의 효과가 전투에 적용되었습니다.",
      };
    case "item_consumed":
      return {
        title: `${actor || "포켓몬"}의 ${detail || "도구"}이 소모되었습니다.`,
        detail: "소모된 도구는 다시 사용할 수 없습니다.",
      };
    case "activated":
      return {
        title: `${actor || "포켓몬"}에게 ${detail || "효과"}가 발동했습니다.`,
        detail: "새로운 효과가 전투에 적용되었습니다.",
      };
    case "status":
      return {
        title: `${actor || "포켓몬"}에게 ${detail || event.condition || "상태 이상"}이 발생했습니다.`,
        detail: "상태 이상이 행동에 영향을 줄 수 있습니다.",
      };
    case "status_cured":
      return {
        title: `${subject} ${detail || event.condition || "상태 이상"}에서 회복했습니다.`,
        detail: "이제 해당 상태 이상의 영향을 받지 않습니다.",
      };
    case "cannot_move":
      return {
        title: `${subject} 이번 턴에 움직일 수 없습니다.`,
        detail: "행동하지 못한 채 턴을 넘깁니다.",
      };
    default:
      return {
        title: `${battleEventNames[event.type] || "새로운 전투 효과"}가 발생했습니다.`,
        detail:
          detail || event.condition
            ? `${detail || event.condition} 효과가 적용되었습니다.`
            : "전투 상황이 변경되었습니다.",
      };
  }
}

function koreanParticle(
  value: string | null | undefined,
  consonantParticle: string,
  vowelParticle: string,
) {
  const last = value?.trim().at(-1);
  if (!last) return vowelParticle;
  const code = last.charCodeAt(0);
  if (code < 0xac00 || code > 0xd7a3) return vowelParticle;
  return (code - 0xac00) % 28 === 0 ? vowelParticle : consonantParticle;
}

const battleDetailNames: Record<string, string> = {
  Sturdy: "옹골참",
  Leftovers: "먹다남은음식",
  "Sitrus Berry": "자뭉열매",
  "Focus Sash": "기합의띠",
  "Life Orb": "생명의구슬",
  "Rocky Helmet": "울퉁불퉁멧",
  "Sticky Barb": "끈적끈적바늘",
  "Black Sludge": "검은진흙",
  "Jaboca Berry": "자보열매",
  "Rowap Berry": "애터열매",
  "Solar Power": "선파워",
  "Dry Skin": "건조피부",
};

function battleEffectName(
  localization: LocalizationCatalog | null,
  source: string | undefined,
) {
  if (!source) return null;
  const separator = source.indexOf(": ");
  const kind = separator >= 0 ? source.slice(0, separator) : "effect";
  const value = separator >= 0 ? source.slice(separator + 2) : source;
  const translated =
    battleDetailNames[value] ??
    (kind === "move" ? localizedMove(localization, value, value) : value);
  return { kind, value, translated };
}

function damageCause(
  localization: LocalizationCatalog | null,
  event: BattleEvent,
) {
  const effect = battleEffectName(localization, event.source);
  if (!effect) return null;
  const sourceActorName = event.sourceActor
    ? localizedSpecies(localization, actorName(event.sourceActor))
    : "";
  const owner = event.sourceActor?.startsWith("p2")
    ? `상대 ${sourceActorName}`
    : sourceActorName;

  if (effect.kind === "item") {
    if (effect.value === "Life Orb") return "생명의구슬의 반동으로";
    return owner
      ? `${owner}의 ${effect.translated} 효과로`
      : `${effect.translated}의 효과로`;
  }
  if (effect.kind === "ability") {
    return owner
      ? `${owner}의 특성 「${effect.translated}」 효과로`
      : `특성 「${effect.translated}」의 효과로`;
  }
  if (effect.kind === "move") return `${effect.translated}의 효과로`;

  const fixed: Record<string, string> = {
    psn: "독의 데미지로",
    tox: "맹독의 데미지로",
    brn: "화상의 데미지로",
    Sandstorm: "모래바람에 휩쓸려",
    Hail: "싸라기눈에 맞아",
    "Salt Cure": "소금절이의 효과로",
    "Leech Seed": "씨뿌리기의 효과로",
    Curse: "저주의 효과로",
    Nightmare: "악몽의 효과로",
    Recoil: "기술의 반동으로",
    recoil: "기술의 반동으로",
    confusion: "혼란에 빠져 자신을 공격해",
    "Stealth Rock": "스텔스록에 부딪혀",
    Spikes: "압정에 찔려",
  };
  return fixed[effect.value] ?? `${effect.translated}의 효과로`;
}

function healCause(
  localization: LocalizationCatalog | null,
  event: BattleEvent,
) {
  const effect = battleEffectName(localization, event.source);
  if (!effect) return null;
  if (effect.kind === "item") return `${effect.translated}의 효과로`;
  if (effect.kind === "ability") return `특성 「${effect.translated}」의 효과로`;
  if (effect.kind === "move") return `${effect.translated}의 효과로`;
  if (effect.value === "drain") return "흡수한 체력으로";
  return `${effect.translated}의 효과로`;
}

function pokemonBattleMessage(
  localization: LocalizationCatalog | null,
  event: BattleEvent,
) {
  const actorNameValue = localizedSpecies(localization, actorName(event.actor));
  const actor = event.actor?.startsWith("p2")
    ? `상대 ${actorNameValue}`
    : actorNameValue;
  const subject = `${actor || "포켓몬"}${koreanParticle(actor, "이", "가")}`;
  const detail =
    battleDetailNames[event.detail ?? ""] ??
    localizedEventDetail(localization, event);
  const statNames: Record<string, string> = {
    atk: "공격",
    def: "방어",
    spa: "특수공격",
    spd: "특수방어",
    spe: "스피드",
    accuracy: "명중률",
    evasion: "회피율",
  };

  switch (event.type) {
    case "move":
      return `${actor || "포켓몬"}의 ${detail || "기술"}!`;
    case "switch":
      return event.actor?.startsWith("p2")
        ? `상대 ${detail || actorNameValue}${koreanParticle(
            detail || actorNameValue,
            "이",
            "가",
          )} 나타났다!`
        : `가랏! ${detail || actorNameValue}!`;
    case "damage":
      return `${subject} ${damageCause(localization, event) ?? ""} 데미지를 입었다!`
        .replace(/\s+/g, " ")
        .trim();
    case "heal":
      return `${actor || "포켓몬"}의 체력이 ${
        healCause(localization, event) ?? ""
      } 회복되었다!`
        .replace(/\s+/g, " ")
        .trim();
    case "faint":
      return `${subject} 쓰러졌다!`;
    case "super_effective":
      return "효과가 굉장했다!";
    case "resisted":
      return "효과가 별로인 듯하다...";
    case "immune":
      return `${actor || "상대"}에게는 효과가 없는 것 같다...`;
    case "critical":
      return "급소에 맞았다!";
    case "miss":
      return `${actor || "포켓몬"}의 공격은 빗나갔다!`;
    case "failed":
      return "그러나 실패하고 말았다!";
    case "stat_up":
      return `${actor || "포켓몬"}의 ${
        (statNames[event.detail ?? ""] ?? detail) || "능력"
      }이 올랐다!`;
    case "stat_down":
      return `${actor || "포켓몬"}의 ${
        (statNames[event.detail ?? ""] ?? detail) || "능력"
      }이 떨어졌다!`;
    case "stat_set":
      return `${actor || "포켓몬"}의 ${
        (statNames[event.detail ?? ""] ?? detail) || "능력"
      }이 변했다!`;
    case "ability":
      return `${actor || "포켓몬"}의 특성 「${detail || "특성"}」!`;
    case "item":
      return `${subject} ${detail || "도구"}${koreanParticle(
        detail,
        "을",
        "를",
      )} 사용했다!`;
    case "item_consumed":
      return `${actor || "포켓몬"}의 ${detail || "도구"}이 없어졌다!`;
    case "activated":
      return `${actor || "포켓몬"}에게 ${detail || "효과"}가 발동했다!`;
    case "status":
      return `${subject} ${detail || event.condition || "상태 이상"} 상태가 되었다!`;
    case "status_cured":
      return `${actor || "포켓몬"}의 ${detail || event.condition || "상태 이상"} 상태가 회복되었다!`;
    case "cannot_move":
      return `${subject} 움직일 수 없다!`;
    case "weather":
      return `${detail || "날씨"}가 전장을 뒤덮었다!`;
    case "field_started":
      return `${detail || "필드 효과"}가 시작되었다!`;
    case "field_ended":
      return `${detail || "필드 효과"}가 사라졌다!`;
    case "mega_evolution":
      return `${actor || "포켓몬"}은(는) ${detail || "메가진화한 모습"}으로 메가진화했다!`;
    case "z_power":
      return `${actor || "포켓몬"}을(를) Z파워가 감쌌다!`;
    case "dynamax_started":
      return `${actor || "포켓몬"}이(가) ${detail ? "거다이맥스" : "다이맥스"}했다!`;
    case "dynamax_ended":
      return `${actor || "포켓몬"}의 다이맥스가 풀렸다!`;
    case "terastallized":
      return `${actor || "포켓몬"}이(가) ${detail || "새로운 타입"}으로 테라스탈했다!`;
    case "win":
      return `${event.actor || "플레이어"}의 승리!`;
    case "tie":
      return "승부가 나지 않았다!";
    default:
      return `${detail || event.condition || battleEventNames[event.type] || "전투 상황"}!`;
  }
}

function BattleLogEventLine({
  event,
  localization,
}: {
  event: BattleEvent;
  localization: LocalizationCatalog | null;
}) {
  const actor = localizedSpecies(localization, actorName(event.actor));
  const target = localizedSpecies(localization, actorName(event.target));
  const detail = localizedEventDetail(localization, event);
  const condition = event.condition ? ` (${event.condition})` : "";

  switch (event.type) {
    case "switch":
      return (
        <p>
          <strong>{actor}</strong> 등장!{condition}
        </p>
      );
    case "move":
      return (
        <p>
          <strong>{actor}</strong>의 <b>{detail}</b>!
          {target ? <small> 대상: {target}</small> : null}
        </p>
      );
    case "damage":
      return (
        <p>
          <strong>{actor}</strong>
          {damageCause(localization, event)
            ? `${koreanParticle(actor, "은", "는")} ${damageCause(
                localization,
                event,
              )} 데미지를 입었다.`
            : "의 체력이 줄었다."}
          {condition}
        </p>
      );
    case "heal":
      return (
        <p>
          <strong>{actor}</strong>의 체력이{" "}
          {healCause(localization, event)
            ? `${healCause(localization, event)} `
            : ""}
          회복되었다.{condition}
        </p>
      );
    case "faint":
      return (
        <p className="battle-log-danger">
          <strong>{actor}</strong> 쓰러졌다!
        </p>
      );
    case "super_effective":
      return <p className="battle-log-positive">효과가 굉장했다!</p>;
    case "resisted":
      return <p className="battle-log-muted">효과가 별로인 듯하다...</p>;
    case "immune":
      return (
        <p className="battle-log-muted">
          <strong>{actor}</strong>에게는 효과가 없었다.
        </p>
      );
    case "critical":
      return <p className="battle-log-positive">급소에 맞았다!</p>;
    case "miss":
      return (
        <p className="battle-log-muted">
          <strong>{actor}</strong>의 공격은 빗나갔다!
        </p>
      );
    case "failed":
      return <p className="battle-log-muted">그러나 실패하고 말았다!</p>;
    case "status":
      return (
        <p>
          <strong>{actor}</strong>에게 <b>{detail || event.condition}</b> 상태
          이상이 발생했다.
        </p>
      );
    case "status_cured":
      return (
        <p>
          <strong>{actor}</strong>의 <b>{detail || event.condition}</b> 상태가
          회복되었다.
        </p>
      );
    case "stat_up":
      return (
        <p>
          <strong>{actor}</strong>의 {detail} 상승했다!
        </p>
      );
    case "stat_down":
      return (
        <p>
          <strong>{actor}</strong>의 {detail} 하락했다!
        </p>
      );
    case "stat_set":
      return (
        <p>
          <strong>{actor}</strong>의 능력치가 {detail}(으)로 변했다.
        </p>
      );
    case "ability":
      return (
        <p>
          [<strong>{actor}</strong>의 특성: <b>{detail}</b>]
        </p>
      );
    case "item":
    case "item_consumed":
    case "activated":
      return (
        <p>
          <strong>{actor}</strong> · {battleEventNames[event.type]}{" "}
          {detail ? <b>{detail}</b> : null}
        </p>
      );
    case "cannot_move":
      return (
        <p className="battle-log-muted">
          <strong>{actor}</strong>은(는) 움직일 수 없다!
        </p>
      );
    case "weather":
    case "field_started":
    case "field_ended":
      return (
        <p>
          {battleEventNames[event.type]}: <b>{detail}</b>
        </p>
      );
    case "win":
      return (
        <p className="battle-log-result">
          <strong>{event.actor}</strong> 승리!
        </p>
      );
    case "tie":
      return <p className="battle-log-result">승부가 나지 않았다.</p>;
    default:
      return (
        <p>
          {battleEventHeading(localization, event)}
          {detail ? ` · ${detail}` : ""}
          {condition}
        </p>
      );
  }
}

function displayId(value: string | null | undefined, fallback = "미지정") {
  if (!value) return fallback;
  return value
    .replace(/^cobblemon:/, "")
    .replaceAll("_", " ")
    .replace(/\b\w/g, (character) => character.toUpperCase());
}

const battleStatLabels: Record<string, string> = {
  hp: "HP",
  atk: "공격",
  attack: "공격",
  defence: "방어",
  defense: "방어",
  def: "방어",
  spa: "특수공격",
  specialAttack: "특수공격",
  specialattack: "특수공격",
  spd: "특수방어",
  specialDefence: "특수방어",
  specialDefense: "특수방어",
  specialdefence: "특수방어",
  specialdefense: "특수방어",
  spe: "스피드",
  speed: "스피드",
};

function catalogAbility(
  catalog: BattleCatalog | null,
  value: string | null | undefined,
) {
  const key = dexId(value);
  return (
    catalog?.abilities.find((entry) => dexId(entry.id) === key) ?? null
  );
}

function sharedI18nAbility(
  catalog: SharedI18nCatalog | null,
  value: string | null | undefined,
) {
  const key = dexId(value);
  return catalog?.abilities?.[key] ?? null;
}

function localizedAbilityInfo(
  catalog: BattleCatalog | null,
  sharedI18n: SharedI18nCatalog | null,
  value: string | null | undefined,
) {
  const entry = catalogAbility(catalog, value);
  const fallback = sharedI18nAbility(sharedI18n, value);
  return {
    name: entry?.name ?? fallback?.name ?? displayId(value),
    description:
      entry?.description ??
      fallback?.description ??
      "아직 카탈로그에 특성 설명이 없습니다.",
  };
}

function catalogItem(
  catalog: BattleCatalog | null,
  value: string | null | undefined,
) {
  const key = dexId(value);
  return (
    catalog?.items.find(
      (entry) => dexId(entry.id) === key || dexId(entry.shortId) === key,
    ) ?? null
  );
}

function sharedI18nItem(
  catalog: SharedI18nCatalog | null,
  value: string | null | undefined,
) {
  const key = dexId(value);
  if (!key || !catalog?.items) return null;
  return (
    catalog.items[key] ??
    Object.values(catalog.items).find(
      (entry) => dexId(entry.shortId) === key,
    ) ??
    null
  );
}

function localizedItemInfo(
  catalog: BattleCatalog | null,
  sharedI18n: SharedI18nCatalog | null,
  value: string | null | undefined,
) {
  const entry = catalogItem(catalog, value);
  const fallback = sharedI18nItem(sharedI18n, value);
  return {
    name: entry?.name ?? fallback?.name ?? displayId(value, "없음"),
    description:
      entry?.description ??
      fallback?.description ??
      "아직 카탈로그에 도구 설명이 없습니다.",
  };
}

function normalizedBattleStatKey(stat: string) {
  const key = stat.toLowerCase();
  if (["atk", "attack"].includes(key)) return "attack";
  if (["def", "defence", "defense"].includes(key)) return "defence";
  if (["spa", "specialattack"].includes(key)) return "specialAttack";
  if (["spd", "specialdefence", "specialdefense"].includes(key)) {
    return "specialDefence";
  }
  if (["spe", "speed"].includes(key)) return "speed";
  if (key === "hp") return "hp";
  return stat;
}

function rankMultiplier(rank: number) {
  if (rank >= 0) return (2 + rank) / 2;
  return 2 / (2 - rank);
}

function rankedBattleStats(
  stats: Record<string, number> | null | undefined,
  ranks: Array<[string, number]>,
) {
  if (!stats) return null;
  const rankByStat = new Map(
    ranks.map(([stat, rank]) => [normalizedBattleStatKey(stat), rank]),
  );
  return Object.fromEntries(
    Object.entries(stats).map(([stat, value]) => {
      const normalized = normalizedBattleStatKey(stat);
      const rank = rankByStat.get(normalized) ?? 0;
      if (normalized === "hp" || rank === 0) return [stat, value];
      return [stat, Math.max(1, Math.floor(value * rankMultiplier(rank)))];
    }),
  );
}

function activeScenarioPokemon(
  battle: InteractiveBattle,
  sideIndex: 0 | 1,
  species: string | null | undefined,
  active: InteractivePokemon | null | undefined,
) {
  const team = battle.sides[sideIndex]?.team ?? [];
  const activeSlot = active?.slot;
  if (activeSlot) {
    const bySlot = team.find((pokemon) => pokemon.slot === activeSlot);
    if (bySlot) return bySlot;
  }
  const speciesId = dexId(species);
  return (
    team.find(
      (pokemon) =>
        dexId(pokemon.resolvedSpecies ?? pokemon.species) === speciesId ||
        dexId(pokemon.species) === speciesId,
    ) ?? null
  );
}

function mergedPokemonInfo({
  battle,
  sideIndex,
  species,
  active,
  opponent,
}: {
  battle: InteractiveBattle;
  sideIndex: 0 | 1;
  species: string | null | undefined;
  active?: InteractivePokemon | null;
  opponent?: NonNullable<NonNullable<InteractiveBattle["request"]>["opponent"]> | null;
}) {
  const configured = activeScenarioPokemon(battle, sideIndex, species, active);
  return {
    species:
      species ??
      active?.species ??
      opponent?.species ??
      configured?.resolvedSpecies ??
      configured?.species ??
      "",
    ability:
      active?.ability ??
      opponent?.ability ??
      configured?.ability ??
      null,
    heldItem:
      active?.heldItem ??
      active?.item ??
      opponent?.heldItem ??
      opponent?.item ??
      configured?.heldItem ??
      configured?.item ??
      null,
    stats:
      active?.stats ??
      opponent?.stats ??
      configured?.stats ??
      null,
    condition: active?.condition ?? configured?.condition ?? null,
  };
}

function PokemonInfoPanel({
  sideLabel,
  info,
  ranks,
  catalog,
  sharedI18n,
  localization,
}: {
  sideLabel: string;
  info: ReturnType<typeof mergedPokemonInfo>;
  ranks: Array<[string, number]>;
  catalog: BattleCatalog | null;
  sharedI18n: SharedI18nCatalog | null;
  localization: LocalizationCatalog | null;
}) {
  const ability = localizedAbilityInfo(catalog, sharedI18n, info.ability);
  const item = localizedItemInfo(catalog, sharedI18n, info.heldItem);
  const stats = info.stats ? Object.entries(info.stats) : [];
  const adjustedStats = rankedBattleStats(info.stats, ranks);
  const adjustedStatEntries = adjustedStats ? Object.entries(adjustedStats) : [];
  return (
    <article className="battle-info-card">
      <header>
        <span>{sideLabel}</span>
        <strong>{localizedSpecies(localization, info.species)}</strong>
      </header>
      <dl className="battle-info-meta">
        <div>
          <dt>특성</dt>
          <dd>{ability.name}</dd>
        </div>
        <div>
          <dt>도구</dt>
          <dd>{item.name}</dd>
        </div>
        <div>
          <dt>현재 HP</dt>
          <dd>
            {info.condition?.current !== null && info.condition?.maximum !== null
              ? `${info.condition?.current}/${info.condition?.maximum}`
              : info.condition?.text ?? "-"}
          </dd>
        </div>
      </dl>
      <div className="battle-info-description">
        <b>특성 설명</b>
        <p>{ability.description}</p>
      </div>
      <div className="battle-info-description">
        <b>도구 설명</b>
        <p>{item.description}</p>
      </div>
      <div className="battle-info-stats">
        <b>기본 실능력치</b>
        {stats.length ? (
          <div>
            {stats.map(([stat, value]) => (
              <span key={stat}>
                <small>{battleStatLabels[stat] ?? stat}</small>
                <strong>{value}</strong>
              </span>
            ))}
          </div>
        ) : (
          <p>
            Showdown 공개 요청에 능력치가 없으면 설정값만 표시됩니다. 자체엔진은
            실제 계산 능력치를 제공합니다.
          </p>
        )}
      </div>
      {adjustedStatEntries.length ? (
        <div className="battle-info-stats adjusted">
          <b>랭크 반영 전투값</b>
          <div>
            {adjustedStatEntries.map(([stat, value]) => {
              const normalized = normalizedBattleStatKey(stat);
              const rank = new Map(
                ranks.map(([rankStat, rankValue]) => [
                  normalizedBattleStatKey(rankStat),
                  rankValue,
                ]),
              ).get(normalized) ?? 0;
              return (
                <span className={rank !== 0 ? "changed" : ""} key={stat}>
                  <small>
                    {battleStatLabels[stat] ?? battleStatLabels[normalized] ?? stat}
                    {rank ? ` ${rank > 0 ? "+" : ""}${rank}` : ""}
                  </small>
                  <strong>{value}</strong>
                </span>
              );
            })}
          </div>
        </div>
      ) : null}
    </article>
  );
}

function TrainerPicker({
  label,
  trainers,
  value,
  onChange,
  localization,
  recentIds,
  minimumTeamSize,
  selectedTrainer,
}: {
  label: string;
  trainers: Trainer[];
  value: string;
  onChange: (id: string) => void;
  localization: LocalizationCatalog | null;
  recentIds: string[];
  minimumTeamSize: number;
  selectedTrainer?: Trainer;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [entryType, setEntryType] = useState("all");
  const [teamSize, setTeamSize] = useState(String(minimumTeamSize));
  const selected =
    selectedTrainer ?? trainers.find((trainer) => trainer.id === value);
  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    const requiredSize = Number(teamSize) || 0;
    return trainers.filter((trainer) => {
      const searchable = [
        trainer.name,
        trainer.id,
        trainer.entry.label,
        ...trainer.team.flatMap((pokemon) => [
          pokemon.species,
          localizedSpecies(localization, pokemon.species),
        ]),
      ]
        .filter(Boolean)
        .join(" ")
        .toLowerCase();
      return (
        (!normalized || searchable.includes(normalized)) &&
        (entryType === "all" ||
          (entryType === "official" &&
            trainer.entry.type === "official-player") ||
          (entryType === "computer" &&
            trainer.entry.type !== "official-player")) &&
        trainer.team.length >= requiredSize
      );
    });
  }, [entryType, localization, query, teamSize, trainers]);

  return (
    <>
      <div className="picker entry-picker">
        <span className="field-label">{label}</span>
        <button
          type="button"
          className={`entry-picker-trigger ${selected ? "selected" : ""}`}
          onClick={() => setOpen(true)}
          aria-label={`${label} 선택`}
        >
          {selected ? (
            <>
              <span>
                <strong>
                  {selected.entry.type === "official-player" ? "★ " : ""}
                  {selected.name}
                </strong>
                <small>
                  {selected.team.length}마리 · {selected.id}
                </small>
              </span>
              <span className="entry-trigger-party">
                {selected.team.slice(0, 6).map((pokemon) => (
                  <img
                    key={`${selected.id}-${pokemon.slot}`}
                    src={showdownSpriteUrl(pokemon.resolvedSpecies ?? pokemon.species)}
                    alt={localizedSpecies(
                      localization,
                      pokemon.resolvedSpecies ?? pokemon.species,
                    )}
                  />
                ))}
              </span>
            </>
          ) : (
            <span>
              <strong>엔트리를 선택하세요</strong>
              <small>검색·필터와 파티 미리보기 지원</small>
            </span>
          )}
          <b>선택</b>
        </button>
      </div>
      {open ? (
        <div className="choice-backdrop" role="presentation">
          <section
            className="entry-choice-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={`${label} 엔트리 선택`}
          >
            <header className="choice-dialog-head">
              <div>
                <p className="eyebrow">ENTRY LIBRARY</p>
                <h2>{label}</h2>
                <small>최근 사용한 엔트리는 목록 위에 표시됩니다.</small>
              </div>
              <button type="button" onClick={() => setOpen(false)}>
                닫기
              </button>
            </header>
            <div className="choice-dialog-filters entry-filters">
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="트레이너·JSON ID·포켓몬 검색"
                autoFocus
              />
              <select
                value={entryType}
                onChange={(event) => setEntryType(event.target.value)}
                aria-label="엔트리 종류 필터"
              >
                <option value="all">모든 엔트리</option>
                <option value="official">공식 플레이어 엔트리</option>
                <option value="computer">컴퓨터 엔트리</option>
              </select>
              <select
                value={teamSize}
                onChange={(event) => setTeamSize(event.target.value)}
                aria-label="파티 인원 필터"
              >
                <option value="0">파티 인원 전체</option>
                <option value="1">1마리 이상</option>
                <option value="2">2마리 이상</option>
                <option value="3">3마리 이상</option>
                <option value="6">6마리 엔트리</option>
              </select>
            </div>
            <div className="entry-choice-count">
              조건에 맞는 엔트리 {filtered.length}개
            </div>
            <div className="entry-choice-grid">
              {filtered.map((trainer) => {
                const recentIndex = recentIds.indexOf(trainer.id);
                return (
                  <button
                    type="button"
                    className={`entry-choice-card ${trainer.id === value ? "active" : ""}`}
                    key={trainer.id}
                    onClick={() => {
                      onChange(trainer.id);
                      setOpen(false);
                    }}
                  >
                    <span className="entry-choice-title">
                      <strong>
                        {trainer.entry.type === "official-player" ? "★ " : ""}
                        {trainer.name}
                      </strong>
                      {recentIndex >= 0 ? (
                        <em>최근 {recentIndex + 1}</em>
                      ) : null}
                    </span>
                    <small>{trainer.id}</small>
                    <span className="entry-choice-party">
                      {trainer.team.slice(0, 6).map((pokemon) => (
                        <span key={`${trainer.id}-${pokemon.slot}`}>
                          <img
                            loading="lazy"
                            src={showdownSpriteUrl(
                              pokemon.resolvedSpecies ?? pokemon.species,
                            )}
                            alt=""
                          />
                          <b>
                            {localizedSpecies(
                              localization,
                              pokemon.resolvedSpecies ?? pokemon.species,
                            )}
                          </b>
                          <small>Lv.{pokemon.level}</small>
                        </span>
                      ))}
                    </span>
                  </button>
                );
              })}
            </div>
          </section>
        </div>
      ) : null}
    </>
  );
}

function AiProfileControls({
  side,
  profile,
  onChange,
}: {
  side: "A" | "B";
  profile: AiProfile;
  onChange: (profile: AiProfile) => void;
}) {
  return (
    <div className="eve-ai-profile">
      <label>
        <span>AI {side} 수준</span>
        <select
          aria-label={`AI ${side} 수준`}
          value={profile.difficulty}
          onChange={(event) =>
            onChange({
              ...profile,
              difficulty: event.target.value as AiDifficulty,
            })
          }
        >
          <option value="novice">초급</option>
          <option value="standard">보통</option>
          <option value="advanced">상급</option>
          <option value="expert">전문가</option>
        </select>
      </label>
      <label>
        <span>AI {side} 성향</span>
        <select
          aria-label={`AI ${side} 성향`}
          value={profile.strategy}
          onChange={(event) =>
            onChange({
              ...profile,
              strategy: event.target.value as AiStrategy,
            })
          }
        >
          <option value="balanced">균형형</option>
          <option value="aggressive">공격형</option>
          <option value="defensive">안정형</option>
          <option value="unpredictable">변칙형</option>
        </select>
      </label>
    </div>
  );
}

function TeamStrip({
  trainer,
  emptyText,
  localization,
  onMove,
}: {
  trainer?: Trainer;
  emptyText: string;
  localization: LocalizationCatalog | null;
  onMove?: (fromIndex: number, toIndex: number) => void;
}) {
  if (!trainer) {
    return <div className="empty-team">{emptyText}</div>;
  }

  return (
    <div className="team-strip" aria-label={`${trainer.name} 파티`}>
      {trainer.entry.type === "official-player" ? (
        <div className="official-entry-banner">
          <strong>★ {trainer.entry.label ?? "공식 엔트리"}</strong>
          <span>
            {trainer.entry.owner} · {trainer.entry.source}
            {trainer.entry.snapshotDate
              ? ` · ${trainer.entry.snapshotDate} 기준`
              : ""}
          </span>
        </div>
      ) : null}
      {trainer.team.map((pokemon, index) => (
        <article className="pokemon-chip" key={`${trainer.id}-${pokemon.slot}`}>
          <div className="party-position-controls">
            <button
              type="button"
              disabled={index === 0}
              onClick={() => onMove?.(index, index - 1)}
              aria-label={`${localizedSpecies(localization, pokemon.resolvedSpecies ?? pokemon.species)} 순서를 앞으로 이동`}
              title="앞으로 이동"
            >
              ←
            </button>
            <span className="slot-number">
              {String(index + 1).padStart(2, "0")}
            </span>
            <button
              type="button"
              disabled={index === trainer.team.length - 1}
              onClick={() => onMove?.(index, index + 1)}
              aria-label={`${localizedSpecies(localization, pokemon.resolvedSpecies ?? pokemon.species)} 순서를 뒤로 이동`}
              title="뒤로 이동"
            >
              →
            </button>
          </div>
          <strong>
            {localizedSpecies(
              localization,
              pokemon.resolvedSpecies ?? pokemon.species,
            )}
          </strong>
          <span>Lv.{pokemon.level}</span>
          <small>
            {displayId(pokemon.ability)} · {displayId(pokemon.heldItem)}
          </small>
        </article>
      ))}
    </div>
  );
}

function CustomPartyEditor({
  party,
  onChange,
  localization,
  catalog,
  onOpenChoice,
}: {
  party: CustomPokemon[];
  onChange: (party: CustomPokemon[]) => void;
  localization: LocalizationCatalog | null;
  catalog: BattleCatalog | null;
  onOpenChoice: (target: ChoiceTarget) => void;
}) {
  const update = (
    index: number,
    key: keyof Omit<CustomPokemon, "moves">,
    value: string | number | boolean,
  ) => {
    onChange(
      party.map((pokemon, pokemonIndex) =>
        pokemonIndex === index ? { ...pokemon, [key]: value } : pokemon,
      ),
    );
  };

  const updateMove = (pokemonIndex: number, moveIndex: number, value: string) => {
    onChange(
      party.map((pokemon, index) => {
        if (index !== pokemonIndex) return pokemon;
        const moves = [...pokemon.moves];
        moves[moveIndex] = value;
        return { ...pokemon, moves };
      }),
    );
  };

  return (
    <div className="custom-grid">
      {party.map((pokemon, index) => (
        <article className="custom-card" key={index}>
          <header>
            <span className="slot-number">{String(index + 1).padStart(2, "0")}</span>
            <strong>
              {pokemon.species
                ? localizedSpecies(localization, pokemon.species)
                : "빈 슬롯"}
            </strong>
            {pokemon.species ? (
              <img
                className="custom-card-sprite"
                src={showdownSpriteUrl(pokemon.species)}
                alt=""
              />
            ) : null}
          </header>
          <div className="custom-fields">
            <label>
              포켓몬
              <span className="editor-picker-input">
                <input
                  value={pokemon.species}
                  onChange={(event) => update(index, "species", event.target.value)}
                  placeholder="예: garchomp"
                />
                <button
                  type="button"
                  onClick={() => onOpenChoice({ kind: "pokemon", pokemonIndex: index })}
                  disabled={!catalog}
                >
                  선택
                </button>
              </span>
            </label>
            <label>
              레벨
              <input
                type="number"
                min="1"
                max="100"
                value={pokemon.level}
                onChange={(event) =>
                  update(index, "level", Math.min(100, Math.max(1, Number(event.target.value))))
                }
              />
            </label>
            <label>
              특성
              <span className="editor-picker-input">
                <input
                  value={pokemon.ability}
                  onChange={(event) => update(index, "ability", event.target.value)}
                  placeholder="예: roughskin"
                />
                <button
                  type="button"
                  onClick={() => onOpenChoice({ kind: "ability", pokemonIndex: index })}
                  disabled={!catalog}
                >
                  선택
                </button>
              </span>
            </label>
            <label>
              지닌 도구
              <span className="editor-picker-input">
                <input
                  value={pokemon.heldItem}
                  onChange={(event) => update(index, "heldItem", event.target.value)}
                  placeholder="예: rocky_helmet"
                />
                <button
                  type="button"
                  onClick={() => onOpenChoice({ kind: "item", pokemonIndex: index })}
                  disabled={!catalog}
                >
                  선택
                </button>
              </span>
            </label>
            <label>
              테라타입
              <select
                value={pokemon.tera}
                onChange={(event) => update(index, "tera", event.target.value)}
              >
                <option value="">지정 안 함</option>
                {Object.entries(pokemonTypeNames).map(([type, name]) => (
                  <option key={type} value={type.toLowerCase()}>
                    {name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>AI 다이맥스 강제</span>
              <input
                type="checkbox"
                checked={pokemon.dynamax}
                onChange={(event) =>
                  update(index, "dynamax", event.target.checked)
                }
              />
            </label>
            <label>
              <span>거다이맥스 개체</span>
              <input
                type="checkbox"
                checked={pokemon.gmax}
                onChange={(event) => {
                  const checked = event.target.checked;
                  onChange(
                    party.map((member, memberIndex) =>
                      memberIndex === index
                        ? {
                            ...member,
                            gmax: checked,
                            dynamax: checked || member.dynamax,
                          }
                        : member,
                    ),
                  );
                }}
              />
            </label>
          </div>
          <div className="moves-grid">
            {pokemon.moves.map((move, moveIndex) => (
              <div className="move-editor-field" key={moveIndex}>
                <span>{String(moveIndex + 1).padStart(2, "0")}</span>
                <input
                  value={move}
                  onChange={(event) => updateMove(index, moveIndex, event.target.value)}
                  aria-label={`${index + 1}번 포켓몬 ${moveIndex + 1}번 기술`}
                  placeholder={`기술 ${moveIndex + 1}`}
                />
                <button
                  type="button"
                  onClick={() =>
                    onOpenChoice({
                      kind: "move",
                      pokemonIndex: index,
                      moveIndex,
                    })
                  }
                  disabled={!catalog}
                >
                  선택
                </button>
              </div>
            ))}
          </div>
        </article>
      ))}
    </div>
  );
}

function EditorChoiceDialog({
  target,
  catalog,
  party,
  onChoose,
  onClose,
}: {
  target: ChoiceTarget;
  catalog: BattleCatalog;
  party: CustomPokemon[];
  onChoose: (value: string) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("");
  const [generationFilter, setGenerationFilter] = useState("");
  const [scopeFilter, setScopeFilter] = useState("recommended");
  const pokemon = party[target.pokemonIndex];
  const species = catalog.species.find(
    (entry) => entry.id === dexId(pokemon.species),
  );
  const normalizedQuery = query.trim().toLowerCase();
  const matchesQuery = (...values: Array<string | number | undefined>) =>
    !normalizedQuery ||
    values
      .filter((value) => value !== undefined)
      .join(" ")
      .toLowerCase()
      .includes(normalizedQuery);

  const rows = (() => {
    if (target.kind === "pokemon") {
      return catalog.species.filter(
        (entry) =>
          matchesQuery(
            entry.id,
            entry.name,
            entry.englishName,
            entry.description,
            entry.number,
          ) &&
          (!typeFilter || entry.types.includes(typeFilter)) &&
          (!generationFilter ||
            entry.generation === Number(generationFilter)),
      );
    }
    if (target.kind === "move") {
      return catalog.moves.filter(
        (entry) =>
          matchesQuery(
            entry.id,
            entry.name,
            entry.englishName,
            entry.description,
          ) &&
          (!typeFilter || entry.type === typeFilter) &&
          (!categoryFilter || entry.category === categoryFilter),
      );
    }
    if (target.kind === "ability") {
      const allowed = new Set(species?.abilities ?? []);
      return catalog.abilities.filter(
        (entry) =>
          matchesQuery(entry.id, entry.name, entry.description) &&
          (scopeFilter !== "recommended" || allowed.has(entry.id)),
      );
    }
    const itemScope =
      scopeFilter === "recommended" ? "battle" : scopeFilter;
    return catalog.items.filter(
      (entry) =>
        matchesQuery(
          entry.id,
          entry.shortId,
          entry.name,
          entry.englishName,
          entry.description,
        ) &&
        (itemScope === "all" ||
          (itemScope === "battle" && entry.battleUsable) ||
          entry.category === itemScope) &&
        (!categoryFilter || entry.namespace === categoryFilter),
    );
  })();

  const titles = {
    pokemon: ["포켓몬 선택", "타입·세대·종족값을 확인하고 파티에 추가합니다."],
    move: [
      "기술 선택",
      `${species?.name ?? "현재 포켓몬"}에게 사용할 기술을 선택합니다.`,
    ],
    ability: [
      "특성 선택",
      `${species?.name ?? "현재 포켓몬"}의 기본 특성을 우선 표시합니다.`,
    ],
    item: ["지닌 도구 선택", "Cobblemon 레지스트리의 배틀 도구를 검색합니다."],
  } as const;
  const [title, description] = titles[target.kind];
  const namespaces = [
    ...new Set(catalog.items.map((entry) => entry.namespace)),
  ].sort();

  return (
    <div className="choice-backdrop" role="presentation">
      <section
        className="editor-choice-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <header className="choice-dialog-head">
          <div>
            <p className="eyebrow">BATTLE DATA PICKER</p>
            <h2>{title}</h2>
            <small>{description}</small>
          </div>
          <button type="button" onClick={onClose}>
            닫기
          </button>
        </header>
        <div className="choice-dialog-filters">
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="이름·ID·설명 검색"
            autoFocus
          />
          {target.kind === "pokemon" ? (
            <>
              <select
                value={typeFilter}
                onChange={(event) => setTypeFilter(event.target.value)}
                aria-label="포켓몬 타입 필터"
              >
                <option value="">모든 타입</option>
                {Object.entries(pokemonTypeNames).map(([type, name]) => (
                  <option key={type} value={type}>
                    {name}
                  </option>
                ))}
              </select>
              <select
                value={generationFilter}
                onChange={(event) => setGenerationFilter(event.target.value)}
                aria-label="포켓몬 세대 필터"
              >
                <option value="">모든 세대</option>
                {Array.from({ length: 9 }, (_, index) => index + 1).map(
                  (generation) => (
                    <option key={generation} value={generation}>
                      {generation}세대
                    </option>
                  ),
                )}
              </select>
            </>
          ) : null}
          {target.kind === "move" ? (
            <>
              <select
                value={typeFilter}
                onChange={(event) => setTypeFilter(event.target.value)}
                aria-label="기술 타입 필터"
              >
                <option value="">모든 타입</option>
                {Object.entries(pokemonTypeNames).map(([type, name]) => (
                  <option key={type} value={type}>
                    {name}
                  </option>
                ))}
              </select>
              <select
                value={categoryFilter}
                onChange={(event) => setCategoryFilter(event.target.value)}
                aria-label="기술 분류 필터"
              >
                <option value="">모든 분류</option>
                <option value="Physical">물리</option>
                <option value="Special">특수</option>
                <option value="Status">변화</option>
              </select>
            </>
          ) : null}
          {target.kind === "ability" ? (
            <select
              value={scopeFilter}
              onChange={(event) => setScopeFilter(event.target.value)}
              aria-label="특성 범위 필터"
            >
              <option value="recommended">이 포켓몬의 특성</option>
              <option value="all">모든 특성</option>
            </select>
          ) : null}
          {target.kind === "item" ? (
            <>
              <select
                value={scopeFilter === "recommended" ? "battle" : scopeFilter}
                onChange={(event) => setScopeFilter(event.target.value)}
                aria-label="아이템 분류 필터"
              >
                <option value="battle">배틀 사용 가능 전체</option>
                <option value="held">일반 지닌 도구</option>
                <option value="berry">배틀용 나무열매</option>
                <option value="gem">타입 주얼</option>
                <option value="mega">메가스톤</option>
                <option value="z">Z크리스탈</option>
                <option value="all">전체 아이템</option>
              </select>
              <select
                value={categoryFilter}
                onChange={(event) => setCategoryFilter(event.target.value)}
                aria-label="아이템 출처 필터"
              >
                <option value="">모든 출처 모드</option>
                {namespaces.map((namespace) => (
                  <option key={namespace} value={namespace}>
                    {namespace}
                  </option>
                ))}
              </select>
            </>
          ) : null}
        </div>
        <div className="choice-result-count">
          검색 결과 {rows.length}개
          {rows.length > 240 ? " · 처음 240개 표시" : ""}
        </div>
        <div className="editor-choice-grid">
          {rows.slice(0, 240).map((entry) => {
            if (isCatalogSpecies(entry)) {
              return (
                <button
                  type="button"
                  className="editor-choice-card pokemon-catalog-card"
                  key={entry.id}
                  onClick={() => onChoose(entry.id)}
                >
                  <img
                    loading="lazy"
                    src={showdownSpriteUrl(entry.id)}
                    alt=""
                  />
                  <span>
                    <span className="catalog-card-title">
                      <strong>{entry.name}</strong>
                      <small>#{entry.number}</small>
                    </span>
                    <span className="catalog-tags">
                      {entry.types.map((type) => (
                        <TypeIcon type={type} withLabel key={type} />
                      ))}
                    </span>
                    <small>
                      HP {entry.baseStats.hp} · 공 {entry.baseStats.atk} · 방{" "}
                      {entry.baseStats.def} · 특공 {entry.baseStats.spa} · 특방{" "}
                      {entry.baseStats.spd} · 스피드 {entry.baseStats.spe}
                    </small>
                    <p>{entry.description || entry.englishName}</p>
                  </span>
                </button>
              );
            }
            if (isCatalogMove(entry)) {
              return (
                <button
                  type="button"
                  className="editor-choice-card"
                  key={entry.id}
                  onClick={() => onChoose(entry.id)}
                >
                  <span className="catalog-card-title">
                    <strong>{entry.name}</strong>
                    <TypeIcon type={entry.type} withLabel />
                  </span>
                  <span className="catalog-move-meta">
                    <MoveCategoryIcon category={entry.category} />
                    {moveCategoryNames[entry.category]} · 위력{" "}
                    {entry.power || "—"} · 명중{" "}
                    {entry.accuracy === true ? "필중" : entry.accuracy} · PP{" "}
                    {entry.pp}
                  </span>
                  <p>{entry.description || entry.englishName}</p>
                </button>
              );
            }
            return (
              <button
                type="button"
                className="editor-choice-card"
                key={entry.id}
                onClick={() => onChoose(entry.id)}
              >
                <span className="catalog-card-title">
                  <strong>{entry.name}</strong>
                  {"namespace" in entry ? (
                    <small>{entry.namespace}</small>
                  ) : (
                    <small>{entry.id}</small>
                  )}
                </span>
                <p>
                  {entry.description ||
                    ("englishName" in entry ? entry.englishName : entry.name)}
                </p>
                {"battleUsable" in entry ? (
                  <span className="catalog-tags">
                    <b>{entry.battleUsable ? "배틀 사용 가능" : "효과 미확인"}</b>
                    <b>{entry.category}</b>
                  </span>
                ) : null}
              </button>
            );
          })}
          {rows.length === 0 ? (
            <div className="choice-empty">조건에 맞는 항목이 없습니다.</div>
          ) : null}
        </div>
      </section>
    </div>
  );
}

function actorName(value: string | undefined) {
  return value?.replace(/^p[12][a-z]?: /, "") ?? "";
}

function showdownSpriteUrl(species: string, back = false) {
  return `/api/pokemon-sprites?species=${encodeURIComponent(species)}${back ? "&back=1" : ""}`;
}

function conditionPercent(condition: string | undefined) {
  const [health = ""] = String(condition ?? "").split(" ");
  const [currentText, maximumText] = health.split("/");
  const current = Number(currentText);
  const maximum = Number(maximumText);
  if (!Number.isFinite(current) || !Number.isFinite(maximum) || maximum <= 0) {
    return condition?.endsWith(" fnt") ? 0 : 100;
  }
  return Math.max(0, Math.min(100, (current / maximum) * 100));
}

function conditionNumbers(condition: string | undefined) {
  const [health = ""] = String(condition ?? "").split(" ");
  const [currentText, maximumText] = health.split("/");
  const current = Number(currentText);
  const maximum = Number(maximumText);
  return {
    current: Number.isFinite(current) ? current : null,
    maximum: Number.isFinite(maximum) ? maximum : null,
  };
}

function displayPokemonFromBattleState(
  species: string,
  condition: string | undefined,
  types: string[] = [],
): InteractivePokemon {
  const numbers = conditionNumbers(condition);
  return {
    slot: 0,
    ident: `p1a: ${species}`,
    species,
    types,
    teraType: "",
    terastallized: "",
    details: species,
    condition: {
      text: condition ?? "",
      current: numbers.current,
      maximum: numbers.maximum,
      percent: conditionPercent(condition),
      status: statusFromCondition(condition),
      fainted: condition?.endsWith(" fnt") ?? false,
    },
    active: true,
  };
}

const statRankNames: Record<string, string> = {
  atk: "공격",
  def: "방어",
  spa: "특수공격",
  spd: "특수방어",
  spe: "스피드",
  accuracy: "명중률",
  evasion: "회피율",
};

const battleStatOrder = [
  "atk",
  "def",
  "spa",
  "spd",
  "spe",
  "accuracy",
  "evasion",
] as const;

const battleFieldNames: Record<string, string> = {
  electricterrain: "전기필드",
  grassyterrain: "그래스필드",
  mistyterrain: "미스트필드",
  psychicterrain: "사이코필드",
  raindance: "비",
  sunnyday: "쾌청",
  desolateland: "강한 햇살",
  primordialsea: "강한 비",
  sandstorm: "모래바람",
  hail: "싸라기눈",
  snow: "설경",
  stealthrock: "스텔스록",
  spikes: "압정뿌리기",
  toxicspikes: "독압정",
  stickyweb: "끈적끈적네트",
  reflect: "리플렉터",
  lightscreen: "빛의장막",
  auroraveil: "오로라베일",
  tailwind: "순풍",
  safeguard: "신비의부적",
};

function normalizedFieldEffect(value: string | undefined) {
  return String(value ?? "")
    .replace(/^(?:move|ability):\s*/i, "")
    .replace(/\s+\[[^\]]+\].*$/, "")
    .trim();
}

function battleFieldLabel(effect: string) {
  const id = dexId(effect);
  const globalFieldNames: Record<string, string> = {
    trickroom: "트릭룸",
    wonderroom: "원더룸",
    magicroom: "매직룸",
    gravity: "중력",
  };
  return battleFieldNames[id] ?? globalFieldNames[id] ?? effect;
}

function isGlobalBattleField(effect: string, condition?: string) {
  const id = dexId(effect);
  const kind = dexId(condition);
  return (
    kind === "weather" ||
    kind === "terrain" ||
    kind === "pseudoweather" ||
    id.endsWith("terrain") ||
    [
      "trickroom",
      "wonderroom",
      "magicroom",
      "gravity",
      "watersport",
      "mudsport",
    ].includes(id)
  );
}

function activeBattleFields(events: BattleEvent[]) {
  let weather = "";
  const global = new Set<string>();
  const playerSide = new Set<string>();
  const opponentSide = new Set<string>();

  for (const event of events) {
    if (event.type === "weather") {
      const nextWeather = normalizedFieldEffect(event.detail);
      if (dexId(nextWeather) === "none") {
        weather = "";
      } else if (nextWeather && dexId(nextWeather) !== "upkeep") {
        weather = nextWeather;
      }
      continue;
    }
    if (!["field_started", "field_ended"].includes(event.type)) continue;

    const legacyGlobalEffect =
      !event.detail && !event.actor?.startsWith("p") ? event.actor : undefined;
    const effect = normalizedFieldEffect(event.detail || legacyGlobalEffect);
    if (!effect) continue;
    const side = isGlobalBattleField(effect, event.condition)
      ? global
      : event.actor?.startsWith("p1")
        ? playerSide
        : event.actor?.startsWith("p2")
          ? opponentSide
          : global;

    if (event.type === "field_started") {
      side.add(effect);
    } else {
      global.delete(effect);
      playerSide.delete(effect);
      opponentSide.delete(effect);
    }
  }

  const terrain = [...global].find((effect) =>
    dexId(effect).endsWith("terrain"),
  );
  return {
    weather,
    terrain: terrain ?? "",
    global: [...global],
    playerSide: [...playerSide],
    opponentSide: [...opponentSide],
  };
}

function activeSpeciesBySide(events: BattleEvent[], side: "p1" | "p2") {
  let species = "";
  for (const event of events) {
    if (!event.actor?.startsWith(side)) continue;
    if (event.type === "switch" || event.type === "mega_evolution") {
      species = event.detail || actorName(event.actor);
    }
  }
  return species;
}

function latestBattlingSpeciesBySide(events: BattleEvent[], side: "p1" | "p2") {
  let species = "";
  for (const event of events) {
    if (!event.actor?.startsWith(side)) continue;
    const actorSpecies = actorName(event.actor);
    if (event.type === "switch") {
      species = event.detail || actorSpecies;
    } else if (event.type === "mega_evolution") {
      species = event.detail || species || actorSpecies;
    } else if (
      [
        "move",
        "damage",
        "heal",
        "faint",
        "status",
        "status_cured",
        "stat_up",
        "stat_down",
        "stat_set",
        "ability",
        "item",
        "item_consumed",
        "activated",
      ].includes(event.type)
    ) {
      species = actorSpecies || species;
    }
  }
  return species;
}

function latestConditionBySide(events: BattleEvent[], side: "p1" | "p2") {
  let condition = "";
  for (const event of events) {
    if (!event.actor?.startsWith(side)) continue;
    if (event.type === "switch" && event.condition) {
      condition = event.condition;
    } else if (event.type === "damage" || event.type === "heal") {
      condition = event.condition ?? condition;
    } else if (event.type === "faint") {
      condition = "0 fnt";
    }
  }
  return condition;
}

function activeGimmickState(events: BattleEvent[], side: "p1" | "p2") {
  const state = { mega: false, dynamax: false, tera: "" };
  for (const event of events) {
    if (!event.actor?.startsWith(side)) continue;
    if (event.type === "switch") {
      state.mega = dexId(event.detail).includes("mega");
      state.dynamax = false;
      state.tera = "";
    } else if (event.type === "mega_evolution") {
      state.mega = true;
    } else if (event.type === "dynamax_started") {
      state.dynamax = true;
    } else if (event.type === "dynamax_ended") {
      state.dynamax = false;
    } else if (event.type === "terastallized") {
      state.tera = event.detail ?? "";
    }
  }
  return state;
}

function usedGimmicksBySide(events: BattleEvent[], side: "p1" | "p2") {
  const used = new Set<BattleGimmick>();
  for (const event of events) {
    if (!event.actor?.startsWith(side)) continue;
    if (event.type === "mega_evolution") used.add("mega");
    if (event.type === "z_power") used.add("zmove");
    if (event.type === "dynamax_started") used.add("dynamax");
    if (event.type === "terastallized") used.add("terastallize");
  }
  return used;
}

function GimmickStateBadges({
  state,
}: {
  state: { mega: boolean; dynamax: boolean; tera: string };
}) {
  if (!state.mega && !state.dynamax && !state.tera) return null;
  return (
    <div className="gimmick-state-badges">
      {state.mega ? <span className="mega">MEGA</span> : null}
      {state.dynamax ? <span className="dynamax">DYNAMAX</span> : null}
      {state.tera ? <span className="tera">TERA · {state.tera}</span> : null}
    </div>
  );
}

function activeStatRanks(events: BattleEvent[], side: "p1" | "p2") {
  const ranks: Record<string, number> = {};
  for (const event of events) {
    if (!event.actor?.startsWith(side)) continue;
    if (event.type === "switch") {
      for (const stat of Object.keys(ranks)) delete ranks[stat];
      continue;
    }
    if (!["stat_up", "stat_down", "stat_set"].includes(event.type)) continue;
    const stat = event.detail ?? "";
    const amount = Number(event.condition ?? 0);
    if (!stat || !Number.isFinite(amount)) continue;
    if (event.type === "stat_set") {
      ranks[stat] = amount;
    } else {
      ranks[stat] = Math.max(
        -6,
        Math.min(
          6,
          (ranks[stat] ?? 0) + (event.type === "stat_up" ? amount : -amount),
        ),
      );
    }
  }
  return Object.entries(ranks).filter(([, rank]) => rank !== 0);
}

function StatRankPanel({
  ranks,
}: {
  ranks: Array<[string, number]>;
}) {
  if (ranks.length === 0) return null;
  const rankByStat = new Map(ranks);
  return (
    <div className="stat-rank-panel" aria-label="현재 능력치 랭크 변화">
      {battleStatOrder.filter((stat) => rankByStat.has(stat)).map((stat) => {
        const rank = rankByStat.get(stat)!;
        return (
          <div className="stat-rank-row" key={stat}>
            <span>{statRankNames[stat]}</span>
            <div aria-label={`${rank > 0 ? "+" : ""}${rank}단계`}>
              {Array.from({ length: 6 }, (_, index) => {
                const active = index < Math.abs(rank);
                return (
                  <i
                    className={
                      active ? (rank > 0 ? "raised" : "lowered") : "neutral"
                    }
                    key={index}
                  >
                    {active ? (rank > 0 ? "▲" : "▼") : "•"}
                  </i>
                );
              })}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function MultiBattleCommandPanel({
  request,
  busy,
  onAction,
  localization,
}: {
  request: NonNullable<InteractiveBattle["request"]>;
  busy: boolean;
  onAction: (action: InteractiveAction) => void;
  localization: LocalizationCatalog | null;
}) {
  const activeSlots = request.activeSlots ?? [];
  const requiredPositions = activeSlots
    .map((_, index) => index)
    .filter(
      (index) =>
        request.kind !== "force_switch" || request.forceSwitch?.[index] === true,
    );
  const [step, setStep] = useState(0);
  const [phase, setPhase] = useState<"action" | "target" | "review">("action");
  const [pendingMove, setPendingMove] = useState<InteractiveMove | null>(null);
  const [selectedGimmick, setSelectedGimmick] =
    useState<BattleGimmick | null>(null);
  const [draft, setDraft] = useState<Array<InteractiveSlotAction | null>>(
    () => activeSlots.map(() => null),
  );
  const activeIndex = requiredPositions[Math.min(step, requiredPositions.length - 1)] ?? 0;
  const current = activeSlots[activeIndex];
  const usedSwitches = new Set(
    draft
      .filter((action): action is InteractiveSlotAction => action?.type === "switch")
      .map((action) => action.slot),
  );
  const usedGimmicks = new Set(
    draft
      .filter((action): action is InteractiveSlotAction => Boolean(action?.gimmick))
      .map((action) => action.gimmick),
  );
  const needsTarget = (move: InteractiveMove) =>
    ["normal", "any", "adjacentFoe"].includes(move.target) &&
    (request.opponents?.length ?? 0) > 0;
  const targetOptions = (move: InteractiveMove) => {
    const opponents = request.opponents ?? [];
    if (move.target === "any" || activeSlots.length < 3) return opponents;
    const mirroredPosition = activeSlots.length - activeIndex;
    return opponents.filter(
      (opponent) => Math.abs(opponent.position - mirroredPosition) <= 1,
    );
  };

  const commit = (action: InteractiveSlotAction) => {
    const nextDraft = [...draft];
    nextDraft[activeIndex] = action;
    setDraft(nextDraft);
    setPendingMove(null);
    setSelectedGimmick(null);
    if (step + 1 >= requiredPositions.length) {
      setPhase("review");
    } else {
      setStep(step + 1);
      setPhase("action");
    }
  };

  const goBack = () => {
    if (phase === "target") {
      setPendingMove(null);
      setPhase("action");
      return;
    }
    if (phase === "review") {
      setStep(Math.max(0, requiredPositions.length - 1));
      setPhase("action");
      const nextDraft = [...draft];
      nextDraft[requiredPositions.at(-1) ?? 0] = null;
      setDraft(nextDraft);
      return;
    }
    if (step <= 0) return;
    const previousStep = step - 1;
    const nextDraft = [...draft];
    nextDraft[requiredPositions[previousStep]] = null;
    setDraft(nextDraft);
    setStep(previousStep);
  };

  const submit = () => {
    const actions = activeSlots.map(
      (_, index) =>
        draft[index] ?? ({ type: "switch", slot: 1 } as InteractiveSlotAction),
    );
    onAction({ type: "multi", actions });
  };
  const multiGimmickOptions = [
    [
      "mega",
      "메가진화",
      current?.gimmicks.canMegaEvo && !usedGimmicks.has("mega"),
    ],
    [
      "zmove",
      "Z파워",
      current?.gimmicks.zMoves.some(Boolean) &&
        !usedGimmicks.has("zmove"),
    ],
    [
      "dynamax",
      "다이맥스",
      current?.gimmicks.canDynamax && !usedGimmicks.has("dynamax"),
    ],
    [
      "terastallize",
      "테라스탈",
      Boolean(current?.gimmicks.canTerastallize) &&
        !usedGimmicks.has("terastallize"),
    ],
  ] as const;
  const activeMultiGimmickSelection =
    multiGimmickOptions.find(
      ([id, , available]) => id === selectedGimmick && available,
    )?.[0] ?? null;

  return (
    <section className="multi-command-panel">
      <header>
        <div>
          <span>MULTI BATTLE COMMAND</span>
          <strong>
            {phase === "review"
              ? "이번 턴의 행동을 확인하세요"
              : `${activeIndex + 1}번 포켓몬의 행동 선택`}
          </strong>
        </div>
        <small>
          {draft.filter(Boolean).length}/{requiredPositions.length} 선택 완료
        </small>
      </header>

      <div className="multi-active-lineup">
        {activeSlots.map((slot, index) => {
          const pokemon = slot.active;
          const selected = index === activeIndex && phase !== "review";
          return (
            <article
              className={`${selected ? "current" : ""} ${
                draft[index] ? "decided" : ""
              }`}
              key={`${pokemon?.ident ?? index}-${index}`}
            >
              {pokemon ? (
                <img
                  src={showdownSpriteUrl(pokemon.species)}
                  alt=""
                />
              ) : null}
              <div>
                <b>
                  {pokemon
                    ? localizedSpecies(localization, pokemon.species)
                    : `${index + 1}번 슬롯`}
                </b>
                <span>
                  {pokemon?.condition.current ?? 0}/{pokemon?.condition.maximum ?? 0}
                </span>
                <div>
                  {pokemon?.types.map((type) => (
                    <TypeIcon key={type} type={type} />
                  ))}
                </div>
              </div>
              <em>{draft[index] ? "선택 완료" : selected ? "선택 중" : "대기"}</em>
            </article>
          );
        })}
      </div>

      {phase === "review" ? (
        <div className="multi-action-review">
          {requiredPositions.map((position) => {
            const action = draft[position];
            const slot = activeSlots[position];
            const move =
              action?.type === "move"
                ? slot.moves.find((entry) => entry.slot === action.slot)
                : null;
            return (
              <div key={position}>
                <b>
                  {localizedSpecies(
                    localization,
                    slot.active?.species ?? `${position + 1}번`,
                  )}
                </b>
                <span>
                  {action?.type === "switch"
                    ? `${action.slot}번 포켓몬으로 교체`
                    : move
                      ? `${localizedMove(localization, move.id, move.name)} → ${
                          request.opponents?.find(
                            (opponent) => opponent.position === action?.target,
                          )?.species
                            ? localizedSpecies(
                                localization,
                                request.opponents?.find(
                                  (opponent) =>
                                    opponent.position === action?.target,
                                )?.species ?? "",
                              )
                            : "전체/자동 대상"
                        }`
                      : "행동 없음"}
                </span>
              </div>
            );
          })}
          <div className="multi-command-actions">
            <button type="button" onClick={goBack} disabled={busy}>
              ← 이전 선택
            </button>
            <button type="button" onClick={submit} disabled={busy}>
              {busy ? "처리 중…" : "행동 확정"}
            </button>
          </div>
        </div>
      ) : phase === "target" && pendingMove ? (
        <div className="multi-target-picker">
          <strong>
            {localizedMove(localization, pendingMove.id, pendingMove.name)}의 대상을
            선택하세요
          </strong>
          <div>
            {targetOptions(pendingMove).map((opponent) => (
              <button
                type="button"
                key={opponent.position}
                onClick={() =>
                  commit({
                    type: "move",
                    slot: pendingMove.slot,
                    target: opponent.position,
                    gimmick: activeMultiGimmickSelection ?? undefined,
                  })
                }
              >
                <img src={showdownSpriteUrl(opponent.species)} alt="" />
                <b>{localizedSpecies(localization, opponent.species)}</b>
                <span>상대 {opponent.position}번</span>
              </button>
            ))}
          </div>
          <button type="button" className="multi-back-button" onClick={goBack}>
            ← 기술 선택으로
          </button>
        </div>
      ) : (
        <div className="multi-action-picker">
          <div className="multi-gimmick-row">
            {multiGimmickOptions.map(([id, label, available]) => (
              <button
                type="button"
                key={String(id)}
                disabled={!available}
                className={activeMultiGimmickSelection === id ? "active" : ""}
                onClick={() =>
                  setSelectedGimmick(() =>
                    activeMultiGimmickSelection === id
                      ? null
                      : (id as BattleGimmick),
                  )
                }
              >
                {String(label)}
              </button>
            ))}
          </div>
          {request.kind === "force_switch" ? null : (
            <div className="multi-move-grid">
              {current?.moves.map((move) => {
                const unavailableForGimmick =
                  (activeMultiGimmickSelection === "zmove" &&
                    !current.gimmicks.zMoves[move.slot - 1]) ||
                  (activeMultiGimmickSelection === "dynamax" &&
                    !current.gimmicks.maxMoves[move.slot - 1]);
                return (
                  <button
                    type="button"
                    key={move.slot}
                    disabled={busy || move.disabled || unavailableForGimmick}
                    onClick={() => {
                      if (needsTarget(move)) {
                        setPendingMove(move);
                        setPhase("target");
                      } else {
                        commit({
                          type: "move",
                          slot: move.slot,
                          gimmick: activeMultiGimmickSelection ?? undefined,
                        });
                      }
                    }}
                  >
                    <span>{String(move.slot).padStart(2, "0")}</span>
                    <b>{localizedMove(localization, move.id, move.name)}</b>
                    <TypeIcon type={move.type} withLabel />
                    <small>
                      위력 {move.power || "—"} · PP {move.pp}/{move.maxPp}
                    </small>
                  </button>
                );
              })}
            </div>
          )}
          <div className="multi-switch-row">
            {request.switches.map((pokemon) => (
              <button
                type="button"
                key={pokemon.slot}
                disabled={busy || usedSwitches.has(pokemon.slot)}
                onClick={() => commit({ type: "switch", slot: pokemon.slot })}
              >
                <img src={showdownSpriteUrl(pokemon.species)} alt="" />
                <span>{localizedSpecies(localization, pokemon.species)}</span>
                <small>교체</small>
              </button>
            ))}
          </div>
          <button
            type="button"
            className="multi-back-button"
            onClick={goBack}
            disabled={step === 0}
          >
            ← 이전 포켓몬
          </button>
        </div>
      )}
    </section>
  );
}

function InteractiveArena({
  battle,
  busy,
  playbackMode,
  actionNotice,
  hpPreview,
  onAction,
  onPlaybackModeChange,
  onClose,
  localization,
  catalog,
  sharedI18n,
}: {
  battle: InteractiveBattle;
  busy: boolean;
  playbackMode: BattlePlaybackMode;
  actionNotice: BattleActionNotice | null;
  hpPreview: BattleHpPreview;
  onAction: (action: {
    type: "move" | "switch";
    slot: number;
    gimmick?: BattleGimmick;
  } | { type: "multi"; actions: InteractiveSlotAction[] }) => void;
  onPlaybackModeChange: (mode: BattlePlaybackMode) => void;
  onClose: () => void;
  localization: LocalizationCatalog | null;
  catalog: BattleCatalog | null;
  sharedI18n: SharedI18nCatalog | null;
}) {
  const [logTab, setLogTab] = useState<"battle" | "ai">("battle");
  const [showAiIntent, setShowAiIntent] = useState(false);
  const [showPokemonInfo, setShowPokemonInfo] = useState(false);
  const [gimmickSelection, setGimmickSelection] = useState<{
    requestId: number;
    value: BattleGimmick | null;
  }>({ requestId: -1, value: null });
  const request = battle.request;
  const active = request?.active;
  const isNativeBattle =
    battle.engine?.id === "cobbleverse-simple" ||
    battle.settings?.battleEngine === "cobbleverse";
  const playerUsedGimmicks = usedGimmicksBySide(battle.events, "p1");
  const selectedGimmick =
    gimmickSelection.requestId === request?.requestId
      ? gimmickSelection.value
      : null;
  const gimmickOptions: Array<{
    id: BattleGimmick;
    label: string;
    detail: string;
    available: boolean;
    reason: string;
  }> = request
    ? [
        {
          id: "mega",
          label: "메가진화",
          detail: "MEGA",
          available:
            request.gimmicks.canMegaEvo && !playerUsedGimmicks.has("mega"),
          reason: "현재 포켓몬에게 호환되는 메가스톤이 필요합니다.",
        },
        {
          id: "zmove",
          label: "Z파워",
          detail: "Z-POWER",
          available:
            request.gimmicks.zMoves.some(Boolean) &&
            !playerUsedGimmicks.has("zmove"),
          reason: "현재 기술과 호환되는 Z크리스탈이 필요합니다.",
        },
        {
          id: "dynamax",
          label: request.gimmicks.gigantamax ? "거다이맥스" : "다이맥스",
          detail: request.gimmicks.gigantamax ? "GIGANTAMAX" : "DYNAMAX",
          available: isNativeBattle
            ? !playerUsedGimmicks.has("dynamax")
            : request.gimmicks.canDynamax &&
              !playerUsedGimmicks.has("dynamax"),
          reason: isNativeBattle
            ? "이번 전투에서 플레이어가 이미 다이맥스를 사용했습니다."
            : "현재 Showdown 세대 규칙에서는 다이맥스를 선택할 수 없습니다.",
        },
        {
          id: "terastallize",
          label: "테라스탈",
          detail: request.gimmicks.canTerastallize || "TERASTAL",
          available:
            Boolean(request.gimmicks.canTerastallize) &&
            !playerUsedGimmicks.has("terastallize"),
          reason: "현재 포켓몬의 테라타입을 확인할 수 없습니다.",
        },
      ]
    : [];
  const activeGimmickSelection =
    gimmickOptions.find(
      (option) => option.id === selectedGimmick && option.available,
    )?.id ?? null;
  const opponentEvent = [...battle.events]
    .reverse()
    .find(
      (event) =>
        event.actor?.startsWith("p2") &&
        (event.type === "switch" ||
          event.type === "mega_evolution" ||
          event.type === "damage" ||
          event.type === "heal" ||
          event.type === "faint"),
    );
  const logTurns = battle.events
    .filter((event) => event.type !== "turn")
    .reduce<Array<{ turn: number; events: BattleEvent[] }>>((groups, event) => {
      const turn = event.turn || 0;
      const previous = groups.at(-1);
      if (previous?.turn === turn) {
        previous.events.push(event);
      } else {
        groups.push({ turn, events: [event] });
      }
      return groups;
    }, []);
  const finished = battle.status !== "awaiting_choice";
  const playerWon = finished && battle.winner === battle.sides[0].name;
  const opponentWon = finished && battle.winner === battle.sides[1].name;
  const latestPlayerSpecies = latestBattlingSpeciesBySide(battle.events, "p1");
  const latestOpponentSpecies = latestBattlingSpeciesBySide(battle.events, "p2");
  const opponentName =
    (playerWon ? latestOpponentSpecies : activeSpeciesBySide(battle.events, "p2")) ||
    actorName(opponentEvent?.actor) ||
    battle.sides[1].name;
  const opponentCondition = playerWon
    ? "0 fnt"
    : hpPreview.p2 ?? opponentEvent?.condition ?? latestConditionBySide(battle.events, "p2");
  const opponentHp = conditionPercent(opponentCondition);
  const playerDisplaySpecies =
    active?.species ||
    (opponentWon ? latestPlayerSpecies : latestPlayerSpecies || activeSpeciesBySide(battle.events, "p1"));
  const playerCondition = opponentWon
    ? "0 fnt"
    : hpPreview.p1 ?? active?.condition.text ?? latestConditionBySide(battle.events, "p1");
  const pokemonStatuses = statusByPokemon(battle.events);
  const opponentStatus =
    pokemonStatuses.get(dexId(opponentName)) ??
    statusFromCondition(opponentCondition);
  const playerStatus =
    active?.condition.status ??
    pokemonStatuses.get(dexId(active?.species)) ??
    statusFromCondition(playerCondition);
  const faintedOpponentSpecies = new Set(
    battle.events
      .filter((event) => event.type === "faint" && event.actor?.startsWith("p2"))
      .map((event) => dexId(actorName(event.actor))),
  );
  const playerRanks = activeStatRanks(battle.events, "p1");
  const opponentRanks = activeStatRanks(battle.events, "p2");
  const playerGimmickState = activeGimmickState(battle.events, "p1");
  const opponentGimmickState = activeGimmickState(battle.events, "p2");
  if (active?.terastallized) {
    playerGimmickState.tera = active.terastallized;
  }
  const fieldState = activeBattleFields(battle.events);
  const fieldVisualClasses = [
    fieldState.terrain ? `terrain-${dexId(fieldState.terrain)}` : "",
    fieldState.weather ? `weather-${dexId(fieldState.weather)}` : "",
  ]
    .filter(Boolean)
    .join(" ");
  const actionCopy = actionNotice
    ? actionNoticeCopy(localization, actionNotice.event)
    : null;
  const actionEvents = actionNotice?.events?.length
    ? actionNotice.events
    : actionNotice
      ? [actionNotice.event]
      : [];
  const actionMessages = actionEvents.map((event) =>
    pokemonBattleMessage(localization, event),
  );
  const actionSide = actionNotice?.event.actor?.startsWith("p2")
    ? "opponent"
    : "player";
  const playerFieldPokemon =
    request?.activeSlots
      ?.map((slot) => slot.active)
      .filter((pokemon): pokemon is InteractivePokemon => Boolean(pokemon)) ??
    (active
      ? [active]
      : playerDisplaySpecies
        ? [displayPokemonFromBattleState(playerDisplaySpecies, playerCondition)]
        : []);
  const opponentFieldPokemon =
    request?.opponents?.length
      ? request.opponents
      : [{ position: 1, species: opponentName, types: request?.opponent?.types ?? [] }];
  const playerInfo = mergedPokemonInfo({
    battle,
    sideIndex: 0,
    species: playerDisplaySpecies,
    active,
  });
  const opponentInfo = mergedPokemonInfo({
    battle,
    sideIndex: 1,
    species: opponentName,
    opponent: request?.opponent,
  });

  return (
    <section className="interactive-arena" aria-label="직접 조작 배틀">
      <header className="arena-header">
        <div>
          <p className="eyebrow">LIVE PVE BATTLE / TURN {battle.turns}</p>
          <h2>
            {battle.sides[0].name} <span>vs</span> {battle.sides[1].name}
          </h2>
        </div>
        <div className="arena-header-tools">
          <button
            className={showPokemonInfo ? "active" : ""}
            onClick={() => setShowPokemonInfo((value) => !value)}
            type="button"
          >
            정보
          </button>
          <div
            className="battle-speed-control"
            role="group"
            aria-label="턴 행동 재생 속도"
          >
            <span>배속</span>
            {(
              [
                ["instant", "즉시"],
                ["fast", "2×"],
                ["normal", "1×"],
              ] as const
            ).map(([mode, label]) => (
              <button
                className={playbackMode === mode ? "active" : ""}
                disabled={busy}
                key={mode}
                onClick={() => onPlaybackModeChange(mode)}
                type="button"
              >
                {label}
              </button>
            ))}
          </div>
          <button onClick={onClose}>
            {finished ? "닫기" : "기권하고 닫기"}
          </button>
        </div>
      </header>

      <div
        className={`battle-stage active-count-${Math.max(
          playerFieldPokemon.length,
          opponentFieldPokemon.length,
          1,
        )} ${finished ? "battle-finished" : ""} ${
          playerWon ? "player-won" : opponentWon ? "opponent-won" : ""
        }`}
      >
        <div
          className={`battle-field-visual ${fieldVisualClasses}`}
          aria-hidden="true"
        />
        {fieldState.weather ||
        fieldState.global.length ||
        fieldState.playerSide.length ||
        fieldState.opponentSide.length ? (
          <div className="battle-field-status" aria-label="현재 필드 효과">
            <div>
              {fieldState.weather ? (
                <span className="weather">
                  날씨 · {battleFieldLabel(fieldState.weather)}
                </span>
              ) : null}
              {fieldState.global.map((effect) => (
                <span className="global" key={effect}>
                  필드 · {battleFieldLabel(effect)}
                </span>
              ))}
            </div>
            {fieldState.playerSide.length ? (
              <div>
                <b>내 진영</b>
                {fieldState.playerSide.map((effect) => (
                  <span key={effect}>{battleFieldLabel(effect)}</span>
                ))}
              </div>
            ) : null}
            {fieldState.opponentSide.length ? (
              <div>
                <b>상대 진영</b>
                {fieldState.opponentSide.map((effect) => (
                  <span key={effect}>{battleFieldLabel(effect)}</span>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}
        <article className="combatant opponent-combatant">
          <div className="combatant-card">
            <span>AI OPPONENT</span>
            <h3>{localizedSpecies(localization, opponentName)}</h3>
            <GimmickStateBadges state={opponentGimmickState} />
            <div className="combatant-meta-row">
              <div className="combatant-types">
                {request?.opponent?.types.map((type) => (
                  <TypeIcon key={type} type={type} withLabel />
                ))}
              </div>
            </div>
            <div className="combatant-condition">
              <p>
                {opponentCondition
                  ? healthFromCondition(opponentCondition)
                  : "상대 포켓몬 대기 중"}
              </p>
              <StatusBadge status={opponentStatus} />
            </div>
            <div
              className={`hp-track opponent-hp ${
                actionNotice?.event.type === "damage" &&
                actionNotice.event.actor?.startsWith("p2")
                  ? "taking-damage"
                  : ""
              }`}
            >
              <i style={{ width: `${opponentHp}%` }} />
            </div>
          </div>
          <div
            className={`sprite-platform-group opponent-platform-group active-count-${opponentFieldPokemon.length} ${
              opponentRanks.length ? "has-stat-ranks" : ""
            }`}
            aria-label={`상대 활성 포켓몬 ${opponentFieldPokemon.length}마리`}
          >
            {opponentFieldPokemon.map((pokemon) => (
              <div
                className={`sprite-platform opponent-platform ${
                  opponentGimmickState.dynamax ? "dynamaxed" : ""
                } ${playerWon ? "fainted" : ""}`}
                key={`${pokemon.position}-${pokemon.species}`}
              >
                {/* Dynamic Showdown sprite URLs are intentionally rendered without Next image optimization. */}
                <img
                  src={showdownSpriteUrl(pokemon.species)}
                  alt={`${localizedSpecies(localization, pokemon.species)} 전면 스프라이트`}
                />
              </div>
            ))}
            <StatRankPanel ranks={opponentRanks} />
          </div>
        </article>
        <div className="battle-turn-marker">
          <small>TURN</small>
          <strong>{battle.turns || 1}</strong>
        </div>
        <div
          key={
            actionNotice
              ? `${actionNotice.event.turn}-${actionNotice.step}-${actionNotice.event.type}`
              : "waiting"
          }
          className={`battle-action-notice ${actionSide} ${
            playbackMode === "instant" ? "instant" : ""
          } ${actionNotice ? "visible" : ""}`}
          aria-live="assertive"
        >
          <small>
            {actionNotice
              ? actionNotice.events?.length
                ? `TURN ${actionNotice.event.turn}`
                : `TURN ACTION ${actionNotice.step}/${actionNotice.total}`
              : "LATEST ACTION"}
          </small>
          {actionMessages.length > 1 ? (
            <div className="battle-turn-messages">
              {actionMessages.map((message, index) => (
                <p key={`${actionEvents[index].type}-${index}`}>{message}</p>
              ))}
            </div>
          ) : (
            <>
              <strong>
                {actionMessages[0] ?? actionCopy?.title ?? "행동 대기 중"}
              </strong>
              {!actionNotice ? (
                <p>기술을 선택하면 이곳에 배틀 메시지가 표시됩니다.</p>
              ) : null}
            </>
          )}
        </div>
        <article className="combatant player-combatant">
          <div
            className={`sprite-platform-group player-platform-group active-count-${playerFieldPokemon.length} ${
              playerRanks.length ? "has-stat-ranks" : ""
            }`}
            aria-label={`플레이어 활성 포켓몬 ${playerFieldPokemon.length}마리`}
          >
            {playerFieldPokemon.map((pokemon, index) => (
              <div
                className={`sprite-platform player-platform ${
                  playerGimmickState.dynamax ? "dynamaxed" : ""
                }`}
                key={`${index}-${pokemon.species}`}
              >
                {/* Dynamic Showdown sprite URLs intentionally skip Next image optimization. */}
                <img
                  src={showdownSpriteUrl(pokemon.species, true)}
                  alt={`${localizedSpecies(localization, pokemon.species)} 후면 스프라이트`}
                />
              </div>
            ))}
            <StatRankPanel ranks={playerRanks} />
          </div>
          <div className="combatant-card">
            <span>PLAYER ACTIVE</span>
            <h3>
              {playerDisplaySpecies
                ? localizedSpecies(localization, playerDisplaySpecies)
                : battle.sides[0].name}
            </h3>
            <GimmickStateBadges state={playerGimmickState} />
            <div className="combatant-meta-row">
              <div className="combatant-types">
                {active?.types.map((type) => (
                  <TypeIcon key={type} type={type} withLabel />
                ))}
              </div>
            </div>
            <div className="combatant-condition">
              <p>
                {playerCondition
                  ? healthFromCondition(playerCondition)
                  : "전투 종료"}
              </p>
              <StatusBadge status={playerStatus} />
            </div>
            <div
              className={`hp-track ${
                actionNotice?.event.type === "damage" &&
                actionNotice.event.actor?.startsWith("p1")
                  ? "taking-damage"
                  : ""
              }`}
            >
              <i style={{ width: `${conditionPercent(playerCondition)}%` }} />
            </div>
          </div>
        </article>
        <div className="team-indicators opponent-team" aria-label="상대 파티">
          {battle.sides[1].team.map((pokemon) => {
            const fainted = faintedOpponentSpecies.has(dexId(pokemon.species));
            const isActive =
              !fainted && dexId(pokemon.species) === dexId(opponentName);
            const status = pokemonStatuses.get(dexId(pokemon.species));
            const statusLabel = status
              ? BATTLE_STATUSES[status].label
              : "";
            return (
              <span
                className={`${isActive ? "active" : ""} ${fainted ? "fainted" : ""}`}
                key={pokemon.slot}
                title={`${localizedSpecies(localization, pokemon.species)}${isActive ? " · 출전 중" : ""}${statusLabel ? ` · ${statusLabel}` : ""}${fainted ? " · 기절" : ""}`}
              >
                {/* Dynamic Showdown sprite URLs intentionally skip Next image optimization. */}
                <img
                  src={showdownSpriteUrl(pokemon.species)}
                  alt={localizedSpecies(localization, pokemon.species)}
                />
                <StatusBadge status={status} compact />
              </span>
            );
          })}
        </div>
        <div className="team-indicators player-team" aria-label="플레이어 파티">
          {battle.sides[0].team.map((pokemon) => {
            const condition = request?.team.find(
              (member) => member.slot === pokemon.slot,
            )?.condition;
            const fainted = condition?.fainted ?? false;
            const isActive =
              !fainted && dexId(pokemon.species) === dexId(playerDisplaySpecies);
            const status =
              condition?.status ??
              pokemonStatuses.get(dexId(pokemon.species));
            const statusLabel = status
              ? BATTLE_STATUSES[status].label
              : "";
            return (
              <span
                className={`${isActive ? "active" : ""} ${fainted ? "fainted" : ""}`}
                key={pokemon.slot}
                title={`${localizedSpecies(localization, pokemon.species)}${isActive ? " · 출전 중" : ""}${statusLabel ? ` · ${statusLabel}` : ""}${fainted ? " · 기절" : ""}`}
              >
                {/* Dynamic Showdown sprite URLs intentionally skip Next image optimization. */}
                <img
                  src={showdownSpriteUrl(pokemon.species)}
                  alt={localizedSpecies(localization, pokemon.species)}
                />
                <StatusBadge status={status} compact />
              </span>
            );
          })}
        </div>
      </div>

      {showPokemonInfo ? (
        <section className="battle-info-panel" aria-label="현재 포켓몬 상세 정보">
          <PokemonInfoPanel
            sideLabel="PLAYER"
            info={playerInfo}
            ranks={playerRanks}
            catalog={catalog}
            sharedI18n={sharedI18n}
            localization={localization}
          />
          <PokemonInfoPanel
            sideLabel="OPPONENT"
            info={opponentInfo}
            ranks={opponentRanks}
            catalog={catalog}
            sharedI18n={sharedI18n}
            localization={localization}
          />
        </section>
      ) : null}

      {finished ? (
        <div className="battle-finish-card">
          <span>{battle.status === "tie" ? "DRAW" : "BATTLE COMPLETE"}</span>
          <strong>
            {battle.winner ? `${battle.winner} 승리` : "무승부"}
          </strong>
          <small>{battle.turns}턴에 전투가 종료되었습니다.</small>
        </div>
      ) : request && (request.activeSlots?.length ?? 0) > 1 ? (
        <MultiBattleCommandPanel
          key={request.requestId}
          request={request}
          busy={busy}
          onAction={onAction}
          localization={localization}
        />
      ) : (
        <div className="battle-command-grid">
          <div className="move-command-panel">
            <div className="command-heading">
              <strong>
                {request?.kind === "force_switch"
                  ? "다음 포켓몬을 선택하세요"
                  : "기술을 선택하세요"}
              </strong>
              <span>
                {busy
                  ? "처리 중…"
                  : isNativeBattle
                    ? "YOUR COMMAND · COBBLEVERSE ENGINE"
                    : "YOUR COMMAND · SHOWDOWN ENGINE"}
              </span>
            </div>
            {request?.kind === "move" && gimmickOptions.length ? (
              <div className="battle-gimmick-controls" aria-label="배틀 기믹 선택">
                {gimmickOptions.map((gimmick) => (
                  <button
                    className={`${gimmick.id} ${
                    activeGimmickSelection === gimmick.id ? "active" : ""
                    } ${gimmick.available ? "" : "unavailable"}`}
                    disabled={busy || !gimmick.available}
                    key={gimmick.id}
                    onClick={() =>
                      setGimmickSelection({
                        requestId: request.requestId,
                        value:
                          activeGimmickSelection === gimmick.id
                            ? null
                            : gimmick.id,
                      })
                    }
                    type="button"
                    title={gimmick.available ? gimmick.label : gimmick.reason}
                  >
                    <span>{gimmick.detail}</span>
                    <strong>{gimmick.label}</strong>
                    {!gimmick.available ? <small>{gimmick.reason}</small> : null}
                  </button>
                ))}
              </div>
            ) : null}
            {request?.kind === "move" ? (
              <div className="move-buttons">
                {request.moves.map((move) => {
                  const zMove = request.gimmicks.zMoves[move.slot - 1];
                  const maxMove =
                    request.gimmicks.maxMoves[move.slot - 1] ??
                    (isNativeBattle
                      ? {
                          id: `max-${move.id}`,
                          move: `${request.gimmicks.gigantamax ? "G-Max" : "Max"} ${move.name}`,
                          target: move.target,
                        }
                      : null);
                  const maxMoveName = maxMove
                    ? localizedMove(localization, maxMove.id, maxMove.move)
                    : null;
                  const maxMoveDescription =
                    maxMove &&
                    localization?.moves[dexId(maxMove.id)]?.description;
                  const gimmickMoveName =
                    activeGimmickSelection === "zmove"
                      ? zMove?.move
                      : activeGimmickSelection === "dynamax"
                        ? maxMoveName
                        : null;
                  const unavailableForGimmick =
                    (activeGimmickSelection === "zmove" && !zMove) ||
                    (activeGimmickSelection === "dynamax" && !maxMove);
                  return (
                    <button
                      key={move.slot}
                      className={`move-card move-type-${move.type.toLowerCase()} ${
                        activeGimmickSelection
                          ? `gimmick-${activeGimmickSelection}`
                          : ""
                      }`}
                      disabled={busy || move.disabled || unavailableForGimmick}
                      onClick={() =>
                        onAction({
                          type: "move",
                          slot: move.slot,
                          gimmick: activeGimmickSelection ?? undefined,
                        })
                      }
                    >
                    <span>{String(move.slot).padStart(2, "0")}</span>
                    <div className="move-card-copy">
                      <strong>
                        {gimmickMoveName ??
                          localizedMove(localization, move.id, move.name)}
                      </strong>
                      <div className="move-facts">
                        <TypeIcon type={move.type} withLabel />
                        <MoveCategoryIcon category={move.category} />
                        <span>위력 {move.power || "—"}</span>
                        <span>
                          명중 {move.accuracy === true ? "필중" : move.accuracy}
                        </span>
                        {move.priority !== 0 ? (
                          <span>
                            우선도 {move.priority > 0 ? "+" : ""}
                            {move.priority}
                          </span>
                        ) : null}
                      </div>
                      <span
                        className={`effectiveness effectiveness-${move.effectiveness}`}
                      >
                        {effectivenessLabels[move.effectiveness]}
                        <small>타입 상성 기준</small>
                      </span>
                      <small className="move-description">
                        {activeGimmickSelection === "dynamax"
                          ? maxMoveDescription ??
                            dynamaxMoveDescription(
                              move,
                              localization,
                              Boolean(request.gimmicks.gigantamax),
                            )
                          : localization?.moves[dexId(move.id)]?.description ??
                            "등록된 한국어 기술 설명이 없습니다."}
                      </small>
                    </div>
                    <small className="move-pp">
                      PP {move.pp}/{move.maxPp}
                    </small>
                  </button>
                  );
                })}
              </div>
            ) : null}
            {request?.trapped ? (
              <p className="trapped-notice">현재 포켓몬은 교체할 수 없습니다.</p>
            ) : null}
          </div>

          <aside className="switch-panel">
            <div className="command-heading">
              <strong>파티 교체</strong>
              <span>{request?.switches.length ?? 0} AVAILABLE</span>
            </div>
            <div className="switch-list">
              {request?.switches.map((pokemon) => (
                <button
                  key={pokemon.slot}
                  disabled={busy}
                  onClick={() =>
                    onAction({ type: "switch", slot: pokemon.slot })
                  }
                >
                  <span className="switch-sprite">
                    {/* Dynamic Showdown sprite URLs intentionally skip Next image optimization. */}
                    <img
                      src={showdownSpriteUrl(pokemon.species)}
                      alt=""
                    />
                  </span>
                  <div className="switch-copy">
                    <strong>
                      {localizedSpecies(localization, pokemon.species)}
                    </strong>
                    <div className="switch-types">
                      {pokemon.types.map((type) => (
                        <TypeIcon key={type} type={type} withLabel />
                      ))}
                      <StatusBadge status={pokemon.condition.status} />
                    </div>
                    <small>
                      {healthFromCondition(pokemon.condition.text)}
                    </small>
                  </div>
                </button>
              ))}
              {request?.switches.length === 0 ? (
                <p>교체 가능한 포켓몬이 없습니다.</p>
              ) : null}
            </div>
            <div className="ai-intent-control">
              <button
                className={showAiIntent ? "active" : ""}
                type="button"
                aria-expanded={showAiIntent}
                onClick={() => setShowAiIntent((visible) => !visible)}
              >
                <span>AI</span>
                <strong>상대 기술·선택 정보</strong>
                <small>{showAiIntent ? "숨기기" : "보기"}</small>
              </button>
              {showAiIntent ? (
                <div className="ai-intent-moves">
                  {request?.opponent?.moves.length ? (
                    request.opponent.moves.map((move) => (
                      <article
                        className={move.selected ? "selected" : ""}
                        key={move.slot}
                      >
                        <header>
                          <strong>
                            {localizedMove(localization, move.id, move.name)}
                          </strong>
                          <b>{move.selected ? "이번 턴 선택" : "후보"}</b>
                        </header>
                        <div>
                          <TypeIcon type={move.type} withLabel />
                          <MoveCategoryIcon category={move.category} />
                          <span>위력 {move.power || "—"}</span>
                          <span>
                            명중 {move.accuracy === true ? "필중" : move.accuracy}
                          </span>
                          <span>
                            PP {move.pp}/{move.maxPp}
                          </span>
                          {move.priority !== 0 ? (
                            <span>
                              우선도 {move.priority > 0 ? "+" : ""}
                              {move.priority}
                            </span>
                          ) : null}
                        </div>
                      </article>
                    ))
                  ) : (
                    <p>현재 공개할 상대 기술 선택 정보가 없습니다.</p>
                  )}
                  {request?.opponent?.decision ? (
                    <section className="ai-decision-explanation">
                      <span>선택 판단</span>
                      <strong>
                        {localizedMove(
                          localization,
                          request.opponent.decision.chosenAction,
                          request.opponent.decision.chosenAction,
                        )}
                      </strong>
                      <p>{request.opponent.decision.reason}</p>
                      <small>
                        정책:{" "}
                        {request.opponent.decision.strategy === "random-baseline"
                          ? "시드 기반 랜덤 기준선"
                          : request.opponent.decision.strategy}
                      </small>
                    </section>
                  ) : null}
                </div>
              ) : null}
            </div>
          </aside>
        </div>
      )}

      <details className="battle-details">
        <summary>
          <span>
            <strong>로그 자세히 보기</strong>
            <small>
              배틀 이벤트 {battle.events.length}건 · AI 판단 로그{" "}
              {battle.aiTrace.length}건
            </small>
          </span>
          <b>펼치기</b>
        </summary>
        <div className="log-tabs" role="tablist" aria-label="전투 상세 로그">
          <button
            role="tab"
            aria-selected={logTab === "battle"}
            className={logTab === "battle" ? "active" : ""}
            onClick={() => setLogTab("battle")}
          >
            배틀 로그
            <span>{battle.events.length}</span>
          </button>
          <button
            role="tab"
            aria-selected={logTab === "ai"}
            className={logTab === "ai" ? "active" : ""}
            onClick={() => setLogTab("ai")}
          >
            AI 판단 로그
            <span>{battle.aiTrace.length}</span>
          </button>
        </div>
        {logTab === "battle" ? (
          <div className="plain-battle-log" aria-live="polite">
            {logTurns.map((group) => (
              <section key={group.turn}>
                <h4>{group.turn === 0 ? "배틀 시작" : `Turn ${group.turn}`}</h4>
                <div>
                  {group.events.map((event, index) => (
                    <BattleLogEventLine
                      event={event}
                      localization={localization}
                      key={`${event.type}-${index}`}
                    />
                  ))}
                </div>
              </section>
            ))}
          </div>
        ) : (
          <div className="ai-log-panel">
            {battle.aiTrace.length > 0 ? (
              battle.aiTrace.map((entry, index) => (
                <article key={`${entry.turn}-${entry.actor}-${index}`}>
                  <span>T{entry.turn}</span>
                  <div>
                    <strong>
                      {entry.actor} · {entry.chosenAction ?? "행동 선택"}
                    </strong>
                    <p>{entry.reason ?? "판단 근거가 기록되지 않았습니다."}</p>
                    <small>
                      {entry.strategy ? `전략 ${entry.strategy}` : "전략 미지정"}
                      {entry.score == null ? "" : ` · 평가 ${entry.score}`}
                    </small>
                  </div>
                </article>
              ))
            ) : (
              <div className="ai-log-empty">
                <span>AI</span>
                <strong>AI 판단 로그 연결 준비 완료</strong>
                <p>
                  향후 후보 행동, 예상 피해, 교체 가치, 승률 변화, 선택 전략과
                  최종 판단 근거가 턴별로 이곳에 표시됩니다.
                </p>
                <div>
                  <small>후보 행동 점수</small>
                  <small>선택한 전략 타입</small>
                  <small>예상 승률 변화</small>
                  <small>난수·난이도 보정</small>
                </div>
              </div>
            )}
          </div>
        )}
      </details>
    </section>
  );
}

export function BattleLab() {
  const [mode, setMode] = useState<BattleMode>("pve");
  const [partySource, setPartySource] = useState<PartySource>("custom");
  const [data, setData] = useState<TrainerIndex | null>(null);
  const [localization, setLocalization] =
    useState<LocalizationCatalog | null>(null);
  const [catalog, setCatalog] = useState<BattleCatalog | null>(null);
  const [sharedI18n, setSharedI18n] = useState<SharedI18nCatalog | null>(null);
  const [choiceTarget, setChoiceTarget] = useState<ChoiceTarget | null>(null);
  const [recentTrainerIds, setRecentTrainerIds] = useState<string[]>([]);
  const [partyOrders, setPartyOrders] = useState<Record<string, number[]>>({});
  const [loadError, setLoadError] = useState("");
  const [workspaceSettings, setWorkspaceSettings] =
    useState<LocalWorkspaceSettings | null>(null);
  const [workspaceDialogOpen, setWorkspaceDialogOpen] = useState(false);
  const [workspacePath, setWorkspacePath] = useState("");
  const [workspaceBusy, setWorkspaceBusy] = useState(false);
  const [workspaceError, setWorkspaceError] = useState("");
  const [customParty, setCustomParty] = useState(initialParty);
  const [playerPreset, setPlayerPreset] = useState("");
  const [opponentPreset, setOpponentPreset] = useState("");
  const [eveLeft, setEveLeft] = useState("");
  const [eveRight, setEveRight] = useState("");
  const [notice, setNotice] = useState("");
  const [seed, setSeed] = useState(20260724);
  const [levelMode, setLevelMode] = useState<LevelMode>("original");
  const [battleType, setBattleType] = useState<BattleType>("single");
  const [battleEngine, setBattleEngine] =
    useState<BattleEngineChoice>("showdown");
  const [gimmickRules, setGimmickRules] =
    useState<BattleGimmickRules>("gen9");
  const [aiDifficulty, setAiDifficulty] =
    useState<AiDifficulty>("standard");
  const [eveAiProfiles, setEveAiProfiles] = useState<[AiProfile, AiProfile]>([
    { difficulty: "standard", strategy: "balanced" },
    { difficulty: "standard", strategy: "aggressive" },
  ]);
  const [scenario, setScenario] = useState<BattleScenario | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [battle, setBattle] = useState<BattleResult | null>(null);
  const [runningBattle, setRunningBattle] = useState(false);
  const [interactiveBattle, setInteractiveBattle] =
    useState<InteractiveBattle | null>(null);
  const [interactiveBusy, setInteractiveBusy] = useState(false);
  const [playbackMode, setPlaybackMode] =
    useState<BattlePlaybackMode>("instant");
  const [actionNotice, setActionNotice] =
    useState<BattleActionNotice | null>(null);
  const [hpPreview, setHpPreview] = useState<BattleHpPreview>({});
  const playbackToken = useRef(0);

  useEffect(() => {
    fetch("/data/trainers.json")
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<TrainerIndex>;
      })
      .then(setData)
      .catch((error: Error) => setLoadError(error.message));
    fetch("/data/cobblemon-ko-kr.json")
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<LocalizationCatalog>;
      })
      .then(setLocalization)
      .catch(() => setLocalization(null));
    fetch("/api/battle-catalog")
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<BattleCatalog>;
      })
      .then(setCatalog)
      .catch(() => setCatalog(null));
    fetch(`/data/pokemon-i18n-ko.json?updated=${Date.now()}`, {
      cache: "no-store",
    })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<SharedI18nCatalog>;
      })
      .then(setSharedI18n)
      .catch(() => setSharedI18n(null));
    fetch("/api/local-workspace", { cache: "no-store" })
      .then((response) => response.json() as Promise<LocalWorkspaceResponse>)
      .then((result) => {
        if (result.ok && result.configured && result.settings) {
          setWorkspaceSettings(result.settings);
          setWorkspacePath(result.settings.workspacePath);
          return;
        }
        setWorkspacePath(result.previousPath ?? "");
        setWorkspaceError(result.message ?? "");
        setWorkspaceDialogOpen(true);
      })
      .catch(() => {
        setWorkspaceError("로컬 모드 폴더 설정 API에 연결하지 못했습니다.");
      });
    queueMicrotask(() => {
      try {
        const recent = JSON.parse(
          localStorage.getItem(RECENT_TRAINERS_KEY) ?? "[]",
        );
        if (Array.isArray(recent)) {
          setRecentTrainerIds(
            recent.filter(
              (entry): entry is string => typeof entry === "string",
            ),
          );
        }
        const storedPartyOrders = JSON.parse(
          localStorage.getItem(PARTY_ORDERS_KEY) ?? "{}",
        );
        if (
          storedPartyOrders &&
          typeof storedPartyOrders === "object" &&
          !Array.isArray(storedPartyOrders)
        ) {
          setPartyOrders(
            Object.fromEntries(
              Object.entries(storedPartyOrders)
                .filter(([, order]) => Array.isArray(order))
                .map(([trainerId, order]) => [
                  trainerId,
                  (order as unknown[])
                    .map(Number)
                    .filter(Number.isInteger),
                ]),
            ),
          );
        }
        const stored = JSON.parse(
          localStorage.getItem(LAST_BATTLE_KEY) ?? "null",
        ) as StoredBattleView | null;
        if (stored?.schemaVersion === 1 && stored.scenario) {
          setMode(stored.scenario.mode);
          setSeed(stored.scenario.seed);
          setLevelMode(stored.scenario.levelMode);
          setBattleType(stored.scenario.battleType);
          setBattleEngine(stored.scenario.battleEngine);
          setGimmickRules(stored.scenario.gimmickRules ?? "gen9");
          setAiDifficulty(stored.scenario.aiDifficulty);
          if (stored.scenario.aiProfiles?.length === 2) {
            setEveAiProfiles([
              stored.scenario.aiProfiles[0],
              stored.scenario.aiProfiles[1],
            ]);
          }
          setPartySource(stored.scenario.sides[0]?.source ?? "custom");
          if (stored.scenario.mode === "pve") {
            setPlayerPreset(stored.scenario.sides[0]?.trainerId ?? "");
            setOpponentPreset(stored.scenario.sides[1]?.trainerId ?? "");
            if (stored.scenario.sides[0]?.source === "custom") {
              const restoredTeam = stored.scenario.sides[0].team.map(
                (pokemon) => ({
                  species: pokemon.species,
                  level: pokemon.level,
                  ability: pokemon.ability ?? "",
                  heldItem: pokemon.heldItem ?? "",
                  dynamax:
                    pokemon.gimmicks?.dynamax === true ||
                    pokemon.gimmicks?.gmax === true,
                  gmax: pokemon.gimmicks?.gmax === true,
                  tera: pokemon.gimmicks?.tera ?? "",
                  moves: [...pokemon.moveset, "", "", "", ""].slice(0, 4),
                }),
              );
              setCustomParty(
                [
                  ...restoredTeam,
                  ...Array.from(
                    { length: Math.max(0, 6 - restoredTeam.length) },
                    emptyPokemon,
                  ),
                ].slice(0, 6),
              );
            }
          } else {
            setEveLeft(stored.scenario.sides[0]?.trainerId ?? "");
            setEveRight(stored.scenario.sides[1]?.trainerId ?? "");
          }
          setScenario(stored.scenario);
          if (stored.kind === "automatic") {
            setBattle(stored.battle);
          } else if (stored.battle.status === "awaiting_choice") {
            setInteractiveBattle(stored.battle);
          }
          setNotice(
            `마지막 전투를 복원했습니다 · ${new Date(stored.savedAt).toLocaleString("ko-KR")}`,
          );
        }
      } catch {
        localStorage.removeItem(RECENT_TRAINERS_KEY);
        localStorage.removeItem(LAST_BATTLE_KEY);
        localStorage.removeItem(PARTY_ORDERS_KEY);
      }
    });
  }, []);

  const saveWorkspace = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setWorkspaceBusy(true);
    setWorkspaceError("");
    try {
      const response = await fetch("/api/local-workspace", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ workspacePath }),
      });
      const result = (await response.json()) as LocalWorkspaceResponse;
      if (!result.ok || !result.settings) {
        setWorkspaceError(result.message ?? "모드 폴더를 저장하지 못했습니다.");
        return;
      }
      setWorkspaceSettings(result.settings);
      setWorkspacePath(result.settings.workspacePath);
      const localizationResponse = await fetch(
        `/data/cobblemon-ko-kr.json?updated=${Date.now()}`,
        { cache: "no-store" },
      );
      if (localizationResponse.ok) {
        setLocalization(
          (await localizationResponse.json()) as LocalizationCatalog,
        );
      }
      setWorkspaceDialogOpen(false);
      setNotice(
        `${result.settings.cobblemonVersion} 기준 데이터와 타입 아이콘을 갱신했습니다.`,
      );
    } catch {
      setWorkspaceError("모드 폴더 설정을 저장하지 못했습니다.");
    } finally {
      setWorkspaceBusy(false);
    }
  };

  const trainerById = useMemo(
    () => new Map(data?.trainers.map((trainer) => [trainer.id, trainer]) ?? []),
    [data],
  );
  const sortedTrainers = useMemo(() => {
    const recentRank = new Map(
      recentTrainerIds.map((trainerId, index) => [trainerId, index]),
    );
    return [...(data?.trainers ?? [])].sort((left, right) => {
      const leftRank = recentRank.get(left.id);
      const rightRank = recentRank.get(right.id);
      if (leftRank !== undefined || rightRank !== undefined) {
        if (leftRank === undefined) return 1;
        if (rightRank === undefined) return -1;
        return leftRank - rightRank;
      }
      return (
        right.entry.priority - left.entry.priority ||
        left.name.localeCompare(right.name, "ko")
      );
    });
  }, [data, recentTrainerIds]);
  const withPartyOrder = (trainer: Trainer | undefined) => {
    if (!trainer) return undefined;
    const order = partyOrders[trainer.id] ?? [];
    const slots = new Set(order);
    const team = [
      ...order
        .map((slot) => trainer.team.find((pokemon) => pokemon.slot === slot))
        .filter((pokemon): pokemon is Pokemon => Boolean(pokemon)),
      ...trainer.team.filter((pokemon) => !slots.has(pokemon.slot)),
    ];
    return { ...trainer, team };
  };
  const playerTrainer = withPartyOrder(trainerById.get(playerPreset));
  const opponentTrainer = withPartyOrder(trainerById.get(opponentPreset));
  const leftTrainer = withPartyOrder(trainerById.get(eveLeft));
  const rightTrainer = withPartyOrder(trainerById.get(eveRight));
  const requiredMemberCount =
    battleType === "triple" ? 3 : battleType === "double" ? 2 : 1;
  const formatSupported =
    battleEngine === "showdown" || battleType === "single";
  const customMemberCount = customParty.filter((pokemon) => pokemon.species.trim()).length;
  const customPartyReady =
    customMemberCount >= requiredMemberCount &&
    customParty
      .filter((pokemon) => pokemon.species.trim())
      .every(
        (pokemon) =>
          Number.isInteger(pokemon.level) &&
          pokemon.level >= 1 &&
          pokemon.level <= 100 &&
          pokemon.moves.some((move) => move.trim()),
      );
  const pveReady =
    formatSupported &&
    Boolean(opponentTrainer) &&
    (opponentTrainer?.team.length ?? 0) >= requiredMemberCount &&
    (partySource === "preset"
      ? (playerTrainer?.team.length ?? 0) >= requiredMemberCount
      : customPartyReady);
  const eveReady =
    formatSupported &&
    Boolean(leftTrainer && rightTrainer && eveLeft !== eveRight) &&
    (leftTrainer?.team.length ?? 0) >= requiredMemberCount &&
    (rightTrainer?.team.length ?? 0) >= requiredMemberCount;

  const rememberTrainer = (trainerId: string) => {
    if (!trainerId) return;
    setRecentTrainerIds((current) => {
      const next = [trainerId, ...current.filter((id) => id !== trainerId)].slice(
        0,
        12,
      );
      localStorage.setItem(RECENT_TRAINERS_KEY, JSON.stringify(next));
      return next;
    });
  };

  const selectTrainer = (
    setter: (trainerId: string) => void,
    trainerId: string,
  ) => {
    setter(trainerId);
    rememberTrainer(trainerId);
    setScenario(null);
    setBattle(null);
    setInteractiveBattle(null);
  };

  const moveTrainerMember = (
    trainer: Trainer | undefined,
    fromIndex: number,
    toIndex: number,
  ) => {
    if (
      !trainer ||
      fromIndex < 0 ||
      toIndex < 0 ||
      fromIndex >= trainer.team.length ||
      toIndex >= trainer.team.length
    ) {
      return;
    }
    const order = trainer.team.map((pokemon) => pokemon.slot);
    [order[fromIndex], order[toIndex]] = [order[toIndex], order[fromIndex]];
    setPartyOrders((current) => {
      const next = { ...current, [trainer.id]: order };
      localStorage.setItem(PARTY_ORDERS_KEY, JSON.stringify(next));
      return next;
    });
    setScenario(null);
    setBattle(null);
    setInteractiveBattle(null);
    setNotice(
      `${trainer.name} 엔트리 순서를 변경했습니다. ${toIndex === 0 ? "첫 번째 포켓몬이 선봉으로 출전합니다." : "시나리오를 다시 생성해 주세요."}`,
    );
  };

  const storeLastBattle = (view: StoredBattleView) => {
    try {
      localStorage.setItem(LAST_BATTLE_KEY, JSON.stringify(view));
    } catch {
      setNotice("전투는 완료됐지만 브라우저에 마지막 전투를 저장하지 못했습니다.");
    }
  };

  const chooseCatalogValue = (value: string) => {
    if (!choiceTarget) return;
    setCustomParty((current) =>
      current.map((pokemon, pokemonIndex) => {
        if (pokemonIndex !== choiceTarget.pokemonIndex) return pokemon;
        if (choiceTarget.kind === "move") {
          const moves = [...pokemon.moves];
          moves[choiceTarget.moveIndex] = value;
          return { ...pokemon, moves };
        }
        if (choiceTarget.kind === "pokemon") {
          const selectedSpecies = catalog?.species.find(
            (entry) => entry.id === value,
          );
          return {
            ...pokemon,
            species: value,
            ability: selectedSpecies?.abilities[0] ?? "",
          };
        }
        return { ...pokemon, [choiceTarget.kind === "item" ? "heldItem" : "ability"]: value };
      }),
    );
    setChoiceTarget(null);
    setScenario(null);
    setBattle(null);
  };

  const prepareTest = async () => {
    const ready = mode === "pve" ? pveReady : eveReady;
    if (!ready) {
      setNotice(
        !formatSupported
          ? "Cobbleverse 자체 엔진은 현재 싱글 배틀만 지원합니다."
          : `${battleType === "triple" ? "트리플" : battleType === "double" ? "더블" : "싱글"} 배틀에 필요한 양쪽 파티와 기술을 먼저 완성해 주세요.`,
      );
      return;
    }

    const customSide = {
      source: "custom",
      name: "Player",
      team: customParty.map((pokemon) => ({
        species: pokemon.species,
        level: pokemon.level,
        ability: pokemon.ability,
        heldItem: pokemon.heldItem,
        gimmicks: {
          dynamax: pokemon.dynamax,
          gmax: pokemon.gmax,
          tera: pokemon.tera || null,
        },
        moves: pokemon.moves,
      })),
    };
    const requestBody =
      mode === "pve"
        ? {
            mode,
            seed,
            levelMode,
            battleType,
            battleEngine,
            gimmickRules: battleEngine === "cobbleverse" ? "all" : gimmickRules,
            aiDifficulty,
            aiProfiles: [
              { difficulty: aiDifficulty, strategy: "balanced" },
              { difficulty: aiDifficulty, strategy: "balanced" },
            ],
            sides: [
              partySource === "preset"
                ? {
                    source: "preset",
                    trainerId: playerPreset,
                    teamOrder: playerTrainer?.team.map(
                      (pokemon) => pokemon.slot,
                    ),
                  }
                : customSide,
              {
                source: "preset",
                trainerId: opponentPreset,
                teamOrder: opponentTrainer?.team.map(
                  (pokemon) => pokemon.slot,
                ),
              },
            ],
          }
        : {
            mode,
            seed,
            levelMode,
            battleType,
            battleEngine,
            gimmickRules: battleEngine === "cobbleverse" ? "all" : gimmickRules,
            aiDifficulty: eveAiProfiles[0].difficulty,
            aiProfiles: eveAiProfiles,
            sides: [
              {
                source: "preset",
                trainerId: eveLeft,
                teamOrder: leftTrainer?.team.map((pokemon) => pokemon.slot),
              },
              {
                source: "preset",
                trainerId: eveRight,
                teamOrder: rightTrainer?.team.map((pokemon) => pokemon.slot),
              },
            ],
          };

    setPreparing(true);
    setScenario(null);
    setBattle(null);
    setInteractiveBattle(null);
    try {
      const response = await fetch("/api/scenarios", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(requestBody),
      });
      const result = (await response.json()) as ScenarioResponse;
      if (!result.ok) {
        setNotice(result.issues[0]?.message ?? "전투 구성을 검증하지 못했습니다.");
        return;
      }
      setScenario(result.scenario);
      setNotice(`시나리오 ${result.scenario.scenarioId}가 준비되었습니다.`);
    } catch {
      setNotice("시나리오 API에 연결하지 못했습니다. 로컬 서버 상태를 확인해 주세요.");
    } finally {
      setPreparing(false);
    }
  };

  const copyScenario = async () => {
    if (!scenario) return;
    await navigator.clipboard.writeText(JSON.stringify(scenario, null, 2));
    setNotice("시나리오 JSON을 클립보드에 복사했습니다.");
  };

  const downloadScenario = () => {
    if (!scenario) return;
    const blob = new Blob([`${JSON.stringify(scenario, null, 2)}\n`], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${scenario.scenarioId}.json`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const runBattle = async () => {
    if (!scenario) return;
    setRunningBattle(true);
    setBattle(null);
    setNotice("자동 대전을 실행하고 있습니다.");
    try {
      const response = await fetch("/api/battles", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(scenario),
      });
      const result = (await response.json()) as BattleResponse;
      if (!result.ok) {
        setNotice(result.issues[0]?.message ?? "전투를 실행하지 못했습니다.");
        return;
      }
      setBattle(result.battle);
      storeLastBattle({
        schemaVersion: 1,
        savedAt: new Date().toISOString(),
        kind: "automatic",
        scenario,
        battle: result.battle,
      });
      if (scenario.mode === "eve") {
        localStorage.setItem(
          EVE_REPORT_KEY,
          JSON.stringify({
            schemaVersion: 1,
            savedAt: new Date().toISOString(),
            scenario,
            battle: result.battle,
          }),
        );
        window.location.assign("/eve-report");
        return;
      }
      setNotice(
        result.battle.winner
          ? `${result.battle.winner} 승리 · ${result.battle.turns}턴`
          : `${result.battle.status} · ${result.battle.turns}턴`,
      );
    } catch {
      setNotice("전투 실행 API에 연결하지 못했습니다.");
    } finally {
      setRunningBattle(false);
    }
  };

  const downloadBattle = () => {
    if (!battle) return;
    const blob = new Blob([`${JSON.stringify(battle, null, 2)}\n`], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${battle.battleId}.json`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const startInteractive = async () => {
    if (!scenario || scenario.mode !== "pve") return;
    playbackToken.current += 1;
    setActionNotice(null);
    setHpPreview({});
    setInteractiveBusy(true);
    setNotice("직접 조작 배틀을 시작하고 있습니다.");
    try {
      const response = await fetch("/api/interactive-battles", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ operation: "start", scenario }),
      });
      const result = (await response.json()) as InteractiveResponse;
      if (!result.ok) {
        setNotice(result.issues[0]?.message ?? "배틀을 시작하지 못했습니다.");
        return;
      }
      setInteractiveBattle(result.battle);
      storeLastBattle({
        schemaVersion: 1,
        savedAt: new Date().toISOString(),
        kind: "interactive",
        scenario,
        battle: result.battle,
      });
      setNotice("배틀이 시작되었습니다. 사용할 기술을 선택하세요.");
    } catch {
      setNotice("대화형 배틀 API에 연결하지 못했습니다.");
    } finally {
      setInteractiveBusy(false);
    }
  };

  const chooseInteractiveAction = async (action: InteractiveAction) => {
    if (!interactiveBattle) return;
    const firstGimmick =
      action.type === "multi"
        ? action.actions.find((slotAction) => slotAction?.gimmick)?.gimmick
        : action.gimmick;
    if (firstGimmick && usedGimmicksBySide(interactiveBattle.events, "p1").has(firstGimmick)) {
      setNotice("이미 이번 전투에서 사용한 기믹입니다. 다른 행동을 선택해주세요.");
      return;
    }
    const previousBattle = interactiveBattle;
    const token = playbackToken.current + 1;
    playbackToken.current = token;
    setInteractiveBusy(true);
    try {
      const response = await fetch("/api/interactive-battles", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          operation: "choose",
          sessionId: interactiveBattle.sessionId,
          action,
        }),
      });
      const result = (await response.json()) as InteractiveResponse;
      if (!result.ok) {
        setNotice(result.issues[0]?.message ?? "행동을 처리하지 못했습니다.");
        return;
      }
      const events = newPlaybackEvents(previousBattle, result.battle);
      if (playbackMode === "instant") {
        setInteractiveBattle(result.battle);
        setHpPreview({});
        if (scenario && result.battle.status === "awaiting_choice") {
          storeLastBattle({
            schemaVersion: 1,
            savedAt: new Date().toISOString(),
            kind: "interactive",
            scenario,
            battle: result.battle,
          });
        }
        const latestEvent = events.at(-1);
        if (latestEvent) {
          setActionNotice({
            event: latestEvent,
            step: events.length,
            total: events.length,
            events,
          });
        }
      } else {
        for (const [index, event] of events.entries()) {
          if (playbackToken.current !== token) return;
          if (event.actor?.startsWith("p1") || event.actor?.startsWith("p2")) {
            const side = event.actor.startsWith("p1") ? "p1" : "p2";
            if (event.type === "switch") {
              setHpPreview((current) => {
                const next = { ...current };
                delete next[side];
                return next;
              });
            } else if (
              event.type === "damage" ||
              event.type === "heal" ||
              event.type === "faint"
            ) {
              setHpPreview((current) => ({
                ...current,
                [side]:
                  event.type === "faint"
                    ? event.condition || "0 fnt"
                    : event.condition,
              }));
            }
          }
          setActionNotice({
            event,
            step: index + 1,
            total: events.length,
          });
          await wait(playbackDelay(playbackMode));
        }
        if (playbackToken.current !== token) return;
        setInteractiveBattle(result.battle);
        if (scenario && result.battle.status === "awaiting_choice") {
          storeLastBattle({
            schemaVersion: 1,
            savedAt: new Date().toISOString(),
            kind: "interactive",
            scenario,
            battle: result.battle,
          });
        }
      }
      if (result.battle.status !== "awaiting_choice") {
        setNotice(
          result.battle.winner
            ? `${result.battle.winner} 승리 · ${result.battle.turns}턴`
            : `무승부 · ${result.battle.turns}턴`,
        );
      }
    } catch {
      setNotice("전투 행동 API에 연결하지 못했습니다.");
    } finally {
      if (playbackToken.current === token) {
        setInteractiveBusy(false);
      }
    }
  };

  const closeInteractive = async () => {
    const current = interactiveBattle;
    playbackToken.current += 1;
    setInteractiveBattle(null);
    setInteractiveBusy(false);
    setActionNotice(null);
    setHpPreview({});
    if (!current || current.status !== "awaiting_choice") return;
    try {
      await fetch("/api/interactive-battles", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          operation: "forfeit",
          sessionId: current.sessionId,
        }),
      });
      setNotice("직접 조작 배틀을 기권하고 종료했습니다.");
    } catch {
      setNotice("화면은 닫았지만 서버의 전투 종료 응답을 확인하지 못했습니다.");
    }
  };

  return (
    <main className="lab-shell">
      <header className="topbar">
        <a className="brand" href="#top" aria-label="Cobbleverse Battle Lab 홈">
          <span className="brand-mark" aria-hidden="true">
            CV
          </span>
          <span>
            <strong>Cobbleverse</strong>
            <small>Battle Lab · Alpha</small>
          </span>
        </a>
        <div className="topbar-actions">
          <button
            className={`workspace-source ${workspaceSettings ? "configured" : ""}`}
            onClick={() => {
              setWorkspaceError("");
              setWorkspaceDialogOpen(true);
            }}
          >
            <span>MOD SOURCE</span>
            <strong>
              {workspaceSettings
                ? `${workspaceSettings.modCount}개 모드`
                : "폴더 설정 필요"}
            </strong>
          </button>
          <div className="data-status" aria-live="polite">
            <span className={`status-dot ${data ? "online" : ""}`} />
            {loadError
              ? `데이터 오류: ${loadError}`
              : data
                ? `${data.trainerCount}개 트레이너 동기화됨`
                : "트레이너 데이터 불러오는 중"}
          </div>
        </div>
      </header>

      {workspaceDialogOpen ? (
        <div className="workspace-dialog-backdrop">
          <section
            className="workspace-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="workspace-dialog-heading"
          >
            <header>
              <div>
                <p className="eyebrow">LOCAL MOD WORKSPACE</p>
                <h2 id="workspace-dialog-heading">Cobbleverse 모드 폴더 설정</h2>
              </div>
              <button
                type="button"
                onClick={() => setWorkspaceDialogOpen(false)}
                aria-label="모드 폴더 설정 닫기"
              >
                닫기
              </button>
            </header>
            <form onSubmit={saveWorkspace}>
              <label htmlFor="workspace-path">
                Cobbleverse 작업공간 또는 mods 폴더
              </label>
              <input
                id="workspace-path"
                value={workspacePath}
                onChange={(event) => setWorkspacePath(event.target.value)}
                placeholder="예: G:\CobbleverseTrainerWebEditorWorkspace"
                spellCheck={false}
                autoFocus
              />
              <p>
                입력한 폴더는 이 컴퓨터의 로컬 설정에만 저장됩니다. `mods`
                폴더에서 Cobblemon 본체를 찾아 한국어 데이터와 타입 아이콘을
                자동으로 갱신합니다.
              </p>
              {workspaceSettings ? (
                <dl>
                  <div>
                    <dt>Cobblemon</dt>
                    <dd>{workspaceSettings.cobblemonVersion}</dd>
                  </div>
                  <div>
                    <dt>mods</dt>
                    <dd>{workspaceSettings.modCount}개 JAR</dd>
                  </div>
                </dl>
              ) : null}
              {workspaceError ? (
                <div className="workspace-error" role="alert">
                  {workspaceError}
                </div>
              ) : null}
              <footer>
                <button
                  type="button"
                  onClick={() => setWorkspaceDialogOpen(false)}
                >
                  나중에 설정
                </button>
                <button type="submit" disabled={workspaceBusy}>
                  {workspaceBusy ? "검증 및 동기화 중…" : "폴더 저장 및 동기화"}
                </button>
              </footer>
            </form>
          </section>
        </div>
      ) : null}

      {choiceTarget && catalog ? (
        <EditorChoiceDialog
          target={choiceTarget}
          catalog={catalog}
          party={customParty}
          onChoose={chooseCatalogValue}
          onClose={() => setChoiceTarget(null)}
        />
      ) : null}

      <section className="hero" id="top">
        <div>
          <p className="eyebrow">AI BATTLE WORKBENCH / 01</p>
          <h1>파티를 고르고,<br />전략을 시험할 준비를 합니다.</h1>
          <p className="hero-copy">
            RCT 트레이너 JSON을 공통 형식으로 읽고 PvE와 EvE 테스트 구성을
            빠르게 만듭니다. PvE에서는 기술과 교체를 직접 선택해 AI 트레이너와
            싸울 수 있고, EvE에서는 양쪽 AI의 자동 대전을 관찰할 수 있습니다.
          </p>
        </div>
        <div className="hero-metric">
          <span>DATASET</span>
          <strong>{data?.trainerCount ?? "—"}</strong>
          <small>normalized trainer definitions</small>
        </div>
      </section>

      <nav className="mode-tabs" aria-label="전투 테스트 모드">
        <button
          className={mode === "pve" ? "active" : ""}
          onClick={() => {
            setMode("pve");
            setNotice("");
            setScenario(null);
            setBattle(null);
            setInteractiveBattle(null);
          }}
        >
          <span>01</span>
          <strong>PvE 테스트</strong>
          <small>플레이어 파티 vs AI 트레이너</small>
        </button>
        <button
          className={mode === "eve" ? "active" : ""}
          onClick={() => {
            setMode("eve");
            setNotice("");
            setScenario(null);
            setBattle(null);
            setInteractiveBattle(null);
          }}
        >
          <span>02</span>
          <strong>EvE 테스트</strong>
          <small>AI 트레이너 vs AI 트레이너</small>
        </button>
      </nav>

      <section className="ai-config-panel" aria-labelledby="ai-config-heading">
        <div>
          <p className="eyebrow">AI CONFIGURATION</p>
          <h2 id="ai-config-heading">AI 설정</h2>
          <p>
            난이도 프로필과 전투 규칙을 계산할 엔진을 선택합니다. 설정은
            시나리오 JSON과 배틀 결과에 함께 기록됩니다.
          </p>
        </div>
        <label>
          <span>대결 타입</span>
          <select
            aria-label="대결 타입"
            value={battleType}
            onChange={(event) => {
              setBattleType(event.target.value as BattleType);
              setScenario(null);
              setBattle(null);
              setInteractiveBattle(null);
              setNotice("대결 타입이 변경되었습니다. 시나리오를 다시 생성해 주세요.");
            }}
          >
            <option value="single">1인 · 싱글 배틀</option>
            <option value="double">2인 · 더블 배틀</option>
            <option value="triple">3인 · 트리플 배틀</option>
          </select>
          <small>각 진영이 동시에 내보내는 포켓몬 수를 선택합니다.</small>
        </label>
        <label>
          <span>기믹 규칙</span>
          <select
            aria-label="기믹 규칙"
            value={battleEngine === "cobbleverse" ? "all" : gimmickRules}
            disabled={battleEngine === "cobbleverse"}
            onChange={(event) => {
              setGimmickRules(event.target.value as BattleGimmickRules);
              setScenario(null);
              setBattle(null);
              setInteractiveBattle(null);
              setNotice("기믹 규칙이 변경되었습니다. 시나리오를 다시 생성해 주세요.");
            }}
          >
            <option value="all">전체 기믹 · 메가 / Z파워 / 다이맥스 / 테라스탈</option>
            <option value="gen9">9세대 · 메가진화 / Z파워 / 테라스탈</option>
            <option value="gen8">8세대 · 메가진화 / Z파워 / 다이맥스</option>
          </select>
          <small>
            {battleEngine === "cobbleverse"
              ? "자체 엔진은 Cobblemon 규칙에 따라 네 가지 기믹을 모두 사용할 수 있습니다."
              : "Showdown 세대 규칙상 다이맥스와 테라스탈은 같은 전투에서 함께 사용할 수 없습니다."}
          </small>
        </label>
        {mode === "pve" ? (
          <label>
            <span>AI 수준</span>
            <select
              aria-label="AI 수준"
              value={aiDifficulty}
              onChange={(event) => {
                setAiDifficulty(event.target.value as AiDifficulty);
                setScenario(null);
                setBattle(null);
              }}
            >
              <option value="novice">초급 · 공격 위주와 의도적인 실수</option>
              <option value="standard">보통 · 기본 평가</option>
              <option value="advanced">상급 · 강한 행동 우선</option>
              <option value="expert">전문가 · 최선 행동 집중</option>
              <option value="cheater" disabled>
                치터 · 상대 행동 열람 (프로토콜 준비 중)
              </option>
            </select>
            <small>
              현재는 초기 휴리스틱 프로필이며 전략 탐색이 구현되면서 단계별로
              확장됩니다.
            </small>
          </label>
        ) : null}
        <label>
          <span>배틀 엔진</span>
          <select
            aria-label="배틀 엔진"
            value={battleEngine}
            onChange={(event) => {
              const nextEngine = event.target.value as BattleEngineChoice;
              setBattleEngine(nextEngine);
              if (nextEngine === "showdown" && gimmickRules === "all") {
                setGimmickRules("gen9");
              }
              setScenario(null);
              setBattle(null);
              setInteractiveBattle(null);
            }}
          >
            <option value="showdown">Pokémon Showdown 엔진</option>
            <option value="cobbleverse">Cobbleverse 자체 엔진 · 싱글 전용</option>
          </select>
          {battleEngine === "cobbleverse" && battleType !== "single" ? (
            <small className="config-warning">
              자체 엔진의 더블·트리플 규칙은 분리 구현 전이므로 Showdown 엔진을
              선택해 주세요.
            </small>
          ) : (
            <small>
              자체 엔진은 싱글 PvE 직접 조작과 자동 대전을 지원합니다. 상태기와
              특수 효과는 단계적으로 확장 중입니다.
            </small>
          )}
        </label>
      </section>

      {mode === "pve" ? (
        <section className="workspace" aria-labelledby="pve-heading">
          <div className="section-heading">
            <div>
              <p className="eyebrow">PLAYER VS ENVIRONMENT</p>
              <h2 id="pve-heading">PvE 매치 구성</h2>
            </div>
            <div className="source-toggle" aria-label="플레이어 파티 입력 방식">
              <button
                className={partySource === "custom" ? "active" : ""}
                onClick={() => setPartySource("custom")}
              >
                직접 구성
              </button>
              <button
                className={partySource === "preset" ? "active" : ""}
                onClick={() => setPartySource("preset")}
              >
                JSON에서 선택
              </button>
            </div>
          </div>

          <div className="match-column">
            <article className="side-panel player-panel">
              <div className="panel-title">
                <span>A</span>
                <div>
                  <small>PLAYER SIDE</small>
                  <h3>{partySource === "custom" ? "내 파티 직접 구성" : "준비된 파티 불러오기"}</h3>
                </div>
              </div>
              {partySource === "custom" ? (
                <>
                  <div className="party-progress">
                    <span>{customMemberCount}/6 슬롯 사용</span>
                    <div>
                      <i style={{ width: `${(customMemberCount / 6) * 100}%` }} />
                    </div>
                  </div>
                  <CustomPartyEditor
                    party={customParty}
                    onChange={setCustomParty}
                    localization={localization}
                    catalog={catalog}
                    onOpenChoice={setChoiceTarget}
                  />
                </>
              ) : (
                <>
                  <TrainerPicker
                    label="플레이어 프리셋"
                    trainers={sortedTrainers}
                    value={playerPreset}
                    onChange={(trainerId) =>
                      selectTrainer(setPlayerPreset, trainerId)
                    }
                    localization={localization}
                    recentIds={recentTrainerIds}
                    minimumTeamSize={requiredMemberCount}
                    selectedTrainer={playerTrainer}
                  />
                  <TeamStrip
                    trainer={playerTrainer}
                    emptyText="프리셋을 선택하면 파티 구성이 여기에 표시됩니다."
                    localization={localization}
                    onMove={(fromIndex, toIndex) =>
                      moveTrainerMember(playerTrainer, fromIndex, toIndex)
                    }
                  />
                </>
              )}
            </article>

            <div className="versus" aria-hidden="true">
              <span />
              <strong>VS</strong>
              <span />
            </div>

            <article className="side-panel opponent-panel">
              <div className="panel-title">
                <span>B</span>
                <div>
                  <small>AI OPPONENT</small>
                  <h3>상대 트레이너 선택</h3>
                </div>
              </div>
              <TrainerPicker
                label="상대 트레이너 JSON"
                trainers={sortedTrainers}
                value={opponentPreset}
                onChange={(trainerId) =>
                  selectTrainer(setOpponentPreset, trainerId)
                }
                localization={localization}
                recentIds={recentTrainerIds}
                minimumTeamSize={requiredMemberCount}
                selectedTrainer={opponentTrainer}
              />
              <TeamStrip
                trainer={opponentTrainer}
                emptyText="상대 트레이너를 선택하면 최대 6마리의 파티가 표시됩니다."
                localization={localization}
                onMove={(fromIndex, toIndex) =>
                  moveTrainerMember(opponentTrainer, fromIndex, toIndex)
                }
              />
            </article>
          </div>
        </section>
      ) : (
        <section className="workspace" aria-labelledby="eve-heading">
          <div className="section-heading">
            <div>
              <p className="eyebrow">ENGINE VS ENGINE</p>
              <h2 id="eve-heading">EvE 매치 구성</h2>
            </div>
            <p className="section-note">
              동일한 데이터셋에서 서로 다른 두 트레이너를 선택합니다.
            </p>
          </div>
          <div className="eve-grid">
            <article className="side-panel">
              <div className="panel-title">
                <span>A</span>
                <div><small>ENGINE A</small><h3>첫 번째 AI</h3></div>
              </div>
              <TrainerPicker
                label="엔진 A 트레이너"
                trainers={sortedTrainers}
                value={eveLeft}
                onChange={(trainerId) => selectTrainer(setEveLeft, trainerId)}
                localization={localization}
                recentIds={recentTrainerIds}
                minimumTeamSize={requiredMemberCount}
                selectedTrainer={leftTrainer}
              />
              <AiProfileControls
                side="A"
                profile={eveAiProfiles[0]}
                onChange={(profile) => {
                  setEveAiProfiles([profile, eveAiProfiles[1]]);
                  setScenario(null);
                  setBattle(null);
                }}
              />
              <TeamStrip
                trainer={leftTrainer}
                emptyText="첫 번째 AI 파티를 선택하세요."
                localization={localization}
                onMove={(fromIndex, toIndex) =>
                  moveTrainerMember(leftTrainer, fromIndex, toIndex)
                }
              />
            </article>
            <article className="side-panel">
              <div className="panel-title">
                <span>B</span>
                <div><small>ENGINE B</small><h3>두 번째 AI</h3></div>
              </div>
              <TrainerPicker
                label="엔진 B 트레이너"
                trainers={sortedTrainers}
                value={eveRight}
                onChange={(trainerId) => selectTrainer(setEveRight, trainerId)}
                localization={localization}
                recentIds={recentTrainerIds}
                minimumTeamSize={requiredMemberCount}
                selectedTrainer={rightTrainer}
              />
              <AiProfileControls
                side="B"
                profile={eveAiProfiles[1]}
                onChange={(profile) => {
                  setEveAiProfiles([eveAiProfiles[0], profile]);
                  setScenario(null);
                  setBattle(null);
                }}
              />
              <TeamStrip
                trainer={rightTrainer}
                emptyText="두 번째 AI 파티를 선택하세요."
                localization={localization}
                onMove={(fromIndex, toIndex) =>
                  moveTrainerMember(rightTrainer, fromIndex, toIndex)
                }
              />
            </article>
          </div>
        </section>
      )}

      {scenario ? (
        <aside className="scenario-panel" aria-labelledby="scenario-heading">
          <div className="scenario-header">
            <div>
              <p className="eyebrow">ENGINE INPUT / SCHEMA V{scenario.schemaVersion}</p>
              <h2 id="scenario-heading">전투 시나리오 준비 완료</h2>
              <span>{scenario.scenarioId}</span>
            </div>
            <button onClick={() => setScenario(null)} aria-label="시나리오 패널 닫기">
              닫기
            </button>
          </div>
          <div className="scenario-summary">
            <div>
              <span>{battle ? "RESULT" : "MODE"}</span>
              <strong>{battle ? battle.status.toUpperCase() : scenario.mode.toUpperCase()}</strong>
            </div>
            <div>
              <span>{battle ? "WINNER" : "SEED"}</span>
              <strong>{battle ? battle.winner ?? "DRAW" : scenario.seed}</strong>
            </div>
            <div>
              <span>{battle ? "TURNS" : "SIDE A"}</span>
              <strong>{battle ? battle.turns : scenario.sides[0].name}</strong>
            </div>
            <div>
              <span>{battle ? "ENGINE" : "SIDE B"}</span>
              <strong>{battle ? `${battle.engine.id} ${battle.engine.version}` : scenario.sides[1].name}</strong>
            </div>
          </div>
          {battle ? (
            <div className="battle-result">
              {battle.warnings.length > 0 ? (
                <div className="battle-warnings">
                  <strong>호환성 경고 {battle.warnings.length}건</strong>
                  {battle.warnings.map((entry, index) => (
                    <p key={`${entry.path}-${index}`}>{entry.message}</p>
                  ))}
                </div>
              ) : null}
              <div className="battle-timeline">
                {battle.events.map((event, index) => (
                  <div className={`battle-event ${event.type}`} key={`${event.turn}-${event.type}-${index}`}>
                    <span>{event.turn > 0 ? `T${event.turn}` : "PRE"}</span>
                    <strong>
                      {event.type === "turn"
                        ? event.label
                        : event.type === "win"
                          ? `${event.actor} 승리`
                          : event.type === "tie"
                            ? "무승부"
                          : localizedSpecies(
                              localization,
                              event.actor?.replace(/^p[12][a-z]?: /, ""),
                            ) || event.type}
                    </strong>
                    <small>
                      {localizedEventDetail(localization, event)}
                      {event.target
                        ? ` → ${localizedSpecies(
                            localization,
                            event.target.replace(/^p[12][a-z]?: /, ""),
                          )}`
                        : ""}
                      {event.condition ? ` · ${event.condition}` : ""}
                    </small>
                  </div>
                ))}
              </div>
            </div>
          ) : (
            <div className="scenario-matchup">
              {scenario.sides.map((side, sideIndex) => (
                <article key={`${side.name}-${sideIndex}`}>
                  <span>{sideIndex === 0 ? "SIDE A" : "SIDE B"}</span>
                  <h3>{side.name}</h3>
                  <div>
                    {side.team.map((pokemon) => (
                      <span key={pokemon.slot}>
                        <strong>
                          {localizedSpecies(
                            localization,
                            pokemon.resolvedSpecies ?? pokemon.species,
                          )}
                        </strong>
                        <small>Lv.{pokemon.level}</small>
                      </span>
                    ))}
                  </div>
                </article>
              ))}
              <details>
                <summary>원본 시나리오 JSON 보기</summary>
                <pre>{JSON.stringify(scenario, null, 2)}</pre>
              </details>
            </div>
          )}
          <div className="scenario-actions">
            {battle ? (
              <>
                <button onClick={() => setBattle(null)}>시나리오 보기</button>
                <button onClick={downloadBattle}>결과 다운로드</button>
              </>
            ) : (
              <>
                <button onClick={copyScenario}>JSON 복사</button>
                <button onClick={downloadScenario}>시나리오 다운로드</button>
                <button className="run-battle" onClick={runBattle} disabled={runningBattle}>
                  {runningBattle ? "대전 진행 중" : "자동 대전 실행"}
                </button>
              </>
              )}
            </div>
          </aside>
      ) : null}

      {interactiveBattle ? (
        <InteractiveArena
          battle={interactiveBattle}
          busy={interactiveBusy}
          playbackMode={playbackMode}
          actionNotice={actionNotice}
          hpPreview={hpPreview}
          onAction={chooseInteractiveAction}
          onPlaybackModeChange={setPlaybackMode}
          onClose={closeInteractive}
          localization={localization}
          catalog={catalog}
          sharedI18n={sharedI18n}
        />
      ) : null}

      <footer className="action-bar">
        <div>
          <span className={`readiness ${(mode === "pve" ? pveReady : eveReady) ? "ready" : ""}`}>
            {(mode === "pve" ? pveReady : eveReady) ? "READY" : "INCOMPLETE"}
          </span>
          <p aria-live="polite">
            {notice || "양쪽 파티를 구성하면 테스트 구성을 확정할 수 있습니다."}
          </p>
        </div>
        <div className="action-controls">
          <label>
            레벨 규칙
            <select
              value={levelMode}
              onChange={(event) => {
                setLevelMode(event.target.value as LevelMode);
                setScenario(null);
                setBattle(null);
                setNotice("레벨 규칙이 변경되었습니다. 시나리오를 다시 생성해 주세요.");
              }}
              disabled={Boolean(interactiveBattle) || preparing || runningBattle}
            >
              <option value="original">기본 모드 (원본 레벨)</option>
              <option value="level-50">50레벨</option>
              <option value="level-100">100레벨</option>
            </select>
          </label>
          <label>
            TEST SEED
            <input
              type="number"
              min="0"
              max="4294967295"
              value={seed}
              onChange={(event) => setSeed(Number(event.target.value))}
            />
          </label>
          <button className="primary-action" onClick={prepareTest} disabled={preparing}>
            {preparing ? "검증 중" : "시나리오 생성"} <span aria-hidden="true">→</span>
          </button>
          <button
            className="battle-start-action"
            onClick={
              scenario?.mode === "pve" &&
              (scenario.battleEngine === "showdown" ||
                scenario.battleType === "single")
                ? startInteractive
                : scenario
                  ? runBattle
                  : undefined
            }
            disabled={!scenario || interactiveBusy || runningBattle}
          >
            {interactiveBusy || runningBattle
              ? "배틀 준비 중"
              : scenario?.battleEngine === "cobbleverse" &&
                  scenario?.mode === "pve" &&
                  scenario?.battleType === "single"
                ? "자체 엔진 PvE 시작"
                : scenario?.battleType === "triple"
                  ? "트리플 직접 대전"
                  : scenario?.battleType === "double"
                    ? "더블 직접 대전"
                : "배틀 시작"}
            <span aria-hidden="true">▶</span>
          </button>
        </div>
      </footer>
    </main>
  );
}

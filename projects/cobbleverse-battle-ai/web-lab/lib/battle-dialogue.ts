export type BattleDialogueEvent = {
  turn: number;
  type: string;
  actor?: string;
  detail?: string;
  condition?: string;
  source?: string;
  target?: string;
  fromActor?: string;
  selection?: string;
  automatic?: boolean;
  forced?: boolean;
  remainingHp?: number;
  maximumHp?: number;
};

export type BattleDialogueContext = {
  speciesName?: (value: string) => string;
  moveName?: (value: string) => string;
  detailName?: (event: BattleDialogueEvent) => string;
  sourceName?: (value: string) => string;
  sideLabels?: Partial<Record<"p1" | "p2", string>>;
  overrides?: Partial<
    Record<string, (event: BattleDialogueEvent, context: BattleDialogueContext) => string>
  >;
};

export const BATTLE_EVENT_NAMES: Record<string, string> = {
  turn: "턴 시작",
  switch: "교체",
  move: "기술 사용",
  damage: "피해",
  damage_prevented: "피해 방지",
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
  item_removed: "도구 소모",
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

const STAT_NAMES: Record<string, string> = {
  atk: "공격",
  def: "방어",
  spa: "특수공격",
  spd: "특수방어",
  spe: "스피드",
  accuracy: "명중률",
  evasion: "회피율",
};

export function battleActorName(value: string | undefined) {
  return String(value ?? "").replace(/^p[12][a-z]?: /, "");
}

function actorSide(value: string | undefined): "p1" | "p2" | null {
  if (String(value ?? "").startsWith("p1")) return "p1";
  if (String(value ?? "").startsWith("p2")) return "p2";
  return null;
}

function hasFinalConsonant(value: string) {
  const character = value.trim().at(-1);
  if (!character) return false;
  const code = character.charCodeAt(0);
  if (code < 0xac00 || code > 0xd7a3) return false;
  return (code - 0xac00) % 28 !== 0;
}

export function koreanBattleParticle(
  value: string,
  consonantForm: string,
  vowelForm: string,
) {
  return hasFinalConsonant(value) ? consonantForm : vowelForm;
}

function resolvedActor(event: BattleDialogueEvent, context: BattleDialogueContext) {
  const raw = battleActorName(event.actor);
  const species = context.speciesName?.(raw) ?? raw;
  const side = actorSide(event.actor);
  return `${side ? context.sideLabels?.[side] ?? "" : ""}${species}`.trim();
}

function resolvedDetail(event: BattleDialogueEvent, context: BattleDialogueContext) {
  if (context.detailName) return context.detailName(event);
  if (!event.detail) return "";
  if (event.type === "move") return context.moveName?.(event.detail) ?? event.detail;
  if (event.type === "switch") {
    return context.speciesName?.(battleActorName(event.detail)) ?? battleActorName(event.detail);
  }
  return event.detail;
}

function hpSuffix(event: BattleDialogueEvent) {
  if (event.condition) return ` (${event.condition})`;
  if (Number.isFinite(event.remainingHp) && Number.isFinite(event.maximumHp)) {
    return ` (${event.remainingHp}/${event.maximumHp})`;
  }
  return "";
}

export function formatBattleDialogue(
  event: BattleDialogueEvent,
  context: BattleDialogueContext = {},
) {
  const override = context.overrides?.[event.type];
  if (override) return override(event, context);

  const actor = resolvedActor(event, context);
  const detail = resolvedDetail(event, context);
  const subject = `${actor || "포켓몬"}${koreanBattleParticle(
    actor,
    "이",
    "가",
  )}`;
  const topic = `${actor || "포켓몬"}${koreanBattleParticle(
    actor,
    "은",
    "는",
  )}`;
  const source = event.source
    ? context.sourceName?.(event.source) ?? event.source
    : "";

  switch (event.type) {
    case "turn":
      return `${event.turn}턴 시작`;
    case "move":
      return `${actor || "포켓몬"}의 ${detail || "기술"}!`;
    case "switch": {
      const incoming = actor || detail || "포켓몬";
      if (event.selection === "lead") return `${incoming}, 선봉으로 나왔다!`;
      if (
        event.selection === "faint" ||
        event.selection === "faint_replacement"
      ) {
        return `${incoming}, 쓰러진 포켓몬을 대신해 출전했다!`;
      }
      if (event.forced) return `${incoming}, 강제로 전장에 나왔다!`;
      return `${incoming}${koreanBattleParticle(incoming, "으로", "로")} 교체했다!`;
    }
    case "damage":
      return `${subject} ${source ? `${source}의 효과로 ` : ""}데미지를 입었다!${hpSuffix(event)}`;
    case "damage_prevented":
      return `${topic} ${detail || source || "버티는 효과"}로 공격을 버텼다!${hpSuffix(event)}`;
    case "heal":
      return `${actor || "포켓몬"}의 체력이 ${source ? `${source}의 효과로 ` : ""}회복되었다!${hpSuffix(event)}`;
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
      return `${actor || "포켓몬"}의 ${(STAT_NAMES[event.detail ?? ""] ?? detail) || "능력"}이 올랐다!`;
    case "stat_down":
      return `${actor || "포켓몬"}의 ${(STAT_NAMES[event.detail ?? ""] ?? detail) || "능력"}이 떨어졌다!`;
    case "stat_set":
      return `${actor || "포켓몬"}의 ${(STAT_NAMES[event.detail ?? ""] ?? detail) || "능력"}이 변했다!`;
    case "ability":
      return `${actor || "포켓몬"}의 특성 「${detail || "특성"}」!`;
    case "item":
      return `${subject} ${detail || "도구"}를 사용했다!`;
    case "item_consumed":
    case "item_removed":
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
      return `${topic} ${detail || "메가진화한 모습"}으로 메가진화했다!`;
    case "z_power":
      return `${actor || "포켓몬"}을 Z파워가 감쌌다!`;
    case "dynamax_started":
      return `${subject} ${detail ? "거다이맥스" : "다이맥스"}했다!`;
    case "dynamax_ended":
      return `${actor || "포켓몬"}의 다이맥스가 풀렸다!`;
    case "terastallized":
      return `${subject} ${detail || "새로운 타입"}으로 테라스탈했다!`;
    case "win":
      return `${event.actor || "플레이어"}의 승리!`;
    case "tie":
      return "승부가 나지 않았다!";
    default:
      return `${detail || event.condition || BATTLE_EVENT_NAMES[event.type] || "전투 상황"}!`;
  }
}

export const BATTLE_FORMATS = {
  single: {
    id: "single",
    activeSlots: 1,
    label: "싱글",
    showdownFormatId: "gen9customgame",
    supportsInteractive: true,
    supportsCobbleverse: true,
  },
  double: {
    id: "double",
    activeSlots: 2,
    label: "더블",
    showdownFormatId: "gen9doublescustomgame",
    supportsInteractive: true,
    supportsCobbleverse: false,
  },
  triple: {
    id: "triple",
    activeSlots: 3,
    label: "트리플",
    showdownFormatId: "gen9triples",
    supportsInteractive: true,
    supportsCobbleverse: false,
  },
};

export function battleFormat(value) {
  return BATTLE_FORMATS[String(value ?? "").toLowerCase()] ?? null;
}

export function requireBattleFormat(value) {
  const format = battleFormat(value);
  if (!format) throw new Error(`지원하지 않는 대결 타입입니다: ${value}`);
  return format;
}

export function showdownFormatId(format, gimmickRules = "gen9") {
  if (gimmickRules !== "gen8") return format.showdownFormatId;
  if (format.id === "single") return "gen8customgame";
  if (format.id === "double") return "gen8doublescustomgame";
  return format.showdownFormatId;
}

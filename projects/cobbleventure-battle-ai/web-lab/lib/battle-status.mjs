export const BATTLE_STATUSES = {
  brn: { label: "화상", shortLabel: "BRN" },
  par: { label: "마비", shortLabel: "PAR" },
  slp: { label: "수면", shortLabel: "SLP" },
  frz: { label: "얼음", shortLabel: "FRZ" },
  psn: { label: "독", shortLabel: "PSN" },
  tox: { label: "맹독", shortLabel: "TOX" },
};

export function normalizeBattleStatus(value) {
  const status = String(value ?? "").trim().toLowerCase();
  return Object.hasOwn(BATTLE_STATUSES, status) ? status : null;
}

export function statusFromCondition(condition) {
  const parts = String(condition ?? "").trim().split(/\s+/);
  return parts.map(normalizeBattleStatus).find(Boolean) ?? null;
}

export function healthFromCondition(condition) {
  return String(condition ?? "").trim().split(/\s+/)[0] ?? "";
}

export function statusByPokemon(events) {
  const statuses = new Map();
  for (const event of events) {
    const pokemon = String(event.actor ?? "")
      .replace(/^p[12][a-z]?: /, "")
      .toLowerCase()
      .replace(/[^a-z0-9]/g, "");
    if (!pokemon) continue;
    if (event.type === "status") {
      const status = normalizeBattleStatus(event.detail);
      if (status) statuses.set(pokemon, status);
    } else if (event.type === "status_cured" || event.type === "faint") {
      statuses.delete(pokemon);
    } else if (
      (event.type === "damage" || event.type === "heal") &&
      event.condition
    ) {
      const status = statusFromCondition(event.condition);
      if (status) statuses.set(pokemon, status);
    }
  }
  return statuses;
}

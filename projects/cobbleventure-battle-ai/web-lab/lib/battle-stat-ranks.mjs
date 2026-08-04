function cleanId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "");
}

export function activeStatRanks(events, side) {
  const ranks = {};
  for (const event of events) {
    if (event.type === "stat_reset_all") {
      for (const stat of Object.keys(ranks)) delete ranks[stat];
      continue;
    }
    if (!event.actor?.startsWith(side)) continue;
    if (event.type === "switch") {
      if (cleanId(event.source) !== "batonpass") {
        for (const stat of Object.keys(ranks)) delete ranks[stat];
      }
      continue;
    }
    if (event.type === "stat_reset") {
      for (const stat of Object.keys(ranks)) delete ranks[stat];
      continue;
    }
    if (event.type === "boosts_passed") {
      for (const stat of Object.keys(ranks)) delete ranks[stat];
      for (const [stat, rank] of Object.entries(event.boosts ?? {})) {
        if (!Number.isFinite(rank) || rank === 0) continue;
        ranks[stat] = Math.max(-6, Math.min(6, rank));
      }
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

function cleanType(value) {
  if (value && typeof value === "object") {
    return String(value.name ?? value.id ?? value.type ?? "").trim();
  }
  return String(value ?? "").trim();
}

export function explicitTeraType(member) {
  const properties =
    member?.properties && typeof member.properties === "object"
      ? member.properties
      : {};
  const pokemonProperties =
    member?.pokemonProperties && typeof member.pokemonProperties === "object"
      ? member.pokemonProperties
      : {};
  return [
    member?.gimmicks?.tera,
    member?.gimmicks?.teraType,
    member?.teraType,
    member?.tera_type,
    member?.teratype,
    properties.teraType,
    properties.tera_type,
    pokemonProperties.teraType,
    pokemonProperties.tera_type,
  ].map(cleanType).find(Boolean) ?? "";
}

export function seededNativeTeraType(
  types,
  seed,
  sideIndex,
  memberIndex,
) {
  const candidates = [
    ...new Set(
      (Array.isArray(types) ? types : [])
        .map(cleanType)
        .filter(Boolean),
    ),
  ];
  if (candidates.length === 0) return "Normal";
  const mixed =
    (Number(seed) >>> 0) +
    Math.imul(Number(sideIndex) + 1, 0x9e3779b1) +
    Math.imul(Number(memberIndex) + 1, 0x85ebca6b);
  return candidates[(mixed >>> 0) % candidates.length];
}

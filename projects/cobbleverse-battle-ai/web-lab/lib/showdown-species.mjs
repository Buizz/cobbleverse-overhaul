import { Dex } from "@pkmn/sim";

export function cleanSpeciesReference(value) {
  const raw = String(value ?? "").trim();
  const withoutNamespace = raw.includes(":") ? raw.split(":").at(-1) : raw;
  return withoutNamespace.replaceAll("_", "-");
}

export function resolveShowdownSpecies(value) {
  const source = cleanSpeciesReference(value);
  const species = Dex.species.get(source);
  if (!species.exists) {
    return {
      exists: false,
      source,
      showdownId: null,
      showdownName: null,
      spriteId: source.toLowerCase(),
      baseSpecies: null,
      forme: null,
    };
  }
  return {
    exists: true,
    source,
    showdownId: species.id,
    showdownName: species.name,
    spriteId: species.spriteid,
    baseSpecies: species.baseSpecies,
    forme: species.forme || null,
  };
}

const ASPECT_FORM_ALIASES = new Map([
  ["alolan", "alola"],
  ["galarian", "galar"],
  ["hisuian", "hisui"],
  ["paldean", "paldea"],
  ["blackfusion", "black"],
  ["whitefusion", "white"],
  ["rapidstrikestyle", "rapidstrike"],
  ["singlestrikestyle", "singlestrike"],
  ["shadowrider", "shadow"],
  ["icerider", "ice"],
  ["originforme", "origin"],
  ["therianforme", "therian"],
  ["incarnateforme", "incarnate"],
  ["ultrafusion", "ultra"],
  ["fanappliance", "fan"],
  ["frostappliance", "frost"],
  ["heatappliance", "heat"],
  ["mowappliance", "mow"],
  ["washappliance", "wash"],
  ["female", "f"],
  ["male", "m"],
]);

function aspectToken(value) {
  const id = Dex.toID(value);
  return ASPECT_FORM_ALIASES.get(id) ?? id;
}

/**
 * Cobblemon stores regional and special forms in `aspects`, while Showdown
 * encodes the same information in its species id.
 */
export function resolveShowdownMemberSpecies(member) {
  const direct = resolveShowdownSpecies(
    member?.resolvedSpecies ?? member?.species,
  );
  if (!direct.exists || direct.forme) return direct;

  const aspects = Array.isArray(member?.aspects)
    ? member.aspects.map(aspectToken).filter(Boolean)
    : [];
  if (aspects.length === 0) return direct;

  const base = Dex.species.get(direct.showdownName);
  const forms = Dex.species
    .all()
    .filter(
      (candidate) =>
        candidate.exists &&
        candidate.forme &&
        candidate.baseSpecies === base.name,
    );
  const scored = forms
    .map((candidate) => {
      const suffix = candidate.id.startsWith(base.id)
        ? candidate.id.slice(base.id.length)
        : Dex.toID(candidate.forme);
      const matched = aspects.filter((token) =>
        token.length === 1 ? suffix === token : suffix.includes(token),
      ).length;
      return { candidate, matched, suffix };
    })
    .filter(({ matched }) => matched > 0)
    .sort(
      (left, right) =>
        right.matched - left.matched ||
        left.suffix.length - right.suffix.length,
    );

  if (
    scored.length === 0 ||
    (scored[1] &&
      scored[0].matched === scored[1].matched &&
      scored[0].suffix.length === scored[1].suffix.length)
  ) {
    return direct;
  }
  return resolveShowdownSpecies(scored[0].candidate.name);
}

export function showdownSpriteId(value) {
  return resolveShowdownSpecies(value).spriteId;
}

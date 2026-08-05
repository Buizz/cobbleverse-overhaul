export const POKEMON_ENTRY_CLIPBOARD_SCHEMA = "cobbleventure:party-entry-clipboard";
export const POKEMON_ENTRY_CLIPBOARD_VERSION = 1;

const statAliases = {
  hp: ["hp"],
  attack: ["attack", "atk"],
  defense: ["defense", "defence", "def"],
  special_attack: ["special_attack", "specialAttack", "special_attack", "spa"],
  special_defense: ["special_defense", "specialDefense", "specialDefence", "spd"],
  speed: ["speed", "spe"],
};

function text(value) {
  return value == null ? "" : String(value).trim();
}

function compactId(value) {
  return text(value).toLowerCase().replace(/^.*:/, "").replace(/[^a-z0-9]+/g, "");
}

function clampInteger(value, fallback, minimum, maximum) {
  const number = Number(value);
  return Number.isFinite(number)
    ? Math.min(maximum, Math.max(minimum, Math.trunc(number)))
    : fallback;
}

function statValue(stats, aliases, fallback, maximum) {
  if (!stats || typeof stats !== "object" || Array.isArray(stats)) return fallback;
  for (const alias of aliases) {
    if (Object.hasOwn(stats, alias)) return clampInteger(stats[alias], fallback, 0, maximum);
  }
  return fallback;
}

function canonicalStats(stats, fallback, maximum) {
  return Object.fromEntries(
    Object.entries(statAliases).map(([name, aliases]) => [
      name,
      statValue(stats, aliases, fallback, maximum),
    ]),
  );
}

function catalogItem(value, items = []) {
  const wanted = compactId(value);
  if (!wanted) return null;
  return items.find(
    (item) => compactId(item.id) === wanted || compactId(item.shortId) === wanted,
  ) ?? null;
}

function itemResourceId(value, items = []) {
  const item = catalogItem(value, items);
  return item?.id ?? (text(value) || null);
}

function catalogSpecies(value, species = []) {
  const wanted = compactId(value);
  if (!wanted) return null;
  return species.find((entry) => compactId(entry.id) === wanted) ?? null;
}

function canonicalSpecies(raw, species = []) {
  const requestedForm = text(raw.form) || null;
  const selected = catalogSpecies(requestedForm || raw.resolvedSpecies || raw.species, species);
  if (selected) {
    const base = species.find((entry) => entry.number === selected.number && !entry.forme) ?? selected;
    return {
      species: `cobblemon:${base.id}`,
      form: selected.forme ? selected.id : requestedForm,
    };
  }
  const source = text(raw.resolvedSpecies || raw.species);
  return {
    species: source.includes(":") ? source : `cobblemon:${source}`,
    form: requestedForm,
  };
}

function canonicalGimmick(raw, heldItem, items = []) {
  const source = raw.gimmick && typeof raw.gimmick === "object" && !Array.isArray(raw.gimmick)
    ? raw.gimmick
    : null;
  if (["mega_evolution", "z_move"].includes(source?.type) && text(source?.item)) {
    return { type: source.type, item: itemResourceId(source.item, items) };
  }
  const item = catalogItem(heldItem, items);
  if (item?.category === "mega") return { type: "mega_evolution", item: item.id };
  if (item?.category === "z") return { type: "z_move", item: item.id };
  return null;
}

function rawParty(value) {
  if (Array.isArray(value)) return value;
  if (!value || typeof value !== "object") throw new Error("포켓몬 엔트리 JSON 객체가 필요합니다.");
  if (Array.isArray(value.pokemon)) return value.pokemon;
  if (Array.isArray(value.party)) return value.party;
  if (Array.isArray(value.team)) return value.team;
  if (Array.isArray(value.battle?.team)) return value.battle.team;
  if (value.species || value.resolvedSpecies) return [value];
  throw new Error("JSON에서 pokemon, party, team 또는 battle.team 배열을 찾지 못했습니다.");
}

function canonicalPokemon(raw, options = {}) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new Error("각 포켓몬은 JSON 객체여야 합니다.");
  }
  const speciesCatalog = options.species ?? [];
  const itemCatalog = options.items ?? [];
  const resolved = canonicalSpecies(raw, speciesCatalog);
  if (!compactId(resolved.species)) throw new Error("포켓몬 species가 비어 있습니다.");
  const rawHeldItem = raw.held_item ?? raw.heldItem ?? raw.item ?? null;
  const gimmick = canonicalGimmick(raw, rawHeldItem, itemCatalog);
  const moves = (Array.isArray(raw.moves) ? raw.moves : Array.isArray(raw.moveset) ? raw.moveset : [])
    .map(text)
    .filter(Boolean)
    .slice(0, 4);
  const gimmicks = raw.gimmicks && typeof raw.gimmicks === "object" && !Array.isArray(raw.gimmicks)
    ? raw.gimmicks
    : {};
  const battleOptions =
    raw.battle_options && typeof raw.battle_options === "object" && !Array.isArray(raw.battle_options)
      ? raw.battle_options
      : {};
  const tera = text(raw.tera_type ?? raw.tera ?? gimmicks.tera) || "auto";
  return {
    species: resolved.species,
    form: resolved.form,
    aspects: Array.isArray(raw.aspects) ? [...new Set(raw.aspects.map(text).filter(Boolean))] : [],
    level: clampInteger(raw.level, 50, 1, 100),
    gender: ["male", "female", "genderless", "random"].includes(raw.gender) ? raw.gender : "random",
    nature: text(raw.nature) || null,
    ability: text(raw.ability) || null,
    held_item: gimmick ? null : itemResourceId(rawHeldItem, itemCatalog),
    gimmick,
    moves,
    ivs: canonicalStats(raw.ivs, 31, 31),
    evs: canonicalStats(raw.evs, 0, 252),
    tera_type: tera === "" ? "auto" : tera.toLowerCase(),
    shiny: raw.shiny === true,
    gigantamax_factor:
      raw.gigantamax_factor === true || raw.gmax === true || gimmicks.gmax === true,
    battle_options: {
      force_dynamax:
        battleOptions.force_dynamax === true ||
        raw.dynamax === true ||
        raw.gmax === true ||
        gimmicks.dynamax === true ||
        gimmicks.gmax === true,
    },
  };
}

export function createPartyClipboardEntry(value, options = {}) {
  const pokemon = rawParty(value)
    .filter((member) => member && typeof member === "object" && text(member.species || member.resolvedSpecies))
    .slice(0, 6)
    .map((member) => canonicalPokemon(member, options));
  if (!pokemon.length) throw new Error("복사할 포켓몬이 한 마리 이상 필요합니다.");
  return {
    $schema: POKEMON_ENTRY_CLIPBOARD_SCHEMA,
    schema_version: POKEMON_ENTRY_CLIPBOARD_VERSION,
    pokemon,
  };
}

export function parsePartyClipboardText(source, options = {}) {
  let value;
  try {
    value = JSON.parse(text(source));
  } catch (error) {
    throw new Error(`클립보드 JSON을 읽을 수 없습니다: ${error.message}`);
  }
  if (value?.$schema === POKEMON_ENTRY_CLIPBOARD_SCHEMA && value.schema_version !== 1) {
    throw new Error(`지원하지 않는 엔트리 클립보드 버전입니다: ${value.schema_version}`);
  }
  return createPartyClipboardEntry(value, options);
}

export function toContentManagerParty(value, options = {}) {
  return createPartyClipboardEntry(value, options).pokemon.map((pokemon) => ({
    species: pokemon.species,
    level: pokemon.level,
    form: pokemon.form,
    aspects: pokemon.aspects,
    gender: pokemon.gender,
    nature: pokemon.nature,
    ability: pokemon.ability,
    held_item: pokemon.held_item,
    gimmick: pokemon.gimmick,
    moves: pokemon.moves.length ? pokemon.moves : ["tackle"],
    ivs: pokemon.ivs,
    evs: pokemon.evs,
    tera_type: pokemon.tera_type,
    shiny: pokemon.shiny,
    gigantamax_factor: pokemon.gigantamax_factor,
  }));
}

export function toBattleLabParty(value, options = {}) {
  return createPartyClipboardEntry(value, options).pokemon.map((pokemon) => ({
    species: text(pokemon.form || pokemon.species).replace(/^.*:/, ""),
    form: pokemon.form,
    aspects: pokemon.aspects,
    level: pokemon.level,
    gender: pokemon.gender,
    nature: pokemon.nature ?? "",
    ability: pokemon.ability ?? "",
    heldItem: pokemon.gimmick?.item ?? pokemon.held_item ?? "",
    gimmick: pokemon.gimmick,
    ivs: {
      hp: pokemon.ivs.hp,
      atk: pokemon.ivs.attack,
      def: pokemon.ivs.defense,
      spa: pokemon.ivs.special_attack,
      spd: pokemon.ivs.special_defense,
      spe: pokemon.ivs.speed,
    },
    evs: {
      hp: pokemon.evs.hp,
      atk: pokemon.evs.attack,
      def: pokemon.evs.defense,
      spa: pokemon.evs.special_attack,
      spd: pokemon.evs.special_defense,
      spe: pokemon.evs.speed,
    },
    dynamax: pokemon.battle_options.force_dynamax,
    gmax: pokemon.gigantamax_factor,
    tera: pokemon.tera_type === "auto" ? "" : pokemon.tera_type,
    shiny: pokemon.shiny,
    moves: [...pokemon.moves, "", "", "", ""].slice(0, 4),
  }));
}

export async function writeClipboardText(value) {
  if (globalThis.navigator?.clipboard?.writeText) {
    try {
      await globalThis.navigator.clipboard.writeText(value);
      return;
    } catch {
      // Fall through to the local document fallback.
    }
  }
  if (!globalThis.document) throw new Error("이 환경에서는 클립보드에 쓸 수 없습니다.");
  const textarea = globalThis.document.createElement("textarea");
  textarea.value = value;
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  globalThis.document.body.append(textarea);
  textarea.select();
  const copied = globalThis.document.execCommand?.("copy") === true;
  textarea.remove();
  if (!copied) throw new Error("클립보드 쓰기 권한을 사용할 수 없습니다.");
}

export async function readClipboardText() {
  if (globalThis.navigator?.clipboard?.readText) {
    try {
      const value = await globalThis.navigator.clipboard.readText();
      if (text(value)) return value;
    } catch {
      // Fall through to manual input.
    }
  }
  const value = globalThis.prompt?.("붙여넣을 포켓몬 엔트리 JSON을 입력하세요.");
  if (value == null) throw new Error("붙여넣기를 취소했습니다.");
  if (!text(value)) throw new Error("클립보드 JSON이 비어 있습니다.");
  return value;
}

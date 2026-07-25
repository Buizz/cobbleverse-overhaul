const MAX_MOVE_BY_TYPE = {
  normal: { id: "maxstrike", name: "Max Strike" },
  fire: { id: "maxflare", name: "Max Flare" },
  water: { id: "maxgeyser", name: "Max Geyser" },
  electric: { id: "maxlightning", name: "Max Lightning" },
  grass: { id: "maxovergrowth", name: "Max Overgrowth" },
  ice: { id: "maxhailstorm", name: "Max Hailstorm" },
  fighting: { id: "maxknuckle", name: "Max Knuckle" },
  poison: { id: "maxooze", name: "Max Ooze" },
  ground: { id: "maxquake", name: "Max Quake" },
  flying: { id: "maxairstream", name: "Max Airstream" },
  psychic: { id: "maxmindstorm", name: "Max Mindstorm" },
  bug: { id: "maxflutterby", name: "Max Flutterby" },
  rock: { id: "maxrockfall", name: "Max Rockfall" },
  ghost: { id: "maxphantasm", name: "Max Phantasm" },
  dragon: { id: "maxwyrmwind", name: "Max Wyrmwind" },
  dark: { id: "maxdarkness", name: "Max Darkness" },
  steel: { id: "maxsteelspike", name: "Max Steelspike" },
  fairy: { id: "maxstarfall", name: "Max Starfall" },
};

const MAX_MOVE_EFFECTS = {
  maxstrike: { boosts: { speed: -1 } },
  maxflare: { weather: "sunnyday" },
  maxgeyser: { weather: "raindance" },
  maxlightning: { terrain: "electricterrain" },
  maxovergrowth: { terrain: "grassyterrain" },
  maxhailstorm: { weather: "snow" },
  maxknuckle: { selfBoosts: { attack: 1 } },
  maxooze: { selfBoosts: { specialAttack: 1 } },
  maxquake: { selfBoosts: { specialDefence: 1 } },
  maxairstream: { selfBoosts: { speed: 1 } },
  maxmindstorm: { terrain: "psychicterrain" },
  maxflutterby: { boosts: { specialAttack: -1 } },
  maxrockfall: { weather: "sandstorm" },
  maxphantasm: { boosts: { defence: -1 } },
  maxwyrmwind: { boosts: { attack: -1 } },
  maxdarkness: { boosts: { specialDefence: -1 } },
  maxsteelspike: { selfBoosts: { defence: 1 } },
  maxstarfall: { terrain: "mistyterrain" },
};

const GMAX_MOVE_BY_SPECIES = {
  venusaur: { id: "gmaxvinelash", name: "G-Max Vine Lash", type: "grass" },
  charizard: { id: "gmaxwildfire", name: "G-Max Wildfire", type: "fire" },
  blastoise: { id: "gmaxcannonade", name: "G-Max Cannonade", type: "water" },
  butterfree: { id: "gmaxbefuddle", name: "G-Max Befuddle", type: "bug" },
  pikachu: { id: "gmaxvoltcrash", name: "G-Max Volt Crash", type: "electric" },
  meowth: { id: "gmaxgoldrush", name: "G-Max Gold Rush", type: "normal" },
  machamp: { id: "gmaxchistrike", name: "G-Max Chi Strike", type: "fighting" },
  gengar: { id: "gmaxterror", name: "G-Max Terror", type: "ghost" },
  kingler: { id: "gmaxfoamburst", name: "G-Max Foam Burst", type: "water" },
  lapras: { id: "gmaxresonance", name: "G-Max Resonance", type: "ice" },
  eevee: { id: "gmaxcuddle", name: "G-Max Cuddle", type: "normal" },
  snorlax: { id: "gmaxreplenish", name: "G-Max Replenish", type: "normal" },
  garbodor: { id: "gmaxmalodor", name: "G-Max Malodor", type: "poison" },
  melmetal: { id: "gmaxmeltdown", name: "G-Max Meltdown", type: "steel" },
  rillaboom: { id: "gmaxdrumsolo", name: "G-Max Drum Solo", type: "grass" },
  cinderace: { id: "gmaxfireball", name: "G-Max Fireball", type: "fire" },
  inteleon: { id: "gmaxhydrosnipe", name: "G-Max Hydrosnipe", type: "water" },
  corviknight: { id: "gmaxwindrage", name: "G-Max Wind Rage", type: "flying" },
  orbeetle: { id: "gmaxgravitas", name: "G-Max Gravitas", type: "psychic" },
  drednaw: { id: "gmaxstonesurge", name: "G-Max Stonesurge", type: "water" },
  coalossal: { id: "gmaxvolcalith", name: "G-Max Volcalith", type: "rock" },
  flapple: { id: "gmaxtartness", name: "G-Max Tartness", type: "grass" },
  appletun: { id: "gmaxsweetness", name: "G-Max Sweetness", type: "grass" },
  sandaconda: { id: "gmaxsandblast", name: "G-Max Sandblast", type: "ground" },
  toxtricity: { id: "gmaxstunshock", name: "G-Max Stun Shock", type: "electric" },
  centiskorch: { id: "gmaxcentiferno", name: "G-Max Centiferno", type: "fire" },
  hatterene: { id: "gmaxsmite", name: "G-Max Smite", type: "fairy" },
  grimmsnarl: { id: "gmaxsnooze", name: "G-Max Snooze", type: "dark" },
  alcremie: { id: "gmaxfinale", name: "G-Max Finale", type: "fairy" },
  copperajah: { id: "gmaxsteelsurge", name: "G-Max Steelsurge", type: "steel" },
  duraludon: { id: "gmaxdepletion", name: "G-Max Depletion", type: "dragon" },
  urshifu: { id: "gmaxoneblow", name: "G-Max One Blow", type: "dark" },
  urshifurapidstrike: {
    id: "gmaxrapidflow",
    name: "G-Max Rapid Flow",
    type: "water",
  },
};

function cleanId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
}

function withMaxMoveEffects(move) {
  return {
    ...move,
    ...(MAX_MOVE_EFFECTS[move.id] ?? {}),
  };
}

export function resolveNativeMaxMove(pokemon, move) {
  if (move?.category === "Status") {
    return { id: "maxguard", name: "Max Guard", volatileStatus: "protect" };
  }

  const type = cleanId(move?.type);
  const gigantamax =
    pokemon?.dynamaxMode === "gigantamax" ||
    pokemon?.gimmicks?.gigantamax === true;
  if (gigantamax) {
    const species = cleanId(pokemon?.id || pokemon?.name);
    const gmaxMove =
      GMAX_MOVE_BY_SPECIES[species] ??
      (species.startsWith("urshifurapidstrike")
        ? GMAX_MOVE_BY_SPECIES.urshifurapidstrike
        : null);
    if (gmaxMove?.type === type) {
      return withMaxMoveEffects({ id: gmaxMove.id, name: gmaxMove.name });
    }
  }

  return withMaxMoveEffects(MAX_MOVE_BY_TYPE[type] ?? MAX_MOVE_BY_TYPE.normal);
}

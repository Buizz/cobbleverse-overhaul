"use client";

export type BattleAudioSettings = {
  bgmEnabled: boolean;
  bgmVolume: number;
  sfxEnabled: boolean;
  sfxVolume: number;
};

type BattleAudioTrack = {
  id: string;
  name: string;
  volume: number;
  pack: string;
};

type BattleAudioCatalog = {
  ok: boolean;
  configured: boolean;
  events: Array<{ id: string; tracks: BattleAudioTrack[] }>;
  message?: string;
};

const STORAGE_KEY = "cobbleventure-battle-lab:battle-audio";
const SETTINGS_EVENT = "cobbleventure-battle-audio-settings";
const DEFAULT_SETTINGS: BattleAudioSettings = {
  bgmEnabled: false,
  bgmVolume: 0.55,
  sfxEnabled: false,
  sfxVolume: 0.65,
};
const SYNTHETIC_FALLBACKS: Record<
  string,
  [number, OscillatorType, number, number]
> = {
  damage: [145, "sawtooth", 0.11, 90],
  damage_prevented: [310, "triangle", 0.14, 480],
  heal: [670, "sine", 0.16, 920],
  super_effective: [660, "square", 0.1, 880],
  critical: [820, "triangle", 0.12, 1120],
  status: [250, "triangle", 0.16, 180],
  residual_damage: [205, "triangle", 0.13, 145],
  boost: [570, "sine", 0.12, 760],
  unboost: [410, "sine", 0.12, 240],
  faint: [150, "sawtooth", 0.24, 55],
  win: [660, "sine", 0.25, 1040],
};

let settingsSnapshot = DEFAULT_SETTINGS;
let settingsLoaded = false;
let player: HTMLAudioElement | null = null;
let soundEffectContext: AudioContext | null = null;
let activeMove = "";
let activeEvent = "";
let activeTrackVolume = 1;
let catalogPromise: Promise<BattleAudioCatalog> | null = null;

function clampedVolume(value: number) {
  return Math.max(0, Math.min(1, Number.isFinite(value) ? value : 0.55));
}

export function getBattleAudioSettings(): BattleAudioSettings {
  if (typeof window === "undefined") return DEFAULT_SETTINGS;
  if (settingsLoaded) return settingsSnapshot;
  try {
    const saved = JSON.parse(
      window.localStorage.getItem(STORAGE_KEY) ?? "null",
    ) as
      | (Partial<BattleAudioSettings> & {
          enabled?: boolean;
          volume?: number;
        })
      | null;
    settingsSnapshot = {
      bgmEnabled: Boolean(saved?.bgmEnabled ?? saved?.enabled),
      bgmVolume: clampedVolume(
        Number(saved?.bgmVolume ?? saved?.volume ?? DEFAULT_SETTINGS.bgmVolume),
      ),
      sfxEnabled: Boolean(saved?.sfxEnabled ?? saved?.enabled),
      sfxVolume: clampedVolume(
        Number(saved?.sfxVolume ?? saved?.volume ?? DEFAULT_SETTINGS.sfxVolume),
      ),
    };
  } catch {
    settingsSnapshot = DEFAULT_SETTINGS;
  }
  settingsLoaded = true;
  return settingsSnapshot;
}

export function getBattleAudioServerSettings() {
  return DEFAULT_SETTINGS;
}

export function setBattleAudioSettings(
  next: BattleAudioSettings,
): BattleAudioSettings {
  const normalized = {
    bgmEnabled: Boolean(next.bgmEnabled),
    bgmVolume: clampedVolume(next.bgmVolume),
    sfxEnabled: Boolean(next.sfxEnabled),
    sfxVolume: clampedVolume(next.sfxVolume),
  };
  settingsSnapshot = normalized;
  settingsLoaded = true;
  if (typeof window !== "undefined") {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(normalized));
    window.dispatchEvent(
      new CustomEvent(SETTINGS_EVENT, { detail: normalized }),
    );
  }
  if (player) {
    player.volume = clampedVolume(
      normalized.bgmVolume * activeTrackVolume,
    );
    if (!normalized.bgmEnabled) stopBattleMusic();
  }
  return normalized;
}

export function subscribeBattleAudioSettings(
  listener: (settings: BattleAudioSettings) => void,
) {
  const handleSettings = (event: Event) => {
    listener(
      (event as CustomEvent<BattleAudioSettings>).detail ??
        getBattleAudioSettings(),
    );
  };
  const handleStorage = (event: StorageEvent) => {
    if (event.key === STORAGE_KEY) {
      settingsLoaded = false;
      listener(getBattleAudioSettings());
    }
  };
  window.addEventListener(SETTINGS_EVENT, handleSettings);
  window.addEventListener("storage", handleStorage);
  return () => {
    window.removeEventListener(SETTINGS_EVENT, handleSettings);
    window.removeEventListener("storage", handleStorage);
  };
}

async function loadCatalog() {
  if (!catalogPromise) {
    catalogPromise = fetch("/api/local-audio/catalog", {
      cache: "no-store",
    }).then(async (response) => {
      const payload = (await response.json()) as BattleAudioCatalog;
      if (!response.ok || !payload.ok) {
        throw new Error(payload.message || "배틀 사운드를 불러오지 못했습니다.");
      }
      return payload;
    });
  }
  return catalogPromise;
}

export async function startBattleMusic(eventId: string) {
  const settings = getBattleAudioSettings();
  if (!settings.bgmEnabled) return { playing: false, reason: "disabled" };
  const catalog = await loadCatalog();
  const event = catalog.events.find((entry) => entry.id === eventId);
  if (!event?.tracks.length) {
    return {
      playing: false,
      reason: catalog.configured ? "missing_event" : "not_configured",
    };
  }
  if (player && activeEvent === eventId && !player.paused) {
    player.volume = clampedVolume(settings.bgmVolume * activeTrackVolume);
    return { playing: true };
  }

  stopBattleMusic();
  const track = event.tracks[Math.floor(Math.random() * event.tracks.length)];
  const audio = new Audio(
    `/api/local-audio/file?id=${encodeURIComponent(track.id)}`,
  );
  audio.loop = true;
  audio.preload = "auto";
  audio.volume = clampedVolume(settings.bgmVolume * track.volume);
  player = audio;
  activeEvent = eventId;
  activeTrackVolume = track.volume;
  try {
    await audio.play();
    return { playing: true, track };
  } catch (error) {
    if (player === audio) {
      player = null;
      activeEvent = "";
      activeTrackVolume = 1;
    }
    throw error;
  }
}

export function stopBattleMusic() {
  if (!player) return;
  player.pause();
  player.removeAttribute("src");
  player.load();
  player = null;
  activeEvent = "";
  activeTrackVolume = 1;
}

function eventId(value: string) {
  return value
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[^a-z0-9]+/g, "");
}

function browserAudioContext() {
  return (
    window.AudioContext ??
    (
      window as typeof window & {
        webkitAudioContext?: typeof AudioContext;
      }
    ).webkitAudioContext
  );
}

export async function unlockBattleSoundEffects() {
  if (typeof window === "undefined") return false;
  const BrowserAudioContext = browserAudioContext();
  if (!BrowserAudioContext) return false;
  if (!soundEffectContext || soundEffectContext.state === "closed") {
    soundEffectContext = new BrowserAudioContext();
  }
  await soundEffectContext.resume();
  return soundEffectContext.state === "running";
}

async function playCatalogEffect(candidates: string[], delayMs: number) {
  const catalog = await loadCatalog();
  const event = candidates
    .map((candidate) =>
      catalog.events.find((entry) => entry.id === candidate),
    )
    .find((entry) => entry?.tracks.length);
  if (!event) return false;
  const track = event.tracks[Math.floor(Math.random() * event.tracks.length)];
  const settings = getBattleAudioSettings();
  const audio = new Audio(
    `/api/local-audio/file?id=${encodeURIComponent(track.id)}`,
  );
  audio.preload = "auto";
  audio.volume = clampedVolume(settings.sfxVolume * track.volume);
  window.setTimeout(() => {
    void audio.play().catch(() => undefined);
  }, delayMs);
  return true;
}

async function playMoveEffectPhases(
  move: string,
  role: "actor" | "target",
  delayMs: number,
) {
  if (await playCatalogEffect([`move.${move}.${role}`], delayMs)) {
    return true;
  }
  const first = await playCatalogEffect(
    [`move.${move}.${role}_1`],
    delayMs,
  );
  const second = await playCatalogEffect(
    [`move.${move}.${role}_2`],
    delayMs + 75,
  );
  return first || second;
}

function playSyntheticEffect(type: string, delayMs: number) {
  const cue = SYNTHETIC_FALLBACKS[type];
  const settings = getBattleAudioSettings();
  if (!cue || !soundEffectContext) return;
  const [frequency, oscillatorType, duration, endFrequency] = cue;
  const start = soundEffectContext.currentTime + delayMs / 1000;
  const oscillator = soundEffectContext.createOscillator();
  const gain = soundEffectContext.createGain();
  oscillator.type = oscillatorType;
  oscillator.frequency.setValueAtTime(frequency, start);
  oscillator.frequency.exponentialRampToValueAtTime(
    endFrequency,
    start + duration,
  );
  gain.gain.setValueAtTime(0.0001, start);
  gain.gain.exponentialRampToValueAtTime(
    Math.max(0.0001, 0.075 * settings.sfxVolume),
    start + 0.012,
  );
  gain.gain.exponentialRampToValueAtTime(0.0001, start + duration);
  oscillator.connect(gain);
  gain.connect(soundEffectContext.destination);
  oscillator.start(start);
  oscillator.stop(start + duration + 0.02);
}

export type BattleSoundEvent = {
  type: string;
  detail?: string;
  source?: string;
  cause?: string;
};

function isIndirectDamage(event: BattleSoundEvent) {
  const cause = eventId(event.cause ?? "");
  return Boolean(cause && cause !== "move" && cause !== "direct");
}

export async function playBattleSoundEffects(events: BattleSoundEvent[]) {
  const settings = getBattleAudioSettings();
  if (!settings.sfxEnabled || events.length === 0) return false;
  await unlockBattleSoundEffects();
  let played = false;

  for (const [index, event] of events.slice(0, 10).entries()) {
    const delayMs = index * 90;
    if (event.type === "move") {
      activeMove = eventId(event.detail ?? "");
      if (activeMove) {
        played =
          (await playMoveEffectPhases(activeMove, "actor", delayMs)) || played;
      }
      continue;
    }
    if (event.type === "switch") {
      activeMove = "";
      played =
        (await playCatalogEffect(
          ["poke_ball.send_out", "poke_ball.recall"],
          delayMs,
        )) || played;
      continue;
    }
    if (event.type === "damage") {
      if (isIndirectDamage(event)) {
        const source = eventId(event.source ?? event.detail ?? "");
        const cause = eventId(event.cause ?? "");
        const residualEffect = await playCatalogEffect(
          [
            ...(source ? [`residual.${source}`] : []),
            ...(cause ? [`residual.${cause}`] : []),
            "impact.residual",
          ],
          delayMs,
        );
        if (!residualEffect) playSyntheticEffect("residual_damage", delayMs);
        activeMove = "";
        played = residualEffect || played || Boolean(SYNTHETIC_FALLBACKS.residual_damage);
        continue;
      }
      const moveEffect = activeMove
        ? (await playMoveEffectPhases(activeMove, "target", delayMs)) ||
          (await playCatalogEffect(["impact.normal"], delayMs))
        : await playCatalogEffect(["impact.normal"], delayMs);
      if (!moveEffect) playSyntheticEffect(event.type, delayMs);
      played = moveEffect || played;
      continue;
    }
    if (SYNTHETIC_FALLBACKS[event.type]) {
      playSyntheticEffect(event.type, delayMs);
      played = true;
    }
  }
  return played;
}

"use client";

export type BattleAudioSettings = {
  enabled: boolean;
  volume: number;
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

const STORAGE_KEY = "cobbleverse-battle-lab:battle-audio";
const SETTINGS_EVENT = "cobbleverse-battle-audio-settings";
const DEFAULT_SETTINGS: BattleAudioSettings = {
  enabled: false,
  volume: 0.55,
};

let settingsSnapshot = DEFAULT_SETTINGS;
let settingsLoaded = false;
let player: HTMLAudioElement | null = null;
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
    ) as Partial<BattleAudioSettings> | null;
    settingsSnapshot = {
      enabled: Boolean(saved?.enabled),
      volume: clampedVolume(Number(saved?.volume ?? DEFAULT_SETTINGS.volume)),
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
    enabled: Boolean(next.enabled),
    volume: clampedVolume(next.volume),
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
    player.volume = clampedVolume(normalized.volume * activeTrackVolume);
    if (!normalized.enabled) stopBattleMusic();
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
  if (!settings.enabled) return { playing: false, reason: "disabled" };
  const catalog = await loadCatalog();
  const event = catalog.events.find((entry) => entry.id === eventId);
  if (!event?.tracks.length) {
    return {
      playing: false,
      reason: catalog.configured ? "missing_event" : "not_configured",
    };
  }
  if (player && activeEvent === eventId && !player.paused) {
    player.volume = settings.volume;
    return { playing: true };
  }

  stopBattleMusic();
  const track = event.tracks[Math.floor(Math.random() * event.tracks.length)];
  const audio = new Audio(
    `/api/local-audio/file?id=${encodeURIComponent(track.id)}`,
  );
  audio.loop = true;
  audio.preload = "auto";
  audio.volume = clampedVolume(settings.volume * track.volume);
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

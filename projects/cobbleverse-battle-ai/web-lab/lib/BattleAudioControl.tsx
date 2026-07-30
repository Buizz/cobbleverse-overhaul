"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import {
  getBattleAudioSettings,
  getBattleAudioServerSettings,
  setBattleAudioSettings,
  startBattleMusic,
  stopBattleMusic,
  subscribeBattleAudioSettings,
  type BattleAudioSettings,
} from "./battle-audio";

export function BattleAudioControl({
  eventId,
  compact = false,
}: {
  eventId: "battle.pvn.default" | "battle.pvp.default";
  compact?: boolean;
}) {
  const settings = useSyncExternalStore(
    (onStoreChange) =>
      subscribeBattleAudioSettings(() => onStoreChange()),
    getBattleAudioSettings,
    getBattleAudioServerSettings,
  ) as BattleAudioSettings;
  const [status, setStatus] = useState("");

  useEffect(() => {
    const current = getBattleAudioSettings();
    if (current.enabled) {
      void startBattleMusic(eventId).catch(() => {
        setStatus("재생 버튼을 다시 눌러 주세요.");
      });
    }
    return () => {
      stopBattleMusic();
    };
  }, [eventId]);

  const toggleSound = async () => {
    if (settings.enabled && status) {
      setStatus("");
      try {
        const result = await startBattleMusic(eventId);
        if (!result.playing) {
          setStatus("이 전투용 BGM을 찾지 못했습니다.");
        }
      } catch {
        setStatus("브라우저가 재생을 막았습니다. 다시 눌러 주세요.");
      }
      return;
    }
    const next = setBattleAudioSettings({
      ...settings,
      enabled: !settings.enabled,
    });
    setStatus("");
    if (!next.enabled) {
      stopBattleMusic();
      return;
    }
    try {
      const result = await startBattleMusic(eventId);
      if (!result.playing) {
        setStatus(
          result.reason === "not_configured"
            ? "리소스팩 폴더를 먼저 지정해 주세요."
            : "이 전투용 BGM을 찾지 못했습니다.",
        );
      }
    } catch {
      setStatus("브라우저가 재생을 막았습니다. 다시 눌러 주세요.");
    }
  };

  const changeVolume = (volume: number) => {
    setBattleAudioSettings({ ...settings, volume });
  };

  return (
    <div
      className={`battle-audio-control${compact ? " compact" : ""}`}
      role="group"
      aria-label="배틀 사운드"
    >
      <button
        type="button"
        className={settings.enabled ? "active" : ""}
        aria-pressed={settings.enabled}
        onClick={() => void toggleSound()}
        title="지정한 리소스팩의 배틀 BGM을 재생합니다."
      >
        <span aria-hidden="true">{settings.enabled ? "♪" : "×"}</span>
        {settings.enabled ? "사운드 켜짐" : "사운드 꺼짐"}
      </button>
      <label title={`음량 ${Math.round(settings.volume * 100)}%`}>
        <span>음량</span>
        <input
          type="range"
          min={0}
          max={100}
          step={5}
          value={Math.round(settings.volume * 100)}
          onChange={(event) => changeVolume(Number(event.target.value) / 100)}
          aria-label="배틀 사운드 음량"
        />
      </label>
      {status ? <small role="status">{status}</small> : null}
    </div>
  );
}

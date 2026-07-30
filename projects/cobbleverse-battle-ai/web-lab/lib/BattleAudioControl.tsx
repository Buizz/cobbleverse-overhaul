"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import {
  getBattleAudioSettings,
  getBattleAudioServerSettings,
  playBattleSoundEffects,
  setBattleAudioSettings,
  startBattleMusic,
  stopBattleMusic,
  subscribeBattleAudioSettings,
  type BattleAudioSettings,
  unlockBattleSoundEffects,
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
    if (current.bgmEnabled) {
      void startBattleMusic(eventId).catch(() => {
        setStatus("재생 버튼을 다시 눌러 주세요.");
      });
    }
    return () => {
      stopBattleMusic();
    };
  }, [eventId]);

  const toggleSound = async () => {
    if (settings.bgmEnabled && status) {
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
      bgmEnabled: !settings.bgmEnabled,
    });
    setStatus("");
    if (!next.bgmEnabled) {
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

  const toggleEffects = async () => {
    const next = setBattleAudioSettings({
      ...settings,
      sfxEnabled: !settings.sfxEnabled,
    });
    if (!next.sfxEnabled) return;
    await unlockBattleSoundEffects();
    await playBattleSoundEffects([{ type: "switch" }]);
  };

  return (
    <div
      className={`battle-audio-control${compact ? " compact" : ""}`}
      role="group"
      aria-label="배틀 사운드"
    >
      <div className="battle-audio-channel">
        <button
          type="button"
          className={settings.bgmEnabled ? "active" : ""}
          aria-pressed={settings.bgmEnabled}
          onClick={() => void toggleSound()}
          title="지정한 리소스팩의 배틀 BGM을 재생합니다."
        >
          <span aria-hidden="true">{settings.bgmEnabled ? "♪" : "×"}</span>
          BGM
        </button>
        <label title={`BGM 음량 ${Math.round(settings.bgmVolume * 100)}%`}>
          <span>BGM</span>
          <input
            type="range"
            min={0}
            max={100}
            step={5}
            value={Math.round(settings.bgmVolume * 100)}
            onChange={(event) =>
              setBattleAudioSettings({
                ...settings,
                bgmVolume: Number(event.target.value) / 100,
              })
            }
            aria-label="전투 BGM 음량"
          />
        </label>
      </div>
      <div className="battle-audio-channel">
        <button
          type="button"
          className={settings.sfxEnabled ? "active" : ""}
          aria-pressed={settings.sfxEnabled}
          onClick={() => void toggleEffects()}
          title="Cobblemon 기술과 타격 효과음을 재생합니다."
        >
          <span aria-hidden="true">{settings.sfxEnabled ? "◆" : "×"}</span>
          효과음
        </button>
        <label title={`효과음 음량 ${Math.round(settings.sfxVolume * 100)}%`}>
          <span>효과음</span>
          <input
            type="range"
            min={0}
            max={100}
            step={5}
            value={Math.round(settings.sfxVolume * 100)}
            onChange={(event) =>
              setBattleAudioSettings({
                ...settings,
                sfxVolume: Number(event.target.value) / 100,
              })
            }
            aria-label="배틀 효과음 음량"
          />
        </label>
      </div>
      {status && !compact ? <small role="status">{status}</small> : null}
    </div>
  );
}

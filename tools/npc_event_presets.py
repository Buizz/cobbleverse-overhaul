from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
from typing import Any


BATTLE_PRESETS = {"battle", "gym", "elite", "champion"}


def _localized(value: Any, fallback: str = "") -> dict[str, str]:
    if isinstance(value, dict):
        result = {key: text for key, text in value.items() if isinstance(text, str) and text}
        if result:
            return result
    if isinstance(value, str) and value:
        return {"ko_kr": value}
    return {"ko_kr": fallback}


def _dialogue(command_id: str, text: Any) -> dict[str, Any]:
    return {
        "type": "dialogue", "id": command_id, "speaker": "npc",
        "text": _localized(text, "대사를 입력하세요."),
    }


def _automatic_state_key(document_id: str, preset_type: str) -> str:
    namespace, _, path = document_id.partition(":")
    namespace = namespace or "cobbleventure"
    path = (path or "npc/new_npc").removeprefix("npc/")
    suffix = "claimed" if preset_type == "item" else "talked"
    return f"{namespace}:flag/npc/{path}/{suffix}"


def compile_event_preset(
    design: dict[str, Any], document_id: str = "cobbleventure:npc/new_npc"
) -> dict[str, Any]:
    """Compile one normalized authoring preset into the EasyNPC adapter script."""
    preset = design["preset"]
    preset_type = preset["type"]
    trigger = copy.deepcopy(preset.get("initial_trigger", {"type": "interact", "range": 4}))
    commands: list[dict[str, Any]] = []
    first_text = preset.get("first_text", {"ko_kr": "안녕하세요!"})

    if preset_type == "simple":
        commands.extend([_dialogue("greeting", first_text), {"type": "end"}])
    elif preset_type in {"repeat", "item"}:
        state_key = (
            _automatic_state_key(document_id, preset_type)
            if preset.get("auto_state_key", "state_key" not in preset) is True
            else preset.get("state_key", "cobbleventure:flag/npc/talked")
        )
        commands.extend([
            {
                "type": "branch",
                "conditions": [{"type": "flag", "key": state_key, "value": True}],
                "target": "repeat_greeting",
            },
            {"type": "label", "name": "first_greeting"},
            _dialogue("first_greeting", first_text),
        ])
        if preset_type == "item":
            commands.append({
                "type": "give_item", "item": preset.get("item", "cobblemon:poke_ball"),
                "count": max(1, int(preset.get("item_count", 1))),
            })
            commands.append(_dialogue(
                "item_explanation",
                preset.get("after_item_text", {"ko_kr": "방금 준 아이템을 잘 활용해 보세요."}),
            ))
        commands.extend([
            {"type": "set_flag", "key": state_key, "value": True},
            {"type": "goto", "target": "end"},
            {"type": "label", "name": "repeat_greeting"},
            _dialogue("repeat_greeting", preset.get("repeat_text", {"ko_kr": "다시 만났네요."})),
            {"type": "label", "name": "end"},
            {"type": "end"},
        ])
    elif preset_type in BATTLE_PRESETS:
        commands.extend([
            {
                "type": "branch",
                "conditions": [{
                    "type": "flag",
                    "key": "cobbleventure:runtime/npc_instance_defeated",
                    "value": True,
                }],
                "target": "victory",
            },
            {"type": "label", "name": "greeting"},
            _dialogue("greeting", first_text),
            {"type": "choices", "options": [
                {"id": "battle", "text": {"ko_kr": "승부한다"}, "target": "battle"},
                {"id": "cancel", "text": {"ko_kr": "다음에"}, "target": "end"},
            ]},
            {"type": "label", "name": "battle"},
            {
                "type": "start_battle", "battle": preset["battle"],
                "results": {"player_win": "victory", "player_loss": "defeat", "cancelled": "end"},
            },
            {"type": "label", "name": "victory"},
        ])
        state_key = preset.get("victory_state_key")
        if state_key:
            commands.append({"type": "set_flag", "key": state_key, "value": True})
        clear_key = preset.get("clear_key")
        if clear_key:
            commands.append({"type": "mark_clear", "key": clear_key})
        if preset_type == "gym" and preset.get("badge"):
            commands.append({"type": "grant_badge", "badge": preset["badge"]})
        if preset.get("win_item"):
            commands.append({
                "type": "give_item", "item": preset["win_item"],
                "count": max(1, int(preset.get("win_item_count", 1))),
            })
        commands.extend([
            _dialogue("victory", preset.get("win_text", {"ko_kr": "좋은 승부였어!"})),
            {"type": "goto", "target": "end"},
            {"type": "label", "name": "defeat"},
        ])
        loss_money = max(0, int(preset.get("loss_money", 0)))
        if loss_money:
            commands.append({
                "type": "take_money", "mode": "fixed", "amount": loss_money,
                "currency_objective": preset.get("currency_objective", "cobbleventure_money"),
            })
        commands.extend([
            _dialogue("defeat", preset.get("loss_text", {"ko_kr": "다시 도전해 줘."})),
            {"type": "label", "name": "end"},
            {"type": "end"},
        ])
    else:
        raise ValueError(f"지원하지 않는 NPC 행동 프리셋입니다: {preset_type}")

    return {"id": "primary", "trigger": trigger, "commands": commands}


def materialize_event_document(document: dict[str, Any]) -> dict[str, Any]:
    if document.get("_event_preset_materialized") is True:
        return document
    design = document.get("event_design")
    if not isinstance(design, dict) or design.get("mode") != "preset":
        return document
    result = copy.deepcopy(document)
    result["events"] = [compile_event_preset(design, str(document.get("id", "")))]
    result["_event_preset_materialized"] = True
    return result


def infer_event_design(document: dict[str, Any]) -> dict[str, Any] | None:
    """Convert the simple legacy editor output; preserve complex scripts as custom."""
    events = document.get("events")
    if not isinstance(events, list) or len(events) != 1:
        return None
    event = events[0]
    commands = event.get("commands", [])
    types = [command.get("type") for command in commands]
    if types.count("start_battle") > 1 or types.count("branch") > 1 or types.count("choices") > 1:
        return None

    labels = {
        command.get("name"): index for index, command in enumerate(commands)
        if command.get("type") == "label"
    }

    def dialogue_after(label: str) -> dict[str, str] | None:
        for command in commands[labels.get(label, -1) + 1:]:
            if command.get("type") == "dialogue":
                return _localized(command.get("text"))
            if command.get("type") == "label":
                break
        return None

    dialogues = [command for command in commands if command.get("type") == "dialogue"]
    first = _localized(dialogues[0].get("text") if dialogues else None, "안녕하세요!")
    start_battle = next((command for command in commands if command.get("type") == "start_battle"), None)
    preset: dict[str, Any] = {
        "type": "simple",
        "initial_trigger": copy.deepcopy(event.get("trigger", {"type": "interact", "range": 4})),
        "first_text": first,
    }
    if start_battle:
        tags = set(document.get("tags", []))
        preset["type"] = "gym" if "gym_leader" in tags else "elite" if "elite_four" in tags else "champion" if "champion" in tags else "battle"
        preset["battle"] = start_battle.get("battle")
        preset["after_victory_trigger"] = {"type": "interact", "range": 4}
        results = start_battle.get("results", {})
        preset["win_text"] = dialogue_after(results.get("player_win", "victory")) or {"ko_kr": "좋은 승부였어!"}
        preset["loss_text"] = dialogue_after(results.get("player_loss", "defeat")) or {"ko_kr": "다시 도전해 줘."}
        for command in commands:
            if command.get("type") == "set_flag" and command.get("value") is True:
                preset["victory_state_key"] = command.get("key")
            elif command.get("type") == "mark_clear":
                preset["clear_key"] = command.get("key")
            elif command.get("type") == "grant_badge":
                preset["badge"] = command.get("badge")
            elif command.get("type") == "give_item":
                preset["win_item"] = command.get("item")
                preset["win_item_count"] = command.get("count", 1)
            elif command.get("type") == "take_money":
                preset["loss_money"] = command.get("amount", 0)
                preset["currency_objective"] = command.get("currency_objective", "cobbleventure_money")
    elif "branch" in types:
        branch = next(command for command in commands if command.get("type") == "branch")
        condition = (branch.get("conditions") or [{}])[0]
        item = next((command for command in commands if command.get("type") == "give_item"), None)
        preset["type"] = "item" if item else "repeat"
        preset["state_key"] = condition.get("key")
        preset["repeat_text"] = dialogue_after(branch.get("target", "repeat_greeting")) or _localized(dialogues[-1].get("text") if dialogues else None, "다시 만났네요.")
        if item:
            preset["item"] = item.get("item")
            preset["item_count"] = item.get("count", 1)
            item_index = commands.index(item)
            followup = next((
                command for command in commands[item_index + 1:]
                if command.get("type") == "dialogue"
            ), None)
            if followup:
                preset["after_item_text"] = _localized(followup.get("text"))
    return {"mode": "preset", "preset": preset}


def migrate_file(path: Path) -> bool:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schema_version") != 4 or "event_design" in document:
        return False
    design = infer_event_design(document)
    if design is None:
        design = {"mode": "easy_npc_events"}
    document["event_design"] = design
    if design["mode"] == "preset":
        document.pop("events", None)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="NPC 이벤트를 정규화된 행동 프리셋으로 마이그레이션합니다.")
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    changed = sum(migrate_file(path) for path in sorted(args.root.rglob("*.json")))
    print(f"NPC 이벤트 문서 {changed}개를 마이그레이션했습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Generate EasyNPC data presets and client-side custom skin files from outfit data."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import shutil
import struct
import uuid
import zipfile
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.npc_event_presets import materialize_event_document
PROJECT_ROOT = Path(os.environ.get(
    "COBBLEVENTURE_PROJECT_PATH", ROOT / "content-projects/cobbleventure-main"
)).resolve()
CATALOG = PROJECT_ROOT / "content" / "catalogs" / "trainer-outfits.json"
TRAINER_CLASSES = PROJECT_ROOT / "content" / "catalogs" / "trainer-classes.json"
CONTENT_ROOT = PROJECT_ROOT / "content" / "source"
BATTLE_ROOT = PROJECT_ROOT / "content" / "battles"
GYM_CATALOG = PROJECT_ROOT / "content" / "catalogs" / "gyms.json"
LEAGUE_CATALOG = PROJECT_ROOT / "content" / "catalogs" / "league-progression.json"
TRAINER_ROSTER = PROJECT_ROOT / "content" / "catalogs" / "trainer-roster.json"
MUSIC_CATALOG = PROJECT_ROOT / "content" / "catalogs" / "music-tracks.json"
RESOURCE_ROOT = ROOT / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources"
PACK_OVERRIDE = ROOT / "pack" / "overrides" / "development-placeholder"
INSTANCE_DEFEATED_FLAG = "cobbleventure:runtime/npc_instance_defeated"
INSTANCE_DEFEATED_OBJECTIVE = "cv_npc_defeated"
STARTER_RECEIVED_FLAG = "cobbleventure:flag/story/starter_received"
STARTER_RECEIVED_OBJECTIVE = "cv_starter_recv"
SUPPORTED_LANGUAGES = {"ko_kr", "en_us"}
EXPORT_LANGUAGE = os.environ.get("COBBLEVENTURE_EXPORT_LANGUAGE", "ko_kr")


def encounter_skin_uuid(document: dict, outfit: dict) -> str:
    appearance = document.get("npc", {}).get("appearance", {})
    resource = appearance.get("resource")
    if isinstance(resource, str) and resource:
        return str(uuid.uuid5(uuid.NAMESPACE_URL, resource + "/easy_npc_skin"))
    return outfit["adapters"]["easy_npc"]["custom_skin_uuid"]


def npc_identity_tag_fragment(document: dict) -> str:
    """Opt-in stable identity used by building-specific interaction bridges."""
    if "building_runtime" not in document.get("tags", []):
        return ""
    identity = "cobbleventure_npc/" + document["id"].replace(":", "/")
    return "," + quote(identity)


def encounter_outfits_by_class(catalog: dict, class_catalog_path: Path) -> dict[str, dict]:
    """Combine authored equipment outfits with class-derived EasyNPC body settings."""
    outfits = {outfit["trainer_class"]: outfit for outfit in catalog["outfits"]}
    if not class_catalog_path.is_file():
        return outfits
    classes = json.loads(class_catalog_path.read_text(encoding="utf-8")).get("classes", [])
    for trainer_class in classes:
        class_id = trainer_class.get("id")
        body = trainer_class.get("body", {})
        appearance = trainer_class.get("default_appearance", {})
        if not isinstance(class_id, str):
            continue
        if class_id in outfits:
            outfits[class_id]["_trainer_class_tags"] = trainer_class.get("tags", [])
            continue
        slug = class_id.rsplit("/", 1)[-1]
        arm_model = body.get("arm_model", "classic")
        outfits[class_id] = {
            "id": f"cobbleventure:trainer_outfit/{slug}",
            "trainer_class": class_id,
            "display_name": trainer_class.get("display_name", {"ko_kr": slug}),
            "base_skin": appearance.get("resource", "cobbleventure:trainer_skin/unimplemented"),
            "fallback_skin": "cobbleventure:trainer_skin/unimplemented",
            "arm_model": arm_model,
            "_trainer_class_tags": trainer_class.get("tags", []),
            "equipment": {},
            "adapters": {"easy_npc": {
                "entity_type": "easy_npc:humanoid",
                "skin_model": "humanoid",
                "custom_skin_uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, class_id + "/easy_npc_skin")),
                "preset": f"cobbleventure:trainer/{slug}",
                "root_scale": float(body.get("height_scale", 1.0)),
            }},
        }
    return outfits


def encounter_music_track(
    outfit: dict, music_defaults: dict[str, str] | None = None
) -> str:
    """Choose the trainer-appears theme from authored presentation data."""
    defaults = music_defaults or {}
    tags = {
        str(tag).lower() for tag in outfit.get("_trainer_class_tags", [])
        if isinstance(tag, str)
    }
    class_slug = str(outfit.get("trainer_class", "")).rsplit("/", 1)[-1].lower()
    if "villain" in tags or any(token in class_slug for token in ("villain", "rocket")):
        return defaults.get(
            "trainer_encounter_bad_guys", "encounter.trainer_bad_guys"
        )
    if "female" in tags or outfit.get("arm_model") == "slim":
        return defaults.get("trainer_encounter_girl", "encounter.trainer_girl")
    return defaults.get("trainer_encounter_boy", "encounter.trainer_boy")


def installed_rct_skin(resource: str) -> bytes | None:
    match = re.fullmatch(r"rctmod:trainers/(single|group)/([a-z0-9_-]+)", resource)
    if not match:
        return None
    archive_entry = (
        f"assets/rctmod/textures/trainers/{match.group(1)}/{match.group(2)}.png"
    )
    instance_root = Path.home() / "curseforge" / "minecraft" / "Instances"
    candidates: list[Path] = []
    override = os.environ.get("COBBLEVERSE_INSTANCE")
    if override:
        candidates.append(Path(override) / "resourcepacks" / "COBBLEVERSE RCTmod RP.zip")
    candidates.extend(sorted(instance_root.glob("*/resourcepacks/COBBLEVERSE RCTmod RP.zip")))
    for archive_path in candidates:
        try:
            with zipfile.ZipFile(archive_path) as archive:
                data = archive.read(archive_entry)
        except (OSError, KeyError, zipfile.BadZipFile):
            continue
        if len(data) <= 2 * 1024 * 1024 and data.startswith(b"\x89PNG\r\n\x1a\n"):
            return data
    return None


def local_appearance_skin(resource: str) -> bytes | None:
    match = re.fullmatch(
        r"([a-z0-9_.-]+):trainer_skin/([a-z0-9_./-]+)", resource
    )
    if not match:
        return None
    path = (
        RESOURCE_ROOT / "assets" / match.group(1) / "textures" / "entity"
        / "trainer" / f"{match.group(2)}.png"
    )
    try:
        data = path.read_bytes()
    except OSError:
        return None
    return data if data.startswith(b"\x89PNG\r\n\x1a\n") else None


def prepare_encounter_skin(document: dict, outfit: dict) -> Path | None:
    appearance = document.get("npc", {}).get("appearance", {})
    resource = appearance.get("resource")
    if not isinstance(resource, str) or not resource:
        return None
    data = installed_rct_skin(resource) or local_appearance_skin(resource)
    if data is None:
        raise ValueError(
            f"EasyNPC 외형 원본을 찾을 수 없습니다: {document.get('id')} -> {resource}"
        )
    adapter = outfit["adapters"]["easy_npc"]
    target = (
        PACK_OVERRIDE / "config" / "easy_npc" / "skin" / adapter["skin_model"]
        / f"{encounter_skin_uuid(document, outfit)}.png"
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return target


def league_entry_npc_id(entry: dict) -> str:
    battle_id = entry["encounter"]["battle_id"]
    return f"cobbleventure:npc/gym_leader/{battle_id.rsplit('/', 1)[-1]}"


def league_dialogue_commands(value: str | list[str], base_id: str) -> list[dict]:
    lines = value if isinstance(value, list) else str(value).splitlines()
    return [
        {"type": "dialogue", "id": f"{base_id}_{index + 1}", "speaker": "npc", "text": {"ko_kr": line.strip()}}
        for index, line in enumerate(lines) if line.strip()
    ]


def league_post_victory_level_caps(entries: list[dict]) -> dict[str, int]:
    """Return the next Gym's challenge cap, or 100 after the final Gym."""
    groups: dict[tuple[int, str], list[dict]] = {}
    for entry in entries:
        if not isinstance(entry, dict) or entry.get("role") != "gym_leader":
            continue
        key = (int(entry.get("generation", 1)), str(entry.get("region", "")))
        groups.setdefault(key, []).append(entry)

    result: dict[str, int] = {}
    for group in groups.values():
        ordered = sorted(group, key=lambda item: (int(item["order"]), str(item["id"])))
        for index, entry in enumerate(ordered):
            result[entry["id"]] = (
                int(ordered[index + 1]["level_cap"])
                if index + 1 < len(ordered) else 100
            )
    return result


def league_encounter_document(entry: dict, post_victory_level_cap: int = 100) -> dict:
    """Compile a concise league-authoring entry into a normal NPC event document."""
    encounter = entry["encounter"]
    dialogue = encounter["dialogue"]
    rewards = encounter["rewards"]
    npc_id = league_entry_npc_id(entry)
    slug = npc_id.rsplit("/", 1)[-1]
    region = entry["region"].rsplit("/", 1)[-1]
    clear_key = f"cobbleventure:flag/gym/{region}/{slug}/defeated"
    name = entry["display_name"]
    display_name = {"ko_kr": f"체육관 관장 {localized(name)}"}
    if name.get("en_us"):
        display_name["en_us"] = f"Gym Leader {name['en_us']}"
    victory_rewards: list[dict] = [
        {"type": "set_flag", "key": clear_key, "value": True},
    ]
    if int(rewards.get("money", 0)) > 0:
        victory_rewards.append({
            "type": "give_money", "mode": "fixed", "amount": int(rewards["money"]),
        })
    if rewards.get("item"):
        victory_rewards.append({
            "type": "give_item", "item": rewards["item"],
            "count": int(rewards.get("item_count", 1)),
        })
    victory_rewards.append({"type": "grant_badge", "badge": rewards["badge_id"]})
    victory_rewards.append({
        "type": "set_level_cap", "level_cap": int(post_victory_level_cap),
    })
    return {
        "$schema": "../../../schemas/npc-event-script.schema.json",
        "schema_version": 4,
        "id": npc_id,
        "enabled": True,
        "name": copy.deepcopy(name),
        "description": {"ko_kr": "리그 운영 약식 설정에서 빌드 시 생성된 관장 NPC입니다."},
        "tags": ["trainer", "gym_leader", region, slug, "generated_from_league"],
        "npc": {
            "display_name": display_name,
            "trainer_class": "cobbleventure:trainer_class/gym_leader",
            "appearance": copy.deepcopy(encounter["appearance"]),
            "behavior": {
                "movement": "stationary", "look_at_player": True,
                "invulnerable": True, "collision": True,
            },
            **({"character": encounter["character"]} if encounter.get("character") else {}),
        },
        "event_design": {"mode": "easy_npc_events"},
        "events": [{
            "id": "on_interact",
            "trigger": {"type": "interact", "range": 4.0},
            "commands": [
                {"type": "branch", "conditions": [{"type": "flag", "key": clear_key, "value": True}], "target": "cleared"},
                {"type": "label", "name": "challenge"},
                *league_dialogue_commands(dialogue["challenge"], "challenge"),
                {"type": "choices", "options": [
                    {"id": "battle", "text": {"ko_kr": "승부한다"}, "target": "battle"},
                    {"id": "cancel", "text": {"ko_kr": "다음에 도전한다"}, "target": "end"},
                ]},
                {"type": "label", "name": "battle"},
                {"type": "start_battle", "battle": encounter["battle_id"], "results": {"player_win": "victory", "player_loss": "defeat"}},
                {"type": "label", "name": "victory"},
                *victory_rewards,
                *league_dialogue_commands(dialogue["victory"], "victory"),
                {"type": "goto", "target": "end"},
                {"type": "label", "name": "defeat"},
                *league_dialogue_commands(dialogue["defeat"], "defeat"),
                {"type": "goto", "target": "end"},
                {"type": "label", "name": "cleared"},
                *league_dialogue_commands(dialogue["cleared"], "cleared"),
                {"type": "label", "name": "end"},
                {"type": "end"},
            ],
        }],
    }


def uuid_int_array(value: str) -> str:
    parts = struct.unpack(">iiii", uuid.UUID(value).bytes)
    return "[I;" + ",".join(str(part) for part in parts) + "]"


def quote(value: str) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def localized(value: dict | None) -> str:
    value = value or {}
    return (
        value.get(EXPORT_LANGUAGE)
        or value.get("ko_kr")
        or value.get("en_us")
        or next(iter(value.values()), "")
    )


def npc_name_component(display: str) -> str:
    """Use an Iris-safe vanilla bitmap font for in-world EasyNPC nameplates."""
    return json.dumps(
        {"text": display, "font": "minecraft:uniform"},
        ensure_ascii=False,
        separators=(",", ":"),
    )


def flag_objective(resource_id: str) -> str:
    """Map a long content flag id to a stable Minecraft scoreboard objective."""
    if resource_id == INSTANCE_DEFEATED_FLAG:
        return INSTANCE_DEFEATED_OBJECTIVE
    if resource_id == STARTER_RECEIVED_FLAG:
        return STARTER_RECEIVED_OBJECTIVE
    return "cvf_" + hashlib.sha1(resource_id.encode("utf-8")).hexdigest()[:12]


def item_condition_objective(item_id: str, count: int) -> str:
    """Stable boolean objective mirrored from vanilla inventory plus the server bag."""
    key = f"{item_id}\0{count}"
    return "cvi_" + hashlib.sha1(key.encode("utf-8")).hexdigest()[:12]


def player_condition_objective(condition: dict) -> str:
    """Stable boolean objective mirrored by the shared server condition tracker."""
    normalized = json.dumps(condition, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return "cvc_" + hashlib.sha1(normalized.encode("utf-8")).hexdigest()[:12]


def dialogue_label(resource_id: str) -> str:
    return resource_id.rsplit("/", 1)[-1]


def easy_npc_condition(operation: dict) -> str | None:
    operation_type = operation.get("type")
    if operation_type in {"flag", "flag_equals"}:
        value = operation.get("value")
        if isinstance(value, bool):
            value = 1 if value else 0
        return (
            "{Name:" + quote(flag_objective(operation["key"]))
            + ',Operation:"EQUALS",Type:"SCOREBOARD",Value:' + str(value) + "}"
        )
    if operation_type in {"item", "has_item"}:
        count = int(operation.get("count", 1))
        expected = 0 if operation.get("negate") else 1
        return (
            "{Name:" + quote(item_condition_objective(operation["item"], count))
            + ',Operation:"EQUALS",Type:"SCOREBOARD",Value:' + str(expected) + "}"
        )
    if operation_type in {"variable", "badge", "pokemon", "party_count"}:
        return (
            "{Name:" + quote(player_condition_objective(operation))
            + ',Operation:"EQUALS",Type:"SCOREBOARD",Value:1}'
        )
    if operation_type == "always":
        return None
    raise ValueError(f"EasyNPC 조건으로 변환할 수 없습니다: {operation_type}")


def graph_reward_commands(document: dict, start_battle: dict, result_key: str = "player_win") -> list[str]:
    target = start_battle.get("results", {}).get(result_key)
    nodes = {node["id"]: node for node in document.get("interaction", {}).get("nodes", [])}
    commands: list[str] = []
    visited: set[str] = set()
    while target and target not in visited:
        visited.add(target)
        node = nodes.get(target)
        if not node or node.get("type") != "actions":
            break
        for action in node.get("actions", []):
            action_type = action.get("type")
            if action_type in {"set_flag", "mark_clear"}:
                objective = flag_objective(action["key"])
                value = 1 if action_type == "mark_clear" else action.get("value")
                if isinstance(value, bool):
                    value = 1 if value else 0
                commands.extend([
                    f"scoreboard objectives add {objective} dummy",
                    f"scoreboard players set @1 {objective} {value}",
                ])
            elif action_type == "give_item":
                commands.append(f"cobbleventurebag acquire @1 {action['item']} {int(action.get('count', 1))}")
            elif action_type == "grant_badge":
                commands.append(f"cobbleventure_badge grant @1 {action['badge']}")
            elif action_type == "unlock_feature":
                commands.append(f"cobbleventure_progress unlock @1 {action['feature']}")
            elif action_type == "set_level_cap":
                commands.append(f"cobbleventure_progress level_cap @1 {int(action['level_cap'])}")
            elif action_type == "grant_loot":
                commands.append(f"cobbleventurebag loot @1 {action['loot_table']}")
            elif action_type in {"give_money", "take_money"}:
                currency = action.get("currency_objective", "cobbleventure_money")
                if action.get("mode") == "fixed":
                    verb = "remove" if action_type == "take_money" else "give"
                    commands.append(f"cobbledollars {verb} @1 {int(action.get('amount', 0))}")
                else:
                    multiplier = action.get("multiplier", 1)
                    if int(multiplier) != multiplier:
                        raise ValueError("EasyNPC/TBCS 어댑터의 레벨캡 상금 배율은 현재 정수만 지원합니다.")
                    level_cap = action.get("level_cap_objective", "cobbleventure_level_cap")
                    commands.extend([
                        "scoreboard objectives add cv_reward_tmp dummy",
                        f"scoreboard players operation @1 cv_reward_tmp = #current {level_cap}",
                        f"scoreboard players set #multiplier cv_reward_tmp {int(multiplier)}",
                        "scoreboard players operation @1 cv_reward_tmp *= #multiplier cv_reward_tmp",
                        f"scoreboard players operation @1 {currency} {'-=' if action_type == 'take_money' else '+='} @1 cv_reward_tmp",
                    ])
        target = node.get("next")
    return commands


def command_reward_commands(
    commands: list[dict], target: str | None, skip_give_money: bool = False
) -> list[str]:
    labels = {
        command.get("name"): index
        for index, command in enumerate(commands)
        if command.get("type") == "label"
    }
    index = labels.get(target, -1) + 1
    result: list[str] = []
    visited: set[int] = set()
    while 0 <= index < len(commands) and index not in visited:
        visited.add(index)
        command = commands[index]
        command_type = command.get("type")
        if command_type in {"set_flag", "mark_clear"}:
            objective = flag_objective(command["key"])
            value = 1 if command_type == "mark_clear" else command.get("value")
            if isinstance(value, bool):
                value = 1 if value else 0
            result.extend([
                f"scoreboard objectives add {objective} dummy",
                f"scoreboard players set @1 {objective} {value}",
            ])
        elif command_type == "give_item":
            result.append(f"cobbleventurebag acquire @1 {command['item']} {int(command.get('count', 1))}")
        elif command_type == "grant_badge":
            result.append(f"cobbleventure_badge grant @1 {command['badge']}")
        elif command_type == "unlock_feature":
            result.append(f"cobbleventure_progress unlock @1 {command['feature']}")
        elif command_type == "set_level_cap":
            result.append(f"cobbleventure_progress level_cap @1 {int(command['level_cap'])}")
        elif command_type == "grant_loot":
            result.append(f"cobbleventurebag loot @1 {command['loot_table']}")
        elif command_type == "grant_field_move":
            result.append(f"cobbleventure_field_move grant @1 {command['move']}")
        elif command_type in {"give_money", "take_money"}:
            if command_type == "give_money" and skip_give_money:
                index += 1
                continue
            currency = command.get("currency_objective", "cobbleventure_money")
            if command.get("mode") == "fixed":
                verb = "remove" if command_type == "take_money" else "give"
                result.append(f"cobbledollars {verb} @1 {int(command.get('amount', 0))}")
            else:
                multiplier = command.get("multiplier", 1)
                if int(multiplier) != multiplier:
                    raise ValueError("EasyNPC/TBCS 어댑터의 레벨캡 상금 배율은 현재 정수만 지원합니다.")
                level_cap = command.get("level_cap_objective", "cobbleventure_level_cap")
                result.extend([
                    "scoreboard objectives add cv_reward_tmp dummy",
                    f"scoreboard players operation @1 cv_reward_tmp = #current {level_cap}",
                    f"scoreboard players set #multiplier cv_reward_tmp {int(multiplier)}",
                    "scoreboard players operation @1 cv_reward_tmp *= #multiplier cv_reward_tmp",
                    f"scoreboard players operation @1 {currency} {'-=' if command_type == 'take_money' else '+='} @1 cv_reward_tmp",
                ])
        elif command_type == "goto":
            index = labels.get(command.get("target"), len(commands)) + 1
            continue
        elif command_type in {"dialogue", "choices", "start_battle", "end"}:
            break
        index += 1
    return result


def npc_money_reward_commands(document: dict, player_selector: str = "@1") -> list[str]:
    money = document.get("npc", {}).get("battle_rewards", {}).get("money", {})
    if not money.get("enabled"):
        return []
    held_bonus = str(bool(money.get("held_item_bonus", True))).lower()
    held_item = money.get("held_item", "cobblemon:amulet_coin")
    held_multiplier = int(money.get("held_item_multiplier", 2))
    if money.get("mode") == "regional_level":
        command = (
            f"cobbleventure_reward prepare {player_selector} regional "
            f"{int(money.get('fallback_region_level', 5))} "
            f"{int(money.get('per_level', 20))} {int(money.get('offset', 0))} "
            f"{held_bonus} {held_item} {held_multiplier}"
        )
    else:
        command = (
            f"cobbleventure_reward prepare {player_selector} fixed {int(money.get('amount', 0))} "
            f"{held_bonus} {held_item} {held_multiplier}"
        )
    for condition in money.get("conditions", []):
        if condition.get("type") == "flag_equals":
            value = condition.get("value", True)
            if isinstance(value, bool):
                value = 1 if value else 0
            command = (
                f"execute if score {player_selector} {flag_objective(condition['key'])} matches {value} run {command}"
            )
    return [command]


def reward_commands(
    document: dict,
    start_battle: dict | None = None,
    result_key: str = "player_win",
) -> list[str]:
    if document.get("schema_version") == 4:
        event = next(
            (event for event in document.get("events", []) if start_battle in event.get("commands", [])),
            None,
        )
        npc_money_enabled = bool(
            document.get("npc", {}).get("battle_rewards", {}).get("money", {}).get("enabled")
        )
        commands = command_reward_commands(
            event.get("commands", []) if event else [],
            (start_battle or {}).get("results", {}).get(result_key),
            skip_give_money=npc_money_enabled and result_key == "player_win",
        )
        return commands
    if document.get("schema_version") == 3:
        return graph_reward_commands(document, start_battle or {}, result_key)
    if result_key == "player_loss":
        return []
    rewards = document.get("rewards", {})
    commands: list[str] = []
    money = rewards.get("money", {})
    currency = money.get("currency_objective", "cobbleventure_money")
    if money.get("mode") == "fixed" and money.get("amount", 0):
        commands.append(f"cobbledollars give @1 {int(money['amount'])}")
    elif money.get("mode") == "level_cap_multiplier":
        multiplier = money.get("multiplier", 1)
        if int(multiplier) != multiplier:
            raise ValueError("EasyNPC/TBCS 어댑터의 레벨캡 상금 배율은 현재 정수만 지원합니다.")
        level_cap = money.get("level_cap_objective", "cobbleventure_level_cap")
        commands.extend([
            "scoreboard objectives add cv_reward_tmp dummy",
            f"scoreboard players operation @1 cv_reward_tmp = #current {level_cap}",
            f"scoreboard players set #multiplier cv_reward_tmp {int(multiplier)}",
            "scoreboard players operation @1 cv_reward_tmp *= #multiplier cv_reward_tmp",
            f"scoreboard players operation @1 {currency} += @1 cv_reward_tmp",
        ])
    items = rewards.get("items", {})
    if items.get("mode") == "fixed":
        commands.extend(
            f"cobbleventurebag acquire @1 {entry['item']} {int(entry['count'])}"
            for entry in items.get("entries", [])
        )
    elif items.get("mode") == "loot_table":
        commands.append(f"cobbleventurebag loot @1 {items['loot_table']}")
    victory_flag = document.get("progression", {}).get("victory_flag")
    if victory_flag:
        objective = flag_objective(victory_flag)
        commands.extend([
            f"scoreboard objectives add {objective} dummy",
            f"scoreboard players set @1 {objective} 1",
        ])
    return commands


def command_result_dialogue(commands: list[dict], target: str | None) -> str | None:
    """Resolve the first dialogue reached by a schema-v4 battle result label."""
    labels = {
        command.get("name"): index
        for index, command in enumerate(commands)
        if command.get("type") == "label"
    }
    index = labels.get(target, -1) + 1
    visited: set[int] = set()
    while 0 <= index < len(commands) and index not in visited:
        visited.add(index)
        command = commands[index]
        command_type = command.get("type")
        if command_type == "dialogue":
            return dialogue_label(command.get("id", target or ""))
        if command_type == "goto":
            index = labels.get(command.get("target"), len(commands)) + 1
            continue
        if command_type in {"choices", "start_battle", "end"}:
            return None
        index += 1
    return None


def result_dialogue_label(
    document: dict,
    start_battle: dict | None,
    result_key: str,
) -> str | None:
    if document.get("schema_version") != 4 or not start_battle:
        return None
    event = next(
        (event for event in document.get("events", []) if start_battle in event.get("commands", [])),
        None,
    )
    if not event:
        return None
    return command_result_dialogue(
        event.get("commands", []),
        start_battle.get("results", {}).get(result_key),
    )


def battle_command(document: dict, start_battle: dict | None = None) -> str:
    if document.get("schema_version") in {3, 4}:
        battle_ref = (start_battle or {}).get("battle")
        preset = document.get("_battle_presets", {}).get(battle_ref)
        if not preset:
            raise ValueError(f"배틀 프리셋을 찾을 수 없습니다: {battle_ref}")
        battle = preset["battle"]
        battle_id = preset["id"]
    else:
        battle = document["battle"]
        battle_id = document["id"]
    slug = battle["trainer_id"].rsplit("/", 1)[-1]
    rules = battle.get("rules", {})
    # EasyNPC expands @npc to the NPC's display name. Names containing spaces
    # (for example "AI 맨") break the TBCS participant parser. Command actions
    # execute as the NPC entity, so vanilla @s is the stable entity selector.
    command = f"/tbcs battle {battle['format']} @initiator vs @s as rctmod:{slug}"
    if "max_item_uses" in rules:
        command += " rules " + json.dumps(
            {"maxItemUses": rules["max_item_uses"]}, separators=(",", ":")
        ).replace('"', "")
    result_commands: dict[int, list[str]] = {}
    for side, result_key in ((1, "player_win"), (2, "player_loss")):
        commands = reward_commands(document, start_battle, result_key)
        # Only a victory consumes the automatic challenge. A defeated player
        # is challenged automatically again on the next approach.
        if result_key == "player_win":
            commands.append(
                "cobbleventure_trainer_state complete @npc-uuid @initiator"
            )
        next_dialogue = result_dialogue_label(document, start_battle, result_key)
        if next_dialogue:
            # ActionUtils expands these macros before TBCS stores its callbacks.
            # The concrete NPC UUID keeps the continuation bound to the exact
            # spawned NPC that initiated this battle.
            commands.append(
                f"easy_npc dialog open @npc-uuid @initiator {next_dialogue}"
            )
        result_commands[side] = commands
    callbacks = [
        f"{side}:[{','.join(quote(value) for value in commands)}]"
        for side, commands in result_commands.items()
        if commands
    ]
    if callbacks:
        command += " onwin {" + ",".join(callbacks) + "}"
    if battle.get("level_mode") == "map_scaling":
        offset = max(-99, min(99, int(battle.get("level_offset", 0))))
        fallback = max(
            (int(member.get("level", 1)) for member in battle.get("team", [])),
            default=1,
        )
        return (
            f"/cobbleventure_scaled_trainer_battle @initiator @s {battle_id} "
            f"{offset} {fallback} rctmod:{slug} {command.removeprefix('/')}"
        )
    # The wrapper shows the client-side trainer cut-in and executes this exact
    # TBCS command after the animation. Keeping both selectors in the nested
    # command preserves EasyNPC's initiator/NPC macro expansion.
    return (
        f"/cobbleventure_battle_intro @initiator @s {battle_id} "
        f"{command.removeprefix('/')}"
    )


def command_action(command: str) -> str:
    """Create a visible, permission-complete EasyNPC command action.

    EasyNPC otherwise falls back to its action defaults and suppresses command
    failures, which makes a rejected or malformed third-party command appear as
    a button that simply does nothing.
    """
    return (
        "{Cmd:" + quote(command)
        + ',Debug:1b,ExecAsUser:0b,PermLevel:2,Type:"COMMAND"}'
    )


def easy_npc_action(operation: dict, document: dict) -> str:
    operation_type = operation.get("type")
    if operation_type in {"next_dialogue", "open_dialogue"}:
        return "{Cmd:" + quote(dialogue_label(operation["target"])) + ',Type:"OPEN_NAMED_DIALOG"}'
    if operation_type == "close_dialogue":
        return '{Type:"CLOSE_DIALOG"}'
    if operation_type == "start_battle":
        prepare_actions = [
            command_action("/" + command)
            for command in npc_money_reward_commands(document, "@initiator")
        ]
        return ",".join([
            '{Type:"CLOSE_DIALOG"}',
            *prepare_actions,
            command_action(battle_command(document, operation)),
        ])
    if operation_type in {"set_flag", "mark_clear"}:
        value = 1 if operation_type == "mark_clear" else operation.get("value")
        if isinstance(value, bool):
            value = 1 if value else 0
        command = f"set:{flag_objective(operation['key'])}:{value}"
        return "{Cmd:" + quote(command) + ',Type:"SCOREBOARD"}'
    if operation_type == "give_item":
        return command_action(
            f"/cobbleventurebag acquire @initiator {operation['item']} {operation.get('count', 1)}"
        )
    if operation_type == "grant_badge":
        return command_action(f"/cobbleventure_badge grant @initiator {operation['badge']}")
    if operation_type == "grant_field_move":
        return command_action(
            f"/cobbleventure_field_move grant @initiator {operation['move']}"
        )
    if operation_type == "unlock_feature":
        return command_action(f"/cobbleventure_progress unlock @initiator {operation['feature']}")
    if operation_type == "set_level_cap":
        return command_action(f"/cobbleventure_progress level_cap @initiator {int(operation['level_cap'])}")
    if operation_type == "start_starter_roulette":
        continuation = operation.get("target")
        command = "/cobbleventure_starter_roulette @initiator"
        if continuation:
            command += f" @s {dialogue_label(continuation)}"
        return ",".join([
            '{Type:"CLOSE_DIALOG"}',
            command_action(command),
        ])
    if operation_type == "teleport_to_gate":
        selector = "@npc-uuid" if operation.get("subject") == "npc" else "@initiator"
        return command_action(
            f"/cobbleventure_gate teleport {selector} {operation['gate']} "
            f"{operation.get('side', 'front')}"
        )
    raise ValueError(f"EasyNPC 행동으로 변환할 수 없습니다: {operation_type}")


def event_target_action(commands: list[dict], target: str, document: dict) -> str:
    labels = {
        command.get("name"): index
        for index, command in enumerate(commands)
        if command.get("type") == "label"
    }
    index = labels.get(target, -1) + 1
    while 0 <= index < len(commands):
        command = commands[index]
        command_type = command.get("type")
        if command_type == "dialogue":
            return "{Cmd:" + quote(dialogue_label(command.get("id", target))) + ',Type:"OPEN_NAMED_DIALOG"}'
        if command_type == "start_battle":
            prepare_actions = [
                command_action("/" + value)
                for value in npc_money_reward_commands(document, "@initiator")
            ]
            return ",".join([
                '{Type:"CLOSE_DIALOG"}',
                *prepare_actions,
                command_action(battle_command(document, command)),
            ])
        if command_type == "goto":
            return event_target_action(commands, command["target"], document)
        if command_type == "end":
            return '{Type:"CLOSE_DIALOG"}'
        index += 1
    return '{Type:"CLOSE_DIALOG"}'


def first_battle_dialogue_label(
    document: dict, start_battle: dict | None
) -> str | None:
    if not start_battle:
        return None
    event = next(
        (
            event
            for event in document.get("events", [])
            if start_battle in event.get("commands", [])
        ),
        None,
    )
    if not event:
        return None
    for command in event.get("commands", []):
        if command.get("type") == "dialogue":
            return dialogue_label(command.get("id", "greeting"))
    return None


def event_script_dialogues(
    document: dict, automatic_start_battle: dict | None = None
) -> str:
    entries: list[str] = []
    for event in document.get("events", []):
        commands = event.get("commands", [])
        automatic_dialogue = first_battle_dialogue_label(
            document, automatic_start_battle
        )
        labels = {
            command.get("name"): index
            for index, command in enumerate(commands)
            if command.get("type") == "label"
        }
        routed: dict[int, tuple[int, list[dict]]] = {}
        for index, command in enumerate(commands):
            if command.get("type") != "branch":
                continue
            target_index = labels.get(command.get("target"), -1) + 1
            while 0 <= target_index < len(commands) and commands[target_index].get("type") != "dialogue":
                target_index += 1
            if target_index < len(commands):
                routed[target_index] = (100 - index, command.get("conditions", []))
        for index, command in enumerate(commands):
            if command.get("type") != "dialogue":
                continue
            dialogue_id = command.get("id", f"dialogue_{index}")
            fields = [
                "Label:" + quote(dialogue_label(dialogue_id)),
                "Name:" + quote(localized(command.get("text"))[:32]),
            ]
            if index in routed:
                fields.append(f"Priority:{routed[index][0]}")
            fields.append("Texts:[{Text:" + quote(localized(command.get("text"))) + "}]")
            conditions = [easy_npc_condition(value) for value in routed.get(index, (0, []))[1]]
            conditions = [value for value in conditions if value]
            if conditions:
                fields.append("Conditions:[" + ",".join(conditions) + "]")
            choice_command = commands[index + 1] if index + 1 < len(commands) else {}
            buttons: list[str] = []
            if (
                automatic_start_battle
                and dialogue_label(dialogue_id) == automatic_dialogue
            ):
                buttons.append(
                    '{Label:"battle",Name:"계속",Actions:['
                    + easy_npc_action(automatic_start_battle, document)
                    + "]}"
                )
            elif choice_command.get("type") == "dialogue":
                buttons.append(
                    '{Label:"next",Name:"다음",Actions:[{Cmd:'
                    + quote(dialogue_label(choice_command.get("id", f"dialogue_{index + 1}")))
                    + ',Type:"OPEN_NAMED_DIALOG"}]}'
                )
            elif choice_command.get("type") == "choices":
                for option in choice_command.get("options", []):
                    buttons.append(
                        "{Label:" + quote(option["id"])
                        + ",Name:" + quote(localized(option["text"]))
                        + ",Actions:[" + event_target_action(commands, option["target"], document) + "]}"
                    )
            if not buttons:
                followup_actions: list[str] = []
                followup_dialogue: str | None = None
                starts_starter_roulette = False
                for value in commands[index + 1:]:
                    if value.get("type") == "dialogue":
                        followup_dialogue = dialogue_label(value.get("id", "dialogue"))
                        break
                    if value.get("type") in {"label", "choices", "end"}:
                        break
                    if value.get("type") in {
                        "give_item",
                        "set_flag",
                        "mark_clear",
                        "teleport_to_gate",
                        "grant_field_move",
                        "unlock_feature",
                        "set_level_cap",
                        "start_starter_roulette",
                    }:
                        followup_actions.append(easy_npc_action(value, document))
                        starts_starter_roulette = (
                            starts_starter_roulette
                            or value.get("type") == "start_starter_roulette"
                        )
                if followup_actions:
                    final_action = (
                        None if starts_starter_roulette
                        else "{Cmd:" + quote(followup_dialogue) + ',Type:"OPEN_NAMED_DIALOG"}'
                        if followup_dialogue
                        else '{Type:"CLOSE_DIALOG"}'
                    )
                    actions = [*followup_actions]
                    if final_action:
                        actions.append(final_action)
                    buttons.append(
                        '{Label:"continue",Name:"계속",Actions:['
                        + ",".join(actions)
                        + "]}"
                    )
            if not buttons:
                buttons.append('{Label:"close",Name:"닫기",Actions:[{Type:"CLOSE_DIALOG"}]}')
            fields.append("Buttons:[" + ",".join(buttons) + "]")
            entries.append("{" + ",".join(fields) + "}")
    return '{DialogDataSet:[' + ",".join(entries) + '],Type:"CUSTOM"}'


def easy_npc_dialogues(
    document: dict, automatic_start_battle: dict | None = None
) -> str:
    if document.get("schema_version") == 4:
        return event_script_dialogues(document, automatic_start_battle)
    if document.get("schema_version") == 3:
        graph = document["interaction"]
        routes = graph.get("entry_routes", [])
        nodes = [node for node in graph.get("nodes", []) if node.get("type") == "dialogue"]
        routed_entries = {route["entry"]: 100 - index for index, route in enumerate(routes)}
        route_conditions = {route["entry"]: route.get("conditions", []) for route in routes}
    else:
        graph = document["dialogue"]
        nodes = graph["nodes"]
        routes = document.get("progression", {}).get("dialogue_routes", [])
        routed_entries = {route["entry"]: 100 - index for index, route in enumerate(routes)}
        route_conditions = {
            route["entry"]: [] if route.get("when", {}).get("type") == "always" else [route["when"]]
            for route in routes
        }
    entries: list[str] = []
    for node in nodes:
        fields = ["Label:" + quote(dialogue_label(node["id"])), "Name:" + quote(localized(node["text"])[:32])]
        priority = routed_entries.get(node["id"])
        if priority is not None:
            fields.append(f"Priority:{priority}")
        fields.append("Texts:[{Text:" + quote(localized(node["text"])) + "}]")
        conditions = [
            easy_npc_condition(value)
            for value in [*route_conditions.get(node["id"], []), *node.get("conditions", [])]
        ]
        conditions = [value for value in conditions if value]
        if conditions:
            fields.append("Conditions:[" + ",".join(conditions) + "]")
        buttons: list[str] = []
        for choice in node.get("choices", []):
            button_fields = [
                "Label:" + quote(choice["id"]),
                "Name:" + quote(localized(choice["text"])),
                "Actions:[" + ",".join(easy_npc_action(value, document) for value in choice["actions"]) + "]",
            ]
            choice_conditions = [easy_npc_condition(value) for value in choice.get("conditions", [])]
            choice_conditions = [value for value in choice_conditions if value]
            if choice_conditions:
                button_fields.append("Conditions:[" + ",".join(choice_conditions) + "]")
            buttons.append("{" + ",".join(button_fields) + "}")
        if not buttons and node.get("actions"):
            buttons.append(
                '{Label:"continue",Name:"계속",Actions:['
                + ",".join(easy_npc_action(value, document) for value in node["actions"])
                + "]}"
            )
        if buttons:
            fields.append("Buttons:[" + ",".join(buttons) + "]")
        entries.append("{" + ",".join(fields) + "}")
    return '{DialogDataSet:[' + ",".join(entries) + '],Type:"CUSTOM"}'


def item_tag(item: dict) -> str:
    return "{Count:1b,id:" + quote(item["item"]) + "}"


def armor_items(equipment: dict) -> str:
    # LivingEntity NBT order: feet, legs, chest, head.
    return "[" + ",".join(
        item_tag(equipment[slot]) if slot in equipment else "{}"
        for slot in ("feet", "legs", "chest", "head")
    ) + "]"


def drop_chances(equipment: dict) -> str:
    return "[" + ",".join(
        f'{float(equipment.get(slot, {}).get("drop_chance", 0.0)):.3f}f'
        for slot in ("feet", "legs", "chest", "head")
    ) + "]"


def preset_snbt(outfit: dict) -> str:
    adapter = outfit["adapters"]["easy_npc"]
    display = outfit["display_name"].get("ko_kr") or outfit["display_name"].get("en_us")
    preset_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, outfit["id"] + "/easy_npc_preset"))
    variant = "ALEX" if outfit["arm_model"] == "slim" else "STEVE"
    scale = float(adapter["root_scale"])
    custom_name = npc_name_component(display)
    return f'''{{
  PresetMetadata:{{
    author:"Cobbleventure",
    category:"Cobbleventure Trainers",
    created:0L,
    description:{quote(display + " EasyNPC 의상 프리셋")},
    entityTypeId:{quote(adapter["entity_type"])},
    modified:0L,
    name:{quote(display)},
    variantType:"{variant}",
    version:"1.0.0"
  }},
  data:{{
    ArmorDropChances:{drop_chances(outfit["equipment"])},
    ArmorItems:{armor_items(outfit["equipment"])},
    CustomName:{quote(custom_name)},
    EasyNPCVersion:3,
    Invulnerable:1b,
    ModelData:{{Root:{{Scale:[{scale:.3f}f,{scale:.3f}f,{scale:.3f}f]}}}},
    PersistenceRequired:1b,
    PresetUUID:{uuid_int_array(preset_uuid)},
    SkinData:{{Type:"CUSTOM",UUID:{uuid_int_array(adapter["custom_skin_uuid"])} }},
    VariantType:"{variant}",
    id:{quote(adapter["entity_type"])}
  }}
}}
'''


def encounter_preset_snbt(
    document: dict, outfit: dict, trigger_override: str | None = None,
    music_defaults: dict[str, str] | None = None,
) -> str:
    document = materialize_event_document(document)
    adapter = outfit["adapters"]["easy_npc"]
    display = localized(document.get("npc", {}).get("display_name")) or localized(document.get("name"))
    preset_variant = f"/{trigger_override}" if trigger_override else ""
    preset_uuid = str(uuid.uuid5(
        uuid.NAMESPACE_URL,
        document["id"] + "/easy_npc_encounter" + preset_variant,
    ))
    arm_model = document.get("_easy_npc_arm_model") or document.get("npc", {}).get(
        "appearance", {}
    ).get("arm_model") or outfit["arm_model"]
    variant = "ALEX" if arm_model == "slim" else "STEVE"
    scale = float(adapter["root_scale"])
    custom_name = npc_name_component(display)
    if document.get("schema_version") == 4:
        event = document.get("events", [{}])[0]
        encounter = event.get("trigger", {"type": "interact", "range": 4})
        encounter_mode = encounter.get("type", "interact")
        candidate_actions = event.get("commands", [])
    else:
        encounter = document["npc"]["behavior"].get("encounter", {"mode": "interaction"})
        encounter_mode = encounter.get("mode", "interaction")
        candidate_actions = [
            action
            for node in document.get("interaction", {}).get("nodes", [])
            for choice in node.get("choices", [])
            for action in choice.get("actions", [])
        ]
    if trigger_override in {"interact", "proximity"}:
        encounter_mode = trigger_override
    automatic_start_battle: dict | None = None
    needs_starter_state = any(
        action.get("type") == "start_starter_roulette"
        for action in candidate_actions
    )
    starter_state_actions = (
        command_action("/cobbleventure_starter_state @initiator") + ","
        if needs_starter_state else ""
    )
    if encounter_mode == "proximity":
        start_battle = next((action for action in candidate_actions if action.get("type") == "start_battle"), None)
        if start_battle:
            automatic_start_battle = start_battle
            first_dialogue = first_battle_dialogue_label(document, start_battle)
            if not first_dialogue:
                raise ValueError(
                    f"자동 조우 트레이너에 첫 대화가 없습니다: {document['id']}"
                )
            # EasyNPC's nested distance events are not reliable enough for the
            # warning -> dialogue transition. Start one Cobbleventure-owned
            # horizontal-distance watcher when the NPC first notices a player;
            # the generated Continue button starts the battle after the line.
            event_actions = (
                "ON_INTERACTION:["
                + starter_state_actions
                + command_action(
                    "/cobbleventure_trainer_state prepare @npc-uuid @initiator"
                )
                + ',{Type:"OPEN_DEFAULT_DIALOG"}],'
                + "ON_DISTANCE_NEAR:["
                + command_action(
                    "/cobbleventure_proximity_battle @initiator @s "
                    + encounter_music_track(outfit, music_defaults)
                    + " "
                    + first_dialogue
                    + " "
                    + battle_command(document, start_battle).removeprefix("/")
                )
                + "]"
            )
        else:
            event_actions = (
                "ON_DISTANCE_VERY_CLOSE:["
                + starter_state_actions
                + '{Type:"OPEN_DEFAULT_DIALOG"}]'
            )
    else:
        event_actions = (
            "ON_INTERACTION:["
            + starter_state_actions
            + command_action(
                "/cobbleventure_trainer_state prepare @npc-uuid @initiator"
            )
            + ',{Type:"OPEN_DEFAULT_DIALOG"}]'
        )
    return f'''{{
  PresetMetadata:{{
    author:"Cobbleventure",
    category:"Cobbleventure Encounters",
    created:0L,
    description:{quote(display + " EasyNPC 이벤트 NPC")},
    entityTypeId:{quote(adapter["entity_type"])},
    modified:0L,
    name:{quote(display)},
    variantType:"{variant}",
    version:"1.0.0"
  }},
  data:{{
    ActionData:{{ActionEventSet:{{{event_actions}}},ActionPermissionLevel:2}},
    ArmorDropChances:{drop_chances(outfit["equipment"])},
    ArmorItems:{armor_items(outfit["equipment"])},
    CustomName:{quote(custom_name)},
    DialogData:{easy_npc_dialogues(document, automatic_start_battle)},
    EasyNPCVersion:3,
    Invulnerable:1b,
    ModelData:{{Root:{{Scale:[{scale:.3f}f,{scale:.3f}f,{scale:.3f}f]}}}},
    ObjectiveData:{{HasObjectives:1b,ObjectiveDataSet:[{{Type:"LOOK_AT_PLAYER"}},{{Type:"LOOK_AT_RESET"}}]}},
    PersistenceRequired:1b,
    PresetUUID:{uuid_int_array(preset_uuid)},
    SkinData:{{Type:"CUSTOM",UUID:{uuid_int_array(encounter_skin_uuid(document, outfit))} }},
    Tags:["cobbleventure_regional_npc","cobbleventure_npc_preset_v4"{npc_identity_tag_fragment(document)}],
    VariantType:"{variant}",
    id:{quote(adapter["entity_type"])}
  }}
}}
'''


def v5_encounter_preset_snbt(
    document: dict, outfit: dict, binding_tag: str, *, proximity: bool = False
) -> str:
    """Render an inert EasyNPC representation whose interaction is owned by CVES."""
    adapter = outfit["adapters"]["easy_npc"]
    display = localized(document.get("npc", {}).get("display_name")) or localized(document.get("name"))
    preset_uuid = str(uuid.uuid5(
        uuid.NAMESPACE_URL,
        document["id"] + "/easy_npc_encounter/v5/" + binding_tag
        + ("/proximity" if proximity else ""),
    ))
    arm_model = document.get("_easy_npc_arm_model") or document.get("npc", {}).get(
        "appearance", {}
    ).get("arm_model") or outfit["arm_model"]
    variant = "ALEX" if arm_model == "slim" else "STEVE"
    scale = float(adapter["root_scale"])
    custom_name = npc_name_component(display)
    trigger_tag = ',"cves_trigger/proximity"' if proximity else ""
    variant_label = " [V5 근접전투]" if proximity else " [V5]"
    return f'''{{
  PresetMetadata:{{
    author:"Cobbleventure",
    category:"Cobbleventure Encounters V5",
    created:0L,
    description:{quote(display + " CVES V5 NPC 표현 프리셋")},
    entityTypeId:{quote(adapter["entity_type"])},
    modified:0L,
    name:{quote(display + variant_label)},
    variantType:"{variant}",
    version:"1.0.0"
  }},
  data:{{
    ActionData:{{ActionEventSet:{{}},ActionPermissionLevel:2}},
    ArmorDropChances:{drop_chances(outfit["equipment"])},
    ArmorItems:{armor_items(outfit["equipment"])},
    CustomName:{quote(custom_name)},
    DialogData:{{DialogDataSet:[],Type:"CUSTOM"}},
    EasyNPCVersion:3,
    Invulnerable:1b,
    ModelData:{{Root:{{Scale:[{scale:.3f}f,{scale:.3f}f,{scale:.3f}f]}}}},
    ObjectiveData:{{HasObjectives:1b,ObjectiveDataSet:[{{Type:"LOOK_AT_PLAYER"}},{{Type:"LOOK_AT_RESET"}}]}},
    PersistenceRequired:1b,
    PresetUUID:{uuid_int_array(preset_uuid)},
    SkinData:{{Type:"CUSTOM",UUID:{uuid_int_array(encounter_skin_uuid(document, outfit))} }},
    Tags:["cobbleventure_regional_npc",{quote(binding_tag)}{npc_identity_tag_fragment(document)}{trigger_tag}],
    VariantType:"{variant}",
    id:{quote(adapter["entity_type"])}
  }}
}}
'''


def cves_binding_tag(content_root: Path, source: Path, document: dict) -> str | None:
    """Resolve a representation tag by convention without adding it to V4 source."""
    return cves_binding_tag_for_relative(
        content_root, source.relative_to(content_root).with_suffix(".json"), document
    )


def cves_binding_tag_for_relative(
    content_root: Path, relative: Path, document: dict
) -> str | None:
    """Resolve a tag for generated representations that have a virtual V4 source path."""
    npc_id = document.get("id")
    if not isinstance(npc_id, str) or ":" not in npc_id:
        return None
    namespace = npc_id.split(":", 1)[0]
    binding = content_root.parent / "event-bindings" / namespace / relative
    if not binding.is_file():
        return None
    value = json.loads(binding.read_text(encoding="utf-8"))
    if value.get("schema_version") != 1 or not isinstance(value.get("script_id"), str):
        raise ValueError(f"올바르지 않은 CVES NPC 바인딩입니다: {binding}")
    return f"cves_binding/{namespace}/{relative.with_suffix('').as_posix()}"


def has_cves_proximity_events(binding_tag: str) -> bool:
    prefix, namespace, relative = binding_tag.split("/", 2)
    if prefix != "cves_binding":
        return False
    script = CONTENT_ROOT.parent / "events" / namespace / f"{relative}.cves"
    return script.is_file() and "event proximity_enter(" in script.read_text(encoding="utf-8")


def resource_path(resource_id: str) -> Path:
    namespace, path = resource_id.split(":", 1)
    return RESOURCE_ROOT / "data" / "easy_npc" / "preset" / namespace / f"{path}.npc.snbt"


def paired_encounter_documents(documents: list[dict], battle_presets: dict[str, dict]) -> list[dict]:
    """Expand a double-battle owner into two NPC presets that share one event script."""
    documents = [document for document in documents if isinstance(document, dict)]
    documents_by_id = {
        document["id"]: document
        for document in documents
        if isinstance(document.get("id"), str) and isinstance(document.get("npc"), dict)
    }
    partner_owners: dict[str, str] = {}
    for document in documents:
        owner_id = document.get("id")
        config = document.get("npc", {}).get("double_battle")
        if not config:
            continue
        partner_id = config.get("partner")
        if partner_id == owner_id:
            raise ValueError(f"더블배틀 NPC가 자기 자신을 파트너로 지정했습니다: {owner_id}")
        if partner_id not in documents_by_id:
            raise ValueError(f"더블배틀 파트너 NPC를 찾을 수 없습니다: {owner_id} -> {partner_id}")
        if not documents_by_id[partner_id].get("enabled", True):
            raise ValueError(f"비활성 NPC를 더블배틀 파트너로 사용할 수 없습니다: {partner_id}")
        previous_owner = partner_owners.get(partner_id)
        if previous_owner and previous_owner != owner_id:
            raise ValueError(f"더블배틀 파트너가 여러 그룹에 지정되었습니다: {partner_id}")
        partner_owners[partner_id] = owner_id
    for owner_id in partner_owners.values():
        if owner_id in partner_owners:
            raise ValueError(f"더블배틀 대표 NPC는 다른 그룹의 파트너가 될 수 없습니다: {owner_id}")

    expanded: list[dict] = []
    for source in documents:
        if source.get("id") in partner_owners:
            continue
        if not source.get("enabled", True) or not (
            source.get("dialogue") or source.get("interaction") or source.get("events")
        ):
            continue
        owner = copy.deepcopy(source)
        owner["_battle_presets"] = battle_presets
        config = owner.get("npc", {}).get("double_battle")
        if config:
            for event in owner.get("events", []):
                commands = event.get("commands", [])
                for command in commands:
                    if command.get("type") != "start_battle":
                        continue
                    battle = battle_presets.get(command.get("battle"))
                    if battle and battle.get("battle", {}).get("battle_type") != "doubles":
                        raise ValueError(
                            f"2인 NPC 그룹은 더블 배틀 프리셋을 사용해야 합니다: "
                            f"{owner['id']} -> {command.get('battle')}"
                        )
                clear_key = config.get("shared_clear_key")
                win_labels = {
                    command.get("results", {}).get("player_win")
                    for command in commands if command.get("type") == "start_battle"
                }
                if clear_key and not any(
                    command.get("type") == "mark_clear" and command.get("key") == clear_key
                    for command in commands
                ):
                    label_index = next((
                        index for index, command in enumerate(commands)
                        if command.get("type") == "label" and command.get("name") in win_labels
                    ), None)
                    if label_index is not None:
                        commands.insert(label_index + 1, {"type": "mark_clear", "key": clear_key})
        expanded.append(owner)
        if not config:
            continue
        partner_source = documents_by_id[config["partner"]]
        partner = copy.deepcopy(owner)
        partner["id"] = partner_source["id"]
        partner["name"] = copy.deepcopy(partner_source.get("name", owner.get("name")))
        partner["tags"] = copy.deepcopy(partner_source.get("tags", owner.get("tags", [])))
        partner["npc"] = copy.deepcopy(partner_source["npc"])
        partner.pop("_cves_binding_tag", None)
        if isinstance(partner_source.get("_cves_binding_tag"), str):
            partner["_cves_binding_tag"] = partner_source["_cves_binding_tag"]
        partner["_battle_presets"] = battle_presets
        expanded.append(partner)
    return expanded


def roster_characters(catalog: dict) -> dict[str, dict]:
    characters = list(catalog.get("league_characters", []))
    for organization in catalog.get("organizations", []):
        characters.extend(organization.get("grunt_variants", []))
        characters.extend(organization.get("named_characters", []))
    return {
        character["id"]: character for character in characters
        if isinstance(character, dict) and isinstance(character.get("id"), str)
    }


def generate(
    catalog_path: Path = CATALOG,
    content_root: Path = CONTENT_ROOT,
    battle_root: Path = BATTLE_ROOT,
) -> list[Path]:
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    music_catalog_path = catalog_path.with_name("music-tracks.json")
    music_defaults = (
        json.loads(music_catalog_path.read_text(encoding="utf-8")).get("defaults", {})
        if music_catalog_path.is_file() else {}
    )
    outfits_by_class = encounter_outfits_by_class(
        catalog, catalog_path.with_name("trainer-classes.json")
    )
    characters_by_id = roster_characters(
        json.loads(TRAINER_ROSTER.read_text(encoding="utf-8"))
    ) if TRAINER_ROSTER.is_file() else {}
    battle_presets = {
        preset["id"]: preset
        for path in sorted(battle_root.rglob("*.json")) if battle_root.is_dir()
        for preset in [json.loads(path.read_text(encoding="utf-8"))]
        if preset.get("enabled", True)
    }
    written: list[Path] = []
    for outfit in catalog["outfits"]:
        adapter = outfit["adapters"]["easy_npc"]
        preset = resource_path(adapter["preset"])
        preset.parent.mkdir(parents=True, exist_ok=True)
        preset.write_text(preset_snbt(outfit), encoding="utf-8", newline="\n")
        written.append(preset)

        skin_name = outfit["base_skin"].split("/", 1)[-1] + ".png"
        source_skin = RESOURCE_ROOT / "assets" / "cobbleventure" / "textures" / "entity" / "trainer" / skin_name
        target_skin = (
            PACK_OVERRIDE / "config" / "easy_npc" / "skin" / adapter["skin_model"]
            / f'{adapter["custom_skin_uuid"]}.png'
        )
        target_skin.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_skin, target_skin)
        written.append(target_skin)
    source_documents = []
    for source in sorted(content_root.rglob("*.json")):
        document = materialize_event_document(json.loads(source.read_text(encoding="utf-8")))
        binding_tag = cves_binding_tag(content_root, source, document)
        if binding_tag is not None:
            document["_cves_binding_tag"] = binding_tag
        source_documents.append(document)
    league_catalog_path = content_root.parent / "catalogs" / "league-progression.json"
    if league_catalog_path.is_file():
        league_entries = json.loads(league_catalog_path.read_text(encoding="utf-8")).get("entries", [])
        post_victory_caps = league_post_victory_level_caps(league_entries)
        generated_leaders = [
            league_encounter_document(entry, post_victory_caps[entry["id"]])
            for entry in league_entries
            if isinstance(entry, dict) and entry.get("role") == "gym_leader"
            and isinstance(entry.get("encounter"), dict)
        ]
        for document in generated_leaders:
            slug = document["id"].rsplit("/", 1)[-1]
            binding_tag = cves_binding_tag_for_relative(
                content_root, Path("gym_leaders") / f"{slug}.json", document
            )
            if binding_tag is not None:
                document["_cves_binding_tag"] = binding_tag
        generated_ids = {document["id"] for document in generated_leaders}
        source_documents = [
            document for document in source_documents if document.get("id") not in generated_ids
        ] + generated_leaders
    if GYM_CATALOG.is_file():
        gyms = json.loads(GYM_CATALOG.read_text(encoding="utf-8")).get("gyms", [])
        badge_by_trainer = {
            leader.get("trainer_id"): leader.get("badge_id")
            for gym in gyms if isinstance(gym, dict)
            for leader in [gym.get("staff", {}).get("leader", {})]
            if leader.get("trainer_id") and leader.get("badge_id")
        }
        for document in source_documents:
            badge_id = badge_by_trainer.get(document.get("id"))
            if not badge_id:
                continue
            for event in document.get("events", []):
                for command in event.get("commands", []):
                    if command.get("type") == "grant_badge":
                        command["badge"] = badge_id
    for document in paired_encounter_documents(source_documents, battle_presets):
        trainer_class = document.get("npc", {}).get("trainer_class")
        outfit = outfits_by_class.get(trainer_class)
        if outfit is None:
            print(f"EasyNPC 조우 프리셋 생략: {document.get('id', '알 수 없는 NPC')} ({trainer_class} 의상 없음)")
            continue
        character = characters_by_id.get(document.get("npc", {}).get("character"), {})
        arm_model = character.get("body", {}).get("arm_model") or character.get(
            "appearance", {}
        ).get("arm_model")
        if arm_model:
            document["_easy_npc_arm_model"] = arm_model
        skin_path = prepare_encounter_skin(document, outfit)
        if skin_path is not None:
            written.append(skin_path)
        slug = document["id"].rsplit("/", 1)[-1]
        preset = RESOURCE_ROOT / "data" / "easy_npc" / "preset" / "encounter" / f"{slug}.npc.snbt"
        preset.parent.mkdir(parents=True, exist_ok=True)
        preset.write_text(
            encounter_preset_snbt(document, outfit, music_defaults=music_defaults),
            encoding="utf-8", newline="\n",
        )
        written.append(preset)
        binding_tag = document.get("_cves_binding_tag")
        if isinstance(binding_tag, str):
            v5_preset = preset.with_name(f"{slug}__v5.npc.snbt")
            v5_preset.write_text(
                v5_encounter_preset_snbt(document, outfit, binding_tag),
                encoding="utf-8", newline="\n",
            )
            written.append(v5_preset)
            preset_type = document.get("event_design", {}).get("preset", {}).get("type")
            has_proximity = (
                preset_type in {"battle", "gym", "elite", "champion"}
                or has_cves_proximity_events(binding_tag)
            )
            if has_proximity:
                proximity_preset = preset.with_name(f"{slug}__v5_proximity.npc.snbt")
                proximity_preset.write_text(
                    v5_encounter_preset_snbt(
                        document, outfit, binding_tag, proximity=True
                    ),
                    encoding="utf-8", newline="\n",
                )
                written.append(proximity_preset)
        for trigger_override in ("interact", "proximity"):
            override_preset = preset.with_name(
                f"{slug}__{trigger_override}.npc.snbt"
            )
            override_preset.write_text(
                encounter_preset_snbt(
                    document, outfit, trigger_override, music_defaults
                ),
                encoding="utf-8",
                newline="\n",
            )
            written.append(override_preset)
    return written


def spawn_command(document: dict) -> str:
    slug = document["id"].rsplit("/", 1)[-1]
    preset = f"easy_npc:preset/encounter/{slug}.npc.snbt"
    return f"/easy_npc preset import_new data {preset} ~ ~ ~"


def main() -> None:
    global EXPORT_LANGUAGE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--content-root", type=Path, default=CONTENT_ROOT)
    parser.add_argument("--battle-root", type=Path, default=BATTLE_ROOT)
    parser.add_argument(
        "--language",
        choices=sorted(SUPPORTED_LANGUAGES),
        default=EXPORT_LANGUAGE,
        help="EasyNPC 고정 텍스트 내보내기 언어 (기본값: ko_kr)",
    )
    args = parser.parse_args()
    EXPORT_LANGUAGE = args.language
    for path in generate(args.catalog.resolve(), args.content_root.resolve(), args.battle_root.resolve()):
        print(path)
    catalog = json.loads(args.catalog.resolve().read_text(encoding="utf-8"))
    supported_classes = set(encounter_outfits_by_class(
        catalog, args.catalog.resolve().with_name("trainer-classes.json")
    ))
    for source in sorted(args.content_root.resolve().rglob("*.json")):
        document = json.loads(source.read_text(encoding="utf-8"))
        if (
            document.get("enabled", True)
            and (document.get("dialogue") or document.get("interaction") or document.get("events") or document.get("event_design"))
            and document.get("npc", {}).get("trainer_class") in supported_classes
        ):
            print(f"{document['id']}: {spawn_command(document)}")


if __name__ == "__main__":
    main()

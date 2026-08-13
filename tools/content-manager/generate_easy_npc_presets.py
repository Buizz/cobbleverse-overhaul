#!/usr/bin/env python3
"""Generate EasyNPC data presets and client-side custom skin files from outfit data."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import shutil
import struct
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROJECT_ROOT = Path(os.environ.get(
    "COBBLEVENTURE_PROJECT_PATH", ROOT / "content-projects/cobbleventure-main"
)).resolve()
CATALOG = PROJECT_ROOT / "content" / "catalogs" / "trainer-outfits.json"
CONTENT_ROOT = PROJECT_ROOT / "content" / "source"
BATTLE_ROOT = PROJECT_ROOT / "content" / "battles"
GYM_CATALOG = PROJECT_ROOT / "content" / "catalogs" / "gyms.json"
RESOURCE_ROOT = ROOT / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources"
PACK_OVERRIDE = ROOT / "pack" / "overrides" / "development-placeholder"
INSTANCE_DEFEATED_FLAG = "cobbleventure:runtime/npc_instance_defeated"
INSTANCE_DEFEATED_OBJECTIVE = "cv_npc_defeated"


def uuid_int_array(value: str) -> str:
    parts = struct.unpack(">iiii", uuid.UUID(value).bytes)
    return "[I;" + ",".join(str(part) for part in parts) + "]"


def quote(value: str) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def localized(value: dict | None) -> str:
    value = value or {}
    return value.get("ko_kr") or value.get("en_us") or next(iter(value.values()), "")


def flag_objective(resource_id: str) -> str:
    """Map a long content flag id to a stable Minecraft scoreboard objective."""
    if resource_id == INSTANCE_DEFEATED_FLAG:
        return INSTANCE_DEFEATED_OBJECTIVE
    return "cvf_" + hashlib.sha1(resource_id.encode("utf-8")).hexdigest()[:12]


def dialogue_label(resource_id: str) -> str:
    return resource_id.rsplit("/", 1)[-1]


def easy_npc_condition(operation: dict) -> str | None:
    operation_type = operation.get("type")
    if operation_type == "flag_equals":
        value = operation.get("value")
        if isinstance(value, bool):
            value = 1 if value else 0
        return (
            "{Name:" + quote(flag_objective(operation["key"]))
            + ',Operation:"EQUALS",Type:"SCOREBOARD",Value:' + str(value) + "}"
        )
    if operation_type == "has_item":
        return (
            "{Amount:" + str(int(operation.get("count", 1)))
            + ",Name:" + quote(operation["item"])
            + ',Type:"HAS_ITEM_IN_INVENTORY"}'
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
                commands.append(f"cobbleventurebag give @1 {action['item']} {int(action.get('count', 1))}")
            elif action_type == "grant_badge":
                commands.append(f"cobbleventure_badge grant @1 {action['badge']}")
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
                if action_type == "take_money":
                    commands.append(f"execute if score @1 {currency} matches ..-1 run scoreboard players set @1 {currency} 0")
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
            result.append(f"cobbleventurebag give @1 {command['item']} {int(command.get('count', 1))}")
        elif command_type == "grant_badge":
            result.append(f"cobbleventure_badge grant @1 {command['badge']}")
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
            if command_type == "take_money":
                result.append(f"execute if score @1 {currency} matches ..-1 run scoreboard players set @1 {currency} 0")
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
            f"cobbleventurebag give @1 {entry['item']} {int(entry['count'])}"
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
            f"/cobbleventurebag give @initiator {operation['item']} {operation.get('count', 1)}"
        )
    if operation_type == "grant_badge":
        return command_action(f"/cobbleventure_badge grant @initiator {operation['badge']}")
    if operation_type == "grant_field_move":
        return command_action(
            f"/cobbleventure_field_move grant @initiator {operation['move']}"
        )
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


def event_script_dialogues(document: dict) -> str:
    entries: list[str] = []
    for event in document.get("events", []):
        commands = event.get("commands", [])
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
            if choice_command.get("type") == "choices":
                for option in choice_command.get("options", []):
                    buttons.append(
                        "{Label:" + quote(option["id"])
                        + ",Name:" + quote(localized(option["text"]))
                        + ",Actions:[" + event_target_action(commands, option["target"], document) + "]}"
                    )
            if not buttons:
                followup_actions: list[str] = []
                for value in commands[index + 1:]:
                    if value.get("type") in {"dialogue", "label", "choices", "end"}:
                        break
                    if value.get("type") in {"teleport_to_gate", "grant_field_move"}:
                        followup_actions.append(easy_npc_action(value, document))
                if followup_actions:
                    buttons.append(
                        '{Label:"continue",Name:"계속",Actions:['
                        + ",".join([*followup_actions, '{Type:"CLOSE_DIALOG"}'])
                        + "]}"
                    )
            if not buttons:
                buttons.append('{Label:"close",Name:"닫기",Actions:[{Type:"CLOSE_DIALOG"}]}')
            fields.append("Buttons:[" + ",".join(buttons) + "]")
            entries.append("{" + ",".join(fields) + "}")
    return '{DialogDataSet:[' + ",".join(entries) + '],Type:"CUSTOM"}'


def easy_npc_dialogues(document: dict) -> str:
    if document.get("schema_version") == 4:
        return event_script_dialogues(document)
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
    custom_name = json.dumps({"text": display}, ensure_ascii=False, separators=(",", ":"))
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


def encounter_preset_snbt(document: dict, outfit: dict) -> str:
    adapter = outfit["adapters"]["easy_npc"]
    display = localized(document.get("npc", {}).get("display_name")) or localized(document.get("name"))
    preset_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, document["id"] + "/easy_npc_encounter"))
    variant = "ALEX" if outfit["arm_model"] == "slim" else "STEVE"
    scale = float(adapter["root_scale"])
    custom_name = json.dumps({"text": display}, ensure_ascii=False, separators=(",", ":"))
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
    if encounter_mode == "proximity":
        start_battle = next((action for action in candidate_actions if action.get("type") == "start_battle"), None)
        proximity_action = (
            command_action(battle_command(document, start_battle))
            if start_battle else '{Type:"OPEN_DEFAULT_DIALOG"}'
        )
        event_actions = (
            "ON_DISTANCE_VERY_CLOSE:[" + proximity_action + "],"
            + "ON_DISTANCE_CLOSE:["
            + command_action('/title @initiator actionbar {"text":"주변에 트레이너가 있습니다!","color":"gold"}')
            + "]"
        )
    else:
        event_actions = (
            "ON_INTERACTION:["
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
    description:{quote(display + " 대화·배틀 테스트 NPC")},
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
    DialogData:{easy_npc_dialogues(document)},
    EasyNPCVersion:3,
    Invulnerable:1b,
    ModelData:{{Root:{{Scale:[{scale:.3f}f,{scale:.3f}f,{scale:.3f}f]}}}},
    ObjectiveData:{{HasObjectives:1b,ObjectiveDataSet:[{{Type:"LOOK_AT_PLAYER"}},{{Type:"LOOK_AT_RESET"}}]}},
    PersistenceRequired:1b,
    PresetUUID:{uuid_int_array(preset_uuid)},
    SkinData:{{Type:"CUSTOM",UUID:{uuid_int_array(adapter["custom_skin_uuid"])} }},
    VariantType:"{variant}",
    id:{quote(adapter["entity_type"])}
  }}
}}
'''


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
        partner["_battle_presets"] = battle_presets
        expanded.append(partner)
    return expanded


def generate(
    catalog_path: Path = CATALOG,
    content_root: Path = CONTENT_ROOT,
    battle_root: Path = BATTLE_ROOT,
) -> list[Path]:
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    outfits_by_class = {outfit["trainer_class"]: outfit for outfit in catalog["outfits"]}
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
    source_documents = [
        json.loads(source.read_text(encoding="utf-8"))
        for source in sorted(content_root.rglob("*.json"))
    ]
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
        slug = document["id"].rsplit("/", 1)[-1]
        preset = RESOURCE_ROOT / "data" / "easy_npc" / "preset" / "encounter" / f"{slug}.npc.snbt"
        preset.parent.mkdir(parents=True, exist_ok=True)
        preset.write_text(encounter_preset_snbt(document, outfit), encoding="utf-8", newline="\n")
        written.append(preset)
    return written


def spawn_command(document: dict) -> str:
    slug = document["id"].rsplit("/", 1)[-1]
    preset = f"easy_npc:preset/encounter/{slug}.npc.snbt"
    return f"/easy_npc preset import_new data {preset} ~ ~ ~"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--content-root", type=Path, default=CONTENT_ROOT)
    parser.add_argument("--battle-root", type=Path, default=BATTLE_ROOT)
    args = parser.parse_args()
    for path in generate(args.catalog.resolve(), args.content_root.resolve(), args.battle_root.resolve()):
        print(path)
    catalog = json.loads(args.catalog.resolve().read_text(encoding="utf-8"))
    supported_classes = {outfit["trainer_class"] for outfit in catalog["outfits"]}
    for source in sorted(args.content_root.resolve().rglob("*.json")):
        document = json.loads(source.read_text(encoding="utf-8"))
        if (
            document.get("enabled", True)
            and (document.get("dialogue") or document.get("interaction") or document.get("events"))
            and document.get("npc", {}).get("trainer_class") in supported_classes
        ):
            print(f"{document['id']}: {spawn_command(document)}")


if __name__ == "__main__":
    main()

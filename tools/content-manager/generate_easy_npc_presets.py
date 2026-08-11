#!/usr/bin/env python3
"""Generate EasyNPC data presets and client-side custom skin files from outfit data."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import struct
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "content" / "catalogs" / "trainer-outfits.json"
CONTENT_ROOT = ROOT / "content" / "source"
BATTLE_ROOT = ROOT / "content" / "battles"
RESOURCE_ROOT = ROOT / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources"
PACK_OVERRIDE = ROOT / "pack" / "overrides" / "development-placeholder"


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


def graph_reward_commands(document: dict, start_battle: dict) -> list[str]:
    target = start_battle.get("results", {}).get("player_win")
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
            if action_type == "set_flag":
                objective = flag_objective(action["key"])
                value = action.get("value")
                if isinstance(value, bool):
                    value = 1 if value else 0
                commands.extend([
                    f"scoreboard objectives add {objective} dummy",
                    f"scoreboard players set @1 {objective} {value}",
                ])
            elif action_type == "give_item":
                commands.append(f"give @1 {action['item']} {int(action.get('count', 1))}")
            elif action_type == "grant_loot":
                commands.append(f"loot give @1 loot {action['loot_table']}")
            elif action_type == "give_money":
                currency = action.get("currency_objective", "cobbleventure_money")
                if action.get("mode") == "fixed":
                    commands.append(f"scoreboard players add @1 {currency} {int(action.get('amount', 0))}")
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
                        f"scoreboard players operation @1 {currency} += @1 cv_reward_tmp",
                    ])
        target = node.get("next")
    return commands


def reward_commands(document: dict, start_battle: dict | None = None) -> list[str]:
    if document.get("schema_version") == 3:
        return graph_reward_commands(document, start_battle or {})
    rewards = document.get("rewards", {})
    commands: list[str] = []
    money = rewards.get("money", {})
    currency = money.get("currency_objective", "cobbleventure_money")
    if money.get("mode") == "fixed" and money.get("amount", 0):
        commands.append(f"scoreboard players add @1 {currency} {int(money['amount'])}")
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
            f"give @1 {entry['item']} {int(entry['count'])}"
            for entry in items.get("entries", [])
        )
    elif items.get("mode") == "loot_table":
        commands.append(f"loot give @1 loot {items['loot_table']}")
    victory_flag = document.get("progression", {}).get("victory_flag")
    if victory_flag:
        objective = flag_objective(victory_flag)
        commands.extend([
            f"scoreboard objectives add {objective} dummy",
            f"scoreboard players set @1 {objective} 1",
        ])
    return commands


def battle_command(document: dict, start_battle: dict | None = None) -> str:
    if document.get("schema_version") == 3:
        battle_ref = (start_battle or {}).get("battle")
        preset = document.get("_battle_presets", {}).get(battle_ref)
        if not preset:
            raise ValueError(f"배틀 프리셋을 찾을 수 없습니다: {battle_ref}")
        battle = preset["battle"]
    else:
        battle = document["battle"]
    slug = battle["trainer_id"].rsplit("/", 1)[-1]
    rules = battle.get("rules", {})
    command = f"/tbcs battle {battle['format']} @initiator vs @npc as rctmod:{slug}"
    if rules:
        command += " rules " + json.dumps(
            {"maxItemUses": rules["max_item_uses"]}, separators=(",", ":")
        ).replace('"', "")
    win_commands = reward_commands(document, start_battle)
    if win_commands:
        quoted_commands = ",".join(quote(value) for value in win_commands)
        command += " onwin {1:[" + quoted_commands + "]}"
    return command


def easy_npc_action(operation: dict, document: dict) -> str:
    operation_type = operation.get("type")
    if operation_type in {"next_dialogue", "open_dialogue"}:
        return "{Cmd:" + quote(dialogue_label(operation["target"])) + ',Type:"OPEN_NAMED_DIALOG"}'
    if operation_type == "close_dialogue":
        return '{Type:"CLOSE_DIALOG"}'
    if operation_type == "start_battle":
        return "{Cmd:" + quote(battle_command(document, operation)) + ',Type:"COMMAND"}'
    if operation_type == "set_flag":
        value = operation.get("value")
        if isinstance(value, bool):
            value = 1 if value else 0
        command = f"set:{flag_objective(operation['key'])}:{value}"
        return "{Cmd:" + quote(command) + ',Type:"SCOREBOARD"}'
    if operation_type == "give_item":
        return "{Cmd:" + quote(f"/give @initiator {operation['item']} {operation.get('count', 1)}") + ',Type:"COMMAND"}'
    raise ValueError(f"EasyNPC 행동으로 변환할 수 없습니다: {operation_type}")


def easy_npc_dialogues(document: dict) -> str:
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
    encounter = document["npc"]["behavior"].get("encounter", {"mode": "interaction"})
    if encounter.get("mode") == "proximity":
        start_battle = next(
            (
                action
                for node in document.get("interaction", {}).get("nodes", [])
                for choice in node.get("choices", [])
                for action in choice.get("actions", [])
                if action.get("type") == "start_battle"
            ),
            None,
        )
        event_actions = (
            "ON_DISTANCE_VERY_CLOSE:[{Cmd:" + quote(battle_command(document, start_battle)) + ',Type:"COMMAND"}],'
            'ON_DISTANCE_CLOSE:[{Cmd:"/title @initiator actionbar {\\"text\\":\\"주변에 트레이너가 있습니다!\\",\\"color\\":\\"gold\\"}",Type:"COMMAND"}]'
        )
    else:
        event_actions = 'ON_INTERACTION:[{Type:"OPEN_DEFAULT_DIALOG"}]'
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
    return RESOURCE_ROOT / "data" / namespace / "easy_npc" / "preset" / f"{path}.npc.snbt"


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
    for source in sorted(content_root.rglob("*.json")):
        document = json.loads(source.read_text(encoding="utf-8"))
        if not document.get("enabled", True) or not (document.get("dialogue") or document.get("interaction")):
            continue
        document["_battle_presets"] = battle_presets
        trainer_class = document.get("npc", {}).get("trainer_class")
        outfit = outfits_by_class.get(trainer_class)
        if outfit is None:
            print(f"EasyNPC 조우 프리셋 생략: {document.get('id', source)} ({trainer_class} 의상 없음)")
            continue
        slug = document["id"].rsplit("/", 1)[-1]
        preset = RESOURCE_ROOT / "data" / "cobbleventure" / "easy_npc" / "preset" / "encounter" / f"{slug}.npc.snbt"
        preset.parent.mkdir(parents=True, exist_ok=True)
        preset.write_text(encounter_preset_snbt(document, outfit), encoding="utf-8", newline="\n")
        written.append(preset)
    return written


def spawn_command(document: dict) -> str:
    slug = document["id"].rsplit("/", 1)[-1]
    return f"/easy_npc preset import_new data cobbleventure:encounter/{slug} ~ ~ ~"


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
            and (document.get("dialogue") or document.get("interaction"))
            and document.get("npc", {}).get("trainer_class") in supported_classes
        ):
            print(f"{document['id']}: {spawn_command(document)}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generate EasyNPC data presets and client-side custom skin files from outfit data."""

from __future__ import annotations

import argparse
import json
import shutil
import struct
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "content" / "catalogs" / "trainer-outfits.json"
RESOURCE_ROOT = ROOT / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources"
PACK_OVERRIDE = ROOT / "pack" / "overrides" / "development-placeholder"


def uuid_int_array(value: str) -> str:
    parts = struct.unpack(">iiii", uuid.UUID(value).bytes)
    return "[I;" + ",".join(str(part) for part in parts) + "]"


def quote(value: str) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


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


def resource_path(resource_id: str) -> Path:
    namespace, path = resource_id.split(":", 1)
    return RESOURCE_ROOT / "data" / namespace / "easy_npc" / "preset" / f"{path}.npc.snbt"


def generate(catalog_path: Path = CATALOG) -> list[Path]:
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
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
    return written


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    args = parser.parse_args()
    for path in generate(args.catalog.resolve()):
        print(path)


if __name__ == "__main__":
    main()

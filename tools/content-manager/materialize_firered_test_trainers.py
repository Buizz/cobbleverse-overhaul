#!/usr/bin/env python3
"""Materialize the FireRed trainers used by the generation-one test map."""

from __future__ import annotations

import copy
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "content-projects" / "cobbleventure-main"
CONTENT = PROJECT / "content"
REFERENCE_CATALOG = CONTENT / "catalogs" / "trainer-reference-entries.json"
CLASS_CATALOG = CONTENT / "catalogs" / "trainer-classes.json"
GYM_CATALOG = CONTENT / "catalogs" / "gyms.json"
NPC_DIR = CONTENT / "source" / "generation_1" / "firered"
BATTLE_DIR = CONTENT / "battles" / "generation_1" / "firered"

GYM_TRAINERS = {
    "pewter": [142],
    "cerulean": [150, 234],
    "vermilion": [141, 220, 423],
    "celadon": [132, 133, 160, 265, 266, 267, 402],
    "fuchsia": [288, 289, 292, 293, 294, 295],
    "saffron": [280, 281, 282, 283, 462, 463, 464],
    "cinnabar": [177, 178, 179, 180, 213, 214, 215],
    "viridian": [296, 297, 322, 323, 324, 392, 400, 401],
}
ROUTE_24_25_TRAINERS = [
    92, 93, 94, 95, 110, 122, 123, 125,
    143, 144, 153, 182, 183, 184, 356, 471,
    334,  # 테스트판은 플레이어가 이상해씨를 골랐을 때의 라이벌 팀으로 고정한다.
]
VICTORY_ROAD_TRAINERS = [167, 287, 290, 298, 325, 393, 394, 396, 403, 404, 406, 485]

CLASS_ALIASES = {
    "boss": "villain_boss",
    "burglar": "villain_grunt",
    "channeler": "hex_maniac",
    "cool_couple": "young_couple_female",
    "cooltrainer": "ace_trainer_male",
    "engineer": "scientist",
    "juggler": "psychic",
    "leader": "gym_leader",
    "rival_early": "rival",
    "rival_late": "rival",
    "swimmer_f": "swimmer_female",
    "swimmer_m": "swimmer_male",
    "team_rocket": "villain_grunt",
}


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def class_for(entry: dict, classes: dict[str, dict]) -> dict:
    trainer_type = entry["trainer_type"]
    class_id = CLASS_ALIASES.get(trainer_type, trainer_type)
    if trainer_type == "cooltrainer" and entry["name"].split()[-1] in {
        "Alexa", "Caroline", "Julie", "Mary", "Michelle", "Naomi", "Shannon",
    }:
        class_id = "ace_trainer_female"
    if class_id not in classes:
        class_id = "custom"
    return classes[class_id]


def materialize(entry: dict, classes: dict[str, dict]) -> tuple[str, str]:
    slug = entry["id"]
    npc_id = f"cobbleventure:npc/{slug}"
    battle_id = f"cobbleventure:battle/{slug}"
    trainer_class = class_for(entry, classes)
    level = entry["max_level"]

    battle = {
        "$schema": "../../../schemas/battle-preset.schema.json",
        "schema_version": 1,
        "id": battle_id,
        "enabled": True,
        "name": {"ko_kr": f"{entry['name']} 배틀", "en_us": f"{entry['name']} Battle"},
        "battle": copy.deepcopy(entry["battle"]),
    }
    battle["battle"]["trainer_id"] = f"cobbleventure:trainer/{slug}"

    npc = {
        "$schema": "../../../schemas/npc-event-script.schema.json",
        "schema_version": 4,
        "id": npc_id,
        "enabled": True,
        "name": {"ko_kr": entry["name"], "en_us": entry["name"]},
        "description": {"ko_kr": "파이어레드 테스트 배치용 트레이너입니다."},
        "tags": ["trainer", "generation_1", "kanto", "firered", entry["trainer_type"]],
        "placement_profile": {
            "classification": "trainer",
            "expected_level": level,
            "preferred_biomes": [],
            "automatic_town_placement": False,
            "automatic_route_placement": True,
        },
        "npc": {
            "display_name": {"ko_kr": entry["name"], "en_us": entry["name"]},
            "role": "default",
            "trainer_class": trainer_class["id"],
            "appearance": copy.deepcopy(trainer_class["default_appearance"]),
            "behavior": {
                "movement": "stationary",
                "look_at_player": True,
                "invulnerable": True,
                "collision": True,
            },
        },
        "event_design": {
            "mode": "preset",
            "preset": {
                "type": "battle",
                "initial_trigger": {"type": "interact", "range": 4},
                "first_text": {"ko_kr": "승부하자!"},
                "battle": battle_id,
                "after_victory_trigger": {"type": "interact", "range": 4},
                "win_text": {"ko_kr": "좋은 승부였어!"},
                "loss_text": {"ko_kr": "다시 준비해서 도전해!"},
                "victory_state_key": f"cobbleventure:flag/trainer/{slug}/defeated",
            },
        },
        "event_runtime": {
            "engine": "cves_v5",
            "authoring": "preset",
            "script_id": f"cobbleventure:event_script/generation_1/{slug}",
        },
    }

    write_json(BATTLE_DIR / f"{slug}.json", battle)
    write_json(NPC_DIR / f"{slug}.json", npc)
    return npc_id, battle_id


def sync_gym_staff(references: dict[int, dict]) -> None:
    catalog = load_json(GYM_CATALOG)
    for gym in catalog["gyms"]:
        slug = gym["id"].rsplit("/", 1)[-1]
        numbers = GYM_TRAINERS.get(slug)
        if numbers is None:
            continue
        gym["staff"]["trainers"] = [
            {
                "id": f"trainer_{index}",
                "trainer_id": f"cobbleventure:npc/{references[number]['id']}",
                "anchor": f"trainer_{index}",
            }
            for index, number in enumerate(numbers, start=1)
        ]
    write_json(GYM_CATALOG, catalog)


def main() -> None:
    references = {
        entry["entry_number"]: entry
        for entry in load_json(REFERENCE_CATALOG)["entries"]
        if entry["source"] == "firered"
    }
    classes = {
        entry["id"].rsplit("/", 1)[-1]: entry
        for entry in load_json(CLASS_CATALOG)["classes"]
    }
    numbers = sorted({
        *ROUTE_24_25_TRAINERS,
        *VICTORY_ROAD_TRAINERS,
        *(number for values in GYM_TRAINERS.values() for number in values),
    })
    for number in numbers:
        materialize(references[number], classes)
    sync_gym_staff(references)
    print(f"materialized {len(numbers)} FireRed test trainers")


if __name__ == "__main__":
    main()

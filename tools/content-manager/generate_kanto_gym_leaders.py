#!/usr/bin/env python3
"""Generate the eight Kanto gym leader NPCs and battle presets from references."""

from __future__ import annotations

import copy
import json
import os
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROJECT_ROOT = Path(os.environ.get(
    "COBBLEVENTURE_PROJECT_PATH", ROOT / "content-projects/cobbleventure-main"
)).resolve()
REFERENCE_CATALOG = PROJECT_ROOT / "content/catalogs/trainer-reference-entries.json"
BATTLE_ROOT = PROJECT_ROOT / "content/battles/gym_leaders"
NPC_ROOT = PROJECT_ROOT / "content/source/trainers/gym_leaders"

LEADERS = (
    {
        "slug": "brock", "name_ko": "웅", "name_en": "Brock",
        "character": "brock", "reference": "another_red_brock_001",
        "appearance": "kanto_brock", "badge": "kanto_boulder_badge",
    },
    {
        "slug": "misty", "name_ko": "이슬", "name_en": "Misty",
        "character": "misty", "reference": "another_red_misty_001",
        "appearance": "kanto_misty", "badge": "kanto_cascade_badge",
    },
    {
        "slug": "lt_surge", "name_ko": "마티스", "name_en": "Lt. Surge",
        "character": "lt_surge", "reference": "another_red_surge_001",
        "appearance": "kanto_ltsurge", "badge": "kanto_thunder_badge",
    },
    {
        "slug": "erika", "name_ko": "민화", "name_en": "Erika",
        "character": "erika", "reference": "another_red_erika_001",
        "appearance": "kanto_erika", "badge": "kanto_rainbow_badge",
    },
    {
        "slug": "koga", "name_ko": "독수", "name_en": "Koga",
        "character": "koga", "reference": "rct_kanto_koga",
        "appearance": "kanto_koga", "badge": "kanto_soul_badge",
    },
    {
        "slug": "sabrina", "name_ko": "초련", "name_en": "Sabrina",
        "character": "sabrina", "reference": "another_red_sabrina_001",
        "appearance": "kanto_sabrina", "badge": "kanto_marsh_badge",
    },
    {
        "slug": "blaine", "name_ko": "강연", "name_en": "Blaine",
        "character": "blaine", "reference": "another_red_blaine_001",
        "appearance": "kanto_blaine", "badge": "kanto_volcano_badge",
    },
    {
        "slug": "giovanni_gym", "name_ko": "비주기", "name_en": "Giovanni",
        "character": "giovanni_gym", "reference": "another_red_giovanni_001",
        "appearance": "kanto_giovanni", "badge": "kanto_earth_badge",
    },
)


def localized(leader: dict[str, str], prefix: str = "") -> dict[str, str]:
    return {
        "ko_kr": f"{prefix}{leader['name_ko']}",
        "en_us": f"{prefix}{leader['name_en']}",
    }


def battle_document(leader: dict[str, str], reference: dict) -> dict:
    battle = copy.deepcopy(reference["battle"])
    battle["trainer_id"] = f"cobbleventure:npc/gym_leader/{leader['slug']}"
    return {
        "$schema": "../../schemas/battle-preset.schema.json",
        "schema_version": 1,
        "id": f"cobbleventure:battle/gym_leader/{leader['slug']}",
        "enabled": True,
        "name": localized(leader, "관동 체육관 관장 "),
        "battle": battle,
    }


def npc_document(leader: dict[str, str]) -> dict:
    slug = leader["slug"]
    flag = f"cobbleventure:flag/gym/kanto/{slug}/defeated"
    battle = f"cobbleventure:battle/gym_leader/{slug}"
    badge = f"cobbleversebadges:{leader['badge']}"
    return {
        "$schema": "../../../schemas/npc-event-script.schema.json",
        "schema_version": 4,
        "id": f"cobbleventure:npc/gym_leader/{slug}",
        "enabled": True,
        "name": localized(leader),
        "description": {
            "ko_kr": f"{leader['name_ko']} 관장 NPC. {leader['reference']} 팀을 기준으로 한다.",
            "en_us": f"Gym Leader {leader['name_en']}, based on {leader['reference']}.",
        },
        "tags": ["trainer", "gym_leader", "kanto", slug],
        "npc": {
            "display_name": {
                "ko_kr": f"체육관 관장 {leader['name_ko']}",
                "en_us": f"Gym Leader {leader['name_en']}",
            },
            "trainer_class": "cobbleventure:trainer_class/gym_leader",
            "character": f"cobbleventure:character/{leader['character']}",
            "appearance": {
                "source": "rct_single", "type": "skin",
                "resource": f"rctmod:trainers/single/{leader['appearance']}",
            },
            "behavior": {
                "movement": "stationary", "look_at_player": True,
                "invulnerable": True, "collision": True,
            },
        },
        "events": [{
            "id": "on_interact",
            "trigger": {"type": "interact", "range": 4.0},
            "commands": [
                {"type": "branch", "conditions": [{"type": "flag_equals", "key": flag, "value": True}], "target": "cleared"},
                {"type": "label", "name": "challenge"},
                {"type": "dialogue", "id": "challenge", "speaker": "npc", "text": {"ko_kr": "체육관에 온 것을 환영한다. 준비가 됐다면 승부하자!", "en_us": "Welcome to my Gym. Challenge me when you are ready!"}},
                {"type": "choices", "options": [
                    {"id": "battle", "text": {"ko_kr": "승부한다", "en_us": "Battle"}, "target": "battle"},
                    {"id": "cancel", "text": {"ko_kr": "다음에 도전한다", "en_us": "Not yet"}, "target": "end"},
                ]},
                {"type": "label", "name": "battle"},
                {"type": "start_battle", "battle": battle, "results": {"player_win": "victory", "player_loss": "defeat"}},
                {"type": "label", "name": "victory"},
                {"type": "set_flag", "key": flag, "value": True},
                {"type": "give_item", "item": badge, "count": 1},
                {"type": "dialogue", "id": "victory", "speaker": "npc", "text": {"ko_kr": "훌륭한 승부였다. 이 배지는 네 것이다.", "en_us": "An excellent battle. This Badge is yours."}},
                {"type": "goto", "target": "end"},
                {"type": "label", "name": "defeat"},
                {"type": "dialogue", "id": "defeat", "speaker": "npc", "text": {"ko_kr": "좋은 승부였다. 준비해서 다시 도전해라.", "en_us": "A good battle. Prepare and challenge me again."}},
                {"type": "goto", "target": "end"},
                {"type": "label", "name": "cleared"},
                {"type": "dialogue", "id": "cleared", "speaker": "npc", "text": {"ko_kr": "이미 실력을 증명했군. 다음 목표를 향해 나아가라.", "en_us": "You have already proven yourself. Move on to your next goal."}},
                {"type": "label", "name": "end"},
                {"type": "end"},
            ],
        }],
    }


def write_json(path: Path, document: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def generate() -> list[Path]:
    catalog = json.loads(REFERENCE_CATALOG.read_text(encoding="utf-8"))
    references = {entry["id"]: entry for entry in catalog["entries"]}
    written: list[Path] = []
    for leader in LEADERS:
        reference = references.get(leader["reference"])
        if reference is None:
            raise ValueError(f"Missing trainer reference: {leader['reference']}")
        battle_path = BATTLE_ROOT / f"{leader['slug']}.json"
        npc_path = NPC_ROOT / f"{leader['slug']}.json"
        write_json(battle_path, battle_document(leader, reference))
        write_json(npc_path, npc_document(leader))
        written.extend((battle_path, npc_path))
    return written


if __name__ == "__main__":
    for generated in generate():
        print(generated.relative_to(ROOT).as_posix())

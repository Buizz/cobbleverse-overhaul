#!/usr/bin/env python3
"""Build the Content Manager trainer reference catalog from Another Red and RCT."""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from pathlib import Path
from typing import Any


STAT_NAMES = {
    "hp": "hp",
    "atk": "attack",
    "def": "defense",
    "defence": "defense",
    "spa": "special_attack",
    "spd": "special_defense",
    "special_defence": "special_defense",
    "spe": "speed",
}


def _namespaced(value: Any, namespace: str) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return None
    value = value.strip().lower()
    return value if ":" in value else f"{namespace}:{value}"


def _stats(raw: Any) -> dict[str, int]:
    if not isinstance(raw, dict):
        return {}
    return {
        STAT_NAMES.get(str(key).lower(), str(key).lower()): int(value)
        for key, value in raw.items()
        if isinstance(value, (int, float))
    }


def _member(raw: dict[str, Any]) -> dict[str, Any]:
    gender = str(raw.get("gender") or "random").lower()
    if gender not in {"male", "female", "genderless", "random"}:
        gender = "random"
    held_item = raw.get("held_item", raw.get("heldItem"))
    moves = raw.get("moves", raw.get("moveset", []))
    tera_type = raw.get("tera_type", raw.get("teraType"))
    normalized_moves = list(dict.fromkeys(
        str(move).lower() for move in (moves or []) if isinstance(move, str)
    )) or ["tackle"]
    return {
        "species": _namespaced(raw.get("species"), "cobblemon") or "cobblemon:missingno",
        "level": max(1, int(raw.get("level") or 1)),
        "form": raw.get("form"),
        "aspects": list(raw.get("aspects") or []),
        "gender": gender,
        "nature": str(raw.get("nature") or "hardy").lower(),
        "ability": str(raw.get("ability") or "").lower() or None,
        "held_item": _namespaced(held_item, "cobblemon"),
        "gimmick": None,
        "moves": normalized_moves,
        "ivs": _stats(raw.get("ivs")),
        "evs": _stats(raw.get("evs")),
        "tera_type": str(tera_type).lower() if tera_type else "auto",
        "shiny": bool(raw.get("shiny", False)),
        "gigantamax_factor": bool(raw.get("gigantamax_factor", raw.get("gmaxFactor", False))),
    }


def _battle(raw: dict[str, Any]) -> dict[str, Any]:
    battle_format = str(raw.get("battleFormat") or raw.get("format") or "GEN_9_SINGLES")
    team = [_member(member) for member in raw.get("team", []) if isinstance(member, dict)]
    bag = []
    for item in raw.get("bag", []) or []:
        if not isinstance(item, dict):
            continue
        item_id = _namespaced(item.get("item"), "cobblemon")
        if item_id:
            bag.append({"item": item_id, "quantity": max(1, int(item.get("quantity") or 1))})
    battle_rules = raw.get("battleRules") if isinstance(raw.get("battleRules"), dict) else {}
    rules = {"can_forfeit": True}
    if "maxItemUses" in battle_rules:
        rules["max_item_uses"] = int(battle_rules["maxItemUses"])
    return {
        "format": battle_format,
        "battle_type": "doubles" if battle_format == "GEN_9_DOUBLES" else "singles",
        "ai": {
            "controller": "cobbleventure",
            "difficulty": "standard",
            "strategy": "balanced",
            "options": {},
        },
        "level_mode": "fixed",
        "rules": rules,
        "bag": bag,
        "mechanics": {
            "mega_evolution": False,
            "z_move": False,
            "dynamax": False,
            "terastallization": any(member.get("tera_type") for member in team),
        },
        "team": team,
    }


def _level_summary(team: list[dict[str, Any]]) -> tuple[int, int]:
    levels = [int(member.get("level") or 0) for member in team]
    return (min(levels), max(levels)) if levels else (0, 0)


def load_another_red(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    entries: list[dict[str, Any]] = []
    for raw in payload.get("entries", []):
        if not isinstance(raw, dict) or not isinstance(raw.get("data"), dict):
            continue
        battle = _battle(raw["data"])
        minimum, maximum = _level_summary(battle["team"])
        entries.append({
            "id": str(raw.get("entryKey") or Path(str(raw.get("file") or "entry")).stem),
            "source": "another_red",
            "source_label": "Pokemon Another Red",
            "category": str(raw.get("sourceCategory") or "기타"),
            "name": str(raw.get("name") or raw["data"].get("name") or "이름 없음"),
            "entry_number": int(raw.get("entryNumber") or 0),
            "trainer_type": str(raw.get("sourceTrainerType") or ""),
            "primary_type": raw.get("primaryType"),
            "team_size": len(battle["team"]),
            "min_level": minimum,
            "max_level": maximum,
            "battle": battle,
        })
    return entries


def _rct_category(slug: str) -> str:
    if any(token in slug for token in ("leader", "gym", "kanto_brock", "kanto_misty")):
        return "관장"
    if "champion" in slug:
        return "챔피언"
    if "league" in slug or "elite" in slug:
        return "사천왕"
    if any(token in slug for token in ("boss", "admin", "commander", "rocket")):
        return "악의 조직"
    return "RCT 기본"


def load_rct(path: Path) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    with zipfile.ZipFile(path) as archive:
        names = sorted(
            name for name in archive.namelist()
            if name.startswith("data/rctmod/trainers/") and name.lower().endswith(".json")
        )
        for name in names:
            raw = json.loads(archive.read(name).decode("utf-8-sig"))
            slug = Path(name).stem
            battle = _battle(raw)
            minimum, maximum = _level_summary(battle["team"])
            entries.append({
                "id": f"rct_{slug}",
                "source": "rct_default",
                "source_label": "Cobbleverse RCT v16",
                "category": _rct_category(slug),
                "name": str(raw.get("name") or re.sub(r"[_-]+", " ", slug).title()),
                "entry_number": 0,
                "trainer_type": slug,
                "primary_type": None,
                "team_size": len(battle["team"]),
                "min_level": minimum,
                "max_level": maximum,
                "battle": battle,
            })
    return entries


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--another-red", type=Path, required=True)
    parser.add_argument("--rct-zip", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    entries = load_another_red(args.another_red) + load_rct(args.rct_zip)
    entries.sort(key=lambda row: (
        row["source"], row["category"], row["name"].casefold(), row["entry_number"], row["id"]
    ))
    payload = {
        "$schema": "../schemas/trainer-reference-entries.schema.json",
        "schema_version": 1,
        "title": "Cobbleventure 트레이너 참고 엔트리",
        "sources": [
            {"id": "another_red", "display_name": "Pokemon Another Red"},
            {"id": "rct_default", "display_name": "Cobbleverse RCT v16"},
        ],
        "entries": entries,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(entries)} trainer references to {args.output}")


if __name__ == "__main__":
    main()

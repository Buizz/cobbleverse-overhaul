#!/usr/bin/env python3
"""Build the trainer reference catalog from Another Red, FireRed, and RCT."""

from __future__ import annotations

import argparse
from collections import Counter
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

FIRERED_SOURCE_REVISION = "c75f352304d529f6ba92d4f74b9cf8b5c3810788"
FIRERED_FIRST_TRAINER = 89
FIRERED_LAST_TRAINER = 742


def _c_blocks(text: str, marker: re.Pattern[str]) -> list[tuple[re.Match[str], str]]:
    """Return brace-balanced C initializer bodies that follow ``marker``."""
    blocks: list[tuple[re.Match[str], str]] = []
    for match in marker.finditer(text):
        start = match.end() - 1 if match.group(0).rstrip().endswith("{") else text.find("{", match.end())
        if start < 0:
            raise ValueError(f"C initializer has no opening brace after {match.group(0)!r}")
        depth = 0
        for index in range(start, len(text)):
            character = text[index]
            if character == "{":
                depth += 1
            elif character == "}":
                depth -= 1
                if depth == 0:
                    blocks.append((match, text[start + 1:index]))
                    break
        else:
            raise ValueError(f"Unclosed C initializer after {match.group(0)!r}")
    return blocks


def _c_field(body: str, name: str) -> str | None:
    match = re.search(rf"\.{re.escape(name)}\s*=\s*([^,\n}}]+)", body)
    return match.group(1).strip() if match else None


def _constant_slug(value: str, prefix: str, *, keep_underscores: bool) -> str:
    if not value.startswith(prefix):
        raise ValueError(f"Expected {prefix} constant, got {value}")
    slug = value[len(prefix):].lower()
    return slug if keep_underscores else slug.replace("_", "")


def _species_slug(value: str) -> str:
    slug = _constant_slug(value, "SPECIES_", keep_underscores=True)
    return {
        "mr_mime": "mrmime",
        "nidoran_f": "nidoranf",
        "nidoran_m": "nidoranm",
    }.get(slug, slug)


def _move_slug(value: str) -> str:
    slug = _constant_slug(value, "MOVE_", keep_underscores=False)
    return {
        "faintattack": "feintattack",
        "hijumpkick": "highjumpkick",
    }.get(slug, slug)


def _trainer_constants(text: str) -> dict[str, int]:
    return {
        name: int(number)
        for name, number in re.findall(r"^#define\s+(TRAINER_[A-Z0-9_]+)\s+(\d+)\s*$", text, re.MULTILINE)
        if FIRERED_FIRST_TRAINER <= int(number) <= FIRERED_LAST_TRAINER
    }


def _trainer_class_names(text: str) -> dict[str, str]:
    return {
        class_id: display.replace("{PKMN}", "Pokémon").replace("Poké", "Poké")
        for class_id, display in re.findall(
            r'\[(TRAINER_CLASS_[A-Z0-9_]+)\]\s*=\s*_\("([^"]*)"\)', text
        )
    }


def _level_up_moves(pointer_text: str, learnset_text: str) -> dict[str, list[tuple[int, str]]]:
    learnsets: dict[str, list[tuple[int, str]]] = {}
    marker = re.compile(
        r"static const u16\s+(s[A-Za-z0-9]+LevelUpLearnset)\[\]\s*=\s*"
    )
    for match, body in _c_blocks(learnset_text, marker):
        learnsets[match.group(1)] = [
            (int(level), _move_slug(move))
            for level, move in re.findall(
                r"LEVEL_UP_MOVE\(\s*(\d+)\s*,\s*(MOVE_[A-Z0-9_]+)\s*\)", body
            )
        ]
    pointers = {
        species: learnset
        for species, learnset in re.findall(
            r"\[(SPECIES_[A-Z0-9_]+)\]\s*=\s*(s[A-Za-z0-9]+LevelUpLearnset)",
            pointer_text,
        )
    }
    return {
        species: learnsets.get(learnset, [])
        for species, learnset in pointers.items()
    }


def _default_moves(
    learnsets: dict[str, list[tuple[int, str]]], species: str, level: int
) -> list[str]:
    known: list[str] = []
    for learned_level, move in learnsets.get(species, []):
        if learned_level > level:
            continue
        if move in known:
            known.remove(move)
        known.append(move)
    return known[-4:] or ["tackle"]


def _party_definitions(
    text: str, learnsets: dict[str, list[tuple[int, str]]]
) -> dict[str, list[dict[str, Any]]]:
    parties: dict[str, list[dict[str, Any]]] = {}
    marker = re.compile(
        r"static const struct\s+(TrainerMon(?:NoItem|Item)(?:DefaultMoves|CustomMoves))\s+"
        r"(sParty_[A-Za-z0-9_]+)\[\]\s*=\s*"
    )
    member_marker = re.compile(r"(?m)^\s*\{\s*$")
    for party_match, party_body in _c_blocks(text, marker):
        structure, party_name = party_match.groups()
        members: list[dict[str, Any]] = []
        for _, member_body in _c_blocks(party_body, member_marker):
            species_constant = _c_field(member_body, "species")
            level_value = _c_field(member_body, "lvl")
            iv_value = _c_field(member_body, "iv")
            if not species_constant or not level_value or not iv_value:
                continue
            level = int(level_value)
            if structure.endswith("CustomMoves"):
                moves_match = re.search(r"\.moves\s*=\s*\{([^}]*)\}", member_body)
                moves = [
                    _move_slug(value)
                    for value in re.findall(r"MOVE_[A-Z0-9_]+", moves_match.group(1) if moves_match else "")
                    if value != "MOVE_NONE"
                ]
            else:
                moves = _default_moves(learnsets, species_constant, level)
            source_iv = int(iv_value)
            fixed_iv = source_iv * 31 // 255
            member: dict[str, Any] = {
                "species": f"cobblemon:{_species_slug(species_constant)}",
                "level": level,
                "form": None,
                "aspects": [],
                "gender": "random",
                "nature": "hardy",
                "ability": None,
                "held_item": None,
                "gimmick": None,
                "moves": list(dict.fromkeys(moves)) or ["tackle"],
                "ivs": {stat: fixed_iv for stat in STAT_NAMES.values()},
                "evs": {},
                "tera_type": "auto",
                "shiny": False,
                "gigantamax_factor": False,
            }
            held_item = _c_field(member_body, "heldItem")
            if held_item and held_item != "ITEM_NONE":
                member["held_item"] = (
                    f"cobblemon:{_constant_slug(held_item, 'ITEM_', keep_underscores=True)}"
                )
            members.append(member)
        if not members:
            dummy = next(
                (token for token in ("DUMMY_TRAINER_STARMIE", "DUMMY_TRAINER_MON_IV", "DUMMY_TRAINER_MON")
                 if token in party_body),
                None,
            )
            if dummy:
                species_constant = "SPECIES_STARMIE" if dummy == "DUMMY_TRAINER_STARMIE" else "SPECIES_EKANS"
                level = 38 if dummy == "DUMMY_TRAINER_STARMIE" else 5
                fixed_iv = (100 * 31 // 255) if dummy == "DUMMY_TRAINER_MON_IV" else 0
                members.append({
                    "species": f"cobblemon:{_species_slug(species_constant)}",
                    "level": level,
                    "form": None,
                    "aspects": [],
                    "gender": "random",
                    "nature": "hardy",
                    "ability": None,
                    "held_item": None,
                    "gimmick": None,
                    "moves": _default_moves(learnsets, species_constant, level),
                    "ivs": {stat: fixed_iv for stat in STAT_NAMES.values()},
                    "evs": {},
                    "tera_type": "auto",
                    "shiny": False,
                    "gigantamax_factor": False,
                })
        parties[party_name] = members
    return parties


def _trainer_definitions(text: str) -> dict[str, dict[str, str]]:
    trainers: dict[str, dict[str, str]] = {}
    marker = re.compile(r"\[(TRAINER_[A-Z0-9_]+)\]\s*=\s*")
    for match, body in _c_blocks(text, marker):
        party_match = re.search(
            r"\.party\s*=\s*(NO_ITEM_DEFAULT_MOVES|NO_ITEM_CUSTOM_MOVES|"
            r"ITEM_DEFAULT_MOVES|ITEM_CUSTOM_MOVES)\((sParty_[A-Za-z0-9_]+)\)",
            body,
        )
        name_match = re.search(r'\.trainerName\s*=\s*_\("([^"]*)"\)', body)
        if not party_match or not name_match:
            continue
        trainers[match.group(1)] = {
            "class": _c_field(body, "trainerClass") or "TRAINER_CLASS_NONE",
            "name": name_match.group(1),
            "items": ",".join(re.findall(r"ITEM_[A-Z0-9_]+", (
                re.search(r"\.items\s*=\s*\{([^}]*)\}", body).group(1)
                if re.search(r"\.items\s*=\s*\{([^}]*)\}", body) else ""
            ))),
            "double": _c_field(body, "doubleBattle") or "FALSE",
            "ai": _c_field(body, "aiFlags") or "0",
            "party_kind": party_match.group(1),
            "party": party_match.group(2),
        }
    return trainers


def _fire_red_category(class_name: str, trainer_constant: str, *, unused: bool = False) -> str:
    if unused:
        return "미사용 슬롯"
    if "LEADER" in trainer_constant:
        return "관장"
    if "ELITE_FOUR" in trainer_constant:
        return "사천왕"
    if "CHAMPION" in trainer_constant:
        return "챔피언"
    if "RIVAL" in trainer_constant:
        return "라이벌"
    if "ROCKET" in trainer_constant or "BOSS_GIOVANNI" in trainer_constant:
        return "로켓단"
    return class_name.title()


def load_fire_red(root: Path) -> list[dict[str, Any]]:
    """Load all 654 real FRLG trainer slots from pret/pokefirered."""
    required = {
        "constants": root / "include/constants/opponents.h",
        "trainers": root / "src/data/trainers.h",
        "parties": root / "src/data/trainer_parties.h",
        "classes": root / "src/data/text/trainer_class_names.h",
        "pointers": root / "src/data/pokemon/level_up_learnset_pointers.h",
        "learnsets": root / "src/data/pokemon/level_up_learnsets.h",
    }
    missing = [str(path) for path in required.values() if not path.is_file()]
    if missing:
        raise FileNotFoundError("Missing pokefirered source files: " + ", ".join(missing))

    read = {key: path.read_text(encoding="utf-8") for key, path in required.items()}
    constants = _trainer_constants(read["constants"])
    class_names = _trainer_class_names(read["classes"])
    learnsets = _level_up_moves(read["pointers"], read["learnsets"])
    parties = _party_definitions(read["parties"], learnsets)
    dummy_parties = set(re.findall(
        r"static const struct\s+TrainerMon[A-Za-z]+\s+(sParty_[A-Za-z0-9_]+)\[\]\s*=\s*"
        r"\{DUMMY_TRAINER_[A-Z_]+\};",
        read["parties"],
    ))
    trainers = _trainer_definitions(read["trainers"])
    entries: list[dict[str, Any]] = []
    for trainer_constant, entry_number in sorted(constants.items(), key=lambda item: item[1]):
        raw = trainers.get(trainer_constant)
        if not raw:
            raise ValueError(f"Missing trainer definition for {trainer_constant}")
        team = parties.get(raw["party"])
        if not team:
            raise ValueError(f"Missing or empty party {raw['party']} for {trainer_constant}")
        item_constants = [value for value in raw["items"].split(",") if value != "ITEM_NONE" and value]
        item_counts = Counter(item_constants)
        bag = [
            {
                "item": f"cobblemon:{_constant_slug(item, 'ITEM_', keep_underscores=True)}",
                "quantity": quantity,
            }
            for item, quantity in item_counts.items()
        ]
        flags = raw["ai"]
        difficulty = (
            "advanced" if "AI_SCRIPT_CHECK_VIABILITY" in flags
            else "standard" if "AI_SCRIPT_CHECK_BAD_MOVE" in flags
            else "novice"
        )
        battle_format = "GEN_9_DOUBLES" if raw["double"] == "TRUE" else "GEN_9_SINGLES"
        battle = {
            "format": battle_format,
            "battle_type": "doubles" if raw["double"] == "TRUE" else "singles",
            "ai": {
                "controller": "cobbleventure",
                "difficulty": difficulty,
                "strategy": "balanced",
                "options": {},
            },
            "level_mode": "fixed",
            "rules": {"can_forfeit": True, "max_item_uses": len(item_constants)},
            "bag": bag,
            "mechanics": {
                "mega_evolution": False,
                "z_move": False,
                "dynamax": False,
                "terastallization": False,
            },
            "team": team,
        }
        minimum, maximum = _level_summary(team)
        class_name = class_names.get(raw["class"], raw["class"].removeprefix("TRAINER_CLASS_"))
        display_name = " ".join(value for value in (class_name.title(), raw["name"].title()) if value)
        entries.append({
            "id": f"firered_{trainer_constant.removeprefix('TRAINER_').lower()}",
            "source": "firered",
            "source_label": f"Pokémon FireRed/LeafGreen ({FIRERED_SOURCE_REVISION[:8]})",
            "category": _fire_red_category(
                class_name, trainer_constant, unused=raw["party"] in dummy_parties
            ),
            "name": display_name or trainer_constant.removeprefix("TRAINER_").replace("_", " ").title(),
            "entry_number": entry_number,
            "trainer_type": raw["class"].removeprefix("TRAINER_CLASS_").lower(),
            "primary_type": None,
            "team_size": len(team),
            "min_level": minimum,
            "max_level": maximum,
            "battle": battle,
        })
    expected_count = FIRERED_LAST_TRAINER - FIRERED_FIRST_TRAINER + 1
    if len(entries) != expected_count:
        raise ValueError(f"Expected {expected_count} FireRed trainers, loaded {len(entries)}")
    return entries


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
    parser.add_argument("--another-red", type=Path)
    parser.add_argument("--pokefirered-root", type=Path)
    parser.add_argument("--rct-zip", type=Path)
    parser.add_argument(
        "--existing-catalog",
        type=Path,
        help="Preserve sources that are not being refreshed by this invocation.",
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    loaders = {
        "another_red": (args.another_red, load_another_red),
        "firered": (args.pokefirered_root, load_fire_red),
        "rct_default": (args.rct_zip, load_rct),
    }
    refreshed_sources = {source for source, (path, _) in loaders.items() if path is not None}
    entries: list[dict[str, Any]] = []
    if args.existing_catalog:
        existing = json.loads(args.existing_catalog.read_text(encoding="utf-8-sig"))
        entries.extend(
            entry for entry in existing.get("entries", [])
            if entry.get("source") not in refreshed_sources
        )
    if not refreshed_sources and not entries:
        parser.error("provide at least one source or --existing-catalog")
    for _, (path, loader) in loaders.items():
        if path is not None:
            entries.extend(loader(path))
    entries.sort(key=lambda row: (
        row["source"], row["category"], row["name"].casefold(), row["entry_number"], row["id"]
    ))
    source_ids = {entry["source"] for entry in entries}
    source_labels = {
        "another_red": "Pokemon Another Red",
        "firered": f"Pokémon FireRed/LeafGreen · pret/pokefirered {FIRERED_SOURCE_REVISION[:8]}",
        "rct_default": "Cobbleverse RCT v16",
    }
    payload = {
        "$schema": "../schemas/trainer-reference-entries.schema.json",
        "schema_version": 1,
        "title": "Cobbleventure 트레이너 참고 엔트리",
        "sources": [
            {"id": source, "display_name": label}
            for source, label in source_labels.items()
            if source in source_ids
        ],
        "entries": entries,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(entries)} trainer references to {args.output}")


if __name__ == "__main__":
    main()

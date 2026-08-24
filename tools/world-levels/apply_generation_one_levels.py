#!/usr/bin/env python3
"""Apply the Kanto story progression to generation one's world level overlay.

The existing overlay footprint is intentionally preserved.  Each painted cell is
assigned to its nearest settlement first, then route corridors receive their
story-appropriate level, and settlement anchors are restored last so route
endpoints do not overwrite town levels.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORLD_PATH = (
    REPOSITORY_ROOT
    / "content-projects"
    / "cobbleventure-main"
    / "content"
    / "worlds"
    / "generation_1.json"
)

# Average wild levels at each settlement. Runtime encounters use average +/- 2.
SETTLEMENT_LEVELS = {
    "cobbleventure:settlement/starter_town": 4,       # Pallet Town
    "cobbleventure:settlement/route_01_town": 6,      # Viridian City
    "cobbleventure:settlement/crimson_town": 9,       # Pewter City
    "cobbleventure:settlement/cerulean_city": 15,
    "cobbleventure:settlement/vermilion_city": 18,
    "cobbleventure:settlement/lavender_town": 24,
    "cobbleventure:settlement/celadon_city": 25,
    "cobbleventure:settlement/saffron_city": 28,
    "cobbleventure:settlement/fuchsia_city": 33,
    "cobbleventure:settlement/tidehaven_town": 42,    # Cinnabar Island
    "cobbleventure:settlement/skyreach_town": 50,     # Indigo Plateau
}

# Route values follow the generation-one encounter ranges already defined in
# content/routes/generation_1. Victory Road is deliberately the final route.
ROUTE_LEVELS = {
    "route_custom_03": 4,                 # Pallet - Viridian
    "route_custom_04": 5,                 # Viridian - Viridian Forest
    "route_viridian_forest_north": 6,
    "route_custom_02": 7,                 # Pewter - Mt. Moon
    "route_custom_01": 10,                # Mt. Moon - Cerulean
    "route_custom_12": 15,                # Cerulean - Saffron
    "route_custom_11": 15,                # Saffron - Vermilion
    "route_custom_16": 16,                # Cerulean - Rock Tunnel
    "route_custom_17": 17,                # Rock Tunnel - Lavender
    "route_custom_06": 20,                # Celadon - Saffron
    "route_custom_18": 22,                # Vermilion - Lavender
    "route_custom_13": 23,                # Saffron - Lavender
    "route_custom_14": 24,                # Vermilion - Lavender
    "route_custom_15": 27,                # Lavender - Fuchsia waterway
    "route_custom_07": 28,                # Celadon - Fuchsia
    "route_custom_10": 30,                # Lavender - Fuchsia
    "route_custom_09": 35,                # Fuchsia - Cinnabar
    "route_custom_08": 38,                # Pallet - Cinnabar
    "route_custom_05": 40,                # Viridian - Indigo Plateau
}


def hex_distance(left: tuple[int, int], right: tuple[int, int]) -> int:
    q_delta = left[0] - right[0]
    r_delta = left[1] - right[1]
    return (abs(q_delta) + abs(r_delta) + abs(q_delta + r_delta)) // 2


def build_levels(world: dict) -> list[dict[str, int]]:
    settlements = [
        (
            entry["settlement"],
            (entry["anchor"]["q"], entry["anchor"]["r"]),
            SETTLEMENT_LEVELS[entry["settlement"]],
        )
        for entry in world["settlements"]
    ]
    unknown = {entry["settlement"] for entry in world["settlements"]} - SETTLEMENT_LEVELS.keys()
    if unknown:
        raise ValueError(f"Settlement levels are missing for: {sorted(unknown)}")

    route_cells = {
        connection["id"]: [(cell["q"], cell["r"]) for cell in connection.get("cells", [])]
        for connection in world["connections"]
        if connection["id"] in ROUTE_LEVELS
    }
    missing_routes = ROUTE_LEVELS.keys() - route_cells.keys()
    if missing_routes:
        raise ValueError(f"Configured routes are missing from the world: {sorted(missing_routes)}")

    result: list[dict[str, int]] = []
    for previous in world["level_overrides"]:
        coordinate = (previous["q"], previous["r"])
        _, _, level = min(settlements, key=lambda entry: hex_distance(coordinate, entry[1]))

        route_candidates = [
            (min(hex_distance(coordinate, route_cell) for route_cell in cells), ROUTE_LEVELS[route_id])
            for route_id, cells in route_cells.items()
            if cells
        ]
        route_distance, route_level = min(
            route_candidates,
            key=lambda candidate: (candidate[0], abs(candidate[1] - level)),
        )
        if route_distance <= 1:
            level = route_level

        # Keep towns legible on the overlay even where several routes converge.
        nearby_settlements = [
            (hex_distance(coordinate, anchor), town_level)
            for _, anchor, town_level in settlements
        ]
        settlement_distance, settlement_level = min(nearby_settlements)
        if settlement_distance <= 1:
            level = settlement_level

        result.append({"q": coordinate[0], "r": coordinate[1], "average_level": level})

    return result


def replace_top_level_overlay(source: str, levels: list[dict[str, int]]) -> str:
    marker = '  "level_overrides": ['
    start = source.find(marker, source.find('  "environment_overrides"'))
    if start < 0:
        raise ValueError("Top-level level_overrides array was not found")

    array_start = source.index("[", start)
    depth = 0
    in_string = False
    escaped = False
    array_end = -1
    for index in range(array_start, len(source)):
        character = source[index]
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue
        if character == '"':
            in_string = True
        elif character == "[":
            depth += 1
        elif character == "]":
            depth -= 1
            if depth == 0:
                array_end = index + 1
                break
    if array_end < 0:
        raise ValueError("Top-level level_overrides array is not closed")

    encoded = json.dumps(levels, ensure_ascii=False, indent=2)
    encoded = encoded.replace("\n", "\n  ")
    return source[:start] + '  "level_overrides": ' + encoded + source[array_end:]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="write the calculated overlay")
    arguments = parser.parse_args()

    source = WORLD_PATH.read_text(encoding="utf-8")
    world = json.loads(source)
    levels = build_levels(world)
    counts: dict[int, int] = {}
    for entry in levels:
        counts[entry["average_level"]] = counts.get(entry["average_level"], 0) + 1

    updated = replace_top_level_overlay(source, levels)
    changed = updated != source
    print(f"cells={len(levels)} changed={changed} range={min(counts)}-{max(counts)}")
    print("distribution=" + ", ".join(f"Lv.{level}:{counts[level]}" for level in sorted(counts)))

    if arguments.apply:
        WORLD_PATH.write_text(updated, encoding="utf-8")
        print(f"updated={WORLD_PATH}")
        return 0
    return 1 if changed else 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Generate the temporary 24x16x20 surface building for underground passages."""

from __future__ import annotations

import json
from pathlib import Path

from generate_underground_road_modules import ROOT, marker, serialize_structure

SIZE = (24, 16, 20)
OUTPUT = ROOT / "content-projects/cobbleventure-main/content/structures/underground_entrance/underground_passage.nbt"
METADATA_OUTPUT = OUTPUT.with_suffix(".structure.json")


def generate() -> Path:
    width, height, depth = SIZE
    air = ("minecraft:air", (), None)
    blocks: dict[tuple[int, int, int], tuple[str, tuple[tuple[str, str], ...], dict[str, object] | None]] = {}

    # Clear and floor the authored building volume. The broad footprint makes
    # the placeholder easy to spot and leaves room for a later detailed build.
    for x in range(2, width - 2):
        for z in range(2, depth - 2):
            blocks[(x, 0, z)] = ("minecraft:polished_deepslate", (), None)
            for y in range(1, 13):
                blocks[(x, y, z)] = air

    # Main stone station shell with an open four-block-wide front doorway.
    for y in range(1, 12):
        for x in range(2, width - 2):
            if not (10 <= x <= 13 and y <= 4):
                blocks[(x, y, 2)] = ("minecraft:deepslate_bricks", (), None)
            blocks[(x, y, depth - 3)] = ("minecraft:deepslate_bricks", (), None)
        for z in range(2, depth - 2):
            blocks[(2, y, z)] = ("minecraft:deepslate_bricks", (), None)
            blocks[(width - 3, y, z)] = ("minecraft:deepslate_bricks", (), None)

    # Windows and a lighter horizontal trim keep the temporary building legible.
    for x in (5, 6, 17, 18):
        for y in range(4, 8):
            blocks[(x, y, 2)] = ("minecraft:tinted_glass", (), None)
            blocks[(x, y, depth - 3)] = ("minecraft:tinted_glass", (), None)
    for z in (6, 7, 12, 13):
        for y in range(4, 8):
            blocks[(2, y, z)] = ("minecraft:tinted_glass", (), None)
            blocks[(width - 3, y, z)] = ("minecraft:tinted_glass", (), None)
    for x in range(2, width - 2):
        for z in range(2, depth - 2):
            blocks[(x, 12, z)] = ("minecraft:stone_bricks", (), None)
    for x in range(1, width - 1):
        blocks[(x, 13, 1)] = ("minecraft:polished_deepslate", (), None)
        blocks[(x, 13, depth - 2)] = ("minecraft:polished_deepslate", (), None)
    for z in range(1, depth - 1):
        blocks[(1, 13, z)] = ("minecraft:polished_deepslate", (), None)
        blocks[(width - 2, 13, z)] = ("minecraft:polished_deepslate", (), None)

    # Raised center sign block gives the building the requested full 16-block height.
    for x in range(8, 16):
        for z in range(7, 13):
            blocks[(x, 13, z)] = ("minecraft:stone_bricks", (), None)
            blocks[(x, 15, z)] = ("minecraft:polished_deepslate", (), None)
    for y in range(14, 16):
        for x in range(8, 16):
            blocks[(x, y, 7)] = ("minecraft:stone_bricks", (), None)
            blocks[(x, y, 12)] = ("minecraft:stone_bricks", (), None)
        for z in range(7, 13):
            blocks[(8, y, z)] = ("minecraft:stone_bricks", (), None)
            blocks[(15, y, z)] = ("minecraft:stone_bricks", (), None)

    # Four-wide front apron. The road anchor is the authoritative placement
    # origin: runtime aligns this exact block to the generated road surface.
    for x in range(10, 14):
        for z in range(0, 3):
            blocks[(x, 0, z)] = ("minecraft:smooth_stone", (), None)
            for y in range(1, 5):
                blocks[(x, y, z)] = air
    blocks[(11, 1, 0)] = marker(
        "cobbleventure:road_anchor", "north", "minecraft:smooth_stone"
    )

    # A coarse descending stair and dark landing show where the future authored
    # stairwell belongs. The connected barrier wall is the touch transition zone;
    # its seed and label live in the structure metadata instead of a jigsaw.
    for offset, y in enumerate((4, 3, 2, 1)):
        z = 9 + offset
        for x in range(10, 14):
            for fill_y in range(1, y + 1):
                blocks[(x, fill_y, z)] = ("minecraft:stone_bricks", (), None)
    for x in range(9, 15):
        for z in range(13, 17):
            blocks[(x, 0, z)] = ("minecraft:polished_blackstone_bricks", (), None)
    for x in range(10, 14):
        for y in range(1, 4):
            blocks[(x, y, 14)] = ("minecraft:barrier", (), None)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(serialize_structure(SIZE, blocks))
    METADATA_OUTPUT.write_text(
        json.dumps({
            "schema_version": 1,
            "structure": "content/structures/underground_entrance/underground_passage.nbt",
            "anchors": [{
                "id": "underground_entry",
                "label": "underground_entry",
                "type": "transition",
                "position": [11, 1, 14],
                "safe_spawn": [11, 1, 13],
                "facing": "north",
            }],
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return OUTPUT


if __name__ == "__main__":
    print(generate().relative_to(ROOT).as_posix())

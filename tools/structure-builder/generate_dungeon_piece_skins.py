#!/usr/bin/env python3
"""Generate editable, contract-compatible dungeon piece skin placeholders."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from generate_underground_road_modules import serialize_structure


ROOT = Path(__file__).resolve().parents[2]
PROJECT = Path("content-projects/cobbleventure-main")
SIZE = (16, 8, 16)
SOCKET = "cobbleventure:dungeon_socket/standard_5"
KIT_TAG = "cobbleventure:dungeon_kit/standard_16"


@dataclass(frozen=True)
class Shape:
    role: str
    directions: tuple[str, ...]
    room_margin: int | None = None
    connector_heights: tuple[tuple[str, int], ...] = ()
    markers: tuple[tuple[str, str, tuple[int, int, int], str | None], ...] = ()
    weight: int = 10


SHAPES = {
    "start": Shape(
        "start", ("west", "east"), 3,
        markers=(("entry", "entry", (3, 1, 7), None),), weight=1,
    ),
    "corridor": Shape("corridor", ("west", "east"), weight=18),
    "corner": Shape("corridor", ("west", "south"), weight=10),
    "junction": Shape("junction", ("west", "east", "north", "south"), weight=7),
    "room": Shape("room", ("west", "east", "north", "south"), 3, weight=14),
    "encounter_room": Shape(
        "room", ("west", "east", "north", "south"), 3,
        markers=(("encounter_1", "encounter", (8, 1, 8), "encounter_1"),),
        weight=5,
    ),
    "stairs_up": Shape(
        "corridor", ("west", "east"), connector_heights=(("west", 1), ("east", 5)),
        weight=5,
    ),
    "stairs_down": Shape(
        "corridor", ("west", "east"), connector_heights=(("west", 5), ("east", 1)),
        weight=5,
    ),
    "dead_end": Shape("dead_end", ("west",), 4, weight=8),
    "support": Shape("support", ("west", "east"), 3, weight=5),
    "treasure": Shape(
        "treasure", ("north",), 3,
        markers=(("loot_1", "loot", (8, 1, 8), "loot_1"),), weight=4,
    ),
    "boss": Shape(
        "boss", ("west", "east"), 2,
        markers=(("boss_1", "boss", (8, 1, 8), "boss_1"),), weight=1,
    ),
    "exit": Shape(
        "exit", ("west",), 3,
        markers=(
            ("exit", "exit", (12, 1, 7), None),
            ("clear_exit", "objective", (8, 1, 8), "clear_exit"),
        ),
        weight=1,
    ),
}


SKINS = {
    "rocket": {
        "floor": "minecraft:polished_deepslate",
        "floor_alt": "minecraft:deepslate_tiles",
        "wall": "minecraft:light_gray_concrete",
        "wall_alt": "minecraft:gray_concrete",
        "ceiling": "minecraft:smooth_stone",
        "accent": "minecraft:red_concrete",
        "lamp": "minecraft:sea_lantern",
    },
}


def _arms(directions: tuple[str, ...], half_width: int = 2) -> set[tuple[int, int]]:
    width, _, depth = SIZE
    cx, cz = (width - 1) // 2, (depth - 1) // 2
    cells = {
        (x, z)
        for x in range(cx - half_width, cx + half_width + 1)
        for z in range(cz - half_width, cz + half_width + 1)
    }
    if "west" in directions:
        cells.update((x, z) for x in range(0, cx + 1) for z in range(cz - half_width, cz + half_width + 1))
    if "east" in directions:
        cells.update((x, z) for x in range(cx, width) for z in range(cz - half_width, cz + half_width + 1))
    if "north" in directions:
        cells.update((x, z) for x in range(cx - half_width, cx + half_width + 1) for z in range(0, cz + 1))
    if "south" in directions:
        cells.update((x, z) for x in range(cx - half_width, cx + half_width + 1) for z in range(cz, depth))
    return cells


def _footprint(shape: Shape) -> set[tuple[int, int]]:
    if shape.room_margin is None:
        return _arms(shape.directions)
    width, _, depth = SIZE
    room = {
        (x, z)
        for x in range(shape.room_margin, width - shape.room_margin)
        for z in range(shape.room_margin, depth - shape.room_margin)
    }
    return room | _arms(shape.directions)


def _connector_position(direction: str, y: int) -> tuple[int, int, int]:
    width, _, depth = SIZE
    cx, cz = (width - 1) // 2, (depth - 1) // 2
    return {
        "west": (0, y, cz), "east": (width - 1, y, cz),
        "north": (cx, y, 0), "south": (cx, y, depth - 1),
    }[direction]


def _block(name: str):
    return name, (), None


def _build_nbt(shape_name: str, shape: Shape, skin: dict[str, str]) -> bytes:
    width, height, depth = SIZE
    footprint = _footprint(shape)
    blocks = {}
    air = _block("minecraft:air")
    for x, z in footprint:
        floor = skin["floor_alt"] if (x * 13 + z * 7) % 17 == 0 else skin["floor"]
        blocks[(x, 0, z)] = _block(floor)
        blocks[(x, height - 1, z)] = _block(skin["ceiling"])
        for y in range(1, height - 1):
            blocks[(x, y, z)] = air
        for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            wall = x + dx, z + dz
            if wall in footprint or not (0 <= wall[0] < width and 0 <= wall[1] < depth):
                continue
            for y in range(height):
                material = skin["accent"] if y == 3 else skin["wall_alt"] if (wall[0] + wall[1]) % 9 == 0 else skin["wall"]
                blocks[(wall[0], y, wall[1])] = _block(material)

    heights = dict(shape.connector_heights)
    for direction in shape.directions:
        y = heights.get(direction, 1)
        x, _, z = _connector_position(direction, y)
        # Keep a five-wide, three-high doorway clear at the shared contract port.
        for offset in range(-2, 3):
            for door_y in range(y, min(height - 1, y + 3)):
                door_x = x if direction in {"west", "east"} else x + offset
                door_z = z + offset if direction in {"west", "east"} else z
                if 0 <= door_x < width and 0 <= door_z < depth:
                    blocks[(door_x, door_y, door_z)] = air

    if shape_name in {"stairs_up", "stairs_down"}:
        cz = (depth - 1) // 2
        ascending = shape_name == "stairs_up"
        for x in range(width):
            level = round(4 * x / (width - 1))
            if not ascending:
                level = 4 - level
            for z in range(cz - 2, cz + 3):
                for y in range(1, level + 1):
                    blocks[(x, y, z)] = _block(skin["floor"])

    # Sparse lamps and a red center emblem make the first skin identifiable,
    # while leaving all gameplay marker positions as walkable air.
    for x, z in ((5, 5), (10, 5), (5, 10), (10, 10)):
        if (x, z) in footprint:
            blocks[(x, height - 1, z)] = _block(skin["lamp"])
    if shape.room_margin is not None:
        for x, z in ((7, 7), (8, 7), (7, 8), (8, 8)):
            blocks[(x, 0, z)] = _block(skin["accent"])
    return serialize_structure(SIZE, blocks)


def _definition(shape_name: str, shape: Shape, skin_name: str) -> dict[str, object]:
    heights = dict(shape.connector_heights)
    theme_tag = f"cobbleventure:dungeon_theme/{skin_name}"
    return {
        "$schema": "../../schemas/dungeon-piece.schema.json",
        "schema_version": 1,
        "piece_id": f"cobbleventure:dungeon_piece/{skin_name}/{shape_name}",
        "structure": f"cobbleventure:dungeon_pieces/{skin_name}/{shape_name}",
        "role": shape.role,
        "size": list(SIZE),
        "weight": shape.weight,
        "allow_rotation": True,
        "tags": [
            KIT_TAG,
            theme_tag,
            f"cobbleventure:dungeon_shape/{shape_name}",
            f"cobbleventure:dungeon_pool/{skin_name}_test",
        ],
        "connectors": [
            {
                "id": direction,
                "position": list(_connector_position(direction, heights.get(direction, 1))),
                "facing": direction,
                "socket": SOCKET,
                "tags": [KIT_TAG],
            }
            for direction in shape.directions
        ],
        "markers": [
            {
                **{"id": marker_id, "kind": kind, "position": list(position)},
                **({"reference": reference} if reference is not None else {}),
            }
            for marker_id, kind, position, reference in shape.markers
        ],
    }


def generate(root: Path = ROOT) -> list[Path]:
    project = root / PROJECT
    structure_root = project / "content/structures/dungeon_pieces"
    definition_root = project / "content/dungeon_pieces"
    written = []
    for skin_name, skin in SKINS.items():
        for shape_name, shape in SHAPES.items():
            nbt_path = structure_root / skin_name / f"{shape_name}.nbt"
            json_path = definition_root / skin_name / f"{shape_name}.json"
            nbt_path.parent.mkdir(parents=True, exist_ok=True)
            json_path.parent.mkdir(parents=True, exist_ok=True)
            nbt_path.write_bytes(_build_nbt(shape_name, shape, skin))
            json_path.write_text(
                json.dumps(_definition(shape_name, shape, skin_name), ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            written.extend((nbt_path, json_path))
    return written


if __name__ == "__main__":
    for generated in generate():
        print(generated.relative_to(ROOT).as_posix())

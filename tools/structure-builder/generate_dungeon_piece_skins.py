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
CORRIDOR_WIDTH = 6
CEILING_OFFSET = 7
SOCKET = "cobbleventure:dungeon_socket/standard_6"
KIT_TAG = "cobbleventure:dungeon_kit/standard_16"


@dataclass(frozen=True)
class Shape:
    role: str
    directions: tuple[str, ...]
    room_margin: int | None = None
    connector_heights: tuple[tuple[str, int], ...] = ()
    markers: tuple[tuple[str, str, tuple[int, int, int], str | None], ...] = ()
    weight: int = 10
    size: tuple[int, int, int] = SIZE
    placement_scope: str = "any"
    min_per_plan: int = 0
    max_per_plan: int = 256
    forbid_adjacent_tags: tuple[str, ...] = ()


SHAPES = {
    "start": Shape(
        "start", ("west", "east"), 3,
        markers=(("entry", "entry", (3, 1, 7), None),), weight=1,
    ),
    "corridor": Shape("corridor", ("west", "east"), weight=18),
    "corner": Shape("corridor", ("west", "south"), weight=10),
    "junction": Shape("junction", ("west", "east", "north", "south"), weight=7),
    "t_junction": Shape("junction", ("west", "east", "south"), weight=12),
    "room": Shape(
        "room", ("west", "east", "north", "south"), 3,
        markers=(
            ("npc_spawn_1", "npc_spawn", (5, 1, 6), None),
            ("npc_spawn_2", "npc_spawn", (10, 1, 6), None),
            ("npc_spawn_3", "npc_spawn", (5, 1, 10), None),
            ("npc_spawn_4", "npc_spawn", (10, 1, 10), None),
            ("encounter_slot_1", "encounter", (5, 1, 6), None),
            ("encounter_slot_2", "encounter", (10, 1, 6), None),
            ("encounter_slot_3", "encounter", (5, 1, 10), None),
            ("encounter_slot_4", "encounter", (10, 1, 10), None),
            ("loot_slot", "loot", (5, 1, 5), None),
            ("gate_slot", "gate", (15, 1, 7), None),
        ),
        weight=14,
    ),
    "route_room": Shape(
        "room", ("west", "east"), 3,
        markers=(
            ("npc_spawn_1", "npc_spawn", (5, 1, 6), None),
            ("npc_spawn_2", "npc_spawn", (10, 1, 6), None),
            ("npc_spawn_3", "npc_spawn", (5, 1, 10), None),
            ("npc_spawn_4", "npc_spawn", (10, 1, 10), None),
            ("encounter_slot_1", "encounter", (5, 1, 6), None),
            ("encounter_slot_2", "encounter", (10, 1, 6), None),
            ("encounter_slot_3", "encounter", (5, 1, 10), None),
            ("encounter_slot_4", "encounter", (10, 1, 10), None),
            ("loot_slot", "loot", (5, 1, 5), None),
            ("gate_slot", "gate", (15, 1, 7), None),
        ),
        weight=18,
    ),
    "encounter_room": Shape(
        "room", ("west", "east", "north", "south"), 3,
        markers=(
            ("npc_spawn_1", "npc_spawn", (5, 1, 6), None),
            ("npc_spawn_2", "npc_spawn", (10, 1, 6), None),
            ("npc_spawn_3", "npc_spawn", (5, 1, 10), None),
            ("npc_spawn_4", "npc_spawn", (10, 1, 10), None),
            ("encounter_slot_1", "encounter", (5, 1, 6), None),
            ("encounter_slot_2", "encounter", (10, 1, 6), None),
            ("encounter_slot_3", "encounter", (5, 1, 10), None),
            ("encounter_slot_4", "encounter", (10, 1, 10), None),
        ),
        weight=5,
    ),
    "stairs_up": Shape(
        "corridor", ("west", "east"), connector_heights=(("west", 1), ("east", 9)),
        weight=5, size=(16, 16, 16),
    ),
    "stairs_down": Shape(
        "corridor", ("west", "east"), connector_heights=(("west", 9), ("east", 1)),
        weight=5, size=(16, 16, 16),
    ),
    "dead_end": Shape("dead_end", ("west",), 4, weight=8),
    "support": Shape(
        "support", ("west", "east"), 3,
        markers=(("healing_station_slot", "healing_station", (8, 1, 8), None),),
        weight=5,
    ),
    "treasure": Shape(
        "treasure", ("north",), 3,
        markers=(
            ("loot_slot", "loot", (8, 1, 8), None),
            ("security_switch", "objective", (8, 1, 10), "security_switch"),
        ),
        weight=4, placement_scope="branch", min_per_plan=1, max_per_plan=1,
        forbid_adjacent_tags=("cobbleventure:dungeon_shape/boss",),
    ),
    "boss": Shape(
        "boss", ("west", "east"), 2,
        markers=(
            ("npc_spawn_1", "npc_spawn", (5, 1, 8), None),
            ("npc_spawn_2", "npc_spawn", (11, 1, 8), None),
            ("boss_slot", "boss", (8, 1, 8), None),
        ),
        weight=1, placement_scope="critical_path",
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


def _center_span(length: int, width: int = CORRIDOR_WIDTH) -> range:
    """Return a block span centred on the half-block axis of an even-sized piece."""
    if width <= 0 or width > length or (length - width) % 2:
        raise ValueError(f"Cannot centre width {width} in length {length}")
    start = (length - width) // 2
    return range(start, start + width)


def _arms(directions: tuple[str, ...], size: tuple[int, int, int] = SIZE) -> set[tuple[int, int]]:
    width, _, depth = size
    center_x = _center_span(width)
    center_z = _center_span(depth)
    cells = {
        (x, z)
        for x in center_x
        for z in center_z
    }
    if "west" in directions:
        cells.update((x, z) for x in range(0, center_x.stop) for z in center_z)
    if "east" in directions:
        cells.update((x, z) for x in range(center_x.start, width) for z in center_z)
    if "north" in directions:
        cells.update((x, z) for x in center_x for z in range(0, center_z.stop))
    if "south" in directions:
        cells.update((x, z) for x in center_x for z in range(center_z.start, depth))
    return cells


def _footprint(shape: Shape) -> set[tuple[int, int]]:
    if shape.room_margin is None:
        return _arms(shape.directions, size=shape.size)
    width, _, depth = shape.size
    room = {
        (x, z)
        for x in range(shape.room_margin, width - shape.room_margin)
        for z in range(shape.room_margin, depth - shape.room_margin)
    }
    return room | _arms(shape.directions, size=shape.size)


def _connector_position(direction: str, y: int, size: tuple[int, int, int] = SIZE) -> tuple[int, int, int]:
    width, _, depth = size
    cx, cz = (width - 1) // 2, (depth - 1) // 2
    return {
        "west": (0, y, cz), "east": (width - 1, y, cz),
        "north": (cx, y, 0), "south": (cx, y, depth - 1),
    }[direction]


def _block(name: str):
    return name, (), None


def _stair_level(
    shape_name: str, x: int, width: int, heights: dict[str, int]
) -> int:
    rise = abs(heights["east"] - heights["west"])
    level = round(rise * x / (width - 1))
    return level if shape_name == "stairs_up" else rise - level


def _build_nbt(shape_name: str, shape: Shape, skin: dict[str, str]) -> bytes:
    width, height, depth = shape.size
    footprint = _footprint(shape)
    heights = dict(shape.connector_heights)
    stairs = shape_name in {"stairs_up", "stairs_down"}
    blocks = {}
    air = _block("minecraft:air")
    for x, z in footprint:
        symmetric_x = min(x, width - 1 - x)
        symmetric_z = min(z, depth - 1 - z)
        floor_pattern = (symmetric_x + symmetric_z) * 7 + symmetric_x * symmetric_z * 3
        floor = skin["floor_alt"] if floor_pattern % 17 == 0 else skin["floor"]
        blocks[(x, 0, z)] = _block(floor)
        ceiling_y = _stair_level(shape_name, x, width, heights) + CEILING_OFFSET \
            if stairs else height - 1
        blocks[(x, ceiling_y, z)] = _block(skin["ceiling"])
        for y in range(1, ceiling_y):
            blocks[(x, y, z)] = air
        for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            wall = x + dx, z + dz
            if wall in footprint or not (0 <= wall[0] < width and 0 <= wall[1] < depth):
                continue
            wall_top = _stair_level(shape_name, wall[0], width, heights) + CEILING_OFFSET \
                if stairs else height - 1
            for y in range(wall_top + 1):
                wall_x = min(wall[0], width - 1 - wall[0])
                wall_z = min(wall[1], depth - 1 - wall[1])
                material = skin["accent"] if y == 3 else skin["wall_alt"] if (wall_x + wall_z) % 9 == 0 else skin["wall"]
                blocks[(wall[0], y, wall[1])] = _block(material)

    for direction in shape.directions:
        y = heights.get(direction, 1)
        x, _, z = _connector_position(direction, y, shape.size)
        # The even-width opening is centred on the 7.5 block axis of standard_16.
        lateral_span = _center_span(depth if direction in {"west", "east"} else width)
        for lateral in lateral_span:
            for door_y in range(y, min(height - 1, y + 3)):
                door_x = x if direction in {"west", "east"} else lateral
                door_z = lateral if direction in {"west", "east"} else z
                if 0 <= door_x < width and 0 <= door_z < depth:
                    blocks[(door_x, door_y, door_z)] = air

    if stairs:
        for x in range(width):
            level = _stair_level(shape_name, x, width, heights)
            for z in _center_span(depth):
                for y in range(1, level + 1):
                    blocks[(x, y, z)] = _block(skin["floor"])

    # Sparse lamps and a red center emblem make the first skin identifiable,
    # while leaving all gameplay marker positions as walkable air.
    for x, z in ((5, 5), (10, 5), (5, 10), (10, 10)):
        if (x, z) in footprint:
            ceiling_y = _stair_level(shape_name, x, width, heights) + CEILING_OFFSET \
                if stairs else height - 1
            blocks[(x, ceiling_y, z)] = _block(skin["lamp"])
    if shape.room_margin is not None:
        for x, z in ((7, 7), (8, 7), (7, 8), (8, 8)):
            blocks[(x, 0, z)] = _block(skin["accent"])
    return serialize_structure(shape.size, blocks)


def _definition(shape_name: str, shape: Shape, skin_name: str) -> dict[str, object]:
    heights = dict(shape.connector_heights)
    theme_tag = f"cobbleventure:dungeon_theme/{skin_name}"
    return {
        "$schema": "../../schemas/dungeon-piece.schema.json",
        "schema_version": 1,
        "piece_id": f"cobbleventure:dungeon_piece/{skin_name}/{shape_name}",
        "structure": f"cobbleventure:dungeon_pieces/{skin_name}/{shape_name}",
        "role": shape.role,
        "size": list(shape.size),
        "weight": shape.weight,
        **({"min_per_plan": shape.min_per_plan} if shape.min_per_plan else {}),
        **({"max_per_plan": shape.max_per_plan} if shape.max_per_plan != 256 else {}),
        **({"placement_scope": shape.placement_scope} if shape.placement_scope != "any" else {}),
        **({"forbid_adjacent_tags": list(shape.forbid_adjacent_tags)} if shape.forbid_adjacent_tags else {}),
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
                "position": list(_connector_position(direction, heights.get(direction, 1), shape.size)),
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
                **({"connector": "east"} if marker_id == "gate_slot" else {}),
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

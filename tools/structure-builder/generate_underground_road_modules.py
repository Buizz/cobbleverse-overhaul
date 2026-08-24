#!/usr/bin/env python3
"""Generate deterministic placeholder NBTs for the modular underground passage editor."""

from __future__ import annotations

import gzip
import struct
from pathlib import Path

TAG_END = 0
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "content-projects/cobbleventure-main/content/structures/underground_road_modules"


def _utf(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def _named(kind: int, name: str, payload: bytes) -> bytes:
    return bytes((kind,)) + _utf(name) + payload


def _int(value: int) -> bytes:
    return struct.pack(">i", value)


def _int_tag(name: str, value: int) -> bytes:
    return _named(TAG_INT, name, _int(value))


def _string(value: str) -> bytes:
    return _utf(value)


def _string_tag(name: str, value: str) -> bytes:
    return _named(TAG_STRING, name, _string(value))


def _list(child_kind: int, children) -> bytes:
    values = list(children)
    return bytes((child_kind,)) + struct.pack(">i", len(values)) + b"".join(values)


def _compound(entries) -> bytes:
    return b"".join(_named(kind, name, payload) for kind, name, payload in entries) + bytes((TAG_END,))


def _value_tag(name: str, value: object) -> tuple[int, str, bytes]:
    if isinstance(value, int):
        return TAG_INT, name, _int(value)
    if isinstance(value, str):
        return TAG_STRING, name, _string(value)
    raise TypeError(f"Unsupported NBT value: {name}={value!r}")


def _state(name: str, **properties: str) -> tuple[str, tuple[tuple[str, str], ...]]:
    return name, tuple(sorted(properties.items()))


def _state_payload(state: tuple[str, tuple[tuple[str, str], ...]]) -> bytes:
    name, properties = state
    entries: list[tuple[int, str, bytes]] = [(TAG_STRING, "Name", _string(name))]
    if properties:
        entries.append((TAG_COMPOUND, "Properties", _compound(
            (TAG_STRING, key, _string(value)) for key, value in properties
        )))
    return _compound(entries)


def marker(name: str, facing: str, final_state: str = "minecraft:air") -> tuple[str, tuple[tuple[str, str], ...], dict[str, object]]:
    orientation = f"{facing}_north" if facing in {"up", "down"} else f"{facing}_up"
    return (
        "minecraft:jigsaw",
        (("orientation", orientation),),
        {
            "id": "minecraft:jigsaw",
            "name": name,
            "target": "minecraft:empty",
            "pool": "minecraft:empty",
            "final_state": final_state,
            "joint": "aligned",
            "selection_priority": 0,
            "placement_priority": 0,
        },
    )


def _jigsaw(tag: str, facing: str) -> tuple[str, tuple[tuple[str, str], ...], dict[str, object]]:
    return marker(f"cobbleventure:underground_connector/{tag}", facing)


def _rough(kind: str, x: int, y: int, z: int) -> str:
    value = (x * 31 + y * 17 + z * 13) % 11
    if kind == "floor":
        return "minecraft:mossy_cobblestone" if value == 0 else "minecraft:cobbled_deepslate"
    if kind == "wall":
        return "minecraft:cracked_deepslate_bricks" if value in {0, 1} else "minecraft:deepslate_bricks"
    return "minecraft:cracked_stone_bricks" if value == 0 else "minecraft:stone_bricks"


def _arms(width: int, depth: int, directions: set[str], half_width: int = 2) -> set[tuple[int, int]]:
    cx, cz = (width - 1) // 2, (depth - 1) // 2
    cells = {(x, z) for x in range(cx - half_width, cx + half_width + 1) for z in range(cz - half_width, cz + half_width + 1)}
    if "west" in directions:
        cells.update((x, z) for x in range(0, cx + 1) for z in range(cz - half_width, cz + half_width + 1))
    if "east" in directions:
        cells.update((x, z) for x in range(cx, width) for z in range(cz - half_width, cz + half_width + 1))
    if "north" in directions:
        cells.update((x, z) for x in range(cx - half_width, cx + half_width + 1) for z in range(0, cz + 1))
    if "south" in directions:
        cells.update((x, z) for x in range(cx - half_width, cx + half_width + 1) for z in range(cz, depth))
    return cells


def _room(width: int, depth: int, directions: set[str], margin: int) -> set[tuple[int, int]]:
    cells = {(x, z) for x in range(margin, width - margin) for z in range(margin, depth - margin)}
    return cells | _arms(width, depth, directions)


def _connector_position(width: int, depth: int, facing: str, y: int = 1) -> tuple[int, int, int]:
    cx, cz = (width - 1) // 2, (depth - 1) // 2
    return {
        "west": (0, y, cz), "east": (width - 1, y, cz),
        "north": (cx, y, 0), "south": (cx, y, depth - 1),
    }[facing]


def serialize_structure(
    size: tuple[int, int, int],
    blocks: dict[tuple[int, int, int], tuple[str, tuple[tuple[str, str], ...], dict[str, object] | None]],
) -> bytes:
    palette: list[tuple[str, tuple[tuple[str, str], ...]]] = []
    indexes: dict[tuple[str, tuple[tuple[str, str], ...]], int] = {}
    for name, properties, _ in blocks.values():
        key = name, properties
        if key not in indexes:
            indexes[key] = len(palette); palette.append(key)
    block_payloads = []
    for position in sorted(blocks):
        name, properties, nbt = blocks[position]
        entries: list[tuple[int, str, bytes]] = [
            (TAG_LIST, "pos", _list(TAG_INT, (_int(value) for value in position))),
            (TAG_INT, "state", _int(indexes[(name, properties)])),
        ]
        if nbt is not None:
            entries.append((TAG_COMPOUND, "nbt", _compound(_value_tag(key, value) for key, value in nbt.items())))
        block_payloads.append(_compound(entries))
    root = _compound([
        (TAG_INT, "DataVersion", _int(3955)),
        (TAG_LIST, "size", _list(TAG_INT, (_int(value) for value in size))),
        (TAG_LIST, "palette", _list(TAG_COMPOUND, (_state_payload(state) for state in palette))),
        (TAG_LIST, "blocks", _list(TAG_COMPOUND, block_payloads)),
        (TAG_LIST, "entities", _list(TAG_COMPOUND, ())),
    ])
    return gzip.compress(_named(TAG_COMPOUND, "", root), mtime=0)


def _module(
    size: tuple[int, int, int],
    footprint: set[tuple[int, int]],
    connectors: dict[str, tuple[tuple[int, int, int], str]],
    *, stairs: str | None = None,
) -> bytes:
    width, height, depth = size
    blocks: dict[tuple[int, int, int], tuple[str, tuple[tuple[str, str], ...], dict[str, object] | None]] = {}
    air = ("minecraft:air", (), None)
    for x, z in footprint:
        blocks[(x, 0, z)] = (_rough("floor", x, 0, z), (), None)
        blocks[(x, height - 1, z)] = (_rough("ceiling", x, height - 1, z), (), None)
        for y in range(1, height - 1):
            blocks[(x, y, z)] = air
        for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            wall = x + dx, z + dz
            if wall in footprint or not (0 <= wall[0] < width and 0 <= wall[1] < depth):
                continue
            for y in range(height):
                blocks[(wall[0], y, wall[1])] = (_rough("wall", wall[0], y, wall[1]), (), None)
    if stairs:
        cx, cz = (width - 1) // 2, (depth - 1) // 2
        for step in range(2, width - 2):
            level = min(height - 4, 1 + (step - 2) * (height - 5) // max(1, width - 5))
            x = step if stairs == "up" else width - 1 - step
            for z in range(cz - 2, cz + 3):
                for y in range(1, level + 1):
                    blocks[(x, y, z)] = ("minecraft:stone_bricks", (), None)
    for tag, (position, facing) in connectors.items():
        name, properties, nbt = _jigsaw(tag, facing)
        blocks[position] = (name, properties, nbt)

    return serialize_structure(size, blocks)


def generate() -> list[Path]:
    definitions: dict[str, tuple[tuple[int, int, int], set[tuple[int, int]], dict[str, tuple[tuple[int, int, int], str]], str | None]] = {}

    def add(name: str, size: tuple[int, int, int], directions: set[str], *, room_margin: int | None = None, stairs: str | None = None, surface: bool = False, vertical_down: bool = False) -> None:
        width, height, depth = size
        footprint = _room(width, depth, directions, room_margin) if room_margin is not None else _arms(width, depth, directions)
        connectors = {direction: (_connector_position(width, depth, direction), direction) for direction in directions}
        if surface:
            connectors["surface"] = (((width - 1) // 2, height - 1, (depth - 1) // 2), "up")
        if vertical_down:
            connectors["vertical_down"] = (((width - 1) // 2, 0, (depth - 1) // 2), "down")
        definitions[name] = size, footprint, connectors, stairs

    add("straight_16", (16, 8, 16), {"west", "east"})
    add("straight_32", (32, 8, 16), {"west", "east"})
    add("corner_16", (16, 8, 16), {"west", "south"})
    add("t_junction", (16, 8, 16), {"west", "east", "south"})
    add("cross_junction", (16, 8, 16), {"west", "east", "north", "south"})
    add("dead_end", (16, 8, 16), {"west"}, room_margin=5)
    add("entrance_room", (16, 8, 16), {"west", "east"}, room_margin=3)
    add("small_room", (16, 8, 16), {"west", "east"}, room_margin=4)
    add("large_room", (32, 10, 32), {"west", "east", "north", "south"}, room_margin=5)
    add("stairs_up", (16, 12, 16), {"west"}, stairs="up", surface=True)
    add("stairs_down", (16, 12, 16), {"east"}, stairs="down", vertical_down=True)

    OUTPUT.mkdir(parents=True, exist_ok=True)
    written = []
    for name, (size, footprint, connectors, stairs) in definitions.items():
        target = OUTPUT / f"{name}.nbt"
        target.write_bytes(_module(size, footprint, connectors, stairs=stairs))
        written.append(target)
    return written


if __name__ == "__main__":
    for generated in generate():
        print(generated.relative_to(ROOT).as_posix())

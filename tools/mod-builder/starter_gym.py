from __future__ import annotations

import gzip
import struct
from collections.abc import Iterable


TAG_END = 0
TAG_BYTE = 1
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def _string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def _named(tag_type: int, name: str, payload: bytes) -> bytes:
    return bytes((tag_type,)) + _string(name) + payload


def _int(value: int) -> bytes:
    return struct.pack(">i", value)


def _compound(entries: Iterable[tuple[int, str, bytes]]) -> bytes:
    return b"".join(_named(tag_type, name, payload) for tag_type, name, payload in entries) + bytes((TAG_END,))


def _list(element_type: int, payloads: Iterable[bytes]) -> bytes:
    values = list(payloads)
    return bytes((element_type,)) + _int(len(values)) + b"".join(values)


def _string_tag(name: str, value: str) -> tuple[int, str, bytes]:
    return TAG_STRING, name, _string(value)


def _int_tag(name: str, value: int) -> tuple[int, str, bytes]:
    return TAG_INT, name, _int(value)


def _block_state_payload(name: str, properties: tuple[tuple[str, str], ...]) -> bytes:
    entries: list[tuple[int, str, bytes]] = [_string_tag("Name", name)]
    if properties:
        entries.append(
            (
                TAG_COMPOUND,
                "Properties",
                _compound(_string_tag(key, value) for key, value in properties),
            )
        )
    return _compound(entries)


def _jigsaw_nbt(orientation: str) -> dict[str, object]:
    return {
        "id": "minecraft:jigsaw",
        "name": "cobbleventure:starter_town_path",
        "target": "bca:paths",
        "pool": "bca:default/paths",
        "final_state": "minecraft:cobblestone",
        "joint": "rollable",
        "selection_priority": 0,
        "placement_priority": 0,
        "orientation": orientation,
    }


def build_starter_gym_nbt() -> bytes:
    """Create a deterministic vanilla structure NBT for the prototype gym."""
    width, height, depth = 31, 10, 25
    blocks: dict[tuple[int, int, int], tuple[str, tuple[tuple[str, str], ...], dict[str, object] | None]] = {}

    def set_block(
        x: int,
        y: int,
        z: int,
        name: str,
        properties: dict[str, str] | None = None,
        block_nbt: dict[str, object] | None = None,
    ) -> None:
        state_properties = tuple(sorted((properties or {}).items()))
        blocks[(x, y, z)] = (name, state_properties, block_nbt)

    def fill(x1: int, y1: int, z1: int, x2: int, y2: int, z2: int, name: str) -> None:
        for x in range(x1, x2 + 1):
            for y in range(y1, y2 + 1):
                for z in range(z1, z2 + 1):
                    set_block(x, y, z, name)

    # 건물 내부를 비우고 기초·벽·지붕을 만든다.
    fill(5, 1, 5, 25, 8, 20, "minecraft:air")
    fill(5, 0, 5, 25, 0, 20, "minecraft:polished_andesite")
    fill(5, 1, 5, 25, 6, 5, "minecraft:bricks")
    fill(5, 1, 20, 25, 6, 20, "minecraft:bricks")
    fill(5, 1, 6, 5, 6, 19, "minecraft:bricks")
    fill(25, 1, 6, 25, 6, 19, "minecraft:bricks")
    fill(5, 7, 5, 25, 7, 20, "minecraft:dark_prismarine")

    # 출입구와 측면 창문.
    fill(14, 1, 5, 16, 3, 5, "minecraft:air")
    for x in (8, 10, 20, 22):
        fill(x, 3, 5, x, 4, 5, "minecraft:glass")
        fill(x, 3, 20, x, 4, 20, "minecraft:glass")
    for z in (9, 12, 15, 18):
        fill(5, 3, z, 5, 4, z, "minecraft:glass")
        fill(25, 3, z, 25, 4, z, "minecraft:glass")

    # 실내 배틀 코트와 중앙선.
    fill(8, 0, 8, 22, 0, 12, "minecraft:red_concrete")
    fill(8, 0, 14, 22, 0, 18, "minecraft:blue_concrete")
    fill(8, 0, 13, 22, 0, 13, "minecraft:white_concrete")
    fill(14, 0, 8, 16, 0, 18, "minecraft:white_concrete")
    set_block(15, 0, 13, "minecraft:sea_lantern")

    # 관람석과 천장 조명.
    fill(6, 1, 8, 6, 2, 18, "minecraft:stone_bricks")
    fill(24, 1, 8, 24, 2, 18, "minecraft:stone_bricks")
    for x in (9, 15, 21):
        for z in (8, 13, 18):
            set_block(x, 6, z, "minecraft:sea_lantern")

    # 정면에 포켓볼 형태의 체육관 표식을 넣는다.
    fill(12, 4, 5, 18, 4, 5, "minecraft:white_concrete")
    fill(12, 6, 5, 18, 6, 5, "minecraft:red_concrete")
    fill(12, 5, 5, 18, 5, 5, "minecraft:black_concrete")
    set_block(15, 5, 5, "minecraft:sea_lantern")

    # 건물 주변 도로 고리와 네 방향 BCA 직소 연결점.
    fill(3, 0, 2, 27, 0, 4, "minecraft:cobblestone")
    fill(3, 0, 21, 27, 0, 23, "minecraft:cobblestone")
    fill(2, 0, 3, 4, 0, 22, "minecraft:cobblestone")
    fill(26, 0, 3, 28, 0, 22, "minecraft:cobblestone")
    fill(14, 0, 0, 16, 0, 5, "minecraft:cobblestone")
    fill(14, 0, 20, 16, 0, 24, "minecraft:cobblestone")
    fill(0, 0, 11, 5, 0, 13, "minecraft:cobblestone")
    fill(25, 0, 11, 30, 0, 13, "minecraft:cobblestone")

    connectors = (
        (15, 0, 0, "north_up"),
        (15, 0, 24, "south_up"),
        (0, 0, 12, "west_up"),
        (30, 0, 12, "east_up"),
    )
    for x, y, z, orientation in connectors:
        set_block(
            x,
            y,
            z,
            "minecraft:jigsaw",
            {"orientation": orientation},
            _jigsaw_nbt(orientation),
        )

    palette: list[tuple[str, tuple[tuple[str, str], ...]]] = []
    palette_indexes: dict[tuple[str, tuple[tuple[str, str], ...]], int] = {}
    for name, properties, _ in blocks.values():
        key = name, properties
        if key not in palette_indexes:
            palette_indexes[key] = len(palette)
            palette.append(key)

    block_payloads: list[bytes] = []
    for position in sorted(blocks):
        name, properties, block_nbt = blocks[position]
        entries: list[tuple[int, str, bytes]] = [
            (TAG_LIST, "pos", _list(TAG_INT, (_int(value) for value in position))),
            _int_tag("state", palette_indexes[(name, properties)]),
        ]
        if block_nbt is not None:
            nbt_entries: list[tuple[int, str, bytes]] = []
            for key, value in block_nbt.items():
                if key == "orientation":
                    continue
                if isinstance(value, int):
                    nbt_entries.append(_int_tag(key, value))
                else:
                    nbt_entries.append(_string_tag(key, str(value)))
            entries.append((TAG_COMPOUND, "nbt", _compound(nbt_entries)))
        block_payloads.append(_compound(entries))

    root_payload = _compound(
        [
            _int_tag("DataVersion", 3955),
            (TAG_LIST, "size", _list(TAG_INT, (_int(value) for value in (width, height, depth)))),
            (
                TAG_LIST,
                "palette",
                _list(TAG_COMPOUND, (_block_state_payload(name, properties) for name, properties in palette)),
            ),
            (TAG_LIST, "blocks", _list(TAG_COMPOUND, block_payloads)),
            (TAG_LIST, "entities", _list(TAG_COMPOUND, ())),
        ]
    )
    uncompressed = _named(TAG_COMPOUND, "", root_payload)
    return gzip.compress(uncompressed, mtime=0)

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

GYM_ROOF_BLOCKS = {
    "normal": "minecraft:white_concrete",
    "fire": "minecraft:red_concrete",
    "water": "minecraft:blue_concrete",
    "electric": "minecraft:yellow_concrete",
    "grass": "minecraft:green_concrete",
    "ice": "minecraft:light_blue_concrete",
    "fighting": "minecraft:orange_concrete",
    "poison": "minecraft:purple_concrete",
    "ground": "minecraft:brown_concrete",
    "flying": "minecraft:cyan_concrete",
    "psychic": "minecraft:magenta_concrete",
    "bug": "minecraft:lime_concrete",
    "rock": "minecraft:gray_concrete",
    "ghost": "minecraft:purple_concrete",
    "dragon": "minecraft:blue_concrete",
    "dark": "minecraft:black_concrete",
    "steel": "minecraft:light_gray_concrete",
    "fairy": "minecraft:pink_concrete",
}


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


def build_starter_gym_nbt(theme: str = "rock") -> bytes:
    """Create a compact gym shell whose roof colour follows a Pokémon type."""
    try:
        roof_block = GYM_ROOF_BLOCKS[theme]
    except KeyError as error:
        raise ValueError(f"지원하지 않는 체육관 테마입니다: {theme}") from error

    width, height, depth = 25, 9, 19
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

    # 공통 외관: 작은 로비만 가진 체육관 껍데기다.
    fill(5, 1, 4, 19, 7, 14, "minecraft:air")
    fill(5, 0, 4, 19, 0, 14, "minecraft:polished_andesite")
    fill(5, 1, 4, 19, 6, 4, "minecraft:stone_bricks")
    fill(5, 1, 14, 19, 6, 14, "minecraft:stone_bricks")
    fill(5, 1, 5, 5, 6, 13, "minecraft:stone_bricks")
    fill(19, 1, 5, 19, 6, 13, "minecraft:stone_bricks")

    # 지붕만 타입 테마 색으로 바꾼다. 한 블록 돌출시켜 외관을 읽기 쉽게 한다.
    fill(4, 7, 3, 20, 7, 15, roof_block)
    fill(5, 8, 4, 19, 8, 14, roof_block)

    # 정면 출입구와 공통 창문.
    fill(11, 1, 4, 13, 3, 4, "minecraft:air")
    for x in (7, 9, 15, 17):
        fill(x, 3, 4, x, 4, 4, "minecraft:glass")
        fill(x, 3, 14, x, 4, 14, "minecraft:glass")
    for z in (7, 10, 12):
        fill(5, 3, z, 5, 4, z, "minecraft:glass")
        fill(19, 3, z, 19, 4, z, "minecraft:glass")

    # 입구에서 향후 실내 인스턴스로 연결될 작은 로비와 테마 카펫.
    fill(8, 0, 6, 16, 0, 12, "minecraft:smooth_stone")
    fill(11, 0, 4, 13, 0, 10, roof_block)
    set_block(12, 0, 10, "minecraft:sea_lantern")

    for x in (8, 12, 16):
        set_block(x, 6, 9, "minecraft:sea_lantern")

    # 정면 포켓볼 표식은 모든 체육관이 공유한다.
    fill(9, 4, 4, 15, 4, 4, "minecraft:white_concrete")
    fill(9, 6, 4, 15, 6, 4, "minecraft:red_concrete")
    fill(9, 5, 4, 15, 5, 4, "minecraft:black_concrete")
    set_block(12, 5, 4, "minecraft:sea_lantern")

    # 건물 주변 도로와 네 방향 BCA 직소 연결점.
    fill(3, 0, 1, 21, 0, 3, "minecraft:cobblestone")
    fill(3, 0, 15, 21, 0, 17, "minecraft:cobblestone")
    fill(1, 0, 2, 3, 0, 16, "minecraft:cobblestone")
    fill(21, 0, 2, 23, 0, 16, "minecraft:cobblestone")
    fill(11, 0, 0, 13, 0, 4, "minecraft:cobblestone")
    fill(11, 0, 14, 13, 0, 18, "minecraft:cobblestone")
    fill(0, 0, 8, 5, 0, 10, "minecraft:cobblestone")
    fill(19, 0, 8, 24, 0, 10, "minecraft:cobblestone")

    connectors = (
        (12, 0, 0, "north_up"),
        (12, 0, 18, "south_up"),
        (0, 0, 9, "west_up"),
        (24, 0, 9, "east_up"),
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

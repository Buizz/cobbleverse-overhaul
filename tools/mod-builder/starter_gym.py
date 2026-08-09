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

BCA_VILLAGE_START_POOLS = {
    "default_small": ("bca:default/small", 2),
    "default_mid": ("bca:default/mid", 3),
    "default_large": ("bca:default/large", 4),
    "fighting_small": ("bca:fighting/small", 4),
    "fighting_mid": ("bca:fighting/mid", 4),
    "fighting_large": ("bca:fighting/large", 6),
    "dark_small": ("bca:dark/small", 2),
    "dark_mid": ("bca:dark/mid", 3),
    "ice_small": ("bca:ice/small", 4),
    "ice_mid": ("bca:ice/mid", 4),
    "ice_large": ("bca:ice/large", 4),
}

# The generated civic hub is retained for backwards-compatible data packs.
# New settlement structures start from BCA's original pools so their town
# centres (Pokecenter, Pokemart and the large department store) are not skipped.
BCA_VILLAGE_PRESETS = {
    **{name: "bca:default/paths" for name in ("default_small", "default_mid", "default_large")},
    **{name: "bca:fighting/paths" for name in ("fighting_small", "fighting_mid", "fighting_large")},
    **{name: "bca:dark/paths" for name in ("dark_small", "dark_mid")},
    **{name: "bca:ice/paths" for name in ("ice_small", "ice_mid", "ice_large")},
    # Legacy aliases are accepted while old user-authored settlement files are migrated.
    "default": "bca:default/paths",
    "fighting": "bca:fighting/paths",
    "dark": "bca:dark/paths",
    "ice": "bca:ice/paths",
    # Authored starter towns use a fixed Cobbleventure centre while retaining
    # BCA's path and housing pieces around that centre.
    "cobbleventure_starter": "bca:default/paths",
}

BCA_VILLAGE_CONNECTORS = {
    "default": ("bca:paths", "bca:path_straight-curved"),
    "fighting": ("bca:fighting/paths", "bca:path_straight-curved_fighting"),
    "dark": ("bca:paths_dark", "bca:path_straight-curved_dark"),
    "ice": ("bca:paths_ice", "bca:path_straight-curved_ice"),
    "cobbleventure_starter": ("bca:paths", "bca:path_straight-curved"),
}

for _variant in ("default_small", "default_mid", "default_large"):
    BCA_VILLAGE_CONNECTORS[_variant] = BCA_VILLAGE_CONNECTORS["default"]
for _variant in ("fighting_small", "fighting_mid", "fighting_large"):
    BCA_VILLAGE_CONNECTORS[_variant] = BCA_VILLAGE_CONNECTORS["fighting"]
for _variant in ("dark_small", "dark_mid"):
    BCA_VILLAGE_CONNECTORS[_variant] = BCA_VILLAGE_CONNECTORS["dark"]
for _variant in ("ice_small", "ice_mid", "ice_large"):
    BCA_VILLAGE_CONNECTORS[_variant] = BCA_VILLAGE_CONNECTORS["ice"]


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


def _jigsaw_nbt(
    orientation: str,
    path_pool: str,
    connector_name: str,
    connector_target: str,
) -> dict[str, object]:
    return {
        "id": "minecraft:jigsaw",
        "name": connector_name,
        "target": connector_target,
        "pool": path_pool,
        "final_state": "minecraft:cobblestone",
        "joint": "rollable",
        "selection_priority": 0,
        "placement_priority": 0,
        "orientation": orientation,
    }


def build_village_hub_nbt(village_preset: str = "default") -> bytes:
    """Create a BCA road grid that reserves its center for an RGS gym."""
    try:
        path_pool = BCA_VILLAGE_PRESETS[village_preset]
        connector_name, connector_target = BCA_VILLAGE_CONNECTORS[village_preset]
    except KeyError as error:
        raise ValueError(f"지원하지 않는 BCA 마을 프리셋입니다: {village_preset}") from error

    # All selected RGS gyms currently use a 25x26 footprint.  The larger start
    # piece claims the whole civic block during Jigsaw assembly, preventing BCA
    # houses and decorations from occupying the future gym plot.
    width, height, depth = 49, 1, 50
    gym_origin_x, gym_origin_z = 12, 12
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

    # A single, consistent road material is used here.  BCA path pieces begin
    # at the four connectors, so no biome road is painted over the assembled
    # village afterward.
    ring_min_x, ring_max_x = 7, 41
    ring_min_z, ring_max_z = 7, 42
    ring_width = 3
    for x in range(ring_min_x, ring_max_x + 1):
        for z in range(ring_min_z, ring_max_z + 1):
            if (
                x < ring_min_x + ring_width
                or x > ring_max_x - ring_width
                or z < ring_min_z + ring_width
                or z > ring_max_z - ring_width
            ):
                set_block(x, 0, z, "minecraft:cobblestone")

    center_x, center_z = width // 2, depth // 2
    fill(center_x - 1, 0, 0, center_x + 1, 0, ring_min_z, "minecraft:cobblestone")
    fill(center_x - 1, 0, ring_max_z, center_x + 1, 0, depth - 1, "minecraft:cobblestone")
    fill(0, 0, center_z - 1, ring_min_x, 0, center_z + 1, "minecraft:cobblestone")
    fill(ring_max_x, 0, center_z - 1, width - 1, 0, center_z + 1, "minecraft:cobblestone")

    # Runtime replaces this invisible marker with the selected RGS gym.  It
    # also lets the placement code recover the rotated Jigsaw plot origin.
    set_block(gym_origin_x, 0, gym_origin_z, "minecraft:barrier")

    connectors = (
        (center_x, 0, 0, "north_up"),
        (center_x, 0, depth - 1, "south_up"),
        (0, 0, center_z, "west_up"),
        (width - 1, 0, center_z, "east_up"),
    )
    for x, y, z, orientation in connectors:
        set_block(
            x,
            y,
            z,
            "minecraft:jigsaw",
            {"orientation": orientation},
            _jigsaw_nbt(
                orientation, path_pool, connector_name, connector_target
            ),
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


def build_starter_gym_nbt(theme: str = "rock", village_preset: str = "default") -> bytes:
    """Compatibility wrapper retained for callers while gym shells are retired."""
    if theme not in GYM_ROOF_BLOCKS:
        raise ValueError(f"지원하지 않는 체육관 테마입니다: {theme}")
    return build_village_hub_nbt(village_preset)

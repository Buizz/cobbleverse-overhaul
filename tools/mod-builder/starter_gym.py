from __future__ import annotations

import gzip
import json
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

HOUSE_BASES = {
    "one_story": {"size": (16, 6, 16), "stories": 1, "wall": "minecraft:oak_planks", "trim": "minecraft:stripped_oak_log"},
    "two_story": {"size": (16, 11, 16), "stories": 2, "wall": "minecraft:stone_bricks", "trim": "minecraft:stripped_dark_oak_log"},
    "five_story": {"size": (16, 26, 16), "stories": 5, "wall": "minecraft:smooth_quartz", "trim": "minecraft:polished_deepslate"},
}
HOUSE_ROOFS = {"gable", "gambrel", "shed", "flat"}
HOUSE_ROOF_STONE_BLOCKS = {
    "red": "minecraft:granite",
    "orange": "minecraft:granite",
    "yellow": "minecraft:cobblestone",
    "green": "minecraft:cobblestone",
    "blue": "minecraft:cobblestone",
    "purple": "minecraft:cobbled_deepslate",
    "brown": "minecraft:granite",
    "gray": "minecraft:cobbled_deepslate",
    "black": "minecraft:cobbled_deepslate",
    "white": "minecraft:cobblestone",
}
HOUSE_ROOF_PALETTES = {
    color: {
        "concrete": f"minecraft:{color}_concrete",
        "wool": f"minecraft:{color}_wool",
        "stone": stone,
    }
    for color, stone in HOUSE_ROOF_STONE_BLOCKS.items()
}
# Existing settlement config only needs the color keys. Keep this alias so the
# config compiler does not need to know how many materials make up each roof.
HOUSE_ROOF_BLOCKS = {
    color: palette["concrete"] for color, palette in HOUSE_ROOF_PALETTES.items()
}
HOUSE_ROOF_TEMPLATE_COLOR = "white"

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

FACILITY_PLACEHOLDERS = {
    "basic_building_1": {"label": "1층 주택", "size": (16, 13, 16), "frame": "minecraft:bricks"},
    "basic_building_2": {"label": "2층 주택", "size": (16, 18, 16), "frame": "minecraft:stone_bricks"},
    "basic_building_3": {"label": "5층 고층주택", "size": (16, 33, 16), "frame": "minecraft:white_concrete"},
    "player_house": {"label": "플레이어 집", "size": (16, 13, 16), "frame": "minecraft:oak_planks"},
    "laboratory": {"label": "연구소", "size": (32, 14, 32), "frame": "minecraft:light_blue_concrete"},
    "fossil_laboratory": {"label": "화석연구소", "size": (32, 14, 32), "frame": "minecraft:brown_concrete"},
    "daycare": {"label": "키우미집", "size": (32, 10, 32), "frame": "minecraft:lime_concrete"},
    "tm_workshop": {"label": "기술머신 조합소", "size": (32, 10, 16), "frame": "minecraft:orange_concrete"},
    "hotel": {"label": "호텔", "size": (32, 20, 32), "frame": "minecraft:pink_concrete"},
    "casino": {"label": "카지노", "size": (48, 20, 48), "frame": "minecraft:yellow_concrete"},
    "battle_tower": {"label": "배틀타워", "size": (48, 32, 48), "frame": "minecraft:purple_concrete"},
    "radio_tower": {"label": "라디오 타워", "size": (48, 32, 48), "frame": "minecraft:blue_concrete"},
    "train_station": {"label": "기차역", "size": (48, 14, 64), "frame": "minecraft:gray_concrete"},
    "lighthouse": {"label": "등대", "size": (32, 48, 32), "frame": "minecraft:white_concrete"},
    "power_plant": {"label": "파워플랜트", "size": (48, 24, 48), "frame": "minecraft:light_gray_concrete"},
    "mansion": {"label": "멘션", "size": (48, 24, 48), "frame": "minecraft:dark_oak_planks"},
    "gym_site": {"label": "체육관 부지", "size": (64, 12, 64), "frame": "minecraft:red_concrete"},
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


def _byte(value: int | bool) -> bytes:
    return struct.pack(">b", int(value))


def _compound(entries: Iterable[tuple[int, str, bytes]]) -> bytes:
    return b"".join(_named(tag_type, name, payload) for tag_type, name, payload in entries) + bytes((TAG_END,))


def _list(element_type: int, payloads: Iterable[bytes]) -> bytes:
    values = list(payloads)
    return bytes((element_type,)) + _int(len(values)) + b"".join(values)


def _string_tag(name: str, value: str) -> tuple[int, str, bytes]:
    return TAG_STRING, name, _string(value)


def _int_tag(name: str, value: int) -> tuple[int, str, bytes]:
    return TAG_INT, name, _int(value)


def _value_tag(name: str, value: object) -> tuple[int, str, bytes]:
    if isinstance(value, bool):
        return TAG_BYTE, name, _byte(value)
    if isinstance(value, int):
        return TAG_INT, name, _int(value)
    if isinstance(value, str):
        return TAG_STRING, name, _string(value)
    if isinstance(value, dict):
        return TAG_COMPOUND, name, _compound(
            _value_tag(str(key), child) for key, child in value.items()
        )
    if isinstance(value, list) and all(isinstance(child, str) for child in value):
        return TAG_LIST, name, _list(TAG_STRING, (_string(child) for child in value))
    raise TypeError(f"지원하지 않는 NBT 값입니다: {name}={value!r}")


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
    }


def _build_structure_nbt(
    size: tuple[int, int, int],
    blocks: dict[
        tuple[int, int, int],
        tuple[str, tuple[tuple[str, str], ...], dict[str, object] | None],
    ],
) -> bytes:
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
            entries.append(
                (TAG_COMPOUND, "nbt", _compound(
                    _value_tag(key, value) for key, value in block_nbt.items()
                ))
            )
        block_payloads.append(_compound(entries))

    root_payload = _compound(
        [
            _int_tag("DataVersion", 3955),
            (TAG_LIST, "size", _list(TAG_INT, (_int(value) for value in size))),
            (
                TAG_LIST,
                "palette",
                _list(TAG_COMPOUND, (_block_state_payload(name, properties) for name, properties in palette)),
            ),
            (TAG_LIST, "blocks", _list(TAG_COMPOUND, block_payloads)),
            (TAG_LIST, "entities", _list(TAG_COMPOUND, ())),
        ]
    )
    return gzip.compress(_named(TAG_COMPOUND, "", root_payload), mtime=0)


def build_facility_placeholder_nbt(facility_id: str) -> bytes:
    """Create a replaceable facility-sized shell with a baked-in name sign."""
    try:
        definition = FACILITY_PLACEHOLDERS[facility_id]
    except KeyError as error:
        raise ValueError(f"지원하지 않는 시설 플레이스홀더입니다: {facility_id}") from error
    label = str(definition["label"])
    width, height, depth = definition["size"]  # type: ignore[misc]
    frame = str(definition["frame"])
    blocks: dict[
        tuple[int, int, int],
        tuple[str, tuple[tuple[str, str], ...], dict[str, object] | None],
    ] = {}

    def set_block(
        x: int,
        y: int,
        z: int,
        name: str,
        properties: dict[str, str] | None = None,
        block_nbt: dict[str, object] | None = None,
    ) -> None:
        blocks[(x, y, z)] = (name, tuple(sorted((properties or {}).items())), block_nbt)

    doorway_center = width // 2
    for x in range(width):
        for z in range(depth):
            set_block(x, 0, z, "minecraft:smooth_stone")
            if x not in {0, width - 1} and z not in {0, depth - 1}:
                continue
            corner = x in {0, width - 1} and z in {0, depth - 1}
            for y in range(1, height):
                if z == 0 and abs(x - doorway_center) <= 1 and y <= 3:
                    continue
                set_block(
                    x, y, z,
                    frame if corner or y in {1, height - 1} else "minecraft:white_stained_glass",
                )

    sign_x = min(width - 2, doorway_center + 4)
    messages = [
        json.dumps({"text": label}, ensure_ascii=False, separators=(",", ":")),
        json.dumps({"text": "PLACEHOLDER"}, separators=(",", ":")),
        json.dumps({"text": f"{width} x {depth}"}, separators=(",", ":")),
        json.dumps({"text": facility_id}, separators=(",", ":")),
    ]
    sign_text = {"has_glowing_text": True, "color": "black", "messages": messages}
    set_block(
        sign_x, 2, 0, "minecraft:oak_wall_sign",
        {"facing": "north", "waterlogged": "false"},
        {
            "id": "minecraft:sign",
            "front_text": sign_text,
            "back_text": {"has_glowing_text": False, "color": "black", "messages": messages},
            "is_waxed": True,
        },
    )
    return _build_structure_nbt((width, height, depth), blocks)


TOWN_DECORATION_SIZES = {
    "street_lamp": (3, 6, 3),
    "bench": (5, 3, 3),
    "street_tree": (5, 8, 5),
    "flower_bed": (5, 2, 5),
    "fountain": (7, 5, 7),
}


def build_town_decoration_nbt(decoration_id: str) -> bytes:
    """Build a compact town decoration whose origin is its north-west corner."""
    try:
        width, height, depth = TOWN_DECORATION_SIZES[decoration_id]
    except KeyError as error:
        raise ValueError(f"지원하지 않는 마을 장식입니다: {decoration_id}") from error
    blocks: dict[
        tuple[int, int, int],
        tuple[str, tuple[tuple[str, str], ...], dict[str, object] | None],
    ] = {}

    def set_block(
        x: int, y: int, z: int, name: str,
        properties: dict[str, str] | None = None,
    ) -> None:
        blocks[(x, y, z)] = (name, tuple(sorted((properties or {}).items())), None)

    if decoration_id == "street_lamp":
        set_block(1, 0, 1, "minecraft:stone_brick_wall")
        for y in range(1, 5):
            set_block(1, y, 1, "minecraft:dark_oak_fence")
        set_block(1, 5, 1, "minecraft:lantern", {"hanging": "false", "waterlogged": "false"})
    elif decoration_id == "bench":
        for x in range(1, 4):
            set_block(x, 0, 1, "minecraft:brick_slab", {"type": "bottom", "waterlogged": "false"})
            set_block(x, 1, 2, "minecraft:dark_oak_trapdoor", {
                "facing": "south", "half": "bottom", "open": "true", "powered": "false", "waterlogged": "false",
            })
        set_block(0, 0, 1, "minecraft:stone_brick_wall")
        set_block(4, 0, 1, "minecraft:stone_brick_wall")
    elif decoration_id == "street_tree":
        for x in range(5):
            for z in range(5):
                edge = x in {0, 4} or z in {0, 4}
                set_block(x, 0, z, "minecraft:brick_slab" if edge else "minecraft:dirt", {
                    "type": "bottom", "waterlogged": "false",
                } if edge else None)
        for y in range(1, 6):
            set_block(2, y, 2, "minecraft:oak_log", {"axis": "y"})
        for y, radius in ((4, 1), (5, 2), (6, 2), (7, 1)):
            for x in range(2 - radius, 3 + radius):
                for z in range(2 - radius, 3 + radius):
                    if (x, z) != (2, 2) or y > 5:
                        set_block(x, y, z, "minecraft:oak_leaves", {
                            "distance": "1", "persistent": "true", "waterlogged": "false",
                        })
    elif decoration_id == "flower_bed":
        flowers = ("minecraft:poppy", "minecraft:dandelion", "minecraft:azure_bluet", "minecraft:allium")
        for x in range(5):
            for z in range(5):
                edge = x in {0, 4} or z in {0, 4}
                set_block(x, 0, z, "minecraft:brick_slab" if edge else "minecraft:dirt", {
                    "type": "bottom", "waterlogged": "false",
                } if edge else None)
                if not edge and (x + z) % 2 == 0:
                    set_block(x, 1, z, flowers[(x * 3 + z) % len(flowers)])
    else:
        for x in range(7):
            for z in range(7):
                edge = x in {0, 6} or z in {0, 6}
                if edge:
                    set_block(x, 0, z, "minecraft:stone_bricks")
                elif x in {1, 5} or z in {1, 5}:
                    set_block(x, 0, z, "minecraft:water", {"level": "0"})
        for y in range(4):
            set_block(3, y, 3, "minecraft:chiseled_stone_bricks")
        set_block(3, 4, 3, "minecraft:water", {"level": "0"})
        for x, z in ((2, 3), (4, 3), (3, 2), (3, 4)):
            set_block(x, 1, z, "minecraft:sea_lantern")
    return _build_structure_nbt((width, height, depth), blocks)


def build_house_variant_nbt(base_id: str, roof_id: str, roof_color: str) -> bytes:
    """Build one deterministic house shell from a base, roof shape and roof palette."""
    if base_id not in HOUSE_BASES:
        raise ValueError(f"지원하지 않는 주택 골격입니다: {base_id}")
    if roof_id not in HOUSE_ROOFS:
        raise ValueError(f"지원하지 않는 지붕 형태입니다: {roof_id}")
    if roof_color not in HOUSE_ROOF_BLOCKS:
        raise ValueError(f"지원하지 않는 지붕 색상입니다: {roof_color}")
    definition = HOUSE_BASES[base_id]
    width, wall_height, depth = definition["size"]  # type: ignore[misc]
    wall = str(definition["wall"])
    trim = str(definition["trim"])
    stories = int(definition["stories"])
    roof_palette = HOUSE_ROOF_PALETTES[roof_color]
    roof_layers = 1 if roof_id == "flat" else min(6, depth // 2)
    total_height = wall_height + roof_layers + 1
    blocks: dict[
        tuple[int, int, int],
        tuple[str, tuple[tuple[str, str], ...], dict[str, object] | None],
    ] = {}

    def set_block(x: int, y: int, z: int, name: str) -> None:
        blocks[(x, y, z)] = (name, (), None)

    def set_roof_block(x: int, y: int, z: int) -> None:
        selector = (x * 31 + y * 13 + z * 17) % 10
        material = "stone" if selector == 0 else ("wool" if selector <= 2 else "concrete")
        set_block(x, y, z, roof_palette[material])

    for x in range(width):
        for z in range(depth):
            set_block(x, 0, z, "minecraft:smooth_stone")
    door_x = width // 2
    for y in range(1, wall_height):
        for x in range(width):
            for z in (0, depth - 1):
                if z == 0 and abs(x - door_x) <= 1 and y <= 3:
                    continue
                window = any(
                    y in {story * 5 + 3, story * 5 + 4}
                    for story in range(stories)
                ) and x % 5 in {2, 3}
                set_block(x, y, z, "minecraft:glass_pane" if window else (trim if x in {0, width - 1} else wall))
        for z in range(1, depth - 1):
            for x in (0, width - 1):
                window = any(
                    y in {story * 5 + 3, story * 5 + 4}
                    for story in range(stories)
                ) and z % 5 in {2, 3}
                set_block(x, y, z, "minecraft:glass_pane" if window else (trim if z in {1, depth - 2} else wall))
    for story in range(1, stories):
        floor_y = story * 5 + 1
        for x in range(1, width - 1):
            for z in range(1, depth - 1):
                set_block(x, floor_y, z, "minecraft:oak_planks")

    roof_y = wall_height
    if roof_id == "flat":
        for x in range(width):
            for z in range(depth):
                set_roof_block(x, roof_y, z)
        for x in range(width):
            for z in (0, depth - 1):
                set_roof_block(x, roof_y + 1, z)
        for z in range(1, depth - 1):
            for x in (0, width - 1):
                set_roof_block(x, roof_y + 1, z)
    elif roof_id == "gable":
        for layer in range(roof_layers):
            left = layer
            right = depth - 1 - layer
            if left > right:
                break
            for x in range(width):
                set_roof_block(x, roof_y + layer, left)
                set_roof_block(x, roof_y + layer, right)
    elif roof_id == "gambrel":
        lower_slope_layers = roof_layers // 2
        for layer in range(roof_layers):
            if layer <= lower_slope_layers:
                inset = layer
            else:
                inset = lower_slope_layers + (layer - lower_slope_layers) * 2
            left = min(depth // 2 - 1, inset)
            right = depth - 1 - left
            for x in range(width):
                set_roof_block(x, roof_y + layer, left)
                set_roof_block(x, roof_y + layer, right)
    else:  # shed
        for z in range(depth):
            layer = min(roof_layers - 1, z * roof_layers // depth)
            for x in range(width):
                set_roof_block(x, roof_y + layer, z)
    return _build_structure_nbt((width, total_height, depth), blocks)


def recolor_house_roof_nbt(structure_nbt: bytes, roof_color: str) -> bytes:
    """Replace the template roof palette entry while preserving the authored structure."""
    if roof_color not in HOUSE_ROOF_BLOCKS:
        raise ValueError(f"지원하지 않는 지붕 색상입니다: {roof_color}")
    try:
        decompressed = gzip.decompress(structure_nbt)
    except (EOFError, OSError) as error:
        raise ValueError("주택 원본이 GZip 구조물 파일이 아닙니다.") from error
    if not decompressed or decompressed[0] != TAG_COMPOUND:
        raise ValueError("주택 원본의 루트가 TAG_Compound가 아닙니다.")

    template_palette = HOUSE_ROOF_PALETTES[HOUSE_ROOF_TEMPLATE_COLOR]
    target_palette = HOUSE_ROOF_PALETTES[roof_color]
    recolored = decompressed
    for material in ("concrete", "wool", "stone"):
        template_block = template_palette[material]
        target_block = target_palette[material]
        template_palette_entry = (
            bytes((TAG_STRING,)) + _string("Name") + _string(template_block)
        )
        if template_palette_entry not in recolored and material == "concrete":
            raise ValueError(
                f"주택 원본 팔레트에 {material} 지붕 표식 블록이 없습니다: "
                f"{template_block}"
            )
        if template_palette_entry not in recolored:
            continue
        target_palette_entry = (
            bytes((TAG_STRING,)) + _string("Name") + _string(target_block)
        )
        recolored = recolored.replace(template_palette_entry, target_palette_entry)
    if target_palette == template_palette:
        return structure_nbt
    return gzip.compress(recolored, mtime=0)


ROAD_MATERIAL_BLOCKS = {
    "cobblestone": "minecraft:cobblestone",
    "stone_bricks": "minecraft:stone_bricks",
    "bricks": "minecraft:bricks",
    "grass_path": "minecraft:dirt_path",
    "gravel": "minecraft:gravel",
    "packed_mud": "minecraft:packed_mud",
    "sandstone": "minecraft:sandstone",
    "snow": "minecraft:polished_diorite",
}


def build_village_hub_nbt(
    village_preset: str = "default",
    layout_shape: str = "branching",
    road_width: int = 7,
    road_material: str = "cobblestone",
) -> bytes:
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

    if layout_shape not in {"branching", "linear", "radial", "loop", "terraced"}:
        raise ValueError(f"지원하지 않는 마을 도로 형태입니다: {layout_shape}")
    if road_width not in {3, 5, 7, 9}:
        raise ValueError(f"지원하지 않는 도로 폭입니다: {road_width}")
    try:
        road_block = ROAD_MATERIAL_BLOCKS[road_material]
    except KeyError as error:
        raise ValueError(f"지원하지 않는 도로 노면입니다: {road_material}") from error

    ring_min_x, ring_max_x = 7, 41
    ring_min_z, ring_max_z = 7, 42
    center_x, center_z = width // 2, depth // 2
    half_width = road_width // 2

    def road_rect(x1: int, z1: int, x2: int, z2: int) -> None:
        fill(x1, 0, z1, x2, 0, z2, road_block)

    connectors: list[tuple[int, int, int, str]] = []
    if layout_shape == "linear":
        road_rect(center_x - half_width, 0, center_x + half_width, depth - 1)
        connectors = [
            (center_x, 0, 0, "north_up"),
            (center_x, 0, depth - 1, "south_up"),
        ]
    elif layout_shape == "loop":
        for x in range(ring_min_x, ring_max_x + 1):
            for z in range(ring_min_z, ring_max_z + 1):
                if (
                    x < ring_min_x + road_width
                    or x > ring_max_x - road_width
                    or z < ring_min_z + road_width
                    or z > ring_max_z - road_width
                ):
                    set_block(x, 0, z, road_block)
        road_rect(center_x - half_width, 0, center_x + half_width, ring_min_z)
        road_rect(center_x - half_width, ring_max_z, center_x + half_width, depth - 1)
        road_rect(0, center_z - half_width, ring_min_x, center_z + half_width)
        road_rect(ring_max_x, center_z - half_width, width - 1, center_z + half_width)
        connectors = [
            (center_x, 0, 0, "north_up"), (center_x, 0, depth - 1, "south_up"),
            (0, 0, center_z, "west_up"), (width - 1, 0, center_z, "east_up"),
        ]
    elif layout_shape == "terraced":
        road_rect(0, center_z - half_width, width - 1, center_z + half_width)
        upper_z = max(half_width, center_z - 14)
        lower_z = min(depth - half_width - 1, center_z + 14)
        road_rect(0, upper_z - half_width, center_x, upper_z + half_width)
        road_rect(center_x, lower_z - half_width, width - 1, lower_z + half_width)
        road_rect(center_x - half_width, upper_z, center_x + half_width, lower_z)
        connectors = [
            (0, 0, upper_z, "west_up"), (width - 1, 0, lower_z, "east_up"),
            (0, 0, center_z, "west_up"), (width - 1, 0, center_z, "east_up"),
        ]
    else:
        road_rect(center_x - half_width, 0, center_x + half_width, depth - 1)
        road_rect(0, center_z - half_width, width - 1, center_z + half_width)
        if layout_shape == "radial":
            plaza_radius = half_width + 4
            road_rect(
                center_x - plaza_radius, center_z - plaza_radius,
                center_x + plaza_radius, center_z + plaza_radius,
            )
        connectors = [
            (center_x, 0, 0, "north_up"), (center_x, 0, depth - 1, "south_up"),
            (0, 0, center_z, "west_up"), (width - 1, 0, center_z, "east_up"),
        ]

    # Runtime replaces this invisible marker with the selected RGS gym.  It
    # also lets the placement code recover the rotated Jigsaw plot origin.
    set_block(gym_origin_x, 0, gym_origin_z, "minecraft:barrier")

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

    return _build_structure_nbt((width, height, depth), blocks)


def build_starter_gym_nbt(theme: str = "rock", village_preset: str = "default") -> bytes:
    """Compatibility wrapper retained for callers while gym shells are retired."""
    if theme not in GYM_ROOF_BLOCKS:
        raise ValueError(f"지원하지 않는 체육관 테마입니다: {theme}")
    return build_village_hub_nbt(village_preset)

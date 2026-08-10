from __future__ import annotations

import argparse
import copy
import gzip
import json
import math
import shutil
from pathlib import Path

from starter_gym import (
    BCA_VILLAGE_PRESETS,
    BCA_VILLAGE_START_POOLS,
    FACILITY_PLACEHOLDERS,
    GYM_ROOF_BLOCKS,
    HOUSE_BASES,
    HOUSE_ROOFS,
    HOUSE_ROOF_BLOCKS,
    build_facility_placeholder_nbt,
    build_house_variant_nbt,
    build_village_hub_nbt,
)


SOURCE = Path("projects/cobbleventure-world-bootstrap/src/main/resources")
OUTPUT = Path("projects/cobbleventure-world-bootstrap/src/generated/resources")
SETTLEMENT_CONFIG_DIR = Path("content/settlements")
STARTER_TOWN_CONFIG = SETTLEMENT_CONFIG_DIR / "generation_1/starter_town.json"
HEX_WORLD_CONFIG_DIR = Path("content/worlds")
BOUNDARY_PROFILE_CONFIG = Path("content/catalogs/boundary-profiles.json")
FACILITY_STRUCTURE_SOURCE_DIR = Path("content/structures/placeholder")
REQUIRED_ENTRIES = {
    "META-INF/neoforge.mods.toml",
    "pack.mcmeta",
    "data/cobbleventure/worldgen/template_pool/starter_town/center.json",
    "data/cobbleventure/worldgen/template_pool/route_01_town/center.json",
    "data/cobbleventure/worldgen/template_pool/crimson_town/center.json",
    "data/cobbleventure/worldgen/template_pool/tidehaven_town/center.json",
    "data/cobbleventure/worldgen/template_pool/skyreach_town/center.json",
    "data/cobbleventure/worldgen/biome/starter_plains.json",
    "data/cobbleventure/dimension_type/generation_world.json",
    "data/cobbleventure/dimension/generation_1.json",
    "data/c/tags/worldgen/biome/is_overworld.json",
    "data/c/tags/worldgen/biome/is_plains.json",
    "data/minecraft/tags/worldgen/biome/is_overworld.json",
}
GENERATED_SETTLEMENT_ENTRY = Path(
    "data/cobbleventure/settlements/generation_1/starter_town.json"
)
GENERATED_SETTLEMENT_DIR = Path("data/cobbleventure/settlements")
GENERATED_HEX_WORLD_DIR = Path("data/cobbleventure/hex_worlds")
GENERATED_BOUNDARY_PROFILE = Path("data/cobbleventure/catalogs/boundary-profiles.json")
LEGACY_GENERATED_SETTLEMENT_DIR = Path("data/cobbleventure/cobbleventure/settlements")


class ModBuildError(RuntimeError):
    pass


class TownFacilityPlacementError(ModBuildError):
    def __init__(self, settlement_id: object, facility_id: str):
        self.settlement_id = str(settlement_id)
        self.facility_id = facility_id
        super().__init__(
            f"육각형 마을 범위에 필수 시설을 배치할 수 없습니다: {settlement_id} / {facility_id}"
        )


TOWN_LAYOUT_REROLL_LIMIT = 8
TOWN_LAYOUT_REROLL_STEP = 104729
TOWN_LAYOUT_CENTER_PATTERNS = (
    ("tee_east", (0, 1, 2)),
    ("tee_west", (0, 2, 3)),
    ("tee_north", (0, 1, 3)),
    ("tee_south", (1, 2, 3)),
)


def _inside(root: Path, path: Path, label: str) -> Path:
    resolved = path.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ModBuildError(f"{label} 경로가 저장소 밖을 가리킵니다: {resolved}") from error
    return resolved


def _settlement_data(root: Path) -> list[tuple[Path, dict[str, object]]]:
    source_dir = _inside(root, root / SETTLEMENT_CONFIG_DIR, "마을 설정 디렉터리")
    if not source_dir.is_dir():
        raise ModBuildError(f"마을 설정 디렉터리가 없습니다: {source_dir}")
    settlements: list[tuple[Path, dict[str, object]]] = []
    for source_path in sorted(source_dir.rglob("*.json")):
        try:
            data = json.loads(source_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ModBuildError(f"마을 설정을 읽을 수 없습니다: {source_path}") from error
        if not isinstance(data, dict) or data.get("schema_version") != 3:
            raise ModBuildError(f"마을 설정은 schema_version 3이어야 합니다: {source_path}")
        settlements.append((source_path.relative_to(source_dir), data))
    return settlements


def _package_settlements(
    root: Path,
    output: Path,
    settlements: list[tuple[Path, dict[str, object]]],
) -> None:
    for relative, data in settlements:
        packaged = copy.deepcopy(data)
        compiled_layout = _compile_town_layout(packaged)
        if int(compiled_layout.get("reroll_count", 0)) > 0:
            print(
                "[경고] 필수 시설 배치를 위해 마을 레이아웃을 자동 리롤했습니다: "
                f"{packaged.get('id')} / {compiled_layout['reroll_count']}회 / "
                f"적용 시드 {compiled_layout['resolved_seed']}"
            )
        packaged["compiled_layout"] = compiled_layout
        target = _inside(root, output / GENERATED_SETTLEMENT_DIR / relative, "생성 마을 설정")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps(packaged, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


class _LayoutRandom:
    def __init__(self, seed: int) -> None:
        self.value = seed & 0xFFFFFFFF

    def next_double(self) -> float:
        self.value = (self.value + 0x6D2B79F5) & 0xFFFFFFFF
        result = self.value
        result = ((result ^ (result >> 15)) * (result | 1)) & 0xFFFFFFFF
        result ^= (result + (((result ^ (result >> 7)) * (result | 61)) & 0xFFFFFFFF)) & 0xFFFFFFFF
        return ((result ^ (result >> 14)) & 0xFFFFFFFF) / 4294967296.0


VILLAGE_TILE_RADIUS = 64.0


def _town_layout_cells(cell_count: int, shape: str = "line_q") -> tuple[tuple[int, int], ...]:
    if cell_count == 3:
        return {
            "triangle_up": ((0, 0), (0, -1), (1, -1)),
            "triangle_down": ((0, 0), (0, 1), (-1, 1)),
            "line_q": ((-1, 0), (0, 0), (1, 0)),
            "line_r": ((0, -1), (0, 0), (0, 1)),
            "line_s": ((-1, 1), (0, 0), (1, -1)),
        }.get(shape, ((-1, 0), (0, 0), (1, 0)))
    if cell_count == 5:
        if shape == "five_down":
            return ((-1, 0), (0, 0), (1, 0), (-1, 1), (0, 1))
        return ((-1, 0), (0, 0), (1, 0), (0, -1), (1, -1))
    if cell_count == 7:
        return ((0, 0), (1, 0), (0, 1), (-1, 1), (-1, 0), (0, -1), (1, -1))
    return ((0, 0),)


def _town_layout_cell_center(q: int, r: int) -> tuple[float, float]:
    return (
        VILLAGE_TILE_RADIUS * math.sqrt(3.0) * (q + r / 2.0),
        VILLAGE_TILE_RADIUS * 1.5 * r,
    )


def _town_layout_hub(cell_count: int) -> tuple[int, int]:
    return (0, 32) if cell_count in (3, 5) else (0, 0)


def _town_layout_center_pattern(shape: str, seed: int) -> tuple[str, tuple[int, ...]]:
    if shape == "linear":
        return ("linear", (1, 3))
    if shape == "terraced":
        return ("terraced", (1, 3, 2))
    return TOWN_LAYOUT_CENTER_PATTERNS[(max(1, seed) - 1) % len(TOWN_LAYOUT_CENTER_PATTERNS)]


def _inside_layout_hex(
    x: float, z: float, center_x: float, center_z: float, margin: float = 0.0
) -> bool:
    usable = max(16.0, VILLAGE_TILE_RADIUS - margin)
    local_x = abs(x - center_x)
    local_z = abs(z - center_z)
    return (
        local_z <= usable
        and local_x <= usable * math.sqrt(3.0) / 2.0
        and local_z + local_x / math.sqrt(3.0) <= usable
    )


def _inside_town_layout(
    x: float, z: float, cell_count: int, shape: str = "line_q", margin: float = 0.0
) -> bool:
    return any(
        _inside_layout_hex(x, z, *_town_layout_cell_center(q, r), margin)
        for q, r in _town_layout_cells(cell_count, shape)
    )


def _plot_inside_town_layout(
    plot: dict[str, object], cell_count: int, shape: str = "line_q"
) -> bool:
    x = float(plot["x"])
    z = float(plot["z"])
    width = int(plot["width"])
    depth = int(plot["depth"])
    samples = [
        (px, pz)
        for px in [x + min(offset, width) for offset in range(0, width + 4, 4)]
        for pz in [z + min(offset, depth) for offset in range(0, depth + 4, 4)]
    ]
    samples.extend(((x + width, z + depth), (x + width / 2.0, z + depth / 2.0)))
    return all(_inside_town_layout(px, pz, cell_count, shape, 4.0) for px, pz in samples)


def _plots_intersect(a: dict[str, object], b: dict[str, object], margin: float) -> bool:
    return (
        float(a["x"]) - margin < float(b["x"]) + int(b["width"])
        and float(a["x"]) + int(a["width"]) + margin > float(b["x"])
        and float(a["z"]) - margin < float(b["z"]) + int(b["depth"])
        and float(a["z"]) + int(a["depth"]) + margin > float(b["z"])
    )


def _compiled_facility_specs(data: dict[str, object]) -> list[tuple[str, int, int]]:
    profile = data.get("structure_profile")
    if not isinstance(profile, dict):
        return []
    starter = str(data.get("id", "")).endswith("/starter_town") \
        or profile.get("village_preset") == AUTHORED_STARTER_PRESET
    specs: list[tuple[str, int, int]] = []
    if bool(profile.get("pokemon_center_enabled", not starter)):
        specs.append(("facility_pokemon_center", 32, 32))
    commercial = str(profile.get("commercial_center", "none" if starter else "pokemart"))
    if commercial == "preset":
        commercial = "pokemart"
    if commercial == "pokemart":
        specs.append(("facility_pokemart", 32, 16))
    elif commercial == "department_store":
        specs.append(("facility_department_store", 48, 48))
    gym = profile.get("gym")
    if isinstance(gym, dict) and gym.get("enabled") is True:
        specs.append(("gym_building", 32, 32))
    for facility in profile.get("facility_placements", []):
        if not isinstance(facility, dict):
            continue
        facility_id = str(facility.get("id", ""))
        if not facility_id or any(existing[0] == facility_id for existing in specs):
            continue
        footprint = facility.get("footprint")
        width = int(footprint.get("width", 16)) if isinstance(footprint, dict) else 16
        depth = int(footprint.get("depth", 16)) if isinstance(footprint, dict) else 16
        specs.append((facility_id, width, depth))
    return specs


def _compile_town_layout_attempt(
    data: dict[str, object], seed_override: int | None = None
) -> dict[str, object]:
    profile = data.get("structure_profile")
    if not isinstance(profile, dict):
        raise ModBuildError("마을 structure_profile이 필요합니다.")
    generation = profile.get("generation_profile")
    generation = generation if isinstance(generation, dict) else {}
    configured_palette = generation.get("house_palette")
    configured_palette = configured_palette if isinstance(configured_palette, dict) else {}
    def palette_values(key: str, allowed: object, fallback: list[str]) -> list[str]:
        values = configured_palette.get(key)
        if not isinstance(values, list):
            return fallback
        selected = list(dict.fromkeys(str(value) for value in values if str(value) in allowed))
        return selected or fallback
    house_bases = palette_values("bases", HOUSE_BASES, list(HOUSE_BASES))
    house_roofs = palette_values("roofs", HOUSE_ROOFS, sorted(HOUSE_ROOFS))
    house_roof_colors = palette_values(
        "roof_colors", HOUSE_ROOF_BLOCKS, ["red", "blue", "green", "brown"]
    )
    seed = int(seed_override if seed_override is not None else generation.get("seed", 1))
    depth = max(1, min(6, int(generation.get("depth", 3))))
    road = profile.get("road_profile")
    road = road if isinstance(road, dict) else {}
    road_width = int(road.get("width", 7))
    shape = str(profile.get("layout_shape", "branching"))
    cell_count = int(data.get("town_radius_cells", 7))
    if cell_count not in (1, 3, 5, 7):
        cell_count = 1
    footprint_shape = str(data.get("town_footprint_shape", "line_q"))
    random = _LayoutRandom(seed)
    directions = ((0, -1), (1, 0), (0, 1), (-1, 0))
    center_pattern, initial = _town_layout_center_pattern(shape, seed)
    hub_x, hub_z = _town_layout_hub(cell_count)
    queue = [(hub_x, hub_z, direction, 0) for direction in initial]
    occupied = {(hub_x // 16, hub_z // 16)}
    roads: list[dict[str, int]] = []
    maximum_roads = min(20, 3 + depth * 3)
    while queue and len(roads) < maximum_roads:
        start_x, start_z, direction, branch_depth = queue.pop(0)
        vector_x, vector_z = directions[direction]
        # 중심에서 뻗는 첫 네 갈래는 길이를 맞춰 한 축으로만 길어지는
        # 레이아웃을 방지한다. 이후 분기만 시드에 따라 길이를 달리한다.
        cells = 2 if branch_depth == 0 else 2 + int(random.next_double() * 3.0)
        points: list[tuple[int, int]] = []
        for step in range(1, cells + 1):
            cell_x = start_x // 16 + vector_x * step
            cell_z = start_z // 16 + vector_z * step
            point = (cell_x * 16, cell_z * 16)
            if not _inside_town_layout(point[0], point[1], cell_count, footprint_shape, 8.0):
                break
            if (cell_x, cell_z) in occupied and step > 1:
                break
            points.append(point)
        if len(points) < 2:
            continue
        if branch_depth > 0 and roads:
            while len(points) >= 2:
                candidate_roads = roads + [{
                    "x1": start_x, "z1": start_z,
                    "x2": points[-1][0], "z2": points[-1][1],
                }]
                xs = [coordinate for segment in candidate_roads for coordinate in (segment["x1"], segment["x2"])]
                zs = [coordinate for segment in candidate_roads for coordinate in (segment["z1"], segment["z2"])]
                x_span = max(xs) - min(xs)
                z_span = max(zs) - min(zs)
                if min(x_span, z_span) > 0 and max(x_span, z_span) / min(x_span, z_span) <= 1.75:
                    break
                points.pop()
            if len(points) < 2:
                continue
        for point_x, point_z in points:
            occupied.add((point_x // 16, point_z // 16))
        end_x, end_z = points[-1]
        roads.append({"x1": start_x, "z1": start_z, "x2": end_x, "z2": end_z})
        if branch_depth + 1 >= depth:
            continue
        next_directions = [direction]
        branch_chance = {"linear": 0.12, "radial": 0.20, "loop": 0.34}.get(shape, 0.55)
        branch_roll = random.next_double()
        if (shape == "branching" and branch_depth == 0) or branch_roll < branch_chance:
            next_directions.append((direction + (1 if random.next_double() < 0.5 else 3)) % 4)
        for next_direction in dict.fromkeys(next_directions):
            queue.append((end_x, end_z, next_direction, branch_depth + 1))
    if not roads:
        roads = [{"x1": hub_x, "z1": hub_z - 32, "x2": hub_x, "z2": hub_z + 32}]

    # 각 점유 타일을 실제 마을 도로망에 포함한다. 타일 외곽선만 넓어지고
    # 건물은 중앙에 몰리는 현상을 막기 위해 타일 중심까지 직교 연결한다.
    road_keys = {
        (segment["x1"], segment["z1"], segment["x2"], segment["z2"])
        for segment in roads
    }

    def append_coverage_road(x1: int, z1: int, x2: int, z2: int) -> None:
        if x1 == x2 and z1 == z2:
            return
        key = (x1, z1, x2, z2)
        reverse = (x2, z2, x1, z1)
        if key in road_keys or reverse in road_keys:
            return
        roads.append({"x1": x1, "z1": z1, "x2": x2, "z2": z2})
        road_keys.add(key)

    coverage_sources = {
        (coordinate_x, coordinate_z)
        for segment in roads
        for coordinate_x, coordinate_z in (
            (segment["x1"], segment["z1"]), (segment["x2"], segment["z2"])
        )
        if (coordinate_x, coordinate_z) != (hub_x, hub_z)
    }
    for q, r in _town_layout_cells(cell_count, footprint_shape):
        center_x, center_z = _town_layout_cell_center(q, r)
        target_x = int(round(center_x / 16.0) * 16)
        target_z = int(round(center_z / 16.0) * 16)
        if (target_x, target_z) == (hub_x, hub_z):
            continue
        source_x, source_z = min(
            coverage_sources or {(hub_x, hub_z)},
            key=lambda point: abs(point[0] - target_x) + abs(point[1] - target_z),
        )
        append_coverage_road(source_x, source_z, target_x, source_z)
        append_coverage_road(target_x, source_z, target_x, target_z)
        coverage_sources.add((target_x, target_z))

    slots = [
        (road_index, ratio, side)
        for road_index in range(len(roads))
        for ratio in (0.15, 0.32, 0.50, 0.68, 0.85)
        for side in (-1, 1)
    ]
    plots: list[dict[str, object]] = []

    def place_plot(identifier: str, width: int, plot_depth: int, attempts: int) -> dict[str, object] | None:
        # 난수로 같은 후보를 반복 추첨하지 않고 모든 도로 후보를 한 번씩
        # 순회한다. 큰 필수 시설도 유효한 부지가 하나라도 있으면 놓인다.
        start_slot = int(random.next_double() * len(slots)) if slots else 0
        for attempt in range(min(attempts, max(1, len(slots)))):
            slot_index = (start_slot + attempt) % len(slots)
            road_index, ratio, side = slots[slot_index]
            segment = roads[road_index]
            horizontal = segment["z1"] == segment["z2"]
            along_x = segment["x1"] + (segment["x2"] - segment["x1"]) * ratio
            along_z = segment["z1"] + (segment["z2"] - segment["z1"]) * ratio
            distance = road_width / 2.0 + (plot_depth if horizontal else width) / 2.0 + 5.0
            center_x = along_x + (0.0 if horizontal else side * distance)
            center_z = along_z + (side * distance if horizontal else 0.0)
            candidate: dict[str, object] = {
                "id": identifier, "x": round(center_x - width / 2.0, 2),
                "z": round(center_z - plot_depth / 2.0, 2),
                "width": width, "depth": plot_depth,
            }
            if not _plot_inside_town_layout(candidate, cell_count, footprint_shape):
                continue
            if any(_plots_intersect(candidate, existing, 4.0) for existing in plots):
                continue
            plots.append(candidate)
            return candidate
        return None

    def place_grid_plot(identifier: str, width: int, plot_depth: int) -> dict[str, object] | None:
        """도로 슬롯이 부족할 때 타일 합집합 내부의 가장 가까운 부지를 찾는다."""
        centers = [
            _town_layout_cell_center(q, r)
            for q, r in _town_layout_cells(cell_count, footprint_shape)
        ]

        def segment_distance_squared(cx: float, cz: float, segment: dict[str, int]) -> float:
            x1, x2 = sorted((segment["x1"], segment["x2"]))
            z1, z2 = sorted((segment["z1"], segment["z2"]))
            nearest_x = min(max(cx, x1), x2)
            nearest_z = min(max(cz, z1), z2)
            return (cx - nearest_x) ** 2 + (cz - nearest_z) ** 2

        candidates: list[tuple[float, float, float, float]] = []
        min_x = int(min(center[0] for center in centers) - VILLAGE_TILE_RADIUS)
        max_x = int(max(center[0] for center in centers) + VILLAGE_TILE_RADIUS)
        min_z = int(min(center[1] for center in centers) - VILLAGE_TILE_RADIUS)
        max_z = int(max(center[1] for center in centers) + VILLAGE_TILE_RADIUS)
        for x in range(min_x, max_x - width + 1, 8):
            for z in range(min_z, max_z - plot_depth + 1, 8):
                candidate = {
                    "id": identifier, "x": float(x), "z": float(z),
                    "width": width, "depth": plot_depth,
                }
                if not _plot_inside_town_layout(candidate, cell_count, footprint_shape):
                    continue
                if any(_plots_intersect(candidate, existing, 4.0) for existing in plots):
                    continue
                center_x = x + width / 2.0
                center_z = z + plot_depth / 2.0
                road_distance = min(
                    (segment_distance_squared(center_x, center_z, segment) for segment in roads),
                    default=0.0,
                )
                center_distance = (center_x - hub_x) ** 2 + (center_z - hub_z) ** 2
                candidates.append((road_distance, center_distance, float(x), float(z)))
        if not candidates:
            return None
        _, _, x, z = min(candidates)
        candidate = {
            "id": identifier, "x": x, "z": z,
            "width": width, "depth": plot_depth,
        }
        plots.append(candidate)
        return candidate

    facilities: dict[str, dict[str, object]] = {}
    for identifier, width, plot_depth in _compiled_facility_specs(data):
        plot = place_plot(identifier, width, plot_depth, len(slots) * 4)
        if plot is None:
            plot = place_grid_plot(identifier, width, plot_depth)
        if plot is None:
            raise TownFacilityPlacementError(data.get("id"), identifier)
        facilities[identifier] = plot
    houses: list[dict[str, object]] = []
    house_target = min(18, max(4, 3 + depth * 3))
    for index in range(house_target):
        base_id = house_bases[int(random.next_double() * len(house_bases))]
        roof_id = house_roofs[int(random.next_double() * len(house_roofs))]
        roof_color = house_roof_colors[int(random.next_double() * len(house_roof_colors))]
        width, _, plot_depth = HOUSE_BASES[base_id]["size"]  # type: ignore[misc]
        plot = place_plot(f"house_{index + 1}", width, plot_depth, len(slots) * 2)
        if plot is not None:
            plot.update({
                "base": base_id,
                "roof": roof_id,
                "roof_color": roof_color,
                "structure": f"cobbleventure:houses/{base_id}_{roof_id}_{roof_color}",
            })
            houses.append(plot)
    return {
        "schema_version": 1,
        "shape": "hex_tiles",
        "cell_count": cell_count,
        "footprint_shape": footprint_shape,
        "tile_radius_blocks": int(VILLAGE_TILE_RADIUS),
        "hub": {"x": hub_x, "z": hub_z},
        "center_pattern": center_pattern,
        "roads": roads,
        "facilities": facilities,
        "houses": houses,
    }


def _town_layout_reroll_seed(seed: int, attempt: int) -> int:
    return 1 + ((max(1, seed) - 1 + attempt * TOWN_LAYOUT_REROLL_STEP) % 999_999_999)


def _compile_town_layout(data: dict[str, object]) -> dict[str, object]:
    profile = data.get("structure_profile")
    generation = profile.get("generation_profile") if isinstance(profile, dict) else None
    requested_seed = int(generation.get("seed", 1)) if isinstance(generation, dict) else 1
    last_error: TownFacilityPlacementError | None = None
    for attempt in range(TOWN_LAYOUT_REROLL_LIMIT):
        resolved_seed = _town_layout_reroll_seed(requested_seed, attempt)
        try:
            layout = _compile_town_layout_attempt(data, resolved_seed)
        except TownFacilityPlacementError as error:
            last_error = error
            continue
        layout["requested_seed"] = requested_seed
        layout["resolved_seed"] = resolved_seed
        layout["reroll_count"] = attempt
        layout["reroll_limit"] = TOWN_LAYOUT_REROLL_LIMIT
        return layout
    missing = last_error.facility_id if last_error is not None else "unknown"
    raise ModBuildError(
        f"필수 시설 자동 리롤 {TOWN_LAYOUT_REROLL_LIMIT}회가 모두 실패했습니다: "
        f"{data.get('id')} / {missing}. 마을 크기나 시설 수를 조정해 주세요."
    ) from last_error


def _package_hex_worlds(root: Path, output: Path) -> None:
    source_dir = _inside(root, root / HEX_WORLD_CONFIG_DIR, "육각 월드 설정 디렉터리")
    if not source_dir.is_dir():
        raise ModBuildError(f"육각 월드 설정 디렉터리가 없습니다: {source_dir}")
    for source_path in sorted(source_dir.rglob("*.json")):
        try:
            data = json.loads(source_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ModBuildError(f"육각 월드 설정을 읽을 수 없습니다: {source_path}") from error
        if not isinstance(data, dict) or data.get("schema_version") not in {1, 2}:
            raise ModBuildError(
                f"육각 월드 설정은 schema_version 1 또는 2여야 합니다: {source_path}"
            )
        relative = source_path.relative_to(source_dir)
        target = _inside(root, output / GENERATED_HEX_WORLD_DIR / relative, "생성 육각 월드 설정")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    boundary_path = _inside(root, root / BOUNDARY_PROFILE_CONFIG, "경계 프로필 설정")
    try:
        boundary_data = json.loads(boundary_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ModBuildError(f"경계 프로필 설정을 읽을 수 없습니다: {boundary_path}") from error
    if not isinstance(boundary_data, dict) or boundary_data.get("schema_version") != 1:
        raise ModBuildError("경계 프로필 설정은 schema_version 1이어야 합니다.")
    boundary_target = _inside(root, output / GENERATED_BOUNDARY_PROFILE, "생성 경계 프로필")
    boundary_target.parent.mkdir(parents=True, exist_ok=True)
    boundary_target.write_text(
        json.dumps(boundary_data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


COMMERCIAL_CENTER_STRUCTURES = {
    "pokemart": "bca:default/one_off/structure_pokemart",
    "department_store": "bca:default/centers/center_department_store",
}
AUTHORED_STARTER_PRESET = "cobbleventure_starter"


def _village_hub_definition(
    data: dict[str, object],
) -> tuple[str, str, str, str, str, int, str]:
    try:
        profile = data["structure_profile"]  # type: ignore[index]
        theme = profile["gym_theme"]  # type: ignore[index]
        village_preset = profile.get("village_preset", "default")  # type: ignore[union-attr]
        commercial_center = profile.get("commercial_center", "preset")  # type: ignore[union-attr]
        layout_shape = profile.get("layout_shape", "branching")  # type: ignore[union-attr]
        road_profile = profile.get("road_profile", {})  # type: ignore[union-attr]
        resource = profile["required_facilities"]["village_hub"]  # type: ignore[index]
    except (KeyError, TypeError) as error:
        raise ModBuildError("마을 BCA 허브 또는 체육관 테마를 읽을 수 없습니다.") from error
    if not isinstance(theme, str):
        raise ModBuildError("마을 체육관 테마는 문자열이어야 합니다.")
    if theme not in GYM_ROOF_BLOCKS:
        raise ModBuildError(f"지원하지 않는 체육관 테마입니다: {theme}")
    if not isinstance(village_preset, str) or village_preset not in BCA_VILLAGE_PRESETS:
        raise ModBuildError(f"지원하지 않는 BCA 마을 프리셋입니다: {village_preset}")
    if commercial_center not in {"none", "preset", *COMMERCIAL_CENTER_STRUCTURES}:
        raise ModBuildError(f"지원하지 않는 상업 중심 시설입니다: {commercial_center}")
    if village_preset == AUTHORED_STARTER_PRESET and commercial_center != "none":
        raise ModBuildError("전용 시작 마을은 commercial_center가 none이어야 합니다.")
    if layout_shape not in {"branching", "linear", "radial", "loop", "terraced"}:
        raise ModBuildError(f"지원하지 않는 마을 도로 형태입니다: {layout_shape}")
    if not isinstance(road_profile, dict):
        raise ModBuildError("마을 도로 프로필은 객체여야 합니다.")
    road_width = road_profile.get("width", 7)
    road_material = road_profile.get("material", "cobblestone")
    if road_width not in {3, 5, 7, 9}:
        raise ModBuildError(f"지원하지 않는 도로 폭입니다: {road_width}")
    if road_material not in {
        "cobblestone", "stone_bricks", "gravel",
        "packed_mud", "sandstone", "snow",
    }:
        raise ModBuildError(f"지원하지 않는 도로 노면입니다: {road_material}")
    if not isinstance(resource, str) or ":" not in resource:
        raise ModBuildError("체육관 리소스 ID가 올바르지 않습니다.")
    return (
        resource, theme, village_preset, commercial_center,
        str(layout_shape), int(road_width), str(road_material),
    )


def _village_hub_output_path(output: Path, resource: str) -> Path:
    namespace, path = resource.split(":", 1)
    return output / "data" / namespace / "structure" / f"{path}.nbt"


def _village_structure_output_path(output: Path, resource: str) -> Path:
    namespace, path = resource.split(":", 1)
    return output / "data" / namespace / "worldgen" / "structure" / f"{path}.json"


def _commercial_center_pool(output: Path, resource: str) -> tuple[str, Path]:
    namespace, path = resource.split(":", 1)
    village_root = path.rsplit("/", 1)[0] if "/" in path else path
    pool_path = f"{village_root}/commercial_center"
    pool_id = f"{namespace}:{pool_path}"
    return pool_id, output / "data" / namespace / "worldgen" / "template_pool" / f"{pool_path}.json"


def _write_authored_starter_pool(
    root: Path, output: Path, resource: str, profile: dict[str, object]
) -> tuple[str, int]:
    layout = profile.get("starter_layout")
    if not isinstance(layout, dict):
        raise ModBuildError("전용 시작 마을에는 starter_layout 설정이 필요합니다.")
    laboratory = layout.get("laboratory_structure")
    if not isinstance(laboratory, str) or ":" not in laboratory:
        raise ModBuildError("시작 마을 연구소 구조물 ID가 올바르지 않습니다.")
    depth = layout.get("jigsaw_depth", 2)
    if not isinstance(depth, int) or isinstance(depth, bool) or not 0 <= depth <= 4:
        raise ModBuildError("시작 마을 Jigsaw 깊이는 0 이상 4 이하의 정수여야 합니다.")
    namespace, path = resource.split(":", 1)
    village_root = path.rsplit("/", 1)[0] if "/" in path else path
    pool_path = f"{village_root}/authored_center"
    pool_id = f"{namespace}:{pool_path}"
    target = _inside(
        root,
        output / "data" / namespace / "worldgen" / "template_pool" / f"{pool_path}.json",
        "전용 시작 마을 중심 풀",
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(
            {
                "fallback": "minecraft:empty",
                "elements": [{
                    "weight": 1,
                    "element": {
                        "location": laboratory,
                        "element_type": "minecraft:single_pool_element",
                        "processors": "minecraft:empty",
                        "projection": "rigid",
                        "terrain_adaptation": "beard_thin",
                    },
                }],
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    return pool_id, depth


def _write_commercial_center_pool(
    root: Path, output: Path, resource: str, commercial_center: str
) -> str:
    structure = COMMERCIAL_CENTER_STRUCTURES[commercial_center]
    pool_id, raw_target = _commercial_center_pool(output, resource)
    target = _inside(root, raw_target, "상업 중심 시설 템플릿 풀")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(
            {
                "fallback": "minecraft:empty",
                "elements": [{
                    "weight": 1,
                    "element": {
                        "location": structure,
                        "element_type": "minecraft:single_pool_element",
                        "processors": "minecraft:empty",
                        "projection": "rigid",
                        "terrain_adaptation": "beard_thin",
                    },
                }],
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    return pool_id


def _write_civic_hub_pool(
    root: Path,
    output: Path,
    structure_resource: str,
    hub_resource: str,
    village_preset: str,
) -> tuple[str, int]:
    namespace, path = structure_resource.split(":", 1)
    village_root = path.rsplit("/", 1)[0] if "/" in path else path
    pool_path = f"{village_root}/configured_hub"
    pool_id = f"{namespace}:{pool_path}"
    target = _inside(
        root,
        output / "data" / namespace / "worldgen" / "template_pool" / f"{pool_path}.json",
        "설정형 마을 허브 풀",
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(
            {
                "fallback": "minecraft:empty",
                "elements": [{
                    "weight": 1,
                    "element": {
                        "location": hub_resource,
                        "element_type": "minecraft:single_pool_element",
                        "processors": "minecraft:empty",
                        "projection": "rigid",
                        "terrain_adaptation": "beard_thin",
                    },
                }],
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    return pool_id, BCA_VILLAGE_START_POOLS.get(village_preset, ("", 3))[1]


def _write_village_structure_override(
    root: Path,
    output: Path,
    settlement: dict[str, object],
    village_preset: str,
    commercial_center: str,
) -> None:
    profile = settlement.get("structure_profile")
    explicit_civic = isinstance(profile, dict) and profile.get("civic_facilities_explicit") is True
    if village_preset not in BCA_VILLAGE_START_POOLS \
            and village_preset != AUTHORED_STARTER_PRESET \
            and not (explicit_civic and village_preset == "default"):
        return
    if not isinstance(profile, dict):
        return
    resource = profile.get("structure")
    # Small unit-test fixtures and legacy fragments do not declare a worldgen
    # structure. They can still build their compatibility hub, but there is no
    # structure registry entry to override.
    if not isinstance(resource, str) or ":" not in resource:
        return
    # A BCA resource ID means "use the mod's registered village verbatim".
    # Never emit a generated data-pack entry in the bca namespace: doing so
    # shadows the upstream structure and can silently change its Jigsaw graph.
    if resource.startswith("bca:"):
        return
    biome = "minecraft:plains"
    biome_layout = settlement.get("biome_layout")
    if isinstance(biome_layout, dict):
        zones = biome_layout.get("zones")
        if isinstance(zones, list) and zones and isinstance(zones[0], dict):
            candidate = zones[0].get("biome")
            if isinstance(candidate, str) and ":" in candidate:
                biome = candidate
    if village_preset == AUTHORED_STARTER_PRESET:
        start_pool, size = _write_authored_starter_pool(root, output, resource, profile)
    elif profile.get("civic_facilities_explicit") is True:
        required_facilities = profile.get("required_facilities")
        if not isinstance(required_facilities, dict):
            raise ModBuildError("설정형 마을에는 required_facilities가 필요합니다.")
        hub_resource = required_facilities.get("village_hub")
        if not isinstance(hub_resource, str) or ":" not in hub_resource:
            raise ModBuildError("설정형 마을 허브 리소스 ID가 올바르지 않습니다.")
        start_pool, size = _write_civic_hub_pool(
            root, output, resource, hub_resource, village_preset
        )
    else:
        start_pool, size = BCA_VILLAGE_START_POOLS[village_preset]
    # BCA's Pokemart is a one-off building, not a complete village root. Using
    # it as start_pool drops the native road/house jigsaw graph and leaves only
    # the Pokemart (plus structures placed later, such as the gym). A regular
    # Pokemart town must therefore keep the selected BCA village start pool;
    # that preset already supplies its Pokemon facilities and full housing
    # layout. The department store remains an explicit large-town centre.
    if commercial_center == "department_store" and profile.get("civic_facilities_explicit") is not True:
        start_pool = _write_commercial_center_pool(
            root, output, resource, commercial_center
        )
    target = _inside(root, _village_structure_output_path(output, resource), "생성 마을 구조")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(
            {
                "type": "minecraft:jigsaw",
                "biomes": biome,
                "spawn_overrides": {},
                "start_pool": start_pool,
                "size": size,
                "step": "surface_structures",
                # Match BCA's original village structures. Secondary houses and
                # decorations that use a raised template origin are corrected
                # separately by TownPlacementHeightContext at placement time.
                "start_height": {"absolute": 0},
                "project_start_to_heightmap": "WORLD_SURFACE_WG",
                "max_distance_from_center": 116,
                "terrain_adaptation": "beard_thin",
                "use_expansion_hack": False,
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )


def build(root: Path) -> Path:
    root = root.resolve()
    source = _inside(root, root / SOURCE, "소스")
    output = _inside(root, root / OUTPUT, "출력")
    if not source.is_dir():
        raise ModBuildError(f"데이터 모드 소스가 없습니다: {source}")

    names = {
        path.relative_to(source).as_posix()
        for path in source.rglob("*")
        if path.is_file()
    }
    settlements = _settlement_data(root)
    missing = sorted(REQUIRED_ENTRIES - names)
    if missing:
        raise ModBuildError(f"필수 데이터 모드 파일이 없습니다: {', '.join(missing)}")

    # OUTPUT contains generated resources only. Recreate it so a town that
    # changes from a commercial centre to an authored layout cannot retain an
    # obsolete template pool from the previous build.
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)

    generated_hubs: dict[str, tuple[str, str, str, str, int, str]] = {}
    first_generated: Path | None = None
    for _, settlement in settlements:
        (
            resource, theme, village_preset, commercial_center,
            layout_shape, road_width, road_material,
        ) = _village_hub_definition(settlement)
        previous = generated_hubs.get(resource)
        definition = (
            theme, village_preset, commercial_center,
            layout_shape, road_width, road_material,
        )
        if previous is not None and previous != definition:
            raise ModBuildError(
                f"같은 BCA 마을 허브에 서로 다른 설정이 지정되었습니다: {resource}"
            )
        generated_hubs[resource] = definition
    generated_structure_dir = _inside(
        root, output / "data/cobbleventure/structure", "생성 구조물 디렉터리"
    )
    if generated_structure_dir.exists():
        shutil.rmtree(generated_structure_dir)
    for resource, (
        _, village_preset, _, layout_shape, road_width, road_material
    ) in generated_hubs.items():
        generated = _inside(root, _village_hub_output_path(output, resource), "생성 마을 허브")
        generated.parent.mkdir(parents=True, exist_ok=True)
        generated.write_bytes(build_village_hub_nbt(
            village_preset, layout_shape, road_width, road_material
        ))
        if first_generated is None:
            first_generated = generated
    for facility_id in FACILITY_PLACEHOLDERS:
        resource = f"cobbleventure:placeholder/{facility_id}"
        generated = _inside(
            root, _village_hub_output_path(output, resource),
            "생성 시설 플레이스홀더",
        )
        generated.parent.mkdir(parents=True, exist_ok=True)
        authored = _inside(
            root,
            root / FACILITY_STRUCTURE_SOURCE_DIR / f"{facility_id}.nbt",
            "시설 NBT 원본",
        )
        if authored.is_file():
            authored_bytes = authored.read_bytes()
            try:
                decompressed = gzip.decompress(authored_bytes)
            except (EOFError, OSError) as error:
                raise ModBuildError(f"시설 NBT가 GZip 구조물 파일이 아닙니다: {authored}") from error
            if not decompressed or decompressed[0] != 10:
                raise ModBuildError(f"시설 NBT의 루트가 TAG_Compound가 아닙니다: {authored}")
            generated.write_bytes(authored_bytes)
        else:
            generated.write_bytes(build_facility_placeholder_nbt(facility_id))
    for base_id in HOUSE_BASES:
        for roof_id in sorted(HOUSE_ROOFS):
            for roof_color in HOUSE_ROOF_BLOCKS:
                resource = f"cobbleventure:houses/{base_id}_{roof_id}_{roof_color}"
                generated = _inside(
                    root, _village_hub_output_path(output, resource),
                    "생성 주택 변형",
                )
                generated.parent.mkdir(parents=True, exist_ok=True)
                generated.write_bytes(build_house_variant_nbt(base_id, roof_id, roof_color))
    for directory in (
        GENERATED_SETTLEMENT_DIR,
        LEGACY_GENERATED_SETTLEMENT_DIR,
        GENERATED_HEX_WORLD_DIR,
    ):
        generated_directory = _inside(root, output / directory, "생성 마을 설정 디렉터리")
        if generated_directory.exists():
            shutil.rmtree(generated_directory)
    _package_settlements(root, output, settlements)
    _package_hex_worlds(root, output)
    if first_generated is None:
        raise ModBuildError("생성할 BCA 마을 허브가 없습니다.")
    return first_generated


def main() -> int:
    parser = argparse.ArgumentParser(description="Cobbleventure 데이터 모드 빌더")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    arguments = parser.parse_args()
    output = build(arguments.root)
    print(f"교체 가능한 마을 건물·시설 NBT 생성 완료: {output.parent.parent}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

from __future__ import annotations

import argparse
import copy
import gzip
import io
import json
import math
import os
import re
import shutil
import struct
from pathlib import Path

from starter_gym import (
    BCA_VILLAGE_PRESETS,
    BCA_VILLAGE_START_POOLS,
    FACILITY_PLACEHOLDERS,
    GYM_ROOF_BLOCKS,
    HOUSE_BASES,
    HOUSE_ROOFS,
    HOUSE_ROOF_BLOCKS,
    TOWN_DECORATION_SIZES,
    build_facility_placeholder_nbt,
    build_house_variant_nbt,
    build_town_decoration_nbt,
    build_village_hub_nbt,
    recolor_house_roof_nbt,
)


PROJECT_ROOT = Path(os.environ.get(
    "COBBLEVENTURE_PROJECT_PATH", "content-projects/cobbleventure-main"
))
CONTENT_ROOT = PROJECT_ROOT / "content"
SOURCE = Path("projects/cobbleventure-world-bootstrap/src/main/resources")
OUTPUT = Path("projects/cobbleventure-world-bootstrap/src/generated/resources")
SETTLEMENT_CONFIG_DIR = CONTENT_ROOT / "settlements"
STARTER_TOWN_CONFIG = SETTLEMENT_CONFIG_DIR / "generation_1/starter_town.json"
HEX_WORLD_CONFIG_DIR = CONTENT_ROOT / "worlds"
ROUTE_PRESET_CONFIG_DIR = CONTENT_ROOT / "routes"
BOUNDARY_PROFILE_CONFIG = CONTENT_ROOT / "catalogs/boundary-profiles.json"
GENERATED_CONTENT_DIR = Path("generated")
CVES_SOURCE_DIR = CONTENT_ROOT / "events"
FACILITY_STRUCTURE_SOURCE_DIR = CONTENT_ROOT / "structures/placeholder"
HOUSE_STRUCTURE_SOURCE_DIR = CONTENT_ROOT / "structures/houses"
TOWN_DECORATION_STRUCTURE_SOURCE_DIR = CONTENT_ROOT / "structures/town_decorations"
CAVE_ENTRANCE_STRUCTURE_SOURCE_DIR = CONTENT_ROOT / "structures/cave_entrance"
FOREST_ENTRANCE_STRUCTURE_SOURCE_DIR = CONTENT_ROOT / "structures/forest_gate"
INTERIOR_STRUCTURE_SOURCE_DIR = CONTENT_ROOT / "structures/interiors"
GYM_STRUCTURE_SOURCE_DIR = CONTENT_ROOT / "structures/gyms"
LEAGUE_STRUCTURE_SOURCE_DIR = CONTENT_ROOT / "structures/league"
GYM_CATALOG_SOURCE = CONTENT_ROOT / "catalogs/gyms.json"
LEAGUE_CATALOG_SOURCE = CONTENT_ROOT / "catalogs/league-progression.json"
GYM_CATALOG_ENTRY = Path("data/cobbleventure/catalogs/gyms.json")
MUSIC_CATALOG_SOURCE = CONTENT_ROOT / "catalogs/music-tracks.json"
MUSIC_CATALOG_ENTRY = Path("data/cobbleventure/catalogs/music-tracks.json")
DIMENSION_ANCHOR_CATALOG_SOURCE = CONTENT_ROOT / "catalogs/dimension-anchors.json"
DIMENSION_ANCHOR_CATALOG_ENTRY = Path("data/cobbleventure/catalogs/dimension-anchors.json")
EVENT_BOUNDARY_CATALOG_SOURCE = CONTENT_ROOT / "catalogs/event-boundaries.json"
EVENT_BOUNDARY_CATALOG_ENTRY = Path("data/cobbleventure/catalogs/event-boundaries.json")
DIALOGUE_THEME_SOURCE = CONTENT_ROOT / "catalogs/dialogue-theme.json"
DIALOGUE_THEME_ENTRY = Path("data/cobbleventure/dialogue_theme/global.json")
DIALOGUE_THEME_ASSET_ENTRY = Path("assets/cobbleventure/dialogue_theme/global.json")
BATTLE_PRESET_SOURCE_DIR = CONTENT_ROOT / "battles"
LOOT_TABLE_SOURCE_DIR = CONTENT_ROOT / "loot_tables"
NPC_SOURCE_DIR = CONTENT_ROOT / "source"
NPC_PLACEMENT_PROFILE_ENTRY = Path("data/cobbleventure/catalogs/npc-placement-profiles.json")
BATTLE_PRESET_ENTRY_DIR = Path("data/cobbleventure/battles")
BUILDING_SETTINGS_SOURCE = CONTENT_ROOT / "catalogs/building-settings.json"
BUILDING_SETTINGS_ENTRY = Path("data/cobbleventure/building_settings.json")
STRUCTURE_METADATA_ENTRY_DIR = Path("data/cobbleventure/structure_metadata")
REQUIRED_ENTRIES = {
    "META-INF/neoforge.mods.toml",
    "pack.mcmeta",
    "data/cobbleventure/worldgen/template_pool/starter_town/center.json",
    "data/cobbleventure/worldgen/template_pool/route_01_town/center.json",
    "data/cobbleventure/worldgen/template_pool/crimson_town/center.json",
    "data/cobbleventure/worldgen/template_pool/tidehaven_town/center.json",
    "data/cobbleventure/worldgen/template_pool/skyreach_town/center.json",
    "data/cobbleventure/worldgen/biome/starter_plains.json",
    "data/cobbleventure/worldgen/biome/sealed_forest_edge.json",
    "data/cobbleventure/worldgen/placed_feature/sealed_forest_edge_trees.json",
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
GENERATED_ROUTE_PRESET_DIR = Path("data/cobbleventure/routes")
GENERATED_BOUNDARY_PROFILE = Path("data/cobbleventure/catalogs/boundary-profiles.json")
LEGACY_GENERATED_SETTLEMENT_DIR = Path("data/cobbleventure/cobbleventure/settlements")


def _package_generated_trainer_content(root: Path, output: Path) -> None:
    """Package generated RCT trainers and Cobbleventure AI profiles into the mod."""
    generated = _inside(root, root / GENERATED_CONTENT_DIR, "생성 콘텐츠 디렉터리")
    rct_data = generated / "rct" / "data"
    ai_profiles = generated / "cobbleventure" / "ai-profiles"
    if not rct_data.is_dir():
        # Minimal test fixtures and settlement-only consumers do not own battle
        # content. A real repository with battle presets must never build a JAR
        # that silently omits their generated RCT resources.
        if (root / BATTLE_PRESET_SOURCE_DIR).is_dir():
            raise ModBuildError(
                "생성된 RCT 트레이너가 없습니다. 먼저 content-manager generate를 실행하세요."
            )
        return
    shutil.copytree(rct_data, output / "data", dirs_exist_ok=True)
    if ai_profiles.is_dir():
        shutil.copytree(
            ai_profiles,
            output / "data" / "cobbleventure" / "ai-profiles",
            dirs_exist_ok=True,
        )


def _package_generated_cves_content(root: Path, output: Path) -> None:
    """Package compiled CVES IR and representation-neutral NPC bindings."""
    generated = _inside(root, root / GENERATED_CONTENT_DIR, "생성 콘텐츠 디렉터리")
    cves_data = generated / "cves" / "data"
    if not cves_data.is_dir():
        if (root / CVES_SOURCE_DIR).is_dir():
            raise ModBuildError(
                "생성된 CVES 데이터가 없습니다. 먼저 content-manager generate를 실행하세요."
            )
        return
    shutil.copytree(cves_data, output / "data", dirs_exist_ok=True)


def _package_dimension_anchor_catalog(root: Path, output: Path) -> None:
    """Package the authoritative CVES dimension arrival registry unchanged."""
    source = _inside(
        root, root / DIMENSION_ANCHOR_CATALOG_SOURCE, "차원 앵커 카탈로그"
    )
    if not source.is_file():
        return
    target = _inside(
        root, output / DIMENSION_ANCHOR_CATALOG_ENTRY, "생성 차원 앵커 카탈로그"
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(source.read_bytes())


def _package_event_boundary_catalog(root: Path, output: Path) -> None:
    """Package the explicit CVES region and anchor boundary index unchanged."""
    source = _inside(root, root / EVENT_BOUNDARY_CATALOG_SOURCE, "이벤트 경계 카탈로그")
    if not source.is_file():
        return
    target = _inside(
        root, output / EVENT_BOUNDARY_CATALOG_ENTRY, "생성 이벤트 경계 카탈로그"
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(source.read_bytes())


def _package_dialogue_theme(root: Path, output: Path) -> None:
    """Package the authored global dialogue presentation contract unchanged."""
    source = _inside(root, root / DIALOGUE_THEME_SOURCE, "대화 테마")
    if not source.is_file():
        return
    target = _inside(root, output / DIALOGUE_THEME_ENTRY, "생성 대화 테마")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(source.read_bytes())
    asset_target = _inside(
        root, output / DIALOGUE_THEME_ASSET_ENTRY, "생성 클라이언트 대화·메뉴 테마"
    )
    asset_target.parent.mkdir(parents=True, exist_ok=True)
    asset_target.write_bytes(source.read_bytes())


def _package_loot_tables(root: Path, output: Path) -> None:
    """Package authoritative loot tables into Minecraft's singular resource folder."""
    source_root = _inside(root, root / LOOT_TABLE_SOURCE_DIR, "loot table 원본 디렉터리")
    if not source_root.is_dir():
        return
    for source in sorted(source_root.rglob("*.json")):
        relative = source.relative_to(source_root)
        if len(relative.parts) < 2:
            raise ModBuildError(
                f"loot table은 <namespace>/<path>.json 구조여야 합니다: {source}"
            )
        namespace = relative.parts[0]
        resource_path = Path(*relative.parts[1:])
        if not re.fullmatch(r"[a-z0-9_.-]+", namespace) or not re.fullmatch(
            r"[a-z0-9_./-]+", resource_path.with_suffix("").as_posix()
        ):
            raise ModBuildError(f"올바르지 않은 loot table 원본 경로입니다: {source}")
        try:
            document = json.loads(source.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ModBuildError(f"loot table JSON을 읽을 수 없습니다: {source}: {error}") from error
        if not isinstance(document, dict):
            raise ModBuildError(f"loot table JSON 루트는 object여야 합니다: {source}")
        target = _inside(
            root,
            output / "data" / namespace / "loot_table" / resource_path,
            "패키징 loot table",
        )
        if target.exists():
            raise ModBuildError(f"중복 loot table 출력 경로입니다: {target}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)


def _compiled_gym_catalog(root: Path, gym_catalog: Path) -> dict[str, object]:
    """Materialize runtime-only NPC and badge references from league authoring data."""
    catalog = json.loads(gym_catalog.read_text(encoding="utf-8"))
    league_path = _inside(root, root / LEAGUE_CATALOG_SOURCE, "리그 카탈로그")
    if not league_path.is_file():
        return catalog
    league = json.loads(league_path.read_text(encoding="utf-8"))
    entries = {
        entry.get("id"): entry
        for entry in league.get("entries", [])
        if isinstance(entry, dict)
    }
    for gym in catalog.get("gyms", []):
        if not isinstance(gym, dict):
            continue
        leader = gym.get("staff", {}).get("leader", {})
        if not isinstance(leader, dict):
            continue
        entry = entries.get(leader.get("league_entry_id"))
        encounter = entry.get("encounter") if isinstance(entry, dict) else None
        if not isinstance(encounter, dict):
            continue
        battle_id = encounter.get("battle_id", "")
        if isinstance(battle_id, str) and battle_id:
            leader["trainer_id"] = f"cobbleventure:npc/gym_leader/{battle_id.rsplit('/', 1)[-1]}"
        rewards = encounter.get("rewards", {})
        if isinstance(rewards, dict) and rewards.get("badge_id"):
            leader["badge_id"] = rewards["badge_id"]
        access = gym.get("access")
        if isinstance(access, dict):
            access.pop("previous_badge", None)
            if access.get("require_previous_gym") is True and isinstance(entry, dict):
                region = entry.get("region")
                order = entry.get("order")
                previous = [
                    candidate for candidate in entries.values()
                    if isinstance(candidate, dict)
                    and candidate.get("role") == "gym_leader"
                    and candidate.get("region") == region
                    and isinstance(candidate.get("order"), int)
                    and isinstance(order, int)
                    and candidate["order"] < order
                ]
                if previous:
                    previous.sort(key=lambda candidate: candidate["order"], reverse=True)
                    previous_rewards = previous[0].get("encounter", {}).get("rewards", {})
                    badge_id = previous_rewards.get("badge_id") if isinstance(previous_rewards, dict) else None
                    if isinstance(badge_id, str) and badge_id:
                        access["previous_badge"] = badge_id
    return catalog


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
BUILDING_DENSITY_PROFILES = {
    "sparse": {"gap": 8.0, "multiplier": 0.7, "ratios": (0.22, 0.50, 0.78)},
    "normal": {"gap": 4.0, "multiplier": 1.0, "ratios": (0.15, 0.32, 0.50, 0.68, 0.85)},
    "dense": {"gap": 1.0, "multiplier": 1.4, "ratios": (0.08, 0.22, 0.36, 0.50, 0.64, 0.78, 0.92)},
    "packed": {"gap": 0.0, "multiplier": 1.8, "ratios": (0.06, 0.17, 0.28, 0.39, 0.50, 0.61, 0.72, 0.83, 0.94)},
}
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


def _read_authored_structure_nbt(path: Path, label: str) -> bytes:
    authored_bytes = path.read_bytes()
    try:
        decompressed = gzip.decompress(authored_bytes)
    except (EOFError, OSError) as error:
        raise ModBuildError(f"{label}가 GZip 구조물 파일이 아닙니다: {path}") from error
    if not decompressed or decompressed[0] != 10:
        raise ModBuildError(f"{label}의 루트가 TAG_Compound가 아닙니다: {path}")
    return authored_bytes


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
    settlements.sort(key=lambda item: (
        item[1].get("load_order") if isinstance(item[1].get("load_order"), int) else 1_000_000,
        item[0].as_posix(),
    ))
    return settlements


def _npc_placement_profiles(root: Path) -> list[dict[str, object]]:
    source_dir = _inside(root, root / NPC_SOURCE_DIR, "NPC 설정 디렉터리")
    profiles: list[dict[str, object]] = []
    for source_path in sorted(source_dir.rglob("*.json")) if source_dir.is_dir() else []:
        try:
            document = json.loads(source_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ModBuildError(f"NPC 설정을 읽을 수 없습니다: {source_path}") from error
        profile = document.get("placement_profile") if isinstance(document, dict) else None
        npc_id = document.get("id") if isinstance(document, dict) else None
        if not isinstance(profile, dict) or not isinstance(npc_id, str):
            continue
        event_runtime = document.get("event_runtime")
        event_engine = event_runtime.get("engine") if isinstance(event_runtime, dict) else "easy_npc_v4"
        profiles.append({
            "npc": npc_id,
            "event_engine": event_engine if event_engine in {"easy_npc_v4", "cves_v5"} else "easy_npc_v4",
            **copy.deepcopy(profile),
        })
    return profiles


def _rank_npc_profiles(
    profiles: list[dict[str, object]], *, classification: str, level: int,
    biomes: set[str], target: str,
) -> list[str]:
    enabled_key = (
        "automatic_town_placement"
        if target == "town"
        else "automatic_route_placement"
    )
    ranked: list[tuple[int, str]] = []
    for profile in profiles:
        if profile.get("classification") != classification or profile.get(enabled_key) is not True:
            continue
        preferred = {str(value) for value in profile.get("preferred_biomes", []) if isinstance(value, str)}
        biome_score = 0 if preferred & biomes else 100 if preferred else 20
        expected = profile.get("expected_level")
        level_score = abs(expected - level) if isinstance(expected, int) else 15
        npc_id = str(profile.get("npc", ""))
        if npc_id:
            ranked.append((biome_score + level_score, npc_id))
    return [npc_id for _, npc_id in sorted(ranked, key=lambda item: (item[0], item[1]))]


def _resolved_trainer_ids(
    profiles: list[dict[str, object]], population: dict[str, object], *,
    level: int, biomes: set[str], target: str,
) -> list[str]:
    known_ids = {str(profile.get("npc")) for profile in profiles if profile.get("classification") == "trainer"}
    direct = [
        str(value) for value in population.get("direct_trainers", [])
        if isinstance(value, str) and value in known_ids
    ]
    automatic = [] if population.get("use_biome_defaults") is False else _rank_npc_profiles(
        profiles, classification="trainer", level=level, biomes=biomes, target=target,
    )
    return list(dict.fromkeys([*direct, *automatic]))


def _town_npc_placement_records(
    ambient: list[str], trainers: list[str], placement_areas: list[str] | None = None,
) -> list[dict[str, str]]:
    """Assign residents indoors and distribute trainers across outdoor/indoor slots."""
    areas = [area for area in (placement_areas or ["indoor", "outdoor"])
             if area in {"indoor", "outdoor"}]
    areas = areas or ["indoor", "outdoor"]
    if areas == ["indoor"]:
        trainer_area = lambda _index: "indoor"
    elif areas == ["outdoor"]:
        trainer_area = lambda _index: "outdoor"
    else:
        trainer_area = lambda index: "outdoor" if index % 2 == 0 else "indoor"
    return [
        *({"npc": npc_id, "classification": "ambient", "placement_area": "indoor"} for npc_id in ambient),
        *({"npc": npc_id, "classification": "trainer", "placement_area": trainer_area(index)} for index, npc_id in enumerate(trainers)),
    ]


def _requested_town_indoor_npcs(data: dict[str, object]) -> int:
    """Return the maximum number of automatic NPCs that need indoor slots."""
    placement = data.get("npc_placement")
    if not isinstance(placement, dict) or placement.get("auto_place_npcs") is not True:
        return 0
    ambient = max(0, int(placement.get("max_ambient_npcs", 0)))
    population = placement.get("trainer_population")
    if not isinstance(population, dict):
        return ambient
    areas = [area for area in population.get("placement_areas", ["indoor", "outdoor"])
             if area in {"indoor", "outdoor"}]
    areas = areas or ["indoor", "outdoor"]
    if "indoor" not in areas:
        return ambient
    if population.get("enabled") is not True:
        return ambient
    trainer_count = max(0, int(population.get("max_active", 0)))
    if "outdoor" not in areas:
        return ambient + trainer_count
    return ambient + trainer_count // 2


def _metadata_npc_slot_count(project_root: Path, structure_id: object) -> int:
    if not isinstance(structure_id, str) or ":" not in structure_id:
        return 0
    namespace, resource = structure_id.split(":", 1)
    if namespace != "cobbleventure":
        return 0
    sidecar = project_root / "content" / "structures" / f"{resource}.structure.json"
    if not sidecar.is_file():
        return 0
    try:
        metadata = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return 0
    anchors = metadata.get("anchors", []) if isinstance(metadata, dict) else []
    return sum(
        1 for anchor in anchors
        if isinstance(anchor, dict) and anchor.get("type") == "npc_position"
    )


def _building_indoor_npc_capacity(
    project_root: Path, settings: dict[str, object],
) -> int:
    if settings.get("citizen_placement_allowed") is not True:
        return 0
    interiors = settings.get("interiors", [])
    routes = settings.get("door_routes", {})
    if not isinstance(interiors, list) or not isinstance(routes, dict):
        return 0
    reachable = {"exterior"}
    edges: list[tuple[str, str]] = []
    for source, target in routes.items():
        if not isinstance(source, str) or ":" not in source or not isinstance(target, dict):
            continue
        target_space = target.get("space")
        if isinstance(target_space, str):
            edges.append((source.split(":", 1)[0], target_space))
    changed = True
    while changed:
        changed = False
        for left, right in edges:
            if left in reachable and right not in reachable:
                reachable.add(right)
                changed = True
            if right in reachable and left not in reachable:
                reachable.add(left)
                changed = True
    explicit_slots = sum(
        _metadata_npc_slot_count(project_root, interior.get("structure"))
        for interior in interiors
        if isinstance(interior, dict) and interior.get("key") in reachable
    )
    # Automatic indoor NPCs live in the separately instanced building world.
    # An exterior-only building therefore has no valid indoor capacity even if
    # citizen placement is enabled for a future interior connection.
    return explicit_slots


def _town_indoor_npc_capacity(
    root: Path, data: dict[str, object], compiled_layout: dict[str, object],
    project_root: Path | None = None,
    resolved_auto_npcs: dict[str, object] | None = None,
) -> dict[str, object]:
    """Count indoor NPC positions in buildings actually selected for this town."""
    resolved_auto_npcs = resolved_auto_npcs or _resolved_town_auto_npcs(root, data)
    placements = resolved_auto_npcs.get("placements", [])
    requested = sum(
        1 for placement in placements
        if isinstance(placement, dict) and placement.get("placement_area") == "indoor"
    )
    project_root = project_root or root / PROJECT_ROOT
    settings_path = project_root / "content" / "catalogs" / "building-settings.json"
    if not settings_path.is_file() and requested == 0:
        return {"requested": 0, "available": 0, "buildings": []}
    try:
        document = json.loads(settings_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ModBuildError(f"건물 설정을 읽을 수 없습니다: {settings_path}") from error
    buildings = document.get("buildings", {}) if isinstance(document, dict) else {}
    if not isinstance(buildings, dict):
        raise ModBuildError(f"건물 설정 객체가 필요합니다: {settings_path}")

    placed: list[dict[str, object]] = []
    for house in compiled_layout.get("houses", []):
        if not isinstance(house, dict):
            continue
        base, roof = house.get("base"), house.get("roof")
        building_id = f"cobbleventure:houses/{base}_{roof}"
        setting = buildings.get(building_id)
        capacity = _building_indoor_npc_capacity(project_root, setting) if isinstance(setting, dict) else 0
        placed.append({"building": str(house.get("id", building_id)), "structure": building_id, "capacity": capacity})

    facility_structures = {
        str(facility.get("id")): facility.get("structure")
        for facility in data.get("structure_profile", {}).get("facility_placements", [])
        if isinstance(facility, dict) and isinstance(facility.get("id"), str)
    } if isinstance(data.get("structure_profile"), dict) else {}
    facilities = compiled_layout.get("facilities", {})
    for facility_id, facility_plot in facilities.items() if isinstance(facilities, dict) else []:
        structure_id = facility_structures.get(str(facility_id))
        if structure_id is None and isinstance(facility_plot, dict):
            structure_id = facility_plot.get("structure")
        setting = buildings.get(structure_id)
        if not isinstance(structure_id, str) or not isinstance(setting, dict):
            continue
        capacity = _building_indoor_npc_capacity(project_root, setting)
        placed.append({"building": str(facility_id), "structure": structure_id, "capacity": capacity})

    available = sum(int(item["capacity"]) for item in placed)
    return {
        "requested": requested,
        "available": available,
        "buildings": placed,
    }


def _assign_town_npc_buildings(
    resolved_auto_npcs: dict[str, object], capacity: dict[str, object],
) -> dict[str, object]:
    """Attach deterministic building and slot targets to indoor NPC placements."""
    available_slots: list[tuple[str, int]] = []
    for building in capacity.get("buildings", []):
        if not isinstance(building, dict) or not isinstance(building.get("building"), str):
            continue
        for slot in range(max(0, int(building.get("capacity", 0)))):
            available_slots.append((building["building"], slot))
    assigned: list[dict[str, object]] = []
    indoor_index = 0
    for placement in resolved_auto_npcs.get("placements", []):
        if not isinstance(placement, dict):
            continue
        item = copy.deepcopy(placement)
        if item.get("placement_area") == "indoor":
            if indoor_index >= len(available_slots):
                raise ModBuildError("검증된 마을 실내 NPC 자리를 배정할 수 없습니다.")
            item["building"], item["slot"] = available_slots[indoor_index]
            indoor_index += 1
        assigned.append(item)
    return {**copy.deepcopy(resolved_auto_npcs), "placements": assigned}


def _settlement_world_levels(root: Path) -> dict[str, int]:
    """Resolve each town's representative level from its world-map anchor."""
    levels_by_settlement: dict[str, int] = {}
    source_dir = _inside(root, root / HEX_WORLD_CONFIG_DIR, "육각 월드 설정 디렉터리")
    for source_path in sorted(source_dir.rglob("*.json")) if source_dir.is_dir() else []:
        try:
            world = json.loads(source_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ModBuildError(f"육각 월드 설정을 읽을 수 없습니다: {source_path}") from error
        overrides = {
            (entry.get("q"), entry.get("r")): entry.get("average_level")
            for entry in world.get("level_overrides", [])
            if isinstance(entry, dict) and isinstance(entry.get("average_level"), int)
        }
        for node in world.get("settlements", []):
            if not isinstance(node, dict) or not isinstance(node.get("anchor"), dict):
                continue
            settlement_id = node.get("settlement")
            anchor = node["anchor"]
            level = overrides.get((anchor.get("q"), anchor.get("r")))
            if isinstance(settlement_id, str) and isinstance(level, int):
                levels_by_settlement[settlement_id] = max(1, min(100, level))
    return levels_by_settlement


def _resolved_town_auto_npcs(
    root: Path, data: dict[str, object],
    npc_profiles: list[dict[str, object]] | None = None,
    world_levels: dict[str, int] | None = None,
) -> dict[str, object]:
    placement = data.get("npc_placement")
    if not isinstance(placement, dict) or placement.get("auto_place_npcs") is not True:
        return {"ambient": [], "trainers": [], "placements": []}
    npc_profiles = npc_profiles if npc_profiles is not None else _npc_placement_profiles(root)
    world_levels = world_levels if world_levels is not None else _settlement_world_levels(root)
    level = world_levels.get(str(data.get("id")), 5)
    biomes = {
        str(zone.get("biome"))
        for zone in data.get("biome_layout", {}).get("zones", [])
        if isinstance(zone, dict) and isinstance(zone.get("biome"), str)
    }
    ambient = _rank_npc_profiles(
        npc_profiles, classification="ambient", level=level,
        biomes=biomes, target="town",
    )[:max(0, int(placement.get("max_ambient_npcs", 0)))]
    population = placement.get("trainer_population")
    population = population if isinstance(population, dict) else {}
    trainer_limit = max(0, int(population.get("max_active", 0)))
    trainers = _resolved_trainer_ids(
        npc_profiles, population, level=level, biomes=biomes, target="town",
    )[:trainer_limit] if population.get("enabled", trainer_limit > 0) else []
    placement_areas = population.get("placement_areas")
    return {
        "level": level,
        "biomes": sorted(biomes),
        "ambient": ambient,
        "trainers": trainers,
        "placements": _town_npc_placement_records(
            ambient, trainers,
            placement_areas if isinstance(placement_areas, list) else None,
        ),
    }


def _package_settlements(
    root: Path,
    output: Path,
    settlements: list[tuple[Path, dict[str, object]]],
) -> None:
    npc_profiles = _npc_placement_profiles(root)
    world_levels = _settlement_world_levels(root)
    profile_target = _inside(root, output / NPC_PLACEMENT_PROFILE_ENTRY, "생성 NPC 자동 배치 카탈로그")
    profile_target.parent.mkdir(parents=True, exist_ok=True)
    profile_target.write_text(
        json.dumps({"schema_version": 1, "profiles": npc_profiles}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    for relative, data in settlements:
        packaged = copy.deepcopy(data)
        placement = packaged.get("npc_placement") if isinstance(packaged.get("npc_placement"), dict) else {}
        compiled_layout = _compile_town_layout(packaged, root=root)
        resolved_auto_npcs = _resolved_town_auto_npcs(
            root, packaged, npc_profiles, world_levels,
        )
        capacity = _town_indoor_npc_capacity(
            root, packaged, compiled_layout,
            resolved_auto_npcs=resolved_auto_npcs,
        )
        placement["indoor_capacity"] = capacity
        if int(capacity["requested"]) > int(capacity["available"]):
            raise ModBuildError(
                "마을 실내 NPC 수용량을 초과했습니다: "
                f"{packaged.get('id')} / 요청 {capacity['requested']}명 / "
                f"수용 {capacity['available']}명"
            )
        resolved_auto_npcs = _assign_town_npc_buildings(
            resolved_auto_npcs, capacity,
        )
        if placement.get("auto_place_npcs") is True:
            placement["resolved_auto_npcs"] = resolved_auto_npcs
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


def _town_layout_cells(cell_count: int, shape: str = "line_q", custom_cells: tuple[tuple[int, int], ...] = ()) -> tuple[tuple[int, int], ...]:
    if shape == "custom":
        return custom_cells
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
    if cell_count == 19:
        return tuple(
            (q, r)
            for q in range(-2, 3)
            for r in range(max(-2, -q - 2), min(2, -q + 2) + 1)
        )
    return ((0, 0),)


def _town_layout_cell_center(q: int, r: int) -> tuple[float, float]:
    return (
        VILLAGE_TILE_RADIUS * math.sqrt(3.0) * (q + r / 2.0),
        VILLAGE_TILE_RADIUS * 1.5 * r,
    )


def _town_layout_centroid(
    cell_count: int, shape: str = "line_q",
    custom_cells: tuple[tuple[int, int], ...] = (),
) -> tuple[float, float]:
    cells = _town_layout_cells(cell_count, shape, custom_cells)
    centers = [_town_layout_cell_center(q, r) for q, r in cells]
    if not centers:
        return 0.0, 0.0
    return (
        sum(center[0] for center in centers) / len(centers),
        sum(center[1] for center in centers) / len(centers),
    )


def _town_layout_centered_cell_center(
    q: int, r: int, cell_count: int, shape: str = "line_q",
    custom_cells: tuple[tuple[int, int], ...] = (),
) -> tuple[float, float]:
    center_x, center_z = _town_layout_cell_center(q, r)
    centroid_x, centroid_z = _town_layout_centroid(cell_count, shape, custom_cells)
    return center_x - centroid_x, center_z - centroid_z


def _town_layout_exit_point(
    q: int, r: int, cell_count: int, shape: str,
    custom_cells: tuple[tuple[int, int], ...] = (),
) -> tuple[int, int]:
    center_x, center_z = _town_layout_centered_cell_center(
        q, r, cell_count, shape, custom_cells
    )
    raw_center_x, raw_center_z = _town_layout_cell_center(q, r)
    directions = ((1, 0), (0, 1), (-1, 1), (-1, 0), (0, -1), (1, -1))
    available = [(dq, dr) for dq, dr in directions if (q + dq, r + dr) not in set(custom_cells)] or list(directions)
    radial_x, radial_z = ((0.0, 1.0) if math.hypot(center_x, center_z) < 0.001 else (center_x, center_z))
    dq, dr = max(available, key=lambda offset: (_town_layout_cell_center(q + offset[0], r + offset[1])[0] - raw_center_x) * radial_x + (_town_layout_cell_center(q + offset[0], r + offset[1])[1] - raw_center_z) * radial_z)
    neighbor_x, neighbor_z = _town_layout_cell_center(q + dq, r + dr)
    delta_x, delta_z = neighbor_x - raw_center_x, neighbor_z - raw_center_z
    length = math.hypot(delta_x, delta_z)
    direction_x, direction_z = delta_x / length, delta_z / length
    return (
        int(round((center_x + direction_x * 48.0) / 16.0) * 16),
        int(round((center_z + direction_z * 48.0) / 16.0) * 16),
    )


def _town_layout_hub(
    cell_count: int, shape: str = "line_q",
    custom_cells: tuple[tuple[int, int], ...] = (),
) -> tuple[int, int]:
    return 0, 0


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
    x: float, z: float, cell_count: int, shape: str = "line_q", margin: float = 0.0,
    custom_cells: tuple[tuple[int, int], ...] = (),
) -> bool:
    return any(
        _inside_layout_hex(
            x, z,
            *_town_layout_centered_cell_center(q, r, cell_count, shape, custom_cells),
            margin,
        )
        for q, r in _town_layout_cells(cell_count, shape, custom_cells)
    )


def _road_center_inside_town_layout(
    x: float, z: float, cell_count: int, shape: str = "line_q",
    margin: float = 0.0, custom_cells: tuple[tuple[int, int], ...] = (),
) -> bool:
    if margin <= 0.0:
        return _inside_town_layout(
            x, z, cell_count, shape, 0.0, custom_cells
        )
    # Apply clearance only to the outside of the complete multi-hex footprint.
    # Shrinking every individual hex cuts roads at shared tile boundaries and
    # was the main reason the built layout differed from the editor preview.
    diagonal = margin / math.sqrt(2.0)
    offsets = (
        (0.0, 0.0), (margin, 0.0), (-margin, 0.0),
        (0.0, margin), (0.0, -margin),
        (diagonal, diagonal), (diagonal, -diagonal),
        (-diagonal, diagonal), (-diagonal, -diagonal),
    )
    return all(
        _inside_town_layout(
            x + offset_x, z + offset_z, cell_count, shape, 0.0, custom_cells
        )
        for offset_x, offset_z in offsets
    )


def _plot_inside_town_layout(
    plot: dict[str, object], cell_count: int, shape: str = "line_q",
    custom_cells: tuple[tuple[int, int], ...] = (),
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
    return all(_inside_town_layout(px, pz, cell_count, shape, 4.0, custom_cells) for px, pz in samples)


def _plots_intersect(a: dict[str, object], b: dict[str, object], margin: float) -> bool:
    return (
        float(a["x"]) - margin < float(b["x"]) + int(b["width"])
        and float(a["x"]) + int(a["width"]) + margin > float(b["x"])
        and float(a["z"]) - margin < float(b["z"]) + int(b["depth"])
        and float(a["z"]) + int(a["depth"]) + margin > float(b["z"])
    )


def _plot_intersects_road(
    plot: dict[str, object], road: dict[str, int], road_width: int, margin: float
) -> bool:
    road_rect: dict[str, object] = {
        "x": min(road["x1"], road["x2"]) - road_width / 2.0,
        "z": min(road["z1"], road["z2"]) - road_width / 2.0,
        "width": abs(road["x2"] - road["x1"]) + road_width,
        "depth": abs(road["z2"] - road["z1"]) + road_width,
    }
    return _plots_intersect(plot, road_rect, margin)


DEFAULT_FACILITY_STRUCTURES = {
    "pokemon_center": "bca:default/one_off/pokecenter",
    "pokemart": "bca:default/one_off/structure_pokemart",
    "department_store": "cobbleventure:facilities/department_store",
}


def _facility_structure(
    data: dict[str, object], facility_type: str, root: Path | None = None,
) -> str:
    profile = data.get("structure_profile")
    if isinstance(profile, dict):
        overrides = profile.get("facility_structures")
        if isinstance(overrides, dict) and isinstance(overrides.get(facility_type), str):
            return str(overrides[facility_type])
    if root is not None:
        settings_path = root / BUILDING_SETTINGS_SOURCE
        if settings_path.is_file():
            settings = json.loads(settings_path.read_text(encoding="utf-8"))
            defaults = settings.get("facility_defaults")
            if isinstance(defaults, dict) and isinstance(defaults.get(facility_type), str):
                return str(defaults[facility_type])
    return DEFAULT_FACILITY_STRUCTURES[facility_type]


def _managed_structure_size(root: Path | None, structure: str) -> tuple[int, int] | None:
    if root is None or not structure.startswith("cobbleventure:"):
        return None
    relative = structure.split(":", 1)[1]
    path = root / CONTENT_ROOT / "structures" / f"{relative}.nbt"
    if not path.is_file():
        return None
    raw = path.read_bytes()
    if raw.startswith(b"\x1f\x8b"):
        raw = gzip.decompress(raw)
    stream = io.BytesIO(raw)

    def read_string() -> str:
        length_data = stream.read(2)
        if len(length_data) != 2:
            raise ModBuildError(f"NBT 문자열이 손상되었습니다: {structure}")
        length = struct.unpack(">H", length_data)[0]
        return stream.read(length).decode("utf-8")

    def skip_payload(tag_type: int) -> None:
        fixed = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
        if tag_type in fixed:
            stream.seek(fixed[tag_type], io.SEEK_CUR)
        elif tag_type == 7:
            stream.seek(struct.unpack(">i", stream.read(4))[0], io.SEEK_CUR)
        elif tag_type == 8:
            read_string()
        elif tag_type == 9:
            child_type = stream.read(1)[0]
            for _ in range(struct.unpack(">i", stream.read(4))[0]):
                skip_payload(child_type)
        elif tag_type == 10:
            while True:
                child_type = stream.read(1)[0]
                if child_type == 0:
                    break
                read_string()
                skip_payload(child_type)
        elif tag_type in {11, 12}:
            length = struct.unpack(">i", stream.read(4))[0]
            stream.seek(length * (4 if tag_type == 11 else 8), io.SEEK_CUR)
        else:
            raise ModBuildError(f"지원하지 않는 NBT 태그입니다: {tag_type}")

    if stream.read(1) != b"\x0a":
        raise ModBuildError(f"NBT 루트가 Compound가 아닙니다: {structure}")
    read_string()
    while True:
        tag_data = stream.read(1)
        if not tag_data:
            raise ModBuildError(f"NBT size 태그를 찾을 수 없습니다: {structure}")
        tag_type = tag_data[0]
        if tag_type == 0:
            raise ModBuildError(f"NBT size 태그를 찾을 수 없습니다: {structure}")
        name = read_string()
        if name == "size" and tag_type == 9:
            element_type = stream.read(1)
            length = struct.unpack(">i", stream.read(4))[0]
            if element_type != b"\x03" or length != 3:
                raise ModBuildError(f"NBT size 태그가 올바르지 않습니다: {structure}")
            width, _height, depth = struct.unpack(">iii", stream.read(12))
            return width, depth
        skip_payload(tag_type)


def _compiled_facility_specs(
    data: dict[str, object], root: Path | None = None,
) -> list[tuple[str, int, int, str]]:
    profile = data.get("structure_profile")
    if not isinstance(profile, dict):
        return []
    starter = str(data.get("id", "")).endswith("/starter_town") \
        or profile.get("village_preset") == AUTHORED_STARTER_PRESET
    specs: list[tuple[str, int, int, str]] = []
    if bool(profile.get("pokemon_center_enabled", not starter)):
        structure = _facility_structure(data, "pokemon_center", root)
        width, depth = _managed_structure_size(root, structure) or (22, 23)
        specs.append(("facility_pokemon_center", width, depth, structure))
    commercial = str(profile.get("commercial_center", "none" if starter else "pokemart"))
    if commercial == "preset":
        commercial = "pokemart"
    if commercial == "pokemart":
        structure = _facility_structure(data, "pokemart", root)
        width, depth = _managed_structure_size(root, structure) or (23, 22)
        specs.append(("facility_pokemart", width, depth, structure))
    elif commercial == "department_store":
        structure = _facility_structure(data, "department_store", root)
        width, depth = _managed_structure_size(root, structure) or (42, 32)
        specs.append(("facility_department_store", width, depth, structure))
    gym = profile.get("gym")
    if isinstance(gym, dict) and gym.get("enabled") is True:
        specs.append(("gym_building", 25, 26, str(gym.get("structure", ""))))
    for facility in profile.get("facility_placements", []):
        if not isinstance(facility, dict):
            continue
        facility_id = str(facility.get("id", ""))
        facility_type = str(facility.get("facility_type", ""))
        canonical_id = {
            "pokemon_center": "facility_pokemon_center",
            "pokemart": "facility_pokemart",
            "department_store": "facility_department_store",
        }.get(facility_type)
        # The civic switches above are the single source of truth for these
        # facilities. Older editor versions also persisted a numbered direct
        # placement for the same building, which produced two buildings in the
        # compiled layout even though the preview only contained one.
        if canonical_id is not None and any(
            existing[0] == canonical_id for existing in specs
        ):
            continue
        if not facility_id or any(existing[0] == facility_id for existing in specs):
            continue
        footprint = facility.get("footprint")
        width = int(footprint.get("width", 16)) if isinstance(footprint, dict) else 16
        depth = int(footprint.get("depth", 16)) if isinstance(footprint, dict) else 16
        specs.append((facility_id, width, depth, str(facility.get("structure", ""))))
    return specs


def _facility_entrance_facing(identifier: str) -> str:
    if identifier == "facility_department_store":
        return "north"
    if identifier == "facility_pokemon_center":
        return "west"
    if identifier == "facility_pokemart":
        return "east"
    if "gym" in identifier:
        return "west"
    return "north"


def _rotated_structure_point(
    x: int, z: int, width: int, depth: int, rotation: str
) -> tuple[int, int]:
    if rotation == "clockwise_90":
        return depth - 1 - z, x
    if rotation == "clockwise_180":
        return width - 1 - x, depth - 1 - z
    if rotation == "counterclockwise_90":
        return z, width - 1 - x
    return x, z


def _house_door_approach(
    plot: dict[str, object], root: Path | None = None
) -> tuple[int, int] | None:
    base = plot.get("base")
    roof = plot.get("roof")
    if not isinstance(base, str) or not isinstance(roof, str):
        return None
    width = int(plot["width"])
    depth = int(plot["depth"])
    approach = [width // 2, 1, -1]
    metadata_path = (root or Path()) / HOUSE_STRUCTURE_SOURCE_DIR / f"{base}_{roof}.structure.json"
    if metadata_path.is_file():
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        anchors = metadata.get("anchors")
        if isinstance(anchors, list):
            door = next((
                anchor for anchor in anchors
                if isinstance(anchor, dict) and anchor.get("type") == "door"
            ), None)
            if isinstance(door, dict):
                configured = door.get("safe_spawn", door.get("position"))
                if isinstance(configured, list) and len(configured) == 3:
                    approach = configured
    local_x, local_z = _rotated_structure_point(
        int(approach[0]), int(approach[2]), width, depth,
        str(plot.get("rotation", "none")),
    )
    return (
        math.floor(float(plot["x"]) + 0.5) + local_x,
        math.floor(float(plot["z"]) + 0.5) + local_z,
    )


def _structure_door_approach(
    plot: dict[str, object], root: Path | None = None,
) -> tuple[int, int] | None:
    structure = plot.get("structure")
    if root is None or not isinstance(structure, str) or not structure.startswith("cobbleventure:"):
        return None
    relative = structure.split(":", 1)[1]
    metadata_path = root / CONTENT_ROOT / "structures" / f"{relative}.structure.json"
    if not metadata_path.is_file():
        return None
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    anchors = metadata.get("anchors") if isinstance(metadata, dict) else None
    door = next((
        anchor for anchor in anchors or []
        if isinstance(anchor, dict) and anchor.get("type") == "door"
    ), None)
    if not isinstance(door, dict):
        return None
    configured = door.get("safe_spawn", door.get("position"))
    if not isinstance(configured, list) or len(configured) != 3:
        return None
    local_x, local_z = _rotated_structure_point(
        int(configured[0]), int(configured[2]),
        int(plot["width"]), int(plot["depth"]),
        str(plot.get("rotation", "none")),
    )
    return (
        math.floor(float(plot["x"]) + 0.5) + local_x,
        math.floor(float(plot["z"]) + 0.5) + local_z,
    )


def _plot_entrance(
    plot: dict[str, object], root: Path | None = None
) -> tuple[int, int]:
    structure_approach = _structure_door_approach(plot, root)
    if structure_approach is not None:
        return structure_approach
    if str(plot["id"]).startswith("house_"):
        door_approach = _house_door_approach(plot, root)
        if door_approach is not None:
            return door_approach
    x = math.floor(float(plot["x"]) + 0.5)
    z = math.floor(float(plot["z"]) + 0.5)
    width = int(plot["width"])
    plot_depth = int(plot["depth"])
    facing = str(plot["entrance_facing"])
    if plot["id"] == "facility_pokemon_center":
        return x - 1, z + min(10, plot_depth - 1)
    if plot["id"] == "facility_pokemart":
        return x + width, z + min(15, plot_depth - 1)
    if "gym" in str(plot["id"]):
        return x + width // 2, z + plot_depth
    return {
        "north": (x + width // 2, z - 1),
        "east": (x + width, z + plot_depth // 2),
        "south": (x + width // 2, z + plot_depth),
        "west": (
            x - 1,
            z + (min(10, plot_depth - 1) if "gym" in str(plot["id"])
                 else plot_depth // 2),
        ),
    }[facing]


def _plot_entrances(
    plot: dict[str, object], root: Path | None = None
) -> list[tuple[str, int, int]]:
    primary_x, primary_z = _plot_entrance(plot, root)
    primary_facing = (_structure_door_safe_side(plot, root)
                      if "gym" in str(plot["id"]) else None) or str(plot["entrance_facing"])
    primary_x, primary_z = _project_entrance_outside_nbt(
        plot, primary_facing, primary_x, primary_z
    )
    if "gym" in str(plot["id"]):
        return [(primary_facing, primary_x, primary_z)]
    if str(plot["id"]) != "facility_department_store":
        return [(str(plot["entrance_facing"]), primary_x, primary_z)]
    x = math.floor(float(plot["x"]) + 0.5)
    z = math.floor(float(plot["z"]) + 0.5)
    width = int(plot["width"])
    plaza_z = z + min(19, int(plot["depth"]) - 1)
    return [
        ("north", x + width // 2, z - 1),
        ("west", x - 1, plaza_z),
        ("east", x + width, plaza_z),
    ]


def _project_entrance_outside_nbt(
    plot: dict[str, object], facing: str, entrance_x: int, entrance_z: int
) -> tuple[int, int]:
    min_x = math.floor(float(plot["x"]) + 0.5)
    min_z = math.floor(float(plot["z"]) + 0.5)
    rotation = str(plot.get("rotation", "none"))
    quarter_turn = rotation in {"clockwise_90", "counterclockwise_90"}
    placed_width = int(plot["depth"] if quarter_turn else plot["width"])
    placed_depth = int(plot["width"] if quarter_turn else plot["depth"])
    max_x = min_x + placed_width - 1
    max_z = min_z + placed_depth - 1
    if not (min_x <= entrance_x <= max_x and min_z <= entrance_z <= max_z):
        return entrance_x, entrance_z
    if facing == "east":
        return max_x + 1, entrance_z
    if facing == "south":
        return entrance_x, max_z + 1
    if facing == "west":
        return min_x - 1, entrance_z
    return entrance_x, min_z - 1


def _structure_door_safe_side(
    plot: dict[str, object], root: Path | None = None
) -> str | None:
    structure = plot.get("structure")
    if not isinstance(structure, str) or ":" not in structure:
        return None
    _, path = structure.split(":", 1)
    metadata_path = (root or Path()) / CONTENT_ROOT / "structures" / f"{path}.structure.json"
    if not metadata_path.is_file():
        return None
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    anchors = metadata.get("anchors") if isinstance(metadata, dict) else None
    door = next((
        anchor for anchor in anchors or []
        if isinstance(anchor, dict) and anchor.get("type") == "door"
    ), None)
    if not isinstance(door, dict) or door.get("safe_side") not in {
        "north", "east", "south", "west"
    }:
        return None
    directions = ["north", "east", "south", "west"]
    turns = {
        "none": 0,
        "clockwise_90": 1,
        "clockwise_180": 2,
        "counterclockwise_90": 3,
    }.get(str(plot.get("rotation", "none")), 0)
    return directions[(directions.index(str(door["safe_side"])) + turns) % 4]


def _compile_town_layout_attempt(
    data: dict[str, object], seed_override: int | None = None,
    root: Path | None = None,
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
        aliases = {"compact": "one_story", "wide": "one_story"} if key == "bases" else {}
        migrated = (aliases.get(str(value), str(value)) for value in values)
        selected = list(dict.fromkeys(value for value in migrated if value in allowed))
        return selected or fallback
    house_bases = palette_values("bases", HOUSE_BASES, list(HOUSE_BASES))
    house_roofs = palette_values("roofs", HOUSE_ROOFS, sorted(HOUSE_ROOFS))
    house_roof_colors = palette_values(
        "roof_colors", HOUSE_ROOF_BLOCKS, ["red", "blue", "green", "brown"]
    )
    seed = int(seed_override if seed_override is not None else generation.get("seed", 1))
    depth = max(1, min(7, int(generation.get("depth", 3))))
    density_id = str(generation.get("building_density", "packed"))
    density = BUILDING_DENSITY_PROFILES.get(
        density_id, BUILDING_DENSITY_PROFILES["packed"]
    )
    road = profile.get("road_profile")
    road = road if isinstance(road, dict) else {}
    road_width = int(road.get("width", 7))
    shape = str(profile.get("layout_shape", "branching"))
    road_template = str(profile.get("road_layout_template", "cross"))
    cell_count = int(data.get("town_radius_cells", 7))
    if cell_count not in (1, 3, 5, 7, 19):
        cell_count = 1
    footprint_shape = str(data.get("town_footprint_shape", "line_q"))
    custom_cells = tuple(
        (int(cell["q"]), int(cell["r"]))
        for cell in data.get("town_footprint_cells", [])
        if isinstance(cell, dict) and isinstance(cell.get("q"), int) and isinstance(cell.get("r"), int)
    ) if footprint_shape == "custom" else ()
    road_exits = tuple(
        (int(cell["q"]), int(cell["r"]))
        for cell in data.get("town_road_exits", [])
        if isinstance(cell, dict) and isinstance(cell.get("q"), int) and isinstance(cell.get("r"), int)
    ) if footprint_shape == "custom" else ()
    if footprint_shape == "custom" and (len(set(custom_cells)) != cell_count or not road_exits):
        raise ModBuildError(f"커스텀 마을은 {cell_count}개의 고유 타일과 하나 이상의 외부 출구가 필요합니다: {data.get('id')}")
    random = _LayoutRandom(seed)
    directions = ((0, -1), (1, 0), (0, 1), (-1, 0))
    center_pattern, initial = _town_layout_center_pattern(shape, seed)
    hub_x, hub_z = _town_layout_hub(cell_count, footprint_shape, custom_cells)
    queue = ([(hub_x, hub_z, direction, 0) for direction in initial]
             if road_template == "cross" else [])
    occupied = {(hub_x // 16, hub_z // 16)}
    roads: list[dict[str, int]] = []

    # 도로 템플릿은 타일 성장 형태(layout_shape)와 별개다. 원하는 축의
    # 16블록 격자를 훑고 실제 육각 타일 내부에 들어오는 연속 구간만 쓴다.
    # 이 방식이면 커스텀 타일이나 비정형 마을에서도 도로가 외부로 새지 않는다.
    layout_centers = [
        _town_layout_centered_cell_center(q, r, cell_count, footprint_shape, custom_cells)
        for q, r in _town_layout_cells(cell_count, footprint_shape, custom_cells)
    ]
    scan_min_x = math.floor((min(center[0] for center in layout_centers) - VILLAGE_TILE_RADIUS) / 16) * 16
    scan_max_x = math.ceil((max(center[0] for center in layout_centers) + VILLAGE_TILE_RADIUS) / 16) * 16
    scan_min_z = math.floor((min(center[1] for center in layout_centers) - VILLAGE_TILE_RADIUS) / 16) * 16
    scan_max_z = math.ceil((max(center[1] for center in layout_centers) + VILLAGE_TILE_RADIUS) / 16) * 16

    def append_clipped_template_line(axis: str, fixed: int) -> None:
        start = scan_min_x if axis == "x" else scan_min_z
        end = scan_max_x if axis == "x" else scan_max_z
        run: list[tuple[int, int]] = []

        def flush() -> None:
            if len(run) >= 2:
                roads.append({
                    "x1": run[0][0], "z1": run[0][1],
                    "x2": run[-1][0], "z2": run[-1][1],
                })
            run.clear()

        for coordinate in range(start, end + 1, 16):
            x, z = (coordinate, fixed) if axis == "x" else (fixed, coordinate)
            if _road_center_inside_town_layout(
                x, z, cell_count, footprint_shape, 8.0, custom_cells
            ):
                run.append((x, z))
            else:
                flush()
        flush()

    if road_template == "grid":
        # 井자형: 평행한 세로·가로 도로 두 쌍.
        for offset in (-32, 32):
            append_clipped_template_line("z", hub_x + offset)
            append_clipped_template_line("x", hub_z + offset)
    elif road_template == "spine":
        # 간선형: 마을의 긴 축을 주도로로 삼고 세 개의 지선을 둔다.
        x_span = scan_max_x - scan_min_x
        z_span = scan_max_z - scan_min_z
        if x_span >= z_span:
            append_clipped_template_line("x", hub_z)
            for offset in (-32, 0, 32):
                append_clipped_template_line("z", hub_x + offset)
        else:
            append_clipped_template_line("z", hub_x)
            for offset in (-32, 0, 32):
                append_clipped_template_line("x", hub_z + offset)
    elif road_template == "ring":
        # 환상형: 규모에 비례하는 사각 순환로. 육각 외곽에서는 자동 절단된다.
        ring_x = max(32, int(((scan_max_x - scan_min_x) * 0.25) // 16) * 16)
        ring_z = max(32, int(((scan_max_z - scan_min_z) * 0.25) // 16) * 16)
        for offset in (-ring_z, ring_z):
            append_clipped_template_line("x", hub_z + offset)
        for offset in (-ring_x, ring_x):
            append_clipped_template_line("z", hub_x + offset)
    maximum_roads = min(36, 6 + depth * 5) if cell_count == 19 else min(20, 3 + depth * 3)
    while queue and len(roads) < maximum_roads:
        start_x, start_z, direction, branch_depth = queue.pop(0)
        vector_x, vector_z = directions[direction]
        # 중심에서 뻗는 첫 네 갈래는 길이를 맞춰 한 축으로만 길어지는
        # 레이아웃을 방지한다. 이후 분기만 시드에 따라 길이를 달리한다.
        cells = 2 if branch_depth == 0 else 2 + int(random.next_double() * 3.0)
        points: list[tuple[int, int]] = []
        blocked = False
        for step in range(1, cells + 1):
            cell_x = start_x // 16 + vector_x * step
            cell_z = start_z // 16 + vector_z * step
            point = (cell_x * 16, cell_z * 16)
            if not _road_center_inside_town_layout(
                point[0], point[1], cell_count, footprint_shape, 8.0, custom_cells
            ):
                break
            if (cell_x, cell_z) in occupied and step > 1:
                blocked = True
                break
            points.append(point)
        if blocked or len(points) < 2:
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
            for index, segment in enumerate(roads):
                inside_x = min(segment["x1"], segment["x2"]) <= x1 <= max(segment["x1"], segment["x2"])
                inside_z = min(segment["z1"], segment["z2"]) <= z1 <= max(segment["z1"], segment["z2"])
                endpoint = (x1, z1) in {
                    (segment["x1"], segment["z1"]), (segment["x2"], segment["z2"])
                }
                if inside_x and inside_z and not endpoint:
                    old_key = (segment["x1"], segment["z1"], segment["x2"], segment["z2"])
                    road_keys.discard(old_key)
                    road_keys.discard((old_key[2], old_key[3], old_key[0], old_key[1]))
                    first = {"x1": segment["x1"], "z1": segment["z1"], "x2": x1, "z2": z1}
                    second = {"x1": x1, "z1": z1, "x2": segment["x2"], "z2": segment["z2"]}
                    roads[index:index + 1] = [first, second]
                    for item in (first, second):
                        road_keys.add((item["x1"], item["z1"], item["x2"], item["z2"]))
                    return
            return
        key = (x1, z1, x2, z2)
        reverse = (x2, z2, x1, z1)
        if key in road_keys or reverse in road_keys:
            return
        roads.append({"x1": x1, "z1": z1, "x2": x2, "z2": z2})
        road_keys.add(key)

    def append_cell_branch_road(
        target_x: int, target_z: int, source_x: int, source_z: int,
        preferred_axis: str | None = None,
    ) -> None:
        # Keep this in lockstep with the editor preview. Reaching an outer hex
        # centre is not enough: continue across that tile so it receives the
        # same usable internal street shown in the preview.
        horizontal = source_z != target_z
        axis = preferred_axis or ("x" if horizontal else "z")

        def available(direction: int) -> int:
            distance = 0
            for step in range(1, 4):
                x = target_x + direction * step * 16 if axis == "x" else target_x
                z = target_z + direction * step * 16 if axis == "z" else target_z
                if not _road_center_inside_town_layout(
                    x, z, cell_count, footprint_shape, 8.0, custom_cells
                ):
                    break
                distance = step * 16
            return distance

        negative = available(-1)
        positive = available(1)
        if negative + positive < 32:
            return
        if axis == "x":
            append_coverage_road(
                target_x - negative, target_z, target_x + positive, target_z
            )
        else:
            append_coverage_road(
                target_x, target_z - negative, target_x, target_z + positive
            )

    coverage_sources = [
        (coordinate_x, coordinate_z)
        for segment in roads
        for coordinate_x, coordinate_z in (
            (segment["x1"], segment["z1"]), (segment["x2"], segment["z2"])
        )
        if (coordinate_x, coordinate_z) != (hub_x, hub_z)
    ]
    for q, r in _town_layout_cells(cell_count, footprint_shape, custom_cells):
        center_x, center_z = _town_layout_centered_cell_center(
            q, r, cell_count, footprint_shape, custom_cells
        )
        target_x = int(round(center_x / 16.0) * 16)
        target_z = int(round(center_z / 16.0) * 16)
        if (target_x, target_z) == (hub_x, hub_z):
            continue
        if road_template != "cross":
            template_points = [
                (
                    min(max(target_x, min(segment["x1"], segment["x2"])), max(segment["x1"], segment["x2"])),
                    min(max(target_z, min(segment["z1"], segment["z2"])), max(segment["z1"], segment["z2"])),
                    "z" if segment["z1"] == segment["z2"] else "x",
                )
                for segment in roads
            ]
            nearest_point = min(
                template_points,
                key=lambda point: (target_x - point[0]) ** 2 + (target_z - point[1]) ** 2,
            )
            nearest_template_distance = (
                (target_x - nearest_point[0]) ** 2
                + (target_z - nearest_point[1]) ** 2
            )
            if nearest_template_distance <= 40 ** 2:
                # 템플릿 도로가 이미 타일 내부를 통과하면 먼 끝점에서 L자로
                # 우회하지 않고 가장 가까운 지점에서 짧은 지선만 연결한다.
                append_coverage_road(
                    nearest_point[0], nearest_point[1], target_x, target_z
                )
                append_cell_branch_road(
                    target_x, target_z, nearest_point[0], nearest_point[1],
                    nearest_point[2],
                )
                coverage_sources.append((target_x, target_z))
                continue
        source_x, source_z = min(
            coverage_sources or {(hub_x, hub_z)},
            key=lambda point: abs(point[0] - target_x) + abs(point[1] - target_z),
        )
        append_coverage_road(source_x, source_z, target_x, source_z)
        append_coverage_road(target_x, source_z, target_x, target_z)
        append_cell_branch_road(target_x, target_z, source_x, source_z)
        coverage_sources.append((target_x, target_z))
    for q, r in road_exits:
        target_x, target_z = _town_layout_exit_point(
            q, r, cell_count, footprint_shape, custom_cells
        )
        source_x, source_z = min(
            coverage_sources or {(hub_x, hub_z)},
            key=lambda point: abs(point[0] - target_x) + abs(point[1] - target_z),
        )
        append_coverage_road(source_x, source_z, target_x, source_z)
        append_coverage_road(target_x, source_z, target_x, target_z)
        coverage_sources.append((target_x, target_z))

    slots = [
        (road_index, ratio, side)
        for road_index in range(len(roads))
        for ratio in density["ratios"]  # type: ignore[union-attr]
        for side in (-1, 1)
    ]
    plots: list[dict[str, object]] = []
    blocked_road_indices: set[int] = set()

    def place_plot(
        identifier: str, width: int, plot_depth: int, attempts: int,
        orient_entrance_to_road: bool = False,
        fixed_entrance_facing: str | None = None,
        balance_cells: bool = False,
    ) -> dict[str, object] | None:
        # 난수로 같은 후보를 반복 추첨하지 않고 모든 도로 후보를 한 번씩
        # 순회한다. 큰 필수 시설도 유효한 부지가 하나라도 있으면 놓인다.
        start_slot = int(random.next_double() * len(slots)) if slots else 0
        valid_candidates: list[tuple[int, dict[str, object]]] = []
        for attempt in range(min(attempts, max(1, len(slots)))):
            slot_index = (start_slot + attempt) % len(slots)
            road_index, ratio, side = slots[slot_index]
            if road_index in blocked_road_indices:
                continue
            segment = roads[road_index]
            horizontal = segment["z1"] == segment["z2"]
            along_x = segment["x1"] + (segment["x2"] - segment["x1"]) * ratio
            along_z = segment["z1"] + (segment["z2"] - segment["z1"]) * ratio
            road_facing = (
                ("south" if side < 0 else "north") if horizontal
                else ("east" if side < 0 else "west")
            )
            if fixed_entrance_facing is not None and road_facing != fixed_entrance_facing:
                continue
            # The NBT edge meets the road edge. Do not add a decorative buffer:
            # structure templates often contain their own yard/setback.
            distance = road_width / 2.0 + (plot_depth if horizontal else width) / 2.0
            center_x = along_x + (0.0 if horizontal else side * distance)
            center_z = along_z + (side * distance if horizontal else 0.0)
            candidate: dict[str, object] = {
                "id": identifier, "x": round(center_x - width / 2.0, 2),
                "z": round(center_z - plot_depth / 2.0, 2),
                "width": width, "depth": plot_depth,
            }
            if orient_entrance_to_road:
                if width != plot_depth:
                    raise ModBuildError("회전 배치 건물은 정사각형 부지여야 합니다.")
                facing = road_facing
                candidate.update({
                    "entrance_facing": facing,
                    "rotation": {
                        "north": "none", "east": "clockwise_90",
                        "south": "clockwise_180", "west": "counterclockwise_90",
                    }[facing],
                    "road_connection": {
                        "x": math.floor(along_x + 0.5),
                        "z": math.floor(along_z + 0.5),
                    },
                })
            elif fixed_entrance_facing is not None:
                candidate.update({
                    "entrance_facing": fixed_entrance_facing,
                    "rotation": "none",
                    "road_connection": {
                        "x": math.floor(along_x + 0.5),
                        "z": math.floor(along_z + 0.5),
                    },
                })
            if not _plot_inside_town_layout(candidate, cell_count, footprint_shape, custom_cells):
                continue
            if any(
                _plots_intersect(candidate, existing, float(density["gap"]))
                for existing in plots
            ):
                continue
            if any(
                other_index != road_index
                and other_index not in blocked_road_indices
                and _plot_intersects_road(candidate, other, road_width + 3, 1.0)
                for other_index, other in enumerate(roads)
            ):
                continue
            if not balance_cells:
                plots.append(candidate)
                return candidate
            valid_candidates.append((attempt, candidate))
        if valid_candidates:
            centers = [
                _town_layout_centered_cell_center(
                    q, r, cell_count, footprint_shape, custom_cells
                )
                for q, r in _town_layout_cells(cell_count, footprint_shape, custom_cells)
            ]

            def plot_cell_index(plot: dict[str, object]) -> int:
                center_x = float(plot["x"]) + int(plot["width"]) / 2.0
                center_z = float(plot["z"]) + int(plot["depth"]) / 2.0
                return min(
                    range(len(centers)),
                    key=lambda index: (
                        (center_x - centers[index][0]) ** 2
                        + (center_z - centers[index][1]) ** 2
                    ),
                )

            occupancy = [0] * len(centers)
            for existing in plots:
                occupancy[plot_cell_index(existing)] += 1
            _, selected = min(
                valid_candidates,
                key=lambda value: (occupancy[plot_cell_index(value[1])], value[0]),
            )
            plots.append(selected)
            return selected
        return None

    def place_grid_plot(
        identifier: str, width: int, plot_depth: int, entrance_facing: str,
        structure: str = "",
    ) -> dict[str, object] | None:
        """도로 슬롯이 부족할 때 타일 합집합 내부의 가장 가까운 부지를 찾는다."""
        centers = [
            _town_layout_centered_cell_center(
                q, r, cell_count, footprint_shape, custom_cells
            )
            for q, r in _town_layout_cells(cell_count, footprint_shape, custom_cells)
        ]

        def segment_distance_squared(cx: float, cz: float, segment: dict[str, int]) -> float:
            x1, x2 = sorted((segment["x1"], segment["x2"]))
            z1, z2 = sorted((segment["z1"], segment["z2"]))
            nearest_x = min(max(cx, x1), x2)
            nearest_z = min(max(cz, z1), z2)
            return (cx - nearest_x) ** 2 + (cz - nearest_z) ** 2

        candidates: list[tuple[tuple[float, ...], float, float]] = []
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
                if not _plot_inside_town_layout(candidate, cell_count, footprint_shape, custom_cells):
                    continue
                if any(_plots_intersect(candidate, existing, 4.0) for existing in plots):
                    continue
                center_x = x + width / 2.0
                center_z = z + plot_depth / 2.0
                intersecting_roads = sum(
                    index not in blocked_road_indices
                    and _plot_intersects_road(candidate, segment, road_width, 0.5)
                    for index, segment in enumerate(roads)
                )
                road_distance = min(
                    (
                        segment_distance_squared(center_x, center_z, segment)
                        for index, segment in enumerate(roads)
                        if index not in blocked_road_indices
                    ),
                    default=0.0,
                )
                if identifier == "facility_department_store":
                    score = (float(intersecting_roads), road_distance)
                else:
                    center_distance = (center_x - hub_x) ** 2 + (center_z - hub_z) ** 2
                    score = (float(intersecting_roads), road_distance, center_distance)
                candidates.append((score, float(x), float(z)))
        if not candidates:
            return None
        _, x, z = min(candidates)
        candidate = {
            "id": identifier, "x": x, "z": z,
            "width": width, "depth": plot_depth,
            "entrance_facing": entrance_facing, "rotation": "none",
            "structure": structure,
        }
        entrance_x, entrance_z = _plot_entrance(candidate, root)
        road_candidates: list[tuple[float, int, int]] = []
        for segment in roads:
            nearest_x = min(max(entrance_x, min(segment["x1"], segment["x2"])), max(segment["x1"], segment["x2"]))
            nearest_z = min(max(entrance_z, min(segment["z1"], segment["z2"])), max(segment["z1"], segment["z2"]))
            road_candidates.append(((entrance_x - nearest_x) ** 2 + (entrance_z - nearest_z) ** 2, nearest_x, nearest_z))
        if road_candidates:
            _, road_x, road_z = min(road_candidates)
            candidate["road_connection"] = {"x": road_x, "z": road_z}
        blocked_road_indices.update(
            index for index, segment in enumerate(roads)
            if _plot_intersects_road(candidate, segment, road_width, 0.5)
        )
        plots.append(candidate)
        return candidate

    def building_access_roads(building: dict[str, object]) -> list[dict[str, object]]:
        result: list[dict[str, object]] = []
        entrances = _plot_entrances(building, root)
        building["entrance"] = {"x": entrances[0][1], "z": entrances[0][2]}
        if len(entrances) > 1:
            building["plaza_entrances"] = [
                {"facing": facing, "x": x, "z": z}
                for facing, x, z in entrances
            ]
        for entrance_index, (facing, entrance_x, entrance_z) in enumerate(entrances):
            connection = building.get("road_connection") if entrance_index == 0 else None
            if not isinstance(connection, dict):
                side_candidates: list[tuple[float, int, int]] = []
                fallback_candidates: list[tuple[float, int, int]] = []
                building_x = float(building["x"])
                building_z = float(building["z"])
                building_max_x = building_x + int(building["width"])
                building_max_z = building_z + int(building["depth"])
                for segment in roads:
                    nearest_x = min(max(entrance_x, min(segment["x1"], segment["x2"])), max(segment["x1"], segment["x2"]))
                    nearest_z = min(max(entrance_z, min(segment["z1"], segment["z2"])), max(segment["z1"], segment["z2"]))
                    candidate = ((entrance_x - nearest_x) ** 2 + (entrance_z - nearest_z) ** 2, nearest_x, nearest_z)
                    fallback_candidates.append(candidate)
                    if ((facing == "north" and nearest_z <= building_z)
                        or (facing == "south" and nearest_z >= building_max_z)
                        or (facing == "west" and nearest_x <= building_x)
                        or (facing == "east" and nearest_x >= building_max_x)):
                        side_candidates.append(candidate)
                candidates = side_candidates or fallback_candidates
                if not candidates:
                    continue
                _, road_x, road_z = min(candidates)
            else:
                road_x, road_z = int(connection["x"]), int(connection["z"])
            if road_x != entrance_x and road_z != entrance_z:
                corner = (road_x, entrance_z) if facing in {"east", "west"} else (entrance_x, road_z)
                result.append({
                    "building": building["id"],
                    "x1": road_x, "z1": road_z, "x2": corner[0], "z2": corner[1],
                })
                road_x, road_z = corner
            result.append({
                "building": building["id"],
                "x1": road_x, "z1": road_z, "x2": entrance_x, "z2": entrance_z,
            })
        return result

    facilities: dict[str, dict[str, object]] = {}
    facility_specs = sorted(
        _compiled_facility_specs(data, root), key=lambda spec: spec[1] * spec[2], reverse=True
    )
    for identifier, width, plot_depth, structure in facility_specs:
        entrance_facing = _facility_entrance_facing(identifier)
        plot = place_plot(
            identifier, width, plot_depth, len(slots) * 4,
            fixed_entrance_facing=entrance_facing,
        )
        if plot is None:
            plot = place_grid_plot(
                identifier, width, plot_depth, entrance_facing, structure
            )
        if plot is None:
            raise TownFacilityPlacementError(data.get("id"), identifier)
        plot["structure"] = structure
        facilities[identifier] = plot
    houses: list[dict[str, object]] = []
    base_house_target = min(36, max(12, 6 + depth * 5)) if cell_count == 19 else min(18, max(4, 3 + depth * 3))
    house_target = (
        max(2, round(base_house_target * float(density["multiplier"])))
        if generation.get("residential_buildings_enabled", True) else 0
    )
    for index in range(house_target):
        base_id = house_bases[int(random.next_double() * len(house_bases))]
        roof_id = house_roofs[int(random.next_double() * len(house_roofs))]
        roof_color = house_roof_colors[int(random.next_double() * len(house_roof_colors))]
        width, _, plot_depth = HOUSE_BASES[base_id]["size"]  # type: ignore[misc]
        plot = place_plot(
            f"house_{index + 1}", width, plot_depth, len(slots) * 2,
            orient_entrance_to_road=True,
            balance_cells=True,
        )
        if plot is not None:
            plot.update({
                "base": base_id,
                "roof": roof_id,
                "roof_color": roof_color,
                "structure": f"cobbleventure:houses/{base_id}_{roof_id}_{roof_color}",
            })
            houses.append(plot)
    access_roads: list[dict[str, object]] = []
    for building in [*facilities.values(), *houses]:
        access_roads.extend(building_access_roads(building))
    visible_roads = [
        road for index, road in enumerate(roads)
        if index not in blocked_road_indices
    ]
    decorations: list[dict[str, object]] = []
    configured_decorations = profile.get("decoration_placements")
    if isinstance(configured_decorations, list):
        decorations = [
            {
                "type": str(item["type"]),
                "x": int(item["x"]),
                "z": int(item["z"]),
                "rotation": str(item.get("rotation", "none")),
            }
            for item in configured_decorations
            if isinstance(item, dict)
        ]

    def try_add_decoration(
        kind: str, x: int, z: int, clearance: int, rotation: str = "none"
    ) -> None:
        footprint = {
            "x": x - clearance, "z": z - clearance,
            "width": clearance * 2 + 1, "depth": clearance * 2 + 1,
        }
        if not _plot_inside_town_layout(
            footprint, cell_count, footprint_shape, custom_cells
        ):
            return
        if any(_plots_intersect(footprint, plot, 1.0) for plot in plots):
            return
        if any(
            _plot_intersects_road(footprint, road, 3, 0.75)
            for road in access_roads
        ):
            return
        minimum_spacing = 8 if kind == "fountain" else 5 if kind in {"street_tree", "flower_bed"} else 4
        if any(
            (x - int(item["x"])) ** 2 + (z - int(item["z"])) ** 2
            < minimum_spacing ** 2
            for item in decorations
        ):
            return
        decorations.append({"type": kind, "x": x, "z": z, "rotation": rotation})

    road_edge = math.ceil(road_width / 2.0)
    for road_index, road in enumerate(visible_roads if configured_decorations is None else []):
        delta_x = int(road["x2"]) - int(road["x1"])
        delta_z = int(road["z2"]) - int(road["z1"])
        length = abs(delta_x) + abs(delta_z)
        if length < 20:
            continue
        direction_x = 0 if delta_x == 0 else (1 if delta_x > 0 else -1)
        direction_z = 0 if delta_z == 0 else (1 if delta_z > 0 else -1)
        perpendicular_x, perpendicular_z = -direction_z, direction_x
        for marker_index, distance in enumerate(range(10, length - 9, 24)):
            side = 1 if (road_index + marker_index) % 2 == 0 else -1
            center_x = int(road["x1"]) + direction_x * distance
            center_z = int(road["z1"]) + direction_z * distance
            decoration_cycle = ("street_lamp", "bench", "flower_bed")
            decoration_kind = decoration_cycle[(road_index + marker_index) % len(decoration_cycle)]
            # The authored bench faces north. Rotate directional street
            # furniture toward the road instead of merely aligning it with the
            # road axis; opposite sides therefore receive opposite rotations.
            facing_x = -perpendicular_x * side
            facing_z = -perpendicular_z * side
            road_facing_rotation = {
                (0, -1): "none",
                (1, 0): "clockwise_90",
                (0, 1): "clockwise_180",
                (-1, 0): "counterclockwise_90",
            }[(facing_x, facing_z)]
            road_rotation = (
                road_facing_rotation if decoration_kind == "bench"
                else "clockwise_90" if direction_z != 0 else "none"
            )
            decoration_x = center_x + perpendicular_x * side * (road_edge + 2)
            decoration_z = center_z + perpendicular_z * side * (road_edge + 2)
            if decoration_kind == "bench":
                def nearest_road_distance_squared(point_x: int, point_z: int) -> int:
                    distances: list[int] = []
                    for item in visible_roads:
                        min_x, max_x = sorted((int(item["x1"]), int(item["x2"])))
                        min_z, max_z = sorted((int(item["z1"]), int(item["z2"])))
                        nearest_x = min(max(point_x, min_x), max_x)
                        nearest_z = min(max(point_z, min_z), max_z)
                        distances.append(
                            (point_x - nearest_x) ** 2 + (point_z - nearest_z) ** 2
                        )
                    return min(distances)
                if nearest_road_distance_squared(
                    decoration_x + facing_x, decoration_z + facing_z
                ) >= nearest_road_distance_squared(decoration_x, decoration_z):
                    # 교차로에서는 다른 도로가 더 가까워져 의자가 엉뚱한
                    # 방향을 바라볼 수 있으므로 해당 후보를 건너뛴다.
                    continue
            try_add_decoration(
                decoration_kind,
                decoration_x,
                decoration_z,
                2 if decoration_kind in {"street_lamp", "bench", "flower_bed"} else 1,
                road_rotation,
            )
            tree_distance = distance + 12
            if tree_distance <= length - 10:
                tree_x = int(road["x1"]) + direction_x * tree_distance
                tree_z = int(road["z1"]) + direction_z * tree_distance
                try_add_decoration(
                    "street_tree",
                    tree_x - perpendicular_x * side * (road_edge + 4),
                    tree_z - perpendicular_z * side * (road_edge + 4),
                    2,
                )
    fountain_offsets = tuple(
        (sign_x * distance, sign_z * distance)
        for distance in range(road_edge + 7, road_edge + 32, 4)
        for sign_x, sign_z in ((1, 1), (-1, 1), (1, -1), (-1, -1))
    )
    for offset_x, offset_z in fountain_offsets if configured_decorations is None else ():
        before = len(decorations)
        try_add_decoration("fountain", hub_x + offset_x, hub_z + offset_z, 3)
        if len(decorations) > before:
            break
    return {
        "schema_version": 1,
        "shape": "hex_tiles",
        "cell_count": cell_count,
        "footprint_shape": footprint_shape,
        "footprint_cells": [{"q": q, "r": r} for q, r in custom_cells],
        "road_exits": [{"q": q, "r": r} for q, r in road_exits],
        "external_exit_points": [
            {"x": _town_layout_exit_point(q, r, cell_count, footprint_shape, custom_cells)[0], "z": _town_layout_exit_point(q, r, cell_count, footprint_shape, custom_cells)[1]}
            for q, r in road_exits
        ],
        "tile_radius_blocks": int(VILLAGE_TILE_RADIUS),
        "hub": {"x": hub_x, "z": hub_z},
        "center_pattern": center_pattern,
        "road_layout_template": road_template,
        "building_density": density_id,
        "roads": visible_roads,
        "access_roads": access_roads,
        "decorations": decorations,
        "facilities": facilities,
        "houses": houses,
    }


def _town_layout_reroll_seed(seed: int, attempt: int) -> int:
    return 1 + ((max(1, seed) - 1 + attempt * TOWN_LAYOUT_REROLL_STEP) % 999_999_999)


def _compile_town_layout(
    data: dict[str, object], root: Path | None = None
) -> dict[str, object]:
    profile = data.get("structure_profile")
    if isinstance(profile, dict) and profile.get("layout_mode") == "manual":
        manual = profile.get("manual_layout")
        if not isinstance(manual, dict):
            raise ModBuildError(f"수동 마을 배치 데이터가 없습니다: {data.get('id')}")
        roads = [dict(road) for road in manual.get("roads", []) if isinstance(road, dict)]
        buildings = [dict(building) for building in manual.get("buildings", []) if isinstance(building, dict)]
        decorations = [dict(item) for item in manual.get("decorations", []) if isinstance(item, dict)]
        cell_count = int(data.get("town_radius_cells", 1))
        footprint_shape = str(data.get("town_footprint_shape", "line_q"))
        custom_cells = tuple(
            (int(cell["q"]), int(cell["r"]))
            for cell in data.get("town_footprint_cells", [])
            if isinstance(cell, dict) and isinstance(cell.get("q"), int) and isinstance(cell.get("r"), int)
        ) if footprint_shape == "custom" else ()
        for building in buildings:
            checked = dict(building)
            if str(building.get("rotation", "none")) in ("clockwise_90", "counterclockwise_90"):
                checked["width"], checked["depth"] = int(building["depth"]), int(building["width"])
            if not _plot_inside_town_layout(checked, cell_count, footprint_shape, custom_cells):
                raise ModBuildError(
                    f"수동 배치 건물이 마을 점유 칸을 벗어났습니다: {data.get('id')} / {building.get('id')}"
                )
        for road in roads:
            x1, z1, x2, z2 = (int(road[key]) for key in ("x1", "z1", "x2", "z2"))
            steps = max(abs(x2 - x1), abs(z2 - z1), 1)
            margin = int(road.get("width", 1)) / 2.0
            if not all(
                _road_center_inside_town_layout(
                    x1 + (x2 - x1) * step / steps,
                    z1 + (z2 - z1) * step / steps,
                    cell_count, footprint_shape, margin, custom_cells,
                )
                for step in range(steps + 1)
            ):
                raise ModBuildError(
                    f"수동 배치 길이 마을 점유 칸을 벗어났습니다: {data.get('id')}"
                )
        houses = [{
            "id": str(building["id"]), "structure": str(building["structure"]),
            "x": int(building["x"]), "z": int(building["z"]),
            "width": int(building["width"]), "depth": int(building["depth"]),
            "rotation": str(building.get("rotation", "none")),
            "road_connection": {"x": int(building["x"]), "z": int(building["z"])},
        } for building in buildings]
        return {
            "schema_version": 1, "shape": "manual", "cell_count": cell_count,
            "footprint_shape": footprint_shape,
            "footprint_cells": list(data.get("town_footprint_cells", [])),
            "road_exits": list(data.get("town_road_exits", [])), "external_exit_points": [],
            "tile_radius_blocks": int(VILLAGE_TILE_RADIUS), "hub": {"x": 0, "z": 0},
            "center_pattern": "manual", "road_layout_template": "manual",
            "building_density": "manual", "roads": roads, "access_roads": [],
            "decorations": [{
                "type": str(item["type"]), "x": int(item["x"]), "z": int(item["z"]),
                "rotation": str(item.get("rotation", "none")),
            } for item in decorations],
            "facilities": {}, "houses": houses, "requested_seed": 0, "resolved_seed": 0,
            "reroll_count": 0, "reroll_limit": 0,
        }
    generation = profile.get("generation_profile") if isinstance(profile, dict) else None
    requested_seed = int(generation.get("seed", 1)) if isinstance(generation, dict) else 1
    last_error: TownFacilityPlacementError | None = None
    for attempt in range(TOWN_LAYOUT_REROLL_LIMIT):
        resolved_seed = _town_layout_reroll_seed(requested_seed, attempt)
        try:
            layout = _compile_town_layout_attempt(data, resolved_seed, root)
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


def _package_hex_worlds(root: Path, output: Path, settlements: list[tuple[Path, dict[str, object]]]) -> None:
    source_dir = _inside(root, root / HEX_WORLD_CONFIG_DIR, "육각 월드 설정 디렉터리")
    if not source_dir.is_dir():
        raise ModBuildError(f"육각 월드 설정 디렉터리가 없습니다: {source_dir}")
    route_presets: dict[str, dict[str, object]] = {}
    npc_profiles = _npc_placement_profiles(root)
    route_source = _inside(root, root / ROUTE_PRESET_CONFIG_DIR, "길 프리셋 디렉터리")
    for route_path in sorted(route_source.rglob("*.json")) if route_source.is_dir() else []:
        try:
            route = json.loads(route_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ModBuildError(f"길 프리셋을 읽을 수 없습니다: {route_path}") from error
        route_id = route.get("id") if isinstance(route, dict) else None
        if not isinstance(route_id, str) or ":" not in route_id:
            raise ModBuildError(f"길 프리셋 ID가 올바르지 않습니다: {route_path}")
        if route_id in route_presets:
            raise ModBuildError(f"중복 길 프리셋 ID입니다: {route_id}")
        automatic = route.get("automatic_npc_placement") if isinstance(route.get("automatic_npc_placement"), dict) else {}
        packaged_route = copy.deepcopy(route)
        if automatic.get("enabled") is True:
            scaling = route.get("level_scaling", {})
            level = round((int(scaling.get("minimum_level", 3)) + int(scaling.get("maximum_level", 7))) / 2) if scaling.get("mode") == "fixed" else max(1, 5 + int(scaling.get("offset", 0)))
            packaged_route["automatic_npc_candidates"] = _resolved_trainer_ids(
                npc_profiles, automatic, level=level, biomes=set(), target="route",
            )[:int(automatic.get("count", 0))]
        route_presets[route_id] = packaged_route
        route_target = _inside(
            root,
            output / GENERATED_ROUTE_PRESET_DIR / route_path.relative_to(route_source),
            "생성 길 프리셋",
        )
        route_target.parent.mkdir(parents=True, exist_ok=True)
        route_target.write_text(json.dumps(packaged_route, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for source_path in sorted(source_dir.rglob("*.json")):
        try:
            data = json.loads(source_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ModBuildError(f"육각 월드 설정을 읽을 수 없습니다: {source_path}") from error
        if not isinstance(data, dict) or data.get("schema_version") not in {1, 2}:
            raise ModBuildError(
                f"육각 월드 설정은 schema_version 1 또는 2여야 합니다: {source_path}"
            )
        presets = {str(value.get("id")): value for _, value in settlements}
        for node in data.get("settlements", []):
            if not isinstance(node, dict): continue
            preset = presets.get(str(node.get("settlement")))
            if not isinstance(preset, dict): continue
            node["town_radius_cells"] = preset.get("town_radius_cells", node.get("town_radius_cells", 1))
            node["town_footprint_shape"] = preset.get("town_footprint_shape", node.get("town_footprint_shape", "line_q"))
            if node["town_footprint_shape"] == "custom":
                node["town_footprint_cells"] = copy.deepcopy(preset.get("town_footprint_cells", []))
                node["town_road_exits"] = copy.deepcopy(preset.get("town_road_exits", []))
            else:
                node.pop("town_footprint_cells", None); node.pop("town_road_exits", None)
        resolved_connections = []
        for connection in data.get("connections", []):
            if not isinstance(connection, dict):
                resolved_connections.append(connection)
                continue
            preset_id = connection.get("route_preset")
            if preset_id is None:
                resolved_connections.append(connection)
                continue
            preset = route_presets.get(str(preset_id))
            if not isinstance(preset, dict):
                raise ModBuildError(f"월드맵이 존재하지 않는 길 프리셋을 참조합니다: {preset_id}")
            corridor = preset.get("corridor") if isinstance(preset.get("corridor"), dict) else {}
            route_type = str(preset.get("route_type", "road"))
            resolved = {
                "surface_style": "natural" if route_type == "trail" else route_type,
                "corridor_width_blocks": corridor.get("width_blocks", 12),
                "edge_noise": corridor.get("edge_noise", 0),
                "pokemon_spawns": copy.deepcopy(preset.get("pokemon_spawns", {})),
                "level_scaling": copy.deepcopy(preset.get("level_scaling", {"mode": "world", "offset": 0})),
                "npc_placements": copy.deepcopy(preset.get("npc_placements", [])),
                "automatic_npc_placement": copy.deepcopy(preset.get("automatic_npc_placement", {"enabled": False, "count": 0})),
                "automatic_npc_candidates": copy.deepcopy(preset.get("automatic_npc_candidates", [])),
            }
            if route_type == "log_bridge":
                resolved["log_bridge_layout"] = copy.deepcopy(preset.get("log_bridge_layout", {"pattern": "straight", "detour_blocks": 18}))
            for source_key, target_key in (("boundary_profile", "boundary_profile"), ("terrain_profile", "terrain_profile")):
                if corridor.get(source_key) is not None:
                    resolved[target_key] = copy.deepcopy(corridor[source_key])
            if preset.get("music_track") is not None:
                resolved["music_track"] = preset["music_track"]
            resolved.update(copy.deepcopy(connection))
            # Once a connection selects a preset, reusable route properties are
            # owned by that preset. Legacy inline fields remain readable in the
            # authoring document but must not shadow later preset edits.
            resolved["surface_style"] = "natural" if route_type == "trail" else route_type
            resolved["corridor_width_blocks"] = corridor.get("width_blocks", 12)
            resolved["edge_noise"] = corridor.get("edge_noise", 0)
            resolved["pokemon_spawns"] = copy.deepcopy(preset.get("pokemon_spawns", {}))
            resolved["level_scaling"] = copy.deepcopy(preset.get("level_scaling", {"mode": "world", "offset": 0}))
            resolved["npc_placements"] = copy.deepcopy(preset.get("npc_placements", []))
            resolved["automatic_npc_placement"] = copy.deepcopy(preset.get("automatic_npc_placement", {"enabled": False, "count": 0}))
            resolved["automatic_npc_candidates"] = copy.deepcopy(preset.get("automatic_npc_candidates", []))
            if route_type == "log_bridge":
                resolved["log_bridge_layout"] = copy.deepcopy(preset.get("log_bridge_layout", {"pattern": "straight", "detour_blocks": 18}))
            else:
                resolved.pop("log_bridge_layout", None)
            for source_key, target_key in (("boundary_profile", "boundary_profile"), ("terrain_profile", "terrain_profile")):
                if corridor.get(source_key) is not None:
                    resolved[target_key] = copy.deepcopy(corridor[source_key])
                else:
                    resolved.pop(target_key, None)
            if preset.get("music_track") is not None:
                resolved["music_track"] = preset["music_track"]
            else:
                resolved.pop("music_track", None)
            resolved_connections.append(resolved)
        data["connections"] = resolved_connections
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
        road_layout_template = profile.get("road_layout_template", "cross")  # type: ignore[union-attr]
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
    if road_layout_template not in {"cross", "grid", "spine", "ring"}:
        raise ModBuildError(f"지원하지 않는 마을 도로 템플릿입니다: {road_layout_template}")
    if not isinstance(road_profile, dict):
        raise ModBuildError("마을 도로 프로필은 객체여야 합니다.")
    road_width = road_profile.get("width", 7)
    road_material = road_profile.get("material", "cobblestone")
    if road_width not in {3, 5, 7, 9}:
        raise ModBuildError(f"지원하지 않는 도로 폭입니다: {road_width}")
    if road_material not in {
        "cobblestone", "stone_bricks", "bricks", "grass_path", "gravel",
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


def _package_building_runtime_data(root: Path, output: Path) -> None:
    settings = _inside(root, root / BUILDING_SETTINGS_SOURCE, "건물 설정")
    if settings.is_file():
        target = _inside(root, output / BUILDING_SETTINGS_ENTRY, "생성 건물 설정")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(settings.read_bytes())

    # Every authored structure is also a runtime resource with the same ID the
    # web editor exposes. Fixed placeholder loops may generate missing
    # defaults, but a copied/custom NBT must never remain editor-only.
    managed_root = _inside(
        root, root / CONTENT_ROOT / "structures", "관리 구조물 원본"
    )
    if managed_root.is_dir():
        for source in sorted(managed_root.rglob("*.nbt")):
            relative = source.relative_to(managed_root)
            target = _inside(
                root, output / "data/cobbleventure/structure" / relative,
                "생성 관리 NBT",
            )
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(_read_authored_structure_nbt(source, "관리 NBT"))

    metadata_root = _inside(
        root, output / STRUCTURE_METADATA_ENTRY_DIR, "생성 구조물 메타데이터"
    )
    if managed_root.is_dir():
        for source in sorted(managed_root.rglob("*.structure.json")):
            target = metadata_root / source.relative_to(managed_root)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())

    house_source = _inside(root, root / HOUSE_STRUCTURE_SOURCE_DIR, "주택 메타데이터 원본")
    if house_source.is_dir():
        for base_id in HOUSE_BASES:
            for roof_id in sorted(HOUSE_ROOFS):
                base_name = f"{base_id}_{roof_id}"
                source = house_source / f"{base_name}.structure.json"
                if source.is_file():
                    metadata = json.loads(source.read_text(encoding="utf-8"))
                else:
                    metadata = {
                        "schema_version": 1,
                        "anchors": [],
                    }
                payload = (
                    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n"
                ).encode("utf-8")
                for roof_color in HOUSE_ROOF_BLOCKS:
                    target = metadata_root / "houses" / f"{base_name}_{roof_color}.structure.json"
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(payload)

    interior_source = _inside(root, root / INTERIOR_STRUCTURE_SOURCE_DIR, "내부 NBT 원본")
    if interior_source.is_dir():
        for source in sorted(interior_source.rglob("*.nbt")):
            relative = source.relative_to(interior_source)
            target = _inside(
                root,
                output / "data/cobbleventure/structure/interiors" / relative,
                "생성 내부 NBT",
            )
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(_read_authored_structure_nbt(source, "내부 NBT"))

    for category, source_relative, label in (
        ("cave_entrance", CAVE_ENTRANCE_STRUCTURE_SOURCE_DIR, "동굴 입구"),
        ("forest_gate", FOREST_ENTRANCE_STRUCTURE_SOURCE_DIR, "숲 입구"),
    ):
        entrance_source = _inside(root, root / source_relative, f"{label} NBT 원본")
        if not entrance_source.is_dir():
            continue
        for source in sorted(entrance_source.rglob("*.nbt")):
            relative = source.relative_to(entrance_source)
            target = _inside(
                root,
                output / "data/cobbleventure/structure" / category / relative,
                f"생성 {label} NBT",
            )
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(_read_authored_structure_nbt(source, f"{label} NBT"))

    for category, source_relative in (
        ("gyms", GYM_STRUCTURE_SOURCE_DIR),
        ("league", LEAGUE_STRUCTURE_SOURCE_DIR),
    ):
        source_dir = _inside(root, root / source_relative, f"{category} NBT 원본")
        if not source_dir.is_dir():
            continue
        for source in sorted(source_dir.glob("*.nbt")):
            target = _inside(
                root,
                output / "data/cobbleventure/structure" / category / source.name,
                f"생성 {category} NBT",
            )
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(_read_authored_structure_nbt(source, f"{category} NBT"))

    gym_catalog = _inside(root, root / GYM_CATALOG_SOURCE, "체육관 카탈로그")
    if gym_catalog.is_file():
        target = _inside(root, output / GYM_CATALOG_ENTRY, "생성 체육관 카탈로그")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps(_compiled_gym_catalog(root, gym_catalog), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    music_catalog = _inside(root, root / MUSIC_CATALOG_SOURCE, "음악 카탈로그")
    if music_catalog.is_file():
        target = _inside(root, output / MUSIC_CATALOG_ENTRY, "생성 음악 카탈로그")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(music_catalog.read_bytes())

    battle_source = _inside(root, root / BATTLE_PRESET_SOURCE_DIR, "배틀 프리셋 원본")
    if battle_source.is_dir():
        for source in sorted(battle_source.rglob("*.json")):
            target = _inside(
                root,
                output / BATTLE_PRESET_ENTRY_DIR / source.relative_to(battle_source),
                "생성 배틀 프리셋",
            )
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())


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
    for decoration_id in TOWN_DECORATION_SIZES:
        generated = _inside(
            root,
            output / "data/cobbleventure/structure/town_decorations" / f"{decoration_id}.nbt",
            "생성 마을 장식",
        )
        generated.parent.mkdir(parents=True, exist_ok=True)
        authored = _inside(
            root,
            root / TOWN_DECORATION_STRUCTURE_SOURCE_DIR / f"{decoration_id}.nbt",
            "마을 장식 NBT 원본",
        )
        generated.write_bytes(
            _read_authored_structure_nbt(authored, "마을 장식 NBT")
            if authored.is_file()
            else build_town_decoration_nbt(decoration_id)
        )
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
            generated.write_bytes(_read_authored_structure_nbt(authored, "시설 NBT"))
        else:
            generated.write_bytes(build_facility_placeholder_nbt(facility_id))
    for base_id in HOUSE_BASES:
        for roof_id in sorted(HOUSE_ROOFS):
            authored = _inside(
                root,
                root / HOUSE_STRUCTURE_SOURCE_DIR / f"{base_id}_{roof_id}.nbt",
                "주택 NBT 원본",
            )
            authored_bytes = (
                _read_authored_structure_nbt(authored, "주택 NBT")
                if authored.is_file()
                else None
            )
            for roof_color in HOUSE_ROOF_BLOCKS:
                resource = f"cobbleventure:houses/{base_id}_{roof_id}_{roof_color}"
                generated = _inside(
                    root, _village_hub_output_path(output, resource),
                    "생성 주택 변형",
                )
                generated.parent.mkdir(parents=True, exist_ok=True)
                if authored_bytes is not None:
                    try:
                        generated.write_bytes(
                            recolor_house_roof_nbt(authored_bytes, roof_color)
                        )
                    except ValueError as error:
                        raise ModBuildError(f"주택 지붕 색상 생성 실패: {authored}") from error
                else:
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
    _package_hex_worlds(root, output, settlements)
    _package_generated_trainer_content(root, output)
    _package_generated_cves_content(root, output)
    _package_dimension_anchor_catalog(root, output)
    _package_event_boundary_catalog(root, output)
    _package_dialogue_theme(root, output)
    _package_loot_tables(root, output)
    _package_building_runtime_data(root, output)
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

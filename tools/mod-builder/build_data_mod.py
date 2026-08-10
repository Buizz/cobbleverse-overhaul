from __future__ import annotations

import argparse
import gzip
import json
import shutil
from pathlib import Path

from starter_gym import (
    BCA_VILLAGE_PRESETS,
    BCA_VILLAGE_START_POOLS,
    FACILITY_PLACEHOLDERS,
    GYM_ROOF_BLOCKS,
    build_facility_placeholder_nbt,
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
        target = _inside(root, output / GENERATED_SETTLEMENT_DIR / relative, "생성 마을 설정")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


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
    print(f"마을별 BCA 도로 허브 리소스 생성 완료: {output.parent.parent}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

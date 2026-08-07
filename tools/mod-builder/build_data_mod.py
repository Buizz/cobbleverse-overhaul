from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from starter_gym import GYM_ROOF_BLOCKS, build_starter_gym_nbt


SOURCE = Path("projects/cobbleventure-world-bootstrap/src/main/resources")
OUTPUT = Path("projects/cobbleventure-world-bootstrap/src/generated/resources")
SETTLEMENT_CONFIG_DIR = Path("content/settlements")
STARTER_TOWN_CONFIG = SETTLEMENT_CONFIG_DIR / "generation_1/starter_town.json"
HEX_WORLD_CONFIG_DIR = Path("content/worlds")
BOUNDARY_PROFILE_CONFIG = Path("content/catalogs/boundary-profiles.json")
REQUIRED_ENTRIES = {
    "META-INF/neoforge.mods.toml",
    "pack.mcmeta",
    "data/cobbleventure/worldgen/structure/starter_town/village.json",
    "data/cobbleventure/worldgen/structure/route_01_town/village.json",
    "data/cobbleventure/worldgen/structure/crimson_town/village.json",
    "data/cobbleventure/worldgen/structure/tidehaven_town/village.json",
    "data/cobbleventure/worldgen/structure/skyreach_town/village.json",
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
        if not isinstance(data, dict) or data.get("schema_version") != 1:
            raise ModBuildError(f"육각 월드 설정은 schema_version 1이어야 합니다: {source_path}")
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


def _gym_definition(data: dict[str, object]) -> tuple[str, str]:
    try:
        profile = data["structure_profile"]  # type: ignore[index]
        theme = profile["gym_theme"]  # type: ignore[index]
        resource = profile["required_facilities"]["gym"]  # type: ignore[index]
    except (KeyError, TypeError) as error:
        raise ModBuildError("마을 체육관 테마 또는 리소스를 읽을 수 없습니다.") from error
    if not isinstance(theme, str):
        raise ModBuildError("마을 체육관 테마는 문자열이어야 합니다.")
    if theme not in GYM_ROOF_BLOCKS:
        raise ModBuildError(f"지원하지 않는 체육관 테마입니다: {theme}")
    if not isinstance(resource, str) or ":" not in resource:
        raise ModBuildError("체육관 리소스 ID가 올바르지 않습니다.")
    return resource, theme


def _gym_output_path(output: Path, resource: str) -> Path:
    namespace, path = resource.split(":", 1)
    return output / "data" / namespace / "structure" / f"{path}.nbt"


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

    generated_gyms: dict[str, str] = {}
    first_generated: Path | None = None
    for _, settlement in settlements:
        resource, theme = _gym_definition(settlement)
        previous = generated_gyms.get(resource)
        if previous is not None and previous != theme:
            raise ModBuildError(
                f"같은 체육관 리소스에 서로 다른 테마가 지정되었습니다: {resource}"
            )
        generated_gyms[resource] = theme
    for resource, theme in generated_gyms.items():
        generated = _inside(root, _gym_output_path(output, resource), "생성 체육관")
        generated.parent.mkdir(parents=True, exist_ok=True)
        generated.write_bytes(build_starter_gym_nbt(theme))
        if first_generated is None:
            first_generated = generated
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
        raise ModBuildError("생성할 마을 체육관이 없습니다.")
    return first_generated


def main() -> int:
    parser = argparse.ArgumentParser(description="Cobbleventure 데이터 모드 빌더")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    arguments = parser.parse_args()
    output = build(arguments.root)
    print(f"마을별 체육관 리소스 생성 완료: {output.parent.parent}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

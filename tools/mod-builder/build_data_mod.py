from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from starter_gym import GYM_ROOF_BLOCKS, build_starter_gym_nbt


SOURCE = Path("projects/cobbleventure-world-bootstrap/src/main/resources")
OUTPUT = Path("projects/cobbleventure-world-bootstrap/src/generated/resources")
STARTER_TOWN_CONFIG = Path("content/settlements/generation_1/starter_town.json")
SETTLEMENT_CONFIG_DIR = Path("content/settlements")
REQUIRED_ENTRIES = {
    "META-INF/neoforge.mods.toml",
    "pack.mcmeta",
    "data/cobbleventure/worldgen/structure/starter_town/village.json",
    "data/cobbleventure/worldgen/structure/route_01_town/village.json",
    "data/cobbleventure/worldgen/template_pool/starter_town/center.json",
    "data/cobbleventure/worldgen/biome/starter_plains.json",
    "data/cobbleventure/dimension_type/generation_world.json",
    "data/cobbleventure/dimension/generation_1.json",
    "data/c/tags/worldgen/biome/is_overworld.json",
    "data/c/tags/worldgen/biome/is_plains.json",
    "data/minecraft/tags/worldgen/biome/is_overworld.json",
}
GENERATED_ENTRY = Path("data/cobbleventure/structure/starter_town/gym.nbt")
GENERATED_SETTLEMENT_ENTRY = Path(
    "data/cobbleventure/settlements/generation_1/starter_town.json"
)
GENERATED_SETTLEMENT_DIR = Path("data/cobbleventure/settlements")
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


def _starter_town_data(root: Path) -> dict[str, object]:
    path = _inside(root, root / STARTER_TOWN_CONFIG, "시작 마을 설정")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ModBuildError(f"시작 마을 설정을 읽을 수 없습니다: {path}") from error
    if not isinstance(data, dict) or data.get("schema_version") != 2:
        raise ModBuildError("시작 마을 설정은 settlement schema_version 2여야 합니다.")
    return data


def _package_settlements(root: Path, output: Path) -> None:
    source_dir = _inside(root, root / SETTLEMENT_CONFIG_DIR, "마을 설정 디렉터리")
    if not source_dir.is_dir():
        raise ModBuildError(f"마을 설정 디렉터리가 없습니다: {source_dir}")
    for source_path in sorted(source_dir.rglob("*.json")):
        try:
            data = json.loads(source_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ModBuildError(f"마을 설정을 읽을 수 없습니다: {source_path}") from error
        if not isinstance(data, dict) or data.get("schema_version") != 2:
            raise ModBuildError(f"마을 설정은 schema_version 2여야 합니다: {source_path}")
        relative = source_path.relative_to(source_dir)
        target = _inside(root, output / GENERATED_SETTLEMENT_DIR / relative, "생성 마을 설정")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _starter_gym_theme(data: dict[str, object]) -> str:
    try:
        theme = data["structure_profile"]["gym_theme"]  # type: ignore[index]
    except (KeyError, TypeError) as error:
        raise ModBuildError("시작 마을 체육관 테마를 읽을 수 없습니다.") from error
    if not isinstance(theme, str):
        raise ModBuildError("시작 마을 체육관 테마는 문자열이어야 합니다.")
    if theme not in GYM_ROOF_BLOCKS:
        raise ModBuildError(f"지원하지 않는 시작 체육관 테마입니다: {theme}")
    return theme


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
    settlement_data = _starter_town_data(root)
    theme = _starter_gym_theme(settlement_data)
    missing = sorted(REQUIRED_ENTRIES - names)
    if missing:
        raise ModBuildError(f"필수 데이터 모드 파일이 없습니다: {', '.join(missing)}")

    generated = _inside(root, output / GENERATED_ENTRY, "생성 체육관")
    generated.parent.mkdir(parents=True, exist_ok=True)
    generated.write_bytes(build_starter_gym_nbt(theme))
    for directory in (GENERATED_SETTLEMENT_DIR, LEGACY_GENERATED_SETTLEMENT_DIR):
        generated_directory = _inside(root, output / directory, "생성 마을 설정 디렉터리")
        if generated_directory.exists():
            shutil.rmtree(generated_directory)
    _package_settlements(root, output)
    return generated


def main() -> int:
    parser = argparse.ArgumentParser(description="Cobbleventure 데이터 모드 빌더")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    arguments = parser.parse_args()
    output = build(arguments.root)
    print(f"시작 마을 체육관 리소스 생성 완료: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

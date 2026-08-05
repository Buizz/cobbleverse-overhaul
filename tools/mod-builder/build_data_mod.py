from __future__ import annotations

import argparse
import json
import os
import zipfile
from pathlib import Path, PurePosixPath

from starter_gym import GYM_ROOF_BLOCKS, build_starter_gym_nbt


SOURCE = Path("projects/cobbleventure-world-bootstrap/src/main/resources")
OUTPUT = Path(
    "pack/overrides/development-placeholder/mods/"
    "cobbleventure-world-bootstrap-0.1.0.jar"
)
STARTER_TOWN_CONFIG = Path("content/settlements/generation_1/starter_town.json")
REQUIRED_ENTRIES = {
    "META-INF/neoforge.mods.toml",
    "pack.mcmeta",
    "data/minecraft/tags/function/tick.json",
    "data/cobbleventure_bootstrap/function/tick.mcfunction",
    "data/cobbleventure_bootstrap/function/schedule_starter_town.mcfunction",
    "data/cobbleventure_bootstrap/function/auto_place_starter_town.mcfunction",
    "data/cobbleventure_bootstrap/function/place_starter_town.mcfunction",
    "data/cobbleventure_bootstrap/function/retry_starter_town.mcfunction",
    "data/cobbleventure/worldgen/structure/starter_town/village.json",
    "data/cobbleventure/worldgen/template_pool/starter_town/center.json",
    "data/cobbleventure/structure/starter_town/gym.nbt",
}
GENERATED_ENTRY_NAMES = {"data/cobbleventure/structure/starter_town/gym.nbt"}


class ModBuildError(RuntimeError):
    pass


def _inside(root: Path, path: Path, label: str) -> Path:
    resolved = path.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ModBuildError(f"{label} 경로가 저장소 밖을 가리킵니다: {resolved}") from error
    return resolved


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    return info


def _starter_gym_theme(root: Path) -> str:
    path = _inside(root, root / STARTER_TOWN_CONFIG, "시작 마을 설정")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        theme = data["structure_profile"]["gym_theme"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        raise ModBuildError(f"시작 마을 체육관 테마를 읽을 수 없습니다: {path}") from error
    if theme not in GYM_ROOF_BLOCKS:
        raise ModBuildError(f"지원하지 않는 시작 체육관 테마입니다: {theme}")
    return theme


def build(root: Path) -> Path:
    root = root.resolve()
    source = _inside(root, root / SOURCE, "소스")
    output = _inside(root, root / OUTPUT, "출력")
    if not source.is_dir():
        raise ModBuildError(f"데이터 모드 소스가 없습니다: {source}")

    files = sorted(path for path in source.rglob("*") if path.is_file())
    names = {PurePosixPath(path.relative_to(source)).as_posix() for path in files}
    theme = _starter_gym_theme(root)
    generated = {
        "data/cobbleventure/structure/starter_town/gym.nbt": build_starter_gym_nbt(theme)
    }
    collisions = sorted(names & generated.keys())
    if collisions:
        raise ModBuildError(f"생성 파일과 소스 파일 경로가 충돌합니다: {', '.join(collisions)}")
    names.update(generated)
    missing = sorted(REQUIRED_ENTRIES - names)
    if missing:
        raise ModBuildError(f"필수 데이터 모드 파일이 없습니다: {', '.join(missing)}")

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    if temporary.exists():
        temporary.unlink()
    try:
        with zipfile.ZipFile(temporary, "w", allowZip64=False) as archive:
            for path in files:
                if path.is_symlink():
                    raise ModBuildError(f"심볼릭 링크를 패키징할 수 없습니다: {path}")
                name = PurePosixPath(path.relative_to(source)).as_posix()
                archive.writestr(_zip_info(name), path.read_bytes())
            for name, data in sorted(generated.items()):
                archive.writestr(_zip_info(name), data)
        os.replace(temporary, output)
    except Exception:
        if temporary.exists():
            temporary.unlink()
        raise
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description="Cobbleventure 데이터 모드 빌더")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    arguments = parser.parse_args()
    output = build(arguments.root)
    print(f"시작 마을 부트스트랩 JAR 생성 완료: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

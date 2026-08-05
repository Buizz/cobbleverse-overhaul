from __future__ import annotations

import argparse
import json
from pathlib import Path

from starter_gym import GYM_ROOF_BLOCKS, build_starter_gym_nbt


SOURCE = Path("projects/cobbleventure-world-bootstrap/src/main/resources")
OUTPUT = Path("projects/cobbleventure-world-bootstrap/src/generated/resources")
STARTER_TOWN_CONFIG = Path("content/settlements/generation_1/starter_town.json")
REQUIRED_ENTRIES = {
    "META-INF/neoforge.mods.toml",
    "pack.mcmeta",
    "data/cobbleventure/worldgen/structure/starter_town/village.json",
    "data/cobbleventure/worldgen/template_pool/starter_town/center.json",
}
GENERATED_ENTRY = Path("data/cobbleventure/structure/starter_town/gym.nbt")


class ModBuildError(RuntimeError):
    pass


def _inside(root: Path, path: Path, label: str) -> Path:
    resolved = path.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ModBuildError(f"{label} 경로가 저장소 밖을 가리킵니다: {resolved}") from error
    return resolved


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

    names = {
        path.relative_to(source).as_posix()
        for path in source.rglob("*")
        if path.is_file()
    }
    theme = _starter_gym_theme(root)
    missing = sorted(REQUIRED_ENTRIES - names)
    if missing:
        raise ModBuildError(f"필수 데이터 모드 파일이 없습니다: {', '.join(missing)}")

    generated = _inside(root, output / GENERATED_ENTRY, "생성 체육관")
    generated.parent.mkdir(parents=True, exist_ok=True)
    generated.write_bytes(build_starter_gym_nbt(theme))
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

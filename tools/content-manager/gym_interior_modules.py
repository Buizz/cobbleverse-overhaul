from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


def _nbt_builder(root: Path):
    module_root = root / "tools" / "mod-builder"
    if not module_root.is_dir():
        module_root = Path(__file__).resolve().parents[1] / "mod-builder"
    sys.path.insert(0, str(module_root))
    try:
        from starter_gym import _build_structure_nbt
    finally:
        sys.path.pop(0)
    return _build_structure_nbt


def blank_module_nbt(root: Path, width: int, depth: int, floor_height: int, floors: int) -> bytes:
    height = floor_height * floors
    air = ("minecraft:air", (), None)
    blocks = {
        (x, y, z): air
        for x in range(width)
        for y in range(height)
        for z in range(depth)
    }
    for x in range(width):
        for z in range(depth):
            blocks[(x, 0, z)] = ("minecraft:smooth_stone", (), None)
            blocks[(x, height - 1, z)] = ("minecraft:light_gray_concrete", (), None)
    for y in range(1, height - 1):
        for x in range(width):
            blocks[(x, y, 0)] = ("minecraft:white_concrete", (), None)
            blocks[(x, y, depth - 1)] = ("minecraft:white_concrete", (), None)
        for z in range(depth):
            blocks[(0, y, z)] = ("minecraft:white_concrete", (), None)
            blocks[(width - 1, y, z)] = ("minecraft:white_concrete", (), None)
    doorway_x = width // 2
    for x in (doorway_x - 1, doorway_x):
        for y in range(1, min(4, height - 1)):
            blocks[(x, y, 0)] = air
    for floor in range(1, floors):
        floor_y = floor * floor_height
        for x in range(1, width - 1):
            for z in range(1, depth - 1):
                blocks[(x, floor_y, z)] = ("minecraft:smooth_stone", (), None)
    return _nbt_builder(root)((width, height, depth), blocks)


def module_metadata(module_id: str, width: int, depth: int, floor_height: int, floors: int) -> dict:
    return {
        "schema_version": 1,
        "anchors": [],
        "interior": {
            "id": module_id,
            "width": width,
            "depth": depth,
            "floor_height": floor_height,
            "floors": floors,
        },
    }


def create_module(
    root: Path, module_id: str, width: int = 32, depth: int = 32,
    floor_height: int = 12, floors: int = 1, *, overwrite: bool = False,
) -> dict:
    if not re.fullmatch(r"[a-z0-9][a-z0-9_]*", module_id):
        raise ValueError("모듈 ID는 영문 소문자, 숫자와 밑줄만 사용할 수 있습니다.")
    if not 5 <= width <= 80 or not 5 <= depth <= 80:
        raise ValueError("내부 너비와 깊이는 5~80이어야 합니다.")
    if not 3 <= floor_height <= 80 or not 1 <= floors <= 8 or floor_height * floors > 80:
        raise ValueError("층 높이는 3~80, 층수는 1~8, 전체 높이는 80 이하여야 합니다.")
    target_root = root / "content" / "structures" / "interiors"
    target_root.mkdir(parents=True, exist_ok=True)
    nbt_path = target_root / f"{module_id}.nbt"
    metadata_path = target_root / f"{module_id}.structure.json"
    if not overwrite and (nbt_path.exists() or metadata_path.exists()):
        raise ValueError(f"이미 존재하는 내부공간입니다: {module_id}")
    nbt_path.write_bytes(blank_module_nbt(root, width, depth, floor_height, floors))
    metadata = module_metadata(module_id, width, depth, floor_height, floors)
    metadata_path.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return {
        "id": module_id,
        "structure": f"cobbleventure:interiors/{module_id}",
        "size": [width, floor_height * floors, depth],
        "anchors": [],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="빈 범용 내부공간 NBT 생성")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--id")
    parser.add_argument("--width", type=int, default=32)
    parser.add_argument("--depth", type=int, default=32)
    parser.add_argument("--floor-height", type=int, default=12)
    parser.add_argument("--floors", type=int, default=1)
    parser.add_argument("--overwrite", action="store_true")
    arguments = parser.parse_args()
    module_ids = (arguments.id,)
    if not all(isinstance(module_id, str) for module_id in module_ids):
        parser.error("--id가 필요합니다.")
    for module_id in module_ids:
        result = create_module(
            arguments.root.resolve(), module_id, arguments.width, arguments.depth,
            arguments.floor_height, arguments.floors, overwrite=arguments.overwrite,
        )
        print(f"내부공간 NBT 생성: {result['structure']} ({result['size']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

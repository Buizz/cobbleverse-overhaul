from __future__ import annotations

import argparse
import collections
import gzip
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "content-manager"))

from content_manager import (  # noqa: E402
    _minecraft_structure_tag_spans,
    _read_minecraft_structure_root,
)
from cave_road_anchor import (  # noqa: E402
    _int_tag,
    _list_records,
    _named,
    _road_anchor_block,
)


DEFAULT_CAVE_ROOT = (
    ROOT
    / "content-projects"
    / "cobbleventure-main"
    / "content"
    / "structures"
    / "cave_entrance"
)
BURIAL_DEPTH = 5
def _plain_block(state: int, position: tuple[int, int, int]) -> bytes:
    return (
        _int_tag("state", state)
        + _named(
            9,
            "pos",
            bytes([3]) + struct.pack(">i", 3) + struct.pack(">iii", *position),
        )
        + b"\x00"
    )


def _progress(z: int, start_z: int, end_z: int) -> float:
    return (z - start_z) / (end_z - start_z)


def _opening_radius(z: int, start_z: int, end_z: int) -> int:
    progress = _progress(z, start_z, end_z)
    if progress < 0.20:
        return 2
    if progress < 0.44:
        return 3
    if progress < 0.72:
        return 4
    return 5


def _opening(radius: int, center_x: int, floor_y: int) -> set[tuple[int, int]]:
    height = radius * 2 + 1
    roof_rows = min(3, radius)
    straight_rows = height - roof_rows
    result: set[tuple[int, int]] = set()
    for row in range(height):
        half_width = radius
        if row >= straight_rows:
            half_width = radius - (row - straight_rows + 1)
        for x in range(center_x - half_width, center_x + half_width + 1):
            result.add((x, floor_y + 1 + row))
    return result


def _shell(
    z: int, opening: set[tuple[int, int]], radius: int,
    center_x: int, floor_y: int, start_z: int, end_z: int,
) -> set[tuple[int, int]]:
    progress = _progress(z, start_z, end_z)
    side_thickness = 2 + round(progress * 4)
    roof_thickness = 2 + round(progress * 3)
    result: set[tuple[int, int]] = set()
    for inner_x, inner_y in opening:
        for dx in range(-side_thickness, side_thickness + 1):
            for dy in range(-roof_thickness, roof_thickness + 1):
                distance = (dx / side_thickness) ** 2 + (dy / roof_thickness) ** 2
                if distance <= 1.0:
                    result.add((inner_x + dx, inner_y + dy))
    result.difference_update(opening)
    for y in range(0, floor_y + 1):
        for x in range(
            center_x - radius - side_thickness,
            center_x + radius + side_thickness + 1,
        ):
            result.add((x, y))
    return result


def _state_indices(root: dict) -> tuple[int, int, int]:
    palette = root.get("palette", [])
    names = [entry.get("Name") for entry in palette]
    try:
        barrier = names.index("minecraft:barrier")
        jigsaw = names.index("minecraft:jigsaw")
    except ValueError as error:
        raise ValueError("동굴 NBT에 베리어 또는 도로 직소 블록이 없습니다.") from error
    counts = collections.Counter(
        block["state"]
        for block in root.get("blocks", [])
        if names[block["state"]]
        not in {"minecraft:barrier", "minecraft:black_concrete", "minecraft:jigsaw"}
    )
    if not counts:
        raise ValueError("동굴 외벽에 사용할 블록 팔레트가 없습니다.")
    return barrier, jigsaw, counts.most_common(1)[0][0]


def _authored_anchor(root: dict) -> tuple[int, int, int]:
    palette = root.get("palette", [])
    anchors = [
        tuple(block.get("pos", []))
        for block in root.get("blocks", [])
        if palette[block["state"]].get("Name") == "minecraft:jigsaw"
        and block.get("nbt", {}).get("name") == "cobbleventure:road_anchor"
    ]
    if len(anchors) != 1 or len(anchors[0]) != 3:
        raise ValueError(f"도로 직소 앵커가 정확히 하나여야 합니다: {anchors}")
    return anchors[0]


def _tunnel_end(root: dict, start_z: int) -> int:
    palette = root.get("palette", [])
    back_wall_z = [
        block["pos"][2]
        for block in root.get("blocks", [])
        if palette[block["state"]].get("Name") == "minecraft:black_concrete"
        and block["pos"][2] > start_z
    ]
    if not back_wall_z:
        raise ValueError("동굴 뒤쪽 검은 벽을 찾을 수 없습니다.")
    return min(back_wall_z) - 1


def _tunnel_floor(root: dict, start_z: int) -> int:
    palette = root.get("palette", [])
    void_y = [
        block["pos"][1]
        for block in root.get("blocks", [])
        if block["pos"][2] == start_z
        and palette[block["state"]].get("Name") == "minecraft:barrier"
    ]
    if not void_y:
        raise ValueError("동굴 입구의 내부 베리어를 찾을 수 없습니다.")
    return min(void_y) - 1


def taper_cave(path: Path) -> bool:
    source = path.read_bytes()
    compressed = source.startswith(b"\x1f\x8b")
    raw = gzip.decompress(source) if compressed else source
    root = _read_minecraft_structure_root(raw)
    authored_anchor = _authored_anchor(root)
    center_x, _, start_z = authored_anchor
    floor_y = _tunnel_floor(root, start_z)
    end_z = _tunnel_end(root, start_z)
    barrier_state, jigsaw_state, primary_state = _state_indices(root)
    spans = _minecraft_structure_tag_spans(raw)
    blocks_type, blocks_start, blocks_end = spans["blocks"]
    if blocks_type != 9:
        raise ValueError(f"NBT 블록 목록이 손상되었습니다: {path}")
    _, records = _list_records(raw[blocks_start:blocks_end])
    existing = {tuple(block["pos"]): (block, encoded) for block, encoded in records}

    replacements: dict[tuple[int, int, int], bytes] = {}
    for position, (_, encoded) in existing.items():
        if not start_z <= position[2] <= end_z:
            replacements[position] = encoded

    palette = root["palette"]
    for z in range(start_z, end_z + 1):
        radius = _opening_radius(z, start_z, end_z)
        opening = _opening(radius, center_x, floor_y)
        shell = _shell(z, opening, radius, center_x, floor_y, start_z, end_z)
        for x, y in opening:
            position = (x, y, z)
            replacements[position] = _plain_block(barrier_state, position)
        for x, y in shell:
            position = (x, y, z)
            original = existing.get(position)
            state = primary_state
            if original is not None:
                original_state = original[0]["state"]
                original_name = palette[original_state].get("Name")
                if original_name not in {
                    "minecraft:barrier",
                    "minecraft:black_concrete",
                    "minecraft:jigsaw",
                }:
                    state = original_state
            replacements[position] = _plain_block(state, position)

    anchor = (center_x, floor_y + BURIAL_DEPTH, start_z)
    replacements[anchor] = _road_anchor_block(jigsaw_state, anchor)

    ordered = [replacements[position] for position in sorted(replacements)]
    new_blocks = bytes([10]) + struct.pack(">i", len(ordered)) + b"".join(ordered)
    rebuilt = bytearray(raw)
    rebuilt[blocks_start:blocks_end] = new_blocks
    output = gzip.compress(bytes(rebuilt), mtime=0) if compressed else bytes(rebuilt)
    if output == source:
        return False
    path.write_bytes(output)
    return True


def validate_taper(path: Path) -> list[str]:
    root = _read_minecraft_structure_root(path.read_bytes())
    palette = root.get("palette", [])
    center_x, _, start_z = _authored_anchor(root)
    floor_y = _tunnel_floor(root, start_z)
    end_z = _tunnel_end(root, start_z)
    barriers = {
        tuple(block["pos"])
        for block in root.get("blocks", [])
        if palette[block["state"]].get("Name") == "minecraft:barrier"
    }
    issues: list[str] = []
    for z in range(start_z, end_z + 1):
        radius = _opening_radius(z, start_z, end_z)
        expected = {(x, y, z) for x, y in _opening(radius, center_x, floor_y)}
        expected.discard((center_x, floor_y + BURIAL_DEPTH, start_z))
        actual = {position for position in barriers if position[2] == z}
        if actual != expected:
            issues.append(f"z={z} 내부 단면이 다릅니다.")
    if (center_x, floor_y + BURIAL_DEPTH, start_z) not in {
        tuple(block["pos"])
        for block in root.get("blocks", [])
        if palette[block["state"]].get("Name") == "minecraft:jigsaw"
    }:
        issues.append("도로 직소 앵커가 없습니다.")
    return issues


def main() -> None:
    parser = argparse.ArgumentParser(description="동굴 입구 NBT를 뒤로 갈수록 넓고 두껍게 만듭니다.")
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    paths = args.paths or sorted(DEFAULT_CAVE_ROOT.glob("*.nbt"))
    failed = False
    for path in paths:
        if args.check:
            issues = validate_taper(path)
            print(f"{'ERROR' if issues else 'OK'} {path}: {', '.join(issues) if issues else '5→11칸 테이퍼'}")
            failed |= bool(issues)
        else:
            changed = taper_cave(path)
            print(f"{'수정' if changed else '유지'} {path}")
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

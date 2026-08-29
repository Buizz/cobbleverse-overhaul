from __future__ import annotations

import argparse
import gzip
import io
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "content-manager"))

from content_manager import (  # noqa: E402
    _minecraft_structure_tag_spans,
    _read_minecraft_structure_root,
    _read_nbt_payload,
)


ANCHOR_NAME = "cobbleventure:road_anchor"
DEFAULT_CAVE_ROOT = (
    ROOT
    / "content-projects"
    / "cobbleventure-main"
    / "content"
    / "structures"
    / "cave_entrance"
)


def _string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def _named(tag_type: int, name: str, payload: bytes) -> bytes:
    return bytes([tag_type]) + _string(name) + payload


def _string_tag(name: str, value: str) -> bytes:
    return _named(8, name, _string(value))


def _int_tag(name: str, value: int) -> bytes:
    return _named(3, name, struct.pack(">i", value))


def _compound_tag(name: str, payload: bytes) -> bytes:
    return _named(10, name, payload + b"\x00")


def _jigsaw_palette_entry(orientation: str) -> bytes:
    properties = _string_tag("orientation", orientation)
    return (
        _string_tag("Name", "minecraft:jigsaw")
        + _compound_tag("Properties", properties)
        + b"\x00"
    )


def _road_anchor_block(
    state: int, position: tuple[int, int, int],
    final_state: str = "minecraft:air",
) -> bytes:
    x, y, z = position
    block_entity = (
        _string_tag("name", ANCHOR_NAME)
        + _string_tag("target", "minecraft:empty")
        + _string_tag("pool", "minecraft:empty")
        + _string_tag("final_state", final_state)
        + _string_tag("joint", "rollable")
        + _int_tag("selection_priority", 0)
        + _int_tag("placement_priority", 0)
    )
    return (
        _int_tag("state", state)
        + _named(
            9,
            "pos",
            bytes([3]) + struct.pack(">i", 3) + struct.pack(">iii", x, y, z),
        )
        + _compound_tag("nbt", block_entity)
        + b"\x00"
    )


def _list_records(payload: bytes) -> tuple[int, list[tuple[dict, bytes]]]:
    if len(payload) < 5 or payload[0] != 10:
        raise ValueError("Compound 목록이 아닙니다.")
    count = struct.unpack(">i", payload[1:5])[0]
    stream = io.BytesIO(payload[5:])
    records: list[tuple[dict, bytes]] = []
    for _ in range(count):
        start = stream.tell()
        value = _read_nbt_payload(stream, 10)
        records.append((value, payload[5 + start : 5 + stream.tell()]))
    return count, records


def add_road_anchor(
    path: Path,
    position: tuple[int, int, int] | None = None,
    orientation: str = "north_up",
    final_state: str = "minecraft:air",
) -> bool:
    source = path.read_bytes()
    compressed = source.startswith(b"\x1f\x8b")
    raw = gzip.decompress(source) if compressed else source
    root = _read_minecraft_structure_root(raw)
    size = root.get("size")
    if not isinstance(size, list) or len(size) != 3:
        raise ValueError(f"NBT 크기를 읽을 수 없습니다: {path}")
    spans = _minecraft_structure_tag_spans(raw)
    palette_type, palette_start, palette_end = spans["palette"]
    blocks_type, blocks_start, blocks_end = spans["blocks"]
    if palette_type != 9 or blocks_type != 9:
        raise ValueError(f"NBT 팔레트 또는 블록 목록이 손상되었습니다: {path}")

    palette_payload = raw[palette_start:palette_end]
    _, palette_records = _list_records(palette_payload)
    jigsaw_state = None
    for index, (entry, _) in enumerate(palette_records):
        if entry.get("Name") == "minecraft:jigsaw" and entry.get("Properties", {}).get(
            "orientation"
        ) == orientation:
            jigsaw_state = index
            break
    palette_encoded = [encoded for _, encoded in palette_records]
    if jigsaw_state is None:
        jigsaw_state = len(palette_encoded)
        palette_encoded.append(_jigsaw_palette_entry(orientation))

    blocks_payload = raw[blocks_start:blocks_end]
    _, block_records = _list_records(blocks_payload)
    authored_anchors = [
        tuple(block.get("pos", []))
        for block, _ in block_records
        if block.get("nbt", {}).get("name") == ANCHOR_NAME
    ]
    if position is None:
        position = (
            authored_anchors[0]
            if len(authored_anchors) == 1
            else (size[0] // 2, 0, min(4, size[2] - 1))
        )
    if any(coordinate < 0 or coordinate >= limit for coordinate, limit in zip(position, size)):
        raise ValueError(f"직소 마커가 NBT 범위를 벗어납니다: {path} {position} / {size}")
    kept_blocks: list[bytes] = []
    existing_anchor = False
    for block, encoded in block_records:
        block_position = tuple(block.get("pos", []))
        block_nbt = block.get("nbt", {})
        if block_nbt.get("name") == ANCHOR_NAME:
            existing_anchor = True
            continue
        if block_position != position:
            kept_blocks.append(encoded)
    marker = _road_anchor_block(jigsaw_state, position, final_state)
    kept_blocks.append(marker)

    new_palette = bytes([10]) + struct.pack(">i", len(palette_encoded)) + b"".join(
        palette_encoded
    )
    new_blocks = bytes([10]) + struct.pack(">i", len(kept_blocks)) + b"".join(kept_blocks)
    rebuilt = bytearray(raw)
    for start, end, replacement in sorted(
        [
            (palette_start, palette_end, new_palette),
            (blocks_start, blocks_end, new_blocks),
        ],
        reverse=True,
    ):
        rebuilt[start:end] = replacement
    output = gzip.compress(bytes(rebuilt), mtime=0) if compressed else bytes(rebuilt)
    if output == source:
        return False
    path.write_bytes(output)
    return not existing_anchor


def road_anchors(path: Path) -> list[tuple[list[int], str]]:
    root = _read_minecraft_structure_root(path.read_bytes())
    palette = root.get("palette", [])
    result: list[tuple[list[int], str]] = []
    for block in root.get("blocks", []):
        nbt = block.get("nbt", {})
        if nbt.get("name") != ANCHOR_NAME:
            continue
        state = palette[block["state"]]
        result.append((block["pos"], state.get("Properties", {}).get("orientation", "")))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="동굴 입구 NBT에 도로 직소 앵커를 추가합니다.")
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--position", nargs=3, type=int, metavar=("X", "Y", "Z"))
    parser.add_argument(
        "--orientation", default="north_up",
        choices=("north_up", "east_up", "south_up", "west_up"),
    )
    parser.add_argument("--final-state", default="minecraft:air")
    args = parser.parse_args()
    paths = args.paths or sorted(DEFAULT_CAVE_ROOT.glob("*.nbt"))
    failed = False
    for path in paths:
        if args.check:
            anchors = road_anchors(path)
            valid = len(anchors) == 1 and anchors[0][1] in {
                "north_up", "east_up", "south_up", "west_up"
            }
            print(f"{'OK' if valid else 'ERROR'} {path}: {anchors}")
            failed |= not valid
            continue
        added = add_road_anchor(
            path,
            tuple(args.position) if args.position is not None else None,
            args.orientation,
            args.final_state,
        )
        print(f"{'추가' if added else '갱신'} {path}: {road_anchors(path)}")
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

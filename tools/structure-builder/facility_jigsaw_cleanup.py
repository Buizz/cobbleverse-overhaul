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


def _string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def _named(tag_type: int, name: str, payload: bytes) -> bytes:
    return bytes([tag_type]) + _string(name) + payload


def _plain_block(state: int, position: tuple[int, int, int]) -> bytes:
    return (
        _named(3, "state", struct.pack(">i", state))
        + _named(
            9,
            "pos",
            bytes([3]) + struct.pack(">i", 3) + struct.pack(">iii", *position),
        )
        + b"\x00"
    )


def _list_records(payload: bytes) -> list[tuple[dict, bytes]]:
    if not payload or payload[0] != 10:
        raise ValueError("NBT 목록이 compound 형식이 아닙니다.")
    count = struct.unpack(">i", payload[1:5])[0]
    stream = io.BytesIO(payload[5:])
    records: list[tuple[dict, bytes]] = []
    for _ in range(count):
        start = stream.tell()
        value = _read_nbt_payload(stream, 10)
        records.append((value, payload[5 + start : 5 + stream.tell()]))
    return records


def replace_jigsaws_with_final_state(path: Path, names: set[str]) -> int:
    source = path.read_bytes()
    compressed = source.startswith(b"\x1f\x8b")
    raw = gzip.decompress(source) if compressed else source
    root = _read_minecraft_structure_root(raw)
    palette = root.get("palette", [])
    state_by_name = {
        entry.get("Name"): index
        for index, entry in enumerate(palette)
        if isinstance(entry, dict) and "Properties" not in entry
    }
    spans = _minecraft_structure_tag_spans(raw)
    blocks_type, blocks_start, blocks_end = spans["blocks"]
    if blocks_type != 9:
        raise ValueError(f"NBT 블록 목록이 손상되었습니다: {path}")

    records = _list_records(raw[blocks_start:blocks_end])
    rewritten: list[bytes] = []
    replaced = 0
    for block, encoded in records:
        block_nbt = block.get("nbt", {})
        marker_name = block_nbt.get("name") if isinstance(block_nbt, dict) else None
        if marker_name not in names:
            rewritten.append(encoded)
            continue
        final_state = block_nbt.get("final_state", "minecraft:air").split("[", 1)[0]
        state = state_by_name.get(final_state)
        if state is None:
            raise ValueError(
                f"직소 final_state가 팔레트에 없습니다: {path} {marker_name}={final_state}"
            )
        rewritten.append(_plain_block(state, tuple(block["pos"])))
        replaced += 1

    if replaced == 0:
        return 0
    new_blocks = bytes([10]) + struct.pack(">i", len(rewritten)) + b"".join(rewritten)
    rebuilt = bytearray(raw)
    rebuilt[blocks_start:blocks_end] = new_blocks
    output = gzip.compress(bytes(rebuilt), mtime=0) if compressed else bytes(rebuilt)
    path.write_bytes(output)
    return replaced


def main() -> None:
    parser = argparse.ArgumentParser(
        description="시설 NBT의 외부 직소를 final_state 블록으로 치환합니다."
    )
    parser.add_argument("path", type=Path)
    parser.add_argument("--name", action="append", required=True)
    args = parser.parse_args()
    count = replace_jigsaws_with_final_state(args.path, set(args.name))
    print(f"치환 {args.path}: {count}개")


if __name__ == "__main__":
    main()

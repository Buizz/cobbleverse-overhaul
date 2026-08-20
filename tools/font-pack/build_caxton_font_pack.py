#!/usr/bin/env python3
"""Build a deterministic Caxton resource pack from a FontStruct archive."""

from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path


FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
PACK_FORMAT = 34


def _archive_member(archive: zipfile.ZipFile, suffix: str) -> str:
    matches = sorted(name for name in archive.namelist() if name.lower().endswith(suffix))
    if len(matches) != 1:
        raise ValueError(f"archive must contain exactly one {suffix} file; found {len(matches)}")
    return matches[0]


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=FIXED_ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    return info


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def build_pack(source: Path, output: Path) -> dict[str, int]:
    with zipfile.ZipFile(source) as archive:
        font = archive.read(_archive_member(archive, ".ttf"))
        license_text = archive.read(_archive_member(archive, "license.txt"))
        readme = archive.read(_archive_member(archive, "readme.txt"))

    # Keep a vanilla fallback so the pack remains readable if Caxton is absent.
    # With Caxton installed, the caxton_providers list replaces providers and
    # renders Pokemon BW through an MSDF atlas at the requested GUI scale.
    font_definition = {
        "providers": [
            {"type": "reference", "id": "minecraft:uniform"},
        ],
        "caxton_providers": [
            {
                "type": "caxton",
                "regular": {
                    "file": "minecraft:pokemon_bw.ttf",
                    "scale_factor": 1.0,
                    "shadow_offset": 1.0,
                    "shift": [0.0, 0.0],
                },
            },
            {"type": "reference", "id": "minecraft:uniform"},
        ],
    }
    font_metadata = {
        "shrinkage": 32.0,
        "margin": 4,
        "range": 4,
        "tech": "msdf",
    }
    pack_meta = {
        "pack": {
            "pack_format": PACK_FORMAT,
            "description": "Pokemon BW MSDF default font for Cobbleventure (Caxton)",
        }
    }
    files = {
        "LICENSES/Pokemon-BW-license.txt": license_text,
        "LICENSES/Pokemon-BW-readme.txt": readme,
        "assets/minecraft/font/default.json": _json_bytes(font_definition),
        "assets/minecraft/textures/font/pokemon_bw.ttf": font,
        "assets/minecraft/textures/font/pokemon_bw.ttf.json": _json_bytes(font_metadata),
        "pack.mcmeta": _json_bytes(pack_meta),
    }

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f".{output.name}.tmp")
    try:
        with zipfile.ZipFile(temporary, "w", allowZip64=True) as archive:
            for name in sorted(files):
                archive.writestr(_zip_info(name), files[name])
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return {"font_bytes": len(font), "files": len(files)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="FontStruct ZIP containing one TTF")
    parser.add_argument("output", type=Path, help="Caxton resource-pack ZIP")
    args = parser.parse_args()
    result = build_pack(args.source, args.output)
    print(f"Built Caxton font pack: {result['font_bytes']} font bytes, {result['files']} files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

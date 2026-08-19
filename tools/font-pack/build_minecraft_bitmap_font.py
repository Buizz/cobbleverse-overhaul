#!/usr/bin/env python3
"""Build a deterministic Minecraft bitmap font pack from a FontStruct archive."""

from __future__ import annotations

import argparse
import io
import json
import math
import zipfile
from dataclasses import dataclass
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont


FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
DEFAULT_FONT_SIZE = 16
DEFAULT_COLUMNS = 80
DEFAULT_DISPLAY_HEIGHT = 9
DEFAULT_DISPLAY_ASCENT = 8
PACK_FORMAT = 34


@dataclass(frozen=True)
class Glyph:
    codepoint: int
    left: int
    top: int
    right: int
    bottom: int
    x_offset: int


def _archive_member(archive: zipfile.ZipFile, suffix: str) -> str:
    matches = sorted(name for name in archive.namelist() if name.lower().endswith(suffix))
    if len(matches) != 1:
        raise ValueError(f"archive must contain exactly one {suffix} file; found {len(matches)}")
    return matches[0]


def _load_source(source: Path) -> tuple[bytes, bytes, bytes]:
    with zipfile.ZipFile(source) as archive:
        font = archive.read(_archive_member(archive, ".ttf"))
        license_text = archive.read(_archive_member(archive, "license.txt"))
        readme = archive.read(_archive_member(archive, "readme.txt"))
    return font, license_text, readme


def _font_codepoints(font_bytes: bytes) -> list[int]:
    font = TTFont(io.BytesIO(font_bytes))
    cmap = font.getBestCmap()
    if not cmap:
        raise ValueError("font does not contain a Unicode cmap")
    return sorted(codepoint for codepoint in cmap if codepoint != 0)


def _measure_glyphs(
    font: ImageFont.FreeTypeFont,
    codepoints: list[int],
) -> tuple[list[Glyph], dict[int, float]]:
    glyphs: list[Glyph] = []
    spaces: dict[int, float] = {}
    for codepoint in codepoints:
        character = chr(codepoint)
        box = font.getbbox(character, anchor="ls")
        if box is None or box[0] == box[2] or box[1] == box[3]:
            spaces[codepoint] = float(font.getlength(character))
            continue
        left, top, right, bottom = box
        glyphs.append(
            Glyph(
                codepoint=codepoint,
                left=left,
                top=top,
                right=right,
                bottom=bottom,
                x_offset=max(0, -left),
            )
        )
    if not glyphs:
        raise ValueError("font does not contain any drawable glyphs")
    return glyphs, spaces


def _render_atlas(
    font: ImageFont.FreeTypeFont,
    glyphs: list[Glyph],
    columns: int,
) -> tuple[Image.Image, list[str], int, int]:
    min_top = min(glyph.top for glyph in glyphs)
    max_bottom = max(glyph.bottom for glyph in glyphs)
    baseline = -min_top
    cell_height = max_bottom - min_top
    cell_width = max(glyph.right + glyph.x_offset for glyph in glyphs)
    rows = math.ceil(len(glyphs) / columns)

    # Pokemon BW is authored on a 16x16 grid. Render straight into a one-bit
    # surface so FreeType cannot introduce semi-transparent edge pixels that
    # later disappear differently from one Hangul syllable to another.
    alpha = Image.new("1", (columns * cell_width, rows * cell_height), 0)
    draw = ImageDraw.Draw(alpha)
    characters: list[str] = []
    for index, glyph in enumerate(glyphs):
        column = index % columns
        row = index // columns
        origin_x = column * cell_width + glyph.x_offset
        origin_y = row * cell_height + baseline
        draw.text(
            (origin_x, origin_y),
            chr(glyph.codepoint),
            font=font,
            fill=1,
            anchor="ls",
        )
        characters.append(chr(glyph.codepoint))

    alpha = alpha.convert("L")
    atlas = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    atlas.putalpha(alpha)

    padded = characters + ["\0"] * (rows * columns - len(characters))
    char_rows = ["".join(padded[offset : offset + columns]) for offset in range(0, len(padded), columns)]
    return atlas, char_rows, cell_height, baseline


def _font_definition(
    char_rows: list[str],
    spaces: dict[int, float],
    display_height: int,
    display_ascent: int,
) -> bytes:
    advances = {
        chr(codepoint): round(advance, 3)
        for codepoint, advance in sorted(spaces.items())
        if advance >= 0
    }
    providers: list[dict[str, object]] = []
    if advances:
        providers.append({"type": "space", "advances": advances})
    providers.append(
        {
            "type": "bitmap",
            "file": "minecraft:font/pokemon_bw.png",
            "height": display_height,
            "ascent": display_ascent,
            "chars": char_rows,
        }
    )
    providers.append({"type": "reference", "id": "minecraft:uniform"})
    return (json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n").encode("utf-8")


def _png_bytes(image: Image.Image) -> bytes:
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=False, compress_level=9)
    return output.getvalue()


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=FIXED_ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    return info


def _write_pack(output: Path, files: dict[str, bytes]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(f".{output.name}.tmp")
    try:
        with zipfile.ZipFile(temporary, "w", allowZip64=True) as archive:
            for name in sorted(files):
                archive.writestr(_zip_info(name), files[name])
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)


def build_pack(
    source: Path,
    output: Path,
    font_size: int = DEFAULT_FONT_SIZE,
    columns: int = DEFAULT_COLUMNS,
    display_height: int = DEFAULT_DISPLAY_HEIGHT,
    display_ascent: int = DEFAULT_DISPLAY_ASCENT,
) -> dict[str, int]:
    if font_size <= 0:
        raise ValueError("font size must be positive")
    if columns <= 0:
        raise ValueError("columns must be positive")
    if display_height <= 0:
        raise ValueError("display height must be positive")
    if not 0 < display_ascent <= display_height:
        raise ValueError("display ascent must be between 1 and display height")
    font_bytes, license_text, readme = _load_source(source)
    font = ImageFont.truetype(io.BytesIO(font_bytes), font_size)
    codepoints = _font_codepoints(font_bytes)
    glyphs, spaces = _measure_glyphs(font, codepoints)
    atlas, char_rows, cell_height, ascent = _render_atlas(font, glyphs, columns)

    pack_meta = {
        "pack": {
            "pack_format": PACK_FORMAT,
            "description": "Pokemon BW default font for Cobbleventure",
        }
    }
    files = {
        "LICENSES/Pokemon-BW-license.txt": license_text,
        "LICENSES/Pokemon-BW-readme.txt": readme,
        "assets/minecraft/font/default.json": _font_definition(
            char_rows, spaces, display_height, display_ascent
        ),
        "assets/minecraft/textures/font/pokemon_bw.png": _png_bytes(atlas),
        "pack.mcmeta": (json.dumps(pack_meta, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    }
    _write_pack(output, files)
    return {
        "glyphs": len(glyphs),
        "spaces": len(spaces),
        "atlas_width": atlas.width,
        "atlas_height": atlas.height,
        "cell_height": cell_height,
        "ascent": ascent,
        "display_height": display_height,
        "display_ascent": display_ascent,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="FontStruct ZIP containing one TTF")
    parser.add_argument("output", type=Path, help="Minecraft resource-pack ZIP")
    parser.add_argument("--font-size", type=int, default=DEFAULT_FONT_SIZE)
    parser.add_argument("--columns", type=int, default=DEFAULT_COLUMNS)
    parser.add_argument("--display-height", type=int, default=DEFAULT_DISPLAY_HEIGHT)
    parser.add_argument("--display-ascent", type=int, default=DEFAULT_DISPLAY_ASCENT)
    args = parser.parse_args()

    result = build_pack(
        args.source,
        args.output,
        args.font_size,
        args.columns,
        args.display_height,
        args.display_ascent,
    )
    print(
        "Built bitmap font pack: "
        f"{result['glyphs']} glyphs, {result['spaces']} spaces, "
        f"atlas {result['atlas_width']}x{result['atlas_height']}, "
        f"cell height {result['cell_height']}, ascent {result['ascent']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

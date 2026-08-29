#!/usr/bin/env python3
"""Fill glass-counter atlases with deterministic tileable mineral noise."""

from __future__ import annotations

import json
import random
import shutil
from collections import Counter
from pathlib import Path

from PIL import Image


WORKSPACE_ROOT = Path(__file__).resolve().parent
TEXTURE_ROOT = (
    WORKSPACE_ROOT
    / "assets/cobbleventure_theme_blocks/textures/block"
)
BACKUP_ROOT = WORKSPACE_ROOT / "recovery/double_glass_counter/pre-filled-noise-20260829"
BLUE_SINGLE_PIXEL_BACKUP_ROOT = (
    WORKSPACE_ROOT
    / "recovery/double_glass_counter/pre-blue-single-pixel-noise-20260829"
)
TARGETS = {
    "dobule_glass_counter_blue.png": (69, 118, 200),
    "dobule_glass_counter_basic.png": (255, 255, 255),
    "dobule_glass_counter_goods_bottom.png": (82, 82, 106),
    "dobule_glass_counter_glass.png": (156, 213, 255),
}
BLUE_TEXTURE = "dobule_glass_counter_blue.png"


def build_pattern() -> list[list[int]]:
    rng = random.Random(0xC0BB1E)
    field = [[rng.uniform(-1.0, 1.0) for _ in range(16)] for _ in range(16)]
    for _ in range(2):
        field = [
            [
                sum(
                    field[(y + dy) % 16][(x + dx) % 16]
                    for dy in (-1, 0, 1)
                    for dx in (-1, 0, 1)
                ) / 9.0
                for x in range(16)
            ]
            for y in range(16)
        ]
    mean = sum(map(sum, field)) / 256.0
    centered = [[value - mean for value in row] for row in field]
    extent = max(abs(value) for row in centered for value in row)
    pattern = []
    for row in centered:
        output_row = []
        for value in row:
            normalized = value / extent
            if normalized <= -0.62:
                output_row.append(-4)
            elif normalized <= -0.22:
                output_row.append(-2)
            elif normalized < 0.22:
                output_row.append(0)
            elif normalized < 0.62:
                output_row.append(2)
            else:
                output_row.append(4)
        pattern.append(output_row)
    return pattern


def varied(color: tuple[int, int, int], offset: int) -> tuple[int, int, int, int]:
    return (*[max(0, min(255, channel + offset)) for channel in color], 255)


def varied_blue(color: tuple[int, int, int], offset: int) -> tuple[int, int, int, int]:
    channel_offsets = (offset * 2, round(offset * 1.5), offset)
    return (*[
        max(0, min(255, channel + channel_offset))
        for channel, channel_offset in zip(color, channel_offsets)
    ], 255)


def build_single_pixel_noise(width: int, height: int) -> list[list[int]]:
    """Return subtle deterministic noise with one independent sample per pixel."""
    rng = random.Random(0xB10E128)
    shades = (-3, -2, -1, 0, 1, 2, 3)
    weights = (6, 12, 20, 24, 20, 12, 6)
    return [rng.choices(shades, weights=weights, k=width) for _ in range(height)]


def main() -> None:
    BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    pattern = build_pattern()
    reports = []
    for filename, base_color in TARGETS.items():
        destination = TEXTURE_ROOT / filename
        backup = BACKUP_ROOT / filename
        if not backup.exists():
            shutil.copy2(destination, backup)

        if filename == BLUE_TEXTURE:
            BLUE_SINGLE_PIXEL_BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
            blue_backup = BLUE_SINGLE_PIXEL_BACKUP_ROOT / filename
            if not blue_backup.exists():
                shutil.copy2(destination, blue_backup)

        with Image.open(backup) as source:
            size = source.size
        output = Image.new("RGBA", size)
        blue_noise = build_single_pixel_noise(*size) if filename == BLUE_TEXTURE else None
        shades: Counter[int] = Counter()
        for y in range(size[1]):
            for x in range(size[0]):
                if filename == BLUE_TEXTURE:
                    # Every atlas pixel gets its own subtle variation. Do not
                    # enlarge a 16px pattern into visible 8x8 color blocks.
                    offset = blue_noise[y][x]
                else:
                    offset = pattern[y % 16][x % 16]
                output.putpixel(
                    (x, y),
                    varied_blue(base_color, offset)
                    if filename == BLUE_TEXTURE
                    else varied(base_color, offset),
                )
                shades[offset] += 1
        output.save(destination, format="PNG", optimize=True)
        reports.append({
            "texture": filename,
            "size": list(size),
            "base_color": "#" + "".join(f"{channel:02X}" for channel in base_color),
            "opaque_pixels": size[0] * size[1],
            "shade_counts": dict(sorted(shades.items())),
        })
    print(json.dumps(reports, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

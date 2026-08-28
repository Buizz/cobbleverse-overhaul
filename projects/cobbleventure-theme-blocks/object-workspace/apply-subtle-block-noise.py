#!/usr/bin/env python3
"""Apply deterministic, very low-contrast mineral noise to flat base colors."""

from __future__ import annotations

import json
import random
import shutil
from collections import Counter
from pathlib import Path

from PIL import Image


WORKSPACE_ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = WORKSPACE_ROOT.parent
TEXTURE_ROOT = (
    PROJECT_ROOT
    / "src/main/resources/assets/cobbleventure_theme_blocks/textures/block"
)
BACKUP_ROOT = WORKSPACE_ROOT / "recovery/block-textures/pre-subtle-noise-20260829"

# Only the exact flat base color is varied. Existing bands, cracks and chevrons
# retain their original colors and pixel boundaries.
TARGETS = {
    "soft_cream_block.png": (0xD5, 0xD5, 0xAC),
    "house_cream_base_wall.png": (0xD5, 0xD5, 0xAC),
    "underground_pale_wall.png": (0xD5, 0xDE, 0xC5),
    "underground_cracked_wall.png": (0xD5, 0xDE, 0xC5),
    "casino_sky_wall.png": (0xA8, 0xC8, 0xE8),
    "casino_sky_chevron_wall.png": (0xA8, 0xC8, 0xE8),
}


def build_noise_pattern() -> list[list[int]]:
    """Create tileable, softly clustered 16x16 noise in five tiny steps."""
    rng = random.Random(0xC0BB1E)
    field = [[rng.uniform(-1.0, 1.0) for _ in range(16)] for _ in range(16)]

    # Toroidal blur keeps opposite texture edges compatible when tiled.
    for _ in range(2):
        field = [
            [
                sum(
                    field[(y + offset_y) % 16][(x + offset_x) % 16]
                    for offset_y in (-1, 0, 1)
                    for offset_x in (-1, 0, 1)
                )
                / 9.0
                for x in range(16)
            ]
            for y in range(16)
        ]

    mean = sum(map(sum, field)) / 256.0
    centered = [[value - mean for value in row] for row in field]
    extent = max(abs(value) for row in centered for value in row)

    pattern: list[list[int]] = []
    for row in centered:
        output_row = []
        for value in row:
            normalized = value / extent
            if normalized <= -0.62:
                offset = -4
            elif normalized <= -0.22:
                offset = -2
            elif normalized < 0.22:
                offset = 0
            elif normalized < 0.62:
                offset = 2
            else:
                offset = 4
            output_row.append(offset)
        pattern.append(output_row)
    return pattern


def vary_color(color: tuple[int, int, int], offset: int) -> tuple[int, int, int]:
    return tuple(max(0, min(255, channel + offset)) for channel in color)


def main() -> None:
    BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    pattern = build_noise_pattern()
    reports = []

    for filename, base_color in TARGETS.items():
        destination = TEXTURE_ROOT / filename
        backup = BACKUP_ROOT / filename
        if not backup.exists():
            shutil.copy2(destination, backup)

        with Image.open(backup) as source_image:
            image = source_image.convert("RGBA")

        changed = 0
        shades: Counter[int] = Counter()
        for y in range(image.height):
            for x in range(image.width):
                red, green, blue, alpha = image.getpixel((x, y))
                if (red, green, blue) != base_color:
                    continue
                offset = pattern[y % 16][x % 16]
                varied = vary_color(base_color, offset)
                image.putpixel((x, y), (*varied, alpha))
                shades[offset] += 1
                changed += int(offset != 0)

        image.save(destination, format="PNG", optimize=True)
        reports.append(
            {
                "texture": filename,
                "base_color": "#" + "".join(f"{channel:02X}" for channel in base_color),
                "changed_pixels": changed,
                "shade_counts": dict(sorted(shades.items())),
            }
        )

    print(json.dumps(reports, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

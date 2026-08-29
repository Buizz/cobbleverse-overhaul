#!/usr/bin/env python3
"""Generate a LabPBR specular atlas for the packed research device texture."""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from PIL import Image


WORKSPACE_ROOT = Path(__file__).resolve().parent
TEXTURE_ROOT = WORKSPACE_ROOT / "assets/cobbleventure_theme_blocks/textures/block"
ALBEDO = TEXTURE_ROOT / "research_device_1_texture.png"
SPECULAR = TEXTURE_ROOT / "research_device_1_texture_s.png"


def material_for(red: int, green: int, blue: int, alpha: int) -> tuple[str, tuple[int, int, int, int]]:
    if alpha == 0:
        return "unused", (0, 0, 0, 255)

    # LabPBR: R=smoothness, G=F0/metal ID, B=porosity/SSS,
    # A=emission (255 means emission data is unused).
    if red - green > 40 and red - blue > 35:
        return "glossy_red_paint", (220, 12, 0, 255)
    if blue - red > 35 and blue - green > 25:
        return "glossy_blue_paint", (196, 12, 0, 255)

    brightness = max(red, green, blue)
    if brightness >= 205:
        return "brushed_aluminum", (175, 232, 0, 255)
    return "matte_iron", (135, 230, 0, 255)


def main() -> None:
    with Image.open(ALBEDO) as source:
        albedo = source.convert("RGBA")
    output = Image.new("RGBA", albedo.size, (0, 0, 0, 255))
    counts: Counter[str] = Counter()
    for y in range(albedo.height):
        for x in range(albedo.width):
            material, pixel = material_for(*albedo.getpixel((x, y)))
            output.putpixel((x, y), pixel)
            counts[material] += 1
    output.save(SPECULAR, format="PNG", optimize=True)
    print(json.dumps({
        "albedo": str(ALBEDO),
        "specular": str(SPECULAR),
        "size": list(albedo.size),
        "materials": dict(sorted(counts.items())),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

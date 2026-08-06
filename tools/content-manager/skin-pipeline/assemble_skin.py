#!/usr/bin/env python3
"""Build a clean Minecraft skin from an AI-generated body-part concept atlas."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image


UV_LAYOUT = {
    "head": {
        "base": {"top": (8, 0), "bottom": (16, 0), "right": (0, 8), "front": (8, 8), "left": (16, 8), "back": (24, 8)},
        "overlay": {"top": (40, 0), "bottom": (48, 0), "right": (32, 8), "front": (40, 8), "left": (48, 8), "back": (56, 8)},
        "sizes": {"top": (8, 8), "bottom": (8, 8), "right": (8, 8), "front": (8, 8), "left": (8, 8), "back": (8, 8)},
    },
    "body": {
        "base": {"top": (20, 16), "bottom": (28, 16), "right": (16, 20), "front": (20, 20), "left": (28, 20), "back": (32, 20)},
        "overlay": {"top": (20, 32), "bottom": (28, 32), "right": (16, 36), "front": (20, 36), "left": (28, 36), "back": (32, 36)},
        "sizes": {"top": (8, 4), "bottom": (8, 4), "right": (4, 12), "front": (8, 12), "left": (4, 12), "back": (8, 12)},
    },
    "right_arm": {
        "base": {"top": (44, 16), "bottom": (48, 16), "right": (40, 20), "front": (44, 20), "left": (48, 20), "back": (52, 20)},
        "overlay": {"top": (44, 32), "bottom": (48, 32), "right": (40, 36), "front": (44, 36), "left": (48, 36), "back": (52, 36)},
        "sizes": {face: (4, 4) if face in {"top", "bottom"} else (4, 12) for face in ("top", "bottom", "right", "front", "left", "back")},
    },
    "left_arm": {
        "base": {"top": (36, 48), "bottom": (40, 48), "right": (32, 52), "front": (36, 52), "left": (40, 52), "back": (44, 52)},
        "overlay": {"top": (52, 48), "bottom": (56, 48), "right": (48, 52), "front": (52, 52), "left": (56, 52), "back": (60, 52)},
        "sizes": {face: (4, 4) if face in {"top", "bottom"} else (4, 12) for face in ("top", "bottom", "right", "front", "left", "back")},
    },
    "right_leg": {
        "base": {"top": (4, 16), "bottom": (8, 16), "right": (0, 20), "front": (4, 20), "left": (8, 20), "back": (12, 20)},
        "overlay": {"top": (4, 32), "bottom": (8, 32), "right": (0, 36), "front": (4, 36), "left": (8, 36), "back": (12, 36)},
        "sizes": {face: (4, 4) if face in {"top", "bottom"} else (4, 12) for face in ("top", "bottom", "right", "front", "left", "back")},
    },
    "left_leg": {
        "base": {"top": (20, 48), "bottom": (24, 48), "right": (16, 52), "front": (20, 52), "left": (24, 52), "back": (28, 52)},
        "overlay": {"top": (4, 48), "bottom": (8, 48), "right": (0, 52), "front": (4, 52), "left": (8, 52), "back": (12, 52)},
        "sizes": {face: (4, 4) if face in {"top", "bottom"} else (4, 12) for face in ("top", "bottom", "right", "front", "left", "back")},
    },
}


def parse_hex(value: str) -> tuple[int, int, int]:
    value = value.removeprefix("#")
    if len(value) != 6:
        raise ValueError(f"색상은 #RRGGBB 형식이어야 합니다: {value}")
    return tuple(int(value[index:index + 2], 16) for index in (0, 2, 4))


def color_distance(left: tuple[int, ...], right: tuple[int, ...]) -> int:
    return sum((left[index] - right[index]) ** 2 for index in range(3))


def remove_chroma(image: Image.Image, key: tuple[int, int, int], threshold: int) -> Image.Image:
    rgba = image.convert("RGBA")
    limit = threshold * threshold
    source_pixels = rgba.get_flattened_data() if hasattr(rgba, "get_flattened_data") else rgba.getdata()
    rgba.putdata([
        (red, green, blue, 0) if color_distance((red, green, blue), key) <= limit else (red, green, blue, alpha)
        for red, green, blue, alpha in source_pixels
    ])
    return rgba


def crop_face(atlas: Image.Image, box: list[int], size: tuple[int, int], background: tuple[int, int, int]) -> Image.Image:
    crop = atlas.crop(tuple(box))
    alpha_box = crop.getchannel("A").getbbox()
    if alpha_box:
        crop = crop.crop(alpha_box)
    target_ratio = size[0] / size[1]
    crop_ratio = crop.width / max(1, crop.height)
    if crop_ratio > target_ratio:
        width = max(1, round(crop.height * target_ratio))
        left = (crop.width - width) // 2
        crop = crop.crop((left, 0, left + width, crop.height))
    elif crop_ratio < target_ratio:
        height = max(1, round(crop.width / target_ratio))
        top = (crop.height - height) // 2
        crop = crop.crop((0, top, crop.width, top + height))
    crop = crop.resize(size, Image.Resampling.BOX)
    base = Image.new("RGBA", size, (*background, 255))
    base.alpha_composite(crop)
    return base


def quantize(image: Image.Image, colors: int) -> Image.Image:
    return image.convert("RGB").quantize(colors=colors, method=Image.Quantize.MEDIANCUT).convert("RGBA")


def equipment_base_skin(skin: Image.Image) -> Image.Image:
    """Remove the full head overlay so a real helmet item can replace the hat."""
    result = skin.copy()
    result.paste((0, 0, 0, 0), (32, 0, 64, 16))
    return result


def armor_texture(skin: Image.Image) -> Image.Image:
    """Move the player head overlay UVs onto the vanilla armor helmet UVs."""
    result = Image.new("RGBA", (64, 32))
    result.alpha_composite(skin.crop((32, 0, 64, 16)), (0, 0))
    return result


def cap_item_icon(palette: list[str]) -> Image.Image:
    colors = [(*parse_hex(value), 255) for value in palette]
    while len(colors) < 4:
        colors.append(colors[-1] if colors else (31, 63, 132, 255))
    icon = Image.new("RGBA", (16, 16))
    pixels = icon.load()
    for y in range(3, 11):
        inset = max(0, 5 - y)
        for x in range(3 + inset, 13 - inset):
            pixels[x, y] = colors[0 if y < 8 else 1]
    for x in range(4, 12):
        pixels[x, 3] = colors[2]
    for x in range(8, 15):
        pixels[x, 10] = colors[2 if x > 12 else 1]
    pixels[7, 4] = colors[3]
    pixels[8, 4] = colors[3]
    return icon


def write_equipment_outputs(skin: Image.Image, manifest: dict, root: Path) -> list[Path]:
    outputs = manifest.get("equipment_outputs")
    if not isinstance(outputs, dict):
        return []
    images = {
        "base_skin": equipment_base_skin(skin),
        "armor_texture": armor_texture(skin),
        "item_icon": cap_item_icon(manifest.get("item_icon_palette", [])),
    }
    written: list[Path] = []
    for key, image in images.items():
        target = (root / outputs[key]).resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        image.save(target, format="PNG", optimize=True)
        written.append(target)
    return written


def split_overlay(face: Image.Image, selectors: list[tuple[int, int, int]], tolerance: int) -> tuple[Image.Image, Image.Image]:
    if not selectors:
        return face, Image.new("RGBA", face.size)
    base = face.copy()
    overlay = Image.new("RGBA", face.size)
    base_pixels = base.load()
    overlay_pixels = overlay.load()
    limit = tolerance * tolerance
    for y in range(face.height):
        for x in range(face.width):
            pixel = face.getpixel((x, y))
            if any(color_distance(pixel, selector) <= limit for selector in selectors):
                overlay_pixels[x, y] = pixel
                neighbours = [
                    face.getpixel((nx, ny))
                    for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))
                    if 0 <= nx < face.width and 0 <= ny < face.height
                    and not any(color_distance(face.getpixel((nx, ny)), selector) <= limit for selector in selectors)
                ]
                if neighbours:
                    base_pixels[x, y] = min(neighbours, key=lambda item: color_distance(item, pixel))
    return base, overlay


def assemble(manifest_path: Path, output_override: Path | None = None) -> Path:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    root = manifest_path.parent
    atlas = Image.open(root / manifest["concept_atlas"])
    atlas = remove_chroma(atlas, parse_hex(manifest.get("chroma_key", "#ff00ff")), int(manifest.get("chroma_threshold", 70)))
    background = parse_hex(manifest.get("fallback_color", "#e7ad7a"))
    palette_colors = int(manifest.get("palette_colors", 20))
    overlay_colors = [parse_hex(color) for color in manifest.get("overlay_colors", [])]
    overlay_tolerance = int(manifest.get("overlay_tolerance", 58))
    skin = Image.new("RGBA", (64, 64))

    for part_name, part_spec in manifest["parts"].items():
        layout = UV_LAYOUT[part_name]
        for face_name, box in part_spec.items():
            size = layout["sizes"][face_name]
            face = quantize(crop_face(atlas, box, size, background), palette_colors)
            selectors = overlay_colors if part_name in set(manifest.get("overlay_parts", [])) else []
            base, overlay = split_overlay(face, selectors, overlay_tolerance)
            skin.alpha_composite(base, layout["base"][face_name])
            if overlay.getbbox():
                skin.alpha_composite(overlay, layout["overlay"][face_name])

    output = output_override or (root / manifest["output"])
    output.parent.mkdir(parents=True, exist_ok=True)
    skin.save(output, format="PNG", optimize=True)
    if output_override is None:
        write_equipment_outputs(skin, manifest, root)
    return output.resolve()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="스킨 작업 manifest.json")
    parser.add_argument("--output", type=Path, help="manifest의 출력 경로 대신 사용할 경로")
    args = parser.parse_args()
    print(assemble(args.manifest.resolve(), args.output.resolve() if args.output else None))


if __name__ == "__main__":
    main()

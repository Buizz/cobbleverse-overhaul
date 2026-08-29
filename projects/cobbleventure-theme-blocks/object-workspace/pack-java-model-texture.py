#!/usr/bin/env python3
"""Pack the used UV islands of a single-texture Java block model."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from PIL import Image


def next_power_of_two(value: int) -> int:
    return 1 << max(0, value - 1).bit_length()


def overlaps(a: dict, b: dict) -> bool:
    return not (
        a["right"] <= b["left"]
        or b["right"] <= a["left"]
        or a["bottom"] <= b["top"]
        or b["bottom"] <= a["top"]
    )


def shelf_pack(items: list[dict], size: int) -> dict[tuple, tuple[int, int]] | None:
    placements: dict[tuple, tuple[int, int]] = {}
    x = y = row_height = 0
    for item in sorted(items, key=lambda entry: (-entry["slot_h"], -entry["slot_w"], entry["key"])):
        if x + item["slot_w"] > size:
            x = 0
            y += row_height
            row_height = 0
        if y + item["slot_h"] > size:
            return None
        placements[item["key"]] = (x, y)
        x += item["slot_w"]
        row_height = max(row_height, item["slot_h"])
    return placements


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("texture", type=Path)
    parser.add_argument("--output-model", required=True, type=Path)
    parser.add_argument("--output-texture", required=True, type=Path)
    parser.add_argument("--padding", type=int, default=1)
    args = parser.parse_args()

    model = json.loads(args.model.read_text(encoding="utf-8-sig"))
    source = Image.open(args.texture).convert("RGBA")
    source_w, source_h = source.size
    padding = max(0, args.padding)
    regions: dict[tuple[int, int, int, int], dict] = {}
    faces: list[tuple[dict, tuple[int, int, int, int], list[float]]] = []

    for element in model.get("elements", []):
        for face in element.get("faces", {}).values():
            uv = face.get("uv")
            if face.get("texture") is None or not uv or len(uv) != 4:
                continue
            values = [float(value) for value in uv]
            pixels = [
                values[0] * source_w / 16.0,
                values[1] * source_h / 16.0,
                values[2] * source_w / 16.0,
                values[3] * source_h / 16.0,
            ]
            left = math.floor(min(pixels[0], pixels[2]) + 1e-7)
            top = math.floor(min(pixels[1], pixels[3]) + 1e-7)
            right = math.ceil(max(pixels[0], pixels[2]) - 1e-7)
            bottom = math.ceil(max(pixels[1], pixels[3]) - 1e-7)
            if left < 0 or top < 0 or right > source_w or bottom > source_h:
                raise ValueError(f"텍스처 밖의 UV입니다: {uv}")
            key = (left, top, right, bottom)
            regions.setdefault(key, {"key": key, "left": left, "top": top, "right": right, "bottom": bottom})
            faces.append((face, key, pixels))

    components: list[dict] = []
    remaining = list(regions.values())
    while remaining:
        component = remaining.pop(0).copy()
        component["members"] = {component["key"]}
        changed = True
        while changed:
            changed = False
            for region in remaining[:]:
                if overlaps(component, region):
                    component["left"] = min(component["left"], region["left"])
                    component["top"] = min(component["top"], region["top"])
                    component["right"] = max(component["right"], region["right"])
                    component["bottom"] = max(component["bottom"], region["bottom"])
                    component["members"].add(region["key"])
                    remaining.remove(region)
                    changed = True
        component["key"] = (component["left"], component["top"], component["right"], component["bottom"])
        component["width"] = component["right"] - component["left"]
        component["height"] = component["bottom"] - component["top"]
        component["slot_w"] = component["width"] + padding * 2
        component["slot_h"] = component["height"] + padding * 2
        components.append(component)

    region_to_component = {
        member: component for component in components for member in component["members"]
    }
    atlas_size = max(16, next_power_of_two(max(max(item["slot_w"], item["slot_h"]) for item in components)))
    placements = None
    while atlas_size <= 4096:
        placements = shelf_pack(components, atlas_size)
        if placements is not None:
            break
        atlas_size *= 2
    if placements is None:
        raise ValueError("4096×4096 안에 UV 영역을 패킹할 수 없습니다.")

    atlas = Image.new("RGBA", (atlas_size, atlas_size), (0, 0, 0, 0))
    destinations: dict[tuple, tuple[int, int]] = {}
    for component in components:
        slot_x, slot_y = placements[component["key"]]
        dest_x, dest_y = slot_x + padding, slot_y + padding
        destinations[component["key"]] = (dest_x, dest_y)
        for py in range(-padding, component["height"] + padding):
            sy = component["top"] + min(max(py, 0), component["height"] - 1)
            for px in range(-padding, component["width"] + padding):
                sx = component["left"] + min(max(px, 0), component["width"] - 1)
                atlas.putpixel((dest_x + px, dest_y + py), source.getpixel((sx, sy)))

    for face, region_key, pixels in faces:
        component = region_to_component[region_key]
        dest_x, dest_y = destinations[component["key"]]
        translated = [
            dest_x + pixels[0] - component["left"],
            dest_y + pixels[1] - component["top"],
            dest_x + pixels[2] - component["left"],
            dest_y + pixels[3] - component["top"],
        ]
        face["uv"] = [round(value * 16.0 / atlas_size, 6) for value in translated]

    model["texture_size"] = [atlas_size, atlas_size]
    args.output_model.parent.mkdir(parents=True, exist_ok=True)
    args.output_texture.parent.mkdir(parents=True, exist_ok=True)
    args.output_model.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    atlas.save(args.output_texture, optimize=True)

    report = {
        "source_model": str(args.model.resolve()),
        "source_texture": str(args.texture.resolve()),
        "output_model": str(args.output_model.resolve()),
        "output_texture": str(args.output_texture.resolve()),
        "source_size": [source_w, source_h],
        "packed_size": [atlas_size, atlas_size],
        "faces": len(faces),
        "source_regions": len(regions),
        "packed_regions": len(components),
        "padding": padding,
    }
    report_path = args.output_model.with_suffix(".pack-report.json")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

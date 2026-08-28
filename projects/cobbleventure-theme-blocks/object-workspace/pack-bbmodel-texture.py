#!/usr/bin/env python3
"""Pack the UV regions used by a single-texture Blockbench model.

The source .bbmodel is never modified. Exact source pixels are copied to a new
power-of-two atlas, and every face UV is translated to the matching new region.
Overlapping but differently-sized source regions are duplicated intentionally so
their appearance remains unchanged.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import math
from pathlib import Path

from PIL import Image


def next_power_of_two(value: int) -> int:
    return 1 << max(0, value - 1).bit_length()


def light_emission_data(element: dict) -> dict | None:
    """Translate Blockbench emission into NeoForge 1.21.1 extra face data."""
    emission = max(0, min(15, int(element.get("light_emission", 0) or 0)))
    if emission == 0:
        return None
    return {
        "block_light": emission,
        "sky_light": emission,
        "ambient_occlusion": False,
    }


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


def decode_texture(texture: dict) -> Image.Image:
    source = texture.get("source", "")
    if not source.startswith("data:image/png;base64,"):
        raise ValueError("내장 PNG 텍스처가 있는 .bbmodel만 지원합니다.")
    data = base64.b64decode(source.split(",", 1)[1])
    return Image.open(io.BytesIO(data)).convert("RGBA")


def encode_texture(image: Image.Image) -> str:
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(output.getvalue()).decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("--output-model", type=Path)
    parser.add_argument("--output-texture", type=Path)
    parser.add_argument("--output-java-model", type=Path)
    parser.add_argument("--namespace", default="cobbleventure_theme_blocks")
    parser.add_argument("--padding", type=int, default=1)
    args = parser.parse_args()

    source_model = args.model.resolve()
    model = json.loads(source_model.read_text(encoding="utf-8-sig"))
    textures = model.get("textures", [])
    if len(textures) != 1:
        raise ValueError(f"현재 패커는 텍스처가 1개인 모델만 지원합니다: {len(textures)}개")

    source_image = decode_texture(textures[0])
    source_w, source_h = source_image.size
    padding = max(0, args.padding)
    regions: dict[tuple, dict] = {}
    face_records: list[tuple[dict, tuple]] = []

    for element in model.get("elements", []):
        for face in element.get("faces", {}).values():
            uv = face.get("uv")
            if face.get("texture") is None or not uv or len(uv) != 4:
                continue
            u0, v0, u1, v1 = (float(value) for value in uv)
            left = math.floor(min(u0, u1))
            top = math.floor(min(v0, v1))
            right = math.ceil(max(u0, u1))
            bottom = math.ceil(max(v0, v1))
            if left < 0 or top < 0 or right > source_w or bottom > source_h:
                raise ValueError(f"텍스처 밖의 UV입니다: {uv}")
            if right <= left or bottom <= top:
                continue
            key = (left, top, right, bottom)
            regions.setdefault(
                key,
                {
                    "key": key,
                    "left": left,
                    "top": top,
                    "right": right,
                    "bottom": bottom,
                    "width": right - left,
                    "height": bottom - top,
                    "slot_w": right - left + padding * 2,
                    "slot_h": bottom - top + padding * 2,
                },
            )
            face_records.append((face, key))

    original_items = list(regions.values())
    if not original_items:
        raise ValueError("패킹할 UV 영역이 없습니다.")

    # Preserve overlapping/nested layouts as one source island. Duplicating each
    # individual face rectangle wastes space and can turn a 64px atlas into 128px.
    components: list[dict] = []
    for original in original_items:
        merged = {
            "members": {original["key"]},
            "left": original["left"],
            "top": original["top"],
            "right": original["right"],
            "bottom": original["bottom"],
        }
        changed = True
        while changed:
            changed = False
            remaining = []
            for component in components:
                intersects = not (
                    merged["right"] <= component["left"]
                    or component["right"] <= merged["left"]
                    or merged["bottom"] <= component["top"]
                    or component["bottom"] <= merged["top"]
                )
                if intersects:
                    merged["members"].update(component["members"])
                    merged["left"] = min(merged["left"], component["left"])
                    merged["top"] = min(merged["top"], component["top"])
                    merged["right"] = max(merged["right"], component["right"])
                    merged["bottom"] = max(merged["bottom"], component["bottom"])
                    changed = True
                else:
                    remaining.append(component)
            components = remaining
        components.append(merged)

    region_to_component: dict[tuple, tuple] = {}
    items = []
    for component in components:
        key = (component["left"], component["top"], component["right"], component["bottom"])
        for member in component["members"]:
            region_to_component[member] = key
        items.append(
            {
                "key": key,
                "left": component["left"],
                "top": component["top"],
                "right": component["right"],
                "bottom": component["bottom"],
                "width": component["right"] - component["left"],
                "height": component["bottom"] - component["top"],
                "slot_w": component["right"] - component["left"] + padding * 2,
                "slot_h": component["bottom"] - component["top"] + padding * 2,
            }
        )
    largest = max(max(item["slot_w"], item["slot_h"]) for item in items)
    atlas_size = max(16, next_power_of_two(largest))
    placements = None
    while atlas_size <= 4096:
        placements = shelf_pack(items, atlas_size)
        if placements is not None:
            break
        atlas_size *= 2
    if placements is None:
        raise ValueError("4096×4096 안에 UV 영역을 패킹할 수 없습니다.")

    atlas = Image.new("RGBA", (atlas_size, atlas_size), (0, 0, 0, 0))
    mapping: dict[tuple, tuple[int, int]] = {}
    for item in items:
        slot_x, slot_y = placements[item["key"]]
        dest_x, dest_y = slot_x + padding, slot_y + padding
        mapping[item["key"]] = (dest_x, dest_y)
        for py in range(-padding, item["height"] + padding):
            source_y = item["top"] + min(max(py, 0), item["height"] - 1)
            for px in range(-padding, item["width"] + padding):
                source_x = item["left"] + min(max(px, 0), item["width"] - 1)
                atlas.putpixel((dest_x + px, dest_y + py), source_image.getpixel((source_x, source_y)))

    for face, original_key in face_records:
        key = region_to_component[original_key]
        left, top, _, _ = key
        dest_x, dest_y = mapping[key]
        u0, v0, u1, v1 = (float(value) for value in face["uv"])
        translated = [dest_x + u0 - left, dest_y + v0 - top, dest_x + u1 - left, dest_y + v1 - top]
        face["uv"] = [int(value) if value.is_integer() else value for value in translated]

    output_model = (args.output_model or source_model.with_name(source_model.stem + "_packed.bbmodel")).resolve()
    default_texture = source_model.parents[4] / "textures" / "block" / "bed_single_texture_packed.png"
    output_texture = (args.output_texture or default_texture).resolve()
    output_model.parent.mkdir(parents=True, exist_ok=True)
    output_texture.parent.mkdir(parents=True, exist_ok=True)

    texture = textures[0]
    texture["name"] = output_texture.name
    texture["relative_path"] = "../../../../textures/block/" + output_texture.name
    texture["width"] = atlas_size
    texture["height"] = atlas_size
    texture["uv_width"] = atlas_size
    texture["uv_height"] = atlas_size
    texture["source"] = encode_texture(atlas)
    texture["internal"] = True
    texture["saved"] = True
    model.setdefault("resolution", {})["width"] = atlas_size
    model["resolution"]["height"] = atlas_size

    output_texture.write_bytes(base64.b64decode(texture["source"].split(",", 1)[1]))
    output_model.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    output_java_model = args.output_java_model.resolve() if args.output_java_model else None
    if output_java_model:
        java_elements = []
        uv_scale = 16.0 / atlas_size
        for element in model.get("elements", []):
            if element.get("export", True) is False:
                continue
            java_element = {
                "name": element.get("name", "cube"),
                "from": element["from"],
                "to": element["to"],
                "faces": {},
            }
            rotation = element.get("rotation")
            if rotation and rotation.get("angle", 0):
                java_element["rotation"] = {
                    key: rotation[key]
                    for key in ("angle", "axis", "origin", "rescale")
                    if key in rotation
                }
            if element.get("shade") is False:
                java_element["shade"] = False
            emission_data = light_emission_data(element)
            if emission_data is not None:
                java_element["neoforge_data"] = emission_data
            for face_name, face in element.get("faces", {}).items():
                if face.get("texture") is None:
                    continue
                java_face = {
                    "uv": [round(float(value) * uv_scale, 6) for value in face["uv"]],
                    "texture": "#0",
                }
                for key in ("rotation", "cullface", "tintindex"):
                    if key in face:
                        java_face[key] = face[key]
                java_element["faces"][face_name] = java_face
            java_elements.append(java_element)

        resource_name = output_texture.stem
        java_model = {
            "format_version": "1.21.11",
            "credit": "Packed from Blockbench by pack-bbmodel-texture.py",
            "parent": "minecraft:block/block",
            "texture_size": [atlas_size, atlas_size],
            "textures": {
                "0": f"{args.namespace}:block/{resource_name}",
                "particle": f"{args.namespace}:block/{resource_name}",
            },
            "elements": java_elements,
        }
        output_java_model.parent.mkdir(parents=True, exist_ok=True)
        output_java_model.write_text(json.dumps(java_model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    report = {
        "source_model": str(source_model),
        "output_model": str(output_model),
        "output_texture": str(output_texture),
        "output_java_model": str(output_java_model) if output_java_model else None,
        "source_size": [source_w, source_h],
        "packed_size": [atlas_size, atlas_size],
        "faces": len(face_records),
        "source_regions": len(original_items),
        "packed_regions": len(items),
        "padding": padding,
    }
    report_path = output_model.with_suffix(".pack-report.json")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

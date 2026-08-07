#!/usr/bin/env python3
"""Build a clean Minecraft skin from an AI-generated body-part concept atlas."""

from __future__ import annotations

import argparse
from collections import deque
import json
from pathlib import Path

from PIL import Image


PIPELINE_ROOT = Path(__file__).resolve().parent
RETOUCH_ROOT = PIPELINE_ROOT / "retouch"
GENERATED_RETOUCH_ROOT = RETOUCH_ROOT / "generated"
MANUAL_RETOUCH_ROOT = RETOUCH_ROOT / "manual"


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

SLIM_UV_LAYOUT = {
    **UV_LAYOUT,
    "right_arm": {
        "base": {"top": (44, 16), "bottom": (47, 16), "right": (40, 20), "front": (44, 20), "left": (47, 20), "back": (51, 20)},
        "overlay": {"top": (44, 32), "bottom": (47, 32), "right": (40, 36), "front": (44, 36), "left": (47, 36), "back": (51, 36)},
        "sizes": {"top": (3, 4), "bottom": (3, 4), "right": (4, 12), "front": (3, 12), "left": (4, 12), "back": (3, 12)},
    },
    "left_arm": {
        "base": {"top": (36, 48), "bottom": (39, 48), "right": (32, 52), "front": (36, 52), "left": (39, 52), "back": (43, 52)},
        "overlay": {"top": (52, 48), "bottom": (55, 48), "right": (48, 52), "front": (52, 52), "left": (55, 52), "back": (59, 52)},
        "sizes": {"top": (3, 4), "bottom": (3, 4), "right": (4, 12), "front": (3, 12), "left": (4, 12), "back": (3, 12)},
    },
}


def parse_hex(value: str) -> tuple[int, int, int]:
    value = value.removeprefix("#")
    if len(value) != 6:
        raise ValueError(f"색상은 #RRGGBB 형식이어야 합니다: {value}")
    return tuple(int(value[index:index + 2], 16) for index in (0, 2, 4))


def color_distance(left: tuple[int, ...], right: tuple[int, ...]) -> int:
    return sum((left[index] - right[index]) ** 2 for index in range(3))


def is_chroma_spill(pixel: tuple[int, ...], key: tuple[int, int, int], threshold: int) -> bool:
    red, green, blue = pixel[:3]
    if color_distance(pixel, key) <= threshold * threshold:
        return True
    key_is_magenta = key[0] > 200 and key[2] > 200 and key[1] < 80
    return (
        key_is_magenta
        and min(red, blue) >= 120
        and green <= max(red, blue) * 0.45
        and abs(red - blue) <= 80
    )


def remove_chroma(image: Image.Image, key: tuple[int, int, int], threshold: int) -> Image.Image:
    rgba = image.convert("RGBA")
    source_pixels = rgba.get_flattened_data() if hasattr(rgba, "get_flattened_data") else rgba.getdata()
    rgba.putdata([
        (red, green, blue, 0) if is_chroma_spill((red, green, blue), key, threshold) else (red, green, blue, alpha)
        for red, green, blue, alpha in source_pixels
    ])
    return rgba


def strip_exterior_outline(image: Image.Image, threshold: int = 58, depth: int | None = None) -> Image.Image:
    """Remove dark contour pixels close to a transparent exterior edge."""
    result = image.copy().convert("RGBA")
    width, height = result.size
    maximum_depth = depth if depth is not None else max(1, min(width, height) // 18)
    for _ in range(maximum_depth):
        pixels = result.load()
        removable: list[tuple[int, int]] = []
        for y in range(height):
            for x in range(width):
                red, green, blue, alpha = pixels[x, y]
                if alpha == 0 or max(red, green, blue) > threshold:
                    continue
                if any(
                    nx < 0 or ny < 0 or nx >= width or ny >= height or pixels[nx, ny][3] == 0
                    for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))
                ):
                    removable.append((x, y))
        if not removable:
            break
        for x, y in removable:
            pixels[x, y] = (0, 0, 0, 0)
    return result


def extend_edge_pixels(image: Image.Image, fallback: tuple[int, int, int]) -> Image.Image:
    """Fill transparent corners with the nearest opaque pixel for a rectangular UV face."""
    result = image.copy().convert("RGBA")
    width, height = result.size
    pixels = result.load()
    queue: deque[tuple[int, int]] = deque()
    visited = bytearray(width * height)
    for y in range(height):
        for x in range(width):
            if pixels[x, y][3] > 0:
                queue.append((x, y))
                visited[y * width + x] = 1
    if not queue:
        return Image.new("RGBA", result.size, (*fallback, 255))
    while queue:
        x, y = queue.popleft()
        source = pixels[x, y]
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if not (0 <= nx < width and 0 <= ny < height):
                continue
            offset = ny * width + nx
            if visited[offset]:
                continue
            visited[offset] = 1
            pixels[nx, ny] = (source[0], source[1], source[2], 255)
            queue.append((nx, ny))
    return result


def crop_face(
    atlas: Image.Image,
    box: list[int],
    size: tuple[int, int],
    background: tuple[int, int, int],
    outline_threshold: int = 58,
    vertical_anchor: str = "center",
) -> Image.Image:
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
        # Minecraft heads always occupy a fixed cube. When generated hair makes
        # a head panel too tall, discard the excess from the top so the lower
        # face pixels retain their original proportions instead of being
        # squeezed or cropped from both ends.
        top = crop.height - height if vertical_anchor == "bottom" else (crop.height - height) // 2
        crop = crop.crop((0, top, crop.width, top + height))
    crop = strip_exterior_outline(crop, outline_threshold)
    crop = extend_edge_pixels(crop, background)
    return crop.resize(size, Image.Resampling.NEAREST)


def quantize(image: Image.Image, colors: int) -> Image.Image:
    return image.convert("RGB").quantize(
        colors=colors,
        method=Image.Quantize.MEDIANCUT,
        dither=Image.Dither.NONE,
    ).convert("RGBA")


def remove_head_side_features(face: Image.Image, face_name: str) -> Image.Image:
    """Replace the front-facing portion of a head side with its adjacent texture column."""
    if face_name not in {"left", "right"} or face.size != (8, 8):
        return face
    result = face.copy()
    source_x = 4 if face_name == "left" else 3
    targets = range(0, 4) if face_name == "left" else range(4, 8)
    for y in range(result.height):
        source = result.getpixel((source_x, y))
        for x in targets:
            result.putpixel((x, y), source)
    return result


def connected_component_boxes(atlas: Image.Image, minimum_area: int = 80) -> list[list[int]]:
    """Find isolated non-transparent atlas parts after chroma removal."""
    alpha = atlas.getchannel("A")
    pixels = alpha.load()
    width, height = alpha.size
    visited = bytearray(width * height)
    boxes: list[list[int]] = []
    for y in range(height):
        for x in range(width):
            offset = y * width + x
            if visited[offset] or pixels[x, y] == 0:
                continue
            visited[offset] = 1
            queue = deque([(x, y)])
            left = right = x
            top = bottom = y
            area = 0
            while queue:
                px, py = queue.pop()
                area += 1
                left, right = min(left, px), max(right, px)
                top, bottom = min(top, py), max(bottom, py)
                for nx, ny in ((px - 1, py), (px + 1, py), (px, py - 1), (px, py + 1)):
                    if not (0 <= nx < width and 0 <= ny < height):
                        continue
                    neighbour = ny * width + nx
                    if visited[neighbour] or pixels[nx, ny] == 0:
                        continue
                    visited[neighbour] = 1
                    queue.append((nx, ny))
            if area >= minimum_area:
                boxes.append([left, top, right + 1, bottom + 1])
    return boxes


def _six_faces(boxes: list[list[int]]) -> dict[str, list[int]]:
    if len(boxes) < 4:
        raise ValueError("자동 UV 행에는 최소 네 개의 분리된 면이 필요합니다.")
    front, left, back, right = boxes[:4]
    top = boxes[4] if len(boxes) > 4 else front
    bottom = boxes[5] if len(boxes) > 5 else top
    return {"front": front, "left": left, "back": back, "right": right, "top": top, "bottom": bottom}


def auto_detect_parts(atlas: Image.Image, minimum_area: int = 80) -> dict[str, dict[str, list[int]]]:
    """Map four- to six-band concept layouts produced by the image workflow to Minecraft parts."""
    components = sorted(
        connected_component_boxes(atlas, minimum_area),
        key=lambda box: ((box[1] + box[3]) / 2, box[0]),
    )
    rows: list[list[list[int]]] = []
    row_centers: list[float] = []
    maximum_row_gap = atlas.height * 0.1
    for box in components:
        center_y = (box[1] + box[3]) / 2
        if not rows or center_y - row_centers[-1] > maximum_row_gap:
            rows.append([box])
            row_centers.append(center_y)
        else:
            rows[-1].append(box)
            row_centers[-1] = sum((item[1] + item[3]) / 2 for item in rows[-1]) / len(rows[-1])
    for row in rows:
        row.sort(key=lambda box: box[0])
    if len(rows) not in {4, 5, 6} or len(rows[0]) < 4 or len(rows[1]) < 4:
        raise ValueError(f"자동 UV 부위 감지에 실패했습니다: {[len(row) for row in rows]}")

    def limb_pair(boxes: list[list[int]]) -> tuple[dict[str, list[int]], dict[str, list[int]]]:
        if len(boxes) >= 8:
            midpoint = len(boxes) // 2
            return _six_faces(boxes[:midpoint]), _six_faces(boxes[midpoint:])
        right = _six_faces(boxes)
        left = {
            "front": right["right"], "left": right["back"], "back": right["left"],
            "right": right["front"], "top": right["top"], "bottom": right["bottom"],
        }
        return right, left

    if len(rows) == 4:
        right_arm, left_arm = limb_pair(rows[2])
        right_leg, left_leg = limb_pair(rows[3])
    elif len(rows) == 6:
        right_arm, left_arm = _six_faces(rows[2]), _six_faces(rows[3])
        right_leg, left_leg = _six_faces(rows[4]), _six_faces(rows[5])
    elif len(rows[2]) >= 8:
        right_arm, left_arm = limb_pair(rows[2])
        right_leg, left_leg = _six_faces(rows[3]), _six_faces(rows[4])
    else:
        right_arm, left_arm = _six_faces(rows[2]), _six_faces(rows[3])
        right_leg, left_leg = limb_pair(rows[4])
    head = _six_faces(rows[0])
    if len(rows[0]) == 4:
        # Four-view atlases deliberately omit top/bottom faces. Reuse the
        # hair-only back panel so front facial pixels can never leak upward.
        head["top"] = head["back"]
        head["bottom"] = head["back"]
    return {
        "head": head,
        "body": _six_faces(rows[1]),
        "right_arm": right_arm,
        "left_arm": left_arm,
        "right_leg": right_leg,
        "left_leg": left_leg,
    }


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


def load_manual_retouch(path: Path) -> Image.Image:
    with Image.open(path) as source:
        if source.size != (64, 64):
            raise ValueError(f"수동 리터치 스킨은 64x64 PNG여야 합니다: {path}")
        if source.format != "PNG":
            raise ValueError(f"수동 리터치 스킨은 PNG 형식이어야 합니다: {path}")
        return source.convert("RGBA")


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
    chroma_key = parse_hex(manifest.get("chroma_key", "#ff00ff"))
    chroma_threshold = int(manifest.get("chroma_threshold", 70))
    atlas = remove_chroma(atlas, chroma_key, chroma_threshold)
    background = parse_hex(manifest.get("fallback_color", "#e7ad7a"))
    palette_colors = int(manifest.get("palette_colors", 20))
    overlay_colors = [parse_hex(color) for color in manifest.get("overlay_colors", [])]
    overlay_tolerance = int(manifest.get("overlay_tolerance", 58))
    outline_threshold = int(manifest.get("outline_threshold", 58))
    model = manifest.get("model", "classic")
    if model not in {"classic", "slim"}:
        raise ValueError(f"지원하지 않는 팔 모델입니다: {model}")
    uv_layout = SLIM_UV_LAYOUT if model == "slim" else UV_LAYOUT
    skin = Image.new("RGBA", (64, 64))

    part_specs = manifest.get("parts")
    if part_specs is None and manifest.get("auto_layout") == "four_row_atlas_v1":
        part_specs = auto_detect_parts(atlas, int(manifest.get("component_minimum_area", 80)))
    if not isinstance(part_specs, dict):
        raise ValueError("manifest에 parts 또는 지원되는 auto_layout이 필요합니다.")

    for part_name, part_spec in part_specs.items():
        layout = uv_layout[part_name]
        for face_name, box in part_spec.items():
            size = layout["sizes"][face_name]
            face = quantize(
                crop_face(
                    atlas, box, size, background, outline_threshold,
                    vertical_anchor="bottom" if part_name == "head" else "center",
                ),
                palette_colors,
            )
            face = extend_edge_pixels(
                remove_chroma(face, chroma_key, chroma_threshold),
                background,
            )
            if part_name == "head":
                face = remove_head_side_features(face, face_name)
            selectors = overlay_colors if part_name in set(manifest.get("overlay_parts", [])) else []
            base, overlay = split_overlay(face, selectors, overlay_tolerance)
            skin.alpha_composite(base, layout["base"][face_name])
            if overlay.getbbox():
                skin.alpha_composite(overlay, layout["overlay"][face_name])

    published_skin = skin
    if output_override is None:
        slug = Path(manifest["output"]).stem
        GENERATED_RETOUCH_ROOT.mkdir(parents=True, exist_ok=True)
        generated_draft = GENERATED_RETOUCH_ROOT / f"{slug}.png"
        skin.save(generated_draft, format="PNG", optimize=True)
        manual_retouch = MANUAL_RETOUCH_ROOT / f"{slug}.png"
        if manual_retouch.is_file():
            published_skin = load_manual_retouch(manual_retouch)

    output = (output_override or (root / manifest["output"])).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    published_skin.save(output, format="PNG", optimize=True)
    if output_override is None:
        write_equipment_outputs(published_skin, manifest, root)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="스킨 작업 manifest.json")
    parser.add_argument("--output", type=Path, help="manifest의 출력 경로 대신 사용할 경로")
    args = parser.parse_args()
    print(assemble(args.manifest.resolve(), args.output.resolve() if args.output else None))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generate wood wall models whose dark grain is recessed on all six faces."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/cobbleventure_theme_blocks/models/block"
MODELS = {
    "underground_olive_band": (7, 15),
    "house_beige_panel_wall": (3, 7, 11, 15),
}


def occupied(x: int, y: int, z: int, grooves: tuple[int, ...]) -> bool:
    vertical_row = 15 - y
    horizontal_row = z
    recessed_vertical_side = (
        (x in (0, 15) or z in (0, 15)) and vertical_row in grooves
    )
    recessed_horizontal_side = y in (0, 15) and horizontal_row in grooves
    return not (recessed_vertical_side or recessed_horizontal_side)


def greedy_boxes(grooves: tuple[int, ...]) -> list[tuple[int, int, int, int, int, int]]:
    cells = [
        [
            [occupied(x, y, z, grooves) for x in range(16)]
            for z in range(16)
        ]
        for y in range(16)
    ]
    used = [[[False] * 16 for _ in range(16)] for _ in range(16)]
    boxes: list[tuple[int, int, int, int, int, int]] = []

    for y in range(16):
        for z in range(16):
            for x in range(16):
                if not cells[y][z][x] or used[y][z][x]:
                    continue

                x2 = x
                while x2 < 16 and cells[y][z][x2] and not used[y][z][x2]:
                    x2 += 1

                z2 = z + 1
                while z2 < 16 and all(
                    cells[y][z2][xx] and not used[y][z2][xx]
                    for xx in range(x, x2)
                ):
                    z2 += 1

                y2 = y + 1
                while y2 < 16 and all(
                    cells[y2][zz][xx] and not used[y2][zz][xx]
                    for zz in range(z, z2)
                    for xx in range(x, x2)
                ):
                    y2 += 1

                for yy in range(y, y2):
                    for zz in range(z, z2):
                        for xx in range(x, x2):
                            used[yy][zz][xx] = True
                boxes.append((x, y, z, x2, y2, z2))
    return boxes


def face_uv(face: str, box: tuple[int, int, int, int, int, int]) -> list[int]:
    x1, y1, z1, x2, y2, z2 = box
    if face in ("north", "south"):
        return [x1, 16 - y2, x2, 16 - y1]
    if face in ("east", "west"):
        return [z1, 16 - y2, z2, 16 - y1]
    return [x1, z1, x2, z2]


def has_exposure(
    face: str,
    box: tuple[int, int, int, int, int, int],
    grooves: tuple[int, ...],
) -> bool:
    x1, y1, z1, x2, y2, z2 = box
    if face == "north":
        return z1 == 0 or any(not occupied(x, y, z1 - 1, grooves) for y in range(y1, y2) for x in range(x1, x2))
    if face == "south":
        return z2 == 16 or any(not occupied(x, y, z2, grooves) for y in range(y1, y2) for x in range(x1, x2))
    if face == "west":
        return x1 == 0 or any(not occupied(x1 - 1, y, z, grooves) for y in range(y1, y2) for z in range(z1, z2))
    if face == "east":
        return x2 == 16 or any(not occupied(x2, y, z, grooves) for y in range(y1, y2) for z in range(z1, z2))
    if face == "down":
        return y1 == 0 or any(not occupied(x, y1 - 1, z, grooves) for z in range(z1, z2) for x in range(x1, x2))
    return y2 == 16 or any(not occupied(x, y2, z, grooves) for z in range(z1, z2) for x in range(x1, x2))


def generate(name: str, grooves: tuple[int, ...]) -> None:
    elements = []
    for index, box in enumerate(greedy_boxes(grooves)):
        faces = {}
        for face in ("north", "east", "south", "west", "up", "down"):
            if not has_exposure(face, box, grooves):
                continue
            face_data = {"uv": face_uv(face, box), "texture": "#wall"}
            coordinate = {
                "north": box[2],
                "east": box[3],
                "south": box[5],
                "west": box[0],
                "up": box[4],
                "down": box[1],
            }[face]
            if coordinate in (0, 16):
                face_data["cullface"] = face
            faces[face] = face_data
        elements.append(
            {
                "name": f"six_face_grain_{index:02d}",
                "from": list(box[:3]),
                "to": list(box[3:]),
                "faces": faces,
            }
        )

    model = {
        "textures": {
            "wall": f"cobbleventure_theme_blocks:block/{name}",
            "particle": f"cobbleventure_theme_blocks:block/{name}",
        },
        "elements": elements,
    }
    (ROOT / f"{name}.json").write_text(
        json.dumps(model, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"{name}: {len(elements)} elements")


if __name__ == "__main__":
    for model_name, groove_rows in MODELS.items():
        generate(model_name, groove_rows)

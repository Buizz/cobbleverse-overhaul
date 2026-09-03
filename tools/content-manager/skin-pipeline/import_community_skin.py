"""Import modern or legacy community skins, preserving Minecraft UV faces."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


CLASSIC_FACES = {
    "right_base": ((44, 16, 48, 20), (48, 16, 52, 20), (40, 20, 44, 32), (44, 20, 48, 32), (48, 20, 52, 32), (52, 20, 56, 32)),
    "right_overlay": ((44, 32, 48, 36), (48, 32, 52, 36), (40, 36, 44, 48), (44, 36, 48, 48), (48, 36, 52, 48), (52, 36, 56, 48)),
    "left_base": ((36, 48, 40, 52), (40, 48, 44, 52), (32, 52, 36, 64), (36, 52, 40, 64), (40, 52, 44, 64), (44, 52, 48, 64)),
    "left_overlay": ((52, 48, 56, 52), (56, 48, 60, 52), (48, 52, 52, 64), (52, 52, 56, 64), (56, 52, 60, 64), (60, 52, 64, 64)),
}

SLIM_FACES = {
    "right_base": ((44, 16, 47, 20), (47, 16, 50, 20), (40, 20, 44, 32), (44, 20, 47, 32), (47, 20, 51, 32), (51, 20, 54, 32)),
    "right_overlay": ((44, 32, 47, 36), (47, 32, 50, 36), (40, 36, 44, 48), (44, 36, 47, 48), (47, 36, 51, 48), (51, 36, 54, 48)),
    "left_base": ((36, 48, 39, 52), (39, 48, 42, 52), (32, 52, 36, 64), (36, 52, 39, 64), (39, 52, 43, 64), (43, 52, 46, 64)),
    "left_overlay": ((52, 48, 55, 52), (55, 48, 58, 52), (48, 52, 52, 64), (52, 52, 55, 64), (55, 52, 59, 64), (59, 52, 62, 64)),
}

ARM_AREAS = ((40, 16, 56, 48), (32, 48, 48, 64), (48, 48, 64, 64))


def modernize_legacy_skin(image: Image.Image) -> Image.Image:
    source = image.convert("RGBA")
    if source.size != (64, 32):
        raise ValueError(f"구형 Minecraft 스킨은 64x32여야 합니다: {source.size}")
    result = Image.new("RGBA", (64, 64))
    result.paste(source, (0, 0))
    # Legacy left limbs reuse mirrored right limbs. Side faces swap places.
    for source_x, target_x in ((0, 16), (40, 32)):
        faces = (
            ((4, 16, 8, 20), (4, 48)), ((8, 16, 12, 20), (8, 48)),
            ((8, 20, 12, 32), (0, 52)), ((4, 20, 8, 32), (4, 52)),
            ((0, 20, 4, 32), (8, 52)), ((12, 20, 16, 32), (12, 52)),
        )
        for (x1, y1, x2, y2), (tx, ty) in faces:
            face = source.crop((source_x + x1, y1, source_x + x2, y2))
            result.paste(face.transpose(Image.Transpose.FLIP_LEFT_RIGHT), (target_x + tx, ty))
    # Match Minecraft's opaque legacy-hat handling without erasing authored alpha.
    if source.getchannel("A").crop((32, 0, 64, 32)).getextrema()[0] >= 128:
        result.paste((0, 0, 0, 0), (32, 0, 64, 16))
    return result


def convert_arm_model(image: Image.Image, source_model: str, target_model: str) -> Image.Image:
    source = image.convert("RGBA")
    if source.size == (64, 32):
        if source_model != "classic":
            raise ValueError("구형 스킨의 원본 팔 모델은 classic이어야 합니다.")
        source = modernize_legacy_skin(source)
    if source.size != (64, 64):
        raise ValueError(f"Minecraft 스킨은 64x64여야 합니다: {source.size}")
    if source_model == target_model:
        return source.copy()

    layouts = {"classic": CLASSIC_FACES, "slim": SLIM_FACES}
    source_layout = layouts[source_model]
    target_layout = layouts[target_model]
    faces = {part: [source.crop(box) for box in boxes] for part, boxes in source_layout.items()}
    result = source.copy()
    for box in ARM_AREAS:
        result.paste((0, 0, 0, 0), box)
    for part, target_boxes in target_layout.items():
        for face, target_box in zip(faces[part], target_boxes):
            size = (target_box[2] - target_box[0], target_box[3] - target_box[1])
            if face.size != size:
                face = face.resize(size, Image.Resampling.NEAREST)
            result.paste(face, target_box)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--source-model", choices=("classic", "slim"), required=True)
    parser.add_argument("--target-model", choices=("classic", "slim"), required=True)
    args = parser.parse_args()
    with Image.open(args.source) as image:
        converted = convert_arm_model(image, args.source_model, args.target_model)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    converted.save(args.output, format="PNG", optimize=False)


if __name__ == "__main__":
    main()

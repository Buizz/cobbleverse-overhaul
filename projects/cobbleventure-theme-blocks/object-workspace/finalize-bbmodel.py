#!/usr/bin/env python3
"""Promote a finished single-texture .bbmodel to canonical editor/game assets."""

from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path


def clean_number(value: float) -> int | float:
    rounded = round(value, 6)
    return int(rounded) if float(rounded).is_integer() else rounded


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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--canonical-model", type=Path, required=True)
    parser.add_argument("--texture", type=Path, required=True)
    parser.add_argument("--java-model", type=Path, required=True)
    parser.add_argument("--namespace", default="cobbleventure_theme_blocks")
    args = parser.parse_args()

    source = args.source.resolve()
    canonical_model = args.canonical_model.resolve()
    texture_path = args.texture.resolve()
    java_model_path = args.java_model.resolve()
    model = json.loads(source.read_text(encoding="utf-8-sig"))
    textures = model.get("textures", [])
    if len(textures) != 1:
        raise ValueError(f"텍스처가 1개인 모델만 지원합니다: {len(textures)}개")

    texture = textures[0]
    data_uri = str(texture.get("source", ""))
    if not data_uri.startswith("data:image/png;base64,"):
        raise ValueError("최종 모델에 내장 PNG 텍스처가 없습니다.")
    png_bytes = base64.b64decode(data_uri.split(",", 1)[1])
    uv_width = int(texture.get("uv_width") or model.get("resolution", {}).get("width") or texture["width"])
    uv_height = int(texture.get("uv_height") or model.get("resolution", {}).get("height") or texture["height"])
    if uv_width != uv_height:
        raise ValueError(f"Java 모델 내보내기는 정사각 UV 공간만 지원합니다: {uv_width}x{uv_height}")

    texture["name"] = texture_path.name
    texture["relative_path"] = "../../../../textures/block/" + texture_path.name
    texture["width"] = uv_width
    texture["height"] = uv_height
    texture["uv_width"] = uv_width
    texture["uv_height"] = uv_height
    texture["internal"] = True
    texture["saved"] = True
    model.setdefault("resolution", {})["width"] = uv_width
    model["resolution"]["height"] = uv_height

    java_elements = []
    scale = 16.0 / uv_width
    face_count = 0
    for element in model.get("elements", []):
        if element.get("export", True) is False:
            continue
        output_element = {
            "name": element.get("name", "cube"),
            "from": element["from"],
            "to": element["to"],
            "faces": {},
        }
        rotation = element.get("rotation")
        if rotation and rotation.get("angle", 0):
            output_element["rotation"] = {
                key: rotation[key]
                for key in ("angle", "axis", "origin", "rescale")
                if key in rotation
            }
        if element.get("shade") is False:
            output_element["shade"] = False
        emission_data = light_emission_data(element)
        if emission_data is not None:
            output_element["neoforge_data"] = emission_data
        for face_name, face in element.get("faces", {}).items():
            if face.get("texture") is None:
                continue
            output_face = {
                "uv": [clean_number(float(value) * scale) for value in face["uv"]],
                "texture": "#0",
            }
            for key in ("rotation", "cullface", "tintindex"):
                if key in face:
                    output_face[key] = face[key]
            output_element["faces"][face_name] = output_face
            face_count += 1
        java_elements.append(output_element)

    resource_name = texture_path.stem
    java_model = {
        "format_version": "1.21.11",
        "credit": "Finalized from Blockbench by finalize-bbmodel.py",
        "parent": "minecraft:block/block",
        "texture_size": [uv_width, uv_height],
        "textures": {
            "0": f"{args.namespace}:block/{resource_name}",
            "particle": f"{args.namespace}:block/{resource_name}",
        },
        "elements": java_elements,
    }

    canonical_model.parent.mkdir(parents=True, exist_ok=True)
    texture_path.parent.mkdir(parents=True, exist_ok=True)
    java_model_path.parent.mkdir(parents=True, exist_ok=True)
    canonical_model.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    texture_path.write_bytes(png_bytes)
    java_model_path.write_text(json.dumps(java_model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(
        json.dumps(
            {
                "source": str(source),
                "canonical_model": str(canonical_model),
                "texture": str(texture_path),
                "java_model": str(java_model_path),
                "resolution": [uv_width, uv_height],
                "elements": len(java_elements),
                "faces": face_count,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

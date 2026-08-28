#!/usr/bin/env python3
"""Synchronize editable Blockbench sources with the game resources before a build."""

from __future__ import annotations

import base64
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image


WORKSPACE_ROOT = Path(__file__).resolve().parent
ASSET_ROOT = WORKSPACE_ROOT / "assets/cobbleventure_theme_blocks"
MODEL_ROOT = ASSET_ROOT / "models/block/workshop"
TEXTURE_ROOT = ASSET_ROOT / "textures/block"
PAUSED_GLOW_WINDOWS = {
    "sky_view_glow_window",
    "bright_double_glow_window",
    "blue_panel_glow_window",
}


def source_with_newest_texture(
    model_path: Path,
    editable_texture_path: Path,
    temporary_root: Path,
) -> tuple[Path, str]:
    """Use an external PNG only when it was saved after the Blockbench model."""
    if not editable_texture_path.exists() or editable_texture_path.stat().st_mtime <= model_path.stat().st_mtime:
        return model_path, "embedded_bbmodel_texture"

    model = json.loads(model_path.read_text(encoding="utf-8-sig"))
    textures = model.get("textures", [])
    if len(textures) != 1:
        raise ValueError(f"단일 텍스처 BBModel만 자동 동기화할 수 있습니다: {model_path}")

    texture = textures[0]
    expected_width = int(texture.get("uv_width") or model.get("resolution", {}).get("width"))
    expected_height = int(texture.get("uv_height") or model.get("resolution", {}).get("height"))
    with Image.open(editable_texture_path) as image:
        actual_size = image.size
    if actual_size != (expected_width, expected_height):
        raise ValueError(
            f"외부 텍스처 크기가 BBModel UV와 다릅니다: {editable_texture_path} "
            f"{actual_size[0]}x{actual_size[1]} != {expected_width}x{expected_height}"
        )

    texture["name"] = editable_texture_path.name
    texture["width"] = expected_width
    texture["height"] = expected_height
    texture["uv_width"] = expected_width
    texture["uv_height"] = expected_height
    texture["internal"] = True
    texture["saved"] = True
    texture["source"] = (
        "data:image/png;base64," + base64.b64encode(editable_texture_path.read_bytes()).decode("ascii")
    )

    prepared_model = temporary_root / model_path.name
    prepared_model.write_text(json.dumps(model, ensure_ascii=False), encoding="utf-8")
    return prepared_model, "external_png"


def run(command: list[str]) -> None:
    subprocess.run(command, cwd=WORKSPACE_ROOT, check=True)


def sync_bed(temporary_root: Path) -> dict:
    model = MODEL_ROOT / "09_large_single_iron_bed/large_single_iron_bed.bbmodel"
    texture = TEXTURE_ROOT / "bed_single_texture.png"
    java_model = MODEL_ROOT / "09_large_single_iron_bed/large_single_iron_bed.json"
    source, texture_source = source_with_newest_texture(model, texture, temporary_root)
    run(
        [
            sys.executable,
            str(WORKSPACE_ROOT / "finalize-bbmodel.py"),
            str(source),
            "--canonical-model",
            str(temporary_root / "large_single_iron_bed_synced.bbmodel"),
            "--texture",
            str(texture),
            "--java-model",
            str(java_model),
        ]
    )
    return {
        "asset": "large_single_iron_bed",
        "model_source": str(model),
        "texture_source": texture_source,
        "editable_texture": str(texture),
        "java_model": str(java_model),
        "game_texture": str(texture),
    }


def sync_glow_window(
    temporary_root: Path,
    asset: str,
    workshop_directory: str,
) -> dict:
    model_directory = MODEL_ROOT / workshop_directory
    model = model_directory / f"{asset}.bbmodel"
    editable_texture = TEXTURE_ROOT / f"windows/{asset}_texture.png"
    packed_model = model_directory / f"{asset}_packed.bbmodel"
    game_texture = TEXTURE_ROOT / f"{asset}_texture.png"
    java_model = model_directory / f"{asset}.json"
    if asset in PAUSED_GLOW_WINDOWS:
        if not java_model.exists() or not game_texture.exists():
            raise FileNotFoundError(f"일시 정지할 기존 게임 리소스가 없습니다: {asset}")
        return {
            "asset": asset,
            "status": "paused_while_editing",
            "model_source": str(model),
            "editable_texture": str(editable_texture),
            "java_model": str(java_model),
            "game_texture": str(game_texture),
        }
    try:
        source, texture_source = source_with_newest_texture(model, editable_texture, temporary_root)
    except ValueError as error:
        if not java_model.exists() or not game_texture.exists():
            raise
        return {
            "asset": asset,
            "status": "skipped_incomplete_edit",
            "reason": str(error),
            "model_source": str(model),
            "editable_texture": str(editable_texture),
            "java_model": str(java_model),
            "game_texture": str(game_texture),
        }
    run(
        [
            sys.executable,
            str(WORKSPACE_ROOT / "pack-bbmodel-texture.py"),
            str(source),
            "--output-model",
            str(packed_model),
            "--output-texture",
            str(game_texture),
            "--output-java-model",
            str(java_model),
        ]
    )
    return {
        "asset": asset,
        "status": "synchronized",
        "model_source": str(model),
        "texture_source": texture_source,
        "editable_texture": str(editable_texture),
        "java_model": str(java_model),
        "game_texture": str(game_texture),
    }


def main() -> None:
    reports = []
    with tempfile.TemporaryDirectory(prefix="cobbleventure-bbmodel-sync-") as temporary_directory:
        temporary_root = Path(temporary_directory)
        reports.append(sync_bed(temporary_root))
        reports.append(sync_glow_window(temporary_root, "sky_view_glow_window", "10_sky_view_glow_window"))
        reports.append(
            sync_glow_window(
                temporary_root,
                "bright_double_glow_window",
                "11_bright_double_glow_window",
            )
        )
        reports.append(
            sync_glow_window(
                temporary_root,
                "blue_panel_glow_window",
                "12_blue_panel_glow_window",
            )
        )

    report_path = WORKSPACE_ROOT / "reports/model-sync-report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(reports, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

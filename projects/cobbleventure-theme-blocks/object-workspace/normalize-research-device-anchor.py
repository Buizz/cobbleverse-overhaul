#!/usr/bin/env python3
"""Move the 2x2 research device from a center anchor to a corner anchor."""

from __future__ import annotations

import json
import shutil
from pathlib import Path


WORKSPACE_ROOT = Path(__file__).resolve().parent
MODEL_ROOT = (
    WORKSPACE_ROOT
    / "assets/cobbleventure_theme_blocks/models/block/workshop/07_research_device"
)
SOURCE_MODEL = MODEL_ROOT / "research_device.bbmodel"
RUNTIME_MODEL = MODEL_ROOT / "research_device_1.json"
BACKUP_ROOT = WORKSPACE_ROOT / "recovery/research_device/pre-corner-anchor-20260829"
OFFSET_X = 8
OFFSET_Z = 8


def bounds(model: dict) -> tuple[float, float, float, float]:
    elements = model.get("elements", [])
    return (
        min(element["from"][0] for element in elements),
        max(element["to"][0] for element in elements),
        min(element["from"][2] for element in elements),
        max(element["to"][2] for element in elements),
    )


def translate(path: Path) -> tuple[float, float, float, float]:
    model = json.loads(path.read_text(encoding="utf-8"))
    before = bounds(model)
    if before[0] >= 0 and before[2] >= 0:
        return before

    BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    backup = BACKUP_ROOT / path.name
    if not backup.exists():
        shutil.copy2(path, backup)

    for element in model.get("elements", []):
        for key in ("from", "to", "origin"):
            point = element.get(key)
            if point is not None:
                point[0] += OFFSET_X
                point[2] += OFFSET_Z

    path.write_text(
        json.dumps(model, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    return bounds(model)


if __name__ == "__main__":
    for target in (SOURCE_MODEL, RUNTIME_MODEL):
        print(f"{target.name}: {translate(target)}")

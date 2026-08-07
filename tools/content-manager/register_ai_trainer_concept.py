#!/usr/bin/env python3
"""Register an AI trainer texture atlas and publish its Minecraft slim skin."""

from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PIPELINE_ROOT = Path(__file__).resolve().parent / "skin-pipeline"
ASSEMBLER_PATH = PIPELINE_ROOT / "assemble_skin.py"
SPEC = importlib.util.spec_from_file_location("trainer_skin_assembler", ASSEMBLER_PATH)
assert SPEC is not None and SPEC.loader is not None
assembler = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = assembler
SPEC.loader.exec_module(assembler)


def register(slug: str, concept: Path, reference: Path, root: Path, model: str = "slim") -> Path:
    work = root / "tools" / "content-manager" / "skin-pipeline" / "work" / slug
    work.mkdir(parents=True, exist_ok=True)
    atlas = work / "concept-v2.png"
    shutil.copy2(concept, atlas)
    reference_dir = work / "reference"
    reference_dir.mkdir(parents=True, exist_ok=True)
    reference_target = reference_dir / f"{slug}.png"
    if reference.is_file() and reference.resolve() != reference_target.resolve():
        shutil.copy2(reference, reference_target)

    manifest = {
        "character": f"cobbleventure:character/{slug}",
        "reference": f"reference/{slug}.png",
        "reference_label": f"Pokémon 본가 {slug} 트레이너 스프라이트",
        "concept_atlas": "concept-v2.png",
        "generation_mode": "ai-reference-plus-deterministic-auto-uv",
        "model": model,
        "auto_layout": "four_row_atlas_v1",
        "component_minimum_area": 80,
        "chroma_key": "#ff00ff",
        "chroma_threshold": 45,
        "palette_colors": 20,
        "fallback_color": "#d8a174",
        "overlay_parts": [],
        "output": (
            "../../../../../projects/cobbleventure-world-bootstrap/src/main/resources/"
            f"assets/cobbleventure/textures/entity/trainer/{slug}.png"
        ),
    }
    manifest_path = work / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return assembler.assemble(manifest_path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("slug")
    parser.add_argument("concept", type=Path)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--model", choices=("classic", "slim"), default="slim")
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    output = register(
        args.slug,
        args.concept.resolve(),
        args.reference.resolve(),
        args.root.resolve(),
        args.model,
    )
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

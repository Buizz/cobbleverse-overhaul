#!/usr/bin/env python3
"""Configure habitat catalogs and settlement zones for numbered partitions."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


MAX_POKEMON_PER_VARIANT = 40
NEUTRAL_ENVIRONMENT = {
    "temperature": "any",
    "humidity": "any",
    "weather": "any",
    "time": "any",
}
DEFAULT_RARITIES = ["common", "medium", "uncommon", "rare"]


def write_json(path: Path, data: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def configure_biome_catalog(root: Path) -> None:
    path = root / "content" / "catalogs" / "biome-profiles.json"
    catalog = json.loads(path.read_text(encoding="utf-8"))
    catalog["max_pokemon_per_habitat_variant"] = MAX_POKEMON_PER_VARIANT
    for profile in catalog["profiles"]:
        settings = profile.setdefault("settings", {})
        settings["habitat_variant"] = 0
        settings.update(NEUTRAL_ENVIRONMENT)
        settings["rarities"] = DEFAULT_RARITIES
    write_json(path, catalog)


def configure_settlements(root: Path) -> None:
    settlement_root = root / "content" / "settlements"
    for path in settlement_root.rglob("*.json"):
        document = json.loads(path.read_text(encoding="utf-8"))
        changed = False
        for zone in document.get("biome_layout", {}).get("zones", []):
            settings = zone.get("spawn_settings")
            if not isinstance(settings, dict):
                continue
            settings["habitat_variant"] = 0
            settings.update(NEUTRAL_ENVIRONMENT)
            settings["rarities"] = DEFAULT_RARITIES
            changed = True
        if changed:
            write_json(path, document)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    configure_biome_catalog(args.root)
    configure_settlements(args.root)
    print(f"max_pokemon_per_habitat_variant={MAX_POKEMON_PER_VARIANT}")


if __name__ == "__main__":
    main()

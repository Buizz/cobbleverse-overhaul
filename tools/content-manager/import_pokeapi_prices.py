#!/usr/bin/env python3
"""Import official-game item prices from PokéAPI CSV data into economy.json.

The importer prefers Scarlet/Violet's explicit purchase and sell prices. Items not
yet covered by PokéAPI's versioned price table fall back to the legacy item cost
and the configured default sell percentage.
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import sys
from pathlib import Path


PREFERRED_VERSION_GROUP = "scarlet-violet"
COLLECTIBLE_CATEGORY_ID = "24"
KNOWN_COBBLEMON_COLLECTIBLES = {
    "cobblemon:balm_mushroom",
    "cobblemon:big_mushroom",
    "cobblemon:big_nugget",
    "cobblemon:big_pearl",
    "cobblemon:comet_shard",
    "cobblemon:nugget",
    "cobblemon:pearl",
    "cobblemon:pearl_string",
    "cobblemon:rare_bone",
    "cobblemon:star_piece",
    "cobblemon:stardust",
    "cobblemon:tiny_mushroom",
}


def load_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def load_content_manager(root: Path):
    path = root / "tools" / "content-manager" / "content_manager.py"
    spec = importlib.util.spec_from_file_location("content_manager_price_import", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def import_prices(root: Path, items_csv: Path, prices_csv: Path, version_groups_csv: Path) -> tuple[int, int]:
    project_root = root / "content-projects" / "cobbleventure-main"
    economy_path = project_root / "content" / "catalogs" / "economy.json"
    economy = json.loads(economy_path.read_text(encoding="utf-8"))
    policy = economy.setdefault("sell_price_policy", {
        "apply_default_to_all": True,
        "default_percentage": 50,
    })
    default_percentage = max(0, min(100, int(policy.get("default_percentage", 50))))

    content_manager = load_content_manager(root)
    workspace = content_manager.load_economy_workspace(project_root, root)
    cobblemon_items = {
        entry["id"] for entry in workspace["editor_catalog"]["items"]
        if entry["id"].startswith("cobblemon:")
    }
    cobblemon_items.update(KNOWN_COBBLEMON_COLLECTIBLES)

    version_groups = {row["id"]: row["identifier"] for row in load_csv(version_groups_csv)}
    prices_by_item: dict[str, list[dict[str, str]]] = {}
    for row in load_csv(prices_csv):
        prices_by_item.setdefault(row["item_id"], []).append(row)

    existing = {entry["item"]: entry for entry in economy.get("standard_prices", [])}
    matched = 0
    explicit_version_prices = 0
    for item in load_csv(items_csv):
        item_id = f'cobblemon:{item["identifier"].replace("-", "_")}'
        if item_id not in cobblemon_items:
            continue
        rows = prices_by_item.get(item["id"], [])
        preferred = next(
            (row for row in rows if version_groups.get(row["version_group_id"]) == PREFERRED_VERSION_GROUP),
            None,
        )
        selected = preferred or (max(rows, key=lambda row: int(row["version_group_id"])) if rows else None)
        legacy_cost = max(0, int(item.get("cost") or 0))
        buy_price = int(selected["purchase_price"]) if selected and selected.get("purchase_price") else legacy_cost
        has_explicit_sell = bool(selected and selected.get("sell_price"))
        sell_price = (
            int(selected["sell_price"])
            if has_explicit_sell
            else buy_price * default_percentage // 100
        )
        no_sell_penalty = item.get("category_id") == COLLECTIBLE_CATEGORY_ID
        previous = existing.get(item_id, {})
        existing[item_id] = {
            "item": item_id,
            "buy_price": str(buy_price),
            "sell_price": str(sell_price),
            "use_default_sell_price": False if no_sell_penalty else previous.get(
                "use_default_sell_price", not has_explicit_sell
            ),
            "no_sell_penalty": previous.get("no_sell_penalty", no_sell_penalty),
        }
        matched += 1
        explicit_version_prices += int(has_explicit_sell)

    economy["standard_prices"] = sorted(existing.values(), key=lambda entry: entry["item"])
    economy_path.write_text(
        json.dumps(economy, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return matched, explicit_version_prices


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).parents[2])
    parser.add_argument("--items", type=Path, required=True)
    parser.add_argument("--prices", type=Path, required=True)
    parser.add_argument("--version-groups", type=Path, required=True)
    args = parser.parse_args()
    matched, explicit = import_prices(
        args.root.resolve(), args.items.resolve(), args.prices.resolve(), args.version_groups.resolve()
    )
    print(f"Imported {matched} Cobblemon item prices ({explicit} with versioned sell prices).")


if __name__ == "__main__":
    main()

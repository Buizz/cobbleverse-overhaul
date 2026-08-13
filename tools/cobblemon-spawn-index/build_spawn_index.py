#!/usr/bin/env python3
"""Build a lossless, species-addressable index from Cobblemon spawn pool JSON."""

from __future__ import annotations

import argparse
import os
import json
import re
import zipfile
from collections import Counter
from pathlib import Path
from typing import Iterable, Iterator


SPAWN_RESOURCE = re.compile(
    r"^data/cobblemon/spawn_pool_world/(?P<name>[^/]+)\.json$",
    re.IGNORECASE,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Cobblemon 스폰 원본과 Cobbleventure 서식지 카탈로그를 대조합니다."
    )
    parser.add_argument(
        "--source",
        required=True,
        type=Path,
        help="Cobblemon JAR 또는 data/cobblemon/spawn_pool_world를 포함한 소스 루트",
    )
    parser.add_argument(
        "--habitats",
        type=Path,
        default=Path(os.environ.get(
            "COBBLEVENTURE_PROJECT_PATH", "content-projects/cobbleventure-main"
        )) / "content/catalogs/pokemon-habitats.json",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("generated/cobbleventure/cobblemon-spawn-index.json"),
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("outputs/cobblemon-spawn-reconciliation.json"),
    )
    parser.add_argument("--cobblemon-version", default="1.7.3")
    return parser.parse_args()


def iter_spawn_documents(source: Path) -> Iterator[tuple[str, dict]]:
    if source.is_file():
        with zipfile.ZipFile(source) as archive:
            for name in sorted(archive.namelist()):
                match = SPAWN_RESOURCE.match(name.replace("\\", "/"))
                if match:
                    yield name, json.loads(archive.read(name).decode("utf-8"))
        return

    candidates = (
        source / "data" / "cobblemon" / "spawn_pool_world",
        source / "common" / "src" / "main" / "resources" / "data" / "cobblemon" / "spawn_pool_world",
        source,
    )
    spawn_root = next((path for path in candidates if path.is_dir()), None)
    if spawn_root is None:
        raise FileNotFoundError(f"Cobblemon spawn_pool_world 경로를 찾지 못했습니다: {source}")
    for path in sorted(spawn_root.glob("*.json")):
        resource = f"data/cobblemon/spawn_pool_world/{path.name}"
        yield resource, json.loads(path.read_text(encoding="utf-8"))


def species_id(expression: str | None) -> str | None:
    if not expression:
        return None
    slug = expression.split(maxsplit=1)[0].lower()
    return slug if ":" in slug else f"cobblemon:{slug}"


def build_index(documents: Iterable[tuple[str, dict]], version: str) -> dict:
    rules: list[dict] = []
    files = 0
    for resource, document in documents:
        files += 1
        file_enabled = document.get("enabled", True) is not False
        for raw in document.get("spawns", []):
            expression = raw.get("pokemon")
            normalized_species = species_id(expression)
            if normalized_species is None:
                # Disabled experimental herd rules do not have a single root Pokemon.
                continue
            rules.append(
                {
                    "source_resource": resource,
                    "rule_id": raw.get("id"),
                    "species_id": normalized_species,
                    "pokemon_expression": expression,
                    "enabled": file_enabled,
                    "type": raw.get("type"),
                    "spawnable_position_type": raw.get("spawnablePositionType"),
                    "bucket": raw.get("bucket"),
                    "level": raw.get("level") or raw.get("levelRange"),
                    "weight": raw.get("weight"),
                    "presets": raw.get("presets", []),
                    "condition": raw.get("condition", {}),
                    "anticondition": raw.get("anticondition", {}),
                    "weight_multiplier": raw.get("weightMultiplier"),
                    "weight_multipliers": raw.get("weightMultipliers", []),
                    "raw": raw,
                }
            )

    rules.sort(key=lambda rule: (rule["species_id"], rule["source_resource"], rule["rule_id"] or ""))
    return {
        "$schema": "../../content/schemas/cobblemon-spawn-index.schema.json",
        "schema_version": 1,
        "source": {
            "mod": "cobblemon",
            "version": version,
            "spawn_pool": "data/cobblemon/spawn_pool_world",
            "resource_files": files,
        },
        "summary": summarize(rules),
        "rules": rules,
    }


def summarize(rules: list[dict]) -> dict:
    return {
        "rules": len(rules),
        "enabled_rules": sum(1 for rule in rules if rule["enabled"]),
        "species": len({rule["species_id"] for rule in rules}),
        "buckets": dict(sorted(Counter(rule["bucket"] for rule in rules).items())),
        "position_types": dict(
            sorted(Counter(rule["spawnable_position_type"] for rule in rules).items())
        ),
    }


def reconcile(index: dict, habitats_path: Path) -> dict:
    habitats_document = json.loads(habitats_path.read_text(encoding="utf-8"))
    habitats = {entry["id"]: entry for entry in habitats_document.get("pokemon", [])}
    rules_by_species: dict[str, list[dict]] = {}
    for rule in index["rules"]:
        if rule["enabled"]:
            rules_by_species.setdefault(rule["species_id"], []).append(rule)

    habitat_ids = set(habitats)
    spawn_ids = set(rules_by_species)
    implemented_ids = {key for key, value in habitats.items() if value.get("implemented") is True}
    return {
        "schema_version": 1,
        "source_version": index["source"]["version"],
        "summary": {
            "habitat_species": len(habitat_ids),
            "implemented_habitat_species": len(implemented_ids),
            "spawn_species": len(spawn_ids),
            "matched_species": len(habitat_ids & spawn_ids),
            "implemented_without_spawn_rules": len(implemented_ids - spawn_ids),
            "spawn_species_without_habitats": len(spawn_ids - habitat_ids),
        },
        "implemented_without_spawn_rules": sorted(implemented_ids - spawn_ids),
        "spawn_species_without_habitats": sorted(spawn_ids - habitat_ids),
        "unimplemented_with_spawn_rules": sorted((habitat_ids - implemented_ids) & spawn_ids),
    }


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    index = build_index(iter_spawn_documents(args.source), args.cobblemon_version)
    report = reconcile(index, args.habitats)
    write_json(args.output, index)
    write_json(args.report, report)
    print(json.dumps({"index": index["summary"], "reconciliation": report["summary"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

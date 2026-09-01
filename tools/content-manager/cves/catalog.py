"""Project-independent resource catalog and Cobbleventure project index loader."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Callable


class ResourceKind(str, Enum):
    ITEM = "item"
    BATTLE = "battle"
    BADGE = "badge"
    FLAG = "flag"
    VARIABLE = "variable"
    SETTLEMENT = "settlement"
    ROUTE = "route"
    DIMENSION = "dimension"
    SPACE = "space"
    BUILDING = "building"
    LOOT = "loot"
    FEATURE = "feature"
    SOUND = "sound"
    EFFECT = "effect"
    EVENT_REGION = "event_region"
    EVENT_ANCHOR = "event_anchor"
    QUEST = "quest"


@dataclass(slots=True)
class ResourceCatalog:
    """A catalog states explicitly which resource domains are authoritative."""

    resources: dict[ResourceKind, set[str]] = field(default_factory=dict)
    complete_kinds: set[ResourceKind] = field(default_factory=set)
    anchors: dict[tuple[ResourceKind, str], set[str]] = field(default_factory=dict)

    def add(self, kind: ResourceKind, resource_id: str) -> None:
        self.resources.setdefault(kind, set()).add(resource_id)

    def add_anchors(self, kind: ResourceKind, resource_id: str, anchors: set[str]) -> None:
        self.add(kind, resource_id)
        self.anchors[(kind, resource_id)] = set(anchors)

    def contains(self, kind: ResourceKind, resource_id: str) -> bool:
        return resource_id in self.resources.get(kind, set())

    def can_reject_missing(self, kind: ResourceKind) -> bool:
        return kind in self.complete_kinds

    def can_validate_anchors(self, kind: ResourceKind, resource_id: str) -> bool:
        return (kind, resource_id) in self.anchors

    def contains_anchor(self, kind: ResourceKind, resource_id: str, anchor: str) -> bool:
        return anchor in self.anchors.get((kind, resource_id), set())


def load_project_catalog(project_root: Path, *, item_catalog: Path | None = None) -> ResourceCatalog:
    """Index authoritative resources without importing the content-manager server."""
    content = project_root / "content"
    if not content.is_dir():
        raise ValueError(f"프로젝트 content 디렉터리를 찾을 수 없습니다: {content}")

    catalog = ResourceCatalog()
    battle_directory = content / "battles"
    _load_document_ids(battle_directory, ResourceKind.BATTLE, catalog)
    if battle_directory.is_dir():
        catalog.complete_kinds.add(ResourceKind.BATTLE)

    badge_path = content / "catalogs" / "badges.json"
    _load_array_catalog(badge_path, "badges", ResourceKind.BADGE, catalog)
    if badge_path.is_file():
        catalog.complete_kinds.add(ResourceKind.BADGE)

    loot_table_directory = content / "loot_tables"
    _load_namespaced_json_ids(loot_table_directory, ResourceKind.LOOT, catalog)
    if loot_table_directory.is_dir():
        catalog.complete_kinds.add(ResourceKind.LOOT)

    definitions = _load_json_if_present(content / "catalogs" / "game-definitions.json")
    if definitions is not None:
        for value in definitions.get("variables", []):
            if not isinstance(value, dict) or not isinstance(value.get("id"), str):
                continue
            kind = ResourceKind.FLAG if value.get("type") == "boolean" else ResourceKind.VARIABLE
            catalog.add(kind, value["id"])
        for value in definitions.get("items", []):
            if isinstance(value, dict) and isinstance(value.get("id"), str):
                catalog.add(ResourceKind.ITEM, value["id"])
        catalog.complete_kinds.update({ResourceKind.FLAG, ResourceKind.VARIABLE})
    _load_npc_preset_flags(content / "source", catalog)

    quest_directory = content / "quests"
    _load_document_ids(quest_directory, ResourceKind.QUEST, catalog)
    if quest_directory.is_dir():
        catalog.complete_kinds.add(ResourceKind.QUEST)

    settlement_directory = content / "settlements"
    _load_location_documents(settlement_directory, ResourceKind.SETTLEMENT, catalog, _object_anchor_ids)
    if settlement_directory.is_dir():
        catalog.complete_kinds.add(ResourceKind.SETTLEMENT)
    route_directory = content / "routes"
    _load_location_documents(route_directory, ResourceKind.ROUTE, catalog, _route_anchor_ids)
    if route_directory.is_dir():
        catalog.complete_kinds.add(ResourceKind.ROUTE)

    for path in sorted((content / "worlds").rglob("*.json")) if (content / "worlds").is_dir() else []:
        data = _load_json(path)
        if isinstance(data.get("dimension"), str):
            catalog.add(ResourceKind.DIMENSION, data["dimension"])

    for directory in (content / "caves", content / "forests"):
        for path in sorted(directory.rglob("*.json")) if directory.is_dir() else []:
            data = _load_json(path)
            resource_id = data.get("id")
            if not isinstance(resource_id, str):
                continue
            anchors = _space_anchor_ids(data)
            catalog.add_anchors(ResourceKind.SPACE, resource_id, anchors)
            dimension = data.get("dimension")
            if isinstance(dimension, dict) and isinstance(dimension.get("id"), str):
                catalog.add(ResourceKind.DIMENSION, dimension["id"])

    dimension_anchors = _load_json_if_present(
        content / "catalogs" / "dimension-anchors.json"
    )
    if dimension_anchors is not None:
        for value in dimension_anchors.get("dimensions", []):
            if not isinstance(value, dict) or not isinstance(value.get("id"), str):
                continue
            anchors = value.get("anchors")
            catalog.add_anchors(
                ResourceKind.DIMENSION,
                value["id"],
                set(anchors) if isinstance(anchors, dict) else set(),
            )
        catalog.complete_kinds.add(ResourceKind.DIMENSION)

    event_boundaries = _load_json_if_present(
        content / "catalogs" / "event-boundaries.json"
    )
    if event_boundaries is not None:
        for value in event_boundaries.get("regions", []):
            if isinstance(value, dict) and isinstance(value.get("id"), str):
                catalog.add(ResourceKind.EVENT_REGION, value["id"])
        for value in event_boundaries.get("anchors", []):
            if isinstance(value, dict) and isinstance(value.get("id"), str):
                catalog.add(ResourceKind.EVENT_ANCHOR, value["id"])
        catalog.complete_kinds.update({
            ResourceKind.EVENT_REGION, ResourceKind.EVENT_ANCHOR,
        })

    _load_building_spaces(content, settlement_directory, catalog)
    if (content / "catalogs" / "building-settings.json").is_file():
        catalog.complete_kinds.add(ResourceKind.BUILDING)

    if item_catalog is not None:
        data = _load_json(item_catalog)
        for value in data.get("items", []):
            if isinstance(value, dict) and isinstance(value.get("id"), str):
                catalog.add(ResourceKind.ITEM, value["id"])
        catalog.complete_kinds.add(ResourceKind.ITEM)
    return catalog


def _load_document_ids(directory: Path, kind: ResourceKind, catalog: ResourceCatalog) -> None:
    for path in sorted(directory.rglob("*.json")) if directory.is_dir() else []:
        data = _load_json(path)
        if isinstance(data.get("id"), str):
            catalog.add(kind, data["id"])


def _load_namespaced_json_ids(
    directory: Path, kind: ResourceKind, catalog: ResourceCatalog
) -> None:
    for path in sorted(directory.rglob("*.json")) if directory.is_dir() else []:
        relative = path.relative_to(directory)
        if len(relative.parts) < 2:
            raise ValueError(
                f"리소스 원본은 <namespace>/<path>.json 구조여야 합니다: {path}"
            )
        namespace = relative.parts[0]
        resource_path = Path(*relative.parts[1:]).with_suffix("").as_posix()
        if not re.fullmatch(r"[a-z0-9_.-]+", namespace) or not re.fullmatch(
            r"[a-z0-9_./-]+", resource_path
        ):
            raise ValueError(f"올바르지 않은 리소스 원본 경로입니다: {path}")
        _load_json(path)
        catalog.add(kind, f"{namespace}:{resource_path}")


def _load_location_documents(
    directory: Path,
    kind: ResourceKind,
    catalog: ResourceCatalog,
    anchor_reader: Callable[[dict[str, Any]], set[str]] | None,
) -> None:
    for path in sorted(directory.rglob("*.json")) if directory.is_dir() else []:
        data = _load_json(path)
        resource_id = data.get("id")
        if not isinstance(resource_id, str):
            continue
        if anchor_reader is None:
            catalog.add(kind, resource_id)
        else:
            catalog.add_anchors(kind, resource_id, anchor_reader(data))


def _load_array_catalog(path: Path, key: str, kind: ResourceKind, catalog: ResourceCatalog) -> None:
    data = _load_json_if_present(path)
    if data is None:
        return
    for value in data.get(key, []):
        if isinstance(value, dict) and isinstance(value.get("id"), str):
            catalog.add(kind, value["id"])


def _object_anchor_ids(data: dict[str, Any]) -> set[str]:
    anchors = data.get("anchors")
    return set(anchors) if isinstance(anchors, dict) else set()


def _load_npc_preset_flags(directory: Path, catalog: ResourceCatalog) -> None:
    """Treat normalized preset state keys as declarations for their generated CVES."""
    for path in sorted(directory.rglob("*.json")) if directory.is_dir() else []:
        data = _load_json(path)
        preset = data.get("event_design", {}).get("preset")
        if not isinstance(preset, dict):
            continue
        preset_type = preset.get("type")
        keys = [preset.get("state_key"), preset.get("victory_state_key"), preset.get("clear_key")]
        if preset_type in {"repeat", "item"} and not preset.get("state_key"):
            document_id = data.get("id")
            if isinstance(document_id, str) and ":" in document_id:
                namespace, resource_path = document_id.split(":", 1)
                suffix = "claimed" if preset_type == "item" else "talked"
                keys.append(f"{namespace}:flag/npc/{resource_path.removeprefix('npc/')}/{suffix}")
        if preset_type in {"battle", "gym", "elite", "champion"} \
                and not preset.get("victory_state_key") and not preset.get("clear_key"):
            document_id = data.get("id")
            if isinstance(document_id, str) and ":" in document_id:
                namespace, resource_path = document_id.split(":", 1)
                keys.append(f"{namespace}:flag/npc/{resource_path.removeprefix('npc/')}/defeated")
        for key in keys:
            if isinstance(key, str):
                catalog.add(ResourceKind.FLAG, key)


def _route_anchor_ids(data: dict[str, Any]) -> set[str]:
    """Stable positions derived from the authored world connection direction."""
    return {"start", "middle", "end"}


def _space_anchor_ids(data: dict[str, Any]) -> set[str]:
    result = {
        value["id"] for value in data.get("entrances", [])
        if isinstance(value, dict) and isinstance(value.get("id"), str)
    }
    manual = data.get("generator", {}).get("manual_layout", {})
    if isinstance(manual, dict):
        result.update(
            value["id"] for value in manual.get("anchors", [])
            if isinstance(value, dict) and isinstance(value.get("id"), str)
        )
    return result


def _load_building_spaces(
    content: Path, settlement_directory: Path, catalog: ResourceCatalog,
) -> None:
    settings_document = _load_json_if_present(content / "catalogs" / "building-settings.json")
    if settings_document is None or not isinstance(settings_document.get("buildings"), dict):
        return
    settings = settings_document["buildings"]
    for path in sorted(settlement_directory.rglob("*.json")) if settlement_directory.is_dir() else []:
        settlement = _load_json(path)
        settlement_id = settlement.get("id")
        profile = settlement.get("structure_profile")
        if not isinstance(settlement_id, str) or not isinstance(profile, dict):
            continue
        facilities = profile.get("facility_placements", [])
        if not isinstance(facilities, list):
            continue
        for facility in facilities:
            if not isinstance(facility, dict):
                continue
            facility_id, structure = facility.get("id"), facility.get("structure")
            building = settings.get(structure) if isinstance(structure, str) else None
            if not isinstance(facility_id, str) or not isinstance(building, dict):
                continue
            anchors = _building_anchor_ids(content, structure, building)
            if anchors:
                building_id = _building_space_id(settlement_id, facility_id)
                catalog.add_anchors(
                    ResourceKind.SPACE,
                    building_id,
                    anchors,
                )
                catalog.add(ResourceKind.BUILDING, building_id)


def _building_space_id(settlement_id: str, facility_id: str) -> str:
    namespace, separator, path = settlement_id.partition(":")
    if not separator:
        namespace, path = "cobbleventure", namespace
    if path.startswith("settlement/"):
        path = path[len("settlement/"):]
    return f"{namespace}:building/{path}/{facility_id}"


def _building_anchor_ids(
    content: Path, exterior_structure: str, building: dict[str, Any],
) -> set[str]:
    if not building.get("door_routes"):
        return set()
    result = _structure_anchor_ids(content, exterior_structure, "exterior")
    interiors = building.get("interiors", [])
    if not isinstance(interiors, list):
        return result
    for interior in interiors:
        if not isinstance(interior, dict):
            continue
        key, structure = interior.get("key"), interior.get("structure")
        if isinstance(key, str) and isinstance(structure, str):
            result.update(_structure_anchor_ids(content, structure, key))
    return result


def _structure_anchor_ids(content: Path, structure: str, space_key: str) -> set[str]:
    namespace, separator, path = structure.partition(":")
    if not separator or namespace != "cobbleventure":
        return set()
    metadata = _load_json_if_present(content / "structures" / f"{path}.structure.json")
    if metadata is None:
        return set()
    result: set[str] = set()
    for anchor in metadata.get("anchors", []):
        if not isinstance(anchor, dict):
            continue
        anchor_id = anchor.get("label", anchor.get("id", anchor.get("type")))
        if isinstance(anchor_id, str) and anchor_id:
            result.add(f"{space_key}/{anchor_id}")
    return result


def _load_json_if_present(path: Path) -> dict[str, Any] | None:
    return _load_json(path) if path.is_file() else None


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"카탈로그 JSON을 읽을 수 없습니다: {path}: {error}") from error
    if not isinstance(value, dict):
        raise ValueError(f"카탈로그 JSON 루트는 객체여야 합니다: {path}")
    return value

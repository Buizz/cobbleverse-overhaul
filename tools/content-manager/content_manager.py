from __future__ import annotations

import argparse
import copy
import functools
import gzip
import hashlib
import importlib.util
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
import uuid
from dataclasses import asdict, dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib import error as urllib_error
from urllib import request as urllib_request
from urllib.parse import parse_qs, urlparse


RESOURCE_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
MOD_ID = re.compile(r"^[a-z][a-z0-9_-]*$")
CHOICE_ID = re.compile(r"^[a-z0-9_.-]+$")
DOCUMENT_SLUG = re.compile(r"^[a-z0-9][a-z0-9_]*$")
LANGUAGE_ID = re.compile(r"^[a-z]{2}_[a-z]{2}$")
PROJECT_ID = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
PROJECT_MANIFEST_NAME = "project.json"
PROJECT_SCHEMA = "cobbleventure-content-project"
PROJECT_VERSION = 1
DEFAULT_PROJECT_RELATIVE_PATH = Path("content-projects/cobbleventure-main")
PROJECT_FOLDER_PICKER_SCRIPT = r'''
Add-Type -AssemblyName System.Windows.Forms
$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = "코블벤처 프로젝트 폴더 선택"
$dialog.ShowNewFolderButton = $false
if ($env:COBBLEVENTURE_PROJECT_PATH -and (Test-Path -LiteralPath $env:COBBLEVENTURE_PROJECT_PATH -PathType Container)) {
  $dialog.SelectedPath = (Resolve-Path -LiteralPath $env:COBBLEVENTURE_PROJECT_PATH).Path
}
$result = $dialog.ShowDialog()
if ($result -eq [System.Windows.Forms.DialogResult]::OK) {
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  [Console]::Write($dialog.SelectedPath)
}
$dialog.Dispose()
'''
STAT_NAMES = {
    "hp",
    "attack",
    "defense",
    "special_attack",
    "special_defense",
    "speed",
}
TERA_TYPES = {
    "auto",
    "normal",
    "fire",
    "water",
    "electric",
    "grass",
    "ice",
    "fighting",
    "poison",
    "ground",
    "flying",
    "psychic",
    "bug",
    "rock",
    "ghost",
    "dragon",
    "dark",
    "steel",
    "fairy",
}
BATTLE_FORMAT_TYPES = {
    "GEN_9_SINGLES": "singles",
    "GEN_9_DOUBLES": "doubles",
}
AI_STRATEGIES = {
    "balanced",
    "aggressive",
    "defensive",
    "ace_check",
    "reckless_ace",
    "setup",
    "hazard",
    "tempo",
    "unpredictable",
}
AI_DIFFICULTIES = {
    "novice",
    "standard",
    "advanced",
    "expert",
    "expert_winrate",
    "expert_search",
    "cheater",
}


@dataclass(frozen=True)
class ContentProject:
    root: Path
    id: str
    name: str
    is_default: bool = False

    def as_json(self) -> dict[str, Any]:
        return {
            "schema": PROJECT_SCHEMA,
            "version": PROJECT_VERSION,
            "id": self.id,
            "name": self.name,
            "path": str(self.root),
            "content_directory": "content",
            "is_default": self.is_default,
        }


def load_content_project(
    path: Path, *, default_root: Path | None = None, require_manifest: bool = True
) -> ContentProject:
    root = path.expanduser().resolve()
    if not root.is_dir():
        raise ValueError("프로젝트 폴더를 찾을 수 없습니다.")
    manifest_path = root / PROJECT_MANIFEST_NAME
    if not manifest_path.is_file():
        if require_manifest:
            raise ValueError(f"{PROJECT_MANIFEST_NAME}이 있는 코블벤처 프로젝트 폴더를 선택해 주세요.")
        project_id = re.sub(r"[^a-z0-9_-]+", "-", root.name.lower()).strip("-")
        return ContentProject(
            root=root,
            id=project_id or "cobbleventure-main",
            name="Cobbleventure Main",
            is_default=default_root is not None and root == default_root.resolve(),
        )
    try:
        manifest = load_json(manifest_path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        raise ValueError(f"프로젝트 명세를 읽을 수 없습니다: {error}") from error
    if not isinstance(manifest, dict):
        raise ValueError("project.json 최상위 값은 객체여야 합니다.")
    if manifest.get("schema") != PROJECT_SCHEMA:
        raise ValueError(f"project.json schema는 {PROJECT_SCHEMA}이어야 합니다.")
    if manifest.get("version") != PROJECT_VERSION:
        raise ValueError(f"지원하지 않는 프로젝트 버전입니다: {manifest.get('version')}")
    project_id = manifest.get("id")
    name = manifest.get("name")
    content_directory = manifest.get("contentDirectory", "content")
    if not isinstance(project_id, str) or not PROJECT_ID.fullmatch(project_id):
        raise ValueError("프로젝트 ID는 소문자, 숫자, 밑줄과 하이픈만 사용할 수 있습니다.")
    if not isinstance(name, str) or not name.strip():
        raise ValueError("프로젝트 이름이 필요합니다.")
    if content_directory != "content":
        raise ValueError("현재 프로젝트의 contentDirectory는 content여야 합니다.")
    if not (root / "content").is_dir():
        raise ValueError("프로젝트 폴더 안에 content 폴더가 필요합니다.")
    return ContentProject(
        root=root,
        id=project_id,
        name=name.strip(),
        is_default=default_root is not None and root == default_root.resolve(),
    )


def resolve_content_project(
    core_root: Path, project_path: Path | None = None
) -> ContentProject:
    core_root = core_root.resolve()
    configured = project_path or (
        Path(os.environ["COBBLEVENTURE_PROJECT_PATH"])
        if os.environ.get("COBBLEVENTURE_PROJECT_PATH")
        else None
    )
    if configured is not None:
        candidate = configured if configured.is_absolute() else core_root / configured
        return load_content_project(
            candidate, default_root=core_root / DEFAULT_PROJECT_RELATIVE_PATH
        )
    default_project = core_root / DEFAULT_PROJECT_RELATIVE_PATH
    if (default_project / PROJECT_MANIFEST_NAME).is_file():
        return load_content_project(default_project, default_root=default_project)
    return load_content_project(core_root, default_root=core_root, require_manifest=False)
OPERATION_TYPES = {
    "always",
    "flag_equals",
    "has_item",
    "next_dialogue",
    "close_dialogue",
    "start_battle",
    "set_flag",
    "mark_clear",
    "give_item",
    "grant_badge",
    "give_money",
    "take_money",
    "grant_loot",
    "grant_field_move",
    "start_quest",
    "complete_quest",
    "teleport",
    "teleport_to_gate",
    "open_dialogue",
}
VALID_LOCK_STATUSES = {"draft", "locked"}
VALID_SIDES = {"client", "server", "both"}
VALID_CLASSIFICATIONS = {
    "required",
    "required-candidate",
    "optional",
    "profile-optional",
    "development",
}
BUILD_COMMANDS = {
    "validate": "콘텐츠와 의존성 검사",
    "test": "Python 도구 회귀 테스트",
    "generate": "RCT와 실제 게임용 AI 프로필 생성",
    "pack-smoke": "최소 CurseForge 임포트 ZIP 생성",
    "pack": "개발용 CurseForge ZIP 생성",
    "validate-pack": "실제 모드팩 빌드 준비 상태 검사",
    "builder-world": "독립 건축 월드 CurseForge ZIP 생성",
}
STRUCTURE_BUILDER_WORLD_NAME = "Cobbleventure Structure Builder"
CONTENT_MANAGER_SETTINGS = "tools/content-manager/settings.local.json"
STATIC_CONTENT_TYPES = {
    ".css": "text/css; charset=utf-8",
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".png": "image/png",
}
HABITAT_IDS = {"plains", "forest", "arid", "mountain", "cave", "wetland", "freshwater", "ocean", "snow", "volcanic", "urban", "special"}
RARITY_IDS = {"common", "medium", "uncommon", "rare", "legendary"}
POKEDEX_SERIES_BY_GENERATION = {
    1: "kanto", 2: "johto", 3: "hoenn", 4: "sinnoh", 5: "unova",
    6: "kalos", 7: "alola", 8: "galar", 9: "paldea",
}
POKEDEX_SERIES_IDS = set(POKEDEX_SERIES_BY_GENERATION.values())


@dataclass(frozen=True)
class Issue:
    level: str
    file: str
    path: str
    message: str


@dataclass(frozen=True)
class ValidationResult:
    valid: bool
    errors: int
    warnings: int
    issues: list[Issue]

    def as_json(self) -> dict[str, Any]:
        return {
            "valid": self.valid,
            "errors": self.errors,
            "warnings": self.warnings,
            "issues": [asdict(issue) for issue in self.issues],
        }


class DuplicateKeyError(ValueError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"중복 JSON 키: {key}")
        result[key] = value
    return result


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8-sig") as source:
        return json.load(source, object_pairs_hook=_reject_duplicate_keys)


def _catalog_path(root: Path, name: str) -> Path:
    return root / "content" / "catalogs" / name


def load_biome_catalog(root: Path) -> dict[str, Any]:
    data = load_json(_catalog_path(root, "biome-profiles.json"))
    if not isinstance(data, dict):
        raise ValueError("바이옴 카탈로그는 객체여야 합니다.")
    return data


def load_pokemon_habitats(root: Path) -> dict[str, Any]:
    data = load_json(_catalog_path(root, "pokemon-habitats.json"))
    if not isinstance(data, dict):
        raise ValueError("포켓몬 서식지 카탈로그는 객체여야 합니다.")
    return data


def _town_footprint(
    anchor: tuple[int, int], cell_count: int, shape: str = "line_q",
    custom_cells: set[tuple[int, int]] | None = None,
) -> set[tuple[int, int]]:
    q, r = anchor
    if shape == "custom":
        return {(q + dq, r + dr) for dq, dr in (custom_cells or set())}
    if cell_count == 3:
        offsets = {
            "triangle_up": ((0, 0), (0, -1), (1, -1)),
            "triangle_down": ((0, 0), (0, 1), (-1, 1)),
            "line_q": ((-1, 0), (0, 0), (1, 0)),
            "line_r": ((0, -1), (0, 0), (0, 1)),
            "line_s": ((-1, 1), (0, 0), (1, -1)),
        }.get(shape, ((-1, 0), (0, 0), (1, 0)))
        return {(q + dq, r + dr) for dq, dr in offsets}
    if cell_count == 5:
        offsets = (
            ((-1, 0), (0, 0), (1, 0), (-1, 1), (0, 1))
            if shape == "five_down"
            else ((-1, 0), (0, 0), (1, 0), (0, -1), (1, -1))
        )
        return {(q + dq, r + dr) for dq, dr in offsets}
    if cell_count == 7:
        return {
            (q, r), (q + 1, r), (q, r + 1), (q - 1, r + 1),
            (q - 1, r), (q, r - 1), (q + 1, r - 1),
        }
    if cell_count == 19:
        return {
            (q + dq, r + dr)
            for dq in range(-2, 3)
            for dr in range(max(-2, -dq - 2), min(2, -dq + 2) + 1)
        }
    return {(q, r)}


def _hex_distance(first: tuple[int, int], second: tuple[int, int]) -> int:
    q1, r1 = first
    q2, r2 = second
    return (abs(q1 - q2) + abs(r1 - r2) + abs((-q1 - r1) - (-q2 - r2))) // 2


def _validate_custom_town_layout(
    cells_value: Any, exits_value: Any, expected_count: Any,
    issues: list[Issue], file: Path, data_path: str,
) -> set[tuple[int, int]]:
    def coordinates(value: Any, field: str) -> set[tuple[int, int]]:
        result: set[tuple[int, int]] = set()
        if not isinstance(value, list):
            _issue(issues, "error", file, f"{data_path}.{field}", "육각 좌표 배열이 필요합니다.")
            return result
        for index, entry in enumerate(value):
            path = f"{data_path}.{field}[{index}]"
            if not isinstance(entry, dict) or not all(isinstance(entry.get(key), int) and not isinstance(entry.get(key), bool) for key in ("q", "r")):
                _issue(issues, "error", file, path, "정수 axial 좌표 q, r이 필요합니다.")
                continue
            coordinate = (entry["q"], entry["r"])
            if coordinate in result: _issue(issues, "error", file, path, f"중복 좌표입니다: {coordinate}")
            result.add(coordinate)
        return result
    cells = coordinates(cells_value, "town_footprint_cells")
    exits = coordinates(exits_value, "town_road_exits")
    if isinstance(expected_count, int) and len(cells) != expected_count:
        _issue(issues, "error", file, f"{data_path}.town_footprint_cells", f"마을 크기와 동일한 {expected_count}개 타일이 필요합니다.")
    if (0, 0) not in cells:
        _issue(issues, "error", file, f"{data_path}.town_footprint_cells", "중심 타일 q=0, r=0이 필요합니다.")
    directions = ((1, 0), (0, 1), (-1, 1), (-1, 0), (0, -1), (1, -1))
    if cells:
        visited = {next(iter(cells))}; queue = list(visited)
        while queue:
            q, r = queue.pop()
            for dq, dr in directions:
                neighbor = (q + dq, r + dr)
                if neighbor in cells and neighbor not in visited: visited.add(neighbor); queue.append(neighbor)
        if visited != cells: _issue(issues, "error", file, f"{data_path}.town_footprint_cells", "모든 커스텀 마을 타일이 서로 이어져야 합니다.")
    if not exits: _issue(issues, "error", file, f"{data_path}.town_road_exits", "외부 월드 도로가 접속할 출구를 하나 이상 지정해야 합니다.")
    for exit_cell in exits:
        if exit_cell not in cells:
            _issue(issues, "error", file, f"{data_path}.town_road_exits", f"출구 타일이 마을 범위에 없습니다: {exit_cell}")
        elif all((exit_cell[0] + dq, exit_cell[1] + dr) in cells for dq, dr in directions):
            _issue(issues, "error", file, f"{data_path}.town_road_exits", f"출구는 외곽 타일에 있어야 합니다: {exit_cell}")
    return cells


def validate_hex_worlds(
    root: Path,
    settlement_ids: set[str],
    cave_documents: dict[str, dict[str, Any]] | None = None,
) -> list[Issue]:
    issues: list[Issue] = []
    known_pokemon: set[str] | None = None
    pokemon_catalog_path = root / "content" / "catalogs" / "pokemon-habitats.json"
    if pokemon_catalog_path.is_file():
        try:
            pokemon_catalog = load_json(pokemon_catalog_path)
            known_pokemon = {
                entry["id"] for entry in pokemon_catalog.get("pokemon", [])
                if isinstance(entry, dict) and isinstance(entry.get("id"), str)
            }
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            known_pokemon = None
    if cave_documents is None:
        cave_documents = {}
        cave_dir = root / "content" / "caves"
        for cave_path in cave_dir.rglob("*.json") if cave_dir.is_dir() else []:
            try:
                cave_data = load_json(cave_path)
                if isinstance(cave_data, dict) and isinstance(cave_data.get("id"), str):
                    cave_documents[cave_data["id"]] = cave_data
            except (OSError, json.JSONDecodeError, DuplicateKeyError):
                continue

    def validate_terrain(value: Any, file: Path, data_path: str) -> None:
        if not isinstance(value, dict):
            _issue(issues, "error", file, data_path, "지형 높이 프로필 객체가 필요합니다.")
            return
        offset = value.get("base_height_offset")
        variation = value.get("height_variation")
        scale = value.get("noise_scale_blocks")
        connection_height = value.get("connection_height", 0)
        if not isinstance(offset, int) or isinstance(offset, bool) or not -48 <= offset <= 32:
            _issue(issues, "error", file, f"{data_path}.base_height_offset", "-48 이상 32 이하의 정수가 필요합니다.")
        if not isinstance(variation, int) or isinstance(variation, bool) or not 0 <= variation <= 8:
            _issue(issues, "error", file, f"{data_path}.height_variation", "0 이상 8 이하의 정수가 필요합니다.")
        if not isinstance(scale, (int, float)) or isinstance(scale, bool) or not 16 <= scale <= 512:
            _issue(issues, "error", file, f"{data_path}.noise_scale_blocks", "16 이상 512 이하의 노이즈 크기가 필요합니다.")
        if (not isinstance(connection_height, int) or isinstance(connection_height, bool)
                or not -8 <= connection_height <= 8):
            _issue(issues, "error", file, f"{data_path}.connection_height", "-8 이상 8 이하의 연결 높이 단계가 필요합니다.")

    def validate_access(value: Any, file: Path, data_path: str) -> None:
        if value is not None and (not isinstance(value, str) or not RESOURCE_ID.fullmatch(value)):
            _issue(issues, "error", file, data_path, "올바른 필드 기술 리소스 ID가 필요합니다.")

    def validate_access_height(terrain: Any, access: Any, file: Path, data_path: str) -> None:
        if access != "cobbleventure:field_move/rock_climb" or not isinstance(terrain, dict):
            return
        offset = terrain.get("base_height_offset")
        variation = terrain.get("height_variation")
        if (isinstance(offset, int) and not isinstance(offset, bool)
                and isinstance(variation, int) and not isinstance(variation, bool)
                and offset - variation < 6):
            _issue(
                issues, "error", file, f"{data_path}.terrain_profile",
                "바위오르기 지역은 최저 지점도 기본 지표보다 6블록 이상 높아야 합니다."
            )

    world_dir = root / "content" / "worlds"
    if not world_dir.exists():
        _issue(issues, "warning", world_dir, "$", "육각 세대 월드 데이터가 아직 없습니다.")
        return issues
    boundary_path = _catalog_path(root, "boundary-profiles.json")
    boundary_ids: set[str] = set()
    try:
        boundary_data = load_json(boundary_path)
        profiles = boundary_data.get("profiles") if isinstance(boundary_data, dict) else None
        if not isinstance(boundary_data, dict) or boundary_data.get("schema_version") != 1:
            _issue(issues, "error", boundary_path, "$.schema_version", "지원 버전은 1입니다.")
        if not isinstance(profiles, list) or not profiles:
            _issue(issues, "error", boundary_path, "$.profiles", "경계 프로필 배열이 필요합니다.")
            profiles = []
        for index, profile in enumerate(profiles):
            profile_path = f"$.profiles[{index}]"
            if not isinstance(profile, dict):
                _issue(issues, "error", boundary_path, profile_path, "경계 프로필은 객체여야 합니다.")
                continue
            profile_id = profile.get("id")
            if not isinstance(profile_id, str) or not RESOURCE_ID.fullmatch(profile_id):
                _issue(issues, "error", boundary_path, f"{profile_path}.id", "올바른 경계 프로필 ID가 필요합니다.")
            elif profile_id in boundary_ids:
                _issue(issues, "error", boundary_path, f"{profile_path}.id", f"중복 경계 프로필 ID: {profile_id}")
            else:
                boundary_ids.add(profile_id)
            if profile.get("type") not in {"wall", "earthwork", "tree_line"}:
                _issue(issues, "error", boundary_path, f"{profile_path}.type", "지원하지 않는 경계 타입입니다.")
            if profile.get("collision") not in {"hard", "protected", "soft"}:
                _issue(issues, "error", boundary_path, f"{profile_path}.collision", "지원하지 않는 충돌 방식입니다.")
            if profile.get("type") == "tree_line" and not isinstance(profile.get("tree"), dict):
                _issue(issues, "error", boundary_path, f"{profile_path}.tree", "수목 경계에는 나무 설정이 필요합니다.")
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", boundary_path, "$", f"경계 프로필을 읽을 수 없습니다: {error}")

    for path in sorted(world_dir.rglob("*.json")):
        try:
            world = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
            _issue(issues, "error", path, "$", f"육각 월드 데이터를 읽을 수 없습니다: {error}")
            continue
        if not isinstance(world, dict):
            _issue(issues, "error", path, "$", "육각 월드 데이터는 객체여야 합니다.")
            continue
        schema_version = world.get("schema_version")
        if schema_version not in {1, 2}:
            _issue(issues, "error", path, "$.schema_version", "지원 버전은 1 또는 2입니다.")
        grid = world.get("grid")
        if not isinstance(grid, dict) or grid.get("orientation") != "pointy_top":
            _issue(issues, "error", path, "$.grid.orientation", "pointy_top 육각 격자만 지원합니다.")
        radius = grid.get("tile_radius_blocks") if isinstance(grid, dict) else None
        if not isinstance(radius, int) or isinstance(radius, bool) or not 32 <= radius <= 256:
            _issue(issues, "error", path, "$.grid.tile_radius_blocks", "32 이상 256 이하의 정수여야 합니다.")
        map_radius = grid.get("map_radius_cells") if isinstance(grid, dict) else None
        if map_radius is not None and (not isinstance(map_radius, int) or isinstance(map_radius, bool) or not 3 <= map_radius <= 14):
            _issue(issues, "error", path, "$.grid.map_radius_cells", "3 이상 14 이하의 정수여야 합니다.")
        empty_terrain = world.get("empty_terrain", {"default_type": "high_forest", "tiles": []})
        empty_types = {"high_forest", "ocean", "desert", "stone_mountain", "snow_mountain"}
        empty_coordinates: set[tuple[int, int]] = set()
        if not isinstance(empty_terrain, dict):
            _issue(issues, "error", path, "$.empty_terrain", "빈 지형 설정은 객체여야 합니다.")
        else:
            if empty_terrain.get("default_type", "high_forest") not in empty_types:
                _issue(issues, "error", path, "$.empty_terrain.default_type", "지원하지 않는 빈 지형 타입입니다.")
            empty_tiles = empty_terrain.get("tiles", [])
            if not isinstance(empty_tiles, list):
                _issue(issues, "error", path, "$.empty_terrain.tiles", "빈 지형 타일 배열이 필요합니다.")
            else:
                for index, empty_tile in enumerate(empty_tiles):
                    empty_path = f"$.empty_terrain.tiles[{index}]"
                    if not isinstance(empty_tile, dict):
                        _issue(issues, "error", path, empty_path, "빈 지형 타일은 객체여야 합니다.")
                        continue
                    q, r = empty_tile.get("q"), empty_tile.get("r")
                    coordinate = (q, r) if all(isinstance(value, int) and not isinstance(value, bool) for value in (q, r)) else None
                    if coordinate is None:
                        _issue(issues, "error", path, empty_path, "정수 axial 좌표 q, r이 필요합니다.")
                    elif coordinate in empty_coordinates:
                        _issue(issues, "error", path, empty_path, f"중복 빈 지형 타일: {coordinate}")
                    else:
                        empty_coordinates.add(coordinate)
                    if empty_tile.get("type") not in empty_types:
                        _issue(issues, "error", path, f"{empty_path}.type", "지원하지 않는 빈 지형 타입입니다.")
        entries = world.get("settlements")
        world_settlements: set[str] = set()
        settlement_anchors: dict[str, tuple[int, int]] = {}
        custom_exit_counts: dict[str, int] = {}
        if not isinstance(entries, list):
            _issue(issues, "error", path, "$.settlements", "마을 셀 설정 배열이 필요합니다.")
            entries = []
        occupied_anchors: set[tuple[int, int]] = set()
        occupied_town_ranges: list[tuple[tuple[int, int], tuple[int, str, set[tuple[int, int]]], str]] = []
        for index, entry in enumerate(entries):
            entry_path = f"$.settlements[{index}]"
            if not isinstance(entry, dict):
                _issue(issues, "error", path, entry_path, "마을 셀 설정은 객체여야 합니다.")
                continue
            settlement = entry.get("settlement")
            if not isinstance(settlement, str) or settlement not in settlement_ids:
                _issue(issues, "error", path, f"{entry_path}.settlement", f"존재하지 않는 마을 ID: {settlement}")
            elif settlement in world_settlements:
                _issue(issues, "error", path, f"{entry_path}.settlement", f"중복 마을 셀 설정: {settlement}")
            else:
                world_settlements.add(settlement)
            _resource_id(entry.get("town_biome"), issues, path, f"{entry_path}.town_biome")
            anchor = entry.get("anchor")
            coordinate = None
            if isinstance(anchor, dict) and isinstance(anchor.get("q"), int) and isinstance(anchor.get("r"), int):
                coordinate = (anchor["q"], anchor["r"])
            if coordinate is None:
                _issue(issues, "error", path, f"{entry_path}.anchor", "정수 axial 좌표 q, r이 필요합니다.")
            elif coordinate in occupied_anchors:
                _issue(issues, "error", path, f"{entry_path}.anchor", f"중복 마을 앵커 셀: {coordinate}")
            else:
                occupied_anchors.add(coordinate)
                if isinstance(settlement, str):
                    settlement_anchors[settlement] = coordinate
            town_radius = entry.get("town_radius_cells")
            town_shape = str(entry.get("town_footprint_shape", "line_q"))
            if town_shape not in {"triangle_up", "triangle_down", "line_q", "line_r", "line_s", "five_up", "five_down", "custom"}:
                _issue(issues, "error", path, f"{entry_path}.town_footprint_shape", "지원하지 않는 마을 배치 형태입니다.")
            if not isinstance(town_radius, int) or isinstance(town_radius, bool) or town_radius not in (1, 3, 5, 7, 19):
                _issue(issues, "error", path, f"{entry_path}.town_radius_cells", "마을 크기는 1칸, 3칸, 5칸, 7칸, 19칸 중 하나여야 합니다.")
            elif town_radius == 3 and town_shape != "custom" and town_shape not in {"triangle_up", "triangle_down", "line_q", "line_r", "line_s"}:
                _issue(issues, "error", path, f"{entry_path}.town_footprint_shape", "3칸 마을은 삼각형 또는 일자 형태여야 합니다.")
            elif town_radius == 5 and town_shape != "custom" and town_shape not in {"five_up", "five_down"}:
                _issue(issues, "error", path, f"{entry_path}.town_footprint_shape", "5칸 마을은 위 확장 또는 아래 확장 형태여야 합니다.")
            custom_cells = _validate_custom_town_layout(entry.get("town_footprint_cells"), entry.get("town_road_exits"), town_radius, issues, path, entry_path) if town_shape == "custom" else set()
            if town_shape == "custom" and isinstance(settlement, str):
                custom_exit_counts[settlement] = len(entry.get("town_road_exits", [])) if isinstance(entry.get("town_road_exits"), list) else 0
            if coordinate is not None and isinstance(town_radius, int):
                for other_coordinate, other_radius, other_settlement in occupied_town_ranges:
                    footprint = _town_footprint(coordinate, town_radius, town_shape, custom_cells)
                    other_footprint = _town_footprint(other_coordinate, other_radius[0], other_radius[1], other_radius[2])
                    if any(_hex_distance(cell, other_cell) < 2 for cell in footprint for other_cell in other_footprint):
                        _issue(
                            issues,
                            "error",
                            path,
                            f"{entry_path}.town_radius_cells",
                            f"마을 외곽 사이에 최소 한 칸의 완충 지형이 필요합니다: {other_settlement}",
                        )
                occupied_town_ranges.append((coordinate, (town_radius, town_shape, custom_cells), str(settlement)))
            boundary = entry.get("boundary_profile")
            if boundary not in boundary_ids:
                _issue(issues, "error", path, f"{entry_path}.boundary_profile", f"존재하지 않는 경계 프로필: {boundary}")
            validate_terrain(entry.get("terrain_profile"), path, f"{entry_path}.terrain_profile")
            validate_access(entry.get("access_requirement"), path, f"{entry_path}.access_requirement")
            validate_access_height(entry.get("terrain_profile"), entry.get("access_requirement"), path, entry_path)
            surroundings = entry.get("surroundings")
            if not isinstance(surroundings, list):
                _issue(issues, "error", path, f"{entry_path}.surroundings", "주변 바이옴 배열이 필요합니다.")
                continue
            seen_regions: set[str] = set()
            for region_index, region in enumerate(surroundings):
                region_path = f"{entry_path}.surroundings[{region_index}]"
                if not isinstance(region, dict):
                    _issue(issues, "error", path, region_path, "주변 바이옴은 객체여야 합니다.")
                    continue
                region_id = region.get("id")
                if not isinstance(region_id, str) or not CHOICE_ID.fullmatch(region_id):
                    _issue(issues, "error", path, f"{region_path}.id", "올바른 주변 바이옴 ID가 아닙니다.")
                elif region_id in seen_regions:
                    _issue(issues, "error", path, f"{region_path}.id", f"중복 주변 바이옴 ID: {region_id}")
                else:
                    seen_regions.add(region_id)
                tile_count = region.get("tile_count")
                if not isinstance(tile_count, int) or isinstance(tile_count, bool) or tile_count < 1:
                    _issue(issues, "error", path, f"{region_path}.tile_count", "1 이상의 셀 수가 필요합니다.")
                influence_radius = region.get("influence_radius_blocks")
                if not isinstance(influence_radius, (int, float)) or isinstance(influence_radius, bool) or not 24 <= influence_radius <= 512:
                    _issue(issues, "error", path, f"{region_path}.influence_radius_blocks", "24 이상 512 이하의 영향 반경이 필요합니다.")
                edge_noise = region.get("edge_noise")
                if not isinstance(edge_noise, (int, float)) or isinstance(edge_noise, bool) or not 0 <= edge_noise <= 0.45:
                    _issue(issues, "error", path, f"{region_path}.edge_noise", "0 이상 0.45 이하의 경계 굴곡값이 필요합니다.")
                region_boundary = region.get("boundary_profile")
                if region_boundary not in boundary_ids:
                    _issue(issues, "error", path, f"{region_path}.boundary_profile", f"존재하지 않는 경계 프로필: {region_boundary}")
                validate_terrain(region.get("terrain_profile"), path, f"{region_path}.terrain_profile")
                validate_access(region.get("access_requirement"), path, f"{region_path}.access_requirement")
                validate_access_height(region.get("terrain_profile"), region.get("access_requirement"), path, region_path)
        tiles = world.get("tiles", [])
        if schema_version == 2 and not isinstance(tiles, list):
            _issue(issues, "error", path, "$.tiles", "직접 배치 타일 배열이 필요합니다.")
            tiles = []
        occupied_tiles: set[tuple[int, int]] = set()
        for index, tile in enumerate(tiles if isinstance(tiles, list) else []):
            tile_path = f"$.tiles[{index}]"
            if not isinstance(tile, dict):
                _issue(issues, "error", path, tile_path, "직접 배치 타일은 객체여야 합니다.")
                continue
            q, r = tile.get("q"), tile.get("r")
            coordinate = (q, r) if all(isinstance(value, int) and not isinstance(value, bool) for value in (q, r)) else None
            if coordinate is None:
                _issue(issues, "error", path, tile_path, "정수 axial 좌표 q, r이 필요합니다.")
            elif coordinate in occupied_tiles:
                _issue(issues, "error", path, tile_path, f"중복 직접 배치 타일: {coordinate}")
            elif coordinate in occupied_anchors:
                _issue(issues, "error", path, tile_path, f"마을과 같은 좌표에는 바이옴 타일을 배치할 수 없습니다: {coordinate}")
            else:
                occupied_tiles.add(coordinate)
            biome = tile.get("biome")
            if not isinstance(biome, str) or not RESOURCE_ID.fullmatch(biome):
                _issue(issues, "error", path, f"{tile_path}.biome", "올바른 바이옴 리소스 ID가 필요합니다.")
            boundary = tile.get("boundary_profile")
            if boundary not in boundary_ids:
                _issue(issues, "error", path, f"{tile_path}.boundary_profile", f"존재하지 않는 경계 프로필: {boundary}")
            validate_terrain(tile.get("terrain_profile"), path, f"{tile_path}.terrain_profile")
        environment_overrides = world.get("environment_overrides", [])
        if not isinstance(environment_overrides, list):
            _issue(issues, "error", path, "$.environment_overrides", "기후 오버라이드 배열이 필요합니다.")
            environment_overrides = []
        seen_environment_coordinates: set[tuple[int, int]] = set()
        environment_values = {
            "temperature": {"cold", "cool", "temperate", "hot"},
            "humidity": {"dry", "normal", "humid", "aquatic"},
            "weather": {"clear", "rain", "thunder", "snow", "fog"},
        }
        for index, override in enumerate(environment_overrides):
            override_path = f"$.environment_overrides[{index}]"
            if not isinstance(override, dict):
                _issue(issues, "error", path, override_path, "기후 오버라이드는 객체여야 합니다.")
                continue
            q, r = override.get("q"), override.get("r")
            coordinate = (q, r) if all(isinstance(value, int) and not isinstance(value, bool) for value in (q, r)) else None
            if coordinate is None:
                _issue(issues, "error", path, override_path, "정수 axial 좌표 q, r이 필요합니다.")
            elif coordinate in seen_environment_coordinates:
                _issue(issues, "error", path, override_path, f"중복 기후 오버라이드 좌표: {coordinate}")
            else:
                seen_environment_coordinates.add(coordinate)
            configured_fields = 0
            for field, allowed in environment_values.items():
                value = override.get(field)
                if value is None:
                    continue
                configured_fields += 1
                if value not in allowed:
                    _issue(issues, "error", path, f"{override_path}.{field}", f"지원하지 않는 {field} 값입니다: {value}")
            if configured_fields == 0:
                _issue(issues, "error", path, override_path, "온도, 습도, 날씨 중 하나 이상을 덮어써야 합니다.")
        level_overrides = world.get("level_overrides", [])
        if not isinstance(level_overrides, list):
            _issue(issues, "error", path, "$.level_overrides", "레벨 오버라이드 배열이 필요합니다.")
            level_overrides = []
        seen_level_coordinates: set[tuple[int, int]] = set()
        for index, override in enumerate(level_overrides):
            override_path = f"$.level_overrides[{index}]"
            if not isinstance(override, dict):
                _issue(issues, "error", path, override_path, "레벨 오버라이드는 객체여야 합니다.")
                continue
            q, r = override.get("q"), override.get("r")
            coordinate = (q, r) if all(isinstance(value, int) and not isinstance(value, bool) for value in (q, r)) else None
            if coordinate is None:
                _issue(issues, "error", path, override_path, "정수 axial 좌표 q, r이 필요합니다.")
            elif coordinate in seen_level_coordinates:
                _issue(issues, "error", path, override_path, f"중복 레벨 오버라이드 좌표: {coordinate}")
            else:
                seen_level_coordinates.add(coordinate)
            average_level = override.get("average_level")
            if not isinstance(average_level, int) or isinstance(average_level, bool) or not 1 <= average_level <= 100:
                _issue(issues, "error", path, f"{override_path}.average_level", "평균 레벨은 1부터 100 사이의 정수여야 합니다.")
        cave_entrances = world.get("cave_entrances", [])
        if not isinstance(cave_entrances, list):
            _issue(issues, "error", path, "$.cave_entrances", "동굴 입구 목록은 배열이어야 합니다.")
            cave_entrances = []
        seen_cave_entrance_ids: set[str] = set()
        seen_cave_pairs: set[tuple[str, str]] = set()
        cave_entrance_anchors: dict[str, tuple[int, int]] = {}
        for index, placement in enumerate(cave_entrances):
            placement_path = f"$.cave_entrances[{index}]"
            if not isinstance(placement, dict):
                _issue(issues, "error", path, placement_path, "동굴 입구 배치는 객체여야 합니다.")
                continue
            placement_id = placement.get("id")
            if not isinstance(placement_id, str) or not RESOURCE_ID.fullmatch(placement_id) or placement_id in seen_cave_entrance_ids:
                _issue(issues, "error", path, f"{placement_path}.id", "유일한 동굴 입구 리소스 ID가 필요합니다.")
            else:
                seen_cave_entrance_ids.add(placement_id)
            cave_id = placement.get("cave")
            entrance_id = placement.get("entrance")
            cave = cave_documents.get(cave_id) if isinstance(cave_id, str) else None
            if cave is None:
                _issue(issues, "error", path, f"{placement_path}.cave", f"존재하지 않는 동굴 ID: {cave_id}")
            entrance_ids = {
                item.get("id") for item in cave.get("entrances", [])
                if isinstance(item, dict) and isinstance(item.get("id"), str)
            } if cave else set()
            if not isinstance(entrance_id, str) or entrance_id not in entrance_ids:
                _issue(issues, "error", path, f"{placement_path}.entrance", f"동굴에 없는 내부 입구 ID: {entrance_id}")
            pair = (str(cave_id), str(entrance_id))
            if pair in seen_cave_pairs:
                _issue(issues, "error", path, placement_path, "같은 동굴 내부 입구를 월드맵에 중복 배치할 수 없습니다.")
            seen_cave_pairs.add(pair)
            anchor = placement.get("anchor")
            if not isinstance(anchor, dict) or not all(isinstance(anchor.get(key), int) and not isinstance(anchor.get(key), bool) for key in ("q", "r")):
                _issue(issues, "error", path, f"{placement_path}.anchor", "정수 axial 좌표 q, r이 필요합니다.")
            elif isinstance(placement_id, str):
                cave_entrance_anchors[placement_id] = (anchor["q"], anchor["r"])
            _resource_id(placement.get("structure"), issues, path, f"{placement_path}.structure")
            center = placement.get("pokemon_center")
            if not isinstance(center, dict):
                _issue(issues, "error", path, f"{placement_path}.pokemon_center", "모든 동굴 입구에는 포켓몬센터 설정이 필요합니다.")
            else:
                _resource_id(center.get("structure"), issues, path, f"{placement_path}.pokemon_center.structure")
                offset = center.get("offset")
                if not isinstance(offset, dict) or not all(isinstance(offset.get(key), int) and not isinstance(offset.get(key), bool) for key in ("q", "r")):
                    _issue(issues, "error", path, f"{placement_path}.pokemon_center.offset", "포켓몬센터의 정수 axial 오프셋 q, r이 필요합니다.")

        connections = world.get("connections")
        if not isinstance(connections, list):
            _issue(issues, "error", path, "$.connections", "연결 목록은 배열이어야 합니다.")
            connections = []
        seen_connections: set[str] = set()
        connection_degrees: dict[str, int] = {}
        for index, connection in enumerate(connections):
            connection_path = f"$.connections[{index}]"
            if not isinstance(connection, dict):
                _issue(issues, "error", path, connection_path, "연결 설정은 객체여야 합니다.")
                continue
            connection_id = connection.get("id")
            if not isinstance(connection_id, str) or connection_id in seen_connections:
                _issue(issues, "error", path, f"{connection_path}.id", "유일한 연결 ID가 필요합니다.")
            else:
                seen_connections.add(connection_id)
            display_name = connection.get("display_name")
            if display_name is not None and (
                not isinstance(display_name, str) or not 1 <= len(display_name.strip()) <= 100
            ):
                _issue(issues, "error", path, f"{connection_path}.display_name", "길 이름은 1자 이상 100자 이하 문자열이어야 합니다.")
            for field in ("from", "to"):
                target = connection.get(field)
                if target is not None and target not in world_settlements and target not in cave_entrance_anchors:
                    _issue(issues, "error", path, f"{connection_path}.{field}", f"월드 지도에 없는 마을 또는 동굴 입구입니다: {target}")
                elif isinstance(target, str) and target in world_settlements:
                    connection_degrees[target] = connection_degrees.get(target, 0) + 1
            boundary = connection.get("boundary_profile")
            if boundary is not None and boundary not in boundary_ids:
                _issue(issues, "error", path, f"{connection_path}.boundary_profile", f"존재하지 않는 경계 프로필: {boundary}")
            corridor_width = connection.get("corridor_width_blocks")
            if not isinstance(corridor_width, (int, float)) or isinstance(corridor_width, bool) or not 12 <= corridor_width <= 256:
                _issue(issues, "error", path, f"{connection_path}.corridor_width_blocks", "12 이상 256 이하의 통로 폭이 필요합니다.")
            edge_noise = connection.get("edge_noise")
            if not isinstance(edge_noise, (int, float)) or isinstance(edge_noise, bool) or not 0 <= edge_noise <= 0.35:
                _issue(issues, "error", path, f"{connection_path}.edge_noise", "0 이상 0.35 이하의 통로 경계 굴곡값이 필요합니다.")
            if connection.get("terrain_profile") is not None:
                validate_terrain(connection.get("terrain_profile"), path, f"{connection_path}.terrain_profile")
            if connection.get("surface_style") not in {"road", "natural", "water"}:
                _issue(issues, "error", path, f"{connection_path}.surface_style", "road, natural, water 중 하나가 필요합니다.")
            pokemon_spawns = connection.get("pokemon_spawns")
            if pokemon_spawns is not None:
                if not isinstance(pokemon_spawns, dict):
                    _issue(issues, "error", path, f"{connection_path}.pokemon_spawns", "길 포켓몬 설정은 객체여야 합니다.")
                else:
                    if not isinstance(pokemon_spawns.get("inherit_biome"), bool):
                        _issue(issues, "error", path, f"{connection_path}.pokemon_spawns.inherit_biome", "기존 바이옴 포켓몬 사용 여부가 필요합니다.")
                    excluded = pokemon_spawns.get("excluded_species")
                    if not isinstance(excluded, list):
                        _issue(issues, "error", path, f"{connection_path}.pokemon_spawns.excluded_species", "제외 포켓몬 목록은 배열이어야 합니다.")
                    else:
                        seen_species: set[str] = set()
                        for species_index, species in enumerate(excluded):
                            species_path = f"{connection_path}.pokemon_spawns.excluded_species[{species_index}]"
                            if not isinstance(species, str) or not RESOURCE_ID.fullmatch(species):
                                _issue(issues, "error", path, species_path, "올바른 포켓몬 리소스 ID가 필요합니다.")
                            elif known_pokemon is not None and species not in known_pokemon:
                                _issue(issues, "error", path, species_path, f"포켓몬 카탈로그에 없는 종입니다: {species}")
                            elif species in seen_species:
                                _issue(issues, "error", path, species_path, f"중복 제외 포켓몬: {species}")
                            else:
                                seen_species.add(species)
                    additions = pokemon_spawns.get("additions")
                    if not isinstance(additions, list):
                        _issue(issues, "error", path, f"{connection_path}.pokemon_spawns.additions", "추가 포켓몬 목록은 배열이어야 합니다.")
                    else:
                        seen_additions: set[str] = set()
                        for addition_index, addition in enumerate(additions):
                            addition_path = f"{connection_path}.pokemon_spawns.additions[{addition_index}]"
                            if not isinstance(addition, dict):
                                _issue(issues, "error", path, addition_path, "추가 포켓몬 설정은 객체여야 합니다.")
                                continue
                            species = addition.get("species")
                            if not isinstance(species, str) or not RESOURCE_ID.fullmatch(species):
                                _issue(issues, "error", path, f"{addition_path}.species", "올바른 포켓몬 리소스 ID가 필요합니다.")
                            elif known_pokemon is not None and species not in known_pokemon:
                                _issue(issues, "error", path, f"{addition_path}.species", f"포켓몬 카탈로그에 없는 종입니다: {species}")
                            elif species in seen_additions:
                                _issue(issues, "error", path, f"{addition_path}.species", f"중복 추가 포켓몬: {species}")
                            else:
                                seen_additions.add(species)
                            minimum = addition.get("min_level")
                            maximum = addition.get("max_level")
                            if not isinstance(minimum, int) or isinstance(minimum, bool) or not 1 <= minimum <= 100:
                                _issue(issues, "error", path, f"{addition_path}.min_level", "최소 출현 레벨은 1부터 100 사이 정수여야 합니다.")
                            if not isinstance(maximum, int) or isinstance(maximum, bool) or not 1 <= maximum <= 100:
                                _issue(issues, "error", path, f"{addition_path}.max_level", "최대 출현 레벨은 1부터 100 사이 정수여야 합니다.")
                            if isinstance(minimum, int) and isinstance(maximum, int) and minimum > maximum:
                                _issue(issues, "error", path, addition_path, "최소 출현 레벨은 최대 출현 레벨보다 클 수 없습니다.")
            validate_access(connection.get("access_requirement"), path, f"{connection_path}.access_requirement")
            validate_access_height(
                connection.get("terrain_profile"), connection.get("access_requirement"), path, connection_path
            )
            anchors = connection.get("anchors")
            anchor_coordinates: list[tuple[int, int]] = []
            if anchors is not None:
                if not isinstance(anchors, list) or len(anchors) < 2:
                    _issue(issues, "error", path, f"{connection_path}.anchors", "길 앵커는 두 개 이상 필요합니다.")
                else:
                    for anchor_index, anchor in enumerate(anchors):
                        if not isinstance(anchor, dict) or not all(isinstance(anchor.get(key), int) and not isinstance(anchor.get(key), bool) for key in ("q", "r")):
                            _issue(issues, "error", path, f"{connection_path}.anchors[{anchor_index}]", "앵커에 정수 axial 좌표 q, r이 필요합니다.")
                            continue
                        anchor_coordinates.append((anchor["q"], anchor["r"]))
            cells = connection.get("cells")
            if not isinstance(cells, list) or not cells:
                _issue(issues, "error", path, f"{connection_path}.cells", "직접 그린 길에는 셀 목록이 필요합니다.")
                continue
            coordinates = []
            for cell_index, cell in enumerate(cells):
                if not isinstance(cell, dict) or not all(isinstance(cell.get(key), int) and not isinstance(cell.get(key), bool) for key in ("q", "r")):
                    _issue(issues, "error", path, f"{connection_path}.cells[{cell_index}]", "정수 axial 좌표 q, r이 필요합니다.")
                    continue
                coordinates.append((cell["q"], cell["r"]))
            for cell_index in range(1, len(coordinates)):
                q1, r1 = coordinates[cell_index - 1]
                q2, r2 = coordinates[cell_index]
                distance = (abs(q1 - q2) + abs(r1 - r2) + abs((-q1 - r1) - (-q2 - r2))) // 2
                if distance != 1:
                    _issue(issues, "error", path, f"{connection_path}.cells[{cell_index}]", "길 셀은 앞 셀과 맞닿아야 합니다.")
            for field, endpoint_index in (("from", 0), ("to", -1)):
                target = connection.get(field)
                entrance_anchor = cave_entrance_anchors.get(target)
                if entrance_anchor is not None and coordinates and coordinates[endpoint_index] != entrance_anchor:
                    _issue(issues, "error", path, f"{connection_path}.cells", f"길의 {field} 끝은 동굴 입구 {target} 좌표까지 이어져야 합니다.")
            if anchor_coordinates and any(anchor not in coordinates for anchor in anchor_coordinates):
                _issue(issues, "error", path, f"{connection_path}.anchors", "모든 길 앵커는 계산된 경로 셀 위에 있어야 합니다.")
        for settlement_id, exit_count in custom_exit_counts.items():
            route_count = connection_degrees.get(settlement_id, 0)
            if exit_count < route_count:
                _issue(issues, "error", path, "$.connections", f"커스텀 마을 {settlement_id}은 외부 연결 {route_count}개에 맞춰 출구를 최소 {route_count}개 지정해야 합니다.")
        connected_targets = {
            connection.get(field)
            for connection in connections if isinstance(connection, dict)
            for field in ("from", "to")
        }
        for entrance_id in cave_entrance_anchors:
            if entrance_id not in connected_targets:
                _issue(issues, "error", path, "$.connections", f"동굴 입구까지 이어지는 길이 필요합니다: {entrance_id}")
        objects = world.get("objects", [])
        if not isinstance(objects, list):
            _issue(issues, "error", path, "$.objects", "커스텀 오브젝트 목록은 배열이어야 합니다.")
            objects = []
        seen_objects: set[str] = set()
        for index, custom_object in enumerate(objects):
            object_path = f"$.objects[{index}]"
            if not isinstance(custom_object, dict):
                _issue(issues, "error", path, object_path, "커스텀 오브젝트는 객체여야 합니다.")
                continue
            object_id = custom_object.get("id")
            if not isinstance(object_id, str) or not CHOICE_ID.fullmatch(object_id) or object_id in seen_objects:
                _issue(issues, "error", path, f"{object_path}.id", "유일한 오브젝트 ID가 필요합니다.")
            else:
                seen_objects.add(object_id)
            object_type = custom_object.get("type")
            if not isinstance(object_type, str) or not CHOICE_ID.fullmatch(object_type):
                _issue(issues, "error", path, f"{object_path}.type", "올바른 오브젝트 타입이 필요합니다.")
            anchor = custom_object.get("anchor")
            if not isinstance(anchor, dict) or not all(isinstance(anchor.get(key), int) and not isinstance(anchor.get(key), bool) for key in ("q", "r")):
                _issue(issues, "error", path, f"{object_path}.anchor", "정수 axial 좌표 q, r이 필요합니다.")
            resource = custom_object.get("resource")
            if resource is not None and (not isinstance(resource, str) or not RESOURCE_ID.fullmatch(resource)):
                _issue(issues, "error", path, f"{object_path}.resource", "올바른 오브젝트 리소스 ID가 필요합니다.")
            reserved_nbt_types = {
                "villain_base": "빌런기지",
                "legendary_site": "전설 포켓몬 장소",
            }
            if object_type in reserved_nbt_types:
                object_label = reserved_nbt_types[object_type]
                if not isinstance(resource, str) or not RESOURCE_ID.fullmatch(resource):
                    _issue(issues, "error", path, f"{object_path}.resource", f"{object_label} NBT 리소스 ID가 필요합니다.")
                rotation = custom_object.get("rotation")
                if not isinstance(rotation, int) or isinstance(rotation, bool) or rotation not in range(4):
                    _issue(issues, "error", path, f"{object_path}.rotation", f"{object_label} NBT 회전은 0~3이어야 합니다.")
                continue
            if object_type != "gate":
                continue
            properties = custom_object.get("properties")
            if not isinstance(properties, dict):
                _issue(issues, "error", path, f"{object_path}.properties", "관문 설정 객체가 필요합니다.")
                continue
            building_enabled = properties.get("building_enabled", True)
            if not isinstance(building_enabled, bool):
                _issue(issues, "error", path, f"{object_path}.properties.building_enabled", "건물 생성 여부는 boolean이어야 합니다.")
            if building_enabled and (not isinstance(resource, str) or not RESOURCE_ID.fullmatch(resource)):
                _issue(issues, "error", path, f"{object_path}.resource", "관문 건물 NBT 리소스 ID가 필요합니다.")
            rotation = custom_object.get("rotation")
            if not isinstance(rotation, int) or isinstance(rotation, bool) or rotation not in range(4):
                _issue(issues, "error", path, f"{object_path}.rotation", "관문 NBT 회전은 0~3이어야 합니다.")
            if properties.get("facing") not in {"north", "east", "south", "west"}:
                _issue(issues, "error", path, f"{object_path}.properties.facing", "관문 방향은 north/east/south/west 중 하나여야 합니다.")
            gate_mode = properties.get("gate_mode", "classic")
            if gate_mode not in {"classic", "npc_only", "system_only"}:
                _issue(issues, "error", path, f"{object_path}.properties.gate_mode", "관문 방식은 classic, npc_only, system_only 중 하나여야 합니다.")
            if properties.get("surrounding_type", "wall") not in {"wall", "trees", "none"}:
                _issue(issues, "error", path, f"{object_path}.properties.surrounding_type", "주변 차단물은 wall, trees, none 중 하나여야 합니다.")
            surrounding_type = properties.get("surrounding_type", "wall")
            block_fields = [("wall_block", "벽")]
            if surrounding_type == "trees":
                block_fields.extend((("tree_log", "나무 줄기"), ("tree_leaves", "나뭇잎")))
            for field, label in block_fields:
                block = properties.get(field)
                if not isinstance(block, str) or not RESOURCE_ID.fullmatch(block):
                    _issue(issues, "error", path, f"{object_path}.properties.{field}", f"관문 {label} 블록 리소스 ID가 필요합니다.")
            numeric_limits = {
                "wall_thickness": (1, 15), "wall_height": (3, 32),
                "opening_width": (3, 31), "barrier_height": (8, 128),
            }
            for field, (minimum, maximum) in numeric_limits.items():
                number = properties.get(field)
                if not isinstance(number, int) or isinstance(number, bool) or not minimum <= number <= maximum:
                    _issue(issues, "error", path, f"{object_path}.properties.{field}", f"{minimum}~{maximum} 범위 정수가 필요합니다.")
                elif field in {"wall_thickness", "opening_width"} and number % 2 == 0:
                    _issue(issues, "error", path, f"{object_path}.properties.{field}", "관문 중심 정렬을 위해 홀수여야 합니다.")
            if isinstance(properties.get("barrier_height"), int) and isinstance(properties.get("wall_height"), int) and properties["barrier_height"] <= properties["wall_height"]:
                _issue(issues, "error", path, f"{object_path}.properties.barrier_height", "배리어 높이는 벽 높이보다 커야 합니다.")
            if properties.get("condition_mode") not in {"all", "any"}:
                _issue(issues, "error", path, f"{object_path}.properties.condition_mode", "조건 조합은 all 또는 any여야 합니다.")
            npc = properties.get("npc")
            if npc is not None and (not isinstance(npc, str) or not RESOURCE_ID.fullmatch(npc)):
                _issue(issues, "error", path, f"{object_path}.properties.npc", "올바른 EasyNPC 프리셋 리소스 ID가 필요합니다.")
            if gate_mode == "npc_only" and npc is None:
                _issue(issues, "error", path, f"{object_path}.properties.npc", "NPC 전용 관문에는 NPC 프리셋이 필요합니다.")
            if gate_mode == "system_only":
                if npc is not None:
                    _issue(issues, "error", path, f"{object_path}.properties.npc", "시스템 전용 관문에는 NPC를 지정할 수 없습니다.")
                if building_enabled is not False or surrounding_type != "none":
                    _issue(issues, "error", path, f"{object_path}.properties", "시스템 전용 관문은 건물과 주변 지형을 생성할 수 없습니다.")
            deny_message = properties.get("deny_message")
            if deny_message is not None and (not isinstance(deny_message, str) or not deny_message.strip() or len(deny_message) > 256):
                _issue(issues, "error", path, f"{object_path}.properties.deny_message", "차단 문구는 1~256자의 문자열이어야 합니다.")
            if gate_mode == "system_only" and deny_message is None:
                _issue(issues, "error", path, f"{object_path}.properties.deny_message", "시스템 전용 관문에는 차단 문구가 필요합니다.")
            conditions = properties.get("conditions")
            if not isinstance(conditions, list):
                _issue(issues, "error", path, f"{object_path}.properties.conditions", "관문 조건 배열이 필요합니다.")
                continue
            if gate_mode == "system_only" and not conditions:
                _issue(issues, "error", path, f"{object_path}.properties.conditions", "시스템 전용 관문에는 통과 조건이 하나 이상 필요합니다.")
            for condition_index, condition in enumerate(conditions):
                condition_path = f"{object_path}.properties.conditions[{condition_index}]"
                if not isinstance(condition, dict):
                    _issue(issues, "error", path, condition_path, "관문 조건은 객체여야 합니다.")
                    continue
                condition_type = condition.get("type")
                if condition_type == "variable":
                    if condition.get("source") not in {"scoreboard", "persistent_data"}:
                        _issue(issues, "error", path, f"{condition_path}.source", "변수 출처는 scoreboard 또는 persistent_data여야 합니다.")
                    if not isinstance(condition.get("key"), str) or not re.fullmatch(r"[A-Za-z0-9_.-]+", condition["key"]):
                        _issue(issues, "error", path, f"{condition_path}.key", "올바른 변수 키가 필요합니다.")
                    if condition.get("operator") not in {"==", "!=", ">", ">=", "<", "<="}:
                        _issue(issues, "error", path, f"{condition_path}.operator", "지원하지 않는 변수 비교 연산자입니다.")
                    if not isinstance(condition.get("value"), (int, float)) or isinstance(condition.get("value"), bool):
                        _issue(issues, "error", path, f"{condition_path}.value", "비교할 숫자 값이 필요합니다.")
                elif condition_type == "item":
                    if not isinstance(condition.get("item"), str) or not RESOURCE_ID.fullmatch(condition["item"]):
                        _issue(issues, "error", path, f"{condition_path}.item", "올바른 아이템 리소스 ID가 필요합니다.")
                    if not isinstance(condition.get("count"), int) or isinstance(condition.get("count"), bool) or condition["count"] < 1:
                        _issue(issues, "error", path, f"{condition_path}.count", "아이템 수량은 1 이상 정수여야 합니다.")
                elif condition_type == "pokemon":
                    if not isinstance(condition.get("species"), str) or not RESOURCE_ID.fullmatch(condition["species"]):
                        _issue(issues, "error", path, f"{condition_path}.species", "올바른 포켓몬 종 리소스 ID가 필요합니다.")
                else:
                    _issue(issues, "error", path, f"{condition_path}.type", "관문 조건 타입은 variable/item/pokemon 중 하나여야 합니다.")
    return issues


def validate_biome_catalogs(root: Path) -> list[Issue]:
    issues: list[Issue] = []
    biome_path = _catalog_path(root, "biome-profiles.json")
    pokemon_path = _catalog_path(root, "pokemon-habitats.json")
    try:
        pokemon_data = load_pokemon_habitats(root)
        pokemon = pokemon_data.get("pokemon")
        if pokemon_data.get("schema_version") != 1:
            _issue(issues, "error", pokemon_path, "$.schema_version", "지원 버전은 1입니다.")
        if not isinstance(pokemon, list) or not pokemon:
            _issue(issues, "error", pokemon_path, "$.pokemon", "포켓몬 배열이 필요합니다.")
            pokemon = []
        pokemon_ids: set[str] = set()
        for index, entry in enumerate(pokemon):
            entry_id = entry.get("id") if isinstance(entry, dict) else None
            if not isinstance(entry_id, str) or not RESOURCE_ID.fullmatch(entry_id):
                _issue(issues, "error", pokemon_path, f"$.pokemon[{index}].id", "올바른 포켓몬 리소스 ID가 필요합니다.")
            elif entry_id in pokemon_ids:
                _issue(issues, "error", pokemon_path, f"$.pokemon[{index}].id", f"중복 포켓몬 ID: {entry_id}")
            else:
                pokemon_ids.add(entry_id)
            habitat = entry.get("habitats", {}).get("primary") if isinstance(entry, dict) else None
            if habitat not in HABITAT_IDS:
                _issue(issues, "error", pokemon_path, f"$.pokemon[{index}].habitats.primary", "지원하지 않는 대표 서식지입니다.")
            series = entry.get("series_appearances") if isinstance(entry, dict) else None
            if not isinstance(series, list) or any(value not in POKEDEX_SERIES_IDS for value in series):
                _issue(issues, "error", pokemon_path, f"$.pokemon[{index}].series_appearances", "올바른 지역도감 시리즈 배열이 필요합니다.")
            for field in ("is_legendary", "is_mythical"):
                if not isinstance(entry.get(field), bool):
                    _issue(issues, "error", pokemon_path, f"$.pokemon[{index}].{field}", "불리언 값이 필요합니다.")
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", pokemon_path, "$", f"카탈로그를 읽을 수 없습니다: {error}")
        pokemon_ids = set()
    try:
        biome_data = load_biome_catalog(root)
        profiles = biome_data.get("profiles")
        sets = biome_data.get("sets")
        if biome_data.get("schema_version") != 1:
            _issue(issues, "error", biome_path, "$.schema_version", "지원 버전은 1입니다.")
        max_per_variant = biome_data.get("max_pokemon_per_habitat_variant")
        if not isinstance(max_per_variant, int) or not 1 <= max_per_variant <= 200:
            _issue(issues, "error", biome_path, "$.max_pokemon_per_habitat_variant", "번호 서식지당 포켓몬 수는 1~200이어야 합니다.")
        if not isinstance(profiles, list):
            _issue(issues, "error", biome_path, "$.profiles", "프로필 배열이 필요합니다.")
            profiles = []
        if not isinstance(sets, list):
            _issue(issues, "error", biome_path, "$.sets", "세트 배열이 필요합니다.")
            sets = []
        profile_ids: set[str] = set()
        weather_by_biome: dict[str, str] = {}
        for index, profile in enumerate(profiles):
            profile_id = profile.get("id") if isinstance(profile, dict) else None
            if not isinstance(profile_id, str) or not RESOURCE_ID.fullmatch(profile_id):
                _issue(issues, "error", biome_path, f"$.profiles[{index}].id", "올바른 프로필 ID가 필요합니다.")
            elif profile_id in profile_ids:
                _issue(issues, "error", biome_path, f"$.profiles[{index}].id", f"중복 프로필 ID: {profile_id}")
            else:
                profile_ids.add(profile_id)
            if isinstance(profile, dict) and profile.get("habitat") not in HABITAT_IDS:
                _issue(issues, "error", biome_path, f"$.profiles[{index}].habitat", "지원하지 않는 대표 서식지입니다.")
            if isinstance(profile, dict):
                profile_weather = profile.get("weather", "inherit")
                if profile_weather not in {"inherit", "clear", "rain", "thunder", "snow", "fog"}:
                    _issue(issues, "error", biome_path, f"$.profiles[{index}].weather", "지원하지 않는 바이옴 기본 날씨입니다.")
                elif profile_weather != "inherit":
                    for minecraft_biome in profile.get("minecraft_biomes", []):
                        previous_weather = weather_by_biome.setdefault(minecraft_biome, profile_weather)
                        if previous_weather != profile_weather:
                            _issue(issues, "error", biome_path, f"$.profiles[{index}].weather", f"같은 마인크래프트 바이옴의 기본 날씨가 충돌합니다: {minecraft_biome}")
                series = profile.get("settings", {}).get("series")
                if series is not None and series not in POKEDEX_SERIES_IDS:
                    _issue(issues, "error", biome_path, f"$.profiles[{index}].settings.series", "지원하는 지역도감 시리즈 ID여야 합니다.")
                habitat_variant = profile.get("settings", {}).get("habitat_variant", 0)
                if not isinstance(habitat_variant, int) or habitat_variant < 0:
                    _issue(issues, "error", biome_path, f"$.profiles[{index}].settings.habitat_variant", "서식지 번호는 0 이상의 정수여야 합니다.")
                rarities = profile.get("settings", {}).get("rarities")
                if not isinstance(rarities, list) or not rarities or any(value not in RARITY_IDS for value in rarities):
                    _issue(issues, "error", biome_path, f"$.profiles[{index}].settings.rarities", "하나 이상의 올바른 레어도가 필요합니다.")
                for key in ("forced_includes", "excluded_pokemon"):
                    values = profile.get(key, [])
                    if not isinstance(values, list):
                        _issue(issues, "error", biome_path, f"$.profiles[{index}].{key}", "배열이어야 합니다.")
                        continue
                    for item_index, pokemon_id in enumerate(values):
                        if pokemon_id not in pokemon_ids:
                            _issue(issues, "error", biome_path, f"$.profiles[{index}].{key}[{item_index}]", f"카탈로그에 없는 포켓몬입니다: {pokemon_id}")
        for index, biome_set in enumerate(sets):
            if not isinstance(biome_set, dict):
                _issue(issues, "error", biome_path, f"$.sets[{index}]", "세트는 객체여야 합니다.")
                continue
            _resource_id(biome_set.get("id"), issues, biome_path, f"$.sets[{index}].id")
            for item_index, item in enumerate(biome_set.get("profiles", [])):
                profile_id = item.get("profile") if isinstance(item, dict) else None
                if profile_id not in profile_ids:
                    _issue(issues, "error", biome_path, f"$.sets[{index}].profiles[{item_index}].profile", f"없는 바이옴 프로필입니다: {profile_id}")
            for key in ("unconditional_spawns",):
                for item_index, pokemon_id in enumerate(biome_set.get(key, [])):
                    if pokemon_id not in pokemon_ids:
                        _issue(issues, "error", biome_path, f"$.sets[{index}].{key}[{item_index}]", f"카탈로그에 없는 포켓몬입니다: {pokemon_id}")
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", biome_path, "$", f"카탈로그를 읽을 수 없습니다: {error}")
    return issues


def preview_biome(root: Path, request: dict[str, Any]) -> dict[str, Any]:
    catalog = load_biome_catalog(root)
    pokemon = load_pokemon_habitats(root).get("pokemon", [])
    return _preview_biome_data(catalog, pokemon, request)


def _preview_biome_data(
    catalog: dict[str, Any], pokemon: list[dict[str, Any]], request: dict[str, Any]
) -> dict[str, Any]:
    profiles = {entry["id"]: entry for entry in catalog.get("profiles", []) if isinstance(entry, dict) and isinstance(entry.get("id"), str)}
    selected: list[dict[str, Any]] = []
    unconditional = set(request.get("unconditional_spawns", []))
    if isinstance(request.get("set_id"), str):
        biome_set = next((entry for entry in catalog.get("sets", []) if entry.get("id") == request["set_id"]), None)
        if biome_set:
            selected = [profiles[item["profile"]] for item in biome_set.get("profiles", []) if item.get("profile") in profiles]
            unconditional.update(biome_set.get("unconditional_spawns", []))
    if isinstance(request.get("profile_id"), str) and request["profile_id"] in profiles:
        selected = [profiles[request["profile_id"]]]
    if isinstance(request.get("profile"), dict) and request["profile"].get("habitat") in HABITAT_IDS:
        selected = [request["profile"]]
    if not selected:
        raise ValueError("미리 볼 바이옴 프로필 또는 세트를 선택해야 합니다.")
    override = request.get("settings") if isinstance(request.get("settings"), dict) else {}
    results: dict[str, dict[str, Any]] = {}
    variant_details: list[dict[str, Any]] = []
    max_per_variant = max(1, int(catalog.get("max_pokemon_per_habitat_variant", 40)))
    for profile in selected:
        settings = {**profile.get("settings", {}), **override}
        habitat = profile.get("habitat")
        forced = set(profile.get("forced_includes", []))
        excluded = set(profile.get("excluded_pokemon", []))
        profile_results: dict[str, dict[str, Any]] = {}
        for entry in pokemon:
            pokemon_id = entry.get("id")
            if pokemon_id in excluded:
                continue
            prefs = entry.get("preferences", {})
            habitats = entry.get("habitats", {})
            habitat_match = habitats.get("primary") == habitat or (settings.get("include_secondary", True) and habitats.get("secondary") == habitat)
            matches = habitat_match
            matches = matches and not entry.get("is_legendary", False) and not entry.get("is_mythical", False)
            generation = settings.get("generation", 0)
            series = settings.get("series")
            if isinstance(series, str) and series:
                matches = matches and series in entry.get("series_appearances", [])
            else:
                matches = matches and (not generation or entry.get("generation") == generation)
            for field in ("temperature", "humidity", "weather", "time"):
                wanted = settings.get(field, "any")
                actual = prefs.get(field, "any")
                matches = matches and (wanted == "any" or actual in {wanted, "any"})
            matches = matches and prefs.get("rarity") in settings.get("rarities", list(RARITY_IDS))
            if matches or pokemon_id in forced:
                result = dict(entry)
                result["matched_profiles"] = [profile["id"]]
                result["match_reason"] = "profile_forced" if pokemon_id in forced and not matches else "rules"
                profile_results[pokemon_id] = result
        ordinary = sorted(
            (entry for entry in profile_results.values() if entry["match_reason"] == "rules"),
            key=lambda entry: entry.get("dex_number", 99999),
        )
        explicit = [entry for entry in profile_results.values() if entry["match_reason"] != "rules"]
        variant_count = max(1, (len(ordinary) + max_per_variant - 1) // max_per_variant)
        selected_variant = settings.get("habitat_variant", 0)
        selected_variant = selected_variant if isinstance(selected_variant, int) and selected_variant > 0 else 0
        if selected_variant:
            ordinary = [
                entry for index, entry in enumerate(ordinary)
                if (index * variant_count // len(ordinary)) + 1 == selected_variant
            ] if ordinary else []
        for result in [*ordinary, *explicit]:
            pokemon_id = result["id"]
            result["matched_profiles"] = sorted(
                set(results.get(pokemon_id, {}).get("matched_profiles", [])) | {profile["id"]}
            )
            results[pokemon_id] = result
        variant_details.append({
            "profile_id": profile["id"],
            "habitat": habitat,
            "selected": selected_variant,
            "count": variant_count,
            "max_pokemon": max_per_variant,
        })
    by_id = {entry.get("id"): entry for entry in pokemon}
    for pokemon_id in unconditional:
        if pokemon_id in by_id:
            result = dict(by_id[pokemon_id])
            result["matched_profiles"] = []
            result["match_reason"] = "unconditional"
            results[pokemon_id] = result
    ordered = sorted(results.values(), key=lambda entry: entry.get("dex_number", 99999))
    return {
        "count": len(ordered),
        "pokemon": ordered,
        "profiles": [entry["id"] for entry in selected],
        "habitat_variants": variant_details,
    }


def save_biome_catalog(root: Path, data: Any) -> list[Issue]:
    target = _catalog_path(root, "biome-profiles.json")
    if not isinstance(data, dict):
        return [Issue("error", target.as_posix(), "$", "바이옴 카탈로그는 객체여야 합니다.")]
    with tempfile.TemporaryDirectory(prefix="cobbleventure-biomes-") as directory:
        candidate_root = Path(directory)
        catalog_dir = candidate_root / "content" / "catalogs"
        catalog_dir.mkdir(parents=True)
        (catalog_dir / "biome-profiles.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        shutil.copy2(_catalog_path(root, "pokemon-habitats.json"), catalog_dir / "pokemon-habitats.json")
        issues = validate_biome_catalogs(candidate_root)
    if any(issue.level == "error" for issue in issues):
        return [Issue(issue.level, target.as_posix(), issue.path, issue.message) for issue in issues]
    temporary = target.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(target)
    return []


def list_world_generations(root: Path) -> list[int]:
    world_dir = root / "content" / "worlds"
    generations = []
    for path in world_dir.glob("generation_*.json") if world_dir.is_dir() else []:
        match = re.fullmatch(r"generation_([1-9])\.json", path.name)
        if match:
            generations.append(int(match.group(1)))
    return sorted(set(generations))


def load_world_layout(root: Path, generation: int = 1) -> dict[str, Any]:
    if not 1 <= generation <= 9:
        raise ValueError("세대는 1 이상 9 이하여야 합니다.")
    data = load_json(root / "content" / "worlds" / f"generation_{generation}.json")
    if not isinstance(data, dict):
        raise ValueError("세대 월드 지도는 객체여야 합니다.")
    return data


def world_pokemon_map(root: Path, generation: int = 1) -> dict[str, Any]:
    """Resolve the saved world layout into per-cell Pokemon spawn candidates."""
    world = load_world_layout(root, generation)
    biome_catalog = load_biome_catalog(root)
    pokemon = load_pokemon_habitats(root).get("pokemon", [])
    pokemon_by_id = {
        entry["id"]: entry
        for entry in pokemon
        if isinstance(entry, dict) and isinstance(entry.get("id"), str)
    }
    profiles = {
        entry["id"]: entry
        for entry in biome_catalog.get("profiles", [])
        if isinstance(entry, dict) and isinstance(entry.get("id"), str)
    }
    profiles_by_biome: dict[str, list[str]] = {}
    for profile_id, profile in profiles.items():
        for biome in profile.get("minecraft_biomes", []):
            if isinstance(biome, str):
                profiles_by_biome.setdefault(biome, []).append(profile_id)

    settlement_documents: dict[str, dict[str, Any]] = {}
    settlement_dir = root / "content" / "settlements"
    for path in settlement_dir.rglob("*.json") if settlement_dir.is_dir() else []:
        try:
            document = load_json(path)
            if isinstance(document, dict) and isinstance(document.get("id"), str):
                settlement_documents[document["id"]] = document
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            continue

    environment_by_cell = {
        (entry.get("q"), entry.get("r")): entry
        for entry in world.get("environment_overrides", [])
        if isinstance(entry, dict)
        and isinstance(entry.get("q"), int)
        and isinstance(entry.get("r"), int)
    }
    preview_cache: dict[str, dict[str, Any]] = {}
    auto_variant_cursor: dict[str, int] = {}

    def resolve(
        biome: str, settings: dict[str, Any] | None = None,
        unconditional: list[str] | None = None, preferred_profile: str | None = None,
    ) -> tuple[list[str], list[dict[str, Any]], dict[str, int]]:
        profile_ids = (
            [preferred_profile]
            if preferred_profile in profiles
            else profiles_by_biome.get(biome, [])
        )
        merged: dict[str, dict[str, Any]] = {}
        selected_variants: dict[str, int] = {}
        effective_settings = {
            "generation": 0,
            "series": POKEDEX_SERIES_BY_GENERATION[generation],
            **(settings or {}),
        }
        for profile_id in profile_ids:
            profile_settings = dict(effective_settings)
            if not isinstance(profile_settings.get("habitat_variant"), int) or profile_settings.get("habitat_variant", 0) <= 0:
                base_key = json.dumps(
                    [profile_id, {**profile_settings, "habitat_variant": 0}, sorted(unconditional or [])],
                    ensure_ascii=False, sort_keys=True,
                )
                if base_key not in preview_cache:
                    preview_cache[base_key] = _preview_biome_data(
                        biome_catalog,
                        pokemon,
                        {
                            "profile_id": profile_id,
                            "settings": {**profile_settings, "habitat_variant": 0},
                            "unconditional_spawns": unconditional or [],
                        },
                    )
                details = preview_cache[base_key].get("habitat_variants", [])
                variant_count = details[0].get("count", 1) if details else 1
                next_variant = auto_variant_cursor.get(profile_id, 0)
                profile_settings["habitat_variant"] = next_variant % max(1, variant_count) + 1
                auto_variant_cursor[profile_id] = next_variant + 1
            selected_variants[profile_id] = profile_settings["habitat_variant"]
            cache_key = json.dumps(
                [profile_id, profile_settings, sorted(unconditional or [])],
                ensure_ascii=False, sort_keys=True,
            )
            if cache_key not in preview_cache:
                preview_cache[cache_key] = _preview_biome_data(
                    biome_catalog,
                    pokemon,
                    {
                        "profile_id": profile_id,
                        "settings": profile_settings,
                        "unconditional_spawns": unconditional or [],
                    },
                )
            for entry in preview_cache[cache_key]["pokemon"]:
                merged[entry["id"]] = entry
        return (
            profile_ids,
            sorted(merged.values(), key=lambda entry: entry.get("dex_number", 99999)),
            selected_variants,
        )

    locations_by_cell: dict[tuple[int, int], dict[str, Any]] = {}
    for tile in world.get("tiles", []):
        if not isinstance(tile, dict):
            continue
        q, r, biome = tile.get("q"), tile.get("r"), tile.get("biome")
        if not isinstance(q, int) or not isinstance(r, int) or not isinstance(biome, str):
            continue
        environment = environment_by_cell.get((q, r), {})
        settings = {
            key: environment[key]
            for key in ("series", "habitat_variant", "temperature", "humidity", "weather", "time", "rarities", "include_secondary")
            if key in environment
        }
        profile_ids, candidates, habitat_variants = resolve(biome, settings)
        locations_by_cell[(q, r)] = {
            "q": q, "r": r, "kind": "biome", "biome": biome,
            "profile_ids": profile_ids,
            "habitat_variants": habitat_variants,
            "habitat_labels": [
                f"{profiles[profile_id].get('display_name', {}).get('ko_kr', profiles[profile_id].get('habitat', profile_id))}{habitat_variants.get(profile_id, 1)}"
                for profile_id in profile_ids
            ],
            "pokemon_ids": [entry["id"] for entry in candidates],
            "count": len(candidates), "unmapped_biome": not profile_ids,
        }

    for node in world.get("settlements", []):
        if not isinstance(node, dict) or not isinstance(node.get("anchor"), dict):
            continue
        anchor = node["anchor"]
        if not isinstance(anchor.get("q"), int) or not isinstance(anchor.get("r"), int):
            continue
        settlement_id = node.get("settlement", "")
        biome = node.get("town_biome", "minecraft:plains")
        document = settlement_documents.get(settlement_id, {})
        zone = next(
            (
                entry for entry in document.get("biome_layout", {}).get("zones", [])
                if isinstance(entry, dict) and entry.get("biome") == biome
            ),
            None,
        )
        if zone is None:
            zones = document.get("biome_layout", {}).get("zones", [])
            zone = zones[0] if zones and isinstance(zones[0], dict) else {}
        settings = zone.get("spawn_settings") if isinstance(zone.get("spawn_settings"), dict) else {}
        unconditional = document.get("content_profile", {}).get("pokemon", {}).get("unconditional_spawns", [])
        if not isinstance(unconditional, list):
            unconditional = []
        profile_ids, candidates, habitat_variants = resolve(
            biome, settings, unconditional, zone.get("habitat_profile"),
        )
        custom_cells = {
            (entry["q"], entry["r"])
            for entry in node.get("town_footprint_cells", [])
            if isinstance(entry, dict)
            and isinstance(entry.get("q"), int)
            and isinstance(entry.get("r"), int)
        }
        footprint = _town_footprint(
            (anchor["q"], anchor["r"]), node.get("town_radius_cells", 1),
            node.get("town_footprint_shape", "line_q"), custom_cells,
        )
        for q, r in footprint:
            locations_by_cell[(q, r)] = {
                "q": q, "r": r, "kind": "settlement", "biome": biome,
                "settlement": settlement_id,
                "profile_ids": profile_ids,
                "habitat_variants": habitat_variants,
                "habitat_labels": [
                    f"{profiles[profile_id].get('display_name', {}).get('ko_kr', profiles[profile_id].get('habitat', profile_id))}{habitat_variants.get(profile_id, 1)}"
                    for profile_id in profile_ids
                ],
                "pokemon_ids": [entry["id"] for entry in candidates],
                "count": len(candidates), "unmapped_biome": not profile_ids,
            }

    connections = [entry for entry in world.get("connections", []) if isinstance(entry, dict)]
    connections.sort(key=lambda entry: 0 if entry.get("surface_style") == "water" else 1)
    routed_cells: set[tuple[int, int]] = set()
    for connection in connections:
        settings = connection.get("pokemon_spawns")
        if not isinstance(settings, dict):
            settings = {"inherit_biome": True, "excluded_species": [], "additions": []}
        inherit_biome = settings.get("inherit_biome", True) is not False
        excluded = {
            species for species in settings.get("excluded_species", [])
            if isinstance(species, str)
        }
        additions = [
            addition for addition in settings.get("additions", [])
            if isinstance(addition, dict) and addition.get("species") in pokemon_by_id
        ]
        addition_levels = {
            addition["species"]: {
                "min_level": addition.get("min_level", 1),
                "max_level": addition.get("max_level", 100),
            }
            for addition in additions
        }
        for cell in connection.get("cells", []):
            if not isinstance(cell, dict) or not isinstance(cell.get("q"), int) or not isinstance(cell.get("r"), int):
                continue
            coordinate = (cell["q"], cell["r"])
            if coordinate in routed_cells:
                continue
            base = locations_by_cell.get(coordinate, {})
            # 마을 범위 안의 도로는 마을 서식지를 덮어쓰지 않는다. 웹 지도에서도
            # 마을 타일 위에는 길 오버레이를 표시하지 않는 것과 같은 우선순위다.
            if base.get("kind") == "settlement":
                continue
            routed_cells.add(coordinate)
            base_ids = list(base.get("pokemon_ids", []))
            selected_ids = [species for species in base_ids if inherit_biome and species not in excluded]
            for species in addition_levels:
                if species not in selected_ids:
                    selected_ids.append(species)
            locations_by_cell[coordinate] = {
                **base,
                "q": coordinate[0],
                "r": coordinate[1],
                "kind": "route",
                "route": connection.get("id", ""),
                "route_name": connection.get("display_name") or connection.get("id", ""),
                "biome": base.get("biome", ""),
                "profile_ids": base.get("profile_ids", []),
                "habitat_variants": base.get("habitat_variants", {}),
                "habitat_labels": base.get("habitat_labels", []),
                "base_pokemon_ids": base_ids,
                "pokemon_ids": selected_ids,
                "custom_level_ranges": addition_levels,
                "count": len(selected_ids),
                # 길만 놓인 빈 셀은 의도적인 도로 구간이므로 미매핑 바이옴으로
                # 집계하지 않는다. 기반 바이옴이 있으면 그 상태를 그대로 따른다.
                "unmapped_biome": base.get("unmapped_biome", False),
            }

    available_ids = {
        pokemon_id
        for location in locations_by_cell.values()
        for pokemon_id in location["pokemon_ids"]
    }
    available = [dict(entry) for entry in pokemon if entry.get("id") in available_ids]
    unavailable = []
    for entry in pokemon:
        if entry.get("id") in available_ids:
            continue
        result = dict(entry)
        result["unavailable_reason"] = (
            "other_generation"
            if entry.get("generation") != generation
            else "no_matching_world_location"
        )
        unavailable.append(result)
    available.sort(key=lambda entry: entry.get("dex_number", 99999))
    unavailable.sort(key=lambda entry: entry.get("dex_number", 99999))
    locations = sorted(locations_by_cell.values(), key=lambda entry: (entry["r"], entry["q"]))
    return {
        "generation": generation,
        "world_id": world.get("id", ""),
        "summary": {
            "locations": len(locations),
            "available": len(available),
            "unavailable": len(unavailable),
            "unmapped_locations": sum(entry["unmapped_biome"] for entry in locations),
        },
        "locations": locations,
        "available_pokemon": available,
        "unavailable_pokemon": unavailable,
    }


def save_world_layout(root: Path, data: Any, generation: int = 1) -> list[Issue]:
    if not 1 <= generation <= 9:
        raise ValueError("세대는 1 이상 9 이하여야 합니다.")
    target = root / "content" / "worlds" / f"generation_{generation}.json"
    if not isinstance(data, dict):
        return [Issue("error", target.as_posix(), "$", "세대 월드 지도는 객체여야 합니다.")]
    settlement_ids = {
        item["id"]
        for item in _list_documents(root, "settlements")
        if isinstance(item.get("id"), str) and item["id"]
    }
    cave_documents: dict[str, dict[str, Any]] = {}
    cave_dir = root / "content" / "caves"
    for cave_path in cave_dir.rglob("*.json") if cave_dir.is_dir() else []:
        try:
            cave = load_json(cave_path)
            if isinstance(cave, dict) and isinstance(cave.get("id"), str):
                cave_documents[cave["id"]] = cave
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            continue
    with tempfile.TemporaryDirectory(prefix="cobbleventure-world-layout-") as directory:
        candidate_root = Path(directory)
        world_dir = candidate_root / "content" / "worlds"
        catalog_dir = candidate_root / "content" / "catalogs"
        world_dir.mkdir(parents=True)
        catalog_dir.mkdir(parents=True)
        (world_dir / f"generation_{generation}.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        shutil.copy2(
            root / "content" / "catalogs" / "boundary-profiles.json",
            catalog_dir / "boundary-profiles.json",
        )
        pokemon_catalog = root / "content" / "catalogs" / "pokemon-habitats.json"
        if pokemon_catalog.is_file():
            shutil.copy2(pokemon_catalog, catalog_dir / "pokemon-habitats.json")
        candidate_issues = validate_hex_worlds(candidate_root, settlement_ids, cave_documents)
    issues = [
        Issue(issue.level, target.as_posix(), issue.path, issue.message)
        for issue in candidate_issues
    ]
    if any(issue.level == "error" for issue in issues):
        return issues
    target.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(
        prefix=f".generation_{generation}-", suffix=".json.tmp", dir=target.parent
    )
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as output:
            json.dump(data, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.replace(temporary_name, target)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    return issues


def load_editor_catalog(root: Path) -> dict[str, Any]:
    script = Path(__file__).with_name("export_editor_catalog.mjs")
    completed = subprocess.run(
        ["node", str(script), str(root.resolve())],
        cwd=root,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        timeout=60,
        check=False,
    )
    if completed.returncode != 0:
        message = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(message or "전투 데이터 카탈로그를 만들지 못했습니다.")
    try:
        catalog = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError("전투 데이터 카탈로그 JSON을 읽지 못했습니다.") from error
    if not isinstance(catalog, dict):
        raise RuntimeError("전투 데이터 카탈로그 형식이 올바르지 않습니다.")
    return catalog


def _issue(
    issues: list[Issue], level: str, path: Path, data_path: str, message: str
) -> None:
    issues.append(Issue(level, path.as_posix(), data_path, message))


def _require_object(
    value: Any,
    issues: list[Issue],
    file: Path,
    data_path: str,
) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        _issue(issues, "error", file, data_path, "객체여야 합니다.")
        return None
    return value


def _require_list(
    value: Any,
    issues: list[Issue],
    file: Path,
    data_path: str,
) -> list[Any] | None:
    if not isinstance(value, list):
        _issue(issues, "error", file, data_path, "배열이어야 합니다.")
        return None
    return value


def _require_string(
    value: Any, issues: list[Issue], file: Path, data_path: str
) -> str | None:
    if not isinstance(value, str) or not value.strip():
        _issue(issues, "error", file, data_path, "비어 있지 않은 문자열이어야 합니다.")
        return None
    return value


def _resource_id(
    value: Any, issues: list[Issue], file: Path, data_path: str
) -> str | None:
    if not isinstance(value, str) or not RESOURCE_ID.fullmatch(value):
        _issue(issues, "error", file, data_path, "namespace:path 형식의 리소스 ID가 필요합니다.")
        return None
    return value


def _localized_text(
    value: Any, issues: list[Issue], file: Path, data_path: str
) -> None:
    text = _require_object(value, issues, file, data_path)
    if text is None:
        return
    if not text:
        _issue(issues, "error", file, data_path, "언어별 문장이 하나 이상 필요합니다.")
    for language, sentence in text.items():
        if not isinstance(language, str) or not LANGUAGE_ID.fullmatch(language):
            _issue(issues, "error", file, f"{data_path}.{language}", "ko_kr 같은 언어 ID가 필요합니다.")
        if not isinstance(sentence, str) or not sentence.strip():
            _issue(issues, "error", file, f"{data_path}.{language}", "비어 있지 않은 문장이어야 합니다.")


def _validate_operation(
    value: Any,
    issues: list[Issue],
    file: Path,
    data_path: str,
    content_id: str | None,
    dialogue_targets: list[tuple[str, str]],
) -> None:
    operation = _require_object(value, issues, file, data_path)
    if operation is None:
        return
    operation_type = operation.get("type")
    if operation_type not in OPERATION_TYPES:
        _issue(issues, "error", file, f"{data_path}.type", "지원하지 않는 조건 또는 행동 타입입니다.")
        return
    if operation_type in {"next_dialogue", "open_dialogue"}:
        target = _resource_id(operation.get("target"), issues, file, f"{data_path}.target")
        if target:
            dialogue_targets.append((data_path, target))
    elif operation_type == "start_battle":
        if "battle" in operation:
            _resource_id(operation.get("battle"), issues, file, f"{data_path}.battle")
            results = operation.get("results", {})
            results = _require_object(results, issues, file, f"{data_path}.results")
            if results is not None:
                for result_name, target_value in results.items():
                    if result_name not in {"player_win", "player_loss", "cancelled"}:
                        _issue(issues, "error", file, f"{data_path}.results.{result_name}", "지원하지 않는 배틀 결과입니다.")
                        continue
                    target = _resource_id(target_value, issues, file, f"{data_path}.results.{result_name}")
                    if target:
                        dialogue_targets.append((f"{data_path}.results.{result_name}", target))
        else:
            trainer = _resource_id(operation.get("trainer"), issues, file, f"{data_path}.trainer")
            if trainer and trainer != content_id:
                _issue(issues, "error", file, f"{data_path}.trainer", "현재 콘텐츠의 트레이너 ID와 일치해야 합니다.")
    elif operation_type in {"flag_equals", "set_flag"}:
        _resource_id(operation.get("key"), issues, file, f"{data_path}.key")
        if "value" not in operation or not isinstance(operation.get("value"), (str, int, float, bool)):
            _issue(issues, "error", file, f"{data_path}.value", "문자열, 숫자 또는 boolean 값이 필요합니다.")
    elif operation_type == "mark_clear":
        _resource_id(operation.get("key"), issues, file, f"{data_path}.key")
    elif operation_type == "has_item":
        _resource_id(operation.get("item"), issues, file, f"{data_path}.item")
        count = operation.get("count", 1)
        if not isinstance(count, int) or isinstance(count, bool) or count < 1:
            _issue(issues, "error", file, f"{data_path}.count", "1 이상의 정수가 필요합니다.")
    elif operation_type == "give_item":
        _resource_id(operation.get("item"), issues, file, f"{data_path}.item")
        count = operation.get("count")
        if not isinstance(count, int) or isinstance(count, bool) or count < 1:
            _issue(issues, "error", file, f"{data_path}.count", "1 이상의 정수가 필요합니다.")
    elif operation_type == "grant_badge":
        _resource_id(operation.get("badge"), issues, file, f"{data_path}.badge")
    elif operation_type in {"give_money", "take_money"}:
        mode = operation.get("mode")
        if mode == "fixed":
            amount = operation.get("amount")
            if not isinstance(amount, int) or isinstance(amount, bool) or amount < 0:
                _issue(issues, "error", file, f"{data_path}.amount", "고정 금액은 0 이상의 정수여야 합니다.")
        elif mode == "level_cap_multiplier":
            multiplier = operation.get("multiplier")
            if not isinstance(multiplier, (int, float)) or isinstance(multiplier, bool) or multiplier <= 0:
                _issue(issues, "error", file, f"{data_path}.multiplier", "레벨캡 배율은 0보다 큰 숫자여야 합니다.")
        else:
            _issue(issues, "error", file, f"{data_path}.mode", "fixed 또는 level_cap_multiplier여야 합니다.")
    elif operation_type == "grant_loot":
        _resource_id(operation.get("loot_table"), issues, file, f"{data_path}.loot_table")
    elif operation_type == "grant_field_move":
        if operation.get("move") not in {
            "surf", "fly", "flash", "defog", "rock_climb", "whirlpool", "strength", "rock_smash",
        }:
            _issue(issues, "error", file, f"{data_path}.move", "지원하는 비전머신 ID가 필요합니다.")
    elif operation_type in {"start_quest", "complete_quest", "teleport"}:
        _resource_id(operation.get("target"), issues, file, f"{data_path}.target")
    elif operation_type == "teleport_to_gate":
        gate = operation.get("gate")
        if not isinstance(gate, str) or not CHOICE_ID.fullmatch(gate):
            _issue(issues, "error", file, f"{data_path}.gate", "이동할 월드맵 관문 ID가 필요합니다.")
        if operation.get("subject") not in {"player", "npc"}:
            _issue(issues, "error", file, f"{data_path}.subject", "이동 대상은 player 또는 npc여야 합니다.")
        if operation.get("side") not in {"front", "back", "center"}:
            _issue(issues, "error", file, f"{data_path}.side", "관문 이동 위치는 front/back/center 중 하나여야 합니다.")


def _validate_operation_list(
    value: Any,
    issues: list[Issue],
    file: Path,
    data_path: str,
    content_id: str | None,
    dialogue_targets: list[tuple[str, str]],
) -> list[Any] | None:
    operations = _require_list(value, issues, file, data_path)
    if operations is not None:
        for index, operation in enumerate(operations):
            _validate_operation(
                operation,
                issues,
                file,
                f"{data_path}[{index}]",
                content_id,
                dialogue_targets,
            )
    return operations


def _validate_block_position(
    value: Any, issues: list[Issue], file: Path, data_path: str
) -> dict[str, Any] | None:
    position = _require_object(value, issues, file, data_path)
    if position is None:
        return None
    for axis in ("x", "y", "z"):
        coordinate = position.get(axis)
        if not isinstance(coordinate, int) or isinstance(coordinate, bool):
            _issue(issues, "error", file, f"{data_path}.{axis}", "정수 좌표여야 합니다.")
    return position


def _validate_gym_condition(
    value: Any, issues: list[Issue], file: Path, data_path: str
) -> None:
    condition = _require_object(value, issues, file, data_path)
    if condition is None:
        return
    condition_type = condition.get("type")
    if condition_type == "variable":
        if condition.get("source") not in {"scoreboard", "persistent_data"}:
            _issue(issues, "error", file, f"{data_path}.source", "변수 출처는 scoreboard 또는 persistent_data여야 합니다.")
        key = condition.get("key")
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+", key):
            _issue(issues, "error", file, f"{data_path}.key", "올바른 변수 키가 필요합니다.")
        if condition.get("operator") not in {"==", "!=", ">", ">=", "<", "<="}:
            _issue(issues, "error", file, f"{data_path}.operator", "지원하지 않는 변수 비교 연산자입니다.")
        number = condition.get("value")
        if not isinstance(number, (int, float)) or isinstance(number, bool):
            _issue(issues, "error", file, f"{data_path}.value", "비교할 숫자 값이 필요합니다.")
    elif condition_type == "item":
        _resource_id(condition.get("item"), issues, file, f"{data_path}.item")
        count = condition.get("count")
        if not isinstance(count, int) or isinstance(count, bool) or count < 1:
            _issue(issues, "error", file, f"{data_path}.count", "아이템 수량은 1 이상 정수여야 합니다.")
    elif condition_type == "pokemon":
        _resource_id(condition.get("species"), issues, file, f"{data_path}.species")
    else:
        _issue(issues, "error", file, f"{data_path}.type", "체육관 문 조건 타입은 variable/item/pokemon 중 하나여야 합니다.")


def _validate_horizontal_bounds(
    value: Any, issues: list[Issue], file: Path, data_path: str
) -> dict[str, Any] | None:
    bounds = _require_object(value, issues, file, data_path)
    if bounds is None:
        return None
    for key in ("min_x", "min_z", "max_x", "max_z"):
        coordinate = bounds.get(key)
        if not isinstance(coordinate, int) or isinstance(coordinate, bool):
            _issue(issues, "error", file, f"{data_path}.{key}", "정수 좌표여야 합니다.")
    if all(
        isinstance(bounds.get(key), int) and not isinstance(bounds.get(key), bool)
        for key in ("min_x", "max_x")
    ):
        if bounds["min_x"] >= bounds["max_x"]:
            _issue(issues, "error", file, data_path, "min_x는 max_x보다 작아야 합니다.")
    if all(
        isinstance(bounds.get(key), int) and not isinstance(bounds.get(key), bool)
        for key in ("min_z", "max_z")
    ):
        if bounds["min_z"] >= bounds["max_z"]:
            _issue(issues, "error", file, data_path, "min_z는 max_z보다 작아야 합니다.")
    return bounds


def validate_settlement_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return None, issues

    root = _require_object(data, issues, path, "$")
    if root is None:
        return None, issues
    if root.get("schema_version") != 3:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 3입니다.")
    settlement_id = _resource_id(root.get("id"), issues, path, "$.id")
    if not isinstance(root.get("enabled"), bool):
        _issue(issues, "error", path, "$.enabled", "boolean이어야 합니다.")
    _localized_text(root.get("display_name"), issues, path, "$.display_name")
    _resource_id(root.get("region"), issues, path, "$.region")
    _resource_id(root.get("dimension"), issues, path, "$.dimension")
    town_radius = root.get("town_radius_cells")
    if not isinstance(town_radius, int) or isinstance(town_radius, bool) or town_radius not in (1, 3, 5, 7, 19):
        _issue(issues, "error", path, "$.town_radius_cells", "마을 크기는 1칸, 3칸, 5칸, 7칸, 19칸 중 하나여야 합니다.")
    town_shape = root.get("town_footprint_shape", "line_q")
    if town_shape not in {"triangle_up", "triangle_down", "line_q", "line_r", "line_s", "five_up", "five_down", "custom"}:
        _issue(issues, "error", path, "$.town_footprint_shape", "지원하지 않는 마을 배치 형태입니다.")
    elif town_radius == 3 and town_shape != "custom" and town_shape not in {"triangle_up", "triangle_down", "line_q", "line_r", "line_s"}:
        _issue(issues, "error", path, "$.town_footprint_shape", "3칸 마을은 삼각형 또는 일자 형태여야 합니다.")
    elif town_radius == 5 and town_shape != "custom" and town_shape not in {"five_up", "five_down"}:
        _issue(issues, "error", path, "$.town_footprint_shape", "5칸 마을은 위 확장 또는 아래 확장 형태여야 합니다.")
    if town_shape == "custom":
        _validate_custom_town_layout(root.get("town_footprint_cells"), root.get("town_road_exits"), town_radius, issues, path, "$")

    bounds = _validate_horizontal_bounds(root.get("bounds"), issues, path, "$.bounds")
    center = _validate_block_position(root.get("center"), issues, path, "$.center")
    if bounds is not None and center is not None:
        if all(
            isinstance(center.get(axis), int) and not isinstance(center.get(axis), bool)
            for axis in ("x", "z")
        ):
            if not bounds.get("min_x", 0) <= center["x"] <= bounds.get("max_x", 0):
                _issue(issues, "error", path, "$.center.x", "마을 경계 안에 있어야 합니다.")
            if not bounds.get("min_z", 0) <= center["z"] <= bounds.get("max_z", 0):
                _issue(issues, "error", path, "$.center.z", "마을 경계 안에 있어야 합니다.")

    anchors = _require_object(root.get("anchors"), issues, path, "$.anchors")
    if anchors is not None:
        for anchor_id, position in anchors.items():
            if not isinstance(anchor_id, str) or not CHOICE_ID.fullmatch(anchor_id):
                _issue(issues, "error", path, f"$.anchors.{anchor_id}", "올바른 앵커 ID가 아닙니다.")
            _validate_block_position(position, issues, path, f"$.anchors.{anchor_id}")

    content_profile = _require_object(
        root.get("content_profile"), issues, path, "$.content_profile"
    )
    if content_profile is not None:
        pokemon = _require_object(
            content_profile.get("pokemon"), issues, path, "$.content_profile.pokemon"
        )
        if pokemon is not None:
            _resource_id(
                pokemon.get("spawn_profile"), issues, path,
                "$.content_profile.pokemon.spawn_profile",
            )
            density = pokemon.get("density_multiplier")
            if (
                not isinstance(density, (int, float))
                or isinstance(density, bool)
                or not 0 < density <= 10
            ):
                _issue(
                    issues, "error", path,
                    "$.content_profile.pokemon.density_multiplier",
                    "0보다 크고 10 이하인 숫자여야 합니다.",
                )
            biome_set = pokemon.get("biome_set")
            if biome_set is not None:
                _resource_id(biome_set, issues, path, "$.content_profile.pokemon.biome_set")
            unconditional = pokemon.get("unconditional_spawns", [])
            if not isinstance(unconditional, list):
                _issue(issues, "error", path, "$.content_profile.pokemon.unconditional_spawns", "배열이어야 합니다.")
            else:
                for index, pokemon_id in enumerate(unconditional):
                    _resource_id(pokemon_id, issues, path, f"$.content_profile.pokemon.unconditional_spawns[{index}]")

        trainers = _require_object(
            content_profile.get("trainers"), issues, path, "$.content_profile.trainers"
        )
        if trainers is not None:
            _resource_id(
                trainers.get("population_profile"), issues, path,
                "$.content_profile.trainers.population_profile",
            )
            maximum_active = trainers.get("max_active")
            if (
                not isinstance(maximum_active, int)
                or isinstance(maximum_active, bool)
                or not 0 <= maximum_active <= 128
            ):
                _issue(
                    issues, "error", path, "$.content_profile.trainers.max_active",
                    "0 이상 128 이하의 정수여야 합니다.",
                )
            class_pool = _require_list(
                trainers.get("class_pool"), issues, path,
                "$.content_profile.trainers.class_pool",
            )
            if class_pool is not None:
                seen_classes: set[str] = set()
                for index, class_id in enumerate(class_pool):
                    class_path = f"$.content_profile.trainers.class_pool[{index}]"
                    parsed_class = _resource_id(class_id, issues, path, class_path)
                    if parsed_class in seen_classes:
                        _issue(issues, "error", path, class_path, f"중복 트레이너 클래스: {parsed_class}")
                    elif parsed_class is not None:
                        seen_classes.add(parsed_class)

        scaling = _require_object(
            content_profile.get("level_scaling"), issues, path,
            "$.content_profile.level_scaling",
        )
        if scaling is not None:
            if scaling.get("mode") not in {
                "fixed", "badges", "region_progress", "badge_and_region", "player_average"
            }:
                _issue(
                    issues, "error", path, "$.content_profile.level_scaling.mode",
                    "지원하지 않는 레벨 스케일링 방식입니다.",
                )
            for field in ("base_level", "min_level", "max_level"):
                value = scaling.get(field)
                if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= 100:
                    _issue(
                        issues, "error", path, f"$.content_profile.level_scaling.{field}",
                        "1 이상 100 이하의 정수여야 합니다.",
                    )
            minimum = scaling.get("min_level")
            base = scaling.get("base_level")
            maximum = scaling.get("max_level")
            if all(isinstance(value, int) and not isinstance(value, bool) for value in (minimum, base, maximum)):
                if not minimum <= base <= maximum:
                    _issue(
                        issues, "error", path, "$.content_profile.level_scaling",
                        "min_level <= base_level <= max_level 순서여야 합니다.",
                    )
            for field, limit in (("per_badge", 20), ("per_region", 30)):
                value = scaling.get(field)
                if (
                    not isinstance(value, (int, float))
                    or isinstance(value, bool)
                    or not 0 <= value <= limit
                ):
                    _issue(
                        issues, "error", path, f"$.content_profile.level_scaling.{field}",
                        f"0 이상 {limit} 이하의 숫자여야 합니다.",
                    )
            for field in ("pokemon_offset", "trainer_offset"):
                value = scaling.get(field)
                if not isinstance(value, int) or isinstance(value, bool) or not -50 <= value <= 50:
                    _issue(
                        issues, "error", path, f"$.content_profile.level_scaling.{field}",
                        "-50 이상 50 이하의 정수여야 합니다.",
                    )

    biome_layout = _require_object(
        root.get("biome_layout"), issues, path, "$.biome_layout"
    )
    if biome_layout is not None:
        if biome_layout.get("arrangement") not in {"organic_patches", "sectors", "concentric"}:
            _issue(issues, "error", path, "$.biome_layout.arrangement", "지원하지 않는 바이옴 배치 방식입니다.")
        transition_width = biome_layout.get("transition_width")
        if not isinstance(transition_width, int) or isinstance(transition_width, bool) or not 0 <= transition_width <= 64:
            _issue(issues, "error", path, "$.biome_layout.transition_width", "0 이상 64 이하의 정수여야 합니다.")
        biome_zones = _require_list(biome_layout.get("zones"), issues, path, "$.biome_layout.zones")
        seen_biome_zones: set[str] = set()
        if biome_zones is not None:
            if len(biome_zones) != 1:
                _issue(issues, "error", path, "$.biome_layout.zones", "마을 프리셋의 바이옴은 정확히 1개여야 합니다.")
            for index, zone_value in enumerate(biome_zones):
                zone_path = f"$.biome_layout.zones[{index}]"
                zone = _require_object(zone_value, issues, path, zone_path)
                if zone is None:
                    continue
                zone_id = zone.get("id")
                if not isinstance(zone_id, str) or not CHOICE_ID.fullmatch(zone_id):
                    _issue(issues, "error", path, f"{zone_path}.id", "올바른 바이옴 구역 ID가 아닙니다.")
                elif zone_id in seen_biome_zones:
                    _issue(issues, "error", path, f"{zone_path}.id", f"중복 바이옴 구역 ID: {zone_id}")
                else:
                    seen_biome_zones.add(zone_id)
                _resource_id(zone.get("biome"), issues, path, f"{zone_path}.biome")
                if zone.get("habitat_profile") is not None:
                    _resource_id(zone.get("habitat_profile"), issues, path, f"{zone_path}.habitat_profile")
                settings = zone.get("spawn_settings")
                if settings is not None:
                    settings = _require_object(settings, issues, path, f"{zone_path}.spawn_settings")
                    if settings is not None:
                        generation = settings.get("generation")
                        if not isinstance(generation, int) or isinstance(generation, bool) or not 0 <= generation <= 9:
                            _issue(issues, "error", path, f"{zone_path}.spawn_settings.generation", "0(전체) 이상 9 이하의 정수여야 합니다.")
                        series = settings.get("series")
                        if series is not None and series not in POKEDEX_SERIES_IDS:
                            _issue(issues, "error", path, f"{zone_path}.spawn_settings.series", "지원하는 지역도감 시리즈 ID여야 합니다.")
                        habitat_variant = settings.get("habitat_variant", 0)
                        if not isinstance(habitat_variant, int) or isinstance(habitat_variant, bool) or habitat_variant < 0:
                            _issue(issues, "error", path, f"{zone_path}.spawn_settings.habitat_variant", "0(자동) 이상의 정수여야 합니다.")
                        rarities = settings.get("rarities")
                        if not isinstance(rarities, list) or not rarities or any(value not in RARITY_IDS for value in rarities):
                            _issue(issues, "error", path, f"{zone_path}.spawn_settings.rarities", "하나 이상의 올바른 레어도가 필요합니다.")
                size = zone.get("size_blocks")
                if not isinstance(size, int) or isinstance(size, bool) or not 32 <= size <= 2048:
                    _issue(issues, "error", path, f"{zone_path}.size_blocks", "32 이상 2048 이하의 정수여야 합니다.")
                if zone.get("placement") not in {"center", "inner", "middle", "outer", "auto"}:
                    _issue(issues, "error", path, f"{zone_path}.placement", "지원하지 않는 바이옴 배치 위치입니다.")
                weight = zone.get("weight")
                if not isinstance(weight, int) or isinstance(weight, bool) or not 1 <= weight <= 100:
                    _issue(issues, "error", path, f"{zone_path}.weight", "1 이상 100 이하의 정수여야 합니다.")

        boundary = _require_object(
            biome_layout.get("boundary"), issues, path, "$.biome_layout.boundary"
        )
        if boundary is not None:
            _resource_id(boundary.get("profile"), issues, path, "$.biome_layout.boundary.profile")
            for field, minimum, maximum in (
                ("width", 1, 128), ("wall_height", 3, 128), ("wall_thickness", 1, 32)
            ):
                value = boundary.get(field)
                if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
                    _issue(
                        issues, "error", path, f"$.biome_layout.boundary.{field}",
                        f"{minimum} 이상 {maximum} 이하의 정수여야 합니다.",
                    )

    connections = _require_list(root.get("connections"), issues, path, "$.connections")
    seen_connections: set[str] = set()
    if connections is not None:
        if connections:
            _issue(issues, "error", path, "$.connections", "마을 간 길과 연결은 월드맵에서 설정해야 합니다.")
        for index, connection_value in enumerate(connections):
            connection_path = f"$.connections[{index}]"
            connection = _require_object(connection_value, issues, path, connection_path)
            if connection is None:
                continue
            connection_id = connection.get("id")
            if not isinstance(connection_id, str) or not CHOICE_ID.fullmatch(connection_id):
                _issue(issues, "error", path, f"{connection_path}.id", "올바른 연결 ID가 아닙니다.")
            elif connection_id in seen_connections:
                _issue(issues, "error", path, f"{connection_path}.id", f"중복 연결 ID: {connection_id}")
            else:
                seen_connections.add(connection_id)
            _resource_id(connection.get("target_settlement"), issues, path, f"{connection_path}.target_settlement")
            gate_placement = _require_object(
                connection.get("placement"), issues, path, f"{connection_path}.placement"
            )
            if gate_placement is not None:
                if gate_placement.get("mode") not in {"toward_target", "fixed_side"}:
                    _issue(issues, "error", path, f"{connection_path}.placement.mode", "지원하지 않는 관문 배치 방식입니다.")
                if gate_placement.get("preferred_side") not in {"north", "south", "east", "west"}:
                    _issue(issues, "error", path, f"{connection_path}.placement.preferred_side", "지원하지 않는 예비 방향입니다.")
                offset = gate_placement.get("offset")
                if not isinstance(offset, int) or isinstance(offset, bool) or not -1024 <= offset <= 1024:
                    _issue(issues, "error", path, f"{connection_path}.placement.offset", "-1024 이상 1024 이하의 정수여야 합니다.")
            for field in ("gate_width", "path_width"):
                value = connection.get(field)
                if not isinstance(value, int) or isinstance(value, bool) or not 3 <= value <= 31:
                    _issue(issues, "error", path, f"{connection_path}.{field}", "3 이상 31 이하의 정수여야 합니다.")
                elif value % 2 == 0:
                    _issue(issues, "error", path, f"{connection_path}.{field}", "중앙 정렬을 위해 홀수여야 합니다.")

    structure_profile = _require_object(
        root.get("structure_profile"), issues, path, "$.structure_profile"
    )
    if structure_profile is not None:
        _resource_id(
            structure_profile.get("structure"),
            issues,
            path,
            "$.structure_profile.structure",
        )
        gym_theme = structure_profile.get("gym_theme")
        if gym_theme not in {
            "normal", "fire", "water", "electric", "grass", "ice",
            "fighting", "poison", "ground", "flying", "psychic", "bug",
            "rock", "ghost", "dragon", "dark", "steel", "fairy",
        }:
            _issue(
                issues,
                "error",
                path,
                "$.structure_profile.gym_theme",
                "지원하는 체육관 타입 테마가 아닙니다.",
            )
        village_preset = structure_profile.get("village_preset")
        supported_villages = {
            "default_small", "default_mid", "default_large",
            "fighting_small", "fighting_mid", "fighting_large",
            "dark_small", "dark_mid",
            "ice_small", "ice_mid", "ice_large",
            "cobbleventure_starter",
        }
        if village_preset is not None and village_preset not in supported_villages:
            _issue(
                issues,
                "error",
                path,
                "$.structure_profile.village_preset",
                "지원하는 BCA 또는 Cobbleventure 전용 마을 프리셋이 아닙니다.",
            )
        commercial_center = structure_profile.get("commercial_center")
        if commercial_center not in {"none", "pokemart", "department_store", "preset"}:
            _issue(
                issues,
                "error",
                path,
                "$.structure_profile.commercial_center",
                "none, pokemart 또는 department_store 중 하나가 필요합니다.",
            )
        shop_configuration = structure_profile.get("shop_configuration")
        if shop_configuration is not None:
            shop_configuration = _require_object(
                shop_configuration, issues, path, "$.structure_profile.shop_configuration"
            )
            if shop_configuration is not None:
                _resource_id(
                    shop_configuration.get("catalog_id"), issues, path,
                    "$.structure_profile.shop_configuration.catalog_id",
                )
                vendor_units = _require_list(
                    shop_configuration.get("vendor_units"), issues, path,
                    "$.structure_profile.shop_configuration.vendor_units",
                )
                seen_vendor_units: set[str] = set()
                for index, vendor_unit in enumerate(vendor_units or []):
                    vendor_path = f"$.structure_profile.shop_configuration.vendor_units[{index}]"
                    vendor_id = _resource_id(vendor_unit, issues, path, vendor_path)
                    if vendor_id and vendor_id in seen_vendor_units:
                        _issue(issues, "error", path, vendor_path, f"중복 판매원 단위: {vendor_id}")
                    if vendor_id:
                        seen_vendor_units.add(vendor_id)
                assignments = _require_list(
                    shop_configuration.get("assignments"), issues, path,
                    "$.structure_profile.shop_configuration.assignments",
                )
                for index, assignment_value in enumerate(assignments or []):
                    assignment_path = f"$.structure_profile.shop_configuration.assignments[{index}]"
                    assignment = _require_object(assignment_value, issues, path, assignment_path)
                    if assignment is None:
                        continue
                    if not isinstance(assignment.get("slot_id"), str) or not CHOICE_ID.fullmatch(assignment["slot_id"]):
                        _issue(issues, "error", path, f"{assignment_path}.slot_id", "상점 위치 ID가 올바르지 않습니다.")
                    _resource_id(assignment.get("vendor_unit"), issues, path, f"{assignment_path}.vendor_unit")
                if commercial_center == "none" and vendor_units:
                    _issue(issues, "error", path, "$.structure_profile.shop_configuration.vendor_units", "상업 시설이 없으면 판매원을 지정할 수 없습니다.")
                if commercial_center == "pokemart" and len(vendor_units or []) > 1:
                    _issue(issues, "error", path, "$.structure_profile.shop_configuration.vendor_units", "프렌들리숍은 판매원 단위 하나만 지정할 수 있습니다.")
        starter_layout = structure_profile.get("starter_layout")
        starter_town = str(root.get("id", "")).endswith("/starter_town")
        if starter_town:
            if commercial_center != "none":
                _issue(
                    issues, "error", path, "$.structure_profile.commercial_center",
                    "시작 마을에서는 상업 중심 시설을 none으로 설정해야 합니다.",
                )
            if structure_profile.get("pokemon_center_enabled", False) is not False:
                _issue(
                    issues, "error", path,
                    "$.structure_profile.pokemon_center_enabled",
                    "시작 마을에는 포켓몬센터를 배치할 수 없습니다.",
                )
        if starter_layout is not None:
            starter_layout = _require_object(
                starter_layout, issues, path, "$.structure_profile.starter_layout"
            )
            if starter_layout is not None:
                _resource_id(
                    starter_layout.get("laboratory_structure"), issues, path,
                    "$.structure_profile.starter_layout.laboratory_structure",
                )
                depth = starter_layout.get("jigsaw_depth")
                if not isinstance(depth, int) or isinstance(depth, bool) or not 0 <= depth <= 4:
                    _issue(
                        issues, "error", path,
                        "$.structure_profile.starter_layout.jigsaw_depth",
                        "0 이상 4 이하의 정수여야 합니다.",
                    )
        if "house_style" in structure_profile:
            _resource_id(
                structure_profile.get("house_style"),
                issues,
                path,
                "$.structure_profile.house_style",
            )
        facilities = _require_object(
            structure_profile.get("required_facilities"),
            issues,
            path,
            "$.structure_profile.required_facilities",
        )
        if facilities is not None:
            if not facilities:
                _issue(
                    issues,
                    "error",
                    path,
                    "$.structure_profile.required_facilities",
                    "하나 이상의 필수 시설이 필요합니다.",
                )
            for facility_id, structure_id in facilities.items():
                facility_path = f"$.structure_profile.required_facilities.{facility_id}"
                if not isinstance(facility_id, str) or not CHOICE_ID.fullmatch(facility_id):
                    _issue(issues, "error", path, facility_path, "올바른 시설 ID가 아닙니다.")
                _resource_id(structure_id, issues, path, facility_path)

        layout_shape = structure_profile.get("layout_shape", "branching")
        if layout_shape not in {"branching", "linear", "radial", "loop", "terraced"}:
            _issue(
                issues, "error", path, "$.structure_profile.layout_shape",
                "지원하는 마을 형태가 아닙니다.",
            )
        road_profile = structure_profile.get("road_profile")
        if road_profile is not None:
            road_profile = _require_object(
                road_profile, issues, path, "$.structure_profile.road_profile"
            )
            if road_profile is not None:
                if road_profile.get("width") not in {3, 5, 7, 9}:
                    _issue(issues, "error", path, "$.structure_profile.road_profile.width", "도로 폭은 3, 5, 7 또는 9블록이어야 합니다.")
                if road_profile.get("material") not in {
                    "cobblestone", "stone_bricks", "bricks", "grass_path", "gravel",
                    "packed_mud", "sandstone", "snow",
                }:
                    _issue(issues, "error", path, "$.structure_profile.road_profile.material", "지원하는 도로 노면이 아닙니다.")
        generation_profile = structure_profile.get("generation_profile")
        if generation_profile is not None:
            generation_profile = _require_object(
                generation_profile, issues, path, "$.structure_profile.generation_profile"
            )
        if generation_profile is not None and "house_palette" in generation_profile:
            house_palette = _require_object(
                generation_profile.get("house_palette"), issues, path,
                "$.structure_profile.generation_profile.house_palette",
            )
            if house_palette is not None:
                for field, allowed in (
                    ("bases", {"one_story", "two_story", "five_story"}),
                    ("roofs", {"gable", "gambrel", "shed", "flat"}),
                    ("roof_colors", {"red", "orange", "yellow", "green", "blue", "purple", "brown", "gray", "black", "white"}),
                ):
                    values = house_palette.get(field)
                    field_path = f"$.structure_profile.generation_profile.house_palette.{field}"
                    if not isinstance(values, list) or not values:
                        _issue(issues, "error", path, field_path, "하나 이상 선택해야 합니다.")
                    elif len(values) != len(set(values)) or any(value not in allowed for value in values):
                        _issue(issues, "error", path, field_path, "지원하는 항목만 중복 없이 선택해야 합니다.")
        if generation_profile is not None and generation_profile.get("building_density", "normal") not in {
            "sparse", "normal", "dense", "packed"
        }:
            _issue(
                issues, "error", path,
                "$.structure_profile.generation_profile.building_density",
                "건물 밀집도는 sparse, normal, dense 또는 packed여야 합니다.",
            )
        facility_requirements = structure_profile.get("facility_requirements", [])
        required_facility_counts: dict[str, int] = {}
        if not isinstance(facility_requirements, list):
            _issue(
                issues, "error", path, "$.structure_profile.facility_requirements",
                "필수 시설 목록은 배열이어야 합니다.",
            )
        else:
            for index, requirement_value in enumerate(facility_requirements):
                requirement_path = f"$.structure_profile.facility_requirements[{index}]"
                requirement = _require_object(requirement_value, issues, path, requirement_path)
                if requirement is None:
                    continue
                facility_id = requirement.get("id")
                if not isinstance(facility_id, str) or not CHOICE_ID.fullmatch(facility_id):
                    _issue(issues, "error", path, f"{requirement_path}.id", "올바른 시설 ID가 아닙니다.")
                    continue
                if facility_id in required_facility_counts:
                    _issue(issues, "error", path, f"{requirement_path}.id", f"중복 필수 시설 항목: {facility_id}")
                label = requirement.get("label")
                if not isinstance(label, str) or not 1 <= len(label) <= 32:
                    _issue(issues, "error", path, f"{requirement_path}.label", "1자 이상 32자 이하의 표지판 이름이 필요합니다.")
                count = requirement.get("count")
                if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= 8:
                    _issue(issues, "error", path, f"{requirement_path}.count", "시설 수량은 1 이상 8 이하의 정수여야 합니다.")
                else:
                    required_facility_counts[facility_id] = count
                if requirement.get("required") is not True:
                    _issue(issues, "error", path, f"{requirement_path}.required", "체크한 시설은 반드시 필수 시설이어야 합니다.")
                footprint = _require_object(
                    requirement.get("footprint"), issues, path, f"{requirement_path}.footprint"
                )
                if footprint is not None:
                    for field, minimum, maximum in (("width", 8, 96), ("depth", 8, 96), ("height", 4, 48)):
                        value = footprint.get(field)
                        if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
                            _issue(issues, "error", path, f"{requirement_path}.footprint.{field}", f"{minimum} 이상 {maximum} 이하의 정수여야 합니다.")

        anchors = root.get("anchors") if isinstance(root.get("anchors"), dict) else {}
        district = _require_object(
            structure_profile.get("special_district"), issues, path,
            "$.structure_profile.special_district",
        )
        if district is not None:
            if not isinstance(district.get("enabled"), bool):
                _issue(issues, "error", path, "$.structure_profile.special_district.enabled", "참/거짓 값이어야 합니다.")
            if district.get("placement_mode", "manual") not in {"auto", "manual"}:
                _issue(issues, "error", path, "$.structure_profile.special_district.placement_mode", "자동 또는 직접 지정이어야 합니다.")
            district_anchor = district.get("anchor")
            if not isinstance(district_anchor, str) or district_anchor not in anchors:
                _issue(issues, "error", path, "$.structure_profile.special_district.anchor", "존재하는 특별 구역 앵커를 지정해야 합니다.")
            footprint = _require_object(
                district.get("footprint"), issues, path,
                "$.structure_profile.special_district.footprint",
            )
            if footprint is not None:
                for field in ("width", "depth"):
                    value = footprint.get(field)
                    if not isinstance(value, int) or isinstance(value, bool) or not 8 <= value <= 192:
                        _issue(issues, "error", path, f"$.structure_profile.special_district.footprint.{field}", "8 이상 192 이하의 정수여야 합니다.")
            clearance = district.get("clearance")
            if not isinstance(clearance, int) or isinstance(clearance, bool) or not 0 <= clearance <= 32:
                _issue(issues, "error", path, "$.structure_profile.special_district.clearance", "0 이상 32 이하의 정수여야 합니다.")
            building = _require_object(
                district.get("building"), issues, path,
                "$.structure_profile.special_district.building",
            )
            if building is not None:
                if not isinstance(building.get("enabled"), bool):
                    _issue(issues, "error", path, "$.structure_profile.special_district.building.enabled", "참/거짓 값이어야 합니다.")
                if building.get("enabled"):
                    _resource_id(building.get("structure"), issues, path, "$.structure_profile.special_district.building.structure")

        gym = _require_object(
            structure_profile.get("gym"), issues, path, "$.structure_profile.gym"
        )
        if gym is not None:
            gym_enabled = gym.get("enabled")
            if not isinstance(gym_enabled, bool):
                _issue(issues, "error", path, "$.structure_profile.gym.enabled", "참/거짓 값이어야 합니다.")
            gym_anchor = gym.get("anchor")
            if not isinstance(gym_anchor, str) or gym_anchor not in anchors:
                _issue(issues, "error", path, "$.structure_profile.gym.anchor", "존재하는 체육관 앵커를 지정해야 합니다.")
            if gym.get("theme") not in {
                "normal", "fire", "water", "electric", "grass", "ice",
                "fighting", "poison", "ground", "flying", "psychic", "bug",
                "rock", "ghost", "dragon", "dark", "steel", "fairy",
            }:
                _issue(issues, "error", path, "$.structure_profile.gym.theme", "지원하는 체육관 타입 테마가 아닙니다.")
            if gym_enabled:
                _resource_id(gym.get("gym_id"), issues, path, "$.structure_profile.gym.gym_id")
                _resource_id(gym.get("structure"), issues, path, "$.structure_profile.gym.structure")
            entrance = gym.get("entrance")
            if entrance is not None:
                entrance = _require_object(
                    entrance, issues, path, "$.structure_profile.gym.entrance"
                )
            if entrance is not None:
                for field in ("door_offset", "outside_offset"):
                    if field in entrance:
                        _validate_block_position(
                            entrance[field], issues, path,
                            f"$.structure_profile.gym.entrance.{field}",
                        )
                if entrance.get("facing", "north") not in {"north", "east", "south", "west"}:
                    _issue(issues, "error", path, "$.structure_profile.gym.entrance.facing", "방향은 north/east/south/west 중 하나여야 합니다.")
                if entrance.get("condition_mode", "all") not in {"all", "any"}:
                    _issue(issues, "error", path, "$.structure_profile.gym.entrance.condition_mode", "조건 조합은 all 또는 any여야 합니다.")
                conditions = entrance.get("conditions", [])
                if not isinstance(conditions, list):
                    _issue(issues, "error", path, "$.structure_profile.gym.entrance.conditions", "체육관 문 조건 배열이 필요합니다.")
                else:
                    for index, condition in enumerate(conditions):
                        _validate_gym_condition(
                            condition, issues, path,
                            f"$.structure_profile.gym.entrance.conditions[{index}]",
                        )
                for field in ("locked_dialogue", "enter_dialogue"):
                    if field not in entrance:
                        continue
                    dialogue = entrance[field]
                    if not isinstance(dialogue, list) or (
                        field == "locked_dialogue" and not dialogue
                    ) or any(not isinstance(line, str) or not line.strip() for line in dialogue):
                        _issue(issues, "error", path, f"$.structure_profile.gym.entrance.{field}", "비어 있지 않은 대사 문자열 배열이어야 합니다.")
            interior = gym.get("interior")
            if interior is not None:
                interior = _require_object(
                    interior, issues, path, "$.structure_profile.gym.interior"
                )
            if interior is not None:
                if "structure" in interior:
                    _resource_id(interior["structure"], issues, path, "$.structure_profile.gym.interior.structure")
                for field in ("entry_offset", "exit_door_offset", "leader_offset"):
                    if field in interior:
                        _validate_block_position(
                            interior[field], issues, path,
                            f"$.structure_profile.gym.interior.{field}",
                        )

        facility_placements = structure_profile.get("facility_placements", [])
        if not isinstance(facility_placements, list):
            _issue(
                issues, "error", path, "$.structure_profile.facility_placements",
                "배치 목록은 배열이어야 합니다.",
            )
        else:
            seen_placements: set[str] = set()
            placeholder_counts: dict[str, int] = {}
            for index, placement_value in enumerate(facility_placements):
                placement_path = f"$.structure_profile.facility_placements[{index}]"
                facility_placement = _require_object(
                    placement_value, issues, path, placement_path
                )
                if facility_placement is None:
                    continue
                placement_id = facility_placement.get("id")
                if not isinstance(placement_id, str) or not CHOICE_ID.fullmatch(placement_id):
                    _issue(issues, "error", path, f"{placement_path}.id", "올바른 시설 배치 ID가 아닙니다.")
                elif placement_id in seen_placements:
                    _issue(issues, "error", path, f"{placement_path}.id", f"중복 시설 배치 ID: {placement_id}")
                else:
                    seen_placements.add(placement_id)
                _resource_id(
                    facility_placement.get("structure"), issues, path,
                    f"{placement_path}.structure",
                )
                mode = facility_placement.get("mode")
                if mode not in {"instanced_entry", "direct_template", "placeholder"}:
                    _issue(issues, "error", path, f"{placement_path}.mode", "지원하지 않는 시설 배치 방식입니다.")
                    continue
                structure_id = facility_placement.get("structure")
                is_placeholder_template = mode == "placeholder" or (
                    isinstance(structure_id, str)
                    and structure_id.startswith("cobbleventure:placeholder/")
                )
                if is_placeholder_template:
                    facility_type = facility_placement.get("facility_type")
                    if not isinstance(facility_type, str) or not CHOICE_ID.fullmatch(facility_type):
                        _issue(issues, "error", path, f"{placement_path}.facility_type", "올바른 시설 종류가 필요합니다.")
                    else:
                        placeholder_counts[facility_type] = placeholder_counts.get(facility_type, 0) + 1
                    label = facility_placement.get("label")
                    if not isinstance(label, str) or not 1 <= len(label) <= 32:
                        _issue(issues, "error", path, f"{placement_path}.label", "1자 이상 32자 이하의 표지판 이름이 필요합니다.")
                    footprint = _require_object(
                        facility_placement.get("footprint"), issues, path,
                        f"{placement_path}.footprint",
                    )
                    if footprint is not None:
                        for field, minimum, maximum in (("width", 8, 96), ("depth", 8, 96), ("height", 4, 48)):
                            value = footprint.get(field)
                            if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
                                _issue(issues, "error", path, f"{placement_path}.footprint.{field}", f"{minimum} 이상 {maximum} 이하의 정수여야 합니다.")
                if mode in {"direct_template", "placeholder"}:
                    anchor = facility_placement.get("anchor")
                    if not isinstance(anchor, str) or anchor not in anchors:
                        _issue(issues, "error", path, f"{placement_path}.anchor", "존재하는 마을 앵커를 지정해야 합니다.")
                    continue
                for field in ("entry_anchor", "return_anchor"):
                    anchor = facility_placement.get(field)
                    if not isinstance(anchor, str) or anchor not in anchors:
                        _issue(issues, "error", path, f"{placement_path}.{field}", "존재하는 마을 앵커를 지정해야 합니다.")
                for field in (
                    "instance_origin", "instance_entry_offset", "instance_exit_offset"
                ):
                    _validate_block_position(
                        facility_placement.get(field), issues, path,
                        f"{placement_path}.{field}",
                    )
                radius = facility_placement.get("trigger_radius")
                if (
                    not isinstance(radius, (int, float)) or isinstance(radius, bool)
                    or not 0.5 <= radius <= 8
                ):
                    _issue(issues, "error", path, f"{placement_path}.trigger_radius", "0.5 이상 8 이하의 숫자여야 합니다.")
            for facility_id, requested_count in required_facility_counts.items():
                placed_count = placeholder_counts.get(facility_id, 0)
                if placed_count != requested_count:
                    _issue(
                        issues, "error", path, "$.structure_profile.facility_placements",
                        f"체크한 시설 {facility_id}은(는) {requested_count}개가 필요하지만 플레이스홀더는 {placed_count}개입니다.",
                    )

    placement = _require_object(
        root.get("npc_placement"), issues, path, "$.npc_placement"
    )
    if placement is not None:
        maximum = placement.get("max_ambient_npcs")
        if not isinstance(maximum, int) or isinstance(maximum, bool) or maximum < 0:
            _issue(issues, "error", path, "$.npc_placement.max_ambient_npcs", "0 이상의 정수여야 합니다.")
        wander_radius = placement.get("default_wander_radius")
        if (
            not isinstance(wander_radius, (int, float))
            or isinstance(wander_radius, bool)
            or wander_radius < 0
        ):
            _issue(issues, "error", path, "$.npc_placement.default_wander_radius", "0 이상의 숫자여야 합니다.")

        slots = _require_list(
            placement.get("trainer_slots"), issues, path, "$.npc_placement.trainer_slots"
        )
        seen_slots: set[str] = set()
        if slots is not None:
            for index, slot_value in enumerate(slots):
                slot_path = f"$.npc_placement.trainer_slots[{index}]"
                slot = _require_object(slot_value, issues, path, slot_path)
                if slot is None:
                    continue
                slot_id = slot.get("id")
                if not isinstance(slot_id, str) or not CHOICE_ID.fullmatch(slot_id):
                    _issue(issues, "error", path, f"{slot_path}.id", "올바른 슬롯 ID가 아닙니다.")
                elif slot_id in seen_slots:
                    _issue(issues, "error", path, f"{slot_path}.id", f"중복 슬롯 ID: {slot_id}")
                else:
                    seen_slots.add(slot_id)
                _resource_id(slot.get("trainer_id"), issues, path, f"{slot_path}.trainer_id")
                battle_type = slot.get("battle_type")
                if battle_type not in {"singles", "doubles"}:
                    _issue(issues, "error", path, f"{slot_path}.battle_type", "singles 또는 doubles여야 합니다.")
                members = _require_list(slot.get("members"), issues, path, f"{slot_path}.members")
                expected_members = 2 if battle_type == "doubles" else 1
                if members is not None:
                    if len(members) != expected_members:
                        label = "듀얼배틀" if battle_type == "doubles" else "싱글배틀"
                        _issue(
                            issues,
                            "error",
                            path,
                            f"{slot_path}.members",
                            f"{label}의 EasyNPC 멤버는 정확히 {expected_members}명이어야 합니다.",
                        )
                    seen_members: set[str] = set()
                    for member_index, member_value in enumerate(members):
                        member_path = f"{slot_path}.members[{member_index}]"
                        member = _require_object(member_value, issues, path, member_path)
                        if member is None:
                            continue
                        member_id = member.get("id")
                        if not isinstance(member_id, str) or not CHOICE_ID.fullmatch(member_id):
                            _issue(issues, "error", path, f"{member_path}.id", "올바른 멤버 ID가 아닙니다.")
                        elif member_id in seen_members:
                            _issue(issues, "error", path, f"{member_path}.id", f"중복 멤버 ID: {member_id}")
                        else:
                            seen_members.add(member_id)
                        _resource_id(member.get("npc_profile"), issues, path, f"{member_path}.npc_profile")
                        _validate_block_position(member.get("position"), issues, path, f"{member_path}.position")
                        rotation = member.get("rotation")
                        if not isinstance(rotation, (int, float)) or isinstance(rotation, bool):
                            _issue(issues, "error", path, f"{member_path}.rotation", "숫자여야 합니다.")
                        elif not -360 <= rotation <= 360:
                            _issue(issues, "error", path, f"{member_path}.rotation", "-360 이상 360 이하여야 합니다.")
                if slot.get("spawn_policy") not in {"persistent", "on_region_load", "manual"}:
                    _issue(issues, "error", path, f"{slot_path}.spawn_policy", "지원하지 않는 생성 정책입니다.")
                tags = _require_list(slot.get("tags"), issues, path, f"{slot_path}.tags")
                if tags is not None:
                    for tag_index, tag in enumerate(tags):
                        if not isinstance(tag, str) or not CHOICE_ID.fullmatch(tag):
                            _issue(issues, "error", path, f"{slot_path}.tags[{tag_index}]", "올바른 태그가 아닙니다.")

        zones = _require_list(
            placement.get("zones"), issues, path, "$.npc_placement.zones"
        )
        seen_zones: set[str] = set()
        if zones is not None:
            for index, zone_value in enumerate(zones):
                zone_path = f"$.npc_placement.zones[{index}]"
                zone = _require_object(zone_value, issues, path, zone_path)
                if zone is None:
                    continue
                zone_id = zone.get("id")
                if not isinstance(zone_id, str) or not CHOICE_ID.fullmatch(zone_id):
                    _issue(issues, "error", path, f"{zone_path}.id", "올바른 구역 ID가 아닙니다.")
                elif zone_id in seen_zones:
                    _issue(issues, "error", path, f"{zone_path}.id", f"중복 구역 ID: {zone_id}")
                else:
                    seen_zones.add(zone_id)
                _validate_horizontal_bounds(zone.get("bounds"), issues, path, f"{zone_path}.bounds")
                maximum_npcs = zone.get("max_npcs")
                if not isinstance(maximum_npcs, int) or isinstance(maximum_npcs, bool) or maximum_npcs < 1:
                    _issue(issues, "error", path, f"{zone_path}.max_npcs", "1 이상의 정수여야 합니다.")
    return settlement_id, issues


def validate_trainer_class_catalog(path: Path) -> list[Issue]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return issues
    root = _require_object(data, issues, path, "$")
    if root is None:
        return issues
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")
    classes = _require_list(root.get("classes"), issues, path, "$.classes")
    seen_ids: set[str] = set()
    if classes is None:
        return issues
    if not classes:
        _issue(issues, "error", path, "$.classes", "트레이너 클래스가 하나 이상 필요합니다.")
    for index, value in enumerate(classes):
        class_path = f"$.classes[{index}]"
        trainer_class = _require_object(value, issues, path, class_path)
        if trainer_class is None:
            continue
        class_id = _resource_id(trainer_class.get("id"), issues, path, f"{class_path}.id")
        if class_id:
            if class_id in seen_ids:
                _issue(issues, "error", path, f"{class_path}.id", f"중복 클래스 ID: {class_id}")
            seen_ids.add(class_id)
        if trainer_class.get("category") not in {
            "children", "outdoor", "specialist", "occupation",
            "social", "advanced", "boss", "custom",
        }:
            _issue(issues, "error", path, f"{class_path}.category", "지원하지 않는 클래스 분류입니다.")
        _localized_text(trainer_class.get("display_name"), issues, path, f"{class_path}.display_name")
        _localized_text(trainer_class.get("title_pattern"), issues, path, f"{class_path}.title_pattern")
        body = _require_object(trainer_class.get("body"), issues, path, f"{class_path}.body")
        if body is not None:
            if body.get("age_group") not in {"child", "teen", "adult"}:
                _issue(issues, "error", path, f"{class_path}.body.age_group", "child, teen, adult 중 하나여야 합니다.")
            height_scale = body.get("height_scale")
            if (
                not isinstance(height_scale, (int, float))
                or isinstance(height_scale, bool)
                or not 0.5 <= height_scale <= 1.25
            ):
                _issue(issues, "error", path, f"{class_path}.body.height_scale", "0.5 이상 1.25 이하 숫자여야 합니다.")
            if body.get("arm_model") not in {"classic", "slim"}:
                _issue(issues, "error", path, f"{class_path}.body.arm_model", "classic 또는 slim이어야 합니다.")
        appearance = _require_object(
            trainer_class.get("default_appearance"),
            issues,
            path,
            f"{class_path}.default_appearance",
        )
        if appearance is not None:
            if appearance.get("source") not in {"custom", "rct_single", "rct_group"}:
                _issue(issues, "error", path, f"{class_path}.default_appearance.source", "지원하지 않는 외형 출처입니다.")
            if appearance.get("type") not in {"skin", "model"}:
                _issue(issues, "error", path, f"{class_path}.default_appearance.type", "skin 또는 model이어야 합니다.")
            _resource_id(appearance.get("resource"), issues, path, f"{class_path}.default_appearance.resource")
            if appearance.get("implementation_status") not in {"ready", "placeholder"}:
                _issue(issues, "error", path, f"{class_path}.default_appearance.implementation_status", "ready 또는 placeholder여야 합니다.")
        appearance_options = trainer_class.get("appearance_options", [])
        if not isinstance(appearance_options, list):
            _issue(issues, "error", path, f"{class_path}.appearance_options", "배열이어야 합니다.")
        else:
            seen_sources: set[str] = set()
            for option_index, option_value in enumerate(appearance_options):
                option_path = f"{class_path}.appearance_options[{option_index}]"
                option = _require_object(option_value, issues, path, option_path)
                if option is None:
                    continue
                source = option.get("source")
                if source not in {"custom", "rct_single", "rct_group"}:
                    _issue(issues, "error", path, f"{option_path}.source", "지원하지 않는 외형 출처입니다.")
                elif source in seen_sources:
                    _issue(issues, "error", path, f"{option_path}.source", f"중복 외형 출처: {source}")
                else:
                    seen_sources.add(source)
                if option.get("type") not in {"skin", "model"}:
                    _issue(issues, "error", path, f"{option_path}.type", "skin 또는 model이어야 합니다.")
                _resource_id(option.get("resource"), issues, path, f"{option_path}.resource")
                if option.get("implementation_status") not in {"ready", "placeholder"}:
                    _issue(issues, "error", path, f"{option_path}.implementation_status", "ready 또는 placeholder여야 합니다.")
        tags = _require_list(trainer_class.get("tags"), issues, path, f"{class_path}.tags")
        if tags is not None:
            for tag_index, tag in enumerate(tags):
                if not isinstance(tag, str) or not CHOICE_ID.fullmatch(tag):
                    _issue(issues, "error", path, f"{class_path}.tags[{tag_index}]", "올바른 태그가 아닙니다.")
    return issues


def validate_trainer_outfit_catalog(path: Path, trainer_class_ids: set[str] | None = None) -> list[Issue]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return issues
    root = _require_object(data, issues, path, "$")
    if root is None:
        return issues
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")
    outfits = _require_list(root.get("outfits"), issues, path, "$.outfits")
    seen_ids: set[str] = set()
    if outfits is None:
        return issues
    for index, value in enumerate(outfits):
        outfit_path = f"$.outfits[{index}]"
        outfit = _require_object(value, issues, path, outfit_path)
        if outfit is None:
            continue
        outfit_id = _resource_id(outfit.get("id"), issues, path, f"{outfit_path}.id")
        if outfit_id in seen_ids:
            _issue(issues, "error", path, f"{outfit_path}.id", f"중복 의상 ID: {outfit_id}")
        elif outfit_id:
            seen_ids.add(outfit_id)
        trainer_class = _resource_id(
            outfit.get("trainer_class"), issues, path, f"{outfit_path}.trainer_class"
        )
        if trainer_class_ids is not None and trainer_class and trainer_class not in trainer_class_ids:
            _issue(issues, "error", path, f"{outfit_path}.trainer_class", f"존재하지 않는 트레이너 클래스: {trainer_class}")
        _localized_text(outfit.get("display_name"), issues, path, f"{outfit_path}.display_name")
        _resource_id(outfit.get("base_skin"), issues, path, f"{outfit_path}.base_skin")
        _resource_id(outfit.get("fallback_skin"), issues, path, f"{outfit_path}.fallback_skin")
        if outfit.get("arm_model") not in {"classic", "slim"}:
            _issue(issues, "error", path, f"{outfit_path}.arm_model", "classic 또는 slim이어야 합니다.")
        equipment = _require_object(outfit.get("equipment"), issues, path, f"{outfit_path}.equipment")
        if equipment is not None:
            for slot, item_value in equipment.items():
                slot_path = f"{outfit_path}.equipment.{slot}"
                if slot not in {"head", "chest", "legs", "feet", "mainhand", "offhand"}:
                    _issue(issues, "error", path, slot_path, "지원하지 않는 장비 슬롯입니다.")
                    continue
                item = _require_object(item_value, issues, path, slot_path)
                if item is None:
                    continue
                _resource_id(item.get("item"), issues, path, f"{slot_path}.item")
                chance = item.get("drop_chance")
                if not isinstance(chance, (int, float)) or isinstance(chance, bool) or not 0 <= chance <= 1:
                    _issue(issues, "error", path, f"{slot_path}.drop_chance", "0 이상 1 이하 숫자여야 합니다.")
        adapters = _require_object(outfit.get("adapters"), issues, path, f"{outfit_path}.adapters")
        easy_npc = _require_object(
            adapters.get("easy_npc") if adapters else None,
            issues,
            path,
            f"{outfit_path}.adapters.easy_npc",
        )
        if easy_npc is not None:
            _resource_id(easy_npc.get("entity_type"), issues, path, f"{outfit_path}.adapters.easy_npc.entity_type")
            _resource_id(easy_npc.get("preset"), issues, path, f"{outfit_path}.adapters.easy_npc.preset")
            try:
                uuid.UUID(str(easy_npc.get("custom_skin_uuid")))
            except (ValueError, AttributeError):
                _issue(issues, "error", path, f"{outfit_path}.adapters.easy_npc.custom_skin_uuid", "올바른 UUID가 아닙니다.")
            scale = easy_npc.get("root_scale")
            if not isinstance(scale, (int, float)) or isinstance(scale, bool) or not 0.5 <= scale <= 1.25:
                _issue(issues, "error", path, f"{outfit_path}.adapters.easy_npc.root_scale", "0.5 이상 1.25 이하 숫자여야 합니다.")
    return issues


def _validate_roster_appearance(value: Any, issues: list[Issue], path: Path, value_path: str) -> None:
    appearance = _require_object(value, issues, path, value_path)
    if appearance is None:
        return
    _resource_id(appearance.get("resource"), issues, path, f"{value_path}.resource")
    if "candidate_resource" in appearance:
        _resource_id(appearance.get("candidate_resource"), issues, path, f"{value_path}.candidate_resource")
    status = appearance.get("asset_status")
    implementation = appearance.get("implementation_status")
    if status not in {"verified", "definition_only", "missing"}:
        _issue(issues, "error", path, f"{value_path}.asset_status", "지원하지 않는 자산 상태입니다.")
    if implementation not in {"ready", "placeholder"}:
        _issue(issues, "error", path, f"{value_path}.implementation_status", "ready 또는 placeholder여야 합니다.")
    visual_match = appearance.get("visual_match_status")
    if visual_match is not None and visual_match not in {"matched", "generic", "unverified"}:
        _issue(issues, "error", path, f"{value_path}.visual_match_status", "지원하지 않는 외형 일치 상태입니다.")
    if status == "verified" and implementation != "ready":
        _issue(issues, "error", path, value_path, "검증된 자산은 ready 상태여야 합니다.")
    if status != "verified" and implementation != "placeholder":
        _issue(issues, "error", path, value_path, "미검증 자산은 placeholder 상태여야 합니다.")
    if appearance.get("distribution") not in {"dependency_reference", "original_required", "third_party_attributed"}:
        _issue(issues, "error", path, f"{value_path}.distribution", "지원하지 않는 배포 정책입니다.")


def validate_trainer_roster_catalog(path: Path) -> tuple[set[str], list[Issue]]:
    issues: list[Issue] = []
    character_ids: set[str] = set()
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return character_ids, issues
    root = _require_object(data, issues, path, "$")
    if root is None:
        return character_ids, issues
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")
    organizations = _require_list(root.get("organizations"), issues, path, "$.organizations") or []
    league = _require_list(root.get("league_characters"), issues, path, "$.league_characters") or []
    seen_organizations: set[str] = set()

    def validate_character(value: Any, value_path: str, allowed_roles: set[str]) -> None:
        character = _require_object(value, issues, path, value_path)
        if character is None:
            return
        character_id = _resource_id(character.get("id"), issues, path, f"{value_path}.id")
        if character_id in character_ids:
            _issue(issues, "error", path, f"{value_path}.id", f"중복 캐릭터 ID: {character_id}")
        elif character_id:
            character_ids.add(character_id)
        _localized_text(character.get("display_name"), issues, path, f"{value_path}.display_name")
        if character.get("role") not in allowed_roles:
            _issue(issues, "error", path, f"{value_path}.role", "지원하지 않는 캐릭터 역할입니다.")
        if character.get("gender") not in {"male", "female", "nonbinary", "unspecified"}:
            _issue(issues, "error", path, f"{value_path}.gender", "지원하지 않는 성별 값입니다.")
        generation = character.get("generation")
        if not isinstance(generation, int) or isinstance(generation, bool) or not 1 <= generation <= 9:
            _issue(issues, "error", path, f"{value_path}.generation", "세대는 1 이상 9 이하 정수여야 합니다.")
        body = character.get("body")
        if body is not None:
            body = _require_object(body, issues, path, f"{value_path}.body")
            if body is not None:
                if body.get("age_group") not in {"child", "teen", "adult"}:
                    _issue(issues, "error", path, f"{value_path}.body.age_group", "지원하지 않는 연령대입니다.")
                height_scale = body.get("height_scale")
                if not isinstance(height_scale, (int, float)) or isinstance(height_scale, bool) or not 0.5 <= height_scale <= 1.25:
                    _issue(issues, "error", path, f"{value_path}.body.height_scale", "0.5 이상 1.25 이하 숫자여야 합니다.")
                if body.get("arm_model") not in {"classic", "slim"}:
                    _issue(issues, "error", path, f"{value_path}.body.arm_model", "classic 또는 slim이어야 합니다.")
        _validate_roster_appearance(character.get("appearance"), issues, path, f"{value_path}.appearance")

    for org_index, value in enumerate(organizations):
        org_path = f"$.organizations[{org_index}]"
        organization = _require_object(value, issues, path, org_path)
        if organization is None:
            continue
        org_id = _resource_id(organization.get("id"), issues, path, f"{org_path}.id")
        if org_id in seen_organizations:
            _issue(issues, "error", path, f"{org_path}.id", f"중복 조직 ID: {org_id}")
        elif org_id:
            seen_organizations.add(org_id)
        _localized_text(organization.get("display_name"), issues, path, f"{org_path}.display_name")
        grunts = _require_list(organization.get("grunt_variants"), issues, path, f"{org_path}.grunt_variants") or []
        genders = {grunt.get("gender") for grunt in grunts if isinstance(grunt, dict)}
        if not {"male", "female"}.issubset(genders):
            _issue(issues, "error", path, f"{org_path}.grunt_variants", "남성·여성 조무래기 항목이 모두 필요합니다.")
        for index, grunt in enumerate(grunts):
            validate_character(grunt, f"{org_path}.grunt_variants[{index}]", {"grunt"})
        named = _require_list(organization.get("named_characters"), issues, path, f"{org_path}.named_characters") or []
        for index, character in enumerate(named):
            validate_character(character, f"{org_path}.named_characters[{index}]", {"admin", "boss", "named_agent"})
    for index, character in enumerate(league):
        validate_character(character, f"$.league_characters[{index}]", {"gym_leader", "elite_four", "champion"})
    defaults = _require_list(
        root.get("battle_reference_defaults", []),
        issues,
        path,
        "$.battle_reference_defaults",
    ) or []
    seen_default_characters: set[str] = set()
    for index, value in enumerate(defaults):
        default_path = f"$.battle_reference_defaults[{index}]"
        default = _require_object(value, issues, path, default_path)
        if default is None:
            continue
        character_id = _resource_id(
            default.get("character"), issues, path, f"{default_path}.character"
        )
        if character_id and character_id not in character_ids:
            _issue(
                issues,
                "error",
                path,
                f"{default_path}.character",
                f"존재하지 않는 명단 캐릭터: {character_id}",
            )
        if character_id in seen_default_characters:
            _issue(
                issues,
                "error",
                path,
                f"{default_path}.character",
                f"중복 기본 엔트리 캐릭터: {character_id}",
            )
        elif character_id:
            seen_default_characters.add(character_id)
        entry_id = default.get("entry")
        if not isinstance(entry_id, str) or re.fullmatch(r"[a-z0-9_-]+", entry_id) is None:
            _issue(
                issues,
                "error",
                path,
                f"{default_path}.entry",
                "참고 엔트리 ID 형식이 올바르지 않습니다.",
            )
    return character_ids, issues


def validate_dependency_lock(path: Path, strict_pack: bool) -> list[Issue]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return issues

    root = _require_object(data, issues, path, "$")
    if root is None:
        return issues

    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")

    status = root.get("status")
    if status not in VALID_LOCK_STATUSES:
        _issue(issues, "error", path, "$.status", "draft 또는 locked여야 합니다.")

    profile = root.get("profile")
    if not isinstance(profile, str) or not profile.strip():
        _issue(issues, "error", path, "$.profile", "비어 있지 않은 문자열이어야 합니다.")

    minecraft = _require_object(root.get("minecraft"), issues, path, "$.minecraft")
    if minecraft is not None:
        loader = _require_object(
            minecraft.get("loader"), issues, path, "$.minecraft.loader"
        )
        if loader is not None and loader.get("type") != "neoforge":
            _issue(
                issues,
                "error",
                path,
                "$.minecraft.loader.type",
                "현재 기준 로더는 neoforge입니다.",
            )
        must_be_locked = strict_pack or status == "locked"
        if must_be_locked and not minecraft.get("version"):
            _issue(
                issues,
                "error",
                path,
                "$.minecraft.version",
                "패키징 전 Minecraft 버전을 고정해야 합니다.",
            )
        if must_be_locked and (loader is None or not loader.get("version")):
            _issue(
                issues,
                "error",
                path,
                "$.minecraft.loader.version",
                "패키징 전 NeoForge 버전을 고정해야 합니다.",
            )

    mods = _require_list(root.get("mods"), issues, path, "$.mods")
    seen_ids: set[str] = set()
    seen_cf_files: set[tuple[int, int]] = set()
    if mods is not None:
        for index, value in enumerate(mods):
            item_path = f"$.mods[{index}]"
            mod = _require_object(value, issues, path, item_path)
            if mod is None:
                continue
            mod_id = mod.get("id")
            if not isinstance(mod_id, str) or not MOD_ID.fullmatch(mod_id):
                _issue(issues, "error", path, f"{item_path}.id", "올바른 모드 ID가 아닙니다.")
            elif mod_id in seen_ids:
                _issue(issues, "error", path, f"{item_path}.id", f"중복 모드 ID: {mod_id}")
            else:
                seen_ids.add(mod_id)

            if mod.get("side") not in VALID_SIDES:
                _issue(issues, "error", path, f"{item_path}.side", "client, server, both 중 하나여야 합니다.")
            if mod.get("classification") not in VALID_CLASSIFICATIONS:
                _issue(issues, "error", path, f"{item_path}.classification", "지원하지 않는 분류입니다.")
            if not isinstance(mod.get("enabled"), bool):
                _issue(issues, "error", path, f"{item_path}.enabled", "boolean이어야 합니다.")
            if not isinstance(mod.get("display_name"), str) or not mod.get("display_name", "").strip():
                _issue(issues, "error", path, f"{item_path}.display_name", "이름이 필요합니다.")
            if not isinstance(mod.get("reason"), str) or not mod.get("reason", "").strip():
                _issue(issues, "error", path, f"{item_path}.reason", "선정 이유가 필요합니다.")

            curseforge = _require_object(
                mod.get("curseforge"), issues, path, f"{item_path}.curseforge"
            )
            project_id = curseforge.get("project_id") if curseforge else None
            file_id = curseforge.get("file_id") if curseforge else None
            if project_id is not None and (not isinstance(project_id, int) or project_id < 1):
                _issue(issues, "error", path, f"{item_path}.curseforge.project_id", "양의 정수 또는 null이어야 합니다.")
            if file_id is not None and (not isinstance(file_id, int) or file_id < 1):
                _issue(issues, "error", path, f"{item_path}.curseforge.file_id", "양의 정수 또는 null이어야 합니다.")
            if isinstance(project_id, int) and isinstance(file_id, int):
                pair = (project_id, file_id)
                if pair in seen_cf_files:
                    _issue(issues, "error", path, f"{item_path}.curseforge", "동일한 CurseForge 파일이 중복되었습니다.")
                seen_cf_files.add(pair)

            if (strict_pack or status == "locked") and mod.get("enabled"):
                if not mod.get("version"):
                    _issue(issues, "error", path, f"{item_path}.version", "활성 모드 버전을 고정해야 합니다.")
                if not isinstance(project_id, int) or not isinstance(file_id, int):
                    _issue(issues, "error", path, f"{item_path}.curseforge", "활성 외부 모드의 CurseForge ID를 고정해야 합니다.")

    content_packs = _require_list(root.get("content_packs"), issues, path, "$.content_packs")
    seen_content_pack_ids: set[str] = set()
    seen_modrinth_versions: set[tuple[str, str]] = set()
    if content_packs is not None:
        for index, value in enumerate(content_packs):
            item_path = f"$.content_packs[{index}]"
            content_pack = _require_object(value, issues, path, item_path)
            if content_pack is None:
                continue

            content_pack_id = content_pack.get("id")
            if not isinstance(content_pack_id, str) or not MOD_ID.fullmatch(content_pack_id):
                _issue(issues, "error", path, f"{item_path}.id", "올바른 콘텐츠팩 ID가 아닙니다.")
            elif content_pack_id in seen_content_pack_ids:
                _issue(issues, "error", path, f"{item_path}.id", f"중복 콘텐츠팩 ID: {content_pack_id}")
            else:
                seen_content_pack_ids.add(content_pack_id)

            if content_pack.get("kind") not in {"datapack", "resourcepack"}:
                _issue(issues, "error", path, f"{item_path}.kind", "datapack 또는 resourcepack이어야 합니다.")
            if content_pack.get("classification") not in VALID_CLASSIFICATIONS:
                _issue(issues, "error", path, f"{item_path}.classification", "지원하지 않는 분류입니다.")
            if content_pack.get("side") not in VALID_SIDES:
                _issue(issues, "error", path, f"{item_path}.side", "client, server, both 중 하나여야 합니다.")
            if not isinstance(content_pack.get("selected"), bool):
                _issue(issues, "error", path, f"{item_path}.selected", "boolean이어야 합니다.")
            if content_pack.get("artifact_format") not in {"zip", "fabric_mod", "neoforge_mod"}:
                _issue(issues, "error", path, f"{item_path}.artifact_format", "지원하지 않는 배포 형식입니다.")
            if content_pack.get("packaging_status") not in {"ready", "blocked"}:
                _issue(issues, "error", path, f"{item_path}.packaging_status", "ready 또는 blocked여야 합니다.")
            if content_pack.get("runtime_status") not in {"ready", "blocked"}:
                _issue(issues, "error", path, f"{item_path}.runtime_status", "ready 또는 blocked여야 합니다.")
            if content_pack.get("install_path") not in {"datapacks", "resourcepacks", "mods"}:
                _issue(issues, "error", path, f"{item_path}.install_path", "지원하지 않는 설치 경로입니다.")
            if not isinstance(content_pack.get("display_name"), str) or not content_pack.get("display_name", "").strip():
                _issue(issues, "error", path, f"{item_path}.display_name", "이름이 필요합니다.")
            if not isinstance(content_pack.get("reason"), str) or not content_pack.get("reason", "").strip():
                _issue(issues, "error", path, f"{item_path}.reason", "선정 이유가 필요합니다.")
            if not isinstance(content_pack.get("source_url"), str) or not content_pack.get("source_url", "").startswith("https://"):
                _issue(issues, "error", path, f"{item_path}.source_url", "HTTPS 원본 주소가 필요합니다.")
            if not isinstance(content_pack.get("license"), str) or not content_pack.get("license", "").strip():
                _issue(issues, "error", path, f"{item_path}.license", "배포 라이선스가 필요합니다.")

            sha1 = content_pack.get("sha1")
            sha512 = content_pack.get("sha512")
            if not isinstance(sha1, str) or not re.fullmatch(r"[0-9a-f]{40}", sha1):
                _issue(issues, "error", path, f"{item_path}.sha1", "소문자 16진수 SHA-1이 필요합니다.")
            if not isinstance(sha512, str) or not re.fullmatch(r"[0-9a-f]{128}", sha512):
                _issue(issues, "error", path, f"{item_path}.sha512", "소문자 16진수 SHA-512가 필요합니다.")

            modrinth = _require_object(
                content_pack.get("modrinth"), issues, path, f"{item_path}.modrinth"
            )
            project_id = modrinth.get("project_id") if modrinth else None
            version_id = modrinth.get("version_id") if modrinth else None
            if project_id is not None and (not isinstance(project_id, str) or not project_id.strip()):
                _issue(issues, "error", path, f"{item_path}.modrinth.project_id", "비어 있지 않은 문자열 또는 null이어야 합니다.")
            if version_id is not None and (not isinstance(version_id, str) or not version_id.strip()):
                _issue(issues, "error", path, f"{item_path}.modrinth.version_id", "비어 있지 않은 문자열 또는 null이어야 합니다.")
            if isinstance(project_id, str) and isinstance(version_id, str):
                pair = (project_id, version_id)
                if pair in seen_modrinth_versions:
                    _issue(issues, "error", path, f"{item_path}.modrinth", "동일한 Modrinth 파일이 중복되었습니다.")
                seen_modrinth_versions.add(pair)

            if content_pack.get("selected"):
                if not content_pack.get("version"):
                    _issue(issues, "error", path, f"{item_path}.version", "선정 콘텐츠팩 버전을 고정해야 합니다.")
                if not isinstance(project_id, str) or not isinstance(version_id, str):
                    _issue(issues, "error", path, f"{item_path}.modrinth", "선정 콘텐츠팩의 Modrinth ID를 고정해야 합니다.")
                if content_pack.get("packaging_status") == "blocked":
                    severity = "error" if strict_pack or status == "locked" else "warning"
                    _issue(
                        issues,
                        severity,
                        path,
                        f"{item_path}.packaging_status",
                        "선정 콘텐츠팩의 패키징 차단 사유를 해결해야 합니다.",
                    )
                if content_pack.get("runtime_status") == "blocked":
                    severity = "error" if strict_pack or status == "locked" else "warning"
                    _issue(
                        issues,
                        severity,
                        path,
                        f"{item_path}.runtime_status",
                        "선정 콘텐츠팩의 게임 런타임 호환 문제를 해결해야 합니다.",
                    )

                vendored_path = content_pack.get("vendored_path")
                if content_pack.get("packaging_status") == "ready":
                    if not isinstance(vendored_path, str) or not vendored_path.strip():
                        _issue(issues, "error", path, f"{item_path}.vendored_path", "패키징할 저장소 파일 경로가 필요합니다.")
                    else:
                        repository_root = path.resolve().parents[1]
                        artifact_path = (repository_root / vendored_path).resolve()
                        try:
                            artifact_path.relative_to(repository_root)
                        except ValueError:
                            _issue(issues, "error", path, f"{item_path}.vendored_path", "저장소 밖의 파일은 패키징할 수 없습니다.")
                        else:
                            if not artifact_path.is_file():
                                _issue(issues, "error", path, f"{item_path}.vendored_path", "패키징할 원본 파일이 없습니다.")
                            elif isinstance(sha1, str) and isinstance(sha512, str):
                                artifact = artifact_path.read_bytes()
                                if hashlib.sha1(artifact).hexdigest() != sha1:
                                    _issue(issues, "error", path, f"{item_path}.sha1", "저장소 파일의 SHA-1이 Lock과 다릅니다.")
                                if hashlib.sha512(artifact).hexdigest() != sha512:
                                    _issue(issues, "error", path, f"{item_path}.sha512", "저장소 파일의 SHA-512가 Lock과 다릅니다.")

    if status == "draft":
        severity = "error" if strict_pack else "warning"
        _issue(
            issues,
            severity,
            path,
            "$.status",
            "의존성이 draft 상태입니다. 일반 콘텐츠 개발은 가능하지만 정식 테스트팩 패키징은 차단됩니다.",
        )
    return issues


def validate_npc_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        root = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return None, issues
    if not isinstance(root, dict):
        _issue(issues, "error", path, "$", "NPC 문서는 객체여야 합니다.")
        return None, issues
    if root.get("schema_version") != 3:
        _issue(issues, "error", path, "$.schema_version", "NPC 상호작용 스키마 버전은 3입니다.")
    if "placement" in root:
        _issue(
            issues,
            "error",
            path,
            "$.placement",
            "NPC 배치는 마을의 npc_placement.trainer_slots에서 관리해야 합니다.",
        )
    npc_id = _resource_id(root.get("id"), issues, path, "$.id")
    if npc_id and ":npc/" not in npc_id:
        _issue(issues, "error", path, "$.id", "NPC ID는 namespace:npc/path 형식이어야 합니다.")
    if not isinstance(root.get("enabled"), bool):
        _issue(issues, "error", path, "$.enabled", "boolean이어야 합니다.")
    _localized_text(root.get("name"), issues, path, "$.name")
    npc = _require_object(root.get("npc"), issues, path, "$.npc")
    if npc is not None:
        _localized_text(npc.get("display_name"), issues, path, "$.npc.display_name")
        _resource_id(npc.get("trainer_class"), issues, path, "$.npc.trainer_class")
        if npc.get("role", "default") not in {"default", "gatekeeper"}:
            _issue(issues, "error", path, "$.npc.role", "NPC 역할은 default 또는 gatekeeper여야 합니다.")
        double_battle = npc.get("double_battle")
        if double_battle is not None:
            double_battle = _require_object(double_battle, issues, path, "$.npc.double_battle")
            if double_battle is not None:
                partner_id = _resource_id(
                    double_battle.get("partner"), issues, path, "$.npc.double_battle.partner"
                )
                _resource_id(
                    double_battle.get("group_id"), issues, path, "$.npc.double_battle.group_id"
                )
                _resource_id(
                    double_battle.get("shared_clear_key"), issues, path,
                    "$.npc.double_battle.shared_clear_key",
                )
                if partner_id and ":npc/" not in partner_id:
                    _issue(
                        issues, "error", path, "$.npc.double_battle.partner",
                        "파트너 NPC ID는 namespace:npc/path 형식이어야 합니다.",
                    )
                if partner_id and partner_id == npc_id:
                    _issue(
                        issues, "error", path, "$.npc.double_battle.partner",
                        "자기 자신을 더블배틀 파트너로 지정할 수 없습니다.",
                    )
        battle_rewards = npc.get("battle_rewards")
        if battle_rewards is not None:
            battle_rewards = _require_object(battle_rewards, issues, path, "$.npc.battle_rewards")
            money = _require_object(
                battle_rewards.get("money") if battle_rewards else None,
                issues, path, "$.npc.battle_rewards.money",
            )
            if money is not None:
                if not isinstance(money.get("enabled"), bool):
                    _issue(issues, "error", path, "$.npc.battle_rewards.money.enabled", "boolean이어야 합니다.")
                mode = money.get("mode")
                if mode == "fixed":
                    amount = money.get("amount")
                    if not isinstance(amount, int) or isinstance(amount, bool) or amount < 0:
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.amount", "0 이상의 고정 상금이 필요합니다.")
                elif mode == "regional_level":
                    fallback = money.get("fallback_region_level")
                    per_level = money.get("per_level")
                    offset = money.get("offset")
                    if not isinstance(fallback, int) or isinstance(fallback, bool) or not 1 <= fallback <= 100:
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.fallback_region_level", "1~100의 기본 지역 레벨이 필요합니다.")
                    if not isinstance(per_level, int) or isinstance(per_level, bool) or per_level < 0:
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.per_level", "레벨당 금액은 0 이상의 정수여야 합니다.")
                    if not isinstance(offset, int) or isinstance(offset, bool):
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.offset", "상금 보정값은 정수여야 합니다.")
                else:
                    _issue(issues, "error", path, "$.npc.battle_rewards.money.mode", "fixed 또는 regional_level이어야 합니다.")
                if money.get("held_item_bonus"):
                    _resource_id(money.get("held_item"), issues, path, "$.npc.battle_rewards.money.held_item")
                    multiplier = money.get("held_item_multiplier")
                    if not isinstance(multiplier, int) or isinstance(multiplier, bool) or multiplier < 1:
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.held_item_multiplier", "지닌 도구 배율은 1 이상의 정수여야 합니다.")
                conditions = _require_list(money.get("conditions", []), issues, path, "$.npc.battle_rewards.money.conditions")
                if conditions is not None:
                    for condition_index, condition in enumerate(conditions):
                        _validate_operation(condition, issues, path, f"$.npc.battle_rewards.money.conditions[{condition_index}]", npc_id, [])
        appearance = _require_object(npc.get("appearance"), issues, path, "$.npc.appearance")
        if appearance is not None:
            _resource_id(appearance.get("resource"), issues, path, "$.npc.appearance.resource")
        behavior = _require_object(npc.get("behavior"), issues, path, "$.npc.behavior")
        if behavior is not None:
            encounter = _require_object(behavior.get("encounter"), issues, path, "$.npc.behavior.encounter")
            if encounter is not None:
                mode = encounter.get("mode")
                if mode not in {"interaction", "proximity"}:
                    _issue(issues, "error", path, "$.npc.behavior.encounter.mode", "interaction 또는 proximity여야 합니다.")
                trigger_range = encounter.get("trigger_range")
                if not isinstance(trigger_range, (int, float)) or isinstance(trigger_range, bool) or trigger_range <= 0:
                    _issue(issues, "error", path, "$.npc.behavior.encounter.trigger_range", "0보다 큰 숫자여야 합니다.")
                warning = _require_object(encounter.get("warning_range"), issues, path, "$.npc.behavior.encounter.warning_range")
                if warning is not None:
                    minimum = warning.get("min")
                    maximum = warning.get("max")
                    if (
                        any(not isinstance(value, (int, float)) or isinstance(value, bool) for value in (minimum, maximum))
                        or minimum < 0
                        or maximum <= minimum
                    ):
                        _issue(issues, "error", path, "$.npc.behavior.encounter.warning_range", "0 이상의 min과 min보다 큰 max가 필요합니다.")
                    elif mode == "proximity" and isinstance(trigger_range, (int, float)) and minimum < trigger_range:
                        _issue(issues, "error", path, "$.npc.behavior.encounter.warning_range.min", "경고 범위는 자동 조우 발동 거리 이상이어야 합니다.")

    interaction = _require_object(root.get("interaction"), issues, path, "$.interaction")
    if interaction is None:
        return npc_id, issues
    nodes = _require_list(interaction.get("nodes"), issues, path, "$.interaction.nodes")
    routes = _require_list(interaction.get("entry_routes"), issues, path, "$.interaction.entry_routes")
    node_ids: set[str] = set()
    targets: list[tuple[str, str]] = []
    if nodes is not None:
        if not nodes:
            _issue(issues, "error", path, "$.interaction.nodes", "상호작용 노드가 하나 이상 필요합니다.")
        for index, value in enumerate(nodes):
            node_path = f"$.interaction.nodes[{index}]"
            node = _require_object(value, issues, path, node_path)
            if node is None:
                continue
            node_id = _resource_id(node.get("id"), issues, path, f"{node_path}.id")
            if node_id:
                if node_id in node_ids:
                    _issue(issues, "error", path, f"{node_path}.id", f"중복 노드 ID: {node_id}")
                node_ids.add(node_id)
            node_type = node.get("type")
            if node_type not in {"dialogue", "actions", "close"}:
                _issue(issues, "error", path, f"{node_path}.type", "dialogue, actions, close 중 하나여야 합니다.")
                continue
            _validate_operation_list(node.get("conditions", []), issues, path, f"{node_path}.conditions", npc_id, targets)
            if node_type == "dialogue":
                if node.get("speaker") not in {"npc", "player", "system"}:
                    _issue(issues, "error", path, f"{node_path}.speaker", "npc, player, system 중 하나여야 합니다.")
                _localized_text(node.get("text"), issues, path, f"{node_path}.text")
                choices = _require_list(node.get("choices"), issues, path, f"{node_path}.choices")
                if choices is not None:
                    for choice_index, choice_value in enumerate(choices):
                        choice_path = f"{node_path}.choices[{choice_index}]"
                        choice = _require_object(choice_value, issues, path, choice_path)
                        if choice is None:
                            continue
                        _localized_text(choice.get("text"), issues, path, f"{choice_path}.text")
                        _validate_operation_list(choice.get("conditions", []), issues, path, f"{choice_path}.conditions", npc_id, targets)
                        _validate_operation_list(choice.get("actions"), issues, path, f"{choice_path}.actions", npc_id, targets)
            elif node_type == "actions":
                actions = _validate_operation_list(node.get("actions"), issues, path, f"{node_path}.actions", npc_id, targets)
                if not actions:
                    _issue(issues, "error", path, f"{node_path}.actions", "액션 노드에는 행동이 하나 이상 필요합니다.")
            next_id = node.get("next")
            if next_id is not None:
                target = _resource_id(next_id, issues, path, f"{node_path}.next")
                if target:
                    targets.append((f"{node_path}.next", target))
    if routes is not None:
        if not routes:
            _issue(issues, "error", path, "$.interaction.entry_routes", "시작 경로가 하나 이상 필요합니다.")
        fallback_count = 0
        for index, value in enumerate(routes):
            route_path = f"$.interaction.entry_routes[{index}]"
            route = _require_object(value, issues, path, route_path)
            if route is None:
                continue
            conditions = _validate_operation_list(route.get("conditions"), issues, path, f"{route_path}.conditions", npc_id, targets)
            if conditions == []:
                fallback_count += 1
                if index != len(routes) - 1:
                    _issue(issues, "error", path, f"{route_path}.conditions", "무조건 시작 경로는 마지막에 있어야 합니다.")
            entry = _resource_id(route.get("entry"), issues, path, f"{route_path}.entry")
            if entry:
                targets.append((f"{route_path}.entry", entry))
        if fallback_count != 1:
            _issue(issues, "error", path, "$.interaction.entry_routes", "조건이 없는 기본 시작 경로가 정확히 하나 필요합니다.")
    for target_path, target in targets:
        if target not in node_ids:
            _issue(issues, "error", path, target_path, f"존재하지 않는 상호작용 노드: {target}")
    return npc_id, issues


def validate_battle_preset_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        root = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return None, issues
    if not isinstance(root, dict):
        _issue(issues, "error", path, "$", "배틀 프리셋은 객체여야 합니다.")
        return None, issues
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "배틀 프리셋 스키마 버전은 1입니다.")
    battle_id = _resource_id(root.get("id"), issues, path, "$.id")
    if battle_id and ":battle/" not in battle_id:
        _issue(issues, "error", path, "$.id", "배틀 ID는 namespace:battle/path 형식이어야 합니다.")
    _localized_text(root.get("name"), issues, path, "$.name")
    battle = _require_object(root.get("battle"), issues, path, "$.battle")
    if battle is not None:
        _resource_id(battle.get("trainer_id"), issues, path, "$.battle.trainer_id")
        battle_format = battle.get("format")
        battle_type = battle.get("battle_type")
        if battle_format not in BATTLE_FORMAT_TYPES:
            _issue(issues, "error", path, "$.battle.format", "지원하지 않는 배틀 포맷입니다.")
        elif BATTLE_FORMAT_TYPES[battle_format] != battle_type:
            _issue(issues, "error", path, "$.battle.battle_type", "배틀 포맷과 전투 방식이 일치해야 합니다.")
        ai = _require_object(battle.get("ai"), issues, path, "$.battle.ai")
        if ai is not None:
            if ai.get("difficulty") not in AI_DIFFICULTIES:
                _issue(issues, "error", path, "$.battle.ai.difficulty", "지원하지 않는 AI 난이도입니다.")
            if ai.get("strategy") not in AI_STRATEGIES:
                _issue(issues, "error", path, "$.battle.ai.strategy", "지원하지 않는 AI 전략입니다.")
        team = _require_list(battle.get("team"), issues, path, "$.battle.team")
        if team is not None and not 1 <= len(team) <= 6:
            _issue(issues, "error", path, "$.battle.team", "포켓몬은 1마리 이상 6마리 이하여야 합니다.")
        trainer_id = battle.get("trainer_id")
        if isinstance(trainer_id, str) and RESOURCE_ID.fullmatch(trainer_id):
            legacy = _trainer_template("validation", _localized_value(root.get("name")) or "배틀")
            legacy["id"] = trainer_id
            legacy["battle"] = battle
            with tempfile.TemporaryDirectory() as directory:
                legacy_path = Path(directory) / "battle-validation.json"
                legacy_path.write_text(json.dumps(legacy, ensure_ascii=False), encoding="utf-8")
                _, legacy_issues = validate_content_file(legacy_path)
            issues.extend(
                Issue(issue.level, path.as_posix(), issue.path, issue.message)
                for issue in legacy_issues
                if issue.path.startswith("$.battle")
            )
    return battle_id, issues


def validate_npc_event_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        root = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return None, issues
    if not isinstance(root, dict):
        _issue(issues, "error", path, "$", "NPC 이벤트 문서는 객체여야 합니다.")
        return None, issues
    npc_id = _resource_id(root.get("id"), issues, path, "$.id")
    if root.get("schema_version") != 4:
        _issue(issues, "error", path, "$.schema_version", "NPC 이벤트 스크립트 버전은 4입니다.")
    if npc_id and ":npc/" not in npc_id:
        _issue(issues, "error", path, "$.id", "NPC ID는 namespace:npc/path 형식이어야 합니다.")
    if "placement" in root:
        _issue(issues, "error", path, "$.placement", "NPC 배치는 마을의 npc_placement.trainer_slots에서 관리해야 합니다.")
    if not isinstance(root.get("enabled"), bool):
        _issue(issues, "error", path, "$.enabled", "boolean이어야 합니다.")
    _localized_text(root.get("name"), issues, path, "$.name")
    npc = _require_object(root.get("npc"), issues, path, "$.npc")
    if npc is not None:
        _localized_text(npc.get("display_name"), issues, path, "$.npc.display_name")
        _resource_id(npc.get("trainer_class"), issues, path, "$.npc.trainer_class")
        battle_rewards = npc.get("battle_rewards")
        if battle_rewards is not None:
            battle_rewards = _require_object(battle_rewards, issues, path, "$.npc.battle_rewards")
            money = _require_object(
                battle_rewards.get("money") if battle_rewards else None,
                issues, path, "$.npc.battle_rewards.money",
            )
            if money is not None:
                mode = money.get("mode")
                if mode == "fixed":
                    amount = money.get("amount")
                    if not isinstance(amount, int) or isinstance(amount, bool) or amount < 0:
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.amount", "0 이상의 고정 상금이 필요합니다.")
                elif mode == "regional_level":
                    fallback = money.get("fallback_region_level")
                    per_level = money.get("per_level")
                    offset = money.get("offset")
                    if not isinstance(fallback, int) or isinstance(fallback, bool) or not 1 <= fallback <= 100:
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.fallback_region_level", "1~100의 기본 지역 레벨이 필요합니다.")
                    if not isinstance(per_level, int) or isinstance(per_level, bool) or per_level < 0:
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.per_level", "레벨당 금액은 0 이상의 정수여야 합니다.")
                    if not isinstance(offset, int) or isinstance(offset, bool):
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.offset", "상금 보정값은 정수여야 합니다.")
                else:
                    _issue(issues, "error", path, "$.npc.battle_rewards.money.mode", "fixed 또는 regional_level이어야 합니다.")
                if money.get("held_item_bonus"):
                    _resource_id(money.get("held_item"), issues, path, "$.npc.battle_rewards.money.held_item")
                    multiplier = money.get("held_item_multiplier")
                    if not isinstance(multiplier, int) or isinstance(multiplier, bool) or multiplier < 1:
                        _issue(issues, "error", path, "$.npc.battle_rewards.money.held_item_multiplier", "지닌 도구 배율은 1 이상의 정수여야 합니다.")
        double_battle = npc.get("double_battle")
        if double_battle is not None:
            double_battle = _require_object(double_battle, issues, path, "$.npc.double_battle")
            if double_battle is not None:
                partner_id = _resource_id(
                    double_battle.get("partner"), issues, path, "$.npc.double_battle.partner"
                )
                _resource_id(
                    double_battle.get("group_id"), issues, path, "$.npc.double_battle.group_id"
                )
                _resource_id(
                    double_battle.get("shared_clear_key"), issues, path,
                    "$.npc.double_battle.shared_clear_key",
                )
                if partner_id and ":npc/" not in partner_id:
                    _issue(
                        issues, "error", path, "$.npc.double_battle.partner",
                        "파트너 NPC ID는 namespace:npc/path 형식이어야 합니다.",
                    )
                if partner_id and partner_id == npc_id:
                    _issue(
                        issues, "error", path, "$.npc.double_battle.partner",
                        "자기 자신을 더블배틀 파트너로 지정할 수 없습니다.",
                    )
        appearance = _require_object(npc.get("appearance"), issues, path, "$.npc.appearance")
        if appearance is not None:
            _resource_id(appearance.get("resource"), issues, path, "$.npc.appearance.resource")
        behavior = _require_object(npc.get("behavior"), issues, path, "$.npc.behavior")
        if behavior is not None:
            if "interaction_range" in behavior or "encounter" in behavior:
                _issue(issues, "error", path, "$.npc.behavior", "상호작용과 자동 조우 거리는 이벤트 trigger에서 설정해야 합니다.")
            if behavior.get("movement") not in {"stationary", "wander", "patrol"}:
                _issue(issues, "error", path, "$.npc.behavior.movement", "지원하지 않는 이동 방식입니다.")
    events = _require_list(root.get("events"), issues, path, "$.events")
    if events is None:
        return npc_id, issues
    if not events:
        _issue(issues, "error", path, "$.events", "NPC 이벤트가 하나 이상 필요합니다.")
    event_ids: set[str] = set()
    command_types = {
        "branch", "label", "dialogue", "choices", "goto", "start_battle",
        "set_flag", "mark_clear", "give_money", "take_money", "give_item", "grant_loot", "grant_badge", "grant_field_move",
        "teleport_to_gate", "end",
    }
    for event_index, event_value in enumerate(events):
        event_path = f"$.events[{event_index}]"
        event = _require_object(event_value, issues, path, event_path)
        if event is None:
            continue
        event_id = event.get("id")
        if not isinstance(event_id, str) or not CHOICE_ID.fullmatch(event_id):
            _issue(issues, "error", path, f"{event_path}.id", "소문자 이벤트 ID가 필요합니다.")
        elif event_id in event_ids:
            _issue(issues, "error", path, f"{event_path}.id", f"중복 이벤트 ID: {event_id}")
        else:
            event_ids.add(event_id)
        trigger = _require_object(event.get("trigger"), issues, path, f"{event_path}.trigger")
        if trigger is not None:
            trigger_type = trigger.get("type")
            if trigger_type not in {"interact", "proximity"}:
                _issue(issues, "error", path, f"{event_path}.trigger.type", "interact 또는 proximity여야 합니다.")
            trigger_range = trigger.get("range")
            if not isinstance(trigger_range, (int, float)) or isinstance(trigger_range, bool) or trigger_range <= 0:
                _issue(issues, "error", path, f"{event_path}.trigger.range", "0보다 큰 발동 거리가 필요합니다.")
            if trigger_type == "interact" and any(key in trigger for key in ("warning_offset", "indicator")):
                _issue(issues, "error", path, f"{event_path}.trigger", "말 걸기 이벤트에는 경고 거리 설정을 사용할 수 없습니다.")
            if trigger_type == "proximity":
                offset = trigger.get("warning_offset", 2)
                if not isinstance(offset, (int, float)) or isinstance(offset, bool) or offset < 0:
                    _issue(issues, "error", path, f"{event_path}.trigger.warning_offset", "경고 여유 거리는 0 이상의 숫자여야 합니다.")
        commands = _require_list(event.get("commands"), issues, path, f"{event_path}.commands")
        if commands is None:
            continue
        labels: set[str] = set()
        targets: list[tuple[str, str]] = []
        for command_index, command_value in enumerate(commands):
            command_path = f"{event_path}.commands[{command_index}]"
            command = _require_object(command_value, issues, path, command_path)
            if command is None:
                continue
            command_type = command.get("type")
            if command_type not in command_types:
                _issue(issues, "error", path, f"{command_path}.type", "지원하지 않는 이벤트 명령입니다.")
                continue
            if command_type == "label":
                name = command.get("name")
                if not isinstance(name, str) or not CHOICE_ID.fullmatch(name):
                    _issue(issues, "error", path, f"{command_path}.name", "소문자 라벨 이름이 필요합니다.")
                elif name in labels:
                    _issue(issues, "error", path, f"{command_path}.name", f"중복 라벨: {name}")
                else:
                    labels.add(name)
            elif command_type == "branch":
                conditions = _require_list(command.get("conditions"), issues, path, f"{command_path}.conditions")
                if conditions is not None:
                    for index, condition in enumerate(conditions):
                        _validate_operation(condition, issues, path, f"{command_path}.conditions[{index}]", npc_id, [])
                if isinstance(command.get("target"), str):
                    targets.append((f"{command_path}.target", command["target"]))
            elif command_type == "dialogue":
                if command.get("speaker") not in {"npc", "player", "system"}:
                    _issue(issues, "error", path, f"{command_path}.speaker", "npc, player, system 중 하나여야 합니다.")
                _localized_text(command.get("text"), issues, path, f"{command_path}.text")
            elif command_type == "choices":
                options = _require_list(command.get("options"), issues, path, f"{command_path}.options")
                if options is not None:
                    for option_index, option_value in enumerate(options):
                        option_path = f"{command_path}.options[{option_index}]"
                        option = _require_object(option_value, issues, path, option_path)
                        if option is not None:
                            _localized_text(option.get("text"), issues, path, f"{option_path}.text")
                            if isinstance(option.get("target"), str):
                                targets.append((f"{option_path}.target", option["target"]))
            elif command_type in {"goto"}:
                if isinstance(command.get("target"), str):
                    targets.append((f"{command_path}.target", command["target"]))
            elif command_type == "start_battle":
                _resource_id(command.get("battle"), issues, path, f"{command_path}.battle")
                results = command.get("results", {})
                if isinstance(results, dict):
                    for key, target in results.items():
                        if key not in {"player_win", "player_loss", "cancelled"}:
                            _issue(issues, "error", path, f"{command_path}.results.{key}", "지원하지 않는 배틀 결과입니다.")
                        elif isinstance(target, str):
                            targets.append((f"{command_path}.results.{key}", target))
            elif command_type in {"set_flag", "mark_clear", "give_money", "take_money", "give_item", "grant_loot", "grant_badge", "grant_field_move"}:
                _validate_operation(command, issues, path, command_path, npc_id, [])
            elif command_type == "teleport_to_gate":
                gate = command.get("gate")
                if not isinstance(gate, str) or not CHOICE_ID.fullmatch(gate):
                    _issue(issues, "error", path, f"{command_path}.gate", "이동할 월드맵 관문 ID가 필요합니다.")
                if command.get("subject") not in {"player", "npc"}:
                    _issue(issues, "error", path, f"{command_path}.subject", "이동 대상은 player 또는 npc여야 합니다.")
                if command.get("side") not in {"front", "back", "center"}:
                    _issue(issues, "error", path, f"{command_path}.side", "관문 이동 위치는 front/back/center 중 하나여야 합니다.")
        for target_path, target in targets:
            if target not in labels:
                _issue(issues, "error", path, target_path, f"존재하지 않는 이벤트 라벨: {target}")
    return npc_id, issues


def validate_content_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return None, issues

    root = _require_object(data, issues, path, "$")
    if root is None:
        return None, issues
    if root.get("schema_version") == 4:
        return validate_npc_event_file(path)
    if root.get("schema_version") == 3:
        return validate_npc_file(path)
    if root.get("schema_version") != 2:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 2입니다.")

    content_id = _resource_id(root.get("id"), issues, path, "$.id")
    if "placement" in root:
        _issue(
            issues,
            "error",
            path,
            "$.placement",
            "트레이너 배치는 마을의 npc_placement.trainer_slots에서 관리해야 합니다.",
        )
    if not isinstance(root.get("enabled"), bool):
        _issue(issues, "error", path, "$.enabled", "boolean이어야 합니다.")
    _localized_text(root.get("name"), issues, path, "$.name")
    if "description" in root:
        _localized_text(root.get("description"), issues, path, "$.description")
    tags = _require_list(root.get("tags"), issues, path, "$.tags")
    if tags is not None:
        seen_tags: set[str] = set()
        for index, tag in enumerate(tags):
            if not isinstance(tag, str) or not CHOICE_ID.fullmatch(tag):
                _issue(issues, "error", path, f"$.tags[{index}]", "올바른 소문자 태그가 아닙니다.")
            elif tag in seen_tags:
                _issue(issues, "error", path, f"$.tags[{index}]", f"중복 태그: {tag}")
            else:
                seen_tags.add(tag)

    npc = _require_object(root.get("npc"), issues, path, "$.npc")
    if npc is not None:
        _localized_text(npc.get("display_name"), issues, path, "$.npc.display_name")
        _resource_id(npc.get("trainer_class"), issues, path, "$.npc.trainer_class")
        if "character" in npc:
            _resource_id(npc.get("character"), issues, path, "$.npc.character")
        appearance = _require_object(npc.get("appearance"), issues, path, "$.npc.appearance")
        if appearance is not None:
            if appearance.get("source") not in {"custom", "rct_single", "rct_group"}:
                _issue(issues, "error", path, "$.npc.appearance.source", "custom, rct_single, rct_group 중 하나여야 합니다.")
            if appearance.get("type") not in {"skin", "model"}:
                _issue(issues, "error", path, "$.npc.appearance.type", "skin 또는 model이어야 합니다.")
            _resource_id(appearance.get("resource"), issues, path, "$.npc.appearance.resource")
            if "portrait" in appearance:
                _resource_id(appearance.get("portrait"), issues, path, "$.npc.appearance.portrait")
        behavior = _require_object(npc.get("behavior"), issues, path, "$.npc.behavior")
        if behavior is not None:
            if behavior.get("movement") not in {"stationary", "wander", "patrol"}:
                _issue(issues, "error", path, "$.npc.behavior.movement", "지원하지 않는 이동 방식입니다.")
            for key in ("look_at_player", "invulnerable"):
                if not isinstance(behavior.get(key), bool):
                    _issue(issues, "error", path, f"$.npc.behavior.{key}", "boolean이어야 합니다.")
            interaction_range = behavior.get("interaction_range")
            if not isinstance(interaction_range, (int, float)) or isinstance(interaction_range, bool) or interaction_range <= 0:
                _issue(issues, "error", path, "$.npc.behavior.interaction_range", "0보다 큰 숫자여야 합니다.")
            encounter = behavior.get("encounter")
            if encounter is not None:
                encounter = _require_object(encounter, issues, path, "$.npc.behavior.encounter")
                if encounter is not None:
                    mode = encounter.get("mode")
                    if mode not in {"interaction", "proximity"}:
                        _issue(issues, "error", path, "$.npc.behavior.encounter.mode", "interaction 또는 proximity여야 합니다.")
                    trigger_range = encounter.get("trigger_range")
                    if mode == "proximity" and (
                        not isinstance(trigger_range, (int, float))
                        or isinstance(trigger_range, bool)
                        or trigger_range <= 0
                    ):
                        _issue(issues, "error", path, "$.npc.behavior.encounter.trigger_range", "자동 조우에는 0보다 큰 발동 거리가 필요합니다.")
                    warning = encounter.get("warning_range")
                    if warning is not None:
                        warning = _require_object(warning, issues, path, "$.npc.behavior.encounter.warning_range")
                        if warning is not None:
                            minimum = warning.get("min")
                            maximum = warning.get("max")
                            if any(
                                not isinstance(value, (int, float)) or isinstance(value, bool)
                                for value in (minimum, maximum)
                            ) or minimum < 0 or maximum <= minimum:
                                _issue(issues, "error", path, "$.npc.behavior.encounter.warning_range", "0 이상의 min과 min보다 큰 max가 필요합니다.")
                            if mode == "proximity" and isinstance(trigger_range, (int, float)) and minimum < trigger_range:
                                _issue(issues, "error", path, "$.npc.behavior.encounter.warning_range.min", "경고 범위는 자동 조우 발동 거리 이상이어야 합니다.")

    battle = _require_object(root.get("battle"), issues, path, "$.battle")
    if battle is not None:
        trainer_id = _resource_id(battle.get("trainer_id"), issues, path, "$.battle.trainer_id")
        if trainer_id and trainer_id != content_id:
            _issue(issues, "error", path, "$.battle.trainer_id", "최상위 콘텐츠 ID와 일치해야 합니다.")
        battle_format = battle.get("format")
        if battle_format not in BATTLE_FORMAT_TYPES:
            _issue(issues, "error", path, "$.battle.format", "지원하지 않는 배틀 포맷입니다.")
        battle_ai = _require_object(battle.get("ai"), issues, path, "$.battle.ai")
        if battle_ai is not None:
            if battle_ai.get("controller") != "cobbleventure":
                _issue(issues, "error", path, "$.battle.ai.controller", "cobbleventure여야 합니다.")
            difficulty = battle_ai.get("difficulty")
            if difficulty not in AI_DIFFICULTIES:
                _issue(issues, "error", path, "$.battle.ai.difficulty", "지원하지 않는 AI 난이도입니다.")
            if battle_ai.get("strategy") not in AI_STRATEGIES:
                _issue(issues, "error", path, "$.battle.ai.strategy", "지원하지 않는 AI 전략입니다.")
            options = _require_object(battle_ai.get("options"), issues, path, "$.battle.ai.options")
            if options is not None:
                cheat_probability = options.get("cheat_probability")
                if difficulty == "cheater":
                    if (
                        not isinstance(cheat_probability, (int, float))
                        or isinstance(cheat_probability, bool)
                        or not 0 <= cheat_probability <= 1
                    ):
                        _issue(issues, "error", path, "$.battle.ai.options.cheat_probability", "치터 확률은 0부터 1 사이의 숫자여야 합니다.")
                elif "cheat_probability" in options:
                    _issue(issues, "error", path, "$.battle.ai.options.cheat_probability", "치터 난이도에서만 설정할 수 있습니다.")
        if battle.get("battle_type") not in {"singles", "doubles"}:
            _issue(issues, "error", path, "$.battle.battle_type", "singles 또는 doubles여야 합니다.")
        elif battle_format in BATTLE_FORMAT_TYPES and BATTLE_FORMAT_TYPES[battle_format] != battle.get("battle_type"):
            _issue(issues, "error", path, "$.battle.format", "배틀 포맷과 전투 방식이 일치해야 합니다.")
        if battle.get("level_mode") not in {"fixed", "scale_to_player", "cap_to_player"}:
            _issue(issues, "error", path, "$.battle.level_mode", "지원하지 않는 레벨 방식입니다.")
        rules = _require_object(battle.get("rules"), issues, path, "$.battle.rules")
        if rules is not None and "max_item_uses" in rules:
            max_item_uses = rules.get("max_item_uses")
            if (
                not isinstance(max_item_uses, int)
                or isinstance(max_item_uses, bool)
                or max_item_uses < 0
            ):
                _issue(
                    issues,
                    "error",
                    path,
                    "$.battle.rules.max_item_uses",
                    "0 이상의 정수여야 합니다.",
                )
        bag = _require_list(battle.get("bag"), issues, path, "$.battle.bag")
        if bag is not None:
            for index, item_value in enumerate(bag):
                item_path = f"$.battle.bag[{index}]"
                item = _require_object(item_value, issues, path, item_path)
                if item is not None:
                    _resource_id(item.get("item"), issues, path, f"{item_path}.item")
                    quantity = item.get("quantity")
                    if not isinstance(quantity, int) or isinstance(quantity, bool) or quantity < 1:
                        _issue(issues, "error", path, f"{item_path}.quantity", "1 이상의 정수여야 합니다.")
        mechanics = _require_object(battle.get("mechanics"), issues, path, "$.battle.mechanics")
        if mechanics is not None:
            for key in ("mega_evolution", "z_move", "dynamax", "terastallization"):
                if not isinstance(mechanics.get(key), bool):
                    _issue(issues, "error", path, f"$.battle.mechanics.{key}", "boolean이어야 합니다.")
        team = _require_list(battle.get("team"), issues, path, "$.battle.team")
        if team is not None:
            if not 1 <= len(team) <= 6:
                _issue(issues, "error", path, "$.battle.team", "포켓몬은 1마리 이상 6마리 이하여야 합니다.")
            for index, pokemon_value in enumerate(team):
                pokemon_path = f"$.battle.team[{index}]"
                pokemon = _require_object(pokemon_value, issues, path, pokemon_path)
                if pokemon is None:
                    continue
                _resource_id(pokemon.get("species"), issues, path, f"{pokemon_path}.species")
                level = pokemon.get("level")
                if not isinstance(level, int) or isinstance(level, bool) or not 1 <= level <= 100:
                    _issue(issues, "error", path, f"{pokemon_path}.level", "1부터 100 사이의 정수여야 합니다.")
                if pokemon.get("gender") not in {"male", "female", "genderless", "random"}:
                    _issue(issues, "error", path, f"{pokemon_path}.gender", "지원하지 않는 성별 값입니다.")
                form = pokemon.get("form")
                if form is not None and (not isinstance(form, str) or not form.strip()):
                    _issue(issues, "error", path, f"{pokemon_path}.form", "폼은 비어 있지 않은 문자열 또는 null이어야 합니다.")
                aspects = pokemon.get("aspects", [])
                if not isinstance(aspects, list) or any(
                    not isinstance(aspect, str) or not aspect.strip() for aspect in aspects
                ):
                    _issue(issues, "error", path, f"{pokemon_path}.aspects", "aspects는 비어 있지 않은 문자열 배열이어야 합니다.")
                elif len(aspects) != len(set(aspects)):
                    _issue(issues, "error", path, f"{pokemon_path}.aspects", "aspects는 중복될 수 없습니다.")
                held_item = pokemon.get("held_item")
                if held_item is not None:
                    _resource_id(held_item, issues, path, f"{pokemon_path}.held_item")
                gimmick = pokemon.get("gimmick")
                if gimmick is not None:
                    gimmick_object = _require_object(gimmick, issues, path, f"{pokemon_path}.gimmick")
                    if gimmick_object is not None:
                        gimmick_type = gimmick_object.get("type")
                        if gimmick_type not in {"mega_evolution", "z_move"}:
                            _issue(
                                issues,
                                "error",
                                path,
                                f"{pokemon_path}.gimmick.type",
                                "mega_evolution 또는 z_move여야 합니다.",
                            )
                        _resource_id(gimmick_object.get("item"), issues, path, f"{pokemon_path}.gimmick.item")
                        if held_item is not None:
                            _issue(
                                issues,
                                "error",
                                path,
                                pokemon_path,
                                "일반 소지품과 메가진화·Z기술 아이템은 동시에 지정할 수 없습니다.",
                            )
                        if gimmick_type in {"mega_evolution", "z_move"} and (
                            mechanics is None or mechanics.get(gimmick_type) is not True
                        ):
                            _issue(
                                issues,
                                "error",
                                path,
                                f"{pokemon_path}.gimmick",
                                "포켓몬 기믹을 사용하려면 같은 전투 기믹을 허용해야 합니다.",
                            )
                for boolean_key in ("shiny", "gigantamax_factor"):
                    if boolean_key in pokemon and not isinstance(pokemon.get(boolean_key), bool):
                        _issue(issues, "error", path, f"{pokemon_path}.{boolean_key}", "boolean이어야 합니다.")
                if pokemon.get("tera_type") not in TERA_TYPES:
                    _issue(
                        issues,
                        "error",
                        path,
                        f"{pokemon_path}.tera_type",
                        "auto 또는 지원하는 포켓몬 타입이어야 합니다.",
                    )
                moves = _require_list(pokemon.get("moves"), issues, path, f"{pokemon_path}.moves")
                if moves is not None and not 1 <= len(moves) <= 4:
                    _issue(issues, "error", path, f"{pokemon_path}.moves", "기술은 1개 이상 4개 이하여야 합니다.")
                for stats_key, maximum in (("ivs", 31), ("evs", 252)):
                    stats = _require_object(pokemon.get(stats_key), issues, path, f"{pokemon_path}.{stats_key}")
                    if stats is not None:
                        for stat, stat_value in stats.items():
                            if stat not in STAT_NAMES:
                                _issue(issues, "error", path, f"{pokemon_path}.{stats_key}.{stat}", "지원하지 않는 능력치 이름입니다.")
                            if not isinstance(stat_value, int) or isinstance(stat_value, bool) or not 0 <= stat_value <= maximum:
                                _issue(issues, "error", path, f"{pokemon_path}.{stats_key}.{stat}", f"0부터 {maximum} 사이의 정수여야 합니다.")
                        if stats_key == "evs" and sum(v for v in stats.values() if isinstance(v, int) and not isinstance(v, bool)) > 510:
                            _issue(issues, "error", path, f"{pokemon_path}.evs", "EV 합계는 510 이하여야 합니다.")

    rewards = root.get("rewards")
    if rewards is not None:
        rewards = _require_object(rewards, issues, path, "$.rewards")
        if rewards is not None:
            money = _require_object(rewards.get("money"), issues, path, "$.rewards.money")
            if money is not None:
                mode = money.get("mode")
                if mode == "fixed":
                    amount = money.get("amount")
                    if not isinstance(amount, int) or isinstance(amount, bool) or amount < 0:
                        _issue(issues, "error", path, "$.rewards.money.amount", "고정 상금은 0 이상의 정수여야 합니다.")
                elif mode == "level_cap_multiplier":
                    multiplier = money.get("multiplier")
                    if not isinstance(multiplier, (int, float)) or isinstance(multiplier, bool) or multiplier <= 0:
                        _issue(issues, "error", path, "$.rewards.money.multiplier", "레벨캡 배율은 0보다 큰 숫자여야 합니다.")
                else:
                    _issue(issues, "error", path, "$.rewards.money.mode", "fixed 또는 level_cap_multiplier여야 합니다.")
            items = _require_object(rewards.get("items"), issues, path, "$.rewards.items")
            if items is not None:
                mode = items.get("mode")
                if mode == "fixed":
                    entries = _require_list(items.get("entries"), issues, path, "$.rewards.items.entries")
                    if entries is not None:
                        for index, value in enumerate(entries):
                            entry_path = f"$.rewards.items.entries[{index}]"
                            entry = _require_object(value, issues, path, entry_path)
                            if entry is not None:
                                _resource_id(entry.get("item"), issues, path, f"{entry_path}.item")
                                count = entry.get("count")
                                if not isinstance(count, int) or isinstance(count, bool) or count < 1:
                                    _issue(issues, "error", path, f"{entry_path}.count", "1 이상의 정수여야 합니다.")
                elif mode == "loot_table":
                    _resource_id(items.get("loot_table"), issues, path, "$.rewards.items.loot_table")
                else:
                    _issue(issues, "error", path, "$.rewards.items.mode", "fixed 또는 loot_table이어야 합니다.")

    dialogue_targets: list[tuple[str, str]] = []
    dialogue = _require_object(root.get("dialogue"), issues, path, "$.dialogue")
    dialogue_ids: set[str] = set()
    dialogue_entry = None
    if dialogue is not None:
        dialogue_entry = _resource_id(dialogue.get("entry"), issues, path, "$.dialogue.entry")
        nodes = _require_list(dialogue.get("nodes"), issues, path, "$.dialogue.nodes")
        if nodes is not None:
            for dialogue_index, value in enumerate(nodes):
                dialogue_path = f"$.dialogue.nodes[{dialogue_index}]"
                node = _require_object(value, issues, path, dialogue_path)
                if node is None:
                    continue
                dialogue_id = _resource_id(node.get("id"), issues, path, f"{dialogue_path}.id")
                if dialogue_id:
                    if dialogue_id in dialogue_ids:
                        _issue(issues, "error", path, f"{dialogue_path}.id", f"중복 대화 ID: {dialogue_id}")
                    dialogue_ids.add(dialogue_id)
                if node.get("speaker") not in {"npc", "player", "system"}:
                    _issue(issues, "error", path, f"{dialogue_path}.speaker", "npc, player, system 중 하나여야 합니다.")
                _localized_text(node.get("text"), issues, path, f"{dialogue_path}.text")
                _validate_operation_list(node.get("conditions"), issues, path, f"{dialogue_path}.conditions", content_id, dialogue_targets)
                node_actions = _validate_operation_list(node.get("actions"), issues, path, f"{dialogue_path}.actions", content_id, dialogue_targets)
                choices = _require_list(node.get("choices"), issues, path, f"{dialogue_path}.choices")
                if not node_actions and not choices:
                    _issue(issues, "error", path, dialogue_path, "행동 또는 선택지가 하나 이상 필요합니다.")
                seen_choices: set[str] = set()
                if choices is None:
                    continue
                for choice_index, choice_value in enumerate(choices):
                    choice_path = f"{dialogue_path}.choices[{choice_index}]"
                    choice = _require_object(choice_value, issues, path, choice_path)
                    if choice is None:
                        continue
                    choice_id = choice.get("id")
                    if not isinstance(choice_id, str) or not CHOICE_ID.fullmatch(choice_id):
                        _issue(issues, "error", path, f"{choice_path}.id", "올바른 선택지 ID가 아닙니다.")
                    elif choice_id in seen_choices:
                        _issue(issues, "error", path, f"{choice_path}.id", f"현재 대화의 중복 선택지 ID: {choice_id}")
                    else:
                        seen_choices.add(choice_id)
                    _localized_text(choice.get("text"), issues, path, f"{choice_path}.text")
                    _validate_operation_list(choice.get("conditions"), issues, path, f"{choice_path}.conditions", content_id, dialogue_targets)
                    actions = _validate_operation_list(choice.get("actions"), issues, path, f"{choice_path}.actions", content_id, dialogue_targets)
                    if not actions:
                        _issue(issues, "error", path, f"{choice_path}.actions", "행동이 하나 이상 필요합니다.")

    progression = _require_object(root.get("progression"), issues, path, "$.progression")
    if progression is not None:
        _validate_operation_list(progression.get("requirements"), issues, path, "$.progression.requirements", content_id, dialogue_targets)
        _resource_id(progression.get("victory_flag"), issues, path, "$.progression.victory_flag")
        rematch = _require_object(progression.get("rematch"), issues, path, "$.progression.rematch")
        if rematch is not None and not isinstance(rematch.get("enabled"), bool):
            _issue(issues, "error", path, "$.progression.rematch.enabled", "boolean이어야 합니다.")
        routes = _require_list(progression.get("dialogue_routes"), issues, path, "$.progression.dialogue_routes")
        if routes is not None:
            if not routes:
                _issue(issues, "error", path, "$.progression.dialogue_routes", "대화 경로가 하나 이상 필요합니다.")
            for index, route_value in enumerate(routes):
                route_path = f"$.progression.dialogue_routes[{index}]"
                route = _require_object(route_value, issues, path, route_path)
                if route is None:
                    continue
                _validate_operation(route.get("when"), issues, path, f"{route_path}.when", content_id, dialogue_targets)
                entry = _resource_id(route.get("entry"), issues, path, f"{route_path}.entry")
                if entry:
                    dialogue_targets.append((route_path, entry))

    outcomes = _require_object(root.get("outcomes"), issues, path, "$.outcomes")
    if outcomes is not None:
        for result_key in ("on_player_win", "on_player_loss"):
            outcome = _require_object(outcomes.get(result_key), issues, path, f"$.outcomes.{result_key}")
            if outcome is not None:
                _validate_operation_list(outcome.get("actions"), issues, path, f"$.outcomes.{result_key}.actions", content_id, dialogue_targets)

    if dialogue_entry and dialogue_entry not in dialogue_ids:
        _issue(issues, "error", path, "$.dialogue.entry", f"존재하지 않는 대화 ID: {dialogue_entry}")
    for action_path, target in dialogue_targets:
        if target not in dialogue_ids:
            _issue(issues, "error", path, f"{action_path}.target", f"존재하지 않는 대화 ID: {target}")
    return content_id, issues


def validate_game_definitions_file(path: Path) -> list[Issue]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return issues
    root = _require_object(data, issues, path, "$")
    if root is None:
        return issues
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")
    seen_ids: set[str] = set()
    items = _require_list(root.get("items"), issues, path, "$.items")
    for index, value in enumerate(items or []):
        entry_path = f"$.items[{index}]"
        entry = _require_object(value, issues, path, entry_path)
        if entry is None:
            continue
        item_id = _resource_id(entry.get("id"), issues, path, f"{entry_path}.id")
        if item_id:
            if item_id in seen_ids:
                _issue(issues, "error", path, f"{entry_path}.id", f"중복 선언 ID: {item_id}")
            seen_ids.add(item_id)
        _resource_id(entry.get("base_item"), issues, path, f"{entry_path}.base_item")
        _localized_text(entry.get("display_name"), issues, path, f"{entry_path}.display_name")
        description = entry.get("description")
        if description is not None and (
            not isinstance(description, dict)
            or any(not isinstance(value, str) or value.strip() for value in description.values())
        ):
            _localized_text(description, issues, path, f"{entry_path}.description")
    variables = _require_list(root.get("variables"), issues, path, "$.variables")
    for index, value in enumerate(variables or []):
        entry_path = f"$.variables[{index}]"
        entry = _require_object(value, issues, path, entry_path)
        if entry is None:
            continue
        variable_id = _resource_id(entry.get("id"), issues, path, f"{entry_path}.id")
        if variable_id:
            if variable_id in seen_ids:
                _issue(issues, "error", path, f"{entry_path}.id", f"중복 선언 ID: {variable_id}")
            seen_ids.add(variable_id)
        if entry.get("scope") not in {"global", "player"}:
            _issue(issues, "error", path, f"{entry_path}.scope", "저장 범위는 global 또는 player여야 합니다.")
        value_type = entry.get("type")
        if value_type not in {"boolean", "integer", "string"}:
            _issue(issues, "error", path, f"{entry_path}.type", "자료형은 boolean, integer, string 중 하나여야 합니다.")
        default = entry.get("default")
        valid_default = (
            (value_type == "boolean" and isinstance(default, bool))
            or (value_type == "integer" and isinstance(default, int) and not isinstance(default, bool))
            or (value_type == "string" and isinstance(default, str))
        )
        if not valid_default:
            _issue(issues, "error", path, f"{entry_path}.default", "기본값은 선택한 자료형과 일치해야 합니다.")
        _localized_text(entry.get("display_name"), issues, path, f"{entry_path}.display_name")
        description = entry.get("description")
        if description is not None and (
            not isinstance(description, dict)
            or any(not isinstance(value, str) or value.strip() for value in description.values())
        ):
            _localized_text(description, issues, path, f"{entry_path}.description")
    return issues


def save_game_definitions(root: Path, data: Any) -> list[Issue]:
    target = root / "content" / "catalogs" / "game-definitions.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        candidate = Path(directory) / target.name
        candidate.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        issues = validate_game_definitions_file(candidate)
    if not any(issue.level == "error" for issue in issues):
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    return issues


MUSIC_CONTEXTS = ("tile", "road", "settlement", "battle", "gym")


def validate_music_catalog_file(path: Path) -> list[Issue]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"음악 카탈로그를 읽을 수 없습니다: {error}")
        return issues
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "음악 카탈로그 스키마 버전은 1입니다.")
        return issues
    tracks = _require_list(data.get("tracks"), issues, path, "$.tracks") or []
    track_ids: set[str] = set()
    for index, value in enumerate(tracks):
        entry = _require_object(value, issues, path, f"$.tracks[{index}]")
        track_id = entry.get("id") if entry else None
        if not isinstance(track_id, str) or not CHOICE_ID.fullmatch(track_id):
            _issue(issues, "error", path, f"$.tracks[{index}].id", "올바른 음악 ID가 아닙니다.")
        elif track_id in track_ids:
            _issue(issues, "error", path, f"$.tracks[{index}].id", f"중복 음악 ID: {track_id}")
        else:
            track_ids.add(track_id)
    defaults = _require_object(data.get("defaults"), issues, path, "$.defaults")
    if defaults is not None:
        for context in MUSIC_CONTEXTS:
            if defaults.get(context) not in track_ids:
                _issue(issues, "error", path, f"$.defaults.{context}", "활성 음악 목록에서 기본값을 선택해야 합니다.")
    return issues


def save_music_catalog(root: Path, data: Any) -> list[Issue]:
    data = copy.deepcopy(data)
    if isinstance(data, dict):
        data.pop("local_library", None)
    target = root / "content" / "catalogs" / "music-tracks.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        candidate = Path(directory) / target.name
        candidate.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        issues = validate_music_catalog_file(candidate)
    if not any(issue.level == "error" for issue in issues):
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    return issues


def _music_source_directory(project_root: Path, core_root: Path, catalog: dict[str, Any]) -> Path:
    relative = catalog.get("source", {}).get("local_directory")
    if not isinstance(relative, str):
        raise ValueError("음악 카탈로그의 로컬 음원 폴더가 올바르지 않습니다.")
    source = (core_root / relative).resolve()
    allowed = (core_root / "local-assets" / "music").resolve()
    try:
        source.relative_to(allowed)
    except ValueError as error:
        raise ValueError("로컬 음원 폴더는 local-assets/music 아래여야 합니다.") from error
    return source


def _automatic_music_track(source_file: str, used_ids: set[str]) -> dict[str, str]:
    stem = Path(source_file).stem
    slug = re.sub(r"[^a-z0-9]+", "_", stem.lower()).strip("_") or "track"
    digest = hashlib.sha1(source_file.encode("utf-8")).hexdigest()[:8]
    base_id = f"local.{slug}_{digest}"
    track_id = base_id
    suffix = 2
    while track_id in used_ids:
        track_id = f"{base_id}_{suffix}"
        suffix += 1
    used_ids.add(track_id)
    event_path = track_id.replace(".", "/")
    return {
        "id": track_id,
        "sound_event": f"music.{track_id}",
        "resource": f"music/{event_path}",
        "source_file": source_file,
        "category": "local",
        "usage": stem,
    }


def sync_local_music_catalog(project_root: Path, core_root: Path) -> tuple[dict[str, Any], int]:
    catalog_path = project_root / "content" / "catalogs" / "music-tracks.json"
    catalog = load_json(catalog_path)
    source = _music_source_directory(project_root, core_root, catalog)
    source.mkdir(parents=True, exist_ok=True)
    tracks = catalog.setdefault("tracks", [])
    registered = {
        str(track.get("source_file", "")).replace("\\", "/").casefold()
        for track in tracks if isinstance(track, dict)
    }
    used_ids = {
        str(track.get("id")) for track in tracks
        if isinstance(track, dict) and isinstance(track.get("id"), str)
    }
    additions: list[dict[str, str]] = []
    for path in sorted(source.rglob("*"), key=lambda value: value.as_posix().casefold()):
        if not path.is_file() or path.suffix.lower() != ".ogg":
            continue
        relative = path.relative_to(source).as_posix()
        if relative.casefold() in registered:
            continue
        additions.append(_automatic_music_track(relative, used_ids))
        registered.add(relative.casefold())
    if additions:
        tracks.extend(additions)
        temporary = catalog_path.with_suffix(".json.tmp")
        temporary.write_text(
            json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        temporary.replace(catalog_path)
    catalog["local_library"] = {
        "directory": str(source),
        "registered_ogg": len(tracks),
        "added": len(additions),
    }
    return catalog, len(additions)


def validate_music_references(root: Path) -> list[Issue]:
    issues: list[Issue] = []
    catalog_path = root / "content" / "catalogs" / "music-tracks.json"
    try:
        catalog = load_json(catalog_path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError):
        return issues
    track_ids = {
        track.get("id") for track in catalog.get("tracks", [])
        if isinstance(track, dict) and isinstance(track.get("id"), str)
    }

    def inspect(value: Any, path: Path, json_path: str = "$") -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                child_path = f"{json_path}.{key}"
                if key == "music_track" and child not in track_ids:
                    _issue(
                        issues, "error", path, child_path,
                        f"활성 음악 목록에 없는 음악 ID입니다: {child}",
                    )
                inspect(child, path, child_path)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                inspect(child, path, f"{json_path}[{index}]")

    candidates = [root / "content" / "catalogs" / "gyms.json"]
    for directory in (
        root / "content" / "worlds",
        root / "content" / "settlements",
        root / "content" / "battles",
    ):
        if directory.is_dir():
            candidates.extend(sorted(directory.rglob("*.json")))
    for path in candidates:
        try:
            inspect(load_json(path), path)
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            continue
    return issues


ECONOMY_VENDOR_ROLES = {
    "pokemart_shopkeeper": "프렌들리숍 판매원",
    "shopkeeper_ds_apricorns": "규토리·씨앗 판매원",
    "shopkeeper_ds_battle_items": "배틀 아이템 판매원",
    "shopkeeper_ds_ev-stone": "진화의 돌 판매원",
    "shopkeeper_ds_ev-stone_2": "진화 아이템 판매원",
    "shopkeeper_ds_food": "식품 판매원",
    "shopkeeper_ds_general": "종합 판매원",
    "shopkeeper_ds_held_items": "지닌물건 판매원",
    "shopkeeper_ds_held_items_2": "전략 아이템 판매원",
    "shopkeeper_ds_mulch": "비료 판매원",
    "shopkeeper_ds_special_balls": "특수 볼 판매원",
    "shopkeeper_ds_tech": "포켓몬 기기 판매원",
    "shopkeeper_ds_vitamins": "영양제 판매원",
    "shopkeeper_ds_xp": "경험치 아이템 판매원",
    "shopkeeper_potions": "포션 판매원",
    "store_worker_currency-exchange": "환전상",
}
ECONOMY_CATEGORY_NAMES_KO = {
    "Pokeballs": "몬스터볼", "Pokéballs": "몬스터볼", "Combat": "배틀 도구",
    "Healing": "회복", "Treatments": "회복약", "Remedies": "상태 회복",
    "Apricorns": "규토리", "Seeds": "씨앗", "Boosts": "능력 강화",
    "Evolution Stones": "진화의 돌", "Evo Items": "진화 아이템", "Food": "식품",
    "Held Items": "지닌물건", "Security": "전략 아이템", "Mulch": "비료",
    "Pokédex": "포켓몬 도감", "PokeFinder": "포켓파인더", "PokéNav": "포켓내비",
    "Vitamins": "영양제", "Experience": "경험치", "Ingredients": "재료",
    "Potions": "포션", "Drinks": "음료", "Relic Coins": "유물 주화", "Minecraft": "마인크래프트",
}

DEFAULT_DEPARTMENT_STORE_VENDOR_IDS = [
    "bca:shopkeeper_ds_vitamins", "bca:shopkeeper_ds_battle_items",
    "bca:shopkeeper_ds_tech", "bca:shopkeeper_ds_general",
    "bca:shopkeeper_ds_special_balls", "bca:shopkeeper_ds_food",
    "bca:store_worker_currency-exchange", "bca:shopkeeper_ds_held_items_2",
    "bca:shopkeeper_ds_ev-stone", "bca:shopkeeper_ds_ev-stone_2",
    "bca:shopkeeper_ds_held_items", "bca:shopkeeper_ds_xp",
    "bca:shopkeeper_ds_apricorns", "bca:shopkeeper_ds_mulch",
]
DEFAULT_DEPARTMENT_STORE_SLOTS = [
    ("1f_left_a", "1층 왼쪽 A"), ("1f_left_b", "1층 왼쪽 B"),
    ("1f_center_a", "1층 중앙 A"), ("1f_center_b", "1층 중앙 B"),
    ("1f_center_c", "1층 중앙 C"), ("1f_right", "1층 오른쪽"),
    ("2f_left", "2층 왼쪽"), ("2f_center_a", "2층 중앙 A"),
    ("2f_center_b", "2층 중앙 B"), ("2f_center_c", "2층 중앙 C"),
    ("2f_right_a", "2층 오른쪽 A"), ("2f_right_b", "2층 오른쪽 B"),
    ("3f_left", "3층 왼쪽"), ("3f_center", "3층 중앙"),
]


@functools.lru_cache(maxsize=8)
def _economy_vendor_units_from_bca(
    root: Path, core_root: Path | None = None
) -> list[dict[str, Any]]:
    source_root = (core_root or root).resolve()
    mods = source_root / "pack" / "overrides" / "development-placeholder" / "mods"
    jars = sorted(mods.glob("cobblemon-additions-*.jar")) if mods.exists() else []
    if not jars:
        return []
    vendors: list[dict[str, Any]] = []
    prefix = "data/bca/structure/stores/store_workers/"
    with zipfile.ZipFile(jars[-1]) as archive:
        for member in sorted(archive.namelist()):
            if not member.startswith(prefix) or not member.endswith(".nbt"):
                continue
            stem = Path(member).stem
            if stem == "nurse_joy":
                continue
            structure = _read_minecraft_structure_root(archive.read(member))
            merchant = next((
                entity.get("nbt", {}) for entity in structure.get("entities", [])
                if isinstance(entity, dict)
                and isinstance(entity.get("nbt"), dict)
                and isinstance(entity["nbt"].get("CobbleMerchantShop"), list)
            ), None)
            if not merchant:
                continue
            categories: list[dict[str, Any]] = []
            for category in merchant.get("CobbleMerchantShop", []):
                if not isinstance(category, dict) or not isinstance(category.get("Offers"), list):
                    continue
                offers = []
                for offer in category["Offers"]:
                    stack = offer.get("Item", {}) if isinstance(offer, dict) else {}
                    if not isinstance(stack, dict) or not isinstance(stack.get("id"), str):
                        continue
                    offers.append({
                        "item": stack["id"],
                        "count": int(stack.get("count", 1)),
                        "price": str(offer.get("Price", "0")),
                    })
                category_name = str(category.get("Category", "Other"))
                categories.append({"name": {"ko_kr": ECONOMY_CATEGORY_NAMES_KO.get(category_name, category_name), "en_us": category_name}, "offers": offers})
            english_name = str(merchant.get("CustomName", stem)).strip('"')
            korean_name = ECONOMY_VENDOR_ROLES.get(stem, english_name)
            vendors.append({
                "id": f"bca:{stem}",
                "facility_scope": "pokemart" if stem == "pokemart_shopkeeper" else "department_store",
                "role": {"ko_kr": korean_name, "en_us": english_name},
                "display_name": {"ko_kr": korean_name, "en_us": english_name},
                "npc_template": f"bca:{stem}",
                "categories": categories,
                "origin": "cobblemon_additions",
                "source": member,
            })
    return vendors


def _cobblemon_species_root(root: Path) -> Path | None:
    candidates = [
        root / ".tmp" / "cobblemon-1.7.3-source" / "common" / "src" / "main" / "resources" / "data" / "cobblemon" / "species",
        root / ".tmp" / "cobblemon-1.7.3-full" / "cobblemon-1.7.3" / "common" / "src" / "main" / "resources" / "data" / "cobblemon" / "species",
    ]
    return next((candidate for candidate in candidates if candidate.exists()), None)


@functools.lru_cache(maxsize=4)
def _economy_pokemon_drops_from_cobblemon(root: Path) -> list[dict[str, Any]]:
    species_root = _cobblemon_species_root(root)
    if species_root is None:
        return []
    catalog = []
    for path in sorted(species_root.rglob("*.json")):
        try:
            species = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(species, dict):
            continue
        drops = species.get("drops") if isinstance(species.get("drops"), dict) else {"amount": 0, "entries": []}
        species_id = species.get("name")
        if not isinstance(species_id, str) or not species_id:
            species_id = path.stem
        if ":" not in species_id:
            species_id = f"cobblemon:{species_id.lower()}"
        catalog.append({
            "species": species_id.lower(),
            "display_name": species.get("name", path.stem),
            "national_dex": species.get("nationalPokedexNumber"),
            "types": [value for value in (species.get("primaryType"), species.get("secondaryType")) if isinstance(value, str)],
            "generation": next((int(label[3:]) for label in species.get("labels", []) if re.fullmatch(r"gen[1-9]", label)), None),
            "labels": copy.deepcopy(species.get("labels", [])),
            "egg_groups": copy.deepcopy(species.get("eggGroups", [])),
            "forms": [form.get("name") for form in species.get("forms", []) if isinstance(form, dict) and isinstance(form.get("name"), str)],
            "height": species.get("height", 0),
            "weight": species.get("weight", 0),
            "amount": drops.get("amount", 0),
            "entries": copy.deepcopy(drops.get("entries", [])),
            "origin": "cobblemon",
            "source": path.relative_to(species_root).as_posix(),
        })
    return catalog


@functools.lru_cache(maxsize=4)
def _economy_localizations(root: Path) -> tuple[dict[str, str], dict[str, str]]:
    ko: dict[str, str] = {}
    en: dict[str, str] = {}
    language_paths = [
        root / ".tmp" / "cobblemon-1.7.3-source" / "common" / "src" / "main" / "resources" / "assets" / "cobblemon" / "lang",
    ]
    for directory in language_paths:
        for locale, target in (("ko_kr", ko), ("en_us", en)):
            path = directory / f"{locale}.json"
            if path.exists():
                try:
                    values = json.loads(path.read_text(encoding="utf-8"))
                    target.update({key: value for key, value in values.items() if isinstance(value, str)})
                except (OSError, json.JSONDecodeError):
                    pass
    asset_roots = [Path.home() / ".gradle" / "caches" / "neoformruntime" / "assets"]
    app_data = os.environ.get("APPDATA")
    if app_data:
        asset_roots.append(Path(app_data) / ".minecraft" / "assets")
    minecraft_ko_loaded = False
    for assets in asset_roots:
        indexes = assets / "indexes"
        objects = assets / "objects"
        if not indexes.exists() or not objects.exists():
            continue
        for index in sorted(indexes.glob("*.json"), reverse=True):
            try:
                metadata = json.loads(index.read_text(encoding="utf-8"))
                asset = metadata.get("objects", {}).get("minecraft/lang/ko_kr.json", {})
                digest = asset.get("hash")
                if not isinstance(digest, str) or len(digest) < 2:
                    continue
                values = json.loads((objects / digest[:2] / digest).read_text(encoding="utf-8"))
                ko.update({key: value for key, value in values.items() if isinstance(value, str)})
                minecraft_ko_loaded = True
                break
            except (OSError, json.JSONDecodeError, AttributeError):
                continue
        if minecraft_ko_loaded:
            break
    mods = root / "pack" / "overrides" / "development-placeholder" / "mods"
    minecraft_resources = root / "projects" / "cobbleventure-world-bootstrap" / "build" / "moddev" / "artifacts"
    language_jars = list(sorted(minecraft_resources.glob("*client-extra*.jar"))) if minecraft_resources.exists() else []
    language_jars.extend(sorted(mods.glob("*.jar")) if mods.exists() else [])
    for jar in language_jars:
        try:
            with zipfile.ZipFile(jar) as archive:
                for member in archive.namelist():
                    match = re.fullmatch(r"assets/[^/]+/lang/(ko_kr|en_us)\.json", member)
                    if not match:
                        continue
                    values = json.loads(archive.read(member).decode("utf-8"))
                    (ko if match.group(1) == "ko_kr" else en).update(
                        {key: value for key, value in values.items() if isinstance(value, str)}
                    )
        except (OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError):
            continue
    return ko, en


def _economy_editor_catalog(root: Path, species: list[dict[str, Any]]) -> dict[str, Any]:
    ko, en = _economy_localizations(root)
    tag_definitions: dict[str, list[Any]] = {}

    def add_tag_document(tag_id: str, document: Any) -> None:
        if not isinstance(document, dict) or not isinstance(document.get("values"), list):
            return
        if document.get("replace") is True:
            tag_definitions[tag_id] = list(document["values"])
        else:
            tag_definitions.setdefault(tag_id, []).extend(document["values"])

    data_roots = [
        root / ".tmp" / "cobblemon-1.7.3-source" / "common" / "src" / "main" / "resources" / "data",
        root / ".tmp" / "cobblemon-1.7.3-full" / "cobblemon-1.7.3" / "common" / "src" / "main" / "resources" / "data",
        root / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources" / "data",
        root / "projects" / "cobbleventure-world-bootstrap" / "src" / "generated" / "resources" / "data",
    ]
    for data_root in data_roots:
        if not data_root.is_dir():
            continue
        for tag_path in data_root.glob("*/tags/item/**/*.json"):
            relative = tag_path.relative_to(data_root)
            tag_id = f"{relative.parts[0]}:{Path(*relative.parts[3:]).with_suffix('').as_posix()}"
            try:
                add_tag_document(tag_id, json.loads(tag_path.read_text(encoding="utf-8")))
            except (OSError, json.JSONDecodeError):
                continue
    mods = root / "pack" / "overrides" / "development-placeholder" / "mods"
    for jar in sorted(mods.glob("*.jar")) if mods.is_dir() else []:
        try:
            with zipfile.ZipFile(jar) as archive:
                for member in archive.namelist():
                    match = re.fullmatch(r"data/([^/]+)/tags/item/(.+)\.json", member)
                    if not match:
                        continue
                    add_tag_document(
                        f"{match.group(1)}:{match.group(2)}",
                        json.loads(archive.read(member).decode("utf-8")),
                    )
        except (OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError):
            continue

    resolved_tags: dict[str, set[str]] = {}

    def resolve_tag(tag_id: str, resolving: set[str] | None = None) -> set[str]:
        if tag_id in resolved_tags:
            return resolved_tags[tag_id]
        resolving = set() if resolving is None else resolving
        if tag_id in resolving:
            return set()
        resolving.add(tag_id)
        namespace = tag_id.split(":", 1)[0]
        result: set[str] = set()
        for entry in tag_definitions.get(tag_id, []):
            value = entry if isinstance(entry, str) else entry.get("id") if isinstance(entry, dict) else None
            if not isinstance(value, str) or not value:
                continue
            if value.startswith("#"):
                nested = value[1:]
                result.update(resolve_tag(nested if ":" in nested else f"{namespace}:{nested}", resolving))
            elif ":" in value:
                result.add(value)
        resolving.remove(tag_id)
        resolved_tags[tag_id] = result
        return result

    item_tags: dict[str, set[str]] = {}
    for tag_id in tag_definitions:
        for item_id in resolve_tag(tag_id):
            item_tags.setdefault(item_id, set()).add(tag_id)
    shop_group_tags = {
        "gems": "cobbleventure:shop/type_gems",
        "machines": "cobbleventure:shop/technical_machines",
        "balls": "cobbleventure:shop/balls",
        "medicine": "cobbleventure:shop/medicine",
        "battle": "cobbleventure:shop/battle_items",
        "evolution": "cobbleventure:shop/evolution_items",
        "held": "cobbleventure:shop/held_items",
        "berries": "cobbleventure:shop/berries",
        "food": "cobbleventure:shop/food",
        "materials": "cobbleventure:shop/materials",
    }
    resource_item_ids: set[str] = set()
    resource_namespaces: set[str] = set()
    resource_roots = [
        root / ".tmp" / "cobblemon-1.7.3-source" / "common" / "src" / "main" / "resources" / "assets",
        root / ".tmp" / "cobblemon-1.7.3-full" / "cobblemon-1.7.3" / "common" / "src" / "main" / "resources" / "assets",
    ]
    for assets in resource_roots:
        if not assets.is_dir():
            continue
        for namespace in assets.iterdir():
            if not namespace.is_dir():
                continue
            item_roots = (namespace / "items", namespace / "models" / "item")
            if not any(item_root.is_dir() for item_root in item_roots):
                continue
            resource_namespaces.add(namespace.name)
            for item_root in item_roots:
                if not item_root.is_dir():
                    continue
                for path in item_root.rglob("*.json"):
                    resource_item_ids.add(f"{namespace.name}:{path.relative_to(item_root).with_suffix('').as_posix()}")
    items: dict[str, dict[str, Any]] = {}
    for key in set(ko) | set(en):
        match = re.fullmatch(r"item\.([a-z0-9_-]+)\.([a-z0-9_/-]+)", key)
        if not match:
            continue
        item_id = f"{match.group(1)}:{match.group(2)}"
        if match.group(1) in resource_namespaces and item_id not in resource_item_ids:
            continue
        tags = item_tags.get(item_id, set())
        product_group = next((group for group, tag_id in shop_group_tags.items() if item_id in resolve_tag(tag_id)), "other")
        items[item_id] = {
            "id": item_id,
            "ko_kr": ko.get(key, en.get(key, item_id)),
            "en_us": en.get(key, ko.get(key, item_id)),
            "product_group": product_group,
            "tags": sorted(tags),
        }
    localized_species = []
    for entry in species:
        slug = entry["species"].split(":", 1)[-1]
        key = f"cobblemon.species.{slug}.name"
        localized_species.append({**entry, "ko_kr": ko.get(key, entry.get("display_name", slug)), "en_us": en.get(key, entry.get("display_name", slug))})
    return {
        "items": sorted(items.values(), key=lambda entry: (entry["ko_kr"], entry["id"])),
        "species": localized_species,
        "filters": {
            "types": sorted({value for entry in species for value in entry.get("types", [])}),
            "generations": sorted({value for entry in species if (value := entry.get("generation")) is not None}),
            "labels": sorted({value for entry in species for value in entry.get("labels", [])}),
            "egg_groups": sorted({value for entry in species for value in entry.get("egg_groups", [])}),
            "forms": sorted({value for entry in species for value in entry.get("forms", [])}),
        },
    }


def load_economy_workspace(
    root: Path, core_root: Path | None = None
) -> dict[str, Any]:
    catalog = load_json(root / "content" / "catalogs" / "economy.json")
    built_in_vendors = _economy_vendor_units_from_bca(root, core_root)
    custom_vendors = catalog.get("vendor_units", []) if isinstance(catalog, dict) else []
    vendor_by_id = {vendor["id"]: vendor for vendor in built_in_vendors}
    for vendor in custom_vendors:
        if isinstance(vendor, dict) and isinstance(vendor.get("id"), str):
            vendor_by_id[vendor["id"]] = {**copy.deepcopy(vendor), "origin": "custom"}
    standard_price_by_item: dict[str, dict[str, str]] = {}
    for vendor in built_in_vendors:
        for category in vendor.get("categories", []):
            for offer in category.get("offers", []):
                item_id = offer.get("item")
                price = offer.get("price")
                if isinstance(item_id, str) and isinstance(price, str) and price.strip():
                    standard_price_by_item.setdefault(item_id, {"item": item_id, "price": price})
    for standard_price in catalog.get("standard_prices", []):
        if isinstance(standard_price, dict) and isinstance(standard_price.get("item"), str):
            standard_price_by_item[standard_price["item"]] = copy.deepcopy(standard_price)
    built_in_catalogs = [
        {
            "id": "cobbleventure:shop_catalog/pokemart_default",
            "display_name": {"ko_kr": "기본 프렌들리숍", "en_us": "Default Poké Mart"},
            "facility_scope": "pokemart",
            "vendor_units": ["bca:pokemart_shopkeeper"],
            "assignments": [{"slot_id": "counter", "display_name": {"ko_kr": "카운터", "en_us": "Counter"}, "vendor_unit": "bca:pokemart_shopkeeper"}],
            "origin": "built_in",
        },
        {
            "id": "cobbleventure:shop_catalog/department_store_default",
            "display_name": {"ko_kr": "기본 백화점", "en_us": "Default Department Store"},
            "facility_scope": "department_store",
            "vendor_units": DEFAULT_DEPARTMENT_STORE_VENDOR_IDS,
            "assignments": [
                {"slot_id": slot_id, "display_name": {"ko_kr": display_name, "en_us": slot_id.replace("_", " ").upper()}, "vendor_unit": vendor_id}
                for (slot_id, display_name), vendor_id in zip(DEFAULT_DEPARTMENT_STORE_SLOTS, DEFAULT_DEPARTMENT_STORE_VENDOR_IDS)
            ],
            "origin": "built_in",
        },
    ]
    catalog_by_id = {entry["id"]: entry for entry in built_in_catalogs}
    for shop_catalog in catalog.get("shop_catalogs", []):
        if isinstance(shop_catalog, dict) and isinstance(shop_catalog.get("id"), str):
            catalog_by_id[shop_catalog["id"]] = {**copy.deepcopy(shop_catalog), "origin": "custom"}
    base_drops = _economy_pokemon_drops_from_cobblemon(root)
    editor_catalog = _economy_editor_catalog(root, base_drops)
    base_drops = editor_catalog["species"]
    drop_by_species = {entry["species"]: entry for entry in base_drops}
    for rule in sorted(
        (entry for entry in catalog.get("pokemon_drop_rules", []) if entry.get("enabled") is True),
        key=lambda entry: (int(entry.get("priority", 0)), str(entry.get("id", ""))),
    ):
        for species_id, existing in list(drop_by_species.items()):
            if not _economy_rule_matches(rule, existing):
                continue
            updated = copy.deepcopy(existing)
            if rule.get("mode") == "replace":
                updated["amount"] = rule["amount"]
                updated["entries"] = copy.deepcopy(rule["entries"])
            else:
                updated["amount"] = rule["amount"]
                by_item = {entry.get("item"): entry for entry in updated.get("entries", []) if isinstance(entry, dict)}
                for drop_entry in rule["entries"]:
                    by_item[drop_entry["item"]] = copy.deepcopy(drop_entry)
                updated["entries"] = list(by_item.values())
            updated["origin"] = "rule"
            updated["applied_rules"] = [*updated.get("applied_rules", []), rule["id"]]
            drop_by_species[species_id] = updated
    for override in catalog.get("pokemon_drop_overrides", []):
        if isinstance(override, dict) and isinstance(override.get("species"), str):
            existing = drop_by_species.get(override["species"], {})
            drop_by_species[override["species"]] = {
                **existing, **copy.deepcopy(override), "origin": "override",
                "display_name": existing.get("display_name", override["species"].split(":")[-1]),
            }
    return {
        **catalog,
        "resolved_shop_catalogs": sorted(catalog_by_id.values(), key=lambda entry: str(entry.get("display_name", ""))),
        "resolved_vendor_units": sorted(vendor_by_id.values(), key=lambda entry: (entry.get("facility_scope", ""), str(entry.get("role", "")))),
        "resolved_standard_prices": sorted(standard_price_by_item.values(), key=lambda entry: entry["item"]),
        "resolved_pokemon_drops": sorted(drop_by_species.values(), key=lambda entry: entry.get("species", "")),
        "editor_catalog": editor_catalog,
        "source_status": {
            "cobblemon_additions_vendors": len(built_in_vendors),
            "cobblemon_species_drops": len(base_drops),
        },
    }


def _economy_rule_matches(rule: dict[str, Any], species: dict[str, Any]) -> bool:
    match = rule.get("match", {})
    checks = (
        ("species", species.get("species")),
        ("types", species.get("types", [])),
        ("generations", species.get("generation")),
        ("labels", species.get("labels", [])),
        ("egg_groups", species.get("egg_groups", [])),
        ("forms", species.get("forms", [])),
    )
    for key, actual in checks:
        expected = match.get(key, [])
        if not expected:
            continue
        if isinstance(actual, list):
            if not set(expected).intersection(actual):
                return False
        elif actual not in expected:
            return False
    size = match.get("size", "any")
    if size != "any":
        height = float(species.get("height", 0) or 0)
        actual_size = "tiny" if height <= 3 else "small" if height <= 8 else "medium" if height <= 16 else "large" if height <= 30 else "giant"
        if size != actual_size:
            return False
    return True


def _write_economy_species_overrides(root: Path, catalog: dict[str, Any]) -> list[Issue]:
    issues: list[Issue] = []
    source_root = _cobblemon_species_root(root)
    if source_root is None:
        if catalog.get("pokemon_drop_rules") or catalog.get("pokemon_drop_overrides"):
            _issue(issues, "warning", root / "content/catalogs/economy.json", "$.pokemon_drop_rules", "Cobblemon 종족 원본을 찾지 못해 인게임 루트 테이블 생성은 건너뜁니다.")
        return issues
    output_root = root / "projects/cobbleventure-world-bootstrap/src/generated/resources/data/cobblemon/species"
    manifest_path = output_root / ".cobbleventure-economy-manifest.json"
    previous: list[str] = []
    if manifest_path.exists():
        try:
            previous = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            previous = []
    species_catalog = _economy_pokemon_drops_from_cobblemon(root)
    rules = sorted(
        (rule for rule in catalog.get("pokemon_drop_rules", []) if rule.get("enabled") is True),
        key=lambda rule: (int(rule.get("priority", 0)), str(rule.get("id", ""))),
    )
    explicit = {entry["species"]: entry for entry in catalog.get("pokemon_drop_overrides", [])}
    written: list[str] = []
    for species in species_catalog:
        matched = [rule for rule in rules if _economy_rule_matches(rule, species)]
        override = explicit.get(species["species"])
        if not matched and override is None:
            continue
        source_path = source_root / species["source"]
        document = json.loads(source_path.read_text(encoding="utf-8"))
        drops = copy.deepcopy(document.get("drops", {"amount": 0, "entries": []}))
        for rule in matched:
            if rule.get("mode") == "replace":
                drops = {"amount": rule["amount"], "entries": copy.deepcopy(rule["entries"])}
            else:
                drops["amount"] = rule["amount"]
                by_item = {entry.get("item"): entry for entry in drops.get("entries", []) if isinstance(entry, dict)}
                for entry in rule["entries"]:
                    by_item[entry["item"]] = copy.deepcopy(entry)
                drops["entries"] = list(by_item.values())
        if override is not None:
            drops = {"amount": override["amount"], "entries": copy.deepcopy(override["entries"])}
        document["drops"] = drops
        relative = species["source"]
        target = output_root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
        written.append(relative)
    for relative in previous:
        if relative not in written:
            stale = output_root / relative
            if stale.is_file():
                stale.unlink()
    output_root.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(written, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return issues


def validate_economy_catalog_file(path: Path, known_drop_items: set[str] | None = None) -> list[Issue]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return issues
    root = _require_object(data, issues, path, "$")
    if root is None:
        return issues
    if root.get("schema_version") != 2:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 2입니다.")
    if root.get("vanilla_crafting_disabled") is not True:
        _issue(issues, "error", path, "$.vanilla_crafting_disabled", "바닐라 조합법 비활성화가 true여야 합니다.")

    def require_localized(value: Any, field_path: str, label: str) -> None:
        if isinstance(value, str) and value.strip():
            return
        if isinstance(value, dict) and all(isinstance(value.get(locale), str) and value[locale].strip() for locale in ("ko_kr", "en_us")):
            return
        _issue(issues, "error", path, field_path, f"{label}의 한국어와 영어 이름이 필요합니다.")

    standard_prices = _require_list(root.get("standard_prices", []), issues, path, "$.standard_prices")
    seen_standard_items: set[str] = set()
    for index, value in enumerate(standard_prices or []):
        entry_path = f"$.standard_prices[{index}]"
        standard_price = _require_object(value, issues, path, entry_path)
        if standard_price is None:
            continue
        item_id = _resource_id(standard_price.get("item"), issues, path, f"{entry_path}.item")
        if item_id in seen_standard_items:
            _issue(issues, "error", path, f"{entry_path}.item", f"중복 표준 가격 아이템: {item_id}")
        if item_id:
            seen_standard_items.add(item_id)
        if not isinstance(standard_price.get("price"), str) or not standard_price["price"].strip():
            _issue(issues, "error", path, f"{entry_path}.price", "표준 가격 문자열이 필요합니다.")

    seen_ids: set[str] = set()
    shop_catalogs = _require_list(root.get("shop_catalogs"), issues, path, "$.shop_catalogs")
    for index, value in enumerate(shop_catalogs or []):
        entry_path = f"$.shop_catalogs[{index}]"
        shop_catalog = _require_object(value, issues, path, entry_path)
        if shop_catalog is None:
            continue
        catalog_id = _resource_id(shop_catalog.get("id"), issues, path, f"{entry_path}.id")
        if catalog_id:
            if catalog_id in seen_ids:
                _issue(issues, "error", path, f"{entry_path}.id", f"중복 경제 콘텐츠 ID: {catalog_id}")
            seen_ids.add(catalog_id)
        require_localized(shop_catalog.get("display_name"), f"{entry_path}.display_name", "상점 카탈로그")
        if shop_catalog.get("facility_scope") not in {"pokemart", "department_store", "specialty"}:
            _issue(issues, "error", path, f"{entry_path}.facility_scope", "상점 카탈로그 사용 시설이 올바르지 않습니다.")
        catalog_vendors = _require_list(shop_catalog.get("vendor_units"), issues, path, f"{entry_path}.vendor_units")
        for vendor_index, vendor_id in enumerate(catalog_vendors or []):
            _resource_id(vendor_id, issues, path, f"{entry_path}.vendor_units[{vendor_index}]")
        assignments = _require_list(shop_catalog.get("assignments"), issues, path, f"{entry_path}.assignments")
        if assignments is not None and not assignments:
            _issue(issues, "error", path, f"{entry_path}.assignments", "백화점 위치에 배정한 상인이 하나 이상 필요합니다.")
        seen_slots: set[str] = set()
        for assignment_index, assignment_value in enumerate(assignments or []):
            assignment_path = f"{entry_path}.assignments[{assignment_index}]"
            assignment = _require_object(assignment_value, issues, path, assignment_path)
            if assignment is None:
                continue
            slot_id = assignment.get("slot_id")
            if not isinstance(slot_id, str) or not CHOICE_ID.fullmatch(slot_id):
                _issue(issues, "error", path, f"{assignment_path}.slot_id", "백화점 위치 ID 형식이 올바르지 않습니다.")
            elif slot_id in seen_slots:
                _issue(issues, "error", path, f"{assignment_path}.slot_id", f"중복 백화점 위치: {slot_id}")
            else:
                seen_slots.add(slot_id)
            require_localized(assignment.get("display_name"), f"{assignment_path}.display_name", "백화점 위치")
            _resource_id(assignment.get("vendor_unit"), issues, path, f"{assignment_path}.vendor_unit")
    vendors = _require_list(root.get("vendor_units"), issues, path, "$.vendor_units")
    for index, value in enumerate(vendors or []):
        entry_path = f"$.vendor_units[{index}]"
        vendor = _require_object(value, issues, path, entry_path)
        if vendor is None:
            continue
        vendor_id = _resource_id(vendor.get("id"), issues, path, f"{entry_path}.id")
        if vendor_id:
            if vendor_id in seen_ids:
                _issue(issues, "error", path, f"{entry_path}.id", f"중복 경제 콘텐츠 ID: {vendor_id}")
            seen_ids.add(vendor_id)
        _resource_id(vendor.get("npc_template"), issues, path, f"{entry_path}.npc_template")
        facility = vendor.get("facility_scope")
        if facility not in {"pokemart", "department_store", "specialty"}:
            _issue(issues, "error", path, f"{entry_path}.facility_scope", "판매원 사용 범위가 올바르지 않습니다.")
        require_localized(vendor.get("role"), f"{entry_path}.role", "판매원 역할")
        require_localized(vendor.get("display_name"), f"{entry_path}.display_name", "판매 NPC")
        categories = _require_list(vendor.get("categories"), issues, path, f"{entry_path}.categories")
        seen_items: set[str] = set()
        for category_index, category_value in enumerate(categories or []):
            category_path = f"{entry_path}.categories[{category_index}]"
            category = _require_object(category_value, issues, path, category_path)
            if category is None:
                continue
            require_localized(category.get("name"), f"{category_path}.name", "판매 카테고리")
            offers = _require_list(category.get("offers"), issues, path, f"{category_path}.offers")
            for offer_index, offer_value in enumerate(offers or []):
                offer_path = f"{category_path}.offers[{offer_index}]"
                offer = _require_object(offer_value, issues, path, offer_path)
                if offer is None:
                    continue
                item_id = _resource_id(offer.get("item"), issues, path, f"{offer_path}.item")
                if item_id and item_id in seen_items:
                    _issue(issues, "error", path, f"{offer_path}.item", f"같은 판매원 단위의 중복 판매 아이템: {item_id}")
                if item_id:
                    seen_items.add(item_id)
                count = offer.get("count")
                if not isinstance(count, int) or isinstance(count, bool) or count < 1:
                    _issue(issues, "error", path, f"{offer_path}.count", "판매 수량은 1 이상 정수여야 합니다.")
                if not isinstance(offer.get("price"), str) or not offer["price"].strip():
                    _issue(issues, "error", path, f"{offer_path}.price", "CobbleDollars 가격 문자열이 필요합니다.")

    drop_items: set[str] = set(known_drop_items or set())
    rules = _require_list(root.get("pokemon_drop_rules"), issues, path, "$.pokemon_drop_rules")
    for index, value in enumerate(rules or []):
        entry_path = f"$.pokemon_drop_rules[{index}]"
        rule = _require_object(value, issues, path, entry_path)
        if rule is None:
            continue
        rule_id = _resource_id(rule.get("id"), issues, path, f"{entry_path}.id")
        if rule_id:
            if rule_id in seen_ids:
                _issue(issues, "error", path, f"{entry_path}.id", f"중복 경제 콘텐츠 ID: {rule_id}")
            seen_ids.add(rule_id)
        if not isinstance(rule.get("display_name"), str) or not rule["display_name"].strip():
            _issue(issues, "error", path, f"{entry_path}.display_name", "루트 규칙 이름이 필요합니다.")
        if not isinstance(rule.get("enabled"), bool):
            _issue(issues, "error", path, f"{entry_path}.enabled", "활성화 여부는 참/거짓이어야 합니다.")
        priority = rule.get("priority")
        if not isinstance(priority, int) or isinstance(priority, bool) or not -1000 <= priority <= 1000:
            _issue(issues, "error", path, f"{entry_path}.priority", "우선순위는 -1000~1000 정수여야 합니다.")
        match = _require_object(rule.get("match"), issues, path, f"{entry_path}.match")
        if match is not None and not any(isinstance(value, list) and value for value in match.values()) and match.get("size", "any") == "any":
            _issue(issues, "warning", path, f"{entry_path}.match", "조건이 없어 모든 포켓몬에게 적용됩니다.")
        if rule.get("mode") not in {"append", "replace"}:
            _issue(issues, "error", path, f"{entry_path}.mode", "적용 방식은 append 또는 replace여야 합니다.")
        amount = rule.get("amount")
        if not ((isinstance(amount, int) and not isinstance(amount, bool) and amount >= 0) or (isinstance(amount, str) and re.fullmatch(r"[0-9]+-[0-9]+", amount))):
            _issue(issues, "error", path, f"{entry_path}.amount", "드롭 amount는 0 이상 정수 또는 1-3 형식이어야 합니다.")
        entries = _require_list(rule.get("entries"), issues, path, f"{entry_path}.entries")
        for entry_index, entry_value in enumerate(entries or []):
            drop_entry_path = f"{entry_path}.entries[{entry_index}]"
            drop_entry = _require_object(entry_value, issues, path, drop_entry_path)
            if drop_entry is None:
                continue
            item_id = _resource_id(drop_entry.get("item"), issues, path, f"{drop_entry_path}.item")
            if item_id:
                drop_items.add(item_id)
            percentage = drop_entry.get("percentage")
            if not isinstance(percentage, (int, float)) or isinstance(percentage, bool) or not 0 < percentage <= 100:
                _issue(issues, "error", path, f"{drop_entry_path}.percentage", "확률은 0보다 크고 100 이하여야 합니다.")
    drops = _require_list(root.get("pokemon_drop_overrides"), issues, path, "$.pokemon_drop_overrides")
    for index, value in enumerate(drops or []):
        entry_path = f"$.pokemon_drop_overrides[{index}]"
        drop = _require_object(value, issues, path, entry_path)
        if drop is None:
            continue
        _resource_id(drop.get("species"), issues, path, f"{entry_path}.species")
        amount = drop.get("amount")
        if not ((isinstance(amount, int) and not isinstance(amount, bool) and amount >= 0) or (isinstance(amount, str) and re.fullmatch(r"[0-9]+-[0-9]+", amount))):
            _issue(issues, "error", path, f"{entry_path}.amount", "Cobblemon 드롭 amount는 0 이상 정수 또는 1-3 형식이어야 합니다.")
        entries = _require_list(drop.get("entries"), issues, path, f"{entry_path}.entries")
        for entry_index, entry_value in enumerate(entries or []):
            drop_entry_path = f"{entry_path}.entries[{entry_index}]"
            drop_entry = _require_object(entry_value, issues, path, drop_entry_path)
            if drop_entry is None:
                continue
            item_id = _resource_id(drop_entry.get("item"), issues, path, f"{drop_entry_path}.item")
            if item_id:
                drop_items.add(item_id)
            percentage = drop_entry.get("percentage")
            if not isinstance(percentage, (int, float)) or isinstance(percentage, bool) or not 0 < percentage <= 100:
                _issue(issues, "error", path, f"{drop_entry_path}.percentage", "Cobblemon percentage는 0보다 크고 100 이하여야 합니다.")

    recipes = _require_list(root.get("npc_recipes"), issues, path, "$.npc_recipes")
    for index, value in enumerate(recipes or []):
        entry_path = f"$.npc_recipes[{index}]"
        recipe = _require_object(value, issues, path, entry_path)
        if recipe is None:
            continue
        recipe_id = _resource_id(recipe.get("id"), issues, path, f"{entry_path}.id")
        if recipe_id:
            if recipe_id in seen_ids:
                _issue(issues, "error", path, f"{entry_path}.id", f"중복 경제 콘텐츠 ID: {recipe_id}")
            seen_ids.add(recipe_id)
        _resource_id(recipe.get("npc"), issues, path, f"{entry_path}.npc")
        if not isinstance(recipe.get("display_name"), str) or not recipe["display_name"].strip():
            _issue(issues, "error", path, f"{entry_path}.display_name", "제작법 표시 이름이 필요합니다.")
        if not isinstance(recipe.get("unlock_note"), str):
            _issue(issues, "error", path, f"{entry_path}.unlock_note", "해금 조건 설명은 문자열이어야 합니다.")
        output = _require_object(recipe.get("output"), issues, path, f"{entry_path}.output")
        if output is not None:
            _resource_id(output.get("item"), issues, path, f"{entry_path}.output.item")
            count = output.get("count")
            if not isinstance(count, int) or isinstance(count, bool) or count < 1:
                _issue(issues, "error", path, f"{entry_path}.output.count", "결과 수량은 1 이상 정수여야 합니다.")
        ingredients = _require_list(recipe.get("ingredients"), issues, path, f"{entry_path}.ingredients")
        if ingredients is not None and not ingredients:
            _issue(issues, "error", path, f"{entry_path}.ingredients", "포켓몬 드롭 재료가 하나 이상 필요합니다.")
        for ingredient_index, ingredient_value in enumerate(ingredients or []):
            ingredient_path = f"{entry_path}.ingredients[{ingredient_index}]"
            ingredient = _require_object(ingredient_value, issues, path, ingredient_path)
            if ingredient is None:
                continue
            ingredient_id = _resource_id(ingredient.get("item"), issues, path, f"{ingredient_path}.item")
            if ingredient_id and ingredient_id not in drop_items:
                _issue(issues, "error", path, f"{ingredient_path}.item", f"포켓몬 드롭으로 등록되지 않은 제작 재료입니다: {ingredient_id}")
            count = ingredient.get("count")
            if not isinstance(count, int) or isinstance(count, bool) or count < 1:
                _issue(issues, "error", path, f"{ingredient_path}.count", "재료 수량은 1 이상 정수여야 합니다.")
    return issues


def save_economy_catalog(root: Path, data: Any) -> list[Issue]:
    target = root / "content" / "catalogs" / "economy.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        candidate = Path(directory) / target.name
        persistent = copy.deepcopy(data) if isinstance(data, dict) else data
        if isinstance(persistent, dict):
            persistent.pop("resolved_shop_catalogs", None)
            persistent.pop("resolved_vendor_units", None)
            persistent.pop("resolved_standard_prices", None)
            persistent.pop("resolved_pokemon_drops", None)
            persistent.pop("source_status", None)
            persistent.pop("editor_catalog", None)
        candidate.write_text(json.dumps(persistent, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        source_items = {
            entry.get("item") for drop in _economy_pokemon_drops_from_cobblemon(root)
            for entry in drop.get("entries", []) if isinstance(entry, dict) and isinstance(entry.get("item"), str)
        }
        issues = validate_economy_catalog_file(candidate, source_items)
    if not any(issue.level == "error" for issue in issues):
        issues.extend(_write_economy_species_overrides(root, persistent))
    if not any(issue.level == "error" for issue in issues):
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(persistent, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    return issues


def validate_league_progression_file(
    path: Path, trainer_ids: set[str] | None = None
) -> tuple[set[str], list[Issue]]:
    issues: list[Issue] = []
    entry_ids: set[str] = set()
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return entry_ids, issues
    root = _require_object(data, issues, path, "$")
    if root is None:
        return entry_ids, issues
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")
    entries = _require_list(root.get("entries"), issues, path, "$.entries")
    if entries is None:
        return entry_ids, issues
    order_keys: set[tuple[int, str, int]] = set()
    for index, value in enumerate(entries):
        entry_path = f"$.entries[{index}]"
        entry = _require_object(value, issues, path, entry_path)
        if entry is None:
            continue
        entry_id = _resource_id(entry.get("id"), issues, path, f"{entry_path}.id")
        if entry_id:
            if entry_id in entry_ids:
                _issue(issues, "error", path, f"{entry_path}.id", f"중복 리그 항목 ID: {entry_id}")
            entry_ids.add(entry_id)
        role = entry.get("role")
        if role not in {"gym_leader", "elite_four", "champion"}:
            _issue(issues, "error", path, f"{entry_path}.role", "관장, 사천왕, 챔피언 중 하나여야 합니다.")
        primary_type = entry.get("primary_type")
        if primary_type not in {"normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"}:
            _issue(issues, "error", path, f"{entry_path}.primary_type", "올바른 포켓몬 주 속성이 필요합니다.")
        if entry.get("badge_id") is not None:
            _resource_id(entry.get("badge_id"), issues, path, f"{entry_path}.badge_id")
        _localized_text(entry.get("display_name"), issues, path, f"{entry_path}.display_name")
        generation = entry.get("generation")
        if not isinstance(generation, int) or isinstance(generation, bool) or not 1 <= generation <= 9:
            _issue(issues, "error", path, f"{entry_path}.generation", "세대는 1~9 정수여야 합니다.")
        region = _resource_id(entry.get("region"), issues, path, f"{entry_path}.region")
        order = entry.get("order")
        if not isinstance(order, int) or isinstance(order, bool) or not 1 <= order <= 99:
            _issue(issues, "error", path, f"{entry_path}.order", "표시 순서는 1~99 정수여야 합니다.")
        elif isinstance(generation, int) and region:
            order_key = (generation, region, order)
            if order_key in order_keys:
                _issue(issues, "error", path, f"{entry_path}.order", "같은 지역에서 표시 순서가 중복됩니다.")
            order_keys.add(order_key)
        level_cap = entry.get("level_cap")
        if not isinstance(level_cap, int) or isinstance(level_cap, bool) or not 1 <= level_cap <= 100:
            _issue(issues, "error", path, f"{entry_path}.level_cap", "레벨캡은 1~100 정수여야 합니다.")
        trainer_id = None
        if role == "gym_leader":
            encounter = _require_object(entry.get("encounter"), issues, path, f"{entry_path}.encounter")
            if encounter is not None:
                _resource_id(encounter.get("battle_id"), issues, path, f"{entry_path}.encounter.battle_id")
                appearance = _require_object(encounter.get("appearance"), issues, path, f"{entry_path}.encounter.appearance")
                if appearance is not None:
                    for field in ("source", "type"):
                        value = appearance.get(field)
                        if not isinstance(value, str) or not value.strip():
                            _issue(issues, "error", path, f"{entry_path}.encounter.appearance.{field}", "비어 있지 않은 문자열이 필요합니다.")
                    _resource_id(appearance.get("resource"), issues, path, f"{entry_path}.encounter.appearance.resource")
                    if appearance.get("texture") is not None:
                        _resource_id(appearance.get("texture"), issues, path, f"{entry_path}.encounter.appearance.texture")
                    if appearance.get("arm_model") not in {None, "wide", "slim", "classic"}:
                        _issue(issues, "error", path, f"{entry_path}.encounter.appearance.arm_model", "wide, slim, classic 중 하나여야 합니다.")
                dialogue = _require_object(encounter.get("dialogue"), issues, path, f"{entry_path}.encounter.dialogue")
                if dialogue is not None:
                    for field in ("challenge", "victory", "defeat", "cleared"):
                        value = dialogue.get(field)
                        lines = [value] if isinstance(value, str) else value
                        if not isinstance(lines, list) or not lines or any(not isinstance(line, str) or not line.strip() for line in lines):
                            _issue(issues, "error", path, f"{entry_path}.encounter.dialogue.{field}", "비어 있지 않은 대사가 한 줄 이상 필요합니다.")
                rewards = _require_object(encounter.get("rewards"), issues, path, f"{entry_path}.encounter.rewards")
                if rewards is not None:
                    money = rewards.get("money")
                    if not isinstance(money, int) or isinstance(money, bool) or money < 0:
                        _issue(issues, "error", path, f"{entry_path}.encounter.rewards.money", "상금은 0 이상의 정수여야 합니다.")
                    _resource_id(rewards.get("badge_id"), issues, path, f"{entry_path}.encounter.rewards.badge_id")
                    reward_item = rewards.get("item")
                    if reward_item is not None:
                        _resource_id(reward_item, issues, path, f"{entry_path}.encounter.rewards.item")
                        item_count = rewards.get("item_count", 1)
                        if not isinstance(item_count, int) or isinstance(item_count, bool) or not 1 <= item_count <= 999:
                            _issue(issues, "error", path, f"{entry_path}.encounter.rewards.item_count", "아이템 수량은 1~999 정수여야 합니다.")
        else:
            trainer_id = _resource_id(entry.get("trainer_id"), issues, path, f"{entry_path}.trainer_id")
            if trainer_ids is not None and trainer_id and trainer_id not in trainer_ids:
                _issue(issues, "error", path, f"{entry_path}.trainer_id", f"트레이너풀에 없는 NPC입니다: {trainer_id}")
        card_order = entry.get("trainer_card_order", order)
        if not isinstance(card_order, int) or isinstance(card_order, bool) or not 1 <= card_order <= 99:
            _issue(issues, "error", path, f"{entry_path}.trainer_card_order", "트레이너 카드 순서는 1~99 정수여야 합니다.")
        if not isinstance(entry.get("trainer_card_visible", True), bool):
            _issue(issues, "error", path, f"{entry_path}.trainer_card_visible", "boolean이어야 합니다.")
    return entry_ids, issues


def save_league_progression(root: Path, data: Any) -> list[Issue]:
    target = root / "content" / "catalogs" / "league-progression.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        candidate = Path(directory) / target.name
        candidate.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        trainer_ids = {
            document_id
            for item in _list_documents(root, "trainers")
            if isinstance((document_id := item.get("id")), str)
        }
        _, issues = validate_league_progression_file(candidate, trainer_ids)
    if not any(issue.level == "error" for issue in issues):
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    return issues


def validate_gym_catalog_file(path: Path, structure_root: Path | None = None) -> list[Issue]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return issues
    root = _require_object(data, issues, path, "$")
    if root is None:
        return issues
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")
    seen_ids: set[str] = set()
    gyms = _require_list(root.get("gyms"), issues, path, "$.gyms")
    for index, value in enumerate(gyms or []):
        gym_path = f"$.gyms[{index}]"
        gym = _require_object(value, issues, path, gym_path)
        if gym is None:
            continue
        gym_id = _resource_id(gym.get("id"), issues, path, f"{gym_path}.id")
        if gym_id:
            if gym_id in seen_ids:
                _issue(issues, "error", path, f"{gym_path}.id", f"중복 체육관 ID: {gym_id}")
            seen_ids.add(gym_id)
        if not isinstance(gym.get("enabled"), bool):
            _issue(issues, "error", path, f"{gym_path}.enabled", "참/거짓 값이어야 합니다.")
        _localized_text(gym.get("display_name"), issues, path, f"{gym_path}.display_name")
        theme = gym.get("theme")
        if not isinstance(theme, str) or not CHOICE_ID.fullmatch(theme):
            _issue(issues, "error", path, f"{gym_path}.theme", "소문자 타입 ID가 필요합니다.")
        exterior = _require_object(gym.get("exterior"), issues, path, f"{gym_path}.exterior")
        if exterior is not None:
            structure = _resource_id(exterior.get("structure"), issues, path, f"{gym_path}.exterior.structure")
            if structure and structure != "cobbleventure:gyms/base_gym":
                _issue(
                    issues, "error", path, f"{gym_path}.exterior.structure",
                    "모든 체육관 외관은 공용 cobbleventure:gyms/base_gym을 사용해야 합니다.",
                )
            if structure_root is not None and structure and structure.startswith("cobbleventure:"):
                relative = structure.split(":", 1)[1]
                if not (structure_root / f"{relative}.nbt").is_file():
                    _issue(issues, "error", path, f"{gym_path}.exterior.structure", f"NBT를 찾을 수 없습니다: {structure}")
        interior = _require_object(gym.get("interior"), issues, path, f"{gym_path}.interior")
        module_ids: set[str] = set()
        npc_anchors: set[str] = set()
        if interior is not None:
            modules = _require_list(interior.get("modules"), issues, path, f"{gym_path}.interior.modules")
            if modules is not None and not modules:
                _issue(issues, "error", path, f"{gym_path}.interior.modules", "내부 시작 모듈이 하나 이상 필요합니다.")
            for module_index, module_value in enumerate(modules or []):
                module_path = f"{gym_path}.interior.modules[{module_index}]"
                module = _require_object(module_value, issues, path, module_path)
                if module is None:
                    continue
                module_id = module.get("id")
                if not isinstance(module_id, str) or not DOCUMENT_SLUG.fullmatch(module_id) or module_id in module_ids:
                    _issue(issues, "error", path, f"{module_path}.id", "중복되지 않는 소문자 모듈 ID가 필요합니다.")
                else:
                    module_ids.add(module_id)
                module_structure = _resource_id(module.get("structure"), issues, path, f"{module_path}.structure")
                position = module.get("position")
                if not isinstance(position, list) or len(position) != 3 or any(not isinstance(axis, int) or isinstance(axis, bool) for axis in position):
                    _issue(issues, "error", path, f"{module_path}.position", "[x, y, z] 정수 좌표가 필요합니다.")
                if module.get("rotation", "none") not in {"none", "clockwise_90", "clockwise_180", "counterclockwise_90"}:
                    _issue(issues, "error", path, f"{module_path}.rotation", "지원하지 않는 회전입니다.")
                if structure_root is not None and module_structure and module_structure.startswith("cobbleventure:"):
                    relative = module_structure.split(":", 1)[1]
                    module_file = structure_root / f"{relative}.nbt"
                    if not module_file.is_file():
                        _issue(issues, "error", path, f"{module_path}.structure", f"내부 모듈 NBT를 찾을 수 없습니다: {module_structure}")
                    else:
                        metadata_file = module_file.with_suffix(".structure.json")
                        if not metadata_file.is_file():
                            _issue(issues, "error", path, f"{module_path}.structure", "내부 모듈 메타데이터가 필요합니다.")
                        else:
                            try:
                                metadata = load_json(metadata_file)
                                anchors = metadata.get("anchors", []) if isinstance(metadata, dict) else []
                                for anchor in anchors if isinstance(anchors, list) else []:
                                    if not isinstance(anchor, dict) or anchor.get("type") != "npc_position":
                                        continue
                                    label = anchor.get("label")
                                    if not isinstance(label, str) or not DOCUMENT_SLUG.fullmatch(label):
                                        continue
                                    if label in npc_anchors:
                                        _issue(issues, "error", path, f"{module_path}.structure", f"중복 NPC 앵커 라벨: {label}")
                                    npc_anchors.add(label)
                            except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                                _issue(issues, "error", path, f"{module_path}.structure", f"모듈 메타데이터를 읽을 수 없습니다: {error}")
            connections = interior.get("connections", [])
            if not isinstance(connections, list):
                _issue(issues, "error", path, f"{gym_path}.interior.connections", "연결 배열이 필요합니다.")
            for connection_index, connection in enumerate(connections if isinstance(connections, list) else []):
                connection_path = f"{gym_path}.interior.connections[{connection_index}]"
                if not isinstance(connection, dict):
                    _issue(issues, "error", path, connection_path, "연결 객체가 필요합니다.")
                    continue
                for endpoint in ("from", "to"):
                    value = connection.get(endpoint)
                    module_id = value.split(":", 1)[0] if isinstance(value, str) else ""
                    if module_id not in module_ids:
                        _issue(issues, "error", path, f"{connection_path}.{endpoint}", "존재하는 모듈의 앵커를 지정해야 합니다.")
        staff = _require_object(gym.get("staff"), issues, path, f"{gym_path}.staff")
        if staff is not None:
            leader = _require_object(staff.get("leader"), issues, path, f"{gym_path}.staff.leader")
            if leader is not None:
                for field in ("trainer_id", "league_entry_id", "badge_id"):
                    value = leader.get(field)
                    if value not in {None, ""}:
                        _resource_id(value, issues, path, f"{gym_path}.staff.leader.{field}")
                leader_anchor = leader.get("anchor", "leader")
                if not isinstance(leader_anchor, str) or not DOCUMENT_SLUG.fullmatch(leader_anchor):
                    _issue(issues, "error", path, f"{gym_path}.staff.leader.anchor", "소문자 NPC 앵커 라벨이 필요합니다.")
                elif (leader.get("trainer_id") or leader.get("league_entry_id")) and leader_anchor not in npc_anchors:
                    _issue(issues, "error", path, f"{gym_path}.staff.leader.anchor", f"내부 NBT에 NPC 앵커가 없습니다: {leader_anchor}")
            trainers = _require_list(staff.get("trainers"), issues, path, f"{gym_path}.staff.trainers")
            trainer_ids: set[str] = set()
            trainer_anchors: set[str] = {
                leader.get("anchor", "leader")
            } if isinstance(leader, dict) and leader.get("trainer_id") else set()
            for trainer_index, trainer_value in enumerate(trainers or []):
                trainer_path = f"{gym_path}.staff.trainers[{trainer_index}]"
                trainer = _require_object(trainer_value, issues, path, trainer_path)
                if trainer is None:
                    continue
                slot_id = trainer.get("id")
                if not isinstance(slot_id, str) or not DOCUMENT_SLUG.fullmatch(slot_id) or slot_id in trainer_ids:
                    _issue(issues, "error", path, f"{trainer_path}.id", "중복되지 않는 소문자 배치 ID가 필요합니다.")
                else:
                    trainer_ids.add(slot_id)
                _resource_id(trainer.get("trainer_id"), issues, path, f"{trainer_path}.trainer_id")
                anchor = trainer.get("anchor")
                if not isinstance(anchor, str) or not DOCUMENT_SLUG.fullmatch(anchor):
                    _issue(issues, "error", path, f"{trainer_path}.anchor", "소문자 NPC 앵커 라벨이 필요합니다.")
                elif anchor in trainer_anchors:
                    _issue(issues, "error", path, f"{trainer_path}.anchor", f"중복 트레이너 앵커: {anchor}")
                else:
                    trainer_anchors.add(anchor)
                    if anchor not in npc_anchors:
                        _issue(issues, "error", path, f"{trainer_path}.anchor", f"내부 NBT에 NPC 앵커가 없습니다: {anchor}")
    leagues = _require_list(root.get("leagues"), issues, path, "$.leagues")
    for index, value in enumerate(leagues or []):
        league_path = f"$.leagues[{index}]"
        league = _require_object(value, issues, path, league_path)
        if league is None:
            continue
        league_id = _resource_id(league.get("id"), issues, path, f"{league_path}.id")
        if league_id in seen_ids:
            _issue(issues, "error", path, f"{league_path}.id", f"중복 시설 ID: {league_id}")
        if league_id:
            seen_ids.add(league_id)
        _localized_text(league.get("display_name"), issues, path, f"{league_path}.display_name")
        structure = _resource_id(league.get("structure"), issues, path, f"{league_path}.structure")
        if structure_root is not None and structure and structure.startswith("cobbleventure:"):
            relative = structure.split(":", 1)[1]
            if not (structure_root / f"{relative}.nbt").is_file():
                _issue(issues, "error", path, f"{league_path}.structure", f"리그 NBT를 찾을 수 없습니다: {structure}")
    return issues


def save_gym_catalog(root: Path, data: Any) -> list[Issue]:
    target = root / "content" / "catalogs" / "gyms.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        candidate = Path(directory) / target.name
        candidate.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        issues = validate_gym_catalog_file(candidate, root / "content" / "structures")
    if not any(issue.level == "error" for issue in issues):
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    return issues


def create_gym(root: Path, slug: str, name: str, source_structure: str) -> tuple[dict[str, Any] | None, list[Issue]]:
    if not DOCUMENT_SLUG.fullmatch(slug):
        return None, [Issue("error", "content/catalogs/gyms.json", "$.slug", "파일 ID는 소문자, 숫자와 밑줄만 사용할 수 있습니다.")]
    if not name.strip():
        return None, [Issue("error", "content/catalogs/gyms.json", "$.name", "한국어 이름이 필요합니다.")]
    source = managed_structure_files(root).get(source_structure)
    if source is None or source.parent.name != "gyms":
        return None, [Issue("error", "content/catalogs/gyms.json", "$.source_structure", "기존 체육관 외관 NBT를 선택해야 합니다.")]
    gym_id = f"cobbleventure:gym/{slug}"
    catalog = load_json(root / "content" / "catalogs" / "gyms.json")
    gyms = catalog.get("gyms", []) if isinstance(catalog, dict) else []
    if any(isinstance(gym, dict) and gym.get("id") == gym_id for gym in gyms):
        return None, [Issue("error", "content/catalogs/gyms.json", "$.id", "같은 ID의 체육관이 이미 존재합니다.")]
    interior_structure = "cobbleventure:interiors/gyms/base_gym_interior"
    if interior_structure not in managed_structure_files(root):
        return None, [Issue(
            "error", "content/catalogs/gyms.json", "$.interior",
            "공용 체육관 내부 base_gym_interior를 찾을 수 없습니다.",
        )]
    gym = {
        "id": gym_id,
        "enabled": True,
        "display_name": {"ko_kr": name.strip()},
        "theme": "normal",
        "exterior": {"structure": "cobbleventure:gyms/base_gym"},
        "interior": {"modules": [{"id": "main", "structure": interior_structure, "position": [0, 0, 0], "rotation": "none"}], "connections": []},
        "staff": {"leader": {"league_entry_id": "", "anchor": "leader"}, "trainers": []},
    }
    catalog.setdefault("gyms", []).append(gym)
    issues = save_gym_catalog(root, catalog)
    if any(issue.level == "error" for issue in issues):
        return None, issues
    return gym, issues


def gym_interior_modules_payload(root: Path) -> dict[str, Any]:
    module_root = root / "content" / "structures" / "interiors" / "gyms"
    catalog_path = root / "content" / "catalogs" / "gyms.json"
    catalog = load_json(catalog_path) if catalog_path.is_file() else {"gyms": []}
    usage: dict[str, list[str]] = {}
    for gym in catalog.get("gyms", []) if isinstance(catalog, dict) else []:
        if not isinstance(gym, dict):
            continue
        for module in gym.get("interior", {}).get("modules", []):
            if isinstance(module, dict) and isinstance(module.get("structure"), str):
                usage.setdefault(module["structure"], []).append(str(gym.get("id", "")))
    modules = []
    for nbt_path in sorted(module_root.glob("*.nbt")) if module_root.is_dir() else []:
        resource = f"cobbleventure:interiors/gyms/{nbt_path.stem}"
        metadata_path = nbt_path.with_suffix(".structure.json")
        metadata = load_json(metadata_path) if metadata_path.is_file() else {}
        anchors = metadata.get("anchors", []) if isinstance(metadata, dict) else []
        leader = next((
            anchor.get("position") for anchor in anchors
            if isinstance(anchor, dict) and anchor.get("type") == "npc_position"
            and anchor.get("label") == "leader"
        ), None)
        structure_metadata = read_minecraft_structure_metadata(nbt_path.read_bytes())
        modules.append({
            "id": nbt_path.stem,
            "structure": resource,
            **structure_metadata,
            "size": [
                structure_metadata["width"], structure_metadata["height"],
                structure_metadata["depth"],
            ],
            "npc_labels": _structure_npc_labels(nbt_path),
            "door_anchors": _structure_named_anchors(
                nbt_path, {"door", "interior_entry", "interior_exit"}
            ),
            "arrival_anchors": _structure_named_anchors(
                nbt_path, {"arrival", "interior_spawn", "exterior_spawn"}
            ),
            "leader_anchor": leader,
            "used_by": usage.get(resource, []),
        })
    return {"modules": modules}


def interior_spaces_payload(root: Path) -> dict[str, Any]:
    module_root = root / "content" / "structures" / "interiors"
    settings = load_building_settings(root)
    usage: dict[str, list[str]] = {}
    for building, entry in settings.get("buildings", {}).items():
        if not isinstance(entry, dict):
            continue
        for interior in entry.get("interiors", []):
            if isinstance(interior, dict) and isinstance(interior.get("structure"), str):
                usage.setdefault(interior["structure"], []).append(building)
    spaces: list[dict[str, Any]] = []
    for nbt_path in sorted(module_root.rglob("*.nbt")) if module_root.is_dir() else []:
        relative = nbt_path.relative_to(module_root).with_suffix("").as_posix()
        resource = f"cobbleventure:interiors/{relative}"
        metadata = read_minecraft_structure_metadata(nbt_path.read_bytes())
        spaces.append({
            "key": relative,
            "structure": resource,
            "size": [metadata["width"], metadata["height"], metadata["depth"]],
            "doors": _structure_named_anchors(
                nbt_path, {"door", "interior_entry", "interior_exit"}
            ),
            "arrivals": _structure_named_anchors(
                nbt_path, {"arrival", "interior_spawn", "exterior_spawn"}
            ),
            "npc_labels": _structure_npc_labels(nbt_path),
            "used_by": sorted(usage.get(resource, [])),
        })
    return {"spaces": spaces}


def create_gym_interior_module(root: Path, payload: dict[str, Any]) -> dict[str, Any]:
    module_path = Path(__file__).with_name("gym_interior_modules.py")
    spec = importlib.util.spec_from_file_location("cobbleventure_gym_interior_modules", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError("체육관 내부 모듈 생성기를 불러올 수 없습니다.")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.create_module(
        root,
        str(payload.get("id", "")),
        int(payload.get("width", 32)),
        int(payload.get("depth", 32)),
        int(payload.get("floor_height", 12)),
        int(payload.get("floors", 1)),
    )


def create_interior_space(root: Path, payload: dict[str, Any]) -> dict[str, Any]:
    return create_gym_interior_module(root, payload)


def validate_repository(
    root: Path, strict_pack: bool = False, dependency_root: Path | None = None
) -> ValidationResult:
    root = root.resolve()
    dependency_root = (dependency_root or root).resolve()
    issues = validate_dependency_lock(
        dependency_root / "pack" / "dependencies.lock.json", strict_pack
    )
    issues.extend(validate_game_definitions_file(root / "content" / "catalogs" / "game-definitions.json"))
    issues.extend(validate_music_catalog_file(root / "content" / "catalogs" / "music-tracks.json"))
    issues.extend(validate_music_references(root))
    issues.extend(validate_gym_catalog_file(root / "content" / "catalogs" / "gyms.json", root / "content" / "structures"))
    economy_source_items = {
        entry.get("item") for drop in _economy_pokemon_drops_from_cobblemon(root)
        for entry in drop.get("entries", []) if isinstance(entry, dict) and isinstance(entry.get("item"), str)
    }
    issues.extend(validate_economy_catalog_file(
        root / "content" / "catalogs" / "economy.json", economy_source_items
    ))
    trainer_class_path = root / "content" / "catalogs" / "trainer-classes.json"
    issues.extend(validate_trainer_class_catalog(trainer_class_path))
    issues.extend(validate_biome_catalogs(root))
    trainer_class_ids: set[str] = set()
    try:
        trainer_class_data = load_json(trainer_class_path)
        trainer_class_ids = {
            value.get("id")
            for value in trainer_class_data.get("classes", [])
            if isinstance(value, dict) and isinstance(value.get("id"), str)
        }
    except (OSError, json.JSONDecodeError, DuplicateKeyError):
        pass
    issues.extend(validate_trainer_outfit_catalog(
        root / "content" / "catalogs" / "trainer-outfits.json", trainer_class_ids
    ))
    roster_ids, roster_issues = validate_trainer_roster_catalog(
        root / "content" / "catalogs" / "trainer-roster.json"
    )
    issues.extend(roster_issues)
    battle_dir = root / "content" / "battles"
    battle_presets: dict[str, tuple[Path, dict[str, Any]]] = {}
    if battle_dir.is_dir():
        for path in sorted(battle_dir.rglob("*.json")):
            battle_id, battle_issues = validate_battle_preset_file(path)
            issues.extend(battle_issues)
            try:
                battle_data = load_json(path)
            except (OSError, json.JSONDecodeError, DuplicateKeyError):
                continue
            if battle_id:
                if battle_id in battle_presets:
                    _issue(issues, "error", path, "$.id", f"중복 배틀 프리셋 ID: {battle_id}")
                battle_presets[battle_id] = (path, battle_data)

    content_dir = root / "content" / "source"
    seen_content: dict[str, Path] = {}
    content_battle_types: dict[str, str] = {}
    content_records: list[tuple[Path, dict[str, Any]]] = []
    if not content_dir.exists():
        _issue(issues, "error", content_dir, "$", "콘텐츠 원본 디렉터리가 없습니다.")
    else:
        for path in sorted(content_dir.rglob("*.json")):
            content_id, file_issues = validate_content_file(path)
            issues.extend(file_issues)
            try:
                content_data = load_json(path)
                if isinstance(content_data, dict):
                    content_records.append((path, content_data))
                selected_class = content_data.get("npc", {}).get("trainer_class")
                if isinstance(selected_class, str) and selected_class not in trainer_class_ids:
                    _issue(
                        issues,
                        "error",
                        path,
                        "$.npc.trainer_class",
                        f"카탈로그에 없는 트레이너 클래스입니다: {selected_class}",
                    )
                selected_character = content_data.get("npc", {}).get("character")
                if isinstance(selected_character, str) and selected_character not in roster_ids:
                    _issue(
                        issues,
                        "error",
                        path,
                        "$.npc.character",
                        f"캐릭터 명단에 없는 네임드 인물입니다: {selected_character}",
                    )
            except (OSError, json.JSONDecodeError, DuplicateKeyError, AttributeError):
                pass
            if content_id is None:
                continue
            if content_id in seen_content:
                _issue(
                    issues,
                    "error",
                    path,
                    "$.id",
                    f"다른 파일과 중복된 콘텐츠 ID: {content_id} ({seen_content[content_id].as_posix()})",
                )
            else:
                seen_content[content_id] = path
                battle_type = content_data.get("battle", {}).get("battle_type")
                if content_data.get("schema_version") in {3, 4}:
                    if content_data.get("schema_version") == 4:
                        referenced_battles = {
                            command.get("battle")
                            for event in content_data.get("events", [])
                            if isinstance(event, dict)
                            for command in event.get("commands", [])
                            if isinstance(command, dict) and command.get("type") == "start_battle"
                        }
                    else:
                        referenced_battles = {
                            action.get("battle")
                            for node in content_data.get("interaction", {}).get("nodes", [])
                            if isinstance(node, dict)
                            for source_actions in [node.get("actions", [])] + [
                                choice.get("actions", [])
                                for choice in node.get("choices", [])
                                if isinstance(choice, dict)
                            ]
                            for action in source_actions
                            if isinstance(action, dict) and action.get("type") == "start_battle"
                        }
                    for battle_ref in referenced_battles:
                        if battle_ref not in battle_presets:
                            _issue(issues, "error", path, "$.interaction", f"존재하지 않는 배틀 프리셋: {battle_ref}")
                        elif battle_type is None:
                            battle_type = battle_presets[battle_ref][1].get("battle", {}).get("battle_type")
                if isinstance(battle_type, str):
                    content_battle_types[content_id] = battle_type

        double_partner_owners: dict[str, tuple[str, Path]] = {}
        for path, content_data in content_records:
            owner_id = content_data.get("id")
            config = content_data.get("npc", {}).get("double_battle")
            if not isinstance(owner_id, str) or not isinstance(config, dict):
                continue
            partner_id = config.get("partner")
            if isinstance(partner_id, str) and partner_id not in seen_content:
                _issue(
                    issues, "error", path, "$.npc.double_battle.partner",
                    f"존재하지 않는 더블배틀 파트너 NPC: {partner_id}",
                )
            previous = double_partner_owners.get(partner_id)
            if previous and previous[0] != owner_id:
                _issue(
                    issues, "error", path, "$.npc.double_battle.partner",
                    f"이미 {previous[0]} 그룹에 지정된 파트너입니다: {partner_id}",
                )
            elif isinstance(partner_id, str):
                double_partner_owners[partner_id] = (owner_id, path)
            referenced_battles = [
                command.get("battle")
                for event in content_data.get("events", []) if isinstance(event, dict)
                for command in event.get("commands", [])
                if isinstance(command, dict) and command.get("type") == "start_battle"
            ]
            if not referenced_battles:
                _issue(
                    issues, "error", path, "$.events",
                    "2인 더블배틀 NPC에는 배틀 시작 명령이 하나 이상 필요합니다.",
                )
            for battle_ref in referenced_battles:
                battle_record = battle_presets.get(battle_ref)
                if battle_record and battle_record[1].get("battle", {}).get("battle_type") != "doubles":
                    _issue(
                        issues, "error", path, "$.npc.double_battle",
                        f"2인 NPC 그룹은 더블 배틀 프리셋을 사용해야 합니다: {battle_ref}",
                    )
        for partner_id, (owner_id, owner_path) in double_partner_owners.items():
            if owner_id in double_partner_owners:
                _issue(
                    issues, "error", owner_path, "$.npc.double_battle.partner",
                    f"대표 NPC는 다른 더블배틀 그룹의 파트너가 될 수 없습니다: {owner_id}",
                )

    league_ids, league_issues = validate_league_progression_file(
        root / "content" / "catalogs" / "league-progression.json", set(seen_content)
    )
    issues.extend(league_issues)
    try:
        league_catalog = load_json(root / "content" / "catalogs" / "league-progression.json")
        badge_catalog = load_json(root / "content" / "catalogs" / "badges.json")
        badge_ids = {
            badge.get("id") for badge in badge_catalog.get("badges", [])
            if isinstance(badge, dict) and isinstance(badge.get("id"), str)
        }
        for entry_index, entry in enumerate(league_catalog.get("entries", [])):
            if not isinstance(entry, dict) or entry.get("role") != "gym_leader":
                continue
            encounter = entry.get("encounter", {})
            if not isinstance(encounter, dict):
                continue
            battle_id = encounter.get("battle_id")
            if isinstance(battle_id, str) and battle_id not in battle_presets:
                _issue(issues, "error", root / "content" / "catalogs" / "league-progression.json", f"$.entries[{entry_index}].encounter.battle_id", f"존재하지 않는 배틀 프리셋: {battle_id}")
            badge_id = encounter.get("rewards", {}).get("badge_id")
            if isinstance(badge_id, str) and badge_id not in badge_ids:
                _issue(issues, "error", root / "content" / "catalogs" / "league-progression.json", f"$.entries[{entry_index}].encounter.rewards.badge_id", f"배지 카탈로그에 없는 배지: {badge_id}")
        gym_catalog = load_json(root / "content" / "catalogs" / "gyms.json")
        gym_ids = {
            gym.get("id") for gym in gym_catalog.get("gyms", [])
            if isinstance(gym, dict) and isinstance(gym.get("id"), str)
        }
        for gym_index, gym in enumerate(gym_catalog.get("gyms", [])):
            if not isinstance(gym, dict):
                continue
            staff = gym.get("staff", {})
            leader = staff.get("leader", {}) if isinstance(staff, dict) else {}
            trainer_id = leader.get("trainer_id") if isinstance(leader, dict) else None
            league_entry_id = leader.get("league_entry_id") if isinstance(leader, dict) else None
            badge_id = leader.get("badge_id") if isinstance(leader, dict) else None
            if isinstance(trainer_id, str) and trainer_id and trainer_id not in seen_content:
                _issue(issues, "error", root / "content" / "catalogs" / "gyms.json", f"$.gyms[{gym_index}].staff.leader.trainer_id", f"존재하지 않는 관장 트레이너 ID: {trainer_id}")
            if isinstance(league_entry_id, str) and league_entry_id and league_entry_id not in league_ids:
                _issue(issues, "error", root / "content" / "catalogs" / "gyms.json", f"$.gyms[{gym_index}].staff.leader.league_entry_id", f"존재하지 않는 리그 항목: {league_entry_id}")
            if isinstance(badge_id, str) and badge_id and badge_id not in badge_ids:
                _issue(issues, "error", root / "content" / "catalogs" / "gyms.json", f"$.gyms[{gym_index}].staff.leader.badge_id", f"배지 카탈로그에 없는 배지: {badge_id}")
            trainers = staff.get("trainers", []) if isinstance(staff, dict) else []
            for trainer_index, trainer in enumerate(trainers if isinstance(trainers, list) else []):
                trainer_id = trainer.get("trainer_id") if isinstance(trainer, dict) else None
                if isinstance(trainer_id, str) and trainer_id not in seen_content:
                    _issue(issues, "error", root / "content" / "catalogs" / "gyms.json", f"$.gyms[{gym_index}].staff.trainers[{trainer_index}].trainer_id", f"존재하지 않는 트레이너 ID: {trainer_id}")
    except (OSError, json.JSONDecodeError, DuplicateKeyError, AttributeError):
        gym_ids = set()
    settlement_dir = root / "content" / "settlements"
    seen_settlements: dict[str, Path] = {}
    settlement_records: list[tuple[Path, dict[str, Any]]] = []
    if settlement_dir.exists():
        for path in sorted(settlement_dir.rglob("*.json")):
            settlement_id, file_issues = validate_settlement_file(path)
            issues.extend(file_issues)
            try:
                settlement_data = load_json(path)
                if isinstance(settlement_data, dict):
                    settlement_records.append((path, settlement_data))
                gym = settlement_data.get("structure_profile", {}).get("gym", {})
                gym_id = gym.get("gym_id") if isinstance(gym, dict) else None
                if gym.get("enabled") and gym_id not in gym_ids:
                    _issue(issues, "error", path, "$.structure_profile.gym.gym_id", f"존재하지 않는 체육관: {gym_id}")
                trainer_slots = settlement_data.get("npc_placement", {}).get("trainer_slots", [])
                for index, slot in enumerate(trainer_slots):
                    trainer_id = slot.get("trainer_id") if isinstance(slot, dict) else None
                    if isinstance(trainer_id, str) and trainer_id not in seen_content:
                        _issue(
                            issues,
                            "error",
                            path,
                            f"$.npc_placement.trainer_slots[{index}].trainer_id",
                            f"존재하지 않는 트레이너 ID: {trainer_id}",
                        )
                    if not isinstance(slot, dict):
                        continue
                    slot_battle_type = slot.get("battle_type")
                    expected_battle_type = content_battle_types.get(trainer_id)
                    if expected_battle_type and slot_battle_type != expected_battle_type:
                        _issue(
                            issues,
                            "error",
                            path,
                            f"$.npc_placement.trainer_slots[{index}].battle_type",
                            f"트레이너 전투 방식({expected_battle_type})과 일치해야 합니다.",
                        )
                    for member_index, member in enumerate(slot.get("members", [])):
                        profile = member.get("npc_profile") if isinstance(member, dict) else None
                        if isinstance(profile, str) and profile not in seen_content:
                            _issue(
                                issues,
                                "error",
                                path,
                                f"$.npc_placement.trainer_slots[{index}].members[{member_index}].npc_profile",
                                f"존재하지 않는 EasyNPC 프로필 트레이너 ID: {profile}",
                            )
            except (OSError, json.JSONDecodeError, DuplicateKeyError, AttributeError):
                pass
            if settlement_id is None:
                continue
            if settlement_id in seen_settlements:
                _issue(
                    issues,
                    "error",
                    path,
                    "$.id",
                    f"다른 파일과 중복된 마을 ID: {settlement_id} ({seen_settlements[settlement_id].as_posix()})",
                )
            else:
                seen_settlements[settlement_id] = path

    for path, settlement_data in settlement_records:
        for index, connection in enumerate(settlement_data.get("connections", [])):
            target = connection.get("target_settlement") if isinstance(connection, dict) else None
            if isinstance(target, str) and target not in seen_settlements:
                _issue(
                    issues,
                    "warning",
                    path,
                    f"$.connections[{index}].target_settlement",
                    f"아직 작성되지 않은 다음 마을 ID입니다: {target}",
                )

    cave_documents: dict[str, dict[str, Any]] = {}
    cave_dir = root / "content" / "caves"
    for path in sorted(cave_dir.rglob("*.json")) if cave_dir.is_dir() else []:
        cave_id, cave_issues = validate_cave_file(path)
        issues.extend(cave_issues)
        try:
            cave_data = load_json(path)
            if cave_id and isinstance(cave_data, dict):
                if cave_id in cave_documents:
                    _issue(issues, "error", path, "$.id", f"다른 파일과 중복된 동굴 ID: {cave_id}")
                cave_documents[cave_id] = cave_data
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            pass

    issues.extend(validate_hex_worlds(root, set(seen_settlements), cave_documents))

    errors = sum(issue.level == "error" for issue in issues)
    warnings = sum(issue.level == "warning" for issue in issues)
    return ValidationResult(errors == 0, errors, warnings, issues)


def _print_result(result: ValidationResult) -> None:
    for issue in result.issues:
        label = "오류" if issue.level == "error" else "경고"
        print(f"[{label}] {issue.file} {issue.path}: {issue.message}")
    if result.valid:
        print(f"검증 성공: 오류 0개, 경고 {result.warnings}개")
    else:
        print(f"검증 실패: 오류 {result.errors}개, 경고 {result.warnings}개")


def _localized_value(value: Any) -> str:
    if not isinstance(value, dict):
        return ""
    return str(value.get("ko_kr") or value.get("en_us") or next(iter(value.values()), ""))


def validate_cave_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return None, issues
    if not isinstance(data, dict):
        _issue(issues, "error", path, "$", "동굴 문서는 객체여야 합니다.")
        return None, issues
    cave_id = data.get("id")
    if not isinstance(cave_id, str) or not RESOURCE_ID.fullmatch(cave_id):
        _issue(issues, "error", path, "$.id", "올바른 동굴 리소스 ID가 필요합니다.")
        cave_id = None
    if data.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "동굴 문서 지원 버전은 1입니다.")
    generation = data.get("generation")
    if not isinstance(generation, int) or isinstance(generation, bool) or not 1 <= generation <= 9:
        _issue(issues, "error", path, "$.generation", "1 이상 9 이하의 세대가 필요합니다.")
    _resource_id(data.get("cave_type"), issues, path, "$.cave_type")
    dimension = data.get("dimension")
    if not isinstance(dimension, dict):
        _issue(issues, "error", path, "$.dimension", "동굴 차원 설정이 필요합니다.")
    else:
        _resource_id(dimension.get("id"), issues, path, "$.dimension.id")
    if not isinstance(data.get("requires_flash"), bool):
        _issue(issues, "error", path, "$.requires_flash", "플래시 필요 여부는 true 또는 false여야 합니다.")
    encounters = data.get("random_encounters")
    if not isinstance(encounters, dict) or not isinstance(encounters.get("enabled"), bool):
        _issue(issues, "error", path, "$.random_encounters", "랜덤 인카운터 설정이 필요합니다.")
    elif encounters.get("enabled"):
        _resource_id(encounters.get("spawn_profile"), issues, path, "$.random_encounters.spawn_profile")
    biomes = data.get("internal_biomes")
    if not isinstance(biomes, list) or not biomes:
        _issue(issues, "error", path, "$.internal_biomes", "내부 바이옴을 하나 이상 지정해야 합니다.")
    else:
        for index, biome in enumerate(biomes):
            if not isinstance(biome, dict):
                _issue(issues, "error", path, f"$.internal_biomes[{index}]", "내부 바이옴은 객체여야 합니다.")
                continue
            _resource_id(biome.get("biome"), issues, path, f"$.internal_biomes[{index}].biome")
    trainer_settings = data.get("trainer_settings")
    if not isinstance(trainer_settings, dict) or not isinstance(trainer_settings.get("enabled"), bool):
        _issue(issues, "error", path, "$.trainer_settings", "트레이너 설정이 필요합니다.")
    entrances = data.get("entrances")
    entrance_ids: set[str] = set()
    if not isinstance(entrances, list) or not entrances:
        _issue(issues, "error", path, "$.entrances", "동굴 내부 입구를 하나 이상 지정해야 합니다.")
    else:
        seen: set[str] = set()
        for index, entrance in enumerate(entrances):
            entrance_id = entrance.get("id") if isinstance(entrance, dict) else None
            if not isinstance(entrance_id, str) or not CHOICE_ID.fullmatch(entrance_id) or entrance_id in seen:
                _issue(issues, "error", path, f"$.entrances[{index}].id", "유일한 내부 입구 ID가 필요합니다.")
            else:
                seen.add(entrance_id)
                entrance_ids.add(entrance_id)
            for field in ("destination_anchor", "fallback_anchor"):
                position = entrance.get(field) if isinstance(entrance, dict) else None
                if not isinstance(position, dict) or not all(isinstance(position.get(key), int) and not isinstance(position.get(key), bool) for key in ("x", "y", "z")):
                    _issue(issues, "error", path, f"$.entrances[{index}].{field}", "정수 블록 좌표 x, y, z가 필요합니다.")
    manual = data.get("generator", {}).get("manual_layout") if isinstance(data.get("generator"), dict) else None
    if isinstance(manual, dict) and manual.get("enabled"):
        anchors = manual.get("anchors", [])
        connections = manual.get("connections", [])
        anchor_ids = set(entrance_ids)
        for index, anchor in enumerate(anchors if isinstance(anchors, list) else []):
            anchor_id = anchor.get("id") if isinstance(anchor, dict) else None
            if not isinstance(anchor_id, str) or not CHOICE_ID.fullmatch(anchor_id) or anchor_id in anchor_ids:
                _issue(issues, "error", path, f"$.generator.manual_layout.anchors[{index}].id", "입구와 겹치지 않는 유일한 앵커 ID가 필요합니다.")
            else:
                anchor_ids.add(anchor_id)
        seen_connections: set[str] = set()
        for index, connection in enumerate(connections if isinstance(connections, list) else []):
            if not isinstance(connection, dict):
                continue
            connection_id = connection.get("id")
            if not isinstance(connection_id, str) or connection_id in seen_connections:
                _issue(issues, "error", path, f"$.generator.manual_layout.connections[{index}].id", "유일한 연결 ID가 필요합니다.")
            else:
                seen_connections.add(connection_id)
            for endpoint in ("from", "to"):
                if connection.get(endpoint) not in anchor_ids:
                    _issue(issues, "error", path, f"$.generator.manual_layout.connections[{index}].{endpoint}", "존재하는 입구 또는 내부 앵커를 선택해야 합니다.")
            if connection.get("from") == connection.get("to"):
                _issue(issues, "error", path, f"$.generator.manual_layout.connections[{index}]", "같은 앵커끼리는 연결할 수 없습니다.")
        if not connections:
            _issue(issues, "warning", path, "$.generator.manual_layout.connections", "수동 배치가 켜져 있지만 연결된 통로가 없습니다.")
    return cave_id, issues


def _managed_directory(root: Path, category: str) -> Path:
    directories = {
        "trainers": root / "content" / "source",
        "battles": root / "content" / "battles",
        "settlements": root / "content" / "settlements",
        "caves": root / "content" / "caves",
    }
    if category not in directories:
        raise ValueError("지원하지 않는 문서 종류입니다.")
    return directories[category].resolve()


def _managed_path(root: Path, category: str, relative_path: str) -> Path:
    if not relative_path or Path(relative_path).is_absolute():
        raise ValueError("저장소 기준 상대 경로가 필요합니다.")
    target = (root / Path(relative_path)).resolve()
    base = _managed_directory(root, category)
    if target.suffix.lower() != ".json" or base not in target.parents:
        raise ValueError("허용된 JSON 디렉터리 밖에는 접근할 수 없습니다.")
    return target


def _list_documents(root: Path, category: str) -> list[dict[str, Any]]:
    base = _managed_directory(root, category)
    if not base.exists():
        return []
    world_biomes: dict[str, str] = {}
    battle_types: dict[str, str] = {}
    if category == "trainers":
        battle_dir = root / "content" / "battles"
        for battle_path in sorted(battle_dir.rglob("*.json")) if battle_dir.is_dir() else []:
            try:
                battle_data = load_json(battle_path)
                battle_id = battle_data.get("id") if isinstance(battle_data, dict) else None
                battle_type = battle_data.get("battle", {}).get("battle_type") if isinstance(battle_data, dict) else None
                if isinstance(battle_id, str) and isinstance(battle_type, str):
                    battle_types[battle_id] = battle_type
            except (OSError, json.JSONDecodeError, DuplicateKeyError):
                pass
    if category == "settlements":
        world_dir = root / "content" / "worlds"
        for world_path in sorted(world_dir.glob("generation_*.json")) if world_dir.is_dir() else []:
            try:
                world = load_json(world_path)
                for node in world.get("settlements", []) if isinstance(world, dict) else []:
                    settlement_id = node.get("settlement") if isinstance(node, dict) else None
                    town_biome = node.get("town_biome") if isinstance(node, dict) else None
                    if isinstance(settlement_id, str) and isinstance(town_biome, str):
                        world_biomes[settlement_id] = town_biome
            except (OSError, json.JSONDecodeError, DuplicateKeyError):
                pass
    documents: list[dict[str, Any]] = []
    for path in sorted(base.rglob("*.json")):
        try:
            data = load_json(path)
            summary = {
                    "path": path.relative_to(root).as_posix(),
                    "id": data.get("id", ""),
                    "name": _localized_value(
                        data.get("name")
                        if category in {"trainers", "battles"}
                        else data.get("display_name")
                    ),
                    "enabled": data.get("enabled", False),
                }
            if category == "trainers":
                summary["battle_type"] = data.get("battle", {}).get("battle_type", "")
                if data.get("schema_version") in {3, 4}:
                    battle_refs = [
                        command.get("battle")
                        for event in data.get("events", [])
                        for command in event.get("commands", [])
                        if isinstance(command, dict) and command.get("type") == "start_battle"
                    ] if data.get("schema_version") == 4 else [
                        action.get("battle")
                        for node in data.get("interaction", {}).get("nodes", [])
                        if isinstance(node, dict)
                        for actions in [node.get("actions", [])] + [
                            choice.get("actions", [])
                            for choice in node.get("choices", [])
                            if isinstance(choice, dict)
                        ]
                        for action in actions
                        if isinstance(action, dict) and action.get("type") == "start_battle"
                    ]
                    summary["battle_type"] = next(
                        (battle_types[reference] for reference in battle_refs if reference in battle_types),
                        "",
                    )
                summary["npc_name"] = _localized_value(data.get("npc", {}).get("display_name"))
            elif category == "battles":
                summary["battle_type"] = data.get("battle", {}).get("battle_type", "singles")
            elif category == "settlements":
                summary["biome"] = world_biomes.get(data.get("id"), "minecraft:plains")
                summary["town_radius_cells"] = data.get("town_radius_cells", 1)
                summary["town_footprint_shape"] = data.get("town_footprint_shape", "line_q")
                summary["town_footprint_cells"] = data.get("town_footprint_cells", [])
                summary["town_road_exits"] = data.get("town_road_exits", [])
            else:
                summary["generation"] = data.get("generation", 1)
                summary["requires_flash"] = data.get("requires_flash", False)
                summary["entrance_count"] = len(data.get("entrances", []))
                summary["entrances"] = data.get("entrances", [])
                summary["cave_type"] = data.get("cave_type", "")
            documents.append(summary)
        except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
            documents.append(
                {
                    "path": path.relative_to(root).as_posix(),
                    "id": "",
                    "name": path.stem,
                    "enabled": False,
                    "error": str(error),
                }
            )
    return documents


def _validate_payload(
    data: Any,
    validator: Any,
) -> tuple[str | None, list[Issue]]:
    with tempfile.TemporaryDirectory(prefix="cobbleventure-content-") as directory:
        candidate = Path(directory) / "candidate.json"
        candidate.write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        return validator(candidate)


def _duplicate_document_issue(
    root: Path,
    category: str,
    target: Path,
    document_id: str | None,
    validator: Any,
) -> Issue | None:
    if not document_id:
        return None
    base = _managed_directory(root, category)
    if not base.exists():
        return None
    for path in base.rglob("*.json"):
        if path.resolve() == target.resolve():
            continue
        existing_id, _ = validator(path)
        if existing_id == document_id:
            return Issue(
                "error",
                target.as_posix(),
                "$.id",
                f"다른 파일과 중복된 ID입니다: {path.relative_to(root).as_posix()}",
            )
    return None


def _save_document(
    root: Path, category: str, relative_path: str, data: Any
) -> tuple[Path | None, list[Issue]]:
    validator = {
        "trainers": validate_content_file,
        "battles": validate_battle_preset_file,
        "settlements": validate_settlement_file,
        "caves": validate_cave_file,
    }[category]
    try:
        target = _managed_path(root, category, relative_path)
    except ValueError as error:
        return None, [Issue("error", relative_path, "$", str(error))]
    document_id, candidate_issues = _validate_payload(data, validator)
    issues = [
        Issue(issue.level, target.as_posix(), issue.path, issue.message)
        for issue in candidate_issues
    ]
    duplicate = _duplicate_document_issue(
        root, category, target, document_id, validator
    )
    if duplicate is not None:
        issues.append(duplicate)
    if any(issue.level == "error" for issue in issues):
        return target, issues

    target.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(
        prefix=f".{target.stem}-", suffix=".json.tmp", dir=target.parent
    )
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as output:
            json.dump(data, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.replace(temporary_name, target)
    finally:
        temporary = Path(temporary_name)
        if temporary.exists():
            temporary.unlink()
    return target, issues


def _delete_settlement_document(
    root: Path, relative_path: str
) -> tuple[Path, list[str]]:
    target = _managed_path(root, "settlements", relative_path)
    if not target.is_file():
        raise FileNotFoundError("마을 프리셋을 찾을 수 없습니다.")
    data = load_json(target)
    settlement_id = data.get("id") if isinstance(data, dict) else None
    if not isinstance(settlement_id, str) or not settlement_id:
        raise ValueError("삭제할 마을 프리셋의 ID를 읽을 수 없습니다.")

    references: list[str] = []
    world_dir = root / "content" / "worlds"
    for world_path in sorted(world_dir.glob("generation_*.json")) if world_dir.is_dir() else []:
        world = load_json(world_path)
        if any(
            isinstance(node, dict) and node.get("settlement") == settlement_id
            for node in (world.get("settlements", []) if isinstance(world, dict) else [])
        ):
            references.append(world_path.relative_to(root).as_posix())

    settlement_dir = root / "content" / "settlements"
    for settlement_path in sorted(settlement_dir.rglob("*.json")) if settlement_dir.is_dir() else []:
        if settlement_path.resolve() == target.resolve():
            continue
        settlement = load_json(settlement_path)
        if any(
            isinstance(connection, dict)
            and connection.get("target_settlement") == settlement_id
            for connection in (
                settlement.get("connections", [])
                if isinstance(settlement, dict) else []
            )
        ):
            references.append(settlement_path.relative_to(root).as_posix())

    if references:
        return target, references
    target.unlink()
    return target, []


def _contains_document_reference(value: Any, target_id: str, keys: set[str]) -> bool:
    if isinstance(value, dict):
        return any(
            (key in keys and child == target_id)
            or _contains_document_reference(child, target_id, keys)
            for key, child in value.items()
        )
    if isinstance(value, list):
        return any(_contains_document_reference(child, target_id, keys) for child in value)
    return False


def _delete_document(root: Path, category: str, relative_path: str) -> tuple[Path, list[str]]:
    if category == "settlements":
        return _delete_settlement_document(root, relative_path)
    target = _managed_path(root, category, relative_path)
    if not target.is_file():
        raise FileNotFoundError("삭제할 문서를 찾을 수 없습니다.")
    data = load_json(target)
    document_id = data.get("id") if isinstance(data, dict) else None
    if not isinstance(document_id, str) or not document_id:
        raise ValueError("삭제할 문서의 ID를 읽을 수 없습니다.")

    reference_keys = {
        "trainers": {"trainer_id", "npc_profile"},
        "battles": {"battle"},
        "caves": {"cave"},
    }[category]
    scan_directories = {
        "trainers": [root / "content" / "battles", root / "content" / "settlements"],
        "battles": [root / "content" / "source"],
        "caves": [root / "content" / "worlds"],
    }[category]
    references: list[str] = []
    for directory in scan_directories:
        for path in sorted(directory.rglob("*.json")) if directory.is_dir() else []:
            if path.resolve() == target.resolve():
                continue
            document = load_json(path)
            if _contains_document_reference(document, document_id, reference_keys):
                references.append(path.relative_to(root).as_posix())
    if references:
        return target, references
    target.unlink()
    return target, []


def _delete_world_layout(root: Path, generation: int) -> Path:
    if not 1 <= generation <= 9:
        raise ValueError("세대는 1 이상 9 이하여야 합니다.")
    target = (root / "content" / "worlds" / f"generation_{generation}.json").resolve()
    world_directory = (root / "content" / "worlds").resolve()
    if world_directory not in target.parents:
        raise ValueError("허용된 월드 디렉터리 밖에는 접근할 수 없습니다.")
    if not target.is_file():
        raise FileNotFoundError("월드맵을 찾을 수 없습니다.")
    target.unlink()
    return target


def _trainer_template(slug: str, name: str) -> dict[str, Any]:
    trainer_id = f"cobbleventure:trainer/{slug}"
    dialogue_id = f"cobbleventure:dialogue/{slug}/greeting"
    victory_flag = f"cobbleventure:flag/trainer/{slug}/defeated"
    return {
        "$schema": "../../schemas/content-bundle.schema.json",
        "schema_version": 2,
        "id": trainer_id,
        "enabled": True,
        "name": {"ko_kr": name},
        "description": {"ko_kr": f"{name} 트레이너 콘텐츠입니다."},
        "tags": ["trainer"],
        "npc": {
            "display_name": {"ko_kr": f"반바지 꼬마 {name}"},
            "trainer_class": "cobbleventure:trainer_class/youngster",
            "appearance": {
                "source": "rct_single",
                "type": "skin",
                "resource": "rctmod:trainers/single/youngster_yasu_0063",
            },
            "behavior": {
                "movement": "stationary",
                "look_at_player": True,
                "interaction_range": 4.0,
                "encounter": {
                    "mode": "interaction",
                    "trigger_range": 4.0,
                    "warning_range": {
                        "min": 4.0,
                        "max": 6.0,
                        "indicator": "trainer_nearby",
                    },
                },
                "invulnerable": True,
                "collision": True,
            },
        },
        "battle": {
            "trainer_id": trainer_id,
            "format": "GEN_9_SINGLES",
            "battle_type": "singles",
            "ai": {
                "controller": "cobbleventure",
                "difficulty": "standard",
                "strategy": "balanced",
                "options": {},
            },
            "level_mode": "fixed",
            "rules": {},
            "bag": [],
            "mechanics": {
                "mega_evolution": False,
                "z_move": False,
                "dynamax": False,
                "terastallization": False,
            },
            "team": [
                {
                    "species": "cobblemon:rattata",
                    "level": 5,
                    "form": None,
                    "aspects": [],
                    "gender": "random",
                    "nature": None,
                    "ability": None,
                    "held_item": None,
                    "gimmick": None,
                    "moves": ["tackle"],
                    "ivs": {},
                    "evs": {},
                    "tera_type": "auto",
                    "shiny": False,
                    "gigantamax_factor": False,
                }
            ],
        },
        "dialogue": {
            "entry": dialogue_id,
            "nodes": [
                {
                    "id": dialogue_id,
                    "speaker": "npc",
                    "text": {"ko_kr": f"안녕! 나는 {name}(이)야. 승부할래?"},
                    "conditions": [],
                    "actions": [],
                    "choices": [
                        {
                            "id": "battle",
                            "text": {"ko_kr": "승부한다"},
                            "conditions": [],
                            "actions": [
                                {"type": "start_battle", "trainer": trainer_id}
                            ],
                        },
                        {
                            "id": "cancel",
                            "text": {"ko_kr": "다음에"},
                            "conditions": [],
                            "actions": [{"type": "close_dialogue"}],
                        },
                    ],
                }
            ],
        },
        "progression": {
            "requirements": [],
            "victory_flag": victory_flag,
            "rematch": {"enabled": True, "cooldown_ticks": 0},
            "dialogue_routes": [
                {"when": {"type": "always"}, "entry": dialogue_id}
            ],
        },
        "outcomes": {
            "on_player_win": {
                "actions": [
                    {"type": "set_flag", "key": victory_flag, "value": True}
                ]
            },
            "on_player_loss": {"actions": []},
        },
        "rewards": {
            "money": {
                "mode": "fixed",
                "amount": 500,
                "currency_objective": "cobbleventure_money",
            },
            "items": {
                "mode": "fixed",
                "entries": [{"item": "cobblemon:poke_ball", "count": 1}],
            },
        },
    }


def _settlement_template(slug: str, name: str, generation: str) -> dict[str, Any]:
    return {
        "$schema": "../../schemas/settlement.schema.json",
        "schema_version": 3,
        "id": f"cobbleventure:settlement/{slug}",
        "enabled": True,
        "display_name": {"ko_kr": name},
        "region": f"cobbleventure:{generation}/region_01",
        "dimension": f"cobbleventure:{generation}",
        "town_radius_cells": 1,
        "town_footprint_shape": "line_q",
        "bounds": {"min_x": -32, "min_z": -32, "max_x": 32, "max_z": 32},
        "center": {"x": 0, "y": 64, "z": 0},
        "anchors": {
            "town_square": {"x": 0, "y": 64, "z": 0},
            "player_spawn": {"x": 0, "y": 64, "z": -24},
            "special_district": {"x": -48, "y": 64, "z": 0},
            "gym_building": {"x": 48, "y": 64, "z": 0},
        },
        "content_profile": {
            "pokemon": {
                "spawn_profile": f"cobbleventure:spawn/{slug}",
                "density_multiplier": 1.0,
                "biome_set": "cobbleventure:biome_set/starter_region",
                "unconditional_spawns": [],
            },
            "trainers": {
                "population_profile": f"cobbleventure:trainer_population/{slug}",
                "max_active": 8,
                "class_pool": ["cobbleventure:trainer_class/youngster"],
            },
            "level_scaling": {
                "mode": "badge_and_region", "base_level": 5, "min_level": 3,
                "max_level": 18, "per_badge": 2, "per_region": 3,
                "pokemon_offset": 0, "trainer_offset": 1,
            },
        },
        "biome_layout": {
            "arrangement": "organic_patches",
            "transition_width": 12,
            "zones": [{
                "id": "primary", "biome": "minecraft:plains",
                "size_blocks": 256, "placement": "center", "weight": 1,
                "habitat_profile": "cobbleventure:biome_profile/plains",
                "spawn_settings": {
                    "generation": 0, "habitat_variant": 0, "temperature": "any", "humidity": "any",
                    "weather": "any", "time": "any",
                    "rarities": ["common", "medium", "uncommon", "rare"],
                    "include_secondary": True,
                },
            }],
            "boundary": {
                "profile": f"cobbleventure:boundary/{slug}",
                "width": 16, "wall_height": 12, "wall_thickness": 5,
            },
        },
        "connections": [],
        "structure_profile": {
            "structure": f"cobbleventure:{slug}/village",
            "gym_theme": "normal",
            "commercial_center": "none",
            "pokemon_center_enabled": True,
            "civic_facilities_explicit": True,
            "layout_shape": "branching",
            "road_profile": {"width": 7, "material": "cobblestone"},
            "required_facilities": {"village_hub": f"cobbleventure:{slug}/village_hub"},
            "facility_requirements": [],
            "special_district": {
                "enabled": False,
                "anchor": "special_district",
                "placement_mode": "auto",
                "footprint": {"width": 8, "depth": 8},
                "clearance": 6,
                "building": {"enabled": False, "structure": ""},
            },
            "gym": {
                "enabled": False,
                "structure": "",
                "theme": "normal",
                "anchor": "gym_building",
            },
            "facility_placements": [],
        },
        "npc_placement": {
            "max_ambient_npcs": 8,
            "default_wander_radius": 5,
            "trainer_slots": [],
            "zones": [],
        },
    }


def _npc_event_template(slug: str, name: str) -> dict[str, Any]:
    npc_id = f"cobbleventure:npc/{slug}"
    return {
        "$schema": "../../schemas/npc-event-script.schema.json",
        "schema_version": 4,
        "id": npc_id,
        "enabled": True,
        "name": {"ko_kr": name},
        "description": {"ko_kr": f"{name} NPC 상호작용 콘텐츠입니다."},
        "tags": ["npc"],
        "npc": {
            "display_name": {"ko_kr": name},
            "role": "default",
            "trainer_class": "cobbleventure:trainer_class/youngster",
            "appearance": {
                "source": "rct_single", "type": "skin",
                "resource": "rctmod:trainers/single/youngster_yasu_0063",
            },
            "behavior": {
                "movement": "stationary", "look_at_player": True,
                "invulnerable": True, "collision": True,
            },
        },
        "events": [{
            "id": "on_interact",
            "trigger": {"type": "interact", "range": 4.0},
            "commands": [
                {"type": "label", "name": "start"},
                {"type": "dialogue", "id": "greeting", "speaker": "npc", "text": {"ko_kr": f"안녕! 나는 {name}(이)야."}},
                {"type": "label", "name": "end"},
                {"type": "end"},
            ],
        }],
    }


def _battle_template(slug: str, name: str) -> dict[str, Any]:
    legacy = _trainer_template(slug, name)
    return {
        "$schema": "../../schemas/battle-preset.schema.json",
        "schema_version": 1,
        "id": f"cobbleventure:battle/{slug}",
        "enabled": True,
        "name": {"ko_kr": f"{name} 배틀"},
        "battle": legacy["battle"],
    }


def _league_member_event_template(
    slug: str,
    name: str,
    name_en: str,
    role: str,
    region_slug: str,
    battle_id: str,
    badge_id: str = "",
) -> dict[str, Any]:
    role_labels = {
        "gym_leader": ("체육관 관장", "Gym Leader", "gym_leaders", "gym"),
        "elite_four": ("사천왕", "Elite Four", "elite_four", "league"),
        "champion": ("챔피언", "Champion", "champions", "league"),
    }
    role_ko, role_en, _, clear_scope = role_labels[role]
    npc_id = f"cobbleventure:npc/{role}/{slug}"
    clear_key = f"cobbleventure:flag/{clear_scope}/{region_slug}/{slug}/defeated"
    localized_name = {"ko_kr": name}
    display_name = {"ko_kr": f"{role_ko} {name}"}
    description = {"ko_kr": f"{name} {role_ko}의 표준 리그 전투 이벤트입니다."}
    if name_en:
        localized_name["en_us"] = name_en
        display_name["en_us"] = f"{role_en} {name_en}"
        description["en_us"] = f"Standard league battle event for {role_en} {name_en}."
    appearances = {
        "gym_leader": "rctmod:trainers/single/kanto_brock",
        "elite_four": "rctmod:trainers/single/kanto_league_lorelei",
        "champion": "rctmod:trainers/single/kanto_champion_blue",
    }
    victory_commands: list[dict[str, Any]] = [
        {"type": "label", "name": "victory"},
        {"type": "set_flag", "key": clear_key, "value": True},
    ]
    if role == "gym_leader":
        victory_commands.append({"type": "grant_badge", "badge": badge_id})
    victory_commands.extend([
        {
            "type": "dialogue", "id": "victory", "speaker": "npc",
            "text": {"ko_kr": "훌륭한 승부였다. 승리를 축하한다!"},
        },
        {"type": "goto", "target": "end"},
    ])
    return {
        "$schema": "../../../schemas/npc-event-script.schema.json",
        "schema_version": 4,
        "id": npc_id,
        "enabled": True,
        "name": localized_name,
        "description": description,
        "tags": ["trainer", role, region_slug, slug],
        "npc": {
            "display_name": display_name,
            "trainer_class": f"cobbleventure:trainer_class/{role}",
            "appearance": {
                "source": "rct_single", "type": "skin", "resource": appearances[role],
            },
            "behavior": {
                "movement": "stationary", "look_at_player": True,
                "invulnerable": True, "collision": True,
            },
        },
        "events": [{
            "id": "on_interact",
            "trigger": {"type": "interact", "range": 4.0},
            "commands": [
                {
                    "type": "branch",
                    "conditions": [{"type": "flag_equals", "key": clear_key, "value": True}],
                    "target": "cleared",
                },
                {"type": "label", "name": "challenge"},
                {
                    "type": "dialogue", "id": "challenge", "speaker": "npc",
                    "text": {"ko_kr": "준비가 됐다면 승부하자!"},
                },
                {
                    "type": "choices",
                    "options": [
                        {"id": "battle", "text": {"ko_kr": "승부한다"}, "target": "battle"},
                        {"id": "cancel", "text": {"ko_kr": "다음에 도전한다"}, "target": "end"},
                    ],
                },
                {"type": "label", "name": "battle"},
                {
                    "type": "start_battle", "battle": battle_id,
                    "results": {"player_win": "victory", "player_loss": "defeat"},
                },
                *victory_commands,
                {"type": "label", "name": "defeat"},
                {
                    "type": "dialogue", "id": "defeat", "speaker": "npc",
                    "text": {"ko_kr": "좋은 승부였다. 준비해서 다시 도전해라."},
                },
                {"type": "goto", "target": "end"},
                {"type": "label", "name": "cleared"},
                {
                    "type": "dialogue", "id": "cleared", "speaker": "npc",
                    "text": {"ko_kr": "이미 실력을 증명했군. 다음 목표로 나아가라."},
                },
                {"type": "label", "name": "end"},
                {"type": "end"},
            ],
        }],
    }


def create_league_member(root: Path, payload: dict[str, Any]) -> tuple[dict[str, Any] | None, list[Issue]]:
    role = payload.get("role")
    slug = payload.get("slug")
    name = payload.get("name")
    name_en = payload.get("name_en", "")
    region = payload.get("region")
    badge_id = payload.get("badge_id", "")
    theme = payload.get("theme", "normal")
    primary_type = payload.get("primary_type", theme if role == "gym_leader" else "normal")
    display_badge_id = payload.get("display_badge_id", "")
    character = payload.get("character", "")
    appearance_resource = payload.get("appearance_resource", "")
    challenge_dialogue = payload.get("challenge_dialogue", "준비가 됐다면 승부하자!")
    victory_dialogue = payload.get("victory_dialogue", "훌륭한 승부였다. 이 배지는 네 것이다.")
    defeat_dialogue = payload.get("defeat_dialogue", "좋은 승부였다. 준비해서 다시 도전해라.")
    cleared_dialogue = payload.get("cleared_dialogue", "이미 실력을 증명했군. 다음 목표로 나아가라.")
    reward_item = payload.get("reward_item", "")
    reward_money = payload.get("reward_money", 0)
    reward_item_count = payload.get("reward_item_count", 1)
    generation = payload.get("generation")
    order = payload.get("order")
    level_cap = payload.get("level_cap")
    input_values = (
        role, slug, name, name_en, region, badge_id, theme, primary_type, display_badge_id, character, appearance_resource,
        challenge_dialogue, victory_dialogue, defeat_dialogue, cleared_dialogue, reward_item,
    )
    if not all(isinstance(value, str) for value in input_values):
        return None, [Issue("error", "", "$", "문자열 입력값의 형식이 올바르지 않습니다.")]
    if role not in {"gym_leader", "elite_four", "champion"}:
        return None, [Issue("error", "", "$.role", "관장, 사천왕, 챔피언 중 하나를 선택해야 합니다.")]
    if not DOCUMENT_SLUG.fullmatch(slug):
        return None, [Issue("error", "", "$.slug", "파일 ID는 소문자, 숫자와 밑줄만 사용할 수 있습니다.")]
    if not name.strip():
        return None, [Issue("error", "", "$.name", "한국어 이름이 필요합니다.")]
    if not RESOURCE_ID.fullmatch(region):
        return None, [Issue("error", "", "$.region", "올바른 지역 리소스 ID가 필요합니다.")]
    if not isinstance(generation, int) or isinstance(generation, bool) or not 1 <= generation <= 9:
        return None, [Issue("error", "", "$.generation", "세대는 1~9 정수여야 합니다.")]
    if not isinstance(order, int) or isinstance(order, bool) or not 1 <= order <= 99:
        return None, [Issue("error", "", "$.order", "표시 순서는 1~99 정수여야 합니다.")]
    if not isinstance(level_cap, int) or isinstance(level_cap, bool) or not 1 <= level_cap <= 100:
        return None, [Issue("error", "", "$.level_cap", "레벨캡은 1~100 정수여야 합니다.")]
    if role == "gym_leader" and not RESOURCE_ID.fullmatch(badge_id):
        return None, [Issue("error", "", "$.badge_id", "관장은 지급할 배지를 선택해야 합니다.")]
    if role == "gym_leader" and not RESOURCE_ID.fullmatch(appearance_resource):
        return None, [Issue("error", "", "$.appearance_resource", "관장 NPC로 생성할 외형 리소스가 필요합니다.")]
    if role == "gym_leader" and (not isinstance(reward_money, int) or isinstance(reward_money, bool) or reward_money < 0):
        return None, [Issue("error", "", "$.reward_money", "상금은 0 이상의 정수여야 합니다.")]
    if reward_item and not RESOURCE_ID.fullmatch(reward_item):
        return None, [Issue("error", "", "$.reward_item", "올바른 보상 아이템 ID가 필요합니다.")]
    if reward_item and (not isinstance(reward_item_count, int) or isinstance(reward_item_count, bool) or not 1 <= reward_item_count <= 999):
        return None, [Issue("error", "", "$.reward_item_count", "보상 아이템 수량은 1~999 정수여야 합니다.")]
    if not CHOICE_ID.fullmatch(theme):
        return None, [Issue("error", "", "$.theme", "올바른 체육관 타입이 필요합니다.")]
    if primary_type not in {"normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"}:
        return None, [Issue("error", "", "$.primary_type", "올바른 포켓몬 주 속성이 필요합니다.")]
    if display_badge_id and not RESOURCE_ID.fullmatch(display_badge_id):
        return None, [Issue("error", "", "$.display_badge_id", "올바른 표시 배지 ID가 필요합니다.")]

    region_slug = region.rsplit("/", 1)[-1]
    folder = {"gym_leader": "gym_leaders", "elite_four": "elite_four", "champion": "champions"}[role]
    npc_id = f"cobbleventure:npc/{role}/{slug}"
    battle_id = f"cobbleventure:battle/{role}/{slug}"
    league_id = f"cobbleventure:league/{region_slug}/{slug}"
    trainer_path = f"content/source/trainers/{folder}/{slug}.json"
    battle_path = f"content/battles/{folder}/{slug}.json"
    target_paths = [root / battle_path]
    if role != "gym_leader":
        target_paths.append(root / trainer_path)
    if any(path.exists() for path in target_paths):
        return None, [Issue("error", "", "$.slug", "같은 역할과 ID의 NPC 또는 배틀 파일이 이미 존재합니다.")]

    badges = load_json(root / "content" / "catalogs" / "badges.json").get("badges", [])
    if role == "gym_leader" and not any(isinstance(item, dict) and item.get("id") == badge_id for item in badges):
        return None, [Issue("error", "", "$.badge_id", "배지 카탈로그에 없는 배지입니다.")]

    trainer = None if role == "gym_leader" else _league_member_event_template(
        slug, name.strip(), name_en.strip(), role, region_slug, battle_id, badge_id
    )
    battle = _battle_template(slug, name.strip())
    battle["id"] = battle_id
    battle["battle"]["trainer_id"] = npc_id
    battle["battle"]["team"][0]["level"] = min(level_cap, 100)
    battle["name"] = {"ko_kr": f"{name.strip()} 리그 배틀"}
    if name_en.strip():
        battle["name"]["en_us"] = f"{name_en.strip()} League Battle"
    league_entry = {
        "id": league_id,
        "role": role,
        "primary_type": primary_type,
        "display_name": {"ko_kr": name.strip()},
        "generation": generation,
        "region": region,
        "order": order,
        "level_cap": level_cap,
    }
    if role == "gym_leader":
        league_entry["encounter"] = {
            "battle_id": battle_id,
            "appearance": {
                "source": "rct_single", "type": "skin", "resource": appearance_resource,
            },
            "dialogue": {
                "challenge": challenge_dialogue.strip(),
                "victory": victory_dialogue.strip(),
                "defeat": defeat_dialogue.strip(),
                "cleared": cleared_dialogue.strip(),
            },
            "rewards": {"money": reward_money, "badge_id": badge_id},
        }
        if character:
            league_entry["encounter"]["character"] = character
        if reward_item:
            league_entry["encounter"]["rewards"].update({
                "item": reward_item, "item_count": reward_item_count,
            })
    else:
        league_entry["trainer_id"] = npc_id
        if display_badge_id:
            league_entry["badge_id"] = display_badge_id
    if name_en.strip():
        league_entry["display_name"]["en_us"] = name_en.strip()

    league_path = root / "content" / "catalogs" / "league-progression.json"
    gym_path = root / "content" / "catalogs" / "gyms.json"
    league_catalog = load_json(league_path)
    if any(isinstance(entry, dict) and entry.get("id") == league_id for entry in league_catalog.get("entries", [])):
        return None, [Issue("error", "", "$.slug", "같은 ID의 리그 구성원이 이미 존재합니다.")]
    league_catalog.setdefault("entries", []).append(league_entry)

    gym_catalog = load_json(gym_path)
    gym = None
    if role == "gym_leader":
        gym_id = f"cobbleventure:gym/{slug}"
        if any(isinstance(item, dict) and item.get("id") == gym_id for item in gym_catalog.get("gyms", [])):
            return None, [Issue("error", "", "$.slug", "같은 ID의 체육관이 이미 존재합니다.")]
        gym = {
            "id": gym_id,
            "enabled": True,
            "display_name": {"ko_kr": f"{name.strip()} 체육관"},
            "theme": theme,
            "exterior": {"structure": "cobbleventure:gyms/base_gym"},
            "interior": {
                "modules": [{
                    "id": "main", "structure": "cobbleventure:interiors/gyms/base_gym_interior",
                    "position": [0, 0, 0], "rotation": "none",
                }],
                "connections": [],
            },
            "staff": {
                "leader": {
                    "league_entry_id": league_id, "anchor": "leader",
                },
                "trainers": [],
            },
        }
        if name_en.strip():
            gym["display_name"]["en_us"] = f"{name_en.strip()} Gym"
        gym_catalog.setdefault("gyms", []).append(gym)

    issues: list[Issue] = []
    documents_to_validate = [(battle, validate_battle_preset_file, battle_path)]
    if trainer is not None:
        documents_to_validate.insert(0, (trainer, validate_content_file, trainer_path))
    for document, validator, path in documents_to_validate:
        _, document_issues = _validate_payload(document, validator)
        issues.extend(Issue(issue.level, path, issue.path, issue.message) for issue in document_issues)
    with tempfile.TemporaryDirectory() as directory:
        temporary_root = Path(directory)
        league_candidate = temporary_root / "league-progression.json"
        league_candidate.write_text(json.dumps(league_catalog, ensure_ascii=False, indent=2), encoding="utf-8")
        trainer_ids = {
            item.get("id") for item in _list_documents(root, "trainers")
            if isinstance(item.get("id"), str)
        } | {npc_id}
        _, league_issues = validate_league_progression_file(league_candidate, trainer_ids)
        issues.extend(league_issues)
        if gym is not None:
            gym_candidate = temporary_root / "gyms.json"
            gym_candidate.write_text(json.dumps(gym_catalog, ensure_ascii=False, indent=2), encoding="utf-8")
            issues.extend(validate_gym_catalog_file(gym_candidate, root / "content" / "structures"))
    if any(issue.level == "error" for issue in issues):
        return None, issues

    catalog_backups = {league_path: league_path.read_bytes(), gym_path: gym_path.read_bytes()}
    created_paths: list[Path] = []
    try:
        documents_to_save = [("battles", battle_path, battle)]
        if trainer is not None:
            documents_to_save.insert(0, ("trainers", trainer_path, trainer))
        for category, path, document in documents_to_save:
            target, save_issues = _save_document(root, category, path, document)
            issues.extend(save_issues)
            if any(issue.level == "error" for issue in save_issues) or target is None:
                raise ValueError("구성원 문서를 저장하지 못했습니다.")
            created_paths.append(target)
        league_save_issues = save_league_progression(root, league_catalog)
        issues.extend(league_save_issues)
        if any(issue.level == "error" for issue in league_save_issues):
            raise ValueError("리그 카탈로그를 저장하지 못했습니다.")
        if gym is not None:
            gym_save_issues = save_gym_catalog(root, gym_catalog)
            issues.extend(gym_save_issues)
            if any(issue.level == "error" for issue in gym_save_issues):
                raise ValueError("체육관 카탈로그를 저장하지 못했습니다.")
    except (OSError, ValueError):
        for path in created_paths:
            if path.is_file():
                path.unlink()
        for path, content in catalog_backups.items():
            path.write_bytes(content)
        if not any(issue.level == "error" for issue in issues):
            issues.append(Issue("error", "", "$", "통합 생성 중 오류가 발생해 변경을 되돌렸습니다."))
        return None, issues
    return {
        "league_entry": league_entry,
        "gym": gym,
        "trainer_path": trainer_path if trainer is not None else "",
        "battle_path": battle_path,
    }, issues


def _cave_template(slug: str, name: str, generation: str) -> dict[str, Any]:
    generation_number = int(generation.removeprefix("generation_"))
    return {
        "$schema": "../../schemas/cave.schema.json",
        "schema_version": 1,
        "id": f"cobbleventure:cave/{slug}",
        "enabled": True,
        "display_name": {"ko_kr": name},
        "generation": generation_number,
        "cave_type": "cobbleventure:cave_type/natural_rock",
        "dimension": {
            "id": "cobbleventure:dungeons",
            "region_id": f"generation_{generation_number}/{slug}",
            "origin": {"x": 0, "y": 48, "z": 0},
            "bounds": {"min_x": -256, "min_z": -256, "max_x": 256, "max_z": 256},
        },
        "requires_flash": False,
        "random_encounters": {
            "enabled": True,
            "spawn_profile": f"cobbleventure:spawn/{slug}",
            "density_multiplier": 1.0,
        },
        "internal_biomes": [
            {"id": "main", "biome": "minecraft:dripstone_caves", "weight": 100}
        ],
        "trainer_settings": {"enabled": False, "max_active": 0, "class_pool": [], "placements": []},
        "entrances": [
            {
                "id": "main",
                "display_name": "주 출입구",
                "destination_anchor": {"x": 0, "y": 48, "z": 0},
                "fallback_anchor": {"x": 0, "y": 49, "z": 0},
            }
        ],
    }


def _create_document(
    root: Path, category: str, slug: str, name: str, generation: str = "generation_1",
    reference_id: str = "",
) -> tuple[Path | None, list[Issue]]:
    if category not in {"trainers", "battles", "settlements", "caves"}:
        return None, [Issue("error", "", "$.category", "지원하지 않는 문서 종류입니다.")]
    if not DOCUMENT_SLUG.fullmatch(slug):
        return None, [
            Issue(
                "error",
                "",
                "$.slug",
                "파일 ID는 소문자, 숫자와 밑줄만 사용할 수 있습니다.",
            )
        ]
    if not name.strip():
        return None, [Issue("error", "", "$.name", "한국어 이름이 필요합니다.")]
    if not DOCUMENT_SLUG.fullmatch(generation):
        return None, [Issue("error", "", "$.generation", "올바른 세대 ID가 아닙니다.")]

    if category == "trainers":
        relative_path = f"content/source/trainers/{slug}.json"
        document = _npc_event_template(slug, name.strip())
    elif category == "battles":
        relative_path = f"content/battles/{slug}.json"
        document = _battle_template(slug, name.strip())
        if reference_id:
            reference_catalog = load_json(root / "content" / "catalogs" / "trainer-reference-entries.json")
            reference = next(
                (entry for entry in reference_catalog.get("entries", []) if entry.get("id") == reference_id),
                None,
            )
            if not isinstance(reference, dict) or not isinstance(reference.get("battle"), dict):
                return None, [Issue("error", relative_path, "$.reference_id", "예비 엔트리를 찾을 수 없습니다.")]
            trainer_id = document["battle"]["trainer_id"]
            document["battle"] = copy.deepcopy(reference["battle"])
            document["battle"]["trainer_id"] = trainer_id
    elif category == "settlements":
        relative_path = f"content/settlements/{generation}/{slug}.json"
        document = _settlement_template(slug, name.strip(), generation)
    else:
        relative_path = f"content/caves/{generation}/{slug}.json"
        document = _cave_template(slug, name.strip(), generation)
    target = (root / relative_path).resolve()
    if target.exists():
        return target, [Issue("error", target.as_posix(), "$", "같은 이름의 파일이 이미 존재합니다.")]
    return _save_document(root, category, relative_path, document)


def _run_build(core_root: Path, project_root: Path, command: str) -> dict[str, Any]:
    if command not in BUILD_COMMANDS:
        raise ValueError("허용되지 않은 빌드 명령입니다.")
    try:
        completed = subprocess.run(
            ["cmd.exe", "/d", "/c", str(core_root / "build.bat"), command],
            cwd=core_root,
            env={**os.environ, "COBBLEVENTURE_PROJECT_PATH": str(project_root)},
            capture_output=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
            check=False,
        )
        output = "\n".join(
            part.strip() for part in (completed.stdout, completed.stderr) if part.strip()
        )
        return {
            "command": command,
            "description": BUILD_COMMANDS[command],
            "success": completed.returncode == 0,
            "return_code": completed.returncode,
            "output": output or "출력 없음",
        }
    except subprocess.TimeoutExpired as error:
        output = (error.stdout or b"") if isinstance(error.stdout, bytes) else (error.stdout or "")
        return {
            "command": command,
            "description": BUILD_COMMANDS[command],
            "success": False,
            "return_code": None,
            "output": f"5분 제한 시간을 초과했습니다.\n{output}",
        }


def _content_manager_settings_path(root: Path) -> Path:
    return root / CONTENT_MANAGER_SETTINGS


def _load_structure_builder_settings(root: Path) -> dict[str, str]:
    path = _content_manager_settings_path(root)
    if not path.is_file():
        return {"instance_path": ""}
    document = load_json(path)
    section = document.get("structure_builder", {}) if isinstance(document, dict) else {}
    instance_path = section.get("instance_path", "") if isinstance(section, dict) else ""
    if not isinstance(instance_path, str):
        raise ValueError("건축 월드 인스턴스 경로 설정이 문자열이 아닙니다.")
    return {"instance_path": instance_path}


def _save_structure_builder_settings(root: Path, instance_path: str) -> dict[str, str]:
    value = instance_path.strip()
    if value:
        resolved = Path(os.path.expandvars(value)).expanduser()
        if not resolved.is_absolute():
            raise ValueError("CurseForge 인스턴스 경로는 절대 경로여야 합니다.")
        value = str(resolved.resolve(strict=False))
    path = _content_manager_settings_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    document = {
        "schema_version": 1,
        "structure_builder": {"instance_path": value},
    }
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(
            json.dumps(document, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    return {"instance_path": value}


def _structure_builder_world_path(instance_path: str) -> Path | None:
    if not instance_path:
        return None
    return Path(instance_path) / "saves" / STRUCTURE_BUILDER_WORLD_NAME


def _structure_builder_export_count(world_path: Path | None) -> int:
    if world_path is None:
        return 0
    export_root = (
        world_path
        / "generated"
        / "cobbleventure_builder"
        / "structures"
        / "export"
    )
    return sum(1 for path in export_root.rglob("*.nbt") if path.is_file()) if export_root.is_dir() else 0


def _structure_builder_instance_candidates() -> list[str]:
    candidates: list[str] = []
    seen: set[str] = set()
    search_roots = [
        Path.home() / "curseforge" / "minecraft" / "Instances",
        Path.home() / "Documents" / "Curse" / "Minecraft" / "Instances",
    ]
    app_data = os.environ.get("APPDATA")
    if app_data:
        search_roots.append(Path(app_data) / "CurseForge" / "Minecraft" / "Instances")
    for search_root in search_roots:
        if not search_root.is_dir():
            continue
        try:
            children = list(search_root.iterdir())
        except OSError:
            continue
        for child in children:
            if not child.is_dir() or not (
                child / "saves" / STRUCTURE_BUILDER_WORLD_NAME
            ).is_dir():
                continue
            value = str(child.resolve())
            key = os.path.normcase(value)
            if key not in seen:
                seen.add(key)
                candidates.append(value)
    return sorted(candidates, key=str.casefold)


def _structure_builder_status(
    project_root: Path, core_root: Path | None = None
) -> dict[str, Any]:
    core_root = (core_root or project_root).resolve()
    settings = _load_structure_builder_settings(core_root)
    instance_path = settings["instance_path"]
    instance = Path(instance_path) if instance_path else None
    world = _structure_builder_world_path(instance_path)
    output = core_root / "dist" / "cobbleventure-structure-builder-0.1.0-curseforge.zip"
    return {
        **settings,
        "world_path": str(world) if world is not None else "",
        "instance_exists": bool(instance and instance.is_dir()),
        "world_exists": bool(world and world.is_dir()),
        "export_count": _structure_builder_export_count(world),
        "source_count": sum(1 for path in (project_root / "content" / "structures").rglob("*.nbt") if path.is_file()),
        "package_path": str(output),
        "package_exists": output.is_file(),
        "candidates": _structure_builder_instance_candidates(),
    }


def _run_structure_builder_import(
    project_root: Path, core_root: Path | None = None
) -> dict[str, Any]:
    core_root = (core_root or project_root).resolve()
    status = _structure_builder_status(project_root, core_root)
    world_path = Path(status["world_path"]) if status["world_path"] else None
    if world_path is None:
        raise ValueError("먼저 CurseForge 인스턴스 경로를 저장해 주세요.")
    if not world_path.is_dir():
        raise ValueError(f"건축 월드를 찾을 수 없습니다: {world_path}")
    if status["export_count"] == 0:
        raise ValueError("내보낸 NBT가 없습니다. 게임에서 /cobbleventure_builder save all을 먼저 실행하세요.")
    try:
        completed = subprocess.run(
            ["cmd.exe", "/d", "/c", str(core_root / "build.bat"), "builder-import", str(world_path)],
            cwd=core_root,
            env={**os.environ, "COBBLEVENTURE_PROJECT_PATH": str(project_root)},
            capture_output=True,
            encoding="cp949",
            errors="replace",
            timeout=120,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        output = (error.stdout or b"") if isinstance(error.stdout, bytes) else (error.stdout or "")
        return {"success": False, "return_code": None, "output": f"2분 제한 시간을 초과했습니다.\n{output}"}
    output = "\n".join(part.strip() for part in (completed.stdout, completed.stderr) if part.strip())
    return {
        "success": completed.returncode == 0,
        "return_code": completed.returncode,
        "output": output or "출력 없음",
        "world_path": str(world_path),
    }


def _short_resource_id(value: Any) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    return value.split(":", 1)[-1]


def _rct_stats(stats: Any) -> dict[str, int]:
    if not isinstance(stats, dict):
        return {}
    keys = {
        "hp": "hp",
        "attack": "atk",
        "defense": "def",
        "special_attack": "spa",
        "special_defense": "spd",
        "speed": "spe",
    }
    return {
        target: value
        for source, target in keys.items()
        if isinstance((value := stats.get(source)), int) and not isinstance(value, bool)
    }


def _rct_team_member(member: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {
        "species": _short_resource_id(member.get("species")),
        "level": member.get("level"),
        "moveset": [_short_resource_id(move) for move in member.get("moves", [])],
    }
    optional_values = {
        "nature": _short_resource_id(member.get("nature")),
        "ability": _short_resource_id(member.get("ability")),
    }
    result.update({key: value for key, value in optional_values.items() if value})
    gender = member.get("gender")
    if gender in {"male", "female", "genderless"}:
        result["gender"] = gender.upper()
    aspects = member.get("aspects")
    if isinstance(aspects, list) and aspects:
        result["aspects"] = list(aspects)
    ivs = _rct_stats(member.get("ivs"))
    evs = _rct_stats(member.get("evs"))
    if ivs:
        result["ivs"] = ivs
    if evs:
        result["evs"] = evs
    held_item = member.get("gimmick", {}).get("item") if isinstance(member.get("gimmick"), dict) else member.get("held_item")
    if held_item:
        result["heldItem"] = _short_resource_id(held_item)
    if member.get("shiny"):
        result["shiny"] = True
    if member.get("gigantamax_factor"):
        result["gmaxFactor"] = True
    tera_type = member.get("tera_type")
    if isinstance(tera_type, str) and tera_type != "auto":
        result["teraType"] = tera_type
    return result


def export_rct_trainer(document: dict[str, Any]) -> dict[str, Any]:
    battle = document["battle"]
    ai = battle["ai"]
    # The Cobbleventure decision engine is still a platform-independent module
    # and is not registered as an RCT AI type in Minecraft yet. Keep its full
    # configuration in the separate runtime profile, while exporting a valid
    # built-in RCT controller so generated trainers can battle in game today.
    select_margin = {
        "easy": 0.35,
        "standard": 0.15,
        "hard": 0.05,
        "cheater": 0.0,
    }.get(ai["difficulty"], 0.15)
    ai_data: dict[str, Any] = {
        "maxSelectMargin": select_margin,
        "canTera": bool(battle.get("mechanics", {}).get("terastallization")),
    }
    result: dict[str, Any] = {
        "name": document.get("name", {}).get("ko_kr") or document["id"],
        "ai": {"type": "rct", "data": ai_data},
        "team": [_rct_team_member(member) for member in battle.get("team", [])],
    }
    rules = battle.get("rules", {})
    if rules:
        result["battleRules"] = {
            "maxItemUses" if key == "max_item_uses" else "canForfeit" if key == "can_forfeit" else key: value
            for key, value in rules.items()
        }
    bag = battle.get("bag", [])
    if bag:
        result["bag"] = [
            {"item": item["item"], "quantity": item["quantity"]}
            for item in bag
        ]
    return result


def export_ai_runtime_profile(document: dict[str, Any]) -> dict[str, Any]:
    ai = document["battle"]["ai"]
    options: dict[str, Any] = {}
    if ai["difficulty"] == "cheater":
        options["cheatProbability"] = ai["options"]["cheat_probability"]
    return {
        "schemaVersion": 1,
        "trainerId": document["battle"]["trainer_id"],
        "controller": ai["controller"],
        "difficulty": ai["difficulty"],
        "strategy": ai["strategy"],
        "options": options,
    }


def generate_content(
    root: Path, output: Path | None = None, dependency_root: Path | None = None
) -> dict[str, Any]:
    root = root.resolve()
    output = (output or root / "generated").resolve()
    marker = output / ".cobbleventure-generated"
    validation = validate_repository(root, dependency_root=dependency_root)
    if not validation.valid:
        raise ValueError("콘텐츠 검증이 실패하여 생성할 수 없습니다.")
    if output.exists():
        if not marker.is_file():
            raise ValueError(f"생성 전용 폴더가 아니므로 삭제하지 않습니다: {output}")
        shutil.rmtree(output)
    output.mkdir(parents=True)
    marker.write_text("generated by tools/content-manager\n", encoding="utf-8")
    rct_root = output / "rct" / "data" / "rctmod" / "trainers"
    runtime_root = output / "cobbleventure" / "ai-profiles"
    trainers: list[str] = []
    for source in sorted((root / "content" / "source").rglob("*.json")):
        trainer_id, issues = validate_content_file(source)
        if trainer_id is None or any(issue.level == "error" for issue in issues):
            continue
        document = load_json(source)
        if not document.get("enabled", True):
            continue
        if document.get("schema_version") in {3, 4}:
            continue
        slug = trainer_id.rsplit("/", 1)[-1]
        for target, payload in (
            (rct_root / f"{slug}.json", export_rct_trainer(document)),
            (runtime_root / f"{slug}.json", export_ai_runtime_profile(document)),
        ):
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        trainers.append(trainer_id)
    battle_root = root / "content" / "battles"
    for source in sorted(battle_root.rglob("*.json")) if battle_root.is_dir() else []:
        battle_id, issues = validate_battle_preset_file(source)
        if battle_id is None or any(issue.level == "error" for issue in issues):
            continue
        preset = load_json(source)
        if not preset.get("enabled", True):
            continue
        trainer_id = preset["battle"]["trainer_id"]
        document = {
            "id": trainer_id,
            "name": preset.get("name", {}),
            "battle": preset["battle"],
        }
        slug = trainer_id.rsplit("/", 1)[-1]
        for target, payload in (
            (rct_root / f"{slug}.json", export_rct_trainer(document)),
            (runtime_root / f"{slug}.json", export_ai_runtime_profile(document)),
        ):
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        trainers.append(trainer_id)
    return {"output": output.as_posix(), "trainers": trainers, "count": len(trainers)}


def _read_nbt_string(stream: io.BytesIO) -> str:
    length_data = stream.read(2)
    if len(length_data) != 2:
        raise ValueError("NBT 문자열 길이가 손상되었습니다.")
    length = struct.unpack(">H", length_data)[0]
    value = stream.read(length)
    if len(value) != length:
        raise ValueError("NBT 문자열이 손상되었습니다.")
    return value.decode("utf-8")


def _skip_nbt_payload(stream: io.BytesIO, tag_type: int) -> None:
    fixed_sizes = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
    if tag_type in fixed_sizes:
        stream.seek(fixed_sizes[tag_type], io.SEEK_CUR)
        return
    if tag_type == 7:
        length = struct.unpack(">i", stream.read(4))[0]
        stream.seek(max(0, length), io.SEEK_CUR)
        return
    if tag_type == 8:
        _read_nbt_string(stream)
        return
    if tag_type == 9:
        element_type_data = stream.read(1)
        if not element_type_data:
            raise ValueError("NBT 목록이 손상되었습니다.")
        element_type = element_type_data[0]
        length = struct.unpack(">i", stream.read(4))[0]
        if length < 0 or length > 16_000_000:
            raise ValueError("NBT 목록 길이가 올바르지 않습니다.")
        for _ in range(length):
            _skip_nbt_payload(stream, element_type)
        return
    if tag_type == 10:
        while True:
            child_type_data = stream.read(1)
            if not child_type_data:
                raise ValueError("NBT Compound가 손상되었습니다.")
            child_type = child_type_data[0]
            if child_type == 0:
                return
            _read_nbt_string(stream)
            _skip_nbt_payload(stream, child_type)
    elif tag_type in {11, 12}:
        length = struct.unpack(">i", stream.read(4))[0]
        if length < 0 or length > 16_000_000:
            raise ValueError("NBT 배열 길이가 올바르지 않습니다.")
        stream.seek(length * (4 if tag_type == 11 else 8), io.SEEK_CUR)
    else:
        raise ValueError(f"지원하지 않는 NBT 태그입니다: {tag_type}")


def _read_nbt_payload(stream: io.BytesIO, tag_type: int) -> Any:
    formats = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}
    if tag_type in formats:
        size = struct.calcsize(formats[tag_type])
        return struct.unpack(formats[tag_type], stream.read(size))[0]
    if tag_type == 7:
        length = struct.unpack(">i", stream.read(4))[0]
        if length < 0 or length > 16_000_000:
            raise ValueError("NBT byte 배열 길이가 올바르지 않습니다.")
        return stream.read(length)
    if tag_type == 8:
        return _read_nbt_string(stream)
    if tag_type == 9:
        element_type_data = stream.read(1)
        if not element_type_data:
            raise ValueError("NBT 목록이 손상되었습니다.")
        length = struct.unpack(">i", stream.read(4))[0]
        if length < 0 or length > 16_000_000:
            raise ValueError("NBT 목록 길이가 올바르지 않습니다.")
        return [_read_nbt_payload(stream, element_type_data[0]) for _ in range(length)]
    if tag_type == 10:
        value: dict[str, Any] = {}
        while True:
            child_type_data = stream.read(1)
            if not child_type_data:
                raise ValueError("NBT Compound가 손상되었습니다.")
            child_type = child_type_data[0]
            if child_type == 0:
                return value
            child_name = _read_nbt_string(stream)
            value[child_name] = _read_nbt_payload(stream, child_type)
    if tag_type in {11, 12}:
        length = struct.unpack(">i", stream.read(4))[0]
        if length < 0 or length > 16_000_000:
            raise ValueError("NBT 배열 길이가 올바르지 않습니다.")
        item_format = ">i" if tag_type == 11 else ">q"
        return [
            struct.unpack(item_format, stream.read(struct.calcsize(item_format)))[0]
            for _ in range(length)
        ]
    raise ValueError(f"지원하지 않는 NBT 태그입니다: {tag_type}")


def _read_minecraft_structure_root(data: bytes) -> dict[str, Any]:
    if data.startswith(b"\x1f\x8b"):
        data = gzip.decompress(data)
    stream = io.BytesIO(data)
    root_type_data = stream.read(1)
    if not root_type_data or root_type_data[0] != 10:
        raise ValueError("마인크래프트 구조물 NBT의 루트가 Compound가 아닙니다.")
    _read_nbt_string(stream)
    root = _read_nbt_payload(stream, 10)
    if not isinstance(root, dict):
        raise ValueError("마인크래프트 구조물 NBT의 루트 형식이 올바르지 않습니다.")
    return root


def _minecraft_structure_tag_spans(raw: bytes) -> dict[str, tuple[int, int, int]]:
    """Return top-level NBT payload spans without re-encoding nested block entities."""
    stream = io.BytesIO(raw)
    root_type = stream.read(1)
    if not root_type or root_type[0] != 10:
        raise ValueError("마인크래프트 구조물 NBT의 루트가 Compound가 아닙니다.")
    _read_nbt_string(stream)
    result: dict[str, tuple[int, int, int]] = {}
    while True:
        tag_data = stream.read(1)
        if not tag_data:
            raise ValueError("NBT Compound가 손상되었습니다.")
        tag_type = tag_data[0]
        if tag_type == 0:
            return result
        name = _read_nbt_string(stream)
        start = stream.tell()
        _skip_nbt_payload(stream, tag_type)
        result[name] = (tag_type, start, stream.tell())


def resize_minecraft_structure_nbt(data: bytes, size: tuple[int, int, int]) -> bytes:
    """Resize a structure template, cropping block records outside reduced bounds."""
    compressed = data.startswith(b"\x1f\x8b")
    raw = gzip.decompress(data) if compressed else data
    root = _read_minecraft_structure_root(raw)
    old_size = root.get("size")
    if not isinstance(old_size, list) or len(old_size) != 3:
        raise ValueError("구조물 size 태그 형식이 올바르지 않습니다.")
    width, height, depth = size
    spans = _minecraft_structure_tag_spans(raw)
    size_span = spans.get("size")
    if size_span is None or size_span[0] != 9:
        raise ValueError("구조물 size 태그를 찾을 수 없습니다.")
    _, size_start, size_end = size_span
    size_payload = raw[size_start:size_end]
    if len(size_payload) != 17 or size_payload[0] != 3 or struct.unpack(">i", size_payload[1:5])[0] != 3:
        raise ValueError("구조물 size 목록 형식이 올바르지 않습니다.")

    replacements: list[tuple[int, int, bytes]] = [(
        size_start, size_end,
        bytes([3]) + struct.pack(">i", 3) + struct.pack(">iii", width, height, depth),
    )]
    blocks_span = spans.get("blocks")
    if blocks_span is not None:
        tag_type, blocks_start, blocks_end = blocks_span
        payload = raw[blocks_start:blocks_end]
        if tag_type != 9 or len(payload) < 5 or payload[0] != 10:
            raise ValueError("구조물 blocks 목록 형식이 올바르지 않습니다.")
        count = struct.unpack(">i", payload[1:5])[0]
        stream = io.BytesIO(payload[5:])
        kept: list[bytes] = []
        for _ in range(count):
            start = stream.tell()
            block = _read_nbt_payload(stream, 10)
            encoded = payload[5 + start:5 + stream.tell()]
            position = block.get("pos") if isinstance(block, dict) else None
            if not isinstance(position, list) or len(position) != 3:
                raise ValueError("구조물 블록 위치가 손상되었습니다.")
            x, y, z = position
            if 0 <= x < width and 0 <= y < height and 0 <= z < depth:
                kept.append(encoded)
        replacements.append((
            blocks_start, blocks_end,
            bytes([10]) + struct.pack(">i", len(kept)) + b"".join(kept),
        ))

    for entity in root.get("entities", []) if isinstance(root.get("entities"), list) else []:
        if not isinstance(entity, dict):
            continue
        position = entity.get("pos")
        if isinstance(position, list) and len(position) == 3:
            x, y, z = position
            if not (0 <= x < width and 0 <= y < height and 0 <= z < depth):
                raise ValueError("축소 범위 밖에 엔티티가 있습니다. 게임에서 엔티티를 옮기거나 제거하세요.")

    resized = bytearray(raw)
    for start, end, replacement in sorted(replacements, reverse=True):
        resized[start:end] = replacement
    return gzip.compress(bytes(resized), mtime=0) if compressed else bytes(resized)


def _minecraft_structure_parts(
    data: bytes,
) -> tuple[list[int], list[str], list[dict[str, Any]]]:
    root = _read_minecraft_structure_root(data)
    size = root.get("size")
    if not isinstance(size, list) or len(size) != 3 or any(
        not isinstance(value, int) or value <= 0 or value > 512 for value in size
    ):
        raise ValueError("구조물 size 태그 형식이 올바르지 않습니다.")
    palette = root.get("palette")
    blocks = root.get("blocks")
    palette_names = [
        entry.get("Name", "minecraft:air") if isinstance(entry, dict)
        else "minecraft:air" for entry in palette
    ] if isinstance(palette, list) else []
    return size, palette_names, blocks if isinstance(blocks, list) else []


def read_minecraft_structure_metadata(data: bytes) -> dict[str, Any]:
    """Read template size, building bounds, and its visible top-down blocks."""
    size, palette_names, blocks = _minecraft_structure_parts(data)
    ignored_blocks = {
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
        "minecraft:structure_void", "minecraft:jigsaw",
        "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
        "minecraft:rooted_dirt", "minecraft:podzol", "minecraft:mycelium",
        "minecraft:mud", "minecraft:dirt_path", "minecraft:farmland",
        "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
        "minecraft:clay", "minecraft:snow", "minecraft:snow_block",
        "minecraft:moss_block", "minecraft:moss_carpet", "minecraft:short_grass",
        "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
        "minecraft:dead_bush", "minecraft:dandelion", "minecraft:poppy",
        "minecraft:blue_orchid", "minecraft:allium", "minecraft:azure_bluet",
        "minecraft:red_tulip", "minecraft:orange_tulip", "minecraft:white_tulip",
        "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower",
        "minecraft:lily_of_the_valley", "minecraft:sunflower", "minecraft:lilac",
        "minecraft:rose_bush", "minecraft:peony",
    }
    occupied: list[tuple[int, int, int]] = []
    top_columns: dict[tuple[int, int], tuple[int, str]] = {}
    cutaway_columns: dict[tuple[int, int], tuple[int, str]] = {}
    cutaway_y = max(1, (size[1] + 1) // 2)
    invisible_blocks = {
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
        "minecraft:structure_void", "minecraft:jigsaw",
    }
    if palette_names and blocks:
        for block in blocks:
            if not isinstance(block, dict):
                continue
            state = block.get("state")
            position = block.get("pos")
            if not isinstance(state, int) or not 0 <= state < len(palette_names):
                continue
            if isinstance(position, list) and len(position) == 3 and all(
                isinstance(value, int) for value in position
            ):
                x, y, z = position
                block_name = palette_names[state]
                if block_name not in ignored_blocks:
                    current = top_columns.get((x, z))
                    if current is None or y > current[0]:
                        top_columns[(x, z)] = (y, block_name)
                    if y < cutaway_y:
                        cutaway_current = cutaway_columns.get((x, z))
                        if cutaway_current is None or y > cutaway_current[0]:
                            cutaway_columns[(x, z)] = (y, block_name)
                if block_name not in ignored_blocks:
                    occupied.append((x, y, z))
    occupied_columns = {(x, z) for x, _, z in occupied}
    # Collision and preview bounds must cover every block that survives the
    # terrain-preservation processor, including one-layer paving and foliage.
    if occupied_columns:
        min_x = min(position[0] for position in occupied_columns)
        max_x = max(position[0] for position in occupied_columns)
        min_z = min(position[1] for position in occupied_columns)
        max_z = max(position[1] for position in occupied_columns)
    else:
        min_x, min_z, max_x, max_z = 0, 0, size[0] - 1, size[2] - 1
    top_palette = sorted({block_name for _, block_name in top_columns.values()})
    top_palette_indexes = {
        block_name: index for index, block_name in enumerate(top_palette)
    }
    top_blocks = [
        [x, z, y, top_palette_indexes[block_name]]
        for (x, z), (y, block_name) in sorted(
            top_columns.items(), key=lambda item: (item[0][1], item[0][0])
        )
    ]
    cutaway_palette = sorted({
        block_name for _, block_name in cutaway_columns.values()
    })
    cutaway_palette_indexes = {
        block_name: index for index, block_name in enumerate(cutaway_palette)
    }
    cutaway_blocks = [
        [x, z, y, cutaway_palette_indexes[block_name]]
        for (x, z), (y, block_name) in sorted(
            cutaway_columns.items(), key=lambda item: (item[0][1], item[0][0])
        )
    ]
    return {
        "width": size[0], "height": size[1], "depth": size[2],
        "occupied": {
            "min_x": min_x, "min_z": min_z, "max_x": max_x, "max_z": max_z,
            "width": max_x - min_x + 1, "depth": max_z - min_z + 1,
        },
        "top_view": {
            "palette": top_palette,
            "blocks": top_blocks,
        },
        "cutaway_view": {
            "cutoff_y": cutaway_y,
            "palette": cutaway_palette,
            "blocks": cutaway_blocks,
        },
    }


def read_minecraft_structure_model(data: bytes) -> dict[str, Any]:
    """Read visible block faces for the interactive NBT structure viewer."""
    size, palette_names, blocks = _minecraft_structure_parts(data)
    invisible_blocks = {
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
        "minecraft:structure_void", "minecraft:jigsaw",
    }
    occupied: dict[tuple[int, int, int], str] = {}
    for block in blocks:
        if not isinstance(block, dict):
            continue
        state = block.get("state")
        position = block.get("pos")
        if not isinstance(state, int) or not 0 <= state < len(palette_names):
            continue
        if not isinstance(position, list) or len(position) != 3 or not all(
            isinstance(value, int) for value in position
        ):
            continue
        block_name = palette_names[state]
        if block_name not in invisible_blocks:
            occupied[tuple(position)] = block_name

    directions = [
        (-1, 0, 0), (1, 0, 0), (0, -1, 0),
        (0, 1, 0), (0, 0, -1), (0, 0, 1),
    ]
    visible: list[tuple[int, int, int, str, int]] = []
    for (x, y, z), block_name in occupied.items():
        face_mask = 0
        for index, (dx, dy, dz) in enumerate(directions):
            if (x + dx, y + dy, z + dz) not in occupied:
                face_mask |= 1 << index
        if face_mask:
            visible.append((x, y, z, block_name, face_mask))
    visible.sort(key=lambda item: (item[1], item[2], item[0]))
    surface_palette = sorted({item[3] for item in visible})
    palette_indexes = {
        block_name: index for index, block_name in enumerate(surface_palette)
    }
    return {
        "width": size[0], "height": size[1], "depth": size[2],
        "palette": surface_palette,
        "blocks": [
            [x, y, z, palette_indexes[block_name], face_mask]
            for x, y, z, block_name, face_mask in visible
        ],
        "total_blocks": len(occupied),
        "surface_blocks": len(visible),
    }
def read_minecraft_structure_size(data: bytes) -> tuple[int, int, int]:
    metadata = read_minecraft_structure_metadata(data)
    return metadata["width"], metadata["height"], metadata["depth"]


_STRUCTURE_ENTRY = re.compile(r"^data/([^/]+)/structures?/(.+)\.nbt$")
STRUCTURE_VIEWER_REQUIRED_EXTERNAL = {
    "bca:default/one_off/pokecenter",
    "bca:default/one_off/structure_pokemart",
    "bca:default/centers/center_department_store",
}
BUILDING_SETTINGS_PATH = Path("content/catalogs/building-settings.json")
SPACE_CONNECTIONS_PATH = Path("content/catalogs/space-connections.json")
STRUCTURE_CATEGORY_LABELS = {
    "building": "일반 건물",
    "residential": "주택",
    "gym_exterior": "체육관 외관 템플릿",
    "gym_interior": "체육관 내부 모듈",
    "interior": "건물 내부 모듈",
    "league": "리그",
    "placeholder": "임시·특수 건물",
    "decoration": "마을 장식",
}


def managed_structure_files(root: Path) -> dict[str, Path]:
    source_root = root / "content" / "structures"
    if not source_root.is_dir():
        return {}
    return {
        f"cobbleventure:{path.relative_to(source_root).with_suffix('').as_posix()}": path
        for path in sorted(source_root.rglob("*.nbt"))
        if path.is_file()
    }


def _managed_structure_category(relative: Path) -> str:
    parts = tuple(part.lower() for part in relative.parts)
    if parts[:2] == ("interiors", "gyms"):
        return "gym_interior"
    if parts[:1] == ("interiors",):
        return "interior"
    if parts[:1] == ("gyms",):
        return "gym_exterior"
    if parts[:1] == ("houses",):
        return "residential"
    if parts[:1] == ("league",):
        return "league"
    if parts[:1] == ("placeholder",):
        return "placeholder"
    if parts[:1] == ("town_decorations",):
        return "decoration"
    return "building"


def _structure_npc_labels(path: Path) -> list[dict[str, Any]]:
    metadata_path = path.with_suffix(".structure.json")
    if not metadata_path.is_file():
        return []
    document = load_json(metadata_path)
    anchors = document.get("anchors", []) if isinstance(document, dict) else []
    labels: list[dict[str, Any]] = []
    seen: set[str] = set()
    for anchor in anchors if isinstance(anchors, list) else []:
        if not isinstance(anchor, dict) or anchor.get("type") != "npc_position":
            continue
        label = anchor.get("label")
        position = anchor.get("position")
        if (
            not isinstance(label, str)
            or not DOCUMENT_SLUG.fullmatch(label)
            or label in seen
            or not isinstance(position, list)
            or len(position) != 3
            or any(not isinstance(value, int) or isinstance(value, bool) for value in position)
        ):
            continue
        seen.add(label)
        labels.append({"label": label, "position": position})
    return labels


def _structure_named_anchors(path: Path, anchor_types: set[str]) -> list[dict[str, Any]]:
    metadata_path = path.with_suffix(".structure.json")
    if not metadata_path.is_file():
        return []
    document = load_json(metadata_path)
    anchors = document.get("anchors", []) if isinstance(document, dict) else []
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for anchor in anchors if isinstance(anchors, list) else []:
        if not isinstance(anchor, dict) or anchor.get("type") not in anchor_types:
            continue
        label = anchor.get("label", anchor.get("id"))
        position = anchor.get("position")
        if (
            not isinstance(label, str) or not DOCUMENT_SLUG.fullmatch(label)
            or label in seen or not isinstance(position, list) or len(position) != 3
            or any(not isinstance(value, int) or isinstance(value, bool) for value in position)
        ):
            continue
        seen.add(label)
        entry = {"label": label, "position": position}
        if isinstance(anchor.get("safe_spawn"), list):
            entry["safe_spawn"] = anchor["safe_spawn"]
        result.append(entry)
    return result


def _default_building_settings() -> dict[str, Any]:
    return {"schema_version": 1, "buildings": {}}


def load_building_settings(root: Path) -> dict[str, Any]:
    path = root / BUILDING_SETTINGS_PATH
    document = load_json(path) if path.is_file() else _default_building_settings()
    if not isinstance(document, dict) or document.get("schema_version") != 1:
        raise ValueError("건물 설정 schema_version은 1이어야 합니다.")
    buildings = document.get("buildings", {})
    if not isinstance(buildings, dict):
        raise ValueError("건물 설정 buildings는 객체여야 합니다.")
    return {"schema_version": 1, "buildings": buildings}


def _space_graph_position(
    layouts: dict[str, Any], graph_id: str, node_id: str, fallback: list[int]
) -> list[int]:
    graph = layouts.get(graph_id, {}) if isinstance(layouts, dict) else {}
    nodes = graph.get("nodes", {}) if isinstance(graph, dict) else {}
    value = nodes.get(node_id) if isinstance(nodes, dict) else None
    if (
        isinstance(value, list) and len(value) == 2
        and all(isinstance(item, int) and not isinstance(item, bool) for item in value)
    ):
        return value
    return fallback


def space_connections_payload(
    root: Path, structure_payload: dict[str, Any] | None = None
) -> dict[str, Any]:
    """Build the visual graph from the two legacy runtime catalogs."""
    saved_path = root / SPACE_CONNECTIONS_PATH
    saved = load_json(saved_path) if saved_path.is_file() else {}
    layouts = saved.get("layouts", {}) if isinstance(saved, dict) else {}
    annotations = saved.get("annotations", {}) if isinstance(saved, dict) else {}
    full_structures = (
        structure_payload if isinstance(structure_payload, dict)
        else building_settings_payload(root)
    ).get("structures", {})
    structures = {
        resource_id: {
            key: metadata[key]
            for key in (
                "category", "category_label", "width", "height", "depth",
                "door_anchors", "arrival_anchors", "cutaway_view",
            )
            if key in metadata
        }
        for resource_id, metadata in full_structures.items()
    }
    settings = load_building_settings(root)["buildings"]
    graphs: list[dict[str, Any]] = []

    for exterior_id, entry in sorted(settings.items()):
        if exterior_id not in structures or not isinstance(entry, dict):
            continue
        graph_id = f"building:{exterior_id}"
        nodes = [{
            "id": "exterior", "kind": "exterior", "structure": exterior_id,
            "position": _space_graph_position(layouts, graph_id, "exterior", [90, 170]),
        }]
        for index, interior in enumerate(entry.get("interiors", [])):
            if not isinstance(interior, dict):
                continue
            node_id = interior.get("key")
            if not isinstance(node_id, str):
                continue
            nodes.append({
                "id": node_id, "kind": "interior", "structure": interior.get("structure", ""),
                "position": _space_graph_position(
                    layouts, graph_id, node_id, [470 + (index % 3) * 340, 90 + (index // 3) * 260]
                ),
            })
        connections = []
        for index, (source, target) in enumerate(sorted(entry.get("door_routes", {}).items())):
            if not isinstance(target, dict) or ":" not in source:
                continue
            source_node, source_anchor = source.split(":", 1)
            edge_id = f"route_{index + 1}"
            note = annotations.get(graph_id, {}).get(edge_id, {}) if isinstance(annotations, dict) else {}
            connections.append({
                "id": edge_id,
                "from": {"node": source_node, "anchor": source_anchor},
                "to": {"node": target.get("space", ""), "anchor": target.get("arrival", "")},
                **(note if isinstance(note, dict) else {}),
            })
        graphs.append({
            "id": graph_id, "kind": "building", "owner": exterior_id,
            "display_name": exterior_id, "nodes": nodes, "connections": connections,
        })

    gyms_path = root / "content" / "catalogs" / "gyms.json"
    gym_catalog = load_json(gyms_path) if gyms_path.is_file() else {"gyms": []}
    for gym in gym_catalog.get("gyms", []) if isinstance(gym_catalog, dict) else []:
        if not isinstance(gym, dict) or not isinstance(gym.get("id"), str):
            continue
        graph_id = f"gym:{gym['id']}"
        exterior = gym.get("exterior", {})
        nodes = [{
            "id": "exterior", "kind": "exterior", "structure": exterior.get("structure", ""),
            "position": _space_graph_position(layouts, graph_id, "exterior", [90, 170]),
        }]
        modules = gym.get("interior", {}).get("modules", [])
        for index, module in enumerate(modules if isinstance(modules, list) else []):
            if not isinstance(module, dict) or not isinstance(module.get("id"), str):
                continue
            nodes.append({
                "id": module["id"], "kind": "interior", "structure": module.get("structure", ""),
                "position": _space_graph_position(
                    layouts, graph_id, module["id"], [470 + (index % 3) * 340, 90 + (index // 3) * 260]
                ),
                "world_position": module.get("position", [0, 0, index * 32]),
                "rotation": module.get("rotation", "none"),
            })
        connections = []
        for index, connection in enumerate(gym.get("interior", {}).get("connections", [])):
            if not isinstance(connection, dict):
                continue
            source = str(connection.get("from", ""))
            target = str(connection.get("to", ""))
            source_node, _, source_anchor = source.partition(":")
            target_node, _, target_anchor = target.partition(":")
            edge_id = f"route_{index + 1}"
            note = annotations.get(graph_id, {}).get(edge_id, {}) if isinstance(annotations, dict) else {}
            connections.append({
                "id": edge_id,
                "from": {"node": source_node, "anchor": source_anchor},
                "to": {"node": target_node, "anchor": target_anchor},
                **(note if isinstance(note, dict) else {}),
            })
        name = gym.get("display_name", {}).get("ko_kr", gym["id"])
        graphs.append({
            "id": graph_id, "kind": "gym", "owner": gym["id"],
            "display_name": name, "nodes": nodes, "connections": connections,
        })
    return {
        "schema_version": 1, "graphs": graphs, "structures": structures,
        "path": SPACE_CONNECTIONS_PATH.as_posix(),
    }


def save_space_connections(root: Path, data: Any) -> list[Issue]:
    path = root / SPACE_CONNECTIONS_PATH
    issues: list[Issue] = []
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        return [Issue("error", path.as_posix(), "$.schema_version", "버전 1이 필요합니다.")]
    graphs = data.get("graphs")
    if not isinstance(graphs, list):
        return [Issue("error", path.as_posix(), "$.graphs", "공간 연결 그래프 배열이 필요합니다.")]

    building_document = load_building_settings(root)
    building_settings = building_document["buildings"]
    gyms_path = root / "content" / "catalogs" / "gyms.json"
    gym_catalog = load_json(gyms_path) if gyms_path.is_file() else {"schema_version": 1, "gyms": [], "leagues": []}
    gyms_by_id = {
        gym.get("id"): gym for gym in gym_catalog.get("gyms", [])
        if isinstance(gym, dict) and isinstance(gym.get("id"), str)
    }
    layouts: dict[str, Any] = {}
    annotations: dict[str, Any] = {}
    seen_graphs: set[str] = set()
    for graph_index, graph in enumerate(graphs):
        graph_path = f"$.graphs[{graph_index}]"
        if not isinstance(graph, dict):
            _issue(issues, "error", path, graph_path, "그래프는 객체여야 합니다.")
            continue
        graph_id = graph.get("id")
        kind = graph.get("kind")
        owner = graph.get("owner")
        nodes = graph.get("nodes")
        connections = graph.get("connections", [])
        if not isinstance(graph_id, str) or graph_id in seen_graphs:
            _issue(issues, "error", path, f"{graph_path}.id", "중복되지 않는 그래프 ID가 필요합니다.")
            continue
        seen_graphs.add(graph_id)
        if kind not in {"building", "gym"} or not isinstance(owner, str):
            _issue(issues, "error", path, graph_path, "건물 또는 체육관 소유자가 필요합니다.")
            continue
        if not isinstance(nodes, list) or not isinstance(connections, list):
            _issue(issues, "error", path, graph_path, "노드와 연결선 배열이 필요합니다.")
            continue
        normalized_nodes: list[dict[str, Any]] = []
        node_ids: set[str] = set()
        for node_index, node in enumerate(nodes):
            node_path = f"{graph_path}.nodes[{node_index}]"
            if not isinstance(node, dict):
                _issue(issues, "error", path, node_path, "공간 노드는 객체여야 합니다.")
                continue
            node_id = node.get("id")
            position = node.get("position")
            if not isinstance(node_id, str) or not DOCUMENT_SLUG.fullmatch(node_id) or node_id in node_ids:
                _issue(issues, "error", path, f"{node_path}.id", "중복되지 않는 소문자 공간 키가 필요합니다.")
                continue
            if not (isinstance(position, list) and len(position) == 2 and all(isinstance(v, int) for v in position)):
                _issue(issues, "error", path, f"{node_path}.position", "캔버스 X/Y 좌표가 필요합니다.")
                continue
            node_ids.add(node_id)
            normalized_nodes.append(node)
        if "exterior" not in node_ids:
            _issue(issues, "error", path, f"{graph_path}.nodes", "외부 공간 노드가 필요합니다.")
        layouts[graph_id] = {"nodes": {node["id"]: node["position"] for node in normalized_nodes}}
        edge_notes: dict[str, Any] = {}
        normalized_connections: list[dict[str, Any]] = []
        for edge_index, edge in enumerate(connections):
            edge_path = f"{graph_path}.connections[{edge_index}]"
            if not isinstance(edge, dict) or not isinstance(edge.get("from"), dict) or not isinstance(edge.get("to"), dict):
                _issue(issues, "error", path, edge_path, "연결선의 출발·도착 포트가 필요합니다.")
                continue
            source, target = edge["from"], edge["to"]
            if source.get("node") not in node_ids or target.get("node") not in node_ids:
                _issue(issues, "error", path, edge_path, "연결선이 존재하지 않는 공간을 가리킵니다.")
                continue
            if not all(isinstance(value, str) and DOCUMENT_SLUG.fullmatch(value) for value in (source.get("anchor"), target.get("anchor"))):
                _issue(issues, "error", path, edge_path, "출발 문과 도착 지점 이름이 필요합니다.")
                continue
            normalized_connections.append(edge)
            edge_id = str(edge.get("id", f"route_{edge_index + 1}"))
            edge_notes[edge_id] = {
                key: edge[key] for key in ("condition_mode", "conditions", "locked_dialogue", "enter_dialogue")
                if key in edge
            }
        annotations[graph_id] = edge_notes
        interiors = [node for node in normalized_nodes if node["id"] != "exterior"]
        if kind == "building":
            current = building_settings.get(owner, {})
            building_settings[owner] = {
                "fixed_npcs": current.get("fixed_npcs", {}) if isinstance(current, dict) else {},
                "citizen_placement_allowed": bool(current.get("citizen_placement_allowed", False)) if isinstance(current, dict) else False,
                "interiors": [{"key": node["id"], "structure": node.get("structure", "")} for node in interiors],
                "door_routes": {
                    f"{edge['from']['node']}:{edge['from']['anchor']}": {
                        "space": edge["to"]["node"], "arrival": edge["to"]["anchor"]
                    } for edge in normalized_connections
                },
            }
        else:
            gym = gyms_by_id.get(owner)
            if gym is None:
                _issue(issues, "error", path, f"{graph_path}.owner", "존재하지 않는 체육관입니다.")
                continue
            gym["interior"] = {
                "modules": [{
                    "id": node["id"], "structure": node.get("structure", ""),
                    "position": node.get("world_position", [0, 0, 0]),
                    "rotation": node.get("rotation", "none"),
                } for node in interiors],
                "connections": [{
                    "from": f"{edge['from']['node']}:{edge['from']['anchor']}",
                    "to": f"{edge['to']['node']}:{edge['to']['anchor']}",
                    **{
                        key: edge[key]
                        for key in ("condition_mode", "conditions", "locked_dialogue", "enter_dialogue")
                        if key in edge
                    },
                } for edge in normalized_connections],
            }
    if any(issue.level == "error" for issue in issues):
        return issues
    building_issues = save_building_settings(root, building_document)
    gym_issues = save_gym_catalog(root, gym_catalog)
    issues.extend(building_issues)
    issues.extend(gym_issues)
    if any(issue.level == "error" for issue in issues):
        return issues
    document = {
        "$schema": "../schemas/space-connections.schema.json", "schema_version": 1,
        "layouts": layouts, "annotations": annotations,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)
    return issues


def building_settings_payload(root: Path) -> dict[str, Any]:
    settings = load_building_settings(root)
    configured = settings["buildings"]
    structures: dict[str, dict[str, Any]] = {}
    for resource_id, path in managed_structure_files(root).items():
        metadata = read_minecraft_structure_metadata(path.read_bytes())
        relative = path.relative_to(root / "content" / "structures")
        residential = bool(relative.parts and relative.parts[0] == "houses")
        category = _managed_structure_category(relative)
        entry = configured.get(resource_id, {})
        if not isinstance(entry, dict):
            entry = {}
        structures[resource_id] = {
            **metadata,
            "source": path.relative_to(root).as_posix(),
            "category": category,
            "category_label": STRUCTURE_CATEGORY_LABELS[category],
            "npc_labels": _structure_npc_labels(path),
            "door_anchors": _structure_named_anchors(
                path, {"door", "interior_entry", "interior_exit"}
            ),
            "arrival_anchors": _structure_named_anchors(
                path, {"arrival", "interior_spawn", "exterior_spawn"}
            ),
            "residential": residential,
            "settings": {
                "placement_y_offset": entry.get("placement_y_offset", 0)
                if isinstance(entry.get("placement_y_offset", 0), int)
                and not isinstance(entry.get("placement_y_offset", 0), bool) else 0,
                "fixed_npcs": entry.get("fixed_npcs", {})
                if isinstance(entry.get("fixed_npcs", {}), dict) else {},
                "citizen_placement_allowed": bool(entry.get(
                    "citizen_placement_allowed",
                    entry.get("random_citizen_eligible", residential),
                )),
                "interiors": entry.get("interiors", [])
                if isinstance(entry.get("interiors", []), list) else [],
                "door_routes": entry.get("door_routes", {})
                if isinstance(entry.get("door_routes", {}), dict) else {},
            },
        }
    return {
        "schema_version": 1,
        "structures": structures,
        "npcs": _list_documents(root, "trainers"),
        "path": BUILDING_SETTINGS_PATH.as_posix(),
    }


def resize_managed_structure(
    root: Path, resource_id: str, width: int, height: int, depth: int,
) -> dict[str, Any]:
    structures = managed_structure_files(root)
    path = structures.get(resource_id)
    if path is None:
        raise ValueError("관리 대상 NBT 구조물이 아닙니다.")
    if any(isinstance(value, bool) or not isinstance(value, int) for value in (width, height, depth)):
        raise ValueError("너비·높이·깊이는 정수여야 합니다.")
    relative = path.relative_to(root / "content" / "structures")
    is_league = bool(relative.parts and relative.parts[0] == "league")
    is_interior = bool(relative.parts and relative.parts[0] == "interiors")
    max_width_depth = 512 if is_league else 64
    if not 1 <= width <= max_width_depth or not 1 <= depth <= max_width_depth:
        raise ValueError(f"너비와 깊이는 1~{max_width_depth} 범위여야 합니다.")
    max_height = 512 if is_league else 80 if is_interior else 240
    if not 1 <= height <= max_height:
        raise ValueError(f"높이는 1~{max_height} 범위여야 합니다.")

    sidecar = path.with_suffix(".structure.json")
    sidecar_document: dict[str, Any] | None = None
    if sidecar.is_file():
        sidecar_document = load_json(sidecar)
        for index, anchor in enumerate(sidecar_document.get("anchors", [])):
            if not isinstance(anchor, dict):
                continue
            for field in ("position", "safe_spawn"):
                position = anchor.get(field)
                if not isinstance(position, list) or len(position) != 3:
                    continue
                x, y, z = position
                if not (0 <= x < width and 0 <= y < height and 0 <= z < depth):
                    label = anchor.get("label", anchor.get("id", index))
                    raise ValueError(
                        f"축소 범위 밖에 앵커가 있습니다: {label}.{field}={position}"
                    )
        interior = sidecar_document.get("interior")
        if isinstance(interior, dict):
            if width < 5 or depth < 5:
                raise ValueError("내부공간의 너비와 깊이는 5 이상이어야 합니다.")
            floors = interior.get("floors", 1)
            if not isinstance(floors, int) or floors < 1 or height % floors != 0:
                raise ValueError("내부공간 높이는 현재 층수로 정확히 나누어져야 합니다.")
            floor_height = height // floors
            if not 3 <= floor_height <= 12:
                raise ValueError("층당 높이는 3~12 블록이어야 합니다. 층수 설정을 먼저 확인하세요.")
            interior.update({
                "width": width, "depth": depth,
                "floor_height": floor_height, "floors": floors,
            })

    resized = resize_minecraft_structure_nbt(path.read_bytes(), (width, height, depth))
    temporary = path.with_name(path.name + ".resize.tmp")
    temporary.write_bytes(resized)
    temporary.replace(path)
    if sidecar_document is not None:
        sidecar_temporary = sidecar.with_name(sidecar.name + ".resize.tmp")
        sidecar_temporary.write_text(
            json.dumps(sidecar_document, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        sidecar_temporary.replace(sidecar)
    return {
        "structure": resource_id,
        "source": path.relative_to(root).as_posix(),
        **read_minecraft_structure_metadata(resized),
    }


def save_building_settings(root: Path, data: Any) -> list[Issue]:
    path = root / BUILDING_SETTINGS_PATH
    issues: list[Issue] = []
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        return [Issue("error", path.as_posix(), "$.schema_version", "버전 1이 필요합니다.")]
    buildings = data.get("buildings")
    if not isinstance(buildings, dict):
        return [Issue("error", path.as_posix(), "$.buildings", "건물 설정 객체가 필요합니다.")]
    structure_files = managed_structure_files(root)
    npc_ids = {item.get("id") for item in _list_documents(root, "trainers") if item.get("id")}
    normalized: dict[str, Any] = {}
    for resource_id, settings in sorted(buildings.items()):
        entry_path = f"$.buildings.{resource_id}"
        structure = structure_files.get(resource_id)
        if structure is None:
            _issue(issues, "error", path, entry_path, "관리 대상 NBT 구조물이 아닙니다.")
            continue
        if not isinstance(settings, dict):
            _issue(issues, "error", path, entry_path, "건물 설정은 객체여야 합니다.")
            continue
        relative = structure.relative_to(root / "content" / "structures")
        residential = bool(relative.parts and relative.parts[0] == "houses")
        interiors = settings.get("interiors", [])
        if not isinstance(interiors, list):
            _issue(issues, "error", path, f"{entry_path}.interiors", "내부공간 목록은 배열이어야 합니다.")
            continue
        normalized_interiors: list[dict[str, str]] = []
        interior_spaces: dict[str, Path] = {}
        for index, value in enumerate(interiors):
            value_path = f"{entry_path}.interiors[{index}]"
            if not isinstance(value, dict):
                _issue(issues, "error", path, value_path, "내부공간 연결은 객체여야 합니다.")
                continue
            key = value.get("key")
            interior_resource = value.get("structure")
            interior_file = structure_files.get(interior_resource) if isinstance(interior_resource, str) else None
            if not isinstance(key, str) or not DOCUMENT_SLUG.fullmatch(key) or key in interior_spaces:
                _issue(issues, "error", path, f"{value_path}.key", "중복되지 않는 소문자 공간 키가 필요합니다.")
                continue
            if interior_file is None or _managed_structure_category(
                interior_file.relative_to(root / "content" / "structures")
            ) not in {"interior", "gym_interior"}:
                _issue(issues, "error", path, f"{value_path}.structure", "내부공간 NBT를 선택해야 합니다.")
                continue
            interior_spaces[key] = interior_file
            normalized_interiors.append({"key": key, "structure": interior_resource})

        space_files = {"exterior": structure, **interior_spaces}
        door_labels = {
            space: {item["label"] for item in _structure_named_anchors(
                space_file, {"door", "interior_entry", "interior_exit"}
            )}
            for space, space_file in space_files.items()
        }
        arrival_labels = {
            space: {item["label"] for item in _structure_named_anchors(
                space_file, {"arrival", "interior_spawn", "exterior_spawn"}
            )}
            for space, space_file in space_files.items()
        }
        routes = settings.get("door_routes", {})
        if not isinstance(routes, dict):
            _issue(issues, "error", path, f"{entry_path}.door_routes", "문 연결 설정은 객체여야 합니다.")
            routes = {}
        normalized_routes: dict[str, dict[str, str]] = {}
        for source_key, destination in sorted(routes.items()):
            route_path = f"{entry_path}.door_routes.{source_key}"
            if not isinstance(source_key, str) or ":" not in source_key:
                _issue(issues, "error", path, route_path, "문 키는 공간:문이름 형식이어야 합니다.")
                continue
            source_space, source_door = source_key.split(":", 1)
            if source_space not in door_labels or source_door not in door_labels[source_space]:
                _issue(issues, "error", path, route_path, "NBT에 없는 문입니다.")
                continue
            if not isinstance(destination, dict):
                _issue(issues, "error", path, route_path, "문 목적지는 객체여야 합니다.")
                continue
            target_space = destination.get("space")
            target_arrival = destination.get("arrival")
            if target_space not in arrival_labels or target_arrival not in arrival_labels[target_space]:
                _issue(issues, "error", path, route_path, "존재하는 공간의 도착 지점을 선택해야 합니다.")
                continue
            normalized_routes[source_key] = {"space": target_space, "arrival": target_arrival}
        fixed = settings.get("fixed_npcs", {})
        if not isinstance(fixed, dict):
            _issue(issues, "error", path, f"{entry_path}.fixed_npcs", "고정 NPC 배정은 객체여야 합니다.")
            continue
        labels = {
            f"{space}:{item['label']}"
            for space, space_file in space_files.items()
            for item in _structure_npc_labels(space_file)
        }
        labels.update(item["label"] for item in _structure_npc_labels(structure))
        normalized_fixed: dict[str, str] = {}
        for label, npc_id in sorted(fixed.items()):
            if label not in labels:
                _issue(issues, "error", path, f"{entry_path}.fixed_npcs.{label}", "NBT에 없는 NPC 라벨입니다.")
            elif not isinstance(npc_id, str) or npc_id not in npc_ids:
                _issue(issues, "error", path, f"{entry_path}.fixed_npcs.{label}", "존재하는 NPC 콘텐츠를 선택해야 합니다.")
            else:
                normalized_fixed[label] = npc_id
        citizen_placement_allowed = bool(settings.get(
            "citizen_placement_allowed",
            settings.get("random_citizen_eligible", residential),
        ))
        placement_y_offset = settings.get("placement_y_offset", 0)
        if (isinstance(placement_y_offset, bool)
                or not isinstance(placement_y_offset, int)
                or not -64 <= placement_y_offset <= 64):
            _issue(
                issues, "error", path, f"{entry_path}.placement_y_offset",
                "Y 배치 보정값은 -64~64 범위의 정수여야 합니다.",
            )
            continue
        if citizen_placement_allowed and normalized_fixed:
            _issue(
                issues, "error", path, f"{entry_path}.fixed_npcs",
                "시민 수용 건물에는 고정 NPC를 배정하지 않습니다.",
            )
        normalized[resource_id] = {
            "placement_y_offset": placement_y_offset,
            "fixed_npcs": {} if citizen_placement_allowed else normalized_fixed,
            "citizen_placement_allowed": citizen_placement_allowed,
            "interiors": normalized_interiors,
            "door_routes": normalized_routes,
        }
    if any(issue.level == "error" for issue in issues):
        return issues
    document = {"schema_version": 1, "buildings": normalized}
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(
        prefix=f".{path.stem}-", suffix=".json.tmp", dir=path.parent
    )
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as output:
            json.dump(document, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.replace(temporary_name, path)
    finally:
        Path(temporary_name).unlink(missing_ok=True)
    return issues


def structure_mod_roots(root: Path) -> list[Path]:
    roots = [root / "pack" / "overrides" / "development-placeholder" / "mods"]
    instance_override = os.environ.get("COBBLEVERSE_INSTANCE")
    if instance_override:
        roots.append(Path(instance_override) / "mods")
    try:
        builder_instance = _load_structure_builder_settings(root)["instance_path"]
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError):
        builder_instance = ""
    if builder_instance:
        roots.append(Path(builder_instance) / "mods")
    roots.append(
        Path.home() / "curseforge" / "minecraft" / "Instances"
        / "COBBLEVERSE - Pokemon Adventure [Cobblemon]" / "mods"
    )
    return list(dict.fromkeys(path.resolve() for path in roots))


def load_structure_size_catalog(
    root: Path, core_root: Path | None = None
) -> dict[str, Any]:
    core_root = (core_root or root).resolve()
    structures: dict[str, dict[str, Any]] = {}
    warnings: list[str] = []

    def add_structure(
        resource_id: str, data: bytes, source: str, *, overwrite: bool = True
    ) -> None:
        if not overwrite and resource_id in structures:
            return
        try:
            metadata = read_minecraft_structure_metadata(data)
        except (OSError, EOFError, ValueError, struct.error) as error:
            warnings.append(f"{resource_id}: {error}")
            return
        structures[resource_id] = {**metadata, "source": source}

    resource_roots = [
        core_root / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources",
        core_root / "projects" / "cobbleventure-world-bootstrap" / "src" / "generated" / "resources",
    ]
    for resource_root in resource_roots:
        if not resource_root.is_dir():
            continue
        for path in resource_root.glob("data/*/structure*/**/*.nbt"):
            relative = path.relative_to(resource_root).as_posix()
            match = _STRUCTURE_ENTRY.fullmatch(relative)
            if match:
                add_structure(
                    f"{match.group(1)}:{match.group(2)}", path.read_bytes(),
                    path.relative_to(core_root).as_posix()
                )

    for mod_root in structure_mod_roots(core_root):
        if not mod_root.is_dir():
            continue
        for archive_path in sorted(mod_root.glob("*.jar")):
            try:
                with zipfile.ZipFile(archive_path) as archive:
                    for entry in archive.infolist():
                        match = _STRUCTURE_ENTRY.fullmatch(entry.filename)
                        if match:
                            add_structure(
                                f"{match.group(1)}:{match.group(2)}",
                                archive.read(entry), archive_path.name,
                                overwrite=False,
                            )
            except (OSError, zipfile.BadZipFile) as error:
                warnings.append(f"{archive_path.name}: {error}")
    return {"structures": structures, "warnings": warnings}


def load_structure_viewer_catalog(
    root: Path, full_catalog: dict[str, Any] | None = None,
    core_root: Path | None = None,
) -> dict[str, dict[str, Any]]:
    core_root = (core_root or root).resolve()
    viewer: dict[str, dict[str, Any]] = {}
    for resource_id, path in managed_structure_files(root).items():
        try:
            metadata = read_minecraft_structure_metadata(path.read_bytes())
        except (OSError, EOFError, ValueError, struct.error):
            continue
        viewer[resource_id] = {
            **metadata,
            "source": path.relative_to(root).as_posix(),
            "managed": True,
        }
    if full_catalog is not None:
        structures = full_catalog.get("structures", {}) if isinstance(full_catalog, dict) else {}
        for resource_id in sorted(STRUCTURE_VIEWER_REQUIRED_EXTERNAL):
            metadata = structures.get(resource_id) if isinstance(structures, dict) else None
            if isinstance(metadata, dict):
                viewer[resource_id] = {**metadata, "managed": False}
        return viewer
    missing = set(STRUCTURE_VIEWER_REQUIRED_EXTERNAL)
    for mod_root in structure_mod_roots(core_root):
        if not missing or not mod_root.is_dir():
            continue
        for archive_path in sorted(mod_root.glob("*.jar")):
            if not missing:
                break
            try:
                with zipfile.ZipFile(archive_path) as archive:
                    for resource_id in sorted(missing):
                        namespace, structure_path = resource_id.split(":", 1)
                        for entry_name in (
                            f"data/{namespace}/structure/{structure_path}.nbt",
                            f"data/{namespace}/structures/{structure_path}.nbt",
                        ):
                            try:
                                data = archive.read(entry_name)
                            except KeyError:
                                continue
                            metadata = read_minecraft_structure_metadata(data)
                            viewer[resource_id] = {
                                **metadata,
                                "source": archive_path.name,
                                "managed": False,
                            }
                            missing.remove(resource_id)
                            break
            except (OSError, EOFError, ValueError, struct.error, zipfile.BadZipFile):
                continue
    return viewer


def load_structure_model(
    root: Path, resource_id: str, core_root: Path | None = None
) -> dict[str, Any] | None:
    core_root = (core_root or root).resolve()
    match = re.fullmatch(r"([a-z0-9_.-]+):([a-z0-9_./-]+)", resource_id)
    if not match:
        raise ValueError("올바른 구조물 리소스 ID가 아닙니다.")
    namespace, structure_path = match.groups()
    managed_path = managed_structure_files(root).get(resource_id)
    if managed_path is not None:
        return {
            **read_minecraft_structure_model(managed_path.read_bytes()),
            "source": managed_path.relative_to(root).as_posix(),
        }
    entry_names = [
        f"data/{namespace}/structure/{structure_path}.nbt",
        f"data/{namespace}/structures/{structure_path}.nbt",
    ]
    resource_roots = [
        core_root / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources",
        core_root / "projects" / "cobbleventure-world-bootstrap" / "src" / "generated" / "resources",
    ]
    for resource_root in resource_roots:
        for entry_name in entry_names:
            path = resource_root / entry_name
            if path.is_file():
                return {
                    **read_minecraft_structure_model(path.read_bytes()),
                    "source": path.relative_to(core_root).as_posix(),
                }

    for mod_root in structure_mod_roots(core_root):
        if not mod_root.is_dir():
            continue
        for archive_path in sorted(mod_root.glob("*.jar")):
            try:
                with zipfile.ZipFile(archive_path) as archive:
                    for entry_name in entry_names:
                        try:
                            data = archive.read(entry_name)
                        except KeyError:
                            continue
                        return {
                            **read_minecraft_structure_model(data),
                            "source": archive_path.name,
                        }
            except (OSError, zipfile.BadZipFile):
                continue
    return None


def structure_catalog_signature(
    root: Path, core_root: Path | None = None
) -> tuple[tuple[str, int, int], ...]:
    """Return a cheap fingerprint for NBT resources and archives used by preview."""
    core_root = (core_root or root).resolve()
    candidates: list[Path] = []
    managed_files = list(managed_structure_files(root).values())
    candidates.extend(managed_files)
    candidates.extend(
        path.with_suffix(".structure.json")
        for path in managed_files
        if path.with_suffix(".structure.json").is_file()
    )
    for resource_root in [
        core_root / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources",
        core_root / "projects" / "cobbleventure-world-bootstrap" / "src" / "generated" / "resources",
    ]:
        if resource_root.is_dir():
            candidates.extend(resource_root.glob("data/*/structure*/**/*.nbt"))
    for mod_root in structure_mod_roots(core_root):
        if mod_root.is_dir():
            candidates.extend(mod_root.glob("*.jar"))
    signature = []
    for path in sorted(candidates, key=lambda candidate: candidate.as_posix()):
        try:
            stat = path.stat()
        except OSError:
            continue
        signature.append((path.as_posix(), stat.st_size, stat.st_mtime_ns))
    return tuple(signature)


STRUCTURE_WEB_CACHE_VERSION = 2
STRUCTURE_WEB_CACHE_PATH = Path("tools/content-manager/.cache/structure-web-catalog.json")


def structure_web_cache_path(root: Path, cache_root: Path | None = None) -> Path:
    if cache_root is None:
        return root / STRUCTURE_WEB_CACHE_PATH
    project_key = hashlib.sha1(str(root.resolve()).encode("utf-8")).hexdigest()[:12]
    return cache_root / STRUCTURE_WEB_CACHE_PATH.parent / f"structure-web-{project_key}.json"


def load_structure_web_cache(
    root: Path, cache_root: Path | None = None
) -> dict[str, Any] | None:
    path = structure_web_cache_path(root, cache_root)
    if not path.is_file():
        return None
    try:
        document = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError):
        return None
    if (
        not isinstance(document, dict)
        or document.get("version") != STRUCTURE_WEB_CACHE_VERSION
        or not isinstance(document.get("size_catalog"), dict)
        or not isinstance(document.get("viewer_catalog"), dict)
        or not isinstance(document.get("building_settings"), dict)
    ):
        return None
    return document


def save_structure_web_cache(
    root: Path, document: dict[str, Any], cache_root: Path | None = None
) -> None:
    path = structure_web_cache_path(root, cache_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(
        prefix=f".{path.stem}-", suffix=".json.tmp", dir=path.parent
    )
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as output:
            json.dump(document, output, ensure_ascii=False, separators=(",", ":"))
            output.write("\n")
        os.replace(temporary_name, path)
    finally:
        Path(temporary_name).unlink(missing_ok=True)


def build_structure_web_cache(
    root: Path, core_root: Path | None = None
) -> dict[str, Any]:
    signature = structure_catalog_signature(root, core_root)
    size_catalog = load_structure_size_catalog(root, core_root)
    return {
        "version": STRUCTURE_WEB_CACHE_VERSION,
        "generated_at": int(time.time()),
        "signature": [list(entry) for entry in signature],
        "size_catalog": size_catalog,
        "viewer_catalog": load_structure_viewer_catalog(root, size_catalog, core_root),
        "building_settings": building_settings_payload(root),
    }


def create_handler(
    root: Path, project_path: Path | None = None
) -> type[BaseHTTPRequestHandler]:
    core_root = root.resolve()
    default_project_root = core_root / DEFAULT_PROJECT_RELATIVE_PATH
    active_project = resolve_content_project(core_root, project_path)
    root = active_project.root
    web_root = (Path(__file__).parent / "web").resolve()

    def project_cache_root(project_root: Path) -> Path | None:
        return core_root if project_root.resolve() != core_root else None

    saved_structure_cache = load_structure_web_cache(root, project_cache_root(root))
    build_lock = threading.Lock()
    editor_catalog_lock = threading.Lock()
    editor_catalog: dict[str, Any] | None = None
    structure_size_catalog_lock = threading.Lock()
    structure_size_catalog: dict[str, Any] | None = (
        saved_structure_cache.get("size_catalog") if saved_structure_cache else None
    )
    structure_viewer_catalog: dict[str, dict[str, Any]] | None = (
        saved_structure_cache.get("viewer_catalog") if saved_structure_cache else None
    )
    building_settings_catalog: dict[str, Any] | None = (
        saved_structure_cache.get("building_settings") if saved_structure_cache else None
    )
    structure_cache_generated_at = (
        int(saved_structure_cache.get("generated_at", 0)) if saved_structure_cache else 0
    )
    structure_cache_generation = 1 if saved_structure_cache else 0
    structure_cache_error: str | None = None
    structure_viewer_catalog_lock = threading.Lock()
    structure_cache_refresh_lock = threading.Lock()
    structure_model_cache: dict[str, dict[str, Any]] = {}
    remote_image_cache: dict[str, bytes] = {}
    remote_image_cache_lock = threading.Lock()
    project_lock = threading.Lock()

    def activate_project(project_path: Path) -> ContentProject:
        nonlocal root, active_project, editor_catalog
        nonlocal structure_size_catalog, structure_viewer_catalog
        nonlocal building_settings_catalog, structure_cache_generated_at
        nonlocal structure_cache_generation, structure_cache_error
        project = load_content_project(
            project_path, default_root=default_project_root, require_manifest=True
        )
        with project_lock, structure_cache_refresh_lock:
            root = project.root
            active_project = project
            saved_cache = load_structure_web_cache(root, project_cache_root(root))
            with editor_catalog_lock:
                editor_catalog = None
            with structure_size_catalog_lock, structure_viewer_catalog_lock:
                structure_size_catalog = (
                    saved_cache.get("size_catalog") if saved_cache else None
                )
                structure_viewer_catalog = (
                    saved_cache.get("viewer_catalog") if saved_cache else None
                )
                building_settings_catalog = (
                    saved_cache.get("building_settings") if saved_cache else None
                )
                structure_cache_generated_at = (
                    int(saved_cache.get("generated_at", 0)) if saved_cache else 0
                )
                structure_cache_generation += 1
                structure_cache_error = None
                structure_model_cache.clear()
        return project

    def refresh_structure_cache() -> None:
        nonlocal structure_size_catalog, structure_viewer_catalog
        nonlocal building_settings_catalog, structure_cache_generated_at
        nonlocal structure_cache_generation, structure_cache_error
        observed_generation = structure_cache_generation
        observed_root = root
        with structure_cache_refresh_lock:
            if structure_cache_generation != observed_generation or root != observed_root:
                return
            try:
                refreshed = build_structure_web_cache(observed_root, core_root)
                save_structure_web_cache(
                    observed_root, refreshed, project_cache_root(observed_root)
                )
            except (
                OSError, ValueError, EOFError, struct.error,
                zipfile.BadZipFile, json.JSONDecodeError, DuplicateKeyError,
            ) as error:
                structure_cache_error = str(error)
                return
            if root != observed_root:
                return
            with structure_size_catalog_lock, structure_viewer_catalog_lock:
                structure_size_catalog = refreshed["size_catalog"]
                structure_viewer_catalog = refreshed["viewer_catalog"]
                building_settings_catalog = refreshed["building_settings"]
                structure_cache_generated_at = int(refreshed["generated_at"])
                structure_cache_generation += 1
                structure_cache_error = None
                structure_model_cache.clear()

    def schedule_structure_cache_refresh() -> None:
        threading.Thread(
            target=refresh_structure_cache,
            name="cobbleventure-nbt-cache-refresh",
            daemon=True,
        ).start()

    def ensure_structure_cache() -> None:
        if structure_size_catalog is None or building_settings_catalog is None:
            refresh_structure_cache()

    def load_installed_cobbleverse_rct_png(resource_group: str, resource_id: str) -> bytes | None:
        instance_override = os.environ.get("COBBLEVERSE_INSTANCE")
        instance_roots = []
        if instance_override:
            instance_roots.append(Path(instance_override))
        instance_roots.append(
            Path.home()
            / "curseforge"
            / "minecraft"
            / "Instances"
            / "COBBLEVERSE - Pokemon Adventure [Cobblemon]"
        )
        archive_entry = (
            f"assets/rctmod/textures/trainers/{resource_group}/{resource_id}.png"
        )
        for instance_root in instance_roots:
            resource_pack = (
                instance_root / "resourcepacks" / "COBBLEVERSE RCTmod RP.zip"
            )
            if not resource_pack.is_file():
                continue
            try:
                with zipfile.ZipFile(resource_pack) as archive:
                    data = archive.read(archive_entry)
            except (OSError, KeyError, zipfile.BadZipFile):
                continue
            if len(data) <= 2 * 1024 * 1024 and data.startswith(b"\x89PNG\r\n\x1a\n"):
                return data
        return None

    def load_remote_png(url: str) -> bytes:
        with remote_image_cache_lock:
            cached = remote_image_cache.get(url)
        if cached is not None:
            return cached
        request = urllib_request.Request(
            url,
            headers={"User-Agent": "CobbleventureContentStudio/0.2"},
        )
        with urllib_request.urlopen(request, timeout=12) as response:
            content_type = response.headers.get_content_type()
            data = response.read(2 * 1024 * 1024 + 1)
        if content_type != "image/png" or len(data) > 2 * 1024 * 1024 or not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("허용된 PNG 이미지가 아닙니다.")
        with remote_image_cache_lock:
            remote_image_cache[url] = data
        return data

    class Handler(BaseHTTPRequestHandler):
        server_version = "CobbleventureContentManager/0.2"

        def _bytes(self, status: int, body: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, status: int, payload: Any) -> None:
            body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
            self._bytes(status, body, "application/json; charset=utf-8")

        def _read_json(self) -> Any:
            try:
                content_length = int(self.headers.get("Content-Length", "0"))
            except ValueError as error:
                raise ValueError("올바르지 않은 Content-Length입니다.") from error
            if content_length < 1 or content_length > 2 * 1024 * 1024:
                raise ValueError("요청 JSON은 1바이트 이상 2MB 이하여야 합니다.")
            try:
                return json.loads(
                    self.rfile.read(content_length).decode("utf-8"),
                    object_pairs_hook=_reject_duplicate_keys,
                )
            except (UnicodeDecodeError, json.JSONDecodeError, DuplicateKeyError) as error:
                raise ValueError(f"JSON을 읽을 수 없습니다: {error}") from error

        def _serve_static(self, request_path: str) -> bool:
            static_files = {
                "/": web_root / "index.html",
                "/index.html": web_root / "index.html",
                "/app.js": web_root / "app.js",
                "/space-connections.js": web_root / "space-connections.js",
                "/styles.css": web_root / "styles.css",
                "/economy.css": web_root / "economy.css",
                "/pokemon-entry-clipboard.mjs": core_root
                / "projects"
                / "cobbleventure-battle-ai"
                / "web-lab"
                / "lib"
                / "pokemon-entry-clipboard.mjs",
            }
            path = static_files.get(request_path)
            if path is None:
                return False
            try:
                body = path.read_bytes()
            except OSError:
                self._json(500, {"error": "관리 화면 파일을 읽을 수 없습니다."})
                return True
            self._bytes(
                200,
                body,
                STATIC_CONTENT_TYPES.get(path.suffix, "application/octet-stream"),
            )
            return True

        def _document_response(self, category: str, request: Any) -> None:
            query = parse_qs(request.query)
            relative_path = query.get("path", [""])[0]
            if not relative_path:
                self._json(200, {"items": _list_documents(root, category)})
                return
            try:
                path = _managed_path(root, category, relative_path)
                self._json(
                    200,
                    {
                        "path": path.relative_to(root).as_posix(),
                        "document": load_json(path),
                    },
                )
            except ValueError as error:
                self._json(400, {"error": str(error)})
            except FileNotFoundError:
                self._json(404, {"error": "문서를 찾을 수 없습니다."})
            except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                self._json(500, {"error": str(error)})

        def _route(self) -> None:
            request = urlparse(self.path)
            if self._serve_static(request.path):
                return
            if request.path == "/health":
                self._json(200, {"status": "ok", "service": "cobbleventure-content-manager"})
                return
            if request.path == "/api/project":
                self._json(
                    200,
                    {
                        "project": active_project.as_json(),
                        "core_path": str(core_root),
                    },
                )
                return
            if request.path in {"/dependencies", "/api/dependencies"}:
                try:
                    self._json(200, load_json(core_root / "pack" / "dependencies.lock.json"))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path in {"/validate", "/api/validate"}:
                query = parse_qs(request.query)
                strict_pack = query.get("strict_pack", ["false"])[0].lower() in {"1", "true", "yes"}
                result = validate_repository(root, strict_pack, core_root)
                self._json(200 if result.valid else 422, result.as_json())
                return
            if request.path == "/api/dashboard":
                result = validate_repository(root, dependency_root=core_root)
                self._json(
                    200,
                    {
                        "trainers": len(_list_documents(root, "trainers")),
                        "settlements": len(_list_documents(root, "settlements")),
                        "gyms": len(load_json(root / "content" / "catalogs" / "gyms.json").get("gyms", [])),
                        "validation": result.as_json(),
                        "build_commands": [
                            {"id": command, "description": description}
                            for command, description in BUILD_COMMANDS.items()
                        ] if active_project.is_default else [],
                    },
                )
                return
            if request.path == "/api/structure-builder":
                try:
                    self._json(200, _structure_builder_status(root, core_root))
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/trainer-classes":
                try:
                    self._json(
                        200,
                        load_json(
                            root / "content" / "catalogs" / "trainer-classes.json"
                        ),
                    )
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/trainer-roster":
                try:
                    self._json(
                        200,
                        load_json(root / "content" / "catalogs" / "trainer-roster.json"),
                    )
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/league-progression":
                try:
                    self._json(200, load_json(root / "content" / "catalogs" / "league-progression.json"))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/badges":
                try:
                    self._json(200, load_json(root / "content" / "catalogs" / "badges.json"))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/badge-atlas":
                try:
                    atlas = core_root / "projects" / "cobbleventure-player-menu" / "src" / "main" / "resources" / "assets" / "cobbleventure_player_menu" / "textures" / "gui" / "badges.png"
                    self._bytes(200, atlas.read_bytes(), "image/png")
                except OSError as error:
                    self._json(404, {"error": f"배지 아틀라스를 찾을 수 없습니다: {error}"})
                return
            if request.path == "/api/game-definitions":
                try:
                    self._json(200, load_json(root / "content" / "catalogs" / "game-definitions.json"))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/music-catalog":
                try:
                    catalog, _ = sync_local_music_catalog(root, core_root)
                    self._json(200, catalog)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/economy":
                try:
                    self._json(200, load_economy_workspace(root, core_root))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/trainer-reference-entries":
                try:
                    self._json(
                        200,
                        load_json(
                            root
                            / "content"
                            / "catalogs"
                            / "trainer-reference-entries.json"
                        ),
                    )
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/trainer-skin":
                query = parse_qs(request.query)
                resource = query.get("resource", [""])[0]
                reference_match = re.fullmatch(r"trainer-reference:([a-z0-9_-]+)", resource)
                if reference_match:
                    slug = reference_match.group(1)
                    reference_root = (
                        core_root / "tools" / "content-manager" / "skin-pipeline" / "work"
                    ).resolve()
                    reference_path = (
                        reference_root / slug / "reference" / f"{slug}.png"
                    ).resolve()
                    if reference_path.is_relative_to(reference_root) and reference_path.is_file():
                        self._bytes(200, reference_path.read_bytes(), "image/png")
                    else:
                        self._json(404, {"error": "로컬 트레이너 참조 이미지를 찾을 수 없습니다."})
                    return
                rct_match = re.fullmatch(
                    r"rctmod:trainers/(single|group)/([a-z0-9_-]+)", resource
                )
                match = re.fullmatch(r"([a-z0-9_.-]+):trainer_skin/([a-z0-9_./-]+)", resource)
                skin_root = (
                    core_root
                    / "projects"
                    / "cobbleventure-world-bootstrap"
                    / "src"
                    / "main"
                    / "resources"
                    / "assets"
                ).resolve()
                manual_retouch_root = (
                    core_root
                    / "tools"
                    / "content-manager"
                    / "skin-pipeline"
                    / "retouch"
                    / "manual"
                ).resolve()
                fallback = skin_root / "cobbleventure" / "textures" / "entity" / "trainer" / "unimplemented.png"
                if rct_match:
                    installed_png = load_installed_cobbleverse_rct_png(
                        rct_match.group(1), rct_match.group(2)
                    )
                    if installed_png is not None:
                        self._bytes(200, installed_png, "image/png")
                        return
                    remote_url = (
                        "https://gitlab.com/srcmc/rct/mod/-/raw/1.21.1/"
                        "common/src/main/resources/assets/rctmod/textures/trainers/"
                        f"{rct_match.group(1)}/{rct_match.group(2)}.png"
                    )
                    try:
                        self._bytes(200, load_remote_png(remote_url), "image/png")
                    except (OSError, ValueError, urllib_error.URLError):
                        try:
                            self._bytes(200, fallback.read_bytes(), "image/png")
                        except OSError as error:
                            self._json(500, {"error": f"대체 트레이너 스킨을 읽을 수 없습니다: {error}"})
                    return
                skin_path = fallback
                if match:
                    manual_candidate = (
                        manual_retouch_root / f"{match.group(2)}.png"
                    ).resolve()
                    candidate = (
                        skin_root
                        / match.group(1)
                        / "textures"
                        / "entity"
                        / "trainer"
                        / f"{match.group(2)}.png"
                    ).resolve()
                    if (
                        manual_candidate.is_relative_to(manual_retouch_root)
                        and manual_candidate.is_file()
                    ):
                        skin_path = manual_candidate
                    elif candidate.is_relative_to(skin_root) and candidate.is_file():
                        skin_path = candidate
                try:
                    self._bytes(200, skin_path.read_bytes(), "image/png")
                except OSError as error:
                    self._json(500, {"error": f"트레이너 스킨을 읽을 수 없습니다: {error}"})
                return
            if request.path == "/api/trainer-reference":
                sprite = parse_qs(request.query).get("sprite", [""])[0]
                if not re.fullmatch(r"[a-z0-9_-]+", sprite):
                    self._json(400, {"error": "올바른 트레이너 스프라이트 ID가 아닙니다."})
                    return
                remote_url = f"https://play.pokemonshowdown.com/sprites/trainers/{sprite}.png"
                try:
                    self._bytes(200, load_remote_png(remote_url), "image/png")
                except (OSError, ValueError, urllib_error.URLError):
                    self._json(404, {"error": "트레이너 참조 이미지를 찾을 수 없습니다."})
                return
            local_reference_match = re.fullmatch(
                r"/trainer-assets/references/([a-z0-9_-]+)\.png", request.path
            )
            if request.path == "/api/trainer-reference-local" or local_reference_match:
                slug = (
                    local_reference_match.group(1)
                    if local_reference_match
                    else parse_qs(request.query).get("slug", [""])[0]
                )
                if not re.fullmatch(r"[a-z0-9_-]+", slug):
                    self._json(400, {"error": "올바른 트레이너 참조 ID가 아닙니다."})
                    return
                reference_root = (
                    core_root / "tools" / "content-manager" / "skin-pipeline" / "work"
                ).resolve()
                reference_path = (
                    reference_root / slug / "reference" / f"{slug}.png"
                ).resolve()
                if not reference_path.is_relative_to(reference_root) or not reference_path.is_file():
                    self._json(404, {"error": "로컬 트레이너 참조 이미지를 찾을 수 없습니다."})
                    return
                try:
                    self._bytes(200, reference_path.read_bytes(), "image/png")
                except OSError as error:
                    self._json(500, {"error": f"트레이너 참조 이미지를 읽을 수 없습니다: {error}"})
                return
            if request.path == "/api/biome-catalog":
                try:
                    self._json(200, load_biome_catalog(root))
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/world-layout":
                try:
                    generation = int(parse_qs(request.query).get("generation", ["1"])[0])
                    self._json(200, load_world_layout(root, generation))
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/world-pokemon-map":
                try:
                    generation = int(parse_qs(request.query).get("generation", ["1"])[0])
                    self._json(200, world_pokemon_map(root, generation))
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/world-layouts":
                self._json(200, {"generations": list_world_generations(root)})
                return
            if request.path == "/api/pokemon-habitats":
                try:
                    self._json(200, load_pokemon_habitats(root))
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/editor-catalog":
                nonlocal editor_catalog
                try:
                    with editor_catalog_lock:
                        if editor_catalog is None:
                            editor_catalog = load_editor_catalog(core_root)
                    self._json(200, editor_catalog)
                except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/structure-sizes":
                nonlocal structure_size_catalog
                try:
                    if parse_qs(request.query).get("refresh", ["0"])[0] == "1":
                        refresh_structure_cache()
                    ensure_structure_cache()
                    with structure_size_catalog_lock:
                        payload = copy.deepcopy(structure_size_catalog or {"structures": {}})
                    payload["cache"] = {
                        "generated_at": structure_cache_generated_at,
                        "refreshing": structure_cache_refresh_lock.locked(),
                        "error": structure_cache_error,
                    }
                    self._json(200, payload)
                except (OSError, ValueError, zipfile.BadZipFile) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/structure-viewer":
                nonlocal structure_viewer_catalog
                try:
                    if parse_qs(request.query).get("refresh", ["0"])[0] == "1":
                        refresh_structure_cache()
                    ensure_structure_cache()
                    with structure_viewer_catalog_lock:
                        payload = copy.deepcopy(structure_viewer_catalog or {})
                    self._json(200, {
                        "structures": payload,
                        "cache": {
                            "generated_at": structure_cache_generated_at,
                            "refreshing": structure_cache_refresh_lock.locked(),
                            "error": structure_cache_error,
                        },
                    })
                except (OSError, ValueError, EOFError, struct.error, zipfile.BadZipFile) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/building-settings":
                try:
                    if parse_qs(request.query).get("refresh", ["0"])[0] == "1":
                        refresh_structure_cache()
                    ensure_structure_cache()
                    payload = copy.deepcopy(building_settings_catalog or {})
                    payload["cache"] = {
                        "generated_at": structure_cache_generated_at,
                        "refreshing": structure_cache_refresh_lock.locked(),
                        "error": structure_cache_error,
                    }
                    self._json(200, payload)
                except (OSError, ValueError, EOFError, struct.error, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/space-connections":
                try:
                    ensure_structure_cache()
                    self._json(200, space_connections_payload(
                        root, copy.deepcopy(building_settings_catalog or {})
                    ))
                except (OSError, ValueError, EOFError, struct.error, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/gyms":
                try:
                    self._json(200, load_json(root / "content" / "catalogs" / "gyms.json"))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/gym-interior-modules":
                try:
                    self._json(200, gym_interior_modules_payload(root))
                except (OSError, ValueError, EOFError, struct.error, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/interior-spaces":
                try:
                    self._json(200, interior_spaces_payload(root))
                except (OSError, ValueError, EOFError, struct.error, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/structure-model":
                resource_id = parse_qs(request.query).get("structure", [""])[0]
                try:
                    with structure_size_catalog_lock:
                        model = structure_model_cache.get(resource_id)
                        if model is None:
                            loaded = load_structure_model(root, resource_id, core_root)
                            if loaded is not None:
                                model = {"structure": resource_id, **loaded}
                                structure_model_cache[resource_id] = model
                    if model is None:
                        self._json(404, {"error": "구조물 NBT를 찾을 수 없습니다."})
                    else:
                        self._json(200, model)
                except ValueError as error:
                    self._json(400, {"error": str(error)})
                except (OSError, EOFError, struct.error, zipfile.BadZipFile) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/trainers":
                self._document_response("trainers", request)
                return
            if request.path == "/api/battles":
                self._document_response("battles", request)
                return
            if request.path == "/api/settlements":
                self._document_response("settlements", request)
                return
            if request.path == "/api/caves":
                self._document_response("caves", request)
                return
            self._json(404, {"error": "not_found"})

        def do_GET(self) -> None:
            self._route()

        def do_POST(self) -> None:
            request = urlparse(self.path)
            if request.path in {"/validate", "/api/validate"}:
                self._route()
                return
            try:
                payload = self._read_json()
            except ValueError as error:
                self._json(400, {"error": str(error)})
                return
            if request.path == "/api/project/pick":
                if os.name != "nt":
                    self._json(501, {"error": "폴더 선택창은 현재 Windows에서만 지원합니다."})
                    return
                initial_path = payload.get("path", "") if isinstance(payload, dict) else ""
                try:
                    completed = subprocess.run(
                        [
                            "powershell.exe", "-NoLogo", "-NoProfile",
                            "-ExecutionPolicy", "Bypass", "-STA", "-Command",
                            PROJECT_FOLDER_PICKER_SCRIPT,
                        ],
                        cwd=core_root,
                        env={**os.environ, "COBBLEVENTURE_PROJECT_PATH": str(initial_path)},
                        capture_output=True,
                        encoding="utf-8",
                        errors="replace",
                        timeout=120,
                        check=False,
                    )
                    if completed.returncode != 0:
                        raise ValueError(completed.stderr.strip() or "폴더 선택창을 열지 못했습니다.")
                    selected_path = completed.stdout.strip()
                    self._json(200, {"cancelled": not selected_path, "path": selected_path})
                except (OSError, subprocess.TimeoutExpired, ValueError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/document-validation":
                category = parse_qs(request.query).get("category", [""])[0]
                if category not in {"trainers", "battles", "settlements", "caves"}:
                    self._json(400, {"error": "지원하지 않는 문서 종류입니다."})
                    return
                validator = {
                    "trainers": validate_content_file,
                    "battles": validate_battle_preset_file,
                    "settlements": validate_settlement_file,
                    "caves": validate_cave_file,
                }[category]
                _, issues = _validate_payload(payload, validator)
                errors = sum(issue.level == "error" for issue in issues)
                self._json(
                    200 if errors == 0 else 422,
                    {
                        "valid": errors == 0,
                        "errors": errors,
                        "issues": [asdict(issue) for issue in issues],
                    },
                )
                return
            if request.path == "/api/biome-preview":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "미리보기 설정은 객체여야 합니다."})
                    return
                try:
                    self._json(200, preview_biome(root, payload))
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/documents":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "문서 생성 정보가 필요합니다."})
                    return
                category = payload.get("category")
                slug = payload.get("slug")
                name = payload.get("name")
                generation = payload.get("generation", "generation_1")
                reference_id = payload.get("reference_id", "")
                if not all(isinstance(value, str) for value in (category, slug, name, generation, reference_id)):
                    self._json(400, {"error": "문서 종류, 파일 ID와 이름을 문자열로 입력해야 합니다."})
                    return
                target, issues = _create_document(
                    root, category, slug, name, generation, reference_id
                )
                errors = sum(issue.level == "error" for issue in issues)
                self._json(
                    201 if errors == 0 else 422,
                    {
                        "created": errors == 0,
                        "path": target.relative_to(root).as_posix() if target else "",
                        "issues": [asdict(issue) for issue in issues],
                    },
                )
                return
            if request.path == "/api/league-members/create":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "리그 구성원 생성 정보가 필요합니다."})
                    return
                try:
                    created, issues = create_league_member(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(
                    201 if errors == 0 else 422,
                    {
                        "created": errors == 0,
                        "member": created,
                        "issues": [asdict(issue) for issue in issues],
                    },
                )
                return
            if request.path == "/api/build":
                if not active_project.is_default:
                    self._json(409, {"error": "프로젝트별 빌드 출력 분리는 아직 지원하지 않습니다. 기본 프로젝트에서 실행해 주세요."})
                    return
                command = payload.get("command") if isinstance(payload, dict) else None
                if not isinstance(command, str) or command not in BUILD_COMMANDS:
                    self._json(400, {"error": "허용된 빌드 명령을 선택해야 합니다."})
                    return
                if not build_lock.acquire(blocking=False):
                    self._json(409, {"error": "다른 빌드 명령이 실행 중입니다."})
                    return
                try:
                    result = _run_build(core_root, root, command)
                finally:
                    build_lock.release()
                self._json(200 if result["success"] else 422, result)
                return
            if request.path == "/api/gyms/create":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "체육관 생성 정보가 필요합니다."})
                    return
                try:
                    gym, issues = create_gym(
                        root,
                        str(payload.get("slug", "")),
                        str(payload.get("name", "")),
                        str(payload.get("source_structure", "")),
                    )
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(201 if errors == 0 else 422, {"created": errors == 0, "gym": gym, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/gym-interior-modules":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "내부 모듈 생성 정보가 필요합니다."})
                    return
                try:
                    module = create_gym_interior_module(root, payload)
                    schedule_structure_cache_refresh()
                    self._json(201, {"created": True, "module": module})
                except (OSError, ValueError, TypeError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/interior-spaces":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "내부공간 생성 정보가 필요합니다."})
                    return
                try:
                    space = create_interior_space(root, payload)
                    schedule_structure_cache_refresh()
                    self._json(201, {"created": True, "space": space})
                except (OSError, ValueError, TypeError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/structure-builder/import":
                if not active_project.is_default:
                    self._json(409, {"error": "건축 NBT 자동 가져오기는 현재 기본 프로젝트에서만 지원합니다."})
                    return
                if not build_lock.acquire(blocking=False):
                    self._json(409, {"error": "다른 빌드 명령이 실행 중입니다."})
                    return
                try:
                    result = _run_structure_builder_import(root, core_root)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                finally:
                    build_lock.release()
                if result["success"]:
                    schedule_structure_cache_refresh()
                self._json(200 if result["success"] else 422, result)
                return
            self._json(404, {"error": "not_found"})

        def do_PUT(self) -> None:
            request = urlparse(self.path)
            if request.path == "/api/project":
                try:
                    payload = self._read_json()
                    project_path = payload.get("path") if isinstance(payload, dict) else None
                    if not isinstance(project_path, str) or not project_path.strip():
                        raise ValueError("불러올 프로젝트 폴더 경로가 필요합니다.")
                    project = activate_project(Path(project_path.strip()))
                    self._json(
                        200,
                        {
                            "loaded": True,
                            "project": project.as_json(),
                            "core_path": str(core_root),
                        },
                    )
                except (OSError, ValueError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/structure-builder/settings":
                try:
                    payload = self._read_json()
                    instance_path = payload.get("instance_path") if isinstance(payload, dict) else None
                    if not isinstance(instance_path, str):
                        raise ValueError("CurseForge 인스턴스 경로를 문자열로 입력해야 합니다.")
                    _save_structure_builder_settings(core_root, instance_path)
                    self._json(200, _structure_builder_status(root, core_root))
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/game-definitions":
                try:
                    payload = self._read_json()
                    issues = save_game_definitions(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(200 if errors == 0 else 422, {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/music-catalog":
                try:
                    payload = self._read_json()
                    issues = save_music_catalog(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(200 if errors == 0 else 422, {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/economy":
                try:
                    payload = self._read_json()
                    issues = save_economy_catalog(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(200 if errors == 0 else 422, {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/structure-size":
                try:
                    payload = self._read_json()
                    resource_id = payload.get("structure", "")
                    result = resize_managed_structure(
                        root,
                        resource_id,
                        payload.get("width"),
                        payload.get("height"),
                        payload.get("depth"),
                    )
                    # The edited NBT must never be served from the old 3D-model
                    # cache.  Updating the known dimensions here lets the web UI
                    # respond immediately; the expensive all-structure scan can
                    # safely finish in the background.
                    with structure_size_catalog_lock, structure_viewer_catalog_lock:
                        structure_model_cache.pop(resource_id, None)
                        sized = (
                            structure_size_catalog.get("structures")
                            if isinstance(structure_size_catalog, dict) else None
                        )
                        target = sized.get(resource_id) if isinstance(sized, dict) else None
                        if isinstance(target, dict):
                            target.update(result)
                        target = (
                            structure_viewer_catalog.get(resource_id)
                            if isinstance(structure_viewer_catalog, dict) else None
                        )
                        if isinstance(target, dict):
                            target.update(result)
                        structures = (
                            building_settings_catalog.get("structures")
                            if isinstance(building_settings_catalog, dict) else None
                        )
                        target = structures.get(resource_id) if isinstance(structures, dict) else None
                        if isinstance(target, dict):
                            target.update(result)
                    schedule_structure_cache_refresh()
                except (OSError, ValueError, EOFError, struct.error, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                self._json(200, {"saved": True, "structure": result})
                return
            if request.path == "/api/building-settings":
                try:
                    payload = self._read_json()
                    issues = save_building_settings(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                if errors == 0:
                    schedule_structure_cache_refresh()
                self._json(
                    200 if errors == 0 else 422,
                    {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]},
                )
                return
            if request.path == "/api/space-connections":
                try:
                    payload = self._read_json()
                    issues = save_space_connections(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                if errors == 0:
                    schedule_structure_cache_refresh()
                self._json(
                    200 if errors == 0 else 422,
                    {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]},
                )
                return
            if request.path == "/api/gyms":
                try:
                    payload = self._read_json()
                    issues = save_gym_catalog(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(200 if errors == 0 else 422, {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/league-progression":
                try:
                    payload = self._read_json()
                    issues = save_league_progression(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(200 if errors == 0 else 422, {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/biome-catalog":
                try:
                    payload = self._read_json()
                    issues = save_biome_catalog(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(200 if errors == 0 else 422, {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/world-layout":
                try:
                    payload = self._read_json()
                    generation = int(parse_qs(request.query).get("generation", ["1"])[0])
                    issues = save_world_layout(root, payload, generation)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(
                    200 if errors == 0 else 422,
                    {
                        "saved": errors == 0,
                        "valid": errors == 0,
                        "issues": [asdict(issue) for issue in issues],
                    },
                )
                return
            categories = {
                "/api/trainers": "trainers",
                "/api/battles": "battles",
                "/api/settlements": "settlements",
                "/api/caves": "caves",
            }
            category = categories.get(request.path)
            if category is None:
                self._json(404, {"error": "not_found"})
                return
            relative_path = parse_qs(request.query).get("path", [""])[0]
            try:
                payload = self._read_json()
            except ValueError as error:
                self._json(400, {"error": str(error)})
                return
            target, issues = _save_document(root, category, relative_path, payload)
            errors = sum(issue.level == "error" for issue in issues)
            self._json(
                200 if errors == 0 else 422,
                {
                    "saved": errors == 0,
                    "path": target.relative_to(root).as_posix() if target else relative_path,
                    "issues": [asdict(issue) for issue in issues],
                },
            )

        def do_DELETE(self) -> None:
            request = urlparse(self.path)
            if request.path == "/api/world-layout":
                try:
                    generation = int(parse_qs(request.query).get("generation", ["1"])[0])
                    target = _delete_world_layout(root, generation)
                except FileNotFoundError as error:
                    self._json(404, {"error": str(error)})
                    return
                except (OSError, ValueError) as error:
                    self._json(400, {"error": str(error)})
                    return
                self._json(200, {"deleted": True, "path": target.relative_to(root).as_posix()})
                return
            categories = {
                "/api/trainers": "trainers",
                "/api/battles": "battles",
                "/api/settlements": "settlements",
                "/api/caves": "caves",
            }
            category = categories.get(request.path)
            if category is None:
                self._json(404, {"error": "not_found"})
                return
            relative_path = parse_qs(request.query).get("path", [""])[0]
            try:
                target, references = _delete_document(root, category, relative_path)
            except FileNotFoundError as error:
                self._json(404, {"error": str(error)})
                return
            except (
                OSError, ValueError, json.JSONDecodeError, DuplicateKeyError
            ) as error:
                self._json(400, {"error": str(error)})
                return
            if references:
                self._json(
                    409,
                    {
                        "error": "다른 문서에서 참조 중이라 삭제할 수 없습니다.",
                        "references": references,
                    },
                )
                return
            self._json(
                200,
                {
                    "deleted": True,
                    "path": target.relative_to(root).as_posix(),
                },
            )

        def log_message(self, format: str, *args: Any) -> None:
            print(f"[API] {self.address_string()} {format % args}")

    cached_signature = tuple(
        tuple(entry) for entry in saved_structure_cache.get("signature", [])
    ) if saved_structure_cache is not None else ()
    if (
        saved_structure_cache is not None
        and cached_signature != structure_catalog_signature(root, core_root)
    ):
        schedule_structure_cache_refresh()
    return Handler


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Cobbleventure 콘텐츠 관리 도구")
    subcommands = parser.add_subparsers(dest="command", required=True)

    validate = subcommands.add_parser("validate", help="콘텐츠와 의존성 Lock 검증")
    validate.add_argument("--root", type=Path, default=Path.cwd())
    validate.add_argument("--project", type=Path)
    validate.add_argument("--strict-pack", action="store_true")
    validate.add_argument("--json", action="store_true", dest="json_output")

    generate = subcommands.add_parser("generate", help="RCT와 실제 게임용 AI 프로필 생성")
    generate.add_argument("--root", type=Path, default=Path.cwd())
    generate.add_argument("--project", type=Path)
    generate.add_argument("--output", type=Path)
    generate.add_argument("--json", action="store_true", dest="json_output")

    api = subcommands.add_parser("api", help="로컬 Web API 실행")
    api.add_argument("--root", type=Path, default=Path.cwd())
    api.add_argument("--project", type=Path)
    api.add_argument("--host", default="127.0.0.1")
    api.add_argument("--port", type=int, default=8765)
    return parser


def main() -> int:
    arguments = _parser().parse_args()
    core_root = arguments.root.resolve()
    project = resolve_content_project(core_root, arguments.project)
    if arguments.command == "validate":
        result = validate_repository(project.root, arguments.strict_pack, core_root)
        if arguments.json_output:
            print(json.dumps(result.as_json(), ensure_ascii=False, indent=2))
        else:
            _print_result(result)
        return 0 if result.valid else 1

    if arguments.command == "generate":
        try:
            result = generate_content(
                project.root, arguments.output or core_root / "generated", core_root
            )
        except ValueError as error:
            print(f"[ERROR] {error}")
            return 1
        if arguments.json_output:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        else:
            print(f"[OK] 트레이너 {result['count']}개 생성: {result['output']}")
        return 0

    server = ThreadingHTTPServer(
        (arguments.host, arguments.port), create_handler(core_root, project.root)
    )
    print(f"Cobbleventure Content Manager: http://{arguments.host}:{arguments.port}")
    print(f"핵심 저장소: {core_root}")
    print(f"프로젝트: {project.root}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nAPI를 종료합니다.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

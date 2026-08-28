from __future__ import annotations

import argparse
import base64
import binascii
import copy
import difflib
import functools
import gzip
import hashlib
import importlib.util
import io
import json
import math
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer as _ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib import error as urllib_error
from urllib import request as urllib_request
from urllib.parse import parse_qs, urlparse

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))
CONTENT_MANAGER_ROOT = Path(__file__).resolve().parent
if str(CONTENT_MANAGER_ROOT) not in sys.path:
    sys.path.insert(0, str(CONTENT_MANAGER_ROOT))

from tools.npc_event_presets import BATTLE_PRESETS, materialize_event_document
from cves import (
    AstCodecError,
    CvesCompilationError,
    CvesEditorConflict,
    CvesProjectError,
    CvesSyntaxError,
    compile_project,
    encode_program as encode_cves_program,
    format_program as format_cves_program,
    diagnostic_document as cves_diagnostic_document,
    editor_contract as cves_editor_contract,
    list_scripts as list_cves_scripts,
    load_project_catalog as load_cves_project_catalog,
    load_script as load_cves_script,
    parse_editor_expression as parse_cves_editor_expression,
    preset_program as cves_preset_program,
    save_script as save_cves_script,
    validate_ast as validate_cves_ast,
    validate_source as validate_cves_source,
    write_project,
)
from loot_table_validation import validate_loot_table_document


class ThreadingHTTPServer(_ThreadingHTTPServer):
    """HTTP server that drains handler-owned background work before closing."""

    def server_close(self) -> None:
        close_background_tasks = getattr(
            self.RequestHandlerClass, "close_background_tasks", None
        )
        try:
            if callable(close_background_tasks):
                close_background_tasks()
        finally:
            super().server_close()


RESOURCE_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
MOD_ID = re.compile(r"^[a-z][a-z0-9_-]*$")
MUSIC_CATALOG_LOCK = threading.RLock()
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
PLAYER_CONDITION_TYPES = {
    "always",
    "variable",
    "flag",
    "flag_equals",
    "item",
    "has_item",
    "badge",
    "pokemon",
    "party_count",
}
OPERATION_TYPES = PLAYER_CONDITION_TYPES | {
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
    "start_starter_roulette",
    "start_quest",
    "complete_quest",
    "teleport",
    "teleport_to_gate",
    "unlock_feature",
    "set_level_cap",
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
    "mod-ai": "독립 Battle AI 모드 JAR 생성",
    "mod-adventure": "게임플레이 규칙 모드 JAR 생성",
    "mod-bootstrap": "월드 부트스트랩 모드 JAR 생성",
    "mod-menu": "플레이어 메뉴 모드 JAR 생성",
    "mod-casino": "커스텀 가챠 기계 애드온 JAR 생성",
    "pack-smoke": "최소 CurseForge 임포트 ZIP 생성",
    "pack": "개발용 CurseForge ZIP 생성",
    "validate-pack": "실제 모드팩 빌드 준비 상태 검사",
    "builder-world": "독립 건축 월드 CurseForge ZIP 생성",
    "live-editor-world": "단일 NBT 라이브 에디터 CurseForge ZIP 생성",
}
EXPORT_LANGUAGES = {
    "ko_kr": "한국어",
    "en_us": "English (US)",
}
COBBLEMON_BUILD_TARGETS = {
    "1.7.3": "1.7.3 안정 버전",
    "1.8": "1.8 스냅샷",
}
STRUCTURE_BUILDER_WORLD_NAME = "Cobbleventure Structure Builder"
LIVE_NBT_EDITOR_WORLD_NAME = "Cobbleventure Live NBT Editor"
CONTENT_MANAGER_SETTINGS = "tools/content-manager/settings.local.json"
STATIC_CONTENT_TYPES = {
    ".css": "text/css; charset=utf-8",
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".png": "image/png",
    ".woff2": "font/woff2",
    ".ttf": "font/ttf",
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
    forest_documents: dict[str, dict[str, Any]] | None = None,
    route_ids: set[str] | None = None,
    underground_documents: dict[str, dict[str, Any]] | None = None,
    structure_root: Path | None = None,
) -> list[Issue]:
    issues: list[Issue] = []
    structure_root = structure_root or root
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
    if forest_documents is None:
        forest_documents = {}
        forest_dir = root / "content" / "forests"
        for forest_path in forest_dir.rglob("*.json") if forest_dir.is_dir() else []:
            try:
                forest_data = load_json(forest_path)
                if isinstance(forest_data, dict) and isinstance(forest_data.get("id"), str):
                    forest_documents[forest_data["id"]] = forest_data
            except (OSError, json.JSONDecodeError, DuplicateKeyError):
                continue
    if underground_documents is None:
        underground_documents = {}
        underground_dir = root / "content" / "underground_roads"
        for underground_path in underground_dir.rglob("*.json") if underground_dir.is_dir() else []:
            try:
                underground_data = load_json(underground_path)
                if isinstance(underground_data, dict) and isinstance(underground_data.get("id"), str):
                    underground_documents[underground_data["id"]] = underground_data
            except (OSError, json.JSONDecodeError, DuplicateKeyError):
                continue
    if route_ids is None:
        route_ids = set()
        route_dir = root / "content" / "routes"
        for route_path in route_dir.rglob("*.json") if route_dir.is_dir() else []:
            try:
                route_data = load_json(route_path)
                route_id = route_data.get("id") if isinstance(route_data, dict) else None
                if isinstance(route_id, str):
                    route_ids.add(route_id)
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
        display_name = world.get("display_name")
        if display_name is not None:
            _localized_text(display_name, issues, path, "$.display_name")
            if isinstance(display_name, dict):
                for language, name in display_name.items():
                    if isinstance(name, str) and len(name) > 64:
                        _issue(issues, "error", path, f"$.display_name.{language}", "지역 이름은 64자 이하여야 합니다.")
        grid = world.get("grid")
        if not isinstance(grid, dict) or grid.get("orientation") != "pointy_top":
            _issue(issues, "error", path, "$.grid.orientation", "pointy_top 육각 격자만 지원합니다.")
        radius = grid.get("tile_radius_blocks") if isinstance(grid, dict) else None
        if not isinstance(radius, int) or isinstance(radius, bool) or not 32 <= radius <= 256:
            _issue(issues, "error", path, "$.grid.tile_radius_blocks", "32 이상 256 이하의 정수여야 합니다.")
        map_radius = grid.get("map_radius_cells") if isinstance(grid, dict) else None
        if map_radius is not None and (not isinstance(map_radius, int) or isinstance(map_radius, bool) or not 3 <= map_radius <= 64):
            _issue(issues, "error", path, "$.grid.map_radius_cells", "3 이상 64 이하의 정수여야 합니다.")
        visible_coordinates: list[tuple[int, int]] = []
        for field in ("tiles", "environment_overrides", "level_overrides", "music_overrides"):
            for value in world.get(field, []) if isinstance(world.get(field, []), list) else []:
                if isinstance(value, dict) and all(
                    isinstance(value.get(axis), int) and not isinstance(value.get(axis), bool)
                    for axis in ("q", "r")
                ):
                    visible_coordinates.append((value["q"], value["r"]))
        for field in ("settlements", "objects", "cave_entrances", "forest_entrances"):
            for value in world.get(field, []) if isinstance(world.get(field, []), list) else []:
                anchor = value.get("anchor") if isinstance(value, dict) else None
                if isinstance(anchor, dict) and all(
                    isinstance(anchor.get(axis), int) and not isinstance(anchor.get(axis), bool)
                    for axis in ("q", "r")
                ):
                    visible_coordinates.append((anchor["q"], anchor["r"]))
                    if field == "settlements" and value.get("town_footprint_shape") == "custom":
                        for relative in value.get("town_footprint_cells", []):
                            if isinstance(relative, dict) and all(
                                isinstance(relative.get(axis), int) and not isinstance(relative.get(axis), bool)
                                for axis in ("q", "r")
                            ):
                                visible_coordinates.append((
                                    anchor["q"] + relative["q"],
                                    anchor["r"] + relative["r"],
                                ))
        for connection in world.get("connections", []) if isinstance(world.get("connections", []), list) else []:
            if not isinstance(connection, dict):
                continue
            route_cells = connection.get("cells", connection.get("path", []))
            for value in route_cells if isinstance(route_cells, list) else []:
                if isinstance(value, dict) and all(
                    isinstance(value.get(axis), int) and not isinstance(value.get(axis), bool)
                    for axis in ("q", "r")
                ):
                    visible_coordinates.append((value["q"], value["r"]))
        visible_radius = max(
            (_hex_distance((0, 0), coordinate) for coordinate in visible_coordinates),
            default=0,
        )
        if isinstance(map_radius, int) and not isinstance(map_radius, bool) and visible_radius > map_radius:
            _issue(
                issues, "error", path, "$.grid.map_radius_cells",
                f"표시할 타일과 배치 요소를 포함하려면 반경이 최소 {visible_radius}이어야 합니다.",
            )
        empty_terrain = world.get("empty_terrain", {"default_type": "high_forest", "tiles": []})
        empty_types = {"high_forest", "dense_forest", "ocean", "deep_ocean", "desert", "stone_mountain", "red_rock_mountain", "snow_mountain"}
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
        settlement_footprints: dict[str, set[tuple[int, int]]] = {}
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
                footprint = _town_footprint(coordinate, town_radius, town_shape, custom_cells)
                if isinstance(settlement, str):
                    settlement_footprints[settlement] = footprint
                for other_coordinate, other_radius, other_settlement in occupied_town_ranges:
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
            underground_id = placement.get("underground_road")
            transition = placement.get("transition")
            structure_id = placement.get("structure")
            structure_path = managed_structure_files(structure_root).get(structure_id) if isinstance(structure_id, str) else None
            transition_ids = {
                item["label"] for item in _structure_named_anchors(structure_path, {"transition"})
            } if structure_path is not None else set()
            if not isinstance(transition, str) or transition not in transition_ids:
                _issue(issues, "error", path, f"{placement_path}.transition", f"입구 구조물에 없는 이동 영역입니다: {transition}")
            if underground_id is not None:
                if "cave" in placement or "entrance" in placement:
                    _issue(issues, "error", path, placement_path, "지하통로 입구에는 cave 또는 entrance 필드를 함께 사용할 수 없습니다.")
                underground = underground_documents.get(underground_id) if isinstance(underground_id, str) else None
                if underground is None:
                    _issue(issues, "error", path, f"{placement_path}.underground_road", f"존재하지 않는 지하통로 ID: {underground_id}")
                module_id = placement.get("underground_module")
                connector_id = placement.get("underground_connector")
                endpoints = {
                    (item["module"], item["connector"])
                    for item in _underground_road_endpoints(underground, structure_root)
                } if underground else set()
                if not isinstance(module_id, str) or not isinstance(connector_id, str) or (module_id, connector_id) not in endpoints:
                    _issue(issues, "error", path, f"{placement_path}.underground_connector", f"지하통로의 열린 위쪽 커넥터가 아닙니다: {module_id}/{connector_id}")
                pair = (str(underground_id), f"{module_id}/{connector_id}")
            else:
                underground_fields = {"underground_module", "underground_connector"}
                if any(field in placement for field in underground_fields):
                    _issue(issues, "error", path, placement_path, "동굴 입구에는 지하통로 연결 필드를 사용할 수 없습니다.")
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
                _issue(issues, "error", path, placement_path, "같은 동굴 입구 또는 지하통로 커넥터를 월드맵에 중복 배치할 수 없습니다.")
            seen_cave_pairs.add(pair)
            anchor = placement.get("anchor")
            if not isinstance(anchor, dict) or not all(isinstance(anchor.get(key), int) and not isinstance(anchor.get(key), bool) for key in ("q", "r")):
                _issue(issues, "error", path, f"{placement_path}.anchor", "정수 axial 좌표 q, r이 필요합니다.")
            elif isinstance(placement_id, str):
                entrance_coordinate = (anchor["q"], anchor["r"])
                cave_entrance_anchors[placement_id] = entrance_coordinate
                for town_anchor, town_layout, settlement in occupied_town_ranges:
                    town_cells = _town_footprint(
                        town_anchor, town_layout[0], town_layout[1], town_layout[2]
                    )
                    if entrance_coordinate in town_cells:
                        _issue(
                            issues, "error", path, f"{placement_path}.anchor",
                            f"동굴 또는 지하통로 입구는 마을 영역 밖에 있어야 합니다: {settlement}",
                        )
                        break
            _resource_id(placement.get("structure"), issues, path, f"{placement_path}.structure")
            if placement.get("facing") not in {"north", "east", "south", "west"}:
                _issue(issues, "error", path, f"{placement_path}.facing", "동굴 입구 방향은 north/east/south/west 중 하나여야 합니다.")
            center_enabled = placement.get("pokemon_center_enabled")
            if center_enabled is not None and not isinstance(center_enabled, bool):
                _issue(issues, "error", path, f"{placement_path}.pokemon_center_enabled", "포켓몬센터 사용 여부는 true 또는 false여야 합니다.")
            center = placement.get("pokemon_center")
            if isinstance(center, dict):
                _resource_id(center.get("structure"), issues, path, f"{placement_path}.pokemon_center.structure")
                offset = center.get("offset")
                if not isinstance(offset, dict) or not all(isinstance(offset.get(key), int) and not isinstance(offset.get(key), bool) for key in ("q", "r")):
                    _issue(issues, "error", path, f"{placement_path}.pokemon_center.offset", "포켓몬센터의 정수 axial 오프셋 q, r이 필요합니다.")

        forest_entrances = world.get("forest_entrances", [])
        if not isinstance(forest_entrances, list):
            _issue(issues, "error", path, "$.forest_entrances", "숲 입구 목록은 배열이어야 합니다.")
            forest_entrances = []
        forest_entrance_anchors: dict[str, tuple[int, int]] = {}
        seen_forest_entrance_ids: set[str] = set()
        seen_forest_pairs: set[tuple[str, str]] = set()
        for index, placement in enumerate(forest_entrances):
                if not isinstance(placement, dict):
                    _issue(issues, "error", path, f"$.forest_entrances[{index}]", "숲 입구 배치는 객체여야 합니다.")
                    continue
                object_path = f"$.forest_entrances[{index}]"
                object_id = placement.get("id")
                if not isinstance(object_id, str) or not CHOICE_ID.fullmatch(object_id) or object_id in seen_forest_entrance_ids:
                    _issue(issues, "error", path, f"{object_path}.id", "유일한 숲 입구 ID가 필요합니다.")
                else:
                    seen_forest_entrance_ids.add(object_id)
                forest_id = placement.get("forest")
                entrance_id = placement.get("entrance")
                forest = forest_documents.get(forest_id) if isinstance(forest_id, str) else None
                if forest is None:
                    _issue(issues, "error", path, f"{object_path}.forest", f"존재하지 않는 숲 ID: {forest_id}")
                entrance_ids = {
                    item.get("id") for item in forest.get("entrances", [])
                    if isinstance(item, dict) and isinstance(item.get("id"), str)
                } if forest else set()
                if not isinstance(entrance_id, str) or entrance_id not in entrance_ids:
                    _issue(issues, "error", path, f"{object_path}.entrance", f"숲에 없는 내부 입구 ID: {entrance_id}")
                pair = (str(forest_id), str(entrance_id))
                if pair in seen_forest_pairs:
                    _issue(issues, "error", path, object_path, "같은 숲 내부 입구를 월드맵에 중복 배치할 수 없습니다.")
                seen_forest_pairs.add(pair)
                anchor = placement.get("anchor")
                if isinstance(object_id, str) and isinstance(anchor, dict) and all(isinstance(anchor.get(key), int) and not isinstance(anchor.get(key), bool) for key in ("q", "r")):
                    forest_entrance_anchors[object_id] = (anchor["q"], anchor["r"])
                else:
                    _issue(issues, "error", path, f"{object_path}.anchor", "정수 axial 좌표 q, r이 필요합니다.")
                _resource_id(placement.get("structure"), issues, path, f"{object_path}.structure")
                center_enabled = placement.get("pokemon_center_enabled")
                if center_enabled is not None and not isinstance(center_enabled, bool):
                    _issue(issues, "error", path, f"{object_path}.pokemon_center_enabled", "포켓몬센터 사용 여부는 true 또는 false여야 합니다.")
                if placement.get("facing") not in {"north", "east", "south", "west"}:
                    _issue(issues, "error", path, f"{object_path}.facing", "숲 입구 방향은 north/east/south/west 중 하나여야 합니다.")
                rotation = placement.get("rotation")
                if not isinstance(rotation, int) or isinstance(rotation, bool) or rotation not in range(4):
                    _issue(issues, "error", path, f"{object_path}.rotation", "ForestGate NBT 회전은 0~3이어야 합니다.")
                for field in ("tree_log", "tree_leaves"):
                    _resource_id(placement.get(field), issues, path, f"{object_path}.{field}")
                numeric_limits = {"wall_thickness": (1, 15), "wall_height": (3, 32), "opening_width": (3, 31), "barrier_height": (8, 128)}
                for field, (minimum, maximum) in numeric_limits.items():
                    number = placement.get(field)
                    if not isinstance(number, int) or isinstance(number, bool) or not minimum <= number <= maximum:
                        _issue(issues, "error", path, f"{object_path}.{field}", f"{minimum}~{maximum} 범위 정수가 필요합니다.")
                    elif field in {"wall_thickness", "opening_width"} and number % 2 == 0:
                        _issue(issues, "error", path, f"{object_path}.{field}", "입구 중심 정렬을 위해 홀수여야 합니다.")

        connections = world.get("connections")
        if not isinstance(connections, list):
            _issue(issues, "error", path, "$.connections", "연결 목록은 배열이어야 합니다.")
            connections = []
        seen_connections: set[str] = set()
        connection_degrees: dict[str, int] = {}
        connection_cells: set[tuple[int, int]] = set()
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
            route_preset = connection.get("route_preset")
            if route_preset is not None and route_ids and route_preset not in route_ids:
                _issue(issues, "error", path, f"{connection_path}.route_preset", f"존재하지 않는 길 프리셋: {route_preset}")
            for field in ("from", "to"):
                target = connection.get(field)
                if target is not None and target not in world_settlements and target not in cave_entrance_anchors and target not in forest_entrance_anchors:
                    _issue(issues, "error", path, f"{connection_path}.{field}", f"월드 지도에 없는 마을, 동굴 입구 또는 숲 입구입니다: {target}")
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
            if connection.get("surface_style") not in {"road", "natural", "water", "log_bridge"}:
                _issue(issues, "error", path, f"{connection_path}.surface_style", "road, natural, water, log_bridge 중 하나가 필요합니다.")
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
                            if "spawn_as_evolved" in addition and not isinstance(addition["spawn_as_evolved"], bool):
                                _issue(issues, "error", path, f"{addition_path}.spawn_as_evolved", "진화본 출현 여부는 true 또는 false여야 합니다.")
                            weight = addition.get("weight", 1)
                            if not isinstance(weight, int) or isinstance(weight, bool) or not 1 <= weight <= 10000:
                                _issue(issues, "error", path, f"{addition_path}.weight", "가중치는 1~10000 정수여야 합니다.")
                    _validate_pokemon_level_overrides(
                        pokemon_spawns.get("level_overrides", []), issues, path,
                        f"{connection_path}.pokemon_spawns.level_overrides", known_pokemon
                    )
                    _validate_route_encounter_pools(
                        pokemon_spawns, issues, path,
                        f"{connection_path}.pokemon_spawns"
                    )
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
            connection_cells.update(coordinates)
            for cell_index in range(1, len(coordinates)):
                q1, r1 = coordinates[cell_index - 1]
                q2, r2 = coordinates[cell_index]
                distance = (abs(q1 - q2) + abs(r1 - r2) + abs((-q1 - r1) - (-q2 - r2))) // 2
                if distance != 1:
                    _issue(issues, "error", path, f"{connection_path}.cells[{cell_index}]", "길 셀은 앞 셀과 맞닿아야 합니다.")
            for field, endpoint_index in (("from", 0), ("to", -1)):
                target = connection.get(field)
                entrance_anchor = cave_entrance_anchors.get(target) or forest_entrance_anchors.get(target)
                if entrance_anchor is not None and coordinates and coordinates[endpoint_index] != entrance_anchor:
                    _issue(issues, "error", path, f"{connection_path}.cells", f"길의 {field} 끝은 입구 {target} 좌표까지 이어져야 합니다.")
                town_footprint = settlement_footprints.get(target)
                if town_footprint and coordinates and all(
                    coordinate in town_footprint for coordinate in coordinates
                ):
                    _issue(
                        issues, "error", path, f"{connection_path}.cells",
                        f"길이 {target} 마을 영역 안에서 끝나 중심선이 생성되지 않습니다. 마을 경계 밖 셀까지 연결해야 합니다.",
                    )
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
        for entrance_id in forest_entrance_anchors:
            if entrance_id not in connected_targets:
                _issue(issues, "error", path, "$.connections", f"숲 입구까지 이어지는 길이 필요합니다: {entrance_id}")
        objects = world.get("objects", [])
        if not isinstance(objects, list):
            _issue(issues, "error", path, "$.objects", "커스텀 오브젝트 목록은 배열이어야 합니다.")
            objects = []
        available_structures = managed_structure_files(root)
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
                "structure": "NBT 오브젝트",
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
            if isinstance(anchor, dict) and all(
                isinstance(anchor.get(key), int) and not isinstance(anchor.get(key), bool)
                for key in ("q", "r")
            ) and (anchor["q"], anchor["r"]) not in connection_cells:
                _issue(
                    issues, "error", path, f"{object_path}.anchor",
                    "관문은 직접 그린 길의 셀 위에 배치해야 합니다.",
                )
            properties = custom_object.get("properties")
            if not isinstance(properties, dict):
                _issue(issues, "error", path, f"{object_path}.properties", "관문 설정 객체가 필요합니다.")
                continue
            center_placement = properties.get("center_placement")
            if center_placement not in {"gate", "gate_npc", "npc"}:
                _issue(issues, "error", path, f"{object_path}.properties.center_placement", "가운데 배치물은 gate, gate_npc, npc 중 하나여야 합니다.")
            building_enabled = center_placement in {"gate", "gate_npc"}
            if building_enabled and (not isinstance(resource, str) or not RESOURCE_ID.fullmatch(resource)):
                _issue(issues, "error", path, f"{object_path}.resource", "관문 건물 NBT 리소스 ID가 필요합니다.")
            elif building_enabled and available_structures and resource not in available_structures:
                _issue(
                    issues, "error", path, f"{object_path}.resource",
                    f"등록되지 않은 관문 건물 NBT입니다: {resource}",
                )
            rotation = custom_object.get("rotation")
            if not isinstance(rotation, int) or isinstance(rotation, bool) or rotation not in range(4):
                _issue(issues, "error", path, f"{object_path}.rotation", "관문 NBT 회전은 0~3이어야 합니다.")
            if properties.get("facing") not in {"north", "east", "south", "west"}:
                _issue(issues, "error", path, f"{object_path}.properties.facing", "관문 방향은 north/east/south/west 중 하나여야 합니다.")
            if properties.get("surrounding_type") not in {"wall", "natural"}:
                _issue(issues, "error", path, f"{object_path}.properties.surrounding_type", "주변 장애물은 wall 또는 natural이어야 합니다.")
            surrounding_type = properties.get("surrounding_type", "wall")
            block_fields = [("wall_block", "벽")] if surrounding_type == "wall" else []
            for field, label in block_fields:
                block = properties.get(field)
                if not isinstance(block, str) or not RESOURCE_ID.fullmatch(block):
                    _issue(issues, "error", path, f"{object_path}.properties.{field}", f"관문 {label} 블록 리소스 ID가 필요합니다.")
            numeric_limits = {
                "wall_thickness": (1, 15), "wall_height": (3, 32),
                "passage_width": (3, 31), "barrier_height": (8, 128),
            }
            for field, (minimum, maximum) in numeric_limits.items():
                number = properties.get(field)
                if not isinstance(number, int) or isinstance(number, bool) or not minimum <= number <= maximum:
                    _issue(issues, "error", path, f"{object_path}.properties.{field}", f"{minimum}~{maximum} 범위 정수가 필요합니다.")
                elif field in {"wall_thickness", "passage_width"} and number % 2 == 0:
                    _issue(issues, "error", path, f"{object_path}.properties.{field}", "관문 중심 정렬을 위해 홀수여야 합니다.")
            if isinstance(properties.get("barrier_height"), int) and isinstance(properties.get("wall_height"), int) and properties["barrier_height"] <= properties["wall_height"]:
                _issue(issues, "error", path, f"{object_path}.properties.barrier_height", "배리어 높이는 벽 높이보다 커야 합니다.")
            if properties.get("condition_mode") not in {"all", "any"}:
                _issue(issues, "error", path, f"{object_path}.properties.condition_mode", "조건 조합은 all 또는 any여야 합니다.")
            npc = properties.get("npc")
            if npc is not None and (not isinstance(npc, str) or not RESOURCE_ID.fullmatch(npc)):
                _issue(issues, "error", path, f"{object_path}.properties.npc", "올바른 EasyNPC 프리셋 리소스 ID가 필요합니다.")
            if center_placement in {"gate_npc", "npc"} and npc is None:
                _issue(issues, "error", path, f"{object_path}.properties.npc", "NPC가 포함된 가운데 배치물에는 NPC 프리셋이 필요합니다.")
            if center_placement == "gate" and npc is not None:
                _issue(issues, "error", path, f"{object_path}.properties.npc", "관문만 배치할 때는 NPC를 지정할 수 없습니다.")
            deny_message = properties.get("deny_message")
            if deny_message is not None and (not isinstance(deny_message, str) or not deny_message.strip() or len(deny_message) > 256):
                _issue(issues, "error", path, f"{object_path}.properties.deny_message", "차단 문구는 1~256자의 문자열이어야 합니다.")
            deny_dialog = properties.get("deny_dialog")
            if deny_dialog is not None and (
                not isinstance(deny_dialog, str)
                or not re.fullmatch(r"[a-z0-9_.-]+", deny_dialog)
            ):
                _issue(issues, "error", path, f"{object_path}.properties.deny_dialog", "차단 대화 라벨은 영문 소문자 형식이어야 합니다.")
            conditions = properties.get("conditions")
            if not isinstance(conditions, list):
                _issue(issues, "error", path, f"{object_path}.properties.conditions", "관문 조건 배열이 필요합니다.")
                continue
            for condition_index, condition in enumerate(conditions):
                condition_path = f"{object_path}.properties.conditions[{condition_index}]"
                _validate_player_condition(
                    condition, issues, path, condition_path
                )
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
            generations = settings.get("generations")
            generation = settings.get("generation", 0)
            series = settings.get("series")
            if isinstance(generations, list) and generations:
                matches = matches and entry.get("generation") in generations
            elif isinstance(series, str) and series:
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
    allowed_generations = sorted({
        value for value in world.get("pokemon_generations", [generation])
        if isinstance(value, int) and 1 <= value <= 9
    }) or [generation]
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

    route_documents: dict[str, dict[str, Any]] = {}
    route_dir = root / "content" / "routes"
    for path in route_dir.rglob("*.json") if route_dir.is_dir() else []:
        try:
            document = load_json(path)
            if isinstance(document, dict) and isinstance(document.get("id"), str):
                route_documents[document["id"]] = document
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            continue

    area_documents: list[tuple[str, dict[str, Any]]] = []
    for kind, directory_name in (("cave", "caves"), ("forest", "forests")):
        area_dir = root / "content" / directory_name / f"generation_{generation}"
        for path in sorted(area_dir.rglob("*.json")) if area_dir.is_dir() else []:
            try:
                document = load_json(path)
                if isinstance(document, dict) and isinstance(document.get("id"), str):
                    area_documents.append((kind, document))
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
            "generations": allowed_generations,
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
        profile_ids, candidates, habitat_variants = resolve(
            biome, settings, preferred_profile=zone.get("habitat_profile"),
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
        route_document = route_documents.get(connection.get("route_preset"), {})
        settings = route_document.get("pokemon_spawns")
        if not isinstance(settings, dict):
            settings = connection.get("pokemon_spawns")
        if not isinstance(settings, dict):
            settings = {"inherit_biome": True, "excluded_species": [], "additions": []}
        route_display_name = route_document.get("display_name", {})
        if isinstance(route_display_name, dict):
            route_display_name = route_display_name.get("ko_kr") or route_display_name.get("en_us")
        if not isinstance(route_display_name, str) or not route_display_name:
            route_display_name = connection.get("display_name") or connection.get("id", "")
        inherit_biome = settings.get("inherit_biome", True) is not False
        excluded = {
            species for species in settings.get("excluded_species", [])
            if isinstance(species, str)
        }
        additions = [
            addition for addition in settings.get("additions", [])
            if isinstance(addition, dict) and addition.get("species") in pokemon_by_id
        ]
        level_overrides = {
            override["species"]: {
                "min_level": override.get("min_level", 1),
                "max_level": override.get("max_level", 100),
            }
            for override in settings.get("level_overrides", [])
            if isinstance(override, dict) and override.get("species") in pokemon_by_id
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
            for species in (addition["species"] for addition in additions):
                if species not in selected_ids:
                    selected_ids.append(species)
            locations_by_cell[coordinate] = {
                **base,
                "q": coordinate[0],
                "r": coordinate[1],
                "kind": "route",
                "route": connection.get("id", ""),
                "route_name": route_display_name,
                "biome": base.get("biome", ""),
                "profile_ids": base.get("profile_ids", []),
                "habitat_variants": base.get("habitat_variants", {}),
                "habitat_labels": base.get("habitat_labels", []),
                "base_pokemon_ids": base_ids,
                "pokemon_ids": selected_ids,
                "custom_level_ranges": level_overrides,
                "count": len(selected_ids),
                # 길만 놓인 빈 셀은 의도적인 도로 구간이므로 미매핑 바이옴으로
                # 집계하지 않는다. 기반 바이옴이 있으면 그 상태를 그대로 따른다.
                "unmapped_biome": base.get("unmapped_biome", False),
            }

    area_locations: list[dict[str, Any]] = []
    for kind, document in area_documents:
        settings = document.get("random_encounters")
        if document.get("enabled", True) is False or not isinstance(settings, dict) or settings.get("enabled", True) is False:
            continue
        biome = settings.get("pokemon_biome")
        if not isinstance(biome, str):
            continue
        profile_ids = profiles_by_biome.get(biome, [])
        base_ids: list[str] = []
        if settings.get("inherit_biome", True) is not False:
            for profile_id in profile_ids:
                preview = _preview_biome_data(
                    biome_catalog,
                    pokemon,
                    {
                        "profile_id": profile_id,
                        "settings": {"generations": allowed_generations, "habitat_variant": 0},
                        "unconditional_spawns": [],
                    },
                )
                for entry in preview.get("pokemon", []):
                    pokemon_id = entry.get("id")
                    if isinstance(pokemon_id, str) and pokemon_id not in base_ids:
                        base_ids.append(pokemon_id)
        excluded = {
            value for value in settings.get("excluded_species", [])
            if isinstance(value, str)
        }
        selected_ids = [pokemon_id for pokemon_id in base_ids if pokemon_id not in excluded]
        for addition in settings.get("additions", []):
            species = addition.get("species") if isinstance(addition, dict) else None
            if isinstance(species, str) and species in pokemon_by_id and species not in selected_ids:
                selected_ids.append(species)
        custom_level_ranges = {
            override["species"]: {
                "min_level": override.get("min_level", settings.get("minimum_level", 1)),
                "max_level": override.get("max_level", settings.get("maximum_level", 100)),
            }
            for override in settings.get("level_overrides", [])
            if isinstance(override, dict) and override.get("species") in pokemon_by_id
        }
        display_name = document.get("display_name", {})
        area_locations.append({
            "kind": kind,
            "id": document["id"],
            "name": (
                display_name.get("ko_kr") or display_name.get("en_us") or document["id"]
                if isinstance(display_name, dict) else document["id"]
            ),
            "biome": biome,
            "profile_ids": profile_ids,
            "pokemon_ids": selected_ids,
            "minimum_level": settings.get("minimum_level", 1),
            "maximum_level": settings.get("maximum_level", 100),
            "custom_level_ranges": custom_level_ranges,
            "count": len(selected_ids),
        })

    available_ids = {
        pokemon_id
        for location in [*locations_by_cell.values(), *area_locations]
        for pokemon_id in location["pokemon_ids"]
    }
    available = [dict(entry) for entry in pokemon if entry.get("id") in available_ids]
    unavailable = []
    for entry in pokemon:
        if entry.get("id") in available_ids:
            continue
        if entry.get("generation") not in allowed_generations:
            continue
        result = dict(entry)
        result["unavailable_reason"] = "no_matching_world_location"
        unavailable.append(result)
    available.sort(key=lambda entry: entry.get("dex_number", 99999))
    unavailable.sort(key=lambda entry: entry.get("dex_number", 99999))
    locations = sorted(locations_by_cell.values(), key=lambda entry: (entry["r"], entry["q"]))
    return {
        "generation": generation,
        "world_id": world.get("id", ""),
        "pokemon_generations": allowed_generations,
        "summary": {
            "locations": len(locations),
            "areas": len(area_locations),
            "available": len(available),
            "unavailable": len(unavailable),
            "unmapped_locations": sum(entry["unmapped_biome"] for entry in locations),
        },
        "locations": locations,
        "area_locations": area_locations,
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
    forest_documents: dict[str, dict[str, Any]] = {}
    forest_dir = root / "content" / "forests"
    for forest_path in forest_dir.rglob("*.json") if forest_dir.is_dir() else []:
        try:
            forest = load_json(forest_path)
            if isinstance(forest, dict) and isinstance(forest.get("id"), str):
                forest_documents[forest["id"]] = forest
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            continue
    underground_documents: dict[str, dict[str, Any]] = {}
    underground_dir = root / "content" / "underground_roads"
    for underground_path in underground_dir.rglob("*.json") if underground_dir.is_dir() else []:
        try:
            underground = load_json(underground_path)
            if isinstance(underground, dict) and isinstance(underground.get("id"), str):
                underground_documents[underground["id"]] = underground
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            continue
    route_ids = {
        item["id"] for item in _list_documents(root, "routes")
        if isinstance(item.get("id"), str) and item["id"]
    }
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
        candidate_issues = validate_hex_worlds(
            candidate_root, settlement_ids, cave_documents, forest_documents, route_ids,
            underground_documents=underground_documents,
            structure_root=root,
        )
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
    if operation_type in PLAYER_CONDITION_TYPES:
        _validate_player_condition(operation, issues, file, data_path)
    elif operation_type in {"next_dialogue", "open_dialogue"}:
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
    elif operation_type == "set_flag":
        _resource_id(operation.get("key"), issues, file, f"{data_path}.key")
        if "value" not in operation or not isinstance(operation.get("value"), (str, int, float, bool)):
            _issue(issues, "error", file, f"{data_path}.value", "문자열, 숫자 또는 boolean 값이 필요합니다.")
    elif operation_type == "mark_clear":
        _resource_id(operation.get("key"), issues, file, f"{data_path}.key")
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
    elif operation_type == "unlock_feature":
        if operation.get("feature") not in {"map", "settlement_teleport", "pc"}:
            _issue(issues, "error", file, f"{data_path}.feature", "지도, 마을 순간이동 또는 포켓몬 PC 기능 ID가 필요합니다.")
    elif operation_type == "set_level_cap":
        level_cap = operation.get("level_cap")
        if not isinstance(level_cap, int) or isinstance(level_cap, bool) or not 1 <= level_cap <= 100:
            _issue(issues, "error", file, f"{data_path}.level_cap", "레벨캡은 1~100 정수여야 합니다.")
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


def _validate_player_condition(
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
    elif condition_type == "badge":
        _resource_id(condition.get("badge"), issues, file, f"{data_path}.badge")
    elif condition_type == "party_count":
        if condition.get("operator") not in {"==", "!=", ">", ">=", "<", "<="}:
            _issue(issues, "error", file, f"{data_path}.operator", "지원하지 않는 파티 수 비교 연산자입니다.")
        number = condition.get("value")
        if not isinstance(number, int) or isinstance(number, bool) or not 0 <= number <= 6:
            _issue(issues, "error", file, f"{data_path}.value", "파티 포켓몬 수는 0~6 사이의 정수여야 합니다.")
    elif condition_type in {"flag", "flag_equals"}:
        _resource_id(condition.get("key"), issues, file, f"{data_path}.key")
        if not isinstance(condition.get("value"), (str, int, float, bool)):
            _issue(issues, "error", file, f"{data_path}.value", "플래그 비교 값이 필요합니다.")
    elif condition_type == "has_item":
        _resource_id(condition.get("item"), issues, file, f"{data_path}.item")
        count = condition.get("count", 1)
        if not isinstance(count, int) or isinstance(count, bool) or count < 1:
            _issue(issues, "error", file, f"{data_path}.count", "아이템 수량은 1 이상 정수여야 합니다.")
    elif condition_type == "always":
        return
    else:
        _issue(issues, "error", file, f"{data_path}.type", "지원하지 않는 공용 플레이어 조건 타입입니다.")


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
    settlement_flags = root.get("settlement_flags", [])
    allowed_settlement_flags = {"special_site", "industrial", "non_residential", "no_ambient_npcs"}
    if not isinstance(settlement_flags, list) or any(not isinstance(flag, str) for flag in settlement_flags) or len(settlement_flags) != len(set(settlement_flags)) or any(flag not in allowed_settlement_flags for flag in settlement_flags):
        _issue(issues, "error", path, "$.settlement_flags", "지원하는 지역 플래그를 중복 없이 지정해야 합니다.")
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

    biome_layout = _require_object(
        root.get("biome_layout"), issues, path, "$.biome_layout"
    )
    if biome_layout is not None:
        if biome_layout.get("arrangement") not in {"organic_patches", "sectors", "concentric"}:
            _issue(issues, "error", path, "$.biome_layout.arrangement", "지원하지 않는 바이옴 배치 방식입니다.")
        transition_width = biome_layout.get("transition_width")
        if not isinstance(transition_width, int) or isinstance(transition_width, bool) or not 0 <= transition_width <= 64:
            _issue(issues, "error", path, "$.biome_layout.transition_width", "0 이상 64 이하의 정수여야 합니다.")
        if biome_layout.get("pokemon_biome_set") is not None:
            _resource_id(
                biome_layout.get("pokemon_biome_set"), issues, path,
                "$.biome_layout.pokemon_biome_set",
            )
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
        if generation_profile is not None and not isinstance(
            generation_profile.get("residential_buildings_enabled", True), bool
        ):
            _issue(issues, "error", path, "$.structure_profile.generation_profile.residential_buildings_enabled", "boolean이어야 합니다.")
        layout_mode = structure_profile.get("layout_mode", "automatic")
        if layout_mode not in {"automatic", "manual"}:
            _issue(issues, "error", path, "$.structure_profile.layout_mode", "automatic 또는 manual이어야 합니다.")
        if layout_mode == "manual" and not isinstance(structure_profile.get("manual_layout"), dict):
            _issue(issues, "error", path, "$.structure_profile.manual_layout", "수동 배치 모드에는 수동 배치 데이터가 필요합니다.")
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
                for field in (
                    "require_previous_gym", "previous_badge", "condition_mode",
                    "conditions", "locked_dialogue", "blocking_npc",
                ):
                    if field in entrance:
                        _issue(
                            issues, "error", path,
                            f"$.structure_profile.gym.entrance.{field}",
                            "체육관 출입 조건은 마을이 아니라 체육관 카탈로그의 access에서 설정해야 합니다.",
                        )
                for field in ("door_offset", "outside_offset"):
                    if field in entrance:
                        _validate_block_position(
                            entrance[field], issues, path,
                            f"$.structure_profile.gym.entrance.{field}",
                        )
                if entrance.get("facing", "north") not in {"north", "east", "south", "west"}:
                    _issue(issues, "error", path, "$.structure_profile.gym.entrance.facing", "방향은 north/east/south/west 중 하나여야 합니다.")
                for field in ("enter_dialogue",):
                    if field not in entrance:
                        continue
                    dialogue = entrance[field]
                    if not isinstance(dialogue, list) or any(not isinstance(line, str) or not line.strip() for line in dialogue):
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
        auto_place_npcs = placement.get("auto_place_npcs", False)
        if not isinstance(auto_place_npcs, bool):
            _issue(issues, "error", path, "$.npc_placement.auto_place_npcs", "boolean이어야 합니다.")
        _validate_trainer_population(
            placement.get("trainer_population"), issues, path,
            "$.npc_placement.trainer_population",
        )
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
    _validate_npc_placement_profile(root.get("placement_profile"), issues, path)
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


def _validate_npc_placement_profile(
    value: Any, issues: list[Issue], path: Path
) -> None:
    """Validate optional placement metadata shared by trainer and ambient NPCs."""
    if value is None:
        return
    profile = _require_object(value, issues, path, "$.placement_profile")
    if profile is None:
        return
    classification = profile.get("classification")
    if classification not in {"trainer", "ambient"}:
        _issue(issues, "error", path, "$.placement_profile.classification", "trainer 또는 ambient여야 합니다.")
    expected_level = profile.get("expected_level")
    if expected_level is not None and (
        not isinstance(expected_level, int) or isinstance(expected_level, bool)
        or not 1 <= expected_level <= 100
    ):
        _issue(issues, "error", path, "$.placement_profile.expected_level", "예상 레벨은 비워 두거나 1~100 정수여야 합니다.")
    preferred_biomes = _require_list(
        profile.get("preferred_biomes"), issues, path, "$.placement_profile.preferred_biomes"
    )
    if preferred_biomes is not None:
        for index, biome in enumerate(preferred_biomes):
            _resource_id(biome, issues, path, f"$.placement_profile.preferred_biomes[{index}]")
        if len(preferred_biomes) != len(set(value for value in preferred_biomes if isinstance(value, str))):
            _issue(issues, "error", path, "$.placement_profile.preferred_biomes", "선호 바이옴은 중복될 수 없습니다.")
    for field in ("automatic_town_placement", "automatic_route_placement"):
        if not isinstance(profile.get(field), bool):
            _issue(issues, "error", path, f"$.placement_profile.{field}", "boolean이어야 합니다.")
    if classification == "ambient" and profile.get("automatic_route_placement") is True:
        _issue(issues, "error", path, "$.placement_profile.automatic_route_placement", "단순 NPC는 길 자동 배치 대상이 될 수 없습니다.")


def _validate_trainer_population(
    value: Any, issues: list[Issue], path: Path, base: str
) -> None:
    if value is None:
        return
    population = _require_object(value, issues, path, base)
    if population is None:
        return
    if not isinstance(population.get("enabled"), bool):
        _issue(issues, "error", path, f"{base}.enabled", "boolean이어야 합니다.")
    maximum = population.get("max_active", population.get("count"))
    if not isinstance(maximum, int) or isinstance(maximum, bool) or not 0 <= maximum <= 128:
        _issue(issues, "error", path, f"{base}.max_active", "최대 트레이너 수는 0~128 정수여야 합니다.")
    use_defaults = population.get("use_biome_defaults", True)
    if not isinstance(use_defaults, bool):
        _issue(issues, "error", path, f"{base}.use_biome_defaults", "boolean이어야 합니다.")
    direct = population.get("direct_trainers", [])
    if not isinstance(direct, list):
        _issue(issues, "error", path, f"{base}.direct_trainers", "직접 지정 트레이너는 배열이어야 합니다.")
    else:
        for index, trainer_id in enumerate(direct):
            _resource_id(trainer_id, issues, path, f"{base}.direct_trainers[{index}]")
        if len(direct) != len(set(value for value in direct if isinstance(value, str))):
            _issue(issues, "error", path, f"{base}.direct_trainers", "직접 지정 트레이너는 중복될 수 없습니다.")
    trigger = population.get("trigger_override", "proximity")
    if trigger not in {"source", "preset", "interact", "proximity"}:
        _issue(issues, "error", path, f"{base}.trigger_override", "지원하지 않는 지역 조우 정책입니다.")
    overrides = population.get("trainer_trigger_overrides", {})
    if not isinstance(overrides, dict):
        _issue(issues, "error", path, f"{base}.trainer_trigger_overrides", "NPC별 조우 정책은 객체여야 합니다.")
    else:
        for trainer_id, trainer_trigger in overrides.items():
            _resource_id(trainer_id, issues, path, f"{base}.trainer_trigger_overrides")
            if trainer_id not in direct:
                _issue(issues, "error", path, f"{base}.trainer_trigger_overrides", "직접 지정한 트레이너만 개별 조우 정책을 설정할 수 있습니다.")
            if trainer_trigger not in {"source", "preset", "interact", "proximity"}:
                _issue(issues, "error", path, f"{base}.trainer_trigger_overrides.{trainer_id}", "지원하지 않는 개별 조우 정책입니다.")


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
    event_runtime = root.get("event_runtime")
    if event_runtime is None:
        _issue(issues, "error", path, "$.event_runtime", "NPC 이벤트 실행 방식은 cves_v5 또는 명시적인 레거시 easy_npc_v4여야 합니다.")
    if event_runtime is not None:
        event_runtime = _require_object(event_runtime, issues, path, "$.event_runtime")
        if event_runtime is not None:
            engine = event_runtime.get("engine")
            if engine not in {"easy_npc_v4", "cves_v5"}:
                _issue(issues, "error", path, "$.event_runtime.engine", "easy_npc_v4 또는 cves_v5가 필요합니다.")
            if engine == "cves_v5":
                script_id = event_runtime.get("script_id")
                if not isinstance(script_id, str) or not re.fullmatch(
                    r"[a-z0-9_.-]+:event_script/[a-z0-9_./-]+", script_id
                ):
                    _issue(issues, "error", path, "$.event_runtime.script_id", "namespace:event_script/path 형식이 필요합니다.")
                if event_runtime.get("authoring") not in {"preset", "custom"}:
                    _issue(issues, "error", path, "$.event_runtime.authoring", "preset 또는 custom이 필요합니다.")
    event_design = _require_object(root.get("event_design"), issues, path, "$.event_design")
    if event_design is not None:
        design_mode = event_design.get("mode")
        if design_mode == "preset":
            preset = _require_object(event_design.get("preset"), issues, path, "$.event_design.preset")
            if preset is not None:
                preset_type = preset.get("type")
                if preset_type not in {"simple", "repeat", "item", *BATTLE_PRESETS}:
                    _issue(issues, "error", path, "$.event_design.preset.type", "지원하지 않는 NPC 행동 프리셋입니다.")
                trigger = _require_object(preset.get("initial_trigger"), issues, path, "$.event_design.preset.initial_trigger")
                if trigger is not None:
                    trigger_type = trigger.get("type")
                    if trigger_type not in {"interact", "proximity"}:
                        _issue(issues, "error", path, "$.event_design.preset.initial_trigger.type", "말 걸기 또는 범위 진입 트리거가 필요합니다.")
                    trigger_range = trigger.get("range")
                    if not isinstance(trigger_range, (int, float)) or isinstance(trigger_range, bool) or trigger_range <= 0:
                        _issue(issues, "error", path, "$.event_design.preset.initial_trigger.range", "0보다 큰 발동 거리가 필요합니다.")
                _localized_text(preset.get("first_text"), issues, path, "$.event_design.preset.first_text")
                if preset_type == "item" and "after_item_text" in preset:
                    _localized_text(preset.get("after_item_text"), issues, path, "$.event_design.preset.after_item_text")
                if preset_type in BATTLE_PRESETS:
                    _resource_id(preset.get("battle"), issues, path, "$.event_design.preset.battle")
                    proximity = preset.get("proximity_trigger")
                    if proximity is not None:
                        proximity = _require_object(
                            proximity, issues, path,
                            "$.event_design.preset.proximity_trigger",
                        )
                        if proximity is not None:
                            battle_range = proximity.get("battle_range", 6)
                            warning_range = proximity.get("warning_range", 9)
                            if (
                                not isinstance(battle_range, (int, float))
                                or isinstance(battle_range, bool)
                                or battle_range <= 0
                            ):
                                _issue(
                                    issues, "error", path,
                                    "$.event_design.preset.proximity_trigger.battle_range",
                                    "0보다 큰 강제전투 범위가 필요합니다.",
                                )
                            if (
                                not isinstance(warning_range, (int, float))
                                or isinstance(warning_range, bool)
                                or not isinstance(battle_range, (int, float))
                                or isinstance(battle_range, bool)
                                or warning_range <= battle_range
                            ):
                                _issue(
                                    issues, "error", path,
                                    "$.event_design.preset.proximity_trigger.warning_range",
                                    "경고 범위는 강제전투 범위보다 커야 합니다.",
                                )
                    after_victory = _require_object(preset.get("after_victory_trigger"), issues, path, "$.event_design.preset.after_victory_trigger")
                    if after_victory is not None and after_victory.get("type") != "interact":
                        _issue(issues, "error", path, "$.event_design.preset.after_victory_trigger.type", "승리 후에는 플레이어가 말을 걸 때만 시작할 수 있습니다.")
                try:
                    root = materialize_event_document(root)
                except (KeyError, TypeError, ValueError) as error:
                    _issue(issues, "error", path, "$.event_design.preset", str(error))
        elif design_mode == "easy_npc_events":
            if not isinstance(root.get("events"), list):
                _issue(issues, "error", path, "$.events", "직접 이벤트 설계에는 events 목록이 필요합니다.")
        else:
            _issue(issues, "error", path, "$.event_design.mode", "preset 또는 easy_npc_events여야 합니다.")
    if npc_id and ":npc/" not in npc_id:
        _issue(issues, "error", path, "$.id", "NPC ID는 namespace:npc/path 형식이어야 합니다.")
    if "placement" in root:
        _issue(issues, "error", path, "$.placement", "NPC 배치는 마을의 npc_placement.trainer_slots에서 관리해야 합니다.")
    if not isinstance(root.get("enabled"), bool):
        _issue(issues, "error", path, "$.enabled", "boolean이어야 합니다.")
    _localized_text(root.get("name"), issues, path, "$.name")
    _validate_npc_placement_profile(root.get("placement_profile"), issues, path)
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
        "start_starter_roulette", "teleport_to_gate", "unlock_feature", "set_level_cap", "end",
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
            elif command_type in {"set_flag", "mark_clear", "give_money", "take_money", "give_item", "grant_loot", "grant_badge", "grant_field_move", "unlock_feature", "set_level_cap", "start_starter_roulette"}:
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
    _validate_npc_placement_profile(root.get("placement_profile"), issues, path)
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
        if battle.get("level_mode") not in {"fixed", "map_scaling"}:
            _issue(issues, "error", path, "$.battle.level_mode", "지원하지 않는 레벨 방식입니다.")
        if battle.get("level_mode") == "map_scaling":
            level_offset = battle.get("level_offset")
            if (
                not isinstance(level_offset, int)
                or isinstance(level_offset, bool)
                or not -99 <= level_offset <= 99
            ):
                _issue(
                    issues,
                    "error",
                    path,
                    "$.battle.level_offset",
                    "맵 레벨 보정은 -99~99 정수여야 합니다.",
                )
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


def validate_starter_settings(root: Path, data: Any) -> list[Issue]:
    path = root / "content" / "catalogs" / "starter-settings.json"
    issues: list[Issue] = []
    document = _require_object(data, issues, path, "$")
    if document is None:
        return issues
    if document.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "스타팅 설정 버전은 1이어야 합니다.")
    default_generation = document.get("default_generation")
    generations = _require_list(document.get("generations"), issues, path, "$.generations") or []
    settlement_ids = {
        item.get("id")
        for item in _list_documents(root, "settlements")
        if isinstance(item.get("id"), str)
    }
    seen: set[int] = set()
    for index, value in enumerate(generations):
        entry_path = f"$.generations[{index}]"
        entry = _require_object(value, issues, path, entry_path)
        if entry is None:
            continue
        generation = entry.get("generation")
        if not isinstance(generation, int) or isinstance(generation, bool) or not 1 <= generation <= 9:
            _issue(issues, "error", path, f"{entry_path}.generation", "세대는 1부터 9까지의 정수여야 합니다.")
        elif generation in seen:
            _issue(issues, "error", path, f"{entry_path}.generation", "같은 세대 설정이 중복되었습니다.")
        else:
            seen.add(generation)
        town = entry.get("town")
        if town not in settlement_ids:
            _issue(issues, "error", path, f"{entry_path}.town", "프로젝트에 존재하는 시작 마을을 선택해야 합니다.")
        spawn = _require_object(entry.get("spawn"), issues, path, f"{entry_path}.spawn")
        if spawn is None:
            continue
        mode = spawn.get("mode")
        if mode not in {"town", "building", "slot"}:
            _issue(issues, "error", path, f"{entry_path}.spawn.mode", "시작 방식은 town, building, slot 중 하나여야 합니다.")
        if "set_respawn" in spawn and not isinstance(spawn["set_respawn"], bool):
            _issue(issues, "error", path, f"{entry_path}.spawn.set_respawn", "리스폰 지점 적용 여부는 true 또는 false여야 합니다.")
        if mode in {"building", "slot"} and not isinstance(spawn.get("building"), str):
            _issue(issues, "error", path, f"{entry_path}.spawn.building", "시작 건물 ID가 필요합니다.")
        if mode == "slot":
            for field in ("space", "npc_slot"):
                if not isinstance(spawn.get(field), str) or not spawn[field]:
                    _issue(issues, "error", path, f"{entry_path}.spawn.{field}", "NPC 슬롯의 내부 공간과 라벨이 필요합니다.")
    if default_generation not in seen:
        _issue(issues, "error", path, "$.default_generation", "기본 시작 세대는 등록된 세대 중 하나여야 합니다.")
    return issues


def save_starter_settings(root: Path, data: Any) -> list[Issue]:
    target = root / "content" / "catalogs" / "starter-settings.json"
    issues = validate_starter_settings(root, data)
    if not any(issue.level == "error" for issue in issues):
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    return issues


DIALOGUE_THEME_DEFAULTS: dict[str, Any] = {
    "$schema": "../schemas/dialogue-theme.schema.json",
    "schema_version": 1,
    "font": {"resource": "minecraft:default", "body_scale": 1.0, "speaker_scale": 1.0, "hint_scale": 0.85},
    "panel": {"background": "#f8fbff", "background_opacity": 0.98, "border": "#72a8d4", "inner_border": "#d9f4ff", "border_width": 3, "inner_border_width": 2, "corner_radius": 18, "shadow": "#24445f", "shadow_opacity": 0.45, "shadow_offset": 3, "speaker_color": "#c52b2b", "text_color": "#27323d", "hint_color": "#57758e", "page_color": "#72a8d4", "height_ratio": 0.333, "min_height": 112, "max_height": 166},
    "choice": {"panel_background": "#f8fbff", "panel_opacity": 0.98, "panel_border": "#72a8d4", "panel_inner_border": "#d9f4ff", "corner_radius": 12, "panel_width": 190, "panel_gap": 8, "panel_padding": 10, "selected_background": "#d9f4ff", "hover_background": "#eaf7ff", "background": "#f8fbff", "selected_accent": "#4f8fc2", "text_color": "#27323d", "row_height": 24},
    "menu": {"background": "#f8fbff", "background_opacity": 0.98, "border": "#72a8d4", "inner_border": "#d9f4ff", "corner_radius": 14, "row_radius": 7, "selected_background": "#d9f4ff", "hover_background": "#eaf7ff", "text_color": "#27323d", "selected_text_color": "#173f5f", "accent": "#4f8fc2"},
    "portrait": {"yaw_degrees": 18.0, "pitch_degrees": -4.0, "scale": 0.7, "background": "#0a1017", "background_opacity": 0.72, "accent": "#5e7789"},
}


def _default_gacha_reward(machine_type: str) -> dict[str, Any]:
    if machine_type == "pokemon":
        return {"id": "pikachu", "display_name": "피카츄", "kind": "pokemon", "value": "pikachu level=15", "count": 1, "weight": 1.0, "selectable": False}
    if machine_type == "technical_machine":
        return {"id": "protect", "display_name": "방어 기술머신", "kind": "item", "value": "tmcraft:tm_protect", "count": 1, "weight": 1.0, "selectable": False}
    return {"id": "poke_ball", "display_name": "몬스터볼", "kind": "item", "value": "cobblemon:poke_ball", "count": 5, "weight": 1.0, "selectable": False}


def _default_gacha_machine(machine_id: str, display_name: str, machine_type: str) -> dict[str, Any]:
    model_blocks = {
        "pokemon": "cobblemoncasino:pokemon_gacha_machine",
        "item": "cobblemoncasino:gacha_machine",
        "technical_machine": "cobblemoncasino:event_gacha_machine",
    }
    return {
        "id": machine_id, "display_name": display_name, "machine_type": machine_type,
        "enabled": True,
        "appearance": {"model_block": model_blocks[machine_type], "facing": "north", "show_nameplate": True},
        "themes": [_default_gacha_theme(
            "default", display_name, 1, f"default_casino/{machine_type}/default", machine_type
        )],
    }


def _default_gacha_theme(
    theme_id: str, display_name: str, ticket_cost: int, pity_group: str, machine_type: str
) -> dict[str, Any]:
    return {
        "id": theme_id, "display_name": display_name, "ticket_cost": ticket_cost,
        "pity_group": pity_group,
        "rarities": [{"id": "common", "display_name": "일반", "weight": 100.0,
                      "rewards": [_default_gacha_reward(machine_type)]}],
        "pity": {
            "soft": {"enabled": False, "start": 30, "max_at": 60, "target_rarity": "common", "max_chance": 0.25},
            "hard": {"enabled": False, "count": 80, "target_rarity": "common"},
            "selection": {"enabled": False, "points_per_pull": 1, "required_points": 100},
        },
    }


GACHA_MACHINE_CATALOG_DEFAULTS: dict[str, Any] = {
    "$schema": "../schemas/gacha-machines.schema.json", "schema_version": 6,
    "tickets": {
        "pokemon": {"display_name": "포켓몬 가챠 티켓", "price": 500, "purchase_min": 1, "purchase_max": 64},
        "item": {"display_name": "아이템 가챠 티켓", "price": 200, "purchase_min": 1, "purchase_max": 64},
        "technical_machine": {"display_name": "기술머신 가챠 티켓", "price": 300, "purchase_min": 1, "purchase_max": 64},
    },
    "casino_sets": [{"id": "cobbleventure:casino/default", "display_name": "기본 카지노", "machines": {"pokemon": "cobbleventure:starter_gacha", "item": "cobbleventure:item_gacha", "technical_machine": "cobbleventure:technical_machine_gacha"}}],
    "machines": [
        _default_gacha_machine("cobbleventure:starter_gacha", "포켓몬 가챠", "pokemon"),
        _default_gacha_machine("cobbleventure:item_gacha", "아이템 가챠", "item"),
        _default_gacha_machine("cobbleventure:technical_machine_gacha", "기술머신 가챠", "technical_machine"),
    ],
}

def gacha_machine_catalog_path(root: Path) -> Path:
    return root / "content" / "catalogs" / "gacha-machines.json"


def gacha_machine_catalog_payload(root: Path) -> dict[str, Any]:
    path = gacha_machine_catalog_path(root)
    data = load_json(path) if path.is_file() else copy.deepcopy(GACHA_MACHINE_CATALOG_DEFAULTS)
    if data.get("schema_version") == 4:
        data = copy.deepcopy(data)
        data["schema_version"] = 5
        for machine in data.get("machines", []):
            if not isinstance(machine, dict) or machine.get("themes"):
                continue
            machine["themes"] = [{
                "id": "default",
                "display_name": machine.get("display_name", "기본 가챠"),
                "ticket_cost": 1,
                "pity_group": machine.pop("pity_group", machine.get("id", "default")),
                "rarities": machine.pop("rarities", []),
                "pity": machine.pop("pity", {}),
            }]
    if data.get("schema_version") == 5:
        data = copy.deepcopy(data)
        catalog = {entry.get("id"): entry for entry in data.get("reward_catalog", []) if isinstance(entry, dict)}
        for machine in data.get("machines", []):
            if not isinstance(machine, dict):
                continue
            for theme in machine.get("themes", []):
                if not isinstance(theme, dict):
                    continue
                for rarity in theme.get("rarities", []):
                    if not isinstance(rarity, dict):
                        continue
                    converted: list[dict[str, Any]] = []
                    for reward in rarity.get("rewards", []):
                        if not isinstance(reward, dict):
                            continue
                        template = catalog.get(reward.get("catalog_id"), {})
                        converted.append({
                            "id": template.get("id", reward.get("catalog_id", "missing_reward")),
                            "display_name": template.get("display_name", reward.get("catalog_id", "보상")),
                            "kind": template.get("kind", "pokemon" if machine.get("machine_type") == "pokemon" else "item"),
                            "value": template.get("value", "pikachu level=15" if machine.get("machine_type") == "pokemon" else "minecraft:stone"),
                            "count": 1 if machine.get("machine_type") == "pokemon" else max(1, int(template.get("count", 1))),
                            "weight": reward.get("weight", 1.0),
                            "selectable": reward.get("selectable", False),
                        })
                    rarity["rewards"] = converted
        data.pop("reward_catalog", None)
        data["schema_version"] = 6
    return data


def _validate_gacha_theme(
    issues: list[Issue], path: Path, base: str, theme: Any,
    machine_type: Any,
) -> None:
    if not isinstance(theme, dict):
        _issue(issues, "error", path, base, "가챠 테마는 객체여야 합니다.")
        return
    if not isinstance(theme.get("display_name"), str) or not theme["display_name"].strip():
        _issue(issues, "error", path, f"{base}.display_name", "테마 표시 이름이 필요합니다.")
    ticket_cost = theme.get("ticket_cost")
    if not isinstance(ticket_cost, int) or isinstance(ticket_cost, bool) or ticket_cost < 1 or ticket_cost > 6400:
        _issue(issues, "error", path, f"{base}.ticket_cost", "티켓 소모량은 1~6400의 정수여야 합니다.")
    if not isinstance(theme.get("pity_group"), str) or not theme["pity_group"].strip():
        _issue(issues, "error", path, f"{base}.pity_group", "테마별 천장 저장 그룹이 필요합니다.")
    rarities = theme.get("rarities")
    if not isinstance(rarities, list) or not rarities:
        _issue(issues, "error", path, f"{base}.rarities", "하나 이상의 희귀도 풀이 필요합니다.")
        return
    rarity_ids: set[str] = set()
    reward_ids: set[str] = set()
    for rarity_index, rarity in enumerate(rarities):
        rarity_path = f"{base}.rarities[{rarity_index}]"
        if not isinstance(rarity, dict):
            _issue(issues, "error", path, rarity_path, "희귀도 풀은 객체여야 합니다.")
            continue
        rarity_id = rarity.get("id")
        if not isinstance(rarity_id, str) or re.fullmatch(r"[a-z0-9_.-]+", rarity_id) is None or rarity_id in rarity_ids:
            _issue(issues, "error", path, f"{rarity_path}.id", "희귀도 ID는 중복되지 않은 소문자 ID여야 합니다.")
        else:
            rarity_ids.add(rarity_id)
        weight = rarity.get("weight")
        if not isinstance(weight, (int, float)) or isinstance(weight, bool) or weight <= 0:
            _issue(issues, "error", path, f"{rarity_path}.weight", "희귀도 가중치는 0보다 커야 합니다.")
        rewards = rarity.get("rewards")
        if not isinstance(rewards, list) or not rewards:
            _issue(issues, "error", path, f"{rarity_path}.rewards", "보상을 하나 이상 추가해야 합니다.")
            continue
        for reward_index, reward in enumerate(rewards):
            reward_path = f"{rarity_path}.rewards[{reward_index}]"
            if not isinstance(reward, dict):
                _issue(issues, "error", path, reward_path, "보상은 객체여야 합니다.")
                continue
            reward_id = reward.get("id")
            if not isinstance(reward_id, str) or re.fullmatch(r"[a-z0-9_.-]+", reward_id) is None or reward_id in reward_ids:
                _issue(issues, "error", path, f"{reward_path}.id", "같은 테마에서 중복되지 않은 소문자 보상 ID가 필요합니다.")
            else:
                reward_ids.add(reward_id)
            if not isinstance(reward.get("display_name"), str) or not reward["display_name"].strip():
                _issue(issues, "error", path, f"{reward_path}.display_name", "보상 표시 이름이 필요합니다.")
            kind = reward.get("kind")
            expected_kind = "pokemon" if machine_type == "pokemon" else "item"
            if kind != expected_kind:
                _issue(issues, "error", path, f"{reward_path}.kind", "기계 종류에 맞는 보상 종류가 필요합니다.")
            value = reward.get("value")
            if not isinstance(value, str) or not value.strip() or (kind == "item" and RESOURCE_ID.fullmatch(value) is None):
                _issue(issues, "error", path, f"{reward_path}.value", "아이템 ID 또는 PokemonProperties 문자열이 필요합니다.")
            count = reward.get("count")
            if machine_type == "pokemon" and count != 1:
                _issue(issues, "error", path, f"{reward_path}.count", "포켓몬 보상 수량은 항상 1이어야 합니다.")
            elif not isinstance(count, int) or isinstance(count, bool) or count < 1:
                _issue(issues, "error", path, f"{reward_path}.count", "수량은 1 이상의 정수여야 합니다.")
            number = reward.get("weight")
            if not isinstance(number, (int, float)) or isinstance(number, bool) or number <= 0:
                _issue(issues, "error", path, f"{reward_path}.weight", "0보다 큰 숫자여야 합니다.")
            if not isinstance(reward.get("selectable"), bool):
                _issue(issues, "error", path, f"{reward_path}.selectable", "선택 가능 여부는 true 또는 false여야 합니다.")
    pity = theme.get("pity")
    if not isinstance(pity, dict):
        _issue(issues, "error", path, f"{base}.pity", "천장 설정이 필요합니다.")
        return
    soft, hard, selection = pity.get("soft"), pity.get("hard"), pity.get("selection")
    if not all(isinstance(entry, dict) for entry in (soft, hard, selection)):
        _issue(issues, "error", path, f"{base}.pity", "소프트·확정·선택 천장 설정이 모두 필요합니다.")
        return
    for section_name, section in (("soft", soft), ("hard", hard)):
        if section.get("enabled") is True and section.get("target_rarity") not in rarity_ids:
            _issue(issues, "error", path, f"{base}.pity.{section_name}.target_rarity", "존재하는 희귀도 ID를 지정해야 합니다.")
    if soft.get("enabled") is True:
        start, max_at, chance = soft.get("start"), soft.get("max_at"), soft.get("max_chance")
        if not isinstance(start, int) or isinstance(start, bool) or start < 1 or not isinstance(max_at, int) or isinstance(max_at, bool) or max_at < start:
            _issue(issues, "error", path, f"{base}.pity.soft", "소프트 천장 시작·최대 횟수가 올바르지 않습니다.")
        if not isinstance(chance, (int, float)) or isinstance(chance, bool) or not 0 < chance <= 1:
            _issue(issues, "error", path, f"{base}.pity.soft.max_chance", "최대 확률은 0 초과 1 이하여야 합니다.")
    if hard.get("enabled") is True and (not isinstance(hard.get("count"), int) or isinstance(hard.get("count"), bool) or hard["count"] < 1):
        _issue(issues, "error", path, f"{base}.pity.hard.count", "확정 천장 횟수는 1 이상이어야 합니다.")
    if selection.get("enabled") is True:
        for key in ("points_per_pull", "required_points"):
            if not isinstance(selection.get(key), int) or isinstance(selection.get(key), bool) or selection[key] < 1:
                _issue(issues, "error", path, f"{base}.pity.selection.{key}", "선택 천장 포인트는 1 이상의 정수여야 합니다.")
        if not any(reward.get("selectable") is True for rarity in rarities if isinstance(rarity, dict) for reward in rarity.get("rewards", []) if isinstance(reward, dict)):
            _issue(issues, "error", path, f"{base}.pity.selection", "선택 천장을 켰다면 선택 가능한 보상이 하나 이상 필요합니다.")


def validate_gacha_machine_catalog(root: Path, data: Any) -> list[Issue]:
    path = gacha_machine_catalog_path(root)
    issues: list[Issue] = []
    document = _require_object(data, issues, path, "$")
    if document is None:
        return issues
    if document.get("schema_version") != 6:
        _issue(issues, "error", path, "$.schema_version", "가챠 기계 설정 버전은 6이어야 합니다.")
    ticket_types = ("pokemon", "item", "technical_machine")
    tickets = document.get("tickets")
    if not isinstance(tickets, dict):
        _issue(issues, "error", path, "$.tickets", "공통 티켓 3종 설정이 필요합니다.")
        tickets = {}
    for ticket_type in ticket_types:
        ticket = tickets.get(ticket_type)
        ticket_path = f"$.tickets.{ticket_type}"
        if not isinstance(ticket, dict):
            _issue(issues, "error", path, ticket_path, "티켓 설정이 필요합니다.")
            continue
        if not isinstance(ticket.get("display_name"), str) or not ticket["display_name"].strip():
            _issue(issues, "error", path, f"{ticket_path}.display_name", "티켓 표시 이름이 필요합니다.")
        for key in ("price", "purchase_min", "purchase_max"):
            value = ticket.get(key)
            if not isinstance(value, int) or isinstance(value, bool) or value < 1:
                _issue(issues, "error", path, f"{ticket_path}.{key}", "1 이상의 정수여야 합니다.")
        if isinstance(ticket.get("price"), int) and ticket["price"] > 2_147_483_647:
            _issue(issues, "error", path, f"{ticket_path}.price", "티켓 가격은 2,147,483,647칩 이하여야 합니다.")
        if isinstance(ticket.get("purchase_min"), int) and isinstance(ticket.get("purchase_max"), int) and ticket["purchase_max"] < ticket["purchase_min"]:
            _issue(issues, "error", path, f"{ticket_path}.purchase_max", "최대 구매 수량은 최소 구매 수량 이상이어야 합니다.")
    machines = document.get("machines")
    if not isinstance(machines, list):
        _issue(issues, "error", path, "$.machines", "기계 목록은 배열이어야 합니다.")
        return issues
    seen_machine_ids: set[str] = set()
    machine_types_by_id: dict[str, str] = {}
    for machine_index, machine in enumerate(machines):
        base = f"$.machines[{machine_index}]"
        if not isinstance(machine, dict):
            _issue(issues, "error", path, base, "기계 설정은 객체여야 합니다.")
            continue
        machine_id = machine.get("id")
        if not isinstance(machine_id, str) or RESOURCE_ID.fullmatch(machine_id) is None:
            _issue(issues, "error", path, f"{base}.id", "기계 ID는 namespace:path 형식이어야 합니다.")
        elif machine_id in seen_machine_ids:
            _issue(issues, "error", path, f"{base}.id", "중복된 기계 ID입니다.")
        else:
            seen_machine_ids.add(machine_id)
        if not isinstance(machine.get("display_name"), str) or not machine["display_name"].strip():
            _issue(issues, "error", path, f"{base}.display_name", "기계 표시 이름이 필요합니다.")
        machine_type = machine.get("machine_type")
        if machine_type not in {"pokemon", "item", "technical_machine"}:
            _issue(issues, "error", path, f"{base}.machine_type", "기계 종류는 pokemon, item, technical_machine 중 하나여야 합니다.")
        elif isinstance(machine_id, str) and RESOURCE_ID.fullmatch(machine_id) is not None:
            machine_types_by_id[machine_id] = machine_type
        appearance = machine.get("appearance")
        if not isinstance(appearance, dict):
            _issue(issues, "error", path, f"{base}.appearance", "외형 설정이 필요합니다.")
        else:
            model_block = appearance.get("model_block")
            if not isinstance(model_block, str) or RESOURCE_ID.fullmatch(model_block) is None:
                _issue(issues, "error", path, f"{base}.appearance.model_block", "올바른 가챠 블록 리소스 ID여야 합니다.")
            elif model_block not in {
                "cobblemoncasino:gacha_machine",
                "cobblemoncasino:pokemon_gacha_machine",
                "cobblemoncasino:event_gacha_machine",
                "cobblemoncasino:plushies_gacha_machine",
            }:
                _issue(issues, "error", path, f"{base}.appearance.model_block", "Cobblemon Casino 가챠 모델 중 하나를 선택해야 합니다.")
            if appearance.get("facing") not in {"north", "east", "south", "west"}:
                _issue(issues, "error", path, f"{base}.appearance.facing", "방향은 north, east, south, west 중 하나여야 합니다.")
        themes = machine.get("themes")
        if not isinstance(themes, list) or not themes:
            _issue(issues, "error", path, f"{base}.themes", "기계에는 하나 이상의 가챠 테마가 필요합니다.")
            continue
        theme_ids: set[str] = set()
        for theme_index, theme in enumerate(themes):
            theme_path = f"{base}.themes[{theme_index}]"
            theme_id = theme.get("id") if isinstance(theme, dict) else None
            if not isinstance(theme_id, str) or re.fullmatch(r"[a-z0-9_.-]+", theme_id) is None or theme_id in theme_ids:
                _issue(issues, "error", path, f"{theme_path}.id", "테마 ID는 기계 안에서 중복되지 않은 소문자 ID여야 합니다.")
            else:
                theme_ids.add(theme_id)
            _validate_gacha_theme(issues, path, theme_path, theme, machine_type)
    casino_sets = document.get("casino_sets")
    if not isinstance(casino_sets, list) or not casino_sets:
        _issue(issues, "error", path, "$.casino_sets", "하나 이상의 카지노 세트가 필요합니다.")
    else:
        seen_set_ids: set[str] = set()
        assigned_machines: set[str] = set()
        for set_index, casino_set in enumerate(casino_sets):
            set_path = f"$.casino_sets[{set_index}]"
            if not isinstance(casino_set, dict):
                _issue(issues, "error", path, set_path, "카지노 세트는 객체여야 합니다.")
                continue
            set_id = casino_set.get("id")
            if not isinstance(set_id, str) or RESOURCE_ID.fullmatch(set_id) is None or set_id in seen_set_ids:
                _issue(issues, "error", path, f"{set_path}.id", "카지노 세트 ID는 중복되지 않은 namespace:path 형식이어야 합니다.")
            else:
                seen_set_ids.add(set_id)
            if not isinstance(casino_set.get("display_name"), str) or not casino_set["display_name"].strip():
                _issue(issues, "error", path, f"{set_path}.display_name", "카지노 세트 표시 이름이 필요합니다.")
            set_machines = casino_set.get("machines")
            if not isinstance(set_machines, dict):
                _issue(issues, "error", path, f"{set_path}.machines", "포켓몬·아이템·기술머신 기계 참조가 필요합니다.")
                continue
            for machine_type in ticket_types:
                machine_id = set_machines.get(machine_type)
                ref_path = f"{set_path}.machines.{machine_type}"
                if machine_types_by_id.get(machine_id) != machine_type:
                    _issue(issues, "error", path, ref_path, f"{machine_type} 종류의 존재하는 기계 ID를 지정해야 합니다.")
                elif machine_id in assigned_machines:
                    _issue(issues, "error", path, ref_path, "하나의 기계는 한 카지노 세트에만 속할 수 있습니다.")
                else:
                    assigned_machines.add(machine_id)
        for machine_id in seen_machine_ids - assigned_machines:
            _issue(issues, "error", path, "$.casino_sets", f"카지노 세트에 연결되지 않은 기계입니다: {machine_id}")
    return issues


def save_gacha_machine_catalog(root: Path, data: Any) -> list[Issue]:
    issues = validate_gacha_machine_catalog(root, data)
    if any(issue.level == "error" for issue in issues):
        return issues
    target = gacha_machine_catalog_path(root)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(target)
    return issues


GACHA_ITEM_GRAPHICS = {
    "coin_case": "coin_case",
    "gacha_ticket_pokemon": "gacha_ticket_pokemon",
    "gacha_ticket_item": "gacha_ticket_item",
    "gacha_ticket_technical_machine": "gacha_ticket_technical_machine",
}


def _gacha_item_asset_paths(core_root: Path, item: str) -> tuple[Path, Path]:
    texture_name = GACHA_ITEM_GRAPHICS.get(item)
    if texture_name is None:
        raise ValueError("지원하지 않는 카지노 아이템 그래픽입니다.")
    asset_root = (
        core_root / "projects" / "cobbleventure-casino" / "src" / "main"
        / "resources" / "assets" / "cobbleventure_casino"
    )
    return (
        asset_root / "textures" / "item" / f"{texture_name}.png",
        asset_root / "models" / "item" / f"{texture_name}.json",
    )


def gacha_item_graphics_payload(core_root: Path) -> dict[str, Any]:
    items = []
    for item in GACHA_ITEM_GRAPHICS:
        texture_path, model_path = _gacha_item_asset_paths(core_root, item)
        model_texture = f"cobbleventure_casino:item/{item}"
        try:
            model = load_json(model_path)
            model_texture = model.get("textures", {}).get("layer0", model_texture)
        except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError):
            pass
        width = height = 0
        if texture_path.is_file():
            try:
                data = texture_path.read_bytes()
                if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24:
                    width, height = struct.unpack(">II", data[16:24])
            except OSError:
                pass
        items.append({
            "id": item,
            "exists": texture_path.is_file(),
            "width": width,
            "height": height,
            "model_texture": model_texture,
            "preview_url": f"/api/gacha-item-texture?item={item}",
        })
    return {"items": items}


def save_gacha_item_graphic(core_root: Path, item: str, encoded: str) -> dict[str, Any]:
    if not isinstance(encoded, str) or not encoded:
        raise ValueError("저장할 PNG 데이터가 필요합니다.")
    try:
        data = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError, TypeError) as error:
        raise ValueError("PNG 데이터를 읽을 수 없습니다.") from error
    if len(data) > 2 * 1024 * 1024 or not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) < 24:
        raise ValueError("2MB 이하의 올바른 PNG 파일만 사용할 수 있습니다.")
    width, height = struct.unpack(">II", data[16:24])
    if width != height or width < 16 or width > 512:
        raise ValueError("아이템 그래픽은 16~512px 정사각형 PNG여야 합니다.")
    texture_path, model_path = _gacha_item_asset_paths(core_root, item)
    texture_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = texture_path.with_suffix(".png.tmp")
    temporary.write_bytes(data)
    temporary.replace(texture_path)
    model = {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"cobbleventure_casino:item/{item}"},
    }
    temporary_model = model_path.with_suffix(".json.tmp")
    temporary_model.write_text(
        json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    temporary_model.replace(model_path)
    return {"saved": True, "item": item, "width": width, "height": height}


CASINO_CONFIG_RELATIVE_ROOT = Path(
    "pack/overrides/development-placeholder/config/cobblemoncasino"
)
CASINO_CONFIG_FILES: dict[str, dict[str, Any]] = {
    "general_config.json": {
        "label": "일반 · 칩 가치",
        "description": "칩별 금액과 제작·파괴·주민 변환 규칙을 설정합니다.",
        "default": {
            "money_chip_values": {
                "red_chip": 1, "blue_chip": 5, "yellow_chip": 10,
                "purple_chip": 50, "copper_chip": 100, "iron_chip": 500,
                "emerald_chip": 1000, "gold_chip": 5000,
                "diamond_chip": 10000, "netherite_chip": 50000,
                "black_chip": 100000, "white_chip": 500000,
                "rainbow_chip": 1000000,
            },
            "enableMachinesCrafting": False,
            "enableGachaCurrencyCrafting": False,
            "makeMachinesUnbreakable": True,
            "enableChipTableCasinoVillagerConversion": False,
        },
    },
    "machines/slot_machine.json": {
        "label": "슬롯머신",
        "description": "베팅 금액, 라인 배수와 세 릴의 심볼 분포를 설정합니다.",
        "default": {
            "debug": False,
            "bet_amounts": [1, 5, 10, 50, 100, 500, 1000, 5000, 10000, 50000, 100000, 500000, 1000000],
            "bet_multipliers": {"mode1": 1, "mode2": 3, "mode3": 5},
            "reels": {
                "reelSize": 256,
                **{
                    f"reel{index}": {
                        "counts": {"SEVEN": 6, "ROCKET": 12, "MEW": 18, "PIKACHU": 24, "CHARMANDER": 38, "SQUIRTLE": 44, "BULBASAUR": 50, "CHERRY": 64},
                        "fillSymbol": "HAUNTER",
                    }
                    for index in range(1, 4)
                },
            },
        },
    },
    "machines/blackjack_table.json": {
        "label": "블랙잭",
        "description": "블랙잭 테이블에서 선택할 수 있는 베팅 금액을 설정합니다.",
        "default": {"bet_amounts": [1, 5, 10, 50, 100, 500, 1000, 5000, 10000, 50000, 100000, 500000, 1000000]},
    },
    "machines/chip_table.json": {
        "label": "칩 교환대",
        "description": "화폐 교환 방향과 유물 주화 묶음별 가치를 설정합니다.",
        "default": {
            "enable_currency_to_chips": False,
            "enable_chips_to_relic_coins": False,
            "enable_chips_to_cobbledollars": False,
            "enable_cobbledollars_to_chips": False,
            "relic_coin_value": 10,
            "handful_of_relic_coins_value": 40,
            "relic_coin_pouch_value": 90,
            "relic_coin_sack_value": 810,
            "stack_of_relic_coins_value": 160,
        },
    },
    "machines/gacha_machines.json": {
        "label": "가챠 확률",
        "description": "희귀도 가중치, 주화별 배수, 천장과 프리미어 보너스를 설정합니다.",
        "default": {
            "rarity_base_weights": {"common": 60, "uncommon": 25, "rare": 10, "ultrarare": 4, "legendary": 1},
            "coin_multipliers": {
                "copper": {"common": 1.0, "uncommon": 1.0, "rare": 1.0, "ultrarare": 1.0, "legendary": 0.1},
                "iron": {"common": 0.85, "uncommon": 1.1, "rare": 1.4, "ultrarare": 1.6, "legendary": 0.5},
                "gold": {"common": 0.55, "uncommon": 1.2, "rare": 2.1, "ultrarare": 2.5, "legendary": 0.95},
                "diamond": {"common": 0.32, "uncommon": 1.1, "rare": 3.0, "ultrarare": 3.2, "legendary": 2.3},
            },
            "pity": {
                "enable": True, "pityUpdateMessages": True,
                "iron": {"usesToMax": 0, "maxLegendaryChance": 0.0},
                "gold": {"usesToMax": 80, "maxLegendaryChance": 0.25},
                "diamond": {"usesToMax": 25, "maxLegendaryChance": 0.25},
            },
            "premier_bonus": {"enable": True, "coinsToBonus": 10},
        },
    },
    "gachapon/item_gachapon.json": {
        "label": "아이템 가챠 보상",
        "description": "희귀도 풀별 아이템, 수량과 당첨 가중치를 편집합니다.",
        "default": {"pools": {}},
    },
    "gachapon/pokemon_gachapon.json": {
        "label": "포켓몬 가챠 보상",
        "description": "희귀도 풀별 포켓몬, 레벨, IV, 이로치 규칙과 당첨 가중치를 편집합니다.",
        "default": {"pools": {}},
    },
    "gachapon/plushies_gachapon.json": {
        "label": "인형 가챠 보상",
        "description": "Pokeblocks 인형 itemId와 weight 목록을 설정합니다.",
        "default": {"plushies": []},
    },
    "npc/exchanger.json": {
        "label": "환전상 거래",
        "description": "buy_item·buy_count를 sell_item·sell_count로 교환하는 거래를 설정합니다.",
        "default": {"trades": []},
    },
    "npc/prize_dealer.json": {
        "label": "경품상 거래",
        "description": "카지노 화폐로 구매할 경품 거래를 설정합니다.",
        "default": {"trades": []},
    },
    "npc/cobbledollars_dealer.json": {
        "label": "CobbleDollars 상점",
        "description": "카테고리별 item, price, buyback_price 상품을 설정합니다.",
        "default": {"categories": []},
    },
}

CASINO_PRODUCT_DEFAULTS_PATH = Path(__file__).with_name("casino_config_defaults.json")
with CASINO_PRODUCT_DEFAULTS_PATH.open(encoding="utf-8") as casino_defaults_file:
    for casino_path, casino_default in json.load(casino_defaults_file).items():
        CASINO_CONFIG_FILES[casino_path]["default"] = casino_default


def casino_config_root(core_root: Path) -> Path:
    return core_root / CASINO_CONFIG_RELATIVE_ROOT


def casino_config_payload(core_root: Path) -> dict[str, Any]:
    config_root = casino_config_root(core_root)
    files = []
    for relative_path, metadata in CASINO_CONFIG_FILES.items():
        target = config_root / Path(relative_path)
        exists = target.is_file()
        files.append({
            "path": relative_path,
            "label": metadata["label"],
            "description": metadata["description"],
            "exists": exists,
            "document": load_json(target) if exists else copy.deepcopy(metadata["default"]),
            "default": copy.deepcopy(metadata["default"]),
        })
    return {
        "mod": "Cobblemon Casino",
        "version": "2.0.0",
        "config_root": CASINO_CONFIG_RELATIVE_ROOT.as_posix(),
        "files": files,
    }


def validate_casino_config(relative_path: str, data: Any, core_root: Path) -> list[Issue]:
    metadata = CASINO_CONFIG_FILES.get(relative_path)
    path = casino_config_root(core_root) / Path(relative_path)
    issues: list[Issue] = []
    if metadata is None:
        _issue(issues, "error", path, "$", "지원하는 Cobblemon Casino 설정 파일이 아닙니다.")
        return issues
    document = _require_object(data, issues, path, "$")
    if document is None:
        return issues

    def positive_number_list(key: str) -> None:
        values = document.get(key)
        if not isinstance(values, list) or not values:
            _issue(issues, "error", path, f"$.{key}", "하나 이상의 양수 금액이 필요합니다.")
            return
        for index, value in enumerate(values):
            if not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0:
                _issue(issues, "error", path, f"$.{key}[{index}]", "0보다 큰 숫자여야 합니다.")

    if relative_path in {"machines/slot_machine.json", "machines/blackjack_table.json"}:
        positive_number_list("bet_amounts")
    if relative_path == "machines/slot_machine.json":
        reels = document.get("reels")
        if not isinstance(reels, dict) or not isinstance(reels.get("reelSize"), int) or not 16 <= reels["reelSize"] <= 4096:
            _issue(issues, "error", path, "$.reels.reelSize", "릴 크기는 16부터 4096까지의 정수여야 합니다.")
    if relative_path == "machines/gacha_machines.json":
        weights = document.get("rarity_base_weights")
        if not isinstance(weights, dict) or not any(isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0 for value in weights.values()):
            _issue(issues, "error", path, "$.rarity_base_weights", "희귀도 가중치 중 하나 이상은 0보다 커야 합니다.")
    if relative_path.startswith("gachapon/"):
        collection_key = "plushies" if relative_path.endswith("plushies_gachapon.json") else "pools"
        expected = list if collection_key == "plushies" else dict
        if not isinstance(document.get(collection_key), expected):
            _issue(issues, "error", path, f"$.{collection_key}", "보상 목록의 JSON 구조가 올바르지 않습니다.")
        elif collection_key == "plushies":
            for index, entry in enumerate(document[collection_key]):
                if not isinstance(entry, dict) or not isinstance(entry.get("itemId"), str) or RESOURCE_ID.fullmatch(entry["itemId"]) is None:
                    _issue(issues, "error", path, f"$.plushies[{index}].itemId", "올바른 아이템 리소스 ID여야 합니다.")
                if not isinstance(entry, dict) or not isinstance(entry.get("weight"), int) or isinstance(entry.get("weight"), bool) or entry["weight"] <= 0:
                    _issue(issues, "error", path, f"$.plushies[{index}].weight", "가중치는 0보다 큰 정수여야 합니다.")
        else:
            pokemon_pool = relative_path.endswith("pokemon_gachapon.json")
            for rarity, entries in document[collection_key].items():
                entry_path = f"$.pools.{rarity}"
                if not isinstance(rarity, str) or not rarity or not isinstance(entries, list):
                    _issue(issues, "error", path, entry_path, "희귀도 풀은 이름과 보상 배열이 필요합니다.")
                    continue
                for index, entry in enumerate(entries):
                    item_path = f"{entry_path}[{index}]"
                    if not isinstance(entry, dict):
                        _issue(issues, "error", path, item_path, "보상은 객체여야 합니다.")
                        continue
                    id_key = "pokemonId" if pokemon_pool else "itemId"
                    identifier = entry.get(id_key)
                    if not isinstance(identifier, str) or not identifier or (not pokemon_pool and RESOURCE_ID.fullmatch(identifier) is None):
                        _issue(issues, "error", path, f"{item_path}.{id_key}", "올바른 보상 ID가 필요합니다.")
                    for number_key in (("level", "ivs", "weight") if pokemon_pool else ("count", "weight")):
                        value = entry.get(number_key)
                        minimum, maximum = ((1, 100) if number_key == "level" else (0, 31) if number_key == "ivs" else (1, None))
                        if not isinstance(value, int) or isinstance(value, bool) or value < minimum or (maximum is not None and value > maximum):
                            _issue(issues, "error", path, f"{item_path}.{number_key}", f"{minimum} 이상의 정수여야 합니다." if maximum is None else f"{minimum}부터 {maximum}까지의 정수여야 합니다.")
                    if pokemon_pool and entry.get("shiny") not in {"default", "boosted", "yes"}:
                        _issue(issues, "error", path, f"{item_path}.shiny", "이로치 규칙은 default, boosted 또는 yes여야 합니다.")
    if relative_path.startswith("npc/"):
        collection_key = "categories" if relative_path.endswith("cobbledollars_dealer.json") else "trades"
        if not isinstance(document.get(collection_key), list):
            _issue(issues, "error", path, f"$.{collection_key}", "거래 목록은 배열이어야 합니다.")
        elif collection_key == "trades":
            for index, trade in enumerate(document[collection_key]):
                if not isinstance(trade, dict):
                    _issue(issues, "error", path, f"$.trades[{index}]", "거래는 객체여야 합니다.")
                    continue
                for item_key in ("buy_item", "sell_item"):
                    if not isinstance(trade.get(item_key), str) or RESOURCE_ID.fullmatch(trade[item_key]) is None:
                        _issue(issues, "error", path, f"$.trades[{index}].{item_key}", "올바른 아이템 리소스 ID여야 합니다.")
                for count_key in ("buy_count", "sell_count"):
                    if not isinstance(trade.get(count_key), int) or isinstance(trade[count_key], bool) or trade[count_key] <= 0:
                        _issue(issues, "error", path, f"$.trades[{index}].{count_key}", "수량은 0보다 큰 정수여야 합니다.")
        else:
            for category_index, category in enumerate(document[collection_key]):
                category_path = f"$.categories[{category_index}]"
                if not isinstance(category, dict) or not isinstance(category.get("name"), str) or not category["name"].strip() or not isinstance(category.get("offers"), list):
                    _issue(issues, "error", path, category_path, "카테고리 이름과 상품 배열이 필요합니다.")
                    continue
                for offer_index, offer in enumerate(category["offers"]):
                    offer_path = f"{category_path}.offers[{offer_index}]"
                    if not isinstance(offer, dict) or not isinstance(offer.get("item"), str) or RESOURCE_ID.fullmatch(offer["item"]) is None:
                        _issue(issues, "error", path, f"{offer_path}.item", "올바른 아이템 리소스 ID여야 합니다.")
                    for price_key in ("price", "buyback_price"):
                        if not isinstance(offer, dict) or not isinstance(offer.get(price_key), int) or isinstance(offer.get(price_key), bool) or offer[price_key] < (-1 if price_key == "buyback_price" else 0):
                            _issue(issues, "error", path, f"{offer_path}.{price_key}", "가격은 0 이상의 정수여야 합니다." if price_key == "price" else "매입가는 -1 이상의 정수여야 합니다.")
    return issues


def save_casino_config(core_root: Path, relative_path: str, data: Any) -> list[Issue]:
    issues = validate_casino_config(relative_path, data, core_root)
    if any(issue.level == "error" for issue in issues):
        return issues
    target = casino_config_root(core_root) / Path(relative_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(target)
    return issues


def validate_dialogue_theme(root: Path, data: Any) -> list[Issue]:
    path = root / "content" / "catalogs" / "dialogue-theme.json"
    issues: list[Issue] = []
    document = _require_object(data, issues, path, "$")
    if document is None:
        return issues
    if document.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "대화 테마 버전은 1이어야 합니다.")

    def section(name: str) -> dict[str, Any] | None:
        return _require_object(document.get(name), issues, path, f"$.{name}")

    def number(obj: dict[str, Any], section_name: str, key: str, minimum: float, maximum: float) -> None:
        value = obj.get(key)
        if not isinstance(value, (int, float)) or isinstance(value, bool) or not minimum <= value <= maximum:
            _issue(issues, "error", path, f"$.{section_name}.{key}", f"{minimum:g} 이상 {maximum:g} 이하의 숫자여야 합니다.")

    def color(obj: dict[str, Any], section_name: str, key: str) -> None:
        value = obj.get(key)
        if not isinstance(value, str) or re.fullmatch(r"#[0-9a-fA-F]{6}", value) is None:
            _issue(issues, "error", path, f"$.{section_name}.{key}", "#RRGGBB 색상이어야 합니다.")

    font = section("font")
    if font is not None:
        if not isinstance(font.get("resource"), str) or RESOURCE_ID.fullmatch(font["resource"]) is None:
            _issue(issues, "error", path, "$.font.resource", "올바른 Minecraft 폰트 리소스 ID여야 합니다.")
        for key in ("body_scale", "speaker_scale", "hint_scale"):
            number(font, "font", key, 0.5, 2.0)
    panel = section("panel")
    if panel is not None:
        for key in ("background", "border", "inner_border", "shadow", "speaker_color", "text_color", "hint_color", "page_color"):
            color(panel, "panel", key)
        number(panel, "panel", "background_opacity", 0, 1)
        number(panel, "panel", "border_width", 1, 8)
        number(panel, "panel", "inner_border_width", 0, 8)
        number(panel, "panel", "corner_radius", 0, 32)
        number(panel, "panel", "shadow_opacity", 0, 1)
        number(panel, "panel", "shadow_offset", 0, 12)
        number(panel, "panel", "height_ratio", 0.2, 0.7)
        number(panel, "panel", "min_height", 80, 300)
        number(panel, "panel", "max_height", 100, 400)
        if isinstance(panel.get("min_height"), (int, float)) and isinstance(panel.get("max_height"), (int, float)) and panel["min_height"] > panel["max_height"]:
            _issue(issues, "error", path, "$.panel.max_height", "최대 높이는 최소 높이보다 작을 수 없습니다.")
    choice = section("choice")
    if choice is not None:
        for key in ("panel_background", "panel_border", "panel_inner_border", "selected_background", "hover_background", "background", "selected_accent", "text_color"):
            color(choice, "choice", key)
        number(choice, "choice", "panel_opacity", 0, 1)
        number(choice, "choice", "corner_radius", 0, 28)
        number(choice, "choice", "panel_width", 100, 360)
        number(choice, "choice", "panel_gap", 0, 32)
        number(choice, "choice", "panel_padding", 4, 24)
        number(choice, "choice", "row_height", 18, 48)
    menu = section("menu")
    if menu is not None:
        for key in ("background", "border", "inner_border", "selected_background", "hover_background", "text_color", "selected_text_color", "accent"):
            color(menu, "menu", key)
        number(menu, "menu", "background_opacity", 0, 1)
        number(menu, "menu", "corner_radius", 0, 32)
        number(menu, "menu", "row_radius", 0, 20)
    portrait = section("portrait")
    if portrait is not None:
        for key in ("background", "accent"):
            color(portrait, "portrait", key)
        number(portrait, "portrait", "background_opacity", 0, 1)
        number(portrait, "portrait", "yaw_degrees", -35, 35)
        number(portrait, "portrait", "pitch_degrees", -20, 20)
        number(portrait, "portrait", "scale", 0.6, 1.5)
    return issues


def validate_dialogue_theme_file(path: Path) -> list[Issue]:
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        issues: list[Issue] = []
        _issue(issues, "error", path, "$", f"대화 테마를 읽을 수 없습니다: {error}")
        return issues
    return validate_dialogue_theme(path.parent.parent.parent, data)


def save_dialogue_theme(root: Path, data: Any) -> list[Issue]:
    target = root / "content" / "catalogs" / "dialogue-theme.json"
    issues = validate_dialogue_theme(root, data)
    if not any(issue.level == "error" for issue in issues):
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    return issues


MUSIC_CONTEXTS = (
    "tile", "road", "settlement", "cave", "forest", "building", "pokemon_center", "pokemart",
    "trainer_encounter_boy", "trainer_encounter_girl", "trainer_encounter_bad_guys",
    "item_acquired", "key_item_acquired", "machine_acquired",
    "battle", "gym", "victory_wild", "victory_trainer", "victory_gym",
)


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


DIMENSION_ANCHOR_ID = re.compile(r"^[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*$")


def validate_dimension_anchor_catalog_file(path: Path) -> list[Issue]:
    """Validate the authoritative, dimension-global CVES arrival registry."""
    issues: list[Issue] = []
    if not path.is_file():
        return issues
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"차원 앵커 카탈로그를 읽을 수 없습니다: {error}")
        return issues
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "차원 앵커 카탈로그 스키마 버전은 1입니다.")
        return issues
    dimensions = _require_list(data.get("dimensions"), issues, path, "$.dimensions") or []
    seen_dimensions: set[str] = set()
    for index, value in enumerate(dimensions):
        base = f"$.dimensions[{index}]"
        entry = _require_object(value, issues, path, base)
        if entry is None:
            continue
        dimension_id = entry.get("id")
        if not isinstance(dimension_id, str) or not RESOURCE_ID.fullmatch(dimension_id):
            _issue(issues, "error", path, f"{base}.id", "올바른 차원 리소스 ID가 아닙니다.")
        elif dimension_id in seen_dimensions:
            _issue(issues, "error", path, f"{base}.id", f"중복 차원 ID: {dimension_id}")
        else:
            seen_dimensions.add(dimension_id)
        anchors = _require_object(entry.get("anchors"), issues, path, f"{base}.anchors")
        if anchors is None:
            continue
        for anchor_id, anchor_value in anchors.items():
            anchor_path = f"{base}.anchors.{anchor_id}"
            if not DIMENSION_ANCHOR_ID.fullmatch(anchor_id):
                _issue(issues, "error", path, anchor_path, "올바른 차원 앵커 ID가 아닙니다.")
            anchor = _require_object(anchor_value, issues, path, anchor_path)
            if anchor is None:
                continue
            for coordinate in ("x", "y", "z"):
                coordinate_value = anchor.get(coordinate)
                if isinstance(coordinate_value, bool) or not isinstance(coordinate_value, int):
                    _issue(issues, "error", path, f"{anchor_path}.{coordinate}", "정수 좌표가 필요합니다.")
            for angle, minimum, maximum in (("yaw", -180, 180), ("pitch", -90, 90)):
                if angle not in anchor:
                    continue
                angle_value = anchor[angle]
                if (
                    isinstance(angle_value, bool)
                    or not isinstance(angle_value, (int, float))
                    or not math.isfinite(angle_value)
                    or not minimum <= angle_value <= maximum
                ):
                    _issue(
                        issues, "error", path, f"{anchor_path}.{angle}",
                        f"{minimum}..{maximum} 범위의 각도가 필요합니다."
                    )
    return issues


def validate_event_boundary_catalog_file(path: Path) -> list[Issue]:
    """Validate explicit indexed boxes used by region and anchor CVES triggers."""
    issues: list[Issue] = []
    if not path.is_file():
        return issues
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"이벤트 경계 카탈로그를 읽을 수 없습니다: {error}")
        return issues
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "이벤트 경계 카탈로그 스키마 버전은 1입니다.")
        return issues
    for collection in ("regions", "anchors"):
        values = _require_list(data.get(collection), issues, path, f"$.{collection}") or []
        seen: set[str] = set()
        for index, value in enumerate(values):
            base = f"$.{collection}[{index}]"
            entry = _require_object(value, issues, path, base)
            if entry is None:
                continue
            boundary_id = entry.get("id")
            if not isinstance(boundary_id, str) or not RESOURCE_ID.fullmatch(boundary_id):
                _issue(issues, "error", path, f"{base}.id", "올바른 이벤트 경계 리소스 ID가 아닙니다.")
            elif boundary_id in seen:
                _issue(issues, "error", path, f"{base}.id", f"중복 이벤트 경계 ID: {boundary_id}")
            else:
                seen.add(boundary_id)
            dimension = entry.get("dimension")
            if not isinstance(dimension, str) or not RESOURCE_ID.fullmatch(dimension):
                _issue(issues, "error", path, f"{base}.dimension", "올바른 차원 리소스 ID가 아닙니다.")
            box = _require_object(entry.get("box"), issues, path, f"{base}.box")
            if box is None:
                continue
            coordinates: dict[str, int] = {}
            for coordinate in ("min_x", "min_y", "min_z", "max_x", "max_y", "max_z"):
                coordinate_value = box.get(coordinate)
                if isinstance(coordinate_value, bool) or not isinstance(coordinate_value, int):
                    _issue(issues, "error", path, f"{base}.box.{coordinate}", "정수 좌표가 필요합니다.")
                else:
                    coordinates[coordinate] = coordinate_value
            for axis in ("x", "y", "z"):
                minimum = coordinates.get(f"min_{axis}")
                maximum = coordinates.get(f"max_{axis}")
                if minimum is not None and maximum is not None and minimum > maximum:
                    _issue(
                        issues, "error", path, f"{base}.box",
                        f"min_{axis}는 max_{axis} 이하여야 합니다."
                    )
    return issues


def save_music_catalog(root: Path, data: Any) -> list[Issue]:
    with MUSIC_CATALOG_LOCK:
        return _save_music_catalog_unlocked(root, data)


def _save_music_catalog_unlocked(root: Path, data: Any) -> list[Issue]:
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


def sync_local_music_catalog(project_root: Path, core_root: Path) -> tuple[dict[str, Any], int]:
    with MUSIC_CATALOG_LOCK:
        return _sync_local_music_catalog_unlocked(project_root, core_root)


def _sync_local_music_catalog_unlocked(
    project_root: Path, core_root: Path
) -> tuple[dict[str, Any], int]:
    catalog_path = project_root / "content" / "catalogs" / "music-tracks.json"
    catalog = load_json(catalog_path)
    source = _music_source_directory(project_root, core_root, catalog)
    source.mkdir(parents=True, exist_ok=True)
    tracks = catalog.setdefault("tracks", [])
    ogg_paths = sorted(
        (
            path for path in source.rglob("*")
            if path.is_file() and path.suffix.lower() == ".ogg"
        ),
        key=lambda value: value.as_posix().casefold(),
    )
    ogg_paths_by_name: dict[str, list[Path]] = {}
    for path in ogg_paths:
        ogg_paths_by_name.setdefault(path.name.casefold(), []).append(path.resolve())
    migrated = 0
    for track in tracks:
        if not isinstance(track, dict) or not isinstance(track.get("source_file"), str):
            continue
        source_file = Path(track["source_file"])
        source_directory = track.get("source_directory")
        candidate: Path | None = None
        if isinstance(source_directory, str):
            candidate = (core_root / source_directory / source_file).resolve()
        else:
            direct_candidate = (source / source_file).resolve()
            if direct_candidate.is_file():
                candidate = direct_candidate
            else:
                matches = ogg_paths_by_name.get(source_file.name.casefold(), [])
                if len(matches) == 1:
                    candidate = matches[0]
        if candidate is None or not candidate.is_file():
            continue
        try:
            relative = candidate.relative_to(source).as_posix()
        except ValueError:
            continue
        if track["source_file"] != relative or "source_directory" in track:
            track["source_file"] = relative
            track.pop("source_directory", None)
            migrated += 1
    # Folder contents are only candidates. A stable tag is created explicitly in
    # the editor when a track is assigned a purpose; scanning must never create
    # public IDs for every local file.
    available_sources = {
        path.relative_to(source).as_posix().casefold() for path in ogg_paths
    }
    missing_tracks = sum(
        1 for track in tracks
        if isinstance(track.get("source_file"), str)
        and track["source_file"].replace("\\", "/").casefold() not in available_sources
    )
    mapped_sources = {
        track["source_file"].replace("\\", "/").casefold()
        for track in tracks
        if isinstance(track, dict) and isinstance(track.get("source_file"), str)
    }
    if migrated:
        temporary = catalog_path.with_suffix(".json.tmp")
        temporary.write_text(
            json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        temporary.replace(catalog_path)
    catalog["local_library"] = {
        "directory": str(source),
        "registered_ogg": len(ogg_paths),
        "registered_tracks": len(tracks),
        "available_tracks": len(tracks) - missing_tracks,
        "missing_tracks": missing_tracks,
        "unmapped_ogg": sum(
            1 for source_file in available_sources if source_file not in mapped_sources
        ),
        "files": [path.relative_to(source).as_posix() for path in ogg_paths],
        "added": 0,
        "removed": 0,
        "migrated": migrated,
    }
    return catalog, 0


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
                    message = (
                        f"음원 경로를 직접 참조할 수 없습니다. 음원 태그를 사용하세요: {child}"
                        if isinstance(child, str) and ("/" in child or "\\" in child or child.lower().endswith(".ogg"))
                        else f"등록되지 않은 음원 태그입니다: {child}"
                    )
                    _issue(
                        issues, "error", path, child_path,
                        message,
                    )
                inspect(child, path, child_path)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                inspect(child, path, f"{json_path}[{index}]")

    content_root = root / "content"
    candidates = [
        path for path in sorted(content_root.rglob("*.json"))
        if "schemas" not in path.parts and path != catalog_path
    ] if content_root.is_dir() else []
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
        root / "projects" / "cobbleventure-player-menu" / "src" / "main" / "resources" / "assets" / "cobbleventure_player_menu" / "lang",
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


def _economy_fallback_product_group(item_id: str, tags: set[str] | list[str] | None = None) -> str:
    namespace, _, path = item_id.partition(":")
    tags = set(tags or ())
    path_words = set(path.replace("/", "_").split("_"))
    if namespace in {"handcrafted", "pokeblocks"} or any(token in path for token in (
        "pokedoll", "cushion", "chair", "table", "desk", "painting", "flower_pot", "lantern", "bookshelf",
    )):
        return "decor"
    if namespace == "cobblenav" or any(token in path for token in (
        "pokedex", "pokenav", "pokefinder", "fishingnav",
    )):
        return "technology"
    if namespace == "tmcraft" or path.startswith(("tm_", "tr_")) or any("technical_machine" in tag or "tm_moves" in tag for tag in tags):
        return "machines"
    if path.startswith("relic_coin") or item_id == "minecraft:emerald":
        return "currency"
    if path.endswith("_ball") or "poke_ball" in path:
        return "balls"
    if path_words.intersection({"potion", "heal", "revive", "ether", "elixir", "remedy"}) or path == "ominous_bottle":
        return "medicine"
    if path_words.intersection({"candy", "mochi", "mint", "feather"}):
        return "medicine"
    if path.startswith("x_") or path in {"dire_hit", "guard_spec"}:
        return "battle"
    if path.endswith("_berry"):
        return "berries"
    food_paths = {
        "apple", "baked_potato", "beef", "beetroot", "beetroot_soup", "bread", "carrot",
        "chicken", "chorus_fruit", "cod", "cooked_beef", "cooked_chicken", "cooked_cod",
        "cooked_mutton", "cooked_porkchop", "cooked_rabbit", "cooked_salmon", "cookie",
        "dried_kelp", "egg", "golden_apple", "golden_carrot", "honey_bottle", "melon_slice",
        "milk_bucket", "mushroom_stew", "mutton", "poisonous_potato", "porkchop", "potato",
        "pufferfish", "pumpkin_pie", "rabbit", "rabbit_stew", "salmon", "spider_eye",
        "suspicious_stew", "sweet_berries", "tropical_fish",
    }
    if path in food_paths or path_words.intersection({"juice", "milk", "stew", "tea", "dip", "puff"}):
        return "food"
    material_items = {
        "minecraft:blaze_powder", "minecraft:fermented_spider_eye", "minecraft:magma_cream",
        "minecraft:phantom_membrane", "minecraft:ghast_tear", "minecraft:nether_wart",
    }
    if item_id in material_items or path_words.intersection({
        "apricorn", "tumblestone", "fossil", "mulch", "seed", "sprout", "ore", "ingot",
        "nugget", "dust", "shard", "plank", "log", "dye",
    }):
        return "materials"
    return "other"


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
        "battle": "cobbleventure:shop/battle_items",
        "evolution": "cobbleventure:shop/evolution_items",
        "held": "cobbleventure:shop/held_items",
        "medicine": "cobbleventure:shop/medicine",
        "berries": "cobbleventure:shop/berries",
        "food": "cobbleventure:shop/food",
        "materials": "cobbleventure:shop/materials",
    }
    resource_item_ids: set[str] = set()
    resource_namespaces: set[str] = set()
    resource_roots = [
        root / ".tmp" / "cobblemon-1.7.3-source" / "common" / "src" / "main" / "resources" / "assets",
        root / ".tmp" / "cobblemon-1.7.3-full" / "cobblemon-1.7.3" / "common" / "src" / "main" / "resources" / "assets",
        root / "projects" / "cobbleventure-player-menu" / "src" / "main" / "resources" / "assets",
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
        if product_group == "other":
            product_group = _economy_fallback_product_group(item_id, tags)
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
    editor_catalog = _economy_editor_catalog(core_root or root, base_drops)
    editor_items_by_id = {entry["id"]: entry for entry in editor_catalog["items"]}
    for vendor in vendor_by_id.values():
        for category in vendor.get("categories", []):
            for offer in category.get("offers", []):
                item_id = offer.get("item")
                if not isinstance(item_id, str) or item_id in editor_items_by_id:
                    continue
                fallback_name = item_id.partition(":")[2].replace("_", " ").replace("/", " ").title()
                editor_items_by_id[item_id] = {
                    "id": item_id,
                    "ko_kr": fallback_name,
                    "en_us": fallback_name,
                    "product_group": _economy_fallback_product_group(item_id),
                    "tags": [],
                }
    editor_catalog["items"] = sorted(
        editor_items_by_id.values(), key=lambda entry: (entry["ko_kr"], entry["id"])
    )
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


def validate_department_store_assignment_slots(root: Path) -> list[Issue]:
    """Warn when a department-store catalog targets an unauthored NPC anchor."""
    issues: list[Issue] = []
    economy_path = root / "content" / "catalogs" / "economy.json"
    settings_path = root / "content" / "catalogs" / "building-settings.json"
    try:
        economy = load_json(economy_path)
        settings = load_json(settings_path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError):
        return issues
    if not isinstance(economy, dict) or not isinstance(settings, dict):
        return issues
    defaults = settings.get("facility_defaults", {})
    buildings = settings.get("buildings", {})
    if not isinstance(defaults, dict) or not isinstance(buildings, dict):
        return issues
    exterior_id = defaults.get("department_store")
    building = buildings.get(exterior_id, {}) if isinstance(exterior_id, str) else {}
    interiors = building.get("interiors", []) if isinstance(building, dict) else []
    authored_slots: set[str] = set()
    for interior in interiors if isinstance(interiors, list) else []:
        if not isinstance(interior, dict):
            continue
        resource_id = interior.get("structure")
        if not isinstance(resource_id, str) or ":" not in resource_id:
            continue
        namespace, resource_path = resource_id.split(":", 1)
        if namespace != "cobbleventure":
            continue
        metadata_path = (
            root / "content" / "structures" / resource_path
        ).with_suffix(".structure.json")
        if not metadata_path.is_file():
            continue
        try:
            metadata = load_json(metadata_path)
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            continue
        anchors = metadata.get("anchors", []) if isinstance(metadata, dict) else []
        authored_slots.update(
            anchor.get("label")
            for anchor in anchors if isinstance(anchor, dict)
            and anchor.get("type") == "npc_position"
            and isinstance(anchor.get("label"), str)
        )
    if not authored_slots:
        return issues
    catalogs = economy.get("shop_catalogs", [])
    for catalog_index, catalog in enumerate(catalogs if isinstance(catalogs, list) else []):
        if not isinstance(catalog, dict) or catalog.get("facility_scope") != "department_store":
            continue
        assignments = catalog.get("assignments", [])
        for assignment_index, assignment in enumerate(
            assignments if isinstance(assignments, list) else []
        ):
            if not isinstance(assignment, dict):
                continue
            slot_id = assignment.get("slot_id")
            if isinstance(slot_id, str) and slot_id not in authored_slots:
                _issue(
                    issues, "warning", economy_path,
                    f"$.shop_catalogs[{catalog_index}].assignments[{assignment_index}].slot_id",
                    f"백화점 NBT에 없는 판매원 위치입니다: {slot_id} (배치 시 건너뜁니다.)",
                )
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
        access = gym.get("access")
        if access is not None:
            access = _require_object(access, issues, path, f"{gym_path}.access")
        if access is not None:
            if not isinstance(access.get("require_previous_gym"), bool):
                _issue(issues, "error", path, f"{gym_path}.access.require_previous_gym", "참/거짓 값이어야 합니다.")
            if access.get("condition_mode", "all") not in {"all", "any"}:
                _issue(issues, "error", path, f"{gym_path}.access.condition_mode", "조건 조합은 all 또는 any여야 합니다.")
            conditions = access.get("conditions", [])
            if not isinstance(conditions, list):
                _issue(issues, "error", path, f"{gym_path}.access.conditions", "체육관 문 조건 배열이 필요합니다.")
            else:
                for condition_index, condition in enumerate(conditions):
                    _validate_player_condition(
                        condition, issues, path,
                        f"{gym_path}.access.conditions[{condition_index}]",
                    )
            dialogue = access.get("locked_dialogue")
            if not isinstance(dialogue, list) or not dialogue or any(
                not isinstance(line, str) or not line.strip() for line in dialogue
            ):
                _issue(issues, "error", path, f"{gym_path}.access.locked_dialogue", "비어 있지 않은 잠금 메시지 배열이어야 합니다.")
            blocker = _require_object(
                access.get("blocking_npc"), issues, path,
                f"{gym_path}.access.blocking_npc",
            )
            if blocker is not None:
                enabled = blocker.get("enabled")
                if not isinstance(enabled, bool):
                    _issue(issues, "error", path, f"{gym_path}.access.blocking_npc.enabled", "참/거짓 값이어야 합니다.")
                profile = blocker.get("npc_profile")
                if enabled:
                    _resource_id(profile, issues, path, f"{gym_path}.access.blocking_npc.npc_profile")
                elif not isinstance(profile, str):
                    _issue(issues, "error", path, f"{gym_path}.access.blocking_npc.npc_profile", "NPC ID 문자열이 필요합니다.")
        exterior = _require_object(gym.get("exterior"), issues, path, f"{gym_path}.exterior")
        connection_anchors_by_space: dict[str, set[str]] = {}
        if exterior is not None:
            structure = _resource_id(exterior.get("structure"), issues, path, f"{gym_path}.exterior.structure")
            if structure and structure != "cobbleventure:gyms/base_gym":
                _issue(
                    issues, "error", path, f"{gym_path}.exterior.structure",
                    "모든 체육관 외관은 공용 cobbleventure:gyms/base_gym을 사용해야 합니다.",
                )
            if structure_root is not None and structure and structure.startswith("cobbleventure:"):
                relative = structure.split(":", 1)[1]
                exterior_file = structure_root / f"{relative}.nbt"
                if not exterior_file.is_file():
                    _issue(issues, "error", path, f"{gym_path}.exterior.structure", f"NBT를 찾을 수 없습니다: {structure}")
                else:
                    connection_anchors_by_space["exterior"] = {
                        anchor["label"] for anchor in _structure_named_anchors(
                            exterior_file, {"door"}
                        )
                    }
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
                                door_anchors: set[str] = set()
                                for anchor in anchors if isinstance(anchors, list) else []:
                                    if not isinstance(anchor, dict):
                                        continue
                                    label = anchor.get("label")
                                    if not isinstance(label, str) or not DOCUMENT_SLUG.fullmatch(label):
                                        continue
                                    if anchor.get("type") == "door":
                                        door_anchors.add(label)
                                        continue
                                    if anchor.get("type") != "npc_position":
                                        continue
                                    if label in npc_anchors:
                                        _issue(issues, "error", path, f"{module_path}.structure", f"중복 NPC 앵커 라벨: {label}")
                                    npc_anchors.add(label)
                                if module_id in module_ids:
                                    connection_anchors_by_space[module_id] = door_anchors
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
                    parts = value.split(":", 1) if isinstance(value, str) else []
                    if len(parts) != 2 or not all(DOCUMENT_SLUG.fullmatch(part) for part in parts):
                        _issue(issues, "error", path, f"{connection_path}.{endpoint}", "공간ID:문앵커 형식으로 지정해야 합니다.")
                        continue
                    space_id, anchor_label = parts
                    if space_id not in connection_anchors_by_space:
                        _issue(issues, "error", path, f"{connection_path}.{endpoint}", "존재하는 외부 또는 내부 공간을 지정해야 합니다.")
                    elif anchor_label not in connection_anchors_by_space[space_id]:
                        _issue(issues, "error", path, f"{connection_path}.{endpoint}", "해당 NBT에 저장된 실제 문 앵커를 지정해야 합니다.")
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
        "settlement_flags": [],
        "display_name": {"ko_kr": name.strip()},
        "theme": "normal",
        "exterior": {"structure": "cobbleventure:gyms/base_gym"},
        "access": {
            "require_previous_gym": False,
            "condition_mode": "all",
            "conditions": [],
            "locked_dialogue": ["문이 잠겨 있다."],
            "blocking_npc": {"enabled": False, "npc_profile": ""},
        },
        "interior": {"modules": [{"id": "main", "structure": interior_structure, "position": [0, 0, 0], "rotation": "none"}], "connections": []},
        "staff": {"leader": {"league_entry_id": "", "anchor": "leader"}, "trainers": []},
    }
    catalog.setdefault("gyms", []).append(gym)
    issues = save_gym_catalog(root, catalog)
    if any(issue.level == "error" for issue in issues):
        return None, issues
    return gym, issues


def gym_interior_modules_payload(root: Path) -> dict[str, Any]:
    root = resolve_content_project(root).root
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
                nbt_path, {"door"}
            ),
            "arrival_anchors": _structure_named_anchors(
                nbt_path, {"arrival", "interior_spawn", "exterior_spawn"}
            ),
            "leader_anchor": leader,
            "used_by": usage.get(resource, []),
        })
    return {"modules": modules}


def interior_spaces_payload(root: Path) -> dict[str, Any]:
    root = resolve_content_project(root).root
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
                nbt_path, {"door"}
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
    issues.extend(_validate_cves_project(root, dependency_root))
    issues.extend(validate_loot_tables(root, _cves_item_catalog(dependency_root)))
    issues.extend(validate_game_definitions_file(root / "content" / "catalogs" / "game-definitions.json"))
    dialogue_theme_path = root / "content" / "catalogs" / "dialogue-theme.json"
    if dialogue_theme_path.is_file():
        issues.extend(validate_dialogue_theme_file(dialogue_theme_path))
    starter_path = root / "content" / "catalogs" / "starter-settings.json"
    if starter_path.is_file():
        try:
            issues.extend(validate_starter_settings(root, load_json(starter_path)))
        except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
            _issue(issues, "error", starter_path, "$", f"스타팅 설정을 읽을 수 없습니다: {error}")
    gacha_path = gacha_machine_catalog_path(root)
    if gacha_path.is_file():
        try:
            issues.extend(validate_gacha_machine_catalog(root, load_json(gacha_path)))
        except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
            _issue(issues, "error", gacha_path, "$", f"가챠 기계 설정을 읽을 수 없습니다: {error}")
    issues.extend(validate_music_catalog_file(root / "content" / "catalogs" / "music-tracks.json"))
    issues.extend(validate_dimension_anchor_catalog_file(
        root / "content" / "catalogs" / "dimension-anchors.json"
    ))
    issues.extend(validate_event_boundary_catalog_file(
        root / "content" / "catalogs" / "event-boundaries.json"
    ))
    issues.extend(validate_music_references(root))
    issues.extend(validate_gym_catalog_file(root / "content" / "catalogs" / "gyms.json", root / "content" / "structures"))
    economy_source_items = {
        entry.get("item") for drop in _economy_pokemon_drops_from_cobblemon(root)
        for entry in drop.get("entries", []) if isinstance(entry, dict) and isinstance(entry.get("item"), str)
    }
    issues.extend(validate_economy_catalog_file(
        root / "content" / "catalogs" / "economy.json", economy_source_items
    ))
    issues.extend(validate_department_store_assignment_slots(root))
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
    issues.extend(validate_building_npc_positions(root))
    issues.extend(validate_town_indoor_npc_capacities(root))
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
                        compiled_content = materialize_event_document(content_data)
                        referenced_battles = {
                            command.get("battle")
                            for event in compiled_content.get("events", [])
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

    underground_documents: dict[str, dict[str, Any]] = {}
    underground_dir = root / "content" / "underground_roads"
    for path in sorted(underground_dir.rglob("*.json")) if underground_dir.is_dir() else []:
        try:
            underground_data = load_json(path)
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            underground_data = None
        underground_id, underground_issues = validate_underground_road_document(underground_data, path, root)
        issues.extend(underground_issues)
        if underground_id and isinstance(underground_data, dict):
            if underground_id in underground_documents:
                _issue(issues, "error", path, "$.id", f"다른 파일과 중복된 지하통로 ID: {underground_id}")
            underground_documents[underground_id] = underground_data

    route_ids: set[str] = set()
    route_dir = root / "content" / "routes"
    for path in sorted(route_dir.rglob("*.json")) if route_dir.is_dir() else []:
        route_id, route_issues = validate_route_file(path)
        issues.extend(route_issues)
        if route_id in route_ids:
            _issue(issues, "error", path, "$.id", f"다른 파일과 중복된 길 프리셋 ID: {route_id}")
        elif route_id:
            route_ids.add(route_id)
        try:
            route_data = load_json(path)
            for index, placement in enumerate(route_data.get("npc_placements", [])):
                npc_id = placement.get("npc") if isinstance(placement, dict) else None
                if isinstance(npc_id, str) and npc_id not in seen_content:
                    _issue(
                        issues, "error", path, f"$.npc_placements[{index}].npc",
                        f"존재하지 않는 NPC 프리셋: {npc_id}",
                    )
        except (OSError, json.JSONDecodeError, DuplicateKeyError, AttributeError):
            pass

    issues.extend(validate_hex_worlds(
        root, set(seen_settlements), cave_documents, route_ids=route_ids,
        underground_documents=underground_documents,
    ))

    forest_dir = root / "content" / "forests"
    seen_forests: set[str] = set()
    for path in sorted(forest_dir.rglob("*.json")) if forest_dir.is_dir() else []:
        forest_id, forest_issues = validate_forest_file(path)
        issues.extend(forest_issues)
        if forest_id in seen_forests:
            _issue(issues, "error", path, "$.id", f"다른 파일과 중복된 숲 ID: {forest_id}")
        elif forest_id:
            seen_forests.add(forest_id)

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


def _derived_aligned_bounds(
    extents: list[tuple[float, float, float, float]],
    *,
    padding: int,
    minimum_size: int,
    alignment: int = 16,
) -> dict[str, int]:
    if not extents:
        extents = [(0, 0, 0, 0)]
    min_x = math.floor((min(item[0] for item in extents) - padding) / alignment) * alignment
    min_z = math.floor((min(item[1] for item in extents) - padding) / alignment) * alignment
    max_x = math.ceil((max(item[2] for item in extents) + padding) / alignment) * alignment
    max_z = math.ceil((max(item[3] for item in extents) + padding) / alignment) * alignment

    def ensure_minimum(minimum: int, maximum: int) -> tuple[int, int]:
        missing = max(0, minimum_size - (maximum - minimum))
        before = math.ceil((missing / 2) / alignment) * alignment
        after = math.ceil((missing - before) / alignment) * alignment
        return minimum - before, maximum + after

    min_x, max_x = ensure_minimum(min_x, max_x)
    min_z, max_z = ensure_minimum(min_z, max_z)
    return {"min_x": min_x, "min_z": min_z, "max_x": max_x, "max_z": max_z}


def derive_cave_build_bounds(data: dict[str, Any]) -> dict[str, int]:
    dimension = data.get("dimension") if isinstance(data.get("dimension"), dict) else {}
    origin = dimension.get("origin") if isinstance(dimension.get("origin"), dict) else {}
    origin_x, origin_z = int(origin.get("x", 0)), int(origin.get("z", 0))
    extents: list[tuple[float, float, float, float]] = [(origin_x, origin_z, origin_x, origin_z)]
    for entrance in data.get("entrances", []) if isinstance(data.get("entrances"), list) else []:
        if not isinstance(entrance, dict):
            continue
        for field in ("destination_anchor", "fallback_anchor"):
            point = entrance.get(field)
            if isinstance(point, dict) and isinstance(point.get("x"), (int, float)) and isinstance(point.get("z"), (int, float)):
                x, z = float(point["x"]), float(point["z"])
                extents.append((x - 4, z - 4, x + 4, z + 4))
    generator = data.get("generator") if isinstance(data.get("generator"), dict) else {}
    manual = generator.get("manual_layout") if isinstance(generator.get("manual_layout"), dict) else {}
    for anchor in manual.get("anchors", []) if isinstance(manual.get("anchors"), list) else []:
        if not isinstance(anchor, dict) or not isinstance(anchor.get("position"), dict):
            continue
        point = anchor["position"]
        x, z = float(point.get("x", 0)), float(point.get("z", 0))
        radius_x, radius_z = max(1, float(anchor.get("radius_x", 12))), max(1, float(anchor.get("radius_z", 12)))
        extents.append((x - radius_x, z - radius_z, x + radius_x, z + radius_z))
    for site in data.get("embedded_sites", []) if isinstance(data.get("embedded_sites"), list) else []:
        point = site.get("position") if isinstance(site, dict) and site.get("placement") == "fixed" else None
        if isinstance(point, dict) and isinstance(point.get("x"), (int, float)) and isinstance(point.get("z"), (int, float)):
            x, z = float(point["x"]), float(point["z"])
            extents.append((x - 8, z - 8, x + 8, z + 8))
    tunnel = generator.get("tunnel_radius") if isinstance(generator.get("tunnel_radius"), dict) else {}
    padding = max(16, math.ceil(float(tunnel.get("max", 7)) * 2))
    minimum_size = 64 if manual.get("enabled") else max(128, int(generator.get("main_rooms", 7)) * 24)
    return _derived_aligned_bounds(extents, padding=padding, minimum_size=minimum_size)


def derive_forest_build_bounds(data: dict[str, Any]) -> dict[str, int]:
    dimension = data.get("dimension") if isinstance(data.get("dimension"), dict) else {}
    origin = dimension.get("origin") if isinstance(dimension.get("origin"), dict) else {}
    origin_x, origin_z = int(origin.get("x", 0)), int(origin.get("z", 0))
    extents: list[tuple[float, float, float, float]] = [(origin_x, origin_z, origin_x, origin_z)]
    for route in data.get("paths", []) if isinstance(data.get("paths"), list) else []:
        if not isinstance(route, dict):
            continue
        radius = max(1, float(route.get("width", 5)) / 2)
        for point in route.get("points", []) if isinstance(route.get("points"), list) else []:
            if isinstance(point, dict) and isinstance(point.get("x"), (int, float)) and isinstance(point.get("z"), (int, float)):
                x, z = float(point["x"]), float(point["z"])
                extents.append((x - radius, z - radius, x + radius, z + radius))
    for entrance in data.get("entrances", []) if isinstance(data.get("entrances"), list) else []:
        point = entrance.get("position") if isinstance(entrance, dict) else None
        if isinstance(point, dict) and isinstance(point.get("x"), (int, float)) and isinstance(point.get("z"), (int, float)):
            x, z = float(point["x"]), float(point["z"])
            extents.append((x - 8, z - 8, x + 8, z + 8))
    for site in data.get("embedded_sites", []) if isinstance(data.get("embedded_sites"), list) else []:
        point = site.get("position") if isinstance(site, dict) and site.get("placement") == "fixed" else None
        if isinstance(point, dict) and isinstance(point.get("x"), (int, float)) and isinstance(point.get("z"), (int, float)):
            x, z = float(point["x"]) - origin_x, float(point["z"]) - origin_z
            extents.append((x - 8, z - 8, x + 8, z + 8))
    generator = data.get("generator") if isinstance(data.get("generator"), dict) else {}
    cell = max(4, min(64, int(generator.get("cell_size", 16))))
    for tile in data.get("terrain_tiles", []) if isinstance(data.get("terrain_tiles"), list) else []:
        if isinstance(tile, dict) and isinstance(tile.get("x"), (int, float)) and isinstance(tile.get("z"), (int, float)):
            x, z = float(tile["x"]), float(tile["z"])
            extents.append((x - cell / 2, z - cell / 2, x + cell / 2, z + cell / 2))
    barrier = data.get("tree_barrier") if isinstance(data.get("tree_barrier"), dict) else {}
    padding = max(16, cell * 2, int(barrier.get("max_height", 16)))
    # Keep an automatically resized build area on the same tile-center lattice.
    # The 16-block build alignment is retained while every shift is also a whole tile.
    alignment = math.lcm(16, cell)
    return _derived_aligned_bounds(extents, padding=padding, minimum_size=max(64, cell * 4), alignment=alignment)


def synchronize_spatial_build_bounds(data: Any, category: str) -> Any:
    if not isinstance(data, dict) or category not in {"caves", "forests"}:
        return data
    dimension = data.get("dimension")
    if not isinstance(dimension, dict):
        dimension = {}
        data["dimension"] = dimension
    dimension["bounds"] = derive_cave_build_bounds(data) if category == "caves" else derive_forest_build_bounds(data)
    return data


def synchronize_spatial_build_files(root: Path) -> int:
    changed = 0
    for category in ("caves", "forests"):
        directory = root / "content" / category
        for path in sorted(directory.rglob("*.json")) if directory.is_dir() else []:
            document = load_json(path)
            previous_dimension = document.get("dimension") if isinstance(document, dict) else None
            previous = copy.deepcopy(previous_dimension.get("bounds")) if isinstance(previous_dimension, dict) else None
            synchronize_spatial_build_bounds(document, category)
            if document.get("dimension", {}).get("bounds") == previous:
                continue
            path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            changed += 1
    return changed


def _validate_pokemon_level_overrides(
    overrides: Any, issues: list[Issue], path: Path, base: str,
    known_pokemon: set[str] | None = None,
) -> None:
    if not isinstance(overrides, list):
        _issue(issues, "error", path, base, "포켓몬 개별 레벨 설정은 배열이어야 합니다.")
        return
    seen: set[str] = set()
    for index, override in enumerate(overrides):
        override_path = f"{base}[{index}]"
        species = override.get("species") if isinstance(override, dict) else None
        if not isinstance(species, str) or not RESOURCE_ID.fullmatch(species):
            _issue(issues, "error", path, f"{override_path}.species", "올바른 포켓몬 리소스 ID가 필요합니다.")
        elif species in seen:
            _issue(issues, "error", path, f"{override_path}.species", f"중복 개별 레벨 포켓몬: {species}")
        elif known_pokemon is not None and species not in known_pokemon:
            _issue(issues, "error", path, f"{override_path}.species", f"포켓몬 카탈로그에 없는 종입니다: {species}")
        else:
            seen.add(species)
        if not isinstance(override, dict):
            continue
        minimum, maximum = override.get("min_level"), override.get("max_level")
        if (not isinstance(minimum, int) or isinstance(minimum, bool) or not 1 <= minimum <= 100
                or not isinstance(maximum, int) or isinstance(maximum, bool) or not 1 <= maximum <= 100
                or minimum > maximum):
            _issue(issues, "error", path, override_path, "개별 레벨 범위는 1~100이며 최소가 최대보다 클 수 없습니다.")


def _validate_pursuit_encounters(encounters: Any, issues: list[Issue], path: Path) -> None:
    base = "$.random_encounters"
    if not isinstance(encounters, dict):
        _issue(issues, "error", path, base, "추적 인카운터 설정이 필요합니다.")
        return
    if not isinstance(encounters.get("enabled"), bool):
        _issue(issues, "error", path, f"{base}.enabled", "사용 여부는 true 또는 false여야 합니다.")
    _resource_id(encounters.get("pokemon_biome"), issues, path, f"{base}.pokemon_biome")
    for minimum_key, maximum_key, low, high, label in (
        ("minimum_distance", "maximum_distance", 1, 10000, "주변 스폰 거리"),
        ("minimum_level", "maximum_level", 1, 100, "레벨"),
    ):
        minimum = encounters.get(minimum_key)
        maximum = encounters.get(maximum_key)
        if not isinstance(minimum, int) or isinstance(minimum, bool) or not low <= minimum <= high:
            _issue(issues, "error", path, f"{base}.{minimum_key}", f"최소 {label}는 {low}~{high} 정수여야 합니다.")
        if not isinstance(maximum, int) or isinstance(maximum, bool) or not low <= maximum <= high:
            _issue(issues, "error", path, f"{base}.{maximum_key}", f"최대 {label}는 {low}~{high} 정수여야 합니다.")
        if isinstance(minimum, int) and isinstance(maximum, int) and minimum > maximum:
            _issue(issues, "error", path, base, f"최소 {label}는 최대값보다 클 수 없습니다.")
    if not isinstance(encounters.get("inherit_biome"), bool):
        _issue(issues, "error", path, f"{base}.inherit_biome", "바이옴 포켓몬 사용 여부가 필요합니다.")
    excluded = encounters.get("excluded_species")
    if not isinstance(excluded, list) or any(not isinstance(value, str) or not RESOURCE_ID.fullmatch(value) for value in excluded):
        _issue(issues, "error", path, f"{base}.excluded_species", "제외 포켓몬 ID 배열이 필요합니다.")
    additions = encounters.get("additions")
    if not isinstance(additions, list):
        _issue(issues, "error", path, f"{base}.additions", "직접 추가 포켓몬 목록이 필요합니다.")
        return
    seen: set[str] = set()
    for index, addition in enumerate(additions):
        addition_path = f"{base}.additions[{index}]"
        species = addition.get("species") if isinstance(addition, dict) else None
        if not isinstance(species, str) or not RESOURCE_ID.fullmatch(species) or species in seen:
            _issue(issues, "error", path, f"{addition_path}.species", "중복되지 않는 포켓몬 ID가 필요합니다.")
        else:
            seen.add(species)
        if not isinstance(addition, dict):
            continue
        minimum = addition.get("min_level")
        maximum = addition.get("max_level")
        if not isinstance(minimum, int) or not 1 <= minimum <= 100 or not isinstance(maximum, int) or not 1 <= maximum <= 100 or minimum > maximum:
            _issue(issues, "error", path, addition_path, "추가 포켓몬 레벨 범위는 1~100이며 최소가 최대보다 클 수 없습니다.")
        if "spawn_as_evolved" in addition and not isinstance(addition["spawn_as_evolved"], bool):
            _issue(issues, "error", path, f"{addition_path}.spawn_as_evolved", "진화본 출현 여부는 true 또는 false여야 합니다.")
    _validate_pokemon_level_overrides(encounters.get("level_overrides", []), issues, path, f"{base}.level_overrides")


def _validate_embedded_sites(
    data: dict[str, Any], region_kind: str, anchor_ids: set[str],
    issues: list[Issue], path: Path,
) -> None:
    sites = data.get("embedded_sites", [])
    if not isinstance(sites, list):
        _issue(issues, "error", path, "$.embedded_sites", "던전 입구 지점 목록은 배열이어야 합니다.")
        return
    seen_ids: set[str] = set()
    rotations = {"none", "clockwise_90", "clockwise_180", "counterclockwise_90"}
    candidates = ({"any", "main_path", "landmark"} if region_kind == "cave"
                  else {"any", "main_path", "branch"})
    for index, site in enumerate(sites):
        base = f"$.embedded_sites[{index}]"
        if not isinstance(site, dict):
            _issue(issues, "error", path, base, "던전 입구 지점은 객체여야 합니다.")
            continue
        site_id = site.get("id")
        if not isinstance(site_id, str) or not CHOICE_ID.fullmatch(site_id) or site_id in seen_ids:
            _issue(issues, "error", path, f"{base}.id", "유일한 소문자 지점 ID가 필요합니다.")
        else:
            seen_ids.add(site_id)
        _resource_id(site.get("entrance_id"), issues, path, f"{base}.entrance_id")
        placement = site.get("placement")
        if placement not in {"fixed", "anchor", "rule"}:
            _issue(issues, "error", path, f"{base}.placement", "fixed, anchor 또는 rule 배치 방식이 필요합니다.")
        if placement == "fixed":
            position = site.get("position")
            if not isinstance(position, dict) or any(
                not isinstance(position.get(axis), int) or isinstance(position.get(axis), bool)
                for axis in ("x", "y", "z")
            ):
                _issue(issues, "error", path, f"{base}.position", "고정 배치에는 절대 블록 좌표 x, y, z가 필요합니다.")
        if placement == "anchor" and site.get("anchor") not in anchor_ids:
            _issue(issues, "error", path, f"{base}.anchor", "존재하는 동굴 앵커 또는 숲 길 ID가 필요합니다.")
        if placement == "rule" and site.get("candidate") not in candidates:
            _issue(issues, "error", path, f"{base}.candidate", "이 지역에서 지원하는 자동 배치 후보가 필요합니다.")
        for field in ("offset", "safe_spawn"):
            position = site.get(field)
            if position is not None and (not isinstance(position, dict) or any(
                not isinstance(position.get(axis), int) or isinstance(position.get(axis), bool)
                for axis in ("x", "y", "z")
            )):
                _issue(issues, "error", path, f"{base}.{field}", "정수 블록 좌표 x, y, z가 필요합니다.")
        structure = site.get("structure")
        door_anchor = site.get("door_anchor")
        if structure is not None:
            _resource_id(structure, issues, path, f"{base}.structure")
        if (structure is None) != (door_anchor is None):
            _issue(issues, "error", path, base, "NBT structure와 EditWorld door_anchor는 함께 지정해야 합니다.")
        elif door_anchor is not None and (not isinstance(door_anchor, str) or not CHOICE_ID.fullmatch(door_anchor)):
            _issue(issues, "error", path, f"{base}.door_anchor", "유효한 EditWorld 문 앵커 ID가 필요합니다.")
        if site.get("rotation", "none") not in rotations:
            _issue(issues, "error", path, f"{base}.rotation", "지원되는 90도 단위 회전이 필요합니다.")


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
    _resource_id(data.get("cave_type"), issues, path, "$.cave_type")
    dimension = data.get("dimension")
    if not isinstance(dimension, dict):
        _issue(issues, "error", path, "$.dimension", "동굴 차원 설정이 필요합니다.")
    else:
        _resource_id(dimension.get("id"), issues, path, "$.dimension.id")
    if not isinstance(data.get("requires_flash"), bool):
        _issue(issues, "error", path, "$.requires_flash", "플래시 필요 여부는 true 또는 false여야 합니다.")
    if data.get("style") not in {"rock", "dripstone", "crystal", "lush"}:
        _issue(issues, "error", path, "$.style", "동굴 스타일은 rock, dripstone, crystal 또는 lush여야 합니다.")
    _validate_pursuit_encounters(data.get("random_encounters"), issues, path)
    trainer_settings = data.get("trainer_settings")
    if not isinstance(trainer_settings, dict) or not isinstance(trainer_settings.get("enabled"), bool):
        _issue(issues, "error", path, "$.trainer_settings", "트레이너 설정이 필요합니다.")
    else:
        _validate_trainer_population(trainer_settings, issues, path, "$.trainer_settings")
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
    cave_anchor_ids = {
        anchor.get("id") for anchor in (manual.get("anchors", []) if isinstance(manual, dict) else [])
        if isinstance(anchor, dict) and isinstance(anchor.get("id"), str)
    }
    _validate_embedded_sites(data, "cave", cave_anchor_ids, issues, path)
    return cave_id, issues


def _underground_road_endpoints(data: Any, root: Path) -> list[dict[str, str]]:
    """Return unpaired upward connectors that a world-map entrance can target."""
    if not isinstance(data, dict) or not isinstance(data.get("modules"), list):
        return []
    structures = managed_structure_files(root)
    directions = ["north", "east", "south", "west"]
    nodes: list[dict[str, Any]] = []
    for module in data["modules"]:
        if not isinstance(module, dict) or not isinstance(module.get("id"), str):
            continue
        structure = structures.get(module.get("structure"))
        position = module.get("position")
        rotation = module.get("rotation", "none")
        if structure is None or not isinstance(position, dict) or any(
            not isinstance(position.get(axis), int) or isinstance(position.get(axis), bool)
            for axis in ("x", "y", "z")
        ):
            continue
        try:
            metadata = read_minecraft_structure_metadata(structure.read_bytes())
        except (OSError, ValueError, gzip.BadGzipFile):
            continue
        width, depth = metadata["width"], metadata["depth"]
        turns = {"none": 0, "clockwise_90": 1, "clockwise_180": 2, "counterclockwise_90": 3}.get(rotation, 0)
        for connector in metadata.get("underground_connectors", []):
            if not isinstance(connector, dict):
                continue
            x, y, z = connector["position"]
            if rotation == "clockwise_90": x, z = depth - 1 - z, x
            elif rotation == "clockwise_180": x, z = width - 1 - x, depth - 1 - z
            elif rotation == "counterclockwise_90": x, z = z, width - 1 - x
            facing = connector["facing"]
            if facing in directions:
                facing = directions[(directions.index(facing) + turns) % 4]
            nodes.append({
                "module": module["id"], "connector": connector["tag"],
                "x": position["x"] + x, "y": position["y"] + y,
                "z": position["z"] + z, "facing": facing,
            })
    opposites = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}
    offsets = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
    result: list[dict[str, str]] = []
    for node in nodes:
        dx, dy, dz = offsets[node["facing"]]
        paired = any(
            other is not node and other["x"] == node["x"] + dx
            and other["y"] == node["y"] + dy and other["z"] == node["z"] + dz
            and other["facing"] == opposites[node["facing"]]
            for other in nodes
        )
        if not paired and node["facing"] == "up":
            result.append({"module": node["module"], "connector": node["connector"]})
    return result


def validate_underground_road_document(
    data: Any, path: Path, root: Path | None = None,
) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    if not isinstance(data, dict):
        _issue(issues, "error", path, "$", "지하통로 문서는 객체여야 합니다.")
        return None, issues
    road_id = data.get("id")
    if (not isinstance(road_id, str) or not RESOURCE_ID.fullmatch(road_id)
            or not road_id.startswith("cobbleventure:underground_road/")):
        _issue(issues, "error", path, "$.id", "cobbleventure:underground_road/<이름> 형식의 ID가 필요합니다.")
        road_id = None
    if data.get("schema_version") != 2:
        _issue(issues, "error", path, "$.schema_version", "조립형 지하통로 문서 지원 버전은 2입니다.")
    if not isinstance(data.get("enabled"), bool):
        _issue(issues, "error", path, "$.enabled", "사용 여부는 true 또는 false여야 합니다.")
    _localized_text(data.get("display_name"), issues, path, "$.display_name")
    dimension = _require_object(data.get("dimension"), issues, path, "$.dimension")
    if dimension is not None:
        if dimension.get("id") != "cobbleventure:dungeons":
            _issue(issues, "error", path, "$.dimension.id", "지하통로는 cobbleventure:dungeons 차원을 사용해야 합니다.")
        _require_string(dimension.get("region_id"), issues, path, "$.dimension.region_id")
        origin = _require_object(dimension.get("origin"), issues, path, "$.dimension.origin")
        if origin is not None and any(
            not isinstance(origin.get(axis), int) or isinstance(origin.get(axis), bool)
            for axis in ("x", "y", "z")
        ):
            _issue(issues, "error", path, "$.dimension.origin", "정수 블록 좌표 x, y, z가 필요합니다.")
    modules = data.get("modules")
    if not isinstance(modules, list) or not modules:
        _issue(issues, "error", path, "$.modules", "배치한 지하통로 조각이 하나 이상 필요합니다.")
        modules = []
    module_ids: set[str] = set()
    module_by_id: dict[str, dict[str, Any]] = {}
    rotations = {"none", "clockwise_90", "clockwise_180", "counterclockwise_90"}
    for index, module in enumerate(modules):
        base = f"$.modules[{index}]"
        if not isinstance(module, dict):
            _issue(issues, "error", path, base, "지하통로 조각 배치는 객체여야 합니다.")
            continue
        module_id = module.get("id")
        if not isinstance(module_id, str) or not CHOICE_ID.fullmatch(module_id) or module_id in module_ids:
            _issue(issues, "error", path, f"{base}.id", "유일한 소문자 조각 배치 ID가 필요합니다.")
        else:
            module_ids.add(module_id); module_by_id[module_id] = module
        structure = _resource_id(module.get("structure"), issues, path, f"{base}.structure")
        if structure and not structure.startswith("cobbleventure:underground_road_modules/"):
            _issue(issues, "error", path, f"{base}.structure", "지하통로 조각 NBT를 선택해야 합니다.")
        position = _require_object(module.get("position"), issues, path, f"{base}.position")
        if position is not None and any(not isinstance(position.get(axis), int) or isinstance(position.get(axis), bool) for axis in ("x", "y", "z")):
            _issue(issues, "error", path, f"{base}.position", "정수 상대 좌표 x, y, z가 필요합니다.")
        if module.get("rotation") not in rotations:
            _issue(issues, "error", path, f"{base}.rotation", "지원되는 90도 단위 회전이 필요합니다.")
    if "ports" in data:
        _issue(issues, "error", path, "$.ports", "입구 연결은 지하통로가 아니라 월드맵 입구 배치에서 정의해야 합니다.")
    if root is not None and modules:
        structures = managed_structure_files(root)
        connector_nodes: list[dict[str, Any]] = []
        boxes: list[tuple[str, int, int, int, int, int, int]] = []
        directions = ["north", "east", "south", "west"]
        for index, module in enumerate(modules):
            if not isinstance(module, dict) or module.get("id") not in module_by_id:
                continue
            structure_id = module.get("structure")
            structure = structures.get(structure_id) if isinstance(structure_id, str) else None
            if structure is None or _managed_structure_category(structure.relative_to(root / "content" / "structures")) != "underground_road_module":
                _issue(issues, "error", path, f"$.modules[{index}].structure", "존재하는 지하통로 조각 NBT를 선택해야 합니다.")
                continue
            try:
                metadata = read_minecraft_structure_metadata(structure.read_bytes())
            except (OSError, ValueError, gzip.BadGzipFile) as error:
                _issue(issues, "error", path, f"$.modules[{index}].structure", f"조각 NBT를 읽을 수 없습니다: {error}")
                continue
            connectors = metadata.get("underground_connectors", [])
            tags = [item.get("tag") for item in connectors if isinstance(item, dict)]
            if not connectors:
                _issue(issues, "error", path, f"$.modules[{index}].structure", "조각 NBT에 underground_connector 직소가 없습니다.")
            if len(tags) != len(set(tags)):
                _issue(issues, "error", path, f"$.modules[{index}].structure", "조각 NBT 안의 커넥터 태그가 중복됩니다.")
            position = module.get("position", {}); rotation = module.get("rotation", "none")
            width, height, depth = metadata["width"], metadata["height"], metadata["depth"]
            rotated_width, rotated_depth = (depth, width) if rotation in {"clockwise_90", "counterclockwise_90"} else (width, depth)
            if all(isinstance(position.get(axis), int) and not isinstance(position.get(axis), bool) for axis in ("x", "y", "z")):
                boxes.append((module["id"], position["x"], position["y"], position["z"], position["x"] + rotated_width, position["y"] + height, position["z"] + rotated_depth))
                turns = {"none": 0, "clockwise_90": 1, "clockwise_180": 2, "counterclockwise_90": 3}.get(rotation, 0)
                for connector in connectors:
                    x, y, z = connector["position"]
                    if rotation == "clockwise_90": x, z = depth - 1 - z, x
                    elif rotation == "clockwise_180": x, z = width - 1 - x, depth - 1 - z
                    elif rotation == "counterclockwise_90": x, z = z, width - 1 - x
                    original_facing = connector["facing"]
                    facing = directions[(directions.index(original_facing) + turns) % 4] if original_facing in directions else original_facing
                    connector_nodes.append({"module": module["id"], "tag": connector["tag"], "x": position["x"] + x, "y": position["y"] + y, "z": position["z"] + z, "facing": facing})
        for left in range(len(boxes)):
            for right in range(left + 1, len(boxes)):
                a, b = boxes[left], boxes[right]
                if a[1] < b[4] and a[4] > b[1] and a[2] < b[5] and a[5] > b[2] and a[3] < b[6] and a[6] > b[3]:
                    _issue(issues, "error", path, "$.modules", f"조각 배치가 겹칩니다: {a[0]}, {b[0]}")
        opposites = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}; offsets = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
        open_up_connectors = 0
        for node in connector_nodes:
            dx, dy, dz = offsets[node["facing"]]
            paired = any(other is not node and other["x"] == node["x"] + dx and other["y"] == node["y"] + dy and other["z"] == node["z"] + dz and other["facing"] == opposites[node["facing"]] for other in connector_nodes)
            if not paired and node["facing"] == "up":
                open_up_connectors += 1
            elif not paired:
                _issue(issues, "error", path, "$.modules", f"수평·아래쪽 커넥터는 다른 조각과 연결해야 합니다: {node['module']}/{node['tag']}")
        if open_up_connectors < 2:
            _issue(issues, "error", path, "$.modules", "월드맵 입구가 연결할 열린 위쪽 계단 커넥터가 두 개 이상 필요합니다.")
    return road_id, issues


def validate_underground_road_file(path: Path) -> tuple[str | None, list[Issue]]:
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        issues: list[Issue] = []
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return None, issues
    return validate_underground_road_document(data, path)


def validate_forest_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return None, issues
    if not isinstance(data, dict):
        _issue(issues, "error", path, "$", "숲 문서는 객체여야 합니다.")
        return None, issues
    forest_id = data.get("id")
    if not isinstance(forest_id, str) or not RESOURCE_ID.fullmatch(forest_id):
        _issue(issues, "error", path, "$.id", "올바른 숲 리소스 ID가 필요합니다.")
        forest_id = None
    if data.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "숲 문서 지원 버전은 1입니다.")
    _validate_pursuit_encounters(data.get("random_encounters"), issues, path)
    _validate_trainer_population(data.get("trainer_settings"), issues, path, "$.trainer_settings")
    bounds = None
    dimension = data.get("dimension")
    if not isinstance(dimension, dict):
        _issue(issues, "error", path, "$.dimension", "숲 차원 설정이 필요합니다.")
    else:
        _resource_id(dimension.get("id"), issues, path, "$.dimension.id")
        if dimension.get("id") != "cobbleventure:forests":
            _issue(issues, "error", path, "$.dimension.id", "숲은 전용 차원 cobbleventure:forests를 사용해야 합니다.")
        bounds = dimension.get("bounds")
        if not isinstance(bounds, dict) or not all(
            isinstance(bounds.get(key), int) and not isinstance(bounds.get(key), bool)
            for key in ("min_x", "min_z", "max_x", "max_z")
        ):
            _issue(issues, "error", path, "$.dimension.bounds", "정수 경계 min_x, min_z, max_x, max_z가 필요합니다.")
    environment = data.get("environment")
    if not isinstance(environment, dict) or environment.get("weather") not in {"clear", "rain", "thunder"}:
        _issue(issues, "error", path, "$.environment.weather", "날씨는 clear, rain 또는 thunder여야 합니다.")
    barrier = data.get("tree_barrier")
    if not isinstance(barrier, dict):
        _issue(issues, "error", path, "$.tree_barrier", "이동 불가 나무 장벽 설정이 필요합니다.")
    else:
        for field in ("min_height", "max_height"):
            if not isinstance(barrier.get(field), int) or isinstance(barrier.get(field), bool) or not 2 <= barrier.get(field, 0) <= 64:
                _issue(issues, "error", path, f"$.tree_barrier.{field}", "나무 높이는 2 이상 64 이하의 정수여야 합니다.")
        if isinstance(barrier.get("min_height"), int) and isinstance(barrier.get("max_height"), int) and barrier["min_height"] > barrier["max_height"]:
            _issue(issues, "error", path, "$.tree_barrier", "최소 나무 높이는 최대 높이보다 클 수 없습니다.")
        _resource_id(barrier.get("barrier_block"), issues, path, "$.tree_barrier.barrier_block")
        for field in ("trunk_blocks", "foliage_blocks"):
            values = barrier.get(field)
            if not isinstance(values, list) or not values:
                _issue(issues, "error", path, f"$.tree_barrier.{field}", "블록을 하나 이상 지정해야 합니다.")
            else:
                for index, value in enumerate(values):
                    _resource_id(value, issues, path, f"$.tree_barrier.{field}[{index}]")
    undergrowth = data.get("undergrowth")
    density = undergrowth.get("density") if isinstance(undergrowth, dict) else None
    if not isinstance(density, (int, float)) or isinstance(density, bool) or not 0 <= density <= 1:
        _issue(issues, "error", path, "$.undergrowth.density", "풀숲 밀도는 0 이상 1 이하의 수여야 합니다.")
    blocks = undergrowth.get("blocks") if isinstance(undergrowth, dict) else None
    if not isinstance(blocks, list) or not blocks:
        _issue(issues, "error", path, "$.undergrowth.blocks", "풀숲 블록을 하나 이상 지정해야 합니다.")
    else:
        for index, value in enumerate(blocks):
            _resource_id(value, issues, path, f"$.undergrowth.blocks[{index}]")
    generator = data.get("generator")
    cell_size = generator.get("cell_size") if isinstance(generator, dict) else None
    if not isinstance(generator, dict) or generator.get("layout") not in {"maze", "manual", "hybrid"}:
        _issue(issues, "error", path, "$.generator.layout", "숲 생성 방식은 maze, manual 또는 hybrid여야 합니다.")
    else:
        if not isinstance(cell_size, int) or isinstance(cell_size, bool) or not 4 <= cell_size <= 64:
            _issue(issues, "error", path, "$.generator.cell_size", "타일 크기는 4 이상 64 이하의 정수여야 합니다.")
        for field in ("maze_complexity", "loop_chance", "spline_tension"):
            value = generator.get(field)
            if not isinstance(value, (int, float)) or isinstance(value, bool) or not 0 <= value <= 1:
                _issue(issues, "error", path, f"$.generator.{field}", "0 이상 1 이하의 수가 필요합니다.")
        if not isinstance(generator.get("spline_enabled"), bool):
            _issue(issues, "error", path, "$.generator.spline_enabled", "스플라인 사용 여부는 true 또는 false여야 합니다.")
    paths = data.get("paths")
    if not isinstance(paths, list) or not paths:
        _issue(issues, "error", path, "$.paths", "이동 가능한 길을 하나 이상 지정해야 합니다.")
        paths = []
    seen_paths: set[str] = set()
    for index, route in enumerate(paths):
        route_path = f"$.paths[{index}]"
        route_id = route.get("id") if isinstance(route, dict) else None
        if not isinstance(route_id, str) or not CHOICE_ID.fullmatch(route_id) or route_id in seen_paths:
            _issue(issues, "error", path, f"{route_path}.id", "유일한 길 ID가 필요합니다.")
        else:
            seen_paths.add(route_id)
        points = route.get("points") if isinstance(route, dict) else None
        if not isinstance(points, list) or len(points) < 2:
            _issue(issues, "error", path, f"{route_path}.points", "길에는 두 개 이상의 2D 지점이 필요합니다.")
        else:
            for point_index, point in enumerate(points):
                if not isinstance(point, dict) or not all(isinstance(point.get(axis), int) and not isinstance(point.get(axis), bool) for axis in ("x", "z")):
                    _issue(issues, "error", path, f"{route_path}.points[{point_index}]", "정수 좌표 x, z가 필요합니다.")
        width = route.get("width") if isinstance(route, dict) else None
        if not isinstance(width, int) or isinstance(width, bool) or not 2 <= width <= 32:
            _issue(issues, "error", path, f"{route_path}.width", "길 너비는 2 이상 32 이하의 정수여야 합니다.")
        if isinstance(route, dict):
            route_kind = route.get("kind")
            if route_kind is not None and route_kind not in {"main", "shortcut", "manual"}:
                _issue(issues, "error", path, f"{route_path}.kind", "길 역할은 주 경로, 지름길 또는 수동 길이어야 합니다.")
            _resource_id(route.get("surface"), issues, path, f"{route_path}.surface")
            spline = route.get("spline")
            if not isinstance(spline, dict) or not isinstance(spline.get("enabled"), bool):
                _issue(issues, "error", path, f"{route_path}.spline", "길의 스플라인 사용 여부가 필요합니다.")
    terrain_tiles = data.get("terrain_tiles", [])
    if not isinstance(terrain_tiles, list):
        _issue(issues, "error", path, "$.terrain_tiles", "높이 조절 타일 목록은 배열이어야 합니다.")
    else:
        seen_tiles: set[tuple[int, int]] = set()
        for index, tile in enumerate(terrain_tiles):
            tile_path = f"$.terrain_tiles[{index}]"
            if not isinstance(tile, dict) or not all(isinstance(tile.get(axis), int) and not isinstance(tile.get(axis), bool) for axis in ("x", "z")):
                _issue(issues, "error", path, tile_path, "높이 타일에는 정수 좌표 x, z가 필요합니다.")
                continue
            coordinate = (tile["x"], tile["z"])
            if coordinate in seen_tiles:
                _issue(issues, "error", path, tile_path, "같은 위치의 높이 타일을 중복 지정할 수 없습니다.")
            seen_tiles.add(coordinate)
            valid_bounds = isinstance(bounds, dict) and all(
                isinstance(bounds.get(key), int) and not isinstance(bounds.get(key), bool)
                for key in ("min_x", "min_z", "max_x", "max_z")
            )
            if isinstance(cell_size, int) and not isinstance(cell_size, bool) and cell_size > 0 and valid_bounds:
                columns = max(1, math.floor((bounds["max_x"] - bounds["min_x"]) / cell_size))
                rows = max(1, math.floor((bounds["max_z"] - bounds["min_z"]) / cell_size))
                center_offset = math.ceil(cell_size / 2)
                min_center_x = bounds["min_x"] + center_offset
                min_center_z = bounds["min_z"] + center_offset
                max_center_x = min_center_x + (columns - 1) * cell_size
                max_center_z = min_center_z + (rows - 1) * cell_size
                aligned_to_tile_centers = (
                    min_center_x <= coordinate[0] <= max_center_x
                    and min_center_z <= coordinate[1] <= max_center_z
                    and (coordinate[0] - min_center_x) % cell_size == 0
                    and (coordinate[1] - min_center_z) % cell_size == 0
                )
                if not aligned_to_tile_centers:
                    _issue(issues, "error", path, tile_path, f"높이 타일은 빌드 영역 기준 {cell_size}블록 타일 격자의 중심에 맞아야 합니다.")
            height_offset = tile.get("height_offset")
            if not isinstance(height_offset, int) or isinstance(height_offset, bool) or not -16 <= height_offset <= 16:
                _issue(issues, "error", path, f"{tile_path}.height_offset", "타일 높이 보정은 -16 이상 16 이하의 정수여야 합니다.")
            transition = tile.get("transition")
            if transition is not None:
                if not isinstance(transition, dict):
                    _issue(issues, "error", path, f"{tile_path}.transition", "높이 전환은 계단 또는 경사로 설정이어야 합니다.")
                else:
                    if transition.get("kind") not in {"stairs", "slope"}:
                        _issue(issues, "error", path, f"{tile_path}.transition.kind", "높이 전환 종류는 계단 또는 경사로여야 합니다.")
                    if transition.get("direction") not in {"north", "south", "east", "west"}:
                        _issue(issues, "error", path, f"{tile_path}.transition.direction", "높이 전환 방향은 동서남북 중 하나여야 합니다.")
                    _resource_id(transition.get("block"), issues, path, f"{tile_path}.transition.block")
    entrances = data.get("entrances")
    if not isinstance(entrances, list) or not entrances:
        _issue(issues, "error", path, "$.entrances", "숲 입구를 하나 이상 지정해야 합니다.")
    else:
        seen_entrances: set[str] = set()
        for index, entrance in enumerate(entrances):
            entrance_path = f"$.entrances[{index}]"
            entrance_id = entrance.get("id") if isinstance(entrance, dict) else None
            if not isinstance(entrance_id, str) or not CHOICE_ID.fullmatch(entrance_id) or entrance_id in seen_entrances:
                _issue(issues, "error", path, f"{entrance_path}.id", "유일한 숲 입구 ID가 필요합니다.")
            else:
                seen_entrances.add(entrance_id)
            point = entrance.get("position") if isinstance(entrance, dict) else None
            if not isinstance(point, dict) or not all(isinstance(point.get(axis), int) and not isinstance(point.get(axis), bool) for axis in ("x", "z")):
                _issue(issues, "error", path, f"{entrance_path}.position", "정수 좌표 x, z가 필요합니다.")
    _validate_embedded_sites(data, "forest", seen_paths, issues, path)
    return forest_id, issues


ROUTE_ENCOUNTER_METHODS = {"surf", "old_rod", "good_rod", "super_rod", "headbutt"}


def _validate_route_encounter_pools(
    pokemon: dict[str, Any], issues: list[Issue], path: Path, base: str
) -> None:
    pools = pokemon.get("encounter_pools")
    if pools is None:
        return
    if not isinstance(pools, dict):
        _issue(issues, "error", path, f"{base}.encounter_pools", "조우 방식별 포켓몬 설정은 객체여야 합니다.")
        return
    for method, pool in pools.items():
        pool_path = f"{base}.encounter_pools.{method}"
        if method not in ROUTE_ENCOUNTER_METHODS:
            _issue(issues, "error", path, pool_path, f"지원하지 않는 조우 방식입니다: {method}")
            continue
        if not isinstance(pool, dict):
            _issue(issues, "error", path, pool_path, "조우 풀 설정은 객체여야 합니다.")
            continue
        for field in ("enabled", "inherit_biome"):
            if not isinstance(pool.get(field), bool):
                _issue(issues, "error", path, f"{pool_path}.{field}", "true 또는 false가 필요합니다.")
        chance = pool.get("trigger_chance")
        if not isinstance(chance, (int, float)) or isinstance(chance, bool) or not 0 <= chance <= 1:
            _issue(issues, "error", path, f"{pool_path}.trigger_chance", "발동 확률은 0 이상 1 이하 숫자여야 합니다.")
        for field in ("excluded_species", "additions", "level_overrides"):
            if not isinstance(pool.get(field), list):
                _issue(issues, "error", path, f"{pool_path}.{field}", "배열이어야 합니다.")
        for index, species in enumerate(pool.get("excluded_species", [])):
            _resource_id(species, issues, path, f"{pool_path}.excluded_species[{index}]")
        for field in ("additions", "level_overrides"):
            for index, entry in enumerate(pool.get(field, [])):
                entry_path = f"{pool_path}.{field}[{index}]"
                if not isinstance(entry, dict):
                    _issue(issues, "error", path, entry_path, "포켓몬과 레벨 범위 설정이 필요합니다.")
                    continue
                _resource_id(entry.get("species"), issues, path, f"{entry_path}.species")
                minimum, maximum = entry.get("min_level"), entry.get("max_level")
                if not isinstance(minimum, int) or isinstance(minimum, bool) or not 1 <= minimum <= 100:
                    _issue(issues, "error", path, f"{entry_path}.min_level", "최소 레벨은 1~100 정수여야 합니다.")
                if not isinstance(maximum, int) or isinstance(maximum, bool) or not 1 <= maximum <= 100:
                    _issue(issues, "error", path, f"{entry_path}.max_level", "최대 레벨은 1~100 정수여야 합니다.")
                if isinstance(minimum, int) and isinstance(maximum, int) and minimum > maximum:
                    _issue(issues, "error", path, entry_path, "최소 레벨은 최대 레벨보다 클 수 없습니다.")
                if field == "additions":
                    weight = entry.get("weight", 1)
                    if not isinstance(weight, int) or isinstance(weight, bool) or not 1 <= weight <= 10000:
                        _issue(issues, "error", path, f"{entry_path}.weight", "가중치는 1~10000 정수여야 합니다.")


def validate_route_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"길 프리셋 JSON을 읽을 수 없습니다: {error}")
        return None, issues
    if not isinstance(data, dict):
        _issue(issues, "error", path, "$", "길 프리셋은 객체여야 합니다.")
        return None, issues
    route_id = data.get("id")
    if not isinstance(route_id, str) or not RESOURCE_ID.fullmatch(route_id):
        _issue(issues, "error", path, "$.id", "올바른 길 프리셋 리소스 ID가 필요합니다.")
        route_id = None
    if data.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "길 프리셋 schema_version은 1이어야 합니다.")
    display_name = data.get("display_name")
    if not isinstance(display_name, dict) or not any(
        isinstance(value, str) and value.strip() for value in display_name.values()
    ):
        _issue(issues, "error", path, "$.display_name", "길 프리셋 이름을 하나 이상 입력해야 합니다.")
    if not isinstance(data.get("enabled"), bool):
        _issue(issues, "error", path, "$.enabled", "사용 여부는 true 또는 false여야 합니다.")
    if data.get("route_type") not in {"road", "trail", "water", "log_bridge"}:
        _issue(issues, "error", path, "$.route_type", "길 종류는 road, trail, water, log_bridge 중 하나여야 합니다.")
    bridge_layout = data.get("log_bridge_layout")
    if bridge_layout is not None:
        if not isinstance(bridge_layout, dict):
            _issue(issues, "error", path, "$.log_bridge_layout", "통나무다리 경로 설정은 객체여야 합니다.")
        else:
            if bridge_layout.get("pattern") not in {"straight", "u_turn", "zigzag", "alternating"}:
                _issue(issues, "error", path, "$.log_bridge_layout.pattern", "직선, ㄷ자, ㄹ자 또는 ㄷ/ㄹ 교차 형태가 필요합니다.")
            detour = bridge_layout.get("detour_blocks")
            if not isinstance(detour, (int, float)) or isinstance(detour, bool) or not 6 <= detour <= 24:
                _issue(issues, "error", path, "$.log_bridge_layout.detour_blocks", "우회 폭은 6 이상 24 이하 숫자여야 합니다.")

    corridor = data.get("corridor")
    if not isinstance(corridor, dict):
        _issue(issues, "error", path, "$.corridor", "통로 외형 설정이 필요합니다.")
    else:
        width = corridor.get("width_blocks")
        if not isinstance(width, (int, float)) or isinstance(width, bool) or not 12 <= width <= 256:
            _issue(issues, "error", path, "$.corridor.width_blocks", "통로 폭은 12 이상 256 이하 숫자여야 합니다.")
        noise = corridor.get("edge_noise")
        if not isinstance(noise, (int, float)) or isinstance(noise, bool) or not 0 <= noise <= 0.35:
            _issue(issues, "error", path, "$.corridor.edge_noise", "통로 굴곡은 0 이상 0.35 이하 숫자여야 합니다.")
        if corridor.get("boundary_profile") is not None:
            _resource_id(corridor.get("boundary_profile"), issues, path, "$.corridor.boundary_profile")

    scaling = data.get("level_scaling")
    if not isinstance(scaling, dict):
        _issue(issues, "error", path, "$.level_scaling", "레벨 조절 설정이 필요합니다.")
    else:
        mode = scaling.get("mode")
        if mode not in {"world", "fixed", "offset"}:
            _issue(issues, "error", path, "$.level_scaling.mode", "레벨 방식은 world, fixed, offset 중 하나여야 합니다.")
        offset = scaling.get("offset")
        if not isinstance(offset, int) or isinstance(offset, bool) or not -100 <= offset <= 100:
            _issue(issues, "error", path, "$.level_scaling.offset", "레벨 보정은 -100 이상 100 이하 정수여야 합니다.")
        minimum = scaling.get("minimum_level")
        maximum = scaling.get("maximum_level")
        if mode == "fixed":
            if not isinstance(minimum, int) or isinstance(minimum, bool) or not 1 <= minimum <= 100:
                _issue(issues, "error", path, "$.level_scaling.minimum_level", "고정 최소 레벨은 1~100 정수여야 합니다.")
            if not isinstance(maximum, int) or isinstance(maximum, bool) or not 1 <= maximum <= 100:
                _issue(issues, "error", path, "$.level_scaling.maximum_level", "고정 최대 레벨은 1~100 정수여야 합니다.")
            if isinstance(minimum, int) and isinstance(maximum, int) and minimum > maximum:
                _issue(issues, "error", path, "$.level_scaling", "최소 레벨은 최대 레벨보다 클 수 없습니다.")

    pokemon = data.get("pokemon_spawns")
    if not isinstance(pokemon, dict):
        _issue(issues, "error", path, "$.pokemon_spawns", "포켓몬 출현 설정이 필요합니다.")
    else:
        if not isinstance(pokemon.get("inherit_biome"), bool):
            _issue(issues, "error", path, "$.pokemon_spawns.inherit_biome", "바이옴 포켓몬 상속 여부가 필요합니다.")
        for field in ("excluded_species", "additions", "level_overrides"):
            if not isinstance(pokemon.get(field), list):
                _issue(issues, "error", path, f"$.pokemon_spawns.{field}", "배열이어야 합니다.")
        excluded = pokemon.get("excluded_species", [])
        if isinstance(excluded, list):
            for index, species in enumerate(excluded):
                _resource_id(species, issues, path, f"$.pokemon_spawns.excluded_species[{index}]")
        for field in ("additions", "level_overrides"):
            entries = pokemon.get(field, [])
            if not isinstance(entries, list):
                continue
            for index, entry in enumerate(entries):
                base = f"$.pokemon_spawns.{field}[{index}]"
                if not isinstance(entry, dict):
                    _issue(issues, "error", path, base, "포켓몬과 레벨 범위 설정이 필요합니다.")
                    continue
                _resource_id(entry.get("species"), issues, path, f"{base}.species")
                minimum = entry.get("min_level")
                maximum = entry.get("max_level")
                if not isinstance(minimum, int) or isinstance(minimum, bool) or not 1 <= minimum <= 100:
                    _issue(issues, "error", path, f"{base}.min_level", "최소 레벨은 1~100 정수여야 합니다.")
                if not isinstance(maximum, int) or isinstance(maximum, bool) or not 1 <= maximum <= 100:
                    _issue(issues, "error", path, f"{base}.max_level", "최대 레벨은 1~100 정수여야 합니다.")
                if isinstance(minimum, int) and isinstance(maximum, int) and minimum > maximum:
                    _issue(issues, "error", path, base, "최소 레벨은 최대 레벨보다 클 수 없습니다.")
                if field == "additions" and "spawn_as_evolved" in entry and not isinstance(entry["spawn_as_evolved"], bool):
                    _issue(issues, "error", path, f"{base}.spawn_as_evolved", "진화본 출현 여부는 true 또는 false여야 합니다.")
                if field == "additions":
                    weight = entry.get("weight", 1)
                    if not isinstance(weight, int) or isinstance(weight, bool) or not 1 <= weight <= 10000:
                        _issue(issues, "error", path, f"{base}.weight", "가중치는 1~10000 정수여야 합니다.")
        _validate_route_encounter_pools(pokemon, issues, path, "$.pokemon_spawns")

    placements = data.get("npc_placements")
    if not isinstance(placements, list):
        _issue(issues, "error", path, "$.npc_placements", "NPC 배치 목록은 배열이어야 합니다.")
    else:
        seen_ids: set[str] = set()
        for index, placement in enumerate(placements):
            base = f"$.npc_placements[{index}]"
            if not isinstance(placement, dict):
                _issue(issues, "error", path, base, "NPC 배치는 객체여야 합니다.")
                continue
            placement_id = placement.get("id")
            if not isinstance(placement_id, str) or not CHOICE_ID.fullmatch(placement_id) or placement_id in seen_ids:
                _issue(issues, "error", path, f"{base}.id", "유일한 NPC 배치 ID가 필요합니다.")
            else:
                seen_ids.add(placement_id)
            _resource_id(placement.get("npc"), issues, path, f"{base}.npc")
            progress = placement.get("progress_percent")
            if not isinstance(progress, int) or isinstance(progress, bool) or not 0 <= progress <= 100:
                _issue(issues, "error", path, f"{base}.progress_percent", "길 진행률은 0~100 정수여야 합니다.")
            if placement.get("side") not in {"center", "left", "right"}:
                _issue(issues, "error", path, f"{base}.side", "NPC 위치는 center, left, right 중 하나여야 합니다.")
            offset = placement.get("offset_blocks")
            if not isinstance(offset, (int, float)) or isinstance(offset, bool) or not 0 <= offset <= 32:
                _issue(issues, "error", path, f"{base}.offset_blocks", "길 옆 거리는 0~32 숫자여야 합니다.")
            if placement.get("facing") not in {"along", "against"}:
                _issue(issues, "error", path, f"{base}.facing", "바라보는 방향은 along 또는 against여야 합니다.")
            chance = placement.get("spawn_chance")
            if not isinstance(chance, (int, float)) or isinstance(chance, bool) or not 0 <= chance <= 1:
                _issue(issues, "error", path, f"{base}.spawn_chance", "등장 확률은 0~1 숫자여야 합니다.")
            if placement.get("respawn_policy") not in {"always", "once_per_player"}:
                _issue(issues, "error", path, f"{base}.respawn_policy", "등장 정책은 always 또는 once_per_player여야 합니다.")
    automatic = data.get("automatic_npc_placement")
    if automatic is not None:
        if not isinstance(automatic, dict):
            _issue(issues, "error", path, "$.automatic_npc_placement", "길 자동 NPC 배치 설정은 객체여야 합니다.")
        else:
            _validate_trainer_population(automatic, issues, path, "$.automatic_npc_placement")
            if not isinstance(automatic.get("enabled"), bool):
                _issue(issues, "error", path, "$.automatic_npc_placement.enabled", "boolean이어야 합니다.")
            count = automatic.get("count")
            if not isinstance(count, int) or isinstance(count, bool) or not 0 <= count <= 32:
                _issue(issues, "error", path, "$.automatic_npc_placement.count", "자동 배치 수는 0~32 정수여야 합니다.")
    return route_id, issues


def _managed_directory(root: Path, category: str) -> Path:
    directories = {
        "trainers": root / "content" / "source",
        "battles": root / "content" / "battles",
        "routes": root / "content" / "routes",
        "settlements": root / "content" / "settlements",
        "caves": root / "content" / "caves",
        "dungeons": root / "content" / "dungeons",
        "dungeon-plans": root / "content" / "dungeon_plans",
        "dungeon-pieces": root / "content" / "dungeon_pieces",
        "underground-roads": root / "content" / "underground_roads",
        "forests": root / "content" / "forests",
    }
    if category not in directories:
        raise ValueError("지원하지 않는 문서 종류입니다.")
    return directories[category].resolve()


def validate_dungeon_piece_file(path: Path) -> tuple[str | None, list[Issue]]:
    """Validate an NBT-backed reusable dungeon piece."""
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
        return None, [Issue("error", path.as_posix(), "$", str(error))]
    if not isinstance(data, dict):
        return None, [Issue("error", path.as_posix(), "$", "던전 조각 문서는 객체여야 합니다.")]
    piece_id = data.get("piece_id")
    _resource_id(piece_id, issues, path, "$.piece_id")
    _resource_id(data.get("structure"), issues, path, "$.structure")
    if data.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "던전 조각 스키마 버전은 1이어야 합니다.")
    role = data.get("role")
    roles = {"start", "room", "corridor", "junction", "dead_end", "support", "treasure", "boss", "exit"}
    if role not in roles:
        _issue(issues, "error", path, "$.role", "지원하지 않는 조각 역할입니다.")
    size = data.get("size")
    size_valid = isinstance(size, list) and len(size) == 3 and all(
        isinstance(axis, int) and not isinstance(axis, bool) and 1 <= axis <= 128 for axis in size
    )
    if not size_valid:
        _issue(issues, "error", path, "$.size", "크기는 1~128인 X, Y, Z 정수 3개여야 합니다.")
    weight = data.get("weight")
    if not isinstance(weight, int) or isinstance(weight, bool) or not 1 <= weight <= 1000:
        _issue(issues, "error", path, "$.weight", "가중치는 1~1000 정수여야 합니다.")
    minimum_per_plan = data.get("min_per_plan", 0)
    maximum_per_plan = data.get("max_per_plan", 256)
    if not isinstance(minimum_per_plan, int) or isinstance(minimum_per_plan, bool) or not 0 <= minimum_per_plan <= 256:
        _issue(issues, "error", path, "$.min_per_plan", "계획당 최소 사용 횟수는 0~256 정수여야 합니다.")
    if not isinstance(maximum_per_plan, int) or isinstance(maximum_per_plan, bool) or not 1 <= maximum_per_plan <= 256:
        _issue(issues, "error", path, "$.max_per_plan", "계획당 최대 사용 횟수는 1~256 정수여야 합니다.")
    if isinstance(minimum_per_plan, int) and isinstance(maximum_per_plan, int) and minimum_per_plan > maximum_per_plan:
        _issue(issues, "error", path, "$.min_per_plan", "최소 사용 횟수는 최대보다 클 수 없습니다.")
    placement_scope = data.get("placement_scope", "any")
    if placement_scope not in {"any", "critical_path", "branch"}:
        _issue(issues, "error", path, "$.placement_scope", "any, critical_path, branch 중 하나여야 합니다.")
    forbidden_adjacent_tags = data.get("forbid_adjacent_tags", [])
    if not isinstance(forbidden_adjacent_tags, list):
        _issue(issues, "error", path, "$.forbid_adjacent_tags", "인접 금지 태그는 리소스 ID 배열이어야 합니다.")
    else:
        for index, tag in enumerate(forbidden_adjacent_tags):
            _resource_id(tag, issues, path, f"$.forbid_adjacent_tags[{index}]")
        if len(forbidden_adjacent_tags) != len(set(tag for tag in forbidden_adjacent_tags if isinstance(tag, str))):
            _issue(issues, "error", path, "$.forbid_adjacent_tags", "인접 금지 태그가 중복되었습니다.")
    if not isinstance(data.get("allow_rotation"), bool):
        _issue(issues, "error", path, "$.allow_rotation", "true 또는 false여야 합니다.")
    tags = data.get("tags")
    if not isinstance(tags, list):
        _issue(issues, "error", path, "$.tags", "태그는 리소스 ID 배열이어야 합니다.")
    else:
        for index, tag in enumerate(tags):
            _resource_id(tag, issues, path, f"$.tags[{index}]")
        if len(tags) != len(set(tag for tag in tags if isinstance(tag, str))):
            _issue(issues, "error", path, "$.tags", "태그가 중복되었습니다.")

    def local_position(value: Any, field: str) -> bool:
        valid = size_valid and isinstance(value, list) and len(value) == 3 and all(
            isinstance(axis, int) and not isinstance(axis, bool) and 0 <= axis < size[index]
            for index, axis in enumerate(value)
        )
        if not valid:
            _issue(issues, "error", path, field, "조각 크기 안의 X, Y, Z 정수 좌표가 필요합니다.")
        return valid

    connectors = data.get("connectors")
    seen_connectors: set[str] = set()
    if not isinstance(connectors, list) or not connectors:
        _issue(issues, "error", path, "$.connectors", "커넥터가 하나 이상 필요합니다.")
        connectors = connectors if isinstance(connectors, list) else []
    for index, connector in enumerate(connectors):
        base = f"$.connectors[{index}]"
        if not isinstance(connector, dict):
            _issue(issues, "error", path, base, "커넥터는 객체여야 합니다.")
            continue
        connector_id = connector.get("id")
        if not isinstance(connector_id, str) or not CHOICE_ID.fullmatch(connector_id):
            _issue(issues, "error", path, f"{base}.id", "소문자 커넥터 ID가 필요합니다.")
        elif connector_id in seen_connectors:
            _issue(issues, "error", path, f"{base}.id", "커넥터 ID가 중복되었습니다.")
        else:
            seen_connectors.add(connector_id)
        position_valid = local_position(connector.get("position"), f"{base}.position")
        facing = connector.get("facing")
        if facing not in {"north", "south", "east", "west"}:
            _issue(issues, "error", path, f"{base}.facing", "north, south, east, west 중 하나여야 합니다.")
        elif position_valid:
            x, _, z = connector["position"]
            on_boundary = {"north": z == 0, "south": z == size[2] - 1, "west": x == 0, "east": x == size[0] - 1}[facing]
            if not on_boundary:
                _issue(issues, "error", path, f"{base}.position", "커넥터는 바라보는 방향의 조각 경계에 있어야 합니다.")
        _resource_id(connector.get("socket"), issues, path, f"{base}.socket")
        connector_tags = connector.get("tags", [])
        if not isinstance(connector_tags, list):
            _issue(issues, "error", path, f"{base}.tags", "커넥터 태그는 리소스 ID 배열이어야 합니다.")
        else:
            for tag_index, tag in enumerate(connector_tags):
                _resource_id(tag, issues, path, f"{base}.tags[{tag_index}]")

    markers = data.get("markers")
    seen_markers: set[str] = set()
    marker_counts: dict[str, int] = {}
    marker_kinds = {"entry", "exit", "encounter", "boss", "loot", "healing_station", "gate", "checkpoint", "wild_spawn", "objective", "trace"}
    if not isinstance(markers, list):
        _issue(issues, "error", path, "$.markers", "마커 목록은 배열이어야 합니다.")
        markers = []
    for index, marker in enumerate(markers):
        base = f"$.markers[{index}]"
        if not isinstance(marker, dict):
            _issue(issues, "error", path, base, "마커는 객체여야 합니다.")
            continue
        marker_id = marker.get("id")
        if not isinstance(marker_id, str) or not CHOICE_ID.fullmatch(marker_id):
            _issue(issues, "error", path, f"{base}.id", "소문자 마커 ID가 필요합니다.")
        elif marker_id in seen_markers:
            _issue(issues, "error", path, f"{base}.id", "마커 ID가 중복되었습니다.")
        else:
            seen_markers.add(marker_id)
        kind = marker.get("kind")
        if kind not in marker_kinds:
            _issue(issues, "error", path, f"{base}.kind", "지원하지 않는 마커 종류입니다.")
        else:
            marker_counts[kind] = marker_counts.get(kind, 0) + 1
        local_position(marker.get("position"), f"{base}.position")
        reference = marker.get("reference")
        if reference is not None and (not isinstance(reference, str) or not reference.strip()):
            _issue(issues, "error", path, f"{base}.reference", "참조 값은 비어 있지 않은 문자열이어야 합니다.")
        blocked_connector = marker.get("connector")
        if blocked_connector is not None:
            if kind != "gate":
                _issue(issues, "error", path, f"{base}.connector", "차단 커넥터는 gate 마커에만 지정할 수 있습니다.")
            elif blocked_connector not in seen_connectors:
                _issue(issues, "error", path, f"{base}.connector", "조각에 존재하는 커넥터 ID가 필요합니다.")
    required_marker = {"start": "entry", "boss": "boss", "exit": "exit"}.get(role)
    if required_marker and marker_counts.get(required_marker, 0) != 1:
        _issue(issues, "error", path, "$.markers", f"{role} 역할은 {required_marker} 마커가 정확히 하나 필요합니다.")
    return piece_id if isinstance(piece_id, str) else None, issues


def validate_dungeon_file(path: Path) -> tuple[str | None, list[Issue]]:
    """Validate dungeon-wide authoring options edited by the web manager."""
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
        return None, [Issue("error", path.as_posix(), "$", str(error))]
    if not isinstance(data, dict):
        return None, [Issue("error", path.as_posix(), "$", "던전 문서는 객체여야 합니다.")]

    required = {
        "$schema", "schema_version", "dungeon_id", "display_name", "description",
        "preset", "entrances", "entry_ui", "difficulty", "eligibility",
        "multiplayer", "match", "battle", "terrain", "encounters",
        "random_encounters", "support", "gates", "loot", "rewards",
        "lifecycle", "completion",
    }
    for key in sorted(required - data.keys()):
        _issue(issues, "error", path, f"$.{key}", "필수 던전 설정입니다.")

    dungeon_id = data.get("dungeon_id")
    _resource_id(dungeon_id, issues, path, "$.dungeon_id")
    _resource_id(data.get("preset"), issues, path, "$.preset")
    for field in ("display_name", "description"):
        value = data.get(field)
        if not isinstance(value, dict) or not isinstance(value.get("ko_kr"), str) or not value["ko_kr"].strip():
            _issue(issues, "error", path, f"$.{field}.ko_kr", "한국어 문구가 필요합니다.")

    def object_at(name: str) -> dict[str, Any]:
        value = data.get(name)
        if not isinstance(value, dict):
            _issue(issues, "error", path, f"$.{name}", "객체여야 합니다.")
            return {}
        return value

    def integer(value: Any, minimum: int, maximum: int, field: str) -> None:
        if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
            _issue(issues, "error", path, field, f"{minimum}~{maximum} 정수여야 합니다.")

    difficulty = object_at("difficulty")
    for key in ("recommended_min", "recommended_max", "internal_min", "internal_max"):
        integer(difficulty.get(key), 1, 100, f"$.difficulty.{key}")
    if isinstance(difficulty.get("recommended_min"), int) and isinstance(difficulty.get("recommended_max"), int) and difficulty["recommended_min"] > difficulty["recommended_max"]:
        _issue(issues, "error", path, "$.difficulty", "권장 최소 레벨은 최대 레벨보다 클 수 없습니다.")
    if isinstance(difficulty.get("internal_min"), int) and isinstance(difficulty.get("internal_max"), int) and difficulty["internal_min"] > difficulty["internal_max"]:
        _issue(issues, "error", path, "$.difficulty", "내부 최소 레벨은 최대 레벨보다 클 수 없습니다.")

    eligibility = object_at("eligibility")
    integer(eligibility.get("minimum_party_size"), 1, 6, "$.eligibility.minimum_party_size")
    integer(eligibility.get("maximum_party_size"), 1, 6, "$.eligibility.maximum_party_size")
    if eligibility.get("level_measure") not in {"average", "highest"}:
        _issue(issues, "error", path, "$.eligibility.level_measure", "average 또는 highest여야 합니다.")
    if eligibility.get("recommended_level_policy") not in {"ignore", "warn", "enforce"}:
        _issue(issues, "error", path, "$.eligibility.recommended_level_policy", "ignore, warn, enforce 중 하나여야 합니다.")

    multiplayer = object_at("multiplayer")
    mode = multiplayer.get("mode")
    if mode not in {"solo", "cooperative", "independent"}:
        _issue(issues, "error", path, "$.multiplayer.mode", "solo, cooperative, independent 중 하나여야 합니다.")
    integer(multiplayer.get("min_size"), 1, 4, "$.multiplayer.min_size")
    integer(multiplayer.get("max_size"), 1, 4, "$.multiplayer.max_size")
    if isinstance(multiplayer.get("min_size"), int) and isinstance(multiplayer.get("max_size"), int) and multiplayer["min_size"] > multiplayer["max_size"]:
        _issue(issues, "error", path, "$.multiplayer", "최소 인원은 최대 인원보다 클 수 없습니다.")
    if mode == "solo" and (multiplayer.get("min_size") != 1 or multiplayer.get("max_size") != 1):
        _issue(issues, "error", path, "$.multiplayer", "1인 던전의 인원 범위는 1~1이어야 합니다.")
    if mode == "cooperative":
        if multiplayer.get("battle_join") not in {"summon_all", "require_nearby", "initiator_only"}:
            _issue(issues, "error", path, "$.multiplayer.battle_join", "협력 전투 합류 방식을 선택해야 합니다.")
        tether = multiplayer.get("tether")
        if not isinstance(tether, dict):
            _issue(issues, "error", path, "$.multiplayer.tether", "협력 던전에는 거리 제한 설정이 필요합니다.")
        else:
            integer(tether.get("warn_distance"), 1, 255, "$.multiplayer.tether.warn_distance")
            integer(tether.get("max_distance"), 2, 256, "$.multiplayer.tether.max_distance")
            if isinstance(tether.get("warn_distance"), int) and isinstance(tether.get("max_distance"), int) and tether["warn_distance"] >= tether["max_distance"]:
                _issue(issues, "error", path, "$.multiplayer.tether", "경고 거리는 최대 거리보다 작아야 합니다.")

    match = object_at("match")
    integer(match.get("required_players"), 1, 4, "$.match.required_players")
    integer(match.get("timeout_seconds"), 1, 3600, "$.match.timeout_seconds")
    integer(match.get("stay_radius"), 1, 64, "$.match.stay_radius")
    if match.get("on_timeout") not in {"cancel", "keep_waiting"}:
        _issue(issues, "error", path, "$.match.on_timeout", "cancel 또는 keep_waiting이어야 합니다.")
    if isinstance(match.get("required_players"), int) and isinstance(multiplayer.get("min_size"), int) and match["required_players"] < multiplayer["min_size"]:
        _issue(issues, "error", path, "$.match.required_players", "매칭 인원은 던전 최소 인원 이상이어야 합니다.")

    battle = object_at("battle")
    for key in ("allow_flee", "allow_capture", "allow_items", "allow_escape_actions"):
        if not isinstance(battle.get(key), bool):
            _issue(issues, "error", path, f"$.battle.{key}", "true 또는 false여야 합니다.")

    terrain = object_at("terrain")
    terrain_mode = terrain.get("mode")
    if terrain_mode not in {"fixed_template", "nbt_pieces", "procedural_cave", "hybrid"}:
        _issue(issues, "error", path, "$.terrain.mode", "지원하지 않는 지형 방식입니다.")
    if terrain_mode == "fixed_template":
        _resource_id(terrain.get("template"), issues, path, "$.terrain.template")
        for key in ("entry_position", "exit_position"):
            value = terrain.get(key)
            if not isinstance(value, list) or len(value) != 3 or any(not isinstance(axis, int) or isinstance(axis, bool) for axis in value):
                _issue(issues, "error", path, f"$.terrain.{key}", "X, Y, Z 정수 좌표 3개가 필요합니다.")
    if terrain_mode in {"nbt_pieces", "procedural_cave", "hybrid"}:
        bounds = terrain.get("bounds")
        if not isinstance(bounds, list) or len(bounds) != 3 or any(not isinstance(axis, int) or isinstance(axis, bool) or axis < 1 for axis in bounds):
            _issue(issues, "error", path, "$.terrain.bounds", "양수인 X, Y, Z 크기 3개가 필요합니다.")
    if terrain_mode == "hybrid":
        _resource_id(terrain.get("piece_pool"), issues, path, "$.terrain.piece_pool")
    if terrain_mode in {"procedural_cave", "hybrid"}:
        if terrain.get("cave_generator") != "minecraft_worldgen":
            _issue(issues, "error", path, "$.terrain.cave_generator", "동굴형 던전은 minecraft_worldgen 동굴 생성기가 필요합니다.")
        if terrain.get("style") not in {"rock", "dripstone", "crystal", "lush", "ice", "lava"}:
            _issue(issues, "error", path, "$.terrain.style", "일반 동굴과 같은 동굴 스타일이 필요합니다.")
        if not isinstance(terrain.get("requires_flash"), bool):
            _issue(issues, "error", path, "$.terrain.requires_flash", "플래시 필요 여부가 필요합니다.")
        generator = terrain.get("generator")
        if not isinstance(generator, dict):
            _issue(issues, "error", path, "$.terrain.generator", "일반 동굴 생성 설정이 필요합니다.")
        else:
            if generator.get("layout") != "natural_network":
                _issue(issues, "error", path, "$.terrain.generator.layout", "일반 동굴의 natural_network 생성 방식을 사용해야 합니다.")
            for key in ("seed_salt", "main_rooms", "branch_count", "vertical_range", "water_level", "water_depth"):
                if not isinstance(generator.get(key), int) or isinstance(generator.get(key), bool):
                    _issue(issues, "error", path, f"$.terrain.generator.{key}", "일반 동굴과 같은 정수 생성값이 필요합니다.")
            for key in ("loop_chance", "surface_roughness"):
                value = generator.get(key)
                if not isinstance(value, (int, float)) or isinstance(value, bool) or not 0 <= value <= 1:
                    _issue(issues, "error", path, f"$.terrain.generator.{key}", "0~1 생성값이 필요합니다.")
            for key in ("room_radius", "tunnel_radius"):
                radius = generator.get(key)
                if not isinstance(radius, dict) or not all(isinstance(radius.get(edge), (int, float)) and not isinstance(radius.get(edge), bool) for edge in ("min", "max")):
                    _issue(issues, "error", path, f"$.terrain.generator.{key}", "일반 동굴과 같은 최소·최대 반경이 필요합니다.")

    if terrain_mode != "fixed_template":
        plan = object_at("plan")
        if plan.get("mode") not in {"authored", "runtime", "authored_pool"}:
            _issue(issues, "error", path, "$.plan.mode", "지원하지 않는 계획 방식입니다.")
        if plan.get("seed_policy") not in {"fixed", "random_per_run", "daily", "weekly", "match", "player"}:
            _issue(issues, "error", path, "$.plan.seed_policy", "지원하지 않는 시드 정책입니다.")
        if plan.get("fallback") not in {"reject_entry", "use_last_valid", "use_fallback_plan"}:
            _issue(issues, "error", path, "$.plan.fallback", "지원하지 않는 실패 대체 방식입니다.")
        if plan.get("mode") in {"authored", "authored_pool"} and not isinstance(plan.get("plan_ids"), list):
            _issue(issues, "error", path, "$.plan.plan_ids", "게시형 계획 ID가 하나 이상 필요합니다.")
        if terrain_mode in {"procedural_cave", "hybrid"} and plan.get("mode") != "runtime":
            _issue(issues, "error", path, "$.plan.mode", "절차 동굴과 혼합형은 현재 입장 시 자동 생성 계획만 지원합니다.")
        if terrain_mode == "nbt_pieces":
            layout = object_at("layout")
            if layout.get("mode") not in {"fixed", "critical_path_branches", "maze", "rooms_and_corridors"}:
                _issue(issues, "error", path, "$.layout.mode", "지원하지 않는 경로 형태입니다.")

    completion = object_at("completion")
    if not isinstance(completion.get("repeatable"), bool):
        _issue(issues, "error", path, "$.completion.repeatable", "true 또는 false여야 합니다.")
    if completion.get("return_trigger") not in {"automatic", "clear_exit"}:
        _issue(issues, "error", path, "$.completion.return_trigger", "automatic 또는 clear_exit여야 합니다.")
    rewards = object_at("rewards")
    if completion.get("repeatable") is True and not isinstance(rewards.get("repeat_table"), str):
        _issue(issues, "error", path, "$.rewards.repeat_table", "반복 클리어 던전에는 반복 보상 테이블이 필요합니다.")
    lifecycle = object_at("lifecycle")
    if lifecycle.get("resume_mode", "keep_until_timeout") not in {"full_reset", "checkpoint", "keep_until_timeout"}:
        _issue(issues, "error", path, "$.lifecycle.resume_mode", "지원하지 않는 재개 방식입니다.")

    def position(value: Any, field: str) -> None:
        if not isinstance(value, list) or len(value) != 3 or any(
            not isinstance(axis, int) or isinstance(axis, bool) for axis in value
        ):
            _issue(issues, "error", path, field, "X, Y, Z 정수 좌표 3개가 필요합니다.")

    def local_id(value: Any, field: str, seen: set[str]) -> None:
        if not isinstance(value, str) or not CHOICE_ID.fullmatch(value):
            _issue(issues, "error", path, field, "소문자 ID가 필요합니다.")
        elif value in seen:
            _issue(issues, "error", path, field, "같은 종류 안에서 ID가 중복되었습니다.")
        else:
            seen.add(value)

    seen_encounters: set[str] = set()
    encounters = data.get("encounters")
    if not isinstance(encounters, list) or not encounters:
        _issue(issues, "error", path, "$.encounters", "고정 조우가 하나 이상 필요합니다.")
    else:
        for index, encounter in enumerate(encounters):
            base = f"$.encounters[{index}]"
            if not isinstance(encounter, dict):
                _issue(issues, "error", path, base, "고정 조우는 객체여야 합니다.")
                continue
            local_id(encounter.get("id"), f"{base}.id", seen_encounters)
            if "position" in encounter:
                position(encounter.get("position"), f"{base}.position")
            elif terrain_mode not in {"nbt_pieces", "procedural_cave", "hybrid"}:
                _issue(issues, "error", path, f"{base}.position", "자동 생성 지형이 아닌 던전은 고정 좌표가 필요합니다.")
            if not isinstance(encounter.get("boss"), bool):
                _issue(issues, "error", path, f"{base}.boss", "true 또는 false여야 합니다.")
            requirements = encounter.get("requires")
            if not isinstance(requirements, list) or any(not isinstance(value, str) or not CHOICE_ID.fullmatch(value) for value in requirements):
                _issue(issues, "error", path, f"{base}.requires", "선행 조우 ID 배열이어야 합니다.")
            if encounter.get("kind", "trainer") == "wild_pokemon":
                pokemon = encounter.get("pokemon")
                if not isinstance(pokemon, dict):
                    _issue(issues, "error", path, f"{base}.pokemon", "야생 포켓몬 설정이 필요합니다.")
                else:
                    _resource_id(pokemon.get("species"), issues, path, f"{base}.pokemon.species")
                    integer(pokemon.get("level"), 1, 100, f"{base}.pokemon.level")
                    if not isinstance(pokemon.get("catchable"), bool):
                        _issue(issues, "error", path, f"{base}.pokemon.catchable", "true 또는 false여야 합니다.")
            else:
                for field in ("npcs",):
                    values = encounter.get(field)
                    if not isinstance(values, list) or not 1 <= len(values) <= 2:
                        _issue(issues, "error", path, f"{base}.{field}", "리소스 ID가 1~2개 필요합니다.")
                    else:
                        for value_index, value in enumerate(values):
                            _resource_id(value, issues, path, f"{base}.{field}[{value_index}]")
                opponents = encounter.get("opponents")
                generated = encounter.get("trainer_generation")
                if opponents is not None and generated is not None:
                    _issue(issues, "error", path, base, "배틀 프리셋과 즉석 트레이너 생성은 동시에 사용할 수 없습니다.")
                elif opponents is None and generated is None:
                    _issue(issues, "error", path, base, "배틀 프리셋 또는 즉석 트레이너 생성 설정이 필요합니다.")
                elif opponents is not None:
                    if not isinstance(opponents, list) or not 1 <= len(opponents) <= 2:
                        _issue(issues, "error", path, f"{base}.opponents", "배틀 프리셋 ID가 1~2개 필요합니다.")
                    else:
                        for value_index, value in enumerate(opponents):
                            _resource_id(value, issues, path, f"{base}.opponents[{value_index}]")
                elif not isinstance(generated, dict):
                    _issue(issues, "error", path, f"{base}.trainer_generation", "즉석 트레이너 생성 설정은 객체여야 합니다.")
                else:
                    pool = generated.get("pokemon_pool")
                    if not isinstance(pool, list) or not pool:
                        _issue(issues, "error", path, f"{base}.trainer_generation.pokemon_pool", "포켓몬 풀이 하나 이상 필요합니다.")
                    else:
                        seen_species: set[str] = set()
                        for pool_index, candidate in enumerate(pool):
                            candidate_path = f"{base}.trainer_generation.pokemon_pool[{pool_index}]"
                            if not isinstance(candidate, dict):
                                _issue(issues, "error", path, candidate_path, "포켓몬 풀 항목은 객체여야 합니다.")
                                continue
                            _resource_id(candidate.get("species"), issues, path, f"{candidate_path}.species")
                            if isinstance(candidate.get("species"), str):
                                if candidate["species"] in seen_species:
                                    _issue(issues, "error", path, f"{candidate_path}.species", "포켓몬 풀에서 같은 종을 중복 선언할 수 없습니다. 가중치를 조절하세요.")
                                seen_species.add(candidate["species"])
                            integer(candidate.get("weight"), 1, 1000, f"{candidate_path}.weight")
                    team_size = generated.get("team_size")
                    if not isinstance(team_size, list) or len(team_size) != 2:
                        _issue(issues, "error", path, f"{base}.trainer_generation.team_size", "최소·최대 팀 크기 두 값이 필요합니다.")
                    else:
                        integer(team_size[0], 1, 6, f"{base}.trainer_generation.team_size[0]")
                        integer(team_size[1], 1, 6, f"{base}.trainer_generation.team_size[1]")
                        if all(isinstance(value, int) and not isinstance(value, bool) for value in team_size) and team_size[0] > team_size[1]:
                            _issue(issues, "error", path, f"{base}.trainer_generation.team_size", "최소 팀 크기는 최대보다 클 수 없습니다.")
                    if not isinstance(generated.get("allow_duplicates"), bool):
                        _issue(issues, "error", path, f"{base}.trainer_generation.allow_duplicates", "중복 허용 여부가 필요합니다.")
                    elif generated.get("allow_duplicates") is False and isinstance(pool, list) and isinstance(team_size, list) and len(team_size) == 2 and isinstance(team_size[1], int):
                        unique_species = {
                            candidate.get("species") for candidate in pool
                            if isinstance(candidate, dict) and isinstance(candidate.get("species"), str)
                        }
                        if len(unique_species) < team_size[1]:
                            _issue(issues, "error", path, f"{base}.trainer_generation.team_size", "중복을 금지하면 포켓몬 풀의 고유 종 수가 최대 팀 크기 이상이어야 합니다.")
                    for field in ("battle_start_lines", "battle_end_lines"):
                        lines = generated.get(field)
                        if not isinstance(lines, list) or not lines or any(not isinstance(line, str) or not line.strip() for line in lines):
                            _issue(issues, "error", path, f"{base}.trainer_generation.{field}", "비어 있지 않은 대사 목록이 필요합니다.")

    random_encounters = object_at("random_encounters")
    for key, minimum, maximum in (
        ("minimum_distance", 1, 128), ("maximum_distance", 1, 128),
        ("max_active", 1, 16), ("spawn_interval_ticks", 20, 12000),
    ):
        integer(random_encounters.get(key), minimum, maximum, f"$.random_encounters.{key}")
    if isinstance(random_encounters.get("minimum_distance"), int) and isinstance(random_encounters.get("maximum_distance"), int) and random_encounters["minimum_distance"] > random_encounters["maximum_distance"]:
        _issue(issues, "error", path, "$.random_encounters", "최소 스폰 거리는 최대 스폰 거리보다 클 수 없습니다.")
    spawn_bounds = random_encounters.get("spawn_bounds")
    if not isinstance(spawn_bounds, dict):
        _issue(issues, "error", path, "$.random_encounters.spawn_bounds", "스폰 범위가 필요합니다.")
    else:
        position(spawn_bounds.get("min"), "$.random_encounters.spawn_bounds.min")
        position(spawn_bounds.get("max"), "$.random_encounters.spawn_bounds.max")
    additions = random_encounters.get("additions")
    if not isinstance(additions, list):
        _issue(issues, "error", path, "$.random_encounters.additions", "랜덤 출현 목록은 배열이어야 합니다.")
    else:
        for index, pokemon in enumerate(additions):
            base = f"$.random_encounters.additions[{index}]"
            if not isinstance(pokemon, dict):
                _issue(issues, "error", path, base, "출현 포켓몬은 객체여야 합니다.")
                continue
            _resource_id(pokemon.get("species"), issues, path, f"{base}.species")
            integer(pokemon.get("min_level"), 1, 100, f"{base}.min_level")
            integer(pokemon.get("max_level"), 1, 100, f"{base}.max_level")
            integer(pokemon.get("weight"), 1, 1000, f"{base}.weight")
            if isinstance(pokemon.get("min_level"), int) and isinstance(pokemon.get("max_level"), int) and pokemon["min_level"] > pokemon["max_level"]:
                _issue(issues, "error", path, base, "최소 레벨은 최대 레벨보다 클 수 없습니다.")

    objective_ids: set[str] = set()
    objectives = data.get("objectives", [])
    if not isinstance(objectives, list):
        _issue(issues, "error", path, "$.objectives", "던전 목표 목록은 배열이어야 합니다.")
        objectives = []
    for index, objective in enumerate(objectives):
        base = f"$.objectives[{index}]"
        if not isinstance(objective, dict):
            _issue(issues, "error", path, base, "던전 목표는 객체여야 합니다.")
            continue
        local_id(objective.get("id"), f"{base}.id", objective_ids)
        if objective.get("kind") not in {"switch", "investigate"}:
            _issue(issues, "error", path, f"{base}.kind", "목표 종류는 switch 또는 investigate여야 합니다.")
        placement = objective.get("placement", "fixed")
        if placement not in {"fixed", "marker"}:
            _issue(issues, "error", path, f"{base}.placement", "목표 배치는 fixed 또는 marker여야 합니다.")
        if placement == "marker":
            if terrain_mode not in {"nbt_pieces", "procedural_cave", "hybrid"}:
                _issue(issues, "error", path, f"{base}.placement", "자동 배치 목표는 생성형 던전에서만 사용할 수 있습니다.")
        else:
            position(objective.get("position"), f"{base}.position")
        _resource_id(objective.get("block"), issues, path, f"{base}.block")
        integer(objective.get("activation_radius"), 1, 8, f"{base}.activation_radius")

    content_groups = (
        ("healing_stations", data.get("support", {}).get("healing_stations", []) if isinstance(data.get("support"), dict) else []),
        ("checkpoints", data.get("support", {}).get("checkpoints", []) if isinstance(data.get("support"), dict) else []),
        ("containers", data.get("loot", {}).get("containers", []) if isinstance(data.get("loot"), dict) else []),
        ("gates", data.get("gates")),
    )
    for group_name, entries in content_groups:
        if not isinstance(entries, list):
            _issue(issues, "error", path, f"$.{group_name}", "배치 목록은 배열이어야 합니다.")
            continue
        seen_ids: set[str] = set()
        for index, entry in enumerate(entries):
            base = f"$.{group_name}[{index}]"
            if not isinstance(entry, dict):
                _issue(issues, "error", path, base, "배치 항목은 객체여야 합니다.")
                continue
            local_id(entry.get("id"), f"{base}.id", seen_ids)
            if group_name == "gates":
                placement = entry.get("placement", "fixed")
                if placement not in {"fixed", "marker"}:
                    _issue(issues, "error", path, f"{base}.placement", "관문 배치는 fixed 또는 marker여야 합니다.")
                if placement == "marker" and terrain_mode not in {"nbt_pieces", "hybrid"}:
                    _issue(issues, "error", path, f"{base}.placement", "NBT 마커 관문은 NBT 조각 또는 혼합 던전에서만 사용할 수 있습니다.")
                position(entry.get("min"), f"{base}.min")
                position(entry.get("max"), f"{base}.max")
                minimum = entry.get("min")
                maximum = entry.get("max")
                if isinstance(minimum, list) and isinstance(maximum, list) and len(minimum) == len(maximum) == 3 and all(isinstance(value, int) for value in minimum + maximum):
                    if any(minimum[axis] > maximum[axis] for axis in range(3)):
                        _issue(issues, "error", path, base, "관문 최소 좌표는 최대 좌표보다 클 수 없습니다.")
                    volume = (maximum[0] - minimum[0] + 1) * (maximum[1] - minimum[1] + 1) * (maximum[2] - minimum[2] + 1)
                    if volume > 256:
                        _issue(issues, "error", path, base, "관문 영역은 256블록을 초과할 수 없습니다.")
                    if placement == "fixed" and any(value < 0 for value in minimum):
                        _issue(issues, "error", path, f"{base}.min", "고정 관문 좌표는 음수일 수 없습니다.")
                legacy_requirements = entry.get("requires", [])
                typed_requirements = entry.get("requirements", [])
                if not isinstance(legacy_requirements, list):
                    _issue(issues, "error", path, f"{base}.requires", "필수 조우 ID 배열이어야 합니다.")
                    legacy_requirements = []
                if not isinstance(typed_requirements, list):
                    _issue(issues, "error", path, f"{base}.requirements", "관문 조건 배열이어야 합니다.")
                    typed_requirements = []
                if not legacy_requirements and not typed_requirements:
                    _issue(issues, "error", path, base, "관문 해제 조건이 하나 이상 필요합니다.")
                requirement_keys: set[str] = set()
                for requirement_index, encounter_id in enumerate(legacy_requirements):
                    requirement_path = f"{base}.requires[{requirement_index}]"
                    if encounter_id not in seen_encounters:
                        _issue(issues, "error", path, requirement_path, "존재하는 조우 ID가 필요합니다.")
                    key = f"encounter:{encounter_id}"
                    if key in requirement_keys:
                        _issue(issues, "error", path, requirement_path, "같은 관문 조건이 중복되었습니다.")
                    requirement_keys.add(key)
                for requirement_index, requirement in enumerate(typed_requirements):
                    requirement_path = f"{base}.requirements[{requirement_index}]"
                    if not isinstance(requirement, dict):
                        _issue(issues, "error", path, requirement_path, "관문 조건은 객체여야 합니다.")
                        continue
                    requirement_type = requirement.get("type")
                    if requirement_type not in {"encounter", "objective", "item"}:
                        _issue(issues, "error", path, f"{requirement_path}.type", "조건 종류는 encounter, objective, item 중 하나여야 합니다.")
                        continue
                    if requirement_type == "item":
                        _resource_id(requirement.get("item"), issues, path, f"{requirement_path}.item")
                        integer(requirement.get("count"), 1, 64, f"{requirement_path}.count")
                        if not isinstance(requirement.get("consume"), bool):
                            _issue(issues, "error", path, f"{requirement_path}.consume", "아이템 소비 여부는 true 또는 false여야 합니다.")
                        key = f"item:{requirement.get('item')}"
                    else:
                        reference = requirement.get("id")
                        known_ids = seen_encounters if requirement_type == "encounter" else objective_ids
                        if reference not in known_ids:
                            _issue(issues, "error", path, f"{requirement_path}.id", "존재하는 조우 또는 목표 ID가 필요합니다.")
                        key = f"{requirement_type}:{reference}"
                    if key in requirement_keys:
                        _issue(issues, "error", path, requirement_path, "같은 관문 조건이 중복되었습니다.")
                    requirement_keys.add(key)
            else:
                if group_name != "containers" or "position" in entry:
                    position(entry.get("position"), f"{base}.position")
                elif terrain_mode not in {"nbt_pieces", "procedural_cave", "hybrid"}:
                    _issue(issues, "error", path, f"{base}.position", "자동 생성 지형이 아닌 던전은 고정 좌표가 필요합니다.")
    return dungeon_id if isinstance(dungeon_id, str) else None, issues


def validate_dungeon_plan_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
        return None, [Issue("error", path.as_posix(), "$", str(error))]
    if not isinstance(data, dict):
        return None, [Issue("error", path.as_posix(), "$", "던전 계획 문서는 객체여야 합니다.")]
    plan_id = data.get("plan_id")
    _resource_id(plan_id, issues, path, "$.plan_id")
    if data.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "던전 계획 스키마 버전은 1이어야 합니다.")

    def position(value: Any, field: str, positive: bool = False) -> bool:
        valid = isinstance(value, list) and len(value) == 3 and all(
            isinstance(axis, int) and not isinstance(axis, bool) and (not positive or axis >= 1)
            for axis in value
        )
        if not valid:
            _issue(issues, "error", path, field, "정수 좌표 3개가 필요합니다." if not positive else "양수인 X, Y, Z 크기 3개가 필요합니다.")
        return valid

    position(data.get("bounds"), "$.bounds", positive=True)
    placements = data.get("placements")
    if not isinstance(placements, list) or len(placements) < 3:
        _issue(issues, "error", path, "$.placements", "게시형 계획에는 조각이 3개 이상 필요합니다.")
        placements = placements if isinstance(placements, list) else []
    for index, placement in enumerate(placements):
        base = f"$.placements[{index}]"
        if not isinstance(placement, dict):
            _issue(issues, "error", path, base, "조각 배치는 객체여야 합니다.")
            continue
        _resource_id(placement.get("piece_id"), issues, path, f"{base}.piece_id")
        position(placement.get("origin"), f"{base}.origin")
        if placement.get("rotation") not in {"none", "clockwise_90", "clockwise_180", "counterclockwise_90"}:
            _issue(issues, "error", path, f"{base}.rotation", "지원하지 않는 회전입니다.")
        if not isinstance(placement.get("critical_path"), bool):
            _issue(issues, "error", path, f"{base}.critical_path", "true 또는 false여야 합니다.")

    links = data.get("links")
    if not isinstance(links, list) or len(links) < 2:
        _issue(issues, "error", path, "$.links", "게시형 계획에는 연결이 2개 이상 필요합니다.")
        links = links if isinstance(links, list) else []
    seen_links: set[tuple[int, str, int, str]] = set()
    for index, link in enumerate(links):
        base = f"$.links[{index}]"
        if not isinstance(link, dict):
            _issue(issues, "error", path, base, "조각 연결은 객체여야 합니다.")
            continue
        for field in ("from_index", "to_index"):
            value = link.get(field)
            if not isinstance(value, int) or isinstance(value, bool) or not 0 <= value < len(placements):
                _issue(issues, "error", path, f"{base}.{field}", "존재하는 조각 번호여야 합니다.")
        for field in ("from_connector", "to_connector"):
            value = link.get(field)
            if not isinstance(value, str) or not CHOICE_ID.fullmatch(value):
                _issue(issues, "error", path, f"{base}.{field}", "커넥터 ID가 필요합니다.")
        if link.get("from_index") == link.get("to_index"):
            _issue(issues, "error", path, base, "같은 조각끼리는 연결할 수 없습니다.")
        if not isinstance(link.get("critical_path"), bool):
            _issue(issues, "error", path, f"{base}.critical_path", "true 또는 false여야 합니다.")
        key = (link.get("from_index"), link.get("from_connector"), link.get("to_index"), link.get("to_connector"))
        reverse = (key[2], key[3], key[0], key[1])
        if key in seen_links or reverse in seen_links:
            _issue(issues, "error", path, base, "같은 커넥터 연결이 중복되었습니다.")
        seen_links.add(key)
    return plan_id if isinstance(plan_id, str) else None, issues


def validate_dungeon_plan_document(
    root: Path, data: Any, path: Path,
) -> list[Issue]:
    """Validate authored placements against the project's piece catalog."""
    issues: list[Issue] = []
    if not isinstance(data, dict):
        return issues
    pieces: dict[str, dict[str, Any]] = {}
    piece_directory = root / "content" / "dungeon_pieces"
    for piece_path in sorted(piece_directory.rglob("*.json")) if piece_directory.is_dir() else []:
        try:
            piece = load_json(piece_path)
        except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError):
            continue
        if isinstance(piece, dict) and isinstance(piece.get("piece_id"), str):
            pieces[piece["piece_id"]] = piece
    placements = data.get("placements") if isinstance(data.get("placements"), list) else []
    bounds = data.get("bounds") if isinstance(data.get("bounds"), list) and len(data.get("bounds")) == 3 else None
    occupied: list[tuple[int, list[int], list[int]]] = []
    piece_usage: dict[str, int] = {}
    for index, placement in enumerate(placements):
        if not isinstance(placement, dict):
            continue
        piece_id = placement.get("piece_id")
        piece = pieces.get(piece_id)
        if piece is None:
            _issue(issues, "error", path, f"$.placements[{index}].piece_id", f"등록되지 않은 던전 조각입니다: {piece_id}")
            continue
        piece_usage[piece_id] = piece_usage.get(piece_id, 0) + 1
        placement_scope = piece.get("placement_scope", "any")
        critical_path = placement.get("critical_path") is True
        if placement_scope == "critical_path" and not critical_path:
            _issue(issues, "error", path, f"$.placements[{index}].critical_path", "이 조각은 주 경로에만 배치할 수 있습니다.")
        if placement_scope == "branch" and critical_path:
            _issue(issues, "error", path, f"$.placements[{index}].critical_path", "이 조각은 곁가지에만 배치할 수 있습니다.")
        size = piece.get("size")
        origin = placement.get("origin")
        rotation = placement.get("rotation")
        if not isinstance(size, list) or len(size) != 3 or not isinstance(origin, list) or len(origin) != 3:
            continue
        minimum = list(origin)
        transformed = list(size)
        if rotation == "clockwise_90":
            minimum[0] -= size[2] - 1
            transformed = [size[2], size[1], size[0]]
        elif rotation == "clockwise_180":
            minimum[0] -= size[0] - 1
            minimum[2] -= size[2] - 1
        elif rotation == "counterclockwise_90":
            minimum[2] -= size[0] - 1
            transformed = [size[2], size[1], size[0]]
        maximum = [minimum[axis] + transformed[axis] for axis in range(3)]
        if bounds and any(minimum[axis] < 0 or maximum[axis] > bounds[axis] for axis in range(3)):
            _issue(issues, "error", path, f"$.placements[{index}].origin", "회전된 조각이 계획 영역 밖으로 나갑니다.")
        for other_index, other_minimum, other_maximum in occupied:
            if all(minimum[axis] < other_maximum[axis] and maximum[axis] > other_minimum[axis] for axis in range(3)):
                _issue(issues, "error", path, f"$.placements[{index}].origin", f"{other_index}번 조각과 영역이 겹칩니다.")
        occupied.append((index, minimum, maximum))

    used_pool_tags = {
        tag
        for piece_id in piece_usage
        for tag in pieces.get(piece_id, {}).get("tags", [])
        if isinstance(tag, str) and ":dungeon_pool/" in tag
    }
    for piece_id, piece in pieces.items():
        piece_pool_tags = {
            tag for tag in piece.get("tags", [])
            if isinstance(tag, str) and ":dungeon_pool/" in tag
        }
        if used_pool_tags and not used_pool_tags.intersection(piece_pool_tags):
            continue
        if not used_pool_tags and piece_id not in piece_usage:
            continue
        minimum = piece.get("min_per_plan", 0)
        maximum = piece.get("max_per_plan", 256)
        count = piece_usage.get(piece_id, 0)
        if isinstance(minimum, int) and isinstance(maximum, int) and not minimum <= count <= maximum:
            _issue(issues, "error", path, "$.placements", f"{piece_id} 조각 사용 횟수 {count}회가 허용 범위 {minimum}~{maximum}회를 벗어납니다.")

    links = data.get("links") if isinstance(data.get("links"), list) else []
    for index, link in enumerate(links):
        if not isinstance(link, dict):
            continue
        endpoints = (("from", link.get("from_index"), link.get("from_connector")), ("to", link.get("to_index"), link.get("to_connector")))
        endpoint_pieces: list[dict[str, Any]] = []
        sockets: list[str] = []
        for side, placement_index, connector_id in endpoints:
            if not isinstance(placement_index, int) or not 0 <= placement_index < len(placements) or not isinstance(placements[placement_index], dict):
                continue
            piece = pieces.get(placements[placement_index].get("piece_id"))
            if isinstance(piece, dict):
                endpoint_pieces.append(piece)
            connectors = piece.get("connectors", []) if isinstance(piece, dict) else []
            connector = next((value for value in connectors if isinstance(value, dict) and value.get("id") == connector_id), None)
            if connector is None:
                _issue(issues, "error", path, f"$.links[{index}].{side}_connector", f"{placement_index}번 조각에 없는 커넥터입니다.")
            elif isinstance(connector.get("socket"), str):
                sockets.append(connector["socket"])
        if len(sockets) == 2 and sockets[0] != sockets[1]:
            _issue(issues, "error", path, f"$.links[{index}]", "서로 다른 소켓 종류의 커넥터는 연결할 수 없습니다.")
        if len(endpoint_pieces) == 2:
            first, second = endpoint_pieces
            first_forbidden = {tag for tag in first.get("forbid_adjacent_tags", []) if isinstance(tag, str)}
            second_forbidden = {tag for tag in second.get("forbid_adjacent_tags", []) if isinstance(tag, str)}
            first_tags = {tag for tag in first.get("tags", []) if isinstance(tag, str)}
            second_tags = {tag for tag in second.get("tags", []) if isinstance(tag, str)}
            if first_forbidden.intersection(second_tags) or second_forbidden.intersection(first_tags):
                _issue(issues, "error", path, f"$.links[{index}]", "인접 금지 태그가 지정된 조각끼리는 연결할 수 없습니다.")

    start_index = next((
        index for index, placement in enumerate(placements)
        if isinstance(placement, dict)
        and pieces.get(placement.get("piece_id"), {}).get("role") == "start"
    ), None)
    if start_index is not None:
        for placement_index, placement in enumerate(placements):
            if not isinstance(placement, dict):
                continue
            piece = pieces.get(placement.get("piece_id"), {})
            for marker in piece.get("markers", []) if isinstance(piece, dict) else []:
                if not isinstance(marker, dict) or marker.get("kind") != "gate" or not isinstance(marker.get("connector"), str):
                    continue
                connector_id = marker["connector"]
                blocked_index = next((link_index for link_index, link in enumerate(links) if isinstance(link, dict) and (
                    link.get("from_index") == placement_index and link.get("from_connector") == connector_id
                    or link.get("to_index") == placement_index and link.get("to_connector") == connector_id
                )), None)
                if blocked_index is None:
                    if marker.get("reference") is not None:
                        _issue(issues, "error", path, f"$.placements[{placement_index}]", "참조형 gate 마커의 차단 커넥터가 계획에서 연결되지 않았습니다.")
                    continue
                graph: dict[int, set[int]] = {}
                for link_index, link in enumerate(links):
                    if link_index == blocked_index or not isinstance(link, dict):
                        continue
                    first, second = link.get("from_index"), link.get("to_index")
                    if not isinstance(first, int) or not isinstance(second, int):
                        continue
                    graph.setdefault(first, set()).add(second)
                    graph.setdefault(second, set()).add(first)
                reachable: set[int] = set()
                queue = [start_index]
                while queue:
                    current = queue.pop()
                    if current in reachable:
                        continue
                    reachable.add(current)
                    queue.extend(graph.get(current, set()) - reachable)
                blocked = links[blocked_index]
                if (blocked.get("from_index") in reachable) == (blocked.get("to_index") in reachable):
                    _issue(issues, "error", path, f"$.placements[{placement_index}]", "gate 커넥터를 막아도 우회 경로가 남아 관문이 진행 영역을 분리하지 못합니다.")
    return issues


def dungeon_workspace_payload(root: Path) -> dict[str, Any]:
    """Load read-only dungeon authoring data for the browser plan preview."""
    content = root / "content"

    def documents(directory: Path, id_key: str) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
        loaded: list[dict[str, Any]] = []
        errors: list[dict[str, str]] = []
        for path in sorted(directory.rglob("*.json")) if directory.is_dir() else []:
            relative = path.relative_to(root).as_posix()
            try:
                document = load_json(path)
                if not isinstance(document, dict):
                    raise ValueError("JSON 최상위 값은 객체여야 합니다.")
                loaded.append({
                    "path": relative,
                    "id": document.get(id_key, ""),
                    "document": document,
                })
            except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                errors.append({"path": relative, "error": str(error)})
        return loaded, errors

    dungeons, dungeon_errors = documents(content / "dungeons", "dungeon_id")
    plans, plan_errors = documents(content / "dungeon_plans", "plan_id")
    pieces, piece_errors = documents(content / "dungeon_pieces", "piece_id")
    for item in dungeons:
        document = item["document"]
        item["name"] = _localized_value(document.get("display_name")) or item["id"]
        item["terrain_mode"] = document.get("terrain", {}).get("mode", "")
        terrain = document.get("terrain", {})
        item["layout_mode"] = (
            terrain.get("generator", {}).get("layout", "natural_network")
            if terrain.get("mode") in {"procedural_cave", "hybrid"}
            else document.get("layout", {}).get("mode", "fixed")
        )
        item["plan_mode"] = document.get("plan", {}).get("mode", "authored")
    return {
        "items": dungeons,
        "plans": plans,
        "pieces": pieces,
        "errors": dungeon_errors + plan_errors + piece_errors,
    }


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
    battle_levels: dict[str, tuple[str, int]] = {}
    battle_teams: dict[str, list[dict[str, Any]]] = {}
    if category == "trainers":
        battle_dir = root / "content" / "battles"
        for battle_path in sorted(battle_dir.rglob("*.json")) if battle_dir.is_dir() else []:
            try:
                battle_data = load_json(battle_path)
                battle_id = battle_data.get("id") if isinstance(battle_data, dict) else None
                battle_type = battle_data.get("battle", {}).get("battle_type") if isinstance(battle_data, dict) else None
                if isinstance(battle_id, str) and isinstance(battle_type, str):
                    battle_types[battle_id] = battle_type
                    team = battle_data.get("battle", {}).get("team", [])
                    battle_teams[battle_id] = [
                        {
                            key: member[key]
                            for key in ("species", "level", "form", "shiny")
                            if key in member
                        }
                        for member in team[:6]
                        if isinstance(member, dict) and isinstance(member.get("species"), str)
                    ] if isinstance(team, list) else []
                    level_mode = battle_data.get("battle", {}).get("level_mode", "fixed")
                    level_offset = battle_data.get("battle", {}).get("level_offset", 0)
                    battle_levels[battle_id] = (
                        level_mode if isinstance(level_mode, str) else "fixed",
                        level_offset if isinstance(level_offset, int) and not isinstance(level_offset, bool) else 0,
                    )
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
                    compiled_data = materialize_event_document(data) if data.get("schema_version") == 4 else data
                    battle_refs = [
                        command.get("battle")
                        for event in compiled_data.get("events", [])
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
                    summary["team"] = next(
                        (battle_teams[reference] for reference in battle_refs if reference in battle_teams),
                        [],
                    )
                    level_settings = next(
                        (battle_levels[reference] for reference in battle_refs if reference in battle_levels),
                        ("fixed", 0),
                    )
                    summary["level_mode"], summary["level_offset"] = level_settings
                else:
                    team = data.get("battle", {}).get("team", [])
                    summary["team"] = [
                        {
                            key: member[key]
                            for key in ("species", "level", "form", "shiny")
                            if key in member
                        }
                        for member in team[:6]
                        if isinstance(member, dict) and isinstance(member.get("species"), str)
                    ] if isinstance(team, list) else []
                summary["team_size"] = len(summary.get("team", []))
                summary["npc_name"] = _localized_value(data.get("npc", {}).get("display_name"))
                profile = data.get("placement_profile", {})
                inferred_trainer = bool(summary.get("battle_type"))
                summary["classification"] = profile.get(
                    "classification", "trainer" if inferred_trainer else "ambient"
                )
                summary["expected_level"] = profile.get("expected_level")
                summary["preferred_biomes"] = profile.get("preferred_biomes", [])
                summary["automatic_town_placement"] = profile.get("automatic_town_placement", False)
                summary["automatic_route_placement"] = profile.get("automatic_route_placement", False)
                summary["event_engine"] = data.get("event_runtime", {}).get("engine", "cves_v5")
            elif category == "battles":
                summary["battle_type"] = data.get("battle", {}).get("battle_type", "singles")
            elif category == "routes":
                summary["route_type"] = data.get("route_type", "road")
                summary["auto_name"] = data.get("auto_name", True)
                summary["corridor_width_blocks"] = data.get("corridor", {}).get("width_blocks", 12)
                summary["npc_count"] = len(data.get("npc_placements", []))
                summary["pokemon_spawns"] = data.get("pokemon_spawns", {})
            elif category == "settlements":
                summary["biome"] = world_biomes.get(data.get("id"), "minecraft:plains")
                summary["load_order"] = data.get("load_order")
                summary["town_radius_cells"] = data.get("town_radius_cells", 1)
                summary["town_footprint_shape"] = data.get("town_footprint_shape", "line_q")
                summary["town_footprint_cells"] = data.get("town_footprint_cells", [])
                summary["town_road_exits"] = data.get("town_road_exits", [])
            elif category == "caves":
                summary["generation"] = int(path.parent.name.removeprefix("generation_")) if path.parent.name.removeprefix("generation_").isdigit() else 1
                summary["requires_flash"] = data.get("requires_flash", False)
                summary["entrance_count"] = len(data.get("entrances", []))
                summary["entrances"] = data.get("entrances", [])
                summary["cave_type"] = data.get("cave_type", "")
            elif category == "underground-roads":
                summary["generation"] = int(path.parent.name.removeprefix("generation_")) if path.parent.name.removeprefix("generation_").isdigit() else 1
                summary["module_count"] = len(data.get("modules", []))
                summary["modules"] = data.get("modules", [])
                summary["endpoints"] = _underground_road_endpoints(data, root)
            elif category == "forests":
                summary["generation"] = int(path.parent.name.removeprefix("generation_")) if path.parent.name.removeprefix("generation_").isdigit() else 1
                summary["entrance_count"] = len(data.get("entrances", []))
                summary["entrances"] = data.get("entrances", [])
                summary["path_count"] = len(data.get("paths", []))
            else:
                summary["generation"] = data.get("generation", 1)
                summary["path_count"] = len(data.get("paths", []))
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
    if category == "settlements":
        documents.sort(key=lambda item: (
            item.get("load_order") if isinstance(item.get("load_order"), int) else 1_000_000,
            item.get("path", ""),
        ))
    return documents


def _reorder_settlements(root: Path, ordered_ids: Any) -> list[Issue]:
    if not isinstance(ordered_ids, list) or not all(isinstance(value, str) for value in ordered_ids):
        raise ValueError("마을 ID 순서가 문자열 배열이어야 합니다.")
    if len(ordered_ids) != len(set(ordered_ids)):
        raise ValueError("마을 ID 순서에 중복이 있습니다.")
    base = _managed_directory(root, "settlements")
    records: dict[str, tuple[Path, dict[str, Any]]] = {}
    for path in sorted(base.rglob("*.json")) if base.is_dir() else []:
        data = load_json(path)
        settlement_id = data.get("id") if isinstance(data, dict) else None
        if not isinstance(settlement_id, str) or not settlement_id:
            raise ValueError(f"마을 ID를 읽을 수 없습니다: {path.relative_to(root).as_posix()}")
        records[settlement_id] = (path, data)
    if set(ordered_ids) != set(records):
        missing = sorted(set(records) - set(ordered_ids))
        unknown = sorted(set(ordered_ids) - set(records))
        details = []
        if missing:
            details.append(f"누락: {', '.join(missing)}")
        if unknown:
            details.append(f"알 수 없음: {', '.join(unknown)}")
        raise ValueError("전체 마을 목록과 순서 요청이 일치하지 않습니다. " + " / ".join(details))
    prepared: list[tuple[Path, dict[str, Any]]] = []
    issues: list[Issue] = []
    for order, settlement_id in enumerate(ordered_ids, start=1):
        path, original = records[settlement_id]
        data = copy.deepcopy(original)
        data["load_order"] = order
        _, candidate_issues = _validate_payload(data, validate_settlement_file)
        issues.extend(Issue(issue.level, path.as_posix(), issue.path, issue.message) for issue in candidate_issues)
        prepared.append((path, data))
    if any(issue.level == "error" for issue in issues):
        return issues
    for path, data in prepared:
        relative_path = path.relative_to(root).as_posix()
        _, save_issues = _save_document(root, "settlements", relative_path, data)
        issues.extend(save_issues)
    return issues


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
        "routes": validate_route_file,
        "settlements": validate_settlement_file,
        "caves": validate_cave_file,
        "dungeons": validate_dungeon_file,
        "dungeon-plans": validate_dungeon_plan_file,
        "dungeon-pieces": validate_dungeon_piece_file,
        "underground-roads": validate_underground_road_file,
        "forests": validate_forest_file,
    }[category]
    data = synchronize_spatial_build_bounds(copy.deepcopy(data), category)
    if category == "forests" and isinstance(data, dict):
        dimension = data.get("dimension")
        if isinstance(dimension, dict):
            # The forest dimension is runtime-owned and is not an editable
            # document property. This also protects against an older browser
            # tab writing the former generation dimension back to disk.
            dimension["id"] = "cobbleventure:forests"
    try:
        target = _managed_path(root, category, relative_path)
    except ValueError as error:
        return None, [Issue("error", relative_path, "$", str(error))]
    document_id, candidate_issues = _validate_payload(data, validator)
    issues = [
        Issue(issue.level, target.as_posix(), issue.path, issue.message)
        for issue in candidate_issues
    ]
    if category == "settlements" and isinstance(data, dict) and not any(
        issue.level == "error" for issue in issues
    ):
        issues.extend(validate_town_indoor_npc_capacity_document(root, data, target))
    if category == "underground-roads" and isinstance(data, dict):
        _, structure_issues = validate_underground_road_document(data, target, root)
        issues.extend(
            issue for issue in structure_issues
            if not any(existing.path == issue.path and existing.message == issue.message for existing in issues)
        )
    if category == "dungeon-plans" and isinstance(data, dict):
        issues.extend(validate_dungeon_plan_document(root, data, target))
    duplicate = _duplicate_document_issue(
        root, category, target, document_id, validator
    )
    if duplicate is not None:
        issues.append(duplicate)
    if any(issue.level == "error" for issue in issues):
        return target, issues

    v5_sync = None
    if category == "trainers":
        try:
            v5_sync = _prepare_v5_preset_sync(root, target, data)
        except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
            issues.append(Issue("error", target.as_posix(), "$.event_runtime", str(error)))
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
    if v5_sync is not None:
        _write_v5_preset_sync(v5_sync)
    return target, issues


def _prepare_v5_preset_sync(
    root: Path,
    target: Path,
    data: Any,
    *,
    allow_managed_upgrade: bool = False,
) -> dict[str, Any] | None:
    """Validate and stage a preset-authored CVES source without touching user files."""
    if not isinstance(data, dict):
        return None
    runtime = data.get("event_runtime")
    if not isinstance(runtime, dict) or runtime.get("engine") != "cves_v5":
        return None
    authoring = runtime.get("authoring")
    if authoring == "preset" and data.get("event_design", {}).get("mode") != "preset":
        raise ValueError("V5 행동 프리셋 자동 작성에는 event_design.mode=preset이 필요합니다.")

    source_root = (root / "content" / "source").resolve()
    relative = target.resolve().relative_to(source_root).with_suffix("")
    npc_id = data.get("id")
    if not isinstance(npc_id, str) or ":npc/" not in npc_id:
        raise ValueError("V5 이벤트 경로를 만들 수 있는 NPC ID가 필요합니다.")
    namespace = npc_id.split(":", 1)[0]
    script_id = f"{namespace}:event_script/{relative.as_posix()}"
    if authoring == "preset" and runtime.get("script_id") != script_id:
        raise ValueError(f"이 NPC의 자동 생성 V5 script_id는 {script_id}여야 합니다.")

    if authoring == "custom":
        custom_script_id = runtime.get("script_id")
        namespace_path = str(custom_script_id).partition(":event_script/")
        if not namespace_path[1]:
            raise ValueError("사용자 정의 V5 script_id 형식이 올바르지 않습니다.")
        event_root = (root / "content" / "events").resolve()
        event_path = (event_root / namespace_path[0] / f"{namespace_path[2]}.cves").resolve()
        try:
            event_path.relative_to(event_root)
        except ValueError as error:
            raise ValueError("사용자 정의 V5 script_id는 이벤트 디렉터리를 벗어날 수 없습니다.") from error
        if not event_path.is_file():
            raise ValueError(f"연결할 사용자 정의 CVES를 찾을 수 없습니다: {event_path.relative_to(root)}")
        binding_path = root / "content" / "event-bindings" / namespace / relative.with_suffix(".json")
        return {
            "binding_path": binding_path,
            "binding_source": json.dumps(
                {"schema_version": 1, "script_id": custom_script_id}, ensure_ascii=False, indent=2
            ) + "\n",
        }
    if authoring != "preset":
        raise ValueError("V5 작성 방식은 preset 또는 custom이어야 합니다.")

    relative_script = f"{namespace}/{relative.as_posix()}.cves"
    program = cves_preset_program(data)
    checked = validate_cves_ast(
        encode_cves_program(program, include_spans=False),
        relative_script,
        load_cves_project_catalog(root, item_catalog=_cves_item_catalog(root)),
    )
    if not checked["valid"]:
        first = checked["diagnostics"][0]
        raise ValueError(f"V5 행동 프리셋을 컴파일할 수 없습니다: {first['rendered']}")
    canonical = checked["canonical"]
    event_path = root / "content" / "events" / Path(relative_script)
    binding_path = root / "content" / "event-bindings" / namespace / relative.with_suffix(".json")

    if event_path.is_file():
        existing = event_path.read_text(encoding="utf-8")
        previous = load_json(target) if target.is_file() else None
        previous_runtime = previous.get("event_runtime") if isinstance(previous, dict) else None
        if isinstance(previous_runtime, dict) and previous_runtime.get("engine") == "cves_v5" \
                and previous_runtime.get("authoring") == "preset":
            expected_previous = format_cves_program(cves_preset_program(previous))
            if existing != expected_previous and not allow_managed_upgrade:
                raise ValueError(
                    "연결된 CVES가 행동 프리셋 생성 후 직접 수정되었습니다. "
                    "자동 덮어쓰기를 중단했습니다. 사용자 정의 이벤트로 전환해 주세요."
                )
        elif existing != canonical:
            raise ValueError(
                "같은 경로에 기존 CVES가 있습니다. 기존 이벤트를 보존하기 위해 V5 전환을 중단했습니다."
            )

    return {
        "event_path": event_path,
        "event_source": canonical,
        "binding_path": binding_path,
        "binding_source": json.dumps(
            {"schema_version": 1, "script_id": script_id}, ensure_ascii=False, indent=2
        ) + "\n",
    }


def _write_v5_preset_sync(plan: dict[str, Any]) -> None:
    for path_key, source_key, suffix in (
        ("event_path", "event_source", ".cves.tmp"),
        ("binding_path", "binding_source", ".json.tmp"),
    ):
        if path_key not in plan:
            continue
        target = plan[path_key]
        target.parent.mkdir(parents=True, exist_ok=True)
        handle, temporary_name = tempfile.mkstemp(prefix=f".{target.stem}-", suffix=suffix, dir=target.parent)
        try:
            with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as output:
                output.write(plan[source_key])
            os.replace(temporary_name, target)
        finally:
            Path(temporary_name).unlink(missing_ok=True)


def _preview_v5_preset_sync(root: Path, plan: dict[str, Any] | None) -> dict[str, Any]:
    """Describe a staged V5 synchronization without writing any files."""
    if plan is None:
        return {"enabled": False, "changed": False, "artifacts": []}
    artifacts: list[dict[str, Any]] = []
    for path_key, source_key, kind in (
        ("event_path", "event_source", "cves"),
        ("binding_path", "binding_source", "binding"),
    ):
        target = plan.get(path_key)
        source = plan.get(source_key)
        if not isinstance(target, Path) or not isinstance(source, str):
            continue
        previous = target.read_text(encoding="utf-8") if target.is_file() else ""
        action = "create" if not target.is_file() else "unchanged" if previous == source else "update"
        relative = target.relative_to(root).as_posix()
        diff = "" if action == "unchanged" else "".join(difflib.unified_diff(
            previous.splitlines(keepends=True),
            source.splitlines(keepends=True),
            fromfile=f"a/{relative}",
            tofile=f"b/{relative}",
        ))
        artifacts.append({
            "kind": kind,
            "path": relative,
            "action": action,
            "source": source,
            "diff": diff,
        })
    return {
        "enabled": True,
        "changed": any(artifact["action"] != "unchanged" for artifact in artifacts),
        "artifacts": artifacts,
    }


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
        "routes": {"route_preset"},
        "caves": {"cave"},
        "forests": {"forest"},
    }[category]
    scan_directories = {
        "trainers": [root / "content" / "battles", root / "content" / "routes", root / "content" / "settlements"],
        "battles": [root / "content" / "source"],
        "routes": [root / "content" / "worlds"],
        "caves": [root / "content" / "worlds"],
        "forests": [root / "content" / "worlds"],
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
        "placement_profile": {
            "classification": "trainer",
            "expected_level": 5,
            "preferred_biomes": [],
            "automatic_town_placement": True,
            "automatic_route_placement": True,
        },
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
        "biome_layout": {
            "arrangement": "organic_patches",
            "transition_width": 12,
            "pokemon_biome_set": "cobbleventure:biome_set/starter_region",
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
            "layout_mode": "automatic",
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
            "decoration_placements": [],
            "manual_layout": {"roads": [], "buildings": [], "decorations": []},
        },
        "npc_placement": {
            "auto_place_npcs": False,
            "trainer_population": {
                "enabled": False, "max_active": 0,
                "use_biome_defaults": True, "direct_trainers": [],
                "placement_areas": ["indoor", "outdoor"],
            },
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
        "placement_profile": {
            "classification": "ambient",
            "preferred_biomes": [],
            "automatic_town_placement": True,
            "automatic_route_placement": False,
        },
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
        "event_runtime": {
            "engine": "cves_v5", "authoring": "preset",
            "script_id": f"cobbleventure:event_script/trainers/{slug}",
        },

        "event_design": {"mode": "preset", "preset": {
            "type": "simple",
            "initial_trigger": {"type": "interact", "range": 4.0},
            "first_text": {"ko_kr": f"안녕! 나는 {name}(이)야."},
        }},
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
        "event_design": {"mode": "easy_npc_events"},
        "events": [{
            "id": "on_interact",
            "trigger": {"type": "interact", "range": 4.0},
            "commands": [
                {
                    "type": "branch",
                    "conditions": [{"type": "flag", "key": clear_key, "value": True}],
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
        "cave_type": "cobbleventure:cave_type/natural_rock",
        "style": "rock",
        "dimension": {
            "id": "cobbleventure:dungeons",
            "region_id": f"generation_{generation_number}/{slug}",
            "origin": {"x": 0, "y": 48, "z": 0},
            "bounds": {"min_x": -256, "min_z": -256, "max_x": 256, "max_z": 256},
        },
        "requires_flash": False,
        "random_encounters": {
            "enabled": True,
            "minimum_distance": 16,
            "maximum_distance": 24,
            "minimum_level": 5,
            "maximum_level": 10,
            "pokemon_biome": "minecraft:dripstone_caves",
            "inherit_biome": True,
            "excluded_species": [],
            "additions": [],
            "level_overrides": [],
        },
        "trainer_settings": {"enabled": False, "max_active": 0, "use_biome_defaults": True, "direct_trainers": [], "class_pool": [], "placements": []},
        "entrances": [
            {
                "id": "main",
                "display_name": "주 출입구",
                "destination_anchor": {"x": 0, "y": 48, "z": 0},
                "fallback_anchor": {"x": 0, "y": 49, "z": 0},
            }
        ],
    }


def _underground_road_template(slug: str, name: str, generation: str) -> dict[str, Any]:
    generation_number = int(generation.removeprefix("generation_"))
    return {
        "$schema": "../../schemas/underground-road.schema.json",
        "schema_version": 2,
        "id": f"cobbleventure:underground_road/{slug}",
        "enabled": True,
        "display_name": {"ko_kr": name},
        "dimension": {
            "id": "cobbleventure:dungeons",
            "region_id": f"generation_{generation_number}/{slug}",
            "origin": {"x": 0, "y": 48, "z": 0},
        },
        "modules": [],
    }


def _forest_template(slug: str, name: str, generation: str) -> dict[str, Any]:
    generation_number = int(generation.removeprefix("generation_"))
    return {
        "$schema": "../../schemas/forest.schema.json",
        "schema_version": 1,
        "id": f"cobbleventure:forest/{slug}",
        "enabled": True,
        "display_name": {"ko_kr": name},
        "dimension": {
            "id": "cobbleventure:forests",
            "region_id": f"generation_{generation_number}/{slug}",
            "origin": {"x": 0, "y": 69, "z": 0},
            "bounds": {"min_x": -256, "min_z": -256, "max_x": 256, "max_z": 256},
        },
        "environment": {"weather": "clear"},
        "random_encounters": {
            "enabled": True,
            "minimum_distance": 16,
            "maximum_distance": 24,
            "minimum_level": 3,
            "maximum_level": 7,
            "pokemon_biome": "minecraft:old_growth_spruce_taiga",
            "inherit_biome": True,
            "excluded_species": [],
            "additions": [],
            "level_overrides": [],
        },
        "trainer_settings": {
            "enabled": False, "max_active": 0,
            "use_biome_defaults": True, "direct_trainers": [],
        },
        "tree_barrier": {
            "min_height": 8,
            "max_height": 16,
            "trunk_blocks": ["minecraft:oak_log"],
            "foliage_blocks": ["minecraft:oak_leaves"],
            "barrier_block": "minecraft:barrier",
        },
        "undergrowth": {
            "density": 0.72,
            "blocks": ["minecraft:short_grass", "minecraft:fern", "minecraft:tall_grass"],
            "path_clearance": 2,
        },
        "generator": {
            "layout": "hybrid",
            "seed_salt": 0,
            "cell_size": 16,
            "maze_complexity": 0.65,
            "loop_chance": 0.18,
            "spline_enabled": True,
            "spline_tension": 0.45,
        },
        "paths": [{
            "id": "main",
            "kind": "main",
            "width": 5,
            "surface": "minecraft:dirt_path",
            "points": [{"x": -160, "z": 0}, {"x": 0, "z": -48}, {"x": 160, "z": 0}],
            "spline": {"enabled": True, "tension": 0.45},
        }],
        "terrain_tiles": [],
        "entrances": [
            {"id": "west", "display_name": "서쪽 입구", "position": {"x": -160, "z": 0}},
            {"id": "east", "display_name": "동쪽 입구", "position": {"x": 160, "z": 0}},
        ],
    }


def _route_template(slug: str, name: str) -> dict[str, Any]:
    return {
        "$schema": "../../schemas/route.schema.json",
        "schema_version": 1,
        "id": f"cobbleventure:route/{slug}",
        "display_name": {"ko_kr": name, "en_us": name},
        "auto_name": True,
        "enabled": True,
        "route_type": "road",
        "log_bridge_layout": {"pattern": "straight", "detour_blocks": 18},
        "corridor": {"width_blocks": 12, "edge_noise": 0},
        "level_scaling": {"mode": "world", "offset": 0},
        "pokemon_spawns": {
            "inherit_biome": True,
            "excluded_species": [],
            "additions": [],
            "level_overrides": [],
        },
        "automatic_npc_placement": {"enabled": False, "count": 0, "use_biome_defaults": True, "direct_trainers": []},
        "npc_placements": [],
    }


def _clone_route_document(
    root: Path, source_id: str, slug: str, name: str, generation: str
) -> tuple[Path | None, dict[str, Any] | None, list[Issue]]:
    if not DOCUMENT_SLUG.fullmatch(slug):
        return None, None, [Issue("error", "", "$.slug", "파일 ID는 소문자, 숫자와 밑줄만 사용할 수 있습니다.")]
    if not DOCUMENT_SLUG.fullmatch(generation):
        return None, None, [Issue("error", "", "$.generation", "올바른 세대 ID가 아닙니다.")]
    source = next((item for item in _list_documents(root, "routes") if item.get("id") == source_id), None)
    if not source:
        return None, None, [Issue("error", "", "$.source_id", "복사할 기존 길을 찾을 수 없습니다.")]
    source_path = _managed_path(root, "routes", source["path"])
    document = copy.deepcopy(load_json(source_path))
    document["id"] = f"cobbleventure:route/{slug}"
    document["auto_name"] = True
    display_name = document.setdefault("display_name", {})
    display_name["ko_kr"] = name.strip() or f"{_localized_value(display_name)} 복사본"
    display_name["en_us"] = display_name.get("en_us") or display_name["ko_kr"]
    relative_path = f"content/routes/{generation}/{slug}.json"
    target = (root / relative_path).resolve()
    if target.exists():
        return target, None, [Issue("error", target.as_posix(), "$", "같은 이름의 길이 이미 존재합니다.")]
    saved, issues = _save_document(root, "routes", relative_path, document)
    return saved, document if saved and not any(issue.level == "error" for issue in issues) else None, issues


def _create_document(
    root: Path, category: str, slug: str, name: str, generation: str = "generation_1",
    reference_id: str = "",
) -> tuple[Path | None, list[Issue]]:
    if category not in {"trainers", "battles", "routes", "settlements", "caves", "underground-roads", "forests"}:
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
        # A new project may not have a content tree yet; V5 preset validation indexes it.
        (root / "content").mkdir(parents=True, exist_ok=True)
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
    elif category == "routes":
        relative_path = f"content/routes/{generation}/{slug}.json"
        document = _route_template(slug, name.strip())
    elif category == "settlements":
        relative_path = f"content/settlements/{generation}/{slug}.json"
        document = _settlement_template(slug, name.strip(), generation)
        existing = _list_documents(root, "settlements")
        if existing and not all(isinstance(item.get("load_order"), int) for item in existing):
            reorder_issues = _reorder_settlements(root, [item.get("id", "") for item in existing])
            if any(issue.level == "error" for issue in reorder_issues):
                return None, reorder_issues
            existing = _list_documents(root, "settlements")
        document["load_order"] = max(
            (item.get("load_order", 0) for item in existing),
            default=0,
        ) + 1
    elif category == "caves":
        relative_path = f"content/caves/{generation}/{slug}.json"
        document = _cave_template(slug, name.strip(), generation)
    elif category == "underground-roads":
        relative_path = f"content/underground_roads/{generation}/{slug}.json"
        document = _underground_road_template(slug, name.strip(), generation)
        candidates = [
            (resource, path) for resource, path in managed_structure_files(root).items()
            if _managed_structure_category(path.relative_to(root / "content" / "structures")) == "underground_road_module"
        ]
        preferred = candidates[0] if candidates else None
        if preferred is None:
            return None, [Issue(
                "error", relative_path, "$.modules",
                "먼저 에딧월드에서 지하통로 조각 NBT를 저장하고 content/structures/underground_road_modules로 가져와 주세요.",
            )]
        document["modules"] = [{"id": "module_1", "structure": preferred[0], "position": {"x": 0, "y": 0, "z": 0}, "rotation": "none"}]
    else:
        relative_path = f"content/forests/{generation}/{slug}.json"
        document = _forest_template(slug, name.strip(), generation)
    target = (root / relative_path).resolve()
    if target.exists():
        return target, [Issue("error", target.as_posix(), "$", "같은 이름의 파일이 이미 존재합니다.")]
    return _save_document(root, category, relative_path, document)


def _run_build(
    core_root: Path, project_root: Path, command: str, language: str = "ko_kr",
    cobblemon_target: str = "1.7.3",
) -> dict[str, Any]:
    if command not in BUILD_COMMANDS:
        raise ValueError("허용되지 않은 빌드 명령입니다.")
    if language not in EXPORT_LANGUAGES:
        raise ValueError("지원하지 않는 내보내기 언어입니다.")
    if cobblemon_target not in COBBLEMON_BUILD_TARGETS:
        raise ValueError("지원하지 않는 Cobblemon 빌드 대상입니다.")
    try:
        music_catalog, _ = sync_local_music_catalog(project_root, core_root)
        music_library = music_catalog.get("local_library", {})
        music_status = (
            "[INFO] 로컬 음원 자동 갱신: "
            f"OGG {music_library.get('registered_ogg', 0)}곡 / "
            f"사용 태그 {music_library.get('registered_tracks', 0)}개 / "
            f"누락 {music_library.get('missing_tracks', 0)}개"
        )
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
        return {
            "command": command,
            "language": language,
            "cobblemon_target": cobblemon_target,
            "description": BUILD_COMMANDS[command],
            "success": False,
            "return_code": None,
            "output": f"[ERROR] 빌드 전 로컬 음원을 자동으로 불러오지 못했습니다.\n{error}",
        }
    try:
        completed = subprocess.run(
            ["cmd.exe", "/d", "/c", str(core_root / "build.bat"), command, language],
            cwd=core_root,
            env={
                **os.environ,
                "COBBLEVENTURE_PROJECT_PATH": str(project_root),
                "COBBLEVENTURE_EXPORT_LANGUAGE": language,
                "COBBLEVENTURE_COBBLEMON_TARGET": cobblemon_target,
            },
            capture_output=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
            check=False,
        )
        output = "\n".join(
            part.strip()
            for part in (music_status, completed.stdout, completed.stderr)
            if part.strip()
        )
        return {
            "command": command,
            "language": language,
            "cobblemon_target": cobblemon_target,
            "description": BUILD_COMMANDS[command],
            "success": completed.returncode == 0,
            "return_code": completed.returncode,
            "output": output or "출력 없음",
        }
    except subprocess.TimeoutExpired as error:
        output = (error.stdout or b"") if isinstance(error.stdout, bytes) else (error.stdout or "")
        return {
            "command": command,
            "language": language,
            "cobblemon_target": cobblemon_target,
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
        return {"instance_path": "", "live_instance_path": ""}
    document = load_json(path)
    section = document.get("structure_builder", {}) if isinstance(document, dict) else {}
    instance_path = section.get("instance_path", "") if isinstance(section, dict) else ""
    live_instance_path = section.get("live_instance_path", "") if isinstance(section, dict) else ""
    if not isinstance(instance_path, str) or not isinstance(live_instance_path, str):
        raise ValueError("건축 월드 인스턴스 경로 설정이 문자열이 아닙니다.")
    return {"instance_path": instance_path, "live_instance_path": live_instance_path}


def _save_structure_builder_settings(
    root: Path, instance_path: str, live_instance_path: str = ""
) -> dict[str, str]:
    def normalize(value: str) -> str:
        value = value.strip()
        if not value:
            return ""
        resolved = Path(os.path.expandvars(value)).expanduser()
        if not resolved.is_absolute():
            raise ValueError("CurseForge 인스턴스 경로는 절대 경로여야 합니다.")
        return str(resolved.resolve(strict=False))
    value = normalize(instance_path)
    live_value = normalize(live_instance_path)
    path = _content_manager_settings_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    document = {
        "schema_version": 1,
        "structure_builder": {
            "instance_path": value,
            "live_instance_path": live_value,
        },
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
    return {"instance_path": value, "live_instance_path": live_value}


def _structure_builder_world_path(instance_path: str) -> Path | None:
    if not instance_path:
        return None
    return Path(instance_path) / "saves" / STRUCTURE_BUILDER_WORLD_NAME


def _live_nbt_editor_world_path(instance_path: str) -> Path | None:
    if not instance_path:
        return None
    return Path(instance_path) / "saves" / LIVE_NBT_EDITOR_WORLD_NAME


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


def _structure_builder_live_root(world_path: Path) -> Path:
    return world_path / "generated" / "cobbleventure_builder" / "live"


def _atomic_write_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_bytes(data)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _atomic_write_json(path: Path, document: dict[str, Any]) -> None:
    _atomic_write_bytes(
        path,
        (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    )


def _structure_builder_sources(project_root: Path) -> list[dict[str, Any]]:
    structures = project_root / "content" / "structures"
    result: list[dict[str, Any]] = []
    if not structures.is_dir():
        return result
    for path in sorted(structures.rglob("*.nbt"), key=lambda value: value.as_posix().casefold()):
        try:
            data = path.read_bytes()
            size = read_minecraft_structure_size(data)
        except (OSError, EOFError, ValueError, gzip.BadGzipFile, struct.error):
            continue
        relative = path.relative_to(structures).as_posix()
        result.append({
            "id": relative[:-4],
            "path": f"content/structures/{relative}",
            "size": list(size),
            "digest": hashlib.sha256(data).hexdigest(),
        })
    return result


def _managed_structure_path(project_root: Path, relative_path: str) -> Path:
    if not relative_path or Path(relative_path).is_absolute():
        raise ValueError("저장소 기준 NBT 상대 경로가 필요합니다.")
    base = (project_root / "content" / "structures").resolve()
    candidate = Path(relative_path.replace("\\", "/"))
    if candidate.parts[:2] == ("content", "structures"):
        candidate = Path(*candidate.parts[2:])
    target = (base / candidate).resolve()
    if target.suffix.lower() != ".nbt" or base not in target.parents:
        raise ValueError("관리 NBT 디렉터리 밖에는 접근할 수 없습니다.")
    return target


def _structure_builder_live_state(world_path: Path | None) -> dict[str, Any]:
    empty = {"connected": False, "active": None, "pending": False, "last_result": None}
    if world_path is None or not world_path.is_dir():
        return empty
    live_root = _structure_builder_live_root(world_path)
    state_path = live_root / "state.json"
    result_path = live_root / "outbox" / "result.json"
    state = load_json(state_path) if state_path.is_file() else None
    result = load_json(result_path) if result_path.is_file() else None
    return {
        "connected": live_root.is_dir(),
        "active": state if isinstance(state, dict) else None,
        "pending": (live_root / "command.json").is_file(),
        "last_result": result if isinstance(result, dict) else None,
    }


def _dungeon_piece_for_structure(
    project_root: Path, structure: Path
) -> tuple[Path, dict[str, Any]] | None:
    structure_root = (project_root / "content" / "structures").resolve()
    relative = structure.resolve().relative_to(structure_root).with_suffix("").as_posix()
    piece_root = project_root / "content" / "dungeon_pieces"
    if not piece_root.is_dir():
        return None
    for path in sorted(piece_root.rglob("*.json")):
        try:
            document = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError):
            continue
        if not isinstance(document, dict):
            continue
        resource = document.get("structure")
        if isinstance(resource, str) and resource.split(":", 1)[-1] == relative:
            return path, document
    return None


def _dungeon_piece_marker_anchor(marker: dict[str, Any]) -> dict[str, Any]:
    marker_id = marker.get("id", "marker")
    anchor = {
        "id": marker_id,
        "label": marker_id,
        "type": "dungeon_marker",
        "kind": marker.get("kind", "encounter"),
        "position": marker.get("position", [0, 0, 0]),
    }
    for key in ("reference", "connector"):
        if isinstance(marker.get(key), str):
            anchor[key] = marker[key]
    return anchor


def _live_structure_metadata(
    project_root: Path, managed: Path, source: str
) -> dict[str, Any] | None:
    metadata_path = managed.with_suffix(".structure.json")
    metadata = load_json(metadata_path) if metadata_path.is_file() else None
    if metadata is not None and not isinstance(metadata, dict):
        raise ValueError(f"구조물 메타데이터는 JSON 객체여야 합니다: {metadata_path}")
    piece = _dungeon_piece_for_structure(project_root, managed)
    if piece is None:
        return metadata
    document = dict(metadata or {})
    anchors = document.get("anchors", [])
    if not isinstance(anchors, list):
        anchors = []
    document["schema_version"] = document.get("schema_version", 1)
    document["structure"] = source
    document["dungeon_piece_id"] = piece[1].get("piece_id", "")
    document["anchors"] = [
        anchor for anchor in anchors
        if not isinstance(anchor, dict) or anchor.get("type") != "dungeon_marker"
    ] + [
        _dungeon_piece_marker_anchor(marker)
        for marker in piece[1].get("markers", [])
        if isinstance(marker, dict)
    ]
    return document


def _sync_live_dungeon_piece_markers(
    project_root: Path, managed: Path, exported: dict[str, Any]
) -> tuple[dict[str, Any], bool]:
    piece = _dungeon_piece_for_structure(project_root, managed)
    if piece is None:
        return exported, False
    piece_path, document = piece
    if exported.get("dungeon_piece_id") != document.get("piece_id"):
        return exported, False
    anchors = exported.get("anchors", [])
    if not isinstance(anchors, list):
        anchors = []
    previous = {
        marker.get("id"): marker
        for marker in document.get("markers", [])
        if isinstance(marker, dict) and isinstance(marker.get("id"), str)
    }
    markers: list[dict[str, Any]] = []
    for anchor in anchors:
        if not isinstance(anchor, dict) or anchor.get("type") != "dungeon_marker":
            continue
        marker_id = anchor.get("id", anchor.get("label"))
        kind = anchor.get("kind")
        position = anchor.get("position")
        if not isinstance(marker_id, str) or not isinstance(kind, str):
            continue
        if not isinstance(position, list) or len(position) != 3:
            continue
        marker: dict[str, Any] = {
            "id": marker_id,
            "kind": kind,
            "position": position,
        }
        old = previous.get(marker_id, {})
        for key in ("reference", "connector"):
            value = anchor.get(key, old.get(key))
            if isinstance(value, str):
                marker[key] = value
        markers.append(marker)
    updated = dict(document)
    updated["markers"] = markers
    _atomic_write_json(piece_path, updated)
    cleaned = dict(exported)
    cleaned.pop("dungeon_piece_id", None)
    cleaned["anchors"] = [
        anchor for anchor in anchors
        if not isinstance(anchor, dict) or anchor.get("type") != "dungeon_marker"
    ]
    return cleaned, True


def _queue_structure_builder_live_open(
    project_root: Path,
    world_path: Path,
    source_path: str,
    size: list[int] | tuple[int, int, int] | None = None,
    *,
    preserve_current: bool = True,
) -> dict[str, Any]:
    managed = _managed_structure_path(project_root, source_path)
    if managed.suffix.lower() != ".nbt" or not managed.is_file():
        raise ValueError("불러올 관리 NBT를 찾을 수 없습니다.")
    data = managed.read_bytes()
    actual_size = read_minecraft_structure_size(data)
    requested_size = tuple(size or actual_size)
    if (
        len(requested_size) != 3
        or any(isinstance(value, bool) or not isinstance(value, int) for value in requested_size)
        or any(value < 1 or value > 256 for value in requested_size)
    ):
        raise ValueError("편집 크기는 1~256 사이 정수 3개여야 합니다.")
    relative = managed.relative_to(project_root).as_posix()
    live_root = _structure_builder_live_root(world_path)
    revision = uuid.uuid4().hex
    _atomic_write_bytes(live_root / "inbox" / "active.nbt", data)
    metadata_target = live_root / "inbox" / "active.structure.json"
    metadata = _live_structure_metadata(project_root, managed, relative)
    if metadata is not None:
        _atomic_write_json(metadata_target, metadata)
    else:
        metadata_target.unlink(missing_ok=True)
    command = {
        "schema_version": 1,
        "action": "open",
        "revision": revision,
        "source": relative,
        "id": managed.relative_to(project_root / "content" / "structures").with_suffix("").as_posix(),
        "size": list(requested_size),
        "source_size": list(actual_size),
        "source_digest": hashlib.sha256(data).hexdigest(),
        "preserve_current": preserve_current,
    }
    _atomic_write_json(live_root / "command.json", command)
    return command


def _merge_live_structure_metadata(
    target: Path, exported: dict[str, Any]
) -> dict[str, Any]:
    editor_owned = {
        "schema_version", "structure", "anchors", "interior", "interior_structure",
    }
    preserved: dict[str, Any] = {}
    if target.is_file():
        existing = load_json(target)
        if not isinstance(existing, dict):
            raise ValueError(f"구조물 메타데이터는 JSON 객체여야 합니다: {target}")
        preserved = {
            key: value for key, value in existing.items()
            if key not in editor_owned
        }
    return {**preserved, **exported}


def _import_structure_builder_live_output(project_root: Path, world_path: Path) -> dict[str, Any] | None:
    live_root = _structure_builder_live_root(world_path)
    result_path = live_root / "outbox" / "result.json"
    nbt_path = live_root / "outbox" / "active.nbt"
    if not result_path.is_file() or not nbt_path.is_file():
        return None
    result = load_json(result_path)
    if not isinstance(result, dict) or result.get("status") != "saved":
        return None
    source = result.get("source")
    revision = result.get("revision")
    if not isinstance(source, str) or not isinstance(revision, str):
        raise ValueError("에딧월드 저장 결과가 올바르지 않습니다.")
    expected_nbt_digest = result.get("nbt_digest")
    expected_metadata_digest = result.get("metadata_digest")
    if not isinstance(expected_nbt_digest, str) or not isinstance(expected_metadata_digest, str):
        return None
    destination = _managed_structure_path(project_root, source)
    if destination.suffix.lower() != ".nbt":
        raise ValueError("에딧월드 저장 대상은 .nbt 파일이어야 합니다.")
    data = nbt_path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_nbt_digest:
        return None
    read_minecraft_structure_size(data)
    metadata_source = live_root / "outbox" / "active.structure.json"
    exported_metadata = None
    if metadata_source.is_file():
        metadata_data = metadata_source.read_bytes()
        if hashlib.sha256(metadata_data).hexdigest() != expected_metadata_digest:
            return None
        try:
            exported_metadata = json.loads(metadata_data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("에딧월드 구조물 메타데이터가 올바른 JSON이 아닙니다.") from error
        if not isinstance(exported_metadata, dict):
            raise ValueError("에딧월드 구조물 메타데이터는 JSON 객체여야 합니다.")
        if exported_metadata.get("structure") != source:
            raise ValueError(
                "에딧월드 NBT와 마커 정보의 구조물 경로가 서로 다릅니다: "
                f"{source} / {exported_metadata.get('structure')}"
            )
    _atomic_write_bytes(destination, data)
    if exported_metadata is not None:
        exported_metadata, dungeon_piece = _sync_live_dungeon_piece_markers(
            project_root, destination, exported_metadata
        )
        metadata_target = destination.with_suffix(".structure.json")
        regular_anchors = exported_metadata.get("anchors", [])
        only_editor_shell = set(exported_metadata) <= {
            "schema_version", "structure", "anchors",
        } and isinstance(regular_anchors, list) and not regular_anchors
        if dungeon_piece and only_editor_shell and not metadata_target.is_file():
            metadata_target.unlink(missing_ok=True)
        else:
            _atomic_write_json(
                metadata_target,
                _merge_live_structure_metadata(metadata_target, exported_metadata),
            )
    receipt = {**result, "imported": True, "imported_at": time.time()}
    _atomic_write_json(live_root / "outbox" / "receipt.json", receipt)
    result_path.unlink(missing_ok=True)
    nbt_path.unlink(missing_ok=True)
    metadata_source.unlink(missing_ok=True)
    return receipt


def _add_external_structure(
    project_root: Path, target_id: str, encoded_nbt: str, encoded_metadata: str | None = None
) -> dict[str, Any]:
    normalized = target_id.strip().replace("\\", "/").removesuffix(".nbt")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_.-]*(/[a-z0-9][a-z0-9_.-]*)*", normalized):
        raise ValueError("NBT ID는 영문 소문자·숫자·점·밑줄·하이픈과 폴더만 사용할 수 있습니다.")
    try:
        data = base64.b64decode(encoded_nbt, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("외부 NBT 데이터가 올바른 Base64가 아닙니다.") from error
    size = read_minecraft_structure_size(data)
    destination = project_root / "content" / "structures" / f"{normalized}.nbt"
    if destination.exists():
        raise ValueError(f"이미 존재하는 NBT입니다: {normalized}")
    _atomic_write_bytes(destination, data)
    if encoded_metadata:
        try:
            metadata_data = base64.b64decode(encoded_metadata, validate=True)
            metadata = json.loads(metadata_data.decode("utf-8"))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError, binascii.Error) as error:
            destination.unlink(missing_ok=True)
            raise ValueError("외부 NBT 메타데이터가 올바른 JSON이 아닙니다.") from error
        if not isinstance(metadata, dict):
            destination.unlink(missing_ok=True)
            raise ValueError("외부 NBT 메타데이터는 JSON 객체여야 합니다.")
        _atomic_write_json(destination.with_suffix(".structure.json"), metadata)
    return {"id": normalized, "path": destination.relative_to(project_root).as_posix(), "size": list(size)}


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
            if not child.is_dir() or not any((child / "saves" / name).is_dir() for name in (
                STRUCTURE_BUILDER_WORLD_NAME, LIVE_NBT_EDITOR_WORLD_NAME
            )):
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
    live_instance_path = settings["live_instance_path"]
    live_instance = Path(live_instance_path) if live_instance_path else None
    live_world = _live_nbt_editor_world_path(live_instance_path)
    output = core_root / "dist" / "cobbleventure-structure-builder-0.1.0-curseforge.zip"
    live_output = core_root / "dist" / "cobbleventure-live-nbt-editor-0.1.0-curseforge.zip"
    live_import = _import_structure_builder_live_output(project_root, live_world) if live_world and live_world.is_dir() else None
    sources = _structure_builder_sources(project_root)
    live = _structure_builder_live_state(live_world)
    active = live.get("active") if isinstance(live, dict) else None
    if isinstance(active, dict) and not live.get("pending"):
        active_source = active.get("source")
        source_entry = next((item for item in sources if item["path"] == active_source), None)
        imported_active = bool(
            isinstance(live_import, dict)
            and live_import.get("imported")
            and live_import.get("source") == active_source
            and source_entry
            and live_import.get("nbt_digest") == source_entry["digest"]
        )
        if (
            source_entry
            and not imported_active
            and source_entry["digest"] != active.get("source_digest")
        ):
            _queue_structure_builder_live_open(
                project_root, live_world, active_source, active.get("size"), preserve_current=False
            )
            live = _structure_builder_live_state(live_world)
    return {
        **settings,
        "world_path": str(world) if world is not None else "",
        "instance_exists": bool(instance and instance.is_dir()),
        "world_exists": bool(world and world.is_dir()),
        "live_world_path": str(live_world) if live_world is not None else "",
        "live_instance_exists": bool(live_instance and live_instance.is_dir()),
        "live_world_exists": bool(live_world and live_world.is_dir()),
        "export_count": _structure_builder_export_count(world),
        "source_count": sum(
            1 for path in (project_root / "content" / "structures").rglob("*.nbt")
            if path.is_file()
        ),
        "sources": sources,
        "live": live,
        "live_import": live_import,
        "package_path": str(output),
        "package_exists": output.is_file(),
        "live_package_path": str(live_output),
        "live_package_exists": live_output.is_file(),
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


def _run_structure_builder_sync(
    project_root: Path, core_root: Path | None = None
) -> dict[str, Any]:
    core_root = (core_root or project_root).resolve()
    status = _structure_builder_status(project_root, core_root)
    instance_path = status["instance_path"]
    if not instance_path:
        raise ValueError("먼저 CurseForge 인스턴스 경로를 저장해 주세요.")
    instance = Path(instance_path)
    if not instance.is_dir():
        raise ValueError(f"CurseForge 인스턴스를 찾을 수 없습니다: {instance}")
    try:
        completed = subprocess.run(
            [
                "cmd.exe", "/d", "/c", str(core_root / "build.bat"),
                "builder-sync", str(instance),
            ],
            cwd=core_root,
            env={**os.environ, "COBBLEVENTURE_PROJECT_PATH": str(project_root)},
            capture_output=True,
            encoding="cp949",
            errors="replace",
            timeout=600,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        output = (error.stdout or b"") if isinstance(error.stdout, bytes) else (error.stdout or "")
        return {
            "success": False,
            "return_code": None,
            "output": f"10분 제한 시간을 초과했습니다.\n{output}",
        }
    output = "\n".join(
        part.strip() for part in (completed.stdout, completed.stderr) if part.strip()
    )
    return {
        "success": completed.returncode == 0,
        "return_code": completed.returncode,
        "output": output or "출력 없음",
        "instance_path": str(instance),
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


def _rct_move_id(value: Any) -> str | None:
    move_id = _short_resource_id(value)
    return move_id.replace("_", "") if move_id else None


def _rct_team_member(
    member: dict[str, Any], mechanics: dict[str, Any] | None = None
) -> dict[str, Any]:
    mechanics = mechanics or {}
    result: dict[str, Any] = {
        "species": _short_resource_id(member.get("species")),
        "level": member.get("level"),
        "moveset": [_rct_move_id(move) for move in member.get("moves", [])],
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
    gimmicks: dict[str, Any] = {}
    tera_type = member.get("tera_type")
    if mechanics.get("terastallization") and isinstance(tera_type, str):
        gimmicks["tera"] = tera_type
    if mechanics.get("dynamax"):
        gimmicks["dynamax"] = True
        if member.get("gigantamax_factor"):
            gimmicks["gmax"] = True
    if gimmicks:
        result["gimmicks"] = gimmicks
    return result


def export_rct_trainer(document: dict[str, Any]) -> dict[str, Any]:
    battle = document["battle"]
    ai = battle["ai"]
    mechanics = battle.get("mechanics", {})
    ai_data: dict[str, Any] = {
        "difficulty": ai["difficulty"],
        "strategy": ai["strategy"],
        "mechanics": {
            "megaEvolution": bool(mechanics.get("mega_evolution")),
            "zMove": bool(mechanics.get("z_move")),
            "dynamax": bool(mechanics.get("dynamax")),
            "terastallization": bool(mechanics.get("terastallization")),
        },
    }
    if ai["difficulty"] == "cheater":
        ai_data["cheatProbability"] = ai["options"]["cheat_probability"]
    result: dict[str, Any] = {
        "name": document.get("name", {}).get("ko_kr") or document["id"],
        "ai": {"type": ai["controller"], "data": ai_data},
        "team": [
            _rct_team_member(member, mechanics)
            for member in battle.get("team", [])
        ],
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


def _write_generated_trainer(
    rct_root: Path, runtime_root: Path, document: dict[str, Any]
) -> None:
    trainer_id = document["battle"]["trainer_id"]
    slug = trainer_id.rsplit("/", 1)[-1]
    targets = (
        (rct_root / f"{slug}.json", export_rct_trainer(document)),
        (runtime_root / f"{slug}.json", export_ai_runtime_profile(document)),
    )
    for target, payload in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


def _cves_item_catalog(dependency_root: Path) -> Path | None:
    candidate = dependency_root / "trainer-data" / "catalogs" / "cobblemon-items.json"
    return candidate if candidate.is_file() else None


def _validate_cves_project(root: Path, dependency_root: Path) -> list[Issue]:
    issues: list[Issue] = []
    try:
        compile_project(root, item_catalog=_cves_item_catalog(dependency_root))
    except CvesSyntaxError as error:
        diagnostic = error.diagnostic
        _issue(
            issues,
            "error",
            Path(diagnostic.span.source),
            f"{diagnostic.span.start.line}:{diagnostic.span.start.column}",
            diagnostic.message,
        )
    except CvesCompilationError as error:
        for diagnostic in error.diagnostics:
            _issue(
                issues,
                "error",
                Path(diagnostic.span.source),
                f"{diagnostic.span.start.line}:{diagnostic.span.start.column}",
                diagnostic.message,
            )
    except (CvesProjectError, ValueError) as error:
        _issue(issues, "error", root / "content" / "events", "$", str(error))
    return issues


def validate_loot_tables(
    root: Path, item_catalog: Path | None = None,
) -> list[Issue]:
    issues: list[Issue] = []
    known_items: set[str] | None = None
    if item_catalog is not None:
        try:
            catalog = load_json(item_catalog)
        except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
            _issue(issues, "error", item_catalog, "$", f"아이템 카탈로그를 읽을 수 없습니다: {error}")
            return issues
        known_items = {
            entry["id"] for entry in catalog.get("items", [])
            if isinstance(entry, dict) and isinstance(entry.get("id"), str)
        }

    source_root = root / "content" / "loot_tables"
    for path in sorted(source_root.rglob("*.json")) if source_root.is_dir() else []:
        try:
            document = load_json(path)
        except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
            _issue(issues, "error", path, "$", f"loot table JSON을 읽을 수 없습니다: {error}")
            continue
        for problem in validate_loot_table_document(document, known_items):
            _issue(issues, "error", path, problem.path, problem.message)
    return issues


def generate_content(
    root: Path, output: Path | None = None, dependency_root: Path | None = None
) -> dict[str, Any]:
    root = root.resolve()
    synchronized_spatial_bounds = synchronize_spatial_build_files(root)
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
        _write_generated_trainer(rct_root, runtime_root, document)
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
        _write_generated_trainer(rct_root, runtime_root, document)
        trainers.append(trainer_id)
    cves_build = compile_project(
        root, item_catalog=_cves_item_catalog((dependency_root or root).resolve())
    )
    write_project(cves_build, output / "cves" / "data")
    return {
        "output": output.as_posix(),
        "trainers": trainers,
        "count": len(trainers),
        "cves_scripts": len(cves_build.scripts),
        "cves_bindings": len(cves_build.bindings),
        "synchronized_spatial_bounds": synchronized_spatial_bounds,
    }


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


def _minecraft_structure_parts_from_root(
    root: dict[str, Any],
) -> tuple[list[int], list[str], list[dict[str, Any]]]:
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


def _minecraft_structure_parts(
    data: bytes,
) -> tuple[list[int], list[str], list[dict[str, Any]]]:
    return _minecraft_structure_parts_from_root(_read_minecraft_structure_root(data))


def read_minecraft_structure_metadata(data: bytes) -> dict[str, Any]:
    """Read template size, building bounds, and its visible top-down blocks."""
    root = _read_minecraft_structure_root(data)
    size, palette_names, blocks = _minecraft_structure_parts_from_root(root)
    palette = root.get("palette", [])
    road_anchors: list[dict[str, Any]] = []
    underground_ports: list[dict[str, Any]] = []
    underground_connectors: list[dict[str, Any]] = []
    underground_entries: list[dict[str, Any]] = []
    for block in root.get("blocks", []):
        if not isinstance(block, dict) or not isinstance(block.get("state"), int):
            continue
        state_index = block["state"]
        if not 0 <= state_index < len(palette) or not isinstance(palette[state_index], dict):
            continue
        block_nbt = block.get("nbt", {})
        if palette[state_index].get("Name") != "minecraft:jigsaw" or not isinstance(block_nbt, dict):
            continue
        marker_name = block_nbt.get("name")
        orientation = palette[state_index].get("Properties", {}).get("orientation", "")
        facing = orientation.split("_", 1)[0]
        position = block.get("pos")
        if (
            facing in {"north", "east", "south", "west", "up", "down"}
            and isinstance(position, list) and len(position) == 3
            and all(isinstance(value, int) for value in position)
        ):
            marker = {
                "position": position,
                "facing": facing,
                "orientation": orientation,
                "final_state": block_nbt.get("final_state", "minecraft:air"),
            }
            if marker_name == "cobbleventure:road_anchor":
                road_anchors.append(marker)
            elif marker_name == "cobbleventure:underground_entry":
                underground_entries.append({"name": marker_name, **marker})
            elif isinstance(marker_name, str) and marker_name.startswith("cobbleventure:underground_port/"):
                tag = marker_name.removeprefix("cobbleventure:underground_port/")
                if CHOICE_ID.fullmatch(tag):
                    underground_ports.append({"tag": tag, "name": marker_name, **marker})
                    underground_connectors.append({"tag": tag, "name": marker_name, **marker})
            elif isinstance(marker_name, str) and marker_name.startswith("cobbleventure:underground_connector/"):
                tag = marker_name.removeprefix("cobbleventure:underground_connector/")
                if CHOICE_ID.fullmatch(tag):
                    underground_connectors.append({"tag": tag, "name": marker_name, **marker})
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
    preview_blocks: list[tuple[int, int, int, str]] = []
    top_columns: dict[tuple[int, int], tuple[int, str]] = {}
    cutaway_columns: dict[tuple[int, int], tuple[int, str]] = {}
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
                    preview_blocks.append((x, y, z, block_name))
                    current = top_columns.get((x, z))
                    if current is None or y > current[0]:
                        top_columns[(x, z)] = (y, block_name)
                if block_name not in ignored_blocks:
                    occupied.append((x, y, z))
    midpoint_y = max(1, (size[1] + 1) // 2)
    layer_counts: dict[int, int] = {}
    for _, y, _, _ in preview_blocks:
        layer_counts[y] = layer_counts.get(y, 0) + 1
    dense_layers = [
        y for y, count in layer_counts.items()
        if 0 < y <= midpoint_y and count >= max(1, size[0] * size[2] * 0.75)
    ]
    cutaway_y = max(dense_layers) if dense_layers else midpoint_y
    cutaway_columns = {}
    for x, y, z, block_name in preview_blocks:
        if y >= cutaway_y:
            continue
        current = cutaway_columns.get((x, z))
        if current is None or y > current[0]:
            cutaway_columns[(x, z)] = (y, block_name)
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
        "road_anchors": road_anchors,
        "underground_ports": underground_ports,
        "underground_connectors": underground_connectors,
        "underground_entries": underground_entries,
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
    midpoint_y = max(1, (size[1] + 1) // 2)
    layer_counts: dict[int, int] = {}
    for _, y, _ in occupied:
        layer_counts[y] = layer_counts.get(y, 0) + 1
    dense_layers = [
        y for y, count in layer_counts.items()
        if 0 < y <= midpoint_y and count >= max(1, size[0] * size[2] * 0.75)
    ]
    # A complete intermediate slab hides the rooms below when the structure is
    # cut at its mathematical midpoint. Cut immediately below the closest slab
    # instead, which produces the architectural dollhouse view users expect.
    cutaway_y = max(dense_layers) if dense_layers else midpoint_y
    cutaway_columns: dict[tuple[int, int], tuple[int, str]] = {}
    for (x, y, z), block_name in occupied.items():
        if y >= cutaway_y:
            continue
        current = cutaway_columns.get((x, z))
        if current is None or y > current[0]:
            cutaway_columns[(x, z)] = (y, block_name)
    cutaway_palette = sorted({
        block_name for _, block_name in cutaway_columns.values()
    })
    cutaway_palette_indexes = {
        block_name: index for index, block_name in enumerate(cutaway_palette)
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
        "cutaway_view": {
            "cutoff_y": cutaway_y,
            "palette": cutaway_palette,
            "blocks": [
                [x, z, y, cutaway_palette_indexes[block_name]]
                for (x, z), (y, block_name) in sorted(
                    cutaway_columns.items(),
                    key=lambda item: (item[0][1], item[0][0]),
                )
            ],
        },
    }
def read_minecraft_structure_size(data: bytes) -> tuple[int, int, int]:
    """Read only the top-level size tag without materializing every block."""
    if data.startswith(b"\x1f\x8b"):
        data = gzip.decompress(data)
    stream = io.BytesIO(data)
    root_type = stream.read(1)
    if not root_type or root_type[0] != 10:
        raise ValueError("마인크래프트 구조물 NBT의 루트가 Compound가 아닙니다.")
    _read_nbt_string(stream)
    while True:
        tag_data = stream.read(1)
        if not tag_data:
            raise ValueError("NBT Compound가 손상되었습니다.")
        tag_type = tag_data[0]
        if tag_type == 0:
            break
        name = _read_nbt_string(stream)
        if name == "size":
            size = _read_nbt_payload(stream, tag_type)
            if (
                tag_type == 9 and isinstance(size, list) and len(size) == 3
                and all(isinstance(value, int) and 0 < value <= 512 for value in size)
            ):
                return size[0], size[1], size[2]
            raise ValueError("구조물 size 태그 형식이 올바르지 않습니다.")
        _skip_nbt_payload(stream, tag_type)
    raise ValueError("구조물 size 태그를 찾을 수 없습니다.")


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
    "natural_feature": "자연물·동굴",
    "underground_road_module": "지하통로 조각",
    "underground_entrance": "지하통로 지상 입구",
}
STRUCTURE_DIRECTORY = re.compile(
    r"[a-z0-9][a-z0-9_.-]*(?:/[a-z0-9][a-z0-9_.-]*)*"
)


def managed_structure_files(root: Path) -> dict[str, Path]:
    source_root = root / "content" / "structures"
    if not source_root.is_dir():
        return {}
    return {
        f"cobbleventure:{path.relative_to(source_root).with_suffix('').as_posix()}": path
        for path in sorted(source_root.rglob("*.nbt"))
        if path.is_file()
    }


def load_managed_structure_catalog(root: Path) -> dict[str, dict[str, Any]]:
    structures: dict[str, dict[str, Any]] = {}
    for resource_id, path in managed_structure_files(root).items():
        structures[resource_id] = {
            **read_minecraft_structure_metadata(path.read_bytes()),
            "source": path.relative_to(root).as_posix(),
        }
    return structures


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
    if parts[:1] == ("underground_road_modules",):
        return "underground_road_module"
    if parts[:1] == ("underground_entrance",):
        return "underground_entrance"
    if parts[:1] in {
        ("cave_entrance",), ("forest_entrance",), ("forest_gate",), ("gate",),
    }:
        return "natural_feature"
    return "building"


def _configured_structure_category(relative: Path, settings: Any) -> str:
    if isinstance(settings, dict):
        category = settings.get("structure_category")
        if category in STRUCTURE_CATEGORY_LABELS:
            return category
    return _managed_structure_category(relative)


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
        if anchor.get("safe_side") in {"north", "east", "south", "west"}:
            entry["safe_side"] = anchor["safe_side"]
        if anchor.get("door_facing") in {"north", "east", "south", "west"}:
            entry["door_facing"] = anchor["door_facing"]
        if anchor.get("facing") in {"north", "east", "south", "west"}:
            entry["facing"] = anchor["facing"]
        if isinstance(anchor.get("entrance_id"), str):
            entry["entrance_id"] = anchor["entrance_id"]
        result.append(entry)
    return result


def _structure_dungeon_marker_summary(path: Path) -> dict[str, Any]:
    """Return fixed-dungeon capabilities and preview markers from the sidecar."""
    kinds = {
        "entry": 0,
        "exit": 0,
        "encounter": 0,
        "boss": 0,
        "loot": 0,
        "healing_station": 0,
        "gate": 0,
        "objective": 0,
        "checkpoint": 0,
    }
    metadata_path = path.with_suffix(".structure.json")
    if not metadata_path.is_file():
        return {
            "available": False,
            "marker_count": 0,
            "slot_count": 0,
            "kinds": kinds,
            "markers": [],
            "has_entry": False,
            "has_exit": False,
            "has_boss": False,
        }
    document = load_json(metadata_path)
    anchors = document.get("anchors", []) if isinstance(document, dict) else []
    markers: list[dict[str, Any]] = []
    for anchor in anchors if isinstance(anchors, list) else []:
        if not isinstance(anchor, dict) or anchor.get("type") != "dungeon_marker":
            continue
        kind = anchor.get("kind")
        position = anchor.get("position")
        if (
            kind not in kinds
            or not isinstance(position, list) or len(position) != 3
            or any(not isinstance(value, int) or isinstance(value, bool) for value in position)
        ):
            continue
        kinds[kind] += 1
        marker = {"kind": kind, "position": position}
        for key in ("id", "reference"):
            if isinstance(anchor.get(key), str) and DOCUMENT_SLUG.fullmatch(anchor[key]):
                marker[key] = anchor[key]
        markers.append(marker)
    marker_count = sum(kinds.values())
    slot_count = marker_count - kinds["entry"] - kinds["exit"]
    return {
        "available": marker_count > 0,
        "marker_count": marker_count,
        "slot_count": slot_count,
        "kinds": kinds,
        "markers": markers,
        "has_entry": kinds["entry"] > 0,
        "has_exit": kinds["exit"] > 0,
        "has_boss": kinds["boss"] > 0,
    }


def _default_building_settings() -> dict[str, Any]:
    return {
        "schema_version": 1,
        "facility_defaults": {
            "pokemon_center": "bca:default/one_off/pokecenter",
            "pokemart": "bca:default/one_off/structure_pokemart",
            "department_store": "cobbleventure:facilities/department_store",
        },
        "buildings": {},
    }


def load_building_settings(root: Path) -> dict[str, Any]:
    path = root / BUILDING_SETTINGS_PATH
    document = load_json(path) if path.is_file() else _default_building_settings()
    if not isinstance(document, dict) or document.get("schema_version") != 1:
        raise ValueError("건물 설정 schema_version은 1이어야 합니다.")
    buildings = document.get("buildings", {})
    if not isinstance(buildings, dict):
        raise ValueError("건물 설정 buildings는 객체여야 합니다.")
    defaults = document.get("facility_defaults", {})
    if not isinstance(defaults, dict):
        raise ValueError("건물 설정 facility_defaults는 객체여야 합니다.")
    return {
        "schema_version": 1,
        "facility_defaults": defaults,
        "buildings": buildings,
    }


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


def dungeon_entrance_catalog(root: Path) -> list[dict[str, str]]:
    """List dungeon entrances that can be assigned to a structure door."""
    result: list[dict[str, str]] = []
    seen: set[str] = set()
    dungeon_root = root / "content" / "dungeons"
    if not dungeon_root.is_dir():
        return result
    for path in sorted(dungeon_root.rglob("*.json")):
        try:
            document = load_json(path)
        except (OSError, json.JSONDecodeError, DuplicateKeyError):
            continue
        if not isinstance(document, dict):
            continue
        dungeon_id = document.get("dungeon_id")
        display = document.get("display_name", {})
        display_name = (
            display.get("ko_kr", display.get("en_us", dungeon_id))
            if isinstance(display, dict) else dungeon_id
        )
        for entrance in document.get("entrances", []):
            entrance_id = entrance.get("entrance_id") if isinstance(entrance, dict) else None
            if not isinstance(entrance_id, str) or entrance_id in seen:
                continue
            seen.add(entrance_id)
            result.append({
                "entrance_id": entrance_id,
                "dungeon_id": dungeon_id if isinstance(dungeon_id, str) else "",
                "display_name": display_name if isinstance(display_name, str) else entrance_id,
                "path": path.relative_to(root).as_posix(),
            })
    return result


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
    structures = {}
    for resource_id, metadata in full_structures.items():
        structure = {
            key: metadata[key]
            for key in (
                "category", "category_label", "width", "height", "depth",
                "door_anchors", "arrival_anchors", "transition_anchors",
                "dungeon_entrance_anchors", "cutaway_view",
            )
            if key in metadata
        }
        metadata_settings = metadata.get("settings", {})
        structure["no_interior_space"] = bool(
            metadata_settings.get("no_interior_space", False)
            if isinstance(metadata_settings, dict) else False
        )
        structures[resource_id] = structure
    settings = load_building_settings(root)["buildings"]
    graphs: list[dict[str, Any]] = []

    for exterior_id, entry in sorted(settings.items()):
        if exterior_id not in structures or not isinstance(entry, dict):
            continue
        if entry.get("no_interior_space", False):
            continue
        if structures[exterior_id].get("category") in {
            "interior", "gym_interior", "league", "gym_exterior", "decoration",
            "natural_feature",
        }:
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
                "to": {
                    "node": target.get("space", ""),
                    "anchor": target.get("door", target.get("arrival", "")),
                },
                **{
                    key: target[key]
                    for key in (
                        "condition_mode", "conditions", "locked_dialogue", "enter_dialogue",
                    )
                    if key in target
                },
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
    available_entrance_ids = {
        entry["entrance_id"] for entry in dungeon_entrance_catalog(root)
    }
    saved_assignments = saved.get("dungeon_entrance_assignments", [])
    dungeon_assignments = []
    for assignment in saved_assignments if isinstance(saved_assignments, list) else []:
        if not isinstance(assignment, dict):
            continue
        structure = assignment.get("structure")
        anchor = assignment.get("anchor")
        entrance_id = assignment.get("entrance_id")
        structure_metadata = structures.get(structure, {})
        entrance_labels = {
            item.get("label")
            for field in ("door_anchors", "transition_anchors")
            for item in structure_metadata.get(field, [])
            if isinstance(item, dict)
        }
        if (
            isinstance(structure, str) and isinstance(anchor, str)
            and anchor in entrance_labels and entrance_id in available_entrance_ids
        ):
            dungeon_assignments.append({
                "structure": structure,
                "anchor": anchor,
                "entrance_id": entrance_id,
            })
    return {
        "schema_version": 1, "graphs": graphs, "structures": structures,
        "available_dungeon_entrances": dungeon_entrance_catalog(root),
        "dungeon_entrance_assignments": dungeon_assignments,
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
    assignment_field_provided = "dungeon_entrance_assignments" in data
    dungeon_assignments = data.get("dungeon_entrance_assignments", [])
    if not isinstance(dungeon_assignments, list):
        return [Issue(
            "error", path.as_posix(), "$.dungeon_entrance_assignments",
            "던전 입구 지정 배열이 필요합니다.",
        )]

    building_document = load_building_settings(root)
    building_settings = building_document["buildings"]
    gyms_path = root / "content" / "catalogs" / "gyms.json"
    gym_catalog = load_json(gyms_path) if gyms_path.is_file() else {"schema_version": 1, "gyms": [], "leagues": []}
    gyms_by_id = {
        gym.get("id"): gym for gym in gym_catalog.get("gyms", [])
        if isinstance(gym, dict) and isinstance(gym.get("id"), str)
    }
    structure_paths = managed_structure_files(root)
    if not assignment_field_provided:
        saved = load_json(path) if path.is_file() else {}
        dungeon_assignments = (
            saved.get("dungeon_entrance_assignments", [])
            if isinstance(saved, dict) else []
        )
    structure_categories = {
        resource_id: _configured_structure_category(
            path.relative_to(root / "content" / "structures"),
            building_settings.get(resource_id, {}),
        )
        for resource_id, path in structure_paths.items()
    }
    door_labels_by_structure = {
        resource_id: {
            anchor["label"] for anchor in _structure_named_anchors(
                structure_path, {"door"}
            )
        }
        for resource_id, structure_path in structure_paths.items()
    }
    dungeon_anchor_labels_by_structure = {
        resource_id: {
            anchor["label"] for anchor in _structure_named_anchors(
                structure_path, {"door", "transition"}
            )
        }
        for resource_id, structure_path in structure_paths.items()
    }
    connection_labels_by_structure = {
        resource_id: {
            anchor["label"] for anchor in _structure_named_anchors(
                structure_path, {"door", "transition"}
            )
        }
        for resource_id, structure_path in structure_paths.items()
    }
    available_entrance_ids = {
        entry["entrance_id"] for entry in dungeon_entrance_catalog(root)
    }
    normalized_dungeon_assignments: dict[tuple[str, str], str] = {}
    assigned_entrance_ids: set[str] = set()
    for assignment_index, assignment in enumerate(dungeon_assignments):
        assignment_path = f"$.dungeon_entrance_assignments[{assignment_index}]"
        if not isinstance(assignment, dict):
            _issue(issues, "error", path, assignment_path, "던전 입구 지정은 객체여야 합니다.")
            continue
        structure = assignment.get("structure")
        anchor_label = assignment.get("anchor")
        entrance_id = assignment.get("entrance_id")
        if not isinstance(structure, str) or structure not in structure_paths:
            _issue(issues, "error", path, f"{assignment_path}.structure", "관리 중인 NBT 구조물이 필요합니다.")
            continue
        if not isinstance(anchor_label, str) or not DOCUMENT_SLUG.fullmatch(anchor_label):
            _issue(issues, "error", path, f"{assignment_path}.anchor", "출입 앵커 이름이 필요합니다.")
            continue
        if anchor_label not in dungeon_anchor_labels_by_structure.get(structure, set()):
            _issue(
                issues, "error", path, f"{assignment_path}.anchor",
                "에딧월드에서 지정된 door 또는 transition 앵커가 필요합니다.",
            )
            continue
        if entrance_id not in available_entrance_ids:
            _issue(issues, "error", path, f"{assignment_path}.entrance_id", "존재하는 던전 입구 ID가 필요합니다.")
            continue
        key = (structure, anchor_label)
        if key in normalized_dungeon_assignments:
            _issue(issues, "error", path, assignment_path, "같은 출입 앵커를 두 던전에 지정할 수 없습니다.")
            continue
        if entrance_id in assigned_entrance_ids:
            _issue(issues, "error", path, f"{assignment_path}.entrance_id", "같은 던전 입구를 두 출입 앵커에 지정할 수 없습니다.")
            continue
        normalized_dungeon_assignments[key] = entrance_id
        assigned_entrance_ids.add(entrance_id)

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
        owner_settings = building_settings.get(owner, {})
        if kind == "building" and (
            structure_categories.get(owner) in {
                "interior", "gym_interior", "league", "gym_exterior", "decoration",
                "natural_feature",
            }
            or (
                isinstance(owner_settings, dict)
                and owner_settings.get("no_interior_space", False)
            )
        ):
            _issue(issues, "error", path, f"{graph_path}.owner", "외부 건물만 연결도의 시작 공간이 될 수 있습니다.")
            continue
        if not isinstance(nodes, list) or not isinstance(connections, list):
            _issue(issues, "error", path, graph_path, "노드와 연결선 배열이 필요합니다.")
            continue
        normalized_nodes: list[dict[str, Any]] = []
        node_structures: dict[str, str] = {}
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
            if isinstance(node.get("structure"), str):
                node_structures[node_id] = node["structure"]
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
            if source.get("node") == target.get("node") and source.get("anchor") == target.get("anchor"):
                _issue(issues, "error", path, edge_path, "같은 문을 자기 자신에게 연결할 수 없습니다.")
                continue
            source_key = (node_structures.get(source.get("node"), ""), source.get("anchor"))
            target_key = (node_structures.get(target.get("node"), ""), target.get("anchor"))
            if source_key in normalized_dungeon_assignments or target_key in normalized_dungeon_assignments:
                _issue(issues, "error", path, edge_path, "던전 입구로 지정한 앵커는 일반 공간 연결선에 사용할 수 없습니다.")
                continue
            anchor_catalog = connection_labels_by_structure if kind == "building" else door_labels_by_structure
            source_doors = anchor_catalog.get(node_structures.get(source.get("node"), ""), set())
            target_doors = anchor_catalog.get(node_structures.get(target.get("node"), ""), set())
            if source.get("anchor") not in source_doors or target.get("anchor") not in target_doors:
                message = "연결 양쪽 모두 NBT에 저장된 문 또는 접촉 전환 앵커여야 합니다." if kind == "building" else "연결 양쪽 모두 NBT에 저장된 실제 문 앵커여야 합니다."
                _issue(issues, "error", path, edge_path, message)
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
                "structure_category": structure_categories.get(owner, "building"),
                "fixed_npcs": current.get("fixed_npcs", {}) if isinstance(current, dict) else {},
                "fixed_pokemon": current.get("fixed_pokemon", {}) if isinstance(current, dict) else {},
                "fixed_gacha_machines": current.get("fixed_gacha_machines", {}) if isinstance(current, dict) else {},
                "citizen_placement_allowed": bool(current.get("citizen_placement_allowed", False)) if isinstance(current, dict) else False,
                "interiors": [{"key": node["id"], "structure": node.get("structure", "")} for node in interiors],
                "door_routes": {
                    f"{edge['from']['node']}:{edge['from']['anchor']}": {
                        "space": edge["to"]["node"], "door": edge["to"]["anchor"],
                        **{
                            key: edge[key]
                            for key in (
                                "condition_mode", "conditions",
                                "locked_dialogue", "enter_dialogue",
                            )
                            if key in edge
                        },
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
        "dungeon_entrance_assignments": [
            {
                "structure": structure,
                "anchor": anchor,
                "entrance_id": entrance_id,
            }
            for (structure, anchor), entrance_id
            in sorted(normalized_dungeon_assignments.items())
        ],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)
    return issues


def building_settings_payload(
    root: Path,
    managed_catalog: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    settings = load_building_settings(root)
    configured = settings["buildings"]
    structures: dict[str, dict[str, Any]] = {}
    for resource_id, path in managed_structure_files(root).items():
        metadata = (
            managed_catalog.get(resource_id)
            if managed_catalog is not None else None
        )
        if metadata is None:
            metadata = read_minecraft_structure_metadata(path.read_bytes())
        relative = path.relative_to(root / "content" / "structures")
        residential = bool(relative.parts and relative.parts[0] == "houses")
        entry = configured.get(resource_id, {})
        if not isinstance(entry, dict):
            entry = {}
        category = _configured_structure_category(relative, entry)
        structures[resource_id] = {
            **metadata,
            "source": path.relative_to(root).as_posix(),
            "category": category,
            "category_label": STRUCTURE_CATEGORY_LABELS[category],
            "npc_labels": _structure_npc_labels(path),
            "door_anchors": _structure_named_anchors(
                path, {"door"}
            ),
            "arrival_anchors": _structure_named_anchors(
                path, {"arrival", "interior_spawn", "exterior_spawn"}
            ),
            "transition_anchors": _structure_named_anchors(path, {"transition"}),
            "dungeon_entrance_anchors": _structure_named_anchors(
                path, {"dungeon_entrance"}
            ),
            "dungeon_markers": _structure_dungeon_marker_summary(path),
            "residential": residential,
            "settings": {
                "placement_y_offset": entry.get("placement_y_offset", 0)
                if isinstance(entry.get("placement_y_offset", 0), int)
                and not isinstance(entry.get("placement_y_offset", 0), bool) else 0,
                "structure_category": category,
                "music_track": entry.get("music_track", "")
                if isinstance(entry.get("music_track", ""), str) else "",
                "no_interior_space": bool(entry.get("no_interior_space", False)),
                "town_placement": entry.get("town_placement", {
                    "enabled": False,
                    "id": "",
                    "label": "",
                    "note": "",
                    "color": "#64748b",
                }) if isinstance(entry.get("town_placement", {}), dict) else {
                    "enabled": False,
                    "id": "",
                    "label": "",
                    "note": "",
                    "color": "#64748b",
                },
                "fixed_npcs": entry.get("fixed_npcs", {})
                if isinstance(entry.get("fixed_npcs", {}), dict) else {},
                "fixed_pokemon": entry.get("fixed_pokemon", {})
                if isinstance(entry.get("fixed_pokemon", {}), dict) else {},
                "fixed_gacha_machines": entry.get("fixed_gacha_machines", {})
                if isinstance(entry.get("fixed_gacha_machines", {}), dict) else {},
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
        "facility_defaults": settings["facility_defaults"],
        "structures": structures,
        "npcs": _list_documents(root, "trainers"),
        "path": BUILDING_SETTINGS_PATH.as_posix(),
    }


def copy_managed_exterior_structure(
    root: Path, source_resource_id: str, target_directory: str, target_slug: str,
) -> dict[str, Any]:
    """Clone a managed exterior NBT and its authored/runtime metadata."""
    structures = managed_structure_files(root)
    source = structures.get(source_resource_id)
    if source is None:
        raise ValueError("복사할 관리 NBT 구조물을 찾을 수 없습니다.")
    source_root = root / "content" / "structures"
    source_relative = source.relative_to(source_root)
    settings_path = root / BUILDING_SETTINGS_PATH
    settings_document = load_building_settings(root)
    source_settings = settings_document["buildings"].get(source_resource_id, {})
    category = _configured_structure_category(source_relative, source_settings)
    if category in {"interior", "gym_interior"}:
        raise ValueError("내부 NBT는 외부 NBT 복사 기능으로 추가할 수 없습니다.")
    if not isinstance(target_slug, str) or not DOCUMENT_SLUG.fullmatch(target_slug):
        raise ValueError("새 NBT ID는 영문 소문자·숫자·밑줄만 사용할 수 있습니다.")
    if (
        not isinstance(target_directory, str)
        or not STRUCTURE_DIRECTORY.fullmatch(target_directory)
    ):
        raise ValueError(
            "리소스 경로는 영문 소문자·숫자·밑줄과 슬래시만 사용할 수 있습니다."
        )

    target = source_root / target_directory / f"{target_slug}.nbt"
    target_relative = target.relative_to(source_root)
    target_resource_id = f"cobbleventure:{target_relative.with_suffix('').as_posix()}"
    target_sidecar = target.with_suffix(".structure.json")
    if target.is_file() or target_sidecar.is_file() or target_resource_id in structures:
        raise ValueError(f"이미 존재하는 NBT ID입니다: {target_resource_id}")

    source_metadata = read_minecraft_structure_metadata(source.read_bytes())
    source_sidecar = source.with_suffix(".structure.json")
    sidecar_document: dict[str, Any] | None = None
    if source_sidecar.is_file():
        loaded = load_json(source_sidecar)
        if not isinstance(loaded, dict):
            raise ValueError("원본 NBT 메타데이터가 객체가 아닙니다.")
        sidecar_document = copy.deepcopy(loaded)
        if "structure" in sidecar_document:
            sidecar_document["structure"] = target.relative_to(root).as_posix()

    target_settings = (
        copy.deepcopy(source_settings) if isinstance(source_settings, dict) else {}
    )
    target_settings["structure_category"] = category
    settings_document["buildings"][target_resource_id] = target_settings

    target.parent.mkdir(parents=True, exist_ok=True)
    settings_path.parent.mkdir(parents=True, exist_ok=True)
    nbt_temporary = target.with_name(target.name + ".copy.tmp")
    sidecar_temporary = target_sidecar.with_name(target_sidecar.name + ".copy.tmp")
    settings_temporary = settings_path.with_name(settings_path.name + ".copy.tmp")
    created: list[Path] = []
    try:
        nbt_temporary.write_bytes(source.read_bytes())
        if sidecar_document is not None:
            sidecar_temporary.write_text(
                json.dumps(sidecar_document, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
        settings_temporary.write_text(
            json.dumps(settings_document, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        nbt_temporary.replace(target)
        created.append(target)
        if sidecar_document is not None:
            sidecar_temporary.replace(target_sidecar)
            created.append(target_sidecar)
        settings_temporary.replace(settings_path)
    except Exception:
        for path in (nbt_temporary, sidecar_temporary, settings_temporary):
            path.unlink(missing_ok=True)
        for path in reversed(created):
            path.unlink(missing_ok=True)
        raise

    return {
        "structure": target_resource_id,
        "source_structure": source_resource_id,
        "source": target.relative_to(root).as_posix(),
        "category": category,
        "category_label": STRUCTURE_CATEGORY_LABELS[category],
        "sidecar_copied": sidecar_document is not None,
        "settings_copied": isinstance(source_settings, dict) and bool(source_settings),
        **source_metadata,
    }


class StructureResizeAnchorConflict(ValueError):
    def __init__(self, anchors: list[dict[str, Any]]) -> None:
        self.anchors = anchors
        labels = ", ".join(str(anchor["label"]) for anchor in anchors)
        super().__init__(f"축소 범위 밖 앵커가 있습니다: {labels}")


def resize_managed_structure(
    root: Path, resource_id: str, width: int, height: int, depth: int,
    *, preview: bool = False, remove_out_of_bounds_anchors: bool = False,
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
    anchor_conflicts: list[dict[str, Any]] = []
    if sidecar.is_file():
        sidecar_document = load_json(sidecar)
        for index, anchor in enumerate(sidecar_document.get("anchors", [])):
            if not isinstance(anchor, dict):
                continue
            fields: list[dict[str, Any]] = []
            for field in ("position", "safe_spawn"):
                position = anchor.get(field)
                if not isinstance(position, list) or len(position) != 3:
                    continue
                x, y, z = position
                if not (0 <= x < width and 0 <= y < height and 0 <= z < depth):
                    fields.append({"field": field, "position": position})
            if fields:
                anchor_conflicts.append({
                    "index": index,
                    "label": anchor.get("label", anchor.get("id", index)),
                    "type": anchor.get("type", "anchor"),
                    "fields": fields,
                })
        interior = sidecar_document.get("interior")
        if isinstance(interior, dict):
            if width < 5 or depth < 5:
                raise ValueError("내부공간의 너비와 깊이는 5 이상이어야 합니다.")
            floors = interior.get("floors", 1)
            if not isinstance(floors, int) or floors < 1 or height % floors != 0:
                raise ValueError("내부공간 높이는 현재 층수로 정확히 나누어져야 합니다.")
            floor_height = height // floors
            if not 3 <= floor_height <= 80:
                raise ValueError("층당 높이는 3~80 블록이어야 합니다. 전체 높이는 80블록을 넘을 수 없습니다.")
            interior.update({
                "width": width, "depth": depth,
                "floor_height": floor_height, "floors": floors,
            })

    if preview:
        return {
            "structure": resource_id, "width": width, "height": height, "depth": depth,
            "anchor_conflicts": anchor_conflicts,
        }
    if anchor_conflicts and not remove_out_of_bounds_anchors:
        raise StructureResizeAnchorConflict(anchor_conflicts)
    if anchor_conflicts and sidecar_document is not None:
        removed_indices = {item["index"] for item in anchor_conflicts}
        sidecar_document["anchors"] = [
            anchor for index, anchor in enumerate(sidecar_document.get("anchors", []))
            if index not in removed_indices
        ]

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
        "removed_anchors": anchor_conflicts,
        "npc_labels": _structure_npc_labels(path),
        "door_anchors": _structure_named_anchors(
            path, {"door"}
        ),
        "arrival_anchors": _structure_named_anchors(
            path, {"arrival", "interior_spawn", "exterior_spawn"}
        ),
        **read_minecraft_structure_metadata(resized),
    }


def validate_building_npc_positions(
    root: Path, buildings: dict[str, Any] | None = None,
) -> list[Issue]:
    """Validate reachable indoor spawn positions for citizen-enabled buildings."""
    settings_path = root / BUILDING_SETTINGS_PATH
    issues: list[Issue] = []
    if buildings is None:
        try:
            buildings = load_building_settings(root)["buildings"]
        except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
            return [Issue("error", settings_path.as_posix(), "$", f"건물 설정을 읽을 수 없습니다: {error}")]
    if not isinstance(buildings, dict):
        return [Issue("error", settings_path.as_posix(), "$.buildings", "건물 설정 객체가 필요합니다.")]

    structure_files = managed_structure_files(root)
    free_blocks = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
    slot_cache: dict[Path, list[str]] = {}

    def valid_slots(structure: Path) -> list[str]:
        if structure in slot_cache:
            return slot_cache[structure]
        sidecar = structure.with_suffix(".structure.json")
        if not sidecar.is_file():
            slot_cache[structure] = []
            return []
        try:
            metadata = load_json(sidecar)
            size, palette, blocks = _minecraft_structure_parts(structure.read_bytes())
        except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
            _issue(issues, "error", sidecar, "$", f"NPC 위치 검사를 위한 구조물을 읽을 수 없습니다: {error}")
            slot_cache[structure] = []
            return []
        block_names = {
            tuple(block["pos"]): palette[block["state"]]
            for block in blocks
            if isinstance(block, dict)
            and isinstance(block.get("pos"), list) and len(block["pos"]) == 3
            and isinstance(block.get("state"), int) and 0 <= block["state"] < len(palette)
        }
        result: list[str] = []
        anchors = metadata.get("anchors", []) if isinstance(metadata, dict) else []
        for index, anchor in enumerate(anchors if isinstance(anchors, list) else []):
            if not isinstance(anchor, dict) or anchor.get("type") != "npc_position":
                continue
            anchor_path = f"$.anchors[{index}]"
            label = anchor.get("label", anchor.get("id"))
            position = anchor.get("position")
            if not isinstance(label, str) or not DOCUMENT_SLUG.fullmatch(label):
                _issue(issues, "error", sidecar, f"{anchor_path}.label", "NPC 위치에 올바른 소문자 라벨이 필요합니다.")
                continue
            if not (
                isinstance(position, list) and len(position) == 3
                and all(isinstance(value, int) and not isinstance(value, bool) for value in position)
            ):
                _issue(issues, "error", sidecar, f"{anchor_path}.position", "NPC 위치는 정수 좌표 3개여야 합니다.")
                continue
            x, y, z = position
            if not (0 <= x < size[0] and 1 <= y < size[1] - 1 and 0 <= z < size[2]):
                _issue(issues, "error", sidecar, f"{anchor_path}.position", "NPC 위치와 머리 공간이 구조물 범위 안에 있어야 합니다.")
                continue
            feet = block_names.get((x, y, z), "minecraft:air")
            head = block_names.get((x, y + 1, z), "minecraft:air")
            floor = block_names.get((x, y - 1, z), "minecraft:air")
            seated = _is_npc_seat_block(feet)
            if (feet not in free_blocks and not seated) or head not in free_blocks:
                _issue(
                    issues, "error", sidecar, f"{anchor_path}.position",
                    "NPC 발 위치는 빈 공간 또는 의자여야 하며 머리 위치는 비어 있어야 합니다.",
                )
                continue
            if floor in free_blocks or floor == "minecraft:structure_void":
                _issue(issues, "error", sidecar, f"{anchor_path}.position", "NPC 위치 바로 아래에 바닥 블록이 필요합니다.")
                continue
            result.append(label)
        slot_cache[structure] = result
        return result

    for resource_id, settings in sorted(buildings.items()):
        if not isinstance(settings, dict) or settings.get("citizen_placement_allowed") is not True:
            continue
        entry_path = f"$.buildings.{resource_id}"
        if settings.get("no_interior_space") is True:
            _issue(
                issues, "error", settings_path, f"{entry_path}.citizen_placement_allowed",
                "내부 공간이 없는 건물은 자동 시민을 받을 수 없습니다.",
            )
            continue
        interiors = settings.get("interiors", [])
        if not isinstance(interiors, list):
            continue
        interior_slots: dict[str, list[str]] = {}
        for index, interior in enumerate(interiors):
            if not isinstance(interior, dict):
                continue
            key = interior.get("key")
            structure_id = interior.get("structure")
            structure = structure_files.get(structure_id) if isinstance(structure_id, str) else None
            if isinstance(key, str) and structure is not None:
                interior_slots[key] = valid_slots(structure)

        edges: list[tuple[str, str]] = []
        routes = settings.get("door_routes", {})
        for source, target in routes.items() if isinstance(routes, dict) else []:
            if not isinstance(source, str) or ":" not in source or not isinstance(target, dict):
                continue
            source_space = source.split(":", 1)[0]
            target_space = target.get("space")
            if isinstance(target_space, str):
                edges.append((source_space, target_space))
        reachable = {"exterior"}
        changed = True
        while changed:
            changed = False
            for left, right in edges:
                if left in reachable and right not in reachable:
                    reachable.add(right)
                    changed = True
                if right in reachable and left not in reachable:
                    reachable.add(left)
                    changed = True

        for index, interior in enumerate(interiors):
            key = interior.get("key") if isinstance(interior, dict) else None
            if isinstance(key, str) and interior_slots.get(key) and key not in reachable:
                _issue(
                    issues, "warning", settings_path, f"{entry_path}.interiors[{index}]",
                    "NPC 위치가 있지만 외부 출입문에서 이 내부 공간으로 들어가는 경로가 없습니다.",
                )
        capacity = sum(len(interior_slots.get(space, [])) for space in reachable if space != "exterior")
        if capacity == 0 and interiors:
            _issue(
                issues, "warning", settings_path, f"{entry_path}.citizen_placement_allowed",
                "내부공간을 직접 연결한 건물에는 외부에서 접근 가능한 npc_position이 하나 이상 필요합니다.",
            )
    return issues


def _is_npc_seat_block(block_name: str) -> bool:
    """Return whether an authored block can act as an NPC seat."""
    path = block_name.partition(":")[2] or block_name
    return any(
        path == suffix or path.endswith(f"_{suffix}")
        for suffix in ("chair", "stool", "seat", "bench")
    )


@functools.lru_cache(maxsize=1)
def _mod_builder_module() -> Any:
    module_path = Path(__file__).resolve().parents[1] / "mod-builder" / "build_data_mod.py"
    module_directory = str(module_path.parent)
    spec = importlib.util.spec_from_file_location("cobbleventure_mod_builder_validation", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"모드 빌더를 불러올 수 없습니다: {module_path}")
    module = importlib.util.module_from_spec(spec)
    inserted = module_directory not in sys.path
    if inserted:
        sys.path.insert(0, module_directory)
    try:
        spec.loader.exec_module(module)
    finally:
        if inserted:
            sys.path.remove(module_directory)
    return module


def validate_town_indoor_npc_capacities(root: Path) -> list[Issue]:
    """Compare each compiled town's indoor NPC demand with its real building slots."""
    issues: list[Issue] = []
    settlement_root = root / "content" / "settlements"
    if not settlement_root.is_dir():
        return issues
    try:
        builder = _mod_builder_module()
    except (OSError, ImportError, RuntimeError) as error:
        return [Issue("error", settlement_root.as_posix(), "$", f"마을 수용량 검증기를 불러올 수 없습니다: {error}")]
    for path in sorted(settlement_root.rglob("*.json")):
        try:
            data = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
            _issue(issues, "error", path, "$.npc_placement", f"마을 실내 NPC 수용량을 계산할 수 없습니다: {error}")
            continue
        if isinstance(data, dict):
            issues.extend(validate_town_indoor_npc_capacity_document(root, data, path, builder))
    return issues


def validate_town_indoor_npc_capacity_document(
    root: Path, data: dict[str, Any], path: Path, builder: Any | None = None,
) -> list[Issue]:
    issues: list[Issue] = []
    try:
        builder = builder or _mod_builder_module()
        repository_root = Path(__file__).resolve().parents[2]
        compiled_layout = builder._compile_town_layout(data, root=repository_root)
        capacity = builder._town_indoor_npc_capacity(
            repository_root, data, compiled_layout, project_root=root,
        )
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError, RuntimeError) as error:
        _issue(issues, "error", path, "$.npc_placement", f"마을 실내 NPC 수용량을 계산할 수 없습니다: {error}")
        return issues
    requested = int(capacity["requested"])
    available = int(capacity["available"])
    if requested > available:
        _issue(
            issues, "error", path, "$.npc_placement",
            f"마을 건물 내부 NPC 자리가 부족합니다: 요청 {requested}명 / 수용 {available}명.",
        )
    return issues


def save_building_settings(root: Path, data: Any) -> list[Issue]:
    path = root / BUILDING_SETTINGS_PATH
    issues: list[Issue] = []
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        return [Issue("error", path.as_posix(), "$.schema_version", "버전 1이 필요합니다.")]
    buildings = data.get("buildings")
    if not isinstance(buildings, dict):
        return [Issue("error", path.as_posix(), "$.buildings", "건물 설정 객체가 필요합니다.")]
    existing_defaults = load_building_settings(root).get("facility_defaults", {})
    defaults = data.get("facility_defaults", existing_defaults)
    if not isinstance(defaults, dict):
        return [Issue("error", path.as_posix(), "$.facility_defaults", "시설 기본 NBT 설정 객체가 필요합니다.")]
    allowed_facilities = {"pokemon_center", "pokemart", "department_store"}
    normalized_defaults: dict[str, str] = {}
    for facility, structure_id in sorted(defaults.items()):
        if facility not in allowed_facilities:
            _issue(issues, "error", path, f"$.facility_defaults.{facility}", "지원하지 않는 시설 종류입니다.")
        elif not isinstance(structure_id, str) or not RESOURCE_ID.fullmatch(structure_id):
            _issue(issues, "error", path, f"$.facility_defaults.{facility}", "NBT 리소스 ID 형식이 올바르지 않습니다.")
        else:
            normalized_defaults[facility] = structure_id
    structure_files = managed_structure_files(root)
    npc_ids = {item.get("id") for item in _list_documents(root, "trainers") if item.get("id")}
    gacha_machine_ids = {
        machine.get("id") for machine in gacha_machine_catalog_payload(root).get("machines", [])
        if isinstance(machine, dict) and isinstance(machine.get("id"), str)
    }
    music_catalog_path = root / "content" / "catalogs" / "music-tracks.json"
    music_track_ids: set[str] | None = None
    if music_catalog_path.is_file():
        music_catalog = load_json(music_catalog_path)
        music_track_ids = {
            track.get("id") for track in music_catalog.get("tracks", [])
            if isinstance(track, dict) and isinstance(track.get("id"), str)
        }
    normalized: dict[str, Any] = {}
    town_placement_ids: set[str] = set()
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
        no_interior_space = settings.get("no_interior_space", False)
        if not isinstance(no_interior_space, bool):
            _issue(
                issues, "error", path, f"{entry_path}.no_interior_space",
                "내부 공간 없음 값은 true 또는 false여야 합니다.",
            )
            continue
        town_placement = settings.get("town_placement", {})
        if not isinstance(town_placement, dict):
            _issue(
                issues, "error", path, f"{entry_path}.town_placement",
                "마을 배치 설정은 객체여야 합니다.",
            )
            continue
        town_enabled = town_placement.get("enabled", False)
        town_id = town_placement.get("id", "")
        town_label = town_placement.get("label", "")
        town_note = town_placement.get("note", "")
        town_color = town_placement.get("color", "#64748b")
        if not isinstance(town_enabled, bool):
            _issue(issues, "error", path, f"{entry_path}.town_placement.enabled", "참/거짓 값이어야 합니다.")
            continue
        if town_enabled and (
            not isinstance(town_id, str) or not DOCUMENT_SLUG.fullmatch(town_id)
        ):
            _issue(issues, "error", path, f"{entry_path}.town_placement.id", "영문 소문자·숫자·밑줄 ID가 필요합니다.")
        elif town_enabled and town_id in town_placement_ids:
            _issue(issues, "error", path, f"{entry_path}.town_placement.id", f"마을 배치 목록 ID가 중복됩니다: {town_id}")
        elif town_enabled:
            town_placement_ids.add(town_id)
        if town_enabled and (
            not isinstance(town_label, str) or not 1 <= len(town_label) <= 32
        ):
            _issue(issues, "error", path, f"{entry_path}.town_placement.label", "1~32자의 표시 이름이 필요합니다.")
        if not isinstance(town_note, str) or len(town_note) > 96:
            _issue(issues, "error", path, f"{entry_path}.town_placement.note", "96자 이하의 설명이 필요합니다.")
        if not isinstance(town_color, str) or not re.fullmatch(r"#[0-9a-fA-F]{6}", town_color):
            _issue(issues, "error", path, f"{entry_path}.town_placement.color", "#RRGGBB 형식의 색상이 필요합니다.")
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
        source_connection_labels = {
            space: {item["label"] for item in _structure_named_anchors(
                space_file, {"door", "transition"}
            )}
            for space, space_file in space_files.items()
        }
        target_connection_labels = {
            space: {item["label"] for item in _structure_named_anchors(
                space_file, {
                    "door", "transition", "arrival", "interior_spawn",
                    "exterior_spawn",
                }
            )}
            for space, space_file in space_files.items()
        }
        routes = settings.get("door_routes", {})
        if not isinstance(routes, dict):
            _issue(issues, "error", path, f"{entry_path}.door_routes", "출입구 연결 설정은 객체여야 합니다.")
            routes = {}
        normalized_routes: dict[str, dict[str, Any]] = {}
        for source_key, destination in sorted(routes.items()):
            route_path = f"{entry_path}.door_routes.{source_key}"
            if not isinstance(source_key, str) or ":" not in source_key:
                _issue(issues, "error", path, route_path, "출입구 키는 공간:앵커이름 형식이어야 합니다.")
                continue
            source_space, source_door = source_key.split(":", 1)
            if (
                source_space not in source_connection_labels
                or source_door not in source_connection_labels[source_space]
            ):
                _issue(issues, "error", path, route_path, "NBT에 없는 문 또는 접촉 전환 영역입니다.")
                continue
            if not isinstance(destination, dict):
                _issue(issues, "error", path, route_path, "출입구 목적지는 객체여야 합니다.")
                continue
            target_space = destination.get("space")
            target_door = destination.get("door", destination.get("arrival"))
            if (
                target_space not in target_connection_labels
                or target_door not in target_connection_labels[target_space]
            ):
                _issue(issues, "error", path, route_path, "존재하는 공간의 문, 접촉 전환 영역 또는 도착점을 선택해야 합니다.")
                continue
            normalized_route: dict[str, Any] = {"space": target_space, "door": target_door}
            condition_mode = destination.get("condition_mode", "all")
            if condition_mode not in {"all", "any"}:
                _issue(issues, "error", path, f"{route_path}.condition_mode", "조건 조합은 all 또는 any여야 합니다.")
                continue
            conditions = destination.get("conditions", [])
            if not isinstance(conditions, list):
                _issue(issues, "error", path, f"{route_path}.conditions", "문 잠금 조건 배열이 필요합니다.")
                continue
            for condition_index, condition in enumerate(conditions):
                _validate_player_condition(
                    condition, issues, path,
                    f"{route_path}.conditions[{condition_index}]",
                )
            normalized_route["condition_mode"] = condition_mode
            normalized_route["conditions"] = conditions
            for dialogue_key in ("locked_dialogue", "enter_dialogue"):
                dialogue = destination.get(dialogue_key, [])
                if not isinstance(dialogue, list) or any(not isinstance(line, str) for line in dialogue):
                    _issue(issues, "error", path, f"{route_path}.{dialogue_key}", "대사는 문자열 배열이어야 합니다.")
                    continue
                normalized_route[dialogue_key] = dialogue
            normalized_routes[source_key] = normalized_route
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
            wildcard = (
                isinstance(label, str)
                and label.endswith("*")
                and label.count("*") == 1
                and len(label) > 1
            )
            label_exists = (
                any(candidate.startswith(label[:-1]) for candidate in labels)
                if wildcard else label in labels
            )
            if not label_exists:
                _issue(issues, "error", path, f"{entry_path}.fixed_npcs.{label}", "NBT에 없는 NPC 라벨입니다.")
            elif not isinstance(npc_id, str) or npc_id not in npc_ids:
                _issue(issues, "error", path, f"{entry_path}.fixed_npcs.{label}", "존재하는 NPC 콘텐츠를 선택해야 합니다.")
            else:
                normalized_fixed[label] = npc_id
        fixed_pokemon = settings.get("fixed_pokemon", {})
        if not isinstance(fixed_pokemon, dict):
            _issue(issues, "error", path, f"{entry_path}.fixed_pokemon", "고정 포켓몬 배정은 객체여야 합니다.")
            continue
        pokemon_labels = {
            item["label"] for item in _structure_named_anchors(
                structure, {"npc_position"}
            )
        }
        normalized_fixed_pokemon: dict[str, str] = {}
        for label, properties in sorted(fixed_pokemon.items()):
            if label not in pokemon_labels:
                _issue(issues, "error", path, f"{entry_path}.fixed_pokemon.{label}", "NBT에 없는 포켓몬 라벨입니다.")
            elif not isinstance(properties, str) or not properties.strip():
                _issue(issues, "error", path, f"{entry_path}.fixed_pokemon.{label}", "Cobblemon 포켓몬 속성 문자열이 필요합니다.")
            else:
                normalized_fixed_pokemon[label] = properties.strip()
        fixed_gacha = settings.get("fixed_gacha_machines", {})
        if not isinstance(fixed_gacha, dict):
            _issue(issues, "error", path, f"{entry_path}.fixed_gacha_machines", "고정 가챠 기계 배정은 객체여야 합니다.")
            continue
        normalized_fixed_gacha: dict[str, str] = {}
        for label, profile_id in sorted(fixed_gacha.items()):
            if label not in labels:
                _issue(issues, "error", path, f"{entry_path}.fixed_gacha_machines.{label}", "NBT에 없는 가챠 플래그입니다.")
            elif not isinstance(profile_id, str) or profile_id not in gacha_machine_ids:
                _issue(issues, "error", path, f"{entry_path}.fixed_gacha_machines.{label}", "존재하는 가챠 기계 프로필을 선택해야 합니다.")
            else:
                normalized_fixed_gacha[label] = profile_id
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
        music_track = settings.get("music_track")
        if music_track is not None and (
            not isinstance(music_track, str) or not CHOICE_ID.fullmatch(music_track)
            or (music_track_ids is not None and music_track not in music_track_ids)
        ):
            _issue(
                issues, "error", path, f"{entry_path}.music_track",
                "활성 음악 목록에서 음악을 선택해야 합니다.",
            )
            continue
        if citizen_placement_allowed and normalized_fixed:
            _issue(
                issues, "error", path, f"{entry_path}.fixed_npcs",
                "시민 수용 건물에는 고정 NPC를 배정하지 않습니다.",
            )
        if no_interior_space and (normalized_interiors or normalized_routes):
            _issue(
                issues, "error", path, entry_path,
                "내부 공간 없음 구조물에는 내부공간이나 문 연결을 설정할 수 없습니다.",
            )
        normalized[resource_id] = {
            "placement_y_offset": placement_y_offset,
            "structure_category": _configured_structure_category(relative, settings),
            **({"music_track": music_track} if music_track else {}),
            "no_interior_space": no_interior_space,
            **(
                {
                    "town_placement": {
                        "enabled": True,
                        "id": town_id if isinstance(town_id, str) else "",
                        "label": town_label if isinstance(town_label, str) else "",
                        "note": town_note if isinstance(town_note, str) else "",
                        "color": town_color if isinstance(town_color, str) else "#64748b",
                    }
                }
                if town_enabled
                else {}
            ),
            "fixed_npcs": {} if citizen_placement_allowed else normalized_fixed,
            "fixed_pokemon": normalized_fixed_pokemon,
            "fixed_gacha_machines": normalized_fixed_gacha,
            "citizen_placement_allowed": citizen_placement_allowed,
            "interiors": [] if no_interior_space else normalized_interiors,
            "door_routes": {} if no_interior_space else normalized_routes,
        }
    issues.extend(validate_building_npc_positions(root, normalized))
    if any(issue.level == "error" for issue in issues):
        return issues
    document = {
        "schema_version": 1,
        "facility_defaults": normalized_defaults,
        "buildings": normalized,
    }
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
            include_preview = resource_id in STRUCTURE_VIEWER_REQUIRED_EXTERNAL
            if include_preview:
                metadata = read_minecraft_structure_metadata(data)
            else:
                width, height, depth = read_minecraft_structure_size(data)
                metadata = {"width": width, "height": height, "depth": depth}
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
    managed_catalog: dict[str, dict[str, Any]] | None = None,
) -> dict[str, dict[str, Any]]:
    core_root = (core_root or root).resolve()
    viewer: dict[str, dict[str, Any]] = {}
    for resource_id, path in managed_structure_files(root).items():
        try:
            metadata = (
                managed_catalog.get(resource_id)
                if managed_catalog is not None else None
            )
            if metadata is None:
                metadata = read_minecraft_structure_metadata(path.read_bytes())
        except (OSError, EOFError, ValueError, struct.error):
            continue
        viewer[resource_id] = {
            **metadata,
            "transition_anchors": _structure_named_anchors(path, {"transition"}),
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
    building_settings_path = root / BUILDING_SETTINGS_PATH
    if building_settings_path.is_file():
        candidates.append(building_settings_path)
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


STRUCTURE_WEB_CACHE_VERSION = 7
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
    current_signature = [
        list(entry) for entry in structure_catalog_signature(root, cache_root)
    ]
    if document.get("signature") != current_signature:
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
    managed_catalog = load_managed_structure_catalog(root)
    return {
        "version": STRUCTURE_WEB_CACHE_VERSION,
        "generated_at": int(time.time()),
        "signature": [list(entry) for entry in signature],
        "size_catalog": size_catalog,
        "viewer_catalog": load_structure_viewer_catalog(
            root, size_catalog, core_root, managed_catalog
        ),
        "building_settings": building_settings_payload(root, managed_catalog),
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
    structure_cache_signature = tuple(
        tuple(entry) for entry in saved_structure_cache.get("signature", [])
    ) if saved_structure_cache else ()
    structure_cache_generation = 1 if saved_structure_cache else 0
    structure_cache_error: str | None = None
    structure_viewer_catalog_lock = threading.Lock()
    structure_cache_refresh_lock = threading.Lock()
    structure_cache_thread_lock = threading.Lock()
    structure_cache_refresh_scheduled = threading.Event()
    structure_cache_shutdown = threading.Event()
    structure_cache_refresh_thread: threading.Thread | None = None
    structure_model_cache: dict[str, dict[str, Any]] = {}
    remote_image_cache: dict[str, bytes] = {}
    remote_image_cache_lock = threading.Lock()
    project_lock = threading.Lock()
    cves_save_lock = threading.Lock()

    def cves_catalog() -> Any:
        item_catalog = core_root / "trainer-data" / "catalogs" / "cobblemon-items.json"
        return load_cves_project_catalog(
            root, item_catalog=item_catalog if item_catalog.is_file() else None
        )

    def activate_project(project_path: Path) -> ContentProject:
        nonlocal root, active_project, editor_catalog
        nonlocal structure_size_catalog, structure_viewer_catalog
        nonlocal building_settings_catalog, structure_cache_generated_at
        nonlocal structure_cache_signature, structure_cache_generation
        nonlocal structure_cache_error
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
                structure_cache_signature = tuple(
                    tuple(entry) for entry in saved_cache.get("signature", [])
                ) if saved_cache else ()
                structure_cache_generation += 1
                structure_cache_error = None
                structure_model_cache.clear()
        return project

    def refresh_structure_cache() -> None:
        nonlocal structure_size_catalog, structure_viewer_catalog
        nonlocal building_settings_catalog, structure_cache_generated_at
        nonlocal structure_cache_signature, structure_cache_generation
        nonlocal structure_cache_error
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
                structure_cache_signature = tuple(
                    tuple(entry) for entry in refreshed["signature"]
                )
                structure_cache_generation += 1
                structure_cache_error = None
                structure_model_cache.clear()

    def schedule_structure_cache_refresh() -> None:
        nonlocal structure_cache_refresh_thread
        with structure_cache_thread_lock:
            if (
                structure_cache_shutdown.is_set()
                or structure_cache_refresh_scheduled.is_set()
            ):
                return
            structure_cache_refresh_scheduled.set()

        def run_refresh() -> None:
            try:
                refresh_structure_cache()
            finally:
                structure_cache_refresh_scheduled.clear()

        refresh_thread = threading.Thread(
            target=run_refresh,
            name="cobbleventure-nbt-cache-refresh",
            daemon=True,
        )
        with structure_cache_thread_lock:
            if structure_cache_shutdown.is_set():
                structure_cache_refresh_scheduled.clear()
                return
            structure_cache_refresh_thread = refresh_thread
            refresh_thread.start()

    def close_background_tasks() -> None:
        structure_cache_shutdown.set()
        with structure_cache_thread_lock:
            refresh_thread = structure_cache_refresh_thread
        if (
            refresh_thread is not None
            and refresh_thread is not threading.current_thread()
            and refresh_thread.is_alive()
        ):
            refresh_thread.join()

    def ensure_structure_cache(validate_signature: bool = False) -> None:
        if structure_size_catalog is None or building_settings_catalog is None:
            if validate_signature:
                refresh_structure_cache()
            else:
                schedule_structure_cache_refresh()
            return
        if validate_signature and (
            structure_catalog_signature(root, core_root) != structure_cache_signature
        ):
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
            body = json.dumps(
                payload, ensure_ascii=False, separators=(",", ":")
            ).encode("utf-8")
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
                "/cves.html": web_root / "cves.html",
                "/cves-editor.js": web_root / "cves-editor.js",
                "/cves-editor.css": web_root / "cves-editor.css",
                "/space-connections.js": web_root / "space-connections.js",
                "/styles.css": web_root / "styles.css",
                "/economy.css": web_root / "economy.css",
                "/typography.css": web_root / "typography.css",
                "/fonts/PretendardVariable.woff2": web_root
                / "fonts"
                / "PretendardVariable.woff2",
                "/fonts/pokemon_bw.ttf": core_root
                / "projects"
                / "cobbleventure-world-bootstrap"
                / "src"
                / "main"
                / "resources"
                / "assets"
                / "cobbleventure"
                / "font"
                / "pokemon_bw.ttf",
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
            if request.path == "/api/dialogue-theme":
                try:
                    path = root / "content" / "catalogs" / "dialogue-theme.json"
                    self._json(200, load_json(path) if path.is_file() else DIALOGUE_THEME_DEFAULTS)
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/casino-config":
                try:
                    self._json(200, casino_config_payload(core_root))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/gacha-machines":
                try:
                    self._json(200, gacha_machine_catalog_payload(root))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/gacha-item-graphics":
                self._json(200, gacha_item_graphics_payload(core_root))
                return
            if request.path == "/api/gacha-item-texture":
                item = parse_qs(request.query).get("item", [""])[0]
                try:
                    texture_path, _ = _gacha_item_asset_paths(core_root, item)
                    self._bytes(200, texture_path.read_bytes(), "image/png")
                except ValueError as error:
                    self._json(400, {"error": str(error)})
                except OSError:
                    self._json(404, {"error": "아직 커스텀 PNG가 지정되지 않았습니다."})
                return
            if request.path == "/api/cves/scripts":
                try:
                    self._json(200, {"items": list_cves_scripts(root)})
                except (OSError, ValueError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/cves/editor-contract":
                try:
                    self._json(200, cves_editor_contract(cves_catalog()))
                except (OSError, ValueError, json.JSONDecodeError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/cves/script":
                relative_path = parse_qs(request.query).get("path", [""])[0]
                try:
                    self._json(200, load_cves_script(root, relative_path, cves_catalog()))
                except CvesSyntaxError as error:
                    self._json(422, {
                        "error": "CVES 문법 오류가 있습니다.",
                        "diagnostics": [cves_diagnostic_document(error.diagnostic)],
                    })
                except FileNotFoundError:
                    self._json(404, {"error": "CVES 원본을 찾을 수 없습니다."})
                except (OSError, ValueError, json.JSONDecodeError) as error:
                    self._json(400, {"error": str(error)})
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
                        "export_languages": [
                            {"id": language, "name": name}
                            for language, name in EXPORT_LANGUAGES.items()
                        ] if active_project.is_default else [],
                        "cobblemon_build_targets": [
                            {"id": target, "name": name}
                            for target, name in COBBLEMON_BUILD_TARGETS.items()
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
            if request.path == "/api/starter-settings":
                try:
                    self._json(200, load_json(root / "content" / "catalogs" / "starter-settings.json"))
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
                        "refreshing": structure_cache_refresh_scheduled.is_set() or structure_cache_refresh_lock.locked(),
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
                            "refreshing": structure_cache_refresh_scheduled.is_set() or structure_cache_refresh_lock.locked(),
                            "error": structure_cache_error,
                        },
                    })
                except (OSError, ValueError, EOFError, struct.error, zipfile.BadZipFile) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/building-settings":
                try:
                    refresh_requested = parse_qs(request.query).get("refresh", ["0"])[0] == "1"
                    if refresh_requested:
                        refresh_structure_cache()
                    ensure_structure_cache()
                    payload = copy.deepcopy(building_settings_catalog or {})
                    payload["cache"] = {
                        "generated_at": structure_cache_generated_at,
                        "refreshing": structure_cache_refresh_scheduled.is_set() or structure_cache_refresh_lock.locked(),
                        "error": structure_cache_error,
                    }
                    self._json(200, payload)
                except (OSError, ValueError, EOFError, struct.error, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/space-connections":
                try:
                    # Space editing must see NBTs and sidecar anchors created while
                    # the web server is already running. Other catalog endpoints
                    # keep the fast startup cache until an explicit refresh.
                    ensure_structure_cache(validate_signature=True)
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
            if request.path == "/api/dungeons":
                try:
                    self._json(200, dungeon_workspace_payload(root))
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/trainers":
                self._document_response("trainers", request)
                return
            if request.path == "/api/battles":
                self._document_response("battles", request)
                return
            if request.path == "/api/routes":
                self._document_response("routes", request)
                return
            if request.path == "/api/settlements":
                self._document_response("settlements", request)
                return
            if request.path == "/api/caves":
                self._document_response("caves", request)
                return
            if request.path == "/api/underground-roads":
                self._document_response("underground-roads", request)
                return
            if request.path == "/api/forests":
                self._document_response("forests", request)
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
            if request.path == "/api/cves/preset-preview":
                if not isinstance(payload, dict) or not isinstance(payload.get("document"), dict):
                    self._json(400, {"error": "NPC 문서와 경로가 필요합니다."})
                    return
                relative_path = payload.get("path")
                if not isinstance(relative_path, str) or not relative_path:
                    self._json(400, {"error": "NPC 문서 경로가 필요합니다."})
                    return
                document = payload["document"]
                try:
                    target = _managed_path(root, "trainers", relative_path)
                    _, candidate_issues = _validate_payload(document, validate_content_file)
                    issues = [
                        Issue(issue.level, target.as_posix(), issue.path, issue.message)
                        for issue in candidate_issues
                    ]
                    if any(issue.level == "error" for issue in issues):
                        self._json(422, {
                            "valid": False,
                            "issues": [asdict(issue) for issue in issues],
                        })
                        return
                    plan = _prepare_v5_preset_sync(root, target, document)
                    self._json(200, {
                        "valid": True,
                        "issues": [asdict(issue) for issue in issues],
                        "preview": _preview_v5_preset_sync(root, plan),
                    })
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    issue = Issue("error", relative_path, "$.event_runtime", str(error))
                    self._json(422, {"valid": False, "issues": [asdict(issue)]})
                return
            if request.path == "/api/cves/validate":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "CVES 검증 요청은 객체여야 합니다."})
                    return
                relative_path = payload.get("path", "<editor>")
                if not isinstance(relative_path, str):
                    self._json(400, {"error": "CVES path는 문자열이어야 합니다."})
                    return
                try:
                    if "source" in payload:
                        document = validate_cves_source(
                            payload["source"], relative_path, cves_catalog()
                        )
                    elif "ast" in payload:
                        document = validate_cves_ast(
                            payload["ast"], relative_path, cves_catalog()
                        )
                    else:
                        raise ValueError("검증할 source 또는 ast가 필요합니다.")
                    self._json(200 if document["valid"] else 422, document)
                except CvesSyntaxError as error:
                    self._json(422, {
                        "valid": False,
                        "diagnostics": [cves_diagnostic_document(error.diagnostic)],
                    })
                except (AstCodecError, ValueError, OSError, json.JSONDecodeError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/cves/expression":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "CVES 식 요청은 객체여야 합니다."})
                    return
                relative_path = payload.get("path", "<expression>")
                if not isinstance(relative_path, str):
                    self._json(400, {"error": "CVES path는 문자열이어야 합니다."})
                    return
                try:
                    self._json(200, parse_cves_editor_expression(
                        payload.get("source"), relative_path
                    ))
                except CvesSyntaxError as error:
                    self._json(422, {
                        "valid": False,
                        "diagnostics": [cves_diagnostic_document(error.diagnostic)],
                    })
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
                if category not in {"trainers", "battles", "routes", "settlements", "caves", "dungeons", "dungeon-plans", "dungeon-pieces", "underground-roads", "forests"}:
                    self._json(400, {"error": "지원하지 않는 문서 종류입니다."})
                    return
                validator = {
                    "trainers": validate_content_file,
                    "battles": validate_battle_preset_file,
                    "routes": validate_route_file,
                    "settlements": validate_settlement_file,
                    "caves": validate_cave_file,
                    "dungeons": validate_dungeon_file,
                    "dungeon-plans": validate_dungeon_plan_file,
                    "dungeon-pieces": validate_dungeon_piece_file,
                    "underground-roads": validate_underground_road_file,
                    "forests": validate_forest_file,
                }[category]
                if category == "forests" and isinstance(payload, dict):
                    payload = copy.deepcopy(payload)
                    dimension = payload.get("dimension")
                    if isinstance(dimension, dict):
                        dimension["id"] = "cobbleventure:forests"
                _, issues = _validate_payload(payload, validator)
                if category == "underground-roads" and isinstance(payload, dict):
                    _, issues = validate_underground_road_document(
                        payload, root / "content" / "underground_roads" / "candidate.json", root,
                    )
                if category == "dungeon-plans" and isinstance(payload, dict):
                    issues.extend(validate_dungeon_plan_document(
                        root, payload, root / "content" / "dungeon_plans" / "candidate.json",
                    ))
                if category == "settlements" and isinstance(payload, dict) and not any(
                    issue.level == "error" for issue in issues
                ):
                    issues.extend(validate_town_indoor_npc_capacity_document(
                        root, payload, root / "content" / "settlements" / "candidate.json",
                    ))
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
            if request.path == "/api/town-layout-preview":
                if (
                    not isinstance(payload, dict)
                    or not isinstance(payload.get("document"), dict)
                ):
                    self._json(400, {"error": "마을 미리보기 문서가 필요합니다."})
                    return
                try:
                    builder = _mod_builder_module()
                    compiled_layout = builder._compile_town_layout(
                        payload["document"], root=core_root,
                    )
                    custom_cells = tuple(
                        (int(cell["q"]), int(cell["r"]))
                        for cell in compiled_layout.get("footprint_cells", [])
                        if (
                            isinstance(cell, dict)
                            and isinstance(cell.get("q"), int)
                            and isinstance(cell.get("r"), int)
                        )
                    )
                    layout_cells = builder._town_layout_cells(
                        int(compiled_layout.get("cell_count", 1)),
                        str(compiled_layout.get("footprint_shape", "line_q")),
                        custom_cells,
                    )
                    self._json(200, {
                        "compiled_layout": compiled_layout,
                        "layout_cells": [
                            {"q": int(q), "r": int(r)} for q, r in layout_cells
                        ],
                    })
                except (
                    OSError, ValueError, TypeError, KeyError,
                    json.JSONDecodeError, DuplicateKeyError, RuntimeError,
                ) as error:
                    self._json(422, {"error": str(error)})
                return
            if request.path == "/api/settlements/order":
                try:
                    ordered_ids = payload.get("ids") if isinstance(payload, dict) else None
                    issues = _reorder_settlements(root, ordered_ids)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(
                    200 if errors == 0 else 422,
                    {
                        "saved": errors == 0,
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
            if request.path == "/api/routes/clone":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "복사할 길 정보가 필요합니다."})
                    return
                source_id = payload.get("source_id", "")
                slug = payload.get("slug", "")
                name = payload.get("name", "")
                generation = payload.get("generation", "generation_1")
                if not all(isinstance(value, str) for value in (source_id, slug, name, generation)):
                    self._json(400, {"error": "길 복사 정보는 문자열이어야 합니다."})
                    return
                try:
                    target, document, issues = _clone_route_document(root, source_id, slug, name, generation)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(201 if errors == 0 else 422, {
                    "created": errors == 0,
                    "path": target.relative_to(root).as_posix() if target else "",
                    "document": document,
                    "issues": [asdict(issue) for issue in issues],
                })
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
                language = payload.get("language", "ko_kr") if isinstance(payload, dict) else "ko_kr"
                cobblemon_target = payload.get("cobblemon_target", "1.7.3") if isinstance(payload, dict) else "1.7.3"
                if not isinstance(command, str) or command not in BUILD_COMMANDS:
                    self._json(400, {"error": "허용된 빌드 명령을 선택해야 합니다."})
                    return
                if not isinstance(language, str) or language not in EXPORT_LANGUAGES:
                    self._json(400, {"error": "지원하는 내보내기 언어를 선택해야 합니다."})
                    return
                if not isinstance(cobblemon_target, str) or cobblemon_target not in COBBLEMON_BUILD_TARGETS:
                    self._json(400, {"error": "지원하는 Cobblemon 빌드 대상을 선택해야 합니다."})
                    return
                if not build_lock.acquire(blocking=False):
                    self._json(409, {"error": "다른 빌드 명령이 실행 중입니다."})
                    return
                try:
                    result = _run_build(core_root, root, command, language, cobblemon_target)
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
            if request.path == "/api/exterior-structures/copy":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "외부 NBT 복사 정보가 필요합니다."})
                    return
                try:
                    result = copy_managed_exterior_structure(
                        root,
                        str(payload.get("source_structure", "")),
                        str(payload.get("target_directory", "")),
                        str(payload.get("target_slug", "")),
                    )
                    refresh_structure_cache()
                    structure_model_cache.pop(result["structure"], None)
                    self._json(201, {"created": True, "structure": result})
                except (
                    OSError, ValueError, EOFError, struct.error,
                    json.JSONDecodeError, DuplicateKeyError,
                ) as error:
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
            if request.path == "/api/structure-builder/live/open":
                try:
                    status = _structure_builder_status(root, core_root)
                    if not status["live_world_exists"]:
                        raise ValueError("먼저 새 라이브 NBT 에디터 월드를 한 번 실행해 주세요.")
                    source = payload.get("source") if isinstance(payload, dict) else None
                    if not isinstance(source, str):
                        raise ValueError("불러올 NBT 경로가 필요합니다.")
                    command = _queue_structure_builder_live_open(
                        root,
                        Path(status["live_world_path"]),
                        source,
                        payload.get("size"),
                        preserve_current=bool(payload.get("preserve_current", True)),
                    )
                    self._json(202, {"queued": True, "command": command})
                except (OSError, ValueError, EOFError, struct.error, json.JSONDecodeError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path in {
                "/api/structure-builder/live/save",
                "/api/structure-builder/live/resize",
                "/api/structure-builder/live/test-place",
            }:
                try:
                    status = _structure_builder_status(root, core_root)
                    if not status["live_world_exists"]:
                        raise ValueError("먼저 새 라이브 NBT 에디터 월드를 한 번 실행해 주세요.")
                    action = (
                        "save" if request.path.endswith("/save")
                        else "test_place" if request.path.endswith("/test-place")
                        else "resize"
                    )
                    command: dict[str, Any] = {
                        "schema_version": 1,
                        "action": action,
                        "revision": uuid.uuid4().hex,
                    }
                    if action == "resize":
                        size = payload.get("size") if isinstance(payload, dict) else None
                        if (
                            not isinstance(size, list) or len(size) != 3
                            or any(isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 256 for value in size)
                        ):
                            raise ValueError("편집 크기는 1~256 사이 정수 3개여야 합니다.")
                        command["size"] = size
                    _atomic_write_json(
                        _structure_builder_live_root(Path(status["live_world_path"])) / "command.json",
                        command,
                    )
                    self._json(202, {"queued": True, "command": command})
                except (OSError, ValueError, json.JSONDecodeError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/structure-builder/external":
                try:
                    if not isinstance(payload, dict):
                        raise ValueError("외부 NBT 추가 정보가 필요합니다.")
                    target_id = payload.get("id")
                    encoded_nbt = payload.get("nbt")
                    if not isinstance(target_id, str) or not isinstance(encoded_nbt, str):
                        raise ValueError("외부 NBT ID와 파일 데이터가 필요합니다.")
                    created = _add_external_structure(
                        root, target_id, encoded_nbt,
                        payload.get("metadata") if isinstance(payload.get("metadata"), str) else None,
                    )
                    refresh_structure_cache()
                    self._json(201, {"created": True, "structure": created})
                except (
                    OSError, ValueError, EOFError, struct.error,
                    json.JSONDecodeError, binascii.Error,
                ) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/structure-builder/sync":
                if not active_project.is_default:
                    self._json(409, {"error": "건축 월드 교체는 현재 기본 프로젝트에서만 지원합니다."})
                    return
                if not build_lock.acquire(blocking=False):
                    self._json(409, {"error": "다른 빌드 명령이 실행 중입니다."})
                    return
                try:
                    result = _run_structure_builder_sync(root, core_root)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                finally:
                    build_lock.release()
                self._json(200 if result["success"] else 422, result)
                return
            self._json(404, {"error": "not_found"})

        def do_PUT(self) -> None:
            request = urlparse(self.path)
            if request.path == "/api/cves/script":
                try:
                    payload = self._read_json()
                    if not isinstance(payload, dict):
                        raise ValueError("CVES 저장 요청은 객체여야 합니다.")
                    relative_path = payload.get("path")
                    if not isinstance(relative_path, str):
                        raise ValueError("저장할 CVES path가 필요합니다.")
                    expected_digest = payload.get("expected_digest")
                    if expected_digest is not None and not isinstance(expected_digest, str):
                        raise ValueError("expected_digest는 문자열 또는 null이어야 합니다.")
                    with cves_save_lock:
                        document = save_cves_script(
                            root,
                            relative_path,
                            payload.get("ast"),
                            expected_digest,
                            cves_catalog(),
                        )
                    self._json(200 if document["saved"] else 422, document)
                except CvesEditorConflict as error:
                    self._json(409, {"error": str(error), "code": "cves_source_conflict"})
                except CvesSyntaxError as error:
                    self._json(422, {
                        "saved": False,
                        "diagnostics": [cves_diagnostic_document(error.diagnostic)],
                    })
                except (AstCodecError, OSError, ValueError, json.JSONDecodeError) as error:
                    self._json(400, {"error": str(error)})
                return
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
                    live_instance_path = payload.get("live_instance_path") if isinstance(payload, dict) else None
                    if live_instance_path is None:
                        live_instance_path = _load_structure_builder_settings(core_root)["live_instance_path"]
                    if not isinstance(instance_path, str) or not isinstance(live_instance_path, str):
                        raise ValueError("CurseForge 인스턴스 경로를 문자열로 입력해야 합니다.")
                    _save_structure_builder_settings(core_root, instance_path, live_instance_path)
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
            if request.path == "/api/dialogue-theme":
                try:
                    payload = self._read_json()
                    issues = save_dialogue_theme(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(200 if errors == 0 else 422, {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/casino-config":
                try:
                    payload = self._read_json()
                    if not isinstance(payload, dict) or not isinstance(payload.get("path"), str):
                        raise ValueError("저장할 카지노 설정 경로와 JSON 문서가 필요합니다.")
                    issues = save_casino_config(core_root, payload["path"], payload.get("document"))
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(200 if errors == 0 else 422, {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/gacha-machines":
                try:
                    payload = self._read_json()
                    issues = save_gacha_machine_catalog(root, payload)
                except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(400, {"error": str(error)})
                    return
                errors = sum(issue.level == "error" for issue in issues)
                self._json(200 if errors == 0 else 422, {"saved": errors == 0, "valid": errors == 0, "issues": [asdict(issue) for issue in issues]})
                return
            if request.path == "/api/gacha-item-graphics":
                try:
                    payload = self._read_json()
                    if not isinstance(payload, dict) or not isinstance(payload.get("item"), str):
                        raise ValueError("아이템 종류와 PNG 데이터가 필요합니다.")
                    result = save_gacha_item_graphic(
                        core_root, payload["item"], payload.get("data_base64")
                    )
                    self._json(200, result)
                except (OSError, ValueError) as error:
                    self._json(400, {"error": str(error)})
                return
            if request.path == "/api/starter-settings":
                try:
                    payload = self._read_json()
                    issues = save_starter_settings(root, payload)
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
                        preview=payload.get("preview") is True,
                        remove_out_of_bounds_anchors=(
                            payload.get("remove_out_of_bounds_anchors") is True
                        ),
                    )
                    if payload.get("preview") is True:
                        self._json(200, {"saved": False, "preview": True, "structure": result})
                        return
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
                except StructureResizeAnchorConflict as error:
                    self._json(409, {
                        "error": str(error),
                        "code": "structure_anchor_conflict",
                        "anchors": error.anchors,
                    })
                    return
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
                "/api/routes": "routes",
                "/api/settlements": "settlements",
                "/api/caves": "caves",
                "/api/dungeons": "dungeons",
                "/api/dungeon-plans": "dungeon-plans",
                "/api/dungeon-pieces": "dungeon-pieces",
                "/api/underground-roads": "underground-roads",
                "/api/forests": "forests",
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
                "/api/routes": "routes",
                "/api/settlements": "settlements",
                "/api/caves": "caves",
                "/api/underground-roads": "underground-roads",
                "/api/forests": "forests",
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
    Handler.close_background_tasks = staticmethod(close_background_tasks)
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

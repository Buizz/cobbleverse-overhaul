from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
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
    "give_money",
    "take_money",
    "grant_loot",
    "start_quest",
    "complete_quest",
    "teleport",
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
}
STATIC_CONTENT_TYPES = {
    ".css": "text/css; charset=utf-8",
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".png": "image/png",
}
HABITAT_IDS = {"plains", "forest", "arid", "mountain", "cave", "wetland", "freshwater", "ocean", "snow", "volcanic", "urban", "special"}
RARITY_IDS = {"common", "medium", "uncommon", "rare", "legendary"}


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
        if not isinstance(offset, int) or isinstance(offset, bool) or not -48 <= offset <= 32:
            _issue(issues, "error", file, f"{data_path}.base_height_offset", "-48 이상 32 이하의 정수가 필요합니다.")
        if not isinstance(variation, int) or isinstance(variation, bool) or not 0 <= variation <= 8:
            _issue(issues, "error", file, f"{data_path}.height_variation", "0 이상 8 이하의 정수가 필요합니다.")
        if not isinstance(scale, (int, float)) or isinstance(scale, bool) or not 16 <= scale <= 512:
            _issue(issues, "error", file, f"{data_path}.noise_scale_blocks", "16 이상 512 이하의 노이즈 크기가 필요합니다.")

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
    except (OSError, ValueError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", pokemon_path, "$", f"카탈로그를 읽을 수 없습니다: {error}")
        pokemon_ids = set()
    try:
        biome_data = load_biome_catalog(root)
        profiles = biome_data.get("profiles")
        sets = biome_data.get("sets")
        if biome_data.get("schema_version") != 1:
            _issue(issues, "error", biome_path, "$.schema_version", "지원 버전은 1입니다.")
        if not isinstance(profiles, list):
            _issue(issues, "error", biome_path, "$.profiles", "프로필 배열이 필요합니다.")
            profiles = []
        if not isinstance(sets, list):
            _issue(issues, "error", biome_path, "$.sets", "세트 배열이 필요합니다.")
            sets = []
        profile_ids: set[str] = set()
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
    for profile in selected:
        settings = {**profile.get("settings", {}), **override}
        habitat = profile.get("habitat")
        forced = set(profile.get("forced_includes", []))
        excluded = set(profile.get("excluded_pokemon", []))
        for entry in pokemon:
            pokemon_id = entry.get("id")
            if pokemon_id in excluded:
                continue
            prefs = entry.get("preferences", {})
            habitats = entry.get("habitats", {})
            habitat_match = habitats.get("primary") == habitat or (settings.get("include_secondary", True) and habitats.get("secondary") == habitat)
            matches = habitat_match
            generation = settings.get("generation", 0)
            matches = matches and (not generation or entry.get("generation") == generation)
            for field in ("temperature", "humidity", "weather", "time"):
                wanted = settings.get(field, "any")
                actual = prefs.get(field, "any")
                matches = matches and (wanted == "any" or actual in {wanted, "any"})
            matches = matches and prefs.get("rarity") in settings.get("rarities", list(RARITY_IDS))
            if matches or pokemon_id in forced:
                result = dict(entry)
                result["matched_profiles"] = sorted(set(results.get(pokemon_id, {}).get("matched_profiles", [])) | {profile["id"]})
                result["match_reason"] = "profile_forced" if pokemon_id in forced and not matches else "rules"
                results[pokemon_id] = result
    by_id = {entry.get("id"): entry for entry in pokemon}
    for pokemon_id in unconditional:
        if pokemon_id in by_id:
            result = dict(by_id[pokemon_id])
            result["matched_profiles"] = []
            result["match_reason"] = "unconditional"
            results[pokemon_id] = result
    ordered = sorted(results.values(), key=lambda entry: entry.get("dex_number", 99999))
    return {"count": len(ordered), "pokemon": ordered, "profiles": [entry["id"] for entry in selected]}


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

    def resolve(
        biome: str, settings: dict[str, Any] | None = None,
        unconditional: list[str] | None = None, preferred_profile: str | None = None,
    ) -> tuple[list[str], list[dict[str, Any]]]:
        profile_ids = (
            [preferred_profile]
            if preferred_profile in profiles
            else profiles_by_biome.get(biome, [])
        )
        merged: dict[str, dict[str, Any]] = {}
        effective_settings = {"generation": generation, **(settings or {})}
        for profile_id in profile_ids:
            cache_key = json.dumps(
                [profile_id, effective_settings, sorted(unconditional or [])],
                ensure_ascii=False, sort_keys=True,
            )
            if cache_key not in preview_cache:
                preview_cache[cache_key] = _preview_biome_data(
                    biome_catalog,
                    pokemon,
                    {
                        "profile_id": profile_id,
                        "settings": effective_settings,
                        "unconditional_spawns": unconditional or [],
                    },
                )
            for entry in preview_cache[cache_key]["pokemon"]:
                merged[entry["id"]] = entry
        return profile_ids, sorted(merged.values(), key=lambda entry: entry.get("dex_number", 99999))

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
            for key in ("temperature", "humidity", "weather", "time", "rarities", "include_secondary")
            if key in environment
        }
        profile_ids, candidates = resolve(biome, settings)
        locations_by_cell[(q, r)] = {
            "q": q, "r": r, "kind": "biome", "biome": biome,
            "profile_ids": profile_ids,
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
        profile_ids, candidates = resolve(
            biome, settings, unconditional, zone.get("habitat_profile")
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
                "pokemon_ids": [entry["id"] for entry in candidates],
                "count": len(candidates), "unmapped_biome": not profile_ids,
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
    elif operation_type in {"start_quest", "complete_quest", "teleport"}:
        _resource_id(operation.get("target"), issues, file, f"{data_path}.target")


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
                    "cobblestone", "stone_bricks", "gravel",
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
                _resource_id(gym.get("structure"), issues, path, "$.structure_profile.gym.structure")
            leader = gym.get("leader_trainer_id")
            if leader not in {None, ""}:
                _resource_id(leader, issues, path, "$.structure_profile.gym.leader_trainer_id")
            league_entry = gym.get("league_entry_id")
            if league_entry not in {None, ""}:
                _resource_id(league_entry, issues, path, "$.structure_profile.gym.league_entry_id")

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
        "set_flag", "mark_clear", "give_money", "take_money", "give_item", "grant_loot", "end",
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
            elif command_type in {"set_flag", "mark_clear", "give_money", "take_money", "give_item", "grant_loot"}:
                _validate_operation(command, issues, path, command_path, npc_id, [])
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
        trainer_id = _resource_id(entry.get("trainer_id"), issues, path, f"{entry_path}.trainer_id")
        if trainer_ids is not None and trainer_id and trainer_id not in trainer_ids:
            _issue(issues, "error", path, f"{entry_path}.trainer_id", f"트레이너풀에 없는 NPC입니다: {trainer_id}")
        badge = entry.get("badge")
        if role == "gym_leader":
            badge = _require_object(badge, issues, path, f"{entry_path}.badge")
            if badge is not None:
                _resource_id(badge.get("item"), issues, path, f"{entry_path}.badge.item")
                _localized_text(badge.get("display_name"), issues, path, f"{entry_path}.badge.display_name")
                if not isinstance(badge.get("trainer_card_visible"), bool):
                    _issue(issues, "error", path, f"{entry_path}.badge.trainer_card_visible", "boolean이어야 합니다.")
        elif badge is not None:
            _issue(issues, "error", path, f"{entry_path}.badge", "배지는 체육관 관장에게만 설정할 수 있습니다.")
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


def validate_repository(root: Path, strict_pack: bool = False) -> ValidationResult:
    root = root.resolve()
    issues = validate_dependency_lock(root / "pack" / "dependencies.lock.json", strict_pack)
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
                league_entry_id = settlement_data.get("structure_profile", {}).get("gym", {}).get("league_entry_id")
                if isinstance(league_entry_id, str) and league_entry_id and league_entry_id not in league_ids:
                    _issue(issues, "error", path, "$.structure_profile.gym.league_entry_id", f"존재하지 않는 리그 항목: {league_entry_id}")
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
                    "generation": 0, "temperature": "temperate", "humidity": "normal",
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
                "leader_trainer_id": "",
                "league_entry_id": "",
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


def _run_build(root: Path, command: str) -> dict[str, Any]:
    if command not in BUILD_COMMANDS:
        raise ValueError("허용되지 않은 빌드 명령입니다.")
    try:
        completed = subprocess.run(
            ["cmd.exe", "/d", "/c", str(root / "build.bat"), command],
            cwd=root,
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


def generate_content(root: Path, output: Path | None = None) -> dict[str, Any]:
    root = root.resolve()
    output = (output or root / "generated").resolve()
    marker = output / ".cobbleventure-generated"
    validation = validate_repository(root)
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


def structure_mod_roots(root: Path) -> list[Path]:
    roots = [root / "pack" / "overrides" / "development-placeholder" / "mods"]
    instance_override = os.environ.get("COBBLEVERSE_INSTANCE")
    if instance_override:
        roots.append(Path(instance_override) / "mods")
    roots.append(
        Path.home() / "curseforge" / "minecraft" / "Instances"
        / "COBBLEVERSE - Pokemon Adventure [Cobblemon]" / "mods"
    )
    return list(dict.fromkeys(path.resolve() for path in roots))


def load_structure_size_catalog(root: Path) -> dict[str, Any]:
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
        root / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources",
        root / "projects" / "cobbleventure-world-bootstrap" / "src" / "generated" / "resources",
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
                    path.relative_to(root).as_posix()
                )

    for mod_root in structure_mod_roots(root):
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


def load_structure_model(root: Path, resource_id: str) -> dict[str, Any] | None:
    match = re.fullmatch(r"([a-z0-9_.-]+):([a-z0-9_./-]+)", resource_id)
    if not match:
        raise ValueError("올바른 구조물 리소스 ID가 아닙니다.")
    namespace, structure_path = match.groups()
    entry_names = [
        f"data/{namespace}/structure/{structure_path}.nbt",
        f"data/{namespace}/structures/{structure_path}.nbt",
    ]
    resource_roots = [
        root / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources",
        root / "projects" / "cobbleventure-world-bootstrap" / "src" / "generated" / "resources",
    ]
    for resource_root in resource_roots:
        for entry_name in entry_names:
            path = resource_root / entry_name
            if path.is_file():
                return {
                    **read_minecraft_structure_model(path.read_bytes()),
                    "source": path.relative_to(root).as_posix(),
                }

    for mod_root in structure_mod_roots(root):
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


def structure_catalog_signature(root: Path) -> tuple[tuple[str, int, int], ...]:
    """Return a cheap fingerprint for NBT resources and archives used by preview."""
    candidates: list[Path] = []
    for resource_root in [
        root / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources",
        root / "projects" / "cobbleventure-world-bootstrap" / "src" / "generated" / "resources",
    ]:
        if resource_root.is_dir():
            candidates.extend(resource_root.glob("data/*/structure*/**/*.nbt"))
    for mod_root in structure_mod_roots(root):
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


def create_handler(root: Path) -> type[BaseHTTPRequestHandler]:
    root = root.resolve()
    web_root = (Path(__file__).parent / "web").resolve()
    build_lock = threading.Lock()
    editor_catalog_lock = threading.Lock()
    editor_catalog: dict[str, Any] | None = None
    structure_size_catalog_lock = threading.Lock()
    structure_size_catalog: dict[str, Any] | None = None
    structure_size_catalog_signature: tuple[tuple[str, int, int], ...] | None = None
    structure_model_cache: dict[str, dict[str, Any]] = {}
    structure_model_cache_signature: tuple[tuple[str, int, int], ...] | None = None
    remote_image_cache: dict[str, bytes] = {}
    remote_image_cache_lock = threading.Lock()

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
                "/styles.css": web_root / "styles.css",
                "/pokemon-entry-clipboard.mjs": root
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
            if request.path in {"/dependencies", "/api/dependencies"}:
                try:
                    self._json(200, load_json(root / "pack" / "dependencies.lock.json"))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path in {"/validate", "/api/validate"}:
                query = parse_qs(request.query)
                strict_pack = query.get("strict_pack", ["false"])[0].lower() in {"1", "true", "yes"}
                result = validate_repository(root, strict_pack)
                self._json(200 if result.valid else 422, result.as_json())
                return
            if request.path == "/api/dashboard":
                result = validate_repository(root)
                self._json(
                    200,
                    {
                        "trainers": len(_list_documents(root, "trainers")),
                        "settlements": len(_list_documents(root, "settlements")),
                        "validation": result.as_json(),
                        "build_commands": [
                            {"id": command, "description": description}
                            for command, description in BUILD_COMMANDS.items()
                        ],
                    },
                )
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
                        root / "tools" / "content-manager" / "skin-pipeline" / "work"
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
                    root
                    / "projects"
                    / "cobbleventure-world-bootstrap"
                    / "src"
                    / "main"
                    / "resources"
                    / "assets"
                ).resolve()
                manual_retouch_root = (
                    root
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
                    root / "tools" / "content-manager" / "skin-pipeline" / "work"
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
                            editor_catalog = load_editor_catalog(root)
                    self._json(200, editor_catalog)
                except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/structure-sizes":
                nonlocal structure_size_catalog, structure_size_catalog_signature
                try:
                    with structure_size_catalog_lock:
                        signature = structure_catalog_signature(root)
                        if structure_size_catalog is None or signature != structure_size_catalog_signature:
                            structure_size_catalog = load_structure_size_catalog(root)
                            structure_size_catalog_signature = signature
                    self._json(200, structure_size_catalog)
                except (OSError, ValueError, zipfile.BadZipFile) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/api/structure-model":
                nonlocal structure_model_cache_signature
                resource_id = parse_qs(request.query).get("structure", [""])[0]
                try:
                    with structure_size_catalog_lock:
                        signature = structure_catalog_signature(root)
                        if signature != structure_model_cache_signature:
                            structure_model_cache.clear()
                            structure_model_cache_signature = signature
                        model = structure_model_cache.get(resource_id)
                        if model is None:
                            loaded = load_structure_model(root, resource_id)
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
            if request.path == "/api/build":
                command = payload.get("command") if isinstance(payload, dict) else None
                if not isinstance(command, str) or command not in BUILD_COMMANDS:
                    self._json(400, {"error": "허용된 빌드 명령을 선택해야 합니다."})
                    return
                if not build_lock.acquire(blocking=False):
                    self._json(409, {"error": "다른 빌드 명령이 실행 중입니다."})
                    return
                try:
                    result = _run_build(root, command)
                finally:
                    build_lock.release()
                self._json(200 if result["success"] else 422, result)
                return
            self._json(404, {"error": "not_found"})

        def do_PUT(self) -> None:
            request = urlparse(self.path)
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

    return Handler


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Cobbleventure 콘텐츠 관리 도구")
    subcommands = parser.add_subparsers(dest="command", required=True)

    validate = subcommands.add_parser("validate", help="콘텐츠와 의존성 Lock 검증")
    validate.add_argument("--root", type=Path, default=Path.cwd())
    validate.add_argument("--strict-pack", action="store_true")
    validate.add_argument("--json", action="store_true", dest="json_output")

    generate = subcommands.add_parser("generate", help="RCT와 실제 게임용 AI 프로필 생성")
    generate.add_argument("--root", type=Path, default=Path.cwd())
    generate.add_argument("--output", type=Path)
    generate.add_argument("--json", action="store_true", dest="json_output")

    api = subcommands.add_parser("api", help="로컬 Web API 실행")
    api.add_argument("--root", type=Path, default=Path.cwd())
    api.add_argument("--host", default="127.0.0.1")
    api.add_argument("--port", type=int, default=8765)
    return parser


def main() -> int:
    arguments = _parser().parse_args()
    if arguments.command == "validate":
        result = validate_repository(arguments.root, arguments.strict_pack)
        if arguments.json_output:
            print(json.dumps(result.as_json(), ensure_ascii=False, indent=2))
        else:
            _print_result(result)
        return 0 if result.valid else 1

    if arguments.command == "generate":
        try:
            result = generate_content(arguments.root, arguments.output)
        except ValueError as error:
            print(f"[ERROR] {error}")
            return 1
        if arguments.json_output:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        else:
            print(f"[OK] 트레이너 {result['count']}개 생성: {result['output']}")
        return 0

    root = arguments.root.resolve()
    server = ThreadingHTTPServer((arguments.host, arguments.port), create_handler(root))
    print(f"Cobbleventure Content Manager: http://{arguments.host}:{arguments.port}")
    print(f"저장소: {root}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nAPI를 종료합니다.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

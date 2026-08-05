from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
from dataclasses import asdict, dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
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
OPERATION_TYPES = {
    "always",
    "flag_equals",
    "next_dialogue",
    "close_dialogue",
    "start_battle",
    "set_flag",
    "give_item",
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
    "pack-smoke": "최소 CurseForge 임포트 ZIP 생성",
    "pack": "개발용 CurseForge ZIP 생성",
    "validate-pack": "실제 모드팩 빌드 준비 상태 검사",
}
STATIC_CONTENT_TYPES = {
    ".css": "text/css; charset=utf-8",
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
}


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
        trainer = _resource_id(operation.get("trainer"), issues, file, f"{data_path}.trainer")
        if trainer and trainer != content_id:
            _issue(issues, "error", file, f"{data_path}.trainer", "현재 콘텐츠의 트레이너 ID와 일치해야 합니다.")
    elif operation_type in {"flag_equals", "set_flag"}:
        _resource_id(operation.get("key"), issues, file, f"{data_path}.key")
        if "value" not in operation or not isinstance(operation.get("value"), (str, int, float, bool)):
            _issue(issues, "error", file, f"{data_path}.value", "문자열, 숫자 또는 boolean 값이 필요합니다.")
    elif operation_type == "give_item":
        _resource_id(operation.get("item"), issues, file, f"{data_path}.item")
        count = operation.get("count")
        if not isinstance(count, int) or isinstance(count, bool) or count < 1:
            _issue(issues, "error", file, f"{data_path}.count", "1 이상의 정수가 필요합니다.")
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
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")
    settlement_id = _resource_id(root.get("id"), issues, path, "$.id")
    if not isinstance(root.get("enabled"), bool):
        _issue(issues, "error", path, "$.enabled", "boolean이어야 합니다.")
    _localized_text(root.get("display_name"), issues, path, "$.display_name")
    _resource_id(root.get("region"), issues, path, "$.region")
    _resource_id(root.get("dimension"), issues, path, "$.dimension")

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
                _validate_block_position(slot.get("position"), issues, path, f"{slot_path}.position")
                rotation = slot.get("rotation")
                if not isinstance(rotation, (int, float)) or isinstance(rotation, bool):
                    _issue(issues, "error", path, f"{slot_path}.rotation", "숫자여야 합니다.")
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
        _localized_text(trainer_class.get("display_name"), issues, path, f"{class_path}.display_name")
        _localized_text(trainer_class.get("title_pattern"), issues, path, f"{class_path}.title_pattern")
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
        tags = _require_list(trainer_class.get("tags"), issues, path, f"{class_path}.tags")
        if tags is not None:
            for tag_index, tag in enumerate(tags):
                if not isinstance(tag, str) or not CHOICE_ID.fullmatch(tag):
                    _issue(issues, "error", path, f"{class_path}.tags[{tag_index}]", "올바른 태그가 아닙니다.")
    return issues


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

    if status == "draft" and not strict_pack:
        _issue(
            issues,
            "warning",
            path,
            "$.status",
            "의존성이 draft 상태입니다. 일반 콘텐츠 개발은 가능하지만 테스트팩 패키징은 차단됩니다.",
        )
    return issues


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
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")

    content_id = _resource_id(root.get("id"), issues, path, "$.id")
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

    placement = _require_object(root.get("placement"), issues, path, "$.placement")
    if placement is not None:
        for key in ("region", "settlement", "anchor"):
            _resource_id(placement.get(key), issues, path, f"$.placement.{key}")
        offset = _require_object(placement.get("offset"), issues, path, "$.placement.offset")
        if offset is not None:
            for key in ("x", "y", "z"):
                value = offset.get(key)
                if not isinstance(value, (int, float)) or isinstance(value, bool):
                    _issue(issues, "error", path, f"$.placement.offset.{key}", "숫자여야 합니다.")
        if placement.get("spawn_policy") not in {"persistent", "on_region_load", "manual"}:
            _issue(issues, "error", path, "$.placement.spawn_policy", "지원하지 않는 생성 정책입니다.")

    battle = _require_object(root.get("battle"), issues, path, "$.battle")
    if battle is not None:
        trainer_id = _resource_id(battle.get("trainer_id"), issues, path, "$.battle.trainer_id")
        if trainer_id and trainer_id != content_id:
            _issue(issues, "error", path, "$.battle.trainer_id", "최상위 콘텐츠 ID와 일치해야 합니다.")
        _require_string(battle.get("format"), issues, path, "$.battle.format")
        _resource_id(battle.get("ai"), issues, path, "$.battle.ai")
        if battle.get("battle_type") not in {"singles", "doubles"}:
            _issue(issues, "error", path, "$.battle.battle_type", "singles 또는 doubles여야 합니다.")
        if battle.get("level_mode") not in {"fixed", "scale_to_player", "cap_to_player"}:
            _issue(issues, "error", path, "$.battle.level_mode", "지원하지 않는 레벨 방식입니다.")
        _require_object(battle.get("rules"), issues, path, "$.battle.rules")
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
                for boolean_key in ("shiny", "gigantamax_factor"):
                    if boolean_key in pokemon and not isinstance(pokemon.get(boolean_key), bool):
                        _issue(issues, "error", path, f"{pokemon_path}.{boolean_key}", "boolean이어야 합니다.")
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


def validate_repository(root: Path, strict_pack: bool = False) -> ValidationResult:
    root = root.resolve()
    issues = validate_dependency_lock(root / "pack" / "dependencies.lock.json", strict_pack)
    trainer_class_path = root / "content" / "catalogs" / "trainer-classes.json"
    issues.extend(validate_trainer_class_catalog(trainer_class_path))
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
    content_dir = root / "content" / "source"
    seen_content: dict[str, Path] = {}
    if not content_dir.exists():
        _issue(issues, "error", content_dir, "$", "콘텐츠 원본 디렉터리가 없습니다.")
    else:
        for path in sorted(content_dir.rglob("*.json")):
            content_id, file_issues = validate_content_file(path)
            issues.extend(file_issues)
            try:
                selected_class = load_json(path).get("npc", {}).get("trainer_class")
                if isinstance(selected_class, str) and selected_class not in trainer_class_ids:
                    _issue(
                        issues,
                        "error",
                        path,
                        "$.npc.trainer_class",
                        f"카탈로그에 없는 트레이너 클래스입니다: {selected_class}",
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

    settlement_dir = root / "content" / "settlements"
    seen_settlements: dict[str, Path] = {}
    if settlement_dir.exists():
        for path in sorted(settlement_dir.rglob("*.json")):
            settlement_id, file_issues = validate_settlement_file(path)
            issues.extend(file_issues)
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


def _managed_directory(root: Path, category: str) -> Path:
    directories = {
        "trainers": root / "content" / "source",
        "settlements": root / "content" / "settlements",
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
    documents: list[dict[str, Any]] = []
    for path in sorted(base.rglob("*.json")):
        try:
            data = load_json(path)
            documents.append(
                {
                    "path": path.relative_to(root).as_posix(),
                    "id": data.get("id", ""),
                    "name": _localized_value(
                        data.get("name")
                        if category == "trainers"
                        else data.get("display_name")
                    ),
                    "enabled": data.get("enabled", False),
                }
            )
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
    validator = (
        validate_content_file if category == "trainers" else validate_settlement_file
    )
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


def _trainer_template(slug: str, name: str) -> dict[str, Any]:
    trainer_id = f"cobbleventure:trainer/{slug}"
    dialogue_id = f"cobbleventure:dialogue/{slug}/greeting"
    victory_flag = f"cobbleventure:flag/trainer/{slug}/defeated"
    return {
        "$schema": "../../schemas/content-bundle.schema.json",
        "schema_version": 1,
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
                "invulnerable": True,
                "collision": True,
            },
        },
        "placement": {
            "region": "cobbleventure:generation_1/region_01",
            "settlement": "cobbleventure:settlement/starter_town",
            "anchor": f"cobbleventure:anchor/{slug}",
            "offset": {"x": 0, "y": 0, "z": 0},
            "rotation": 0,
            "spawn_policy": "persistent",
        },
        "battle": {
            "trainer_id": trainer_id,
            "format": "GEN_9_SINGLES",
            "battle_type": "singles",
            "ai": "cobbleventure:ai/balanced",
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
                    "moves": ["tackle"],
                    "ivs": {},
                    "evs": {},
                    "tera_type": None,
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
    }


def _settlement_template(slug: str, name: str, generation: str) -> dict[str, Any]:
    return {
        "$schema": "../../schemas/settlement.schema.json",
        "schema_version": 1,
        "id": f"cobbleventure:settlement/{slug}",
        "enabled": True,
        "display_name": {"ko_kr": name},
        "region": f"cobbleventure:{generation}/region_01",
        "dimension": f"cobbleventure:{generation}",
        "bounds": {"min_x": -32, "min_z": -32, "max_x": 32, "max_z": 32},
        "center": {"x": 0, "y": 64, "z": 0},
        "anchors": {"town_square": {"x": 0, "y": 64, "z": 0}},
        "npc_placement": {
            "max_ambient_npcs": 8,
            "default_wander_radius": 5,
            "trainer_slots": [],
            "zones": [],
        },
    }


def _create_document(
    root: Path, category: str, slug: str, name: str, generation: str = "generation_1"
) -> tuple[Path | None, list[Issue]]:
    if category not in {"trainers", "settlements"}:
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
        document = _trainer_template(slug, name.strip())
    else:
        relative_path = f"content/settlements/{generation}/{slug}.json"
        document = _settlement_template(slug, name.strip(), generation)
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


def create_handler(root: Path) -> type[BaseHTTPRequestHandler]:
    root = root.resolve()
    web_root = (Path(__file__).parent / "web").resolve()
    build_lock = threading.Lock()
    editor_catalog_lock = threading.Lock()
    editor_catalog: dict[str, Any] | None = None

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
                "/": "index.html",
                "/index.html": "index.html",
                "/app.js": "app.js",
                "/styles.css": "styles.css",
            }
            file_name = static_files.get(request_path)
            if file_name is None:
                return False
            path = web_root / file_name
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
            if request.path == "/api/trainers":
                self._document_response("trainers", request)
                return
            if request.path == "/api/settlements":
                self._document_response("settlements", request)
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
                if category not in {"trainers", "settlements"}:
                    self._json(400, {"error": "지원하지 않는 문서 종류입니다."})
                    return
                validator = (
                    validate_content_file
                    if category == "trainers"
                    else validate_settlement_file
                )
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
            if request.path == "/api/documents":
                if not isinstance(payload, dict):
                    self._json(400, {"error": "문서 생성 정보가 필요합니다."})
                    return
                category = payload.get("category")
                slug = payload.get("slug")
                name = payload.get("name")
                generation = payload.get("generation", "generation_1")
                if not all(isinstance(value, str) for value in (category, slug, name, generation)):
                    self._json(400, {"error": "문서 종류, 파일 ID와 이름을 문자열로 입력해야 합니다."})
                    return
                target, issues = _create_document(
                    root, category, slug, name, generation
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
            categories = {
                "/api/trainers": "trainers",
                "/api/settlements": "settlements",
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

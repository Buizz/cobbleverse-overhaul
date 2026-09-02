"""Safe filesystem boundary used by the CVES web tree editor."""

from __future__ import annotations

import hashlib
import os
import re
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any

from . import ast
from .catalog import ResourceCatalog, ResourceKind
from .codec import decode_program, encode_expression, encode_program
from .compiler import IMPLICIT_AWAIT_COMMANDS
from .diagnostics import Diagnostic
from .formatter import format_expression, format_program
from .parser import AWAIT_COMMANDS, parse, parse_expression
from .semantic import COMMANDS, RESULT_FIELDS, Parameter, validate


NAMESPACE = re.compile(r"^[a-z0-9_.-]+$")
RESOURCE_PATH = re.compile(r"^[a-z0-9_./-]+$")


class CvesEditorConflict(ValueError):
    """The source changed after the editor loaded it."""


def diagnostic_document(diagnostic: Diagnostic) -> dict[str, Any]:
    """Return a stable, JSON-compatible diagnostic contract for the GUI."""
    return {
        "source": diagnostic.span.source,
        "line": diagnostic.span.start.line,
        "column": diagnostic.span.start.column,
        "end_line": diagnostic.span.end.line,
        "end_column": diagnostic.span.end.column,
        "message": diagnostic.message,
        "token": diagnostic.token,
        "rendered": diagnostic.render(),
    }


def resolve_script_path(project_root: Path, relative_path: str) -> Path:
    """Resolve an editor path beneath content/events without accepting traversal."""
    if not isinstance(relative_path, str) or not relative_path:
        raise ValueError("CVES 파일 경로가 필요합니다.")
    if "\\" in relative_path:
        raise ValueError("CVES 파일 경로에는 / 구분자를 사용해야 합니다.")
    relative = PurePosixPath(relative_path)
    if relative.is_absolute() or ".." in relative.parts or len(relative.parts) < 2:
        raise ValueError("CVES 경로는 <namespace>/<path>.cves 형식이어야 합니다.")
    namespace = relative.parts[0]
    resource_path = PurePosixPath(*relative.parts[1:]).as_posix()
    if not resource_path.endswith(".cves"):
        raise ValueError("CVES 파일 확장자는 .cves여야 합니다.")
    resource_id_path = resource_path[:-5]
    if (
        not NAMESPACE.fullmatch(namespace)
        or not RESOURCE_PATH.fullmatch(resource_id_path)
        or "//" in relative_path
    ):
        raise ValueError("올바르지 않은 CVES 리소스 경로입니다.")
    event_root = (project_root / "content" / "events").resolve()
    target = event_root.joinpath(*relative.parts).resolve()
    if target != event_root and event_root not in target.parents:
        raise ValueError("CVES 이벤트 디렉터리 밖의 파일은 편집할 수 없습니다.")
    return target


def list_scripts(project_root: Path) -> list[dict[str, str]]:
    event_root = project_root / "content" / "events"
    items: list[dict[str, str]] = []
    for source in sorted(event_root.rglob("*.cves")) if event_root.is_dir() else []:
        relative = source.relative_to(event_root).as_posix()
        target = resolve_script_path(project_root, relative)
        resource_path = PurePosixPath(relative)
        script_path = PurePosixPath(*resource_path.parts[1:]).as_posix()[:-5]
        items.append({
            "path": target.relative_to(event_root.resolve()).as_posix(),
            "script_id": f"{resource_path.parts[0]}:event_script/{script_path}",
            "name": resource_path.stem,
        })
    return items


def load_script(
    project_root: Path,
    relative_path: str,
    catalog: ResourceCatalog | None = None,
) -> dict[str, Any]:
    target = resolve_script_path(project_root, relative_path)
    source = target.read_bytes().decode("utf-8")
    program = parse(source, relative_path)
    diagnostics = validate(program, catalog)
    return _editor_document(relative_path, source, program, diagnostics)


def validate_source(
    source: str,
    relative_path: str = "<editor>",
    catalog: ResourceCatalog | None = None,
) -> dict[str, Any]:
    if not isinstance(source, str):
        raise ValueError("CVES source는 문자열이어야 합니다.")
    program = parse(source, relative_path)
    diagnostics = validate(program, catalog)
    return _editor_document(relative_path, source, program, diagnostics)


def parse_editor_expression(
    source: str, relative_path: str = "<expression>"
) -> dict[str, Any]:
    if not isinstance(source, str):
        raise ValueError("CVES expression source는 문자열이어야 합니다.")
    expression = parse_expression(source, relative_path)
    return {
        "source": source,
        "canonical": format_expression(expression),
        "expression": encode_expression(expression, include_spans=False),
    }


def editor_contract(catalog: ResourceCatalog) -> dict[str, Any]:
    """Serialize semantic contracts instead of duplicating them in JavaScript."""
    commands: list[dict[str, Any]] = []
    for kind, contract in COMMANDS.items():
        commands.append({
            "id": kind.value,
            "awaited": kind in AWAIT_COMMANDS,
            "waits_for_completion": kind in AWAIT_COMMANDS or kind in IMPLICIT_AWAIT_COMMANDS,
            "advanced": kind in {
                ast.CommandKind.LABEL, ast.CommandKind.JUMP,
                ast.CommandKind.CALL, ast.CommandKind.RETURN,
            },
            "result_type": contract.result.value if contract.result is not None else None,
            "positional": [_parameter_document(value) for value in contract.positional],
            "named": [_parameter_document(value) for value in contract.named],
            "flags": sorted(contract.flags),
            "properties": [_parameter_document(value) for value in contract.properties],
        })
    resources = {
        kind.value: sorted(values)
        for kind, values in sorted(catalog.resources.items(), key=lambda item: item[0].value)
    }
    anchors = {
        f"{kind.value}:{resource_id}": sorted(values)
        for (kind, resource_id), values in sorted(
            catalog.anchors.items(), key=lambda item: (item[0][0].value, item[0][1])
        )
    }
    range_triggers = {"interact", "proximity_enter", "proximity_exit"}
    target_kinds = {
        "region_enter": ResourceKind.EVENT_REGION,
        "region_exit": ResourceKind.EVENT_REGION,
        "anchor_step": ResourceKind.EVENT_ANCHOR,
        "building_enter": ResourceKind.BUILDING,
        "building_exit": ResourceKind.BUILDING,
        "dimension_enter": ResourceKind.DIMENSION,
        "dimension_exit": ResourceKind.DIMENSION,
        "flag_changed": ResourceKind.FLAG,
        "item_used": ResourceKind.ITEM,
        "battle_finished": ResourceKind.BATTLE,
    }
    triggers: list[dict[str, Any]] = []
    for trigger_id in sorted(range_triggers | set(target_kinds)):
        parameters: list[dict[str, Any]] = []
        if trigger_id in range_triggers:
            parameters.append(_parameter_document(Parameter(
                "range", frozenset({ast.ValueType.INT, ast.ValueType.DECIMAL}), optional=True
            )))
        if trigger_id in {"proximity_enter", "proximity_exit"}:
            parameters.extend([
                _parameter_document(Parameter("group", frozenset({ast.ValueType.STRING}), optional=True)),
                _parameter_document(Parameter("stage", frozenset({ast.ValueType.STRING}), optional=True)),
                _parameter_document(Parameter("after", frozenset({ast.ValueType.STRING}), optional=True)),
            ])
        if trigger_id in target_kinds:
            parameters.append(_parameter_document(Parameter(
                "target",
                frozenset({ast.ValueType.RESOURCE_ID}),
                resource_kind=target_kinds[trigger_id],
            )))
        parameters.extend([
            _parameter_document(Parameter("once", frozenset({ast.ValueType.BOOL}), optional=True)),
            _parameter_document(Parameter(
                "cooldown", frozenset({ast.ValueType.INT, ast.ValueType.DECIMAL}), optional=True
            )),
            _parameter_document(Parameter(
                "scope", allowed_names=frozenset({"player", "world", "party", "instance"}), optional=True
            )),
        ])
        triggers.append({"id": trigger_id, "arguments": parameters})
    return {
        "commands": commands,
        "triggers": triggers,
        "resources": resources,
        "anchors": anchors,
        "speakers": ["npc", "player", "system"],
        "condition_presets": ["always", "flag", "money", "not", "all", "any", "advanced"],
        "result_fields": {
            result_type.value: [
                {"name": name, "type": value_type.value}
                for name, value_type in fields.items()
            ]
            for result_type, fields in RESULT_FIELDS.items()
        },
        "template_filters": {
            "localized_name": ["josa:을/를", "josa:이/가", "josa:은/는"],
            "string": ["josa:을/를", "josa:이/가", "josa:은/는"],
        },
    }


def validate_ast(
    wire_ast: object,
    relative_path: str = "<editor>",
    catalog: ResourceCatalog | None = None,
) -> dict[str, Any]:
    program = decode_program(wire_ast)
    canonical = format_program(program)
    # Reparse the formatter result so GUI-created nodes receive useful locations.
    canonical_program = parse(canonical, relative_path)
    diagnostics = validate(canonical_program, catalog)
    return _editor_document(
        relative_path, canonical, canonical_program, diagnostics, canonical=canonical
    )


def save_script(
    project_root: Path,
    relative_path: str,
    wire_ast: object,
    expected_digest: str | None,
    catalog: ResourceCatalog | None = None,
    *,
    usage_digest: str | None = None,
) -> dict[str, Any]:
    from .library import check_source_write
    check_source_write(project_root, relative_path, usage_digest)
    target = resolve_script_path(project_root, relative_path)
    document = validate_ast(wire_ast, relative_path, catalog)
    if not document["valid"]:
        return {**document, "saved": False}

    if target.is_file():
        current_digest = _digest(target.read_bytes())
        if not isinstance(expected_digest, str) or expected_digest != current_digest:
            raise CvesEditorConflict(
                "CVES 원본이 편집기를 연 뒤 변경되었습니다. 다시 불러온 뒤 저장해 주세요."
            )
    elif expected_digest not in (None, ""):
        raise CvesEditorConflict("새 CVES 파일 경로에 다른 원본이 생겼습니다. 목록을 새로고침해 주세요.")

    target.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(
        prefix=f".{target.stem}-", suffix=".cves.tmp", dir=target.parent
    )
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as output:
            output.write(document["canonical"])
        os.replace(temporary_name, target)
    finally:
        Path(temporary_name).unlink(missing_ok=True)

    saved_bytes = target.read_bytes()
    saved_source = saved_bytes.decode("utf-8")
    return {
        **document,
        "source": saved_source,
        "digest": _digest(saved_bytes),
        "saved": True,
    }


def _editor_document(
    relative_path: str,
    source: str,
    program: object,
    diagnostics: tuple[Diagnostic, ...],
    *,
    canonical: str | None = None,
) -> dict[str, Any]:
    canonical_source = canonical if canonical is not None else format_program(program)
    return {
        "path": relative_path,
        "source": source,
        "canonical": canonical_source,
        "digest": _digest(source.encode("utf-8")),
        "ast": encode_program(program, include_spans=False),
        "valid": not diagnostics,
        "diagnostics": [diagnostic_document(item) for item in diagnostics],
    }


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _parameter_document(parameter: Parameter) -> dict[str, Any]:
    return {
        "name": parameter.name,
        "types": sorted(value.value for value in parameter.types),
        "optional": parameter.optional,
        "allowed_names": sorted(parameter.allowed_names),
        "resource_kind": (
            parameter.resource_kind.value if parameter.resource_kind is not None else None
        ),
    }

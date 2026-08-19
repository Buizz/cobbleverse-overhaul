"""Versioned JSON-compatible wire codec for the shared CVES AST."""

from __future__ import annotations

from dataclasses import MISSING, fields, is_dataclass
from enum import Enum
from typing import Any

from . import ast
from .diagnostics import SourcePosition, SourceSpan


WIRE_VERSION = 1


class AstCodecError(ValueError):
    def __init__(self, path: str, message: str) -> None:
        self.path = path
        self.message = message
        super().__init__(f"{path}: {message}")


NODE_TYPES = (
    SourcePosition, SourceSpan,
    ast.LiteralExpression, ast.NameExpression, ast.MemberExpression,
    ast.CallExpression, ast.UnaryExpression, ast.BinaryExpression,
    ast.Argument, ast.Property, ast.Trigger,
    ast.TextLiteral, ast.LocalizedTextEntry, ast.LocalizedText,
    ast.SayStatement, ast.NarrateStatement, ast.LetStatement,
    ast.IfStatement, ast.ChoiceOption, ast.ChoiceStatement,
    ast.RepeatStatement, ast.CommandStatement,
    ast.Block, ast.Page, ast.Event, ast.Program,
)

TAGS = {
    SourcePosition: "source_position",
    SourceSpan: "source_span",
    ast.LiteralExpression: "literal",
    ast.NameExpression: "name",
    ast.MemberExpression: "member",
    ast.CallExpression: "call",
    ast.UnaryExpression: "unary",
    ast.BinaryExpression: "binary",
    ast.Argument: "argument",
    ast.Property: "property",
    ast.Trigger: "trigger",
    ast.TextLiteral: "text",
    ast.LocalizedTextEntry: "localized_entry",
    ast.LocalizedText: "localized_text",
    ast.SayStatement: "say",
    ast.NarrateStatement: "narrate",
    ast.LetStatement: "let",
    ast.IfStatement: "if",
    ast.ChoiceOption: "choice_option",
    ast.ChoiceStatement: "choice",
    ast.RepeatStatement: "repeat",
    ast.CommandStatement: "command",
    ast.Block: "block",
    ast.Page: "page",
    ast.Event: "event",
    ast.Program: "program",
}
CLASSES = {tag: node_type for node_type, tag in TAGS.items()}

ENUM_FIELDS = {
    (ast.LiteralExpression, "value_type"): ast.ValueType,
    (ast.CommandStatement, "kind"): ast.CommandKind,
}

TUPLE_FIELDS = {
    (ast.CallExpression, "arguments"),
    (ast.Trigger, "arguments"),
    (ast.LocalizedText, "entries"),
    (ast.ChoiceStatement, "options"),
    (ast.CommandStatement, "arguments"),
    (ast.CommandStatement, "properties"),
    (ast.Block, "statements"),
    (ast.Event, "pages"),
    (ast.Program, "events"),
}

STRING_FIELDS = {
    (SourceSpan, "source"),
    (ast.NameExpression, "name"),
    (ast.MemberExpression, "member"),
    (ast.UnaryExpression, "operator"),
    (ast.BinaryExpression, "operator"),
    (ast.Property, "name"),
    (ast.Trigger, "name"),
    (ast.TextLiteral, "value"),
    (ast.LocalizedTextEntry, "language"),
    (ast.LocalizedTextEntry, "value"),
    (ast.SayStatement, "speaker"),
    (ast.LetStatement, "name"),
}

OPTIONAL_STRING_FIELDS = {
    (ast.Argument, "name"),
    (ast.ChoiceStatement, "result"),
    (ast.CommandStatement, "result"),
    *((node_type, "stable_id") for node_type in (
        ast.SayStatement, ast.NarrateStatement, ast.LetStatement,
        ast.IfStatement, ast.ChoiceStatement, ast.RepeatStatement,
        ast.CommandStatement,
    )),
}


def encode_program(program: ast.Program, *, include_spans: bool = True) -> dict[str, Any]:
    return {
        "wire_version": WIRE_VERSION,
        "root": _encode_node(program, include_spans),
    }


def decode_program(data: object) -> ast.Program:
    if not isinstance(data, dict):
        raise AstCodecError("$", "AST wire 루트는 객체여야 합니다.")
    unknown = set(data) - {"wire_version", "root"}
    if unknown:
        raise AstCodecError("$", f"지원하지 않는 필드입니다: {', '.join(sorted(unknown))}")
    if data.get("wire_version") != WIRE_VERSION:
        raise AstCodecError("$.wire_version", f"지원하는 AST wire 버전은 {WIRE_VERSION}입니다.")
    if "root" not in data:
        raise AstCodecError("$.root", "program 노드가 필요합니다.")
    root = _decode_node(data["root"], "$.root")
    if not isinstance(root, ast.Program):
        raise AstCodecError("$.root", "최상위 노드는 program이어야 합니다.")
    return root


def encode_expression(
    expression: ast.Expression, *, include_spans: bool = True
) -> dict[str, Any]:
    return {
        "wire_version": WIRE_VERSION,
        "root": _encode_node(expression, include_spans),
    }


def decode_expression(data: object) -> ast.Expression:
    if not isinstance(data, dict):
        raise AstCodecError("$", "AST wire 루트는 객체여야 합니다.")
    unknown = set(data) - {"wire_version", "root"}
    if unknown:
        raise AstCodecError("$", f"지원하지 않는 필드입니다: {', '.join(sorted(unknown))}")
    if data.get("wire_version") != WIRE_VERSION:
        raise AstCodecError("$.wire_version", f"지원하는 AST wire 버전은 {WIRE_VERSION}입니다.")
    if "root" not in data:
        raise AstCodecError("$.root", "expression 노드가 필요합니다.")
    root = _decode_node(data["root"], "$.root")
    if not isinstance(root, ast.Expression):
        raise AstCodecError("$.root", "최상위 노드는 expression이어야 합니다.")
    return root


def _encode_node(value: object, include_spans: bool) -> object:
    if value is None or isinstance(value, (str, int, bool)):
        return value
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, tuple):
        return [_encode_node(item, include_spans) for item in value]
    if not is_dataclass(value) or type(value) not in TAGS:
        raise TypeError(f"AST wire로 인코딩할 수 없는 값입니다: {type(value).__name__}")
    result: dict[str, object] = {"node": TAGS[type(value)]}
    for data_field in fields(value):
        if data_field.name == "span" and not include_spans:
            continue
        result[data_field.name] = _encode_node(getattr(value, data_field.name), include_spans)
    return result


def _decode_node(value: object, path: str) -> object:
    if not isinstance(value, dict):
        raise AstCodecError(path, "노드는 객체여야 합니다.")
    tag = value.get("node")
    if not isinstance(tag, str) or tag not in CLASSES:
        raise AstCodecError(f"{path}.node", f"지원하지 않는 노드 종류입니다: {tag!r}")
    node_type = CLASSES[tag]
    node_fields = {data_field.name: data_field for data_field in fields(node_type)}
    unknown = set(value) - {"node", *node_fields}
    if unknown:
        raise AstCodecError(path, f"지원하지 않는 필드입니다: {', '.join(sorted(unknown))}")

    arguments: dict[str, object] = {}
    for name, data_field in node_fields.items():
        field_path = f"{path}.{name}"
        if name not in value:
            if data_field.default is not MISSING:
                arguments[name] = data_field.default
                continue
            raise AstCodecError(field_path, "필수 필드가 없습니다.")
        raw = value[name]
        enum_type = ENUM_FIELDS.get((node_type, name))
        if enum_type is not None:
            if not isinstance(raw, str):
                raise AstCodecError(field_path, "enum 값은 문자열이어야 합니다.")
            try:
                arguments[name] = enum_type(raw)
            except ValueError as error:
                raise AstCodecError(field_path, f"지원하지 않는 enum 값입니다: {raw!r}") from error
        elif (node_type, name) in TUPLE_FIELDS:
            if not isinstance(raw, list):
                raise AstCodecError(field_path, "목록이어야 합니다.")
            arguments[name] = tuple(_decode_node(item, f"{field_path}[{index}]") for index, item in enumerate(raw))
        elif name == "span":
            arguments[name] = None if raw is None else _decode_node(raw, field_path)
        elif isinstance(raw, dict):
            arguments[name] = _decode_node(raw, field_path)
        else:
            arguments[name] = raw
    try:
        result = node_type(**arguments)
    except TypeError as error:
        raise AstCodecError(path, f"노드를 만들 수 없습니다: {error}") from error
    _validate_node(result, path)
    return result


def _validate_node(value: object, path: str) -> None:
    node_type = type(value)
    for name in (field.name for field in fields(value)):
        field_path = f"{path}.{name}"
        field_value = getattr(value, name)
        if (node_type, name) in STRING_FIELDS and not isinstance(field_value, str):
            raise AstCodecError(field_path, "문자열이어야 합니다.")
        if (node_type, name) in OPTIONAL_STRING_FIELDS and field_value is not None and not isinstance(field_value, str):
            raise AstCodecError(field_path, "문자열 또는 null이어야 합니다.")
    if isinstance(value, SourcePosition):
        for name in ("offset", "line", "column"):
            number = getattr(value, name)
            minimum = 0 if name == "offset" else 1
            if not isinstance(number, int) or isinstance(number, bool) or number < minimum:
                raise AstCodecError(f"{path}.{name}", f"{minimum} 이상의 정수여야 합니다.")
    elif isinstance(value, SourceSpan):
        _require_instance(value.start, SourcePosition, f"{path}.start")
        _require_instance(value.end, SourcePosition, f"{path}.end")
    elif isinstance(value, ast.LiteralExpression):
        valid = (
            isinstance(value.value, bool) if value.value_type is ast.ValueType.BOOL else
            isinstance(value.value, int) and not isinstance(value.value, bool) if value.value_type is ast.ValueType.INT else
            isinstance(value.value, str)
        )
        if not valid:
            raise AstCodecError(f"{path}.value", f"{value.value_type.value} 리터럴 값이 올바르지 않습니다.")
    elif isinstance(value, ast.MemberExpression):
        _require_expression(value.target, f"{path}.target")
    elif isinstance(value, ast.CallExpression):
        _require_expression(value.callee, f"{path}.callee")
        _require_all(value.arguments, ast.Argument, f"{path}.arguments")
    elif isinstance(value, ast.UnaryExpression):
        _require_expression(value.operand, f"{path}.operand")
    elif isinstance(value, ast.BinaryExpression):
        _require_expression(value.left, f"{path}.left")
        _require_expression(value.right, f"{path}.right")
    elif isinstance(value, ast.Argument):
        if value.value is not None: _require_expression(value.value, f"{path}.value")
    elif isinstance(value, ast.Property):
        _require_expression(value.value, f"{path}.value")
    elif isinstance(value, ast.Trigger):
        _require_all(value.arguments, ast.Argument, f"{path}.arguments")
    elif isinstance(value, ast.LocalizedText):
        _require_all(value.entries, ast.LocalizedTextEntry, f"{path}.entries")
    elif isinstance(value, ast.SayStatement):
        _require_text(value.text, f"{path}.text")
    elif isinstance(value, ast.NarrateStatement):
        _require_text(value.text, f"{path}.text")
    elif isinstance(value, ast.LetStatement):
        _require_expression(value.value, f"{path}.value")
    elif isinstance(value, ast.IfStatement):
        _require_expression(value.condition, f"{path}.condition")
        _require_instance(value.then_block, ast.Block, f"{path}.then_block")
        if value.else_block is not None: _require_instance(value.else_block, ast.Block, f"{path}.else_block")
    elif isinstance(value, ast.ChoiceOption):
        _require_text(value.text, f"{path}.text")
        _require_instance(value.block, ast.Block, f"{path}.block")
    elif isinstance(value, ast.ChoiceStatement):
        _require_text(value.prompt, f"{path}.prompt")
        _require_all(value.options, ast.ChoiceOption, f"{path}.options")
    elif isinstance(value, ast.RepeatStatement):
        _require_expression(value.count, f"{path}.count")
        _require_instance(value.block, ast.Block, f"{path}.block")
    elif isinstance(value, ast.CommandStatement):
        if not isinstance(value.awaited, bool): raise AstCodecError(f"{path}.awaited", "bool이어야 합니다.")
        _require_all(value.arguments, ast.Argument, f"{path}.arguments")
        _require_all(value.properties, ast.Property, f"{path}.properties")
    elif isinstance(value, ast.Block):
        for index, statement in enumerate(value.statements):
            if not isinstance(statement, ast.Statement):
                raise AstCodecError(f"{path}.statements[{index}]", "statement 노드여야 합니다.")
    elif isinstance(value, ast.Page):
        if value.condition is not None: _require_expression(value.condition, f"{path}.condition")
        _require_instance(value.block, ast.Block, f"{path}.block")
    elif isinstance(value, ast.Event):
        _require_instance(value.trigger, ast.Trigger, f"{path}.trigger")
        _require_all(value.pages, ast.Page, f"{path}.pages")
    elif isinstance(value, ast.Program):
        _require_all(value.events, ast.Event, f"{path}.events")
    span = getattr(value, "span", None)
    if span is not None and not isinstance(span, SourceSpan):
        raise AstCodecError(f"{path}.span", "source_span 또는 null이어야 합니다.")


def _require_expression(value: object, path: str) -> None:
    if not isinstance(value, ast.Expression):
        raise AstCodecError(path, "expression 노드여야 합니다.")


def _require_text(value: object, path: str) -> None:
    if not isinstance(value, ast.Text):
        raise AstCodecError(path, "text 또는 localized_text 노드여야 합니다.")


def _require_instance(value: object, expected: type, path: str) -> None:
    if not isinstance(value, expected):
        raise AstCodecError(path, f"{TAGS[expected]} 노드여야 합니다.")


def _require_all(values: tuple[object, ...], expected: type, path: str) -> None:
    for index, value in enumerate(values):
        _require_instance(value, expected, f"{path}[{index}]")

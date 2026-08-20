"""Deterministic lowering from the tree AST to address-based runtime IR."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field

from . import ast
from .catalog import ResourceCatalog
from .diagnostics import Diagnostic, SourcePosition, SourceSpan
from .formatter import format_program
from .parser import AWAIT_COMMANDS
from .semantic import validate


IR_VERSION = 1
SCRIPT_ID = re.compile(r"^[a-z0-9_.-]+:event_script/[a-z0-9_./-]+$")
UNKNOWN_POSITION = SourcePosition(0, 1, 1)
UNKNOWN_SPAN = SourceSpan("<ast>", UNKNOWN_POSITION, UNKNOWN_POSITION)

# Repeatable services still need a stable instruction anchor for await recovery,
# but must not be added to the cross-invocation idempotency journal.
REPEATABLE_AWAIT_COMMANDS = {ast.CommandKind.HEAL_PARTY}
PERSISTENT_COMMANDS = (AWAIT_COMMANDS - REPEATABLE_AWAIT_COMMANDS) | {
    ast.CommandKind.SHOW_CHOICES,
    ast.CommandKind.SET_FLAG,
    ast.CommandKind.SET_VARIABLE,
    ast.CommandKind.SET_PLAYER_VARIABLE,
    ast.CommandKind.UNLOCK_FEATURE,
    ast.CommandKind.SET_LEVEL_CAP,
    ast.CommandKind.GIVE_ITEM,
    ast.CommandKind.GIVE_LOOT,
    ast.CommandKind.GIVE_MONEY,
    ast.CommandKind.TAKE_MONEY,
    ast.CommandKind.GRANT_BADGE,
    ast.CommandKind.GRANT_FIELD_MOVE,
    ast.CommandKind.FADE,
    ast.CommandKind.WAIT,
    ast.CommandKind.SOUND,
    ast.CommandKind.EFFECT,
}
STABLE_COMMANDS = PERSISTENT_COMMANDS | REPEATABLE_AWAIT_COMMANDS
IMPLICIT_AWAIT_COMMANDS = {
    ast.CommandKind.SHOW_CHOICES,
    ast.CommandKind.GIVE_ITEM,
    ast.CommandKind.GIVE_LOOT,
    ast.CommandKind.FADE,
    ast.CommandKind.WAIT,
    ast.CommandKind.SOUND,
    ast.CommandKind.EFFECT,
}


class CvesCompilationError(Exception):
    def __init__(self, diagnostics: tuple[Diagnostic, ...]) -> None:
        self.diagnostics = diagnostics
        super().__init__("\n".join(value.render() for value in diagnostics))


def compile_program(
    program: ast.Program,
    script_id: str,
    catalog: ResourceCatalog | None = None,
) -> dict:
    diagnostics = list(validate(program, catalog))
    if not SCRIPT_ID.fullmatch(script_id):
        diagnostics.append(Diagnostic(
            "스크립트 ID는 namespace:event_script/path 형식이어야 합니다.",
            program.span or UNKNOWN_SPAN,
            script_id,
        ))
    for statement in _walk_program(program):
        if (
            isinstance(statement, ast.CommandStatement)
            and statement.kind in STABLE_COMMANDS
            and statement.stable_id is None
        ):
            diagnostics.append(Diagnostic(
                f"{statement.kind.value} 명령은 안정 ID가 필요합니다.",
                statement.span or UNKNOWN_SPAN,
                statement.kind.value,
            ))
    if diagnostics:
        raise CvesCompilationError(tuple(diagnostics))

    events: list[dict] = []
    for event_index, event in enumerate(program.events):
        builder = _EventBuilder(script_id, event_index)
        pages: list[dict] = []
        for page_index, page in enumerate(event.pages):
            entry = len(builder.instructions)
            builder.lower_block(page.block, f"p{page_index}")
            builder.emit_generated("page_end", f"p{page_index}/end", page.span)
            pages.append({
                "index": page_index,
                "condition": _expression(page.condition) if page.condition is not None else None,
                "entry": entry,
            })
        builder.finalize()
        if builder.diagnostics:
            raise CvesCompilationError(tuple(builder.diagnostics))
        events.append({
            "index": event_index,
            "trigger": _trigger(event.trigger),
            "pages": pages,
            "instructions": builder.instructions,
            "source_map": builder.source_map,
        })
    canonical_source = format_program(program).encode("utf-8")
    return {
        "schema_version": IR_VERSION,
        "script_id": script_id,
        "source_digest": hashlib.sha256(canonical_source).hexdigest(),
        "events": events,
    }


@dataclass(slots=True)
class _EventBuilder:
    script_id: str
    event_index: int
    instructions: list[dict] = field(init=False, default_factory=list)
    source_map: list[dict] = field(init=False, default_factory=list)
    diagnostics: list[Diagnostic] = field(init=False, default_factory=list)

    def lower_block(self, block: ast.Block, path: str) -> None:
        for index, statement in enumerate(block.statements):
            self.lower_statement(statement, f"{path}/s{index}")

    def lower_statement(self, statement: ast.Statement, path: str) -> None:
        if isinstance(statement, ast.SayStatement):
            self.emit("say", statement, path, speaker=statement.speaker, text=_text(statement.text))
        elif isinstance(statement, ast.NarrateStatement):
            self.emit("narrate", statement, path, text=_text(statement.text))
        elif isinstance(statement, ast.LetStatement):
            self.emit("let", statement, path, name=statement.name, value=_expression(statement.value))
        elif isinstance(statement, ast.IfStatement):
            branch = self.emit("branch", statement, path, condition=_expression(statement.condition))
            then_entry = len(self.instructions)
            self.lower_block(statement.then_block, f"{path}/then")
            then_jump = self.emit_generated("jump", f"{path}/then_end", statement.then_block.span)
            else_entry = len(self.instructions)
            if statement.else_block is not None:
                self.lower_block(statement.else_block, f"{path}/else")
            join = len(self.instructions)
            self.instructions[branch]["then"] = then_entry
            self.instructions[branch]["else"] = else_entry if statement.else_block is not None else join
            self.instructions[then_jump]["target"] = join
        elif isinstance(statement, ast.ChoiceStatement):
            choice = self.emit(
                "choice", statement, path,
                prompt=_text(statement.prompt), result=statement.result,
                options=[], **{"await": True},
            )
            exits: list[int] = []
            options: list[dict] = []
            for option_index, option in enumerate(statement.options):
                target = len(self.instructions)
                self.lower_block(option.block, f"{path}/option{option_index}")
                exits.append(self.emit_generated("jump", f"{path}/option{option_index}_end", option.block.span))
                options.append({"text": _text(option.text), "target": target})
            join = len(self.instructions)
            self.instructions[choice]["options"] = options
            for exit_address in exits:
                self.instructions[exit_address]["target"] = join
        elif isinstance(statement, ast.RepeatStatement):
            begin = self.emit("repeat_begin", statement, path, count=_expression(statement.count))
            body = len(self.instructions)
            self.lower_block(statement.block, f"{path}/body")
            self.emit_generated("repeat_next", f"{path}/repeat_next", statement.block.span, target=begin)
            self.instructions[begin]["body"] = body
            self.instructions[begin]["exit"] = len(self.instructions)
        elif isinstance(statement, ast.CommandStatement):
            self.lower_command(statement, path)

    def lower_command(self, statement: ast.CommandStatement, path: str) -> None:
        arguments = [_argument(value) for value in statement.arguments]
        properties = [{"name": value.name, "value": _expression(value.value)} for value in statement.properties]
        symbol = _command_symbol(statement)
        if statement.kind is ast.CommandKind.LABEL:
            self.emit("label", statement, path, label=symbol)
        elif statement.kind in {ast.CommandKind.JUMP, ast.CommandKind.CALL}:
            self.emit(statement.kind.value, statement, path, label=symbol)
        elif statement.kind is ast.CommandKind.RETURN:
            self.emit("return", statement, path)
        else:
            fields = {
                "command": statement.kind.value,
                "arguments": arguments,
                "properties": properties,
                "await": statement.awaited or statement.kind in IMPLICIT_AWAIT_COMMANDS,
                "await_explicit": statement.awaited,
                "result": statement.result,
            }
            if statement.kind in PERSISTENT_COMMANDS:
                fields["operation_id"] = f"{self.script_id}/{statement.stable_id}"
            self.emit("command", statement, path, **fields)

    def emit(self, operation: str, statement: ast.Statement, path: str, **fields) -> int:
        instruction_id = statement.stable_id or f"e{self.event_index}/{path}"
        return self._emit(operation, instruction_id, statement.stable_id, statement.span, fields)

    def emit_generated(self, operation: str, path: str, span: SourceSpan | None, **fields) -> int:
        return self._emit(operation, f"e{self.event_index}/{path}", None, span, fields)

    def _emit(
        self,
        operation: str,
        instruction_id: str,
        stable_id: str | None,
        span: SourceSpan | None,
        fields: dict,
    ) -> int:
        address = len(self.instructions)
        self.instructions.append({
            "address": address,
            "instruction_id": instruction_id,
            "op": operation,
            **fields,
        })
        self.source_map.append({
            "address": address,
            "instruction_id": instruction_id,
            "stable_id": stable_id,
            "span": _span(span),
        })
        return address

    def finalize(self) -> None:
        labels: dict[str, int] = {}
        for instruction in self.instructions:
            if instruction["op"] != "label":
                continue
            label = instruction.get("label")
            if not label:
                self.diagnostics.append(Diagnostic("label 명령에는 라벨 이름이 필요합니다.", self._instruction_span(instruction)))
            elif label in labels:
                self.diagnostics.append(Diagnostic(f"중복 라벨입니다: {label}", self._instruction_span(instruction), label))
            else:
                labels[label] = instruction["address"]

        for index, instruction in enumerate(self.instructions):
            operation = instruction["op"]
            if operation in {"say", "narrate", "let", "label"}:
                instruction["next"] = index + 1
                if operation in {"say", "narrate"}:
                    instruction["await"] = True
                    instruction["resume"] = instruction["next"]
            elif operation == "command":
                command = instruction["command"]
                instruction["next"] = None if command == ast.CommandKind.STOP.value else index + 1
                if instruction["await"]:
                    instruction["resume"] = instruction["next"]
            elif operation in {"jump", "call"}:
                if operation == "jump" and "target" in instruction:
                    continue
                label = instruction.get("label")
                if label not in labels:
                    self.diagnostics.append(Diagnostic(
                        f"존재하지 않는 라벨입니다: {label}", self._instruction_span(instruction), label,
                    ))
                else:
                    instruction["target"] = labels[label]
                    if operation == "call":
                        instruction["return_address"] = index + 1

    def _instruction_span(self, instruction: dict) -> SourceSpan:
        return next(
            (entry["_span"] for entry in self._source_entries() if entry["address"] == instruction["address"]),
            UNKNOWN_SPAN,
        )

    def _source_entries(self):
        for entry in self.source_map:
            span_data = entry["span"]
            if span_data is None:
                yield {**entry, "_span": UNKNOWN_SPAN}
            else:
                yield {
                    **entry,
                    "_span": SourceSpan(
                        span_data["source"],
                        SourcePosition(**span_data["start"]),
                        SourcePosition(**span_data["end"]),
                    ),
                }


def _walk_program(program: ast.Program) -> tuple[ast.Statement, ...]:
    result: list[ast.Statement] = []
    blocks = [page.block for event in program.events for page in event.pages]
    while blocks:
        block = blocks.pop(0)
        for statement in block.statements:
            result.append(statement)
            if isinstance(statement, ast.IfStatement):
                blocks.append(statement.then_block)
                if statement.else_block is not None:
                    blocks.append(statement.else_block)
            elif isinstance(statement, ast.ChoiceStatement):
                blocks.extend(option.block for option in statement.options)
            elif isinstance(statement, ast.RepeatStatement):
                blocks.append(statement.block)
    return tuple(result)


def _command_symbol(statement: ast.CommandStatement) -> str | None:
    positional = [value for value in statement.arguments if value.name is None and value.value is not None]
    if not positional:
        return None
    value = positional[0].value
    if isinstance(value, ast.NameExpression):
        return value.name
    if isinstance(value, ast.LiteralExpression) and value.value_type is ast.ValueType.STRING:
        return str(value.value)
    return None


def _trigger(trigger: ast.Trigger) -> dict:
    return {"name": trigger.name, "arguments": [_argument(value) for value in trigger.arguments]}


def _argument(argument: ast.Argument) -> dict:
    return {"name": argument.name, "value": _expression(argument.value) if argument.value is not None else None}


def _text(text: ast.Text) -> dict:
    if isinstance(text, ast.TextLiteral):
        return {"kind": "literal", "value": text.value}
    return {
        "kind": "localized",
        "entries": [
            {"language": entry.language, "value": entry.value}
            for entry in text.entries
        ],
    }


def _expression(expression: ast.Expression) -> dict:
    if isinstance(expression, ast.LiteralExpression):
        return {"kind": "literal", "type": expression.value_type.value, "value": expression.value}
    if isinstance(expression, ast.NameExpression):
        return {"kind": "name", "name": expression.name}
    if isinstance(expression, ast.MemberExpression):
        return {"kind": "member", "target": _expression(expression.target), "member": expression.member}
    if isinstance(expression, ast.CallExpression):
        return {"kind": "call", "callee": _expression(expression.callee), "arguments": [_argument(value) for value in expression.arguments]}
    if isinstance(expression, ast.UnaryExpression):
        return {"kind": "unary", "operator": expression.operator, "operand": _expression(expression.operand)}
    if isinstance(expression, ast.BinaryExpression):
        return {
            "kind": "binary", "operator": expression.operator,
            "left": _expression(expression.left), "right": _expression(expression.right),
        }
    raise TypeError(f"지원하지 않는 expression입니다: {type(expression).__name__}")


def _span(span: SourceSpan | None) -> dict | None:
    if span is None:
        return None
    return {
        "source": span.source,
        "start": {"offset": span.start.offset, "line": span.start.line, "column": span.start.column},
        "end": {"offset": span.end.offset, "line": span.end.line, "column": span.end.column},
    }

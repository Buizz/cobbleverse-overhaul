"""Flow-sensitive type and template validation for the CVES tree AST."""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

from . import ast
from .catalog import ResourceCatalog, ResourceKind
from .diagnostics import Diagnostic, SourcePosition, SourceSpan
from .parser import AWAIT_COMMANDS
from .template import TemplateParseError, TemplateReference, parse_template


RESOURCE_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
STABLE_ID = re.compile(r"^[a-z0-9][a-z0-9_.-]*(?:/[a-z0-9][a-z0-9_.-]*)*$")
NUMBER_TYPES = {ast.ValueType.INT, ast.ValueType.DECIMAL}
UNKNOWN_POSITION = SourcePosition(0, 1, 1)
UNKNOWN_SPAN = SourceSpan("<ast>", UNKNOWN_POSITION, UNKNOWN_POSITION)


class _InternalType(Enum):
    PLAYER = "player"
    LOCALIZED_NAME = "localized_name"
    ERROR = "error"


Type = ast.ValueType | _InternalType


@dataclass(frozen=True, slots=True)
class Parameter:
    name: str
    types: frozenset[ast.ValueType] = frozenset()
    optional: bool = False
    allowed_names: frozenset[str] = frozenset()
    resource_kind: ResourceKind | None = None


@dataclass(frozen=True, slots=True)
class CommandContract:
    positional: tuple[Parameter, ...] = ()
    named: tuple[Parameter, ...] = ()
    flags: frozenset[str] = frozenset()
    properties: tuple[Parameter, ...] = ()
    result: ast.ValueType | None = None


RESOURCE = frozenset({ast.ValueType.RESOURCE_ID})
STRING = frozenset({ast.ValueType.STRING})
BOOL = frozenset({ast.ValueType.BOOL})
INT = frozenset({ast.ValueType.INT})
NUMBER = frozenset(NUMBER_TYPES)
LOCATION = frozenset({ast.ValueType.LOCATION_REF, ast.ValueType.POSITION})


def _p(name: str, types: frozenset[ast.ValueType] = frozenset(), *, optional: bool = False,
       names: frozenset[str] = frozenset(), resource: ResourceKind | None = None) -> Parameter:
    return Parameter(name, types, optional, names, resource)


MOVE_PROPERTIES = (
    _p("mode", names=frozenset({"walk", "offset_teleport"}), optional=True),
    _p("speed", NUMBER, optional=True),
    _p("lock_input", BOOL, optional=True),
    _p("collision", names=frozenset({"stop", "ignore"}), optional=True),
    _p("safe_landing", names=frozenset({"required", "preferred", "disabled"}), optional=True),
    _p("preload_chunks", BOOL, optional=True),
)

TELEPORT_PROPERTIES = (
    _p("anchor", STRING, optional=True),
    _p("fade", names=frozenset({"black", "white", "none"}), optional=True),
    _p("safe_landing", names=frozenset({"required", "preferred", "disabled"}), optional=True),
    _p("preload_chunks", BOOL, optional=True),
)


COMMANDS: dict[ast.CommandKind, CommandContract] = {
    ast.CommandKind.STOP: CommandContract(),
    ast.CommandKind.SHOW_CHOICES: CommandContract((_p("prompt", STRING),)),
    ast.CommandKind.SET_FLAG: CommandContract((_p("key", RESOURCE, resource=ResourceKind.FLAG), _p("value", BOOL))),
    ast.CommandKind.SET_VARIABLE: CommandContract((_p("name", STRING), _p("value"))),
    ast.CommandKind.SET_PLAYER_VARIABLE: CommandContract((_p("key", RESOURCE, resource=ResourceKind.VARIABLE), _p("value"))),
    ast.CommandKind.UNLOCK_FEATURE: CommandContract((_p("feature", RESOURCE, resource=ResourceKind.FEATURE),)),
    ast.CommandKind.SET_LEVEL_CAP: CommandContract((_p("level", INT),)),
    ast.CommandKind.GIVE_ITEM: CommandContract(
        (_p("item", RESOURCE, resource=ResourceKind.ITEM),), (_p("count", INT, optional=True),), frozenset({"notify"}),
        result=ast.ValueType.ITEM_RESULT,
    ),
    ast.CommandKind.GIVE_LOOT: CommandContract(
        (_p("loot", RESOURCE, resource=ResourceKind.LOOT),), (_p("count", INT, optional=True),), frozenset({"notify"}),
        result=ast.ValueType.ITEM_RESULT,
    ),
    ast.CommandKind.GIVE_MONEY: CommandContract(
        (_p("amount", INT),), flags=frozenset({"notify"}), result=ast.ValueType.BOOL,
    ),
    ast.CommandKind.TAKE_MONEY: CommandContract(
        (_p("amount", INT),), flags=frozenset({"allow_debt"}), result=ast.ValueType.BOOL,
    ),
    ast.CommandKind.GRANT_BADGE: CommandContract((_p("badge", RESOURCE, resource=ResourceKind.BADGE),), result=ast.ValueType.BOOL),
    ast.CommandKind.GRANT_FIELD_MOVE: CommandContract(
        (_p("move", names=frozenset({"surf", "fly", "flash", "defog", "rock_climb", "whirlpool", "strength", "rock_smash"})),),
        result=ast.ValueType.BOOL,
    ),
    ast.CommandKind.BATTLE: CommandContract((_p("battle", RESOURCE, resource=ResourceKind.BATTLE),), result=ast.ValueType.BATTLE_RESULT),
    ast.CommandKind.STARTER_ROULETTE: CommandContract(result=ast.ValueType.POKEMON_SELECTION),
    ast.CommandKind.MAP_SELECTION: CommandContract(result=ast.ValueType.LOCATION_REF),
    ast.CommandKind.MOVE: CommandContract(
        (_p("subject", names=frozenset({"player", "npc"})), _p("destination", LOCATION)),
        properties=MOVE_PROPERTIES, result=ast.ValueType.MOVEMENT_RESULT,
    ),
    ast.CommandKind.TELEPORT: CommandContract(
        (_p("subject", names=frozenset({"player", "npc"})), _p("destination", LOCATION)),
        properties=TELEPORT_PROPERTIES, result=ast.ValueType.MOVEMENT_RESULT,
    ),
    ast.CommandKind.ENTER_SPACE: CommandContract(
        (_p("subject", names=frozenset({"player", "npc"})), _p("destination", LOCATION)),
        properties=TELEPORT_PROPERTIES, result=ast.ValueType.MOVEMENT_RESULT,
    ),
    ast.CommandKind.FACE: CommandContract(
        (_p("subject", names=frozenset({"player", "npc"})),
         _p("direction", names=frozenset({"north", "south", "east", "west", "player", "npc"}))),
    ),
    ast.CommandKind.FADE: CommandContract((_p("color", names=frozenset({"black", "white"})),), result=ast.ValueType.BOOL),
    ast.CommandKind.WAIT: CommandContract((_p("duration", NUMBER),), result=ast.ValueType.BOOL),
    ast.CommandKind.SOUND: CommandContract((_p("sound", RESOURCE, resource=ResourceKind.SOUND),), result=ast.ValueType.BOOL),
    ast.CommandKind.EFFECT: CommandContract((_p("effect", RESOURCE, resource=ResourceKind.EFFECT),), result=ast.ValueType.BOOL),
    ast.CommandKind.LABEL: CommandContract((_p("label"),)),
    ast.CommandKind.JUMP: CommandContract((_p("label"),)),
    ast.CommandKind.CALL: CommandContract((_p("routine"),)),
    ast.CommandKind.RETURN: CommandContract(),
}


RESULT_FIELDS: dict[ast.ValueType, dict[str, Type]] = {
    ast.ValueType.LOCATION_REF: {
        "name": _InternalType.LOCALIZED_NAME,
    },
    ast.ValueType.POKEMON_SELECTION: {
        "species_id": ast.ValueType.RESOURCE_ID, "form": ast.ValueType.STRING,
        "level": ast.ValueType.INT, "name": _InternalType.LOCALIZED_NAME,
    },
    ast.ValueType.BATTLE_RESULT: {
        "outcome": ast.ValueType.STRING, "opponent": ast.ValueType.RESOURCE_ID,
    },
    ast.ValueType.ITEM_RESULT: {
        "requested_count": ast.ValueType.INT, "granted_count": ast.ValueType.INT,
        "remaining_count": ast.ValueType.INT, "failure_reason": ast.ValueType.STRING,
    },
    ast.ValueType.MOVEMENT_RESULT: {
        "arrived": ast.ValueType.BOOL, "failure_reason": ast.ValueType.STRING,
        "destination": ast.ValueType.LOCATION_REF,
    },
}


class Scope:
    def __init__(self, parent: "Scope | None" = None) -> None:
        self.parent = parent
        self.values: dict[str, Type] = {}

    def lookup(self, name: str) -> Type | None:
        if name in self.values:
            return self.values[name]
        return self.parent.lookup(name) if self.parent else None

    def define(self, name: str, value_type: Type) -> bool:
        if self.lookup(name) is not None:
            return False
        self.values[name] = value_type
        return True


class SemanticValidator:
    def __init__(self, catalog: ResourceCatalog | None = None) -> None:
        self.catalog = catalog
        self.diagnostics: list[Diagnostic] = []

    def validate(self, program: ast.Program) -> tuple[Diagnostic, ...]:
        self.diagnostics.clear()
        if not program.events:
            self._issue(program.span, "program에는 event가 하나 이상 필요합니다.")
        stable_ids: dict[str, ast.Statement] = {}
        for event in program.events:
            for statement in self._walk_statements(page.block for page in event.pages):
                if statement.stable_id is None:
                    continue
                if not STABLE_ID.fullmatch(statement.stable_id):
                    self._issue(statement.span, f"올바르지 않은 안정 ID입니다: {statement.stable_id!r}", statement.stable_id)
                elif statement.stable_id in stable_ids:
                    self._issue(statement.span, f"중복 안정 ID입니다: {statement.stable_id}", statement.stable_id)
                else:
                    stable_ids[statement.stable_id] = statement
            self._trigger(event.trigger)
            if not event.pages:
                self._issue(event.span, "event에는 page가 하나 이상 필요합니다.")
            default_indexes = [index for index, page in enumerate(event.pages) if page.condition is None]
            if len(default_indexes) > 1:
                self._issue(event.span, "event에는 default page를 하나만 둘 수 있습니다.")
            if default_indexes and default_indexes[0] != len(event.pages) - 1:
                self._issue(event.pages[default_indexes[0]].span, "default page는 event의 마지막 page여야 합니다.")
            for page in event.pages:
                scope = Scope()
                scope.values["player"] = _InternalType.PLAYER
                if page.condition is not None:
                    self._require_type(page.condition, scope, BOOL, "page 조건은 bool이어야 합니다.")
                self._block(page.block, scope)
        return tuple(self.diagnostics)

    def _trigger(self, trigger: ast.Trigger) -> None:
        ranges = {"interact", "proximity_enter", "proximity_exit"}
        required_targets = {
            "region_enter", "region_exit", "anchor_step",
            "building_enter", "building_exit", "dimension_enter", "dimension_exit",
            "flag_changed", "item_used", "battle_finished",
        }
        known = ranges | {
            "region_enter", "region_exit", "anchor_step", "building_enter", "building_exit",
            "dimension_enter", "dimension_exit", "flag_changed", "item_used", "battle_finished",
        }
        target_triggers = known - ranges
        if trigger.name not in known:
            self._issue(trigger.span, f"지원하지 않는 이벤트 트리거 {trigger.name!r}입니다.", trigger.name)
            return
        seen: set[str] = set()
        scope = Scope()
        for argument in trigger.arguments:
            if argument.name is None:
                self._issue(argument.span, "트리거 인자는 name: value 형식이어야 합니다.")
                continue
            if argument.name in seen:
                self._issue(argument.span, f"중복 트리거 인자 {argument.name!r}입니다.", argument.name)
            seen.add(argument.name)
            if argument.name == "range" and argument.value is not None:
                if trigger.name not in ranges:
                    self._issue(argument.span, f"{trigger.name} 트리거는 range 인수를 지원하지 않습니다.", "range")
                else:
                    self._require_type(argument.value, scope, NUMBER, "range는 숫자여야 합니다.")
                    if self._numeric_literal(argument.value) is not None and self._numeric_literal(argument.value) <= 0:
                        self._issue(argument.span, "range는 0보다 커야 합니다.", "range")
            elif argument.name == "once" and argument.value is not None:
                self._require_type(argument.value, scope, BOOL, "once는 bool이어야 합니다.")
            elif argument.name == "cooldown" and argument.value is not None:
                self._require_type(argument.value, scope, NUMBER, "cooldown은 숫자여야 합니다.")
                if self._numeric_literal(argument.value) is not None and self._numeric_literal(argument.value) < 0:
                    self._issue(argument.span, "cooldown은 0 이상이어야 합니다.", "cooldown")
            elif argument.name == "scope" and argument.value is not None:
                self._parameter(
                    argument.value,
                    _p("scope", names=frozenset({"player", "world", "party", "instance"})),
                    scope,
                )
            elif argument.name == "target" and argument.value is not None:
                if trigger.name not in target_triggers:
                    self._issue(argument.span, f"{trigger.name} 트리거는 target 인수를 지원하지 않습니다.", "target")
                else:
                    self._require_type(argument.value, scope, RESOURCE, "target은 resource_id여야 합니다.")
                    if trigger.name in {"region_enter", "region_exit"}:
                        self._catalog_resource(argument.value, ResourceKind.EVENT_REGION)
                    elif trigger.name == "anchor_step":
                        self._catalog_resource(argument.value, ResourceKind.EVENT_ANCHOR)
                    elif trigger.name in {"building_enter", "building_exit"}:
                        self._catalog_resource(argument.value, ResourceKind.BUILDING)
                    elif trigger.name in {"dimension_enter", "dimension_exit"}:
                        self._catalog_resource(argument.value, ResourceKind.DIMENSION)
                    elif trigger.name == "flag_changed":
                        self._catalog_resource(argument.value, ResourceKind.FLAG)
                    elif trigger.name == "item_used":
                        self._catalog_resource(argument.value, ResourceKind.ITEM)
                    elif trigger.name == "battle_finished":
                        self._catalog_resource(argument.value, ResourceKind.BATTLE)
            elif argument.name not in {"range", "once", "cooldown", "scope", "target"}:
                self._issue(argument.span, f"지원하지 않는 트리거 인자 {argument.name!r}입니다.", argument.name)
        if trigger.name in required_targets and "target" not in seen:
            self._issue(trigger.span, f"{trigger.name} 트리거에는 target 인수가 필요합니다.", trigger.name)

    def _block(self, block: ast.Block, scope: Scope) -> None:
        for statement in block.statements:
            if isinstance(statement, ast.SayStatement):
                if statement.speaker not in {"npc", "player", "system"}:
                    self._issue(statement.span, f"지원하지 않는 화자 {statement.speaker!r}입니다.", statement.speaker)
                self._text(statement.text, scope)
            elif isinstance(statement, ast.NarrateStatement):
                self._text(statement.text, scope)
            elif isinstance(statement, ast.LetStatement):
                value_type = self._expression(statement.value, scope)
                self._define(scope, statement.name, value_type, statement.span)
            elif isinstance(statement, ast.IfStatement):
                self._require_type(statement.condition, scope, BOOL, "if 조건은 bool이어야 합니다.")
                self._block(statement.then_block, Scope(scope))
                if statement.else_block is not None:
                    self._block(statement.else_block, Scope(scope))
            elif isinstance(statement, ast.ChoiceStatement):
                self._text(statement.prompt, scope)
                if not statement.options:
                    self._issue(statement.span, "choice에는 선택지가 하나 이상 필요합니다.")
                for option in statement.options:
                    self._text(option.text, scope)
                    self._block(option.block, Scope(scope))
                if statement.result is not None:
                    self._define(scope, statement.result, ast.ValueType.INT, statement.span)
            elif isinstance(statement, ast.RepeatStatement):
                self._require_type(statement.count, scope, INT, "repeat 횟수는 int여야 합니다.")
                self._block(statement.block, Scope(scope))
            elif isinstance(statement, ast.CommandStatement):
                self._command(statement, scope)

    def _walk_statements(self, blocks) -> tuple[ast.Statement, ...]:
        result: list[ast.Statement] = []
        pending = list(blocks)
        while pending:
            block = pending.pop(0)
            for statement in block.statements:
                result.append(statement)
                if isinstance(statement, ast.IfStatement):
                    pending.append(statement.then_block)
                    if statement.else_block is not None:
                        pending.append(statement.else_block)
                elif isinstance(statement, ast.ChoiceStatement):
                    pending.extend(option.block for option in statement.options)
                elif isinstance(statement, ast.RepeatStatement):
                    pending.append(statement.block)
        return tuple(result)

    def _command(self, statement: ast.CommandStatement, scope: Scope) -> None:
        contract = COMMANDS[statement.kind]
        requires_await = statement.kind in AWAIT_COMMANDS
        if requires_await and not statement.awaited:
            self._issue(statement.span, f"비동기 명령 {statement.kind.value!r}에는 await가 필요합니다.", statement.kind.value)
        elif statement.awaited and not requires_await:
            self._issue(statement.span, f"명령 {statement.kind.value!r}에는 await를 사용할 수 없습니다.", statement.kind.value)
        positional = [argument for argument in statement.arguments if argument.name is None]
        named = [argument for argument in statement.arguments if argument.name is not None]
        required = len([value for value in contract.positional if not value.optional])
        if not required <= len(positional) <= len(contract.positional):
            self._issue(
                statement.span,
                f"{statement.kind.value} 위치 인자는 {required}~{len(contract.positional)}개여야 하지만 {len(positional)}개입니다.",
                statement.kind.value,
            )
        for argument, parameter in zip(positional, contract.positional):
            if argument.value is not None:
                self._parameter(argument.value, parameter, scope)

        named_contracts = {value.name: value for value in contract.named}
        seen: set[str] = set()
        for argument in named:
            assert argument.name is not None
            if argument.name in seen:
                self._issue(argument.span, f"중복 명령 인자 {argument.name!r}입니다.", argument.name)
            seen.add(argument.name)
            if argument.name in contract.flags:
                if argument.value is not None:
                    self._issue(argument.span, f"플래그 인자 {argument.name!r}에는 값을 지정할 수 없습니다.", argument.name)
            elif argument.name in named_contracts:
                if argument.value is None:
                    self._issue(argument.span, f"명령 인자 {argument.name!r}에는 값이 필요합니다.", argument.name)
                else:
                    self._parameter(argument.value, named_contracts[argument.name], scope)
            else:
                self._issue(argument.span, f"{statement.kind.value}에서 지원하지 않는 인자 {argument.name!r}입니다.", argument.name)

        property_contracts = {value.name: value for value in contract.properties}
        property_names: set[str] = set()
        for prop in statement.properties:
            if prop.name in property_names:
                self._issue(prop.span, f"중복 명령 속성 {prop.name!r}입니다.", prop.name)
            property_names.add(prop.name)
            parameter = property_contracts.get(prop.name)
            if parameter is None:
                self._issue(prop.span, f"{statement.kind.value}에서 지원하지 않는 속성 {prop.name!r}입니다.", prop.name)
            else:
                self._parameter(prop.value, parameter, scope)

        self._destination_anchor(statement)
        self._move_destination(statement)
        self._enter_space_destination(statement)

        if statement.result is not None:
            if contract.result is None:
                self._issue(statement.span, f"{statement.kind.value} 명령은 결과 변수를 만들지 않습니다.", statement.result)
            else:
                self._define(scope, statement.result, contract.result, statement.span)

    def _parameter(self, expression: ast.Expression, parameter: Parameter, scope: Scope) -> None:
        if parameter.allowed_names:
            if isinstance(expression, ast.NameExpression) and expression.name in parameter.allowed_names:
                return
            self._issue(expression.span, f"{parameter.name}은 {', '.join(sorted(parameter.allowed_names))} 중 하나여야 합니다.", self._expression_token(expression))
            return
        if not parameter.types:
            self._expression(expression, scope, allow_unbound_name=True)
            return
        self._require_type(expression, scope, parameter.types, f"{parameter.name} 타입은 {self._types(parameter.types)}이어야 합니다.")
        if parameter.resource_kind is not None:
            self._catalog_resource(expression, parameter.resource_kind)

    def _expression(self, expression: ast.Expression, scope: Scope, *, allow_unbound_name: bool = False) -> Type:
        if isinstance(expression, ast.LiteralExpression):
            return expression.value_type
        if isinstance(expression, ast.NameExpression):
            value_type = scope.lookup(expression.name)
            if value_type is None:
                if allow_unbound_name:
                    return ast.ValueType.STRING
                self._issue(expression.span, f"정의되지 않은 변수 {expression.name!r}입니다.", expression.name)
                return _InternalType.ERROR
            return value_type
        if isinstance(expression, ast.MemberExpression):
            target_type = self._expression(expression.target, scope)
            if target_type is _InternalType.PLAYER:
                if expression.member == "name": return ast.ValueType.STRING
                self._issue(expression.span, f"player에 {expression.member!r} 필드가 없습니다.", expression.member)
                return _InternalType.ERROR
            fields = RESULT_FIELDS.get(target_type, {}) if isinstance(target_type, ast.ValueType) else {}
            if expression.member not in fields:
                self._issue(expression.span, f"{self._type_name(target_type)}에 {expression.member!r} 필드가 없습니다.", expression.member)
                return _InternalType.ERROR
            return fields[expression.member]
        if isinstance(expression, ast.CallExpression):
            return self._call(expression, scope)
        if isinstance(expression, ast.UnaryExpression):
            expected = BOOL if expression.operator == "!" else NUMBER
            result = self._require_type(expression.operand, scope, expected, f"단항 {expression.operator}의 피연산자 타입이 올바르지 않습니다.")
            return ast.ValueType.BOOL if expression.operator == "!" else result
        if isinstance(expression, ast.BinaryExpression):
            left = self._expression(expression.left, scope)
            right = self._expression(expression.right, scope)
            if expression.operator in {"&&", "||"}:
                self._expect_inferred(expression.left, left, BOOL, "논리 연산자는 bool 피연산자가 필요합니다.")
                self._expect_inferred(expression.right, right, BOOL, "논리 연산자는 bool 피연산자가 필요합니다.")
                return ast.ValueType.BOOL
            if expression.operator in {"+", "-", "*", "/", "%", "<", "<=", ">", ">="}:
                self._expect_inferred(expression.left, left, NUMBER, "산술·비교 연산자는 숫자 피연산자가 필요합니다.")
                self._expect_inferred(expression.right, right, NUMBER, "산술·비교 연산자는 숫자 피연산자가 필요합니다.")
                if expression.operator in {"<", "<=", ">", ">="}: return ast.ValueType.BOOL
                return ast.ValueType.DECIMAL if ast.ValueType.DECIMAL in {left, right} or expression.operator == "/" else ast.ValueType.INT
            if expression.operator in {"==", "!="}:
                if left is not _InternalType.ERROR and right is not _InternalType.ERROR and left != right and not ({left, right} <= NUMBER_TYPES):
                    self._issue(expression.span, "동등 비교의 두 피연산자 타입이 다릅니다.", expression.operator)
                return ast.ValueType.BOOL
        return _InternalType.ERROR

    def _call(self, expression: ast.CallExpression, scope: Scope) -> Type:
        if not isinstance(expression.callee, ast.NameExpression):
            self._issue(expression.span, "호출 대상은 내장 함수 이름이어야 합니다.")
            return _InternalType.ERROR
        name = expression.callee.name
        simple: dict[str, tuple[tuple[frozenset[ast.ValueType], ...], ast.ValueType]] = {
            "flag": ((RESOURCE,), ast.ValueType.BOOL),
            "has_item": ((RESOURCE, INT), ast.ValueType.BOOL),
            "money": ((), ast.ValueType.INT),
            "level_cap": ((), ast.ValueType.INT),
            "anchor": ((RESOURCE,), ast.ValueType.LOCATION_REF),
            "settlement": ((RESOURCE,), ast.ValueType.LOCATION_REF),
            "route": ((RESOURCE,), ast.ValueType.LOCATION_REF),
            "dimension": ((RESOURCE,), ast.ValueType.LOCATION_REF),
            "space": ((RESOURCE,), ast.ValueType.LOCATION_REF),
        }
        if name == "relative":
            return self._coordinate_call(expression, scope, "relative", {"x", "y", "z"}, set(), ast.ValueType.LOCATION_REF)
        if name == "position":
            return self._coordinate_call(
                expression, scope, "position", {"dimension", "x", "y", "z"}, {"yaw", "pitch"}, ast.ValueType.POSITION
            )
        signature = simple.get(name)
        if signature is None:
            self._issue(expression.span, f"지원하지 않는 내장 함수 {name!r}입니다.", name)
            return _InternalType.ERROR
        parameters, result = signature
        if len(expression.arguments) != len(parameters):
            self._issue(expression.span, f"{name} 함수 인자는 {len(parameters)}개여야 합니다.", name)
        for argument, expected in zip(expression.arguments, parameters):
            if argument.name is not None:
                self._issue(argument.span, f"{name} 함수는 이름 있는 인자를 사용하지 않습니다.", argument.name)
            if argument.value is not None:
                self._require_type(argument.value, scope, expected, f"{name} 함수 인자 타입은 {self._types(expected)}이어야 합니다.")
        resource_calls = {
            "flag": ResourceKind.FLAG,
            "has_item": ResourceKind.ITEM,
            "anchor": ResourceKind.EVENT_ANCHOR,
            "settlement": ResourceKind.SETTLEMENT,
            "route": ResourceKind.ROUTE,
            "dimension": ResourceKind.DIMENSION,
            "space": ResourceKind.SPACE,
        }
        resource_kind = resource_calls.get(name)
        if resource_kind is not None and expression.arguments and expression.arguments[0].value is not None:
            self._catalog_resource(expression.arguments[0].value, resource_kind)
        return result

    def _coordinate_call(
        self,
        expression: ast.CallExpression,
        scope: Scope,
        name: str,
        required: set[str],
        optional: set[str],
        result: ast.ValueType,
    ) -> Type:
        allowed = {value: NUMBER for value in required | optional}
        if "dimension" in allowed:
            allowed["dimension"] = RESOURCE
        seen: set[str] = set()
        for argument in expression.arguments:
            if argument.name is None or argument.name not in allowed:
                self._issue(argument.span, f"{name} 인자는 {', '.join(sorted(allowed))} 이름을 사용해야 합니다.")
                continue
            if argument.name in seen:
                self._issue(argument.span, f"{name}의 {argument.name!r} 인자가 중복되었습니다.", argument.name)
            seen.add(argument.name)
            if argument.value is not None:
                self._require_type(argument.value, scope, allowed[argument.name], f"{name}.{argument.name} 타입이 올바르지 않습니다.")
                if name == "position" and argument.name == "dimension":
                    self._catalog_resource(argument.value, ResourceKind.DIMENSION)
        missing = required - seen
        if missing:
            self._issue(expression.span, f"{name}에 필수 인자가 없습니다: {', '.join(sorted(missing))}")
        return result

    def _text(self, text: ast.Text, scope: Scope) -> None:
        if isinstance(text, ast.LocalizedText):
            if not text.entries:
                self._issue(text.span, "현지화 대사 블록은 비어 있을 수 없습니다.")
            languages: set[str] = set()
            for entry in text.entries:
                if entry.language in languages:
                    self._issue(entry.span, f"중복 언어 코드 {entry.language!r}입니다.", entry.language)
                languages.add(entry.language)
        entries = (
            ((None, text.value, text.span),)
            if isinstance(text, ast.TextLiteral)
            else tuple((entry.language, entry.value, entry.span) for entry in text.entries)
        )
        path_sets: list[tuple[str | None, frozenset[tuple[str, ...]]]] = []
        for language, value, entry_span in entries:
            try:
                references = parse_template(value)
            except TemplateParseError as error:
                self._issue(entry_span, error.message, error.token)
                continue
            path_sets.append((language, frozenset(reference.path for reference in references)))
            for reference in references:
                self._template_reference(reference, entry_span, scope)
        if len(path_sets) > 1:
            expected = path_sets[0][1]
            for language, paths in path_sets[1:]:
                if paths != expected:
                    self._issue(text.span, f"언어별 대사의 변수 경로가 다릅니다: {path_sets[0][0]} / {language}", language)

    def _template_reference(self, reference: TemplateReference, span: SourceSpan, scope: Scope) -> None:
        value_type = scope.lookup(reference.path[0])
        if value_type is None:
            self._issue(span, f"템플릿 변수가 정의되지 않았습니다: {reference.path[0]}", reference.source)
            return
        for member in reference.path[1:]:
            if value_type is _InternalType.PLAYER and member == "name":
                value_type = ast.ValueType.STRING
                continue
            fields = RESULT_FIELDS.get(value_type, {}) if isinstance(value_type, ast.ValueType) else {}
            if member not in fields:
                self._issue(span, f"템플릿 경로에 존재하지 않는 필드입니다: {'.'.join(reference.path)}", reference.source)
                return
            value_type = fields[member]
        for filter_value in reference.filters:
            if filter_value.name == "name":
                if value_type not in {ast.ValueType.RESOURCE_ID, _InternalType.LOCALIZED_NAME}:
                    self._issue(span, "name 필터는 resource_id 또는 현지화 name 값에만 사용할 수 있습니다.", reference.source)
                if filter_value.argument is not None:
                    self._issue(span, "name 필터는 인자를 받지 않습니다.", reference.source)
                value_type = ast.ValueType.STRING
            elif filter_value.name == "number":
                if value_type not in NUMBER_TYPES:
                    self._issue(span, "number 필터는 int 또는 decimal에만 사용할 수 있습니다.", reference.source)
                if filter_value.argument is not None:
                    self._issue(span, "number 필터는 인자를 받지 않습니다.", reference.source)
                value_type = ast.ValueType.STRING
            elif filter_value.name == "josa":
                if value_type not in {ast.ValueType.STRING, _InternalType.LOCALIZED_NAME}:
                    self._issue(span, "josa 필터는 string에만 사용할 수 있습니다.", reference.source)
                if filter_value.argument not in {"은/는", "이/가", "을/를", "과/와"}:
                    self._issue(span, "지원하는 조사는 은/는, 이/가, 을/를, 과/와입니다.", reference.source)
                value_type = ast.ValueType.STRING
            elif filter_value.name == "fallback":
                if filter_value.argument is None:
                    self._issue(span, "fallback 필터에는 기본 문자열이 필요합니다.", reference.source)
                value_type = ast.ValueType.STRING
            else:
                self._issue(span, f"지원하지 않는 템플릿 필터 {filter_value.name!r}입니다.", reference.source)

    def _require_type(self, expression: ast.Expression, scope: Scope, expected: frozenset[ast.ValueType], message: str) -> Type:
        inferred = self._expression(expression, scope)
        if ast.ValueType.RESOURCE_ID in expected and isinstance(expression, ast.LiteralExpression) and expression.value_type is ast.ValueType.STRING:
            if RESOURCE_ID.fullmatch(str(expression.value)):
                return ast.ValueType.RESOURCE_ID
            self._issue(expression.span, f"올바른 resource_id가 아닙니다: {expression.value!r}", str(expression.value))
            return _InternalType.ERROR
        self._expect_inferred(expression, inferred, expected, message)
        return inferred

    def _expect_inferred(self, expression: ast.Expression, inferred: Type, expected: frozenset[ast.ValueType], message: str) -> None:
        if inferred is not _InternalType.ERROR and inferred not in expected:
            self._issue(expression.span, f"{message} 실제 타입: {self._type_name(inferred)}", self._expression_token(expression))

    def _define(self, scope: Scope, name: str, value_type: Type, span: SourceSpan) -> None:
        if value_type is _InternalType.ERROR:
            return
        if not scope.define(name, value_type):
            self._issue(span, f"변수 {name!r}이 이미 정의되어 있습니다.", name)

    def _catalog_resource(self, expression: ast.Expression, kind: ResourceKind) -> None:
        if self.catalog is None or not isinstance(expression, ast.LiteralExpression):
            return
        if expression.value_type is not ast.ValueType.STRING:
            return
        resource_id = str(expression.value)
        if not RESOURCE_ID.fullmatch(resource_id):
            return
        if self.catalog.can_reject_missing(kind) and not self.catalog.contains(kind, resource_id):
            self._issue(expression.span, f"{kind.value} 카탈로그에 없는 리소스입니다: {resource_id}", resource_id)

    def _destination_anchor(self, statement: ast.CommandStatement) -> None:
        if statement.kind not in {
            ast.CommandKind.MOVE, ast.CommandKind.TELEPORT, ast.CommandKind.ENTER_SPACE,
        }:
            return
        positional = [value for value in statement.arguments if value.name is None]
        if len(positional) < 2 or positional[1].value is None:
            return
        destination = positional[1].value
        if not isinstance(destination, ast.CallExpression) or not isinstance(destination.callee, ast.NameExpression):
            return
        kind_by_call = {
            "anchor": ResourceKind.EVENT_ANCHOR,
            "settlement": ResourceKind.SETTLEMENT,
            "route": ResourceKind.ROUTE,
            "dimension": ResourceKind.DIMENSION,
            "space": ResourceKind.SPACE,
        }
        kind = kind_by_call.get(destination.callee.name)
        if kind is None or not destination.arguments or destination.arguments[0].value is None:
            return
        anchor_property = next((value for value in statement.properties if value.name == "anchor"), None)
        if kind is ResourceKind.EVENT_ANCHOR and anchor_property is not None:
            self._issue(
                anchor_property.span,
                "anchor(...) 목적지에는 하위 anchor 속성을 사용할 수 없습니다.",
                "anchor",
            )
            return
        if kind in {ResourceKind.ROUTE, ResourceKind.DIMENSION, ResourceKind.SPACE} and anchor_property is None:
            self._issue(
                destination.span,
                f"{destination.callee.name} 목적지에는 anchor 속성이 필요합니다.",
                destination.callee.name,
            )
            return
        resource_expression = destination.arguments[0].value
        if not isinstance(resource_expression, ast.LiteralExpression) or resource_expression.value_type is not ast.ValueType.STRING:
            return
        if anchor_property is None or not isinstance(anchor_property.value, ast.LiteralExpression):
            return
        if self.catalog is None:
            return
        resource_id = str(resource_expression.value)
        anchor = str(anchor_property.value.value)
        if self.catalog.can_validate_anchors(kind, resource_id) and not self.catalog.contains_anchor(kind, resource_id, anchor):
            self._issue(anchor_property.span, f"{resource_id}에 없는 앵커입니다: {anchor}", anchor)

    def _enter_space_destination(self, statement: ast.CommandStatement) -> None:
        if statement.kind is not ast.CommandKind.ENTER_SPACE:
            return
        positional = [value for value in statement.arguments if value.name is None]
        if len(positional) < 2 or positional[1].value is None:
            return
        destination = positional[1].value
        if (
            isinstance(destination, ast.CallExpression)
            and isinstance(destination.callee, ast.NameExpression)
            and destination.callee.name == "space"
        ):
            return
        self._issue(
            destination.span,
            "enter_space destination은 space(...) 위치여야 합니다.",
            self._expression_token(destination),
        )

    def _move_destination(self, statement: ast.CommandStatement) -> None:
        if statement.kind is not ast.CommandKind.MOVE:
            return
        positional = [value for value in statement.arguments if value.name is None]
        if len(positional) < 2 or positional[1].value is None:
            return
        destination = positional[1].value
        if (
            isinstance(destination, ast.CallExpression)
            and isinstance(destination.callee, ast.NameExpression)
            and destination.callee.name == "relative"
        ):
            return
        self._issue(
            destination.span,
            "move destination은 relative(...) 위치여야 합니다.",
            self._expression_token(destination),
        )

    def _issue(self, span: SourceSpan | None, message: str, token: str | None = None) -> None:
        self.diagnostics.append(Diagnostic(message, span or UNKNOWN_SPAN, token))

    @staticmethod
    def _types(types: frozenset[ast.ValueType]) -> str:
        return " 또는 ".join(sorted(value.value for value in types))

    @staticmethod
    def _type_name(value_type: Type) -> str:
        return value_type.value

    @staticmethod
    def _expression_token(expression: ast.Expression) -> str:
        if isinstance(expression, ast.NameExpression): return expression.name
        if isinstance(expression, ast.LiteralExpression): return str(expression.value)
        if isinstance(expression, ast.MemberExpression): return expression.member
        if isinstance(expression, ast.CallExpression) and isinstance(expression.callee, ast.NameExpression): return expression.callee.name
        if isinstance(expression, (ast.UnaryExpression, ast.BinaryExpression)): return expression.operator
        return type(expression).__name__

    @staticmethod
    def _numeric_literal(expression: ast.Expression) -> float | None:
        sign = 1
        if isinstance(expression, ast.UnaryExpression) and expression.operator == "-":
            sign = -1
            expression = expression.operand
        if isinstance(expression, ast.LiteralExpression) and expression.value_type in NUMBER_TYPES:
            return sign * float(expression.value)
        return None


def validate(program: ast.Program, catalog: ResourceCatalog | None = None) -> tuple[Diagnostic, ...]:
    return SemanticValidator(catalog).validate(program)

"""Text-independent tree AST shared by CVES tools and future editors."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import TypeAlias

from .diagnostics import SourceSpan


def _span_field() -> object:
    # A default keeps metadata out of the semantic constructor contract, which is
    # useful for GUI-created nodes. Parsed nodes always receive a concrete span.
    return field(default=None, compare=False, repr=False)


class ValueType(str, Enum):
    BOOL = "bool"
    INT = "int"
    DECIMAL = "decimal"
    STRING = "string"
    RESOURCE_ID = "resource_id"
    POSITION = "position"
    LOCATION_REF = "location_ref"
    POKEMON_SELECTION = "pokemon_selection"
    BATTLE_RESULT = "battle_result"
    ITEM_RESULT = "item_result"
    MOVEMENT_RESULT = "movement_result"


class CommandKind(str, Enum):
    STOP = "stop"
    SHOW_CHOICES = "show_choices"
    SET_FLAG = "set_flag"
    SET_VARIABLE = "set_variable"
    SET_PLAYER_VARIABLE = "set_player_variable"
    UNLOCK_FEATURE = "unlock_feature"
    SET_LEVEL_CAP = "set_level_cap"
    GIVE_ITEM = "give_item"
    GIVE_LOOT = "give_loot"
    GIVE_MONEY = "give_money"
    TAKE_MONEY = "take_money"
    GRANT_BADGE = "grant_badge"
    GRANT_FIELD_MOVE = "grant_field_move"
    BATTLE = "battle"
    STARTER_ROULETTE = "starter_roulette"
    MAP_SELECTION = "map_selection"
    MOVE = "move"
    TELEPORT = "teleport"
    ENTER_SPACE = "enter_space"
    FACE = "face"
    FADE = "fade"
    WAIT = "wait"
    SOUND = "sound"
    EFFECT = "effect"
    LABEL = "label"
    JUMP = "jump"
    CALL = "call"
    RETURN = "return"


@dataclass(frozen=True, slots=True)
class LiteralExpression:
    value: bool | int | str
    value_type: ValueType
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class NameExpression:
    name: str
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class MemberExpression:
    target: "Expression"
    member: str
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class CallExpression:
    callee: "Expression"
    arguments: tuple["Argument", ...]
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class UnaryExpression:
    operator: str
    operand: "Expression"
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class BinaryExpression:
    left: "Expression"
    operator: str
    right: "Expression"
    span: SourceSpan = _span_field()


Expression: TypeAlias = (
    LiteralExpression | NameExpression | MemberExpression | CallExpression
    | UnaryExpression | BinaryExpression
)


@dataclass(frozen=True, slots=True)
class Argument:
    value: Expression | None
    name: str | None = None
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class Property:
    name: str
    value: Expression
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class Trigger:
    name: str
    arguments: tuple[Argument, ...]
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class TextLiteral:
    value: str
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class LocalizedTextEntry:
    language: str
    value: str
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class LocalizedText:
    entries: tuple[LocalizedTextEntry, ...]
    span: SourceSpan = _span_field()


Text: TypeAlias = TextLiteral | LocalizedText


@dataclass(frozen=True, slots=True)
class SayStatement:
    speaker: str
    text: Text
    span: SourceSpan = _span_field()
    stable_id: str | None = None


@dataclass(frozen=True, slots=True)
class NarrateStatement:
    text: Text
    span: SourceSpan = _span_field()
    stable_id: str | None = None


@dataclass(frozen=True, slots=True)
class LetStatement:
    name: str
    value: Expression
    span: SourceSpan = _span_field()
    stable_id: str | None = None


@dataclass(frozen=True, slots=True)
class IfStatement:
    condition: Expression
    then_block: "Block"
    else_block: "Block | None"
    span: SourceSpan = _span_field()
    stable_id: str | None = None


@dataclass(frozen=True, slots=True)
class ChoiceOption:
    text: Text
    block: "Block"
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class ChoiceStatement:
    prompt: Text
    options: tuple[ChoiceOption, ...]
    result: str | None
    span: SourceSpan = _span_field()
    stable_id: str | None = None


@dataclass(frozen=True, slots=True)
class RepeatStatement:
    count: Expression
    block: "Block"
    span: SourceSpan = _span_field()
    stable_id: str | None = None


@dataclass(frozen=True, slots=True)
class CommandStatement:
    kind: CommandKind
    arguments: tuple[Argument, ...]
    properties: tuple[Property, ...]
    awaited: bool
    result: str | None
    span: SourceSpan = _span_field()
    stable_id: str | None = None


Statement: TypeAlias = (
    SayStatement | NarrateStatement | LetStatement | IfStatement
    | ChoiceStatement | RepeatStatement | CommandStatement
)


@dataclass(frozen=True, slots=True)
class Block:
    statements: tuple[Statement, ...]
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class Page:
    condition: Expression | None
    block: Block
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class Event:
    trigger: Trigger
    pages: tuple[Page, ...]
    span: SourceSpan = _span_field()


@dataclass(frozen=True, slots=True)
class Program:
    events: tuple[Event, ...]
    span: SourceSpan = _span_field()

"""Compile the normalized NPC behavior presets into representation-neutral CVES ASTs."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import replace
from typing import Any

from . import ast


BATTLE_PRESETS = frozenset({"battle", "gym", "elite", "champion"})


def preset_program(document: Mapping[str, Any]) -> ast.Program:
    """Build the canonical V5 tree for one schema-v4 normalized behavior preset."""
    design = document.get("event_design")
    if not isinstance(design, Mapping) or design.get("mode") != "preset":
        raise ValueError("V5 자동 생성에는 NPC 행동 프리셋이 필요합니다.")
    preset = design.get("preset")
    if not isinstance(preset, Mapping):
        raise ValueError("NPC 행동 프리셋 설정이 필요합니다.")
    preset_type = preset.get("type")
    if preset_type not in {"simple", "repeat", "item", *BATTLE_PRESETS}:
        raise ValueError(f"지원하지 않는 NPC 행동 프리셋입니다: {preset_type}")

    trigger = preset.get("initial_trigger", {"type": "interact", "range": 4})
    if not isinstance(trigger, Mapping):
        raise ValueError("행동 프리셋 트리거는 객체여야 합니다.")
    trigger_name = str(trigger.get("type", "interact"))
    trigger_arguments = (_named("range", _number(trigger.get("range", 4))),)
    if trigger_name == "proximity":
        trigger_name = "proximity_enter"

    if preset_type == "simple":
        pages = (_page(None, *_says(preset.get("first_text"), "안녕하세요!")),)
    elif preset_type in {"repeat", "item"}:
        pages = _repeat_pages(document, preset, item=preset_type == "item")
    else:
        pages = _battle_pages(document, preset)
        manual = ast.Event(ast.Trigger("interact", (
            _named("range", _number(trigger.get("range", 4) if trigger_name == "interact" else 4)),
        )), pages)
        return ast.Program((*_battle_proximity_events(document, preset, trigger), manual))
    return ast.Program((ast.Event(ast.Trigger(trigger_name, trigger_arguments), pages),))


def automatic_state_key(document_id: object, suffix: str) -> str:
    value = str(document_id or "cobbleventure:npc/new_npc")
    namespace, separator, path = value.partition(":")
    if not separator:
        namespace, path = "cobbleventure", value
    return f"{namespace}:flag/npc/{path.removeprefix('npc/')}/{suffix}"


def _repeat_pages(
    document: Mapping[str, Any], preset: Mapping[str, Any], *, item: bool
) -> tuple[ast.Page, ...]:
    suffix = "claimed" if item else "talked"
    state_key = (
        automatic_state_key(document.get("id"), suffix)
        if preset.get("auto_state_key", "state_key" not in preset) is True
        else str(preset.get("state_key", automatic_state_key(document.get("id"), suffix)))
    )
    first: list[ast.Statement] = list(_says(preset.get("first_text"), "안녕하세요!"))
    if item:
        reward_name = "reward"
        first.append(_command(
            ast.CommandKind.GIVE_ITEM,
            _string(preset.get("item", "cobblemon:poke_ball")),
            named=("count", _integer(preset.get("item_count", 1))),
            flags=("notify",), result=reward_name, stable_id="first/give_item",
        ))
        first.append(_remaining_guard(reward_name))
        if preset.get("after_item_text"):
            first.extend(_says(preset["after_item_text"], "방금 준 아이템을 잘 활용해 보세요."))
    first.append(_command(
        ast.CommandKind.SET_FLAG, _string(state_key), _boolean(True),
        stable_id=f"first/set_{suffix}",
    ))
    return (
        _page(_call("flag", _string(state_key)), *_says(preset.get("repeat_text"), "다시 만났네요.")),
        _page(None, *first),
    )


def _battle_pages(
    document: Mapping[str, Any], preset: Mapping[str, Any]
) -> tuple[ast.Page, ...]:
    state_key = str(
        preset.get("victory_state_key")
        or preset.get("clear_key")
        or automatic_state_key(document.get("id"), "defeated")
    )
    victory: list[ast.Statement] = []
    for key in dict.fromkeys(filter(None, (state_key, preset.get("clear_key")))):
        victory.append(_command(
            ast.CommandKind.SET_FLAG, _string(key), _boolean(True),
            stable_id=f"victory/set_{'clear' if key == preset.get('clear_key') else 'defeated'}",
        ))
    if preset.get("type") == "gym" and preset.get("badge"):
        victory.append(_command(
            ast.CommandKind.GRANT_BADGE, _string(preset["badge"]),
            stable_id="victory/grant_badge",
        ))
    if preset.get("win_item"):
        reward_name = "win_item"
        victory.extend((
            _command(
                ast.CommandKind.GIVE_ITEM, _string(preset["win_item"]),
                named=("count", _integer(preset.get("win_item_count", 1))),
                flags=("notify",), result=reward_name, stable_id="victory/give_item",
            ),
            _remaining_guard(reward_name),
        ))
    victory.extend(_says(preset.get("win_text"), "좋은 승부였어!"))

    defeat: list[ast.Statement] = []
    loss_money = max(0, int(preset.get("loss_money", 0)))
    if loss_money:
        defeat.append(_command(
            ast.CommandKind.TAKE_MONEY, _integer(loss_money),
            result="money_taken", stable_id="defeat/take_money",
        ))
    defeat.extend(_says(preset.get("loss_text"), "다시 도전해 줘."))

    battle = _command(
        ast.CommandKind.BATTLE, _string(preset.get("battle", "cobbleventure:battle/example")),
        awaited=True, result="battle_result", stable_id="challenge/battle",
    )
    outcome = ast.IfStatement(
        ast.BinaryExpression(
            ast.MemberExpression(ast.NameExpression("battle_result"), "outcome"),
            "==", _string("win"),
        ),
        ast.Block(tuple(victory)), ast.Block(tuple(defeat)),
    )
    choice = ast.ChoiceStatement(
        _text({"ko_kr": "도전하시겠습니까?"}),
        (
            ast.ChoiceOption(_text({"ko_kr": "승부한다"}), ast.Block((battle, outcome))),
            ast.ChoiceOption(
                _text({"ko_kr": "다음에 도전한다"}),
                ast.Block((_command(ast.CommandKind.STOP),)),
            ),
        ),
        None,
    )
    return (
        _page(_call("flag", _string(state_key)), *_says(preset.get("win_text"), "좋은 승부였어!")),
        _page(None, *_says(preset.get("first_text"), "안녕하세요!"), choice),
    )


def _battle_proximity_events(
    document: Mapping[str, Any], preset: Mapping[str, Any], initial: Mapping[str, Any]
) -> tuple[ast.Event, ast.Event]:
    """Build the warning and forced challenge stages used by route placements."""
    configured = preset.get("proximity_trigger")
    proximity = configured if isinstance(configured, Mapping) else {}
    if initial.get("type") == "proximity":
        battle_range = float(initial.get("range", 6))
        warning_range = battle_range + float(initial.get("warning_offset", 3))
    else:
        battle_range = float(proximity.get("battle_range", 6))
        warning_range = float(proximity.get("warning_range", 9))
    if battle_range <= 0 or warning_range <= battle_range:
        raise ValueError("근접 강제전투는 0보다 큰 battle_range와 그보다 큰 warning_range가 필요합니다.")

    group = str(proximity.get("group", "trainer_battle"))
    warning_stage = str(proximity.get("warning_stage", "warning"))
    track = str(proximity.get("warning_track", "encounter.trainer_boy"))
    state_key = str(
        preset.get("victory_state_key")
        or preset.get("clear_key")
        or automatic_state_key(document.get("id"), "defeated")
    )
    undefeated = ast.UnaryExpression("!", _call("flag", _string(state_key)))

    pages = _battle_pages(document, preset)
    challenge = pages[1].block.statements[-1]
    if not isinstance(challenge, ast.ChoiceStatement):
        raise ValueError("배틀 프리셋의 도전 선택지 트리를 만들 수 없습니다.")
    forced = (
        *pages[1].block.statements[:-1],
        *challenge.options[0].block.statements,
    )
    forced = tuple(_prefix_stable_ids(statement, "proximity/") for statement in forced)
    warning = ast.Event(
        ast.Trigger("proximity_enter", (
            _named("range", _number(warning_range)),
            _named("group", _string(group)),
            _named("stage", _string(warning_stage)),
        )),
        (_page(undefeated, _command(ast.CommandKind.ENCOUNTER_WARNING, _string(track))),),
    )
    battle = ast.Event(
        ast.Trigger("proximity_enter", (
            _named("range", _number(battle_range)),
            _named("group", _string(group)),
            _named("after", _string(warning_stage)),
        )),
        (_page(undefeated, *forced),),
    )
    return warning, battle


def _prefix_stable_ids(statement: ast.Statement, prefix: str) -> ast.Statement:
    """Clone a reused subtree while keeping operation IDs unique in the program."""
    stable_id = getattr(statement, "stable_id", None)
    changes: dict[str, object] = {}
    if stable_id is not None:
        changes["stable_id"] = prefix + stable_id
    if isinstance(statement, ast.IfStatement):
        changes["then_block"] = ast.Block(tuple(
            _prefix_stable_ids(child, prefix) for child in statement.then_block.statements
        ))
        if statement.else_block is not None:
            changes["else_block"] = ast.Block(tuple(
                _prefix_stable_ids(child, prefix) for child in statement.else_block.statements
            ))
    elif isinstance(statement, ast.ChoiceStatement):
        changes["options"] = tuple(
            replace(option, block=ast.Block(tuple(
                _prefix_stable_ids(child, prefix) for child in option.block.statements
            )))
            for option in statement.options
        )
    elif isinstance(statement, ast.RepeatStatement):
        changes["block"] = ast.Block(tuple(
            _prefix_stable_ids(child, prefix) for child in statement.block.statements
        ))
    return replace(statement, **changes)


def _remaining_guard(result: str) -> ast.IfStatement:
    return ast.IfStatement(
        ast.BinaryExpression(
            ast.MemberExpression(ast.NameExpression(result), "remaining_count"),
            ">", _int(0),
        ),
        ast.Block((
            ast.NarrateStatement(ast.TextLiteral("가방에 빈 공간이 없어 보상을 모두 받을 수 없습니다.")),
            _command(ast.CommandKind.STOP),
        )),
        None,
    )


def _page(condition: ast.Expression | None, *statements: ast.Statement) -> ast.Page:
    return ast.Page(condition, ast.Block(tuple(statements)))


def _says(value: object, fallback: str) -> tuple[ast.SayStatement, ...]:
    if isinstance(value, Mapping):
        lines = {
            str(language): tuple(line.strip() for line in text.splitlines() if line.strip())
            for language, text in value.items()
            if isinstance(text, str) and text.strip()
        }
        count = max((len(entries) for entries in lines.values()), default=0)
        if count:
            return tuple(ast.SayStatement("npc", ast.LocalizedText(tuple(
                ast.LocalizedTextEntry(language, entries[index])
                for language, entries in lines.items()
                if index < len(entries)
            ))) for index in range(count))
    text = str(value).strip() if isinstance(value, str) else fallback
    lines = tuple(line.strip() for line in text.splitlines() if line.strip()) or (fallback,)
    return tuple(ast.SayStatement("npc", ast.TextLiteral(line)) for line in lines)


def _text(value: object, fallback: str = "") -> ast.Text:
    if isinstance(value, Mapping):
        entries = tuple(
            ast.LocalizedTextEntry(str(language), text)
            for language, text in value.items()
            if isinstance(text, str) and text
        )
        if entries:
            return ast.LocalizedText(entries)
    if isinstance(value, str) and value:
        return ast.TextLiteral(value)
    return ast.LocalizedText((ast.LocalizedTextEntry("ko_kr", fallback),))


def _command(
    kind: ast.CommandKind,
    *values: ast.Expression,
    named: tuple[str, ast.Expression] | None = None,
    flags: tuple[str, ...] = (),
    awaited: bool = False,
    result: str | None = None,
    stable_id: str | None = None,
) -> ast.CommandStatement:
    arguments = [ast.Argument(value) for value in values]
    if named is not None:
        arguments.append(ast.Argument(named[1], named[0]))
    arguments.extend(ast.Argument(None, flag) for flag in flags)
    return ast.CommandStatement(kind, tuple(arguments), (), awaited, result, stable_id=stable_id)


def _call(name: str, *values: ast.Expression) -> ast.CallExpression:
    return ast.CallExpression(ast.NameExpression(name), tuple(ast.Argument(value) for value in values))


def _named(name: str, value: ast.Expression) -> ast.Argument:
    return ast.Argument(value, name)


def _string(value: object) -> ast.LiteralExpression:
    return ast.LiteralExpression(str(value), ast.ValueType.STRING)


def _boolean(value: bool) -> ast.LiteralExpression:
    return ast.LiteralExpression(value, ast.ValueType.BOOL)


def _integer(value: object) -> ast.LiteralExpression:
    return ast.LiteralExpression(max(1, int(value)), ast.ValueType.INT)


def _int(value: object) -> ast.LiteralExpression:
    return ast.LiteralExpression(int(value), ast.ValueType.INT)


def _number(value: object) -> ast.LiteralExpression:
    number = float(value)
    if number.is_integer():
        return ast.LiteralExpression(int(number), ast.ValueType.INT)
    return ast.LiteralExpression(str(number), ast.ValueType.DECIMAL)

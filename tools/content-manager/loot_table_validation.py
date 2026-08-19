"""Forward-compatible structural validation for authored Minecraft loot tables."""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Any


RESOURCE_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
COMPOSITE_ENTRIES = frozenset({
    "minecraft:alternatives", "minecraft:group", "minecraft:sequence",
})


@dataclass(frozen=True, slots=True)
class LootTableProblem:
    path: str
    message: str


def validate_loot_table_document(
    document: Any, known_items: set[str] | None = None,
) -> tuple[LootTableProblem, ...]:
    problems: list[LootTableProblem] = []
    if not isinstance(document, dict):
        return (LootTableProblem("$", "loot table JSON 루트는 object여야 합니다."),)
    _resource_id(document.get("type"), "$.type", problems, optional=True)
    pools = document.get("pools")
    if not isinstance(pools, list):
        problems.append(LootTableProblem("$.pools", "pools는 배열이어야 합니다."))
        return tuple(problems)
    for index, pool in enumerate(pools):
        _validate_pool(pool, f"$.pools[{index}]", problems, known_items)
    return tuple(problems)


def _validate_pool(
    value: Any,
    path: str,
    problems: list[LootTableProblem],
    known_items: set[str] | None,
) -> None:
    if not isinstance(value, dict):
        problems.append(LootTableProblem(path, "loot pool은 object여야 합니다."))
        return
    _number_provider(value.get("rolls"), f"{path}.rolls", problems, required=True)
    if "bonus_rolls" in value:
        _number_provider(value["bonus_rolls"], f"{path}.bonus_rolls", problems)
    entries = value.get("entries")
    if not isinstance(entries, list) or not entries:
        problems.append(LootTableProblem(f"{path}.entries", "entries는 비어 있지 않은 배열이어야 합니다."))
    else:
        for index, entry in enumerate(entries):
            _validate_entry(entry, f"{path}.entries[{index}]", problems, known_items)
    _validate_conditions(value.get("conditions"), f"{path}.conditions", problems)
    _validate_functions(value.get("functions"), f"{path}.functions", problems, known_items)


def _validate_entry(
    value: Any,
    path: str,
    problems: list[LootTableProblem],
    known_items: set[str] | None,
) -> None:
    if not isinstance(value, dict):
        problems.append(LootTableProblem(path, "loot entry는 object여야 합니다."))
        return
    entry_type = _resource_id(value.get("type"), f"{path}.type", problems)
    if "weight" in value:
        _integer(value["weight"], f"{path}.weight", problems, minimum=0)
    if "quality" in value:
        _integer(value["quality"], f"{path}.quality", problems)
    _validate_conditions(value.get("conditions"), f"{path}.conditions", problems)
    _validate_functions(value.get("functions"), f"{path}.functions", problems, known_items)

    if entry_type == "minecraft:item":
        item_id = _resource_id(value.get("name"), f"{path}.name", problems)
        if item_id and known_items is not None and item_id not in known_items:
            problems.append(LootTableProblem(
                f"{path}.name", f"아이템 카탈로그에 없는 ID입니다: {item_id}",
            ))
    elif entry_type in COMPOSITE_ENTRIES:
        children = value.get("children")
        if not isinstance(children, list) or not children:
            problems.append(LootTableProblem(
                f"{path}.children", "composite entry의 children은 비어 있지 않은 배열이어야 합니다.",
            ))
        else:
            for index, child in enumerate(children):
                _validate_entry(child, f"{path}.children[{index}]", problems, known_items)
    elif entry_type == "minecraft:tag":
        _resource_id(value.get("name"), f"{path}.name", problems)
        if "expand" in value and not isinstance(value["expand"], bool):
            problems.append(LootTableProblem(f"{path}.expand", "expand는 bool이어야 합니다."))


def _validate_functions(
    value: Any,
    path: str,
    problems: list[LootTableProblem],
    known_items: set[str] | None,
) -> None:
    if value is None:
        return
    if not isinstance(value, list):
        problems.append(LootTableProblem(path, "functions는 배열이어야 합니다."))
        return
    for index, function in enumerate(value):
        function_path = f"{path}[{index}]"
        if not isinstance(function, dict):
            problems.append(LootTableProblem(function_path, "loot function은 object여야 합니다."))
            continue
        function_id = _resource_id(
            function.get("function"), f"{function_path}.function", problems,
        )
        _validate_conditions(
            function.get("conditions"), f"{function_path}.conditions", problems,
        )
        if function_id == "minecraft:set_count":
            _number_provider(
                function.get("count"), f"{function_path}.count", problems, required=True,
            )
            if "add" in function and not isinstance(function["add"], bool):
                problems.append(LootTableProblem(
                    f"{function_path}.add", "set_count.add는 bool이어야 합니다.",
                ))
        elif function_id == "minecraft:set_item":
            item_id = _resource_id(
                function.get("item"), f"{function_path}.item", problems,
            )
            if item_id and known_items is not None and item_id not in known_items:
                problems.append(LootTableProblem(
                    f"{function_path}.item",
                    f"아이템 카탈로그에 없는 ID입니다: {item_id}",
                ))
        elif function_id == "minecraft:set_contents":
            entries = function.get("entries")
            if not isinstance(entries, list):
                problems.append(LootTableProblem(
                    f"{function_path}.entries", "set_contents.entries는 배열이어야 합니다.",
                ))
            else:
                for entry_index, entry in enumerate(entries):
                    _validate_entry(
                        entry,
                        f"{function_path}.entries[{entry_index}]",
                        problems,
                        known_items,
                    )


def _validate_conditions(
    value: Any, path: str, problems: list[LootTableProblem],
) -> None:
    if value is None:
        return
    if not isinstance(value, list):
        problems.append(LootTableProblem(path, "conditions는 배열이어야 합니다."))
        return
    for index, condition in enumerate(value):
        condition_path = f"{path}[{index}]"
        if not isinstance(condition, dict):
            problems.append(LootTableProblem(condition_path, "loot condition은 object여야 합니다."))
            continue
        _resource_id(condition.get("condition"), f"{condition_path}.condition", problems)
        if "terms" in condition:
            _validate_conditions(condition["terms"], f"{condition_path}.terms", problems)
        if "term" in condition:
            term = condition["term"]
            if not isinstance(term, dict):
                problems.append(LootTableProblem(
                    f"{condition_path}.term", "condition term은 object여야 합니다.",
                ))
            else:
                _resource_id(
                    term.get("condition"), f"{condition_path}.term.condition", problems,
                )


def _number_provider(
    value: Any,
    path: str,
    problems: list[LootTableProblem],
    *,
    required: bool = False,
) -> None:
    if value is None:
        if required:
            problems.append(LootTableProblem(path, "숫자 또는 number provider가 필요합니다."))
        return
    if isinstance(value, bool):
        problems.append(LootTableProblem(path, "bool은 숫자로 사용할 수 없습니다."))
        return
    if isinstance(value, (int, float)):
        finite = True if isinstance(value, int) else math.isfinite(value)
        if not finite or value < 0:
            problems.append(LootTableProblem(path, "숫자는 유한한 0 이상 값이어야 합니다."))
        return
    if not isinstance(value, dict):
        problems.append(LootTableProblem(path, "숫자 또는 number provider object여야 합니다."))
        return
    provider_type = _resource_id(value.get("type"), f"{path}.type", problems)
    if provider_type == "minecraft:uniform":
        _number_provider(value.get("min"), f"{path}.min", problems, required=True)
        _number_provider(value.get("max"), f"{path}.max", problems, required=True)
    elif provider_type == "minecraft:binomial":
        _number_provider(value.get("n"), f"{path}.n", problems, required=True)
        _number_provider(value.get("p"), f"{path}.p", problems, required=True)
    elif provider_type == "minecraft:constant":
        _number_provider(value.get("value"), f"{path}.value", problems, required=True)


def _resource_id(
    value: Any,
    path: str,
    problems: list[LootTableProblem],
    *,
    optional: bool = False,
) -> str | None:
    if value is None and optional:
        return None
    if not isinstance(value, str) or not RESOURCE_ID.fullmatch(value):
        problems.append(LootTableProblem(path, "올바른 namespace:path 리소스 ID가 필요합니다."))
        return None
    return value


def _integer(
    value: Any,
    path: str,
    problems: list[LootTableProblem],
    *,
    minimum: int | None = None,
) -> None:
    if not isinstance(value, int) or isinstance(value, bool):
        problems.append(LootTableProblem(path, "정수가 필요합니다."))
    elif minimum is not None and value < minimum:
        problems.append(LootTableProblem(path, f"{minimum} 이상의 정수가 필요합니다."))

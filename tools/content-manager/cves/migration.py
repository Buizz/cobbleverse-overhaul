"""Temporary V4/V5 semantic contracts used during staged NPC migration."""

from __future__ import annotations

from dataclasses import dataclass, fields
from decimal import Decimal
from typing import Any

from . import ast


@dataclass(frozen=True, slots=True)
class ItemRewardMigrationContract:
    trigger_range: Decimal
    state_key: str
    first_text: tuple[tuple[str, str], ...]
    repeat_text: tuple[tuple[str, str], ...]
    item: str
    count: int
    notify: bool


@dataclass(frozen=True, slots=True)
class BattleEventMigrationContract:
    trigger_range: Decimal
    repeat_state_key: str
    prepared_item: str
    prepared_count: int
    greeting_text: tuple[tuple[str, str], ...]
    prepared_text: tuple[tuple[str, str], ...]
    default_choices: tuple[tuple[str, str], ...]
    prepared_choices: tuple[tuple[str, str], ...]
    battle: str
    victory_flag: str
    loot: str
    victory_text: tuple[tuple[str, str], ...]
    defeat_text: tuple[tuple[str, str], ...]
    money_reward: tuple[tuple[str, object], ...]


@dataclass(frozen=True, slots=True)
class SimpleDialogueMigrationContract:
    trigger_range: Decimal
    text: tuple[tuple[str, str], ...]


@dataclass(frozen=True, slots=True)
class StarterEventMigrationContract:
    trigger_range: Decimal
    pokedex_state_key: str
    starter_state_key: str
    greeting_text: tuple[tuple[tuple[str, str], ...], ...]
    starter_repeat_text: tuple[tuple[str, str], ...]
    pokedex_offer_text: tuple[tuple[str, str], ...]
    pokedex_explanation_text: tuple[tuple[str, str], ...]
    completed_text: tuple[tuple[str, str], ...]
    pokedex_item: str
    pokedex_count: int


@dataclass(frozen=True, slots=True)
class GymLeaderMigrationContract:
    trigger_range: Decimal
    defeated_state_key: str
    challenge_text: tuple[tuple[tuple[str, str], ...], ...]
    choices: tuple[tuple[tuple[str, str], ...], ...]
    battle: str
    victory_text: tuple[tuple[tuple[str, str], ...], ...]
    defeat_text: tuple[tuple[tuple[str, str], ...], ...]
    cleared_text: tuple[tuple[tuple[str, str], ...], ...]
    badge: str
    post_victory_level_cap: int


def item_reward_contract_from_v4(document: dict[str, Any]) -> ItemRewardMigrationContract:
    """Read the externally meaningful fields of a V4 item preset."""
    try:
        preset = document["event_design"]["preset"]
        if document["schema_version"] != 4 or preset["type"] != "item":
            raise ValueError
        return ItemRewardMigrationContract(
            Decimal(str(preset["initial_trigger"]["range"])),
            _string(preset["state_key"], "state_key"),
            _localized_document(preset["first_text"], "first_text"),
            _localized_document(preset["repeat_text"], "repeat_text"),
            _string(preset["item"], "item"),
            _positive_int(preset["item_count"], "item_count"),
            True,
        )
    except (KeyError, TypeError, ValueError, ArithmeticError) as error:
        raise ValueError("V4 item preset 의미 계약을 읽을 수 없습니다.") from error


def item_reward_contract_from_cves(program: ast.Program) -> ItemRewardMigrationContract:
    """Read the same contract from a structured two-page CVES item event."""
    if len(program.events) != 1:
        raise ValueError("V5 item reward는 이벤트 하나여야 합니다.")
    event = program.events[0]
    if event.trigger.name != "interact" or len(event.pages) != 2:
        raise ValueError("V5 item reward는 interact 조건 페이지와 default 페이지가 필요합니다.")
    trigger_range = _named_literal(event.trigger.arguments, "range", 4)
    claimed_page, default_page = event.pages
    state_key = _flag_condition(claimed_page.condition)
    if default_page.condition is not None:
        raise ValueError("V5 item reward의 마지막 페이지는 default여야 합니다.")
    repeat_say = next((value for value in claimed_page.block.statements if isinstance(value, ast.SayStatement)), None)
    first_say = next((value for value in default_page.block.statements if isinstance(value, ast.SayStatement)), None)
    reward = next((
        value for value in default_page.block.statements
        if isinstance(value, ast.CommandStatement) and value.kind is ast.CommandKind.GIVE_ITEM
    ), None)
    set_flag = next((
        value for value in default_page.block.statements
        if isinstance(value, ast.CommandStatement) and value.kind is ast.CommandKind.SET_FLAG
    ), None)
    if repeat_say is None or first_say is None or reward is None or set_flag is None:
        raise ValueError("V5 item reward에 반복/최초 대사, give_item, set_flag가 필요합니다.")
    if reward.result is None:
        raise ValueError("V5 give_item은 실패 분기를 위한 결과 변수가 필요합니다.")
    reward_index = default_page.block.statements.index(reward)
    set_flag_index = default_page.block.statements.index(set_flag)
    guarded = any(
        _is_remaining_guard(value, reward.result)
        for value in default_page.block.statements[reward_index + 1:set_flag_index]
    )
    if not guarded:
        raise ValueError("V5 give_item과 set_flag 사이에 remaining_count 실패·중단 분기가 필요합니다.")
    set_key = _positional_literal(set_flag.arguments, 0)
    set_value = _positional_literal(set_flag.arguments, 1)
    if set_key != state_key or set_value is not True:
        raise ValueError("V5 set_flag는 조건 페이지와 같은 플래그를 true로 기록해야 합니다.")
    return ItemRewardMigrationContract(
        Decimal(str(trigger_range)),
        state_key,
        _text_document(first_say.text),
        _text_document(repeat_say.text),
        _string(_positional_literal(reward.arguments, 0), "give_item item"),
        _positive_int(_named_literal(reward.arguments, "count", 1), "give_item count"),
        any(value.name == "notify" and value.value is None for value in reward.arguments),
    )


def compare_item_reward_migration(
    legacy_document: dict[str, Any], program: ast.Program
) -> tuple[str, ...]:
    """Return deterministic field-level differences between V4 and V5."""
    legacy = item_reward_contract_from_v4(legacy_document)
    current = item_reward_contract_from_cves(program)
    return tuple(
        f"{field.name}: V4={getattr(legacy, field.name)!r}, V5={getattr(current, field.name)!r}"
        for field in fields(ItemRewardMigrationContract)
        if getattr(legacy, field.name) != getattr(current, field.name)
    )


def compare_battle_event_migration(
    legacy_document: dict[str, Any], program: ast.Program, battle_document: dict[str, Any]
) -> tuple[str, ...]:
    """Compare one V4 label battle graph with its nested V5 tree and battle metadata."""
    legacy = battle_event_contract_from_v4(legacy_document)
    current = battle_event_contract_from_cves(program, battle_document)
    return tuple(
        f"{field.name}: V4={getattr(legacy, field.name)!r}, V5={getattr(current, field.name)!r}"
        for field in fields(BattleEventMigrationContract)
        if getattr(legacy, field.name) != getattr(current, field.name)
    )


def compare_simple_dialogue_migration(
    legacy_document: dict[str, Any], program: ast.Program
) -> tuple[str, ...]:
    legacy = simple_dialogue_contract_from_v4(legacy_document)
    current = simple_dialogue_contract_from_cves(program)
    return _contract_differences(SimpleDialogueMigrationContract, legacy, current)


def simple_dialogue_contract_from_v4(
    document: dict[str, Any],
) -> SimpleDialogueMigrationContract:
    try:
        preset = document["event_design"]["preset"]
        if document["schema_version"] != 4 or preset["type"] != "simple":
            raise ValueError
        return SimpleDialogueMigrationContract(
            Decimal(str(preset["initial_trigger"]["range"])),
            _localized_document(preset["first_text"], "first_text"),
        )
    except (KeyError, TypeError, ValueError, ArithmeticError) as error:
        raise ValueError("V4 simple preset 의미 계약을 읽을 수 없습니다.") from error


def simple_dialogue_contract_from_cves(program: ast.Program) -> SimpleDialogueMigrationContract:
    if len(program.events) != 1:
        raise ValueError("V5 simple 이벤트는 이벤트 하나여야 합니다.")
    event = program.events[0]
    if event.trigger.name != "interact" or len(event.pages) != 1:
        raise ValueError("V5 simple 이벤트는 interact default 페이지 하나여야 합니다.")
    page = event.pages[0]
    if page.condition is not None or len(page.block.statements) != 1 \
            or not isinstance(page.block.statements[0], ast.SayStatement):
        raise ValueError("V5 simple 이벤트 default 페이지에는 대사 하나만 있어야 합니다.")
    return SimpleDialogueMigrationContract(
        Decimal(str(_named_literal(event.trigger.arguments, "range", 4))),
        _text_document(page.block.statements[0].text),
    )


def compare_starter_event_migration(
    legacy_document: dict[str, Any], program: ast.Program
) -> tuple[str, ...]:
    legacy = starter_event_contract_from_v4(legacy_document)
    current = starter_event_contract_from_cves(program)
    return _contract_differences(StarterEventMigrationContract, legacy, current)


def starter_event_contract_from_v4(document: dict[str, Any]) -> StarterEventMigrationContract:
    try:
        if document["schema_version"] != 4:
            raise ValueError
        event = document["events"][0]
        commands = event["commands"]
        branches = [value for value in commands if value.get("type") == "branch"]
        dialogues = {
            value["id"]: value["text"]
            for value in commands if value.get("type") == "dialogue"
        }
        roulette = [value for value in commands if value.get("type") == "start_starter_roulette"]
        rewards = [value for value in commands if value.get("type") == "give_item"]
        state = [value for value in commands if value.get("type") == "set_flag"]
        if len(branches) != 2 or len(roulette) != 1 or len(rewards) != 1 or len(state) != 1:
            raise ValueError
        return StarterEventMigrationContract(
            Decimal(str(event["trigger"]["range"])),
            _v4_true_flag(branches[0]),
            _v4_true_flag(branches[1]),
            (
                _localized_document(dialogues["greeting_1"], "greeting_1"),
                _localized_document(dialogues["greeting_2"], "greeting_2"),
            ),
            _localized_document(dialogues["starter_chosen_praise"], "starter praise"),
            _localized_document(dialogues["pokedex_offer"], "pokedex offer"),
            _localized_document(dialogues["pokedex_explanation"], "pokedex explanation"),
            _localized_document(dialogues["starter_received"], "completed text"),
            _string(rewards[0]["item"], "pokedex item"),
            _positive_int(rewards[0]["count"], "pokedex count"),
        )
    except (KeyError, IndexError, TypeError, ValueError, ArithmeticError) as error:
        raise ValueError("V4 starter event 의미 계약을 읽을 수 없습니다.") from error


def starter_event_contract_from_cves(program: ast.Program) -> StarterEventMigrationContract:
    if len(program.events) != 1 or len(program.events[0].pages) != 3:
        raise ValueError("V5 starter event는 조건 페이지 둘과 default 페이지가 필요합니다.")
    event = program.events[0]
    completed_page, retry_page, first_page = event.pages
    if first_page.condition is not None:
        raise ValueError("V5 starter event의 마지막 페이지는 default여야 합니다.")
    pokedex_state = _flag_condition(completed_page.condition)
    starter_state = _flag_condition(retry_page.condition)
    completed_says = _direct_says(completed_page.block)
    retry_says = _direct_says(retry_page.block)
    first_says = _direct_says(first_page.block)
    if len(completed_says) != 1 or len(retry_says) != 3 or len(first_says) != 5:
        raise ValueError("V5 starter event의 페이지별 대사 구성이 올바르지 않습니다.")
    roulette = _single_command(first_page.block, ast.CommandKind.STARTER_ROULETTE)
    if not roulette.awaited or roulette.result is None:
        raise ValueError("V5 starter_roulette는 결과를 받는 명시적 await여야 합니다.")
    expected_template = "${" + roulette.result + ".name|josa:을/를}"
    first_praise = dict(_text_document(first_says[2].text)).get("ko_kr", "")
    if expected_template not in first_praise:
        raise ValueError("V5 starter 선택 대사는 결과 name과 조사 필터를 사용해야 합니다.")
    retry_reward = _guarded_item_reward(retry_page.block, pokedex_state)
    first_reward = _guarded_item_reward(first_page.block, pokedex_state)
    if retry_reward != first_reward:
        raise ValueError("V5 starter 최초/재시도 도감 지급 계약이 다릅니다.")
    return StarterEventMigrationContract(
        Decimal(str(_named_literal(event.trigger.arguments, "range", 4))),
        pokedex_state,
        starter_state,
        tuple(_text_document(value.text) for value in first_says[:2]),
        _text_document(retry_says[0].text),
        _text_document(retry_says[1].text),
        _text_document(retry_says[2].text),
        _text_document(completed_says[0].text),
        retry_reward[0],
        retry_reward[1],
    )


def compare_gym_leader_migration(
    league_entry: dict[str, Any], program: ast.Program, post_victory_level_cap: int
) -> tuple[str, ...]:
    legacy = gym_leader_contract_from_league(league_entry, post_victory_level_cap)
    current = gym_leader_contract_from_cves(program)
    return _contract_differences(GymLeaderMigrationContract, legacy, current)


def gym_leader_contract_from_league(
    entry: dict[str, Any], post_victory_level_cap: int
) -> GymLeaderMigrationContract:
    try:
        if entry["role"] != "gym_leader":
            raise ValueError
        encounter = entry["encounter"]
        dialogue = encounter["dialogue"]
        rewards = encounter["rewards"]
        slug = _string(encounter["battle_id"], "battle id").rsplit("/", 1)[-1]
        region = _string(entry["region"], "region").rsplit("/", 1)[-1]
        return GymLeaderMigrationContract(
            Decimal("4"),
            f"cobbleventure:flag/gym/{region}/{slug}/defeated",
            _league_dialogue_documents(dialogue["challenge"]),
            ((('ko_kr', '승부한다'),), (('ko_kr', '다음에 도전한다'),)),
            _string(encounter["battle_id"], "battle id"),
            _league_dialogue_documents(dialogue["victory"]),
            _league_dialogue_documents(dialogue["defeat"]),
            _league_dialogue_documents(dialogue["cleared"]),
            _string(rewards["badge_id"], "badge id"),
            _positive_int(post_victory_level_cap, "post victory level cap"),
        )
    except (KeyError, TypeError, ValueError, ArithmeticError) as error:
        raise ValueError("리그 관장 의미 계약을 읽을 수 없습니다.") from error


def gym_leader_contract_from_cves(program: ast.Program) -> GymLeaderMigrationContract:
    if len(program.events) != 1 or len(program.events[0].pages) != 2:
        raise ValueError("V5 gym leader는 클리어 조건 페이지와 default 페이지가 필요합니다.")
    event = program.events[0]
    cleared_page, default_page = event.pages
    defeated_state = _flag_condition(cleared_page.condition)
    if default_page.condition is not None:
        raise ValueError("V5 gym leader의 마지막 페이지는 default여야 합니다.")
    cleared_says = _direct_says(cleared_page.block)
    challenge_says = _direct_says(default_page.block)
    choices = [value for value in default_page.block.statements if isinstance(value, ast.ChoiceStatement)]
    if len(cleared_says) < 1 or len(challenge_says) < 1 or len(choices) != 1:
        raise ValueError("V5 gym leader 페이지의 대사와 choice 구성이 올바르지 않습니다.")
    choice = choices[0]
    if len(choice.options) != 2:
        raise ValueError("V5 gym leader choice에는 승부와 취소 선택지가 필요합니다.")
    battle_options = [
        option for option in choice.options
        if _find_commands(option.block, ast.CommandKind.BATTLE)
    ]
    if len(battle_options) != 1:
        raise ValueError("V5 gym leader choice에는 battle 선택지 하나가 필요합니다.")
    battle_block = battle_options[0].block
    battle = _single_command(battle_block, ast.CommandKind.BATTLE)
    if not battle.awaited or battle.result is None:
        raise ValueError("V5 gym leader battle은 결과를 받는 명시적 await여야 합니다.")
    outcome = next(
        (value for value in battle_block.statements if isinstance(value, ast.IfStatement)),
        None,
    )
    if outcome is None or outcome.else_block is None \
            or not _is_outcome_win(outcome.condition, battle.result):
        raise ValueError("V5 gym leader battle 결과는 outcome == \"win\" if/else여야 합니다.")
    state = _single_command(outcome.then_block, ast.CommandKind.SET_FLAG)
    badge = _single_command(outcome.then_block, ast.CommandKind.GRANT_BADGE)
    cap = _single_command(outcome.then_block, ast.CommandKind.SET_LEVEL_CAP)
    if _positional_literal(state.arguments, 0) != defeated_state \
            or _positional_literal(state.arguments, 1) is not True:
        raise ValueError("V5 gym leader 승리 상태가 클리어 페이지 조건과 다릅니다.")
    victory_says = _direct_says(outcome.then_block)
    defeat_says = _direct_says(outcome.else_block)
    if not victory_says or not defeat_says:
        raise ValueError("V5 gym leader 승패 대사가 필요합니다.")
    return GymLeaderMigrationContract(
        Decimal(str(_named_literal(event.trigger.arguments, "range", 4))),
        defeated_state,
        tuple(_text_document(value.text) for value in challenge_says),
        tuple(_text_document(option.text) for option in choice.options),
        _string(_positional_literal(battle.arguments, 0), "battle"),
        tuple(_text_document(value.text) for value in victory_says),
        tuple(_text_document(value.text) for value in defeat_says),
        tuple(_text_document(value.text) for value in cleared_says),
        _string(_positional_literal(badge.arguments, 0), "badge"),
        _positive_int(_positional_literal(cap.arguments, 0), "level cap"),
    )


def battle_event_contract_from_v4(document: dict[str, Any]) -> BattleEventMigrationContract:
    try:
        if document["schema_version"] != 4:
            raise ValueError
        event = document["events"][0]
        commands = event["commands"]
        branches = [value for value in commands if value.get("type") == "branch"]
        repeat_state = branches[0]["conditions"][0]["key"]
        prepared = branches[1]["conditions"][0]
        dialogues = {value["id"]: value["text"] for value in commands if value.get("type") == "dialogue"}
        choices = [value for value in commands if value.get("type") == "choices"]
        battle = next(value for value in commands if value.get("type") == "start_battle")
        victory_flag = next(value for value in commands if value.get("type") == "set_flag")
        loot = next(value for value in commands if value.get("type") == "grant_loot")
        money = document["npc"]["battle_rewards"]["money"]
        return BattleEventMigrationContract(
            Decimal(str(event["trigger"]["range"])),
            _v4_battle_state(repeat_state, victory_flag["key"]),
            _string(prepared["item"], "prepared item"),
            _positive_int(prepared["count"], "prepared count"),
            _localized_document(dialogues["greeting"], "greeting"),
            _localized_document(dialogues["prepared_greeting"], "prepared greeting"),
            _v4_choice_texts(choices[0]),
            _v4_choice_texts(choices[1]),
            _string(battle["battle"], "battle"),
            _string(victory_flag["key"], "victory flag"),
            _string(loot["loot_table"], "loot"),
            _localized_document(dialogues["after_victory"], "victory text"),
            _localized_document(dialogues["after_defeat"], "defeat text"),
            _money_reward(money),
        )
    except (KeyError, IndexError, StopIteration, TypeError, ValueError, ArithmeticError) as error:
        raise ValueError("V4 battle event 의미 계약을 읽을 수 없습니다.") from error


def battle_event_contract_from_cves(
    program: ast.Program, battle_document: dict[str, Any]
) -> BattleEventMigrationContract:
    if len(program.events) != 1 or len(program.events[0].pages) != 2:
        raise ValueError("V5 battle event는 interact 조건 페이지와 default 페이지가 필요합니다.")
    event = program.events[0]
    repeat_page, default_page = event.pages
    repeat_state = _flag_condition(repeat_page.condition)
    if default_page.condition is not None or len(default_page.block.statements) != 1:
        raise ValueError("V5 battle event default 페이지는 준비 상태 if 트리여야 합니다.")
    prepared_if = default_page.block.statements[0]
    if not isinstance(prepared_if, ast.IfStatement) or prepared_if.else_block is None:
        raise ValueError("V5 battle event에 has_item if/else가 필요합니다.")
    prepared_item, prepared_count = _has_item_condition(prepared_if.condition)
    prepared_path = _battle_path(prepared_if.then_block)
    default_path = _battle_path(prepared_if.else_block)
    if prepared_path[4:] != default_path[4:]:
        raise ValueError("V5 준비/기본 배틀 분기의 승패·보상 의미가 다릅니다.")
    repeat_say = next((value for value in repeat_page.block.statements if isinstance(value, ast.SayStatement)), None)
    if repeat_say is None or _text_document(repeat_say.text) != default_path[7]:
        raise ValueError("V5 반복 페이지와 승리 대사가 다릅니다.")
    if repeat_state != default_path[5]:
        raise ValueError("V5 반복 페이지 조건과 승리 플래그가 다릅니다.")
    return BattleEventMigrationContract(
        Decimal(str(_named_literal(event.trigger.arguments, "range", 4))),
        repeat_state,
        prepared_item,
        prepared_count,
        default_path[0],
        prepared_path[0],
        default_path[1],
        prepared_path[1],
        default_path[4],
        default_path[5],
        default_path[6],
        default_path[7],
        default_path[8],
        _money_reward(battle_document["battle"]["money_reward"]),
    )


def _battle_path(block: ast.Block) -> tuple:
    if len(block.statements) != 2 or not isinstance(block.statements[0], ast.SayStatement) \
            or not isinstance(block.statements[1], ast.ChoiceStatement):
        raise ValueError("V5 battle 분기는 대사와 choice로 구성되어야 합니다.")
    greeting, choice = block.statements
    battle_options = [option for option in choice.options if _find_commands(option.block, ast.CommandKind.BATTLE)]
    if len(battle_options) != 1:
        raise ValueError("V5 choice에는 battle 선택지 하나가 필요합니다.")
    battle_block = battle_options[0].block
    battle_commands = _find_commands(battle_block, ast.CommandKind.BATTLE)
    if len(battle_commands) != 1 or battle_commands[0].result is None:
        raise ValueError("V5 battle은 결과 변수를 받는 await 명령이어야 합니다.")
    battle_command = battle_commands[0]
    outcome = next((value for value in battle_block.statements if isinstance(value, ast.IfStatement)), None)
    if outcome is None or outcome.else_block is None or not _is_outcome_win(outcome.condition, battle_command.result):
        raise ValueError("V5 battle 결과는 outcome == \"win\" if/else로 처리해야 합니다.")
    loot_commands = _find_commands(outcome.then_block, ast.CommandKind.GIVE_LOOT)
    flag_commands = _find_commands(outcome.then_block, ast.CommandKind.SET_FLAG)
    if len(loot_commands) != 1 or len(flag_commands) != 1:
        raise ValueError("V5 승리 분기에 give_loot와 상태 플래그가 필요합니다.")
    loot = loot_commands[0]
    if loot.result is None or not any(_is_remaining_guard(value, loot.result) for value in outcome.then_block.statements):
        raise ValueError("V5 give_loot에는 remaining_count 실패·중단 분기가 필요합니다.")
    victory_flag = _positional_literal(flag_commands[0].arguments, 0)
    victory_say = next((value for value in outcome.then_block.statements if isinstance(value, ast.SayStatement)), None)
    defeat_say = next((value for value in outcome.else_block.statements if isinstance(value, ast.SayStatement)), None)
    if victory_flag is None or victory_say is None or defeat_say is None:
        raise ValueError("V5 승패 분기에 플래그와 대사가 필요합니다.")
    return (
        _text_document(greeting.text),
        tuple(_text_document(option.text)[0] for option in choice.options),
        battle_options[0], outcome, _string(_positional_literal(battle_command.arguments, 0), "battle"),
        _string(victory_flag, "victory flag"),
        _string(_positional_literal(loot.arguments, 0), "loot"),
        _text_document(victory_say.text), _text_document(defeat_say.text),
    )


def _find_commands(block: ast.Block, kind: ast.CommandKind) -> list[ast.CommandStatement]:
    return [value for value in block.statements if isinstance(value, ast.CommandStatement) and value.kind is kind]


def _direct_says(block: ast.Block) -> list[ast.SayStatement]:
    return [value for value in block.statements if isinstance(value, ast.SayStatement)]


def _single_command(block: ast.Block, kind: ast.CommandKind) -> ast.CommandStatement:
    commands = _find_commands(block, kind)
    if len(commands) != 1:
        raise ValueError(f"V5 페이지에는 {kind.value} 명령 하나가 필요합니다.")
    return commands[0]


def _guarded_item_reward(block: ast.Block, state_key: str) -> tuple[str, int]:
    reward = _single_command(block, ast.CommandKind.GIVE_ITEM)
    set_flag = _single_command(block, ast.CommandKind.SET_FLAG)
    if reward.result is None:
        raise ValueError("V5 give_item은 실패 분기를 위한 결과 변수가 필요합니다.")
    reward_index = block.statements.index(reward)
    state_index = block.statements.index(set_flag)
    if state_index <= reward_index or not any(
        _is_remaining_guard(value, reward.result)
        for value in block.statements[reward_index + 1:state_index]
    ):
        raise ValueError("V5 give_item과 상태 기록 사이에 remaining_count 실패·중단 분기가 필요합니다.")
    if _positional_literal(set_flag.arguments, 0) != state_key \
            or _positional_literal(set_flag.arguments, 1) is not True:
        raise ValueError("V5 set_flag는 도감 완료 상태를 true로 기록해야 합니다.")
    return (
        _string(_positional_literal(reward.arguments, 0), "give_item item"),
        _positive_int(_named_literal(reward.arguments, "count", 1), "give_item count"),
    )


def _has_item_condition(value: ast.Expression) -> tuple[str, int]:
    if not isinstance(value, ast.CallExpression) or not isinstance(value.callee, ast.NameExpression) \
            or value.callee.name != "has_item":
        raise ValueError("V5 준비 조건은 has_item(...)이어야 합니다.")
    return (
        _string(_positional_literal(value.arguments, 0), "has_item item"),
        _positive_int(_positional_literal(value.arguments, 1), "has_item count"),
    )


def _is_outcome_win(value: ast.Expression, result: str) -> bool:
    return isinstance(value, ast.BinaryExpression) and value.operator == "==" \
        and isinstance(value.left, ast.MemberExpression) \
        and isinstance(value.left.target, ast.NameExpression) \
        and value.left.target.name == result and value.left.member == "outcome" \
        and isinstance(value.right, ast.LiteralExpression) and value.right.value == "win"


def _v4_choice_texts(value: dict[str, Any]) -> tuple[tuple[str, str], ...]:
    return tuple(_localized_document(option["text"], "choice text")[0] for option in value["options"])


def _v4_true_flag(value: dict[str, Any]) -> str:
    conditions = value.get("conditions")
    if not isinstance(conditions, list) or len(conditions) != 1:
        raise ValueError("V4 branch에는 조건 하나가 필요합니다.")
    condition = conditions[0]
    if condition.get("type") != "flag_equals" or condition.get("value") is not True:
        raise ValueError("V4 branch는 true flag 조건이어야 합니다.")
    return _string(condition.get("key"), "branch flag")


def _v4_battle_state(repeat_state: object, victory_state: object) -> str:
    if repeat_state != "cobbleventure:runtime/npc_instance_defeated":
        raise ValueError("V4 battle 반복 조건은 EasyNPC 인스턴스 격파 상태여야 합니다.")
    return _string(victory_state, "victory state")


def _money_reward(value: dict[str, Any]) -> tuple[tuple[str, object], ...]:
    keys = (
        "enabled", "mode", "amount", "fallback_region_level", "per_level", "offset",
        "held_item_bonus", "held_item", "held_item_multiplier", "conditions",
    )
    return tuple((key, tuple(value[key]) if key == "conditions" else value[key]) for key in keys if key in value)


def _localized_document(value: object, name: str) -> tuple[tuple[str, str], ...]:
    if not isinstance(value, dict) or not value or any(
        not isinstance(language, str) or not isinstance(text, str)
        for language, text in value.items()
    ):
        raise ValueError(f"{name}은 다국어 object여야 합니다.")
    return tuple(sorted(value.items()))


def _league_dialogue_documents(value: object) -> tuple[tuple[tuple[str, str], ...], ...]:
    lines = value if isinstance(value, list) else str(value).splitlines()
    documents = tuple(
        (("ko_kr", line.strip()),)
        for line in lines if isinstance(line, str) and line.strip()
    )
    if not documents:
        raise ValueError("리그 관장 대사가 비어 있습니다.")
    return documents


def _text_document(value: ast.Text) -> tuple[tuple[str, str], ...]:
    if isinstance(value, ast.TextLiteral):
        return (("ko_kr", value.value),)
    return tuple(sorted((entry.language, entry.value) for entry in value.entries))


def _flag_condition(value: ast.Expression | None) -> str:
    if not isinstance(value, ast.CallExpression) or not isinstance(value.callee, ast.NameExpression):
        raise ValueError("V5 조건 페이지는 flag(...)여야 합니다.")
    if value.callee.name != "flag":
        raise ValueError("V5 조건 페이지는 flag(...)여야 합니다.")
    return _string(_positional_literal(value.arguments, 0), "flag key")


def _is_remaining_guard(value: ast.Statement, result_name: str) -> bool:
    if not isinstance(value, ast.IfStatement) or not isinstance(value.condition, ast.BinaryExpression):
        return False
    left = value.condition.left
    right = value.condition.right
    checks_remaining = (
        isinstance(left, ast.MemberExpression)
        and isinstance(left.target, ast.NameExpression)
        and left.target.name == result_name
        and left.member == "remaining_count"
        and value.condition.operator == ">"
        and isinstance(right, ast.LiteralExpression)
        and right.value == 0
    )
    stops = any(
        isinstance(statement, ast.CommandStatement)
        and statement.kind is ast.CommandKind.STOP
        for statement in value.then_block.statements
    )
    return checks_remaining and stops


def _named_literal(arguments: tuple[ast.Argument, ...], name: str, default: object) -> object:
    argument = next((value for value in arguments if value.name == name), None)
    return default if argument is None else _literal(argument.value, name)


def _positional_literal(arguments: tuple[ast.Argument, ...], index: int) -> object:
    positional = [value for value in arguments if value.name is None]
    if index >= len(positional):
        raise ValueError("필수 위치 인자가 없습니다.")
    return _literal(positional[index].value, f"argument {index}")


def _literal(value: ast.Expression | None, name: str) -> object:
    if not isinstance(value, ast.LiteralExpression):
        raise ValueError(f"{name}은 literal이어야 합니다.")
    return value.value


def _string(value: object, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{name}은 문자열이어야 합니다.")
    return value


def _positive_int(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValueError(f"{name}은 양의 정수여야 합니다.")
    return value


def _contract_differences(
    contract_type: type, legacy: object, current: object
) -> tuple[str, ...]:
    return tuple(
        f"{field.name}: V4={getattr(legacy, field.name)!r}, V5={getattr(current, field.name)!r}"
        for field in fields(contract_type)
        if getattr(legacy, field.name) != getattr(current, field.name)
    )

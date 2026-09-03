"""Project NPC reward settings into the V5 binding without changing event scripts."""

from __future__ import annotations

from copy import deepcopy
import re


def npc_money_reward(document: dict) -> dict | None:
    source = document.get("npc", {}).get("battle_rewards", {}).get("money")
    if source is None:
        return None  # No implicit payout for gyms or arbitrary custom scripts.
    if not isinstance(source, dict):
        raise ValueError("npc.battle_rewards.money는 객체여야 합니다.")
    money = deepcopy(source)
    money.setdefault("held_item_bonus", True)
    money.setdefault("held_item", "cobblemon:amulet_coin")
    money.setdefault("held_item_multiplier", 2)
    money.setdefault("conditions", [])
    for field in ("enabled", "held_item_bonus"):
        if type(money.get(field)) is not bool:
            raise ValueError(f"npc.battle_rewards.money.{field}는 boolean이어야 합니다.")
    mode = money.get("mode")
    fields = {"held_item_multiplier": (1, 2147483647)}
    if mode == "fixed":
        fields["amount"] = (0, 2147483647)
    elif mode == "regional_level":
        fields.update(fallback_region_level=(1, 100), per_level=(0, 2147483647),
                      offset=(-2147483648, 2147483647))
    else:
        raise ValueError("npc.battle_rewards.money.mode는 fixed 또는 regional_level이어야 합니다.")
    for field, (minimum, maximum) in fields.items():
        value = money.get(field)
        if type(value) is not int or not minimum <= value <= maximum:
            raise ValueError(f"npc.battle_rewards.money.{field}의 정수 범위가 올바르지 않습니다.")
    resource = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
    if not isinstance(money["held_item"], str) or not resource.fullmatch(money["held_item"]):
        raise ValueError("npc.battle_rewards.money.held_item은 리소스 ID여야 합니다.")
    if not isinstance(money["conditions"], list):
        raise ValueError("npc.battle_rewards.money.conditions는 배열이어야 합니다.")
    for condition in money["conditions"]:
        if not isinstance(condition, dict) or condition.get("type") != "flag_equals":
            raise ValueError("NPC V5 상금 조건은 flag_equals만 지원합니다.")
        key = condition.get("key")
        if not isinstance(key, str) or not resource.fullmatch(key):
            raise ValueError("NPC V5 상금 조건의 key는 리소스 ID여야 합니다.")
        value = condition.get("value", True)
        if type(value) is int and value in (0, 1):
            value = bool(value)
        if type(value) is not bool:
            raise ValueError("NPC V5 상금 플래그 조건 값은 boolean이어야 합니다.")
        condition["value"] = value
    return money

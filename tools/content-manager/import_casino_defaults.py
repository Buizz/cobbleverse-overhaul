#!/usr/bin/env python3
"""Extract Cobblemon Casino's product defaults from its Java config sources."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


POOL_BLOCK = re.compile(r'pools\.put\("([^"]+)",\s*List\.of\((.*?)\)\);', re.DOTALL)


def java_int(value: str) -> int:
    return int(value.replace("_", ""))


def source(config_root: Path, relative: str) -> str:
    return (config_root / relative).read_text(encoding="utf-8")


def item_pools(config_root: Path) -> dict[str, list[dict[str, object]]]:
    text = source(config_root, "gachapon/ItemGachaponConfig.java")
    entry = re.compile(r'new GachaEntry\("([^"]+)",\s*([\d_]+),\s*([\d_]+)\)')
    return {
        rarity: [
            {"itemId": item_id, "weight": java_int(weight), "count": java_int(count)}
            for item_id, count, weight in entry.findall(block)
        ]
        for rarity, block in POOL_BLOCK.findall(text)
    }


def pokemon_pools(config_root: Path) -> dict[str, list[dict[str, object]]]:
    text = source(config_root, "gachapon/PokemonGachaponConfig.java")
    entry = re.compile(
        r'new GachaEntry\("([^"]+)",\s*([\d_]+),\s*([\d_]+),\s*"([^"]+)",\s*([\d_]+)\)'
    )
    return {
        rarity: [
            {
                "pokemonId": pokemon_id,
                "level": java_int(level),
                "ivs": java_int(ivs),
                "shiny": shiny,
                "weight": java_int(weight),
            }
            for pokemon_id, level, ivs, shiny, weight in entry.findall(block)
        ]
        for rarity, block in POOL_BLOCK.findall(text)
    }


def plushies(config_root: Path) -> list[dict[str, object]]:
    text = source(config_root, "gachapon/PlushiesGachaponConfig.java")
    entry = re.compile(r'list\.add\(new GachaEntry\("([^"]+)",\s*([\d_]+)\)\);')
    return [{"itemId": item_id, "weight": java_int(weight)} for item_id, weight in entry.findall(text)]


def trades(config_root: Path, filename: str) -> list[dict[str, object]]:
    text = source(config_root, f"npc/{filename}")
    entry = re.compile(
        r'new Trade\("([^"]+)",\s*([\d_]+),\s*"([^"]+)",\s*([\d_]+)\)'
    )
    return [
        {
            "buy_item": buy_item,
            "buy_count": java_int(buy_count),
            "sell_item": sell_item,
            "sell_count": java_int(sell_count),
        }
        for buy_item, buy_count, sell_item, sell_count in entry.findall(text)
    ]


def categories(config_root: Path) -> list[dict[str, object]]:
    text = source(config_root, "npc/CobbledollarsDealerNpcConfig.java")
    category = re.compile(r'new Category\("([^"]+)",\s*List\.of\((.*?)\)\)', re.DOTALL)
    offer = re.compile(r'new Offer\("([^"]+)",\s*(-?[\d_]+),\s*(-?[\d_]+)\)')
    return [
        {
            "name": name,
            "offers": [
                {"item": item, "price": java_int(price), "buyback_price": java_int(buyback)}
                for item, price, buyback in offer.findall(block)
            ],
        }
        for name, block in category.findall(text)
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Cobblemon Casino Java config directory")
    parser.add_argument("output", type=Path, help="Generated JSON file")
    args = parser.parse_args()
    defaults = {
        "gachapon/item_gachapon.json": {"pools": item_pools(args.source)},
        "gachapon/pokemon_gachapon.json": {"pools": pokemon_pools(args.source)},
        "gachapon/plushies_gachapon.json": {"plushies": plushies(args.source)},
        "npc/exchanger.json": {"trades": trades(args.source, "ExchangerNpcConfig.java")},
        "npc/prize_dealer.json": {"trades": trades(args.source, "PrizeDealerNpcConfig.java")},
        "npc/cobbledollars_dealer.json": {"categories": categories(args.source)},
    }
    args.output.write_text(json.dumps(defaults, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "generated",
        args.output,
        "with",
        sum(len(pool) for pool in defaults["gachapon/item_gachapon.json"]["pools"].values()),
        "items,",
        sum(len(pool) for pool in defaults["gachapon/pokemon_gachapon.json"]["pools"].values()),
        "pokemon, and",
        len(defaults["gachapon/plushies_gachapon.json"]["plushies"]),
        "plushies",
    )


if __name__ == "__main__":
    main()

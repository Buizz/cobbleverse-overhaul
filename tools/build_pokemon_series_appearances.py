#!/usr/bin/env python3
"""Refresh regional Pokédex membership and special species flags."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import re
import urllib.request
from pathlib import Path


POKEAPI_BASE = "https://pokeapi.co/api/v2/pokedex"
POKEAPI_SPECIES_BASE = "https://pokeapi.co/api/v2/pokemon-species"
SERIES_POKEDEXES = {
    "kanto": ["kanto"],
    "johto": ["original-johto"],
    "hoenn": ["hoenn"],
    "sinnoh": ["extended-sinnoh"],
    "unova": ["original-unova", "updated-unova"],
    "kalos": ["kalos-central", "kalos-coastal", "kalos-mountain"],
    "alola": ["original-alola", "updated-alola"],
    "galar": ["galar", "isle-of-armor", "crown-tundra"],
    "paldea": ["paldea", "kitakami", "blueberry"],
}


def fetch_pokedex(name: str) -> dict:
    request = urllib.request.Request(
        f"{POKEAPI_BASE}/{name}",
        headers={"User-Agent": "cobbleventure-series-catalog/1.0"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def fetch_species_classification(number: int) -> tuple[int, bool, bool]:
    request = urllib.request.Request(
        f"{POKEAPI_SPECIES_BASE}/{number}",
        headers={"User-Agent": "cobbleventure-species-catalog/1.0"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        data = json.load(response)
    return number, bool(data["is_legendary"]), bool(data["is_mythical"])


def species_number(entry: dict) -> int:
    url = entry["pokemon_species"]["url"]
    match = re.search(r"/pokemon-species/(\d+)/?$", url)
    if not match:
        raise ValueError(f"포켓몬 종 번호를 읽을 수 없습니다: {url}")
    return int(match.group(1))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--catalog",
        type=Path,
        default=Path("content/catalogs/pokemon-habitats.json"),
    )
    args = parser.parse_args()

    membership: dict[str, set[int]] = {}
    for series, pokedexes in SERIES_POKEDEXES.items():
        numbers: set[int] = set()
        for pokedex in pokedexes:
            data = fetch_pokedex(pokedex)
            numbers.update(species_number(entry) for entry in data["pokemon_entries"])
        membership[series] = numbers

    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    dex_numbers = [pokemon["dex_number"] for pokemon in catalog["pokemon"]]
    with ThreadPoolExecutor(max_workers=16) as executor:
        classifications = {
            number: (is_legendary, is_mythical)
            for number, is_legendary, is_mythical in executor.map(
                fetch_species_classification, dex_numbers
            )
        }
    for pokemon in catalog["pokemon"]:
        dex_number = pokemon["dex_number"]
        pokemon["series_appearances"] = [
            series for series in SERIES_POKEDEXES if dex_number in membership[series]
        ]
        pokemon["is_legendary"], pokemon["is_mythical"] = classifications[dex_number]

    source = catalog.setdefault("source", {})
    source["regional_pokedexes"] = {
        "provider": "PokeAPI",
        "endpoint": POKEAPI_BASE,
        "selection": SERIES_POKEDEXES,
        "semantics": "각 월드 시리즈에서 전국도감 해금 전에 사용하는 지역도감 수록 여부의 합집합",
    }
    source["species_classification"] = {
        "provider": "PokeAPI",
        "endpoint": POKEAPI_SPECIES_BASE,
        "fields": ["is_legendary", "is_mythical"],
        "semantics": "전설 및 환상 포켓몬은 일반 서식지 출현에서 제외하고 명시적 강제 출현만 허용",
    }

    temporary = args.catalog.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(args.catalog)

    for series, numbers in membership.items():
        print(f"{series}: {len(numbers)}")
    print(f"legendary: {sum(value[0] for value in classifications.values())}")
    print(f"mythical: {sum(value[1] for value in classifications.values())}")


if __name__ == "__main__":
    main()

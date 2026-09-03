"""Water-method map previews must agree with authored encounter ownership."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import content_manager

PROJECT = Path(__file__).resolve().parents[3] / "content-projects/cobbleventure-main"


class WaterHabitatMapTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.result = content_manager.world_pokemon_map(PROJECT, 1)
        cls.cells = {(entry["q"], entry["r"]): entry for entry in cls.result["locations"]}

    def test_all_vermilion_water_cells_show_tentacool_and_separate_rods(self):
        for cell in ((1, 8), (1, 7), (2, 6), (2, 8), (2, 7), (3, 7)):
            with self.subTest(cell=cell):
                location = self.cells[cell]
                self.assertEqual("surf", location["default_encounter_method"])
                self.assertEqual(["cobblemon:tentacool"], location["pokemon_ids"])
                pools = location["encounters"]
                self.assertEqual(["cobblemon:magikarp"], pools["old_rod"]["pokemon_ids"])
                self.assertEqual({"cobblemon:horsea", "cobblemon:magikarp", "cobblemon:krabby"}, set(pools["good_rod"]["pokemon_ids"]))
                self.assertEqual({"cobblemon:horsea", "cobblemon:shellder", "cobblemon:gyarados", "cobblemon:psyduck"}, set(pools["super_rod"]["pokemon_ids"]))
                self.assertFalse(pools["surf"]["inherit_biome"])

    def test_cerulean_river_uses_psyduck_and_keeps_biome_separate(self):
        for cell in ((7, -4), (8, -5), (9, -4)):
            location = self.cells[cell]
            self.assertEqual(["cobblemon:psyduck"], location["pokemon_ids"])
            self.assertEqual({"min_level": 20, "max_level": 40}, location["custom_level_ranges"]["cobblemon:psyduck"])
            self.assertIn("cobblemon:dragonite", location["base_pokemon_ids"])
            self.assertEqual({"cobblemon:poliwag", "cobblemon:magikarp", "cobblemon:goldeen"}, set(location["encounters"]["good_rod"]["pokemon_ids"]))

    def test_fishing_only_species_remain_in_world_availability(self):
        available = {entry["id"] for entry in self.result["available_pokemon"]}
        self.assertIn("cobblemon:shellder", available)

    def test_explicit_area_wins_over_water_path_and_disabled_pool_stays_empty(self):
        cell = {"q": 0, "r": 0}
        base = {(0, 0): {**cell, "kind": "biome", "biome": "minecraft:river", "pokemon_ids": ["base"], "count": 1}}
        routes = [
            {"id": "path", "surface_style": "water", "cells": [cell], "pokemon_spawns": {"additions": [{"species": "wrong"}]}},
            {"id": "explicit", "encounter_cells": [cell], "pokemon_spawns": {
                "inherit_biome": False, "additions": [{"species": "land"}],
                "encounter_pools": {"surf": {"enabled": False, "additions": [{"species": "fish"}]},
                                    "old_rod": {"inherit_biome": True, "excluded_species": ["base"], "additions": [{"species": "fish"}]},
                                    "headbutt": {"inherit_biome": True, "additions": []}},
            }},
        ]
        for town in (False, True):
            locations = copy.deepcopy(base)
            if town:
                locations[(0, 0)]["kind"] = "settlement"
            content_manager._apply_route_encounter_locations(locations, routes, {}, dict.fromkeys(("base", "wrong", "land", "fish")))
            result = locations[(0, 0)]
            self.assertEqual([], result["pokemon_ids"])
            self.assertEqual("explicit", result["route"])
            self.assertFalse(result["encounters"]["surf"]["enabled"])
            self.assertEqual(["fish"], result["encounters"]["old_rod"]["pokemon_ids"])
            self.assertEqual([], result["encounters"]["headbutt"]["pokemon_ids"])
            self.assertNotIn("good_rod", result["encounters"])
            self.assertEqual(not town, "land" in result["encounters"])


if __name__ == "__main__":
    unittest.main()

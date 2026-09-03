"""Route 1/Viridian must not regain generic test NPC populations.

FireRed references (outdoor object events contain no battle trainers):
https://github.com/pret/pokefirered/blob/master/data/maps/Route1/map.json
https://github.com/pret/pokefirered/blob/master/data/maps/ViridianCity/map.json
Canonical residents are not equivalent to biome-selected ambient NPCs.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

BUILDER_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BUILDER_ROOT))
import build_data_mod

PROJECT_ROOT = BUILDER_ROOT.parents[1] / "content-projects/cobbleventure-main"
CONTENT_ROOT = PROJECT_ROOT / "content"


def read_content(path: str) -> dict:
    return json.loads((CONTENT_ROOT / path).read_text(encoding="utf-8"))


class ViridianNpcPlacementTests(unittest.TestCase):
    def test_route_one_has_no_battle_trainers(self):
        route = read_content("routes/generation_1/route_custom_03.json")
        self.assertEqual([], route["npc_placements"])
        population = route["automatic_npc_placement"]
        self.assertFalse(population["enabled"])
        self.assertEqual(0, population["count"])
        self.assertFalse(population["use_biome_defaults"])
        self.assertEqual([], population["direct_trainers"])
        world = read_content("worlds/generation_1.json")
        connection = next(c for c in world["connections"] if c["id"] == "route_custom_03")
        self.assertEqual(route["id"], connection["route_preset"])
        self.assertNotIn("npc_placements", connection)
        self.assertNotIn("automatic_npc_placement", connection)

    def test_viridian_has_no_generic_residents_or_trainers(self):
        town = read_content("settlements/generation_1/route_01_town.json")
        placement = town["npc_placement"]
        self.assertFalse(placement["auto_place_npcs"])
        self.assertEqual(0, placement["max_ambient_npcs"])
        self.assertEqual([], placement["trainer_slots"])
        population = placement["trainer_population"]
        self.assertFalse(population["enabled"])
        self.assertEqual(0, population["max_active"])
        self.assertFalse(population["use_biome_defaults"])
        self.assertEqual([], population["direct_trainers"])
        self.assertEqual([], placement["zones"])

    def test_builder_does_not_resolve_biome_npcs_for_viridian(self):
        town = read_content("settlements/generation_1/route_01_town.json")
        with mock.patch.object(build_data_mod, "_npc_placement_profiles") as profiles:
            resolved = build_data_mod._resolved_town_auto_npcs(PROJECT_ROOT, town)
            self.assertEqual([], resolved["ambient"])
            self.assertEqual([], resolved["trainers"])
            # Explicit progression NPCs are independent of generic population.
            fixed_ids = set(town["npc_placement"].get("fixed_npcs", []))
            self.assertEqual(fixed_ids, {entry["npc"] for entry in resolved["placements"]})
            profiles.assert_not_called()
        self.assertEqual(0, build_data_mod._requested_town_indoor_npcs(town))

    def test_viridian_keeps_gym_and_service_facilities(self):
        town = read_content("settlements/generation_1/route_01_town.json")
        profile = town["structure_profile"]
        self.assertTrue(profile["gym"]["enabled"])
        self.assertEqual("cobbleventure:gym/viridian", profile["gym"]["gym_id"])
        self.assertTrue(profile["pokemon_center_enabled"])
        self.assertEqual("pokemart", profile["commercial_center"])
        self.assertIn("bca:pokemart_shopkeeper", profile["shop_configuration"]["vendor_units"])


if __name__ == "__main__":
    unittest.main()

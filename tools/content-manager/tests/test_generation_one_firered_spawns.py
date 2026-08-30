import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CONTENT = ROOT / "content-projects" / "cobbleventure-main" / "content"


class GenerationOneFireRedSpawnTests(unittest.TestCase):
    """Locks FireRed encounter pools used by newly separated generation-one areas."""

    @staticmethod
    def load(relative: str) -> dict:
        return json.loads((CONTENT / relative).read_text(encoding="utf-8"))

    @staticmethod
    def by_species(entries: list[dict]) -> dict[str, tuple[int, int, int]]:
        return {
            entry["species"].split(":", 1)[1]: (
                entry["min_level"],
                entry["max_level"],
                entry["weight"],
            )
            for entry in entries
        }

    def test_cerulean_north_road_uses_combined_route_24_and_25_pool(self) -> None:
        route = self.load("routes/generation_1/route_custom_19.json")
        spawns = route["pokemon_spawns"]

        self.assertFalse(spawns["inherit_biome"])
        self.assertEqual(
            {
                "weedle": (7, 8, 20),
                "caterpie": (7, 8, 20),
                "pidgey": (11, 13, 15),
                "oddish": (12, 14, 25),
                "abra": (8, 13, 15),
                "kakuna": (8, 9, 4),
                "metapod": (8, 9, 1),
            },
            self.by_species(spawns["additions"]),
        )
        self.assertEqual(100, sum(entry["weight"] for entry in spawns["additions"]))
        self.assertTrue(next(entry for entry in spawns["additions"] if entry["species"].endswith(":kakuna"))["spawn_as_evolved"])
        self.assertTrue(next(entry for entry in spawns["additions"] if entry["species"].endswith(":metapod"))["spawn_as_evolved"])

    def test_cerulean_north_road_reaches_bill_house_anchor(self) -> None:
        world = self.load("worlds/generation_1.json")
        route = next(connection for connection in world["connections"] if connection["id"] == "route_custom_19")
        bill_house = next(obj for obj in world["objects"] if obj["id"] == "bill_house")

        self.assertEqual(bill_house["anchor"], route["anchors"][-1])
        self.assertIn(bill_house["anchor"], route["cells"])

    def test_power_plant_uses_firered_land_encounter_pool(self) -> None:
        dungeon = self.load("dungeons/generation_1/rocket_power_plant.json")
        encounters = dungeon["random_encounters"]

        self.assertTrue(encounters["enabled"])
        self.assertEqual(
            {
                "voltorb": (22, 25, 30),
                "magnemite": (22, 25, 30),
                "pikachu": (22, 26, 25),
                "magneton": (31, 34, 10),
                "electabuzz": (32, 35, 5),
            },
            self.by_species(encounters["additions"]),
        )
        self.assertEqual(100, sum(entry["weight"] for entry in encounters["additions"]))

    def test_league_approach_routes_and_victory_road_use_separate_pools(self) -> None:
        expected = {
            "route_custom_20": {
                "rattata": (2, 5, 45),
                "mankey": (2, 5, 45),
                "spearow": (3, 5, 10),
            },
            "route_custom_21": {
                "mankey": (32, 34, 30),
                "fearow": (40, 44, 25),
                "ekans": (32, 34, 20),
                "spearow": (32, 34, 15),
                "primeape": (42, 42, 5),
                "arbok": (44, 44, 5),
            },
            "route_custom_05": {
                "machop": (32, 34, 60),
                "geodude": (32, 34, 60),
                "onix": (40, 48, 80),
                "zubat": (32, 34, 30),
                "arbok": (44, 46, 15),
                "golbat": (44, 46, 15),
                "marowak": (44, 48, 15),
                "machoke": (44, 48, 15),
                "primeape": (42, 42, 10),
            },
        }

        for slug, encounter_pool in expected.items():
            with self.subTest(route=slug):
                route = self.load(f"routes/generation_1/{slug}.json")
                spawns = route["pokemon_spawns"]
                self.assertFalse(spawns["inherit_biome"])
                self.assertEqual(encounter_pool, self.by_species(spawns["additions"]))

        route_22 = self.load("routes/generation_1/route_custom_20.json")
        route_23 = self.load("routes/generation_1/route_custom_21.json")
        victory_road = self.load("routes/generation_1/route_custom_05.json")
        self.assertEqual(100, sum(entry["weight"] for entry in route_22["pokemon_spawns"]["additions"]))
        self.assertEqual(100, sum(entry["weight"] for entry in route_23["pokemon_spawns"]["additions"]))
        self.assertEqual(300, sum(entry["weight"] for entry in victory_road["pokemon_spawns"]["additions"]))
        self.assertEqual("road", victory_road["route_type"])
        self.assertFalse(route_22["automatic_npc_placement"]["enabled"])
        self.assertFalse(route_23["automatic_npc_placement"]["enabled"])
        self.assertEqual(12, victory_road["automatic_npc_placement"]["count"])

    def test_existing_league_road_cells_are_partitioned_with_shared_junctions(self) -> None:
        world = self.load("worlds/generation_1.json")
        connections = {
            connection["id"]: connection
            for connection in world["connections"]
            if connection["id"] in {"route_custom_20", "route_custom_21", "route_custom_05"}
        }
        expected_cells = {
            (-4, 4), (-5, 4), (-6, 4), (-7, 4), (-8, 4),
            (-8, 3), (-7, 2), (-7, 1), (-7, 0), (-6, -1), (-6, -2),
        }
        claimed_cells = [
            (cell["q"], cell["r"])
            for connection in connections.values()
            for cell in connection["cells"]
        ]

        self.assertEqual({"route_custom_20", "route_custom_21", "route_custom_05"}, set(connections))
        self.assertEqual(expected_cells, set(claimed_cells))
        self.assertEqual(len(expected_cells) + 2, len(claimed_cells))
        self.assertEqual(2, claimed_cells.count((-6, 4)))
        self.assertEqual(2, claimed_cells.count((-8, 4)))
        self.assertEqual("road", connections["route_custom_05"]["surface_style"])
        self.assertNotIn("from", connections["route_custom_05"])
        self.assertEqual(
            {(-8, 4), (-8, 3), (-7, 2), (-7, 1), (-7, 0), (-6, -1), (-6, -2)},
            {(cell["q"], cell["r"]) for cell in connections["route_custom_05"]["cells"]},
        )

        route_order = [connection["id"] for connection in world["connections"]]
        self.assertLess(route_order.index("route_custom_20"), route_order.index("route_custom_21"))
        self.assertLess(route_order.index("route_custom_21"), route_order.index("route_custom_05"))


if __name__ == "__main__":
    unittest.main()

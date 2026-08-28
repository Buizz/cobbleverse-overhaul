import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CONTENT = ROOT / "content-projects" / "cobbleventure-main" / "content"


class GenerationOneWildEncounterTests(unittest.TestCase):
    """Locks the Red/Blue union imported from pret/pokered@0cd19d3 data/wild/maps."""

    ROUTES = {
        "route_custom_01": {"ekans": (6, 12), "rattata": (8, 12), "sandshrew": (6, 12), "spearow": (8, 12)},
        "route_custom_02": {"jigglypuff": (3, 7), "pidgey": (6, 8), "spearow": (5, 8)},
        "route_custom_03": {"pidgey": (2, 5), "rattata": (2, 4)},
        "route_custom_04": {"caterpie": (3, 5), "pidgey": (3, 5), "rattata": (2, 5), "weedle": (3, 5)},
        "route_viridian_forest_north": {"caterpie": (3, 5), "pidgey": (3, 5), "rattata": (2, 5), "weedle": (3, 5)},
        "route_custom_06": {"bellsprout": (19, 22), "growlithe": (18, 20), "mankey": (17, 20), "meowth": (17, 20), "oddish": (19, 22), "pidgey": (19, 22), "vulpix": (18, 20)},
        "route_custom_07": {"doduo": (18, 28), "fearow": (25, 29), "raticate": (23, 29), "rattata": (18, 22), "spearow": (20, 22)},
        "route_custom_08": {"tentacool": (5, 40)},
        "route_custom_09": {"tentacool": (5, 40)},
        "route_custom_10": {"bellsprout": (22, 26), "ditto": (23, 26), "gloom": (28, 30), "oddish": (22, 26), "pidgeotto": (28, 30), "pidgey": (23, 27), "venonat": (24, 28), "weepinbell": (28, 30)},
        "route_custom_11": {"bellsprout": (13, 16), "mankey": (10, 16), "meowth": (10, 16), "oddish": (13, 16), "pidgey": (13, 16)},
        "route_custom_12": {"bellsprout": (13, 16), "mankey": (10, 16), "meowth": (10, 16), "oddish": (13, 16), "pidgey": (13, 16)},
        "route_custom_13": {"ekans": (17, 19), "growlithe": (15, 18), "mankey": (18, 20), "meowth": (18, 20), "pidgey": (18, 20), "sandshrew": (17, 19), "vulpix": (15, 18)},
        "route_custom_14": {"bellsprout": (22, 26), "drowzee": (9, 15), "ekans": (12, 15), "gloom": (28, 30), "oddish": (22, 26), "pidgey": (23, 27), "sandshrew": (12, 15), "spearow": (13, 17), "venonat": (24, 26), "weepinbell": (28, 30)},
        "route_custom_15": {"gyarados": (25, 30), "horsea": (20, 26), "magikarp": (15, 24), "seadra": (27, 31), "tentacool": (22, 27), "tentacruel": (28, 32)},
        "route_custom_16": {"ekans": (11, 17), "rattata": (14, 17), "sandshrew": (11, 17), "spearow": (13, 17), "voltorb": (14, 17)},
        "route_custom_17": {"ekans": (11, 17), "sandshrew": (11, 17), "spearow": (13, 17), "voltorb": (14, 17)},
        "route_custom_18": {"bellsprout": (22, 26), "drowzee": (9, 15), "ekans": (12, 15), "gloom": (28, 30), "oddish": (22, 26), "pidgey": (23, 27), "sandshrew": (12, 15), "spearow": (13, 17), "venonat": (24, 26), "weepinbell": (28, 30)},
    }

    AREAS = {
        "forests/generation_1/viridian_forest.json": {
            "caterpie": (3, 5), "kakuna": (4, 6), "metapod": (4, 6),
            "pikachu": (3, 5), "weedle": (3, 5),
        },
        "caves/generation_1/mt_moon.json": {
            "clefairy": (8, 12), "geodude": (7, 10), "paras": (8, 12), "zubat": (6, 12),
        },
        "caves/generation_1/rock_tunnel.json": {
            "geodude": (16, 18), "machop": (15, 17), "onix": (13, 17), "zubat": (15, 18),
        },
    }

    @staticmethod
    def load(relative: str) -> dict:
        return json.loads((CONTENT / relative).read_text(encoding="utf-8"))

    @staticmethod
    def ranges(entries: list[dict]) -> dict[str, tuple[int, int]]:
        return {
            entry["species"].split(":", 1)[1]: (entry["min_level"], entry["max_level"])
            for entry in entries
        }

    def assert_red_blue_pool(self, settings: dict, expected: dict[str, tuple[int, int]]) -> None:
        self.assertFalse(settings["inherit_biome"])
        self.assertEqual(expected, self.ranges(settings["additions"]))
        self.assertEqual(expected, self.ranges(settings["level_overrides"]))

    def test_routes_use_red_blue_union_with_original_level_ranges(self) -> None:
        for slug, expected in self.ROUTES.items():
            with self.subTest(route=slug):
                route = self.load(f"routes/generation_1/{slug}.json")
                self.assert_red_blue_pool(route["pokemon_spawns"], expected)

    def test_forests_and_caves_use_red_blue_union_with_original_level_ranges(self) -> None:
        for relative, expected in self.AREAS.items():
            with self.subTest(area=relative):
                area = self.load(relative)
                self.assert_red_blue_pool(area["random_encounters"], expected)

    def test_ocean_bridge_has_surf_and_all_three_rod_pools(self) -> None:
        route = self.load("routes/generation_1/route_custom_15.json")
        pools = route["pokemon_spawns"]["encounter_pools"]
        self.assertEqual({"surf", "old_rod", "good_rod", "super_rod"}, set(pools))
        self.assertEqual(
            set(self.ROUTES["route_custom_15"]),
            set(self.ranges(pools["surf"]["additions"])),
        )
        self.assertEqual(
            {"magikarp", "goldeen"},
            set(self.ranges(pools["old_rod"]["additions"])),
        )
        self.assertEqual(
            {"gyarados", "seadra", "seaking", "staryu", "tentacruel"},
            set(self.ranges(pools["super_rod"]["additions"])),
        )


if __name__ == "__main__":
    unittest.main()

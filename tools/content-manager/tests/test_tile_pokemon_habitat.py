"""Tile habitat sources and time metadata stay visible in the world preview."""
import copy
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import content_manager

PROJECT = Path(__file__).resolve().parents[3] / "content-projects/cobbleventure-main"


class TilePokemonHabitatTests(unittest.TestCase):
    def test_web_editor_exposes_tile_sources_and_day_night_controls(self):
        web_root = Path(__file__).resolve().parents[1] / "web"
        html = (web_root / "index.html").read_text(encoding="utf-8")
        script = (web_root / "app.js").read_text(encoding="utf-8")

        self.assertIn('name="pokemonHabitatSource"', html)
        self.assertIn('name="pokemonHabitatRoute"', html)
        self.assertIn('openRoutePokemonDialog("tile")', script)
        self.assertIn('data-${target}-pokemon-time', script)

    def test_tiles_can_inherit_routes_or_use_custom_time_pools(self):
        world = copy.deepcopy(content_manager.load_world_layout(PROJECT, 1))
        inherited_tile, custom_tile = world["tiles"][:2]
        inherited_tile["pokemon_habitat"] = {
            "source": "route", "route_id": "route_custom_03",
        }
        custom_tile["pokemon_habitat"] = {
            "source": "custom",
            "pokemon_spawns": {
                "inherit_biome": False,
                "excluded_species": [],
                "additions": [{
                    "species": "cobblemon:ekans", "min_level": 4,
                    "max_level": 6, "weight": 1,
                }],
                "level_overrides": [{
                    "species": "cobblemon:ekans", "min_level": 4,
                    "max_level": 6,
                }],
                "time_overrides": [{"species": "cobblemon:ekans", "time": "night"}],
            },
        }

        with patch.object(content_manager, "load_world_layout", return_value=world):
            result = content_manager.world_pokemon_map(PROJECT, 1)
        cells = {(entry["q"], entry["r"]): entry for entry in result["locations"]}
        inherited = cells[(inherited_tile["q"], inherited_tile["r"])]
        custom = cells[(custom_tile["q"], custom_tile["r"])]

        self.assertEqual("route_custom_03", inherited["route"])
        self.assertEqual(
            {"cobblemon:pidgey", "cobblemon:rattata"},
            set(inherited["pokemon_ids"]),
        )
        self.assertEqual(["cobblemon:ekans"], custom["pokemon_ids"])
        self.assertEqual({"cobblemon:ekans": "night"}, custom["time_overrides"])


if __name__ == "__main__":
    unittest.main()

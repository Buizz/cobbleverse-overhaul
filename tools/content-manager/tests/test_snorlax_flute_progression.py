import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CONTENT = ROOT / "content-projects/cobbleventure-main/content"
RESOURCES = ROOT / "projects/cobbleventure-world-bootstrap/src/main/resources"
FLUTE = "cobbleventure_bootstrap:poke_flute"


def read(relative):
    return json.loads((CONTENT / relative).read_text(encoding="utf-8"))


class SnorlaxFluteProgressionTests(unittest.TestCase):
    def test_sleeping_snorlax_blocks_eastern_end_of_vermilion_road(self):
        world = read("worlds/generation_1.json")
        road = next(value for value in world["connections"] if value["id"] == "route_custom_14")
        gate = next(value for value in world["objects"] if value["id"] == "vermilion_east_snorlax")
        self.assertEqual("cobbleventure:settlement/vermilion_city", road["from"])
        self.assertEqual(road["cells"][-1], gate["anchor"])
        self.assertEqual({"q": 6, "r": 6}, gate["anchor"])
        properties = gate["properties"]
        self.assertEqual("east", properties["facing"])
        self.assertEqual("pokemon", properties["center_placement"])
        self.assertEqual("natural", properties["surrounding_type"])
        pokemon = properties["pokemon"]
        self.assertEqual("cobblemon:snorlax", pokemon["species"])
        self.assertEqual(30, pokemon["level"])
        self.assertEqual("sleep", pokemon["pose"])
        self.assertGreaterEqual(pokemon["collision"]["width"] * pokemon["scale"], properties["passage_width"])
        self.assertEqual(FLUTE, pokemon["activation_item"])
        self.assertIn({"type": "item", "item": FLUTE, "count": 1}, pokemon["activation_conditions"])

    def test_tower_first_clear_adds_one_flute_without_changing_repeat_rewards(self):
        tower = read("dungeons/generation_1/rocket_pokemon_tower.json")
        self.assertEqual("cobbleventure:dungeon/pokemon_tower_first_clear", tower["rewards"]["first_clear_table"])
        self.assertEqual("cobbleventure:dungeon/rocket_power_plant_repeat_clear", tower["rewards"]["repeat_table"])
        reward = read("loot_tables/cobbleventure/dungeon/pokemon_tower_first_clear.json")
        self.assertEqual([
            {"rolls": 1, "entries": [{"type": "minecraft:loot_table", "value": "cobbleventure:dungeon/rocket_power_plant_first_clear"}]},
            {"rolls": 1, "entries": [{"type": "minecraft:item", "name": FLUTE}]},
        ], reward["pools"])
        self.assertNotIn(FLUTE, json.dumps(read("loot_tables/cobbleventure/dungeon/rocket_power_plant_repeat_clear.json")))

    def test_flute_acquisition_is_distinct_from_boss_victory_and_snorlax_clear(self):
        item = next(value for value in read("catalogs/important-items.json")["items"] if value["item"] == FLUTE)
        variables = {value["id"]: value for value in read("catalogs/game-definitions.json")["variables"]}
        tower = read("dungeons/generation_1/rocket_pokemon_tower.json")
        gate = next(value for value in read("worlds/generation_1.json")["objects"] if value["id"] == "vermilion_east_snorlax")
        clear = gate["properties"]["pokemon"]["completion_flag"]
        self.assertEqual(3, len({item["acquisition_flag"], clear, tower["completion"]["victory_flag"]}))
        for flag in (item["acquisition_flag"], clear):
            self.assertEqual("player", variables[flag]["scope"])
            self.assertFalse(variables[flag]["default"])
        tag = json.loads((RESOURCES / "data/cobbleventure_player_menu/tags/item/key_items.json").read_text(encoding="utf-8"))
        self.assertIn(FLUTE, tag["values"])
        self.assertFalse(tag["replace"])


if __name__ == "__main__":
    unittest.main()

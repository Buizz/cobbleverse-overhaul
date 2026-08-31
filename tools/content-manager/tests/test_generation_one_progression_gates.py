import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORLD = ROOT / "content-projects/cobbleventure-main/content/worlds/generation_1.json"


class GenerationOneProgressionGateTests(unittest.TestCase):
    def test_starter_town_gate_requires_partner_and_pokenav(self) -> None:
        world = json.loads(WORLD.read_text(encoding="utf-8"))
        gate = next(item for item in world["objects"] if item["id"] == "starter_town_north_gate")

        self.assertIn({
            "type": "party_count", "operator": ">=", "value": 1,
        }, gate["properties"]["conditions"])
        self.assertIn({
            "type": "flag_equals",
            "key": "cobbleventure:flag/story/pokenav_received",
            "value": True,
        }, gate["properties"]["conditions"])

    def test_viridian_west_gate_requires_all_kanto_badges(self) -> None:
        world = json.loads(WORLD.read_text(encoding="utf-8"))
        gate = next(item for item in world["objects"] if item["id"] == "viridian_gate")
        properties = gate["properties"]

        self.assertEqual("all", properties["condition_mode"])
        self.assertEqual(
            {
                "cobbleventure:badge/kanto/boulder",
                "cobbleventure:badge/kanto/cascade",
                "cobbleventure:badge/kanto/thunder",
                "cobbleventure:badge/kanto/rainbow",
                "cobbleventure:badge/kanto/soul",
                "cobbleventure:badge/kanto/marsh",
                "cobbleventure:badge/kanto/volcano",
                "cobbleventure:badge/kanto/earth",
            },
            {condition["badge"] for condition in properties["conditions"]},
        )
        self.assertEqual(
            "easy_npc:preset/encounter/feature_map_guide__v5.npc.snbt",
            properties["npc"],
        )


if __name__ == "__main__":
    unittest.main()

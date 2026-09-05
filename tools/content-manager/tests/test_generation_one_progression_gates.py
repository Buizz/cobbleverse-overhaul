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
        self.assertNotIn("pokefinder", json.dumps(gate))

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
            "easy_npc:preset/encounter/viridian_gatekeeper__v5.npc.snbt",
            properties["npc"],
        )

    def test_map_guide_is_fixed_inside_viridian_and_never_a_gate_npc(self) -> None:
        content = WORLD.parent.parent
        world = json.loads(WORLD.read_text(encoding="utf-8"))
        for item in world["objects"]:
            self.assertNotIn("feature_map_guide", item.get("properties", {}).get("npc", ""))
        placements = []
        for path in (content / "settlements").rglob("*.json"):
            settlement = json.loads(path.read_text(encoding="utf-8"))
            if "cobbleventure:npc/rewards/feature_map_guide" in settlement.get("npc_placement", {}).get("fixed_npcs", []):
                placements.append(settlement["id"])
        self.assertEqual(["cobbleventure:settlement/route_01_town"], placements)

    def test_teleport_guide_is_fixed_inside_cerulean_and_never_a_gate_npc(self) -> None:
        content = WORLD.parent.parent
        world = json.loads(WORLD.read_text(encoding="utf-8"))
        for item in world["objects"]:
            self.assertNotIn("feature_teleport_guide", item.get("properties", {}).get("npc", ""))
        placements = []
        for path in (content / "settlements").rglob("*.json"):
            settlement = json.loads(path.read_text(encoding="utf-8"))
            if "cobbleventure:npc/rewards/feature_teleport_guide" in settlement.get("npc_placement", {}).get("fixed_npcs", []):
                placements.append(settlement["id"])
        self.assertEqual(["cobbleventure:settlement/cerulean_city"], placements)


if __name__ == "__main__":
    unittest.main()

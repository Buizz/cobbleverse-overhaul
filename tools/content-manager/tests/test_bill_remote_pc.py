import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CONTENT = ROOT / "content-projects" / "cobbleventure-main" / "content"


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


class BillRemotePcTests(unittest.TestCase):
    def test_bill_house_is_placed_at_the_end_of_route_25(self) -> None:
        world = load(CONTENT / "worlds" / "generation_1.json")
        route = next(
            route for route in world["connections"]
            if route["id"] == "route_custom_19"
        )
        bill_house = next(
            obj for obj in world["objects"] if obj["id"] == "bill_house"
        )

        self.assertEqual(route["anchors"][-1], bill_house["anchor"])
        self.assertEqual("structure", bill_house["type"])
        self.assertEqual(
            "cobbleventure:houses/bill_house", bill_house["resource"]
        )
        self.assertEqual(3, bill_house["rotation"])
        self.assertEqual("road_anchor", bill_house["properties"]["placement_anchor"])
        metadata = load(CONTENT / "structures" / "houses" / "bill_house.structure.json")
        door = next(anchor for anchor in metadata["anchors"] if anchor["id"] == "door")
        self.assertEqual([14, 1, 2], door["safe_spawn"])

    def test_bill_house_uses_a_private_interior_npc_assignment(self) -> None:
        settings = load(CONTENT / "catalogs" / "building-settings.json")["buildings"]
        bill_house = settings["cobbleventure:houses/bill_house"]
        shared_house = settings["cobbleventure:houses/one_story_gable"]

        self.assertEqual(
            "cobbleventure:npc/rewards/feature_pc_technician",
            bill_house["fixed_npcs"]["room_1:npc1"],
        )
        self.assertEqual({}, shared_house["fixed_npcs"])
        self.assertFalse(bill_house["citizen_placement_allowed"])
        self.assertTrue((CONTENT / "structures" / "houses" / "bill_house.nbt").is_file())

    def test_bill_explains_pc_and_unlocks_the_remote_pc_feature(self) -> None:
        source = load(CONTENT / "source" / "rewards" / "feature_pc_technician.json")
        commands = source["events"][0]["commands"]

        self.assertEqual("이수재", source["npc"]["display_name"]["ko_kr"])
        self.assertEqual("cves_v5", source["event_runtime"]["engine"])
        self.assertTrue(any(
            command.get("type") == "dialogue"
            and "박스" in command.get("text", {}).get("ko_kr", "")
            for command in commands
        ))
        self.assertIn(
            {"type": "unlock_feature", "feature": "pc"}, commands
        )
        self.assertIn(
            {
                "type": "set_flag",
                "key": "cobbleventure:flag/rewards/feature/pc",
                "value": True,
            },
            commands,
        )


if __name__ == "__main__":
    unittest.main()

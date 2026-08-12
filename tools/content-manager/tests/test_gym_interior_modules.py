import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "content-manager"))

import content_manager  # noqa: E402
import gym_interior_modules  # noqa: E402


class InteriorSpaceTests(unittest.TestCase):
    def test_existing_interiors_are_exposed_as_generic_library(self) -> None:
        payload = content_manager.interior_spaces_payload(ROOT)
        self.assertGreaterEqual(len(payload["spaces"]), 3)
        self.assertTrue(all(space["structure"].startswith("cobbleventure:interiors/") for space in payload["spaces"]))

    def test_web_creator_makes_unassigned_generic_nbt(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            created = content_manager.create_interior_space(root, {
                "id": "web_room", "width": 24, "depth": 28,
                "floor_height": 6, "floors": 2,
            })
            self.assertEqual([24, 12, 28], created["size"])
            self.assertEqual("cobbleventure:interiors/web_room", created["structure"])
            nbt = root / "content/structures/interiors/web_room.nbt"
            metadata = json.loads(nbt.with_suffix(".structure.json").read_text(encoding="utf-8"))
            self.assertEqual((24, 12, 28), content_manager.read_minecraft_structure_size(nbt.read_bytes()))
            self.assertEqual([], metadata["anchors"])

    def test_building_connects_named_door_to_generic_interior_arrival(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            exterior = root / "content/structures/placeholder/test_building.nbt"
            exterior.parent.mkdir(parents=True)
            exterior.write_bytes(gym_interior_modules.blank_module_nbt(root, 16, 16, 8, 1))
            exterior.with_suffix(".structure.json").write_text(json.dumps({
                "schema_version": 1,
                "anchors": [{
                    "type": "door", "id": "front_door", "label": "front_door",
                    "position": [8, 1, 0], "safe_spawn": [8, 1, 2],
                    "door_facing": "north", "safe_side": "south",
                }, {
                    "type": "exterior_spawn", "id": "outside",
                    "position": [8, 1, 3], "facing": "south",
                }],
            }), encoding="utf-8")
            interior = content_manager.create_interior_space(root, {
                "id": "shared_lobby", "width": 16, "depth": 16,
                "floor_height": 8, "floors": 1,
            })
            interior_path = root / "content/structures/interiors/shared_lobby.nbt"
            interior_path.with_suffix(".structure.json").write_text(json.dumps({
                "schema_version": 1,
                "anchors": [{
                    "type": "arrival", "id": "entrance", "label": "entrance",
                    "position": [8, 1, 3], "facing": "south",
                }],
                "interior": {"id": "shared_lobby", "width": 16, "depth": 16, "floor_height": 8, "floors": 1},
            }), encoding="utf-8")

            issues = content_manager.save_building_settings(root, {
                "schema_version": 1,
                "buildings": {"cobbleventure:placeholder/test_building": {
                    "fixed_npcs": {}, "citizen_placement_allowed": False,
                    "interiors": [{"key": "lobby", "structure": interior["structure"]}],
                    "door_routes": {"exterior:front_door": {"space": "lobby", "arrival": "entrance"}},
                }},
            })
            self.assertFalse([issue for issue in issues if issue.level == "error"])
            saved = json.loads((root / "content/catalogs/building-settings.json").read_text(encoding="utf-8"))
            self.assertEqual("lobby", saved["buildings"]["cobbleventure:placeholder/test_building"]["interiors"][0]["key"])


if __name__ == "__main__":
    unittest.main()

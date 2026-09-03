from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import unittest

MODULE_PATH = Path(__file__).parents[1] / "cave_road_anchor.py"
SPEC = importlib.util.spec_from_file_location("facility_radar_road_anchor", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
anchors = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(anchors)
ROOT = Path(__file__).resolve().parents[3]
CONTENT = ROOT / "content-projects/cobbleventure-main/content"


class FacilityRadarAnchorTests(unittest.TestCase):
    def test_open_facilities_have_a_unique_entrance_without_portal_routes(self):
        settings = json.loads((CONTENT / "catalogs/building-settings.json").read_text(
            encoding="utf-8"))
        for name in ("pokemon_center", "pokemart"):
            with self.subTest(facility=name):
                metadata = json.loads((CONTENT / f"structures/facilities/{name}.structure.json")
                    .read_text(encoding="utf-8"))
                self.assertFalse(any(anchor["type"] == "door"
                                     for anchor in metadata["anchors"]))
                self.assertEqual({}, settings["buildings"][f"cobbleventure:facilities/{name}"]
                                 ["door_routes"])
                self.assertEqual(1, len(anchors.road_anchors(
                    CONTENT / f"structures/facilities/{name}.nbt")))


if __name__ == "__main__":
    unittest.main()

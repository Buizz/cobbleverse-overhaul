import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class DaycareDistributionTests(unittest.TestCase):
    def test_daycare_attendant_is_inside_and_paddock_stays_outside(self) -> None:
        content = ROOT / "content-projects/cobbleventure-main/content"
        settings = json.loads(
            (content / "catalogs/building-settings.json").read_text(encoding="utf-8")
        )["buildings"]["cobbleventure:placeholder/daycare"]
        self.assertEqual(
            {"room_1:npc": "cobbleventure:npc/facilities/daycare_attendant"},
            settings["fixed_npcs"],
        )
        self.assertEqual(
            [{"key": "room_1", "structure": "cobbleventure:interiors/daycare"}],
            settings["interiors"],
        )
        self.assertEqual({"exterior:door1", "exterior:door2"}, set(settings["door_routes"]))
        sidecar = json.loads(
            (content / "structures/placeholder/daycare.structure.json").read_text(encoding="utf-8")
        )
        anchors = {anchor["id"]: anchor for anchor in sidecar["anchors"]}
        self.assertIn("paddock", anchors)
        self.assertNotIn("attendant", {anchor["id"] for anchor in sidecar["anchors"]})
        paddock = anchors["paddock"]["position"]
        door2 = anchors["door2"]
        self.assertEqual("west", door2["safe_side"])
        self.assertEqual(door2["position"][2], paddock[2])
        self.assertGreaterEqual(door2["position"][0] - paddock[0], 6)

    def test_cobbreeding_is_pinned_and_vendored(self) -> None:
        lock = json.loads((ROOT / "pack/dependencies.lock.json").read_text(encoding="utf-8"))
        entry = next(item for item in lock["content_packs"] if item["id"] == "cobbreeding")
        self.assertEqual("2.2.2", entry["version"])
        self.assertEqual("neoforge_mod", entry["artifact_format"])
        self.assertEqual(
            {"project_id": "ItmVb4zY", "version_id": "9bPk2DC3"},
            entry["modrinth"],
        )
        artifact = ROOT / entry["vendored_path"]
        self.assertTrue(artifact.is_file())
        payload = artifact.read_bytes()
        self.assertEqual(entry["sha1"], hashlib.sha1(payload).hexdigest())
        self.assertEqual(entry["sha512"], hashlib.sha512(payload).hexdigest())

    def test_development_pack_syncs_cobbreeding_before_validation(self) -> None:
        build = (ROOT / "build.bat").read_text(encoding="utf-8")
        pack_section = build.split(":pack\n", 1)[1].split(":pack_release\n", 1)[0]
        self.assertLess(
            pack_section.index("syncCobbreedingDevelopmentJar"),
            pack_section.index(" validate --root"),
        )


if __name__ == "__main__":
    unittest.main()

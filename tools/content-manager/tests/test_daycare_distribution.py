import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class DaycareDistributionTests(unittest.TestCase):
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

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "pack_builder.py"
SPEC = importlib.util.spec_from_file_location("pack_builder", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
pack_builder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = pack_builder
SPEC.loader.exec_module(pack_builder)


class PackBuilderTests(unittest.TestCase):
    def _fixture(self, root: Path) -> Path:
        overrides = root / "pack" / "overrides" / "smoke" / "config"
        overrides.mkdir(parents=True)
        (overrides / "marker.txt").write_text("smoke\n", encoding="utf-8")
        profile_path = root / "pack" / "profiles" / "smoke.json"
        profile_path.parent.mkdir(parents=True)
        profile = {
            "schema_version": 1,
            "profile_id": "smoke",
            "name": "Smoke",
            "version": "0.0.1",
            "author": "Test",
            "minecraft": {
                "version": "1.21.1",
                "mod_loader": {
                    "id": "neoforge-21.1.248",
                    "primary": True,
                },
            },
            "files": [],
            "overrides_directory": "pack/overrides/smoke",
            "output": "dist/smoke.zip",
        }
        profile_path.write_text(json.dumps(profile), encoding="utf-8")
        return profile_path.relative_to(root)

    def test_builds_and_validates_minimal_curseforge_zip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile_path = self._fixture(root)
            output = pack_builder.build_pack(root, profile_path)
            manifest = pack_builder.validate_pack(
                output,
                root=root,
                profile_path=profile_path,
            )
            with zipfile.ZipFile(output) as archive:
                names = archive.namelist()
            self.assertEqual("minecraftModpack", manifest["manifestType"])
            self.assertEqual([], manifest["files"])
            self.assertIn("manifest.json", names)
            self.assertIn("overrides/", names)
            self.assertIn("overrides/config/marker.txt", names)
            self.assertTrue(output.with_name(output.name + ".sha256").is_file())

    def test_rejects_output_outside_dist(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile_path = self._fixture(root)
            absolute_profile = root / profile_path
            profile = json.loads(absolute_profile.read_text(encoding="utf-8"))
            profile["output"] = "smoke.zip"
            absolute_profile.write_text(json.dumps(profile), encoding="utf-8")
            with self.assertRaises(pack_builder.PackError):
                pack_builder.load_profile(root, profile_path)


if __name__ == "__main__":
    unittest.main()

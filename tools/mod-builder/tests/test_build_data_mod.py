from __future__ import annotations

import importlib.util
import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "build_data_mod.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("build_data_mod", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
build_data_mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = build_data_mod
SPEC.loader.exec_module(build_data_mod)


class DataModBuilderTests(unittest.TestCase):
    def _fixture(self, root: Path) -> Path:
        source = root / build_data_mod.SOURCE
        source_entries = build_data_mod.REQUIRED_ENTRIES
        for name in source_entries:
            path = source / Path(name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"fixture: {name}\n", encoding="utf-8")
        config = root / build_data_mod.STARTER_TOWN_CONFIG
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(
            json.dumps({"structure_profile": {"gym_theme": "rock"}}),
            encoding="utf-8",
        )
        return source

    def test_builds_deterministic_gym_resource(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)

            output = build_data_mod.build(root)
            first = output.read_bytes()
            output = build_data_mod.build(root)

            self.assertEqual(first, output.read_bytes())
            gym = gzip.decompress(output.read_bytes())
            self.assertIn(b"minecraft:jigsaw", gym)
            self.assertIn(b"bca:default/paths", gym)
            self.assertIn(b"minecraft:gray_concrete", gym)

    def test_roof_colour_follows_settlement_theme(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            config = root / build_data_mod.STARTER_TOWN_CONFIG
            config.write_text(
                json.dumps({"structure_profile": {"gym_theme": "water"}}),
                encoding="utf-8",
            )

            output = build_data_mod.build(root)

            gym = gzip.decompress(output.read_bytes())
            self.assertIn(b"minecraft:blue_concrete", gym)

    def test_rejects_missing_required_entry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self._fixture(root)
            (source / "pack.mcmeta").unlink()

            with self.assertRaisesRegex(build_data_mod.ModBuildError, "필수 데이터 모드 파일"):
                build_data_mod.build(root)


if __name__ == "__main__":
    unittest.main()

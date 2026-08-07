from __future__ import annotations

import importlib.util
import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "build_data_mod.py"
REPOSITORY_ROOT = MODULE_PATH.parents[2]
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("build_data_mod", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
build_data_mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = build_data_mod
SPEC.loader.exec_module(build_data_mod)


class DataModBuilderTests(unittest.TestCase):
    def test_starter_structure_targets_custom_biome(self) -> None:
        path = (
            REPOSITORY_ROOT
            / build_data_mod.SOURCE
            / "data/cobbleventure/worldgen/structure/starter_town/village.json"
        )
        structure = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual("cobbleventure:starter_plains", structure["biomes"])

    def test_route_town_structure_targets_forest(self) -> None:
        path = (
            REPOSITORY_ROOT
            / build_data_mod.SOURCE
            / "data/cobbleventure/worldgen/structure/route_01_town/village.json"
        )
        structure = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual("minecraft:forest", structure["biomes"])

    def test_generation_dimension_disables_external_biome_features(self) -> None:
        path = (
            REPOSITORY_ROOT
            / build_data_mod.SOURCE
            / "data/cobbleventure/dimension/generation_1.json"
        )
        dimension = json.loads(path.read_text(encoding="utf-8"))

        self.assertFalse(dimension["generator"]["settings"]["features"])

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
            json.dumps({
                "schema_version": 3,
                "structure_profile": {
                    "gym_theme": "rock",
                    "required_facilities": {"gym": "cobbleventure:starter_town/gym"},
                },
            }),
            encoding="utf-8",
        )
        world = root / build_data_mod.HEX_WORLD_CONFIG_DIR / "generation_1.json"
        world.parent.mkdir(parents=True, exist_ok=True)
        world.write_text(json.dumps({"schema_version": 1}), encoding="utf-8")
        boundary = root / build_data_mod.BOUNDARY_PROFILE_CONFIG
        boundary.parent.mkdir(parents=True, exist_ok=True)
        boundary.write_text(json.dumps({"schema_version": 1, "profiles": []}), encoding="utf-8")
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
                json.dumps({
                    "schema_version": 3,
                    "structure_profile": {
                        "gym_theme": "water",
                        "required_facilities": {"gym": "cobbleventure:starter_town/gym"},
                    },
                }),
                encoding="utf-8",
            )

            output = build_data_mod.build(root)

            gym = gzip.decompress(output.read_bytes())
            self.assertIn(b"minecraft:blue_concrete", gym)

    def test_packages_settlement_region_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            config = root / build_data_mod.STARTER_TOWN_CONFIG
            config.write_text(
                json.dumps({
                    "schema_version": 3,
                    "structure_profile": {
                        "gym_theme": "rock",
                        "required_facilities": {"gym": "cobbleventure:starter_town/gym"},
                    },
                    "biome_layout": {"zones": [{}, {}, {}]},
                    "connections": [{"placement": {"mode": "toward_target"}}],
                }),
                encoding="utf-8",
            )
            second = root / build_data_mod.SETTLEMENT_CONFIG_DIR / "generation_1" / "second.json"
            second.write_text(
                json.dumps({
                    "schema_version": 3,
                    "id": "cobbleventure:settlement/second",
                    "structure_profile": {
                        "gym_theme": "bug",
                        "required_facilities": {"gym": "cobbleventure:second/gym"},
                    },
                }),
                encoding="utf-8",
            )

            build_data_mod.build(root)
            generated = root / build_data_mod.OUTPUT / build_data_mod.GENERATED_SETTLEMENT_ENTRY
            generated_second = (
                root / build_data_mod.OUTPUT / build_data_mod.GENERATED_SETTLEMENT_DIR
                / "generation_1" / "second.json"
            )
            data = json.loads(generated.read_text(encoding="utf-8"))

            self.assertEqual(3, data["schema_version"])
            self.assertEqual(3, len(data["biome_layout"]["zones"]))
            self.assertEqual("toward_target", data["connections"][0]["placement"]["mode"])
            self.assertTrue(generated_second.is_file())
            second_gym = root / build_data_mod.OUTPUT / "data/cobbleventure/structure/second/gym.nbt"
            self.assertIn(b"minecraft:lime_concrete", gzip.decompress(second_gym.read_bytes()))
            self.assertTrue(
                (root / build_data_mod.OUTPUT / build_data_mod.GENERATED_HEX_WORLD_DIR / "generation_1.json").is_file()
            )
            self.assertTrue(
                (root / build_data_mod.OUTPUT / build_data_mod.GENERATED_BOUNDARY_PROFILE).is_file()
            )

    def test_rejects_missing_required_entry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self._fixture(root)
            (source / "pack.mcmeta").unlink()

            with self.assertRaisesRegex(build_data_mod.ModBuildError, "필수 데이터 모드 파일"):
                build_data_mod.build(root)


if __name__ == "__main__":
    unittest.main()

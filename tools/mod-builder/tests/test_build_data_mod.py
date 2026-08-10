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
    @classmethod
    def setUpClass(cls) -> None:
        build_data_mod.build(REPOSITORY_ROOT)

    def test_does_not_register_legacy_village_structures(self) -> None:
        path = (
            REPOSITORY_ROOT
            / build_data_mod.OUTPUT
            / "data/cobbleventure/worldgen/structure"
        )

        self.assertFalse(path.exists())

    def test_route_town_uses_upstream_bca_mid_village(self) -> None:
        settlement_path = (
            REPOSITORY_ROOT
            / build_data_mod.OUTPUT
            / "data/cobbleventure/settlements/generation_1/route_01_town.json"
        )
        settlement = json.loads(settlement_path.read_text(encoding="utf-8"))
        generated_override = (
            REPOSITORY_ROOT
            / build_data_mod.OUTPUT
            / "data/cobbleventure/worldgen/structure/route_01_town/village.json"
        )

        self.assertEqual(
            "bca:village/default_mid",
            settlement["structure_profile"]["structure"],
        )
        self.assertFalse(generated_override.exists())

    def test_does_not_register_generated_template_pools(self) -> None:
        pool_root = (
            REPOSITORY_ROOT
            / build_data_mod.OUTPUT
            / "data/cobbleventure/worldgen/template_pool"
        )

        self.assertFalse(pool_root.exists())

    def test_generation_dimension_uses_json_backed_native_generator(self) -> None:
        path = (
            REPOSITORY_ROOT
            / build_data_mod.SOURCE
            / "data/cobbleventure/dimension/generation_1.json"
        )
        dimension = json.loads(path.read_text(encoding="utf-8"))

        generator = dimension["generator"]
        self.assertEqual("cobbleventure:hex_map", generator["type"])
        self.assertEqual(19960227, generator["seed"])
        self.assertEqual(
            "cobbleventure:hex_map", generator["biome_source"]["type"]
        )
        self.assertIn(
            "cobbleventure:starter_plains",
            generator["biome_source"]["biomes"],
        )
        self.assertNotIn("settings", generator)

    def test_sealed_dark_forest_has_no_native_spawns(self) -> None:
        path = (
            REPOSITORY_ROOT
            / build_data_mod.SOURCE
            / "data/cobbleventure/worldgen/biome/sealed_dark_forest.json"
        )
        biome = json.loads(path.read_text(encoding="utf-8"))

        self.assertTrue(all(not entries for entries in biome["features"]))
        self.assertTrue(all(not entries for entries in biome["spawners"].values()))

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
                    "required_facilities": {"village_hub": "cobbleventure:starter_town/village_hub"},
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

    def test_builds_deterministic_village_hub_resource(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)

            output = build_data_mod.build(root)
            first = output.read_bytes()
            output = build_data_mod.build(root)

            self.assertEqual(first, output.read_bytes())
            hub = gzip.decompress(output.read_bytes())
            self.assertIn(b"minecraft:jigsaw", hub)
            self.assertIn(b"bca:default/paths", hub)
            self.assertIn(b"minecraft:barrier", hub)
            self.assertNotIn(b"concrete", hub)

    def test_packages_replaceable_facility_placeholder_structures(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)

            build_data_mod.build(root)

            placeholder_root = (
                root / build_data_mod.OUTPUT / "data/cobbleventure/structure/placeholder"
            )
            generated = sorted(path.stem for path in placeholder_root.glob("*.nbt"))
            self.assertEqual(sorted(build_data_mod.FACILITY_PLACEHOLDERS), generated)

            hotel = gzip.decompress((placeholder_root / "hotel.nbt").read_bytes())
            self.assertIn(b"PLACEHOLDER", hotel)
            self.assertIn(b"hotel", hotel)
            self.assertIn("호텔".encode("utf-8"), hotel)

    def test_authored_facility_nbt_replaces_generated_placeholder(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            authored = root / build_data_mod.FACILITY_STRUCTURE_SOURCE_DIR / "hotel.nbt"
            authored.parent.mkdir(parents=True, exist_ok=True)
            authored_bytes = gzip.compress(b"\x0aAUTHORED HOTEL", mtime=0)
            authored.write_bytes(authored_bytes)

            build_data_mod.build(root)

            packaged = (
                root / build_data_mod.OUTPUT
                / "data/cobbleventure/structure/placeholder/hotel.nbt"
            )
            self.assertEqual(authored_bytes, packaged.read_bytes())

    def test_explicit_civic_facilities_use_configured_hub_and_road(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            config = root / build_data_mod.STARTER_TOWN_CONFIG
            config.write_text(
                json.dumps({
                    "schema_version": 3,
                    "id": "cobbleventure:settlement/test_town",
                    "structure_profile": {
                        "structure": "bca:village/default_mid",
                        "gym_theme": "rock",
                        "commercial_center": "department_store",
                        "pokemon_center_enabled": True,
                        "civic_facilities_explicit": True,
                        "layout_shape": "loop",
                        "road_profile": {"width": 9, "material": "packed_mud"},
                        "required_facilities": {
                            "village_hub": "cobbleventure:test_town/village_hub"
                        },
                    },
                }),
                encoding="utf-8",
            )

            build_data_mod.build(root)

            packaged = json.loads(
                (
                    root / build_data_mod.OUTPUT
                    / "data/cobbleventure/settlements/generation_1/starter_town.json"
                ).read_text(encoding="utf-8")
            )
            self.assertEqual(
                "bca:village/default_mid",
                packaged["structure_profile"]["structure"],
            )
            self.assertFalse(
                (root / build_data_mod.OUTPUT / "data/cobbleventure/worldgen").exists()
            )
            hub = gzip.decompress(
                (
                    root / build_data_mod.OUTPUT
                    / "data/cobbleventure/structure/test_town/village_hub.nbt"
                ).read_bytes()
            )
            self.assertIn(b"minecraft:packed_mud", hub)

    def test_village_hub_does_not_contain_custom_gym_shell(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            config = root / build_data_mod.STARTER_TOWN_CONFIG
            config.write_text(
                json.dumps({
                    "schema_version": 3,
                    "structure_profile": {
                        "gym_theme": "water",
                        "required_facilities": {"village_hub": "cobbleventure:starter_town/village_hub"},
                    },
                }),
                encoding="utf-8",
            )

            output = build_data_mod.build(root)

            hub = gzip.decompress(output.read_bytes())
            self.assertNotIn(b"concrete", hub)
            self.assertIn(b"minecraft:cobblestone", hub)

    def test_village_preset_selects_bca_path_pool(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            config = root / build_data_mod.STARTER_TOWN_CONFIG
            config.write_text(
                json.dumps({
                    "schema_version": 3,
                    "structure_profile": {
                        "gym_theme": "rock",
                        "village_preset": "dark_mid",
                        "required_facilities": {"village_hub": "cobbleventure:starter_town/village_hub"},
                    },
                }),
                encoding="utf-8",
            )

            output = build_data_mod.build(root)

            hub = gzip.decompress(output.read_bytes())
            self.assertIn(b"bca:dark/paths", hub)
            self.assertIn(b"bca:paths_dark", hub)
            self.assertIn(b"bca:path_straight-curved_dark", hub)
            self.assertNotIn(b"bca:default/paths", hub)

    def test_bca_large_preset_is_not_registered_as_worldgen(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            config = root / build_data_mod.STARTER_TOWN_CONFIG
            config.write_text(
                json.dumps({
                    "schema_version": 3,
                    "structure_profile": {
                        "structure": "cobbleventure:starter_town/village",
                        "gym_theme": "rock",
                        "village_preset": "default_large",
                        "required_facilities": {"village_hub": "cobbleventure:starter_town/village_hub"},
                    },
                    "biome_layout": {"zones": [{"biome": "cobbleventure:starter_plains"}]},
                }),
                encoding="utf-8",
            )

            build_data_mod.build(root)

            worldgen = root / build_data_mod.OUTPUT / "data/cobbleventure/worldgen"
            self.assertFalse(worldgen.exists())

    def test_pokemart_does_not_restore_the_native_bca_village_graph(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            config = root / build_data_mod.STARTER_TOWN_CONFIG
            config.write_text(
                json.dumps({
                    "schema_version": 3,
                    "structure_profile": {
                        "structure": "cobbleventure:starter_town/village",
                        "gym_theme": "rock",
                        "village_preset": "default_mid",
                        "commercial_center": "pokemart",
                        "required_facilities": {"village_hub": "cobbleventure:starter_town/village_hub"},
                    },
                }),
                encoding="utf-8",
            )

            build_data_mod.build(root)

            worldgen = root / build_data_mod.OUTPUT / "data/cobbleventure/worldgen"
            self.assertFalse(worldgen.exists())

    def test_authored_starter_uses_laboratory_and_removes_old_commercial_pool(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            stale = root / build_data_mod.OUTPUT / "data/cobbleventure/worldgen/template_pool/starter_town/commercial_center.json"
            stale.parent.mkdir(parents=True, exist_ok=True)
            stale.write_text("{}", encoding="utf-8")
            config = root / build_data_mod.STARTER_TOWN_CONFIG
            config.write_text(
                json.dumps({
                    "schema_version": 3,
                    "structure_profile": {
                        "structure": "cobbleventure:starter_town/village",
                        "gym_theme": "rock",
                        "village_preset": "cobbleventure_starter",
                        "commercial_center": "none",
                        "starter_layout": {
                            "laboratory_structure": "bca:default/centers/center_the_academy",
                            "jigsaw_depth": 2,
                        },
                        "required_facilities": {"village_hub": "cobbleventure:starter_town/village_hub"},
                    },
                }),
                encoding="utf-8",
            )

            build_data_mod.build(root)

            self.assertFalse(
                (root / build_data_mod.OUTPUT / "data/cobbleventure/worldgen").exists()
            )
            self.assertFalse(stale.exists())

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
                        "required_facilities": {"village_hub": "cobbleventure:starter_town/village_hub"},
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
                        "required_facilities": {"village_hub": "cobbleventure:second/village_hub"},
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
            second_hub = root / build_data_mod.OUTPUT / "data/cobbleventure/structure/second/village_hub.nbt"
            self.assertIn(b"bca:default/paths", gzip.decompress(second_hub.read_bytes()))
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

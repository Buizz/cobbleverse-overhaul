from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).parents[1] / "structure_builder.py"
REPOSITORY_ROOT = MODULE_PATH.parents[2]
PROJECT_ROOT = REPOSITORY_ROOT / "content-projects" / "cobbleventure-main"
SPEC = importlib.util.spec_from_file_location("structure_builder", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
structure_builder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = structure_builder
SPEC.loader.exec_module(structure_builder)
CONTENT_MANAGER_PATH = REPOSITORY_ROOT / "tools/content-manager/content_manager.py"
CONTENT_MANAGER_SPEC = importlib.util.spec_from_file_location(
    "structure_builder_test_content_manager", CONTENT_MANAGER_PATH
)
assert CONTENT_MANAGER_SPEC is not None and CONTENT_MANAGER_SPEC.loader is not None
content_manager = importlib.util.module_from_spec(CONTENT_MANAGER_SPEC)
sys.modules[CONTENT_MANAGER_SPEC.name] = content_manager
CONTENT_MANAGER_SPEC.loader.exec_module(content_manager)


class StructureBuilderTests(unittest.TestCase):
    def test_structure_metadata_allows_single_tall_interior(self) -> None:
        document = structure_builder._validate_structure_metadata({
            "schema_version": 1,
            "anchors": [],
            "interior": {
                "id": "department_store",
                "width": 64,
                "depth": 64,
                "floor_height": 80,
                "floors": 1,
            },
        }, Path("department_store.structure.json"))

        self.assertEqual(80, document["interior"]["floor_height"])

    def test_structure_metadata_rejects_total_interior_height_over_limit(self) -> None:
        with self.assertRaisesRegex(
            structure_builder.StructureBuilderError, "전체 높이는 80 이하"
        ):
            structure_builder._validate_structure_metadata({
                "schema_version": 1,
                "anchors": [],
                "interior": {
                    "id": "too_tall",
                    "width": 16,
                    "depth": 16,
                    "floor_height": 48,
                    "floors": 2,
                },
            }, Path("too_tall.structure.json"))

    def test_anchor_id_allows_localized_display_label(self) -> None:
        document = structure_builder._validate_structure_metadata({
            "schema_version": 1,
            "anchors": [{
                "id": "world_side",
                "label": "월드 방향 입구",
                "type": "door",
                "position": [8, 1, 0],
                "safe_spawn": [8, 1, 1],
                "door_facing": "north",
                "safe_side": "south",
            }],
        }, Path("localized.structure.json"))

        self.assertEqual("world_side", document["anchors"][0]["id"])

    def test_deploy_replaces_only_builder_world_and_builder_jar(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            instance = Path(directory) / "instance"
            packaged = root / structure_builder.PACKAGED_BUILDER_ROOT
            source_world = packaged / "saves" / structure_builder.BUILDER_WORLD_NAME
            source_world.mkdir(parents=True)
            (source_world / "level.dat").write_bytes(b"new-world")
            source_mods = packaged / "mods"
            source_mods.mkdir(parents=True)
            (source_mods / "cobbleventure-structure-builder-0.2.0.jar").write_bytes(b"new-jar")

            old_world = instance / "saves" / structure_builder.BUILDER_WORLD_NAME
            old_world.mkdir(parents=True)
            (old_world / "level.dat").write_bytes(b"old-world")
            mods = instance / "mods"
            mods.mkdir(parents=True)
            (mods / "cobbleventure-structure-builder-0.1.0.jar").write_bytes(b"old-jar")
            (mods / "unrelated.jar").write_bytes(b"keep")

            deployed = structure_builder.deploy_builder_world(root, instance)

            self.assertEqual(b"new-world", (old_world / "level.dat").read_bytes())
            backup = Path(str(deployed["world_backup"]))
            self.assertEqual(b"old-world", (backup / "level.dat").read_bytes())
            self.assertEqual(
                b"new-jar",
                (mods / "cobbleventure-structure-builder-0.2.0.jar").read_bytes(),
            )
            self.assertFalse((mods / "cobbleventure-structure-builder-0.1.0.jar").exists())
            self.assertEqual(b"keep", (mods / "unrelated.jar").read_bytes())

    def test_deploy_restores_world_and_jar_when_swap_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            instance = Path(directory) / "instance"
            packaged = root / structure_builder.PACKAGED_BUILDER_ROOT
            source_world = packaged / "saves" / structure_builder.BUILDER_WORLD_NAME
            source_world.mkdir(parents=True)
            (source_world / "level.dat").write_bytes(b"new-world")
            source_mods = packaged / "mods"
            source_mods.mkdir(parents=True)
            (source_mods / "cobbleventure-structure-builder-0.2.0.jar").write_bytes(b"new-jar")
            old_world = instance / "saves" / structure_builder.BUILDER_WORLD_NAME
            old_world.mkdir(parents=True)
            (old_world / "level.dat").write_bytes(b"old-world")
            old_jar = instance / "mods" / "cobbleventure-structure-builder-0.1.0.jar"
            old_jar.parent.mkdir(parents=True)
            old_jar.write_bytes(b"old-jar")
            real_replace = structure_builder.os.replace

            def failing_replace(source: Path, target: Path) -> None:
                if Path(source).name == f".{structure_builder.BUILDER_WORLD_NAME}.builder-sync.tmp":
                    raise PermissionError("world is open")
                real_replace(source, target)

            with mock.patch.object(structure_builder.os, "replace", side_effect=failing_replace):
                with self.assertRaises(structure_builder.StructureBuilderError):
                    structure_builder.deploy_builder_world(root, instance)

            self.assertEqual(b"old-world", (old_world / "level.dat").read_bytes())
            self.assertEqual(b"old-jar", old_jar.read_bytes())

    def test_import_accepts_resized_existing_interior_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            world = Path(directory) / "world"
            sample = PROJECT_ROOT / "content/structures/interiors/player_house.nbt"
            source = root / "content/structures/interiors/player_house.nbt"
            source.parent.mkdir(parents=True)
            shutil.copy2(sample, source)
            module = root / "tools/content-manager/content_manager.py"
            module.parent.mkdir(parents=True)
            shutil.copy2(CONTENT_MANAGER_PATH, module)
            old_size = content_manager.read_minecraft_structure_size(sample.read_bytes())
            new_size = (old_size[0] + 1, old_size[1], old_size[2] + 1)
            resized = content_manager.resize_minecraft_structure_nbt(
                sample.read_bytes(), new_size
            )
            exported = (
                world / "generated/cobbleventure_builder/structures/export"
                / "interiors/player_house.nbt"
            )
            exported.parent.mkdir(parents=True)
            exported.write_bytes(resized)
            metadata = (
                world / "generated/cobbleventure_builder/structure_metadata/export"
                / "interiors/player_house.structure.json"
            )
            metadata.parent.mkdir(parents=True)
            metadata.write_text(json.dumps({
                "schema_version": 1,
                "structure": "content/structures/interiors/player_house.nbt",
                "interior": {
                    "id": "player_house", "width": new_size[0],
                    "depth": new_size[2], "floor_height": new_size[1], "floors": 1,
                },
                "anchors": [],
            }), encoding="utf-8")

            changed = structure_builder.import_exports(root, world)

            self.assertEqual(2, changed)
            self.assertEqual(
                new_size,
                content_manager.read_minecraft_structure_size(source.read_bytes()),
            )

    def test_import_adds_new_variable_size_interior_with_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            world = Path(directory) / "world"
            sample = PROJECT_ROOT / "content/structures/houses/one_story_flat.nbt"
            source = root / "content/structures/placeholder/base.nbt"
            source.parent.mkdir(parents=True)
            shutil.copy2(sample, source)
            module = root / "tools/content-manager/content_manager.py"
            module.parent.mkdir(parents=True)
            shutil.copy2(
                REPOSITORY_ROOT / "tools/content-manager/content_manager.py", module
            )
            export_root = world / "generated/cobbleventure_builder/structures/export"
            base_export = export_root / "placeholder/base.nbt"
            interior_export = export_root / "interiors/sample_room.nbt"
            base_export.parent.mkdir(parents=True)
            interior_export.parent.mkdir(parents=True)
            shutil.copy2(sample, base_export)
            shutil.copy2(sample, interior_export)
            sample_size = content_manager.read_minecraft_structure_size(
                sample.read_bytes()
            )
            self.assertEqual((24, 24, 16), sample_size)
            metadata = (
                world
                / "generated/cobbleventure_builder/structure_metadata/export"
                / "interiors/sample_room.structure.json"
            )
            metadata.parent.mkdir(parents=True)
            metadata.write_text(json.dumps({
                "schema_version": 1,
                "structure": "content/structures/interiors/sample_room.nbt",
                "interior": {
                    "id": "sample_room",
                    "width": sample_size[0],
                    "depth": sample_size[2],
                    "floor_height": 8,
                    "floors": 3,
                },
                "anchors": [],
            }), encoding="utf-8")

            changed = structure_builder.import_exports(root, world)

            self.assertEqual(2, changed)
            self.assertEqual(
                sample.read_bytes(),
                (root / "content/structures/interiors/sample_room.nbt").read_bytes(),
            )
            self.assertTrue(
                (root / "content/structures/interiors/sample_room.structure.json").is_file()
            )

    def test_catalog_restores_authored_door_anchor_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "content/structures/placeholder/player_house.nbt"
            source.parent.mkdir(parents=True)
            shutil.copy2(
                PROJECT_ROOT / "content/structures/placeholder/player_house.nbt",
                source,
            )
            module = root / "tools/content-manager/content_manager.py"
            module.parent.mkdir(parents=True)
            shutil.copy2(
                REPOSITORY_ROOT / "tools/content-manager/content_manager.py",
                module,
            )
            anchors = [{
                "id": "door",
                "type": "door",
                "position": [7, 1, 0],
                "safe_spawn": [7, 1, -1],
                "door_facing": "south",
                "safe_side": "north",
            }, {
                "label": "resident",
                "type": "npc_position",
                "position": [8, 1, 6],
            }, {
                "id": "interior_spawn",
                "type": "interior_spawn",
                "position": [8, 1, 5],
                "facing": "north",
            }, {
                "id": "patrol_1",
                "type": "patrol_point",
                "position": [5, 1, 5],
                "facing": "east",
            }]
            source.with_suffix(".structure.json").write_text(
                json.dumps({
                    "schema_version": 1,
                    "interior_structure": "cobbleventure:interiors/player_house",
                    "anchors": anchors,
                }),
                encoding="utf-8",
            )

            entries = structure_builder.catalog_entries(root)

            self.assertEqual(anchors, entries[0]["anchors"])
            self.assertEqual(
                "cobbleventure:interiors/player_house",
                entries[0]["interior_structure"],
            )

    def test_generate_packages_every_authored_nbt_without_recoloring(self) -> None:
        catalog_path = structure_builder.generate(REPOSITORY_ROOT)
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        sources = sorted(
            source for source in (PROJECT_ROOT / "content/structures").rglob("*.nbt")
            if source.relative_to(PROJECT_ROOT / "content/structures").parts[0] != "league"
        )

        self.assertEqual(len(sources), len(catalog["entries"]))
        self.assertEqual(1, sum(entry["category"] == "gyms" for entry in catalog["entries"]))
        self.assertEqual(1, sum(
            entry["source"].startswith("content/structures/interiors/gyms/")
            for entry in catalog["entries"]
        ))
        self.assertFalse(any(entry["category"] == "league" for entry in catalog["entries"]))
        self.assertTrue(any(
            entry["source"] == "content/structures/placeholder/player_house.nbt"
            for entry in catalog["entries"]
        ))
        self.assertEqual(
            {"bench", "flower_bed", "fountain", "street_lamp", "street_tree"},
            {
                entry["label"] for entry in catalog["entries"]
                if entry["category"] == "town_decorations"
            },
        )
        for entry in catalog["entries"]:
            source = PROJECT_ROOT / entry["source"]
            resource_path = entry["structure"].split(":", 1)[1]
            packaged = (
                REPOSITORY_ROOT / structure_builder.GENERATED_RESOURCES
                / "data/cobbleventure_builder/structure" / f"{resource_path}.nbt"
            )
            self.assertEqual(source.read_bytes(), packaged.read_bytes())

    def test_import_rejects_incomplete_export_before_overwriting(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            world = Path(directory)
            export = world / "generated/cobbleventure_builder/structures/export/houses"
            export.mkdir(parents=True)
            source = PROJECT_ROOT / "content/structures/houses/one_story_flat.nbt"
            shutil.copy2(source, export / source.name)

            with self.assertRaisesRegex(
                structure_builder.StructureBuilderError, "누락된 구조물"
            ):
                structure_builder.import_exports(REPOSITORY_ROOT, world)

    def test_import_accepts_legacy_gate_export_paths_after_resource_rename(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            world = Path(directory) / "world"
            module = root / "tools/content-manager/content_manager.py"
            module.parent.mkdir(parents=True)
            shutil.copy2(CONTENT_MANAGER_PATH, module)
            entries = []
            for canonical, legacy in (
                ("forest_gate/forest_gate.nbt", "forest_entrance/forest_gate.nbt"),
                ("gate/default_gate.nbt", "forest_entrance/default_gate.nbt"),
            ):
                sample = PROJECT_ROOT / "content/structures" / canonical
                source = root / "content/structures" / canonical
                exported = (
                    world / "generated/cobbleventure_builder/structures/export" / legacy
                )
                source.parent.mkdir(parents=True, exist_ok=True)
                exported.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(sample, source)
                shutil.copy2(sample, exported)
                size = content_manager.read_minecraft_structure_size(sample.read_bytes())
                entries.append({
                    "source": f"content/structures/{canonical}",
                    "size": list(size),
                })

            with mock.patch.object(
                structure_builder, "catalog_entries", return_value=entries,
            ):
                changed = structure_builder.import_exports(root, world)

            self.assertEqual(0, changed)

    def test_named_door_and_arrival_are_valid_builder_anchors(self) -> None:
        document = structure_builder._validate_structure_metadata({
            "schema_version": 1,
            "anchors": [{
                "type": "door", "id": "next_room", "label": "next_room",
                "position": [4, 1, 0], "safe_spawn": [4, 1, 2],
                "door_facing": "north", "safe_side": "south",
            }, {
                "type": "arrival", "id": "entrance", "label": "entrance",
                "position": [4, 1, 3], "facing": "south",
            }],
        }, Path("shared_room.structure.json"))
        self.assertEqual("next_room", document["anchors"][0]["label"])

if __name__ == "__main__":
    unittest.main()

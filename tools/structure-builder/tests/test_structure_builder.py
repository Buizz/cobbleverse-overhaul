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
GENERATOR_PATH = REPOSITORY_ROOT / "tools/structure-builder/generate_underground_road_modules.py"
GENERATOR_SPEC = importlib.util.spec_from_file_location("underground_module_generator", GENERATOR_PATH)
assert GENERATOR_SPEC is not None and GENERATOR_SPEC.loader is not None
underground_module_generator = importlib.util.module_from_spec(GENERATOR_SPEC)
sys.modules[GENERATOR_SPEC.name] = underground_module_generator
sys.modules["generate_underground_road_modules"] = underground_module_generator
GENERATOR_SPEC.loader.exec_module(underground_module_generator)
ENTRANCE_GENERATOR_PATH = REPOSITORY_ROOT / "tools/structure-builder/generate_underground_entrance.py"
ENTRANCE_GENERATOR_SPEC = importlib.util.spec_from_file_location("underground_entrance_generator", ENTRANCE_GENERATOR_PATH)
assert ENTRANCE_GENERATOR_SPEC is not None and ENTRANCE_GENERATOR_SPEC.loader is not None
underground_entrance_generator = importlib.util.module_from_spec(ENTRANCE_GENERATOR_SPEC)
sys.modules[ENTRANCE_GENERATOR_SPEC.name] = underground_entrance_generator
ENTRANCE_GENERATOR_SPEC.loader.exec_module(underground_entrance_generator)
DUNGEON_SKIN_GENERATOR_PATH = REPOSITORY_ROOT / "tools/structure-builder/generate_dungeon_piece_skins.py"
DUNGEON_SKIN_GENERATOR_SPEC = importlib.util.spec_from_file_location(
    "dungeon_piece_skin_generator", DUNGEON_SKIN_GENERATOR_PATH
)
assert DUNGEON_SKIN_GENERATOR_SPEC is not None and DUNGEON_SKIN_GENERATOR_SPEC.loader is not None
dungeon_piece_skin_generator = importlib.util.module_from_spec(DUNGEON_SKIN_GENERATOR_SPEC)
sys.modules[DUNGEON_SKIN_GENERATOR_SPEC.name] = dungeon_piece_skin_generator
DUNGEON_SKIN_GENERATOR_SPEC.loader.exec_module(dungeon_piece_skin_generator)


class StructureBuilderTests(unittest.TestCase):
    def test_standard_dungeon_structures_are_symmetric_on_their_shape_axes(self) -> None:
        symmetry_axes = {
            "start": ("x", "z"), "corridor": ("x", "z"),
            "junction": ("x", "z"), "room": ("x", "z"),
            "encounter_room": ("x", "z"), "support": ("x", "z"),
            "boss": ("x", "z"), "stairs_up": ("z",),
            "stairs_down": ("z",), "dead_end": ("z",),
            "treasure": ("x",), "exit": ("z",), "corner": ("diagonal",),
            "empty_chamber_1x2": ("x", "z"),
            "empty_chamber_2x2": ("x", "z"),
        }
        for shape_name, axes in symmetry_axes.items():
            payload = dungeon_piece_skin_generator._build_nbt(
                shape_name,
                dungeon_piece_skin_generator.SHAPES[shape_name],
                dungeon_piece_skin_generator.SKINS["rocket"],
            )
            size, palette, blocks = content_manager._minecraft_structure_parts(payload)
            states = {tuple(block["pos"]): palette[block["state"]] for block in blocks}
            width, height, depth = size
            for axis in axes:
                with self.subTest(shape=shape_name, axis=axis):
                    for x in range(width):
                        for y in range(height):
                            for z in range(depth):
                                mirrored = {
                                    "x": (width - 1 - x, y, z),
                                    "z": (x, y, depth - 1 - z),
                                    "diagonal": (width - 1 - z, y, depth - 1 - x),
                                }[axis]
                                self.assertEqual(states.get((x, y, z)), states.get(mirrored))

    def test_standard_dungeon_openings_use_symmetric_even_width(self) -> None:
        room = dungeon_piece_skin_generator.SHAPES["room"]
        footprint = dungeon_piece_skin_generator._footprint(room)

        self.assertEqual(set(range(5, 11)), {z for x, z in footprint if x == 0})
        self.assertEqual(set(range(5, 11)), {x for x, z in footprint if z == 0})
        definition = dungeon_piece_skin_generator._definition("room", room, "rocket")
        self.assertEqual(
            {"cobbleventure:dungeon_socket/standard_6"},
            {connector["socket"] for connector in definition["connectors"]},
        )

    def test_dungeon_stair_ceiling_follows_each_floor_step(self) -> None:
        for shape_name in ("stairs_up", "stairs_down"):
            shape = dungeon_piece_skin_generator.SHAPES[shape_name]
            payload = dungeon_piece_skin_generator._build_nbt(
                shape_name, shape, dungeon_piece_skin_generator.SKINS["rocket"]
            )
            size, palette, blocks = content_manager._minecraft_structure_parts(payload)
            states = {tuple(block["pos"]): palette[block["state"]] for block in blocks}
            heights = dict(shape.connector_heights)
            for x in range(size[0]):
                level = dungeon_piece_skin_generator._stair_level(
                    shape_name, x, size[0], heights
                )
                ceiling_y = level + dungeon_piece_skin_generator.CEILING_OFFSET
                with self.subTest(shape=shape_name, x=x):
                    self.assertIn(
                        states[(x, ceiling_y, 7)],
                        {
                            dungeon_piece_skin_generator.SKINS["rocket"]["ceiling"],
                            dungeon_piece_skin_generator.SKINS["rocket"]["lamp"],
                        },
                    )
                    for air_y in range(level + 1, ceiling_y):
                        self.assertEqual("minecraft:air", states[(x, air_y, 7)])
                    self.assertNotIn((x, ceiling_y + 1, 7), states)

    def test_dungeon_piece_skins_share_the_same_shape_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            alternate = {
                key: "minecraft:stone" for key in next(
                    iter(dungeon_piece_skin_generator.SKINS.values())
                )
            }
            skins = {
                "rocket": dungeon_piece_skin_generator.SKINS["rocket"],
                "alternate": alternate,
            }
            with mock.patch.object(dungeon_piece_skin_generator, "SKINS", skins):
                generated = dungeon_piece_skin_generator.generate(root)

            with mock.patch.object(
                structure_builder,
                "_metadata_reader",
                return_value=content_manager.read_minecraft_structure_metadata,
            ):
                catalog = structure_builder.catalog_entries(root)

            self.assertEqual(
                len(skins) * len(dungeon_piece_skin_generator.SHAPES) * 2,
                len(generated),
            )
            self.assertEqual(
                len(skins) * len(dungeon_piece_skin_generator.SHAPES),
                len(catalog),
            )
            self.assertEqual({"dungeon_pieces"}, {entry["category"] for entry in catalog})
            definition_root = (
                root / dungeon_piece_skin_generator.PROJECT / "content/dungeon_pieces"
            )
            structure_root = (
                root / dungeon_piece_skin_generator.PROJECT / "content/structures/dungeon_pieces"
            )
            for shape_name in dungeon_piece_skin_generator.SHAPES:
                rocket = json.loads(
                    (definition_root / "rocket" / f"{shape_name}.json").read_text(encoding="utf-8")
                )
                alternate_definition = json.loads(
                    (definition_root / "alternate" / f"{shape_name}.json").read_text(encoding="utf-8")
                )
                for field in ("role", "size", "allow_rotation", "connectors", "markers"):
                    self.assertEqual(rocket[field], alternate_definition[field], (shape_name, field))
                for skin_name in skins:
                    size = content_manager.read_minecraft_structure_size(
                        (structure_root / skin_name / f"{shape_name}.nbt").read_bytes()
                    )
                    self.assertEqual(
                        dungeon_piece_skin_generator.SHAPES[shape_name].size,
                        size,
                    )
            stairs = json.loads(
                (definition_root / "rocket" / "stairs_up.json").read_text(encoding="utf-8")
            )
            self.assertEqual([16, 16, 16], stairs["size"])
            self.assertEqual({1, 9}, {connector["position"][1] for connector in stairs["connectors"]})
            for shape_name, expected_size in {
                "empty_chamber_1x2": [16, 8, 32],
                "empty_chamber_2x2": [32, 8, 32],
            }.items():
                chamber = json.loads(
                    (definition_root / "rocket" / f"{shape_name}.json").read_text(
                        encoding="utf-8"
                    )
                )
                self.assertEqual("chamber", chamber["spatial_kind"])
                self.assertEqual(expected_size, chamber["size"])
                self.assertEqual([], chamber["markers"])

    def test_generated_underground_entrance_has_road_anchor_and_transition_barriers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "underground_passage.nbt"
            metadata_target = target.with_suffix(".structure.json")
            with mock.patch.object(underground_entrance_generator, "OUTPUT", target), mock.patch.object(
                underground_entrance_generator, "METADATA_OUTPUT", metadata_target
            ):
                generated = underground_entrance_generator.generate()
            metadata = content_manager.read_minecraft_structure_metadata(generated.read_bytes())

            self.assertEqual((24, 16, 20), (metadata["width"], metadata["height"], metadata["depth"]))
            self.assertEqual(
                [{"position": [11, 1, 0], "facing": "north", "orientation": "north_up", "final_state": "minecraft:smooth_stone"}],
                metadata["road_anchors"],
            )
            self.assertEqual([], metadata["underground_entries"])
            _, palette, blocks = content_manager._minecraft_structure_parts(
                generated.read_bytes()
            )
            excavation_state = palette.index(
                "cobbleventure_bootstrap:excavation_marker"
            )
            barrier_state = palette.index("minecraft:barrier")
            self.assertGreater(
                sum(block["state"] == excavation_state for block in blocks), 1000
            )
            self.assertEqual(
                12, sum(block["state"] == barrier_state for block in blocks)
            )
            authored = json.loads(metadata_target.read_text(encoding="utf-8"))
            self.assertEqual("transition", authored["anchors"][0]["type"])
            self.assertEqual([11, 1, 14], authored["anchors"][0]["position"])

    def test_generated_underground_modules_have_expected_connectors(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with mock.patch.object(underground_module_generator, "OUTPUT", output):
                generated = underground_module_generator.generate()
                first_payloads = {path.name: path.read_bytes() for path in generated}
                regenerated = underground_module_generator.generate()

            self.assertEqual(11, len(generated))
            self.assertEqual(first_payloads, {path.name: path.read_bytes() for path in regenerated})
            metadata = {
                path.stem: content_manager.read_minecraft_structure_metadata(path.read_bytes())
                for path in generated
            }
            upward = {
                name for name, document in metadata.items()
                if any(connector["facing"] == "up" for connector in document["underground_connectors"])
            }
            self.assertEqual({"stairs_up"}, upward)
            self.assertEqual(
                {"west", "surface"},
                {connector["tag"] for connector in metadata["stairs_up"]["underground_connectors"]},
            )
            surface = next(
                connector for connector in metadata["stairs_up"]["underground_connectors"]
                if connector["tag"] == "surface"
            )
            self.assertEqual([13, 10, 7], surface["position"])
            self.assertEqual(
                {"east", "vertical_down"},
                {connector["tag"] for connector in metadata["stairs_down"]["underground_connectors"]},
            )

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

    def test_structure_metadata_accepts_touch_transition_anchor(self) -> None:
        document = structure_builder._validate_structure_metadata({
            "schema_version": 1,
            "anchors": [{
                "id": "underground_entry",
                "type": "transition",
                "position": [11, 1, 14],
                "safe_spawn": [11, 1, 13],
                "facing": "north",
            }],
        }, Path("underground_passage.structure.json"))

        self.assertEqual("transition", document["anchors"][0]["type"])

    def test_structure_metadata_accepts_dungeon_entrance_anchor(self) -> None:
        document = structure_builder._validate_structure_metadata({
            "schema_version": 1,
            "anchors": [{
                "id": "rocket_entry",
                "label": "rocket_entry",
                "type": "dungeon_entrance",
                "position": [8, 1, 1],
                "safe_spawn": [8, 1, 3],
                "facing": "north",
                "entrance_id": "cobbleventure:entrance/rocket_test",
            }],
        }, Path("rocket_entry.structure.json"))

        self.assertEqual(
            "cobbleventure:entrance/rocket_test",
            document["anchors"][0]["entrance_id"],
        )

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
            source.with_suffix(".structure.json").write_text(json.dumps({
                "schema_version": 1,
                "structure": "content/structures/interiors/player_house.nbt",
                "display_name": {"ko_kr": "플레이어의 집", "en_us": "Player House"},
                "provenance": {"license": "CC0-1.0"},
                "anchors": [{
                    "label": "old_npc", "type": "npc_position",
                    "position": [1, 1, 1],
                }],
            }), encoding="utf-8")
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
            imported_metadata = json.loads(
                source.with_suffix(".structure.json").read_text(encoding="utf-8")
            )
            self.assertEqual("플레이어의 집", imported_metadata["display_name"]["ko_kr"])
            self.assertEqual("CC0-1.0", imported_metadata["provenance"]["license"])
            self.assertEqual([], imported_metadata["anchors"])

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

    def test_import_adds_new_underground_road_module_with_named_connectors(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            world = Path(directory) / "world"
            exported = (
                world / "generated/cobbleventure_builder/structures/export"
                / "underground_road_modules/test_passage.nbt"
            )
            exported.parent.mkdir(parents=True)
            exported.write_bytes(b"authored-underground-passage")
            metadata = {
                "width": 64, "height": 24, "depth": 96,
                "underground_connectors": [
                    {"tag": "north"}, {"tag": "south"},
                ],
            }

            with mock.patch.object(structure_builder, "catalog_entries", return_value=[]), \
                 mock.patch.object(structure_builder, "_metadata_reader", return_value=lambda _: metadata):
                changed = structure_builder.import_exports(root, world)

            target = root / "content/structures/underground_road_modules/test_passage.nbt"
            self.assertEqual(1, changed)
            self.assertEqual(exported.read_bytes(), target.read_bytes())

    def test_import_rejects_underground_road_module_with_duplicate_connectors(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            world = Path(directory) / "world"
            exported = (
                world / "generated/cobbleventure_builder/structures/export"
                / "underground_road_modules/test_passage.nbt"
            )
            exported.parent.mkdir(parents=True)
            exported.write_bytes(b"invalid-underground-passage")
            metadata = {
                "width": 64, "height": 24, "depth": 96,
                "underground_connectors": [
                    {"tag": "exit_1"}, {"tag": "exit_1"},
                ],
            }

            with mock.patch.object(structure_builder, "catalog_entries", return_value=[]), \
                 mock.patch.object(structure_builder, "_metadata_reader", return_value=lambda _: metadata):
                with self.assertRaisesRegex(
                    structure_builder.StructureBuilderError, "중복 없는 직소 커넥터"
                ):
                    structure_builder.import_exports(root, world)

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

    def test_import_skips_size_mismatch_and_imports_valid_exports(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            world = Path(directory) / "world"
            module = root / "tools/content-manager/content_manager.py"
            module.parent.mkdir(parents=True)
            shutil.copy2(CONTENT_MANAGER_PATH, module)

            valid_sample = PROJECT_ROOT / "content/structures/placeholder/casino.nbt"
            invalid_sample = PROJECT_ROOT / "content/structures/houses/five_story_flat.nbt"
            valid_target = root / "content/structures/placeholder/casino.nbt"
            invalid_target = root / "content/structures/houses/five_story_flat.nbt"
            valid_target.parent.mkdir(parents=True)
            invalid_target.parent.mkdir(parents=True)
            valid_target.write_bytes(b"old-casino")
            invalid_target.write_bytes(b"keep-flat")

            export_root = (
                world / "generated/cobbleventure_builder/structures/export"
            )
            valid_export = export_root / "placeholder/casino.nbt"
            invalid_export = export_root / "houses/five_story_flat.nbt"
            valid_export.parent.mkdir(parents=True)
            invalid_export.parent.mkdir(parents=True)
            shutil.copy2(valid_sample, valid_export)
            shutil.copy2(invalid_sample, invalid_export)

            valid_size = content_manager.read_minecraft_structure_size(
                valid_sample.read_bytes()
            )
            invalid_size = content_manager.read_minecraft_structure_size(
                invalid_sample.read_bytes()
            )
            entries = [{
                "source": "content/structures/placeholder/casino.nbt",
                "size": list(valid_size),
            }, {
                "source": "content/structures/houses/five_story_flat.nbt",
                "size": [invalid_size[0] + 1, invalid_size[1], invalid_size[2]],
            }]

            with mock.patch.object(
                structure_builder, "catalog_entries", return_value=entries,
            ), mock.patch.object(
                structure_builder,
                "_metadata_reader",
                return_value=content_manager.read_minecraft_structure_metadata,
            ), mock.patch("sys.stderr") as stderr:
                changed = structure_builder.import_exports(root, world)

            self.assertEqual(1, changed)
            self.assertEqual(valid_sample.read_bytes(), valid_target.read_bytes())
            self.assertEqual(b"keep-flat", invalid_target.read_bytes())
            self.assertIn("건너뜁니다", "".join(
                str(call.args[0]) for call in stderr.write.call_args_list
                if call.args
            ))

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

    def test_npc_facing_is_optional_and_validated_when_present(self) -> None:
        document = structure_builder._validate_structure_metadata({
            "schema_version": 1,
            "anchors": [{
                "type": "npc_position", "label": "clerk",
                "position": [4, 1, 3], "facing": "east",
            }],
        }, Path("shop.structure.json"))
        self.assertEqual("east", document["anchors"][0]["facing"])

        with self.assertRaises(structure_builder.StructureBuilderError):
            structure_builder._validate_structure_metadata({
                "schema_version": 1,
                "anchors": [{
                    "type": "npc_position", "label": "clerk",
                    "position": [4, 1, 3], "facing": "up",
                }],
            }, Path("shop.structure.json"))

if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import hashlib
import gzip
import importlib.util
import json
import shutil
import struct
import sys
import tempfile
import threading
import unittest
import urllib.request
import zipfile
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).parents[1] / "content_manager.py"
SPEC = importlib.util.spec_from_file_location("content_manager", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
content_manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = content_manager
SPEC.loader.exec_module(content_manager)


class ContentManagerTests(unittest.TestCase):
    @staticmethod
    def _structure_nbt(size: tuple[int, int, int]) -> bytes:
        payload = (
            b"\x0a\x00\x00"
            + b"\x09\x00\x04size\x03"
            + struct.pack(">i", 3)
            + struct.pack(">iii", *size)
            + b"\x00"
        )
        return gzip.compress(payload)

    @staticmethod
    def _structure_nbt_with_blocks() -> bytes:
        def nbt_string(value: str) -> bytes:
            encoded = value.encode("utf-8")
            return struct.pack(">H", len(encoded)) + encoded

        palette = ["minecraft:stone", "minecraft:oak_planks", "minecraft:grass_block"]
        palette_payload = b"".join(
            b"\x08" + nbt_string("Name") + nbt_string(block_name) + b"\x00"
            for block_name in palette
        )
        block_values = [
            (1, 0, 1, 0), (1, 2, 1, 1), (2, 0, 1, 2),
            (3, 0, 2, 1),
        ]
        blocks_payload = b"".join(
            b"\x09" + nbt_string("pos") + b"\x03" + struct.pack(">i", 3)
            + struct.pack(">iii", x, y, z)
            + b"\x03" + nbt_string("state") + struct.pack(">i", state)
            + b"\x00"
            for x, y, z, state in block_values
        )
        payload = (
            b"\x0a\x00\x00"
            + b"\x09" + nbt_string("size") + b"\x03" + struct.pack(">i", 3)
            + struct.pack(">iii", 4, 4, 4)
            + b"\x09" + nbt_string("palette") + b"\x0a" + struct.pack(">i", len(palette))
            + palette_payload
            + b"\x09" + nbt_string("blocks") + b"\x0a" + struct.pack(">i", len(block_values))
            + blocks_payload
            + b"\x00"
        )
        return gzip.compress(payload)

    def test_reads_exact_minecraft_structure_size_from_nbt(self) -> None:
        self.assertEqual(
            (22, 15, 23),
            content_manager.read_minecraft_structure_size(
                self._structure_nbt((22, 15, 23))
            ),
        )

    def test_reads_visible_top_block_for_each_nbt_column(self) -> None:
        metadata = content_manager.read_minecraft_structure_metadata(
            self._structure_nbt_with_blocks()
        )

        self.assertEqual(
            ["minecraft:oak_planks"],
            metadata["top_view"]["palette"],
        )
        self.assertEqual(
            [[1, 1, 2, 0], [3, 2, 0, 0]],
            metadata["top_view"]["blocks"],
        )
        self.assertEqual(
            {
                "min_x": 1, "min_z": 1, "max_x": 3, "max_z": 2,
                "width": 3, "depth": 2,
            },
            metadata["occupied"],
        )

    def test_reads_visible_block_faces_for_structure_model(self) -> None:
        model = content_manager.read_minecraft_structure_model(
            self._structure_nbt_with_blocks()
        )

        self.assertEqual((4, 4, 4), (model["width"], model["height"], model["depth"]))
        self.assertEqual(4, model["total_blocks"])
        self.assertEqual(4, model["surface_blocks"])
        self.assertEqual(
            ["minecraft:grass_block", "minecraft:oak_planks", "minecraft:stone"],
            model["palette"],
        )
        self.assertEqual([61, 62, 63, 63], [block[4] for block in model["blocks"]])

    def test_structure_size_catalog_reads_mod_jar_resources(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mods = root / "pack/overrides/development-placeholder/mods"
            mods.mkdir(parents=True)
            with zipfile.ZipFile(mods / "structures.jar", "w") as archive:
                archive.writestr(
                    "data/example/structure/town/center.nbt",
                    self._structure_nbt((31, 12, 27)),
                )

            catalog = content_manager.load_structure_size_catalog(root)

            self.assertEqual(
                {
                    "width": 31, "height": 12, "depth": 27,
                    "occupied": {
                        "min_x": 0, "min_z": 0, "max_x": 30, "max_z": 26,
                        "width": 31, "depth": 27,
                    },
                    "top_view": {"palette": [], "blocks": []},
                    "source": "structures.jar",
                },
                catalog["structures"]["example:town/center"],
            )

    def test_local_structure_overrides_installed_jar_with_same_resource_id(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            local = (
                root / "projects/cobbleventure-world-bootstrap/src/generated/resources"
                / "data/example/structure/town/center.nbt"
            )
            local.parent.mkdir(parents=True)
            local.write_bytes(self._structure_nbt((16, 18, 16)))
            mods = root / "pack/overrides/development-placeholder/mods"
            mods.mkdir(parents=True)
            with zipfile.ZipFile(mods / "old-structures.jar", "w") as archive:
                archive.writestr(
                    "data/example/structure/town/center.nbt",
                    self._structure_nbt((32, 10, 16)),
                )

            catalog = content_manager.load_structure_size_catalog(root)

            self.assertEqual(16, catalog["structures"]["example:town/center"]["width"])
            self.assertEqual(18, catalog["structures"]["example:town/center"]["height"])
            self.assertIn("src/generated/resources", catalog["structures"]["example:town/center"]["source"])

    def test_three_cell_town_footprint_uses_center_and_two_neighbors(self) -> None:
        self.assertEqual(
            {(-1, 0), (0, 0), (1, 0)},
            content_manager._town_footprint((0, 0), 3),
        )

    def test_five_cell_town_footprint_expands_only_selected_side(self) -> None:
        middle = {(-1, 0), (0, 0), (1, 0)}
        self.assertEqual(
            middle | {(0, -1), (1, -1)},
            content_manager._town_footprint((0, 0), 5, "five_up"),
        )
        self.assertEqual(
            middle | {(-1, 1), (0, 1)},
            content_manager._town_footprint((0, 0), 5, "five_down"),
        )

    def test_nineteen_cell_town_footprint_is_complete_radius_two_hexagon(self) -> None:
        cells = content_manager._town_footprint((0, 0), 19)

        self.assertEqual(19, len(cells))
        self.assertTrue(all(content_manager._hex_distance((0, 0), cell) <= 2 for cell in cells))

    def test_custom_town_footprint_uses_authored_relative_cells(self) -> None:
        relative = {(0, 0), (1, 0), (1, -1)}

        self.assertEqual(
            {(4, -2), (5, -2), (5, -3)},
            content_manager._town_footprint((4, -2), 3, "custom", relative),
        )

    @staticmethod
    def _valid_settlement_trainer_slot() -> dict:
        return {
            "id": "starter_guide",
            "trainer_id": "cobbleventure:trainer/starter_guide",
            "battle_type": "singles",
            "members": [{
                "id": "primary",
                "npc_profile": "cobbleventure:trainer/starter_guide",
                "position": {"x": 600, "y": 69, "z": -300},
                "rotation": 0,
            }],
            "spawn_policy": "persistent",
            "tags": ["trainer"],
        }

    def test_player_menu_accepts_null_secondary_pokemon_habitat(self) -> None:
        root = Path(__file__).parents[3]
        catalog = json.loads((root / "content" / "catalogs" / "pokemon-habitats.json").read_text(encoding="utf-8"))
        self.assertTrue(any(entry.get("habitats", {}).get("secondary") is None for entry in catalog["pokemon"]))
        source = (root / "projects" / "cobbleventure-player-menu" / "src" / "main" / "java" / "dev" / "buizz" / "cobbleventure" / "playermenu" / "MapContent.java").read_text(encoding="utf-8")
        self.assertIn('nullableString(habitats, "secondary")', source)
        self.assertIn("value.isJsonNull()", source)

    def test_world_layout_graph_can_be_saved_atomically(self) -> None:
        root = Path(__file__).parents[3]
        layout = content_manager.load_world_layout(root)
        self.assertEqual(11, len(layout["settlements"]))
        settlement_ids = {node["settlement"] for node in layout["settlements"]}
        self.assertGreater(len(layout["connections"]), 0)
        self.assertTrue(all(connection.get("from") in settlement_ids for connection in layout["connections"] if connection.get("from")))
        self.assertTrue(all(connection.get("to") in settlement_ids for connection in layout["connections"] if connection.get("to")))
        self.assertTrue(all(connection["pathfinding"] == "explicit" for connection in layout["connections"]))
        self.assertTrue(all(len(connection["cells"]) >= 2 for connection in layout["connections"]))
        self.assertEqual("high_forest", layout["empty_terrain"]["default_type"])
        with tempfile.TemporaryDirectory() as directory:
            candidate_root = Path(directory)
            settlement_dir = candidate_root / "content" / "settlements" / "generation_1"
            catalog_dir = candidate_root / "content" / "catalogs"
            settlement_dir.mkdir(parents=True)
            catalog_dir.mkdir(parents=True)
            for node in layout["settlements"]:
                slug = node["settlement"].rsplit("/", 1)[-1]
                (settlement_dir / f"{slug}.json").write_text(
                    json.dumps({"id": node["settlement"], "display_name": {"ko_kr": slug}}),
                    encoding="utf-8",
                )
            shutil.copy2(
                root / "content" / "catalogs" / "boundary-profiles.json",
                catalog_dir / "boundary-profiles.json",
            )
            self.assertEqual([], content_manager.save_world_layout(candidate_root, layout))
            saved = content_manager.load_world_layout(candidate_root)
            invalid = json.loads(json.dumps(saved))
            invalid["settlements"][1]["anchor"] = dict(invalid["settlements"][0]["anchor"])
            issues = content_manager.save_world_layout(candidate_root, invalid)
            self.assertTrue(any(issue.level == "error" for issue in issues))
            self.assertEqual(saved, content_manager.load_world_layout(candidate_root))
            invalid_biome = json.loads(json.dumps(saved))
            invalid_biome["settlements"][0].pop("town_biome")
            issues = content_manager.save_world_layout(candidate_root, invalid_biome)
            self.assertTrue(any(issue.path.endswith(".town_biome") for issue in issues))
            self.assertEqual(saved, content_manager.load_world_layout(candidate_root))
            invalid_buffer = json.loads(json.dumps(saved))
            first_anchor = invalid_buffer["settlements"][0]["anchor"]
            invalid_buffer["settlements"][1]["anchor"] = {"q": first_anchor["q"] + 1, "r": first_anchor["r"]}
            issues = content_manager.save_world_layout(candidate_root, invalid_buffer)
            self.assertTrue(any("완충 지형" in issue.message for issue in issues))
            self.assertEqual(saved, content_manager.load_world_layout(candidate_root))
            invalid_empty = json.loads(json.dumps(saved))
            invalid_empty["empty_terrain"]["tiles"] = [
                {"q": 20, "r": -4, "type": "lava"},
            ]
            issues = content_manager.save_world_layout(candidate_root, invalid_empty)
            self.assertTrue(any(issue.path.endswith(".type") for issue in issues))
            self.assertEqual(saved, content_manager.load_world_layout(candidate_root))
            with_environment = json.loads(json.dumps(saved))
            with_environment["environment_overrides"] = [
                {"q": 0, "r": 0, "temperature": "hot", "humidity": "dry", "weather": "clear"},
            ]
            first_route = with_environment["connections"][0]
            first_route["anchors"] = [dict(first_route["cells"][0]), dict(first_route["cells"][-1])]
            self.assertEqual([], content_manager.save_world_layout(candidate_root, with_environment))
            saved_with_environment = content_manager.load_world_layout(candidate_root)
            self.assertEqual("hot", saved_with_environment["environment_overrides"][0]["temperature"])
            self.assertEqual(2, len(saved_with_environment["connections"][0]["anchors"]))
            invalid_environment = json.loads(json.dumps(saved_with_environment))
            invalid_environment["environment_overrides"].append({"q": 0, "r": 0, "weather": "monsoon"})
            issues = content_manager.save_world_layout(candidate_root, invalid_environment)
            self.assertTrue(any("기후 오버라이드" in issue.message or "weather" in issue.message for issue in issues))
            self.assertEqual(saved_with_environment, content_manager.load_world_layout(candidate_root))
            invalid_anchor = json.loads(json.dumps(saved_with_environment))
            invalid_anchor["connections"][0]["anchors"].insert(1, {"q": 999, "r": 999})
            issues = content_manager.save_world_layout(candidate_root, invalid_anchor)
            self.assertTrue(any(issue.path.endswith(".anchors") for issue in issues))
            self.assertEqual(saved_with_environment, content_manager.load_world_layout(candidate_root))
            generation_two = {
                "$schema": "../schemas/hex-world.schema.json",
                "schema_version": 2,
                "id": "cobbleventure:world/generation_2",
                "dimension": "cobbleventure:generation_2",
                "seed_salt": 1702,
                "grid": {"orientation": "pointy_top", "tile_radius_blocks": 64, "map_radius_cells": 6, "origin": {"x": 0, "y": 69, "z": 0}},
                "empty_terrain": {"default_type": "high_forest", "tiles": []},
                "tiles": [], "settlements": [], "connections": [],
            }
            self.assertEqual([], content_manager.save_world_layout(candidate_root, generation_two, 2))
            self.assertEqual(generation_two, content_manager.load_world_layout(candidate_root, 2))
            self.assertEqual([1, 2], content_manager.list_world_generations(candidate_root))

    def test_generation_one_uses_kanto_location_names_and_layout(self) -> None:
        root = Path(__file__).parents[3]
        expected_names = {
            "starter_town": "태초마을",
            "route_01_town": "상록시티",
            "crimson_town": "회색시티",
            "cerulean_city": "블루시티",
            "vermilion_city": "갈색시티",
            "lavender_town": "보라타운",
            "celadon_city": "무지개시티",
            "saffron_city": "노랑시티",
            "fuchsia_city": "연분홍시티",
            "tidehaven_town": "홍련마을",
            "skyreach_town": "석영고원",
        }
        for slug, expected_name in expected_names.items():
            settlement = json.loads(
                (root / "content" / "settlements" / "generation_1" / f"{slug}.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(expected_name, settlement["display_name"]["ko_kr"])
            self.assertNotIn("biome", settlement)

        layout = content_manager.load_world_layout(root)
        anchors = {
            node["settlement"].rsplit("/", 1)[-1]: node["anchor"]
            for node in layout["settlements"]
        }
        self.assertGreater(anchors["starter_town"]["r"], anchors["route_01_town"]["r"])
        self.assertGreater(anchors["route_01_town"]["r"], anchors["crimson_town"]["r"])
        self.assertLess(anchors["celadon_city"]["q"], anchors["saffron_city"]["q"])
        self.assertGreater(anchors["lavender_town"]["q"], anchors["saffron_city"]["q"])
        self.assertGreater(anchors["tidehaven_town"]["r"], anchors["starter_town"]["r"])

        nodes = layout["settlements"]
        self.assertTrue(all(node.get("town_biome") for node in nodes))
        for index, node in enumerate(nodes):
            for other in nodes[index + 1:]:
                q1, r1 = node["anchor"]["q"], node["anchor"]["r"]
                q2, r2 = other["anchor"]["q"], other["anchor"]["r"]
                distance = (abs(q1 - q2) + abs(r1 - r2) + abs((-q1 - r1) - (-q2 - r2))) // 2
                minimum = node["town_radius_cells"] + other["town_radius_cells"] + 2
                self.assertGreaterEqual(distance, minimum)

        cinnabar = anchors["tidehaven_town"]
        ocean_tiles = {
            (tile["q"], tile["r"])
            for tile in layout["empty_terrain"]["tiles"]
            if tile["type"] == "ocean"
        }
        nearby_ocean = 0
        for q, r in ocean_tiles:
            distance = (
                abs(q - cinnabar["q"])
                + abs(r - cinnabar["r"])
                + abs((-q - r) - (-cinnabar["q"] - cinnabar["r"]))
            ) // 2
            if distance <= 2:
                nearby_ocean += 1
        self.assertGreaterEqual(nearby_ocean, 3)
        self.assertGreaterEqual(len(layout["tiles"]), 40)

    def test_web_command_stops_only_matching_previous_content_manager(self) -> None:
        root = Path(__file__).parents[3]
        build_script = (root / "build.bat").read_text(encoding="utf-8")
        stop_script = (
            root / "tools" / "content-manager" / "stop_existing_server.ps1"
        ).read_text(encoding="utf-8")
        self.assertIn("stop_existing_server.ps1", build_script)
        self.assertIn("-ManagerPath", build_script)
        self.assertIn("$command.Contains($managerNeedle)", stop_script)
        self.assertIn("$command.Contains($rootNeedle)", stop_script)
        self.assertIn("Stop-Process -Id $_.ProcessId", stop_script)

    def test_biome_catalog_contains_all_pokemon_and_profiles(self) -> None:
        root = Path(__file__).parents[3]
        pokemon = content_manager.load_pokemon_habitats(root)
        biomes = content_manager.load_biome_catalog(root)
        self.assertEqual(1025, len(pokemon["pokemon"]))
        self.assertEqual(12, len(biomes["profiles"]))
        self.assertEqual([], content_manager.validate_biome_catalogs(root))

    def test_biome_preview_filters_generation_and_unconditional_bypasses_rules(self) -> None:
        root = Path(__file__).parents[3]
        filtered = content_manager.preview_biome(
            root,
            {"profile_id": "cobbleventure:biome_profile/plains", "settings": {"generation": 1}},
        )
        self.assertGreater(filtered["count"], 0)
        self.assertTrue(all(entry["generation"] == 1 for entry in filtered["pokemon"]))
        forced = content_manager.preview_biome(
            root,
            {
                "profile_id": "cobbleventure:biome_profile/plains",
                "settings": {"generation": 1},
                "unconditional_spawns": ["cobblemon:arceus"],
            },
        )
        arceus = next(entry for entry in forced["pokemon"] if entry["id"] == "cobblemon:arceus")
        self.assertEqual("unconditional", arceus["match_reason"])

    def test_settlements_reference_web_biome_settings(self) -> None:
        root = Path(__file__).parents[3]
        settlement = content_manager.load_json(
            root / "content" / "settlements" / "generation_1" / "starter_town.json"
        )
        self.assertEqual(
            "cobbleventure:biome_set/starter_region",
            settlement["content_profile"]["pokemon"]["biome_set"],
        )
        self.assertIn("spawn_settings", settlement["biome_layout"]["zones"][0])

    def test_example_content_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        content_id, issues = content_manager.validate_content_file(
            root / "content" / "source" / "examples" / "ai_test.json"
        )
        self.assertEqual("cobbleventure:trainer/ai_test", content_id)
        self.assertEqual([], issues)

    def test_starter_town_leader_content_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        content_id, issues = content_manager.validate_content_file(
            root / "content" / "source" / "examples" / "starter_town_leader.json"
        )
        self.assertEqual("cobbleventure:trainer/starter_town_leader", content_id)
        self.assertEqual([], issues)

    def test_trainer_owned_placement_is_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["placement"] = {}
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("마을의 npc_placement" in issue.message for issue in issues))

    def test_missing_dialogue_target_is_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["dialogue"]["entry"] = "cobbleventure:dialogue/missing"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(source), encoding="utf-8")
            _, issues = content_manager.validate_content_file(path)
        self.assertTrue(any("존재하지 않는 대화 ID" in issue.message for issue in issues))

    def test_invalid_ev_total_is_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["team"][0]["evs"] = {
            "hp": 252,
            "attack": 252,
            "speed": 252,
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(source), encoding="utf-8")
            _, issues = content_manager.validate_content_file(path)
        self.assertTrue(any("EV 합계" in issue.message for issue in issues))

    def test_duplicate_pokemon_aspects_are_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["team"][0]["aspects"] = ["alolan", "alolan"]
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("aspects는 중복" in issue.message for issue in issues))

    def test_pokemon_cannot_hold_regular_and_gimmick_items_together(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        pokemon = source["battle"]["team"][0]
        pokemon["held_item"] = "cobblemon:choice_band"
        pokemon["gimmick"] = {
            "type": "mega_evolution",
            "item": "mega_showdown:charizardite_x",
        }
        source["battle"]["mechanics"]["mega_evolution"] = True
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("동시에 지정" in issue.message for issue in issues))

    def test_pokemon_gimmick_requires_matching_battle_mechanic(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["team"][0]["gimmick"] = {
            "type": "z_move",
            "item": "mega_showdown:normalium_z",
        }
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("같은 전투 기믹" in issue.message for issue in issues))

    def test_invalid_battle_bag_limits_are_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["rules"]["max_item_uses"] = -1
        source["battle"]["bag"] = [
            {"item": "cobblemon:potion", "quantity": 0}
        ]
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("max_item_uses" in issue.path for issue in issues))
        self.assertTrue(any("bag[0].quantity" in issue.path for issue in issues))

    def test_battle_format_difficulty_and_ai_profile_are_restricted(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["format"] = "GEN_9_DOUBLES"
        source["battle"]["ai"]["difficulty"] = "impossible"
        source["battle"]["ai"]["strategy"] = "unknown"
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("전투 방식이 일치" in issue.message for issue in issues))
        self.assertTrue(any("AI 난이도" in issue.message for issue in issues))
        self.assertTrue(any("AI 전략" in issue.message for issue in issues))

    def test_cheater_probability_is_required_and_restricted(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["ai"]["difficulty"] = "cheater"
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("치터 확률" in issue.message for issue in issues))

        source["battle"]["ai"]["options"]["cheat_probability"] = 0.35
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertEqual([], issues)

        source["battle"]["ai"]["difficulty"] = "expert_search"
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("치터 난이도에서만" in issue.message for issue in issues))

    def test_invalid_tera_type_is_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["team"][0]["tera_type"] = "not_a_type"
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("지원하는 포켓몬 타입" in issue.message for issue in issues))

    def test_starter_town_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        settlement_id, issues = content_manager.validate_settlement_file(
            root / "content" / "settlements" / "generation_1" / "starter_town.json"
        )
        self.assertEqual("cobbleventure:settlement/starter_town", settlement_id)
        self.assertEqual([], issues)

    def test_route_01_town_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        settlement_id, issues = content_manager.validate_settlement_file(
            root / "content" / "settlements" / "generation_1" / "route_01_town.json"
        )
        self.assertEqual("cobbleventure:settlement/route_01_town", settlement_id)
        self.assertEqual([], issues)

    def test_settlement_requires_valid_structure_profile(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (
                root
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["structure_profile"]["required_facilities"] = {}

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertTrue(any("하나 이상의 필수 시설" in issue.message for issue in issues))

    def test_settlement_rejects_invalid_village_and_house_styles(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (
                root
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["structure_profile"]["village_preset"] = "unknown"
        source["structure_profile"]["commercial_center"] = "shopping_mall"
        source["structure_profile"]["house_style"] = "invalid pool"

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        locations = {issue.path for issue in issues if issue.level == "error"}
        self.assertIn("$.structure_profile.village_preset", locations)
        self.assertIn("$.structure_profile.commercial_center", locations)
        self.assertIn("$.structure_profile.house_style", locations)

    def test_settlement_accepts_new_layout_without_legacy_village_fields(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"].pop("village_preset", None)
        source["structure_profile"].pop("starter_layout", None)
        source["structure_profile"].pop("house_style", None)

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        legacy_paths = {
            "$.structure_profile.village_preset",
            "$.structure_profile.starter_layout",
            "$.structure_profile.house_style",
        }
        self.assertFalse(any(issue.path in legacy_paths for issue in issues))

    def test_settlement_rejects_empty_or_unknown_house_palette_values(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["generation_profile"]["house_palette"] = {
            "bases": [], "roofs": ["tower"], "roof_colors": ["red", "red"],
        }

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        locations = {issue.path for issue in issues if issue.level == "error"}
        prefix = "$.structure_profile.generation_profile.house_palette"
        self.assertIn(f"{prefix}.bases", locations)
        self.assertIn(f"{prefix}.roofs", locations)
        self.assertIn(f"{prefix}.roof_colors", locations)

    def test_settlement_accepts_one_two_and_five_story_house_bases(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["generation_profile"]["house_palette"] = {
            "bases": ["one_story", "two_story", "five_story"],
            "roofs": ["gable"],
            "roof_colors": ["red"],
        }

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertEqual([], [issue for issue in issues if issue.level == "error"])

    def test_special_district_allows_reserved_empty_plot(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["special_district"]["building"] = {
            "enabled": False, "id": "future_lab", "structure": "",
        }

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertEqual([], [issue for issue in issues if issue.level == "error"])

    def test_enabled_special_building_requires_resource_id(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["special_district"]["building"].update({
            "enabled": True, "structure": "",
        })

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertTrue(any(
            issue.path == "$.structure_profile.special_district.building.structure"
            for issue in issues if issue.level == "error"
        ))

    def test_gym_can_be_disabled_without_leader_or_structure(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["gym"].update({
            "enabled": False, "structure": "", "leader_trainer_id": "",
        })
        source["structure_profile"]["facility_placements"] = [
            placement
            for placement in source["structure_profile"]["facility_placements"]
            if placement.get("id") != "gym_building"
        ]
        source["npc_placement"]["trainer_slots"] = []

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertEqual([], [issue for issue in issues if issue.level == "error"])

    def test_new_settlement_uses_automatic_special_building_defaults(self) -> None:
        document = content_manager._settlement_template("new_town", "새 마을", "generation_1")

        district = document["structure_profile"]["special_district"]
        self.assertFalse(district["enabled"])
        self.assertEqual("auto", district["placement_mode"])
        self.assertEqual({"width": 8, "depth": 8}, district["footprint"])
        self.assertFalse(district["building"]["enabled"])
        self.assertNotIn("id", district["building"])
        self.assertNotIn("entrance_direction", district)
        self.assertFalse(document["structure_profile"]["gym"]["enabled"])
        self.assertNotIn("gym_entrance_offset", document["structure_profile"])
        self.assertNotIn("entrance_offset", document["structure_profile"]["gym"])
        self.assertIn("special_district", document["anchors"])
        self.assertIn("gym_building", document["anchors"])
        self.assertNotIn("biome", document)

    def test_settlement_supports_exactly_one_biome(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["biome_layout"]["zones"].append({
            "id": "second", "biome": "minecraft:desert", "size_blocks": 64,
            "placement": "outer", "weight": 1,
        })
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )
        self.assertTrue(any("정확히 1개" in issue.message for issue in issues))

    def test_settlement_level_scaling_requires_ordered_range(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["content_profile"]["level_scaling"].update({
            "min_level": 20, "base_level": 10, "max_level": 15,
        })
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )
        self.assertTrue(any("min_level <= base_level <= max_level" in issue.message for issue in issues))

    def test_settlement_connections_belong_to_world_map(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["connections"].append({
            "id": "legacy_gate",
            "target_settlement": "cobbleventure:settlement/route_01_town",
            "placement": {"mode": "toward_target", "preferred_side": "east", "offset": 0},
            "gate_width": 9,
            "path_width": 3,
        })
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )
        self.assertTrue(any("월드맵" in issue.message for issue in issues))

    def test_settlement_rejects_unknown_gym_theme(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (
                root
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["structure_profile"]["gym_theme"] = "rainbow"

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertTrue(any("체육관 타입 테마" in issue.message for issue in issues))

    def test_direct_facility_requires_existing_anchor(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["facility_placements"] = [{
            "id": "test_facility",
            "mode": "direct_template",
            "structure": "cobbleventure:test/facility",
            "anchor": "missing",
        }]

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertTrue(any("존재하는 마을 앵커" in issue.message for issue in issues))

    def test_nbt_placeholder_facilities_satisfy_checked_quantity(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["layout_shape"] = "radial"
        source["structure_profile"]["facility_requirements"] = [{
            "id": "hotel", "label": "호텔", "count": 2, "required": True,
            "footprint": {"width": 32, "depth": 32, "height": 20},
        }]
        for index in (1, 2):
            anchor = f"facility_hotel_{index}"
            source["anchors"][anchor] = {"x": index * 40, "y": 69, "z": 80}
            source["structure_profile"].setdefault("facility_placements", []).append({
                "id": anchor,
                "facility_type": "hotel",
                "mode": "direct_template",
                "structure": "cobbleventure:placeholder/hotel",
                "anchor": anchor,
                "label": f"호텔 {index}",
                "footprint": {"width": 32, "depth": 32, "height": 20},
            })

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertFalse(any("플레이스홀더" in issue.message for issue in issues))
        self.assertFalse(any("지원하지 않는 시설 배치 방식" in issue.message for issue in issues))

    def test_starter_town_rejects_center_and_commercial_facility(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["pokemon_center_enabled"] = True
        source["structure_profile"]["commercial_center"] = "pokemart"

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        locations = {issue.path for issue in issues if issue.level == "error"}
        self.assertIn("$.structure_profile.pokemon_center_enabled", locations)
        self.assertIn("$.structure_profile.commercial_center", locations)

    def test_settlement_rejects_invalid_road_profile(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["road_profile"] = {
            "width": 16,
            "material": "diamond_block",
        }

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        locations = {issue.path for issue in issues if issue.level == "error"}
        self.assertIn("$.structure_profile.road_profile.width", locations)
        self.assertIn("$.structure_profile.road_profile.material", locations)

    def test_checked_facility_quantity_must_match_placeholders(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["facility_requirements"] = [{
            "id": "hotel", "label": "호텔", "count": 2, "required": True,
            "footprint": {"width": 32, "depth": 32, "height": 20},
        }]

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertTrue(any("2개가 필요하지만 플레이스홀더는 0개" in issue.message for issue in issues))

    def test_instanced_facility_requires_existing_anchors(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "settlements" / "generation_1" / "starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["facility_placements"] = [{
            "id": "gym_interior",
            "mode": "instanced_entry",
            "structure": "rgs:pewter_gym",
            "entry_anchor": "missing",
            "return_anchor": "gym_return",
            "instance_origin": {"x": 2048, "y": 69, "z": 0},
            "instance_entry_offset": {"x": 12, "y": 4, "z": 4},
            "instance_exit_offset": {"x": 12, "y": 4, "z": 1},
            "trigger_radius": 1.75,
        }]

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertTrue(any("존재하는 마을 앵커" in issue.message for issue in issues))

    def test_settlement_trainer_slot_requires_trainer_and_spawn_policy(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (
                root
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["npc_placement"]["trainer_slots"] = [self._valid_settlement_trainer_slot()]
        slot = source["npc_placement"]["trainer_slots"][0]
        slot.pop("trainer_id")
        slot["spawn_policy"] = "unknown"
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )
        self.assertTrue(any("trainer_id" in issue.path for issue in issues))
        self.assertTrue(any("생성 정책" in issue.message for issue in issues))

    def test_settlement_double_battle_requires_two_easy_npc_members(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (
                root
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["npc_placement"]["trainer_slots"] = [self._valid_settlement_trainer_slot()]
        slot = source["npc_placement"]["trainer_slots"][0]
        slot["battle_type"] = "doubles"

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertTrue(any("정확히 2명" in issue.message for issue in issues))

    def test_settlement_double_battle_accepts_two_easy_npc_members(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (
                root
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["npc_placement"]["trainer_slots"] = [self._valid_settlement_trainer_slot()]
        slot = source["npc_placement"]["trainer_slots"][0]
        slot["battle_type"] = "doubles"
        partner = json.loads(json.dumps(slot["members"][0]))
        partner["id"] = "partner"
        partner["position"]["x"] += 2
        slot["members"].append(partner)

        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )

        self.assertEqual([], issues)

    def test_trainer_class_catalog_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        issues = content_manager.validate_trainer_class_catalog(
            root / "content" / "catalogs" / "trainer-classes.json"
        )
        self.assertEqual([], issues)

    def test_trainer_class_catalog_covers_common_classes_and_child_scale(self) -> None:
        root = Path(__file__).parents[3]
        catalog = content_manager.load_json(
            root / "content" / "catalogs" / "trainer-classes.json"
        )
        classes = {entry["id"].rsplit("/", 1)[-1]: entry for entry in catalog["classes"]}
        self.assertGreaterEqual(len(classes), 50)
        self.assertEqual("짧은치마", classes["lass"]["display_name"]["ko_kr"])
        self.assertLess(classes["youngster"]["body"]["height_scale"], 1)
        self.assertEqual("child", classes["preschooler"]["body"]["age_group"])
        self.assertTrue(
            all(
                entry["default_appearance"]["implementation_status"] == "ready"
                for entry in classes.values()
            )
        )
        general_classes = [
            entry
            for entry in classes.values()
            if entry["category"] not in {"boss", "custom"}
        ]
        self.assertGreaterEqual(len(general_classes), 60)
        self.assertTrue(
            all(
                entry["default_appearance"]["implementation_status"] == "ready"
                for entry in general_classes
            )
        )
        custom_defaults = [
            entry for entry in classes.values()
            if entry["default_appearance"]["source"] == "custom"
        ]
        for entry in custom_defaults:
            appearance = entry["default_appearance"]
            slug = appearance["resource"].rsplit("/", 1)[-1]
            manifest = content_manager.load_json(
                root / "tools" / "content-manager" / "skin-pipeline" / "work" / slug / "manifest.json"
            )
            self.assertEqual(entry["body"]["arm_model"], manifest["model"])
            self.assertTrue(
                (
                    root
                    / "projects"
                    / "cobbleventure-world-bootstrap"
                    / "src"
                    / "main"
                    / "resources"
                    / "assets"
                    / "cobbleventure"
                    / "textures"
                    / "entity"
                    / "trainer"
                    / f"{slug}.png"
                ).is_file()
            )

    def test_requested_custom_and_rct_appearance_options_exist_with_gender_models(self) -> None:
        root = Path(__file__).parents[3]
        catalog = content_manager.load_json(root / "content" / "catalogs" / "trainer-classes.json")
        classes = {entry["id"].rsplit("/", 1)[-1]: entry for entry in catalog["classes"]}
        expected_models = {
            "lass": "slim", "bug_catcher": "classic", "school_kid": "classic",
            "twins": "slim", "camper": "classic", "picnicker": "slim",
            "fisherman": "classic", "sailor": "classic", "swimmer_male": "classic",
            "swimmer_female": "slim", "bird_keeper": "classic", "tamer": "classic",
            "hex_maniac": "slim", "aroma_lady": "slim",
            "pokemon_ranger_male": "classic", "pokemon_ranger_female": "slim",
            "collector": "classic", "worker": "classic", "rich_boy": "classic",
            "madame": "slim", "young_couple_male": "classic",
            "young_couple_female": "slim", "ace_trainer_male": "classic",
            "ace_trainer_female": "slim", "ace_trainer_gen6_male": "classic",
            "ace_trainer_gen6_female": "slim", "veteran_male": "classic",
            "veteran_female": "slim", "interviewers_male": "classic",
            "interviewers_female": "slim", "expert": "classic",
        }
        self.assertNotIn("bug_catcher_female", classes)
        self.assertNotIn("double_team_male", classes)
        self.assertNotIn("double_team_female", classes)
        custom_only = {"ace_trainer_gen6_male", "ace_trainer_gen6_female", "veteran_female"}
        skin_root = root / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources" / "assets" / "cobbleventure" / "textures" / "entity" / "trainer"
        for slug, model in expected_models.items():
            with self.subTest(slug=slug):
                entry = classes[slug]
                appearances = [entry["default_appearance"], *entry["appearance_options"]]
                custom = next(option for option in appearances if option["source"] == "custom")
                rct = next((option for option in appearances if option["source"] == "rct_single"), None)
                self.assertEqual(f"cobbleventure:trainer_skin/{slug}", custom["resource"])
                if slug in custom_only:
                    self.assertIsNone(rct)
                else:
                    self.assertTrue(rct["resource"].startswith("rctmod:trainers/single/"))
                rct_defaults = {"hex_maniac", "interviewers_female"}
                self.assertEqual("rct_single" if slug in rct_defaults else "custom", entry["default_appearance"]["source"])
                self.assertEqual(model, entry["body"]["arm_model"])
                manifest = content_manager.load_json(root / "tools" / "content-manager" / "skin-pipeline" / "work" / slug / "manifest.json")
                self.assertEqual(model, manifest["model"])
                self.assertTrue((skin_root / f"{slug}.png").is_file())

        for slug, model in {"old_couple_male": "classic", "old_couple_female": "slim"}.items():
            entry = classes[slug]
            self.assertEqual("custom", entry["default_appearance"]["source"])
            self.assertEqual(f"cobbleventure:trainer_skin/{slug}", entry["default_appearance"]["resource"])
            self.assertEqual(model, entry["body"]["arm_model"])
            self.assertNotIn("appearance_options", entry)
        self.assertNotIn("pokemon_ranger", classes)
        self.assertNotIn("old_couple", classes)
        self.assertNotIn("interviewers", classes)
        self.assertEqual(
            "rctmod:trainers/single/young_couple_nat_047f",
            classes["hex_maniac"]["default_appearance"]["resource"],
        )
        self.assertEqual(
            "rctmod:trainers/single/interviewers_roxy_03fe",
            classes["interviewers_female"]["default_appearance"]["resource"],
        )

    def test_trainer_outfit_catalog_links_equipment_and_easy_npc_scale(self) -> None:
        root = Path(__file__).parents[3]
        classes = content_manager.load_json(root / "content" / "catalogs" / "trainer-classes.json")
        class_ids = {entry["id"] for entry in classes["classes"]}
        issues = content_manager.validate_trainer_outfit_catalog(
            root / "content" / "catalogs" / "trainer-outfits.json", class_ids
        )
        self.assertEqual([], issues)
        catalog = content_manager.load_json(root / "content" / "catalogs" / "trainer-outfits.json")
        youngster = catalog["outfits"][0]
        self.assertEqual("cobbleventure_bootstrap:youngster_cap", youngster["equipment"]["head"]["item"])
        self.assertEqual(0.78, youngster["adapters"]["easy_npc"]["root_scale"])

    def test_trainer_roster_covers_generational_organizations_genders_and_named_roles(self) -> None:
        root = Path(__file__).parents[3]
        character_ids, issues = content_manager.validate_trainer_roster_catalog(
            root / "content" / "catalogs" / "trainer-roster.json"
        )
        self.assertEqual([], issues)
        roster = content_manager.load_json(root / "content" / "catalogs" / "trainer-roster.json")
        organizations = {entry["id"].rsplit("/", 1)[-1]: entry for entry in roster["organizations"]}
        self.assertEqual(set(range(1, 10)), {generation for entry in organizations.values() for generation in entry["generations"]})
        self.assertTrue({"team_rocket", "team_aqua", "team_magma", "team_galactic", "team_plasma", "team_flare", "team_skull", "team_yell", "team_star"}.issubset(organizations))
        self.assertTrue(all({"male", "female"}.issubset({grunt["gender"] for grunt in entry["grunt_variants"]}) for entry in organizations.values()))
        self.assertIn("cobbleventure:character/giovanni", character_ids)
        self.assertIn("cobbleventure:character/cyrus", character_ids)
        self.assertIn("cobbleventure:character/cynthia", character_ids)
        implemented_named = {
            character["id"].rsplit("/", 1)[-1]: character["appearance"]
            for organization in roster["organizations"]
            for character in organization["named_characters"]
        }
        self.assertEqual(
            "cobbleventure:trainer_skin/archie",
            implemented_named["archie"]["resource"],
        )
        self.assertEqual(
            "cobbleventure:trainer_skin/maxie",
            implemented_named["maxie"]["resource"],
        )
        self.assertEqual(
            "rctmod:trainers/single/team_galactic_charon",
            implemented_named["charon"]["resource"],
        )
        self.assertTrue(
            all(
                implemented_named[slug]["implementation_status"] == "ready"
                for slug in ("proton", "petrel", "archie", "maxie", "charon")
            )
        )
        self.assertEqual(
            "cobbleventure:trainer_skin/proton",
            implemented_named["proton"]["resource"],
        )
        self.assertEqual(
            "cobbleventure:trainer_skin/petrel",
            implemented_named["petrel"]["resource"],
        )
        roles = {entry["role"] for entry in roster["league_characters"]}
        self.assertEqual({"gym_leader", "elite_four", "champion"}, roles)
        self.assertGreaterEqual(sum(entry["appearance"]["asset_status"] == "verified" for entry in roster["league_characters"]), 35)
        organization_characters = [
            character
            for organization in roster["organizations"]
            for group in ("grunt_variants", "named_characters")
            for character in organization[group]
        ]
        self.assertTrue(
            all(
                character["appearance"]["implementation_status"] == "ready"
                for character in organization_characters
            )
        )
        custom_characters = [
            character
            for character in organization_characters
            if character["appearance"]["source"] == "custom"
        ]
        for character in custom_characters:
            appearance = character["appearance"]
            slug = appearance["resource"].rsplit("/", 1)[-1]
            self.assertEqual("verified", appearance["asset_status"])
            self.assertTrue(
                (
                    root
                    / "projects"
                    / "cobbleventure-world-bootstrap"
                    / "src"
                    / "main"
                    / "resources"
                    / "assets"
                    / "cobbleventure"
                    / "textures"
                    / "entity"
                    / "trainer"
                    / f"{slug}.png"
                ).is_file()
            )

    def test_settlement_center_must_be_inside_bounds(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (
                root
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["center"]["x"] = 9999
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )
        self.assertTrue(any("마을 경계 안" in issue.message for issue in issues))

    def test_managed_path_rejects_directory_escape(self) -> None:
        root = Path(__file__).parents[3]
        with self.assertRaises(ValueError):
            content_manager._managed_path(root, "trainers", "../outside.json")

    def test_settlement_save_is_validated_before_overwrite(self) -> None:
        repository = Path(__file__).parents[3]
        source = json.loads(
            (
                repository
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["id"] = "cobbleventure:settlement/save_test"
        relative_path = "content/settlements/tests/save_test.json"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target, issues = content_manager._save_document(
                root, "settlements", relative_path, source
            )
            self.assertEqual([], issues)
            self.assertIsNotNone(target)
            self.assertEqual(source, content_manager.load_json(target))

            invalid = json.loads(json.dumps(source))
            invalid["center"]["x"] = 9999
            _, issues = content_manager._save_document(
                root, "settlements", relative_path, invalid
            )
            self.assertTrue(any("마을 경계 안" in issue.message for issue in issues))
            self.assertEqual(source, content_manager.load_json(target))

    def test_deletes_unreferenced_settlement_document(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "content/settlements/generation_1/delete_me.json"
            target.parent.mkdir(parents=True)
            target.write_text(
                json.dumps({"id": "cobbleventure:settlement/delete_me"}),
                encoding="utf-8",
            )

            deleted, references = content_manager._delete_settlement_document(
                root, "content/settlements/generation_1/delete_me.json"
            )

            self.assertEqual(target, deleted)
            self.assertEqual([], references)
            self.assertFalse(target.exists())

    def test_refuses_to_delete_referenced_settlement_document(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "content/settlements/generation_1/keep_me.json"
            target.parent.mkdir(parents=True)
            target.write_text(
                json.dumps({"id": "cobbleventure:settlement/keep_me"}),
                encoding="utf-8",
            )
            world = root / "content/worlds/generation_1.json"
            world.parent.mkdir(parents=True)
            world.write_text(
                json.dumps({
                    "settlements": [
                        {"settlement": "cobbleventure:settlement/keep_me"}
                    ]
                }),
                encoding="utf-8",
            )

            preserved, references = content_manager._delete_settlement_document(
                root, "content/settlements/generation_1/keep_me.json"
            )

            self.assertEqual(target, preserved)
            self.assertEqual(["content/worlds/generation_1.json"], references)
            self.assertTrue(target.exists())

    def test_new_trainer_template_is_valid(self) -> None:
        template = content_manager._trainer_template("route_01", "길목 트레이너")
        content_id, issues = content_manager._validate_payload(
            template, content_manager.validate_content_file
        )
        self.assertEqual("cobbleventure:trainer/route_01", content_id)
        self.assertEqual([], issues)
        self.assertNotIn("placement", template)
        self.assertEqual(2, template["schema_version"])
        self.assertEqual("standard", template["battle"]["ai"]["difficulty"])
        self.assertEqual("balanced", template["battle"]["ai"]["strategy"])

    def test_generate_exports_same_ai_profile_to_rct_and_runtime(self) -> None:
        root = Path(__file__).parents[3]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "generated"
            result = content_manager.generate_content(root, output)
            self.assertGreaterEqual(result["count"], 2)
            rct = content_manager.load_json(
                output / "rct" / "data" / "rctmod" / "trainers" / "ai_test.json"
            )
            runtime = content_manager.load_json(
                output / "cobbleventure" / "ai-profiles" / "ai_test.json"
            )
            self.assertEqual("cobbleventure", rct["ai"]["type"])
            self.assertEqual("standard", rct["ai"]["data"]["difficulty"])
            self.assertEqual("balanced", rct["ai"]["data"]["strategy"])
            self.assertEqual("standard", runtime["difficulty"])
            self.assertEqual("balanced", runtime["strategy"])

    def test_cheater_probability_is_exported_for_runtime_use(self) -> None:
        root = Path(__file__).parents[3]
        source = content_manager.load_json(
            root / "content" / "source" / "examples" / "ai_test.json"
        )
        source["battle"]["ai"] = {
            "controller": "cobbleventure",
            "difficulty": "cheater",
            "strategy": "ace_check",
            "options": {"cheat_probability": 0.35},
        }
        rct = content_manager.export_rct_trainer(source)
        runtime = content_manager.export_ai_runtime_profile(source)
        self.assertEqual(0.35, rct["ai"]["data"]["cheatProbability"])
        self.assertEqual(0.35, runtime["options"]["cheatProbability"])

    def test_create_document_writes_valid_template_and_rejects_duplicate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trainer_path, trainer_issues = content_manager._create_document(
                root, "trainers", "route_01", "길목 트레이너"
            )
            settlement_path, settlement_issues = content_manager._create_document(
                root, "settlements", "forest_town", "숲 마을", "generation_1"
            )
            self.assertEqual([], trainer_issues)
            self.assertEqual([], settlement_issues)
            self.assertTrue(trainer_path.is_file())
            self.assertTrue(settlement_path.is_file())

            _, duplicate_issues = content_manager._create_document(
                root, "trainers", "route_01", "중복 트레이너"
            )
            self.assertTrue(any("이미 존재" in issue.message for issue in duplicate_issues))

    def test_strict_pack_rejects_draft_lock(self) -> None:
        root = Path(__file__).parents[3]
        issues = content_manager.validate_dependency_lock(
            root / "pack" / "dependencies.lock.json", strict_pack=True
        )
        self.assertTrue(any("draft" in issue.message for issue in issues))

    def test_cobblemon_additions_content_pack_is_registered(self) -> None:
        root = Path(__file__).parents[3]
        dependency_lock = content_manager.load_json(
            root / "pack" / "dependencies.lock.json"
        )
        content_pack = next(
            item
            for item in dependency_lock["content_packs"]
            if item["id"] == "cobblemon_additions"
        )
        self.assertTrue(content_pack["selected"])
        self.assertEqual("4.2.1", content_pack["version"])
        self.assertEqual("fabric_mod", content_pack["artifact_format"])
        self.assertEqual("ready", content_pack["packaging_status"])
        self.assertEqual("ready", content_pack["runtime_status"])
        self.assertEqual("W2pr9jyL", content_pack["modrinth"]["project_id"])
        self.assertEqual("9PMzbD4o", content_pack["modrinth"]["version_id"])

    def test_server_full_dex_and_mega_addons_are_pinned_in_development_pack(self) -> None:
        root = Path(__file__).parents[3]
        dependency_lock = content_manager.load_json(
            root / "pack" / "dependencies.lock.json"
        )
        mods = {item["id"]: item for item in dependency_lock["mods"]}
        expected_files = {
            "accessories": (938917, 7046407),
            "owo_lib": (532610, 6416633),
            "mega_showdown": (1189523, 8519042),
            "paxi_neoforge": (1015157, 6485740),
            "yungs_api_neoforge": (1015100, 6715463),
        }

        for mod_id, (project_id, file_id) in expected_files.items():
            with self.subTest(mod_id=mod_id):
                mod = mods[mod_id]
                self.assertTrue(mod["enabled"])
                self.assertEqual("required", mod["classification"])
                self.assertEqual("both", mod["side"])
                self.assertEqual(project_id, mod["curseforge"]["project_id"])
                self.assertEqual(file_id, mod["curseforge"]["file_id"])

        profile = content_manager.load_json(
            root / "pack" / "profiles" / "development-placeholder.json"
        )
        profile_files = {
            (entry["projectID"], entry["fileID"]) for entry in profile["files"]
        }
        self.assertTrue(set(expected_files.values()).issubset(profile_files))
        self.assertNotIn(
            "allthemons_x_mega_showdown_datapack",
            {item["id"] for item in dependency_lock["content_packs"]},
        )

        expected_cccc_sha1 = "b37e878f7e5539bfd145ca0fe9d63bcfef0a128c"
        expected_fix_sha1 = "9d20719aea859c9f20dfffccf3c30b756a419581"
        paxi_root = (
            root
            / "pack"
            / "overrides"
            / "development-placeholder"
            / "config"
            / "paxi"
        )
        cccc_paths = [
            paxi_root / folder / "CCCC-1.7.2.zip"
            for folder in ("datapacks", "resourcepacks")
        ]
        fix_paths = [
            path.parent / "ZA-Mega-Staraptor-Contrary-Fix.zip"
            for path in cccc_paths
        ]
        for cccc_path in cccc_paths:
            with self.subTest(cccc_path=cccc_path):
                self.assertTrue(cccc_path.is_file())
                self.assertEqual(
                    expected_cccc_sha1,
                    hashlib.sha1(cccc_path.read_bytes()).hexdigest(),
                )
        for fix_path in fix_paths:
            with self.subTest(fix_path=fix_path):
                self.assertTrue(fix_path.is_file())
                self.assertEqual(
                    expected_fix_sha1,
                    hashlib.sha1(fix_path.read_bytes()).hexdigest(),
                )

        self.assertLess(
            "CCCC-1.7.2.zip",
            "ZA-Mega-Staraptor-Contrary-Fix.zip",
        )
        with zipfile.ZipFile(cccc_paths[0]) as archive:
            self.assertIn("LICENSE", archive.namelist())
            self.assertIn("Credits.txt", archive.namelist())
        with zipfile.ZipFile(fix_paths[0]) as archive:
            staraptor = json.loads(
                archive.read(
                    "data/cobblemon/species_additions/generation4/staraptor_mega.json"
                )
            )
        mega_form = staraptor["forms"][0]
        self.assertIn("contrary", mega_form["abilities"])
        self.assertEqual("fighting", mega_form["primaryType"])
        self.assertEqual("flying", mega_form["secondaryType"])

    def test_local_api_health_and_validation(self) -> None:
        root = Path(__file__).parents[3]
        server = content_manager.ThreadingHTTPServer(
            ("127.0.0.1", 0), content_manager.create_handler(root)
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base_url = f"http://127.0.0.1:{server.server_port}"
            with urllib.request.urlopen(f"{base_url}/health") as response:
                health = json.load(response)
            with urllib.request.urlopen(f"{base_url}/validate") as response:
                validation = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/dashboard") as response:
                dashboard = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/trainers") as response:
                trainers = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/settlements") as response:
                settlements = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/trainer-classes") as response:
                trainer_classes = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/trainer-roster") as response:
                trainer_roster = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/trainer-reference-entries") as response:
                trainer_references = json.load(response)
            with urllib.request.urlopen(
                f"{base_url}/api/trainer-skin?resource=cobbleventure%3Atrainer_skin%2Funimplemented"
            ) as response:
                trainer_skin = response.read()
            with urllib.request.urlopen(
                f"{base_url}/api/trainer-reference-local?slug=colress"
            ) as response:
                trainer_reference = response.read()
            with urllib.request.urlopen(
                f"{base_url}/trainer-assets/references/colress.png"
            ) as response:
                trainer_reference_static = response.read()
            with urllib.request.urlopen(
                f"{base_url}/api/trainer-skin?resource=trainer-reference%3Acolress"
            ) as response:
                trainer_reference_skin_api = response.read()
            with urllib.request.urlopen(f"{base_url}/api/editor-catalog") as response:
                editor_catalog = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/biome-catalog") as response:
                biome_catalog = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/pokemon-habitats") as response:
                pokemon_habitats = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/world-layout") as response:
                world_layout = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/world-layouts") as response:
                world_layouts = json.load(response)
            preview_request = urllib.request.Request(
                f"{base_url}/api/biome-preview",
                data=json.dumps({"set_id": "cobbleventure:biome_set/starter_region"}).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(preview_request) as response:
                biome_preview = json.load(response)
            with urllib.request.urlopen(base_url) as response:
                page = response.read().decode("utf-8")
            with urllib.request.urlopen(f"{base_url}/app.js") as response:
                app_script = response.read().decode("utf-8")
            with urllib.request.urlopen(f"{base_url}/styles.css") as response:
                styles = response.read().decode("utf-8")
            with urllib.request.urlopen(
                f"{base_url}/pokemon-entry-clipboard.mjs"
            ) as response:
                clipboard_module = response.read().decode("utf-8")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        self.assertEqual("ok", health["status"])
        self.assertTrue(trainer_skin.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertTrue(trainer_reference.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertTrue(trainer_reference_static.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertTrue(trainer_reference_skin_api.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertGreaterEqual(len(trainer_classes["classes"]), 50)
        self.assertGreaterEqual(len(trainer_roster["organizations"]), 10)
        self.assertGreaterEqual(len(trainer_roster["league_characters"]), 50)
        self.assertEqual(535, len(trainer_references["entries"]))
        self.assertEqual(
            {"another_red", "rct_default"},
            {entry["source"] for entry in trainer_references["entries"]},
        )
        reference_by_id = {entry["id"]: entry for entry in trainer_references["entries"]}
        self.assertEqual(20, len(trainer_roster["battle_reference_defaults"]))
        self.assertTrue(all(
            default["entry"] in reference_by_id
            and reference_by_id[default["entry"]]["source"] == "another_red"
            and reference_by_id[default["entry"]]["entry_number"] == 1
            for default in trainer_roster["battle_reference_defaults"]
        ))
        self.assertTrue(validation["valid"])
        self.assertGreaterEqual(dashboard["trainers"], 2)
        self.assertTrue(all(item["battle_type"] in {"singles", "doubles"} for item in trainers["items"]))
        self.assertEqual(11, dashboard["settlements"])
        self.assertEqual(11, len(settlements["items"]))
        self.assertEqual(11, len(world_layout["settlements"]))
        starter_summary = next(item for item in settlements["items"] if item["id"] == "cobbleventure:settlement/starter_town")
        starter_world_node = next(item for item in world_layout["settlements"] if item["settlement"] == starter_summary["id"])
        self.assertEqual(starter_world_node["town_biome"], starter_summary["biome"])
        self.assertGreater(len(world_layout["connections"]), 0)
        self.assertGreater(len(world_layout["empty_terrain"]["tiles"]), 0)
        self.assertIn(1, world_layouts["generations"])
        self.assertGreaterEqual(len(trainer_classes["classes"]), 10)
        self.assertGreaterEqual(len(editor_catalog["species"]), 1000)
        self.assertGreaterEqual(len(editor_catalog["moves"]), 900)
        self.assertEqual(12, len(biome_catalog["profiles"]))
        self.assertEqual(1025, len(pokemon_habitats["pokemon"]))
        self.assertGreater(biome_preview["count"], 0)
        self.assertTrue(any(entry.get("forme") for entry in editor_catalog["species"]))
        self.assertTrue(
            any(entry["id"] == "cobblemon:potion" for entry in editor_catalog["bagItems"])
        )
        self.assertIn("Cobbleventure Content Studio", page)
        self.assertIn("바이옴 관리", page)
        self.assertIn("육각형 기반 월드 미니맵", page)
        self.assertIn("세대 추가", page)
        self.assertIn('id="worlds"', page)
        self.assertIn('id="settlements"', page)
        self.assertIn("마을 바이옴", page)
        self.assertIn("form.elements.townBiome", app_script)
        self.assertNotIn("node.town_biome = settlementPresetBiome", app_script)
        self.assertIn("마을 프리셋", page)
        self.assertNotIn("마을 동선 · 입구와 출구", page)
        self.assertNotIn("바이옴 2 — 선택", page)
        self.assertIn("엔트리 JSON 복사", page)
        self.assertIn("전투 가방", page)
        self.assertIn("듀얼배틀은 같은 전투에 참여할 EasyNPC 2명", page)
        self.assertIn('<select name="battleFormat"', page)
        self.assertIn('<select name="battleDifficulty"', page)
        self.assertIn('value="expert_winrate"', page)
        self.assertIn('value="expert_search"', page)
        self.assertIn('name="cheatProbability"', page)
        self.assertIn('<select name="battleAi"', page)
        self.assertIn("normalizeTrainerAi", app_script)
        self.assertIn("saveWorldLayout", app_script)
        self.assertIn("renderHexMap", app_script)
        self.assertIn("primaryRouteAt", app_script)
        self.assertNotIn("connection.route_biome", app_script)
        self.assertIn("is-route-terrain", app_script)
        self.assertNotIn('name="kind" value="route"', page)
        self.assertNotIn('name="routeBiome"', page)
        self.assertIn('data-map-tool="route"', page)
        self.assertIn("handleRoutePoint", app_script)
        self.assertIn("바이옴과 독립된 길", app_script)
        self.assertIn("routeDraft", app_script)
        self.assertIn('id="undo-route-anchor"', page)
        self.assertIn("routeCellsFromAnchors", app_script)
        self.assertIn("route-anchor", styles)
        self.assertIn("data-select-route", app_script)
        self.assertIn("hex-route-hit", styles)
        self.assertIn("data-delete-route-inline", app_script)
        self.assertIn("route-anchor-actions", styles)
        self.assertIn("objects", app_script)
        self.assertNotIn("migrateLegacyRouteBaseTiles", app_script)
        self.assertIn("finishSettlementDrag", app_script)
        self.assertIn("visibleHexCells", app_script)
        self.assertIn("beginMapPan", app_script)
        self.assertIn("settlementFootprintAt", app_script)
        self.assertIn("마을 사용 범위", page)
        self.assertIn('id="empty-terrain-brush-type"', page)
        self.assertIn('data-map-tool="biome"', page)
        self.assertIn('data-map-tool="climate"', page)
        self.assertIn('data-map-tool="eraser"', page)
        self.assertNotIn('id="route-from"', page)
        self.assertNotIn('id="route-to"', page)
        self.assertNotIn('id="create-auto-route"', page)
        self.assertIn('id="route-manager-list"', page)
        self.assertIn("두 마을을 자동 경로로 연결", app_script)
        self.assertIn("paintEmptyTerrainArea", app_script)
        self.assertIn("emptyTerrainSymbol", app_script)
        self.assertIn("empty-terrain-red-hatch", app_script)
        self.assertIn("empty-type-ocean", styles)
        self.assertIn("repeating-linear-gradient", styles)
        self.assertIn("paintBiomeArea", app_script)
        self.assertIn("paintClimateArea", app_script)
        self.assertIn("environment_overrides", app_script)
        self.assertIn("snow_mountain", app_script)
        self.assertNotIn('name="townRadius"', page)
        self.assertIn('name="townRadiusCells"', page)
        self.assertLess(page.index('id="save-settlement"'), page.index('id="settlement-form"'))
        self.assertIn('name="specialDistrictPlacementMode"', page)
        self.assertNotIn('name="specialDistrictEnabled"', page)
        self.assertNotIn('name="specialBuildingId"', page)
        self.assertNotIn('name="specialDistrictEntrance"', page)
        self.assertNotIn('name="gymEntranceX"', page)
        self.assertNotIn('name="gymEntranceY"', page)
        self.assertNotIn('name="gymEntranceZ"', page)
        self.assertNotIn("마을 중심 좌표", page)
        self.assertNotIn("<legend>마을 경계</legend>", page)
        self.assertIn("syncConnectionPaths", app_script)
        self.assertIn("cheat_probability", app_script)
        self.assertIn("PokeAPI/sprites/master/sprites/pokemon", app_script)
        self.assertIn("trainerReferenceSprites", app_script)
        self.assertIn("본가 디자인 기준", app_script)
        self.assertIn("현재 Minecraft 외형", app_script)
        self.assertIn("rosterCharacterOptions", app_script)
        self.assertIn("rosterCharactersForClass", app_script)
        self.assertIn("rosterRolesByClass", app_script)
        self.assertIn("syncTrainerSlotMembers", app_script)
        self.assertIn('name="rosterCharacter"', page)
        self.assertIn("youngster-gen4", app_script)
        self.assertIn("trainerCharacterReferenceSprites", app_script)
        for villain_admin in ("archer", "ariana", "proton", "petrel", "mars", "jupiter", "saturn", "charon"):
            self.assertIn(f'{villain_admin}: "{villain_admin}"', app_script)
        self.assertIn('[`local:${characterSlug}`, mappedCharacterSprite]', app_script)
        self.assertIn("characterVisualMatchStatus", app_script)
        self.assertIn("1차 스킨 검토 필요", app_script)
        self.assertIn("trainerClassAppearanceForSource", app_script)
        self.assertIn('rosterCharacter?.gender === "male"', app_script)
        self.assertIn('rosterCharacter?.gender === "female"', app_script)
        self.assertIn("/api/trainer-reference?sprite=", app_script)
        self.assertIn("trainer-reference:", app_script)
        self.assertIn("/api/trainer-skin?resource=", app_script)
        self.assertNotIn("https://gitlab.com/srcmc/rct/mod/-/raw/1.21.1/common", app_script)
        self.assertIn("trainer-reference-image", styles)
        self.assertIn("world-map-viewport", styles)
        self.assertIn("hex-settlement", styles)
        self.assertIn("hex-route", styles)
        self.assertIn("is-route-terrain", styles)
        self.assertIn("other/official-artwork", app_script)
        self.assertIn("pokeapi.co/api/v2/pokemon", app_script)
        self.assertIn("pokemonCatalogDisplayName", app_script)
        self.assertIn("pokemonCatalogDescription", app_script)
        self.assertIn("pokemonFormLabel", app_script)
        self.assertIn("pokemonCatalogDisplayName(entry), pokemonFormLabel(entry.forme)", app_script)
        self.assertIn("escapeHtml(pokemonCatalogDisplayName(entry))", app_script)
        self.assertIn("escapeHtml(pokemonFormLabel(entry.forme))", app_script)
        self.assertIn('pokemon?.shiny ? "shiny/" : ""', app_script)
        self.assertNotIn('name="aspects"', app_script)
        self.assertIn("Array.isArray(pokemon.aspects)", app_script)
        self.assertIn("move-type-badge", app_script)
        self.assertIn('loading="lazy"', app_script)
        self.assertIn("const resultCards = rows.map", app_script)
        self.assertIn("hydrateChoicePokemonArt(rows)", app_script)
        self.assertNotIn("처음 120개 표시", app_script)
        self.assertIn("pokemonDisplayName", app_script)
        self.assertIn("normalizePokemonStats", app_script)
        self.assertIn("remainingEvs = 510", app_script)
        self.assertIn("moveSelectedPokemon", app_script)
        self.assertIn("move-pokemon-left", app_script)
        self.assertIn("focusedMoveCard", app_script)
        self.assertIn("moveCatalogEntry", app_script)
        self.assertIn("input.dataset.value ?? input.value", app_script)
        self.assertIn(".party-order-toolbar", styles)
        self.assertIn(".focused-move-meta", styles)
        self.assertIn(".focused-move-description", styles)
        self.assertIn(".move-type-badge.type-fire", styles)
        self.assertIn(".move-type-badge.type-fairy", styles)
        self.assertIn(".choice-tags b.move-type-badge", styles)
        self.assertIn(".focused-pokemon-preview [hidden]", styles)
        self.assertIn(".bag-settings > label input, .bag-item-row input", styles)
        self.assertIn("color-scheme: light", styles)
        self.assertIn("Desktop authoring UI readability scale", styles)
        self.assertIn(".bag-item-description strong { font-size: 14px; }", styles)
        self.assertIn(".focused-move-description,", styles)
        self.assertIn("POKEMON_ENTRY_CLIPBOARD_SCHEMA", clipboard_module)

    def test_build_api_uses_allowlisted_runner(self) -> None:
        root = Path(__file__).parents[3]
        server = content_manager.ThreadingHTTPServer(
            ("127.0.0.1", 0), content_manager.create_handler(root)
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        result = {
            "command": "validate",
            "description": "검사",
            "success": True,
            "return_code": 0,
            "output": "검증 성공",
        }
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/api/build",
                data=json.dumps({"command": "validate"}).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with mock.patch.object(content_manager, "_run_build", return_value=result) as runner:
                with urllib.request.urlopen(request) as response:
                    payload = json.load(response)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        runner.assert_called_once_with(root.resolve(), "validate")
        self.assertTrue(payload["success"])

    def test_document_creation_api(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = content_manager.ThreadingHTTPServer(
                ("127.0.0.1", 0), content_manager.create_handler(root)
            )
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                request = urllib.request.Request(
                    f"http://127.0.0.1:{server.server_port}/api/documents",
                    data=json.dumps(
                        {
                            "category": "trainers",
                            "slug": "api_trainer",
                            "name": "API 트레이너",
                            "generation": "generation_1",
                        }
                    ).encode("utf-8"),
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(request) as response:
                    payload = json.load(response)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)
            self.assertTrue(payload["created"])
            self.assertTrue((root / payload["path"]).is_file())


if __name__ == "__main__":
    unittest.main()

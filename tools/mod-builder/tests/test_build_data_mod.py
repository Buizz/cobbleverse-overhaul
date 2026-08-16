from __future__ import annotations

import importlib.util
import gzip
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).parents[1] / "build_data_mod.py"
REPOSITORY_ROOT = MODULE_PATH.parents[2]
PROJECT_ROOT = REPOSITORY_ROOT / "content-projects" / "cobbleventure-main"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("build_data_mod", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
build_data_mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = build_data_mod
SPEC.loader.exec_module(build_data_mod)


class TownNpcCapacityUnitTests(unittest.TestCase):
    def test_town_indoor_capacity_counts_reachable_slots_in_placed_houses(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            settings = project / "content/catalogs/building-settings.json"
            settings.parent.mkdir(parents=True)
            settings.write_text(json.dumps({
                "schema_version": 1,
                "buildings": {
                    "cobbleventure:houses/one_story_shed": {
                        "citizen_placement_allowed": True,
                        "interiors": [{
                            "key": "room",
                            "structure": "cobbleventure:interiors/test_room",
                        }],
                        "door_routes": {
                            "exterior:front": {"space": "room", "door": "door"},
                        },
                    },
                },
            }), encoding="utf-8")
            sidecar = project / "content/structures/interiors/test_room.structure.json"
            sidecar.parent.mkdir(parents=True)
            sidecar.write_text(json.dumps({
                "schema_version": 1,
                "anchors": [
                    {"type": "npc_position", "label": "resident_1", "position": [1, 1, 1]},
                    {"type": "npc_position", "label": "resident_2", "position": [2, 1, 1]},
                ],
            }), encoding="utf-8")
            data = {
                "npc_placement": {
                    "auto_place_npcs": True,
                    "max_ambient_npcs": 1,
                    "trainer_population": {
                        "enabled": True,
                        "max_active": 2,
                        "placement_areas": ["indoor"],
                    },
                },
            }
            layout = {"houses": [{
                "id": "house_1", "base": "one_story", "roof": "shed",
                "roof_color": "red",
            }], "facilities": {}}

            capacity = build_data_mod._town_indoor_npc_capacity(
                Path(directory), data, layout, project_root=project,
            )

            self.assertEqual(2, capacity["available"])
            self.assertEqual(3, capacity["requested"])

    def test_town_trainer_placement_respects_indoor_only_setting(self) -> None:
        placements = build_data_mod._town_npc_placement_records(
            [], ["test:npc/a", "test:npc/b"], ["indoor"],
        )

        self.assertEqual(["indoor", "indoor"], [item["placement_area"] for item in placements])


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

    def test_automatic_npc_profiles_prioritize_biome_then_level(self) -> None:
        profiles = [
            {"npc": "test:npc/neutral", "classification": "trainer", "expected_level": 10, "preferred_biomes": [], "automatic_route_placement": True},
            {"npc": "test:npc/forest", "classification": "trainer", "expected_level": 18, "preferred_biomes": ["minecraft:forest"], "automatic_route_placement": True},
            {"npc": "test:npc/desert", "classification": "trainer", "expected_level": 10, "preferred_biomes": ["minecraft:desert"], "automatic_route_placement": True},
        ]
        ranked = build_data_mod._rank_npc_profiles(
            profiles, classification="trainer", level=10,
            biomes={"minecraft:forest"}, target="route",
        )
        self.assertEqual("test:npc/forest", ranked[0])
        self.assertEqual("test:npc/desert", ranked[-1])

    def test_direct_trainers_override_biome_defaults_without_duplicates(self) -> None:
        profiles = [
            {"npc": "test:npc/forest", "classification": "trainer", "expected_level": 12, "preferred_biomes": ["minecraft:forest"], "automatic_route_placement": True},
            {"npc": "test:npc/direct", "classification": "trainer", "expected_level": 30, "preferred_biomes": ["minecraft:desert"], "automatic_route_placement": False},
        ]
        resolved = build_data_mod._resolved_trainer_ids(
            profiles,
            {"use_biome_defaults": True, "direct_trainers": ["test:npc/direct", "test:npc/forest"]},
            level=12,
            biomes={"minecraft:forest"},
            target="route",
        )
        self.assertEqual(["test:npc/direct", "test:npc/forest"], resolved)

    def test_town_npc_placement_keeps_residents_inside(self) -> None:
        placements = build_data_mod._town_npc_placement_records(
            ["test:npc/resident"], ["test:npc/trainer_a", "test:npc/trainer_b"],
        )
        self.assertEqual("indoor", placements[0]["placement_area"])
        self.assertEqual(
            {"indoor", "outdoor"},
            {entry["placement_area"] for entry in placements if entry["classification"] == "trainer"},
        )

    def test_settlement_auto_npc_level_comes_from_world_map(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            world_path = root / "content/worlds/generation_1.json"
            world_path.parent.mkdir(parents=True)
            world_path.write_text(json.dumps({
                "level_overrides": [{"q": 3, "r": -2, "average_level": 37}],
                "settlements": [{
                    "settlement": "test:settlement/town",
                    "anchor": {"q": 3, "r": -2},
                }],
            }), encoding="utf-8")
            with mock.patch.object(build_data_mod, "HEX_WORLD_CONFIG_DIR", Path("content/worlds")):
                levels = build_data_mod._settlement_world_levels(root)

        self.assertEqual({"test:settlement/town": 37}, levels)

    def test_route_presets_are_packaged_and_merged_into_world_connections(self) -> None:
        output = REPOSITORY_ROOT / build_data_mod.OUTPUT
        preset_path = output / "data/cobbleventure/routes/generation_1/route_custom_03.json"
        world_path = output / "data/cobbleventure/hex_worlds/generation_1.json"
        self.assertTrue(preset_path.is_file())
        world = json.loads(world_path.read_text(encoding="utf-8"))
        route = next(connection for connection in world["connections"] if connection["id"] == "route_custom_03")
        self.assertEqual("cobbleventure:route/route_custom_03", route["route_preset"])
        self.assertEqual("road", route["surface_style"])
        self.assertEqual(12, route["corridor_width_blocks"])
        self.assertEqual({"mode": "world", "offset": 0}, route["level_scaling"])
        self.assertEqual([], route["npc_placements"])

    def test_settlement_data_uses_authored_load_order(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "content/settlements/generation_1"
            source.mkdir(parents=True)
            entries = [
                ("alpha.json", "cobbleventure:settlement/alpha", 3),
                ("beta.json", "cobbleventure:settlement/beta", 1),
                ("gamma.json", "cobbleventure:settlement/gamma", 2),
            ]
            for filename, settlement_id, load_order in entries:
                (source / filename).write_text(json.dumps({
                    "schema_version": 3,
                    "id": settlement_id,
                    "load_order": load_order,
                }), encoding="utf-8")
            with mock.patch.object(build_data_mod, "SETTLEMENT_CONFIG_DIR", Path("content/settlements")):
                settlements = build_data_mod._settlement_data(root)

            self.assertEqual(
                ["cobbleventure:settlement/beta", "cobbleventure:settlement/gamma", "cobbleventure:settlement/alpha"],
                [data["id"] for _, data in settlements],
            )

    def test_world_bootstrap_uses_settlement_load_order(self) -> None:
        source = (
            REPOSITORY_ROOT
            / "projects/cobbleventure-world-bootstrap/src/main/java/dev/buizz/cobbleventure/bootstrap/CobbleventureBootstrap.java"
        ).read_text(encoding="utf-8")
        self.assertIn("Comparator.comparingInt(SettlementPlan::loadOrder)", source)
        self.assertIn("int loadOrder", source)

    def test_packages_generated_rct_trainer_data(self) -> None:
        output = REPOSITORY_ROOT / build_data_mod.OUTPUT
        self.assertTrue((output / "data/rctmod/trainers/ai_test.json").is_file())
        self.assertTrue(
            (output / "data/cobbleventure/ai-profiles/ai_test.json").is_file()
        )

    def test_materializes_gym_leader_runtime_references_from_league_entries(self) -> None:
        source = json.loads((PROJECT_ROOT / "content/catalogs/gyms.json").read_text(encoding="utf-8"))
        generated = json.loads((
            REPOSITORY_ROOT / build_data_mod.OUTPUT / build_data_mod.GYM_CATALOG_ENTRY
        ).read_text(encoding="utf-8"))

        self.assertTrue(all("trainer_id" not in gym["staff"]["leader"] for gym in source["gyms"]))
        self.assertTrue(all("badge_id" not in gym["staff"]["leader"] for gym in source["gyms"]))
        self.assertTrue(all(gym["staff"]["leader"]["trainer_id"].startswith("cobbleventure:npc/gym_leader/") for gym in generated["gyms"]))
        self.assertTrue(all(gym["staff"]["leader"]["badge_id"].startswith("cobbleventure:badge/") for gym in generated["gyms"]))

    def test_town_layout_rerolls_until_required_facilities_fit(self) -> None:
        source = {"id": "cobbleventure:settlement/test", "structure_profile": {"generation_profile": {"seed": 17}}}
        failure = build_data_mod.TownFacilityPlacementError(source["id"], "gym_building")
        with mock.patch.object(
            build_data_mod,
            "_compile_town_layout_attempt",
            side_effect=[failure, {"roads": [], "facilities": {}, "houses": []}],
        ) as attempt:
            layout = build_data_mod._compile_town_layout(source)

        self.assertEqual(1, layout["reroll_count"])
        self.assertEqual(17, layout["requested_seed"])
        self.assertEqual(build_data_mod._town_layout_reroll_seed(17, 1), layout["resolved_seed"])
        self.assertEqual(2, attempt.call_count)

    def test_town_layout_reroll_has_a_hard_limit(self) -> None:
        source = {"id": "cobbleventure:settlement/test", "structure_profile": {"generation_profile": {"seed": 17}}}
        failure = build_data_mod.TownFacilityPlacementError(source["id"], "gym_building")
        with mock.patch.object(
            build_data_mod,
            "_compile_town_layout_attempt",
            side_effect=failure,
        ) as attempt:
            with self.assertRaisesRegex(build_data_mod.ModBuildError, "자동 리롤 8회"):
                build_data_mod._compile_town_layout(source)

        self.assertEqual(build_data_mod.TOWN_LAYOUT_REROLL_LIMIT, attempt.call_count)

    def test_town_center_uses_four_seeded_t_patterns(self) -> None:
        patterns = [build_data_mod._town_layout_center_pattern("branching", seed) for seed in range(1, 5)]

        self.assertEqual(
            ["tee_east", "tee_west", "tee_north", "tee_south"],
            [pattern[0] for pattern in patterns],
        )
        self.assertTrue(all(len(pattern[1]) == 3 for pattern in patterns))
        self.assertEqual(4, len({pattern[1] for pattern in patterns}))

    def test_every_town_size_uses_the_selected_tiles_centroid_as_road_hub(self) -> None:
        for cell_count in (1, 3, 5, 7, 19):
            self.assertEqual((0, 0), build_data_mod._town_layout_hub(cell_count))

    def test_multi_tile_towns_extend_an_internal_street_through_each_outer_cell(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        profile = source["structure_profile"]
        profile["pokemon_center_enabled"] = False
        profile["commercial_center"] = "none"
        profile["facility_placements"] = []
        profile.setdefault("gym", {})["enabled"] = False

        for cell_count, shape in ((3, "triangle_up"), (5, "five_up"), (7, "line_q")):
            with self.subTest(cell_count=cell_count, shape=shape):
                source["town_radius_cells"] = cell_count
                source["town_footprint_shape"] = shape
                layout = build_data_mod._compile_town_layout(source)
                for q, r in build_data_mod._town_layout_cells(cell_count, shape):
                    center_x, center_z = build_data_mod._town_layout_centered_cell_center(
                        q, r, cell_count, shape
                    )
                    target_x = round(center_x / 16) * 16
                    target_z = round(center_z / 16) * 16
                    if (target_x, target_z) == (layout["hub"]["x"], layout["hub"]["z"]):
                        continue
                    self.assertTrue(any(
                        (
                            road["z1"] == road["z2"] == target_z
                            and min(road["x1"], road["x2"]) < target_x
                            < max(road["x1"], road["x2"])
                        ) or (
                            road["x1"] == road["x2"] == target_x
                            and min(road["z1"], road["z2"]) < target_z
                            < max(road["z1"], road["z2"])
                        )
                        for road in layout["roads"]
                    ), f"{cell_count}칸 {shape}의 ({q}, {r}) 타일 내부 도로가 없습니다.")

    def test_tile_coverage_roads_do_not_restore_the_missing_center_arm(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["layout_shape"] = "branching"
        source["structure_profile"]["road_layout_template"] = "cross"
        layout = build_data_mod._compile_town_layout_attempt(source, 1)
        hub = (layout["hub"]["x"], layout["hub"]["z"])
        arms: set[str] = set()
        for road in layout["roads"]:
            start = (road["x1"], road["z1"])
            end = (road["x2"], road["z2"])
            if start == hub:
                target = end
            elif end == hub:
                target = start
            else:
                continue
            if target[0] > hub[0]: arms.add("east")
            if target[0] < hub[0]: arms.add("west")
            if target[1] > hub[1]: arms.add("south")
            if target[1] < hub[1]: arms.add("north")

        self.assertEqual("tee_east", layout["center_pattern"])
        self.assertEqual({"north", "east", "south"}, arms)

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

    def test_compiles_town_layout_inside_hexagon_with_required_civic_facilities(self) -> None:
        settlement_path = (
            REPOSITORY_ROOT
            / build_data_mod.OUTPUT
            / "data/cobbleventure/settlements/generation_1/route_01_town.json"
        )
        settlement = json.loads(settlement_path.read_text(encoding="utf-8"))
        layout = settlement["compiled_layout"]

        self.assertEqual("hex_tiles", layout["shape"])
        self.assertIn(layout["cell_count"], (1, 3, 5, 7, 19))
        self.assertTrue(layout["roads"])
        self.assertIn("facility_pokemon_center", layout["facilities"])
        self.assertIn("facility_pokemart", layout["facilities"])
        for plot in [*layout["facilities"].values(), *layout["houses"]]:
            self.assertTrue(build_data_mod._plot_inside_town_layout(plot, layout["cell_count"], layout["footprint_shape"]))
            self.assertTrue(all(
                not build_data_mod._plot_intersects_road(
                    plot,
                    road,
                    settlement["structure_profile"].get("road_profile", {}).get("width", 7),
                    0.0,
                )
                for road in layout["roads"]
            ))

    def test_compiled_houses_reference_generated_palette_variants(self) -> None:
        settlement_path = (
            REPOSITORY_ROOT / build_data_mod.OUTPUT
            / "data/cobbleventure/settlements/generation_1/route_01_town.json"
        )
        settlement = json.loads(settlement_path.read_text(encoding="utf-8"))
        houses = settlement["compiled_layout"]["houses"]
        access_roads = settlement["compiled_layout"]["access_roads"]
        self.assertTrue(houses)
        house_ids = {house["id"] for house in houses}
        house_access_roads = [
            road for road in access_roads if road["building"] in house_ids
        ]
        self.assertGreaterEqual(len(house_access_roads), len(houses))
        for house in houses:
            self.assertIn(house["base"], build_data_mod.HOUSE_BASES)
            self.assertIn(house["roof"], build_data_mod.HOUSE_ROOFS)
            self.assertIn(house["roof_color"], build_data_mod.HOUSE_ROOF_BLOCKS)
            namespace, resource = house["structure"].split(":", 1)
            generated = (
                REPOSITORY_ROOT / build_data_mod.OUTPUT
                / "data" / namespace / "structure" / f"{resource}.nbt"
            )
            self.assertTrue(generated.is_file(), house["structure"])
            self.assertIn(
                house["rotation"],
                {"none", "clockwise_90", "clockwise_180", "counterclockwise_90"},
            )
            self.assertIn("entrance", house)
            self.assertIn("road_connection", house)

        access_by_house = {
            house["id"]: [
                road for road in house_access_roads
                if road["building"] == house["id"]
            ]
            for house in houses
        }
        for house in houses:
            accesses = access_by_house[house["id"]]
            self.assertIn(len(accesses), {1, 2})
            self.assertEqual(
                (accesses[0]["x1"], accesses[0]["z1"]),
                (house["road_connection"]["x"], house["road_connection"]["z"]),
            )
            self.assertEqual(
                (accesses[-1]["x2"], accesses[-1]["z2"]),
                (house["entrance"]["x"], house["entrance"]["z"]),
            )
            for index, access in enumerate(accesses):
                self.assertTrue(access["x1"] == access["x2"] or access["z1"] == access["z2"])
                if index > 0:
                    self.assertEqual(
                        (accesses[index - 1]["x2"], accesses[index - 1]["z2"]),
                        (access["x1"], access["z1"]),
                    )
                for other in houses:
                    if other["id"] == house["id"]:
                        continue
                    self.assertFalse(
                        build_data_mod._plot_intersects_road(other, access, 3, 0.25),
                        f"{access['building']} 진입로가 {other['id']} 건물을 가로지릅니다.",
                    )

        self.assertEqual({"one_story", "two_story", "five_story"}, set(build_data_mod.HOUSE_BASES))
        self.assertTrue(all(house["width"] == 16 and house["depth"] == 16 for house in houses))

    def test_house_access_uses_rotated_door_safe_spawn(self) -> None:
        expected = {
            "none": (114, 202),
            "clockwise_90": (113, 214),
            "clockwise_180": (101, 213),
            "counterclockwise_90": (102, 201),
        }
        for rotation, entrance in expected.items():
            plot = {
                "id": "house_test",
                "x": 100.0,
                "z": 200.0,
                "width": 16,
                "depth": 16,
                "base": "one_story",
                "roof": "gable",
                "rotation": rotation,
            }
            self.assertEqual(
                entrance,
                build_data_mod._plot_entrance(plot, REPOSITORY_ROOT),
            )

    def test_house_bases_are_one_two_and_five_story_sixteen_block_plots(self) -> None:
        self.assertEqual(
            {"one_story": 1, "two_story": 2, "five_story": 5},
            {base_id: int(definition["stories"]) for base_id, definition in build_data_mod.HOUSE_BASES.items()},
        )

    def test_town_layout_centers_selected_hex_tiles_on_origin(self) -> None:
        cases = [
            (3, "triangle_up", ()),
            (3, "line_q", ()),
            (5, "five_up", ()),
            (5, "five_down", ()),
            (7, "line_q", ()),
            (3, "custom", ((0, 0), (1, 0), (1, -1))),
        ]
        for cell_count, shape, custom_cells in cases:
            cells = build_data_mod._town_layout_cells(cell_count, shape, custom_cells)
            centers = [
                build_data_mod._town_layout_centered_cell_center(
                    q, r, cell_count, shape, custom_cells
                )
                for q, r in cells
            ]
            with self.subTest(cell_count=cell_count, shape=shape):
                self.assertAlmostEqual(0.0, sum(x for x, _ in centers) / len(centers))
                self.assertAlmostEqual(0.0, sum(z for _, z in centers) / len(centers))
                self.assertEqual((0, 0), build_data_mod._town_layout_hub(cell_count, shape, custom_cells))
        self.assertTrue(
            all(definition["size"][0::2] == (16, 16) for definition in build_data_mod.HOUSE_BASES.values())
        )
        self.assertEqual(
            [(16, 13, 16), (16, 18, 16), (16, 33, 16)],
            [
                build_data_mod.FACILITY_PLACEHOLDERS[f"basic_building_{index}"]["size"]
                for index in range(1, 4)
            ],
        )

    def test_every_generated_house_has_a_clear_entrance_road(self) -> None:
        settlement_root = (
            REPOSITORY_ROOT / build_data_mod.OUTPUT
            / "data/cobbleventure/settlements"
        )
        for settlement_path in settlement_root.rglob("*.json"):
            settlement = json.loads(settlement_path.read_text(encoding="utf-8"))
            layout = settlement["compiled_layout"]
            houses = {house["id"]: house for house in layout["houses"]}
            facilities = layout["facilities"]
            buildings = {**facilities, **houses}
            access_roads = layout["access_roads"]
            self.assertEqual(
                set(buildings), {road["building"] for road in access_roads},
                settlement["id"],
            )
            for access in access_roads:
                self.assertIn(access["building"], buildings)
                self.assertTrue(
                    access["x1"] == access["x2"] or access["z1"] == access["z2"],
                    f"{settlement['id']} / {access['building']}",
                )
                for other_id, other in buildings.items():
                    if other_id == access["building"]:
                        continue
                    self.assertFalse(
                        build_data_mod._plot_intersects_road(other, access, 3, 0.0),
                        f"{settlement['id']} / {access['building']} -> {other_id}",
                    )

    def test_department_store_plaza_connects_on_three_sides(self) -> None:
        settlement_path = (
            REPOSITORY_ROOT / build_data_mod.OUTPUT
            / "data/cobbleventure/settlements/generation_1/celadon_city.json"
        )
        layout = json.loads(settlement_path.read_text(encoding="utf-8"))["compiled_layout"]
        department_store = layout["facilities"]["facility_department_store"]
        entrances = department_store["plaza_entrances"]
        access_roads = [
            road for road in layout["access_roads"]
            if road["building"] == "facility_department_store"
        ]

        self.assertEqual(["north", "west", "east"], [entry["facing"] for entry in entrances])
        road_endpoints = {(road["x2"], road["z2"]) for road in access_roads}
        self.assertTrue(all((entry["x"], entry["z"]) in road_endpoints for entry in entrances))

        plot_z = float(department_store["z"])
        self.assertEqual(
            math.floor(plot_z + 0.5) + 19,
            next(entry["z"] for entry in entrances if entry["facing"] == "west"),
        )
        self.assertEqual(
            math.floor(plot_z + 0.5) + 19,
            next(entry["z"] for entry in entrances if entry["facing"] == "east"),
        )

    def test_department_store_is_not_forced_into_non_ring_town_center(self) -> None:
        source = json.loads(
            (
                PROJECT_ROOT
                / "content/settlements/generation_1/celadon_city.json"
            ).read_text(encoding="utf-8")
        )
        for road_template in ("cross", "grid", "spine"):
            with self.subTest(road_template=road_template):
                source["structure_profile"]["road_layout_template"] = road_template
                layout = build_data_mod._compile_town_layout(source)
                store = layout["facilities"]["facility_department_store"]
                hub_x, hub_z = layout["hub"]["x"], layout["hub"]["z"]
                self.assertFalse(
                    float(store["x"]) <= hub_x < float(store["x"]) + int(store["width"])
                    and float(store["z"]) <= hub_z < float(store["z"]) + int(store["depth"])
                )
                self.assertIn("road_connection", store)

    def test_generated_street_decorations_avoid_buildings_and_access_roads(self) -> None:
        generated_root = (
            REPOSITORY_ROOT / build_data_mod.OUTPUT
            / "data/cobbleventure/settlements/generation_1"
        )
        decoration_types: set[str] = set()
        for settlement_path in generated_root.glob("*.json"):
            layout = json.loads(settlement_path.read_text(encoding="utf-8"))["compiled_layout"]
            plots = [*layout["facilities"].values(), *layout["houses"]]
            for decoration in layout["decorations"]:
                decoration_types.add(decoration["type"])
                clearance = {
                    "street_lamp": 1,
                    "bench": 2,
                    "street_tree": 2,
                    "flower_bed": 2,
                    "fountain": 3,
                }[decoration["type"]]
                footprint = {
                    "x": decoration["x"] - clearance,
                    "z": decoration["z"] - clearance,
                    "width": clearance * 2 + 1,
                    "depth": clearance * 2 + 1,
                }
                self.assertFalse(any(
                    build_data_mod._plots_intersect(footprint, plot, 1.0)
                    for plot in plots
                ), settlement_path.name)
                self.assertFalse(any(
                    build_data_mod._plot_intersects_road(footprint, road, 3, 0.75)
                    for road in layout["access_roads"]
                ), settlement_path.name)
        self.assertEqual(
            {"street_lamp", "bench", "street_tree", "flower_bed", "fountain"},
            decoration_types,
        )

    def test_generated_town_decoration_structures_exist(self) -> None:
        structure_root = (
            REPOSITORY_ROOT / build_data_mod.OUTPUT
            / "data/cobbleventure/structure/town_decorations"
        )
        self.assertEqual(
            {f"{name}.nbt" for name in build_data_mod.TOWN_DECORATION_SIZES},
            {path.name for path in structure_root.glob("*.nbt")},
        )

    def test_builtin_facilities_use_actual_template_footprints(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        profile = source["structure_profile"]
        profile["pokemon_center_enabled"] = True
        profile["commercial_center"] = "department_store"
        profile.setdefault("gym", {})["enabled"] = True

        specs = {identifier: (width, depth) for identifier, width, depth in build_data_mod._compiled_facility_specs(source)}

        self.assertEqual((22, 23), specs["facility_pokemon_center"])
        self.assertEqual((40, 72), specs["facility_department_store"])
        self.assertEqual((25, 26), specs["gym_building"])
        self.assertNotIn("facility_pokemon_center_1", specs)

        layout = build_data_mod._compile_town_layout(source)
        center = layout["facilities"]["facility_pokemon_center"]
        self.assertEqual("west", center["entrance_facing"])
        self.assertEqual(
            {
                "x": math.floor(float(center["x"]) + 0.5) - 1,
                "z": math.floor(float(center["z"]) + 0.5) + 10,
            },
            center["entrance"],
        )

        profile["commercial_center"] = "pokemart"
        mart_layout = build_data_mod._compile_town_layout(source)
        mart = mart_layout["facilities"]["facility_pokemart"]
        self.assertEqual("east", mart["entrance_facing"])
        self.assertEqual(
            {
                "x": math.floor(float(mart["x"]) + 0.5) + 23,
                "z": math.floor(float(mart["z"]) + 0.5) + 15,
            },
            mart["entrance"],
        )

    def test_generated_houses_touch_their_assigned_road(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        layout = build_data_mod._compile_town_layout(source)
        road_half_width = source["structure_profile"].get("road_profile", {}).get("width", 7) / 2
        for house in layout["houses"]:
            connection = house["road_connection"]
            x, z = float(house["x"]), float(house["z"])
            width, depth = int(house["width"]), int(house["depth"])
            distance = {
                "north": z - connection["z"],
                "south": connection["z"] - (z + depth),
                "east": connection["x"] - (x + width),
                "west": x - connection["x"],
            }[house["entrance_facing"]]
            self.assertAlmostEqual(road_half_width, distance, places=2, msg=house["id"])

    def test_packed_density_places_at_least_as_many_houses_as_normal(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        generation = source.setdefault("structure_profile", {}).setdefault("generation_profile", {})
        generation["building_density"] = "normal"
        normal = build_data_mod._compile_town_layout(source)
        generation["building_density"] = "packed"
        packed = build_data_mod._compile_town_layout(source)

        self.assertEqual("packed", packed["building_density"])
        self.assertGreaterEqual(len(packed["houses"]), len(normal["houses"]))

    def test_town_layout_defaults_to_touching_building_density(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        source.get("structure_profile", {}).pop("generation_profile", None)

        layout = build_data_mod._compile_town_layout(source)

        self.assertEqual("packed", layout["building_density"])
        self.assertEqual(0.0, build_data_mod.BUILDING_DENSITY_PROFILES["packed"]["gap"])

    def test_generated_benches_face_the_nearest_town_road(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        layout = build_data_mod._compile_town_layout(source)
        benches = [
            decoration for decoration in layout["decorations"]
            if decoration["type"] == "bench"
        ]
        facing_by_rotation = {
            "none": (0, -1),
            "clockwise_90": (1, 0),
            "clockwise_180": (0, 1),
            "counterclockwise_90": (-1, 0),
        }

        self.assertTrue(benches)
        for bench in benches:
            x, z = int(bench["x"]), int(bench["z"])
            facing_x, facing_z = facing_by_rotation[bench["rotation"]]

            def road_distance_squared(point_x: int, point_z: int) -> float:
                distances = []
                for road in layout["roads"]:
                    min_x, max_x = sorted((road["x1"], road["x2"]))
                    min_z, max_z = sorted((road["z1"], road["z2"]))
                    nearest_x = min(max(point_x, min_x), max_x)
                    nearest_z = min(max(point_z, min_z), max_z)
                    distances.append(
                        (point_x - nearest_x) ** 2 + (point_z - nearest_z) ** 2
                    )
                return min(distances)

            self.assertLess(
                road_distance_squared(x + facing_x, z + facing_z),
                road_distance_squared(x, z),
                bench,
            )

    def test_compile_respects_single_house_palette_selection(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        source.setdefault("structure_profile", {}).setdefault("generation_profile", {}).update({
            "house_palette": {
                "bases": ["five_story"], "roofs": ["flat"], "roof_colors": ["black"],
            }
        })

        houses = build_data_mod._compile_town_layout(source)["houses"]

        self.assertTrue(houses)
        self.assertEqual(
            {("five_story", "flat", "black", "cobbleventure:houses/five_story_flat_black")},
            {(house["base"], house["roof"], house["roof_color"], house["structure"]) for house in houses},
        )

    def test_legacy_wide_house_palette_migrates_to_one_story(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        source.setdefault("structure_profile", {}).setdefault("generation_profile", {}).update({
            "house_palette": {
                "bases": ["wide"], "roofs": ["flat"], "roof_colors": ["black"],
            }
        })

        houses = build_data_mod._compile_town_layout(source)["houses"]

        self.assertTrue(houses)
        self.assertEqual({"one_story"}, {house["base"] for house in houses})
        self.assertTrue(all(house["width"] == 16 and house["depth"] == 16 for house in houses))

    def test_branching_layout_spreads_across_both_axes(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        source["structure_profile"]["layout_shape"] = "branching"

        roads = build_data_mod._compile_town_layout(source)["roads"]
        xs = [coordinate for road in roads for coordinate in (road["x1"], road["x2"])]
        zs = [coordinate for road in roads for coordinate in (road["z1"], road["z2"])]
        x_span = max(xs) - min(xs)
        z_span = max(zs) - min(zs)

        self.assertLess(min(xs), 0)
        self.assertGreater(max(xs), 0)
        self.assertLess(min(zs), 0)
        self.assertGreater(max(zs), 0)
        self.assertLessEqual(max(x_span, z_span) / min(x_span, z_span), 1.75)

    def test_five_cell_layout_uses_middle_row_and_only_selected_side(self) -> None:
        middle = {(-1, 0), (0, 0), (1, 0)}
        self.assertEqual(
            middle | {(0, -1), (1, -1)},
            set(build_data_mod._town_layout_cells(5, "five_up")),
        )
        self.assertEqual(
            middle | {(-1, 1), (0, 1)},
            set(build_data_mod._town_layout_cells(5, "five_down")),
        )

    def test_nineteen_cell_layout_is_complete_radius_two_hexagon(self) -> None:
        cells = set(build_data_mod._town_layout_cells(19))

        self.assertEqual(19, len(cells))
        self.assertTrue(all((abs(q) + abs(r) + abs(-q - r)) // 2 <= 2 for q, r in cells))

    def test_custom_layout_keeps_authored_tiles_and_connects_authored_exits(self) -> None:
        source = json.loads((PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(encoding="utf-8"))
        profile = source["structure_profile"]
        profile["pokemon_center_enabled"] = False
        profile["commercial_center"] = "none"
        profile["facility_placements"] = []
        profile.setdefault("gym", {})["enabled"] = False
        cells = ((0, 0), (1, 0), (1, -1), (2, -1), (2, -2))
        source["town_radius_cells"] = 5
        source["town_footprint_shape"] = "custom"
        source["town_footprint_cells"] = [{"q": q, "r": r} for q, r in cells]
        source["town_road_exits"] = [{"q": 0, "r": 0}, {"q": 2, "r": -2}]

        layout = build_data_mod._compile_town_layout(source)
        endpoints = {(road[key_x], road[key_z]) for road in layout["roads"] for key_x, key_z in (("x1", "z1"), ("x2", "z2"))}

        self.assertEqual([{"q": q, "r": r} for q, r in cells], layout["footprint_cells"])
        for q, r in ((0, 0), (2, -2)):
            self.assertIn(build_data_mod._town_layout_exit_point(q, r, 5, "custom", cells), endpoints)
        self.assertTrue(all(build_data_mod._plot_inside_town_layout(plot, 5, "custom", cells) for plot in layout["houses"]))

    def test_plot_must_fit_inside_selected_five_cell_union(self) -> None:
        included_x, included_z = build_data_mod._town_layout_centered_cell_center(0, -1, 5, "five_up")
        excluded_x, excluded_z = build_data_mod._town_layout_centered_cell_center(0, 1, 5, "five_up")
        included = {"x": included_x - 8, "z": included_z - 8, "width": 16, "depth": 16}
        excluded = {"x": excluded_x - 8, "z": excluded_z - 8, "width": 16, "depth": 16}

        self.assertTrue(build_data_mod._plot_inside_town_layout(included, 5, "five_up"))
        self.assertFalse(build_data_mod._plot_inside_town_layout(excluded, 5, "five_up"))

    def test_compiled_five_cell_layout_covers_each_tile_without_clipped_buildings(self) -> None:
        source = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/route_01_town.json").read_text(
                encoding="utf-8"
            )
        )
        profile = source["structure_profile"]
        profile["pokemon_center_enabled"] = False
        profile["commercial_center"] = "none"
        profile["facility_placements"] = []
        profile.setdefault("gym", {})["enabled"] = False
        source["town_radius_cells"] = 5

        for shape in ("five_up", "five_down"):
            with self.subTest(shape=shape):
                source["town_footprint_shape"] = shape
                layout = build_data_mod._compile_town_layout(source)
                road_endpoints = {
                    (road["x1"], road["z1"]) for road in layout["roads"]
                } | {
                    (road["x2"], road["z2"]) for road in layout["roads"]
                }
                for q, r in build_data_mod._town_layout_cells(5, shape):
                    center_x, center_z = build_data_mod._town_layout_centered_cell_center(q, r, 5, shape)
                    target = (round(center_x / 16) * 16, round(center_z / 16) * 16)
                    self.assertIn(target, road_endpoints)
                self.assertTrue(layout["houses"])
                self.assertTrue(all(
                    build_data_mod._plot_inside_town_layout(plot, 5, shape)
                    for plot in layout["houses"]
                ))

    def test_enabled_gym_is_reserved_in_compiled_layout(self) -> None:
        settlement_path = (
            REPOSITORY_ROOT / build_data_mod.OUTPUT
            / "data/cobbleventure/settlements/generation_1/fuchsia_city.json"
        )
        settlement = json.loads(settlement_path.read_text(encoding="utf-8"))

        self.assertTrue(settlement["structure_profile"]["gym"]["enabled"])
        layout = settlement["compiled_layout"]
        self.assertIn("gym_building", layout["facilities"])
        gym = layout["facilities"]["gym_building"]
        expected_entrance = {
            "x": math.floor(float(gym["x"]) + 0.5) + int(gym["width"]) // 2,
            "z": math.floor(float(gym["z"]) + 0.5) + int(gym["depth"]),
        }
        self.assertEqual(expected_entrance, gym["entrance"])
        gym_roads = [
            road for road in layout["access_roads"]
            if road["building"] == "gym_building"
        ]
        self.assertTrue(gym_roads)
        self.assertEqual(
            (expected_entrance["x"], expected_entrance["z"]),
            (gym_roads[-1]["x2"], gym_roads[-1]["z2"]),
        )

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
        self.assertIn(
            "cobbleventure:sealed_forest_edge",
            generator["biome_source"]["biomes"],
        )
        self.assertNotIn("settings", generator)

    def test_generation_dimension_registers_every_authored_world_biome(self) -> None:
        world_path = (
            REPOSITORY_ROOT
            / build_data_mod.HEX_WORLD_CONFIG_DIR
            / "generation_1.json"
        )
        dimension_path = (
            REPOSITORY_ROOT
            / build_data_mod.SOURCE
            / "data/cobbleventure/dimension/generation_1.json"
        )
        world = json.loads(world_path.read_text(encoding="utf-8"))
        dimension = json.loads(dimension_path.read_text(encoding="utf-8"))
        registered = set(dimension["generator"]["biome_source"]["biomes"])
        authored = {
            entry["biome"]
            for entry in world.get("tiles", [])
            if isinstance(entry, dict) and isinstance(entry.get("biome"), str)
        }
        authored.update(
            entry["town_biome"]
            for entry in world.get("settlements", [])
            if isinstance(entry, dict) and isinstance(entry.get("town_biome"), str)
        )

        self.assertEqual(set(), authored - registered)

    def test_sealed_dark_forest_has_no_native_spawns(self) -> None:
        path = (
            REPOSITORY_ROOT
            / build_data_mod.SOURCE
            / "data/cobbleventure/worldgen/biome/sealed_dark_forest.json"
        )
        biome = json.loads(path.read_text(encoding="utf-8"))

        self.assertTrue(all(not entries for entries in biome["features"]))
        self.assertTrue(all(not entries for entries in biome["spawners"].values()))

    def test_sealed_forest_edge_uses_dense_automatic_vegetation(self) -> None:
        biome_path = (
            REPOSITORY_ROOT
            / build_data_mod.SOURCE
            / "data/cobbleventure/worldgen/biome/sealed_forest_edge.json"
        )
        feature_path = (
            REPOSITORY_ROOT
            / build_data_mod.SOURCE
            / "data/cobbleventure/worldgen/placed_feature/sealed_forest_edge_trees.json"
        )
        biome = json.loads(biome_path.read_text(encoding="utf-8"))
        feature = json.loads(feature_path.read_text(encoding="utf-8"))

        self.assertIn(
            "cobbleventure:sealed_forest_edge_trees", biome["features"][9]
        )
        self.assertTrue(all(not entries for entries in biome["spawners"].values()))
        self.assertEqual("minecraft:dark_forest_vegetation", feature["feature"])
        count = next(
            modifier["count"] for modifier in feature["placement"]
            if modifier["type"] == "minecraft:count"
        )
        self.assertGreater(count, 16)

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

    def test_packages_authored_cave_entrance_structures(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            source = (
                root / build_data_mod.CAVE_ENTRANCE_STRUCTURE_SOURCE_DIR
                / "stone_mountain.nbt"
            )
            source.parent.mkdir(parents=True, exist_ok=True)
            authored = gzip.compress(b"\x0a\x00\x00\x00", mtime=0)
            source.write_bytes(authored)

            build_data_mod.build(root)

            packaged = (
                root / build_data_mod.OUTPUT
                / "data/cobbleventure/structure/cave_entrance/stone_mountain.nbt"
            )
            self.assertEqual(authored, packaged.read_bytes())

    def test_packages_authored_forest_gate_structure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            source = (
                root / build_data_mod.FOREST_ENTRANCE_STRUCTURE_SOURCE_DIR
                / "forest_gate.nbt"
            )
            source.parent.mkdir(parents=True, exist_ok=True)
            authored = gzip.compress(b"\x0a\x00\x00\x00", mtime=0)
            source.write_bytes(authored)

            build_data_mod.build(root)

            packaged = (
                root / build_data_mod.OUTPUT
                / "data/cobbleventure/structure/forest_gate/forest_gate.nbt"
            )
            self.assertEqual(authored, packaged.read_bytes())

    def test_packages_replaceable_casino_placeholder_for_casinocraft(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)

            build_data_mod.build(root)

            casino_path = (
                root / build_data_mod.OUTPUT
                / "data/cobbleventure/structure/placeholder/casino.nbt"
            )
            casino = gzip.decompress(casino_path.read_bytes())
            self.assertEqual((48, 20, 48), build_data_mod.FACILITY_PLACEHOLDERS["casino"]["size"])
            self.assertIn(b"PLACEHOLDER", casino)
            self.assertIn("카지노".encode("utf-8"), casino)

    def test_packages_landmark_facility_structures(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)

            build_data_mod.build(root)

            placeholder_root = (
                root / build_data_mod.OUTPUT / "data/cobbleventure/structure/placeholder"
            )
            expected = {
                "player_house": ((16, 13, 16), "플레이어 집"),
                "lighthouse": ((32, 48, 32), "등대"),
                "power_plant": ((48, 24, 48), "파워플랜트"),
                "mansion": ((48, 24, 48), "멘션"),
            }
            for facility_id, (size, label) in expected.items():
                self.assertEqual(
                    size, build_data_mod.FACILITY_PLACEHOLDERS[facility_id]["size"]
                )
                structure = gzip.decompress(
                    (placeholder_root / f"{facility_id}.nbt").read_bytes()
                )
                self.assertIn(facility_id.encode("utf-8"), structure)
                self.assertIn(label.encode("utf-8"), structure)

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

    def test_packages_copied_external_nbt_and_metadata_as_runtime_resources(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            source = (
                root / build_data_mod.FACILITY_STRUCTURE_SOURCE_DIR
                / "custom_station.nbt"
            )
            source.parent.mkdir(parents=True, exist_ok=True)
            source_bytes = build_data_mod.build_facility_placeholder_nbt("train_station")
            source.write_bytes(source_bytes)
            metadata = source.with_suffix(".structure.json")
            metadata.write_text(json.dumps({
                "schema_version": 1,
                "structure": "content/structures/placeholder/custom_station.nbt",
                "anchors": [],
            }), encoding="utf-8")

            build_data_mod.build(root)

            output = root / build_data_mod.OUTPUT
            self.assertEqual(
                source_bytes,
                (output / "data/cobbleventure/structure/placeholder/custom_station.nbt").read_bytes(),
            )
            self.assertEqual(
                metadata.read_bytes(),
                (output / build_data_mod.STRUCTURE_METADATA_ENTRY_DIR
                 / "placeholder/custom_station.structure.json").read_bytes(),
            )

    def test_authored_house_nbt_generates_roof_color_variants(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            authored = (
                root / build_data_mod.HOUSE_STRUCTURE_SOURCE_DIR
                / "one_story_flat.nbt"
            )
            authored.parent.mkdir(parents=True, exist_ok=True)
            authored_bytes = build_data_mod.build_house_variant_nbt(
                "one_story", "flat", "white"
            )
            authored.write_bytes(authored_bytes)

            build_data_mod.build(root)

            white = (
                root / build_data_mod.OUTPUT
                / "data/cobbleventure/structure/houses/one_story_flat_white.nbt"
            )
            blue = (
                root / build_data_mod.OUTPUT
                / "data/cobbleventure/structure/houses/one_story_flat_blue.nbt"
            )
            red = (
                root / build_data_mod.OUTPUT
                / "data/cobbleventure/structure/houses/one_story_flat_red.nbt"
            )
            self.assertEqual(authored_bytes, white.read_bytes())
            blue_nbt = gzip.decompress(blue.read_bytes())
            self.assertIn(b"minecraft:blue_concrete", blue_nbt)
            self.assertIn(b"minecraft:blue_wool", blue_nbt)
            self.assertIn(b"minecraft:cobblestone", blue_nbt)
            self.assertNotIn(b"minecraft:white_concrete", blue_nbt)
            self.assertNotIn(b"minecraft:white_wool", blue_nbt)
            red_nbt = gzip.decompress(red.read_bytes())
            self.assertIn(b"minecraft:red_concrete", red_nbt)
            self.assertIn(b"minecraft:red_wool", red_nbt)
            self.assertIn(b"minecraft:granite", red_nbt)

    def test_packages_building_settings_metadata_and_interiors(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            settings = root / build_data_mod.BUILDING_SETTINGS_SOURCE
            settings.parent.mkdir(parents=True, exist_ok=True)
            settings.write_text(json.dumps({
                "schema_version": 1,
                "buildings": {"cobbleventure:placeholder/hotel": {
                    "fixed_npcs": {"clerk": "cobbleventure:npc/hotel_clerk"},
                    "citizen_placement_allowed": False,
                }},
            }), encoding="utf-8")
            metadata = (
                root / build_data_mod.FACILITY_STRUCTURE_SOURCE_DIR
                / "hotel.structure.json"
            )
            metadata.parent.mkdir(parents=True, exist_ok=True)
            metadata.write_text(json.dumps({
                "schema_version": 1,
                "anchors": [{"type": "npc_position", "label": "clerk", "position": [1, 2, 3]}],
            }), encoding="utf-8")
            interior = root / build_data_mod.INTERIOR_STRUCTURE_SOURCE_DIR / "hotel.nbt"
            interior.parent.mkdir(parents=True, exist_ok=True)
            interior_bytes = gzip.compress(b"\x0aAUTHORED INTERIOR", mtime=0)
            interior.write_bytes(interior_bytes)

            build_data_mod.build(root)

            output = root / build_data_mod.OUTPUT
            self.assertEqual(
                settings.read_bytes(), (output / build_data_mod.BUILDING_SETTINGS_ENTRY).read_bytes()
            )
            self.assertEqual(
                metadata.read_bytes(),
                (output / build_data_mod.STRUCTURE_METADATA_ENTRY_DIR
                 / "placeholder/hotel.structure.json").read_bytes(),
            )
            self.assertEqual(
                interior_bytes,
                (output / "data/cobbleventure/structure/interiors/hotel.nbt").read_bytes(),
            )

    def test_packages_gym_exterior_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            metadata = (
                root / build_data_mod.CONTENT_ROOT
                / "structures/gyms/base_gym.structure.json"
            )
            metadata.parent.mkdir(parents=True, exist_ok=True)
            payload = json.dumps({
                "schema_version": 1,
                "anchors": [{
                    "id": "door",
                    "type": "door",
                    "position": [12, 3, 3],
                    "safe_spawn": [12, 3, 2],
                }],
            }).encode("utf-8")
            metadata.write_bytes(payload)

            build_data_mod.build(root)

            packaged = (
                root / build_data_mod.OUTPUT
                / build_data_mod.STRUCTURE_METADATA_ENTRY_DIR
                / "gyms/base_gym.structure.json"
            )
            self.assertEqual(payload, packaged.read_bytes())

    def test_house_metadata_is_copied_to_every_roof_color_variant(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            metadata = (
                root / build_data_mod.HOUSE_STRUCTURE_SOURCE_DIR
                / "one_story_flat.structure.json"
            )
            metadata.parent.mkdir(parents=True, exist_ok=True)
            metadata.write_text(json.dumps({
                "schema_version": 1,
                "anchors": [{"type": "npc_position", "label": "resident", "position": [4, 1, 4]}],
            }), encoding="utf-8")

            build_data_mod.build(root)

            output = root / build_data_mod.OUTPUT / build_data_mod.STRUCTURE_METADATA_ENTRY_DIR / "houses"
            for roof_color in build_data_mod.HOUSE_ROOF_BLOCKS:
                generated = json.loads(
                    (output / f"one_story_flat_{roof_color}.structure.json").read_text(
                        encoding="utf-8"
                    )
                )
                self.assertEqual(
                    "cobbleventure:interiors/one_story_shed",
                    generated["interior_structure"],
                )
                self.assertIn(
                    {"type": "npc_position", "label": "resident", "position": [4, 1, 4]},
                    generated["anchors"],
                )
                self.assertTrue(any(
                    anchor.get("type") == "door"
                    for anchor in generated["anchors"]
                ))

    def test_house_without_metadata_uses_shared_instanced_interior(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            (root / build_data_mod.HOUSE_STRUCTURE_SOURCE_DIR).mkdir(
                parents=True, exist_ok=True
            )

            build_data_mod.build(root)

            metadata_path = (
                root / build_data_mod.OUTPUT
                / build_data_mod.STRUCTURE_METADATA_ENTRY_DIR
                / "houses/five_story_gable_red.structure.json"
            )
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            self.assertEqual(
                "cobbleventure:interiors/one_story_shed",
                metadata["interior_structure"],
            )
            self.assertEqual("door", metadata["anchors"][0]["type"])
            self.assertEqual([8, 1, 0], metadata["anchors"][0]["position"])
            self.assertEqual([8, 1, -1], metadata["anchors"][0]["safe_spawn"])
            self.assertTrue(metadata["anchors"][0]["seal_entry"])

    def test_authored_house_door_is_used_for_shared_interior(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._fixture(root)
            metadata = (
                root / build_data_mod.HOUSE_STRUCTURE_SOURCE_DIR
                / "one_story_flat.structure.json"
            )
            metadata.parent.mkdir(parents=True, exist_ok=True)
            authored_door = {
                "id": "door",
                "type": "door",
                "position": [13, 1, 3],
                "safe_spawn": [13, 1, 2],
                "door_facing": "south",
            }
            metadata.write_text(json.dumps({
                "schema_version": 1,
                "anchors": [authored_door],
            }), encoding="utf-8")

            build_data_mod.build(root)

            generated = json.loads((
                root / build_data_mod.OUTPUT
                / build_data_mod.STRUCTURE_METADATA_ENTRY_DIR
                / "houses/one_story_flat_red.structure.json"
            ).read_text(encoding="utf-8"))
            self.assertIn(authored_door, generated["anchors"])
            self.assertEqual(1, sum(
                anchor.get("type") == "door"
                for anchor in generated["anchors"]
            ))

    def test_authored_roof_shapes_include_shed_and_gambrel(self) -> None:
        shed = gzip.decompress(
            build_data_mod.build_house_variant_nbt("one_story", "shed", "white")
        )
        gambrel = gzip.decompress(
            build_data_mod.build_house_variant_nbt("one_story", "gambrel", "white")
        )
        self.assertIn(b"minecraft:white_concrete", shed)
        self.assertIn(b"minecraft:white_concrete", gambrel)
        self.assertNotEqual(shed, gambrel)
        self.assertIn(b"minecraft:white_wool", shed)
        with self.assertRaises(ValueError):
            build_data_mod.build_house_variant_nbt("one_story", "hip", "white")

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

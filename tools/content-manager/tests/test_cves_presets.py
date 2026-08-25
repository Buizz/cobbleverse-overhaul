import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
CONTENT_MANAGER = ROOT / "tools" / "content-manager"
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
sys.path.insert(0, str(CONTENT_MANAGER))

import content_manager  # noqa: E402
import sync_cves_behavior_presets  # noqa: E402
from cves import (  # noqa: E402
    format_program,
    load_project_catalog,
    parse,
    preset_program,
    validate,
)


class CvesBehaviorPresetTests(unittest.TestCase):
    def test_npc_editor_exposes_v5_preset_authoring_without_opening_tree_editor(self) -> None:
        markup = (CONTENT_MANAGER / "web/index.html").read_text(encoding="utf-8")
        script = (CONTENT_MANAGER / "web/app.js").read_text(encoding="utf-8")
        cves_script = (CONTENT_MANAGER / "web/cves-editor.js").read_text(encoding="utf-8")
        self.assertIn('id="event-runtime-engine"', markup)
        self.assertIn('value="cves_v5"', markup)
        self.assertIn("NPC를 저장하면 CVES와 바인딩이 함께 생성됩니다", script)
        self.assertIn('authoring: "preset"', script)
        self.assertIn("customizeLinkedCvesEvent", script)
        self.assertIn("previewLinkedCvesEvent", script)
        self.assertIn("/api/cves/preset-preview", script)
        self.assertIn('id="event-cves-preview"', markup)
        self.assertIn('new URLSearchParams(window.location.search).get("path")', cves_script)

    def test_authoritative_npc_sources_and_generated_presets_are_v5_only(self) -> None:
        source_root = PROJECT_ROOT / "content/source"
        binding_root = PROJECT_ROOT / "content/event-bindings"
        preset_root = (
            ROOT / "projects/cobbleventure-world-bootstrap/src/main/resources/data/"
            "easy_npc/preset/encounter"
        )
        sources = sorted(source_root.rglob("*.json"))
        self.assertGreater(len(sources), 0)
        for source in sources:
            with self.subTest(source=source.relative_to(source_root).as_posix()):
                document = json.loads(source.read_text(encoding="utf-8"))
                self.assertEqual("cves_v5", document.get("event_runtime", {}).get("engine"))
                relative = source.relative_to(source_root).with_suffix(".json")
                namespace = document["id"].split(":", 1)[0]
                self.assertTrue((binding_root / namespace / relative).is_file())

        presets = sorted(preset_root.glob("*.npc.snbt"))
        self.assertGreater(len(presets), 0)
        for preset in presets:
            with self.subTest(preset=preset.name):
                source = preset.read_text(encoding="utf-8")
                self.assertIn("cves_binding/", source)
                self.assertIn("ActionEventSet:{}", source)
                self.assertIn("DialogDataSet:[]", source)
                self.assertNotIn("cobbleventure_npc_preset_v4", source)
                self.assertNotIn("OPEN_DEFAULT_DIALOG", source)
                self.assertNotIn("tbcs battle", source)

    def test_power_plant_dungeon_trainers_use_v5_proximity_dialogue(self) -> None:
        slugs = (
            "rocket_power_plant_grunt",
            "rocket_power_plant_grunt_east",
            "rocket_power_plant_officer",
        )
        preset_root = (
            ROOT / "projects/cobbleventure-world-bootstrap/src/main/resources/data/"
            "easy_npc/preset/encounter"
        )
        for slug in slugs:
            with self.subTest(slug=slug):
                document = json.loads((
                    PROJECT_ROOT / f"content/source/generation_1/{slug}.json"
                ).read_text(encoding="utf-8"))
                trigger = document["event_design"]["preset"]["initial_trigger"]
                self.assertEqual("cves_v5", document["event_runtime"]["engine"])
                self.assertEqual("proximity", trigger["type"])
                self.assertEqual(6, trigger["range"])
                self.assertTrue(document["event_design"]["preset"]["first_text"]["ko_kr"])
                script = (
                    PROJECT_ROOT / f"content/events/cobbleventure/generation_1/{slug}.cves"
                ).read_text(encoding="utf-8")
                self.assertIn('event proximity_enter(range: 6', script)
                self.assertIn("say npc", script)
                representation = (
                    preset_root / f"{slug}__v5_proximity.npc.snbt"
                ).read_text(encoding="utf-8")
                self.assertIn("cves_binding/cobbleventure/generation_1/", representation)
                self.assertIn('"cves_trigger/proximity"', representation)

        dungeon_system = (
            ROOT / "projects/cobbleventure-world-bootstrap/src/main/java/dev/buizz/"
            "cobbleventure/bootstrap/DungeonSystem.java"
        ).read_text(encoding="utf-8")
        self.assertIn('encounter.yaw(), "proximity"', dungeon_system)
        self.assertIn("EventBattleBridge", dungeon_system)
        self.assertIn(".pendingContext(playerId)", dungeon_system)

    def test_world_placement_selects_v5_representation_for_v5_npcs(self) -> None:
        bootstrap = (
            ROOT / "projects/cobbleventure-world-bootstrap/src/main/java/dev/buizz/"
            "cobbleventure/bootstrap/CobbleventureBootstrap.java"
        ).read_text(encoding="utf-8")
        buildings = (
            ROOT / "projects/cobbleventure-world-bootstrap/src/main/java/dev/buizz/"
            "cobbleventure/bootstrap/BuildingRuntimeSystem.java"
        ).read_text(encoding="utf-8")
        gyms = (
            ROOT / "projects/cobbleventure-world-bootstrap/src/main/java/dev/buizz/"
            "cobbleventure/bootstrap/GymInteriorSystem.java"
        ).read_text(encoding="utf-8")
        world = json.loads((PROJECT_ROOT / "content/worlds/generation_1.json").read_text(encoding="utf-8"))
        self.assertIn("RegionalNpcPresetSelection.suffix(cvesV5, triggerOverride)", bootstrap)
        self.assertIn("CobbleventureBootstrap.npcPresetSuffix(level, npcId)", buildings)
        self.assertIn('role.equals("leader") ? "__v5"', gyms)
        gate_presets = [
            value.get("properties", {}).get("npc")
            for value in world.get("objects", [])
            if value.get("type") == "gate" and value.get("properties", {}).get("npc")
        ]
        self.assertTrue(any(value.endswith("starter_town_gatekeeper_minho__v5.npc.snbt") for value in gate_presets))

    def test_every_supported_preset_kind_builds_a_tree(self) -> None:
        cases = {
            "simple": {},
            "repeat": {"repeat_text": {"ko_kr": "다시 만났네요."}},
            "item": {"repeat_text": {"ko_kr": "이미 받았습니다."}, "item": "cobblemon:potion"},
            "battle": {"battle": "cobbleventure:battle/sample_youngster_minjun"},
            "gym": {
                "battle": "cobbleventure:battle/gym_leader/brock",
                "badge": "cobbleventure:badge/kanto/boulder",
                "clear_key": "cobbleventure:flag/gym/kanto/brock/defeated",
            },
            "elite": {"battle": "cobbleventure:battle/sample_youngster_minjun"},
            "champion": {"battle": "cobbleventure:battle/sample_youngster_minjun"},
        }
        for preset_type, extra in cases.items():
            preset = {
                "type": preset_type,
                "initial_trigger": {"type": "interact", "range": 4},
                "first_text": {"ko_kr": "안녕하세요!"},
                "win_text": {"ko_kr": "이겼습니다."},
                "loss_text": {"ko_kr": "다시 도전하세요."},
                **extra,
            }
            document = {
                "id": f"cobbleventure:npc/test_{preset_type}",
                "event_design": {"mode": "preset", "preset": preset},
            }
            with self.subTest(preset_type=preset_type):
                canonical = format_program(preset_program(document))
                self.assertEqual(canonical, format_program(parse(canonical, preset_type)))
                if preset_type in {"battle", "gym", "elite", "champion"}:
                    self.assertIn("await battle", canonical)
                    self.assertIn("choice", canonical)

    def test_every_sample_behavior_preset_builds_a_valid_round_trip_tree(self) -> None:
        catalog = load_project_catalog(PROJECT_ROOT)
        sources = sorted((PROJECT_ROOT / "content/source/samples").glob("*.json"))
        self.assertEqual(14, len(sources))
        for source in sources:
            with self.subTest(source=source.name):
                document = json.loads(source.read_text(encoding="utf-8"))
                program = preset_program(document)
                canonical = format_program(program)
                reparsed = parse(canonical, source.as_posix())
                self.assertEqual(canonical, format_program(reparsed))
                self.assertEqual((), validate(reparsed, catalog))

    def test_multiline_preset_dialogue_becomes_sequential_say_nodes(self) -> None:
        document = {
            "id": "test:npc/multiline",
            "event_design": {"mode": "preset", "preset": {
                "type": "simple", "initial_trigger": {"type": "interact", "range": 4},
                "first_text": {"ko_kr": "첫 번째 대사\n두 번째 대사", "en_us": "First\nSecond"},
            }},
        }
        source = format_program(preset_program(document))
        self.assertEqual(2, source.count("    say npc {"))
        self.assertLess(source.index("첫 번째 대사"), source.index("두 번째 대사"))

    def test_checked_in_potion_event_is_the_preset_compiler_golden_file(self) -> None:
        document = json.loads((
            PROJECT_ROOT / "content/source/samples/sample_potion_giver.json"
        ).read_text(encoding="utf-8"))
        checked_in = (
            PROJECT_ROOT / "content/events/cobbleventure/samples/sample_potion_giver.cves"
        ).read_text(encoding="utf-8")
        self.assertEqual(checked_in, format_program(preset_program(document)))
        self.assertEqual("cves_v5", document["event_runtime"]["engine"])

    def test_battle_preset_uses_nested_choice_and_await_without_jumps(self) -> None:
        document = json.loads((
            PROJECT_ROOT / "content/source/samples/sample_youngster_minjun.json"
        ).read_text(encoding="utf-8"))
        source = format_program(preset_program(document))
        self.assertIn('choice { ko_kr: "도전하시겠습니까?" } {', source)
        self.assertIn(
            'event proximity_enter(range: 9, group: "trainer_battle", stage: "warning")',
            source,
        )
        self.assertIn(
            'event proximity_enter(range: 6, group: "trainer_battle", after: "warning")',
            source,
        )
        self.assertIn('encounter_warning "encounter.trainer_boy"', source)
        proximity_challenge = source.split(
            'event proximity_enter(range: 6, group: "trainer_battle", after: "warning")', 1
        )[1].split("event interact", 1)[0]
        self.assertNotIn("choice", proximity_challenge)
        self.assertIn('await battle "cobbleventure:battle/sample_youngster_minjun" -> battle_result', source)
        self.assertIn('if battle_result.outcome == "win" {', source)
        self.assertNotIn("\n    label ", source)
        self.assertNotIn("\n    jump ", source)

    def test_preset_managed_script_refuses_to_overwrite_manual_edits(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "content/source/samples/example.json"
            event = root / "content/events/test/samples/example.cves"
            target.parent.mkdir(parents=True)
            event.parent.mkdir(parents=True)
            (root / "content/catalogs").mkdir(parents=True)
            (root / "content/catalogs/game-definitions.json").write_text(
                json.dumps({"variables": [], "items": []}), encoding="utf-8"
            )
            document = {
                "id": "test:npc/example",
                "event_runtime": {
                    "engine": "cves_v5", "authoring": "preset",
                    "script_id": "test:event_script/samples/example",
                },
                "event_design": {"mode": "preset", "preset": {
                    "type": "simple", "initial_trigger": {"type": "interact", "range": 4},
                    "first_text": {"ko_kr": "안녕하세요!"},
                }},
            }
            target.write_text(json.dumps(document), encoding="utf-8")
            event.write_text(format_program(preset_program(document)) + "# manual\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "직접 수정"):
                content_manager._prepare_v5_preset_sync(root, target, document)

            managed_upgrade = content_manager._prepare_v5_preset_sync(
                root, target, document, allow_managed_upgrade=True
            )
            self.assertEqual(
                format_program(preset_program(document)), managed_upgrade["event_source"]
            )

            document["event_runtime"]["authoring"] = "custom"
            plan = content_manager._prepare_v5_preset_sync(root, target, document)
            self.assertNotIn("event_source", plan)
            self.assertEqual(
                "test:event_script/samples/example",
                json.loads(plan["binding_source"])["script_id"],
            )

    def test_sync_dry_run_writes_nothing_and_reports_deterministic_actions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "content/source/samples/example.json"
            source.parent.mkdir(parents=True)
            catalogs = root / "content/catalogs"
            catalogs.mkdir(parents=True)
            (catalogs / "game-definitions.json").write_text(
                json.dumps({"variables": [], "items": []}), encoding="utf-8"
            )
            document = {
                "schema_version": 4,
                "id": "test:npc/example",
                "event_design": {"mode": "preset", "preset": {
                    "type": "simple", "initial_trigger": {"type": "interact", "range": 4},
                    "first_text": {"ko_kr": "안녕하세요!"},
                }},
            }
            original = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
            source.write_text(original, encoding="utf-8")

            preview = sync_cves_behavior_presets.synchronize(
                root, source.parent, enable=True, dry_run=True
            )
            self.assertEqual(original, source.read_text(encoding="utf-8"))
            self.assertFalse((root / "content/events").exists())
            self.assertFalse((root / "content/event-bindings").exists())
            self.assertEqual(["update", "create", "create"], [
                artifact["action"] for artifact in preview[0]["artifacts"]
            ])

            sync_cves_behavior_presets.synchronize(
                root, source.parent, enable=True, dry_run=False
            )
            second_preview = sync_cves_behavior_presets.synchronize(
                root, source.parent, enable=True, dry_run=True
            )
            self.assertFalse(second_preview[0]["changed"])
            self.assertEqual({"unchanged"}, {
                artifact["action"] for artifact in second_preview[0]["artifacts"]
            })

    def test_preview_reports_cves_and_binding_diffs_without_writing(self) -> None:
        document = json.loads((
            PROJECT_ROOT / "content/source/samples/sample_potion_giver.json"
        ).read_text(encoding="utf-8"))
        target = PROJECT_ROOT / "content/source/samples/sample_potion_giver.json"
        plan = content_manager._prepare_v5_preset_sync(PROJECT_ROOT, target, document)
        before = {
            key: plan[key].read_bytes()
            for key in ("event_path", "binding_path")
        }
        preview = content_manager._preview_v5_preset_sync(PROJECT_ROOT, plan)
        self.assertTrue(preview["enabled"])
        self.assertFalse(preview["changed"])
        self.assertEqual(["cves", "binding"], [value["kind"] for value in preview["artifacts"]])
        self.assertEqual({"unchanged"}, {value["action"] for value in preview["artifacts"]})
        self.assertEqual(before, {
            key: plan[key].read_bytes()
            for key in ("event_path", "binding_path")
        })

    def test_custom_script_id_cannot_escape_the_event_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "content/source/samples/example.json"
            target.parent.mkdir(parents=True)
            document = {
                "id": "test:npc/example",
                "event_runtime": {
                    "engine": "cves_v5", "authoring": "custom",
                    "script_id": "test:event_script/../../../outside",
                },
                "event_design": {"mode": "preset", "preset": {
                    "type": "simple", "initial_trigger": {"type": "interact", "range": 4},
                    "first_text": {"ko_kr": "안녕하세요!"},
                }},
            }
            with self.assertRaisesRegex(ValueError, "이벤트 디렉터리를 벗어날 수 없습니다"):
                content_manager._prepare_v5_preset_sync(root, target, document)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
MODULE_PATH = ROOT / "tools" / "content-manager" / "content_manager.py"
SPEC = importlib.util.spec_from_file_location("content_manager_samples", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
content_manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = content_manager
SPEC.loader.exec_module(content_manager)

GENERATOR_PATH = ROOT / "tools" / "content-manager" / "generate_easy_npc_presets.py"
GENERATOR_SPEC = importlib.util.spec_from_file_location("generate_sample_easy_npc_presets", GENERATOR_PATH)
assert GENERATOR_SPEC is not None and GENERATOR_SPEC.loader is not None
generator = importlib.util.module_from_spec(GENERATOR_SPEC)
sys.modules[GENERATOR_SPEC.name] = generator
GENERATOR_SPEC.loader.exec_module(generator)


class SampleNpcTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source_root = PROJECT_ROOT / "content" / "source" / "samples"
        cls.battle_root = PROJECT_ROOT / "content" / "battles" / "samples"
        cls.sources = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(cls.source_root.glob("*.json"))
        ]
        cls.battles = {
            document["id"]: document
            for path in sorted(cls.battle_root.glob("*.json"))
            for document in [json.loads(path.read_text(encoding="utf-8"))]
        }
        outfits = json.loads(
            (PROJECT_ROOT / "content" / "catalogs" / "trainer-outfits.json").read_text(encoding="utf-8")
        )["outfits"]
        cls.youngster_outfit = next(
            outfit
            for outfit in outfits
            if outfit["trainer_class"] == "cobbleventure:trainer_class/youngster"
        )

    def test_sample_documents_pass_individual_validation(self) -> None:
        issues = []
        for path in sorted(self.source_root.glob("*.json")):
            _, issues_for_file = content_manager.validate_npc_event_file(path)
            issues.extend(issues_for_file)
        for path in sorted(self.battle_root.glob("*.json")):
            _, issues_for_file = content_manager.validate_battle_preset_file(path)
            issues.extend(issues_for_file)

        self.assertEqual([], issues)

    def test_has_four_one_time_item_givers(self) -> None:
        item_givers = [document for document in self.sources if "item_giver" in document["tags"]]

        self.assertEqual(4, len(item_givers))
        for document in item_givers:
            commands = document["events"][0]["commands"]
            give_commands = [command for command in commands if command["type"] == "give_item"]
            set_flag = next(command for command in commands if command["type"] == "set_flag")
            first_command = commands[0]

            self.assertEqual(1, len(give_commands))
            self.assertGreaterEqual(give_commands[0]["count"], 1)
            self.assertEqual("branch", first_command["type"])
            self.assertEqual(set_flag["key"], first_command["conditions"][0]["key"])
            self.assertTrue(set_flag["value"])

            preset = generator.encounter_preset_snbt(document, self.youngster_outfit)
            self.assertIn(
                f"cobbleventurebag give @initiator {give_commands[0]['item']}",
                preset,
            )
            self.assertIn(generator.flag_objective(set_flag["key"]), preset)

    def test_has_ten_trainers_with_matching_battles(self) -> None:
        trainers = [document for document in self.sources if "basic_battle" in document["tags"]]

        self.assertEqual(10, len(trainers))
        self.assertEqual(10, len(self.battles))
        for trainer in trainers:
            start_battle = next(
                command
                for command in trainer["events"][0]["commands"]
                if command["type"] == "start_battle"
            )
            battle = self.battles[start_battle["battle"]]

            self.assertEqual("trainer", trainer["placement_profile"]["classification"])
            self.assertTrue(trainer["placement_profile"]["automatic_route_placement"])
            self.assertEqual(
                trainer["placement_profile"]["expected_level"],
                battle["battle"]["team"][0]["level"],
            )

            compiled = json.loads(json.dumps(trainer))
            compiled["_battle_presets"] = {battle["id"]: battle}
            preset = generator.encounter_preset_snbt(compiled, self.youngster_outfit)
            self.assertIn(f"tbcs battle GEN_9_SINGLES @initiator vs @s as rctmod:{trainer['id'].rsplit('/', 1)[-1]}", preset)

    def test_direct_trainer_checkboxes_keep_compact_form_layout(self) -> None:
        styles = (ROOT / "tools" / "content-manager" / "web" / "styles.css").read_text(encoding="utf-8")

        self.assertIn('.form-grid .trainer-pool-choice > input[type="checkbox"]', styles)
        self.assertIn("min-width: 17px", styles)
        self.assertIn("flex: 1 1 auto", styles)


if __name__ == "__main__":
    unittest.main()

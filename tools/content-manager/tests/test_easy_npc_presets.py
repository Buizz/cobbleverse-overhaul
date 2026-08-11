import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
SPEC = importlib.util.spec_from_file_location(
    "generate_easy_npc_presets",
    ROOT / "tools" / "content-manager" / "generate_easy_npc_presets.py",
)
generator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(generator)


class EasyNpcEncounterPresetTests(unittest.TestCase):
    def setUp(self) -> None:
        self.document = json.loads(
            (ROOT / "content" / "source" / "examples" / "ai_test.json").read_text(encoding="utf-8")
        )
        battle = json.loads(
            (ROOT / "content" / "battles" / "examples" / "ai_test.json").read_text(encoding="utf-8")
        )
        self.document["_battle_presets"] = {battle["id"]: battle}
        catalog = json.loads(
            (ROOT / "content" / "catalogs" / "trainer-outfits.json").read_text(encoding="utf-8")
        )
        self.outfit = catalog["outfits"][0]

    def test_generates_dialogue_battle_conditions_and_rewards(self) -> None:
        preset = generator.encounter_preset_snbt(self.document, self.outfit)

        self.assertIn("DialogDataSet", preset)
        self.assertIn("HAS_ITEM_IN_INVENTORY", preset)
        self.assertIn("tbcs battle GEN_9_SINGLES @initiator vs @npc as rctmod:ai_test", preset)
        self.assertIn("scoreboard players add @1 cobbleventure_money 500", preset)
        self.assertIn("loot give @1 loot cobbleventure:trainer/ai_test_rewards", preset)
        self.assertIn("ON_INTERACTION", preset)

    def test_spawn_command_imports_generated_data_preset(self) -> None:
        self.assertEqual(
            "/easy_npc preset import_new data cobbleventure:encounter/ai_test ~ ~ ~",
            generator.spawn_command(self.document),
        )

    def test_flag_objective_is_stable_and_minecraft_sized(self) -> None:
        first = generator.flag_objective("cobbleventure:flag/trainer/ai_test/defeated")
        second = generator.flag_objective("cobbleventure:flag/trainer/ai_test/defeated")
        self.assertEqual(first, second)
        self.assertLessEqual(len(first), 16)


if __name__ == "__main__":
    unittest.main()

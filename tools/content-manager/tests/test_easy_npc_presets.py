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
        self.assertIn('Debug:1b,ExecAsUser:0b,PermLevel:2,Type:"COMMAND"', preset)
        self.assertIn("scoreboard players add @1 cobbleventure_money 500", preset)
        self.assertIn("loot give @1 loot cobbleventure:trainer/ai_test_rewards", preset)
        self.assertIn("ON_INTERACTION", preset)

    def test_generates_loss_money_callback_for_player_defeat(self) -> None:
        commands = self.document["events"][0]["commands"]
        loss_label = next(
            index for index, command in enumerate(commands)
            if command.get("type") == "label" and command.get("name") == "after_defeat"
        )
        commands.insert(loss_label + 1, {
            "type": "take_money",
            "mode": "fixed",
            "amount": 250,
            "currency_objective": "cobbleventure_money",
        })

        preset = generator.encounter_preset_snbt(self.document, self.outfit)

        self.assertIn('2:[\\"scoreboard players remove @1 cobbleventure_money 250\\"', preset)
        self.assertIn("matches ..-1 run scoreboard players set @1 cobbleventure_money 0", preset)

    def test_generates_progression_clear_scoreboard_command(self) -> None:
        commands = self.document["events"][0]["commands"]
        win_label = next(
            index for index, command in enumerate(commands)
            if command.get("type") == "label" and command.get("name") == "victory_reward"
        )
        clear_key = "cobbleventure:clear/gym/ai_test"
        commands.insert(win_label + 1, {"type": "mark_clear", "key": clear_key})

        preset = generator.encounter_preset_snbt(self.document, self.outfit)

        self.assertIn(f"scoreboard players set @1 {generator.flag_objective(clear_key)} 1", preset)

    def test_spawn_command_imports_generated_data_preset(self) -> None:
        self.assertEqual(
            "/easy_npc preset import_new data "
            "easy_npc:preset/encounter/ai_test.npc.snbt ~ ~ ~",
            generator.spawn_command(self.document),
        )

    def test_generated_path_is_compatible_with_easy_npc_7_0_1_security(self) -> None:
        path = generator.resource_path("cobbleventure:trainer/youngster")
        self.assertEqual(
            generator.RESOURCE_ROOT / "data" / "easy_npc" / "preset"
            / "cobbleventure" / "trainer" / "youngster.npc.snbt",
            path,
        )

    def test_flag_objective_is_stable_and_minecraft_sized(self) -> None:
        first = generator.flag_objective("cobbleventure:flag/trainer/ai_test/defeated")
        second = generator.flag_objective("cobbleventure:flag/trainer/ai_test/defeated")
        self.assertEqual(first, second)
        self.assertLessEqual(len(first), 16)

    def test_double_battle_pair_uses_partner_appearance_and_shared_events(self) -> None:
        owner = json.loads(json.dumps(self.document))
        owner["npc"]["double_battle"] = {
            "partner": "cobbleventure:npc/ai_test_partner",
            "group_id": "cobbleventure:double_battle/ai_test",
            "shared_clear_key": "cobbleventure:clear/double_battle/ai_test",
        }
        partner = json.loads(json.dumps(self.document))
        partner["id"] = "cobbleventure:npc/ai_test_partner"
        partner["name"]["ko_kr"] = "AI 파트너"
        partner["npc"]["display_name"]["ko_kr"] = "AI 파트너"
        partner["npc"]["appearance"]["resource"] = "cobbleventure:trainer/youngster"
        battle = next(iter(self.document["_battle_presets"].values()))
        battle = json.loads(json.dumps(battle))
        battle["battle"]["format"] = "GEN_9_DOUBLES"
        battle["battle"]["battle_type"] = "doubles"

        expanded = generator.paired_encounter_documents(
            [owner, partner], {battle["id"]: battle}
        )

        self.assertEqual(2, len(expanded))
        self.assertEqual("cobbleventure:npc/ai_test", expanded[0]["id"])
        self.assertEqual("cobbleventure:npc/ai_test_partner", expanded[1]["id"])
        self.assertEqual(expanded[0]["events"], expanded[1]["events"])
        self.assertEqual(
            "cobbleventure:trainer/youngster",
            expanded[1]["npc"]["appearance"]["resource"],
        )
        clear_key = owner["npc"]["double_battle"]["shared_clear_key"]
        self.assertTrue(any(
            command.get("type") == "mark_clear" and command.get("key") == clear_key
            for command in expanded[0]["events"][0]["commands"]
        ))


if __name__ == "__main__":
    unittest.main()

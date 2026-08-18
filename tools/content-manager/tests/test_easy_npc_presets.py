import importlib.util
import json
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).parents[3]
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
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
            (PROJECT_ROOT / "content" / "source" / "examples" / "ai_test.json").read_text(encoding="utf-8")
        )
        battle = json.loads(
            (PROJECT_ROOT / "content" / "battles" / "examples" / "ai_test.json").read_text(encoding="utf-8")
        )
        self.document["_battle_presets"] = {battle["id"]: battle}
        catalog = json.loads(
            (PROJECT_ROOT / "content" / "catalogs" / "trainer-outfits.json").read_text(encoding="utf-8")
        )
        self.outfit = catalog["outfits"][0]

    def test_generates_dialogue_battle_conditions_and_rewards(self) -> None:
        preset = generator.encounter_preset_snbt(self.document, self.outfit)

        self.assertIn("DialogDataSet", preset)
        item_objective = generator.item_condition_objective("cobblemon:potion", 1)
        self.assertIn(
            f'Name:"{item_objective}",Operation:"EQUALS",Type:"SCOREBOARD",Value:1',
            preset,
        )
        self.assertNotIn("HAS_ITEM_IN_INVENTORY", preset)
        self.assertIn("tbcs battle GEN_9_SINGLES @initiator vs @s as rctmod:ai_test", preset)
        self.assertIn(
            "cobbleventure_battle_intro @initiator @s "
            "cobbleventure:battle/ai_test tbcs battle GEN_9_SINGLES",
            preset,
        )
        self.assertIn(
            'Actions:[{Type:"CLOSE_DIALOG"},{Cmd:"/cobbleventure_reward prepare',
            preset,
        )
        self.assertNotIn(" vs @npc as ", preset)
        self.assertIn('Debug:1b,ExecAsUser:0b,PermLevel:2,Type:"COMMAND"', preset)
        self.assertIn(
            "cobbleventure_reward prepare @initiator regional 20 20 100 true cobblemon:amulet_coin 2",
            preset,
        )
        self.assertNotIn("cobbledollars give @1 500", preset)
        self.assertIn("cobbleventurebag loot @1 cobbleventure:trainer/ai_test_rewards", preset)
        self.assertIn(
            "easy_npc dialog open @npc-uuid @initiator after_victory",
            preset,
        )
        self.assertIn(
            "cobbleventure_trainer_state complete @npc-uuid @initiator",
            preset,
        )
        self.assertNotIn(
            '2:["cobbleventure_trainer_state complete @npc-uuid @initiator"',
            preset,
        )
        self.assertIn(
            "/cobbleventure_trainer_state prepare @npc-uuid @initiator",
            preset,
        )
        self.assertIn(
            "easy_npc dialog open @npc-uuid @initiator after_defeat",
            preset,
        )
        self.assertIn("ON_INTERACTION", preset)

    def test_event_preset_can_generate_a_unique_state_key_from_the_npc_id(self) -> None:
        document = {
            "id": "cobbleventure:npc/test/researcher",
            "event_design": {
                "mode": "preset",
                "preset": {
                    "type": "item",
                    "initial_trigger": {"type": "interact", "range": 4},
                    "first_text": {"ko_kr": "받아."},
                    "repeat_text": {"ko_kr": "이미 줬어."},
                    "auto_state_key": True,
                    "item": "cobblemon:poke_ball",
                    "item_count": 1,
                    "after_item_text": {"ko_kr": "이 비전머신은 어두운 동굴에서 사용해."},
                },
            },
        }

        materialized = generator.materialize_event_document(document)
        commands = materialized["events"][0]["commands"]
        condition = materialized["events"][0]["commands"][0]["conditions"][0]
        set_flag = next(
            command
            for command in materialized["events"][0]["commands"]
            if command["type"] == "set_flag"
        )

        self.assertEqual(
            "cobbleventure:flag/npc/test/researcher/claimed", condition["key"]
        )
        self.assertEqual(condition["key"], set_flag["key"])
        reward_index = next(index for index, command in enumerate(commands) if command["type"] == "give_item")
        self.assertEqual("dialogue", commands[reward_index + 1]["type"])
        self.assertEqual("이 비전머신은 어두운 동굴에서 사용해.", commands[reward_index + 1]["text"]["ko_kr"])
        self.assertLess(reward_index + 1, commands.index(set_flag))

        dialogues = generator.event_script_dialogues(materialized)
        reward_action = '/cobbleventurebag acquire @initiator cobblemon:poke_ball 1'
        followup_action = 'Cmd:"item_explanation",Type:"OPEN_NAMED_DIALOG"'
        self.assertIn(reward_action, dialogues)
        self.assertIn(followup_action, dialogues)
        self.assertLess(dialogues.index(reward_action), dialogues.index(followup_action))

    def test_shared_player_conditions_are_mirrored_for_easy_npc(self) -> None:
        condition = {"type": "party_count", "operator": ">=", "value": 1}
        objective = generator.player_condition_objective(condition)

        self.assertEqual(
            f'{{Name:"{objective}",Operation:"EQUALS",Type:"SCOREBOARD",Value:1}}',
            generator.easy_npc_condition(condition),
        )

    def test_progression_unlock_actions_use_server_owned_commands(self) -> None:
        unlock = generator.easy_npc_action(
            {"type": "unlock_feature", "feature": "pc"}, self.document
        )
        level_cap = generator.easy_npc_action(
            {"type": "set_level_cap", "level_cap": 25}, self.document
        )

        self.assertIn("/cobbleventure_progress unlock @initiator pc", unlock)
        self.assertIn("/cobbleventure_progress level_cap @initiator 25", level_cap)
        self.assertIn('PermLevel:2,Type:"COMMAND"', unlock)
        self.assertEqual(
            generator.flag_objective("cobbleventure:flag/story/example"),
            generator.easy_npc_condition({
                "type": "flag", "key": "cobbleventure:flag/story/example", "value": True,
            }).split('"')[1],
        )

    def test_trigger_override_generates_independent_interaction_and_proximity_presets(self) -> None:
        interaction = generator.encounter_preset_snbt(
            self.document, self.outfit, "interact"
        )
        proximity = generator.encounter_preset_snbt(
            self.document, self.outfit, "proximity"
        )

        self.assertIn("ON_INTERACTION", interaction)
        self.assertNotIn("ON_DISTANCE_VERY_CLOSE", interaction)
        self.assertIn("ON_DISTANCE_NEAR", proximity)
        self.assertNotIn("ON_DISTANCE_CLOSE", proximity)
        self.assertIn(
            "/cobbleventure_proximity_battle @initiator @s", proximity
        )
        self.assertIn(
            "encounter.trainer_boy greeting cobbleventure_battle_intro",
            proximity,
        )
        self.assertIn(
            'Label:"greeting",Name:"잠깐! 나는 배틀 연습 중인 AI 맨이야.',
            proximity,
        )
        self.assertIn('Label:"battle",Name:"계속",Actions:', proximity)
        self.assertIn("cobbleventure_battle_intro @initiator @s", proximity)
        self.assertIn("encounter.trainer_boy", proximity)
        self.assertIn("ON_INTERACTION", proximity)
        self.assertIn("cobbleventure_npc_preset_v4", proximity)
        self.assertNotIn("ON_DISTANCE_VERY_CLOSE", proximity)
        self.assertNotIn("/title @initiator actionbar", proximity)
        self.assertNotEqual(interaction, proximity)

    def test_normalized_battle_routes_completed_trainer_to_victory_dialogue(self) -> None:
        sample = json.loads(
            (
                PROJECT_ROOT
                / "content/source/samples/sample_youngster_minjun.json"
            ).read_text(encoding="utf-8")
        )
        battle = json.loads(
            (
                PROJECT_ROOT
                / "content/battles/samples/sample_youngster_minjun.json"
            ).read_text(encoding="utf-8")
        )
        sample["_battle_presets"] = {battle["id"]: battle}

        preset = generator.encounter_preset_snbt(sample, self.outfit, "proximity")

        self.assertIn(
            'Name:"cv_npc_defeated",Operation:"EQUALS",Type:"SCOREBOARD",Value:1',
            preset,
        )
        self.assertIn('Label:"victory",Name:"좋은 승부였어!', preset)
        victory = preset.split('Label:"victory"', 1)[1].split("}]", 1)[0]
        self.assertNotIn("cobbleventure_battle_intro", victory)

    def test_encounter_music_uses_presentation_metadata(self) -> None:
        girl = {**self.outfit, "arm_model": "slim"}
        villain = {**self.outfit, "_trainer_class_tags": ["villain"]}

        self.assertEqual("encounter.trainer_boy", generator.encounter_music_track(self.outfit))
        self.assertEqual("encounter.trainer_girl", generator.encounter_music_track(girl))
        self.assertEqual("encounter.trainer_bad_guys", generator.encounter_music_track(villain))
        configured = {
            "trainer_encounter_boy": "custom.encounter_boy",
            "trainer_encounter_girl": "custom.encounter_girl",
            "trainer_encounter_bad_guys": "custom.encounter_villain",
        }
        self.assertEqual(
            "custom.encounter_girl",
            generator.encounter_music_track(girl, configured),
        )

    def test_encounter_uses_its_own_appearance_skin_and_arm_model(self) -> None:
        first = json.loads(json.dumps(self.document))
        second = json.loads(json.dumps(self.document))
        second["npc"]["appearance"]["resource"] = "rctmod:trainers/single/kanto_koga"
        second["_easy_npc_arm_model"] = "slim"

        first_uuid = generator.encounter_skin_uuid(first, self.outfit)
        second_uuid = generator.encounter_skin_uuid(second, self.outfit)
        second_preset = generator.encounter_preset_snbt(second, self.outfit)

        self.assertNotEqual(first_uuid, second_uuid)
        self.assertNotEqual(second_uuid, self.outfit["adapters"]["easy_npc"]["custom_skin_uuid"])
        self.assertIn(generator.uuid_int_array(second_uuid), second_preset)
        self.assertIn('variantType:"ALEX"', second_preset)
        self.assertIn('VariantType:"ALEX"', second_preset)

    def test_reads_selected_rct_skin_from_installed_resource_pack(self) -> None:
        png = b"\x89PNG\r\n\x1a\n" + b"test-skin"
        with tempfile.TemporaryDirectory() as directory:
            instance = Path(directory)
            resource_pack = instance / "resourcepacks" / "COBBLEVERSE RCTmod RP.zip"
            resource_pack.parent.mkdir(parents=True)
            with zipfile.ZipFile(resource_pack, "w") as archive:
                archive.writestr(
                    "assets/rctmod/textures/trainers/single/kanto_koga.png", png
                )
            with mock.patch.dict(os.environ, {"COBBLEVERSE_INSTANCE": directory}):
                result = generator.installed_rct_skin(
                    "rctmod:trainers/single/kanto_koga"
                )

        self.assertEqual(png, result)

    def test_compiles_gym_leader_rewards_from_league_authoring_entry(self) -> None:
        league = json.loads(
            (PROJECT_ROOT / "content/catalogs/league-progression.json").read_text(encoding="utf-8")
        )
        entry = next(item for item in league["entries"] if item["role"] == "gym_leader")
        post_victory_cap = generator.league_post_victory_level_caps(
            league["entries"]
        )[entry["id"]]
        entry = json.loads(json.dumps(entry))
        entry["encounter"]["rewards"].update({
            "money": 1200, "item": "cobblemon:rare_candy", "item_count": 2,
        })
        document = generator.league_encounter_document(entry, post_victory_cap)
        battle_id = entry["encounter"]["battle_id"]
        battle_path = PROJECT_ROOT / "content/battles/gym_leaders" / f"{battle_id.rsplit('/', 1)[-1]}.json"
        battle = json.loads(battle_path.read_text(encoding="utf-8"))
        document["_battle_presets"] = {battle_id: battle}
        catalog = json.loads(
            (PROJECT_ROOT / "content/catalogs/trainer-outfits.json").read_text(encoding="utf-8")
        )
        outfit = next(item for item in catalog["outfits"] if item["trainer_class"] == "cobbleventure:trainer_class/gym_leader")

        preset = generator.encounter_preset_snbt(document, outfit)

        self.assertIn("cobbledollars give @1 1200", preset)
        self.assertIn("cobbleventurebag acquire @1 cobblemon:rare_candy 2", preset)
        self.assertIn(entry["encounter"]["rewards"]["badge_id"], preset)
        self.assertIn(
            f"cobbleventure_progress level_cap @1 {post_victory_cap}",
            preset,
        )

    def test_gym_caps_advance_to_next_challenge_and_end_unrestricted(self) -> None:
        entries = [
            {
                "id": "cobbleventure:league/test/first", "role": "gym_leader",
                "generation": 1, "region": "cobbleventure:region/test",
                "order": 1, "level_cap": 20,
            },
            {
                "id": "cobbleventure:league/test/second", "role": "gym_leader",
                "generation": 1, "region": "cobbleventure:region/test",
                "order": 2, "level_cap": 25,
            },
        ]

        self.assertEqual(
            {
                "cobbleventure:league/test/first": 25,
                "cobbleventure:league/test/second": 100,
            },
            generator.league_post_victory_level_caps(entries),
        )

    def test_compiles_each_league_dialogue_line_as_a_sequential_dialogue(self) -> None:
        league = json.loads(
            (PROJECT_ROOT / "content/catalogs/league-progression.json").read_text(encoding="utf-8")
        )
        entry = json.loads(json.dumps(next(item for item in league["entries"] if item["role"] == "gym_leader")))
        entry["encounter"]["dialogue"]["challenge"] = ["첫 번째 대사", "두 번째 대사"]

        document = generator.league_encounter_document(entry)
        battle_id = entry["encounter"]["battle_id"]
        battle_path = PROJECT_ROOT / "content/battles/gym_leaders" / f"{battle_id.rsplit('/', 1)[-1]}.json"
        document["_battle_presets"] = {
            battle_id: json.loads(battle_path.read_text(encoding="utf-8"))
        }
        challenge_dialogues = [
            command for command in document["events"][0]["commands"]
            if command.get("type") == "dialogue" and command.get("id", "").startswith("challenge_")
        ]

        self.assertEqual([command["id"] for command in challenge_dialogues], ["challenge_1", "challenge_2"])
        self.assertEqual([command["text"]["ko_kr"] for command in challenge_dialogues], ["첫 번째 대사", "두 번째 대사"])
        dialogues = generator.event_script_dialogues(document)
        self.assertIn('Label:"challenge_1"', dialogues)
        self.assertIn('Name:"다음",Actions:[{Cmd:"challenge_2",Type:"OPEN_NAMED_DIALOG"}]', dialogues)
        self.assertIn('Label:"challenge_2"', dialogues)

    def test_professor_oak_closes_dialogue_then_opens_starter_roulette(self) -> None:
        document = json.loads(
            (
                PROJECT_ROOT
                / "content/source/story/professor_oak.json"
            ).read_text(encoding="utf-8")
        )

        preset = generator.encounter_preset_snbt(document, self.outfit)

        self.assertIn('Label:"greeting_1"', preset)
        self.assertIn('Cmd:"greeting_2",Type:"OPEN_NAMED_DIALOG"', preset)
        self.assertIn(
            'Cmd:"/cobbleventure_starter_state @initiator",Debug:1b,'
            'ExecAsUser:0b,PermLevel:2,Type:"COMMAND"',
            preset,
        )
        self.assertIn('Label:"starter_received"', preset)
        self.assertIn(
            'Conditions:[{Name:"cv_starter_recv",Operation:"EQUALS",'
            'Type:"SCOREBOARD",Value:1}]',
            preset,
        )
        self.assertIn(
            'Actions:[{Type:"CLOSE_DIALOG"},'
            '{Cmd:"/cobbleventure_starter_roulette @initiator @s starter_chosen_praise",Debug:1b,'
            'ExecAsUser:0b,PermLevel:2,Type:"COMMAND"}',
            preset,
        )
        self.assertEqual(
            preset.count('/cobbleventure_starter_roulette @initiator @s starter_chosen_praise'), 1
        )
        self.assertIn('Label:"starter_chosen_praise"', preset)
        self.assertIn('Label:"pokedex_offer"', preset)
        self.assertIn(
            'cobbleventurebag acquire @initiator cobblemon:pokedex_red 1', preset
        )
        self.assertIn('Label:"pokedex_explanation"', preset)
        self.assertNotIn('ExecAsUser:1b', preset)

    def test_npc_money_setting_overrides_legacy_event_money(self) -> None:
        commands = self.document["events"][0]["commands"]
        reward_label = next(
            index
            for index, command in enumerate(commands)
            if command.get("type") == "label" and command.get("name") == "victory_reward"
        )
        commands.insert(
            reward_label + 1,
            {"type": "give_money", "mode": "fixed", "amount": 9999},
        )

        preset = generator.encounter_preset_snbt(self.document, self.outfit)

        self.assertEqual(preset.count("cobbleventure_reward prepare @initiator regional"), 2)
        self.assertNotIn("cobbledollars give @1 9999", preset)

    def test_battle_without_item_limit_omits_optional_tbcs_rule(self) -> None:
        battle = next(iter(self.document["_battle_presets"].values()))
        battle["battle"]["rules"] = {"can_forfeit": True}

        preset = generator.encounter_preset_snbt(self.document, self.outfit)

        self.assertIn("tbcs battle GEN_9_SINGLES", preset)
        self.assertNotIn("maxItemUses", preset)

    def test_new_npc_routes_from_instance_state_not_global_victory_flag(self) -> None:
        preset = generator.encounter_preset_snbt(self.document, self.outfit)

        victory_objective = generator.flag_objective(
            "cobbleventure:flag/trainer/ai_test/defeated"
        )
        self.assertIn(f"scoreboard players set @1 {victory_objective} 1", preset)
        self.assertIn(
            'Name:"cv_npc_defeated",Operation:"EQUALS",Type:"SCOREBOARD",Value:1',
            preset,
        )
        self.assertNotIn(
            f'Name:"{victory_objective}",Operation:"EQUALS",Type:"SCOREBOARD"',
            preset,
        )

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

        self.assertIn('2:[\\"cobbledollars remove @1 250\\"', preset)
        self.assertNotIn('matches ..-1 run scoreboard players set @1 cobbleventure_money 0', preset)

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

    def test_generates_field_move_reward_for_battle_callback(self) -> None:
        commands = self.document["events"][0]["commands"]
        win_label = next(
            index for index, command in enumerate(commands)
            if command.get("type") == "label" and command.get("name") == "victory_reward"
        )
        commands.insert(win_label + 1, {"type": "grant_field_move", "move": "surf"})

        preset = generator.encounter_preset_snbt(self.document, self.outfit)

        self.assertIn("cobbleventure_field_move grant @1 surf", preset)

    def test_generates_field_move_dialogue_action_for_initiator(self) -> None:
        action = generator.easy_npc_action(
            {"type": "grant_field_move", "move": "fly"}, self.document
        )

        self.assertIn("/cobbleventure_field_move grant @initiator fly", action)

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

    def test_localized_uses_selected_export_language_with_korean_fallback(self) -> None:
        previous = generator.EXPORT_LANGUAGE
        try:
            generator.EXPORT_LANGUAGE = "en_us"
            self.assertEqual(
                "Pokemart Clerk",
                generator.localized({"ko_kr": "프렌들리숍 판매원", "en_us": "Pokemart Clerk"}),
            )
            self.assertEqual("한국어만 있음", generator.localized({"ko_kr": "한국어만 있음"}))
        finally:
            generator.EXPORT_LANGUAGE = previous

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

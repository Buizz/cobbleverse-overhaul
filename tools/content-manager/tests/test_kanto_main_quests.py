from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

from test_quest_system import CORE_ROOT, content_manager


PROJECT = CORE_ROOT / "content-projects/cobbleventure-main"
CONTENT = PROJECT / "content"
ORDER = [
    "choose_starter", "receive_pokenav", "receive_travel_tools", "learn_teleport",
    "meet_bill", "restore_power_plant", "learn_surf", "learn_rock_climb",
    "reach_indigo_plateau",
]


def read(relative):
    return json.loads((CONTENT / relative).read_text(encoding="utf-8"))


def quest(slug):
    return read(f"quests/cobbleventure/main/{slug}.json")


class KantoMainQuestTests(unittest.TestCase):
    def test_authored_order_has_valid_automatic_quests_and_real_npc_targets(self):
        payload = content_manager.main_quest_progression_payload(PROJECT)
        steps = payload["document"]["steps"]
        self.assertEqual(ORDER, [step["id"] for step in steps])
        npc_ids = {npc["id"] for npc in payload["npcs"]}
        self.assertEqual(len(npc_ids), len(payload["npcs"]))
        for step in steps:
            with self.subTest(step=step["id"]):
                document = quest(step["id"])
                path = CONTENT / f"quests/cobbleventure/main/{step['id']}.json"
                _, issues = content_manager.validate_quest_file(path)
                self.assertFalse([issue for issue in issues if issue.level == "error"], issues)
                self.assertEqual(step["quest"], document["id"])
                self.assertIn(step["npc"], npc_ids)
                self.assertEqual(step["npc"], document["objectives"][0]["marker"]["target"])
                self.assertEqual("automatic", document["completion"]["mode"])
                self.assertTrue(document["global_activation"]["enabled"])
                self.assertNotIn("event_hooks", document, "Existing reward owners must not be duplicated")

    def test_badge_gaps_leave_default_gym_guidance_available(self):
        required = {
            "learn_teleport": {"boulder"}, "meet_bill": {"boulder"},
            "restore_power_plant": {"thunder"}, "learn_surf": {"rainbow"},
            "learn_rock_climb": {"soul"},
            "reach_indigo_plateau": {"boulder", "cascade", "thunder", "rainbow", "soul", "earth", "marsh", "volcano"},
        }
        for slug, badges in required.items():
            group = quest(slug)["global_activation"]["conditions"]
            self.assertEqual("all", group["condition_mode"])
            actual = {c["badge"].rsplit("/", 1)[-1] for c in group["conditions"] if c["type"] == "badge"}
            self.assertEqual(badges, actual, slug)
        final_conditions = quest("reach_indigo_plateau")["global_activation"]["conditions"]["conditions"]
        self.assertIn({"type": "variable", "source": "persistent_data",
                       "key": "cobbleventureFieldMove.rock_climb", "operator": ">=", "value": 1}, final_conditions)

    def test_completion_uses_existing_reward_flags_and_real_hm_ownership(self):
        event_flags = {
            "receive_pokenav": ("story/starter_town_gatekeeper_minho", ["story/pokenav_received"]),
            "receive_travel_tools": ("rewards/feature_map_guide", ["rewards/feature/map"]),
            "learn_teleport": ("rewards/feature_teleport_guide", ["rewards/feature/settlement_teleport"]),
            "meet_bill": ("rewards/feature_pc_technician", ["rewards/feature/pc"]),
        }
        for slug, (event, suffixes) in event_flags.items():
            script = (CONTENT / f"events/cobbleventure/{event}.cves").read_text(encoding="utf-8")
            conditions = quest(slug)["objectives"][0]["conditions"]["conditions"]
            for suffix in suffixes:
                key = f"cobbleventure:flag/{suffix}"
                self.assertIn({"type": "flag", "key": key, "value": True}, conditions)
                self.assertIn(f'set_flag "{key}" true', script)
        for slug, move in [("restore_power_plant", "flash"), ("learn_surf", "surf"),
                           ("learn_rock_climb", "rock_climb"), ("reach_indigo_plateau", "fly")]:
            conditions = quest(slug)["objectives"][0]["conditions"]["conditions"]
            self.assertIn({"type": "variable", "source": "persistent_data",
                           "key": f"cobbleventureFieldMove.{move}", "operator": ">=", "value": 1}, conditions)
            self.assertFalse(any(c["type"] == "flag" for c in conditions), slug)
        dungeon = read("dungeons/generation_1/rocket_power_plant.json")
        self.assertIn("flash", dungeon["rewards"]["first_clear_field_moves"])
        for slug, move in [("koga", "surf"), ("giovanni_gym", "rock_climb")]:
            script = (CONTENT / f"events/cobbleventure/gym_leaders/{slug}.cves").read_text(encoding="utf-8")
            self.assertIn(f"grant_field_move {move}", script)

    def test_gym_prerequisites_do_not_cycle_through_saffron_gate(self):
        gyms = {gym["id"].rsplit("/", 1)[-1]: gym for gym in read("catalogs/gyms.json")["gyms"]}
        earned = set()
        for city, badge in [("pewter", "boulder"), ("cerulean", "cascade"), ("vermilion", "thunder"),
                            ("celadon", "rainbow"), ("fuchsia", "soul"), ("viridian", "earth"),
                            ("saffron", "marsh"), ("cinnabar", "volcano")]:
            access = gyms[city]["access"]
            if access["require_previous_gym"]:
                self.assertIn(access["previous_badge"], earned, city)
            if city == "saffron":
                gate = next(obj for obj in read("worlds/generation_1.json")["objects"] if obj["id"] == "saffron_gate_south")
                for condition in gate["properties"]["conditions"]:
                    self.assertIn(condition["badge"], earned)
            earned.add(f"cobbleventure:badge/kanto/{badge}")

    def test_generated_gym_npcs_can_be_saved_without_source_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for folder in ("source", "battles", "quests"):
                shutil.copytree(CONTENT / folder, root / "content" / folder)
            (root / "content/catalogs").mkdir()
            shutil.copy2(CONTENT / "catalogs/league-progression.json", root / "content/catalogs/league-progression.json")
            progression = read("catalogs/main-quest-progression.json")
            issues = content_manager.save_main_quest_progression(root, progression)
            self.assertFalse([issue for issue in issues if issue.level == "error"], issues)
            progression["steps"][-1]["npc"] = "cobbleventure:npc/missing"
            self.assertTrue(content_manager.save_main_quest_progression(root, progression))

    def test_all_authored_steps_are_packaged_unchanged(self):
        module_path = CORE_ROOT / "tools/mod-builder/build_data_mod.py"
        spec = importlib.util.spec_from_file_location("kanto_quest_packaging", module_path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        sys.path.insert(0, str(module_path.parent))
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(CONTENT / "quests", root / module.QUEST_SOURCE_DIR)
            source = root / module.MAIN_QUEST_PROGRESSION_SOURCE
            source.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(CONTENT / "catalogs/main-quest-progression.json", source)
            output = root / "output"
            module._package_quests(root, output)
            module._package_main_quest_progression(root, output)
            self.assertEqual(source.read_bytes(), (output / module.MAIN_QUEST_PROGRESSION_ENTRY).read_bytes())
            for slug in ORDER:
                self.assertEqual(quest(slug), json.loads((output / f"data/cobbleventure/quest/main/{slug}.json").read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()

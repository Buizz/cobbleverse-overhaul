import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools/content-manager"))
from cves.project import compile_project, write_project
from cves.rewards import npc_money_reward

PROJECT = ROOT / "content-projects/cobbleventure-main"


class CvesRewardTests(unittest.TestCase):
    def test_normalization_is_non_mutating_and_preserves_disabled_and_zero(self):
        for enabled in (True, False):
            source = {"npc": {"battle_rewards": {"money": {
                "enabled": enabled, "mode": "fixed", "amount": 0,
                "conditions": [{"type": "flag_equals", "key": "test:flag", "value": 0}],
            }}}}
            before = copy.deepcopy(source)
            money = npc_money_reward(source)
            self.assertEqual(source, before)
            self.assertEqual(money["enabled"], enabled)
            self.assertEqual(money["amount"], 0)
            self.assertIs(money["conditions"][0]["value"], False)
            self.assertEqual(money["held_item"], "cobblemon:amulet_coin")
        self.assertIsNone(npc_money_reward({}))

    def test_invalid_conditions_and_amounts_fail_before_packaging(self):
        for change in ({"amount": True}, {"amount": -1}, {"amount": 2**31},
                       {"conditions": [{"type": "has_item"}]},
                       {"conditions": [{"type": "flag_equals", "key": "test:f", "value": 2}]}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                npc_money_reward({"npc": {"battle_rewards": {"money": {
                    "enabled": True, "mode": "fixed", "amount": 10, **change,
                }}}})

    @classmethod
    def setUpClass(cls):
        cls.build = compile_project(PROJECT)
        cls.bindings = {str(a.relative_path).replace("\\", "/"): a.document for a in cls.build.bindings}

    def test_every_regular_trainer_has_explicit_money_and_deployed_binding_matches(self):
        count = 0
        for path in (PROJECT / "content/source").rglob("*.json"):
            source = json.loads(path.read_text(encoding="utf-8"))
            if source.get("event_design", {}).get("preset", {}).get("type") != "battle":
                continue
            with self.subTest(npc=source["id"]):
                reward = npc_money_reward(source)
                self.assertIsNotNone(reward)
                relative = path.relative_to(PROJECT / "content/source").as_posix()
                binding = self.bindings[f"cobbleventure/npc_event_binding/{relative}"]
                self.assertEqual(binding["money_reward"], reward)
                count += 1
        self.assertGreaterEqual(count, 95)

    def test_yuna_fallback_is_220_and_existing_kanto_settings_are_preserved(self):
        money = self.bindings["cobbleventure/npc_event_binding/samples/sample_lass_yuna.json"]["money_reward"]
        self.assertEqual(money["fallback_region_level"] * money["per_level"] + money["offset"], 220)
        sora = self.bindings["cobbleventure/npc_event_binding/generation_1/kanto_beauty_sora.json"]["money_reward"]
        self.assertEqual(sora["fallback_region_level"], 23)
        self.assertTrue(sora["held_item_bonus"])

    def test_gym_rewards_remain_script_owned_and_source_binding_stays_minimal(self):
        gyms = [v for k, v in self.bindings.items() if "/gym_leaders/" in k]
        self.assertEqual(len(gyms), 8)
        for binding in gyms:
            self.assertNotIn("money_reward", binding)
        for path in (PROJECT / "content/event-bindings").rglob("*.json"):
            self.assertNotIn("money_reward", json.loads(path.read_text(encoding="utf-8")))

    def test_generated_rewards_survive_the_data_mod_packaging_path(self):
        sys.path.insert(0, str(ROOT / "tools/mod-builder"))
        import build_data_mod
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generated = root / build_data_mod.GENERATED_CONTENT_DIR / "cves/data"
            write_project(self.build, generated)
            output = root / "packaged"
            build_data_mod._package_generated_cves_content(root, output)
            for relative, expected in self.bindings.items():
                actual = json.loads((output / "data" / relative).read_text(encoding="utf-8"))
                self.assertEqual(actual, expected)

    def test_npc_setting_edit_changes_binding_without_rewriting_shared_cves(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            script = project / "content/events/test/shared.cves"
            script.parent.mkdir(parents=True)
            script.write_text("event interact { page default { stop } }", encoding="utf-8")
            outputs = []
            for amount, enabled in ((220, True), (700, True), (700, False)):
                for slug, npc_amount in (("first", amount), ("second", 50)):
                    binding = project / f"content/event-bindings/test/{slug}.json"
                    binding.parent.mkdir(parents=True, exist_ok=True)
                    binding.write_text(json.dumps({"schema_version": 1,
                        "script_id": "test:event_script/shared"}), encoding="utf-8")
                    source = project / f"content/source/{slug}.json"
                    source.parent.mkdir(parents=True, exist_ok=True)
                    source.write_text(json.dumps({"npc": {"battle_rewards": {"money": {
                        "enabled": enabled if slug == "first" else True,
                        "mode": "fixed", "amount": npc_amount,
                    }}}}), encoding="utf-8")
                build = compile_project(project)
                outputs.append(build.scripts)
                first, second = [a.document["money_reward"] for a in build.bindings]
                self.assertEqual(first["amount"], amount)
                self.assertEqual(first["enabled"], enabled)
                self.assertEqual(second["amount"], 50)
                self.assertTrue(second["enabled"])
            self.assertTrue(all(scripts == outputs[0] for scripts in outputs))


if __name__ == "__main__":
    unittest.main()

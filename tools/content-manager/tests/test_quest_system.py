from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CORE_ROOT = Path(__file__).parents[3]
MODULE_PATH = Path(__file__).parents[1] / "content_manager.py"
SPEC = importlib.util.spec_from_file_location("quest_content_manager", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
content_manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = content_manager
SPEC.loader.exec_module(content_manager)
sys.path.insert(0, str(Path(__file__).parents[1]))

from cves.catalog import ResourceCatalog, ResourceKind
from cves.compiler import compile_program
from cves.parser import parse


def quest_document() -> dict:
    return {
        "$schema": "../../../schemas/quest.schema.json",
        "schema_version": 1,
        "id": "cobbleventure:quest/main/get_cut",
        "enabled": True,
        "category": "main",
        "display_name": {"ko_kr": "풀베기 준비"},
        "summary": {"ko_kr": "풀베기를 준비하세요."},
        "accept_conditions": {"condition_mode": "all", "conditions": []},
        "objectives": [{
            "id": "unlock_cut",
            "text": {"ko_kr": "풀베기 배우기"},
            "conditions": {"condition_mode": "all", "conditions": [{
                "type": "flag", "key": "cobbleventure:flag/field_move/cut", "value": True,
            }]},
        }],
        "completion": {"mode": "npc_turn_in"},
        "next_quests": [],
    }


class QuestSystemTests(unittest.TestCase):
    def test_quest_editor_hides_unused_guidance_and_followup_fields(self) -> None:
        web = MODULE_PATH.parent / "web"
        markup = (web / "quests.html").read_text(encoding="utf-8")
        script = (web / "quest-editor.js").read_text(encoding="utf-8")
        for field in ("requiredTools", "nextQuests"):
            self.assertNotIn(f'name="{field}"', markup)
            self.assertNotIn(field, script)
        self.assertIn('name="completionMode"', markup)
        self.assertIn('href="/main-quest-flow.html"', markup)

    def test_quest_pages_reuse_the_existing_condition_selector(self) -> None:
        web = MODULE_PATH.parent / "web"
        manager = MODULE_PATH.read_text(encoding="utf-8")
        for filename in ("index.html", "quests.html", "quest-global.html"):
            markup = (web / filename).read_text(encoding="utf-8")
            self.assertIn('src="/player-condition-editor.js"', markup)
        for filename in ("player-condition-editor.js", "quest-conditions.css"):
            self.assertIn(f'"/{filename}"', manager)
        markup = (web / "quests.html").read_text(encoding="utf-8")
        script = (web / "quest-editor.js").read_text(encoding="utf-8")
        global_markup = (web / "quest-global.html").read_text(encoding="utf-8")
        global_script = (web / "quest-global.js").read_text(encoding="utf-8")
        self.assertIn('id="quest-accept-conditions"', markup)
        self.assertIn('id="quest-objectives"', markup)
        self.assertNotIn('name="acceptConditions"', markup)
        self.assertNotIn('name="objectives"', markup)
        self.assertIn('conditions: conditionEditor.read(acceptEditor)', script)
        self.assertIn('conditionEditor.read(editor)', script)
        self.assertIn('id="quest-global-conditions"', global_markup)
        self.assertNotIn('name="conditions"', global_markup)
        self.assertIn('conditionEditor.read(globalEditor)', global_script)
        for editor_script in (script, global_script):
            self.assertIn('method: "PUT"', editor_script)
            self.assertNotIn('method: "POST"', editor_script)
            self.assertIn('throw new Error(payload.error ||', editor_script)

    @unittest.skipUnless(shutil.which("node"), "Node.js is required for web editor behavior tests")
    def test_shared_condition_selector_behavior(self) -> None:
        result = subprocess.run(
            [shutil.which("node"), "--test", str(Path(__file__).with_name("player_condition_editor.test.cjs"))],
            capture_output=True, text=True, encoding="utf-8",
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_web_quest_document_uses_shared_player_conditions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "quest.json"
            path.write_text(json.dumps(quest_document()), encoding="utf-8")
            quest_id, issues = content_manager.validate_quest_file(path)
        self.assertEqual("cobbleventure:quest/main/get_cut", quest_id)
        self.assertFalse(any(issue.level == "error" for issue in issues), issues)

    def test_v5_compiles_three_quest_commands_and_state_expression(self) -> None:
        source = '''event interact(range: 4) {
  page when quest_state("cobbleventure:quest/main/get_cut") == "completed" {
    say npc "완료"
  }
  page default {
    id "quest/grant" quest_grant "cobbleventure:quest/main/get_cut" -> granted
    quest_check "cobbleventure:quest/main/get_cut" -> checked
    if checked.ready {
      id "quest/complete" quest_complete "cobbleventure:quest/main/get_cut" -> completed
    }
  }
}'''
        catalog = ResourceCatalog()
        catalog.add(ResourceKind.QUEST, "cobbleventure:quest/main/get_cut")
        catalog.complete_kinds.add(ResourceKind.QUEST)
        ir = compile_program(
            parse(source, "quest.cves"),
            "cobbleventure:event_script/story/quest_test",
            catalog,
        )
        commands = [
            instruction.get("command")
            for event in ir["events"] for instruction in event["instructions"]
            if instruction["op"] == "command"
        ]
        self.assertIn("quest_grant", commands)
        self.assertIn("quest_check", commands)
        self.assertIn("quest_complete", commands)

    def test_existing_web_server_exposes_quest_editor(self) -> None:
        manager = MODULE_PATH.read_text(encoding="utf-8")
        markup = (Path(__file__).parents[1] / "web/quests.html").read_text(encoding="utf-8")
        global_markup = (Path(__file__).parents[1] / "web/quest-global.html").read_text(encoding="utf-8")
        self.assertIn('"/api/quests": "quests"', manager)
        self.assertIn('id="quest-form"', markup)
        self.assertIn('href="/cves.html"', markup)
        self.assertIn('"/quest-global.html"', manager)
        self.assertIn('id="activation-form"', global_markup)
        self.assertIn('"/main-quest-flow.html"', manager)
        flow_markup = (Path(__file__).parents[1] / "web/main-quest-flow.html").read_text(encoding="utf-8")
        flow_script = (Path(__file__).parents[1] / "web/main-quest-flow.js").read_text(encoding="utf-8")
        self.assertIn('id="flow-steps"', flow_markup)
        self.assertIn('method: "PUT"', flow_script)

    def test_existing_document_api_storage_saves_and_lists_quests(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = "content/quests/cobbleventure/main/get_cut.json"
            document = quest_document()
            document["global_activation"] = {
                "enabled": True,
                "conditions": {
                    "condition_mode": "all",
                    "conditions": [{
                        "type": "flag",
                        "key": "cobbleventure:flag/story/arrived",
                        "value": True,
                    }],
                },
            }
            target, issues = content_manager._save_document(
                root, "quests", path, document
            )
            items = content_manager._list_documents(root, "quests")
        self.assertIsNotNone(target)
        self.assertFalse(any(issue.level == "error" for issue in issues), issues)
        self.assertEqual("cobbleventure:quest/main/get_cut", items[0]["id"])
        self.assertEqual("main", items[0]["category"])
        self.assertTrue(items[0]["global_activation_enabled"])

    def test_global_activation_requires_main_quest_and_non_empty_conditions(self) -> None:
        document = quest_document()
        document["global_activation"] = {
            "enabled": True,
            "conditions": {
                "condition_mode": "all",
                "conditions": [{
                    "type": "flag",
                    "key": "cobbleventure:flag/story/arrived",
                    "value": True,
                }],
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "quest.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            _, valid_issues = content_manager.validate_quest_file(path)
            document["category"] = "side"
            path.write_text(json.dumps(document), encoding="utf-8")
            _, side_issues = content_manager.validate_quest_file(path)
            document["category"] = "main"
            document["global_activation"]["conditions"]["conditions"] = []
            path.write_text(json.dumps(document), encoding="utf-8")
            _, empty_issues = content_manager.validate_quest_file(path)
        self.assertFalse(any(issue.level == "error" for issue in valid_issues), valid_issues)
        self.assertTrue(any("메인 퀘스트" in issue.message for issue in side_issues))
        self.assertTrue(any("하나 이상" in issue.message for issue in empty_issues))

    def test_main_quest_progression_saves_authored_quest_and_npc_order(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            quest_path = "content/quests/cobbleventure/main/get_cut.json"
            content_manager._save_document(root, "quests", quest_path, quest_document())
            npc_path = root / "content/source/story/professor_oak.json"
            npc_path.parent.mkdir(parents=True)
            npc_path.write_text(json.dumps({
                "id": "cobbleventure:npc/professor_oak",
                "enabled": True,
                "name": {"ko_kr": "오박사"},
            }), encoding="utf-8")
            document = {
                "$schema": "../schemas/main-quest-progression.schema.json",
                "schema_version": 1,
                "enabled": True,
                "steps": [{
                    "id": "get_cut",
                    "quest": "cobbleventure:quest/main/get_cut",
                    "npc": "cobbleventure:npc/professor_oak",
                }],
            }
            issues = content_manager.save_main_quest_progression(root, document)
            payload = content_manager.main_quest_progression_payload(root)
        self.assertFalse(any(issue.level == "error" for issue in issues), issues)
        self.assertEqual("get_cut", payload["document"]["steps"][0]["id"])
        self.assertEqual(1, len(payload["quests"]))
        self.assertEqual(1, len(payload["npcs"]))


if __name__ == "__main__":
    unittest.main()

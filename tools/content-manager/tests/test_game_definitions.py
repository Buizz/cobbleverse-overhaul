import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
MODULE_PATH = ROOT / "tools" / "content-manager" / "content_manager.py"
SPEC = importlib.util.spec_from_file_location("content_manager_game_definitions", MODULE_PATH)
content_manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = content_manager
SPEC.loader.exec_module(content_manager)


class GameDefinitionTests(unittest.TestCase):
    def validate(self, payload):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "game-definitions.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            return content_manager.validate_game_definitions_file(path)

    def test_empty_catalog_is_valid(self):
        issues = self.validate({"schema_version": 1, "items": [], "variables": []})
        self.assertEqual([], issues)

    def test_item_and_scoped_variables_are_valid(self):
        payload = {
            "schema_version": 1,
            "items": [{
                "id": "cobbleventure:item/quest_letter",
                "base_item": "minecraft:paper",
                "display_name": {"ko_kr": "박사의 편지"},
                "description": {"ko_kr": "NPC 분기에 사용하는 퀘스트 아이템"},
            }],
            "variables": [
                {"id": "cobbleventure:flag/story_started", "scope": "global", "type": "boolean", "default": False, "display_name": {"ko_kr": "스토리 시작"}},
                {"id": "cobbleventure:flag/player/reputation", "scope": "player", "type": "integer", "default": 0, "display_name": {"ko_kr": "평판"}},
            ],
        }
        self.assertEqual([], self.validate(payload))

    def test_duplicate_ids_and_wrong_default_type_are_rejected(self):
        payload = {
            "schema_version": 1,
            "items": [{"id": "cobbleventure:shared/id", "base_item": "minecraft:paper", "display_name": {"ko_kr": "편지"}}],
            "variables": [{"id": "cobbleventure:shared/id", "scope": "player", "type": "integer", "default": False, "display_name": {"ko_kr": "잘못된 변수"}}],
        }
        issues = self.validate(payload)
        self.assertTrue(any("중복 선언 ID" in issue.message for issue in issues))
        self.assertTrue(any(issue.path == "$.variables[0].default" for issue in issues))

    def test_web_exposes_definition_screen_and_npc_datalists(self):
        page = (ROOT / "tools" / "content-manager" / "web" / "index.html").read_text(encoding="utf-8")
        script = (ROOT / "tools" / "content-manager" / "web" / "app.js").read_text(encoding="utf-8")
        self.assertIn('data-section="definitions"', page)
        self.assertIn('id="game-item-list"', page)
        self.assertIn('id="game-variable-list"', page)
        self.assertIn('list="declared-item-ids"', script)
        self.assertIn('list="declared-variable-ids"', script)


if __name__ == "__main__":
    unittest.main()

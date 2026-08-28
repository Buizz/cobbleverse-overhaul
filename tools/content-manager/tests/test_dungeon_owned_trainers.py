import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
MODULE_PATH = ROOT / "tools" / "content-manager" / "content_manager.py"
SPEC = importlib.util.spec_from_file_location("dungeon_owned_content_manager", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
content_manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = content_manager
SPEC.loader.exec_module(content_manager)


class DungeonOwnedTrainerTests(unittest.TestCase):
    def test_casino_uses_dungeon_owned_actor_and_trigger_data(self) -> None:
        source = (
            ROOT / "content-projects" / "cobbleventure-main" / "content"
            / "dungeons" / "generation_1" / "rocket_casino_hideout.json"
        )
        document = json.loads(source.read_text(encoding="utf-8"))

        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "dungeon.json"
            target.write_text(json.dumps(document), encoding="utf-8")
            _, issues = content_manager.validate_dungeon_file(target)

        self.assertFalse([issue for issue in issues if issue.level == "error"], issues)
        self.assertTrue(all("trainers" in encounter for encounter in document["encounters"]))
        self.assertTrue(all("trigger" in encounter for encounter in document["encounters"]))
        self.assertTrue(all("npcs" not in encounter for encounter in document["encounters"]))

    def test_web_editor_exposes_dungeon_owned_trainer_mode(self) -> None:
        script = (ROOT / "tools" / "content-manager" / "web" / "app.js").read_text(
            encoding="utf-8"
        )

        self.assertIn('option("dungeon", "던전 자동 NPC"', script)
        self.assertIn("function dungeonOwnedTrainerFields", script)
        self.assertIn("ownedTrainerClass", script)
        self.assertIn("triggerWarningTrack", script)


if __name__ == "__main__":
    unittest.main()

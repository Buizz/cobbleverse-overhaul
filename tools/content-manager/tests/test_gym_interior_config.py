import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
sys.path.insert(0, str(ROOT / "tools" / "content-manager"))

import content_manager  # noqa: E402


class GymInteriorConfigTests(unittest.TestCase):
    def setUp(self) -> None:
        self.settlement = json.loads(
            (PROJECT_ROOT / "content/settlements/generation_1/starter_town.json").read_text(
                encoding="utf-8"
            )
        )
        gym = self.settlement["structure_profile"]["gym"]
        gym["entrance"] = {
            "door_offset": {"x": 12, "y": 3, "z": 3},
            "outside_offset": {"x": 12, "y": 4, "z": 1},
            "facing": "north",
            "enter_dialogue": ["체육관 문이 열렸다."],
        }
        gym["interior"] = {
            "structure": "rgs:pewter_gym",
            "entry_offset": {"x": 12, "y": 4, "z": 5},
            "exit_door_offset": {"x": 12, "y": 3, "z": 3},
        }

    def validate(self, document: dict) -> list[content_manager.Issue]:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settlement.json"
            path.write_text(
                json.dumps(document, ensure_ascii=False), encoding="utf-8"
            )
            _, issues = content_manager.validate_settlement_file(path)
            return issues

    def test_accepts_instanced_gym_geometry(self) -> None:
        self.assertEqual([], self.validate(self.settlement))

    def test_rejects_gym_access_policy_in_settlement(self) -> None:
        document = copy.deepcopy(self.settlement)
        document["structure_profile"]["gym"]["entrance"]["require_previous_gym"] = True
        issues = self.validate(document)
        self.assertTrue(any("require_previous_gym" in issue.path or "require_previous_gym" in issue.message for issue in issues))


if __name__ == "__main__":
    unittest.main()

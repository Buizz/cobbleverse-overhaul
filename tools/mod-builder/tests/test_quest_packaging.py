from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "build_data_mod.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("quest_build_data_mod", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
build_data_mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = build_data_mod
SPEC.loader.exec_module(build_data_mod)


class QuestPackagingTests(unittest.TestCase):
    def test_packages_quest_under_namespaced_runtime_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / build_data_mod.QUEST_SOURCE_DIR / "test/main/welcome.json"
            source.parent.mkdir(parents=True)
            source.write_text('{"schema_version":1}', encoding="utf-8")
            output = root / "output"

            build_data_mod._package_quests(root, output)

            self.assertEqual(
                '{"schema_version":1}',
                (output / "data/test/quest/main/welcome.json").read_text(encoding="utf-8"),
            )

    def test_packages_main_quest_progression_for_server_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / build_data_mod.MAIN_QUEST_PROGRESSION_SOURCE
            source.parent.mkdir(parents=True)
            source.write_text('{"schema_version":1,"enabled":true,"steps":[]}', encoding="utf-8")
            output = root / "output"

            build_data_mod._package_main_quest_progression(root, output)

            self.assertEqual(
                source.read_text(encoding="utf-8"),
                (output / build_data_mod.MAIN_QUEST_PROGRESSION_ENTRY).read_text(encoding="utf-8"),
            )


if __name__ == "__main__":
    unittest.main()

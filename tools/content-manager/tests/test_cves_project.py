import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).parents[3]
CONTENT_MANAGER = ROOT / "tools" / "content-manager"
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
sys.path.insert(0, str(CONTENT_MANAGER))

from cves import CvesProjectError, compile_project, write_project  # noqa: E402


class CvesProjectBuildTests(unittest.TestCase):
    def test_migrated_sources_compile_to_datapack_paths(self) -> None:
        build = compile_project(
            PROJECT_ROOT,
            item_catalog=ROOT / "trainer-data/catalogs/cobblemon-items.json",
        )

        self.assertEqual(
            [
                Path("cobbleventure/event_script/examples/ai_test.json"),
                Path("cobbleventure/event_script/gym_leaders/brock.json"),
                Path("cobbleventure/event_script/samples/sample_potion_giver.json"),
                Path("cobbleventure/event_script/story/professor_oak.json"),
                Path("cobbleventure/event_script/story/starter_town_gatekeeper_minho.json"),
            ],
            [artifact.relative_path for artifact in build.scripts],
        )
        self.assertEqual(
            [
                Path("cobbleventure/npc_event_binding/examples/ai_test.json"),
                Path("cobbleventure/npc_event_binding/gym_leaders/brock.json"),
                Path("cobbleventure/npc_event_binding/samples/sample_potion_giver.json"),
                Path("cobbleventure/npc_event_binding/story/professor_oak.json"),
                Path("cobbleventure/npc_event_binding/story/starter_town_gatekeeper_minho.json"),
            ],
            [artifact.relative_path for artifact in build.bindings],
        )
        self.assertEqual(
            "cobbleventure:event_script/samples/sample_potion_giver",
            build.bindings[2].document["script_id"],
        )
        sources = {
            entry["span"]["source"]
            for artifact in build.scripts
            for event in artifact.document["events"]
            for entry in event["source_map"]
        }
        self.assertTrue(all(value.startswith("content/events/") for value in sources))
        self.assertTrue(all(str(PROJECT_ROOT) not in value for value in sources))

    def test_project_build_enforces_simple_and_starter_migration_contracts(self) -> None:
        with mock.patch(
            "cves.project.compare_simple_dialogue_migration",
            return_value=("text: V4='a', V5='b'",),
        ):
            with self.assertRaisesRegex(CvesProjectError, "simple dialogue 의미"):
                compile_project(PROJECT_ROOT)

        with mock.patch(
            "cves.project.compare_starter_event_migration",
            return_value=("pokedex_count: V4=1, V5=2",),
        ):
            with self.assertRaisesRegex(CvesProjectError, "starter event 의미"):
                compile_project(PROJECT_ROOT)

        with mock.patch(
            "cves.project.compare_gym_leader_migration",
            return_value=("badge: V4='a', V5='b'",),
        ):
            with self.assertRaisesRegex(CvesProjectError, "리그 관장 V4/V5 의미"):
                compile_project(PROJECT_ROOT)

    def test_output_is_byte_deterministic(self) -> None:
        build = compile_project(
            PROJECT_ROOT,
            item_catalog=ROOT / "trainer-data/catalogs/cobblemon-items.json",
        )
        with tempfile.TemporaryDirectory() as first_dir, tempfile.TemporaryDirectory() as second_dir:
            first = Path(first_dir)
            second = Path(second_dir)
            write_project(build, first)
            write_project(build, second)
            for artifact in build.artifacts:
                self.assertEqual(
                    (first / artifact.relative_path).read_bytes(),
                    (second / artifact.relative_path).read_bytes(),
                )

    def test_binding_must_reference_a_script_in_the_same_project(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            content = project / "content"
            content.mkdir()
            binding = content / "event-bindings/test/orphan.json"
            binding.parent.mkdir(parents=True)
            binding.write_text(json.dumps({
                "schema_version": 1,
                "script_id": "test:event_script/missing",
            }), encoding="utf-8")

            with self.assertRaisesRegex(CvesProjectError, "프로젝트에 없는"):
                compile_project(project)

    def test_namespace_directory_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            source = project / "content/events/root.cves"
            source.parent.mkdir(parents=True)
            source.write_text("event interact { page default { stop } }", encoding="utf-8")

            with self.assertRaisesRegex(CvesProjectError, "namespace"):
                compile_project(project)


if __name__ == "__main__":
    unittest.main()

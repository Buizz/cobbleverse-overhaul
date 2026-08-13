import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
sys.path.insert(0, str(ROOT / "tools" / "content-manager"))

import content_manager  # noqa: E402
import generate_easy_npc_presets as generator  # noqa: E402


class KantoGymLeaderTests(unittest.TestCase):
    def test_catalog_has_exactly_eight_kanto_gyms(self) -> None:
        catalog = content_manager.load_json(PROJECT_ROOT / "content/catalogs/gyms.json")
        self.assertEqual(
            ["pewter", "cerulean", "vermilion", "celadon", "fuchsia", "saffron", "cinnabar", "viridian"],
            [gym["id"].rsplit("/", 1)[-1] for gym in catalog["gyms"]],
        )

    def test_all_gyms_share_one_exterior_template(self) -> None:
        catalog = content_manager.load_json(PROJECT_ROOT / "content/catalogs/gyms.json")
        self.assertEqual(
            {"cobbleventure:gyms/base_gym"},
            {gym["exterior"]["structure"] for gym in catalog["gyms"]},
        )
        self.assertEqual(
            ["base_gym.nbt"],
            sorted(path.name for path in (PROJECT_ROOT / "content/structures/gyms").glob("*.nbt")),
        )
        self.assertEqual(
            {"cobbleventure:interiors/gyms/base_gym_interior"},
            {module["structure"] for gym in catalog["gyms"] for module in gym["interior"]["modules"]},
        )
        self.assertEqual(
            ["base_gym_interior.nbt"],
            sorted(path.name for path in (PROJECT_ROOT / "content/structures/interiors/gyms").glob("*.nbt")),
        )

    def test_each_gym_is_assigned_to_one_town_and_staff_belongs_to_gym(self) -> None:
        assignments = []
        for path in (PROJECT_ROOT / "content/settlements/generation_1").glob("*.json"):
            settlement = content_manager.load_json(path)
            gym = settlement["structure_profile"]["gym"]
            if gym["enabled"]:
                assignments.append(gym)
        self.assertEqual(8, len(assignments))
        self.assertEqual(8, len({gym["gym_id"] for gym in assignments}))
        self.assertEqual({"cobbleventure:gyms/base_gym"}, {gym["structure"] for gym in assignments})
        self.assertTrue(all("leader_trainer_id" not in gym for gym in assignments))
        self.assertTrue(all("league_entry_id" not in gym for gym in assignments))
        catalog = content_manager.load_json(PROJECT_ROOT / "content/catalogs/gyms.json")
        leaders = [gym["staff"]["leader"] for gym in catalog["gyms"]]
        self.assertTrue(all(leader["league_entry_id"] for leader in leaders))
        self.assertTrue(all("trainer_id" not in leader for leader in leaders))
        self.assertTrue(all("badge_id" not in leader for leader in leaders))
        self.assertTrue(all("trainer_card_skin" not in leader for leader in leaders))
        self.assertTrue(all("trainer_card_model" not in leader for leader in leaders))
        league = content_manager.load_json(PROJECT_ROOT / "content/catalogs/league-progression.json")
        appearances = [entry["encounter"]["appearance"] for entry in league["entries"] if entry["role"] == "gym_leader"]
        self.assertEqual(8, len({appearance["resource"] for appearance in appearances}))
        self.assertEqual({"leader"}, {leader["anchor"] for leader in leaders})
        self.assertTrue(all(isinstance(gym["staff"]["trainers"], list) for gym in catalog["gyms"]))

    def test_leaders_compile_from_league_entries_without_authored_npc_files(self) -> None:
        league = content_manager.load_json(PROJECT_ROOT / "content/catalogs/league-progression.json")
        leaders = [entry for entry in league["entries"] if entry["role"] == "gym_leader"]
        self.assertEqual(8, len(leaders))
        for leader in leaders:
            document = generator.league_encounter_document(leader)
            slug = document["id"].rsplit("/", 1)[-1]
            self.assertTrue((PROJECT_ROOT / f"content/battles/gym_leaders/{slug}.json").is_file())
            self.assertFalse((PROJECT_ROOT / f"content/source/trainers/gym_leaders/{slug}.json").exists())
            commands = document["events"][0]["commands"]
            self.assertTrue(any(command.get("type") == "grant_badge" for command in commands))


if __name__ == "__main__":
    unittest.main()

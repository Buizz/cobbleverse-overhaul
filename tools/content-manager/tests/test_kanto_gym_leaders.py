import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "content-manager"))

import content_manager  # noqa: E402
import generate_kanto_gym_leaders as generator  # noqa: E402


class KantoGymLeaderTests(unittest.TestCase):
    def test_catalog_has_exactly_eight_kanto_gyms(self) -> None:
        catalog = content_manager.load_json(ROOT / "content/catalogs/gyms.json")
        self.assertEqual(
            ["pewter", "cerulean", "vermilion", "celadon", "fuchsia", "saffron", "cinnabar", "viridian"],
            [gym["id"].rsplit("/", 1)[-1] for gym in catalog["gyms"]],
        )

    def test_all_gyms_share_one_exterior_template(self) -> None:
        catalog = content_manager.load_json(ROOT / "content/catalogs/gyms.json")
        self.assertEqual(
            {"cobbleventure:gyms/base_gym"},
            {gym["exterior"]["structure"] for gym in catalog["gyms"]},
        )
        self.assertEqual(
            ["base_gym.nbt"],
            sorted(path.name for path in (ROOT / "content/structures/gyms").glob("*.nbt")),
        )

    def test_each_gym_is_assigned_to_one_town_and_leader(self) -> None:
        assignments = []
        for path in (ROOT / "content/settlements/generation_1").glob("*.json"):
            settlement = content_manager.load_json(path)
            gym = settlement["structure_profile"]["gym"]
            if gym["enabled"]:
                assignments.append(gym)
        self.assertEqual(8, len(assignments))
        self.assertEqual(8, len({gym["gym_id"] for gym in assignments}))
        self.assertEqual({"cobbleventure:gyms/base_gym"}, {gym["structure"] for gym in assignments})
        self.assertTrue(all(gym.get("leader_trainer_id") for gym in assignments))
        self.assertTrue(all(gym.get("league_entry_id") for gym in assignments))

    def test_leaders_use_declared_reference_entries(self) -> None:
        references = content_manager.load_json(
            ROOT / "content/catalogs/trainer-reference-entries.json"
        )["entries"]
        reference_ids = {entry["id"] for entry in references}
        for leader in generator.LEADERS:
            self.assertIn(leader["reference"], reference_ids)
            self.assertTrue((ROOT / f"content/battles/gym_leaders/{leader['slug']}.json").is_file())
            self.assertTrue((ROOT / f"content/source/trainers/gym_leaders/{leader['slug']}.json").is_file())
            self.assertTrue((
                ROOT / "projects/cobbleventure-world-bootstrap/src/main/resources/data/easy_npc/preset/encounter"
                / f"{leader['slug']}.npc.snbt"
            ).is_file())


if __name__ == "__main__":
    unittest.main()

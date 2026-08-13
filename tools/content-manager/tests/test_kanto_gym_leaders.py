import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
sys.path.insert(0, str(ROOT / "tools" / "content-manager"))

import content_manager  # noqa: E402
import generate_kanto_gym_leaders as generator  # noqa: E402


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
        self.assertEqual(8, len({leader["trainer_id"] for leader in leaders}))
        self.assertTrue(all(leader["league_entry_id"] for leader in leaders))
        self.assertTrue(all(leader["badge_id"].startswith("cobbleventure:badge/kanto/") for leader in leaders))
        self.assertTrue(all("trainer_card_skin" not in leader for leader in leaders))
        self.assertTrue(all("trainer_card_model" not in leader for leader in leaders))
        npc_appearances = [
            content_manager.load_json(PROJECT_ROOT / f"content/source/trainers/gym_leaders/{leader['trainer_id'].rsplit('/', 1)[-1]}.json")["npc"]["appearance"]
            for leader in leaders
        ]
        self.assertEqual(8, len({appearance["texture"] for appearance in npc_appearances}))
        self.assertTrue(all(appearance["texture"].startswith("rctmod:textures/trainers/single/") for appearance in npc_appearances))
        self.assertTrue(all(appearance["texture"].endswith(".png") for appearance in npc_appearances))
        self.assertTrue(all(appearance["arm_model"] in {"wide", "slim"} for appearance in npc_appearances))
        self.assertEqual({"leader"}, {leader["anchor"] for leader in leaders})
        self.assertTrue(all(isinstance(gym["staff"]["trainers"], list) for gym in catalog["gyms"]))

    def test_leaders_use_declared_reference_entries(self) -> None:
        references = content_manager.load_json(
            PROJECT_ROOT / "content/catalogs/trainer-reference-entries.json"
        )["entries"]
        reference_ids = {entry["id"] for entry in references}
        for leader in generator.LEADERS:
            self.assertIn(leader["reference"], reference_ids)
            self.assertTrue((PROJECT_ROOT / f"content/battles/gym_leaders/{leader['slug']}.json").is_file())
            self.assertTrue((PROJECT_ROOT / f"content/source/trainers/gym_leaders/{leader['slug']}.json").is_file())
            self.assertTrue((
                ROOT / "projects/cobbleventure-world-bootstrap/src/main/resources/data/easy_npc/preset/encounter"
                / f"{leader['slug']}.npc.snbt"
            ).is_file())


if __name__ == "__main__":
    unittest.main()

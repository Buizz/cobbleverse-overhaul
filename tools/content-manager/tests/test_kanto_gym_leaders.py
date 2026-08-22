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
    def test_trainer_card_appearances_use_rct_0181_texture_ids(self) -> None:
        expected = {
            "brock": "leader_brock_019e",
            "misty": "leader_misty_019f",
            "lt_surge": "leader_lt_surge_01a0",
            "erika": "leader_erika_01a1",
            "koga": "leader_koga_01a2",
            "sabrina": "leader_sabrina_01a4",
            "blaine": "leader_blaine_01a3",
            "giovanni_gym": "leader_giovanni_015e",
        }
        league = content_manager.load_json(PROJECT_ROOT / "content/catalogs/league-progression.json")
        leaders = [entry for entry in league["entries"] if entry["role"] == "gym_leader"]
        actual = {
            entry["encounter"]["character"].rsplit("/", 1)[-1]:
                entry["encounter"]["appearance"]["resource"].rsplit("/", 1)[-1]
            for entry in leaders
        }
        self.assertEqual(expected, actual)

        roster = content_manager.load_json(PROJECT_ROOT / "content/catalogs/trainer-roster.json")
        roster_resources = {
            character["id"].rsplit("/", 1)[-1]: character["appearance"]["resource"].rsplit("/", 1)[-1]
            for character in roster["league_characters"]
            if character["id"].rsplit("/", 1)[-1] in expected
        }
        self.assertEqual(expected, roster_resources)

    def test_red_blue_gym_lineups_and_challenge_caps_match_generation_one(self) -> None:
        expected = {
            "brock": (["geodude", "onix"], [12, 14], 14),
            "misty": (["staryu", "starmie"], [18, 21], 21),
            "lt_surge": (["voltorb", "pikachu", "raichu"], [21, 18, 24], 24),
            "erika": (["victreebel", "tangela", "vileplume"], [29, 24, 29], 29),
            "koga": (["koffing", "koffing", "muk", "weezing"], [37, 37, 39, 43], 43),
            "sabrina": (["kadabra", "mrmime", "venomoth", "alakazam"], [38, 37, 38, 43], 43),
            "blaine": (["growlithe", "ponyta", "rapidash", "arcanine"], [42, 40, 42, 47], 47),
            "giovanni_gym": (
                ["rhyhorn", "dugtrio", "nidoqueen", "nidoking", "rhydon"],
                [45, 42, 44, 45, 50],
                50,
            ),
        }
        league = content_manager.load_json(PROJECT_ROOT / "content/catalogs/league-progression.json")
        leaders = [entry for entry in league["entries"] if entry["role"] == "gym_leader"]

        for entry, (slug, (species, levels, level_cap)) in zip(leaders, expected.items(), strict=True):
            battle = content_manager.load_json(
                PROJECT_ROOT / f"content/battles/gym_leaders/{slug}.json"
            )["battle"]
            self.assertEqual(species, [member["species"].split(":", 1)[1] for member in battle["team"]])
            self.assertEqual(levels, [member["level"] for member in battle["team"]])
            self.assertEqual(level_cap, entry["level_cap"])
            self.assertEqual([], battle["bag"])
            self.assertFalse(any(battle["mechanics"].values()))

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

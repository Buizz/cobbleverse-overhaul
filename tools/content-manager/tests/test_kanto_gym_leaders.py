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
    def test_trainer_card_appearances_use_bundled_kanto_texture_ids(self) -> None:
        expected = {
            "brock": "kanto_brock",
            "misty": "kanto_misty",
            "lt_surge": "kanto_ltsurge",
            "erika": "kanto_erika",
            "koga": "kanto_koga",
            "sabrina": "kanto_sabrina",
            "blaine": "kanto_blaine",
            "giovanni_gym": "kanto_giovanni",
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

        texture_root = (
            ROOT / "projects/cobbleventure-world-bootstrap/src/main/resources"
            / "assets/rctmod/textures/trainers/single"
        )
        for slug in expected.values():
            texture = texture_root / f"{slug}.png"
            with self.subTest(texture=slug):
                self.assertTrue(texture.is_file())
                self.assertEqual(b"\x89PNG\r\n\x1a\n", texture.read_bytes()[:8])
                self.assertEqual(
                    texture.read_bytes(),
                    generator.installed_rct_skin(f"rctmod:trainers/single/{slug}"),
                )

    def test_previous_wrong_skin_uuids_resolve_to_correct_kanto_textures(self) -> None:
        aliases = {
            "kanto_brock": "b5891107-35df-5685-8d16-5aedd0b3cc30",
            "kanto_misty": "b7aecf2d-1e9c-584b-b20f-49b4ee243a3d",
            "kanto_ltsurge": "2abfe472-1a95-5580-b3f0-9973b6764c21",
            "kanto_erika": "d1e7710e-509b-58f3-9283-1f42d7c49139",
            "kanto_koga": "655f39f3-b8d0-56c2-affa-eec25fedb8ca",
            "kanto_sabrina": "59c1a438-b69f-5fb7-a19d-40b2c13cf7bd",
            "kanto_blaine": "3be5af91-effa-5abc-ad1c-80cc1e315c0f",
            "kanto_giovanni": "bbe69b2e-c900-59ac-be96-a99b46202bd3",
        }
        texture_root = (
            ROOT / "projects/cobbleventure-world-bootstrap/src/main/resources"
            / "assets/rctmod/textures/trainers/single"
        )
        skin_root = (
            ROOT / "pack/overrides/development-placeholder/config/easy_npc"
            / "skin/humanoid"
        )
        for slug, skin_uuid in aliases.items():
            with self.subTest(texture=slug):
                self.assertEqual(
                    (texture_root / f"{slug}.png").read_bytes(),
                    (skin_root / f"{skin_uuid}.png").read_bytes(),
                )
    def test_fire_red_gym_lineups_moves_items_and_challenge_caps(self) -> None:
        expected = {
            "brock": (["geodude", "onix"], [12, 14], [["tackle", "defensecurl"], ["tackle", "bind", "rocktomb"]], [], 14),
            "misty": (["staryu", "starmie"], [18, 21], [["tackle", "harden", "recover", "waterpulse"], ["swift", "recover", "rapidspin", "waterpulse"]], [("super_potion", 1)], 21),
            "lt_surge": (["voltorb", "pikachu", "raichu"], [21, 18, 24], [["sonicboom", "tackle", "screech", "shockwave"], ["quickattack", "thunderwave", "doubleteam", "shockwave"], ["quickattack", "thunderwave", "doubleteam", "shockwave"]], [("super_potion", 1), ("full_heal", 1)], 24),
            "erika": (["victreebel", "tangela", "vileplume"], [29, 24, 29], [["stunspore", "acid", "poisonpowder", "gigadrain"], ["poisonpowder", "constrict", "ingrain", "gigadrain"], ["sleeppowder", "acid", "stunspore", "gigadrain"]], [("hyper_potion", 1), ("full_heal", 1)], 29),
            "koga": (["koffing", "muk", "koffing", "weezing"], [37, 39, 37, 43], [["selfdestruct", "sludge", "smokescreen", "toxic"], ["minimize", "sludge", "acidarmor", "toxic"], ["selfdestruct", "sludge", "smokescreen", "toxic"], ["tackle", "sludge", "smokescreen", "toxic"]], [("hyper_potion", 2), ("full_heal", 1)], 43),
            "sabrina": (["kadabra", "mrmime", "venomoth", "alakazam"], [38, 37, 38, 43], [["psybeam", "reflect", "futuresight", "calmmind"], ["barrier", "psybeam", "batonpass", "calmmind"], ["psybeam", "gust", "leechlife", "supersonic"], ["psychic", "recover", "futuresight", "calmmind"]], [("hyper_potion", 2), ("full_heal", 1)], 43),
            "blaine": (["growlithe", "ponyta", "rapidash", "arcanine"], [42, 40, 42, 47], [["bite", "roar", "takedown", "fireblast"], ["stomp", "bounce", "firespin", "fireblast"], ["stomp", "bounce", "firespin", "fireblast"], ["bite", "roar", "takedown", "fireblast"]], [("hyper_potion", 2), ("full_heal", 1)], 47),
            "giovanni_gym": (
                ["rhyhorn", "dugtrio", "nidoqueen", "nidoking", "rhyhorn"],
                [45, 42, 44, 45, 50],
                [["takedown", "rockblast", "scaryface", "earthquake"], ["slash", "sandtomb", "mudslap", "earthquake"], ["bodyslam", "doublekick", "poisonsting", "earthquake"], ["thrash", "doublekick", "poisonsting", "earthquake"], ["takedown", "rockblast", "scaryface", "earthquake"]],
                [("hyper_potion", 2), ("full_heal", 1)],
                50,
            ),
        }
        league = content_manager.load_json(PROJECT_ROOT / "content/catalogs/league-progression.json")
        leaders = [entry for entry in league["entries"] if entry["role"] == "gym_leader"]

        for entry, (slug, (species, levels, moves, bag, level_cap)) in zip(leaders, expected.items(), strict=True):
            battle = content_manager.load_json(
                PROJECT_ROOT / f"content/battles/gym_leaders/{slug}.json"
            )["battle"]
            self.assertEqual(species, [member["species"].split(":", 1)[1] for member in battle["team"]])
            self.assertEqual(levels, [member["level"] for member in battle["team"]])
            self.assertEqual(moves, [member["moves"] for member in battle["team"]])
            self.assertEqual(
                bag,
                [(item["item"].split(":", 1)[1], item["quantity"]) for item in battle["bag"]],
            )
            self.assertEqual(level_cap, entry["level_cap"])
            self.assertEqual(level_cap * 100, entry["encounter"]["rewards"]["money"])
            self.assertEqual(sum(quantity for _, quantity in bag), battle["rules"]["max_item_uses"])
            self.assertEqual("advanced", battle["ai"]["difficulty"])
            self.assertFalse(any(battle["mechanics"].values()))

    def test_fire_red_reference_catalog_contains_every_real_trainer_slot(self) -> None:
        catalog = content_manager.load_json(
            PROJECT_ROOT / "content/catalogs/trainer-reference-entries.json"
        )
        entries = [entry for entry in catalog["entries"] if entry["source"] == "firered"]
        self.assertEqual(654, len(entries))
        self.assertEqual(set(range(89, 743)), {entry["entry_number"] for entry in entries})
        self.assertEqual(15, sum(entry["category"] == "미사용 슬롯" for entry in entries))
        by_id = {entry["id"]: entry for entry in entries}
        self.assertEqual(
            ["rhyhorn", "dugtrio", "nidoqueen", "nidoking", "rhyhorn"],
            [member["species"].split(":", 1)[1] for member in by_id["firered_leader_giovanni"]["battle"]["team"]],
        )
        self.assertEqual(
            {"firered_champion_first_squirtle", "firered_champion_first_bulbasaur", "firered_champion_first_charmander"},
            {entry_id for entry_id in by_id if entry_id.startswith("firered_champion_first_")},
        )
        localization = content_manager.load_json(
            ROOT / "projects/cobbleventure-battle-ai/web-lab/public/data/cobblemon-ko-kr.json"
        )
        species = {
            member["species"].split(":", 1)[1]
            for entry in entries
            for member in entry["battle"]["team"]
        }
        moves = {
            move
            for entry in entries
            for member in entry["battle"]["team"]
            for move in member["moves"]
        }
        self.assertFalse(species - set(localization["species"]))
        self.assertFalse(moves - set(localization["moves"]))

    def test_generation_one_trainers_prepare_regional_money_rewards(self) -> None:
        source_root = PROJECT_ROOT / "content/source/generation_1"
        documents = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(source_root.glob("*.json"))
        ]

        self.assertEqual(8, len(documents))
        for document in documents:
            money = document["npc"]["battle_rewards"]["money"]
            with self.subTest(trainer=document["id"]):
                self.assertTrue(money["enabled"])
                self.assertEqual(
                    document["placement_profile"]["expected_level"],
                    money["fallback_region_level"],
                )
                self.assertEqual(20, money["per_level"])
                self.assertEqual(100, money["offset"])
                self.assertTrue(money["held_item_bonus"])
                commands = generator.npc_money_reward_commands(document, "@initiator")
                self.assertEqual(1, len(commands))

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

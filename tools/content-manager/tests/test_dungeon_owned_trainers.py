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
    CONTENT = ROOT / "content-projects" / "cobbleventure-main" / "content"

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

        self.assertIn('option("dungeon", "던전 소유 NPC"', script)
        self.assertIn("function dungeonOwnedTrainerFields", script)
        self.assertIn("function renderDungeonGeneratedPopulation", script)
        self.assertIn("generatedTeamMin", script)
        self.assertIn("generatedTeamMax", script)
        self.assertIn("ownedTrainerClass", script)
        self.assertIn("ownedTrainerCharacter", script)
        self.assertIn("function dungeonTrainerAppearance", script)
        self.assertIn("function dungeonCooperativeBattleField", script)
        self.assertIn('name="cooperativeBattle"', script)
        self.assertIn("cooperative_battle", script)
        self.assertIn("initializeSkinPreviews(root)", script)
        self.assertIn("triggerWarningTrack", script)

    def test_cooperative_battle_requires_exactly_two_trainers(self) -> None:
        source = self.CONTENT / "dungeons" / "generation_1" / "rocket_casino_hideout.json"
        document = json.loads(source.read_text(encoding="utf-8"))
        encounter = document["encounters"][0]
        encounter["cooperative_battle"] = True
        partner = json.loads(json.dumps(encounter["trainers"][0]))
        partner["id"] = "hideout_partner"
        partner["display_name"] = {"ko_kr": "로켓단 지원 간부"}
        encounter["trainers"].append(partner)

        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "dungeon.json"
            target.write_text(json.dumps(document), encoding="utf-8")
            _, issues = content_manager.validate_dungeon_file(target)
            self.assertFalse([issue for issue in issues if issue.level == "error"], issues)

            encounter["trainers"].pop()
            target.write_text(json.dumps(document), encoding="utf-8")
            _, issues = content_manager.validate_dungeon_file(target)

        self.assertTrue(any(
            issue.level == "error" and issue.path.endswith(".trainers")
            for issue in issues
        ), issues)

    def test_generation_one_rocket_dungeons_use_requested_rosters_and_room_capacity(self) -> None:
        expected = {
            "rocket_power_plant": {"fixed": 4, "generated": 0},
            "rocket_pokemon_tower": {"fixed": 1, "generated": 5},
            "rocket_casino_hideout": {"fixed": 1, "generated": 5},
            "rocket_silph_company": {"fixed": 1, "generated": 8},
        }
        original_layout = {
            "rocket_pokemon_tower": {"rooms": 11, "branches": 4, "depth": 2},
            "rocket_casino_hideout": {"rooms": 16, "branches": 7, "depth": 4},
            "rocket_silph_company": {"rooms": 12, "branches": 1, "depth": 1},
        }

        for slug, counts in expected.items():
            dungeon = json.loads((
                self.CONTENT / "dungeons" / "generation_1" / f"{slug}.json"
            ).read_text(encoding="utf-8"))
            actors = [
                trainer
                for encounter in dungeon["encounters"]
                for trainer in encounter.get("trainers", [])
            ]
            self.assertEqual(counts["fixed"], len(actors), slug)
            generated = dungeon.get("generated_trainers")
            self.assertEqual(counts["generated"], generated["count"][1] if generated else 0, slug)
            self.assertGreater(dungeon["difficulty"]["internal_min"], 1, slug)

            for actor in actors:
                battle_slug = actor["battle"].split("/")[-1]
                battle = json.loads((
                    self.CONTENT / "battles" / "generation_1" / f"{battle_slug}.json"
                ).read_text(encoding="utf-8"))
                levels = [pokemon["level"] for pokemon in battle["battle"]["team"]]
                self.assertTrue(all(level > 1 for level in levels), actor["id"])
                self.assertGreaterEqual(min(levels), dungeon["difficulty"]["internal_min"])
                self.assertLessEqual(max(levels), dungeon["difficulty"]["internal_max"])

            if slug in original_layout:
                previous = original_layout[slug]
                layout = dungeon["layout"]
                total_demand = counts["fixed"] + counts["generated"]
                self.assertGreaterEqual(
                    layout["critical_path_rooms"][1], 3, slug
                )
                self.assertGreater(total_demand, 0, slug)
                self.assertLessEqual(layout["branch_count"][1], previous["branches"], slug)
                self.assertLessEqual(layout["branch_depth"][1], previous["depth"], slug)

    def test_pokemon_tower_uses_an_independent_copy_of_the_rocket_piece_skin(self) -> None:
        dungeon = json.loads((
            self.CONTENT / "dungeons" / "generation_1" / "rocket_pokemon_tower.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(
            "cobbleventure:dungeon_pool/pokemon_tower_test",
            dungeon["terrain"]["piece_pool"],
        )

        rocket_definitions = self.CONTENT / "dungeon_pieces" / "rocket"
        tower_definitions = self.CONTENT / "dungeon_pieces" / "pokemon_tower"
        rocket_structures = self.CONTENT / "structures" / "dungeon_pieces" / "rocket"
        tower_structures = self.CONTENT / "structures" / "dungeon_pieces" / "pokemon_tower"
        names = sorted(path.name for path in rocket_definitions.glob("*.json"))
        self.assertEqual(names, sorted(path.name for path in tower_definitions.glob("*.json")))

        for name in names:
            rocket = json.loads((rocket_definitions / name).read_text(encoding="utf-8"))
            tower = json.loads((tower_definitions / name).read_text(encoding="utf-8"))
            shape = Path(name).stem
            self.assertEqual(
                f"cobbleventure:dungeon_piece/pokemon_tower/{shape}",
                tower["piece_id"],
            )
            self.assertEqual(
                f"cobbleventure:dungeon_pieces/pokemon_tower/{shape}",
                tower["structure"],
            )
            self.assertIn("cobbleventure:dungeon_theme/pokemon_tower", tower["tags"])
            self.assertIn("cobbleventure:dungeon_pool/pokemon_tower_test", tower["tags"])
            self.assertEqual(rocket["connectors"], tower["connectors"])
            self.assertEqual(rocket["markers"], tower["markers"])
            self.assertEqual(
                (rocket_structures / f"{shape}.nbt").read_bytes(),
                (tower_structures / f"{shape}.nbt").read_bytes(),
            )


if __name__ == "__main__":
    unittest.main()

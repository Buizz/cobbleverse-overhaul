import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CONTENT = ROOT / "content-projects" / "cobbleventure-main" / "content"
EASY_NPC_PRESETS = (
    ROOT / "projects" / "cobbleventure-world-bootstrap" / "src" / "main"
    / "resources" / "data" / "easy_npc" / "preset" / "encounter"
)
TRAINER_SLUGS = {
    "kanto_sailor_junho": "sailor",
    "kanto_swimmer_taejin": "swimmer_male",
    "kanto_swimmer_nari": "swimmer_female",
    "kanto_biker_gangho": "biker",
    "kanto_psychic_seon": "psychic",
    "kanto_beauty_sora": "beauty",
    "kanto_gentleman_doyoung": "gentleman",
    "kanto_black_belt_hyuk": "black_belt",
}
GENERATION_ONE_SPECIES = {
    "cobblemon:abra",
    "cobblemon:clefairy",
    "cobblemon:drowzee",
    "cobblemon:goldeen",
    "cobblemon:grimer",
    "cobblemon:growlithe",
    "cobblemon:hitmonlee",
    "cobblemon:horsea",
    "cobblemon:koffing",
    "cobblemon:machoke",
    "cobblemon:machop",
    "cobblemon:pikachu",
    "cobblemon:primeape",
    "cobblemon:shellder",
    "cobblemon:staryu",
    "cobblemon:tentacool",
    "cobblemon:weepinbell",
}


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


class GenerationOneTrainerTests(unittest.TestCase):
    def test_active_regional_trainer_assignments_are_globally_unique(self) -> None:
        assigned: dict[str, Path] = {}
        region_paths = [
            *sorted((CONTENT / "routes" / "generation_1").glob("*.json")),
            *sorted((CONTENT / "forests" / "generation_1").glob("*.json")),
            *sorted((CONTENT / "caves" / "generation_1").glob("*.json")),
        ]
        for region_path in region_paths:
            document = load(region_path)
            population = document.get(
                "automatic_npc_placement", document.get("trainer_settings", {})
            )
            if not population.get("enabled", True):
                continue
            self.assertFalse(
                population.get("use_biome_defaults", False),
                f"{region_path.name} can add an untracked duplicate trainer",
            )
            for trainer_id in population.get("direct_trainers", []):
                self.assertNotIn(
                    trainer_id,
                    assigned,
                    f"{trainer_id} is assigned by both {assigned.get(trainer_id)} "
                    f"and {region_path}",
                )
                assigned[trainer_id] = region_path

        self.assertEqual(78, len(assigned))

    def test_viridian_forest_approaches_do_not_duplicate_forest_trainers(self) -> None:
        forest = load(CONTENT / "forests" / "generation_1" / "viridian_forest.json")
        forest_trainers = set(forest["trainer_settings"]["direct_trainers"])
        self.assertTrue(forest_trainers)
        for route_id in ("route_custom_04", "route_viridian_forest_north"):
            with self.subTest(route=route_id):
                route = load(CONTENT / "routes" / "generation_1" / f"{route_id}.json")
                population = route["automatic_npc_placement"]
                self.assertFalse(population["enabled"])
                self.assertEqual(0, population["count"])
                self.assertEqual(set(), forest_trainers & set(population["direct_trainers"]))

    def test_kanto_trainers_use_matching_classes_and_generation_one_teams(self) -> None:
        for slug, trainer_class in TRAINER_SLUGS.items():
            with self.subTest(slug=slug):
                source = load(CONTENT / "source" / "generation_1" / f"{slug}.json")
                battle = load(CONTENT / "battles" / "generation_1" / f"{slug}.json")

                self.assertEqual(f"cobbleventure:npc/{slug}", source["id"])
                self.assertEqual(
                    f"cobbleventure:trainer_class/{trainer_class}",
                    source["npc"]["trainer_class"],
                )
                self.assertIn("generation_1", source["tags"])
                self.assertEqual(
                    f"cobbleventure:battle/{slug}",
                    source["event_design"]["preset"]["battle"],
                )

                team = battle["battle"]["team"]
                self.assertGreaterEqual(len(team), 2)
                self.assertLessEqual(len(team), 3)
                self.assertTrue(
                    {pokemon["species"] for pokemon in team}
                    <= GENERATION_ONE_SPECIES
                )
                self.assertFalse(any(battle["battle"]["mechanics"].values()))

    def test_every_generation_one_route_has_a_bounded_trainer_pool(self) -> None:
        route_paths = sorted((CONTENT / "routes" / "generation_1").glob("*.json"))
        self.assertEqual(23, len(route_paths))
        expanded_test_routes = {
            "route_custom_05": 12,
            "route_custom_19": 10,
        }
        for route_path in route_paths:
            with self.subTest(route=route_path.stem):
                route = load(route_path)
                population = route["automatic_npc_placement"]
                trainers = population.get("direct_trainers", [])

                if not population["enabled"]:
                    self.assertEqual([], trainers)
                    continue
                self.assertTrue(population["enabled"])
                self.assertEqual("proximity", population["trigger_override"])
                self.assertGreaterEqual(population["count"], 2)
                if route_path.stem in expanded_test_routes:
                    self.assertEqual(expanded_test_routes[route_path.stem], population["count"])
                else:
                    self.assertLessEqual(population["count"], 4)
                self.assertEqual(population["count"], len(trainers))
                self.assertEqual(len(trainers), len(set(trainers)))
                self.assertTrue(
                    all(trainer.startswith("cobbleventure:npc/") for trainer in trainers)
                )

    def test_firered_test_trainers_are_materialized_and_gym_slots_are_filled(self) -> None:
        trainers = sorted((CONTENT / "source" / "generation_1" / "firered").glob("*.json"))
        battles = sorted((CONTENT / "battles" / "generation_1" / "firered").glob("*.json"))
        events = sorted((CONTENT / "events" / "cobbleventure" / "generation_1" / "firered").glob("*.cves"))
        bindings = sorted((CONTENT / "event-bindings" / "cobbleventure" / "generation_1" / "firered").glob("*.json"))
        self.assertEqual(71, len(trainers))
        self.assertEqual(70, len(battles))
        self.assertEqual(70, len(events))
        self.assertEqual(71, len(bindings))

        first_texts = set()
        for trainer_path in trainers:
            trainer = load(trainer_path)
            korean_name = trainer["npc"]["display_name"]["ko_kr"]
            english_name = trainer["npc"]["display_name"]["en_us"]
            preset = trainer["event_design"]["preset"]
            first_texts.add(preset["first_text"]["ko_kr"])

            self.assertNotEqual(english_name, korean_name)
            self.assertNotEqual("승부하자!", preset["first_text"]["ko_kr"])
            self.assertNotEqual("좋은 승부였어!", preset["win_text"]["ko_kr"])
            self.assertIn("/firered/", trainer["event_runtime"]["script_id"])
            slug = trainer["id"].rsplit("/", 1)[-1]
            self.assertTrue((EASY_NPC_PRESETS / f"{slug}__v5.npc.snbt").is_file())
            self.assertTrue((EASY_NPC_PRESETS / f"{slug}__v5_proximity.npc.snbt").is_file())
        self.assertGreaterEqual(len(first_texts), 65)

        catalog = load(CONTENT / "catalogs" / "gyms.json")
        expected_counts = [1, 2, 3, 7, 6, 7, 7, 8]
        self.assertEqual(expected_counts, [len(gym["staff"]["trainers"]) for gym in catalog["gyms"]])
        for gym in catalog["gyms"]:
            for index, trainer in enumerate(gym["staff"]["trainers"], start=1):
                self.assertEqual(f"trainer_{index}", trainer["anchor"])


if __name__ == "__main__":
    unittest.main()

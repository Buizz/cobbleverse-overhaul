import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
CONTENT_MANAGER = ROOT / "tools" / "content-manager"
sys.path.insert(0, str(CONTENT_MANAGER))

from content_manager import validate_loot_tables  # noqa: E402
from loot_table_validation import validate_loot_table_document  # noqa: E402


class LootTableDocumentTests(unittest.TestCase):
    def test_accepts_nested_entries_functions_and_conditions(self) -> None:
        document = {
            "type": "minecraft:gift",
            "pools": [{
                "rolls": {"type": "minecraft:uniform", "min": 1, "max": 2},
                "entries": [{
                    "type": "minecraft:alternatives",
                    "children": [
                        {
                            "type": "minecraft:item",
                            "name": "cobblemon:poke_ball",
                            "functions": [{
                                "function": "minecraft:set_count",
                                "count": 3,
                            }],
                        },
                        {"type": "minecraft:empty"},
                        {
                            "type": "example:custom_entry",
                            "functions": [{"function": "example:custom_function"}],
                        },
                    ],
                    "conditions": [{
                        "condition": "minecraft:any_of",
                        "terms": [{"condition": "minecraft:random_chance", "chance": 0.5}],
                    }],
                }],
            }],
        }

        self.assertEqual((), validate_loot_table_document(
            document, {"cobblemon:poke_ball"}
        ))

    def test_reports_structural_and_unknown_item_errors_with_paths(self) -> None:
        document = {
            "type": "not a resource",
            "pools": [{
                "rolls": True,
                "entries": [{
                    "type": "minecraft:item",
                    "name": "cobblemon:missing",
                    "weight": -1,
                    "functions": [{
                        "function": "minecraft:set_count",
                        "count": -2,
                        "add": "yes",
                    }],
                }],
            }],
        }

        rendered = "\n".join(
            f"{problem.path}: {problem.message}"
            for problem in validate_loot_table_document(document, set())
        )
        self.assertIn("$.type", rendered)
        self.assertIn("$.pools[0].rolls", rendered)
        self.assertIn("$.pools[0].entries[0].weight", rendered)
        self.assertIn("아이템 카탈로그에 없는", rendered)
        self.assertIn("set_count.add", rendered)

    def test_repository_validation_uses_dependency_item_catalog(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "content/loot_tables/test/reward.json"
            source.parent.mkdir(parents=True)
            source.write_text(json.dumps({
                "pools": [{
                    "rolls": 1,
                    "entries": [{
                        "type": "minecraft:item",
                        "name": "test:missing",
                    }],
                }],
            }), encoding="utf-8")
            catalog = root / "items.json"
            catalog.write_text(json.dumps({
                "items": [{"id": "test:known"}],
            }), encoding="utf-8")

            issues = validate_loot_tables(root, catalog)

        self.assertEqual(1, len(issues))
        self.assertEqual("$.pools[0].entries[0].name", issues[0].path)
        self.assertIn("test:missing", issues[0].message)

    def test_repository_validation_reports_duplicate_json_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "content/loot_tables/test/reward.json"
            source.parent.mkdir(parents=True)
            source.write_text(
                '{"pools": [], "pools": [{"rolls": 1, "entries": []}]}',
                encoding="utf-8",
            )

            issues = validate_loot_tables(root)

        self.assertEqual(1, len(issues))
        self.assertIn("중복 JSON 키", issues[0].message)


if __name__ == "__main__":
    unittest.main()

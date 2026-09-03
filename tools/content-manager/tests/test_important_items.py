import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT / "content-projects/cobbleventure-main/content"


class ImportantItemCatalogTests(unittest.TestCase):
    def test_important_items_have_declared_acquisition_flags(self) -> None:
        catalog = json.loads(
            (PROJECT / "catalogs/important-items.json").read_text(encoding="utf-8")
        )
        definitions = json.loads(
            (PROJECT / "catalogs/game-definitions.json").read_text(encoding="utf-8")
        )
        flags = {value["id"] for value in definitions["variables"]}

        self.assertEqual(
            {
                "cobblemon:pokedex_red",
                "cobblenav:pokenav_item_red",
                "cobbleventure_casino:coin_case",
                "cobbleventure_bootstrap:poke_flute",
            },
            {value["item"] for value in catalog["items"]},
        )
        self.assertTrue(all(value["minimum_count"] == 1 for value in catalog["items"]))
        self.assertTrue(all(value["acquisition_flag"] in flags for value in catalog["items"]))


if __name__ == "__main__":
    unittest.main()

import importlib.util
import json
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path


ROOT = Path(__file__).parents[3]
MODULE_PATH = ROOT / "tools" / "content-manager" / "content_manager.py"
SPEC = importlib.util.spec_from_file_location("content_manager_economy", MODULE_PATH)
content_manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = content_manager
SPEC.loader.exec_module(content_manager)


class EconomyCatalogTests(unittest.TestCase):
    def validate(self, payload, known_drop_items=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "economy.json"
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            return content_manager.validate_economy_catalog_file(path, known_drop_items)

    def valid_catalog(self):
        return {
            "schema_version": 2,
            "vanilla_crafting_disabled": True,
            "shop_catalogs": [{
                "id": "cobbleventure:shop_catalog/tm_store",
                "display_name": "기술머신 백화점",
                "facility_scope": "department_store",
                "vendor_units": ["cobbleventure:vendor/tm_clerk"],
            }],
            "vendor_units": [{
                "id": "cobbleventure:vendor/tm_clerk",
                "facility_scope": "department_store",
                "role": "기술머신 판매원",
                "display_name": "기술머신 전문가",
                "npc_template": "cobbleventure:vendor/tm_clerk",
                "categories": [{
                    "name": "기술머신",
                    "offers": [{"item": "cobblemon:poke_ball", "count": 1, "price": "3000"}],
                }],
            }],
            "pokemon_drop_overrides": [{
                "species": "cobblemon:geodude",
                "amount": 3,
                "entries": [{"item": "cobbleventure:hard_stone_shard", "percentage": 70.0, "quantityRange": "1-3"}],
            }],
            "npc_recipes": [{
                "id": "cobbleventure:recipe/great_ball",
                "npc": "cobbleventure:npc/ball_crafter",
                "display_name": "슈퍼볼 가공",
                "output": {"item": "cobblemon:great_ball", "count": 3},
                "ingredients": [{"item": "cobbleventure:hard_stone_shard", "count": 2}],
                "unlock_note": "회색체육관 클리어",
            }],
        }

    def test_vendor_unit_drop_override_and_npc_recipe_are_valid(self):
        self.assertEqual([], self.validate(self.valid_catalog()))

    def test_catalog_has_no_town_binding(self):
        payload = self.valid_catalog()
        self.assertNotIn("town", payload["vendor_units"][0])
        self.assertEqual("기술머신 판매원", payload["vendor_units"][0]["role"])

    def test_vanilla_crafting_must_stay_disabled(self):
        payload = self.valid_catalog(); payload["vanilla_crafting_disabled"] = False
        self.assertTrue(any(issue.path == "$.vanilla_crafting_disabled" for issue in self.validate(payload)))

    def test_recipe_can_use_original_cobblemon_drop_item(self):
        payload = self.valid_catalog()
        payload["npc_recipes"][0]["ingredients"][0]["item"] = "cobblemon:oran_berry"
        payload["pokemon_drop_overrides"] = []
        self.assertEqual([], self.validate(payload, {"cobblemon:oran_berry"}))

    def test_real_bca_vendor_units_are_loaded(self):
        vendors = content_manager._economy_vendor_units_from_bca(ROOT)
        by_id = {vendor["id"]: vendor for vendor in vendors}
        self.assertIn("bca:shopkeeper_ds_special_balls", by_id)
        self.assertEqual("특수 볼 판매원", by_id["bca:shopkeeper_ds_special_balls"]["role"])
        self.assertTrue(by_id["bca:shopkeeper_ds_special_balls"]["categories"])

    def test_api_returns_resolved_catalog_and_saves_only_overrides(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); target = root / "content" / "catalogs" / "economy.json"; target.parent.mkdir(parents=True)
            payload = self.valid_catalog(); target.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            server = content_manager.ThreadingHTTPServer(("127.0.0.1", 0), content_manager.create_handler(root))
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            base_url = f"http://127.0.0.1:{server.server_address[1]}"
            try:
                with urllib.request.urlopen(f"{base_url}/api/economy") as response: loaded = json.load(response)
                self.assertIn("resolved_vendor_units", loaded)
                self.assertIn("resolved_shop_catalogs", loaded)
                request = urllib.request.Request(f"{base_url}/api/economy", data=json.dumps(loaded, ensure_ascii=False).encode(), headers={"Content-Type": "application/json"}, method="PUT")
                with urllib.request.urlopen(request) as response: self.assertTrue(json.load(response)["saved"])
                saved = json.loads(target.read_text(encoding="utf-8"))
                self.assertNotIn("resolved_vendor_units", saved)
            finally:
                server.shutdown(); server.server_close(); thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()

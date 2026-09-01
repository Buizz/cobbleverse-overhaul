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
            "sell_price_policy": {
                "apply_default_to_all": True,
                "default_percentage": 50,
            },
            "standard_prices": [],
            "shop_catalogs": [{
                "id": "cobbleventure:shop_catalog/tm_store",
                "display_name": "기술머신 백화점",
                "facility_scope": "department_store",
                "vendor_units": ["cobbleventure:vendor/tm_clerk"],
                "assignments": [{"slot_id": "1f_left_a", "display_name": "1층 왼쪽 A", "vendor_unit": "cobbleventure:vendor/tm_clerk"}],
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
            "pokemon_drop_rules": [],
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

    def test_shop_names_support_korean_and_english(self):
        payload = self.valid_catalog()
        payload["shop_catalogs"][0]["display_name"] = {"ko_kr": "기술머신 백화점", "en_us": "TM Department Store"}
        payload["shop_catalogs"][0]["assignments"][0]["display_name"] = {"ko_kr": "1층 왼쪽 A", "en_us": "1F Left A"}
        vendor = payload["vendor_units"][0]
        vendor["role"] = {"ko_kr": "기술머신 판매원", "en_us": "TM Clerk"}
        vendor["display_name"] = {"ko_kr": "기술머신 전문가", "en_us": "TM Specialist"}
        vendor["categories"][0]["name"] = {"ko_kr": "기술머신", "en_us": "Technical Machines"}
        self.assertEqual([], self.validate(payload))

    def test_department_store_assignment_without_nbt_anchor_is_a_warning(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalogs = root / "content" / "catalogs"
            interiors = root / "content" / "structures" / "interiors"
            catalogs.mkdir(parents=True)
            interiors.mkdir(parents=True)
            economy = self.valid_catalog()
            economy["shop_catalogs"][0]["assignments"] = [{
                "slot_id": "rooftop_sale",
                "display_name": "옥상 판매대",
                "vendor_unit": "cobbleventure:vendor/tm_clerk",
            }]
            (catalogs / "economy.json").write_text(
                json.dumps(economy, ensure_ascii=False), encoding="utf-8"
            )
            (catalogs / "building-settings.json").write_text(json.dumps({
                "facility_defaults": {
                    "department_store": "cobbleventure:facilities/department_store"
                },
                "buildings": {
                    "cobbleventure:facilities/department_store": {
                        "interiors": [{
                            "key": "room_1",
                            "structure": "cobbleventure:interiors/department_store",
                        }]
                    }
                },
            }), encoding="utf-8")
            (interiors / "department_store.structure.json").write_text(json.dumps({
                "anchors": [{
                    "type": "npc_position", "label": "5f_left", "position": [1, 2, 3]
                }]
            }), encoding="utf-8")

            issues = content_manager.validate_department_store_assignment_slots(root)

            self.assertEqual(1, len(issues))
            self.assertEqual("warning", issues[0].level)
            self.assertIn("rooftop_sale", issues[0].message)

    def test_standard_item_prices_are_validated(self):
        payload = self.valid_catalog()
        payload["standard_prices"] = [{
            "item": "cobblemon:poke_ball",
            "buy_price": "200",
            "sell_price": "0",
            "use_default_sell_price": True,
            "no_sell_penalty": False,
        }]
        self.assertEqual([], self.validate(payload))
        payload["standard_prices"].append({
            "item": "cobblemon:poke_ball",
            "buy_price": "300",
            "sell_price": "150",
            "use_default_sell_price": False,
            "no_sell_penalty": False,
        })
        self.assertTrue(any("중복 표준 가격" in issue.message for issue in self.validate(payload)))

    def test_standard_item_price_rejects_negative_values(self):
        payload = self.valid_catalog()
        payload["standard_prices"] = [{
            "item": "cobblemon:poke_ball",
            "buy_price": "-1",
            "sell_price": "100",
            "use_default_sell_price": False,
            "no_sell_penalty": False,
        }]
        self.assertTrue(any("0 이상의 정수 구매가" in issue.message for issue in self.validate(payload)))

    def test_default_sell_percentage_must_be_between_zero_and_one_hundred(self):
        payload = self.valid_catalog()
        payload["sell_price_policy"]["default_percentage"] = 101
        self.assertTrue(any(issue.path == "$.sell_price_policy.default_percentage" for issue in self.validate(payload)))

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
        self.assertEqual("특수 볼 판매원", by_id["bca:shopkeeper_ds_special_balls"]["role"]["ko_kr"])
        self.assertEqual("Pokeball Specialist", by_id["bca:shopkeeper_ds_special_balls"]["role"]["en_us"])
        self.assertTrue(by_id["bca:shopkeeper_ds_special_balls"]["categories"])

    def test_project_workspace_loads_shared_bca_vendor_units(self):
        project_root = ROOT / "content-projects" / "cobbleventure-main"
        workspace = content_manager.load_economy_workspace(project_root, ROOT)
        by_id = {vendor["id"]: vendor for vendor in workspace["resolved_vendor_units"]}
        self.assertGreaterEqual(len(by_id), 16)
        self.assertIn("bca:shopkeeper_ds_special_balls", by_id)
        self.assertIn("bca:pokemart_shopkeeper", by_id)
        self.assertEqual("custom", by_id["bca:pokemart_shopkeeper"]["origin"])

    def test_project_workspace_can_search_unsold_player_menu_items(self):
        project_root = ROOT / "content-projects" / "cobbleventure-main"
        workspace = content_manager.load_economy_workspace(project_root, ROOT)
        items = {entry["id"]: entry for entry in workspace["editor_catalog"]["items"]}

        self.assertEqual("상한떡", items["cobbleventure_player_menu:stale_rice_cake"]["ko_kr"])
        self.assertEqual("신묘한 사탕", items["cobbleventure_player_menu:mystical_candy"]["ko_kr"])
        self.assertEqual("food", items["cobbleventure_player_menu:stale_rice_cake"]["product_group"])
        self.assertEqual("food", items["cobbleventure_player_menu:mystical_candy"]["product_group"])
        self.assertNotIn(
            "cobbleventure_player_menu:stale_rice_cake",
            {entry["item"] for entry in workspace["resolved_standard_prices"]},
        )
        self.assertNotIn(
            "cobbleventure_player_menu:mystical_candy",
            {entry["item"] for entry in workspace["resolved_standard_prices"]},
        )

    def test_shop_products_use_semantic_groups_and_include_external_offers(self):
        project_root = ROOT / "content-projects" / "cobbleventure-main"
        workspace = content_manager.load_economy_workspace(project_root, ROOT)
        items = {entry["id"]: entry for entry in workspace["editor_catalog"]["items"]}

        expected_groups = {
            "cobblemon:rare_candy": "medicine",
            "cobblemon:white_herb": "held",
            "cobblemon:black_apricorn_seed": "materials",
            "cobblemon:growth_mulch": "materials",
            "minecraft:apple": "food",
            "minecraft:emerald": "currency",
            "tmcraft:tm_fireblast": "machines",
            "handcrafted:oak_chair": "decor",
            "pokeblocks:pokedoll_treecko": "decor",
            "cobblenav:pokenav_item_red": "technology",
        }
        for item_id, expected_group in expected_groups.items():
            self.assertIn(item_id, items)
            self.assertEqual(expected_group, items[item_id]["product_group"])

    def test_type_rule_matches_many_species_without_individual_editing(self):
        rule = {"match": {"types": ["electric"], "generations": [1]}}
        pikachu = {"species": "cobblemon:pikachu", "types": ["electric"], "generation": 1}
        charmander = {"species": "cobblemon:charmander", "types": ["fire"], "generation": 1}
        self.assertTrue(content_manager._economy_rule_matches(rule, pikachu))
        self.assertFalse(content_manager._economy_rule_matches(rule, charmander))

    def test_editor_catalog_contains_korean_names(self):
        species = content_manager._economy_pokemon_drops_from_cobblemon(ROOT)
        editor = content_manager._economy_editor_catalog(ROOT, species)
        self.assertEqual(1025, len(editor["species"]))
        pikachu = next(entry for entry in editor["species"] if entry["species"] == "cobblemon:pikachu")
        poke_ball = next(entry for entry in editor["items"] if entry["id"] == "cobblemon:poke_ball")
        item_ids = {entry["id"] for entry in editor["items"]}
        self.assertEqual("피카츄", pikachu["ko_kr"])
        self.assertEqual("몬스터볼", poke_ball["ko_kr"])
        self.assertIn("cobblemon:normal_gem", item_ids)
        self.assertNotIn("cobblemon.normal_gem:tooltip_1", item_ids)
        self.assertEqual("balls", poke_ball["product_group"])
        self.assertEqual("gems", next(entry for entry in editor["items"] if entry["id"] == "cobblemon:normal_gem")["product_group"])
        self.assertEqual("medicine", next(entry for entry in editor["items"] if entry["id"] == "cobblemon:ether")["product_group"])
        self.assertEqual("other", next(entry for entry in editor["items"] if entry["id"] == "minecraft:netherite_pickaxe")["product_group"])

    def test_drop_rule_generates_cobblemon_species_override(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            species_root = root / ".tmp/cobblemon-1.7.3-source/common/src/main/resources/data/cobblemon/species"
            pikachu = species_root / "generation1/pikachu.json"
            pikachu.parent.mkdir(parents=True)
            pikachu.write_text(json.dumps({
                "name": "Pikachu", "nationalPokedexNumber": 25,
                "primaryType": "electric", "labels": ["gen1"], "eggGroups": ["field"],
                "height": 4, "drops": {"amount": 1, "entries": []},
            }), encoding="utf-8")
            content_manager._economy_pokemon_drops_from_cobblemon.cache_clear()
            issues = content_manager._write_economy_species_overrides(root, {
                "pokemon_drop_rules": [{
                    "id": "cobbleventure:drop_rule/electric", "enabled": True,
                    "priority": 0, "match": {"types": ["electric"]}, "mode": "append",
                    "amount": 2, "entries": [{"item": "cobblemon:thunder_stone", "percentage": 10.0}],
                }],
                "pokemon_drop_overrides": [],
            })
            self.assertFalse(any(issue.level == "error" for issue in issues))
            output = root / "projects/cobbleventure-world-bootstrap/src/generated/resources/data/cobblemon/species/generation1/pikachu.json"
            generated = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("cobblemon:thunder_stone", generated["drops"]["entries"][0]["item"])

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

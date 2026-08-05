from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).parents[1] / "content_manager.py"
SPEC = importlib.util.spec_from_file_location("content_manager", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
content_manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = content_manager
SPEC.loader.exec_module(content_manager)


class ContentManagerTests(unittest.TestCase):
    def test_example_content_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        content_id, issues = content_manager.validate_content_file(
            root / "content" / "source" / "examples" / "ai_test.json"
        )
        self.assertEqual("cobbleventure:trainer/ai_test", content_id)
        self.assertEqual([], issues)

    def test_starter_town_leader_content_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        content_id, issues = content_manager.validate_content_file(
            root / "content" / "source" / "examples" / "starter_town_leader.json"
        )
        self.assertEqual("cobbleventure:trainer/starter_town_leader", content_id)
        self.assertEqual([], issues)

    def test_trainer_owned_placement_is_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["placement"] = {}
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("마을의 npc_placement" in issue.message for issue in issues))

    def test_missing_dialogue_target_is_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["dialogue"]["entry"] = "cobbleventure:dialogue/missing"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(source), encoding="utf-8")
            _, issues = content_manager.validate_content_file(path)
        self.assertTrue(any("존재하지 않는 대화 ID" in issue.message for issue in issues))

    def test_invalid_ev_total_is_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["team"][0]["evs"] = {
            "hp": 252,
            "attack": 252,
            "speed": 252,
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text(json.dumps(source), encoding="utf-8")
            _, issues = content_manager.validate_content_file(path)
        self.assertTrue(any("EV 합계" in issue.message for issue in issues))

    def test_duplicate_pokemon_aspects_are_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["team"][0]["aspects"] = ["alolan", "alolan"]
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("aspects는 중복" in issue.message for issue in issues))

    def test_pokemon_cannot_hold_regular_and_gimmick_items_together(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        pokemon = source["battle"]["team"][0]
        pokemon["held_item"] = "cobblemon:choice_band"
        pokemon["gimmick"] = {
            "type": "mega_evolution",
            "item": "mega_showdown:charizardite_x",
        }
        source["battle"]["mechanics"]["mega_evolution"] = True
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("동시에 지정" in issue.message for issue in issues))

    def test_pokemon_gimmick_requires_matching_battle_mechanic(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["team"][0]["gimmick"] = {
            "type": "z_move",
            "item": "mega_showdown:normalium_z",
        }
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("같은 전투 기믹" in issue.message for issue in issues))

    def test_invalid_battle_bag_limits_are_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["rules"]["max_item_uses"] = -1
        source["battle"]["bag"] = [
            {"item": "cobblemon:potion", "quantity": 0}
        ]
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("max_item_uses" in issue.path for issue in issues))
        self.assertTrue(any("bag[0].quantity" in issue.path for issue in issues))

    def test_invalid_tera_type_is_rejected(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (root / "content" / "source" / "examples" / "ai_test.json").read_text(
                encoding="utf-8"
            )
        )
        source["battle"]["team"][0]["tera_type"] = "not_a_type"
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_content_file
        )
        self.assertTrue(any("지원하는 포켓몬 타입" in issue.message for issue in issues))

    def test_starter_town_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        settlement_id, issues = content_manager.validate_settlement_file(
            root / "content" / "settlements" / "generation_1" / "starter_town.json"
        )
        self.assertEqual("cobbleventure:settlement/starter_town", settlement_id)
        self.assertEqual([], issues)

    def test_settlement_trainer_slot_requires_trainer_and_spawn_policy(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (
                root
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        slot = source["npc_placement"]["trainer_slots"][0]
        slot.pop("trainer_id")
        slot["spawn_policy"] = "unknown"
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )
        self.assertTrue(any("trainer_id" in issue.path for issue in issues))
        self.assertTrue(any("생성 정책" in issue.message for issue in issues))

    def test_trainer_class_catalog_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        issues = content_manager.validate_trainer_class_catalog(
            root / "content" / "catalogs" / "trainer-classes.json"
        )
        self.assertEqual([], issues)

    def test_settlement_center_must_be_inside_bounds(self) -> None:
        root = Path(__file__).parents[3]
        source = json.loads(
            (
                root
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["center"]["x"] = 9999
        _, issues = content_manager._validate_payload(
            source, content_manager.validate_settlement_file
        )
        self.assertTrue(any("마을 경계 안" in issue.message for issue in issues))

    def test_managed_path_rejects_directory_escape(self) -> None:
        root = Path(__file__).parents[3]
        with self.assertRaises(ValueError):
            content_manager._managed_path(root, "trainers", "../outside.json")

    def test_settlement_save_is_validated_before_overwrite(self) -> None:
        repository = Path(__file__).parents[3]
        source = json.loads(
            (
                repository
                / "content"
                / "settlements"
                / "generation_1"
                / "starter_town.json"
            ).read_text(encoding="utf-8")
        )
        source["id"] = "cobbleventure:settlement/save_test"
        relative_path = "content/settlements/tests/save_test.json"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target, issues = content_manager._save_document(
                root, "settlements", relative_path, source
            )
            self.assertEqual([], issues)
            self.assertIsNotNone(target)
            self.assertEqual(source, content_manager.load_json(target))

            invalid = json.loads(json.dumps(source))
            invalid["center"]["x"] = 9999
            _, issues = content_manager._save_document(
                root, "settlements", relative_path, invalid
            )
            self.assertTrue(any("마을 경계 안" in issue.message for issue in issues))
            self.assertEqual(source, content_manager.load_json(target))

    def test_new_trainer_template_is_valid(self) -> None:
        template = content_manager._trainer_template("route_01", "길목 트레이너")
        content_id, issues = content_manager._validate_payload(
            template, content_manager.validate_content_file
        )
        self.assertEqual("cobbleventure:trainer/route_01", content_id)
        self.assertEqual([], issues)
        self.assertNotIn("placement", template)

    def test_create_document_writes_valid_template_and_rejects_duplicate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trainer_path, trainer_issues = content_manager._create_document(
                root, "trainers", "route_01", "길목 트레이너"
            )
            settlement_path, settlement_issues = content_manager._create_document(
                root, "settlements", "forest_town", "숲 마을", "generation_1"
            )
            self.assertEqual([], trainer_issues)
            self.assertEqual([], settlement_issues)
            self.assertTrue(trainer_path.is_file())
            self.assertTrue(settlement_path.is_file())

            _, duplicate_issues = content_manager._create_document(
                root, "trainers", "route_01", "중복 트레이너"
            )
            self.assertTrue(any("이미 존재" in issue.message for issue in duplicate_issues))

    def test_strict_pack_rejects_draft_lock(self) -> None:
        root = Path(__file__).parents[3]
        issues = content_manager.validate_dependency_lock(
            root / "pack" / "dependencies.lock.json", strict_pack=True
        )
        self.assertTrue(any("Minecraft 버전" in issue.message for issue in issues))

    def test_local_api_health_and_validation(self) -> None:
        root = Path(__file__).parents[3]
        server = content_manager.ThreadingHTTPServer(
            ("127.0.0.1", 0), content_manager.create_handler(root)
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base_url = f"http://127.0.0.1:{server.server_port}"
            with urllib.request.urlopen(f"{base_url}/health") as response:
                health = json.load(response)
            with urllib.request.urlopen(f"{base_url}/validate") as response:
                validation = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/dashboard") as response:
                dashboard = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/settlements") as response:
                settlements = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/trainer-classes") as response:
                trainer_classes = json.load(response)
            with urllib.request.urlopen(f"{base_url}/api/editor-catalog") as response:
                editor_catalog = json.load(response)
            with urllib.request.urlopen(base_url) as response:
                page = response.read().decode("utf-8")
            with urllib.request.urlopen(f"{base_url}/app.js") as response:
                app_script = response.read().decode("utf-8")
            with urllib.request.urlopen(f"{base_url}/styles.css") as response:
                styles = response.read().decode("utf-8")
            with urllib.request.urlopen(
                f"{base_url}/pokemon-entry-clipboard.mjs"
            ) as response:
                clipboard_module = response.read().decode("utf-8")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        self.assertEqual("ok", health["status"])
        self.assertTrue(validation["valid"])
        self.assertGreaterEqual(dashboard["trainers"], 2)
        self.assertEqual(1, dashboard["settlements"])
        self.assertEqual(1, len(settlements["items"]))
        self.assertGreaterEqual(len(trainer_classes["classes"]), 10)
        self.assertGreaterEqual(len(editor_catalog["species"]), 1000)
        self.assertGreaterEqual(len(editor_catalog["moves"]), 900)
        self.assertTrue(any(entry.get("forme") for entry in editor_catalog["species"]))
        self.assertTrue(
            any(entry["id"] == "cobblemon:potion" for entry in editor_catalog["bagItems"])
        )
        self.assertIn("Cobbleventure Content Studio", page)
        self.assertIn("엔트리 JSON 복사", page)
        self.assertIn("전투 가방", page)
        self.assertIn("PokeAPI/sprites/master/sprites/pokemon", app_script)
        self.assertIn("other/official-artwork", app_script)
        self.assertNotIn("pokeapi.co/api/v2/pokemon", app_script)
        self.assertIn("move-type-badge", app_script)
        self.assertIn('loading="lazy"', app_script)
        self.assertIn("const resultCards = rows.map", app_script)
        self.assertIn("hydrateChoicePokemonArt(rows)", app_script)
        self.assertNotIn("처음 120개 표시", app_script)
        self.assertIn("normalizePokemonStats", app_script)
        self.assertIn("remainingEvs = 510", app_script)
        self.assertIn("moveSelectedPokemon", app_script)
        self.assertIn("move-pokemon-left", app_script)
        self.assertIn(".party-order-toolbar", styles)
        self.assertIn(".move-type-badge.type-fire", styles)
        self.assertIn(".move-type-badge.type-fairy", styles)
        self.assertIn(".choice-tags b.move-type-badge", styles)
        self.assertIn(".bag-settings > label input, .bag-item-row input", styles)
        self.assertIn("color-scheme: light", styles)
        self.assertIn("POKEMON_ENTRY_CLIPBOARD_SCHEMA", clipboard_module)

    def test_build_api_uses_allowlisted_runner(self) -> None:
        root = Path(__file__).parents[3]
        server = content_manager.ThreadingHTTPServer(
            ("127.0.0.1", 0), content_manager.create_handler(root)
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        result = {
            "command": "validate",
            "description": "검사",
            "success": True,
            "return_code": 0,
            "output": "검증 성공",
        }
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/api/build",
                data=json.dumps({"command": "validate"}).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with mock.patch.object(content_manager, "_run_build", return_value=result) as runner:
                with urllib.request.urlopen(request) as response:
                    payload = json.load(response)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        runner.assert_called_once_with(root.resolve(), "validate")
        self.assertTrue(payload["success"])

    def test_document_creation_api(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = content_manager.ThreadingHTTPServer(
                ("127.0.0.1", 0), content_manager.create_handler(root)
            )
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                request = urllib.request.Request(
                    f"http://127.0.0.1:{server.server_port}/api/documents",
                    data=json.dumps(
                        {
                            "category": "trainers",
                            "slug": "api_trainer",
                            "name": "API 트레이너",
                            "generation": "generation_1",
                        }
                    ).encode("utf-8"),
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(request) as response:
                    payload = json.load(response)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)
            self.assertTrue(payload["created"])
            self.assertTrue((root / payload["path"]).is_file())


if __name__ == "__main__":
    unittest.main()

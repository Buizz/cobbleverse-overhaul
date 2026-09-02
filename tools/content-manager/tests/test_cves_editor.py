from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path


CONTENT_MANAGER_ROOT = Path(__file__).parents[1]
if str(CONTENT_MANAGER_ROOT) not in sys.path:
    sys.path.insert(0, str(CONTENT_MANAGER_ROOT))

from cves import (  # noqa: E402
    CvesEditorConflict,
    encode_program,
    format_program,
    list_scripts,
    load_script,
    parse,
    save_script,
)


MODULE_PATH = CONTENT_MANAGER_ROOT / "content_manager.py"
SPEC = importlib.util.spec_from_file_location("cves_editor_content_manager", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
content_manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = content_manager
SPEC.loader.exec_module(content_manager)


SOURCE = '''event interact(range: 4) {
  page default {
    say npc "안녕하세요."
  }
}
'''
OAK_FIXTURE = Path(__file__).parent / "fixtures" / "professor_oak.cves"


class CvesEditorServiceTests(unittest.TestCase):
    def test_span_free_professor_oak_gui_ast_saves_and_reopens_losslessly(self) -> None:
        program = parse(OAK_FIXTURE.read_text(encoding="utf-8"), str(OAK_FIXTURE))
        wire_ast = encode_program(program, include_spans=False)
        expected = format_program(program)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            v4_path = root / "content/source/npcs/professor_oak.json"
            v4_path.parent.mkdir(parents=True)
            v4_path.write_text('{"schema_version":4}\n', encoding="utf-8")

            saved = save_script(root, "test/story/professor_oak.cves", wire_ast, None)
            reopened = load_script(root, "test/story/professor_oak.cves")

            self.assertTrue(saved["saved"])
            self.assertEqual(expected, saved["canonical"])
            self.assertEqual(saved["canonical"], reopened["canonical"])
            self.assertEqual(saved["ast"], reopened["ast"])
            self.assertEqual('{"schema_version":4}\n', v4_path.read_text(encoding="utf-8"))

    def test_ast_save_is_canonical_conflict_safe_and_v4_independent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "content/events/test/story/welcome.cves"
            source_path.parent.mkdir(parents=True)
            source_path.write_text(SOURCE, encoding="utf-8", newline="\n")
            v4_path = root / "content/source/trainers/legacy.json"
            v4_path.parent.mkdir(parents=True)
            v4_path.write_text('{"schema_version":4}\n', encoding="utf-8")

            document = load_script(root, "test/story/welcome.cves")
            document["ast"]["root"]["events"][0]["pages"][0]["block"]["statements"][0]["text"]["value"] = "반갑습니다."
            saved = save_script(
                root,
                "test/story/welcome.cves",
                document["ast"],
                document["digest"],
            )

            self.assertTrue(saved["saved"])
            self.assertEqual(saved["canonical"], source_path.read_text(encoding="utf-8"))
            self.assertEqual(
                hashlib.sha256(source_path.read_bytes()).hexdigest(), saved["digest"]
            )
            self.assertIn('say npc "반갑습니다."', saved["source"])
            self.assertEqual('{"schema_version":4}\n', v4_path.read_text(encoding="utf-8"))
            with self.assertRaises(CvesEditorConflict):
                save_script(
                    root,
                    "test/story/welcome.cves",
                    document["ast"],
                    document["digest"],
                )

    def test_script_discovery_is_deterministic_and_rejects_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ("zeta/b.cves", "alpha/nested/a.cves"):
                path = root / "content/events" / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(SOURCE, encoding="utf-8")

            self.assertEqual(
                ["alpha/nested/a.cves", "zeta/b.cves"],
                [item["path"] for item in list_scripts(root)],
            )
            with self.assertRaises(ValueError):
                load_script(root, "../source/trainers/legacy.json")
            with self.assertRaises(ValueError):
                load_script(root, "alpha\\nested\\a.cves")


class CvesEditorApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        source_path = self.root / "content/events/test/story/welcome.cves"
        source_path.parent.mkdir(parents=True)
        source_path.write_text(SOURCE, encoding="utf-8", newline="\n")
        definitions_path = self.root / "content/catalogs/game-definitions.json"
        definitions_path.parent.mkdir(parents=True)
        definitions_path.write_text(json.dumps({
            "$schema": "../schemas/game-definitions.schema.json",
            "schema_version": 1,
            "items": [],
            "variables": [],
        }), encoding="utf-8")
        self.server = content_manager.ThreadingHTTPServer(
            ("127.0.0.1", 0), content_manager.create_handler(self.root)
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def call(self, path: str, method: str = "GET", payload: object | None = None) -> tuple[int, dict]:
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.server.server_port}{path}",
            data=None if payload is None else json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method=method,
        )
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def test_library_metadata_api_conflict_and_managed_source_protection(self) -> None:
        source = self.root / "content/events/test/story/welcome.cves"
        original = source.read_bytes()
        payload = {"path": "test/story/welcome.cves", "expected_digest": None,
                   "metadata": {"schema_version": 1, "display_name": "공용 인사", "category": "system", "tags": ["공통"]}}
        status, saved = self.call("/api/cves/metadata", "PUT", payload)
        self.assertEqual(status, 200)
        self.assertEqual(saved["name"], "공용 인사")
        self.assertEqual(self.call("/api/cves/metadata", "PUT", payload)[0], 409)
        self.assertEqual(source.read_bytes(), original)
        npc = self.root / "content/source/story/npc.json"
        npc.parent.mkdir(parents=True)
        npc.write_text(json.dumps({"id": "test:npc/npc", "event_runtime": {
            "engine": "cves_v5", "authoring": "preset", "script_id": "test:event_script/story/welcome"}}), encoding="utf-8")
        status, loaded = self.call("/api/cves/script?path=test/story/welcome.cves")
        self.assertEqual(status, 200)
        self.assertTrue(loaded["library"]["managed"])
        status, rejected = self.call("/api/cves/script", "PUT", {
            "path": loaded["path"], "ast": loaded["ast"], "expected_digest": loaded["digest"]})
        self.assertEqual(status, 400)
        self.assertIn("프리셋 관리", rejected["error"])
        self.assertEqual(source.read_bytes(), original)

    def test_custom_binding_reuses_selected_source_and_rejects_other_managed_source(self) -> None:
        target = self.root / "content/source/story/new_npc.json"
        data = {"id": "test:npc/new_npc", "event_runtime": {"engine": "cves_v5", "authoring": "custom", "script_id": "test:event_script/story/welcome"}}
        original = (self.root / "content/events/test/story/welcome.cves").read_bytes()
        plan = content_manager._prepare_v5_preset_sync(self.root, target, data)
        self.assertNotIn("event_path", plan)
        content_manager._write_v5_preset_sync(plan)
        self.assertEqual(json.loads(plan["binding_path"].read_text())["script_id"], "test:event_script/story/welcome")
        self.assertEqual((self.root / "content/events/test/story/welcome.cves").read_bytes(), original)
        owner = self.root / "content/source/story/owner.json"
        owner.parent.mkdir(parents=True)
        owner.write_text(json.dumps({"id": "test:npc/owner", "event_runtime": {**data["event_runtime"], "authoring": "preset"}}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "다른 NPC"):
            content_manager._prepare_v5_preset_sync(self.root, target, data)
        # Switching the owner itself to custom is allowed and never rewrites CVES.
        self.assertNotIn("event_path", content_manager._prepare_v5_preset_sync(self.root, owner, data))

    def test_list_load_validate_save_and_conflict_contract(self) -> None:
        status, listing = self.call("/api/cves/scripts")
        self.assertEqual(200, status)
        self.assertEqual("test:event_script/story/welcome", listing["items"][0]["script_id"])

        status, loaded = self.call("/api/cves/script?path=test%2Fstory%2Fwelcome.cves")
        self.assertEqual(200, status)
        self.assertTrue(loaded["valid"])
        loaded["ast"]["root"]["events"][0]["pages"][0]["block"]["statements"][0]["text"]["value"] = "API 저장"

        status, saved = self.call("/api/cves/script", "PUT", {
            "path": loaded["path"], "ast": loaded["ast"],
            "expected_digest": loaded["digest"],
        })
        self.assertEqual(200, status)
        self.assertTrue(saved["saved"])
        self.assertIn('say npc "API 저장"', saved["source"])

        status, conflict = self.call("/api/cves/script", "PUT", {
            "path": loaded["path"], "ast": loaded["ast"],
            "expected_digest": loaded["digest"],
        })
        self.assertEqual(409, status)
        self.assertEqual("cves_source_conflict", conflict["code"])

        status, invalid = self.call("/api/cves/validate", "POST", {
            "path": "test/story/broken.cves",
            "source": "event interact {\n  page default {\n    say npc @\n  }\n}\n",
        })
        self.assertEqual(422, status)
        self.assertEqual("test/story/broken.cves", invalid["diagnostics"][0]["source"])
        self.assertEqual(3, invalid["diagnostics"][0]["line"])
        self.assertGreaterEqual(invalid["diagnostics"][0]["column"], 1)
        self.assertIsNotNone(invalid["diagnostics"][0]["token"])

    def test_editor_contract_expression_parser_and_new_source_save(self) -> None:
        status, contract = self.call("/api/cves/editor-contract")
        self.assertEqual(200, status)
        take_money = next(value for value in contract["commands"] if value["id"] == "take_money")
        battle = next(value for value in contract["commands"] if value["id"] == "battle")
        give_item = next(value for value in contract["commands"] if value["id"] == "give_item")
        call = next(value for value in contract["commands"] if value["id"] == "call")
        self.assertEqual(["allow_debt"], take_money["flags"])
        self.assertEqual("int", take_money["positional"][0]["types"][0])
        self.assertTrue(battle["awaited"])
        self.assertTrue(battle["waits_for_completion"])
        self.assertEqual("battle_result", battle["result_type"])
        self.assertFalse(give_item["awaited"])
        self.assertTrue(give_item["waits_for_completion"])
        self.assertFalse(give_item["advanced"])
        self.assertTrue(call["advanced"])
        self.assertEqual(
            ["species_id", "form", "level", "name"],
            [field["name"] for field in contract["result_fields"]["pokemon_selection"]],
        )
        self.assertIn("josa:을/를", contract["template_filters"]["localized_name"])
        flag_trigger = next(value for value in contract["triggers"] if value["id"] == "flag_changed")
        target = next(value for value in flag_trigger["arguments"] if value["name"] == "target")
        self.assertFalse(target["optional"])
        self.assertEqual("flag", target["resource_kind"])

        status, parsed = self.call("/api/cves/expression", "POST", {
            "path": "test/story/new.cves",
            "source": 'flag("cobbleventure:flag/story/started") && money() >= 100',
        })
        self.assertEqual(200, status)
        self.assertEqual("binary", parsed["expression"]["root"]["node"])
        self.assertEqual(
            'flag("cobbleventure:flag/story/started") && money() >= 100',
            parsed["canonical"],
        )

        status, invalid = self.call("/api/cves/expression", "POST", {
            "path": "test/story/new.cves", "source": "money() >= @",
        })
        self.assertEqual(422, status)
        self.assertEqual(1, invalid["diagnostics"][0]["line"])
        self.assertEqual("@", invalid["diagnostics"][0]["token"])

        status, loaded = self.call("/api/cves/script?path=test%2Fstory%2Fwelcome.cves")
        self.assertEqual(200, status)
        status, created = self.call("/api/cves/script", "PUT", {
            "path": "test/story/created.cves", "ast": loaded["ast"],
            "expected_digest": None,
        })
        self.assertEqual(200, status)
        self.assertTrue(created["saved"])
        self.assertTrue((self.root / "content/events/test/story/created.cves").is_file())
        status, reopened = self.call("/api/cves/script?path=test%2Fstory%2Fcreated.cves")
        self.assertEqual(200, status)
        self.assertEqual(created["ast"], reopened["ast"])
        self.assertEqual(created["canonical"], reopened["canonical"])

        status, listing = self.call("/api/cves/scripts")
        self.assertEqual(200, status)
        self.assertEqual(
            ["test/story/created.cves", "test/story/welcome.cves"],
            [value["path"] for value in listing["items"]],
        )

    def test_v5_static_entrypoint_is_served(self) -> None:
        with urllib.request.urlopen(
            f"http://127.0.0.1:{self.server.server_port}/cves.html"
        ) as response:
            body = response.read().decode("utf-8")
        self.assertIn("이벤트 스크립트", body)
        self.assertIn('/cves-editor.js', body)
        self.assertIn('id="new-script"', body)
        self.assertIn('data-add="let"', body)
        self.assertIn('data-add="repeat"', body)

    def test_editor_uses_event_anchor_resource_without_nested_anchor_property(self) -> None:
        source = (CONTENT_MANAGER_ROOT / "web" / "cves-editor.js").read_text(
            encoding="utf-8"
        )

        self.assertIn('anchor: "event_anchor"', source)
        self.assertIn(
            'parameter.name === "anchor" && destinationKind === "anchor"',
            source,
        )
        self.assertIn(
            'callName(value) === "anchor"',
            source,
        )

    def test_new_game_variable_is_available_in_the_editor_contract(self) -> None:
        status, definitions = self.call("/api/game-definitions")
        self.assertEqual(200, status)
        definitions["variables"].append({
            "id": "test:flag/story/met_guide",
            "scope": "player",
            "type": "boolean",
            "default": False,
            "display_name": {"ko_kr": "안내인과 대화함"},
            "description": {"ko_kr": ""},
        })
        status, saved = self.call("/api/game-definitions", "PUT", definitions)
        self.assertEqual(200, status)
        self.assertTrue(saved["saved"])

        status, contract = self.call("/api/cves/editor-contract")
        self.assertEqual(200, status)
        self.assertIn("test:flag/story/met_guide", contract["resources"]["flag"])

    def test_editor_reuses_game_variables_and_can_create_them_in_place(self) -> None:
        markup = (CONTENT_MANAGER_ROOT / "web" / "cves.html").read_text(encoding="utf-8")
        source = (CONTENT_MANAGER_ROOT / "web" / "cves-editor.js").read_text(encoding="utf-8")

        self.assertIn('id="new-variable-dialog"', markup)
        self.assertIn("전체 변수 관리", markup)
        self.assertIn('data-add="let" type="button" disabled>임시 변수', markup)
        self.assertIn('request("/api/game-definitions")', source)
        self.assertIn("variableResourceField", source)
        self.assertIn("declaredVariableEntries", source)
        self.assertIn("createGameVariable", source)
        self.assertIn("결과 임시 변수", source)
        self.assertIn("저장되는 진행 변수와는 별개", source)


if __name__ == "__main__":
    unittest.main()

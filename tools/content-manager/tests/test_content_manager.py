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

    def test_starter_town_is_valid(self) -> None:
        root = Path(__file__).parents[3]
        settlement_id, issues = content_manager.validate_settlement_file(
            root / "content" / "settlements" / "generation_1" / "starter_town.json"
        )
        self.assertEqual("cobbleventure:settlement/starter_town", settlement_id)
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
            with urllib.request.urlopen(base_url) as response:
                page = response.read().decode("utf-8")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        self.assertEqual("ok", health["status"])
        self.assertTrue(validation["valid"])
        self.assertGreaterEqual(dashboard["trainers"], 2)
        self.assertEqual(1, dashboard["settlements"])
        self.assertEqual(1, len(settlements["items"]))
        self.assertIn("Cobbleventure Content Studio", page)

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


if __name__ == "__main__":
    unittest.main()

import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
CONTENT_MANAGER = ROOT / "tools" / "content-manager"
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
FIXTURE = Path(__file__).parent / "fixtures" / "professor_oak.cves"
sys.path.insert(0, str(CONTENT_MANAGER))

SPEC = importlib.util.spec_from_file_location("cves_tool", CONTENT_MANAGER / "cves_tool.py")
cves_tool = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(cves_tool)

from cves import decode_program, format_program, parse  # noqa: E402


class CvesToolTests(unittest.TestCase):
    def run_tool(self, *arguments: str):
        stdout = io.StringIO()
        stderr = io.StringIO()
        code = cves_tool.main(arguments, stdout=stdout, stderr=stderr)
        return code, stdout.getvalue(), stderr.getvalue()

    def test_check_uses_real_project_catalog(self) -> None:
        code, stdout, stderr = self.run_tool(
            "check",
            str(FIXTURE),
            "--project-root", str(PROJECT_ROOT),
            "--item-catalog", str(ROOT / "trainer-data" / "catalogs" / "cobblemon-items.json"),
        )

        self.assertEqual(0, code)
        self.assertIn("[OK]", stdout)
        self.assertEqual("", stderr)

    def test_check_reports_syntax_diagnostic_and_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.cves"
            path.write_text('event interact {\n  page default {\n    say npc "닫히지 않음\n', encoding="utf-8")

            code, stdout, stderr = self.run_tool("check", str(path))

        self.assertEqual(1, code)
        self.assertEqual("", stdout)
        self.assertIn("bad.cves:3:13", stderr)
        self.assertIn("문제 토큰", stderr)

    def test_format_check_accepts_canonical_fixture(self) -> None:
        code, stdout, stderr = self.run_tool("format", str(FIXTURE), "--check")

        self.assertEqual(0, code)
        self.assertIn("[OK]", stdout)
        self.assertEqual("", stderr)

    def test_format_write_is_explicit_and_writes_utf8_lf(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "format.cves"
            path.write_bytes(b'event interact {\r\n page default { stop }\r\n}\r\n')

            check_code, _, _ = self.run_tool("format", str(path), "--check")
            write_code, stdout, stderr = self.run_tool("format", str(path), "--write")
            result = path.read_bytes()

        self.assertEqual(1, check_code)
        self.assertEqual(0, write_code)
        self.assertIn("[WRITE]", stdout)
        self.assertEqual("", stderr)
        self.assertNotIn(b"\r\n", result)
        self.assertEqual(b"event interact {\n  page default {\n    stop\n  }\n}\n", result)

    def test_ast_outputs_versioned_json_without_spans(self) -> None:
        code, stdout, stderr = self.run_tool("ast", str(FIXTURE), "--no-spans")
        data = json.loads(stdout)
        program = decode_program(data)

        self.assertEqual(0, code)
        self.assertEqual("", stderr)
        self.assertEqual(1, data["wire_version"])
        self.assertNotIn("span", data["root"])
        self.assertEqual(format_program(parse(FIXTURE.read_text(encoding="utf-8"))), format_program(program))

    def test_compile_outputs_runtime_ir_with_project_validation(self) -> None:
        code, stdout, stderr = self.run_tool(
            "compile", str(FIXTURE),
            "--script-id", "cobbleventure:event_script/story/professor_oak",
            "--project-root", str(PROJECT_ROOT),
            "--item-catalog", str(ROOT / "trainer-data" / "catalogs" / "cobblemon-items.json"),
        )
        ir = json.loads(stdout)

        self.assertEqual(0, code)
        self.assertEqual("", stderr)
        self.assertEqual(1, ir["schema_version"])
        self.assertEqual("cobbleventure:event_script/story/professor_oak", ir["script_id"])

    def test_compile_writes_only_when_output_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "generated" / "oak.json"
            code, stdout, stderr = self.run_tool(
                "compile", str(FIXTURE),
                "--script-id", "cobbleventure:event_script/story/professor_oak",
                "--output", str(target),
            )
            ir = json.loads(target.read_text(encoding="utf-8"))

        self.assertEqual(0, code)
        self.assertIn("[WRITE]", stdout)
        self.assertEqual("", stderr)
        self.assertEqual(1, ir["schema_version"])


if __name__ == "__main__":
    unittest.main()

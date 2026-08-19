import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
CONTENT_MANAGER = ROOT / "tools" / "content-manager"
sys.path.insert(0, str(CONTENT_MANAGER))

from cves import (  # noqa: E402
    AstCodecError,
    decode_expression,
    decode_program,
    encode_expression,
    encode_program,
    format_program,
    format_expression,
    parse,
    parse_expression,
    validate,
)


FIXTURE = Path(__file__).parent / "fixtures" / "professor_oak.cves"


class CvesAstCodecTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.program = parse(FIXTURE.read_text(encoding="utf-8"), str(FIXTURE))

    def test_ast_wire_round_trip_preserves_meaning_and_source_spans(self) -> None:
        data = encode_program(self.program)
        decoded = decode_program(json.loads(json.dumps(data, ensure_ascii=False)))

        self.assertEqual(self.program, decoded)
        self.assertEqual(self.program.span, decoded.span)
        self.assertEqual("program", data["root"]["node"])
        self.assertEqual(1, data["wire_version"])

    def test_span_free_wire_round_trip_is_valid_for_gui_created_ast(self) -> None:
        data = encode_program(self.program, include_spans=False)
        decoded = decode_program(data)

        self.assertEqual(self.program, decoded)
        self.assertIsNone(decoded.span)
        self.assertNotIn("span", data["root"])
        self.assertEqual(format_program(self.program), format_program(decoded))

    def test_wire_data_can_be_edited_and_formatted_back_to_cves(self) -> None:
        data = encode_program(self.program, include_spans=False)
        first_say = data["root"]["events"][0]["pages"][0]["block"]["statements"][0]
        first_say["text"]["entries"][0]["value"] = "GUI에서 변경한 대사"

        formatted = format_program(decode_program(data))

        self.assertIn('ko_kr: "GUI에서 변경한 대사"', formatted)
        self.assertEqual(decode_program(data), parse(formatted, "edited.cves"))

    def test_decoder_rejects_unknown_version_field_and_child_type(self) -> None:
        cases = [
            ({"wire_version": 2, "root": {"node": "program", "events": []}}, "wire_version"),
            ({"wire_version": 1, "extra": True, "root": {"node": "program", "events": []}}, "지원하지 않는 필드"),
            ({
                "wire_version": 1,
                "root": {
                    "node": "program",
                    "events": [{"node": "text", "value": "잘못된 자식"}],
                },
            }, "event 노드"),
        ]
        for data, message in cases:
            with self.subTest(message=message), self.assertRaises(AstCodecError) as raised:
                decode_program(data)
            self.assertIn(message, str(raised.exception))

    def test_decoder_rejects_unknown_command_enum(self) -> None:
        data = encode_program(parse('event interact {\n  page default { stop }\n}\n'))
        command = data["root"]["events"][0]["pages"][0]["block"]["statements"][0]
        command["kind"] = "execute_arbitrary_code"

        with self.assertRaises(AstCodecError) as raised:
            decode_program(data)

        self.assertIn("지원하지 않는 enum", str(raised.exception))
        self.assertIn(".kind", raised.exception.path)

    def test_span_free_gui_ast_gets_safe_semantic_diagnostics(self) -> None:
        data = encode_program(self.program, include_spans=False)
        pages = data["root"]["events"][0]["pages"]
        pages.insert(0, pages.pop())
        roulette = next(
            statement
            for statement in pages[0]["block"]["statements"]
            if statement["node"] == "command" and statement["kind"] == "starter_roulette"
        )
        roulette["awaited"] = False

        diagnostics = validate(decode_program(data))

        self.assertEqual(2, len(diagnostics))
        self.assertTrue(all(value.span.source == "<ast>" for value in diagnostics))
        self.assertTrue(any("default page" in value.message for value in diagnostics))
        self.assertTrue(any("await" in value.message for value in diagnostics))

    def test_nested_control_nodes_round_trip_through_wire(self) -> None:
        source = '''event interact {
  page default {
    let count = 2
    repeat count {
      if money() >= 100 {
        choice "선택" {
          "계속" { narrate "진행" }
        }
      } else {
        stop
      }
    }
  }
}
'''
        program = parse(source, "nested-wire.cves")

        self.assertEqual(program, decode_program(encode_program(program)))

    def test_standalone_expression_wire_round_trip_is_canonical(self) -> None:
        expression = parse_expression(
            'flag("cobbleventure:flag/story/started") && money() >= 500',
            "condition-field.cves",
        )
        wire = encode_expression(expression, include_spans=False)
        decoded = decode_expression(wire)

        self.assertEqual(expression, decoded)
        self.assertEqual(
            'flag("cobbleventure:flag/story/started") && money() >= 500',
            format_expression(decoded),
        )
        self.assertNotIn("span", wire["root"])


if __name__ == "__main__":
    unittest.main()

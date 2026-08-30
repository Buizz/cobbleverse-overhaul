import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
CONTENT_MANAGER = ROOT / "tools" / "content-manager"
sys.path.insert(0, str(CONTENT_MANAGER))

from cves import CvesSyntaxError, Lexer, TokenKind, format_program, parse, validate  # noqa: E402
from cves import ast  # noqa: E402


FIXTURES = Path(__file__).parent / "fixtures"


class CvesLexerTests(unittest.TestCase):
    def test_tokens_keep_line_and_column(self) -> None:
        tokens = Lexer('say npc "안녕"\n  await battle "cv:battle/test" -> result', "sample.cves").tokenize()

        await_token = next(token for token in tokens if token.lexeme == "await")
        self.assertEqual((2, 3), (await_token.span.start.line, await_token.span.start.column))
        self.assertIn(TokenKind.ARROW, [token.kind for token in tokens])

    def test_invalid_character_reports_source_token_and_position(self) -> None:
        with self.assertRaises(CvesSyntaxError) as raised:
            Lexer("event interact {\n  @\n}", "bad.cves").tokenize()

        self.assertEqual("bad.cves", raised.exception.diagnostic.span.source)
        self.assertEqual((2, 3), (
            raised.exception.diagnostic.span.start.line,
            raised.exception.diagnostic.span.start.column,
        ))
        self.assertEqual("@", raised.exception.diagnostic.token)
        self.assertIn("bad.cves:2:3", str(raised.exception))


class CvesRoundTripTests(unittest.TestCase):
    def test_map_selection_fixture_round_trips_deterministically(self) -> None:
        path = FIXTURES / "map_selection.cves"
        first = parse(path.read_text(encoding="utf-8"), str(path))

        formatted = format_program(first)
        second = parse(formatted, str(path))

        self.assertEqual(first, second)
        self.assertEqual(formatted, format_program(second))
        statements = first.events[0].pages[0].block.statements
        selection = next(
            value for value in statements
            if isinstance(value, ast.CommandStatement)
            and value.kind is ast.CommandKind.MAP_SELECTION
        )
        self.assertTrue(selection.awaited)
        self.assertEqual("destination", selection.result)
        self.assertIn("${destination.name|josa:을/를}", formatted)

    def test_professor_oak_fixture_round_trips_semantically(self) -> None:
        path = FIXTURES / "professor_oak.cves"
        first = parse(path.read_text(encoding="utf-8"), str(path))

        formatted = format_program(first)
        second = parse(formatted, str(path))

        self.assertEqual(first, second)
        self.assertEqual(formatted, format_program(second))
        self.assertEqual(3, len(first.events[0].pages))
        default_statements = first.events[0].pages[-1].block.statements
        roulette = next(
            statement for statement in default_statements
            if isinstance(statement, ast.CommandStatement)
            and statement.kind is ast.CommandKind.STARTER_ROULETTE
        )
        self.assertTrue(roulette.awaited)
        self.assertEqual("starter", roulette.result)

    def test_professor_oak_uses_only_structured_default_flow(self) -> None:
        path = FIXTURES / "professor_oak.cves"
        program = parse(path.read_text(encoding="utf-8"), str(path))
        statements = []

        def collect(block):
            for statement in block.statements:
                statements.append(statement)
                if isinstance(statement, ast.IfStatement):
                    collect(statement.then_block)
                    if statement.else_block is not None:
                        collect(statement.else_block)
                elif isinstance(statement, ast.ChoiceStatement):
                    for option in statement.options:
                        collect(option.block)
                elif isinstance(statement, ast.RepeatStatement):
                    collect(statement.block)

        for page in program.events[0].pages:
            collect(page.block)

        advanced = {
            ast.CommandKind.LABEL, ast.CommandKind.JUMP,
            ast.CommandKind.CALL, ast.CommandKind.RETURN,
        }
        self.assertFalse(any(
            isinstance(statement, ast.CommandStatement) and statement.kind in advanced
            for statement in statements
        ))
        self.assertEqual(
            [
                "retry/give_pokedex", "retry/set_pokedex_received",
                "first/starter_roulette", "first/give_pokedex",
                "first/set_pokedex_received",
            ],
            [statement.stable_id for statement in statements if statement.stable_id],
        )

    def test_server_signal_fixture_round_trips_deterministically(self) -> None:
        path = FIXTURES / "server_signals.cves"
        first = parse(path.read_text(encoding="utf-8"), str(path))

        formatted = format_program(first)
        second = parse(formatted, str(path))

        self.assertEqual(first, second)
        self.assertEqual(formatted, format_program(second))
        self.assertEqual(
            ["flag_changed", "item_used", "battle_finished"],
            [event.trigger.name for event in first.events],
        )

    def test_nested_control_flow_movement_and_expressions_round_trip(self) -> None:
        source = '''event interact(range: 4) {
  page default {
    let reward = level_cap() * 20
    if money() >= reward {
      choice "무엇을 할까?" {
        "구입한다" {
          take_money reward
          give_item "cobblemon:poke_ball" count 5 notify -> items
        }
        "그만둔다" {
          stop
        }
      } -> selected
    } else {
      narrate "돈이 부족하구나."
    }
    await teleport player settlement("cobbleventure:settlement/pallet_town") {
      anchor: "pokemon_center/interior"
      fade: black
      safe_landing: required
    } -> arrival
    if !arrival.arrived {
      narrate "안전한 도착 지점을 찾지 못했다."
    }
  }
}
'''
        first = parse(source, "nested.cves")
        formatted = format_program(first)
        second = parse(formatted, "nested.cves")

        self.assertEqual(first, second)
        self.assertIn("} else {", formatted)
        self.assertIn("await teleport", formatted)

    def test_stable_id_prefix_round_trips_with_statement(self) -> None:
        source = '''event interact {
  page default {
    id "intro/greeting" say npc "안녕"
  }
}
'''
        program = parse(source, "stable.cves")
        statement = program.events[0].pages[0].block.statements[0]

        self.assertEqual("intro/greeting", statement.stable_id)
        self.assertEqual(source, format_program(program))


class CvesParserDiagnosticTests(unittest.TestCase):
    def test_missing_closing_brace_reports_eof_location(self) -> None:
        source = 'event interact {\n  page default {\n    say npc "안녕"\n'

        with self.assertRaises(CvesSyntaxError) as raised:
            parse(source, "missing.cves")

        diagnostic = raised.exception.diagnostic
        self.assertEqual("missing.cves", diagnostic.span.source)
        self.assertEqual("<EOF>", diagnostic.token)
        self.assertEqual(4, diagnostic.span.start.line)
        self.assertIn("'}'", diagnostic.message)

    def test_default_page_must_be_last(self) -> None:
        source = '''event interact {
  page default {}
  page when true {}
}
'''
        with self.assertRaises(CvesSyntaxError) as raised:
            parse(source, "pages.cves")

        self.assertIn("default page 뒤", raised.exception.diagnostic.message)
        self.assertEqual(3, raised.exception.diagnostic.span.start.line)

    def test_async_command_requires_await(self) -> None:
        source = 'event interact {\n  page default {\n    battle "cv:battle/test"\n  }\n}\n'

        with self.assertRaises(CvesSyntaxError) as raised:
            parse(source, "await.cves")

        self.assertEqual((3, 5), (
            raised.exception.diagnostic.span.start.line,
            raised.exception.diagnostic.span.start.column,
        ))
        self.assertEqual("battle", raised.exception.diagnostic.token)
        self.assertIn("await", raised.exception.diagnostic.message)


class CvesSemanticTests(unittest.TestCase):
    def test_professor_oak_fixture_is_semantically_valid(self) -> None:
        path = FIXTURES / "professor_oak.cves"
        program = parse(path.read_text(encoding="utf-8"), str(path))

        self.assertEqual((), validate(program))

    def test_result_type_flows_to_movement_condition_and_template(self) -> None:
        source = '''event interact(range: 4) {
  page default {
    await starter_roulette -> starter
    say npc "${starter.name|josa:을/를} 골랐구나!"
    await teleport player settlement("cobbleventure:settlement/pallet_town") {
      anchor: "pokemon_center/interior"
      safe_landing: required
    } -> travel
    if !travel.arrived {
      narrate "이동하지 못했다."
    }
  }
}
'''
        self.assertEqual((), validate(parse(source, "flow.cves")))

    def test_choice_result_is_a_zero_based_integer_index(self) -> None:
        source = '''event interact {
  page default {
    choice "선택" {
      "첫 번째" { narrate "A" }
      "두 번째" { narrate "B" }
    } -> selected
    if selected == 1 {
      narrate "두 번째 선택"
    }
  }
}
'''
        self.assertEqual((), validate(parse(source, "choice-result.cves")))

    def test_has_item_is_a_typed_catalog_checked_condition(self) -> None:
        source = '''event interact {
  page default {
    if has_item("cobblemon:potion", 1) {
      narrate "준비됐다."
    }
  }
}
'''

        self.assertEqual((), validate(parse(source, "has-item.cves")))

        wrong_count = source.replace(", 1)", ", true)")
        diagnostics = validate(parse(wrong_count, "has-item-count.cves"))
        self.assertTrue(any("has_item 함수 인자 타입" in issue.message for issue in diagnostics))

    def test_unlock_feature_uses_the_runtime_feature_key_enum(self) -> None:
        source = '''event interact {
  page default {
    unlock_feature map
    unlock_feature settlement_teleport
    unlock_feature pc
  }
}
'''
        self.assertEqual((), validate(parse(source, "feature-keys.cves")))

        invalid = source.replace("unlock_feature pc", "unlock_feature bag")
        diagnostics = validate(parse(invalid, "feature-key-invalid.cves"))

        self.assertEqual(1, len(diagnostics))
        self.assertIn("map, pc, settlement_teleport 중 하나", diagnostics[0].message)

    def test_open_daycare_is_a_parameterless_immediate_command(self) -> None:
        source = '''event interact {
  page default {
    say npc "어서 오세요."
    open_daycare
  }
}
'''

        self.assertEqual((), validate(parse(source, "daycare.cves")))


    def test_reports_unknown_variable_wrong_filter_and_bad_resource_id(self) -> None:
        source = '''event interact {
  page default {
    give_item "Not A Resource" count 1
    let reward = 500
    say npc "${missing.name} ${reward|josa:을/를}"
  }
}
'''
        diagnostics = validate(parse(source, "semantic.cves"))
        rendered = "\n".join(value.render() for value in diagnostics)

        self.assertEqual(3, len(diagnostics))
        self.assertIn("올바른 resource_id", rendered)
        self.assertIn("템플릿 변수가 정의되지", rendered)
        self.assertIn("josa 필터는 string", rendered)
        self.assertTrue(all(value.span.source == "semantic.cves" for value in diagnostics))

    def test_localized_text_requires_same_variable_paths(self) -> None:
        source = '''event interact {
  page default {
    await starter_roulette -> starter
    say npc {
      ko_kr: "${starter.name|josa:을/를} 골랐구나!"
      en_us: "Welcome!"
    }
  }
}
'''
        diagnostics = validate(parse(source, "locale.cves"))

        self.assertEqual(1, len(diagnostics))
        self.assertIn("언어별 대사의 변수 경로", diagnostics[0].message)
        self.assertEqual(4, diagnostics[0].span.start.line)

    def test_branch_local_variable_does_not_escape_its_block(self) -> None:
        source = '''event interact {
  page default {
    if true {
      let secret = 1
    }
    give_money secret
  }
}
'''
        diagnostics = validate(parse(source, "scope.cves"))

        self.assertEqual(1, len(diagnostics))
        self.assertEqual("secret", diagnostics[0].token)
        self.assertIn("정의되지 않은 변수", diagnostics[0].message)

    def test_give_money_accepts_notify_flag_and_bool_result(self) -> None:
        source = '''event interact {
  page default {
    give_money 500 notify -> rewarded
    if rewarded {
      narrate "상금을 받았다."
    }
  }
}
'''

        self.assertEqual((), validate(parse(source, "money-notify.cves")))

    def test_take_money_accepts_explicit_allow_debt_flag(self) -> None:
        source = '''event interact {
  page default {
    take_money 900 allow_debt -> charged
    if charged {
      narrate "외상으로 계산했다."
    }
  }
}
'''

        program = parse(source, "money-debt.cves")
        formatted = format_program(program)

        self.assertEqual((), validate(program))
        self.assertIn("take_money 900 allow_debt -> charged", formatted)
        self.assertEqual((), validate(parse(formatted, "money-debt-formatted.cves")))

    def test_template_parser_rejects_executable_expression(self) -> None:
        source = '''event interact {
  page default {
    say npc "${money()}"
  }
}
'''
        diagnostics = validate(parse(source, "template.cves"))

        self.assertEqual(1, len(diagnostics))
        self.assertIn("올바르지 않은 템플릿 경로", diagnostics[0].message)

    def test_rejects_unknown_result_field_and_invalid_command_shape(self) -> None:
        source = '''event interact {
  page default {
    await battle "cobbleventure:battle/test" -> battle
    if battle.winner {
      give_item "cobblemon:poke_ball" count "five" {
        unknown: true
      }
    }
  }
}
'''
        diagnostics = validate(parse(source, "contract.cves"))
        rendered = "\n".join(value.message for value in diagnostics)

        self.assertEqual(3, len(diagnostics))
        self.assertIn("winner", rendered)
        self.assertIn("count 타입", rendered)
        self.assertIn("지원하지 않는 속성", rendered)

    def test_relative_location_requires_named_xyz_coordinates(self) -> None:
        valid = '''event interact {
  page default {
    await move player relative(x: 0, y: 0, z: -4) -> moved
  }
}
'''
        invalid = '''event interact {
  page default {
    await move player relative(0, 0) -> moved
  }
}
'''

        self.assertEqual((), validate(parse(valid, "relative.cves")))
        diagnostics = validate(parse(invalid, "relative-bad.cves"))
        self.assertEqual(3, len(diagnostics))
        self.assertTrue(any("필수 인자" in value.message for value in diagnostics))

    def test_move_and_teleport_properties_are_command_specific(self) -> None:
        valid = '''event interact {
  page default {
    await move npc relative(x: 2, y: 0, z: -1) {
      mode: walk
      speed: 1.25
      lock_input: true
      collision: stop
    } -> moved
  }
}
'''
        invalid = '''event interact {
  page default {
    await move player position(dimension: "minecraft:overworld", x: 0, y: 64, z: 0) {
      fade: black
    } -> moved
    await teleport player relative(x: 0, y: 0, z: 1) {
      speed: 1.0
    } -> arrival
  }
}
'''

        self.assertEqual((), validate(parse(valid, "move.cves")))
        diagnostics = validate(parse(invalid, "movement-options.cves"))
        rendered = "\n".join(value.message for value in diagnostics)
        self.assertEqual(3, len(diagnostics))
        self.assertIn("move destination은 relative", rendered)
        self.assertIn("move에서 지원하지 않는 속성 'fade'", rendered)
        self.assertIn("teleport에서 지원하지 않는 속성 'speed'", rendered)

    def test_enter_space_requires_space_location(self) -> None:
        program = parse('''event interact {
  page default {
    id "travel/not_space" await enter_space player relative(x: 0, y: 0, z: -4) -> arrival
  }
}
''', "enter-space-relative.cves")

        diagnostics = validate(program)

        self.assertEqual(1, len(diagnostics))
        self.assertIn("space(...) 위치", diagnostics[0].message)
        self.assertEqual(3, diagnostics[0].span.start.line)

    def test_route_and_space_locations_require_anchor(self) -> None:
        source = '''event interact {
  page default {
    await teleport player route("cobbleventure:route/route_custom_01")
    await enter_space player space("cobbleventure:cave/mt_moon")
  }
}
'''

        diagnostics = validate(parse(source, "anchor-required.cves"))

        self.assertEqual(2, len(diagnostics))
        self.assertTrue(all("anchor 속성이 필요" in value.message for value in diagnostics))

    def test_global_anchor_rejects_nested_anchor_property(self) -> None:
        source = '''event interact {
  page default {
    await teleport player anchor("cobbleventure:event_anchor/world_spawn") {
      anchor: "nested"
    }
  }
}
'''

        diagnostics = validate(parse(source, "global-anchor.cves"))

        self.assertEqual(1, len(diagnostics))
        self.assertIn("하위 anchor 속성", diagnostics[0].message)

    def test_localized_name_view_is_distinct_from_plain_string(self) -> None:
        source = '''event interact {
  page default {
    let plain = "파이리"
    say npc "${plain|name}"
  }
}
'''
        diagnostics = validate(parse(source, "name-filter.cves"))

        self.assertEqual(1, len(diagnostics))
        self.assertIn("name 필터", diagnostics[0].message)

    def test_trigger_options_have_types_and_ranges(self) -> None:
        source = '''event interact(range: 0, once: "yes", scope: global) {
  page default {}
}
'''
        diagnostics = validate(parse(source, "trigger.cves"))
        rendered = "\n".join(value.message for value in diagnostics)

        self.assertEqual(3, len(diagnostics))
        self.assertIn("0보다 커야", rendered)
        self.assertIn("once는 bool", rendered)
        self.assertIn("scope은", rendered)

    def test_proximity_stage_relationships_are_validated(self) -> None:
        source = '''event proximity_enter(range: 6, group: "battle", stage: "warning") {
  page default {}
}
event proximity_enter(range: 9, group: "battle", after: "warning") {
  page default {}
}
event proximity_enter(range: 4, group: "battle", after: "missing") {
  page default {}
}
event proximity_enter(range: 12, group: "battle", stage: "warning") {
  page default {}
}
'''
        diagnostics = validate(parse(source, "proximity-stage.cves"))
        rendered = "\n".join(value.message for value in diagnostics)

        self.assertEqual(3, len(diagnostics))
        self.assertIn("중복 단계", rendered)
        self.assertIn("after 대상 단계", rendered)
        self.assertIn("선행 proximity 단계의 range", rendered)

    def test_proximity_outer_stage_can_lead_to_smaller_inner_stage(self) -> None:
        source = '''event proximity_enter(range: 9, group: "battle", stage: "warning") {
  page default {}
}
event proximity_enter(range: 6, group: "battle", after: "warning") {
  page default {}
}
'''

        self.assertEqual((), validate(parse(source, "proximity-stage-valid.cves")))

    def test_indexed_boundary_triggers_require_target_and_reject_range(self) -> None:
        source = '''event region_enter(range: 3) {
  page default {}
}
event anchor_step {
  page default {}
}
event building_enter {
  page default {}
}
event dimension_exit {
  page default {}
}
event flag_changed {
  page default {}
}
event item_used {
  page default {}
}
event battle_finished {
  page default {}
}
'''

        diagnostics = validate(parse(source, "indexed-trigger.cves"))
        rendered = "\n".join(value.message for value in diagnostics)

        self.assertEqual(8, len(diagnostics))
        self.assertIn("range 인수를 지원하지", rendered)
        self.assertEqual(7, rendered.count("target 인수가 필요"))

    def test_stable_ids_are_valid_and_unique_across_nested_blocks(self) -> None:
        source = '''event interact {
  page default {
    id "reward/item" if true {
      id "reward/item" stop
    }
    id "Invalid ID" stop
  }
}
'''
        diagnostics = validate(parse(source, "stable-invalid.cves"))
        rendered = "\n".join(value.message for value in diagnostics)

        self.assertEqual(2, len(diagnostics))
        self.assertIn("중복 안정 ID", rendered)
        self.assertIn("올바르지 않은 안정 ID", rendered)


if __name__ == "__main__":
    unittest.main()

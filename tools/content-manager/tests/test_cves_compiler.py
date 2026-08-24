import copy
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
CONTENT_MANAGER = ROOT / "tools" / "content-manager"
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
FIXTURE = Path(__file__).parent / "fixtures" / "professor_oak.cves"
SIGNAL_FIXTURE = Path(__file__).parent / "fixtures" / "server_signals.cves"
MOVEMENT_FIXTURE = Path(__file__).parent / "fixtures" / "movement_showcase.cves"
MAP_SELECTION_FIXTURE = Path(__file__).parent / "fixtures" / "map_selection.cves"
HEALING_EVENT = (
    PROJECT_ROOT / "content/events/cobbleventure/facilities/pokemon_center_nurse.cves"
)
sys.path.insert(0, str(CONTENT_MANAGER))

from cves import (  # noqa: E402
    CvesCompilationError,
    compile_program,
    load_project_catalog,
    parse,
)


class CvesCompilerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = load_project_catalog(
            PROJECT_ROOT,
            item_catalog=ROOT / "trainer-data" / "catalogs" / "cobblemon-items.json",
        )
        cls.program = parse(FIXTURE.read_text(encoding="utf-8"), str(FIXTURE))

    def test_professor_oak_compiles_to_deterministic_addressed_ir(self) -> None:
        first = compile_program(
            self.program,
            "cobbleventure:event_script/story/professor_oak",
            self.catalog,
        )
        second = compile_program(
            self.program,
            "cobbleventure:event_script/story/professor_oak",
            self.catalog,
        )
        instructions = first["events"][0]["instructions"]

        self.assertEqual(first, second)
        self.assertEqual(
            json.dumps(first, ensure_ascii=False, separators=(",", ":")),
            json.dumps(second, ensure_ascii=False, separators=(",", ":")),
        )
        self.assertEqual(list(range(len(instructions))), [value["address"] for value in instructions])
        self.assertEqual([0, 2, 12], [value["entry"] for value in first["events"][0]["pages"]])

    def test_healing_is_typed_await_without_cross_visit_idempotency(self) -> None:
        program = parse(
            HEALING_EVENT.read_text(encoding="utf-8"), str(HEALING_EVENT)
        )
        ir = compile_program(
            program,
            "cobbleventure:event_script/facilities/pokemon_center_nurse",
            self.catalog,
        )
        healing = next(
            value for value in ir["events"][0]["instructions"]
            if value.get("command") == "heal_party"
        )

        self.assertTrue(healing["await"])
        self.assertTrue(healing["await_explicit"])
        self.assertEqual("healing", healing["result"])
        self.assertEqual(healing["next"], healing["resume"])
        self.assertNotIn("operation_id", healing)

    def test_server_signal_fixture_preserves_typed_trigger_targets(self) -> None:
        program = parse(
            SIGNAL_FIXTURE.read_text(encoding="utf-8"), str(SIGNAL_FIXTURE)
        )
        ir = compile_program(
            program, "cobbleventure:event_script/test/server_signals", self.catalog
        )

        self.assertEqual(
            ["flag_changed", "item_used", "battle_finished"],
            [event["trigger"]["name"] for event in ir["events"]],
        )
        self.assertTrue(all(
            event["trigger"]["arguments"][0]["name"] == "target"
            for event in ir["events"]
        ))

    def test_movement_fixture_lowers_all_typed_recoverable_awaits(self) -> None:
        program = parse(
            MOVEMENT_FIXTURE.read_text(encoding="utf-8"), str(MOVEMENT_FIXTURE)
        )

        ir = compile_program(
            program, "cobbleventure:event_script/test/movement_showcase", self.catalog
        )
        movements = [
            value for value in ir["events"][0]["instructions"]
            if value["op"] == "command"
            and value["command"] in {"move", "teleport", "enter_space"}
        ]

        self.assertEqual(
            [
                "move", "teleport", "teleport", "teleport",
                "teleport", "enter_space", "teleport",
            ],
            [value["command"] for value in movements],
        )
        self.assertEqual(
            [
                "npc_movement", "absolute_movement", "anchor_movement",
                "route_movement", "dimension_movement", "space_movement",
                "global_anchor_movement",
            ],
            [value["result"] for value in movements],
        )
        self.assertEqual(
            [
                "relative", "position", "settlement", "route",
                "dimension", "space", "anchor",
            ],
            [value["arguments"][1]["value"]["callee"]["name"] for value in movements],
        )
        self.assertEqual(
            [
                "cobbleventure:event_script/test/movement_showcase/movement/npc_relative",
                "cobbleventure:event_script/test/movement_showcase/movement/player_absolute",
                "cobbleventure:event_script/test/movement_showcase/movement/starter_town",
                "cobbleventure:event_script/test/movement_showcase/movement/route_middle",
                "cobbleventure:event_script/test/movement_showcase/movement/world_spawn",
                "cobbleventure:event_script/test/movement_showcase/movement/mt_moon",
                "cobbleventure:event_script/test/movement_showcase/movement/global_anchor",
            ],
            [value["operation_id"] for value in movements],
        )
        self.assertTrue(all(value["await"] and value["resume"] == value["next"] for value in movements))
        branches = [value for value in ir["events"][0]["instructions"] if value["op"] == "branch"]
        self.assertEqual(7, len(branches))
        self.assertTrue(all(value["condition"]["operator"] == "!" for value in branches))

    def test_map_selection_result_is_a_typed_teleport_destination(self) -> None:
        program = parse(
            MAP_SELECTION_FIXTURE.read_text(encoding="utf-8"),
            str(MAP_SELECTION_FIXTURE),
        )

        ir = compile_program(
            program,
            "cobbleventure:event_script/test/map_selection",
            self.catalog,
        )
        commands = [
            value for value in ir["events"][0]["instructions"]
            if value["op"] == "command"
            and value["command"] in {"map_selection", "teleport"}
        ]
        selection, teleport = commands

        self.assertEqual("map_selection", selection["command"])
        self.assertEqual("destination", selection["result"])
        self.assertTrue(selection["await"])
        self.assertEqual(selection["next"], selection["resume"])
        self.assertEqual(
            "cobbleventure:event_script/test/map_selection/travel/select",
            selection["operation_id"],
        )
        self.assertEqual("teleport", teleport["command"])
        self.assertEqual("name", teleport["arguments"][1]["value"]["kind"])
        self.assertEqual("destination", teleport["arguments"][1]["value"]["name"])
        self.assertEqual(
            "cobbleventure:event_script/test/map_selection/travel/go",
            teleport["operation_id"],
        )
        dialogue = next(
            value for value in ir["events"][0]["instructions"]
            if value["op"] == "say"
            and "destination.name" in value["text"]["value"]
        )
        self.assertEqual(
            "${destination.name|josa:을/를} 목적지로 선택했어.",
            dialogue["text"]["value"],
        )

    def test_operation_ids_and_await_resume_addresses_are_explicit(self) -> None:
        ir = compile_program(
            self.program,
            "cobbleventure:event_script/story/professor_oak",
            self.catalog,
        )
        instructions = ir["events"][0]["instructions"]
        roulette = next(value for value in instructions if value["instruction_id"] == "first/starter_roulette")
        item = next(value for value in instructions if value["instruction_id"] == "first/give_pokedex")

        self.assertTrue(roulette["await"])
        self.assertTrue(roulette["await_explicit"])
        self.assertEqual(roulette["next"], roulette["resume"])
        self.assertTrue(item["await"])
        self.assertFalse(item["await_explicit"])
        self.assertEqual(item["next"], item["resume"])
        self.assertEqual(
            "cobbleventure:event_script/story/professor_oak/first/give_pokedex",
            item["operation_id"],
        )

    def test_professor_oak_uses_page_scoped_recoverable_pokedex_rewards(self) -> None:
        ir = compile_program(
            self.program,
            "cobbleventure:event_script/story/professor_oak",
            self.catalog,
        )
        instructions = ir["events"][0]["instructions"]
        rewards = [
            value for value in instructions
            if value.get("command") == "give_item"
        ]
        advanced_flow = [
            value for value in instructions
            if value["op"] in {"label", "jump", "call", "return"}
            and not value["instruction_id"].endswith(("then_end", "option0_end", "option1_end"))
        ]

        self.assertEqual(2, len(rewards))
        self.assertEqual(
            {"retry/give_pokedex", "first/give_pokedex"},
            {value["instruction_id"] for value in rewards},
        )
        self.assertEqual(0, len(advanced_flow))

    def test_missing_stable_id_on_side_effect_is_compile_error(self) -> None:
        program = parse('''event interact {
  page default {
    give_item "cobblemon:pokedex_red" count 1
  }
}
''', "missing-id.cves")

        with self.assertRaises(CvesCompilationError) as raised:
            compile_program(program, "cobbleventure:event_script/test/missing_id", self.catalog)

        self.assertEqual(1, len(raised.exception.diagnostics))
        self.assertIn("안정 ID", raised.exception.diagnostics[0].message)
        self.assertEqual((3, 5), (
            raised.exception.diagnostics[0].span.start.line,
            raised.exception.diagnostics[0].span.start.column,
        ))

    def test_battle_lowers_to_stable_explicit_await(self) -> None:
        program = parse('''event interact {
  page default {
    id "trainer/battle" await battle "cobbleventure:battle/ai_test" -> result
  }
}
''', "battle.cves")
        ir = compile_program(
            program, "cobbleventure:event_script/test/battle", self.catalog
        )
        battle = ir["events"][0]["instructions"][0]

        self.assertEqual("battle", battle["command"])
        self.assertTrue(battle["await"])
        self.assertTrue(battle["await_explicit"])
        self.assertEqual("result", battle["result"])
        self.assertEqual(battle["next"], battle["resume"])
        self.assertEqual(
            "cobbleventure:event_script/test/battle/trainer/battle",
            battle["operation_id"],
        )

    def test_teleport_lowers_typed_location_and_stable_explicit_await(self) -> None:
        program = parse('''event interact {
  page default {
    id "travel/leave_gate" await teleport player relative(x: 0, y: 0, z: -4) {
      safe_landing: required
      preload_chunks: true
      fade: none
    } -> travel
  }
}
''', "teleport.cves")
        ir = compile_program(
            program, "cobbleventure:event_script/test/teleport", self.catalog
        )
        teleport = ir["events"][0]["instructions"][0]

        self.assertEqual("teleport", teleport["command"])
        self.assertTrue(teleport["await"])
        self.assertTrue(teleport["await_explicit"])
        self.assertEqual(teleport["next"], teleport["resume"])
        self.assertEqual("travel", teleport["result"])
        self.assertEqual(
            "cobbleventure:event_script/test/teleport/travel/leave_gate",
            teleport["operation_id"],
        )
        destination = teleport["arguments"][1]["value"]
        self.assertEqual("call", destination["kind"])
        self.assertEqual("relative", destination["callee"]["name"])
        self.assertEqual(
            ["safe_landing", "preload_chunks", "fade"],
            [value["name"] for value in teleport["properties"]],
        )

    def test_enter_space_preserves_space_resource_and_anchor(self) -> None:
        program = parse('''event interact {
  page default {
    id "travel/mt_moon" await enter_space player space("cobbleventure:cave/mt_moon") {
      anchor: "west"
    } -> arrival
  }
}
''', "enter-space.cves")
        ir = compile_program(
            program, "cobbleventure:event_script/test/enter_space", self.catalog
        )
        command = ir["events"][0]["instructions"][0]

        self.assertEqual("enter_space", command["command"])
        self.assertTrue(command["await"])
        self.assertEqual("space", command["arguments"][1]["value"]["callee"]["name"])
        self.assertEqual(
            "cobbleventure:cave/mt_moon",
            command["arguments"][1]["value"]["arguments"][0]["value"]["value"],
        )
        self.assertEqual("anchor", command["properties"][0]["name"])

    def test_face_is_immediate_and_presentations_use_implicit_await(self) -> None:
        program = parse('''event interact {
  page default {
    face npc player
    id "scene/fade" fade black -> faded
    id "scene/wait" wait 0.25 -> waited
    id "scene/sound" sound "minecraft:block.note_block.pling" -> sounded
    id "scene/effect" effect "minecraft:happy_villager" -> effected
  }
}
''', "presentation.cves")
        ir = compile_program(
            program, "cobbleventure:event_script/test/presentation", self.catalog
        )
        commands = {
            value["command"]: value
            for value in ir["events"][0]["instructions"]
            if value["op"] == "command"
        }

        self.assertFalse(commands["face"]["await"])
        self.assertNotIn("operation_id", commands["face"])
        for name in ("fade", "wait", "sound", "effect"):
            command = commands[name]
            self.assertTrue(command["await"])
            self.assertFalse(command["await_explicit"])
            self.assertEqual(command["next"], command["resume"])
            self.assertEqual(
                f"cobbleventure:event_script/test/presentation/scene/{name}",
                command["operation_id"],
            )

    def test_if_choice_and_repeat_lower_to_explicit_targets(self) -> None:
        program = parse('''event interact {
  page default {
    let rounds = 2
    if money() >= 100 {
      choice "진행할까?" {
        "진행" { narrate "좋아." }
        "중단" { stop }
      }
    } else {
      narrate "돈이 부족하다."
    }
    repeat rounds {
      narrate "반복"
    }
  }
}
''', "control.cves")
        ir = compile_program(program, "cobbleventure:event_script/test/control")
        instructions = ir["events"][0]["instructions"]
        branch = next(value for value in instructions if value["op"] == "branch")
        choice = next(value for value in instructions if value["op"] == "choice")
        repeat = next(value for value in instructions if value["op"] == "repeat_begin")

        self.assertEqual("choice", instructions[branch["then"]]["op"])
        self.assertEqual("narrate", instructions[branch["else"]]["op"])
        self.assertEqual(2, len(choice["options"]))
        self.assertIs(True, choice["await"])
        self.assertTrue(all(instructions[value["target"]]["op"] in {"narrate", "command"} for value in choice["options"]))
        self.assertEqual("narrate", instructions[repeat["body"]]["op"])
        self.assertEqual("repeat_next", instructions[repeat["exit"] - 1]["op"])

    def test_label_jump_and_call_targets_are_resolved(self) -> None:
        program = parse('''event interact {
  page default {
    jump finish
    label routine
    return
    call routine
    label finish
    stop
  }
}
''', "labels.cves")
        ir = compile_program(program, "cobbleventure:event_script/test/labels")
        instructions = ir["events"][0]["instructions"]
        labels = {value["label"]: value["address"] for value in instructions if value["op"] == "label"}
        jump = next(value for value in instructions if value["op"] == "jump")
        call = next(value for value in instructions if value["op"] == "call")

        self.assertEqual(labels["finish"], jump["target"])
        self.assertEqual(labels["routine"], call["target"])
        self.assertEqual(call["address"] + 1, call["return_address"])

    def test_casino_cashier_compiles_bounded_number_input_and_exchange(self) -> None:
        source = (
            PROJECT_ROOT
            / "content/events/cobbleventure/facilities/casino_cashier.cves"
        )
        ir = compile_program(
            parse(source.read_text(encoding="utf-8"), str(source)),
            "cobbleventure:event_script/facilities/casino_cashier",
            self.catalog,
        )
        instructions = ir["events"][0]["instructions"]
        number_input = next(
            value for value in instructions if value.get("command") == "number_input"
        )
        exchange = next(
            value for value in instructions if value.get("command") == "server_command"
        )

        self.assertTrue(number_input["await"])
        self.assertEqual("amount", number_input["result"])
        self.assertEqual(
            ["min", "max"],
            [value["name"] for value in number_input["properties"]],
        )
        self.assertEqual(
            "cobbleventure:event_script/facilities/casino_cashier/exchange/perform",
            exchange["operation_id"],
        )
        self.assertEqual("exchanged", exchange["result"])

    def test_source_map_changes_do_not_change_canonical_source_digest(self) -> None:
        canonical = compile_program(
            self.program,
            "cobbleventure:event_script/story/professor_oak",
            self.catalog,
        )
        shifted_source = "\n\n" + FIXTURE.read_text(encoding="utf-8")
        shifted = compile_program(
            parse(shifted_source, str(FIXTURE)),
            "cobbleventure:event_script/story/professor_oak",
            self.catalog,
        )

        self.assertEqual(canonical["source_digest"], shifted["source_digest"])
        self.assertNotEqual(
            canonical["events"][0]["source_map"][0]["span"],
            shifted["events"][0]["source_map"][0]["span"],
        )
        without_maps = copy.deepcopy(canonical)
        shifted_without_maps = copy.deepcopy(shifted)
        without_maps["events"][0].pop("source_map")
        shifted_without_maps["events"][0].pop("source_map")
        self.assertEqual(without_maps, shifted_without_maps)


if __name__ == "__main__":
    unittest.main()

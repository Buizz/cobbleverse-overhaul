import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[3]
CONTENT_MANAGER = ROOT / "tools" / "content-manager"
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
sys.path.insert(0, str(CONTENT_MANAGER))

from cves import (  # noqa: E402
    ResourceCatalog,
    ResourceKind,
    load_project_catalog,
    parse,
    validate,
)


class CvesProjectCatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = load_project_catalog(
            PROJECT_ROOT,
            item_catalog=ROOT / "trainer-data" / "catalogs" / "cobblemon-items.json",
        )

    def test_loads_authoritative_project_resources_and_anchors(self) -> None:
        self.assertTrue(self.catalog.contains(ResourceKind.ITEM, "cobblemon:pokedex_red"))
        self.assertTrue(self.catalog.contains(ResourceKind.BATTLE, "cobbleventure:battle/ai_test"))
        self.assertTrue(self.catalog.contains(ResourceKind.BADGE, "cobbleventure:badge/kanto/boulder"))
        self.assertTrue(self.catalog.contains(
            ResourceKind.LOOT, "cobbleventure:trainer/ai_test_rewards"
        ))
        self.assertTrue(self.catalog.contains(ResourceKind.FLAG, "cobbleventure:flag/story/starter_received"))
        self.assertTrue(self.catalog.contains(ResourceKind.SETTLEMENT, "cobbleventure:settlement/starter_town"))
        self.assertTrue(self.catalog.contains_anchor(
            ResourceKind.SETTLEMENT,
            "cobbleventure:settlement/starter_town",
            "town_square",
        ))
        self.assertTrue(self.catalog.contains(ResourceKind.ROUTE, "cobbleventure:route/route_custom_01"))
        self.assertTrue(self.catalog.contains_anchor(
            ResourceKind.ROUTE,
            "cobbleventure:route/route_custom_01",
            "middle",
        ))
        self.assertTrue(self.catalog.contains_anchor(
            ResourceKind.SPACE,
            "cobbleventure:cave/mt_moon",
            "west_gallery",
        ))
        self.assertTrue(self.catalog.contains_anchor(
            ResourceKind.SPACE,
            "cobbleventure:building/starter_town/facility_laboratory_1",
            "room_1/professor_oak",
        ))
        self.assertTrue(self.catalog.contains(
            ResourceKind.BUILDING,
            "cobbleventure:building/starter_town/facility_laboratory_1",
        ))
        self.assertTrue(self.catalog.contains_anchor(
            ResourceKind.DIMENSION,
            "cobbleventure:generation_1",
            "world/spawn",
        ))
        self.assertTrue(self.catalog.contains(
            ResourceKind.EVENT_REGION, "cobbleventure:event_region/starter_origin"
        ))
        self.assertTrue(self.catalog.contains(
            ResourceKind.EVENT_ANCHOR, "cobbleventure:event_anchor/world_spawn"
        ))

    def test_dimension_anchor_catalog_rejects_unknown_anchor(self) -> None:
        source = '''event interact {
  page default {
    await teleport player dimension("cobbleventure:generation_1") {
      anchor: "world/missing"
    }
  }
}
'''

        diagnostics = validate(parse(source, "dimension-anchor.cves"), self.catalog)

        self.assertEqual(1, len(diagnostics))
        self.assertIn("없는 앵커", diagnostics[0].message)
        self.assertEqual("world/missing", diagnostics[0].token)

    def test_global_anchor_uses_authoritative_event_anchor_catalog(self) -> None:
        valid = '''event interact {
  page default {
    await teleport player anchor("cobbleventure:event_anchor/world_spawn")
  }
}
'''
        missing = valid.replace("world_spawn", "missing")

        self.assertEqual((), validate(parse(valid, "global-anchor.cves"), self.catalog))
        diagnostics = validate(parse(missing, "missing-global-anchor.cves"), self.catalog)
        self.assertEqual(1, len(diagnostics))
        self.assertIn("event_anchor 카탈로그에 없는", diagnostics[0].message)

    def test_dimension_destination_requires_explicit_anchor(self) -> None:
        source = '''event interact {
  page default {
    await teleport player dimension("cobbleventure:generation_1")
  }
}
'''

        diagnostics = validate(parse(source, "dimension-required-anchor.cves"), self.catalog)

        self.assertEqual(1, len(diagnostics))
        self.assertIn("anchor 속성이 필요", diagnostics[0].message)

    def test_indexed_trigger_targets_are_checked_against_boundary_catalog(self) -> None:
        source = '''event region_enter(target: "cobbleventure:event_region/missing") {
  page default {}
}
event anchor_step(target: "cobbleventure:event_anchor/missing") {
  page default {}
}
'''

        diagnostics = validate(parse(source, "event-boundary-target.cves"), self.catalog)
        rendered = "\n".join(value.message for value in diagnostics)

        self.assertEqual(2, len(diagnostics))
        self.assertIn("event_region 카탈로그에 없는", rendered)
        self.assertIn("event_anchor 카탈로그에 없는", rendered)

    def test_building_and_dimension_trigger_targets_use_typed_catalogs(self) -> None:
        source = '''event building_enter(target: "cobbleventure:building/missing") {
  page default {}
}
event dimension_exit(target: "cobbleventure:missing_dimension") {
  page default {}
}
'''

        diagnostics = validate(parse(source, "space-trigger-target.cves"), self.catalog)
        rendered = "\n".join(value.message for value in diagnostics)

        self.assertEqual(2, len(diagnostics))
        self.assertIn("building 카탈로그에 없는", rendered)
        self.assertIn("dimension 카탈로그에 없는", rendered)

    def test_state_signal_targets_use_flag_item_and_battle_catalogs(self) -> None:
        source = '''event flag_changed(target: "cobbleventure:flag/story/missing") {
  page default {}
}
event item_used(target: "cobblemon:missing_item") {
  page default {}
}
event battle_finished(target: "cobbleventure:battle/missing") {
  page default {}
}
'''

        diagnostics = validate(parse(source, "signal-trigger-target.cves"), self.catalog)
        rendered = "\n".join(value.message for value in diagnostics)

        self.assertEqual(3, len(diagnostics))
        self.assertIn("flag 카탈로그에 없는", rendered)
        self.assertIn("item 카탈로그에 없는", rendered)
        self.assertIn("battle 카탈로그에 없는", rendered)

    def test_professor_oak_fixture_passes_project_cross_reference_validation(self) -> None:
        path = Path(__file__).parent / "fixtures" / "professor_oak.cves"
        program = parse(path.read_text(encoding="utf-8"), str(path))

        self.assertEqual((), validate(program, self.catalog))

    def test_server_signal_fixture_passes_project_cross_reference_validation(self) -> None:
        path = Path(__file__).parent / "fixtures" / "server_signals.cves"
        program = parse(path.read_text(encoding="utf-8"), str(path))

        self.assertEqual((), validate(program, self.catalog))

    def test_item_result_exposes_optional_failure_reason(self) -> None:
        source = '''event interact {
  page default {
    give_loot "cobbleventure:trainer/ai_test_rewards" count 1 -> reward
    say npc "실패 사유: ${reward.failure_reason}"
  }
}
'''

        self.assertEqual((), validate(parse(source, "loot-result.cves"), self.catalog))

    def test_reports_missing_resources_and_known_location_anchor(self) -> None:
        catalog = ResourceCatalog()
        catalog.complete_kinds.update({
            ResourceKind.ITEM,
            ResourceKind.BATTLE,
            ResourceKind.SETTLEMENT,
            ResourceKind.LOOT,
        })
        catalog.add_anchors(
            ResourceKind.SETTLEMENT,
            "cobbleventure:settlement/test_town",
            {"town_square"},
        )
        source = '''event interact {
  page default {
    await battle "cobbleventure:battle/missing"
    give_item "cobblemon:missing_item" count 1
    give_loot "cobbleventure:missing_loot" count 1
    await teleport player settlement("cobbleventure:settlement/test_town") {
      anchor: "missing_anchor"
    }
  }
}
'''

        diagnostics = validate(parse(source, "catalog.cves"), catalog)
        rendered = "\n".join(value.render() for value in diagnostics)

        self.assertEqual(4, len(diagnostics))
        self.assertIn("battle 카탈로그에 없는", rendered)
        self.assertIn("item 카탈로그에 없는", rendered)
        self.assertIn("loot 카탈로그에 없는", rendered)
        self.assertIn("없는 앵커", rendered)
        self.assertIn("catalog.cves:7:7", rendered)

    def test_route_builtin_anchor_is_authoritative(self) -> None:
        source = '''event interact {
  page default {
    await teleport player route("cobbleventure:route/route_custom_01") {
      anchor: "quarter"
    }
  }
}
'''

        diagnostics = validate(parse(source, "route-anchor.cves"), self.catalog)

        self.assertEqual(1, len(diagnostics))
        self.assertIn("없는 앵커", diagnostics[0].message)
        self.assertEqual("quarter", diagnostics[0].token)

    def test_building_instance_anchor_is_authoritative(self) -> None:
        source = '''event interact {
  page default {
    await enter_space player space("cobbleventure:building/starter_town/facility_laboratory_1") {
      anchor: "room_1/missing"
    }
  }
}
'''

        diagnostics = validate(parse(source, "building-anchor.cves"), self.catalog)

        self.assertEqual(1, len(diagnostics))
        self.assertIn("없는 앵커", diagnostics[0].message)
        self.assertEqual("room_1/missing", diagnostics[0].token)

    def test_incomplete_resource_domain_does_not_create_false_missing_error(self) -> None:
        catalog = ResourceCatalog()
        source = '''event interact {
  page default {
    sound "external_mod:unknown_to_project"
    await enter_space player space("another_mod:instance") {
      anchor: "entry"
    }
  }
}
'''

        self.assertEqual((), validate(parse(source, "partial.cves"), catalog))

    def test_loot_table_source_requires_namespace_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            source = project / "content/loot_tables/flat.json"
            source.parent.mkdir(parents=True)
            source.write_text("{}\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "<namespace>/<path>.json"):
                load_project_catalog(project)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from cves import CvesEditorConflict, encode_program, load_script, parse, save_script
from cves.library import list_library, save_metadata, script_details, validate_metadata

SOURCE = 'event interact { page default { say npc "안녕" } }\n'
PATH = "test/story/welcome.cves"
ID = "test:event_script/story/welcome"
METADATA = {"schema_version": 1, "display_name": "인사", "description": "첫 만남",
            "category": "system", "tags": ["공통", "튜토리얼", "공통"]}


class LibraryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "content/events" / PATH
        self.source.parent.mkdir(parents=True)
        self.source.write_text(SOURCE, encoding="utf-8")

    def npc(self, name="one", authoring="custom"):
        target = self.root / f"content/source/story/{name}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps({"id": f"test:npc/{name}", "name": {"ko_kr": name},
                                     "event_runtime": {"engine": "cves_v5", "authoring": authoring, "script_id": ID}}), encoding="utf-8")
        return target

    def test_listing_without_metadata_does_not_write_files(self):
        before = sorted(self.root.rglob("*"))
        items = list_library(self.root)
        self.assertEqual(items[0]["name"], "welcome")
        self.assertEqual(items[0]["usages"], [])
        self.assertIsNone(items[0]["metadata_digest"])
        self.assertEqual(before, sorted(self.root.rglob("*")))

    def test_metadata_is_deterministic_conflict_safe_and_does_not_modify_source(self):
        source = self.source.read_bytes()
        first = save_metadata(self.root, PATH, METADATA, None)
        self.assertEqual(first["metadata"]["tags"], ["공통", "튜토리얼"])
        with self.assertRaises(CvesEditorConflict):
            save_metadata(self.root, PATH, METADATA, None)
        second = save_metadata(self.root, PATH, METADATA, first["metadata_digest"])
        self.assertEqual(first, second)
        self.assertEqual(source, self.source.read_bytes())
        self.assertEqual(list_library(self.root)[0]["name"], "인사")

    def test_invalid_metadata_and_traversal(self):
        for field, value in (("category", "bogus"), ("tags", "string"), ("tags", [""]),
                             ("display_name", 7), ("schema_version", True), ("managed", True)):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                validate_metadata({**METADATA, field: value})
        for path in ("../outside.cves", "test/../../outside.cves", "test\\outside.cves"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                save_metadata(self.root, path, METADATA, None)
        with self.assertRaises(ValueError):
            save_metadata(self.root, "test/missing.cves", METADATA, None)

    def test_usage_deduplicates_matching_binding_but_preserves_stale_binding(self):
        self.npc()
        binding = self.root / "content/event-bindings/test/story/one.json"
        binding.parent.mkdir(parents=True)
        binding.write_text(json.dumps({"script_id": ID}), encoding="utf-8")
        self.assertEqual(len(script_details(self.root, PATH)["usages"]), 1)
        binding.write_text(json.dumps({"script_id": "test:event_script/other"}), encoding="utf-8")
        self.assertEqual(script_details(self.root, "test/other.cves")["usages"][0]["kind"], "binding")

    def test_preset_source_protected_but_metadata_and_copy_allowed(self):
        self.npc(authoring="preset")
        document = load_script(self.root, PATH)
        with self.assertRaisesRegex(ValueError, "프리셋 관리"):
            save_script(self.root, PATH, document["ast"], document["digest"])
        self.assertEqual(self.source.read_text(encoding="utf-8"), SOURCE)
        save_metadata(self.root, PATH, METADATA, None)
        self.assertTrue(save_script(self.root, "test/copy.cves", document["ast"], None)["saved"])
        with self.assertRaises(CvesEditorConflict):
            save_script(self.root, "test/copy.cves", document["ast"], None)

    def test_shared_event_requires_current_usage_confirmation(self):
        self.npc("one")
        self.npc("two")
        document = load_script(self.root, PATH)
        first = script_details(self.root, PATH)
        with self.assertRaises(CvesEditorConflict):
            save_script(self.root, PATH, document["ast"], document["digest"])
        self.npc("three")
        with self.assertRaises(CvesEditorConflict):
            save_script(self.root, PATH, document["ast"], document["digest"], usage_digest=first["usage_digest"])
        current = script_details(self.root, PATH)
        saved = save_script(self.root, PATH, document["ast"], document["digest"], usage_digest=current["usage_digest"])
        self.assertTrue(saved["saved"])

    def test_preset_conversion_unlocks_only_after_npc_saved(self):
        npc = self.npc(authoring="preset")
        self.assertTrue(script_details(self.root, PATH)["managed"])
        data = json.loads(npc.read_text())
        data["event_runtime"]["authoring"] = "custom"
        self.assertTrue(script_details(self.root, PATH)["managed"])
        npc.write_text(json.dumps(data), encoding="utf-8")
        self.assertFalse(script_details(self.root, PATH)["managed"])


if __name__ == "__main__":
    unittest.main()

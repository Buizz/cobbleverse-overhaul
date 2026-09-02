from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from test_quest_system import content_manager, quest_document
from cves import encode_program, load_script, parse, save_script
from cves.compiler import compile_program
from cves.formatter import format_program
from cves.library import script_details
from cves.quest_hooks import validate_references

SCRIPT_PATH = 'test/quest/intro.cves'
HOOK = {'script_id': 'test:event_script/quest/intro', 'npc_id': 'test:npc/oak'}
SOURCE = (Path(__file__).parent / 'fixtures/quest_hook.cves').read_text(encoding='utf-8')


class QuestHookTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / 'content/events' / SCRIPT_PATH
        self.source.parent.mkdir(parents=True)
        self.source.write_text(SOURCE, encoding='utf-8')
        self.npc = self.root / 'content/source/story/oak.json'
        self.npc.parent.mkdir(parents=True)
        self.npc.write_text(json.dumps({'id': HOOK['npc_id']}), encoding='utf-8')
        self.document = quest_document()
        self.document['event_hooks'] = {'on_accept': dict(HOOK), 'on_complete': dict(HOOK)}
        self.document['objectives'][0]['on_complete'] = dict(HOOK)
        self.quest_path = 'content/quests/cobbleventure/main/get_cut.json'

    def test_quest_trigger_roundtrip_and_compilation(self):
        program = parse(SOURCE, 'quest_hook.cves')
        self.assertEqual(program, parse(format_program(program)))
        compiled = compile_program(program, HOOK['script_id'])
        self.assertEqual('quest', compiled['events'][0]['trigger']['name'])
        self.assertEqual([], compiled['events'][0]['trigger']['arguments'])

    def test_invalid_trigger_arguments_are_diagnosed(self):
        from cves.semantic import validate
        self.assertTrue(validate(parse(SOURCE.replace('event quest', 'event quest(range: 4)'))))

    def test_save_roundtrip_and_all_three_usages(self):
        target, issues = content_manager._save_document(self.root, 'quests', self.quest_path, self.document)
        self.assertIsNotNone(target, issues)
        self.assertFalse(any(issue.level == 'error' for issue in issues), issues)
        self.assertEqual(self.document, json.loads(target.read_text(encoding='utf-8')))
        details = script_details(self.root, SCRIPT_PATH)
        self.assertTrue(details['quest_compatible'])
        self.assertEqual(3, len(details['usages']))
        self.assertEqual({'quest'}, {usage['kind'] for usage in details['usages']})

    def test_bad_reference_does_not_overwrite_saved_quest(self):
        target, _ = content_manager._save_document(self.root, 'quests', self.quest_path, self.document)
        before = target.read_bytes()
        for field, value in [('npc_id', 'test:npc/missing'), ('script_id', 'test:event_script/missing')]:
            with self.subTest(field=field):
                invalid = copy.deepcopy(self.document)
                invalid['event_hooks']['on_accept'][field] = value
                result, issues = content_manager._save_document(self.root, 'quests', self.quest_path, invalid)
                self.assertEqual(target, result)
                self.assertTrue(any('event_hooks.on_accept' in issue.message for issue in issues))
                self.assertEqual(before, target.read_bytes())

    def test_shape_validation_rejects_missing_fields_unknown_keys_and_traversal(self):
        for hook in ({'script_id': HOOK['script_id']}, {**HOOK, 'extra': True},
                     {**HOOK, 'npc_id': '../oak'}, {**HOOK, 'script_id': 'test:event_script/../intro'}):
            with self.subTest(hook=hook):
                self.document['event_hooks']['on_accept'] = hook
                result, issues = content_manager._save_document(self.root, 'quests', self.quest_path, self.document)
                self.assertFalse(result.exists())
                self.assertTrue(any(issue.level == 'error' for issue in issues))

    def test_requires_exactly_one_explicit_quest_entry(self):
        for source in (SOURCE.replace('event quest', 'event interact'), SOURCE + '\n' + SOURCE,
                       SOURCE.replace('event quest', 'event quest(once: true)'), 'event quest {'):
            with self.subTest(source=source):
                self.source.write_text(source, encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'event_hooks.on_accept'):
                    validate_references(self.root, self.document)

    def test_managed_preset_cannot_be_used_as_hook(self):
        self.npc.write_text(json.dumps({'id': HOOK['npc_id'], 'event_runtime': {
            'engine': 'cves_v5', 'authoring': 'preset', 'script_id': HOOK['script_id']}}), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, '프리셋 관리'):
            validate_references(self.root, self.document)

    def test_referenced_script_cannot_lose_quest_entry(self):
        content_manager._save_document(self.root, 'quests', self.quest_path, self.document)
        details = script_details(self.root, SCRIPT_PATH)
        document = load_script(self.root, SCRIPT_PATH)
        changed = encode_program(parse(SOURCE.replace('event quest', 'event interact')))
        with self.assertRaisesRegex(ValueError, '퀘스트가 사용 중'):
            save_script(self.root, SCRIPT_PATH, changed, document['digest'], usage_digest=details['usage_digest'])
        self.assertEqual(SOURCE, self.source.read_text(encoding='utf-8'))
        self.assertTrue(save_script(self.root, SCRIPT_PATH, document['ast'], document['digest'],
                                   usage_digest=details['usage_digest'])['saved'])


if __name__ == '__main__':
    unittest.main()

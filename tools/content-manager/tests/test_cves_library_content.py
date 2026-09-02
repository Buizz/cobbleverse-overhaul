"""Authored library metadata must cover the shipped scripts, never change ownership."""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from cves.library import list_library, validate_metadata
from cves import encode_program, parse
from cves.editor import validate_ast
from cves.catalog import ResourceCatalog

PROJECT = Path(__file__).resolve().parents[3] / 'content-projects/cobbleventure-main'


class LibraryContentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.items = list_library(PROJECT)
        cls.by_path = {item['path']: item for item in cls.items}

    def test_all_shipped_scripts_have_valid_searchable_metadata(self):
        self.assertTrue(self.items)
        for item in self.items:
            with self.subTest(path=item['path']):
                target = PROJECT / 'content/event-metadata' / Path(item['path']).with_suffix('.json')
                raw = json.loads(target.read_text(encoding='utf-8'))
                self.assertEqual(raw, validate_metadata(raw))
                self.assertTrue(raw['display_name'])
                self.assertTrue(raw['description'])
                self.assertTrue(raw['tags'])
                self.assertIsNotNone(item['metadata_digest'])
                self.assertEqual(item['managed'], any(usage['managed'] for usage in item['usages']))

    def test_metadata_has_no_orphaned_sources(self):
        for file in (PROJECT / 'content/event-metadata').rglob('*.json'):
            source = PROJECT / 'content/events' / file.relative_to(PROJECT / 'content/event-metadata').with_suffix('.cves')
            self.assertTrue(source.is_file(), str(file))

    def test_roles_are_not_confused_with_execution_triggers_or_authoring_mode(self):
        examples = {
            'facilities/pokemon_center_nurse': ('system', '회복'),
            'facilities/blackjack_dealer': ('system', '블랙잭'),
            'rewards/feature_pc_technician': ('system', 'PC'),
            'story/professor_oak': ('npc', '스토리'),
            'story/starter_town_gatekeeper_minho': ('region', '관문'),
            'gym_leaders/brock': ('npc', '관장전'),
            'samples/sample_potion_giver': ('common', '예제'),
        }
        for path, (category, tag) in examples.items():
            metadata = self.by_path[f'cobbleventure/{path}.cves']['metadata']
            self.assertEqual(metadata['category'], category)
            self.assertIn(tag, metadata['tags'])

    def test_new_event_shapes_round_trip_through_real_editor_validation(self):
        ast = encode_program(parse('event interact { page default {} }'), include_spans=False)
        for trigger, arguments in [
            ('proximity_enter', [{'node':'argument', 'name':'range', 'value':{'node':'literal', 'value':'0.25', 'value_type':'decimal'}}]),
            ('flag_changed', [{'node':'argument', 'name':'target', 'value':{'node':'literal', 'value':'test:ready', 'value_type':'string'}}]),
            ('quest', []),
        ]:
            ast['root']['events'].append({'node':'event', 'trigger':{'node':'trigger', 'name':trigger, 'arguments':arguments},
                                         'pages':[{'node':'page', 'condition':None, 'block':{'node':'block', 'statements':[]}}]})
        document = validate_ast(ast, 'test/new_events.cves', ResourceCatalog())
        self.assertEqual(len(parse(document['source']).events), 4)
        self.assertIn('range: 0.25', document['source'])


if __name__ == '__main__':
    unittest.main()

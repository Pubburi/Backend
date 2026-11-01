import json
import os
from pathlib import Path
import tempfile
import unittest

from smoke_search import outbox_fixture
from search_index import SearchError


class OutboxSmokeFixtureTests(unittest.TestCase):
    def test_only_a_private_single_product_business_history_ending_in_tombstone_is_accepted(self):
        original = Path.cwd()
        with tempfile.TemporaryDirectory() as temp:
            try:
                os.chdir(temp); Path('.pubburi').mkdir()
                source = Path('.pubburi/events.json')
                live = {'productId': 7, 'sourceVersion': 1, 'deleted': False, 'document': {
                    'name': 'Authored fixture', 'type': 'fixture', 'priceKrw': 1000, 'abvPercent': 5.555,
                    'orderCount': 0, 'tasteTags': None, 'aromaTags': []}}
                deleted = dict(live, sourceVersion=2, deleted=True, document=None)
                wire = {'schemaVersion': 1, 'dataKind': 'backend_product_events', 'events': [live, deleted]}
                source.write_text(json.dumps(wire))
                frozen = Path('.pubburi/frozen.json')
                self.assertEqual(outbox_fixture(source, frozen)[-1]['source_version'], 2)
                self.assertEqual(frozen.read_bytes(), source.read_bytes())
                invalid = [dict(wire, dataKind='authored_fixture'), dict(wire, events=[live]),
                           dict(wire, events=[deleted, live]), dict(wire, events=[live, dict(deleted, productId=8)]),
                           dict(wire, events=[live, dict(deleted, sourceVersion=1)])]
                for value in invalid:
                    source.write_text(json.dumps(value))
                    with self.assertRaises(SearchError): outbox_fixture(source)
                Path('outside.json').write_text(json.dumps(wire))
                with self.assertRaises(ValueError): outbox_fixture('outside.json')
                source.unlink(); source.symlink_to(Path('outside.json').resolve())
                with self.assertRaises(ValueError): outbox_fixture(source)
            finally:
                os.chdir(original)


if __name__ == '__main__': unittest.main()

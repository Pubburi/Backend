import os
import sys
import unittest
from unittest.mock import Mock, patch

from search_cutover import READ_ALIAS, READ_DEFINITION, main, move_alias, read_alias_index
from search_index import Engine, SearchError


class SearchCutoverTests(unittest.TestCase):
    def test_fixed_alias_requires_exactly_one_filtered_nonwriter(self):
        http = Mock()
        index = "pubburi-products-v1-old"
        valid = {"aliases": {READ_ALIAS: READ_DEFINITION}}
        http.request.return_value = 200, {index: valid}
        self.assertEqual(read_alias_index(http), index)
        for invalid in ({index: valid, "pubburi-products-v1-other": valid},
                        {index: {"aliases": {READ_ALIAS: {**READ_DEFINITION, "is_write_index": True}}}},
                        {index: {"aliases": {READ_ALIAS: {**READ_DEFINITION, "search_routing": "tenant"}}}}):
            http.request.return_value = 200, invalid
            with self.assertRaisesRegex(SearchError, "cutover_single_filtered_read_alias_required"):
                read_alias_index(http)
        with self.assertRaises(SearchError):
            Engine(http, READ_ALIAS)

    def test_alias_move_uses_a_single_atomic_request_and_requires_acknowledgement(self):
        http = Mock()
        old, new = "pubburi-products-v1-old", "pubburi-products-v1-new"
        http.request.return_value = 200, {"acknowledged": True}
        move_alias(http, old, new)
        http.request.assert_called_once_with("POST", "/_aliases", {"actions": [
            {"remove": {"index": old, "alias": READ_ALIAS, "must_exist": True}},
            {"add": {"index": new, "alias": READ_ALIAS, **READ_DEFINITION}},
        ]})
        http.request.return_value = 200, {"acknowledged": False}
        with self.assertRaisesRegex(SearchError, "cutover_alias_ack_required"):
            move_alias(http, old, new)

    def test_cli_without_explicit_stopped_writer_marker_performs_no_access(self):
        args = ["search_cutover.py", "--endpoint", "http://127.0.0.1:9200", "--source-id", "authored",
                "--cluster-uuid", "cluster", "--old-index", "old", "--old-uuid", "old",
                "--new-index", "new", "--new-uuid", "new"]
        with patch.object(sys, "argv", args), patch.dict(os.environ, {"PUBBURI_SEARCH_CUTOVER_MUTATING": "1"}, clear=True), \
                patch("search_cutover.database_settings") as database, patch("search_cutover.Http") as engine, \
                patch("sys.stderr"):
            self.assertEqual(main(), 2)
            database.assert_not_called()
            engine.assert_not_called()

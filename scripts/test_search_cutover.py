import os
import sys
import unittest
from contextlib import nullcontext
from unittest.mock import ANY, DEFAULT, Mock, patch

from search_cutover import CutoverPlan, READ_ALIAS, READ_DEFINITION, main, move_alias, read_alias_index, run_cutover
from search_index import Engine, SearchError
from search_relay import encode_target_identity


class SearchCutoverTests(unittest.TestCase):
    def test_cutover_rechecks_the_bound_generation_when_a_physical_index_is_missing(self):
        plan = CutoverPlan(
            "76b33720-8b20-4ed4-809e-3ea301fab26f", "authored-cluster",
            "pubburi-products-v1-old", "old-uuid", "pubburi-products-v1-new", "new-uuid",
        )
        old = plan.identity(plan.old_index, plan.old_uuid)
        new = plan.identity(plan.new_index, plan.new_uuid)
        for bound, missing, error in (
            (old, old, "owned_strict_mapping_required"),
            (new, old, None),
            (new, new, "cutover_physical_generation_changed"),
        ):
            with self.subTest(bound=bound["index_name"], missing=missing["index_name"]):
                cursor, store = Mock(), Mock()
                store.transaction.return_value = nullcontext(cursor)
                encoded, fingerprint = encode_target_identity(bound)
                with patch.multiple("search_cutover", guarded_transaction=DEFAULT, lock_source=DEFAULT,
                        source_projection=DEFAULT, read_alias_index=DEFAULT, verify_generation=DEFAULT,
                        verify_stage=DEFAULT, move_alias=DEFAULT) as checks:
                    checks["lock_source"].return_value = {
                        "source_id": plan.source_id, "fingerprint": fingerprint, "identity": encoded,
                    }
                    checks["source_projection"].return_value = ([], {})
                    checks["read_alias_index"].return_value = bound["index_name"]

                    def generation(engine, expected):
                        self.assertEqual(engine.index, expected["index_name"])
                        if expected == missing:
                            raise SearchError(error or "retired_generation_must_not_be_read")

                    checks["verify_generation"].side_effect = generation
                    arguments = (store, Mock(), plan, "authored_ci", "authored-marker")
                    if error:
                        with self.assertRaisesRegex(SearchError, error):
                            run_cutover(*arguments)
                        checks["verify_stage"].assert_not_called()
                    else:
                        self.assertEqual(run_cutover(*arguments), {"status": "already_cut_over", "events": 0, "documents": 0})
                        checks["verify_stage"].assert_called_with(ANY, plan.new_index, {})
                    self.assertEqual({call.args[1]["index_name"] for call in checks["verify_generation"].call_args_list},
                                     {bound["index_name"]})
                    checks["move_alias"].assert_not_called()
                    cursor.execute.assert_not_called()

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

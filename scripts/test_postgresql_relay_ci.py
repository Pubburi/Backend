"""Unsafe CI fixture requests must fail before inspecting or mutating a database."""
from pathlib import Path
import tempfile
import json
import unittest
from unittest.mock import patch

from prepare_postgresql_relay_ci import prepare
from postgresql_relay_smoke import validate_business_events
from relay_smoke import event
from search_index import SearchError


class PostgreSQLRelayPreparationTests(unittest.TestCase):
    def test_missing_opt_in_role_collision_and_reused_business_database_never_reach_database(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / ".pubburi").mkdir()
            env = {"PUBBURI_PG_RELAY_CI": "1", "PUBBURI_PG_RELAY_USER": "relay", "PUBBURI_PG_RELAY_PASSWORD": "authored-only",
                "PUBBURI_PG_RELAY_DB_NAME": "queue_ci", "PUBBURI_PG_JPA_DB_NAME": "business_ci",
                "PUBBURI_PG_RELAY_BUSINESS_DB_NAME": "relay_business_ci",
                "PUBBURI_PG_RELAY_RUN_TOKEN": "authored-token-123", "PUBBURI_PG_RELAY_FIXTURE": str(root / ".pubburi/relay.json")}
            business = {"PUBBURI_PG_USER": "application", "PUBBURI_JPA_CONTROL_USER": "control", "PUBBURI_PG_DB_NAME": "relay_business_ci"}
            with patch("prepare_postgresql_relay_ci.ROOT", root), patch("prepare_postgresql_relay_ci.verify_owned_postgres") as inspect:
                for changes in ({"PUBBURI_PG_RELAY_CI": "0"}, {"PUBBURI_PG_RELAY_USER": "control"},
                                {"PUBBURI_PG_RELAY_USER": "application"}, {"PUBBURI_PG_RELAY_DB_NAME": "business_ci"},
                                {"PUBBURI_PG_RELAY_USER": "relay; unsafe"}, {"PUBBURI_PG_RELAY_PASSWORD": ""}):
                    with self.assertRaises(ValueError): prepare(dict(env, **changes), business, root)
                inspect.assert_not_called()

    def test_public_or_existing_private_output_never_changes_a_database_or_overwrites_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / ".pubburi").mkdir()
            existing = root / ".pubburi/relay.json"; existing.write_text("previous immutable evidence")
            env = {"PUBBURI_PG_RELAY_CI": "1", "PUBBURI_PG_RELAY_USER": "relay", "PUBBURI_PG_RELAY_PASSWORD": "authored-only",
                "PUBBURI_PG_RELAY_DB_NAME": "queue_ci", "PUBBURI_PG_RELAY_RUN_TOKEN": "authored-token-123",
                "PUBBURI_PG_RELAY_BUSINESS_DB_NAME": "relay_business_ci"}
            business = {"PUBBURI_PG_USER": "application", "PUBBURI_JPA_CONTROL_USER": "control", "PUBBURI_PG_DB_NAME": "relay_business_ci"}
            with patch("prepare_postgresql_relay_ci.ROOT", root), patch("prepare_postgresql_relay_ci.verify_owned_postgres") as inspect:
                for path in (root / "public.json", existing):
                    with self.assertRaises(ValueError): prepare(dict(env, PUBBURI_PG_RELAY_FIXTURE=str(path)), business, root)
                inspect.assert_not_called()
            self.assertEqual(existing.read_text(), "previous immutable evidence")

    def test_business_relay_cannot_reuse_a_jpa_regression_database_before_any_access(self):
        env = {"PUBBURI_PG_RELAY_CI": "1", "PUBBURI_PG_RELAY_USER": "relay", "PUBBURI_PG_RELAY_PASSWORD": "authored-only",
            "PUBBURI_PG_RELAY_DB_NAME": "queue_ci", "PUBBURI_PG_RELAY_RUN_TOKEN": "authored-token-123",
            "PUBBURI_PG_RELAY_BUSINESS_DB_NAME": "relay_business_ci", "PUBBURI_PG_JPA_DB_NAME": "normal_ci"}
        business = {"PUBBURI_PG_USER": "application", "PUBBURI_JPA_CONTROL_USER": "control", "PUBBURI_PG_DB_NAME": "normal_ci"}
        with patch("prepare_postgresql_relay_ci.verify_owned_postgres") as inspect:
            with self.assertRaisesRegex(ValueError, "postgresql_relay_ci_separate_business_required"):
                prepare(env, business, Path("unused"))
            # Even if both business selectors are mistyped, normal is still a reserved regression DB.
            with self.assertRaisesRegex(ValueError, "postgresql_relay_ci_separate_business_required"):
                prepare(dict(env, PUBBURI_PG_RELAY_BUSINESS_DB_NAME="normal_ci"), business, Path("unused"))
            inspect.assert_not_called()

    def test_storage_boundary_partial_document_is_rejected_without_filtering_real_wire(self):
        valid = event(7)
        row = {"product_id": 7, "source_version": 1, "deleted": False, "payload": json.dumps(valid)}
        validate_business_events([row])
        # Same deliberately incomplete wire as the JPA raw-json storage test, which must remain intact.
        raw = '{ "sourceVersion" : 2, "productId" : 7, "deleted" : false, "document" : { "tasteTags" : null, "name" : "authored" } }'
        for invalid in (dict(row, source_version=2, payload=raw), dict(row, source_version=2)):
            with self.assertRaisesRegex(SearchError, "pg_relay_business_event_invalid"):
                validate_business_events([row, invalid])


if __name__ == "__main__": unittest.main()

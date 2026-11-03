"""Unsafe CI fixture requests must fail before inspecting or mutating a database."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from prepare_postgresql_relay_ci import prepare


class PostgreSQLRelayPreparationTests(unittest.TestCase):
    def test_missing_opt_in_role_collision_and_reused_business_database_never_reach_database(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / ".pubburi").mkdir()
            env = {"PUBBURI_PG_RELAY_CI": "1", "PUBBURI_PG_RELAY_USER": "relay", "PUBBURI_PG_RELAY_PASSWORD": "authored-only",
                "PUBBURI_PG_RELAY_DB_NAME": "queue_ci", "PUBBURI_PG_JPA_DB_NAME": "business_ci",
                "PUBBURI_PG_RELAY_RUN_TOKEN": "authored-token-123", "PUBBURI_PG_RELAY_FIXTURE": str(root / ".pubburi/relay.json")}
            business = {"PUBBURI_PG_USER": "application", "PUBBURI_JPA_CONTROL_USER": "control"}
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
                "PUBBURI_PG_RELAY_DB_NAME": "queue_ci", "PUBBURI_PG_RELAY_RUN_TOKEN": "authored-token-123"}
            business = {"PUBBURI_PG_USER": "application", "PUBBURI_JPA_CONTROL_USER": "control"}
            with patch("prepare_postgresql_relay_ci.ROOT", root), patch("prepare_postgresql_relay_ci.verify_owned_postgres") as inspect:
                for path in (root / "public.json", existing):
                    with self.assertRaises(ValueError): prepare(dict(env, PUBBURI_PG_RELAY_FIXTURE=str(path)), business, root)
                inspect.assert_not_called()
            self.assertEqual(existing.read_text(), "previous immutable evidence")


if __name__ == "__main__": unittest.main()

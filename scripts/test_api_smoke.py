"""Guard against running mutation smoke checks against an unrelated database."""

import json
import os
from pathlib import Path
import socket
import subprocess
import unittest
from unittest.mock import Mock

from api_smoke import ApiClient, require_disposable_database


class DisposableDatabaseGuardTests(unittest.TestCase):
    def setUp(self):
        self.environ = {
            "PUBBURI_SMOKE_MUTATING": "1",
            "PUBBURI_DB_NAME": "pubburi_ci",
            "PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:62928/pubburi_ci",
            "PUBBURI_DB_USER": "ci_user",
            "PUBBURI_DB_PASSWORD": "ci_test_only",
            "PUBBURI_SMOKE_CONTAINER_ID": "disposable-test-container",
            "PUBBURI_SMOKE_RUN_TOKEN": "test-run-marker-0123456789",
        }
        self.inspect = subprocess.CompletedProcess([], 0, json.dumps([{
            "NetworkSettings": {"Ports": {"3306/tcp": [{"HostIp": "127.0.0.1", "HostPort": "62928"}]}}
        }]))

    def test_unsafe_configuration_is_rejected_before_any_docker_or_database_access(self):
        cases = [
            {"PUBBURI_SMOKE_MUTATING": "0"},
            {"PUBBURI_DB_NAME": "pubburi"},
            {"PUBBURI_DB_URL": "jdbc:mysql://db.example.invalid:62928/pubburi_ci"},
            {"PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:62928/another_ci"},
            {"PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:62928/pubburi_ci?socketFactory=custom"},
            {"PUBBURI_SMOKE_RUN_TOKEN": ""},
        ]
        for overrides in cases:
            with self.subTest(overrides=list(overrides)):
                run = Mock()
                with self.assertRaises(RuntimeError):
                    require_disposable_database(dict(self.environ, **overrides), run=run)
                run.assert_not_called()

    def test_port_mismatch_never_reads_the_database_marker(self):
        run = Mock(return_value=self.inspect)
        with self.assertRaisesRegex(RuntimeError, "port"):
            require_disposable_database(dict(
                self.environ, PUBBURI_DB_URL="jdbc:mysql://127.0.0.1:3306/pubburi_ci"), run=run)
        self.assertEqual(run.call_count, 1)

    def test_missing_marker_is_rejected(self):
        run = Mock(side_effect=[self.inspect, subprocess.CompletedProcess([], 0, "")])
        with self.assertRaisesRegex(RuntimeError, "marker"):
            require_disposable_database(self.environ, run=run)

    def test_matching_container_schema_and_marker_are_accepted(self):
        token = self.environ["PUBBURI_SMOKE_RUN_TOKEN"]
        run = Mock(side_effect=[self.inspect, subprocess.CompletedProcess([], 0, token + "\n")])
        require_disposable_database(self.environ, run=run)
        self.assertEqual(run.call_count, 2)
        # Passwords travel through the environment, not process arguments/logs.
        self.assertNotIn(self.environ["PUBBURI_DB_PASSWORD"], str(run.call_args.args))

    def test_api_client_rejects_non_local_port_inputs(self):
        for port in ("0", "65536", "example.invalid", "9987/api"):
            with self.subTest(port=port), self.assertRaises(ValueError):
                ApiClient(port)

    def test_occupied_port_is_rejected_without_stopping_its_listener(self):
        root = Path(__file__).resolve().parents[1]
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            result = subprocess.run(
                ["bash", str(root / "scripts/smoke.sh")], cwd=root,
                env=dict(os.environ, PUBBURI_SERVER_PORT=str(port)),
                capture_output=True, text=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("port is occupied", result.stdout + result.stderr)
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                pass


if __name__ == "__main__":
    unittest.main()

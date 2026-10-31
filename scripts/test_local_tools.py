"""Safety and lifecycle checks for local setup and cooperative automation."""

import importlib.util
import json
import os
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("local_stack", Path(__file__).with_name("local_stack.py"))
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)
LEASE = Path(__file__).with_name("automation_lease.py")


class LocalToolsTests(unittest.TestCase):
    def test_lease_cannot_be_stolen_or_released_by_another_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            (repo / ".git").mkdir()

            def lease(command, owner):
                return subprocess.run([sys.executable, str(LEASE), command, "--repo", str(repo),
                                       "--owner", owner], capture_output=True, text=True)

            self.assertEqual(lease("acquire", "first").returncode, 0)
            self.assertNotEqual(lease("acquire", "second").returncode, 0)
            self.assertNotEqual(lease("release", "second").returncode, 0)
            record = repo / ".pubburi/automation-lease/owner.json"
            self.assertEqual(json.loads(record.read_text())["owner"], "first")
            self.assertEqual(lease("release", "first").returncode, 0)
            self.assertEqual(lease("acquire", "second").returncode, 0)

    def test_init_is_private_and_preserves_existing_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(local, "BACKEND", root), patch.object(local, "STATE", root / ".pubburi"):
                local.initialize()
                original = (root / ".env").read_bytes()
                local.initialize()
                self.assertEqual((root / ".env").read_bytes(), original)
                self.assertEqual((root / ".env").stat().st_mode & 0o777, 0o600)
                with patch.dict(os.environ, {}, clear=True):
                    env = local.environment()
                self.assertEqual(env["PUBBURI_SERVER_PORT"], "9987")
                self.assertEqual(env["VITE_API_PROXY_TARGET"], "http://127.0.0.1:9987")
                with patch.dict(os.environ, {"PUBBURI_DB_URL": "jdbc:mysql://remote.invalid:3306/pub"}, clear=True):
                    with self.assertRaisesRegex(RuntimeError, "loopback"):
                        local.environment()

    def test_occupied_port_is_preserved_and_startup_lock_is_released(self):
        with tempfile.TemporaryDirectory() as directory, socket.socket() as listener:
            root = Path(directory)
            frontend, recommendation = root / "Frontend", root / "Recommendation"
            (frontend / "node_modules").mkdir(parents=True)
            (recommendation / ".venv").mkdir(parents=True)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            env = {"PUBBURI_FRONTEND_PORT": str(listener.getsockname()[1])}
            with patch.object(local, "STATE", root / ".pubburi"), \
                 patch.object(local, "FRONTEND", frontend), \
                 patch.object(local, "RECOMMENDATION", recommendation), \
                 patch.object(local, "doctor"), patch.object(local, "compose") as compose:
                with self.assertRaises(OSError):
                    local.start(env, smoke=True)
                compose.assert_not_called()
                self.assertFalse((root / ".pubburi/local-stack-lock").exists())
                with socket.create_connection(listener.getsockname(), timeout=1):
                    pass

    def test_owned_process_is_stopped(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"],
                                 start_new_session=True)
        try:
            local.stop(child)
            self.assertIsNotNone(child.poll())
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()

    def test_reset_requires_explicit_confirmation_before_docker(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(local, "STATE", Path(directory)), \
             patch.object(local, "environment", return_value={}), \
             patch.object(local, "compose") as compose, \
             patch.object(sys, "argv", ["local_stack.py", "reset-db"]):
            with self.assertRaisesRegex(RuntimeError, "confirm-delete-local-data"):
                local.main()
            compose.assert_not_called()


if __name__ == "__main__":
    unittest.main()

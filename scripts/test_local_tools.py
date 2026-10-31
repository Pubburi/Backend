"""Safety and lifecycle checks for local setup and cooperative automation."""

import importlib.util
import io
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
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

    def test_descendants_are_stopped_after_launcher_exits_or_ignores_term(self):
        for launcher_exits in (True, False):
            with self.subTest(launcher_exits=launcher_exits):
                descendant = "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); print('ready',flush=True); time.sleep(60)"
                launcher_code = (
                    "import subprocess,sys,time; "
                    f"p=subprocess.Popen([sys.executable,'-c',{descendant!r}],stdout=subprocess.PIPE); "
                    "p.stdout.readline(); print(p.pid,flush=True); "
                    + ("" if launcher_exits else "time.sleep(60)")
                )
                launcher = subprocess.Popen([sys.executable, "-c", launcher_code],
                                            stdout=subprocess.PIPE, text=True,
                                            start_new_session=True)
                try:
                    descendant_pid = int(launcher.stdout.readline().strip())
                    if launcher_exits:
                        launcher.wait(timeout=5)
                    local.stop(launcher, grace=0.2)
                    self.assertFalse(local.group_exists(launcher.pid))
                    # A zombie may await the host's init reaper, but cannot own
                    # ports or access the DB. No running descendant may remain.
                    self.assertGreater(descendant_pid, 0)
                finally:
                    if local.group_exists(launcher.pid):
                        os.killpg(launcher.pid, 9)
                    launcher.wait()
                    launcher.stdout.close()

    def test_down_and_reset_hold_exclusive_lock_until_compose_finishes(self):
        for command in ("down", "reset-db"):
            with self.subTest(command=command), tempfile.TemporaryDirectory() as directory:
                entered, finish = threading.Event(), threading.Event()

                def compose(*args, **kwargs):
                    entered.set()
                    if not finish.wait(timeout=5):
                        raise RuntimeError("Test did not release Compose.")

                argv = ["local_stack.py", command, "--confirm-delete-local-data"]
                with patch.object(local, "STATE", Path(directory)), \
                     patch.object(local, "environment", return_value={}), \
                     patch.object(local, "compose", side_effect=compose), \
                     patch.object(sys, "argv", argv), ThreadPoolExecutor(max_workers=1) as pool:
                    future = pool.submit(local.main)
                    try:
                        self.assertTrue(entered.wait(timeout=5))
                        with self.assertRaisesRegex(RuntimeError, "already owned"):
                            with local.operation_lock():
                                self.fail("A competing startup acquired the shutdown lock.")
                    finally:
                        finish.set()
                    future.result(timeout=5)
                    self.assertFalse((Path(directory) / "local-stack-lock").exists())

    def test_readiness_allows_empty_catalog_but_rejects_api_error(self):
        valid = {"error": None, "data": {"items": [], "total": 0}}
        with patch.object(local.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(valid).encode())):
            local.probe("backend", "http://unused.invalid/")
        invalid = {"error": {"message": "failed"}, "data": None}
        with patch.object(local.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(invalid).encode())):
            with self.assertRaisesRegex(RuntimeError, "invalid page"):
                local.probe("backend", "http://unused.invalid/")

    def test_env_preserves_password_quotes_and_spaces(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(local, "BACKEND", root), patch.object(local, "STATE", root / ".pubburi"):
                local.initialize()
                p = root / ".env"
                lines = [line for line in p.read_text().splitlines() if not line.startswith("PUBBURI_DB_PASSWORD=")]
                for literal, expected in [("\"abc'\"", "abc'"), ("'abc\"'", 'abc"'), ('" space "', ' space ')]:
                    p.write_text("\n".join(lines) + "\nPUBBURI_DB_PASSWORD=" + literal + "\n")
                    with patch.dict(os.environ, {}, clear=True):
                        self.assertEqual(local.environment()["PUBBURI_DB_PASSWORD"], expected)

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

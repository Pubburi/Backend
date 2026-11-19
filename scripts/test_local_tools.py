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
    def runtime_identity(self, second=False):
        return dict(container_id=('b' if second else 'a') * 64,
                    source_id=('87654321' if second else '12345678') + '-1234-1234-1234-123456789abc',
                    database_name='owned_ci', database_port=26432 if second else 25432,
                    ports=[26432, 16001, 16002] if second else [25432, 15001, 15002],
                    marker_sha256='c' * 64)

    def test_two_runtime_lifetimes_share_only_the_registration_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            with local.runtime_lock(state, 'operating', lambda: (self.runtime_identity(), 'operating')) as first:
                self.assertEqual(first, 'operating')
                self.assertFalse((state / 'local-stack-lock').exists())
                with self.assertRaisesRegex(RuntimeError, 'already owned'):
                    with local.operation_lock(state):
                        self.fail('Exclusive operation admitted a live runtime.')
                with self.assertRaisesRegex(RuntimeError, 'already owned'):
                    with local.runtime_lock(state, 'operating', lambda: self.fail('Duplicate preflight ran.')):
                        pass
                with local.runtime_lock(state, 'demo', lambda: (self.runtime_identity(True), 'demo')) as second:
                    self.assertEqual(second, 'demo')
                    with local.operation_gate(state) as parent:
                        self.assertEqual(set(local.runtime_records(parent, live=True)), {'operating', 'demo'})
                with local.operation_gate(state) as parent:
                    self.assertEqual(set(local.runtime_records(parent, live=True)), {'operating'})
            with local.operation_lock(state):
                pass
            self.assertEqual(list(state.glob(local.RUNTIME_PREFIX + '*')), [])

    def test_peer_container_source_and_cross_role_ports_reject_in_both_orders(self):
        for first_lane, second_lane in (('operating', 'demo'), ('demo', 'operating')):
            for change in (dict(container_id='a' * 64), dict(source_id=self.runtime_identity()['source_id']),
                           dict(ports=[26432, 15001, 16002]), dict(ports=[26432, 16001, 15002]),
                           dict(database_port=25432, ports=[25432, 16001, 16002]),
                           dict(ports=[26432, 16001, 16002, 15001])):
                with self.subTest(first=first_lane, change=change), tempfile.TemporaryDirectory() as directory:
                    state = Path(directory)
                    with local.runtime_lock(state, first_lane, lambda: (self.runtime_identity(), None)):
                        with self.assertRaisesRegex(RuntimeError, 'separate containers'):
                            with local.runtime_lock(state, second_lane, lambda: (dict(self.runtime_identity(True), **change), None)):
                                self.fail('Conflicting peer was admitted.')

    def test_runtime_ownership_is_bound_to_the_explicit_private_state_and_not_inherited(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); selected = root / 'selected'; original = root / 'original'
            opened = []; actual_open = local.os.open
            def observe(name, *args, **kwargs):
                descriptor = actual_open(name, *args, **kwargs)
                if isinstance(name, str) and name.startswith(local.RUNTIME_PREFIX):
                    opened.append(descriptor)
                return descriptor
            with patch.object(local, 'STATE', original), patch.object(local.os, 'open', side_effect=observe):
                with local.runtime_lock(selected, 'demo', lambda: (self.runtime_identity(), None)):
                    self.assertFalse(original.exists())
                    self.assertTrue(opened)
                    self.assertFalse(os.get_inheritable(opened[0]))
                    child = subprocess.run([sys.executable, '-c',
                        'import os,sys\ntry: os.fstat(int(sys.argv[1]))\nexcept OSError: sys.exit(0)\nsys.exit(1)',
                        str(opened[0])], capture_output=True, timeout=10)
                    self.assertEqual(child.returncode, 0)

    def test_runtime_registration_serializes_two_independent_owners(self):
        with tempfile.TemporaryDirectory() as directory, ThreadPoolExecutor(max_workers=2) as pool:
            state = Path(directory); inside = threading.Barrier(2); release = threading.Event()
            def run(lane, second):
                with local.runtime_lock(state, lane, lambda: (self.runtime_identity(second), None)):
                    inside.wait(timeout=5)
                    if not release.wait(timeout=5):
                        raise RuntimeError('Test did not release runtime owners.')
            futures = [pool.submit(run, 'operating', False), pool.submit(run, 'demo', True)]
            try:
                deadline = __import__('time').monotonic() + 5
                while not (state / local.RUNTIME_REGISTRY).exists() and __import__('time').monotonic() < deadline:
                    __import__('time').sleep(.01)
                with self.assertRaisesRegex(RuntimeError, 'already owned'):
                    with local.operation_lock(state):
                        self.fail('Exclusive operation overlapped registration/runtime.')
            finally:
                release.set()
            for future in futures:
                future.result(timeout=10)
            with local.operation_lock(state):
                pass

    def test_crashed_owner_is_not_reclaimed_even_after_its_pid_and_flock_end(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            code = ('import json,os,sys;sys.path.insert(0,sys.argv[1]);import local_stack as local\n'
                    'with local.runtime_lock(__import__("pathlib").Path(sys.argv[2]),"operating",lambda:(json.loads(sys.argv[3]),None)):\n'
                    ' print("ready",flush=True);sys.stdin.read(1);os._exit(0)\n')
            child = subprocess.Popen([sys.executable, '-B', '-c', code, str(LEASE.parent), str(state),
                                      json.dumps(self.runtime_identity())], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
            try:
                self.assertEqual(child.stdout.readline().strip(), 'ready')
                child.communicate('x', timeout=10)
                self.assertEqual(child.returncode, 0)
                original = (state / local.RUNTIME_REGISTRY).read_bytes()
                with self.assertRaises(local.ProcessCleanupError):
                    with local.runtime_lock(state, 'demo', lambda: self.fail('Dead owner preflight ran.')):
                        pass
                self.assertEqual((state / local.RUNTIME_REGISTRY).read_bytes(), original)
                self.assertTrue((state / 'local-stack-lock').exists())
            finally:
                if child.poll() is None:
                    child.communicate('x', timeout=10)

    def test_uncertain_cleanup_and_mutated_owner_keep_the_durable_fence(self):
        for mutation in ('cleanup', 'replace', 'hardlink', 'symlink', 'missing', 'malformed', 'parent'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                state = Path(directory) / 'state'
                with self.assertRaises(local.ProcessCleanupError):
                    with local.runtime_lock(state, 'operating', lambda: (self.runtime_identity(), None)):
                        record = next(state.glob(local.RUNTIME_PREFIX + '*'))
                        if mutation == 'replace':
                            raw = record.read_bytes(); record.unlink(); record.write_bytes(raw); record.chmod(0o600)
                        elif mutation == 'hardlink':
                            os.link(record, state / 'other-file')
                        elif mutation == 'symlink':
                            raw = record.read_bytes(); record.unlink(); target = state / 'other-file'
                            target.write_bytes(raw); target.chmod(0o600); record.symlink_to(target)
                        elif mutation == 'missing':
                            record.unlink()
                        elif mutation == 'malformed':
                            record.write_bytes(b'{}')
                        elif mutation == 'parent':
                            state.rename(state.with_name('prior')); state.mkdir(mode=0o700)
                        if mutation == 'cleanup':
                            raise local.ProcessCleanupError('Owned child cleanup is uncertain.')
                if mutation != 'parent':
                    with self.assertRaises((local.ProcessCleanupError, RuntimeError)):
                        with local.operation_lock(state):
                            self.fail('Uncertain owner fence disappeared.')

    def test_unlocked_record_cannot_adopt_a_still_live_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            with self.assertRaises(local.ProcessCleanupError):
                with local.runtime_lock(state, 'operating', lambda: (self.runtime_identity(), None)):
                    raise local.ProcessCleanupError('Fixture leaves its own unresolved record.')
            original = (state / local.RUNTIME_REGISTRY).read_bytes()
            with self.assertRaises(local.ProcessCleanupError):
                with local.runtime_lock(state, 'demo', lambda: self.fail('Unlocked owner preflight ran.')):
                    pass
            self.assertEqual((state / local.RUNTIME_REGISTRY).read_bytes(), original)

    def test_parent_replaced_during_preflight_never_enters_runtime_or_cleans_the_new_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); state = root / 'state'; previous = root / 'previous'
            entered = False
            def prepare():
                state.rename(previous); state.mkdir(mode=0o700)
                return self.runtime_identity(), None
            with self.assertRaises(local.ProcessCleanupError):
                with local.runtime_lock(state, 'operating', prepare):
                    entered = True
            self.assertFalse(entered)
            self.assertTrue((previous / 'local-stack-lock').is_dir())
            self.assertFalse((previous / local.RUNTIME_REGISTRY).exists())
            self.assertEqual(list(state.iterdir()), [])

    def test_parent_replaced_after_gate_release_never_enters_the_runtime_body(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); state = root / 'state'; previous = root / 'previous'
            entered = False; release = local.release_gate
            def replace_after_gate(*args):
                release(*args)
                state.rename(previous); state.mkdir(mode=0o700)
            with patch.object(local, 'release_gate', side_effect=replace_after_gate), \
                    self.assertRaises(local.ProcessCleanupError):
                with local.runtime_lock(state, 'operating', lambda: (self.runtime_identity(), None)):
                    entered = True
            self.assertFalse(entered)
            self.assertTrue((previous / local.RUNTIME_REGISTRY).is_file())
            self.assertTrue(list(previous.glob(local.RUNTIME_PREFIX + '*')))
            self.assertEqual(list(state.iterdir()), [])

    def test_missing_gate_keeps_registered_owner_and_blocks_exclusive_and_new_lane(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory); entered = False
            def prepare():
                (state / 'local-stack-lock').rmdir()
                return self.runtime_identity(), None
            with self.assertRaises(local.ProcessCleanupError):
                with local.runtime_lock(state, 'operating', prepare):
                    entered = True
            self.assertFalse(entered)
            original = (state / local.RUNTIME_REGISTRY).read_bytes()
            self.assertTrue(json.loads(original)['owners'].get('operating'))
            self.assertEqual(len(list(state.glob(local.RUNTIME_PREFIX + '*'))), 1)
            with self.assertRaises(RuntimeError):
                with local.operation_lock(state):
                    self.fail('Exclusive operation admitted an uncertain gate owner.')
            with self.assertRaises(RuntimeError):
                with local.runtime_lock(state, 'demo', lambda: self.fail('Uncertain owner preflight ran.')):
                    pass
            self.assertEqual((state / local.RUNTIME_REGISTRY).read_bytes(), original)

    def test_replaced_gate_is_not_removed_or_used_to_release_the_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory); retained = state / 'retained-original-gate'; entered = False
            def prepare():
                (state / 'local-stack-lock').rename(retained)
                (state / 'local-stack-lock').mkdir(mode=0o700)
                return self.runtime_identity(), None
            with self.assertRaises(local.ProcessCleanupError):
                with local.runtime_lock(state, 'operating', prepare):
                    entered = True
            self.assertFalse(entered)
            original = (state / local.RUNTIME_REGISTRY).read_bytes()
            self.assertTrue(json.loads(original)['owners'].get('operating'))
            self.assertTrue(retained.is_dir()); self.assertTrue((state / 'local-stack-lock').is_dir())
            with self.assertRaises(RuntimeError):
                with local.operation_lock(state):
                    self.fail('Exclusive operation adopted a replaced gate.')
            with self.assertRaises(RuntimeError):
                with local.runtime_lock(state, 'demo', lambda: self.fail('Replaced gate preflight ran.')):
                    pass
            self.assertEqual((state / local.RUNTIME_REGISTRY).read_bytes(), original)
            self.assertTrue(retained.is_dir()); self.assertTrue((state / 'local-stack-lock').is_dir())

    def test_gate_and_registry_symlinks_or_incomplete_files_are_not_adopted(self):
        for kind in ('gate-symlink', 'registry-symlink', 'registry-empty', 'orphan-record'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                state = Path(directory); other = state / 'untouched'; other.mkdir(mode=0o700)
                if kind == 'gate-symlink':
                    (state / 'local-stack-lock').symlink_to(other, target_is_directory=True)
                elif kind == 'registry-symlink':
                    (state / local.RUNTIME_REGISTRY).symlink_to(other / 'missing')
                elif kind == 'registry-empty':
                    (state / local.RUNTIME_REGISTRY).touch(mode=0o600)
                else:
                    (state / (local.RUNTIME_PREFIX + 'partial.json')).touch(mode=0o600)
                with self.assertRaises((RuntimeError, OSError)):
                    with local.operation_lock(state):
                        self.fail('Uncertain control path was admitted.')
                self.assertTrue(other.is_dir())
                self.assertEqual(list(other.iterdir()), [])

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
            frontend = root / "Frontend"
            (frontend / "node_modules").mkdir(parents=True)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            env = {"PUBBURI_FRONTEND_PORT": str(listener.getsockname()[1])}
            with patch.object(local, "STATE", root / ".pubburi"), \
                 patch.object(local, "FRONTEND", frontend), \
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

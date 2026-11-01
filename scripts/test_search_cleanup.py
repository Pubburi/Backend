import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import smoke_search


class SearchCleanupTests(unittest.TestCase):
    def run_failure(self, mode):
        calls = []
        label = "cleanup-unit"
        name = "pubburi-search-smoke-" + label
        def docker(*args, check=True):
            calls.append(args)
            if not check: return SimpleNamespace(returncode=1, stdout="", stderr="")
            if args[:2] == ("image", "inspect"): output = "sha256:fixture"
            elif args[:2] == ("volume", "create"): output = name
            elif args[0] == "create": output = "ownedcid"
            elif args[0] == "inspect":
                owner = "foreign" if mode == "foreign_container" else label
                output = json.dumps([{"Config": {"Labels": {"pubburi.validation": owner}},
                    "NetworkSettings": {"Ports": {"9200/tcp": [{"HostPort": "19000"}]}}, "State": {"Pid": 123}}])
            elif args[:2] == ("volume", "inspect"):
                owner = "foreign" if mode == "foreign_volume" else label
                output = json.dumps([{"Labels": {"pubburi.validation": owner}}])
            elif args[0] == "logs":
                if mode == "log_read": raise OSError("private-log-read-path")
                output = "authored log"
            elif args[0] == "rm" and mode == "remove_failure": raise OSError("private-remove-path")
            else: output = ""
            return SimpleNamespace(returncode=0, stdout=output, stderr="")
        original_write = Path.write_text
        def write(path, *args, **kwargs):
            if mode == "log_write" and path.name == "engine.log": raise OSError("private-log-write-path")
            return original_write(path, *args, **kwargs)
        def cli(args, **kwargs):
            directory = Path(args[args.index("--output-dir") + 1])
            directory.mkdir()
            failed = directory.name == "unavailable"
            (directory / "receipt.json").write_text(json.dumps({"completed": not failed,
                "applied": 0, "duplicate": 2, "stale_ignored": 2}))
            return SimpleNamespace(returncode=2 if failed else 0)
        events = [smoke_search.authored(), smoke_search.authored(version=2),
                  smoke_search.authored(2), smoke_search.authored(version=3, deleted=True)]
        prior = Path.cwd()
        with tempfile.TemporaryDirectory() as temp:
            try:
                os.chdir(temp)
                Path("scripts").mkdir(); Path("scripts/search_index.py").write_text("authored source marker")
                with patch.dict(os.environ, {"PUBBURI_SEARCH_VALIDATION_RUN": label}), patch.object(smoke_search, "docker", side_effect=docker), patch.object(smoke_search, "wait_ready"), patch.object(smoke_search, "exercise", return_value=(SimpleNamespace(index="pubburi-products-v1-validation"), events)), patch.object(smoke_search.Engine, "ready"), patch.object(smoke_search.Engine, "apply", return_value="stale_ignored"), patch.object(smoke_search, "visible", return_value=[2]), patch.object(smoke_search.subprocess, "run", side_effect=cli), patch.object(Path, "write_text", new=write), patch("builtins.print"):
                    self.assertEqual(smoke_search.main(), 2)
                report = json.loads(Path("target/search-smoke-report.json").read_text())
                state = json.loads(next(Path(".pubburi").glob("*/runtime.json")).read_text())
            finally: os.chdir(prior)
        self.assertEqual(report["status"], "failed")
        if mode != "foreign_container": self.assertNotIn("error_code", report)  # Core validation reached success before cleanup.
        self.assertNotIn("private-", json.dumps(report))
        return calls, state, report, name

    def test_log_read_failure_still_removes_owned_container_and_volume(self):
        calls, state, report, name = self.run_failure("log_read")
        self.assertIn(("rm", "-f", "ownedcid"), calls)
        self.assertIn(("volume", "rm", name), calls)
        self.assertEqual(state["phase"], "cleaned")
        self.assertEqual(report["diagnostic_error"], "OSError")

    def test_log_file_failure_still_removes_owned_container_and_volume(self):
        calls, state, report, name = self.run_failure("log_write")
        self.assertIn(("rm", "-f", "ownedcid"), calls)
        self.assertIn(("volume", "rm", name), calls)
        self.assertEqual(state["phase"], "cleaned")
        self.assertEqual(report["diagnostic_error"], "OSError")

    def test_nonowned_container_is_preserved_and_its_volume_is_not_removed(self):
        calls, state, report, name = self.run_failure("foreign_container")
        self.assertNotIn(("rm", "-f", "ownedcid"), calls)
        self.assertNotIn(("volume", "rm", name), calls)
        self.assertEqual(state["phase"], "cleanup_failed")

    def test_remove_failure_or_foreign_volume_stays_failed_without_unsafe_volume_removal(self):
        for mode in ("remove_failure", "foreign_volume"):
            with self.subTest(mode=mode):
                calls, state, report, name = self.run_failure(mode)
                self.assertNotIn(("volume", "rm", name), calls)
                self.assertEqual(state["phase"], "cleanup_failed")


if __name__ == "__main__": unittest.main()

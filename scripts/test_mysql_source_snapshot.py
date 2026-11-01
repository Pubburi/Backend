import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from mysql_source_snapshot import verify_owned_source, private_path, child_environment
from source_snapshot_ci import run


class MySqlSourceGuardTests(unittest.TestCase):
    def settings(self):
        return {"PUBBURI_SOURCE_URL":"jdbc:mysql://127.0.0.1:54321/source_ci", "PUBBURI_SOURCE_DB_NAME":"source_ci",
                "PUBBURI_SOURCE_CONTAINER_ID":"a"*64, "PUBBURI_SOURCE_VALIDATION_LABEL":"authored-source"}

    def test_only_exact_owned_running_loopback_source_is_accepted(self):
        fixture={"Id":"a"*64,"State":{"Running":True},"Config":{"Labels":{"pubburi.validation":"authored-source"}},
                 "NetworkSettings":{"Ports":{"3306/tcp":[{"HostIp":"127.0.0.1","HostPort":"54321"}]}}}
        def check(value):
            with patch("mysql_source_snapshot.subprocess.run",return_value=subprocess.CompletedProcess([],0,json.dumps([value]))):
                verify_owned_source(self.settings())
        check(fixture)
        for transform in (lambda x:x["State"].update(Running=False),lambda x:x["Config"]["Labels"].update({"pubburi.validation":"someone-else"}),
                          lambda x:x["NetworkSettings"]["Ports"]["3306/tcp"][0].update(HostIp="0.0.0.0"),
                          lambda x:x["NetworkSettings"]["Ports"]["3306/tcp"][0].update(HostPort="54322")):
            bad=copy.deepcopy(fixture);transform(bad)
            with self.assertRaises(ValueError):check(bad)
        for url in ("jdbc:mysql://localhost:54321/source_ci","jdbc:mysql://127.0.0.1:54321/source_ci?sslMode=DISABLED",
                    "jdbc:mysql://user:pass@127.0.0.1:54321/source_ci","jdbc:mysql://127.0.0.1:54321/customer_db"):
            with patch("mysql_source_snapshot.subprocess.run") as cmd:
                with self.assertRaises(ValueError):verify_owned_source(dict(self.settings(),PUBBURI_SOURCE_URL=url))
                cmd.assert_not_called()

    def test_java_child_receives_only_reader_settings_and_offline_gets_no_credentials(self):
        env={"JAVA_HOME":"authored-java", "PUBBURI_SOURCE_SNAPSHOT_DIR":"authored-private-dir", "PUBBURI_SOURCE_PASSWORD":"reader-only",
             "PUBBURI_SOURCE_CONTROL_PASSWORD":"control-only", "PUBBURI_SOURCE_WILDCARD_USER":"broad-reader", "PUBBURI_DB_PASSWORD":"app-only",
             "PUBBURI_PG_ADMIN_PASSWORD":"admin-only", "MYSQL_PWD":"root-only", "JAVA_TOOL_OPTIONS":"authored-untrusted-option"}
        self.assertEqual(child_environment(env,True),{k:env[k] for k in ("JAVA_HOME","PUBBURI_SOURCE_SNAPSHOT_DIR","PUBBURI_SOURCE_PASSWORD")})
        self.assertEqual(child_environment(env,False),{k:env[k] for k in ("JAVA_HOME","PUBBURI_SOURCE_SNAPSHOT_DIR")})

    def test_private_artifact_path_cannot_escape_or_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);ignored=root/".pubburi";ignored.mkdir()
            path=ignored/"accepted"
            with patch("mysql_source_snapshot.ROOT",root):
                self.assertEqual(private_path({"PUBBURI_SOURCE_SNAPSHOT_DIR":str(path)},False),path)
                path.mkdir()
                self.assertEqual(private_path({"PUBBURI_SOURCE_SNAPSHOT_DIR":str(path)},True),path)
                with self.assertRaises(ValueError):private_path({"PUBBURI_SOURCE_SNAPSHOT_DIR":str(path)},False)
                for p in (root/"public",ignored/"../public"):
                    with self.assertRaises(ValueError):private_path({"PUBBURI_SOURCE_SNAPSHOT_DIR":str(p)},False)
                alias=ignored/"alias";alias.symlink_to(path,target_is_directory=True)
                with self.assertRaises(ValueError):private_path({"PUBBURI_SOURCE_SNAPSHOT_DIR":str(alias)},True)

    def test_ci_bootstrap_requires_explicit_run_and_fresh_private_material(self):
        with patch("source_snapshot_ci.subprocess.run") as cmd:
            with self.assertRaises(ValueError):run({})
            cmd.assert_not_called()
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def stop(args,**kwargs):
                self.assertTrue((root/".pubburi").is_dir())
                self.assertEqual(args[0],"openssl")
                raise RuntimeError("authored-stop-before-container")
            with patch("source_snapshot_ci.ROOT",root),patch("source_snapshot_ci.subprocess.run",side_effect=stop):
                with self.assertRaisesRegex(RuntimeError,"authored-stop"):
                    run({"PUBBURI_SOURCE_CI_RUN":"authored-bootstrap"})
            self.assertEqual(list((root/".pubburi").iterdir()),[])


if __name__=="__main__":unittest.main()

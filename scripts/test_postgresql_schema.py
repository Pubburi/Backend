import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from postgresql_schema import verify_owned_postgres
from prepare_postgresql_ci import prepare


class PostgreSqlSchemaGuardTests(unittest.TestCase):
    def test_preparation_creates_its_parent_in_a_clean_checkout_before_certificate_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            tls=root/".pubburi/postgres-ci-tls"
            env={"PUBBURI_PG_MUTATING":"1","PUBBURI_PG_CONTAINER_ID":"a"*64,"PUBBURI_PG_USER":"fixture_migrator",
                 "PUBBURI_PG_ADMIN_USER":"fixture_admin","PUBBURI_PG_PASSWORD":"authored-fixture-only",
                 "PUBBURI_PG_ADMIN_PASSWORD":"authored-admin-only","PUBBURI_PG_DB_NAME":"fresh_ci","PUBBURI_PG_RUN_TOKEN":"authored-marker-fresh",
                 "PUBBURI_PG_RECOVERY_DB_NAME":"recovery_ci","PUBBURI_PG_RECOVERY_RUN_TOKEN":"authored-marker-recovery",
                 "PUBBURI_PG_BLOCKED_DB_NAME":"blocked_ci","PUBBURI_PG_BLOCKED_RUN_TOKEN":"authored-marker-blocked",
                 "PUBBURI_PG_CA":str(tls/"ca.pem")}
            def command(args,**kwargs):
                if args[0]=="openssl":
                    self.assertTrue(tls.is_dir())
                    raise RuntimeError("stop-after-owned-directory-creation")
                return subprocess.CompletedProcess(args,0,"postgres" if "SELECT datname" in kwargs["input"] else "180006")
            self.assertFalse((root/".pubburi").exists())
            with patch("prepare_postgresql_ci.ROOT",root),patch("prepare_postgresql_ci.verify_owned_postgres"), \
                    patch("prepare_postgresql_ci.subprocess.run",side_effect=command):
                with self.assertRaisesRegex(RuntimeError,"stop-after-owned-directory-creation"):
                    prepare(env)

    def test_bootstrap_refuses_implicit_mutation_or_an_existing_user_database_before_tls_or_ddl(self):
        with patch("prepare_postgresql_ci.verify_owned_postgres") as guard:
            with self.assertRaises(ValueError): prepare({})
            guard.assert_not_called()
        env = {"PUBBURI_PG_MUTATING":"1", "PUBBURI_PG_CONTAINER_ID":"a"*64, "PUBBURI_PG_USER":"fixture_migrator",
               "PUBBURI_PG_ADMIN_USER":"fixture_admin", "PUBBURI_PG_PASSWORD":"authored-fixture-only",
               "PUBBURI_PG_ADMIN_PASSWORD":"authored-admin-only", "PUBBURI_PG_DB_NAME":"fresh_ci", "PUBBURI_PG_RUN_TOKEN":"authored-marker-fresh",
               "PUBBURI_PG_RECOVERY_DB_NAME":"recovery_ci", "PUBBURI_PG_RECOVERY_RUN_TOKEN":"authored-marker-recovery",
               "PUBBURI_PG_BLOCKED_DB_NAME":"blocked_ci", "PUBBURI_PG_BLOCKED_RUN_TOKEN":"authored-marker-blocked"}
        with patch("prepare_postgresql_ci.verify_owned_postgres"), \
                patch("prepare_postgresql_ci.subprocess.run", return_value=subprocess.CompletedProcess([],0,"customer_db\npostgres")) as command:
            with self.assertRaises(ValueError): prepare(env)
            self.assertEqual(command.call_count,1)
            self.assertTrue(command.call_args.kwargs["input"].startswith("SELECT datname"))

    def test_container_label_running_state_and_exact_loopback_publication_are_required(self):
        environ = {"PUBBURI_PG_URL": "jdbc:postgresql://127.0.0.1:54321/fixture_ci", "PUBBURI_PG_DB_NAME": "fixture_ci",
                   "PUBBURI_PG_CONTAINER_ID": "a" * 64, "PUBBURI_PG_VALIDATION_LABEL": "authored-fixture"}
        fixture = {"Id": "a" * 64, "State": {"Running": True}, "Config": {"Labels": {"pubburi.validation": "authored-fixture"}},
                   "NetworkSettings": {"Ports": {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "54321"}]}}}
        def check(value):
            with patch("postgresql_schema.subprocess.run", return_value=subprocess.CompletedProcess([], 0, json.dumps([value]))):
                verify_owned_postgres(environ)
        check(fixture)
        changes = []
        bad = copy.deepcopy(fixture); bad["State"]["Running"] = False; changes.append(bad)
        bad = copy.deepcopy(fixture); bad["Config"]["Labels"]["pubburi.validation"] = "other-owner"; changes.append(bad)
        bad = copy.deepcopy(fixture); bad["NetworkSettings"]["Ports"]["5432/tcp"][0]["HostPort"] = "54322"; changes.append(bad)
        bad = copy.deepcopy(fixture); bad["NetworkSettings"]["Ports"]["5432/tcp"][0]["HostIp"] = "0.0.0.0"; changes.append(bad)
        for value in changes:
            with self.assertRaises(ValueError): check(value)
        # Actions' original -p 5432:5432 published IPv4/IPv6 all-interface bindings.
        dual=copy.deepcopy(fixture)
        dual["NetworkSettings"]["Ports"]["5432/tcp"]=[{"HostIp":"0.0.0.0","HostPort":"54321"},{"HostIp":"::","HostPort":"54321"}]
        with self.assertRaisesRegex(ValueError,"postgresql_published_port_guard_rejected"): check(dual)
        for url in ("jdbc:postgresql://example.com:54321/fixture_ci", "jdbc:postgresql://127.0.0.1:54321/user_data",
                    "jdbc:postgresql://127.0.0.1:54321/fixture_ci?sslmode=disable"):
            with patch("postgresql_schema.subprocess.run") as command:
                with self.assertRaises(ValueError): verify_owned_postgres(dict(environ, PUBBURI_PG_URL=url))
                command.assert_not_called()


if __name__ == "__main__":
    unittest.main()

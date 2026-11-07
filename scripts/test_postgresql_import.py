import unittest
import contextlib
import io
import os
import subprocess
from unittest.mock import patch
import postgresql_import
from postgresql_import import child_environment


class PostgreSqlImportChildTests(unittest.TestCase):
    def test_import_child_has_no_source_reader_admin_or_api_credentials(self):
        env={"JAVA_HOME":"authored-java","PUBBURI_PG_URL":"authored-pg","PUBBURI_PG_PASSWORD":"target-only",
             "PUBBURI_PG_IMPORT_SHA256":"authored-hash","PUBBURI_SOURCE_SNAPSHOT_DIR":"authored-private-dir",
             "PUBBURI_PG_ADMIN_PASSWORD":"admin-only","PUBBURI_SOURCE_PASSWORD":"source-only","PUBBURI_DB_PASSWORD":"api-only",
             "PGPASSWORD":"admin-other","JAVA_TOOL_OPTIONS":"authored-untrusted-option"}
        self.assertEqual(child_environment(env),{key:env[key] for key in ("JAVA_HOME","PUBBURI_PG_URL","PUBBURI_PG_PASSWORD","PUBBURI_PG_IMPORT_SHA256","PUBBURI_SOURCE_SNAPSHOT_DIR")})
        cutover=child_environment(env,True)
        self.assertEqual(cutover['PUBBURI_SOURCE_PASSWORD'],'source-only')
        for key in ('PUBBURI_PG_ADMIN_PASSWORD','PUBBURI_DB_PASSWORD','PGPASSWORD','JAVA_TOOL_OPTIONS'):
            self.assertNotIn(key,cutover)

    def test_cutover_checks_both_owned_containers_before_the_read_only_child(self):
        env={'PUBBURI_PG_IMPORT_WRITES_STOPPED':'1'}
        with patch('postgresql_import.private_path'),patch('postgresql_import.verify_owned_postgres') as pg, \
             patch('postgresql_import.verify_owned_source',side_effect=ValueError('owned source changed')), \
             patch('postgresql_import.subprocess.run') as child:
            with self.assertRaises(ValueError):postgresql_import.execute(env,'verify-cutover')
            pg.assert_called_once_with(env);child.assert_not_called()

    def test_parent_timeout_does_not_claim_an_import_rollback_or_leak_exception_details(self):
        env={"PUBBURI_PG_MUTATING":"1","PUBBURI_PG_IMPORT_WRITES_STOPPED":"1"}
        for command,code in (("import-source","postgresql_import_commit_status_unknown"),("verify-import","postgresql_import_verification_timed_out")):
            output=io.StringIO()
            with patch.dict(os.environ,env,clear=True),patch("sys.argv",["postgresql_import.py",command]), \
                 patch("postgresql_import.private_path"),patch("postgresql_import.verify_owned_postgres"),patch("pathlib.Path.is_file",return_value=True), \
                 patch("postgresql_import.subprocess.run",side_effect=subprocess.TimeoutExpired(["authored-private-detail"],120)),contextlib.redirect_stdout(output):
                self.assertEqual(postgresql_import.main(),2)
            self.assertIn(code,output.getvalue());self.assertNotIn("authored-private-detail",output.getvalue())

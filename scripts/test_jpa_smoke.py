import unittest
import io
import subprocess
import signal
from unittest.mock import Mock
import jpa_rehearsal_ci
from jpa_smoke import cleanup_owned
from unittest.mock import patch
from jpa_smoke import child_environment,run

class JpaSmokeSafetyTests(unittest.TestCase):
    def test_runtime_child_gets_no_fixture_login_source_or_admin_credentials(self):
        env={"JAVA_HOME":"authored-java","PUBBURI_PG_PASSWORD":"app-db-only","PUBBURI_JPA_REHEARSAL":"1","PUBBURI_JPA_SOURCE_ID":"authored-source",
             "PUBBURI_PG_ADMIN_PASSWORD":"admin-only","PUBBURI_JPA_ADMIN_PASSWORD":"http-fixture-only","PUBBURI_SOURCE_PASSWORD":"source-only","JAVA_TOOL_OPTIONS":"ignored-option"}
        self.assertEqual(child_environment(env),{k:env[k] for k in ("JAVA_HOME","PUBBURI_PG_PASSWORD","PUBBURI_JPA_REHEARSAL","PUBBURI_JPA_SOURCE_ID")})
    def test_no_runtime_or_docker_action_without_explicit_fixture_mutation(self):
        with patch("jpa_smoke.verify_owned_postgres") as verify:
            with self.assertRaises(ValueError):run({})
            verify.assert_not_called()

    def test_diagnostic_failure_does_not_skip_later_owned_process_cleanup(self):
        closed=[io.StringIO(),io.StringIO()];calls=[]
        def stop(process):
            calls.append(process)
            if process==1:raise OSError("authored diagnostic failure")
        with self.assertRaises(ValueError):cleanup_owned([(1,closed[0]),(2,closed[1])],stop)
        self.assertEqual(calls,[1,2]);self.assertTrue(all(stream.closed for stream in closed))

    def test_parent_deadline_kills_only_the_owned_unreaped_session_before_reaping(self):
        process=Mock(pid=12345);calls=[]
        def wait(timeout):
            calls.append(("wait",timeout))
            if timeout==180:raise subprocess.TimeoutExpired(["authored"],180)
            return -9
        process.wait.side_effect=wait
        with patch("pathlib.Path.open",return_value=io.StringIO()),patch("jpa_rehearsal_ci.subprocess.Popen",return_value=process) as popen, \
             patch("jpa_rehearsal_ci.os.killpg",side_effect=lambda pid,sig:calls.append(("kill",pid,sig))):
            with self.assertRaises(ValueError):jpa_rehearsal_ci.command(["authored"],{},__import__('pathlib').Path("authored.log"))
            self.assertTrue(popen.call_args.kwargs["start_new_session"])
        self.assertEqual(calls,[("wait",180),("kill",12345,signal.SIGKILL),("wait",5)])

    def test_success_does_not_signal_a_session_after_its_leader_has_been_reaped(self):
        process=Mock(pid=12345);process.wait.return_value=0
        with patch("pathlib.Path.open",return_value=io.StringIO()),patch("jpa_rehearsal_ci.subprocess.Popen",return_value=process),patch("jpa_rehearsal_ci.os.killpg") as kill:
            jpa_rehearsal_ci.command(["authored"],{},__import__('pathlib').Path("authored.log"));kill.assert_not_called()

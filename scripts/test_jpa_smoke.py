import unittest
import io
import subprocess
import signal
import os
import json
import socket
import sys
import tempfile
import threading
import time
import http.cookiejar
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import jpa_rehearsal_ci
from jpa_smoke import cleanup_owned,request_json,cleanup_on_termination,defer_termination
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

    def test_pending_sigterm_unwinds_only_after_child_registration_and_restores_handlers(self):
        previous=signal.getsignal(signal.SIGTERM);registered=[]
        with cleanup_on_termination():
            with self.assertRaises(SystemExit) as ended:
                with defer_termination():
                    signal.raise_signal(signal.SIGTERM);registered.append(True)
            self.assertEqual(ended.exception.code,143)
        self.assertEqual(registered,[True]);self.assertEqual(signal.getsignal(signal.SIGTERM),previous)

    def test_sigterm_during_first_cleanup_still_reclaims_all_streams_before_unwinding(self):
        streams=[io.StringIO(),io.StringIO()];stopped=[];saved=[]
        def stop(process):
            stopped.append(process)
            if process==1:signal.raise_signal(signal.SIGTERM)
        with cleanup_on_termination():
            with self.assertRaises(SystemExit) as ended:
                # Same outer ownership boundary as run.finally: pending termination follows metadata cleanup.
                with defer_termination():
                    cleanup_owned([(1,streams[0]),(2,streams[1])],stop);saved.append(True)
            self.assertEqual(ended.exception.code,143)
        self.assertEqual(stopped,[1,2]);self.assertTrue(all(stream.closed for stream in streams));self.assertEqual(saved,[True])



class JpaHttpDeadlineTests(unittest.TestCase):
    def setUp(self):
        self.release=threading.Event();self.mode="normal";test=self
        class Handler(BaseHTTPRequestHandler):
            protocol_version="HTTP/1.1"
            def log_message(self,*args):pass
            def handle(self):
                try:super().handle()
                except (OSError,ValueError):pass
            def do_GET(self):
                if test.mode=="headers":
                    self.wfile.write(b"HTTP/1.1 200 OK\r\n");self.wfile.flush()
                    for _ in range(8):
                        if test.release.wait(.04):return
                        self.wfile.write(b"X-Trickle: value\r\n");self.wfile.flush()
                    self.wfile.write(b"Content-Length: 2\r\n\r\n{}");return
                self.send_response(200);self.send_header("Content-Type","application/json");self.send_header("Connection","close")
                if test.mode=="body":
                    self.send_header("Content-Length","10");self.end_headers();self.wfile.write(b"{}");self.wfile.flush()
                    for _ in range(8):
                        if test.release.wait(.04):return
                        self.wfile.write(b" ");self.wfile.flush()
                    return
                if test.mode=="short":self.send_header("Content-Length","20");self.end_headers();self.wfile.write(b"{}");return
                if test.mode=="cap":self.send_header("Content-Length","262145");self.end_headers();return
                content=json.dumps({"cookie":self.headers.get("Cookie","")}).encode()
                self.send_header("Set-Cookie","JSESSIONID=authored; Path=/; HttpOnly")
                if test.mode=="chunked":
                    self.send_header("Transfer-Encoding","chunked");self.end_headers();self.wfile.write(hex(len(content))[2:].encode()+b"\r\n"+content+b"\r\n0\r\n\r\n")
                else:self.send_header("Content-Length",str(len(content)));self.end_headers();self.wfile.write(content)
        self.server=ThreadingHTTPServer(("127.0.0.1",0),Handler);self.server.daemon_threads=True
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.base="http://127.0.0.1:"+str(self.server.server_port)
    def tearDown(self):
        self.release.set();self.server.shutdown();self.server.server_close();self.thread.join(2)
    def test_header_and_body_trickle_are_interrupted_at_the_absolute_budget(self):
        for mode in ("headers","body"):
            with self.subTest(mode=mode):
                self.mode=mode;start=time.monotonic()
                with self.assertRaisesRegex(ValueError,"jpa_runtime_total_deadline"):
                    request_json(http.cookiejar.CookieJar(),self.base,"GET","/",None,start+.12)
                self.assertLess(time.monotonic()-start,.3)
                self.assertFalse(any(t.name=="pubburi-search-deadline" for t in threading.enumerate()))
    def test_exact_length_and_chunked_close_preserve_session_cookies(self):
        jar=http.cookiejar.CookieJar()
        for mode in ("normal","chunked"):
            self.mode=mode
            status,body=request_json(jar,self.base,"GET","/",None,time.monotonic()+1)
            self.assertEqual(status,200)
        self.assertEqual(body["cookie"],"JSESSIONID=authored")
    def test_short_declared_body_and_byte_cap_are_not_success(self):
        for mode in ("short","cap"):
            self.mode=mode
            with self.assertRaises(ValueError):request_json(http.cookiejar.CookieJar(),self.base,"GET","/",None,time.monotonic()+1)


class JpaOwnedCommandTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
    def launcher(self,outcome):
        status=self.root/(outcome+".json")
        child="import json,os,socket,sys,time;from pathlib import Path;s=socket.socket();s.bind(('127.0.0.1',0));s.listen();Path(sys.argv[1]).write_text(json.dumps({'pid':os.getpid(),'port':s.getsockname()[1]}));time.sleep(60)"
        end={"zero":"sys.exit(0)","nonzero":"sys.exit(2)","sigterm":"os.kill(os.getpid(),signal.SIGTERM)","waiting":"time.sleep(60)"}[outcome]
        code="import os,signal,subprocess,sys,time;from pathlib import Path;subprocess.Popen([sys.executable,'-c',"+repr(child)+","+repr(str(status))+"]);\nwhile not Path("+repr(str(status))+").exists():time.sleep(.01)\n"+end
        return status,[sys.executable,"-c",code]
    def assert_stopped(self,status):
        value=json.loads(status.read_text())
        with socket.socket() as probe:probe.bind(("127.0.0.1",value["port"]))
    def emergency_cleanup(self,status):
        if status.exists():
            value=json.loads(status.read_text())
            try:
                # Only the authored child whose argv contains this private status path.
                args=subprocess.check_output(["ps","-p",str(value["pid"]),"-o","args="],text=True,stderr=subprocess.DEVNULL)
                if str(status) in args:os.kill(value["pid"],signal.SIGKILL)
            except (ProcessLookupError,subprocess.CalledProcessError):pass
    def test_zero_nonzero_and_sigterm_leader_exits_leave_no_owned_listener(self):
        for outcome in ("zero","nonzero","sigterm"):
            status,args=self.launcher(outcome)
            try:
                if outcome=="zero":jpa_rehearsal_ci.command(args,dict(os.environ),self.root/(outcome+".log"))
                else:
                    with self.assertRaises(ValueError):jpa_rehearsal_ci.command(args,dict(os.environ),self.root/(outcome+".log"))
                self.assert_stopped(status)
            finally:self.emergency_cleanup(status)
    def test_parent_sigterm_also_cleans_its_unreaped_owned_session(self):
        status,args=self.launcher("waiting")
        code="import os,sys;from pathlib import Path;sys.path.insert(0,"+repr(str(Path(jpa_rehearsal_ci.__file__).parent))+");import jpa_rehearsal_ci;jpa_rehearsal_ci.command("+repr(args)+",dict(os.environ),Path("+repr(str(self.root/'parent.log'))+"))"
        driver=subprocess.Popen([sys.executable,"-c",code])
        try:
            end=time.monotonic()+5
            while not status.exists() and time.monotonic()<end:time.sleep(.01)
            self.assertTrue(status.exists());driver.terminate();self.assertEqual(driver.wait(timeout=5),143);self.assert_stopped(status)
        finally:
            if driver.poll() is None:driver.kill();driver.wait(timeout=5)
            self.emergency_cleanup(status)

    def test_absolute_command_deadline_cleans_a_live_child_before_reaping_the_supervisor(self):
        status,args=self.launcher("waiting")
        try:
            with self.assertRaisesRegex(ValueError,"jpa_ci_process_deadline"):
                jpa_rehearsal_ci.command(args,dict(os.environ),self.root/"deadline.log",budget=.5)
            self.assert_stopped(status)
        finally:self.emergency_cleanup(status)

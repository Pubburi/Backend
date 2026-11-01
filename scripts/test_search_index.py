import copy
from concurrent.futures import ThreadPoolExecutor
import errno
import io
import json
from pathlib import Path
import tempfile
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest
from unittest.mock import patch

import search_index as search


def event(product_id=1, version=1, deleted=False):
    return {"productId": product_id, "sourceVersion": version, "deleted": deleted,
            "document": None if deleted else {"name": "Authored fixture", "type": "탁주", "priceKrw": 1000,
                "abvPercent": 5.555, "orderCount": 0, "tasteTags": None, "aromaTags": []}}


class SearchInputTests(unittest.TestCase):
    def test_explicit_unknown_reviewed_empty_zero_and_tombstone(self):
        value = search.document(event())
        self.assertIsNone(value["taste_tags"])
        self.assertEqual(value["aroma_tags"], [])
        self.assertFalse(value["taste_known"])
        self.assertTrue(value["aroma_known"])
        zero = event(); zero["document"]["abvPercent"] = 0
        self.assertEqual(search.document(zero)["abv_percent"], 0)
        self.assertEqual(set(search.document(event(version=3, deleted=True))), {"product_id", "source_version", "deleted"})

    def test_event_and_batch_validation_rejects_inference_coercion_and_extra_fields(self):
        invalid = []
        for key, value in (("productId", True), ("productId", 0), ("sourceVersion", 0),
                           ("sourceVersion", 9223372036854775808), ("deleted", "false")):
            row = event(); row[key] = value; invalid.append(row)
        for key, value in (("priceKrw", "1000"), ("priceKrw", True), ("abvPercent", float("nan")),
                           ("abvPercent", 101), ("tasteTags", ["dry", "dry"]),
                           ("aromaTags", [" Floral "]), ("name", "\ud800")):
            row = event(); row["document"][key] = value; invalid.append(row)
        row = event(deleted=True); row["document"] = {}; invalid.append(row)
        row = event(); row["document"]["userId"] = "must-not-enter-index"; invalid.append(row)
        for row in invalid:
            with self.subTest(row=list(row)):
                with self.assertRaises(search.SearchError): search.document(row)
        for value in (b'{"schemaVersion":1,"schemaVersion":1}', b'[]', b'{"x":NaN}', b'[' * 20000):
            with self.assertRaises(search.SearchError): search.batch(value)
        payload = {"schemaVersion": 1, "dataKind": [], "events": [event()]}
        with self.assertRaises(search.SearchError): search.batch(json.dumps(payload).encode())

    def test_canonical_tag_order_and_owned_loopback_targets(self):
        row = event(); row["document"]["tasteTags"] = ["sweet", "dry"]
        self.assertEqual(search.document(row)["taste_tags"], ["dry", "sweet"])
        for endpoint in ("http://example.org:9200", "http://127.0.0.1:9200/path", "http://user@127.0.0.1:9200", "http://127.0.0.1:9200/?x=1", "https://127.0.0.1:9200"):
            with self.assertRaises(search.SearchError): search.Http(endpoint)
        for index in ("other-index", "pubburi-products-v1/_all", "pubburi-products-v1-*"):
            with self.assertRaises(search.SearchError): search.Engine(None, index)


class FakeHttp:
    def __init__(self, replies): self.replies, self.calls = list(replies), []
    def request(self, method, path, body=None):
        self.calls.append((method, path, body))
        return self.replies.pop(0)


class SearchProtocolTests(unittest.TestCase):
    def stored(self, value, version=None):
        return {"_index": "pubburi-products-v1", "_id": str(value["product_id"]),
                "_version": value["source_version"] if version is None else version, "_source": value}

    def test_strict_versions_compare_server_truth_and_conflicting_content_is_not_a_retry(self):
        value = search.document(event(version=2)); current = copy.deepcopy(value)
        conflict = (409, {"error": {"type": "version_conflict_engine_exception"}})
        for outcome in ("duplicate", "stale_ignored", "conflict", "invalid"):
            stored = copy.deepcopy(current)
            if outcome == "stale_ignored": stored["source_version"] = 3
            if outcome == "conflict": stored["price_krw"] = 999
            if outcome == "invalid": stored["taste_known"] = True
            http = FakeHttp([conflict, (200, self.stored(stored))]); engine = search.Engine(http)
            if outcome in ("conflict", "invalid"):
                with self.assertRaises(search.SearchError): engine.apply(value)
            else: self.assertEqual(engine.apply(value), outcome)
            self.assertEqual(len(http.calls), 2)
            self.assertEqual(http.calls[0][0], "PUT")
            self.assertIn("version_type=external&require_alias=true", http.calls[0][1])
            self.assertEqual(http.calls[1][0], "GET")

    def ready_replies(self, engine, source=None):
        mapping = copy.deepcopy(engine.schema["mappings"])
        if source is None: mapping.pop("_source")
        else: mapping["_source"] = source
        return [(200, {"version": {"number": search.VERSION, "distribution": "opensearch"}}),
                (200, [{"component": "analysis-nori", "version": search.VERSION}]),
                (200, {engine.index: {"mappings": mapping}}),
                (200, {engine.index: {"aliases": {engine.alias: copy.deepcopy(engine.alias_definition)}}}),
                (200, {engine.index: {"settings": {"index": {"analysis": engine.schema["settings"]["analysis"], "number_of_shards": "1", "number_of_replicas": "0"}}}})]

    def test_owned_schema_defaults_alias_and_engine_version_are_fail_closed(self):
        engine = search.Engine(None); http = FakeHttp(self.ready_replies(engine)); engine.http = http
        engine.ready(); self.assertTrue(all(row[0] == "GET" for row in http.calls))
        for source in ({"enabled": False}, {"enabled": True, "excludes": ["name"]}):
            engine.http = FakeHttp(self.ready_replies(engine, source))
            with self.assertRaises(search.SearchError): engine.ready()
        for change in ("plugin", "alias", "mapping"):
            replies = self.ready_replies(engine)
            if change == "plugin": replies[1][1][0]["version"] = "other"
            if change == "alias": replies[3][1]["foreign-index"] = {}
            if change == "mapping": replies[2][1][engine.index]["mappings"]["dynamic"] = "true"
            engine.http = FakeHttp(replies)
            with self.assertRaises(search.SearchError): engine.ready()

    def test_boolean_metadata_and_ack_cannot_be_replaced_by_numeric_zero(self):
        for change in ("mapping_boolean", "alias_boolean", "ack_boolean"):
            with self.subTest(change=change):
                engine = search.Engine(None)
                if change == "ack_boolean":
                    engine.http = FakeHttp([(200, {"_index": engine.index, "_id": "1", "_version": 1, "_shards": {"failed": False}})])
                    with self.assertRaises(search.SearchError): engine.apply(search.document(event()))
                else:
                    replies = self.ready_replies(engine)
                    if change == "mapping_boolean": replies[2][1][engine.index]["mappings"]["properties"]["price_krw"]["coerce"] = 0
                    else: replies[3][1][engine.index]["aliases"][engine.alias]["filter"]["term"]["deleted"] = 0
                    engine.http = FakeHttp(replies)
                    with self.assertRaises(search.SearchError): engine.ready()

    def test_partial_replay_and_final_receipt_failure_never_claim_completion_or_leak_paths(self):
        for failure in ("engine_response_length_mismatch", "engine_request_deadline", "final_receipt"):
            final_failure = failure == "final_receipt"
            with tempfile.TemporaryDirectory() as temp:
                directory = Path(temp) / ".pubburi" / "result"
                source = Path(temp) / "events.json"
                source.write_text(json.dumps({"schemaVersion": 1, "dataKind": "authored_fixture", "events": [event(), event(2)]}))
                args = ["search_index.py", "replay", "--endpoint", "http://127.0.0.1:9200", "--data-kind", "authored_fixture", "--input", str(source), "--output-dir", str(directory)]
                real_receipt = search.receipt
                def write_receipt(path, report):
                    if final_failure and report["completed"]: raise OSError("private-path-must-not-leak")
                    return real_receipt(path, report)
                effects = ["applied", "duplicate"] if final_failure else ["applied", search.SearchError(failure)]
                output = io.StringIO()
                with patch.object(search.sys, "argv", args), patch.object(search.Engine, "ready"), patch.object(search.Engine, "apply", side_effect=effects), patch.object(search, "receipt", side_effect=write_receipt), patch("sys.stderr", output):
                    self.assertEqual(search.main(), 2)
                report = json.loads((directory / "receipt.json").read_text())
                self.assertFalse(report["completed"]); self.assertEqual(report["applied"], 1)
                if not final_failure: self.assertEqual(report["error_code"], failure)
                self.assertNotIn("private-path", output.getvalue())


class SearchTransportTests(unittest.TestCase):
    def setUp(self):
        self.mode = "normal"
        self.started, self.release, self.headers_finished = threading.Event(), threading.Event(), threading.Event()
        self.connections = []
        self.connection_type = search._DeadlineConnection
        def connection(*args):
            result = self.connection_type(*args)
            self.connections.append(result)
            return result
        self.connection_patch = patch.object(search, "_DeadlineConnection", side_effect=connection)
        self.connection_patch.start(); self.addCleanup(self.connection_patch.stop)
        test = self
        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            def log_message(self, *args): pass
            def handle(self):
                try: super().handle()
                except (ConnectionResetError, BrokenPipeError): pass  # Expected client cancellation.
            def do_GET(self):
                if self.path == "/delayed":
                    if test.release.wait(0.30): return
                if test.mode == "trickle_headers" or self.path == "/trickle":
                    self.wfile.write(b"HTTP/1.1 200 OK\r\n"); self.wfile.flush(); test.started.set()
                    for number in range(5):
                        if test.release.wait(0.08): return
                        self.wfile.write(f"X-Trickle-{number}: value\r\n".encode()); self.wfile.flush()
                    self.wfile.write(b"Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"); self.wfile.flush()
                    test.headers_finished.set(); return
                self.send_response(302 if test.mode == "redirect" else 200)
                self.send_header("Content-Type", "application/json")
                if test.mode in ("normal_close", "chunked_close"): self.send_header("Connection", "close")
                if test.mode == "redirect": self.send_header("Location", "/must-not-follow")
                if test.mode == "declared_overflow":
                    self.send_header("Content-Length", str(search.MAX_RESPONSE_BYTES + 1)); self.end_headers(); return
                if test.mode in ("partial", "partial_close"):
                    if test.mode == "partial_close": self.send_header("Connection", "close")
                    self.send_header("Content-Length", "1000"); self.end_headers()
                    self.wfile.write(b"{"); self.wfile.flush(); test.started.set(); test.release.wait(2); return
                if test.mode == "short_length":
                    self.send_header("Content-Length", "12"); self.end_headers()
                    self.wfile.write(b"{}"); self.wfile.flush(); self.close_connection = True; return
                if test.mode == "chunked_close":
                    self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
                    self.wfile.write(b"2\r\n{}\r\n0\r\n\r\n"); self.wfile.flush(); return
                if test.mode in ("at_cap", "chunked_overflow"):
                    content = b"{}" + b" " * (search.MAX_RESPONSE_BYTES - 2 + (test.mode == "chunked_overflow"))
                    self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
                    try:
                        self.wfile.write(f"{len(content):x}\r\n".encode() + content + b"\r\n0\r\n\r\n"); self.wfile.flush()
                    except OSError: pass  # An oversized stream can be canceled by the client.
                    return
                self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b"{}")
            def do_POST(self):
                test.started.set(); test.release.wait(2)  # Intentionally do not consume the request body.
                self.close_connection = True
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.http = search.Http("http://127.0.0.1:" + str(self.server.server_port))

    def tearDown(self):
        self.release.set(); self.server.shutdown(); self.server.server_close(); self.thread.join(2)
        for connection in self.connections:
            self.assertFalse(connection.watchdog.is_alive())
            self.assertIsNone(connection.sock)
            self.assertIsNone(connection.interrupt_socket)

    def test_redirect_is_not_followed_and_content_length_is_bounded(self):
        self.mode = "redirect"; self.assertEqual(self.http.request("GET", "/")[0], 302)
        self.mode = "declared_overflow"
        with self.assertRaisesRegex(search.SearchError, "response_length_rejected"): self.http.request("GET", "/")

    def test_valid_json_with_padding_checks_unknown_length_exact_byte_boundary(self):
        self.mode = "at_cap"; self.assertEqual(self.http.request("GET", "/"), (200, {}))
        self.mode = "chunked_overflow"
        with self.assertRaisesRegex(search.SearchError, "response_exceeds_2_mib"): self.http.request("GET", "/")

    def test_total_deadline_includes_an_unfinished_response_body(self):
        self.mode = "partial"; self.http.deadline = 0.2
        with self.assertRaises(search.SearchError): self.http.request("GET", "/")
        self.assertTrue(self.started.is_set()); self.assertFalse(self.release.is_set())

    def test_complete_json_cannot_hide_a_premature_eof_before_declared_length(self):
        self.mode = "short_length"
        with self.assertRaisesRegex(search.SearchError, "response_length_mismatch"):
            self.http.request("GET", "/")

    def test_absolute_deadline_interrupts_headers_that_never_hit_the_idle_timeout(self):
        self.mode = "trickle_headers"; self.http.deadline = 0.15
        start = time.monotonic()
        with self.assertRaisesRegex(search.SearchError, "engine_request_deadline"):
            self.http.request("GET", "/")
        elapsed = time.monotonic() - start
        self.assertTrue(self.started.is_set())
        self.assertFalse(self.headers_finished.is_set(), f"headers already completed after {elapsed:.3f}s")
        self.assertLess(elapsed, 0.32)  # Scheduling allowance, before the fixture's 0.40s headers finish.

    def test_deadline_still_interrupts_body_after_http_connection_detaches_a_close_socket(self):
        self.mode = "partial_close"; self.http.deadline = 0.15
        with self.assertRaisesRegex(search.SearchError, "engine_request_deadline"):
            self.http.request("GET", "/")
        self.assertTrue(self.started.is_set()); self.assertFalse(self.release.is_set())

    def test_complete_connection_close_body_is_not_reused_after_http_response_closes_its_fd(self):
        for mode in ("normal_close", "chunked_close"):
            with self.subTest(mode=mode):
                self.mode = mode
                self.assertEqual(self.http.request("GET", "/"), (200, {}))

    def test_request_body_blocked_by_a_nonreading_peer_has_the_same_absolute_budget(self):
        original = self.connection_type.connect
        def small_send_buffer(connection):
            original(connection); connection.sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
        self.http.deadline = 0.15
        with patch.object(self.connection_type, "connect", small_send_buffer):
            with self.assertRaisesRegex(search.SearchError, "engine_request_deadline"):
                self.http.request("POST", "/", {"padding": " " * (4 * 1024 * 1024)})
        self.assertTrue(self.started.is_set()); self.assertFalse(self.release.is_set())

    def test_nonblocking_connect_wait_obeys_remaining_budget_and_cleans_up(self):
        actual_socket = socket.socket
        class PendingConnect(actual_socket):
            def connect_ex(self, address): return errno.EINPROGRESS
        def wait_for_budget(readable, writable, exceptional, timeout):
            time.sleep(timeout); return [], [], []
        self.http.deadline = 0.05
        with patch.object(search.socket, "socket", PendingConnect), patch.object(search.select, "select", side_effect=wait_for_budget):
            with self.assertRaisesRegex(search.SearchError, "engine_request_deadline"):
                self.http.request("GET", "/")

    def test_expiring_one_request_does_not_cancel_a_concurrent_independent_connection(self):
        slow = search.Http("http://127.0.0.1:" + str(self.server.server_port), deadline=0.15)
        with ThreadPoolExecutor(max_workers=2) as requests:
            independent = requests.submit(self.http.request, "GET", "/delayed")
            pending = requests.submit(slow.request, "GET", "/trickle")
            self.assertTrue(self.started.wait(1))
            with self.assertRaisesRegex(search.SearchError, "engine_request_deadline"):
                pending.result(timeout=1)
            self.assertEqual(independent.result(timeout=1), (200, {}))


if __name__ == "__main__": unittest.main()

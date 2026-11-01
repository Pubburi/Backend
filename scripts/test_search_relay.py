import io
import json
import os
from pathlib import Path
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest
from unittest.mock import MagicMock, Mock, patch
from search_index import Engine, SearchError
from search_relay import Claim, Relay, Store, claim_document, database_settings, main

SOURCE = "e308100e-e37e-4ccb-9af9-f9baaf692204"
IDENTITY = {"source_id": SOURCE, "cluster_uuid": "cluster-a", "index_uuid": "index-a",
            "index_name": "pubburi-products-v1-unit", "schema": "products-v1", "data_kind": "backend_product_events"}


def claimed():
    payload = {"productId": 7, "sourceVersion": 3, "deleted": True, "document": None}
    return Claim(9, "606fb49f-7eaf-46fe-8db4-0868e2bad9f6", 1,
        {"product_id": 7, "source_version": 3, "deleted": 1, "payload": json.dumps(payload)}, "f" * 64, SOURCE)


class RelayUnitTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.ca = Path(self.temp.name) / "test-ca.pem"; self.ca.write_text("Authored config fixture only")
    def test_database_origin_and_credentials_are_explicit_and_never_parsed_from_url(self):
        good = {"PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:3306/demo", "PUBBURI_DB_USER": "local", "PUBBURI_DB_PASSWORD": "test-only", "PUBBURI_RELAY_DB_CA": str(self.ca)}
        self.assertEqual(database_settings(good)["database"], "demo")
        for url in ("jdbc:mysql://example.org:3306/demo", "jdbc:mysql://localhost:3306/demo", "jdbc:mysql://u:p@127.0.0.1:3306/demo",
                    "jdbc:mysql://127.0.0.1:3306/demo?x=1", "jdbc:mysql://127.0.0.1:3306/demo/other"):
            with self.assertRaises(SearchError): database_settings(dict(good, PUBBURI_DB_URL=url))
        with self.assertRaises(SearchError): database_settings(dict(good, PUBBURI_DB_PASSWORD=""))
        with self.assertRaises(SearchError): database_settings(dict(good, PUBBURI_RELAY_DB_CA=""))

    def test_database_columns_and_strict_payload_must_describe_the_same_event(self):
        claim = claimed()
        self.assertTrue(claim_document(claim)["deleted"])
        for changes in ({"product_id": 8}, {"source_version": 4}, {"deleted": 0}, {"payload": "[]"}, {"payload": None}):
            candidate = Claim(claim.event_id, claim.token, 1, dict(claim.row, **changes), claim.fingerprint, SOURCE)
            with self.assertRaises(SearchError): claim_document(candidate)

    def test_valid_write_outcomes_ack_only_after_identity_recheck_and_errors_retry(self):
        for outcome in ("applied", "duplicate", "stale_ignored"):
            store, engine = Mock(), Mock()
            store.claim.return_value = claimed(); store.ack.return_value = True; engine.apply.return_value = outcome
            with patch("search_relay.target_identity", return_value=IDENTITY):
                self.assertEqual(Relay(store, engine, SOURCE).once(), {"status": "delivered", "outcome": outcome})
            self.assertEqual(engine.ready.call_count, 2); store.ack.assert_called_once(); store.retry.assert_not_called()
        store, engine = Mock(), Mock()
        store.claim.return_value = claimed(); store.retry.return_value = True
        engine.apply.side_effect = SearchError("engine_request_deadline")
        with patch("search_relay.target_identity", return_value=IDENTITY):
            self.assertEqual(Relay(store, engine, SOURCE).once()["status"], "retry")
        store.ack.assert_not_called(); self.assertEqual(store.retry.call_args.args[1], "engine_request_deadline")

    def test_target_replacement_and_lost_claim_cannot_be_acknowledged_as_delivered(self):
        store, engine = Mock(), Mock()
        store.claim.return_value = claimed(); store.retry.return_value = True; engine.apply.return_value = "applied"
        with patch("search_relay.target_identity", side_effect=[IDENTITY, dict(IDENTITY, index_uuid="index-b")]):
            result = Relay(store, engine, SOURCE).once()
        self.assertEqual(result["error_code"], "relay_target_changed_during_delivery"); store.ack.assert_not_called()
        store.ack.return_value = False
        with patch("search_relay.target_identity", return_value=IDENTITY):
            self.assertEqual(Relay(store, engine, SOURCE).once()["status"], "lease_lost")

    def test_transaction_rolls_back_and_closes_on_interruption_and_target_fence_precedes_ack(self):
        connection = MagicMock(); cursor = connection.cursor.return_value.__enter__.return_value
        store = Store(lambda: connection)
        with self.assertRaises(KeyboardInterrupt):
            with store.transaction(): raise KeyboardInterrupt()
        connection.rollback.assert_called_once(); connection.close.assert_called_once(); connection.commit.assert_not_called()
        cursor.fetchone.return_value = {"source_id": SOURCE, "fingerprint": "other"}
        with self.assertRaises(SearchError): store.ack(claimed())
        self.assertTrue(all("UPDATE" not in call.args[0] for call in cursor.execute.call_args_list))

    def test_watch_stops_on_binding_errors_but_retries_only_documented_transport_failures(self):
        environ = {"PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:3306/demo", "PUBBURI_DB_USER": "local", "PUBBURI_DB_PASSWORD": "not-a-real-credential", "PUBBURI_RELAY_DB_CA": str(self.ca)}
        for code, expected, sleeps in (("relay_target_identity_mismatch", 2, 0), ("relay_engine_request_rejected", 2, 0),
                                        ("relay_engine_unavailable", 0, 1), ("engine_request_deadline", 0, 1)):
            store, relay = Mock(), Mock(); store.source_id.return_value = SOURCE
            relay.once.side_effect = [{"status": "retry", "error_code": code}, KeyboardInterrupt()]
            with patch("search_relay.os.environ", environ), patch("search_relay.Store", return_value=store), \
                    patch("search_relay.Engine"), patch("search_relay.Relay", return_value=relay), \
                    patch("search_relay.target_identity", return_value=IDENTITY), patch("search_relay.time.sleep") as sleep, \
                    patch("sys.stdout", io.StringIO()), patch("search_relay.sys.argv", ["relay", "watch", "--endpoint", "http://127.0.0.1:9200", "--index", "pubburi-products-v1-unit"]):
                self.assertEqual(main(), expected); self.assertEqual(sleep.call_count, sleeps)

    def test_watch_startup_validation_fails_fast_without_entering_the_retry_loop(self):
        environ = {"PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:3306/demo", "PUBBURI_DB_USER": "local",
                   "PUBBURI_DB_PASSWORD": "not-a-real-credential", "PUBBURI_RELAY_DB_CA": str(self.ca)}
        store, engine = Mock(), Mock(); store.source_id.return_value = SOURCE
        engine.ready.side_effect = SearchError("engine_transport_failed")
        with patch("search_relay.os.environ", environ), patch("search_relay.Store", return_value=store), \
                patch("search_relay.Engine", return_value=engine), patch("search_relay.Relay") as relay, \
                patch("search_relay.time.sleep") as sleep, patch("sys.stderr", io.StringIO()), \
                patch("search_relay.sys.argv", ["relay", "watch", "--endpoint", "http://127.0.0.1:9200", "--index", "pubburi-products-v1-unit"]):
            self.assertEqual(main(), 2)
        store.bind.assert_not_called(); relay.assert_not_called(); sleep.assert_not_called()

    def test_http_error_status_is_classified_before_an_untrusted_html_body(self):
        from search_index import Http
        status = [400]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_PUT(self):
                self.rfile.read(int(self.headers.get('Content-Length', '0')))
                self.send_response(status[0]); self.send_header('Content-Type', 'text/html')
                self.send_header('Content-Length', '9'); self.end_headers(); self.wfile.write(b'untrusted')
        server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        worker = threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        try:
            for value in (400,403,429,503):
                status[0] = value
                store = Mock();store.claim.return_value = claimed();store.retry.return_value = True
                transport=Http('http://127.0.0.1:'+str(server.server_port))
                engine=Engine(transport,'pubburi-products-v1-unit','backend_product_events',SOURCE)
                with patch.object(engine,'ready'),patch('search_relay.target_identity',return_value=IDENTITY):
                    report=Relay(store,engine,SOURCE).once()
                self.assertEqual(report['error_code'],'relay_engine_unavailable' if value in (429,503) else 'relay_engine_request_rejected')
                self.assertNotIn('untrusted',str(report));store.ack.assert_not_called()
                self.assertIsNone(transport.status_guard)  # Default management client was not changed.
        finally:
            server.shutdown();server.server_close();worker.join(2)

    def test_source_bound_schema_is_optional_only_for_the_existing_management_client(self):
        self.assertNotIn("source_id", Engine(None).schema["mappings"]["_meta"])
        self.assertEqual(Engine(None, kind="backend_product_events", source_id=SOURCE).schema["mappings"]["_meta"]["source_id"], SOURCE)
        for bad in ("", "not-uuid", SOURCE.upper(), 7):
            with self.assertRaises(SearchError): Engine(None, source_id=bad)


if __name__ == "__main__": unittest.main()

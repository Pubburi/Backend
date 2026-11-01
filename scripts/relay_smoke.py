"""Real disposable MySQL/engine relay checks; never initialize an existing application database."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import signal
import socket
import struct
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, urlunsplit

from api_smoke import require_disposable_database
from search_index import Engine, Http, SearchError, document
from search_relay import Store, Relay, connect_database, database_settings, target_identity, relay_status


def queue_environment(environ):
    name = environ.get("PUBBURI_RELAY_SMOKE_DB_NAME", "")
    if not re.fullmatch(r"[A-Za-z0-9_]+_ci", name) or name == environ.get("PUBBURI_DB_NAME"):
        raise RuntimeError("Separate disposable relay database required")
    url = urlsplit(environ["PUBBURI_DB_URL"][5:])
    result = dict(environ, PUBBURI_DB_NAME=name, PUBBURI_DB_URL="jdbc:" + urlunsplit(url._replace(path="/" + name)),
                  PUBBURI_SMOKE_RUN_TOKEN=environ["PUBBURI_SMOKE_RUN_TOKEN"] + "-relay")
    require_disposable_database(result)
    return result


def event(product_id, version=1, deleted=False):
    return {"productId": product_id, "sourceVersion": version, "deleted": deleted,
        "document": None if deleted else {"name": "Authored relay fixture", "type": "fixture", "priceKrw": 1000,
            "abvPercent": 5.555, "orderCount": 0, "tasteTags": None, "aromaTags": []}}


def insert_event(connection, value):
    with connection.cursor() as cursor:
        cursor.execute("""INSERT INTO t_product_search_version(product_id,source_version) VALUES(%s,%s) AS incoming
            ON DUPLICATE KEY UPDATE source_version=GREATEST(t_product_search_version.source_version,incoming.source_version)""",
            (value["productId"], value["sourceVersion"]))
        cursor.execute("INSERT INTO t_product_search_outbox(product_id,source_version,deleted,payload) VALUES(%s,%s,%s,%s)",
            (value["productId"], value["sourceVersion"], value["deleted"], json.dumps(value)))
        event_id = cursor.lastrowid
        cursor.execute("INSERT INTO t_product_search_delivery(event_id) VALUES(%s)", (event_id,))
    return event_id


def verify_non_tls_rejected(settings):
    observed = []
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0)); listener.listen(1); listener.settimeout(3)
        port = listener.getsockname()[1]
        def handshake():
            try:
                peer, _ = listener.accept()
                with peer:
                    peer.settimeout(3)
                    # A valid authored MySQL greeting with no CLIENT.SSL capability.
                    flags = 0x200 | 0x8000 | 0x80000
                    body = (b"\x0a8.4.fixture\0" + struct.pack("<I", 1) + b"12345678\0"
                        + struct.pack("<H", flags & 65535) + b"\x2d\x02\x00"
                        + struct.pack("<H", flags >> 16) + b"\x15" + b"\0" * 10
                        + b"abcdefghijklm" + b"caching_sha2_password\0")
                    peer.sendall(len(body).to_bytes(3, "little") + b"\0" + body)
                    try: observed.append(peer.recv(1))
                    except ConnectionResetError: observed.append(b"")
            except OSError:
                pass
        worker = threading.Thread(target=handshake, daemon=True); worker.start()
        try:
            try:
                connect_database(dict(settings, host="127.0.0.1", port=port))
                raise AssertionError("Non-TLS peer accepted")
            except Exception as error:
                assert getattr(error, "args", (None,))[0] == 2026 and "SSL is required" in str(error)
        finally:
            worker.join(4)
        assert not worker.is_alive() and observed == [b""], "Auth was sent before required TLS"


def exercise(http, endpoint, private, environ):
    require_disposable_database(environ)
    queue_env = queue_environment(environ)
    settings = database_settings(queue_env)
    verify_non_tls_rejected(settings)
    connect = lambda: connect_database(settings)
    connection = connect()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SHOW TABLES")
            if [next(iter(row.values())) for row in cursor.fetchall()] != ["pubburi_ci_smoke_guard"]:
                raise RuntimeError("Relay fixture schema is not empty; refusing to adopt or reset it")
    finally:
        connection.close()
    ddl = Path("docs/migrations/007-product-search-outbox.sql").read_text().split("-- Current state only:")[0]
    ddl += "\n" + Path("docs/migrations/008-search-outbox-delivery.sql").read_text()
    subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD", queue_env["PUBBURI_SMOKE_CONTAINER_ID"],
        "mysql", "-u" + queue_env["PUBBURI_DB_USER"], "--default-character-set=utf8mb4", queue_env["PUBBURI_DB_NAME"]],
        input=ddl, capture_output=True, text=True, check=True,
        env=dict(queue_env, MYSQL_PWD=queue_env["PUBBURI_DB_PASSWORD"]))
    store = Store(connect); source_id = store.source_id()
    index = "pubburi-products-v1-relay"
    engine = Engine(http, index, "backend_product_events", source_id, refresh="false"); engine.ready(create=True)
    fingerprint = store.bind(target_identity(engine, source_id))
    relay = Relay(store, engine, source_id)

    def add(value):
        connection = connect()
        try:
            result = insert_event(connection, value); connection.commit(); return result
        finally:
            connection.close()

    def sql(statement, args=()):
        with store.transaction() as cursor:
            cursor.execute(statement, args)
            return cursor.fetchall() if cursor.description else cursor.rowcount

    # A lower auto-ID remains uncommitted while a later independent product commits/delivers.
    late = connect()
    try:
        low = insert_event(late, event(101)); high = add(event(102))
        assert high > low and relay.once()["status"] == "delivered"
        assert sql("SELECT status FROM t_product_search_delivery WHERE event_id=%s", (high,))[0]["status"] == "DELIVERED"
        late.commit()
    finally:
        late.close()
    assert relay.once()["status"] == "delivered"
    assert sql("SELECT status FROM t_product_search_delivery WHERE event_id=%s", (low,))[0]["status"] == "DELIVERED"

    # Both claims must be committed before either worker can leave the controlled IO gate.
    pair = [add(event(103)), add(event(104))]
    gate = threading.Barrier(3); release = threading.Event()
    class GatedEngine(Engine):
        def apply(self, value):
            gate.wait(10)
            if not release.wait(10): raise RuntimeError("Owned IO gate timed out")
            return super().apply(value)
    def worker(): return Relay(Store(connect), GatedEngine(http, index, "backend_product_events", source_id, refresh="false"), source_id).once()
    with ThreadPoolExecutor(max_workers=2) as pool:
        workers = [pool.submit(worker) for _ in range(2)]
        try:
            gate.wait(10)
            assert len(sql("SELECT event_id FROM t_product_search_delivery WHERE status='IN_FLIGHT'")) == 2
            sql("SELECT event_id FROM t_product_search_delivery WHERE event_id IN (%s,%s) FOR UPDATE NOWAIT", tuple(pair))
        finally:
            release.set()
        assert all(worker.result(timeout=20)["status"] == "delivered" for worker in workers)

    # Real HTTP error bodies cannot turn a permanent 4xx into a blind watch retry.
    status = [400]
    class ErrorHandler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_PUT(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            self.send_response(status[0]); self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", "9"); self.end_headers(); self.wfile.write(b"untrusted")
    errors = ThreadingHTTPServer(("127.0.0.1", 0), ErrorHandler)
    worker = threading.Thread(target=errors.serve_forever, daemon=True); worker.start()
    rejected_http = Http("http://127.0.0.1:" + str(errors.server_port), status_guard=relay_status)
    class ErrorWriteHttp:
        def request(self, method, path, body=None):
            return (rejected_http if method == "PUT" else http).request(method, path, body)
    try:
        for number, value in enumerate((400, 429, 503)):
            pending = add(event(201 + number)); status[0] = value
            failed = Relay(store, Engine(ErrorWriteHttp(), index, "backend_product_events", source_id, refresh="false"), source_id).once()
            expected = "relay_engine_request_rejected" if value == 400 else "relay_engine_unavailable"
            assert failed == {"status": "retry", "error_code": expected}
            row = sql("SELECT status,last_error,delivered_at FROM t_product_search_delivery WHERE event_id=%s", (pending,))[0]
            assert row == {"status": "PENDING", "last_error": expected, "delivered_at": None}
            sql("UPDATE t_product_search_delivery SET available_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE event_id=%s", (pending,))
            assert relay.once()["status"] == "delivered"  # Explicit repair of the controlled error origin.
    finally:
        errors.shutdown(); errors.server_close(); worker.join(2)

    # Persisted retry/backoff lets a higher tombstone overtake a temporarily failed old update.
    old = add(event(105))
    with socket.socket() as refusal:
        refusal.bind(("127.0.0.1", 0))  # Owned, reserved, intentionally non-listening TCP origin.
        unavailable = Http("http://127.0.0.1:" + str(refusal.getsockname()[1]))
        class FailedWriteHttp:
            def request(self, method, path, body=None):
                return (unavailable if method == "PUT" else http).request(method, path, body)
        failed = Relay(store, Engine(FailedWriteHttp(), index, "backend_product_events", source_id, refresh="false"), source_id).once()
        assert failed == {"status": "retry", "error_code": "engine_transport_failed"}
    due = sql("SELECT status, available_at>NOW(6) AS retry_due_later, last_error FROM t_product_search_delivery WHERE event_id=%s", (old,))[0]
    assert due == {"status": "PENDING", "retry_due_later": 1, "last_error": "engine_transport_failed"}
    deleted = add(event(105, 2, True)); assert relay.once()["outcome"] == "applied"
    sql("UPDATE t_product_search_delivery SET available_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE event_id=%s", (old,))
    assert relay.once()["outcome"] == "stale_ignored"
    assert http.request("GET", f"/{index}/_doc/105")[1]["_source"] == document(event(105, 2, True))

    # Expiry/reclaim fences the old token for both ack and retry.
    fenced_id = add(event(106)); first = store.claim(fingerprint, source_id)
    sql("UPDATE t_product_search_delivery SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE event_id=%s", (fenced_id,))
    second = store.claim(fingerprint, source_id)
    assert first.token != second.token and not store.ack(first) and not store.retry(first, "engine_transport_failed")
    assert engine.apply(document(event(106))) == "applied" and store.ack(second)

    # Kill an actual worker after engine acknowledgement but before DB ack; restart redelivers a duplicate.
    crashed_id = add(event(107))
    child_env = dict(queue_env, PUBBURI_RELAY_ENDPOINT=endpoint, PUBBURI_RELAY_INDEX=index)
    child_env.pop("PUBBURI_DB_ROOT_PASSWORD", None); child_env.pop("MYSQL_PWD", None)
    code = """import os,sys
sys.path.insert(0,'scripts')
from search_index import Engine,Http
from search_relay import Store,Relay,database_settings,connect_database
store=Store(lambda:connect_database(database_settings(os.environ))); source=store.source_id()
class Crash(Engine):
 def apply(self,value):
  super().apply(value)
  os._exit(23)
engine=Crash(Http(os.environ['PUBBURI_RELAY_ENDPOINT']),os.environ['PUBBURI_RELAY_INDEX'],'backend_product_events',source,refresh='false')
Relay(store,engine,source).once()
"""
    with (private / "relay-crash.log").open("wb") as log:
        process = subprocess.Popen([sys.executable, "-c", code], env=child_env, stdout=log, stderr=subprocess.STDOUT)
        (private / "relay-worker.json").write_text(json.dumps({"pid": process.pid, "phase": "running"}))
        try:
            assert process.wait(timeout=45) == 23
        finally:
            if process.poll() is None: process.kill(); process.wait(timeout=5)
            (private / "relay-worker.json").write_text(json.dumps({"pid": None, "phase": "stopped"}))
    assert sql("SELECT status FROM t_product_search_delivery WHERE event_id=%s", (crashed_id,))[0]["status"] == "IN_FLIGHT"
    sql("UPDATE t_product_search_delivery SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE event_id=%s", (crashed_id,))
    restarted = subprocess.run([sys.executable, "scripts/search_relay.py", "once", "--endpoint", endpoint, "--index", index],
        env=child_env, capture_output=True, text=True, timeout=60)
    assert restarted.returncode == 0 and json.loads(restarted.stdout) == {"status": "delivered", "outcome": "duplicate"}
    # A DB target change cannot race through ack while an old worker still owns its token.
    pending = add(event(108)); claim = store.claim(fingerprint, source_id)
    assert engine.apply(document(event(108))) == "applied"
    sql("UPDATE t_product_search_relay_target SET fingerprint=%s WHERE singleton_id=1", ("0" * 64,))
    try:
        try: store.ack(claim); raise AssertionError("Changed DB binding allowed acknowledgement")
        except SearchError as error: assert str(error) == "relay_target_identity_mismatch"
    finally:
        sql("UPDATE t_product_search_relay_target SET fingerprint=%s WHERE singleton_id=1", (fingerprint,))
    assert store.retry(claim, "engine_transport_failed")
    sql("UPDATE t_product_search_delivery SET available_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE event_id=%s", (pending,))
    assert relay.once()["outcome"] == "duplicate"
    assert relay.once()["status"] == "idle"
    other = Engine(http, index, "backend_product_events", "4b9d3279-297c-4e3a-a3ee-56be0eec1ca7")
    try: other.ready(); raise AssertionError("Foreign source was accepted")
    except SearchError as error: assert str(error) == "owned_strict_mapping_required"
    return dict(settings=settings, source_id=source_id, index=index, deleted=deleted)


def consume_business(http, endpoint, private, environ):
    require_disposable_database(environ)
    settings = database_settings(environ)
    store = Store(lambda: connect_database(settings)); source_id = store.source_id()
    engine = Engine(http, "pubburi-products-v1-relaybusiness", "backend_product_events", source_id, refresh="false")
    engine.ready(create=True)
    with store.transaction() as cursor:
        cursor.execute("SELECT COUNT(*) AS n FROM t_product_search_outbox o LEFT JOIN t_product_search_delivery d ON d.event_id=o.event_id WHERE d.event_id IS NULL")
        assert cursor.fetchone()["n"] == 0
        cursor.execute("SELECT COUNT(*) AS n FROM t_product_search_delivery")
        total = cursor.fetchone()["n"]; assert 0 < total <= 1000
    child_env = dict(environ); child_env.pop("PUBBURI_DB_ROOT_PASSWORD", None); child_env.pop("MYSQL_PWD", None)
    with (private / "relay-business.log").open("wb") as log:
        process = subprocess.Popen([sys.executable, "scripts/search_relay.py", "watch", "--endpoint", endpoint, "--index", engine.index],
            env=child_env, stdout=log, stderr=subprocess.STDOUT)
        record = private / "relay-business-worker.json"
        record.write_text(json.dumps({"pid": process.pid, "phase": "running"}))
        try:
            import time
            end = time.monotonic() + 120
            while True:
                assert process.poll() is None, "Owned business relay exited; see private log"
                with store.transaction() as cursor:
                    cursor.execute("SELECT COUNT(*) AS n FROM t_product_search_delivery WHERE status!='DELIVERED'")
                    if cursor.fetchone()["n"] == 0: break
                if time.monotonic() >= end: raise RuntimeError("Owned business relay did not drain its bounded fixture")
                time.sleep(.2)
            process.send_signal(signal.SIGINT)
            assert process.wait(timeout=10) == 0
        finally:
            if process.poll() is None: process.terminate(); process.wait(timeout=10)
            record.write_text(json.dumps({"pid": None, "phase": "stopped"}))
    from smoke_search import outbox_fixture
    fixture = outbox_fixture(environ["PUBBURI_SEARCH_OUTBOX_INPUT"])
    final = fixture[-1]
    assert http.request("GET", f"/{engine.index}/_doc/{final['product_id']}")[1]["_source"] == final
    with store.transaction() as cursor:
        cursor.execute("""SELECT COUNT(*) AS n FROM t_product_search_outbox o
            JOIN t_product_search_delivery d ON d.event_id=o.event_id
            WHERE o.product_id=%s AND d.status='DELIVERED'""", (final["product_id"],))
        assert cursor.fetchone()["n"] == len(fixture)
    return dict(settings=settings, source_id=source_id, index=engine.index, final=final, delivered=total)


def business_after_restart(http, state):
    store = Store(lambda: connect_database(state["settings"]))
    engine = Engine(http, state["index"], "backend_product_events", state["source_id"], refresh="false")
    assert Relay(store, engine, state["source_id"]).once()["status"] == "idle"
    assert http.request("GET", f"/{engine.index}/_doc/{state['final']['product_id']}")[1]["_source"] == state["final"]


def after_restart(http, state):
    store = Store(lambda: connect_database(state["settings"]))
    engine = Engine(http, state["index"], "backend_product_events", state["source_id"], refresh="false")
    assert Relay(store, engine, state["source_id"]).once()["status"] == "idle"
    assert http.request("GET", f"/{engine.index}/_doc/105")[1]["_source"] == document(event(105, 2, True))
    connection = store.connect()
    try:
        pending = insert_event(connection, event(109)); connection.commit()
    finally:
        connection.close()
    # Replace only this owned disposable physical index during IO, before DB ack.
    class ReplacedDuringWrite(Engine):
        def apply(self, value):
            result = super().apply(value)
            assert http.request("DELETE", f"/{self.index}")[0] == 200
            Engine(http, self.index, "backend_product_events", state["source_id"], refresh="false").ready(create=True)
            return result
    report = Relay(store, ReplacedDuringWrite(http, state["index"], "backend_product_events", state["source_id"], refresh="false"), state["source_id"]).once()
    assert report == {"status": "retry", "error_code": "relay_target_changed_during_delivery"}
    with store.transaction() as cursor:
        cursor.execute("SELECT status, delivered_at FROM t_product_search_delivery WHERE event_id=%s", (pending,))
        row = cursor.fetchone(); assert row == {"status": "PENDING", "delivered_at": None}
    try:
        Relay(store, engine, state["source_id"]).once()
        raise AssertionError("A new physical index reused old acknowledgements")
    except SearchError as error:
        assert str(error) == "relay_target_identity_mismatch"

"""Actual PostgreSQL/engine relay contracts in explicitly reserved disposable fixtures.

Resource creation/restart belongs to smoke_search's owner. This module never resets a
source UUID, existing ACK, sequence, schema, or immutable event to make a rerun pass.
"""
from concurrent.futures import ThreadPoolExecutor
import hashlib
from http.cookiejar import CookieJar
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
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from postgresql_schema import verify_owned_postgres
from search_index import Engine, Http, SearchError, document, decode
from search_relay import PostgresStore, Relay, connect_database, database_settings, target_identity, relay_status, claim_document
from relay_smoke import event
from jpa_smoke import child_environment, request_json, verify_jar, cleanup_on_termination, defer_termination


def settings(environ, control=False):
    return database_settings(dict(environ, PUBBURI_DB_URL=environ["PUBBURI_PG_URL"],
        PUBBURI_DB_USER=environ["PUBBURI_RELAY_CONTROL_USER" if control else "PUBBURI_RELAY_USER"],
        PUBBURI_DB_PASSWORD=environ["PUBBURI_RELAY_CONTROL_PASSWORD" if control else "PUBBURI_RELAY_PASSWORD"],
        PUBBURI_RELAY_DB_CA=environ["PUBBURI_PG_CA"]))


def fixture(environ):
    if environ.get("PUBBURI_PG_RELAY_MUTATING") != "1":
        raise ValueError("postgresql_relay_explicit_fixture_required")
    verify_owned_postgres(environ)
    runtime = settings(environ)
    control = settings(environ, True)
    if runtime["user"] == control["user"]:
        raise ValueError("postgresql_relay_separate_control_required")
    store = PostgresStore(lambda: connect_database(runtime))
    owner = PostgresStore(lambda: connect_database(control))
    with owner.transaction() as cursor:
        cursor.execute("SELECT token FROM public.pubburi_pg_migration_guard")
        if cursor.fetchall() != [{"token": environ["PUBBURI_PG_RUN_TOKEN"]}]:
            raise ValueError("postgresql_relay_marker_rejected")
    with store.transaction() as cursor:
        cursor.execute("SELECT current_setting('server_version_num') AS version, current_setting('transaction_isolation') AS isolation, "
                       "current_setting('TimeZone') AS timezone, current_setting('search_path') AS path, "
                       "(SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()) AS tls")
        row = cursor.fetchone()
        assert row == {"version": "180006", "isolation": "read committed", "timezone": "UTC", "path": "pubburi,pg_catalog", "tls": True}
        cursor.execute("SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=current_user")
        assert not any(cursor.fetchone().values())
        cursor.execute("SELECT has_schema_privilege(current_user,'pubburi','CREATE') AS ddl, "
                       "has_database_privilege(current_user,current_database(),'TEMP') AS temp")
        assert cursor.fetchone() == {"ddl": False, "temp": False}
    for statement in ("UPDATE t_product_search_outbox SET payload=payload WHERE false",
                      "DELETE FROM t_product_search_outbox WHERE false",
                      "INSERT INTO t_product_search_outbox(product_id,source_version,deleted,payload) VALUES(1,1,true,'{}')",
                      "UPDATE t_product_search_delivery SET event_id=event_id WHERE false",
                      "DELETE FROM t_product_search_delivery WHERE false",
                      "UPDATE t_product_search_relay_target SET source_id=source_id WHERE false",
                      "SELECT * FROM t_product", "CREATE TABLE pubburi.ci_relay_forbidden(id integer)"):
        try:
            with store.transaction() as cursor: cursor.execute(statement)
        except Exception as error:
            if getattr(error, "sqlstate", None) != "42501": raise
        else: raise AssertionError("Relay runtime accepted an ungranted operation")
    return store, owner, runtime


def sql(store, statement, args=()):
    with store.transaction() as cursor:
        cursor.execute(statement, args)
        return cursor.fetchall() if cursor.description else cursor.rowcount


def verify_tls(runtime, private):
    """Cold real verify-full; wrong CA/name and non-TLS fail before authentication."""
    import psycopg
    wrong = private / "wrong-relay-ca.pem"
    key = private / "wrong-relay-ca.key"
    try:
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-sha256",
            "-subj", "/CN=Authored wrong relay test CA", "-keyout", str(key), "-out", str(wrong)],
            capture_output=True, check=True, timeout=15)
        try: connect_database(dict(runtime, ssl_ca=str(wrong)))
        except psycopg.OperationalError: pass
        else: raise AssertionError("Wrong PostgreSQL CA accepted")
        # Address the same reserved peer but demand a name absent from its explicit SAN.
        try:
            with psycopg.connect(host="wrong-relay-name.invalid", hostaddr=runtime["host"], port=runtime["port"],
                dbname=runtime["database"], user=runtime["user"], password=runtime["password"],
                connect_timeout=2, sslmode="verify-full", sslrootcert=runtime["ssl_ca"], gssencmode="disable"):
                raise AssertionError("Wrong PostgreSQL SAN accepted")
        except psycopg.OperationalError as error:
            assert "does not match host name" in str(error), "Wrong-name test failed outside certificate identity validation"
    finally:
        key.unlink(missing_ok=True); wrong.unlink(missing_ok=True)
    observed = []
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0)); listener.listen(1); listener.settimeout(3)
        def peer():
            try:
                connection, _ = listener.accept()
                with connection:
                    connection.settimeout(3)
                    request = bytearray()
                    while len(request) < 8:
                        chunk = connection.recv(8-len(request))
                        if not chunk: break
                        request.extend(chunk)
                    observed.append(bytes(request)); connection.sendall(b"N")
                    try: observed.append(connection.recv(1))
                    except ConnectionResetError: observed.append(b"")
            except OSError: pass
        worker = threading.Thread(target=peer, daemon=True); worker.start()
        try:
            try: connect_database(dict(runtime, host="127.0.0.1", port=listener.getsockname()[1]))
            except psycopg.OperationalError: pass
            else: raise AssertionError("Non-TLS PostgreSQL peer accepted")
        finally: worker.join(4)
        assert not worker.is_alive() and observed == [struct.pack("!II", 8, 80877103), b""], "Auth sent before required TLS"


def insert_event(connection, value, raw=None):
    with connection.cursor() as cursor:
        cursor.execute("""INSERT INTO t_product_search_version(product_id,source_version) VALUES(%s,%s)
            ON CONFLICT(product_id) DO UPDATE SET source_version=GREATEST(t_product_search_version.source_version,EXCLUDED.source_version)""",
            (value["productId"], value["sourceVersion"]))
        cursor.execute("INSERT INTO t_product_search_outbox(product_id,source_version,deleted,payload) VALUES(%s,%s,%s,%s::json) RETURNING event_id",
            (value["productId"], value["sourceVersion"], value["deleted"], raw if raw is not None else json.dumps(value)))
        event_id = cursor.fetchone()["event_id"]
        cursor.execute("INSERT INTO t_product_search_delivery(event_id) VALUES(%s)", (event_id,))
    return event_id


def add(owner, value, raw=None):
    with owner.transaction() as cursor:
        return insert_event(cursor.connection, value, raw)


def wait_lock(runtime, pid):
    observer = PostgresStore(lambda: connect_database(runtime))
    end = time.monotonic() + 3
    while time.monotonic() < end:
        rows = sql(observer, "SELECT wait_event_type FROM pg_stat_activity WHERE pid=%s", (pid,))
        if rows and rows[0]["wait_event_type"] == "Lock": return
        time.sleep(.02)
    raise AssertionError("Controlled PostgreSQL lock wait was not observed")


def blocked_call(store, owner, runtime, statement, action, hold_until):
    """Execute an actual call begun before its target/delivery lock is released."""
    ready = threading.Event(); pids = []
    class Observed(PostgresStore):
        def begin(self, connection):
            super().begin(connection); pids.append(connection.info.backend_pid); ready.set()
    delayed = Observed(store.connect)
    with ThreadPoolExecutor(max_workers=1) as pool:
        with owner.transaction() as cursor:
            cursor.execute(statement)
            future = pool.submit(action, delayed)
            assert ready.wait(3); wait_lock(runtime, pids[0])
            while True:
                cursor.execute("SELECT clock_timestamp()>%s AS expired", (hold_until,))
                if cursor.fetchone()["expired"]: break
                time.sleep(.02)
            # Context commit releases the fixed owned lock before joining.
        return future.result(timeout=8)


@cleanup_on_termination()
def exercise(http, endpoint, private, environ, index="pubburi-products-v1-pgqueue"):
    store, owner, runtime = fixture(environ)
    verify_tls(runtime, private)
    assert sql(owner, "SELECT COUNT(*) AS n FROM t_product_search_version")[0]["n"] == 0
    assert sql(owner, "SELECT COUNT(*) AS n FROM t_product_search_outbox")[0]["n"] == 0
    assert sql(owner, "SELECT COUNT(*) AS n FROM t_product_search_delivery")[0]["n"] == 0
    assert sql(owner, "SELECT fingerprint FROM t_product_search_relay_target")[0]["fingerprint"] is None
    source_id = store.source_id()
    engine = Engine(http, index, "backend_product_events", source_id, refresh="false"); engine.ready(create=True)
    identity = target_identity(engine, source_id)
    # First binding waits in an older TX; its audit time is the actual lock-release time.
    future_time = sql(owner, "SELECT clock_timestamp()+INTERVAL '0.35 seconds' AS t")[0]["t"]
    fingerprint = blocked_call(store, owner, runtime, "SELECT singleton_id FROM t_product_search_relay_target WHERE singleton_id=1 FOR UPDATE",
        lambda delayed: delayed.bind(identity), future_time)
    assert sql(owner, "SELECT bound_at>%s AS recent FROM t_product_search_relay_target", (future_time,))[0]["recent"]
    relay = Relay(store, engine, source_id)

    # Native json text/UUID/boolean conversion is lossless at the unchanged strict wire boundary.
    raw = json.dumps(event(1001), ensure_ascii=False, indent=2) + "\n"
    raw_id = add(owner, event(1001), raw)
    claim = store.claim(fingerprint, source_id)
    assert claim.event_id == raw_id and claim.row["payload"] == raw and type(claim.row["deleted"]) is int
    assert str(uuid.UUID(claim.token)) == claim.token and claim.source_id == source_id
    assert engine.apply(claim_document(claim)) == "applied" and store.ack(claim)
    assert sql(owner, "SELECT payload::text AS raw FROM t_product_search_outbox WHERE event_id=%s", (raw_id,))[0]["raw"] == raw

    # Lower uncommitted identity is not lost behind a later committed ID.
    late = owner.connect(); late.execute("BEGIN ISOLATION LEVEL READ COMMITTED")
    try:
        low = insert_event(late, event(1002)); high = add(owner, event(1003))
        assert high > low and relay.once()["status"] == "delivered"
        assert sql(owner, "SELECT status FROM t_product_search_delivery WHERE event_id=%s", (high,))[0]["status"] == "DELIVERED"
        late.commit()
    finally: late.close()
    assert relay.once()["status"] == "delivered"

    ack_id = add(owner, event(1010)); claim = store.claim(fingerprint, source_id)
    assert engine.apply(claim_document(claim)) == "applied"
    expiry = sql(owner, "UPDATE t_product_search_delivery SET lease_until=clock_timestamp()+INTERVAL '0.35 seconds' WHERE event_id=%s RETURNING lease_until", (ack_id,))[0]["lease_until"]
    assert blocked_call(store, owner, runtime, "SELECT event_id FROM t_product_search_delivery WHERE event_id=" + str(ack_id) + " FOR UPDATE",
        lambda delayed: delayed.ack(claim), expiry) is False
    assert relay.once()["outcome"] == "duplicate"
    assert sql(owner, "SELECT status FROM t_product_search_delivery WHERE event_id=%s", (low,))[0]["status"] == "DELIVERED"

    pair = [add(owner, event(1004)), add(owner, event(1005))]
    gate = threading.Barrier(3); release = threading.Event()
    class GatedEngine(Engine):
        def apply(self, value):
            gate.wait(8)
            if not release.wait(8): raise RuntimeError("Owned relay IO gate timed out")
            return super().apply(value)
    def worker(): return Relay(PostgresStore(store.connect), GatedEngine(http, index, "backend_product_events", source_id, refresh="false"), source_id).once()
    with ThreadPoolExecutor(max_workers=2) as pool:
        workers = [pool.submit(worker) for _ in range(2)]
        try:
            gate.wait(8)
            assert len(sql(owner, "SELECT event_id FROM t_product_search_delivery WHERE status='IN_FLIGHT'")) == 2
            sql(owner, "SELECT event_id FROM t_product_search_delivery WHERE event_id IN (%s,%s) FOR UPDATE NOWAIT", tuple(pair))
            sql(owner, "SELECT singleton_id FROM t_product_search_relay_target FOR UPDATE NOWAIT")
        finally: release.set()
        assert all(item.result(timeout=15)["status"] == "delivered" for item in workers)

    # A real locked row is skipped, rather than serializing both claims behind it.
    locked_id = add(owner, event(1006)); available_id = add(owner, event(1007))
    with owner.transaction() as cursor:
        cursor.execute("SELECT event_id FROM t_product_search_delivery WHERE event_id=%s FOR UPDATE", (locked_id,))
        claim = store.claim(fingerprint, source_id); assert claim.event_id == available_id
        assert engine.apply(claim_document(claim)) == "applied" and store.ack(claim)
    assert relay.once()["status"] == "delivered"

    # Due/lease timestamps must be evaluated after an older transaction's target wait.
    clock_id = add(owner, event(1008))
    due = sql(owner, "UPDATE t_product_search_delivery SET available_at=clock_timestamp()+INTERVAL '0.35 seconds' WHERE event_id=%s RETURNING available_at", (clock_id,))[0]["available_at"]
    claim = blocked_call(store, owner, runtime, "SELECT singleton_id FROM t_product_search_relay_target WHERE singleton_id=1 FOR UPDATE",
        lambda delayed: delayed.claim(fingerprint, source_id), due)
    assert claim is not None and claim.event_id == clock_id
    assert sql(owner, "SELECT lease_until>clock_timestamp()+INTERVAL '118 seconds' AS fresh FROM t_product_search_delivery WHERE event_id=%s", (clock_id,))[0]["fresh"]
    assert engine.apply(claim_document(claim)) == "applied"
    expiry = sql(owner, "UPDATE t_product_search_delivery SET lease_until=clock_timestamp()+INTERVAL '0.35 seconds' WHERE event_id=%s RETURNING lease_until", (clock_id,))[0]["lease_until"]
    assert blocked_call(store, owner, runtime, "SELECT singleton_id FROM t_product_search_relay_target WHERE singleton_id=1 FOR UPDATE",
        lambda delayed: delayed.ack(claim), expiry) is False
    newer = store.claim(fingerprint, source_id)
    assert newer.token != claim.token and not store.ack(claim) and not store.retry(claim, "engine_transport_failed")
    assert engine.apply(claim_document(newer)) == "duplicate" and store.ack(newer)
    retry_id = add(owner, event(1009)); claim = store.claim(fingerprint, source_id)
    expiry = sql(owner, "UPDATE t_product_search_delivery SET lease_until=clock_timestamp()+INTERVAL '0.35 seconds' WHERE event_id=%s RETURNING lease_until", (retry_id,))[0]["lease_until"]
    assert blocked_call(store, owner, runtime, "SELECT event_id FROM t_product_search_delivery WHERE event_id=" + str(retry_id) + " FOR UPDATE",
        lambda delayed: delayed.retry(claim, "engine_transport_failed"), expiry) is False
    assert relay.once()["status"] == "delivered"

    status = [400]
    class ErrorHandler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_PUT(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            self.send_response(status[0]); self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", "9"); self.end_headers(); self.wfile.write(b"untrusted")
    errors = ThreadingHTTPServer(("127.0.0.1", 0), ErrorHandler)
    worker = threading.Thread(target=errors.serve_forever, daemon=True); worker.start()
    rejected = Http("http://127.0.0.1:" + str(errors.server_port), status_guard=relay_status)
    class ErrorWriteHttp:
        def request(self, method, path, body=None): return (rejected if method == "PUT" else http).request(method, path, body)
    try:
        for number, value in enumerate((400, 429, 503)):
            pending = add(owner, event(1101 + number)); status[0] = value
            failed = Relay(store, Engine(ErrorWriteHttp(), index, "backend_product_events", source_id, refresh="false"), source_id).once()
            expected = "relay_engine_request_rejected" if value == 400 else "relay_engine_unavailable"
            assert failed == {"status": "retry", "error_code": expected}
            assert sql(owner, "SELECT status,last_error,delivered_at,available_at>clock_timestamp() AS later FROM t_product_search_delivery WHERE event_id=%s", (pending,))[0] == {
                "status": "PENDING", "last_error": expected, "delivered_at": None, "later": True}
            sql(owner, "UPDATE t_product_search_delivery SET available_at=clock_timestamp()-INTERVAL '1 second' WHERE event_id=%s", (pending,))
            assert relay.once()["status"] == "delivered"
    finally: errors.shutdown(); errors.server_close(); worker.join(2)

    old = add(owner, event(1201))
    with socket.socket() as refusal:
        refusal.bind(("127.0.0.1", 0))
        unavailable = Http("http://127.0.0.1:" + str(refusal.getsockname()[1]))
        class FailedWriteHttp:
            def request(self, method, path, body=None): return (unavailable if method == "PUT" else http).request(method, path, body)
        assert Relay(store, Engine(FailedWriteHttp(), index, "backend_product_events", source_id, refresh="false"), source_id).once() == {"status": "retry", "error_code": "engine_transport_failed"}
    add(owner, event(1201, 2, True)); assert relay.once()["outcome"] == "applied"
    sql(owner, "UPDATE t_product_search_delivery SET available_at=clock_timestamp()-INTERVAL '1 second' WHERE event_id=%s", (old,))
    assert relay.once()["outcome"] == "stale_ignored"
    assert http.request("GET", f"/{index}/_doc/1201")[1]["_source"] == document(event(1201, 2, True))

    saturated = add(owner, event(1202))
    sql(owner, "UPDATE t_product_search_delivery SET attempts=2147483647 WHERE event_id=%s", (saturated,))
    claim = store.claim(fingerprint, source_id); assert claim.attempts == 2147483647
    assert store.retry(claim, "unsafe raw / credential")
    assert sql(owner, "SELECT attempts,last_error,available_at BETWEEN clock_timestamp()+INTERVAL '58 seconds' AND clock_timestamp()+INTERVAL '61 seconds' AS bounded FROM t_product_search_delivery WHERE event_id=%s", (saturated,))[0] == {"attempts": 2147483647, "last_error": "relay_delivery_failed", "bounded": True}
    sql(owner, "UPDATE t_product_search_delivery SET available_at=clock_timestamp()-INTERVAL '1 second' WHERE event_id=%s", (saturated,))
    assert relay.once()["status"] == "delivered"

    crashed = add(owner, event(1301))
    child = {key: value for key, value in os.environ.items() if key in ("PATH", "HOME", "LANG", "LC_ALL")}
    child.update(PUBBURI_DB_URL=environ["PUBBURI_PG_URL"], PUBBURI_DB_USER=runtime["user"], PUBBURI_DB_PASSWORD=runtime["password"],
        PUBBURI_RELAY_DB_CA=runtime["ssl_ca"], PUBBURI_RELAY_ENDPOINT=endpoint, PUBBURI_RELAY_INDEX=index)
    code = """import os,sys
sys.path.insert(0,'scripts')
from search_index import Engine,Http
from search_relay import Relay,database_settings,database_store
store=database_store(database_settings(os.environ));source=store.source_id()
class Crash(Engine):
 def apply(self,value):
  super().apply(value)
  os._exit(23)
Relay(store,Crash(Http(os.environ['PUBBURI_RELAY_ENDPOINT']),os.environ['PUBBURI_RELAY_INDEX'],'backend_product_events',source,refresh='false'),source).once()
"""
    with (private / "pg-relay-crash.log").open("wb") as log:
        with defer_termination():
            process = subprocess.Popen([sys.executable, "-c", code], env=child, stdout=log, stderr=subprocess.STDOUT)
            crash_record = private / "pg-relay-crash-process.json"
            crash_record.write_text(json.dumps({"pid": process.pid, "stopped": False}))
        try: assert process.wait(timeout=40) == 23
        finally:
            if process.poll() is None: process.kill(); process.wait(timeout=5)
            crash_record.write_text(json.dumps({"pid": process.pid, "stopped": True, "exit_code": process.returncode}))
    assert sql(owner, "SELECT status FROM t_product_search_delivery WHERE event_id=%s", (crashed,))[0]["status"] == "IN_FLIGHT"
    sql(owner, "UPDATE t_product_search_delivery SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE event_id=%s", (crashed,))
    result = subprocess.run([sys.executable, "scripts/search_relay.py", "once", "--endpoint", endpoint, "--index", index], env=child, capture_output=True, text=True, timeout=45)
    assert result.returncode == 0 and json.loads(result.stdout) == {"status": "delivered", "outcome": "duplicate"}

    pending = add(owner, event(1302)); claim = store.claim(fingerprint, source_id)
    assert engine.apply(claim_document(claim)) == "applied"
    sql(owner, "UPDATE t_product_search_relay_target SET fingerprint=%s WHERE singleton_id=1", ("0" * 64,))
    try:
        try: store.ack(claim); raise AssertionError("Changed DB binding accepted an ACK")
        except SearchError as error: assert str(error) == "relay_target_identity_mismatch"
    finally: sql(owner, "UPDATE t_product_search_relay_target SET fingerprint=%s WHERE singleton_id=1", (fingerprint,))
    assert store.retry(claim, "engine_transport_failed")
    sql(owner, "UPDATE t_product_search_delivery SET available_at=clock_timestamp()-INTERVAL '1 second' WHERE event_id=%s", (pending,))
    assert relay.once()["outcome"] == "duplicate" and relay.once()["status"] == "idle"
    other = Engine(http, index, "backend_product_events", str(uuid.uuid4()))
    try: other.ready(); raise AssertionError("Foreign source accepted")
    except SearchError as error: assert str(error) == "owned_strict_mapping_required"
    assert store.source_id() == source_id
    counts = sql(owner, "SELECT status,COUNT(*) AS n FROM t_product_search_delivery GROUP BY status")
    assert len(counts) == 1 and counts[0]["status"] == "DELIVERED"
    return {"environ": environ, "index": index, "source_id": source_id, "delivered": counts[0]["n"],
            "completed_ack_snapshot": completed_ack_snapshot(owner), "crash_pid_stopped": process.pid}


def after_restart(http, endpoint, state, replace_index):
    store, owner, _ = fixture(state["environ"])
    assert completed_ack_snapshot(owner) == state["completed_ack_snapshot"]
    engine = Engine(http, state["index"], "backend_product_events", state["source_id"], refresh="false")
    assert Relay(store, engine, state["source_id"]).once()["status"] == "idle"
    assert http.request("GET", f"/{engine.index}/_doc/1201")[1]["_source"] == document(event(1201, 2, True))
    pending = add(owner, event(1401))
    class Replaced(Engine):
        def apply(self, value):
            result = super().apply(value); replace_index(self.index, state["source_id"]); return result
    report = Relay(store, Replaced(http, engine.index, "backend_product_events", state["source_id"], refresh="false"), state["source_id"]).once()
    assert report == {"status": "retry", "error_code": "relay_target_changed_during_delivery"}
    assert sql(owner, "SELECT status,delivered_at FROM t_product_search_delivery WHERE event_id=%s", (pending,))[0] == {"status": "PENDING", "delivered_at": None}
    try: Relay(store, engine, state["source_id"]).once(); raise AssertionError("Replacement index reused old ACKs")
    except SearchError as error: assert str(error) == "relay_target_identity_mismatch"
    assert store.source_id() == state["source_id"]
    assert completed_ack_snapshot(owner) == state["completed_ack_snapshot"]
    return {"restart_binding_tombstone": True, "replacement_refused": True, "pending": 1}


BUSINESS_TABLES = ("t_user", "t_product", "t_product_feature", "t_product_feature_tag", "t_order", "t_order_detail",
                   "t_comment", "t_stamp", "t_market", "t_user_preference", "t_user_preference_value", "t_user_favorite")


def business_snapshot(owner):
    return {table: sql(owner, 'SELECT row_to_json(t)::text AS raw FROM ' + table + ' t ORDER BY row_to_json(t)::text COLLATE "C"')
            for table in BUSINESS_TABLES}


def completed_ack_snapshot(owner):
    rows = sql(owner, "SELECT row_to_json(d)::text AS raw FROM t_product_search_delivery d WHERE status='DELIVERED' ORDER BY event_id")
    assert rows, "Completed ACK preservation must have a nonempty denominator"
    return {"count": len(rows), "sha256": hashlib.sha256(json.dumps(rows, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()}


def validate_business_events(rows):
    # A storage-only JSON fixture can be valid PostgreSQL json without being Engine wire.
    # Refuse it before claiming anything; never filter, repair, or fake an acknowledgement.
    for row in rows:
        try:
            value = document(decode(row["payload"]))
            if (value["product_id"] != row["product_id"] or value["source_version"] != row["source_version"]
                    or value["deleted"] != row["deleted"]):
                raise SearchError("relay_outbox_metadata_mismatch")
        except (SearchError, KeyError, TypeError, ValueError):
            raise SearchError("pg_relay_business_event_invalid") from None


def consume_business(http, endpoint, private, environ, index="pubburi-products-v1-pgbusiness"):
    """Original packaged JPA producer -> real watch -> real engine, without source row writes."""
    from postgresql_schema import ROOT
    store, owner, runtime = fixture(environ)
    source_id = store.source_id()
    original_events = sql(owner, "SELECT event_id,payload::text AS payload,product_id,source_version,deleted,created_at FROM t_product_search_outbox ORDER BY event_id")
    original_versions = sql(owner, "SELECT product_id,source_version FROM t_product_search_version ORDER BY product_id")
    original_acks = sql(owner, "SELECT row_to_json(d)::text AS raw FROM t_product_search_delivery d WHERE status='DELIVERED' ORDER BY event_id")
    assert 0 < len(original_events) <= 1000
    validate_business_events(original_events)
    assert sql(owner, "SELECT COUNT(*) AS n FROM t_product_search_outbox o LEFT JOIN t_product_search_delivery d USING(event_id) WHERE d.event_id IS NULL")[0]["n"] == 0
    engine = Engine(http, index, "backend_product_events", source_id, refresh="false"); engine.ready(create=True)
    jar = Path(environ.get("PUBBURI_PG_RELAY_JPA_JAR", ROOT / "tools/jpa-rehearsal/target/pubburi-jpa-rehearsal-0.0.1-SNAPSHOT.jar"))
    verify_jar(jar)
    immutable = private / "pg-relay-business-runtime.jar"
    with immutable.open("xb") as target: target.write(jar.read_bytes())
    immutable.chmod(0o600)
    jar_sha = hashlib.sha256(immutable.read_bytes()).hexdigest()
    child = child_environment(environ); child.update(PUBBURI_JPA_PORT="0", PUBBURI_JPA_REHEARSAL="1")
    java = str(Path(child["JAVA_HOME"]) / "bin/java") if child.get("JAVA_HOME") else "java"
    deadline = time.monotonic() + 160
    process = watcher = None; product_id = None; admin = CookieJar(); anonymous = CookieJar()
    state = {"phase": "starting", "jar_sha256": jar_sha, "processes": []}
    record = private / "pg-relay-business-processes.json"
    def save(): record.write_text(json.dumps(state)); record.chmod(0o600)
    def stop(owned):
        if owned is not None and owned.poll() is None:
            owned.terminate()
            try: owned.wait(timeout=10)
            except subprocess.TimeoutExpired: owned.kill(); owned.wait(timeout=5)
        if owned is not None:
            for item in state["processes"]:
                if item["pid"] == owned.pid: item["stopped"] = True
            save()
    def request(method, path, body=None, cookies=admin):
        return request_json(cookies, base, method, path, body, deadline)
    def expect(method, path, body=None, status=200, cookies=admin):
        actual, response = request(method, path, body, cookies)
        assert actual == status, "Owned packaged producer HTTP contract failed"
        return response["data"]
    def drained():
        end = min(deadline, time.monotonic() + 50)
        while time.monotonic() < end:
            assert watcher.poll() is None, "Owned PostgreSQL watch exited; private log retained"
            if sql(owner, "SELECT COUNT(*) AS n FROM t_product_search_delivery WHERE status!='DELIVERED'")[0]["n"] == 0: return
            time.sleep(.1)
        raise AssertionError("Owned bounded PostgreSQL queue did not drain")
    server_log = private / "pg-relay-business-server.log"
    watch_log = private / "pg-relay-business-watch.log"
    with cleanup_on_termination(), server_log.open("wb") as server_output, watch_log.open("wb") as watch_output, socket.socket() as refusal:
        refusal.bind(("127.0.0.1", 0))
        child["PUBBURI_RECOMMENDATION_BASE_URL"] = "http://127.0.0.1:" + str(refusal.getsockname()[1])
        try:
            with defer_termination():
                process = subprocess.Popen([java, "-jar", str(immutable)], env=child, cwd=ROOT, stdout=server_output, stderr=subprocess.STDOUT)
                state["processes"].append({"pid": process.pid, "kind": "packaged-jpa", "stopped": False}); save()
            end = min(deadline, time.monotonic() + 45)
            while time.monotonic() < end:
                assert process.poll() is None, "Owned JPA producer failed startup"
                match = re.search(r"Tomcat started on port (\d+)", server_log.read_text(errors="replace"))
                if match:
                    port = int(match.group(1)); assert port not in (5173, 9987, 8000, 4173)
                    base = "http://127.0.0.1:" + str(port); break
                time.sleep(.1)
            else: raise AssertionError("Owned JPA producer startup deadline")
            expect("POST", "/api/auth/login", {"id": environ["PUBBURI_JPA_ADMIN_ID"], "pass": environ["PUBBURI_JPA_ADMIN_PASSWORD"]})
            before = business_snapshot(owner)
            spec = request("GET", "/v3/api-docs", cookies=anonymous)[1]
            methods = {"get", "put", "post", "delete", "patch", "head", "options", "trace"}
            assert len(spec["paths"]) == 35 and sum(method in methods for path in spec["paths"].values() for method in path) == 42
            watch_env = {key: value for key, value in child.items() if not key.startswith(("PUBBURI_", "PG"))}
            watch_env.update(PUBBURI_DB_URL=environ["PUBBURI_PG_URL"], PUBBURI_DB_USER=runtime["user"], PUBBURI_DB_PASSWORD=runtime["password"], PUBBURI_RELAY_DB_CA=runtime["ssl_ca"])
            with defer_termination():
                watcher = subprocess.Popen([sys.executable, "scripts/search_relay.py", "watch", "--endpoint", endpoint, "--index", index],
                    env=watch_env, cwd=ROOT, stdout=watch_output, stderr=subprocess.STDOUT)
                state["processes"].append({"pid": watcher.pid, "kind": "pg-watch", "stopped": False}); save()
            drained()
            payload = {"name": "ci_jpa_relay_" + uuid.uuid4().hex, "type": "증류주", "price": 1200, "img": "authored.png", "abv": 5.555, "orderCount": 0}
            product_id = expect("POST", "/api/admin/products", payload, 201)["id"]
            payload["price"] = 2300; expect("PUT", "/api/admin/products/" + str(product_id), payload)
            expect("PUT", "/api/admin/products/" + str(product_id) + "/features", {"tasteTags": ["dry"], "aromaTags": [], "sourceName": "Authored relay verification"})
            drained()
            rows = sql(owner, "SELECT payload::text AS payload FROM t_product_search_outbox WHERE product_id=%s ORDER BY source_version", (product_id,))
            latest = document(json.loads(rows[-1]["payload"]))
            assert len(rows) == 3 and latest["price_krw"] == 2300 and latest["taste_tags"] == ["dry"] and latest["aroma_tags"] == []
            assert http.request("GET", f"/{index}/_doc/{product_id}")[1]["_source"] == latest
            expect("DELETE", "/api/admin/products/" + str(product_id)); drained()
            rows = sql(owner, "SELECT payload::text AS payload FROM t_product_search_outbox WHERE product_id=%s ORDER BY source_version", (product_id,))
            final = document(json.loads(rows[-1]["payload"]))
            assert len(rows) == 4 and final["deleted"] is True
            assert http.request("GET", f"/{index}/_doc/{product_id}")[1]["_source"] == final
            assert engine.apply(document(json.loads(rows[0]["payload"]))) == "stale_ignored"
            assert sql(owner, "SELECT COUNT(*) AS n FROM t_product_search_delivery d JOIN t_product_search_outbox o USING(event_id) WHERE o.product_id=%s AND d.status='DELIVERED'", (product_id,))[0]["n"] == 4
            product_id = None
            assert business_snapshot(owner) == before
            ids = [row["event_id"] for row in original_events]
            assert sql(owner, "SELECT event_id,payload::text AS payload,product_id,source_version,deleted,created_at FROM t_product_search_outbox WHERE event_id=ANY(%s::bigint[]) ORDER BY event_id", (ids,)) == original_events
            assert sql(owner, "SELECT product_id,source_version FROM t_product_search_version WHERE product_id=ANY(%s::integer[]) ORDER BY product_id", ([row["product_id"] for row in original_versions],)) == original_versions
            all_acks = sql(owner, "SELECT row_to_json(d)::text AS raw FROM t_product_search_delivery d ORDER BY event_id")
            assert all(row in all_acks for row in original_acks)
            assert store.source_id() == source_id
            watcher.send_signal(signal.SIGINT); assert watcher.wait(timeout=10) == 0
            state["phase"] = "passed"
            return {"environ": environ, "source_id": source_id, "index": index, "final": final,
                    "original_events": len(original_events), "authored_events": 4, "completed_ack_snapshot": completed_ack_snapshot(owner),
                    "jar_sha256": jar_sha, "api_paths": 35, "api_operations": 42}
        finally:
            try:
                if product_id is not None and process is not None and process.poll() is None:
                    expect("DELETE", "/api/admin/products/" + str(product_id))
            finally:
                stop(watcher); stop(process); immutable.unlink(missing_ok=True)


def business_after_restart(http, state):
    store, owner, _ = fixture(state["environ"])
    engine = Engine(http, state["index"], "backend_product_events", state["source_id"], refresh="false")
    assert Relay(store, engine, state["source_id"]).once()["status"] == "idle"
    assert http.request("GET", f"/{engine.index}/_doc/{state['final']['product_id']}")[1]["_source"] == state["final"]
    assert completed_ack_snapshot(owner) == state["completed_ack_snapshot"]

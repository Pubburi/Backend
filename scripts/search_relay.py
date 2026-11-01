#!/usr/bin/env python3
"""Opt-in local durable delivery; never a public search API or an event-ID watermark."""
import argparse
from contextlib import contextmanager
import copy
from dataclasses import dataclass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlsplit
import uuid

from search_index import Engine, Http, SearchError, decode, document, MAX_INPUT_BYTES


def database_settings(environ):
    try:
        raw = environ.get("PUBBURI_DB_URL", "")
        if not raw.startswith("jdbc:mysql://"): raise ValueError()
        url = urlsplit(raw[5:])
        if (not ipaddress.ip_address(url.hostname).is_loopback or not url.port or not 1 <= url.port <= 65535
                or url.username is not None or url.password is not None or url.query or url.fragment
                or not re.fullmatch(r"/[A-Za-z0-9_]+", url.path)
                or not environ.get("PUBBURI_DB_USER") or not environ.get("PUBBURI_DB_PASSWORD")):
            raise ValueError()
        settings = dict(host=url.hostname, port=url.port, database=url.path[1:],
                        user=environ["PUBBURI_DB_USER"], password=environ["PUBBURI_DB_PASSWORD"])
    except (ValueError, TypeError, AttributeError) as error:
        raise SearchError("explicit_loopback_database_required") from error
    ca = environ.get("PUBBURI_RELAY_DB_CA")
    if not ca or not Path(ca).is_file():
        raise SearchError("explicit_local_database_ca_required")
    return dict(settings, ssl_ca=ca)


def connect_database(settings):
    import pymysql  # Only the opt-in relay needs the separately locked environment.
    connection = pymysql.connect(**settings, charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=2, read_timeout=5, write_timeout=5, autocommit=False, local_infile=False,
        ssl_verify_cert=True, ssl_verify_identity=False,
        init_command="SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED")
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION time_zone='+00:00'")
            cursor.execute("SET SESSION innodb_lock_wait_timeout=5")
        connection.commit()
    except BaseException:
        connection.close()
        raise
    return connection


@dataclass(frozen=True)
class Claim:
    event_id: int
    token: str
    attempts: int
    row: dict
    fingerprint: str
    source_id: str


class Store:
    def __init__(self, connection_factory):
        self.connect = connection_factory

    @contextmanager
    def transaction(self):
        connection = self.connect()
        try:
            connection.begin()
            with connection.cursor() as cursor:
                yield cursor
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def source_id(self):
        with self.transaction() as cursor:
            cursor.execute("SELECT source_id FROM t_product_search_relay_target WHERE singleton_id=1")
            row = cursor.fetchone()
            if row is None or str(uuid.UUID(row["source_id"])) != row["source_id"]:
                raise SearchError("relay_source_identity_required")
            return row["source_id"]

    def bind(self, identity):
        encoded = json.dumps(identity, sort_keys=True, separators=(",", ":"))
        fingerprint = hashlib.sha256(encoded.encode()).hexdigest()
        with self.transaction() as cursor:
            cursor.execute("SELECT source_id, fingerprint FROM t_product_search_relay_target WHERE singleton_id=1 FOR UPDATE")
            row = cursor.fetchone()
            if row is None or row["source_id"] != identity["source_id"]:
                raise SearchError("relay_source_identity_mismatch")
            if row["fingerprint"] is None:
                cursor.execute("UPDATE t_product_search_relay_target SET fingerprint=%s, identity_json=%s, bound_at=NOW(6) WHERE singleton_id=1", (fingerprint, encoded))
            elif row["fingerprint"] != fingerprint:
                raise SearchError("relay_target_identity_mismatch")
        return fingerprint

    def fence_target(self, cursor, fingerprint, source_id):
        cursor.execute("SELECT source_id, fingerprint FROM t_product_search_relay_target WHERE singleton_id=1 FOR SHARE")
        row = cursor.fetchone()
        if row is None or row["fingerprint"] != fingerprint or row["source_id"] != source_id:
            raise SearchError("relay_target_identity_mismatch")

    def claim(self, fingerprint, source_id):
        with self.transaction() as cursor:
            self.fence_target(cursor, fingerprint, source_id)
            cursor.execute("""SELECT event_id, attempts FROM t_product_search_delivery
                WHERE (status='PENDING' AND available_at<=NOW(6))
                   OR (status='IN_FLIGHT' AND lease_until<=NOW(6))
                ORDER BY event_id LIMIT 1 FOR UPDATE SKIP LOCKED""")
            pending = cursor.fetchone()
            if pending is None: return None
            token = str(uuid.uuid4())
            attempts = min(pending["attempts"] + 1, 2147483647)
            cursor.execute("""UPDATE t_product_search_delivery SET status='IN_FLIGHT', claim_token=%s,
                lease_until=TIMESTAMPADD(SECOND,120,NOW(6)), attempts=%s WHERE event_id=%s""",
                (token, attempts, pending["event_id"]))
            cursor.execute("""SELECT product_id, source_version, deleted,
                IF(OCTET_LENGTH(payload)<=%s, payload, NULL) AS payload
                FROM t_product_search_outbox WHERE event_id=%s""", (MAX_INPUT_BYTES, pending["event_id"]))
            row = cursor.fetchone()
            if row is None: raise SearchError("relay_outbox_event_missing")
            return Claim(pending["event_id"], token, attempts, row, fingerprint, source_id)

    def ack(self, claim):
        with self.transaction() as cursor:
            self.fence_target(cursor, claim.fingerprint, claim.source_id)
            cursor.execute("""UPDATE t_product_search_delivery SET status='DELIVERED', delivered_at=NOW(6),
                claim_token=NULL, lease_until=NULL, last_error=NULL
                WHERE event_id=%s AND status='IN_FLIGHT' AND claim_token=%s AND lease_until>NOW(6)""",
                (claim.event_id, claim.token))
            return cursor.rowcount == 1

    def retry(self, claim, code):
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", code): code = "relay_delivery_failed"
        delay = min(2 ** min(claim.attempts, 6), 60)
        with self.transaction() as cursor:
            cursor.execute("""UPDATE t_product_search_delivery SET status='PENDING', claim_token=NULL,
                lease_until=NULL, available_at=TIMESTAMPADD(SECOND,%s,NOW(6)), last_error=%s
                WHERE event_id=%s AND status='IN_FLIGHT' AND claim_token=%s AND lease_until>NOW(6)""",
                (delay, code, claim.event_id, claim.token))
            return cursor.rowcount == 1


def target_identity(engine, source_id):
    status, root = engine.http.request("GET", "/")
    if status != 200 or not isinstance(root, dict): raise SearchError("relay_cluster_identity_required")
    status, settings = engine.http.request("GET", f"/{engine.index}/_settings")
    try:
        cluster_id, index_id = root["cluster_uuid"], settings[engine.index]["settings"]["index"]["uuid"]
        if status != 200 or any(not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value) for value in (cluster_id, index_id)):
            raise ValueError()
    except (KeyError, TypeError, ValueError) as error:
        raise SearchError("relay_index_identity_required") from error
    return dict(source_id=source_id, cluster_uuid=cluster_id, index_uuid=index_id,
                index_name=engine.index, schema="products-v1", data_kind="backend_product_events")


def claim_document(claim):
    if claim.row["payload"] is None: raise SearchError("relay_payload_exceeds_limit")
    try:
        value = document(decode(claim.row["payload"]))
    except SearchError as error:
        raise SearchError("relay_outbox_payload_invalid") from error
    if (value["product_id"] != claim.row["product_id"] or value["source_version"] != claim.row["source_version"]
            or type(claim.row["deleted"]) is not int or claim.row["deleted"] not in (0, 1)
            or value["deleted"] != bool(claim.row["deleted"])):
        raise SearchError("relay_outbox_metadata_mismatch")
    return value


def safe_code(error):
    code = str(error) if isinstance(error, SearchError) else "relay_database_or_runtime_failure"
    return code if re.fullmatch(r"[a-z][a-z0-9_]{0,63}", code) else "relay_delivery_failed"


TRANSIENT_CODES = {"engine_transport_failed", "engine_request_deadline", "engine_response_length_mismatch",
                   "relay_engine_unavailable"}


def relay_status(method, path, status):
    if status == 429 or 500 <= status <= 599:
        raise SearchError("relay_engine_unavailable")
    if 400 <= status <= 499 and not (status == 409 and method == "PUT" and "/_doc/" in path):
        raise SearchError("relay_engine_request_rejected")


class RelayHttp:
    def __init__(self, delegate):
        # Keep the management client's policy unchanged. Classify real HTTP status
        # before an HTML/error body can hide a permanent 4xx behind a JSON error.
        self.delegate = copy.copy(delegate) if isinstance(delegate, Http) else delegate
        if isinstance(self.delegate, Http): self.delegate.status_guard = relay_status

    def request(self, method, path, body=None):
        status, result = self.delegate.request(method, path, body)
        relay_status(method, path, status)
        return status, result


class Relay:
    def __init__(self, store, engine, source_id):
        self.store, self.engine, self.source_id = store, engine, source_id
        if not isinstance(engine.http, RelayHttp): engine.http = RelayHttp(engine.http)

    def once(self):
        # Validate metadata as well as UUIDs before claiming and again before ack.
        self.engine.ready()
        before = target_identity(self.engine, self.source_id)
        fingerprint = self.store.bind(before)
        claim = self.store.claim(fingerprint, self.source_id)
        if claim is None: return {"status": "idle"}
        try:
            result = self.engine.apply(claim_document(claim))
            if result not in ("applied", "duplicate", "stale_ignored"):
                raise SearchError("relay_write_outcome_invalid")
            self.engine.ready()
            if target_identity(self.engine, self.source_id) != before:
                raise SearchError("relay_target_changed_during_delivery")
            accepted = self.store.ack(claim)
            return {"status": "delivered" if accepted else "lease_lost", "outcome": result}
        except Exception as error:
            code = safe_code(error)
            accepted = self.store.retry(claim, code)
            return {"status": "retry" if accepted else "lease_lost", "error_code": code}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "once", "watch"))
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--index", required=True)
    args = parser.parse_args()
    try:
        settings = database_settings(os.environ)
        store = Store(lambda: connect_database(settings))
        source_id = store.source_id()
        engine = Engine(Http(args.endpoint), args.index, "backend_product_events", source_id, refresh="false")
        engine.ready(create=args.command == "init")
        store.bind(target_identity(engine, source_id))
        if args.command == "init":
            print(json.dumps({"status": "initialized"})); return 0
        relay = Relay(store, engine, source_id)
        while True:
            try:
                report = relay.once()
            except Exception as error:
                report = {"status": "failed", "error_code": safe_code(error)}
            print(json.dumps(report), flush=True)
            if args.command == "once": return 0 if report["status"] in ("idle", "delivered") else 2
            if report["status"] not in ("idle", "delivered") and report.get("error_code") not in TRANSIENT_CODES:
                return 2  # Binding/config/data violations require intervention, never blind watch retry.
            time.sleep(2 if report["status"] != "delivered" else 0.05)
    except KeyboardInterrupt:
        return 0
    except Exception as error:
        print(json.dumps({"status": "failed", "error_code": safe_code(error)}), file=sys.stderr)
        return 2


if __name__ == "__main__": raise SystemExit(main())

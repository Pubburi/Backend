#!/usr/bin/env python3
"""Move the fixed local search read alias and the guarded PostgreSQL relay target."""

import argparse
from copy import copy
from dataclasses import dataclass
import json
import os
import re
import struct
import sys
import time
import uuid

from postgresql_schema import verify_owned_postgres
from search_index import Engine, Http, MAX_INPUT_BYTES, READ_ALIAS, SearchError, checked_stored, decode, document, same_json
from search_relay import database_settings, database_store, encode_target_identity, outbox_document, target_identity

READ_DEFINITION = {"is_write_index": False, "filter": {"term": {"deleted": False}}}
EVENT_LIMIT = 1000
CUTOVER_SECONDS = 60


@dataclass(frozen=True)
class CutoverPlan:
    source_id: str
    cluster_uuid: str
    old_index: str
    old_uuid: str
    new_index: str
    new_uuid: str
    bootstrap_read_alias: bool = False

    def __post_init__(self):
        try:
            canonical = str(uuid.UUID(self.source_id)) == self.source_id
        except (ValueError, AttributeError, TypeError):
            canonical = False
        if (not canonical or self.old_index == self.new_index or self.old_uuid == self.new_uuid
                or any(not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value)
                       for value in (self.cluster_uuid, self.old_uuid, self.new_uuid))):
            raise SearchError("cutover_exact_generation_pins_required")

    def identity(self, index, physical_uuid):
        return dict(source_id=self.source_id, cluster_uuid=self.cluster_uuid, index_name=index,
                    index_uuid=physical_uuid, schema="products-v1", data_kind="backend_product_events")


class FencedHttp:
    def __init__(self, transport, cursor, end):
        self.transport, self.cursor, self.end = transport, cursor, end

    def request(self, method, path, body=None):
        self.cursor.execute("SELECT 1")
        remaining = self.end - time.monotonic()
        if remaining <= 0:
            raise SearchError("cutover_deadline")
        transport = self.transport
        if isinstance(transport, Http):
            transport = copy(transport)
            transport.deadline = min(transport.deadline, remaining)
        result = transport.request(method, path, body)
        if time.monotonic() >= self.end:
            raise SearchError("cutover_deadline")
        return result


def guarded_transaction(cursor, database, token):
    cursor.execute("SET LOCAL transaction_timeout = '60s'")
    cursor.execute("""SELECT current_database() AS db, current_setting('server_version_num') AS version,
        (SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()) AS tls,
        (SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
           FROM pg_roles WHERE rolname=current_user) AS elevated,
        has_column_privilege(current_user,'pubburi.t_product_search_relay_target','fingerprint','UPDATE')
        AND has_column_privilege(current_user,'pubburi.t_product_search_relay_target','identity_json','UPDATE')
        AND has_column_privilege(current_user,'pubburi.t_product_search_relay_target','bound_at','UPDATE') AS cas_allowed""")
    if cursor.fetchone() != {"db": database, "version": "180006", "tls": True, "elevated": False, "cas_allowed": True}:
        raise SearchError("cutover_control_guard_rejected")
    cursor.execute("SELECT token FROM public.pubburi_pg_migration_guard")
    if cursor.fetchall() != [{"token": token}]:
        raise SearchError("cutover_database_marker_rejected")


def lock_source(cursor):
    cursor.execute("""SELECT source_id::text AS source_id, fingerprint, identity_json::text AS identity
        FROM pubburi.t_product_search_relay_target WHERE singleton_id=1 FOR UPDATE NOWAIT""")
    target = cursor.fetchone()
    cursor.execute("""LOCK TABLE pubburi.t_product, pubburi.t_product_feature, pubburi.t_product_feature_tag,
        pubburi.t_product_search_version, pubburi.t_product_search_outbox, pubburi.t_product_search_delivery
        IN SHARE MODE NOWAIT""")
    return target


def source_projection(cursor):
    cursor.execute("""SELECT count(*) AS count, coalesce(sum(octet_length(o.payload::text)),0) AS bytes,
        count(*) FILTER (WHERE d.status IS DISTINCT FROM 'DELIVERED') AS outstanding
        FROM pubburi.t_product_search_outbox o LEFT JOIN pubburi.t_product_search_delivery d USING(event_id)""")
    size = cursor.fetchone()
    if size["outstanding"] != 0:
        raise SearchError("cutover_delivery_not_drained")
    if size["count"] > EVENT_LIMIT or size["bytes"] > MAX_INPUT_BYTES:
        raise SearchError("cutover_replay_budget_exceeded")
    cursor.execute("""SELECT product_id, source_version, CASE WHEN deleted THEN 1 ELSE 0 END AS deleted,
        payload::text AS payload FROM pubburi.t_product_search_outbox ORDER BY product_id, source_version""")
    events = [outbox_document(row) for row in cursor.fetchall()]
    latest = {}
    for event in events:
        product_id, version = event["product_id"], event["source_version"]
        if version != latest.get(product_id, {}).get("source_version", 0) + 1:
            raise SearchError("cutover_outbox_version_gap")
        latest[product_id] = event
    cursor.execute("SELECT product_id, source_version FROM pubburi.t_product_search_version LIMIT %s", (EVENT_LIMIT + 1,))
    if {row["product_id"]: row["source_version"] for row in cursor.fetchall()} != {
            product_id: value["source_version"] for product_id, value in latest.items()}:
        raise SearchError("cutover_latest_versions_mismatch")

    cursor.execute("""SELECT p.id, p.name, p.type, p.price, p.abv, p.order_count,
        CASE WHEN f.taste_known THEN ARRAY(SELECT tag FROM pubburi.t_product_feature_tag
            WHERE product_id=p.id AND kind='TASTE') END AS taste_tags,
        CASE WHEN f.aroma_known THEN ARRAY(SELECT tag FROM pubburi.t_product_feature_tag
            WHERE product_id=p.id AND kind='AROMA') END AS aroma_tags
        FROM pubburi.t_product p LEFT JOIN pubburi.t_product_feature f ON f.product_id=p.id LIMIT %s""", (EVENT_LIMIT + 1,))
    current = {row["id"]: row for row in cursor.fetchall()}
    if current.keys() != {product_id for product_id, value in latest.items() if not value["deleted"]}:
        raise SearchError("cutover_live_product_set_mismatch")
    for product_id, row in current.items():
        saved = latest[product_id]
        expected = document({"productId": product_id, "sourceVersion": saved["source_version"], "deleted": False,
            "document": {"name": row["name"], "type": row["type"], "priceKrw": row["price"], "abvPercent": row["abv"],
                "orderCount": row["order_count"], "tasteTags": row["taste_tags"], "aromaTags": row["aroma_tags"]}})
        # Compare the stored REAL value with the outbox's Java FLOAT JSON representation.
        if struct.pack("!f", expected["abv_percent"]) != struct.pack("!f", saved["abv_percent"]):
            raise SearchError("cutover_current_product_projection_mismatch")
        expected["abv_percent"] = saved["abv_percent"]
        if expected != saved:
            raise SearchError("cutover_current_product_projection_mismatch")
    return events, latest


def read_alias_index(http):
    status, body = http.request("GET", f"/_alias/{READ_ALIAS}")
    if status == 404:
        return None
    if status != 200 or not isinstance(body, dict) or len(body) != 1:
        raise SearchError("cutover_single_filtered_read_alias_required")
    (physical, metadata), = body.items()
    if not same_json(metadata, {"aliases": {READ_ALIAS: READ_DEFINITION}}):
        raise SearchError("cutover_single_filtered_read_alias_required")
    return physical


def verify_generation(engine, expected):
    engine.ready()
    if target_identity(engine, expected["source_id"]) != expected:
        raise SearchError("cutover_physical_generation_changed")


def verify_stage(http, index, latest):
    status, result = http.request("POST", f"/{index}/_refresh")
    if status != 200 or not one_successful_shard(result):
        raise SearchError("cutover_refresh_rejected")
    status, result = http.request("GET", f"/{index}/_count")
    if (status != 200 or type(result.get("count")) is not int or result["count"] != len(latest)
            or not one_successful_shard(result)):
        raise SearchError("cutover_staged_document_count_mismatch")
    ids = list(latest)
    for offset in range(0, len(ids), 100):
        group = ids[offset:offset + 100]
        status, result = http.request("POST", f"/{index}/_mget", {"ids": [str(product_id) for product_id in group]})
        rows = result.get("docs")
        if status != 200 or not isinstance(rows, list) or len(rows) != len(group):
            raise SearchError("cutover_staged_document_mismatch")
        for product_id, row in zip(group, rows):
            saved = latest[product_id]
            if (row.get("found") is not True or row.get("_index") != index or row.get("_id") != str(product_id)
                    or type(row.get("_version")) is not int or row["_version"] != saved["source_version"]
                    or checked_stored(row.get("_source")) != saved):
                raise SearchError("cutover_staged_document_mismatch")


def one_successful_shard(result):
    shards = result.get("_shards", {})
    return all(type(shards.get(key)) is int and shards[key] == value
               for key, value in (("total", 1), ("successful", 1), ("failed", 0)))


def move_alias(http, previous, following):
    actions = []
    if previous is not None:
        actions.append({"remove": {"index": previous, "alias": READ_ALIAS, "must_exist": True}})
    actions.append({"add": {"index": following, "alias": READ_ALIAS, **READ_DEFINITION}})
    status, result = http.request("POST", "/_aliases", {"actions": actions})
    if status != 200 or result.get("acknowledged") is not True:
        raise SearchError("cutover_alias_ack_required")


def run_cutover(store, transport, plan, database, token):
    end = time.monotonic() + CUTOVER_SECONDS
    old = plan.identity(plan.old_index, plan.old_uuid)
    new = plan.identity(plan.new_index, plan.new_uuid)
    _, old_fingerprint = encode_target_identity(old)
    new_json, new_fingerprint = encode_target_identity(new)
    with store.transaction() as cursor:
        guarded_transaction(cursor, database, token)
        bound = lock_source(cursor)
        if bound is None or bound["source_id"] != plan.source_id or bound["identity"] is None:
            raise SearchError("cutover_source_binding_required")
        identity = decode(bound["identity"])
        if bound["fingerprint"] == old_fingerprint and same_json(identity, old):
            already = False
        elif bound["fingerprint"] == new_fingerprint and same_json(identity, new):
            already = True
        else:
            raise SearchError("cutover_unexpected_database_generation")
        http = FencedHttp(transport, cursor, end)
        old_engine = Engine(http, plan.old_index, old["data_kind"], plan.source_id, refresh="false")
        new_engine = Engine(http, plan.new_index, new["data_kind"], plan.source_id, refresh="false")
        alias = read_alias_index(http)
        allowed_aliases = {plan.new_index}
        if not already:
            allowed_aliases.add(plan.old_index)
            if plan.bootstrap_read_alias:
                allowed_aliases.add(None)
        if alias not in allowed_aliases:
            raise SearchError("cutover_unexpected_read_generation")
        events, latest = source_projection(cursor)
        verify_generation(old_engine, old)
        verify_generation(new_engine, new)
        if not already:
            for event in events:
                new_engine.apply(event)
        verify_stage(http, plan.new_index, latest)
        verify_generation(old_engine, old)
        verify_generation(new_engine, new)
        if read_alias_index(http) != alias:
            raise SearchError("cutover_read_alias_changed")
        if alias != plan.new_index:
            move_alias(http, alias, plan.new_index)
        verify_generation(new_engine, new)
        if read_alias_index(http) != plan.new_index:
            raise SearchError("cutover_read_alias_not_visible")
        if not already:
            cursor.execute("""UPDATE pubburi.t_product_search_relay_target SET fingerprint=%s,
                identity_json=%s::json, bound_at=clock_timestamp() WHERE singleton_id=1 AND source_id=%s::uuid
                AND fingerprint=%s AND identity_json::text=%s""",
                (new_fingerprint, new_json, plan.source_id, old_fingerprint, bound["identity"]))
            if cursor.rowcount != 1:
                raise SearchError("cutover_binding_cas_rejected")
        return {"status": "already_cut_over" if already else "cut_over", "events": len(events), "documents": len(latest)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("endpoint", "source-id", "cluster-uuid", "old-index", "old-uuid", "new-index", "new-uuid"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--bootstrap-read-alias", action="store_true")
    args = parser.parse_args()
    try:
        token = os.environ.get("PUBBURI_PG_RUN_TOKEN", "")
        if (os.environ.get("PUBBURI_SEARCH_CUTOVER_MUTATING") != "1"
                or os.environ.get("PUBBURI_SEARCH_CUTOVER_WRITERS_STOPPED") != "1" or not re.fullmatch(r"[A-Za-z0-9_-]{12,100}", token)
                or os.environ.get("PUBBURI_DB_URL") != os.environ.get("PUBBURI_PG_URL")
                or os.environ.get("PUBBURI_RELAY_DB_CA") != os.environ.get("PUBBURI_PG_CA")):
            raise SearchError("cutover_explicit_owned_database_required")
        plan = CutoverPlan(args.source_id, args.cluster_uuid, args.old_index, args.old_uuid, args.new_index,
                           args.new_uuid, args.bootstrap_read_alias)
        settings = database_settings(os.environ)
        if settings.get("dialect") != "postgresql":
            raise SearchError("cutover_postgresql_required")
        verify_owned_postgres(os.environ)
        report = run_cutover(database_store(settings), Http(args.endpoint), plan, settings["database"], token)
        print(json.dumps(report))
        return 0
    except Exception as error:
        code = str(error) if isinstance(error, SearchError) else "cutover_database_or_runtime_failure"
        print(json.dumps({"status": "failed", "error_code": code}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

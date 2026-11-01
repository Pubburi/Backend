#!/usr/bin/env python3
"""Explicit local index administration; not a MySQL outbox or public search path."""

import argparse
import errno
import hashlib
import http.client
import ipaddress
import json
import math
from pathlib import Path
import re
import select
import socket
import sys
import threading
import time
from urllib.parse import urlsplit

VERSION = "3.9.0"
MAX_INPUT_BYTES = 4 * 1024 * 1024
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
SCHEMA = Path(__file__).resolve().parents[1] / "search" / "products-v1.json"
KINDS = {"authored_fixture", "backend_product_events"}


class SearchError(ValueError):
    """Fixed diagnostics never contain source documents or server messages."""


def decode(content):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise SearchError("duplicate_json_key")
            result[key] = value
        return result

    def number(value):
        result = float(value)
        if not math.isfinite(result):
            raise SearchError("nonfinite_json_number")
        return result

    try:
        return json.loads(content, object_pairs_hook=pairs, parse_float=number,
                          parse_constant=lambda value: (_ for _ in ()).throw(SearchError("nonfinite_json_number")))
    except SearchError:
        raise
    except (ValueError, UnicodeError, RecursionError) as error:
        raise SearchError("invalid_json") from error


def integer(value, maximum):
    if type(value) is not int or not 0 <= value <= maximum:
        raise SearchError("integer_out_of_range")
    return value


def text(value, maximum):
    if not isinstance(value, str) or not value.strip():
        raise SearchError("invalid_text")
    try:
        if len(value.encode("utf-16-le")) // 2 > maximum:
            raise SearchError("invalid_text")
    except UnicodeError as error:
        raise SearchError("invalid_text") from error
    return value


def tags(value):
    if value is None:
        return None
    if not isinstance(value, list) or len(value) > 20:
        raise SearchError("invalid_tags")
    normalized = []
    for tag in value:
        text(tag, 40)
        if tag != tag.strip().lower() or tag in normalized:
            raise SearchError("noncanonical_or_duplicate_tag")
        normalized.append(tag)
    return sorted(normalized)


def document(event):
    if not isinstance(event, dict) or set(event) != {"productId", "sourceVersion", "deleted", "document"}:
        raise SearchError("invalid_event_fields")
    product_id = integer(event["productId"], 2147483647)
    version = integer(event["sourceVersion"], 9223372036854775807)
    if product_id == 0 or version == 0 or type(event["deleted"]) is not bool:
        raise SearchError("positive_id_version_and_boolean_deleted_required")
    result = {"product_id": product_id, "source_version": version, "deleted": event["deleted"]}
    raw = event["document"]
    if event["deleted"]:
        if raw is not None:
            raise SearchError("tombstone_document_must_be_null")
        return result
    if not isinstance(raw, dict) or set(raw) != {"name", "type", "priceKrw", "abvPercent", "orderCount", "tasteTags", "aromaTags"}:
        raise SearchError("invalid_product_projection")
    abv = raw["abvPercent"]
    if type(abv) not in (int, float) or not math.isfinite(abv) or not 0 <= abv <= 100:
        raise SearchError("invalid_abv")
    taste, aroma = tags(raw["tasteTags"]), tags(raw["aromaTags"])
    result.update(name=text(raw["name"], 100), category=text(raw["type"], 20),
                  price_krw=integer(raw["priceKrw"], 2147483647), abv_percent=abv,
                  order_count=integer(raw["orderCount"], 2147483647),
                  taste_known=taste is not None, aroma_known=aroma is not None,
                  taste_tags=taste, aroma_tags=aroma)
    return result


def batch(content):
    if len(content) > MAX_INPUT_BYTES:
        raise SearchError("input_exceeds_4_mib")
    data = decode(content)
    if (not isinstance(data, dict) or set(data) != {"schemaVersion", "dataKind", "events"}
            or type(data["schemaVersion"]) is not int or data["schemaVersion"] != 1
            or not isinstance(data["dataKind"], str) or data["dataKind"] not in KINDS or not isinstance(data["events"], list)
            or not 1 <= len(data["events"]) <= 1000):
        raise SearchError("invalid_event_batch")
    # Validate every item before making any network request. Runtime failures can still be partial.
    return data["dataKind"], [document(event) for event in data["events"]]


def same_json(left, right):
    # Python containers equate False/0 and True/1; protocol metadata must retain JSON types.
    return json.dumps(left, sort_keys=True, allow_nan=False) == json.dumps(right, sort_keys=True, allow_nan=False)


def checked_stored(raw):
    if not isinstance(raw, dict) or type(raw.get("deleted")) is not bool:
        raise SearchError("conflict_current_document_invalid")
    live = {"name", "category", "price_krw", "abv_percent", "order_count", "taste_known", "aroma_known", "taste_tags", "aroma_tags"}
    expected = {"product_id", "source_version", "deleted"} | (set() if raw["deleted"] else live)
    if set(raw) != expected:
        raise SearchError("conflict_current_document_invalid")
    projection = None
    if not raw["deleted"]:
        if type(raw["taste_known"]) is not bool or type(raw["aroma_known"]) is not bool:
            raise SearchError("conflict_current_document_invalid")
        projection = {"name": raw["name"], "type": raw["category"], "priceKrw": raw["price_krw"],
                      "abvPercent": raw["abv_percent"], "orderCount": raw["order_count"],
                      "tasteTags": raw["taste_tags"], "aromaTags": raw["aroma_tags"]}
    checked = document({"productId": raw["product_id"], "sourceVersion": raw["source_version"],
                        "deleted": raw["deleted"], "document": projection})
    if checked != raw:
        raise SearchError("conflict_current_document_invalid")
    return checked


class _DeadlineConnection(http.client.HTTPConnection):
    """One request owns its sockets and a joined, per-connection IO interrupter."""

    def __init__(self, host, port, budget):
        super().__init__(host, port, timeout=min(2, budget))
        self.end = time.monotonic() + budget
        self.expired, self.stopped = threading.Event(), threading.Event()
        self.lock, self.interrupt_socket = threading.Lock(), None
        self.watchdog = threading.Thread(target=self._expire, name="pubburi-search-deadline", daemon=True)
        self.watchdog.start()

    def remaining(self):
        remaining = self.end - time.monotonic()
        if self.expired.is_set() or remaining <= 0:
            raise SearchError("engine_request_deadline")
        return remaining

    def _expire(self):
        if self.stopped.wait(max(0, self.end - time.monotonic())):
            return
        with self.lock:
            if self.stopped.is_set():
                return
            self.expired.set()
            if self.interrupt_socket is not None:
                try:
                    self.interrupt_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass  # A failed/unconnected socket is still closed by finish().

    def connect(self):
        # Numeric loopback only: no unbounded resolver. Nonblocking connect cannot start
        # a fresh blocking timeout in the race between expiry and socket registration.
        transport = socket.socket(socket.AF_INET6 if ":" in self.host else socket.AF_INET, socket.SOCK_STREAM)
        with self.lock:
            self.sock = transport
            # HTTPConnection closes its socket after headers for Connection: close.
            # A duplicate retains shutdown access to the same connection during body IO.
            self.interrupt_socket = transport.dup()
        transport.setblocking(False)
        self.remaining()
        error = transport.connect_ex((self.host, self.port))
        if error not in (0, errno.EISCONN):
            if error not in (errno.EINPROGRESS, errno.EWOULDBLOCK, errno.EALREADY, errno.EINTR):
                raise OSError(error, "connect_failed")
            _, writable, exceptional = select.select([], [transport], [transport], min(2, self.remaining()))
            self.remaining()
            if not writable and not exceptional:
                raise TimeoutError("connect_timeout")
            error = transport.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR)
            if error:
                raise OSError(error, "connect_failed")
        transport.settimeout(self.remaining())

    def finish(self):
        # No background request future survives return. Stop/join before closing the
        # cancellation descriptor, so the watchdog never targets a reused descriptor.
        self.stopped.set()
        self.watchdog.join()
        try:
            self.close()
        finally:
            if self.interrupt_socket is not None:
                self.interrupt_socket.close()
                self.interrupt_socket = None


class Http:
    def __init__(self, endpoint, deadline=5.0):
        try:
            url = urlsplit(endpoint)
            valid = (url.scheme == "http" and ipaddress.ip_address(url.hostname).is_loopback
                     and url.username is None and url.password is None and url.path in ("", "/")
                     and not url.query and not url.fragment and url.port is not None)
        except (ValueError, TypeError):
            valid = False
        if not valid or not 1 <= url.port <= 65535 or not 0 < deadline <= 5:
            raise SearchError("explicit_loopback_http_origin_required")
        self.host, self.port, self.deadline = url.hostname, url.port, deadline

    def request(self, method, path, body=None):
        connection = _DeadlineConnection(self.host, self.port, self.deadline)
        try:
            payload = None if body is None else json.dumps(body, ensure_ascii=False, allow_nan=False).encode()
            connection.request(method, path, payload, {"Content-Type": "application/json", "Accept": "application/json"})
            transport = connection.sock
            transport.settimeout(connection.remaining())
            with connection.getresponse() as response:
                if response.getheader("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
                    raise SearchError("engine_response_not_json")
                length = response.getheader("Content-Length")
                if length is not None and (not length.isascii() or not length.isdigit() or int(length) > MAX_RESPONSE_BYTES):
                    raise SearchError("engine_response_length_rejected")
                declared_length = None if length is None else int(length)
                content = bytearray()
                while True:
                    transport.settimeout(connection.remaining())
                    chunk = response.read1(min(16384, MAX_RESPONSE_BYTES + 1 - len(content)))
                    connection.remaining()
                    content.extend(chunk)
                    if len(content) > MAX_RESPONSE_BYTES:
                        raise SearchError("engine_response_exceeds_2_mib")
                    if not chunk or response.isclosed():
                        break
                if declared_length is not None and len(content) != declared_length:
                    raise SearchError("engine_response_length_mismatch")
                result = response.status, decode(content)
                connection.remaining()
                return result
        except (OSError, http.client.HTTPException, ValueError) as error:
            connection.remaining()  # Expired IO gets one fixed diagnostic, including EOF on shutdown.
            if isinstance(error, SearchError):
                raise
            raise SearchError("engine_transport_failed") from error
        finally:
            connection.finish()


class Engine:
    def __init__(self, http, index="pubburi-products-v1", kind="authored_fixture"):
        if not re.fullmatch(r"pubburi-products-v1(?:-[a-z0-9]{1,32})?", index) or kind not in KINDS:
            raise SearchError("owned_index_and_data_kind_required")
        self.http, self.index, self.kind = http, index, kind
        self.alias = index + "-visible"
        self.schema = decode(SCHEMA.read_bytes())
        self.schema["mappings"]["_meta"]["data_kind"] = kind
        self.alias_definition = {"is_write_index": True, "filter": {"term": {"deleted": False}}}

    def ready(self, create=False):
        status, root = self.http.request("GET", "/")
        if status != 200 or not isinstance(root, dict) or root.get("version", {}).get("number") != VERSION or root.get("version", {}).get("distribution") != "opensearch":
            raise SearchError("pinned_engine_version_required")
        status, plugins = self.http.request("GET", "/_cat/plugins?format=json&h=component,version")
        if status != 200 or not isinstance(plugins, list) or not any(isinstance(row, dict) and row.get("component") == "analysis-nori" and row.get("version") == VERSION for row in plugins):
            raise SearchError("matching_nori_plugin_required")
        status, mapping = self.http.request("GET", f"/{self.index}/_mapping")
        if status == 404 and create:
            payload = {**self.schema, "aliases": {self.alias: self.alias_definition}}
            status, created = self.http.request("PUT", f"/{self.index}", payload)
            if status not in (200, 201) or created.get("acknowledged") is not True:
                raise SearchError("index_create_not_acknowledged")
            status, mapping = self.http.request("GET", f"/{self.index}/_mapping")
        actual_mapping = mapping.get(self.index, {}).get("mappings", {})
        source = actual_mapping.get("_source", {})
        if not isinstance(source, dict):
            raise SearchError("owned_strict_mapping_required")
        # OpenSearch omits the default enabled _source from its mapping response.
        actual_mapping = {**actual_mapping, "_source": {"enabled": True, **source}}
        if status != 200 or not same_json(actual_mapping, self.schema["mappings"]):
            raise SearchError("owned_strict_mapping_required")
        status, aliases = self.http.request("GET", f"/_alias/{self.alias}")
        if status != 200 or set(aliases) != {self.index} or not same_json(aliases[self.index].get("aliases", {}).get(self.alias), self.alias_definition):
            raise SearchError("single_owned_filtered_alias_required")
        status, settings = self.http.request("GET", f"/{self.index}/_settings")
        actual = settings.get(self.index, {}).get("settings", {}).get("index", {})
        if status != 200 or actual.get("analysis") != self.schema["settings"]["analysis"] or actual.get("number_of_shards") != "1" or actual.get("number_of_replicas") != "0":
            raise SearchError("owned_index_settings_required")

    def apply(self, value):
        product_id, version = value["product_id"], value["source_version"]
        path = f"/{self.alias}/_doc/{product_id}?version={version}&version_type=external&require_alias=true&timeout=2s&refresh=wait_for"
        status, result = self.http.request("PUT", path, value)
        if status in (200, 201):
            failed = result.get("_shards", {}).get("failed")
            if result.get("_index") != self.index or result.get("_id") != str(product_id) or type(result.get("_version")) is not int or result["_version"] != version or type(failed) is not int or failed != 0:
                raise SearchError("index_write_acknowledgement_invalid")
            return "applied"
        if status != 409 or result.get("error", {}).get("type") != "version_conflict_engine_exception":
            raise SearchError("index_write_failed")
        status, stored = self.http.request("GET", f"/{self.index}/_doc/{product_id}")
        current, raw = stored.get("_version"), stored.get("_source")
        if (status != 200 or stored.get("_index") != self.index or stored.get("_id") != str(product_id)
                or type(current) is not int or not isinstance(raw, dict)
                or type(raw.get("source_version")) is not int or raw["source_version"] != current
                or type(raw.get("product_id")) is not int or raw["product_id"] != product_id
                or type(raw.get("deleted")) is not bool):
            raise SearchError("conflict_current_document_invalid")
        checked_stored(raw)
        if current > version:
            return "stale_ignored"
        if current == version and raw == value:
            return "duplicate"
        raise SearchError("same_version_content_conflict")


def receipt(directory, value):
    temporary = directory / "receipt.tmp"
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(directory / "receipt.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "replay"))
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--index", default="pubburi-products-v1")
    parser.add_argument("--data-kind", choices=sorted(KINDS), required=True)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    output = None
    report = {"schema_version": 1, "completed": False, "applied": 0, "duplicate": 0, "stale_ignored": 0}
    try:
        if ".pubburi" not in args.output_dir.resolve().parts:
            raise SearchError("private_receipt_directory_required")
        engine = Engine(Http(args.endpoint), args.index, args.data_kind)
        events = []
        if args.command == "replay":
            if args.input is None:
                raise SearchError("replay_input_required")
            with args.input.open("rb") as source:
                content = source.read(MAX_INPUT_BYTES + 1)
            kind, events = batch(content)
            if kind != args.data_kind:
                raise SearchError("input_data_kind_mismatch")
            report["input_sha256"] = hashlib.sha256(content).hexdigest()
        elif args.input is not None:
            raise SearchError("init_has_no_event_input")
        args.output_dir.mkdir(parents=True, exist_ok=False)
        output = args.output_dir
        report.update(index=args.index, data_kind=args.data_kind, command=args.command)
        receipt(output, report)
        engine.ready(create=args.command == "init")
        for value in events:
            report[engine.apply(value)] += 1
        report["completed"] = True
        receipt(output, report)
        print(json.dumps(report))
        return 0
    except (SearchError, OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        code = str(error) if isinstance(error, SearchError) else type(error).__name__
        report["completed"] = False
        report["error_code"] = code
        if output is not None:
            try:
                receipt(output, report)
            except OSError:
                pass  # Never replace a failure with success or leak a raw filesystem error.
        print("Search index operation failed: " + code, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

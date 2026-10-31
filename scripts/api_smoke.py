#!/usr/bin/env python3
"""Check the packaged HTTP contract; write only to an explicitly marked CI database."""

import http.cookiejar
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid


def require_disposable_database(environ, run=subprocess.run):
    """Fail closed before any HTTP mutation if the isolated database is not proven."""
    if environ.get("PUBBURI_SMOKE_MUTATING") != "1":
        raise RuntimeError("Mutating checks require explicit disposable-database opt-in")
    database = environ.get("PUBBURI_DB_NAME", "")
    token = environ.get("PUBBURI_SMOKE_RUN_TOKEN", "")
    container = environ.get("PUBBURI_SMOKE_CONTAINER_ID", "")
    jdbc_url = environ.get("PUBBURI_DB_URL", "")
    if not re.fullmatch(r"[A-Za-z0-9_]+_ci", database):
        raise RuntimeError("Mutating checks require a dedicated *_ci database")
    if not re.fullmatch(r"[A-Za-z0-9_-]{16,80}", token) or not container:
        raise RuntimeError("Missing disposable database container or run marker")
    if not jdbc_url.startswith("jdbc:mysql://"):
        raise RuntimeError("Only a local MySQL CI database is supported")
    parsed = urllib.parse.urlsplit(jdbc_url.removeprefix("jdbc:"))
    if (parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parsed.path != "/" + database or parsed.username or parsed.password
            or parsed.query or parsed.fragment):
        raise RuntimeError("Database URL does not identify the local CI schema")

    inspected = run(["docker", "inspect", container], capture_output=True, text=True, check=True)
    bindings = json.loads(inspected.stdout)[0]["NetworkSettings"]["Ports"].get("3306/tcp") or []
    if not any(int(binding["HostPort"]) == (parsed.port or 3306)
               and binding["HostIp"] in {"", "0.0.0.0", "127.0.0.1", "::", "::1"}
               for binding in bindings):
        raise RuntimeError("CI database URL does not match the disposable container's port")

    client_env = dict(environ, MYSQL_PWD=environ.get("PUBBURI_DB_PASSWORD", ""))
    marker = run([
        "docker", "exec", "-e", "MYSQL_PWD", container,
        "mysql", "-u" + environ.get("PUBBURI_DB_USER", ""),
        "--batch", "--skip-column-names", database,
        "--execute", "SELECT token FROM pubburi_ci_smoke_guard WHERE token = '" + token + "'",
    ], capture_output=True, text=True, check=True, env=client_env)
    if marker.stdout.strip() != token:
        raise RuntimeError("Disposable database run marker is absent or mismatched")


class ApiClient:
    def __init__(self, port):
        if not str(port).isdigit() or not 1 <= int(port) <= 65535:
            raise ValueError("Invalid local API port")
        self.base = "http://127.0.0.1:" + str(port) + "/api"
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, method, path, expected_status, payload=None, error_code=None):
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base + path, data=data, method=method,
            headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            response = self.opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == expected_status, (
                f"{method} {path}: expected HTTP {expected_status}, got {response.status}")
            assert response.headers.get_content_type() == "application/json", "Expected JSON response"
            envelope = json.load(response)
        assert set(envelope) == {"data", "message", "error"}, "ApiResponse contract changed"
        if error_code is None:
            assert envelope["error"] is None, f"Unexpected API error from {method} {path}"
        else:
            assert envelope["data"] is None, "Error response exposed a data payload"
            assert envelope["error"]["code"] == error_code, "Unexpected API error code"
        return envelope


def read_only_contract(client):
    page = client.request("GET", "/products?page=1&size=1", 200)["data"]
    assert page["page"] == 1 and page["size"] == 1
    assert isinstance(page["total"], int) and page["total"] >= len(page["items"])
    assert len(page["items"]) == min(page["total"], page["size"])
    assert page["totalPages"] == (page["total"] + page["size"] - 1) // page["size"]
    assert page["hasNext"] is (page["page"] < page["totalPages"])
    product = page["items"][0] if page["items"] else None
    if product is not None:
        assert {"id", "name", "type", "price", "img", "abv", "orderCount"} == set(product)
    client.request("GET", "/products/0", 404, error_code="NOT_FOUND")
    client.request("GET", "/auth/me", 401, error_code="UNAUTHORIZED")
    client.request("GET", "/admin/orders", 401, error_code="UNAUTHORIZED")
    invalid = client.request("POST", "/auth/login", 400, {"id": "", "pass": ""}, "VALIDATION_FAILED")
    assert {"id", "pass"} <= set(invalid["error"]["fields"]), "Validation field details are missing"
    return product


def mutating_contract(client, product, environ):
    require_disposable_database(environ)
    assert product is not None, "Disposable CI fixture must include a product"
    # This deliberately absent product ID belongs to the isolated data.sql fixture.
    client.request("GET", "/products/2147483647", 404, error_code="NOT_FOUND")
    user_id = "ci_smoke_" + uuid.uuid4().hex
    password = uuid.uuid4().hex
    client.request("POST", "/users", 201, {"id": user_id, "name": "CI smoke", "pass": password})
    logged_in = client.request("POST", "/auth/login", 200, {"id": user_id, "pass": password})["data"]
    assert set(logged_in) == {"id", "name", "stamps", "role", "admin"}, "User response fields changed"
    assert logged_in["id"] == user_id and logged_in["role"] == "USER" and logged_in["admin"] is False
    assert client.request("GET", "/auth/me", 200)["data"]["id"] == user_id, "Session cookie was not retained"
    client.request("GET", "/admin/orders", 403, error_code="FORBIDDEN")
    client.request("GET", "/users/another-ci-user/profile", 403, error_code="FORBIDDEN")
    invalid = client.request("POST", "/orders", 400, {"details": []}, "VALIDATION_FAILED")
    assert "details" in invalid["error"]["fields"]

    product_id = product["id"]
    before_product = client.request("GET", f"/products/{product_id}", 200)["data"]
    orders_path = f"/users/{user_id}/orders?page=1&size=100"
    assert client.request("GET", orders_path, 200)["data"]["total"] == 0
    created = client.request("POST", "/orders", 201, {
        "orderType": "TAKEOUT", "details": [
            {"productId": product_id, "quantity": 2}, {"productId": product_id, "quantity": 1}],
    })["data"]
    assert created["userId"] == user_id and created["completed"] == "N"
    assert len(created["details"]) == 1 and created["details"][0]["quantity"] == 3
    assert created["details"][0]["sumPrice"] == before_product["price"] * 3
    client.request("GET", f"/orders/{created['oId']}", 200)
    orders_before = client.request("GET", orders_path, 200)["data"]
    product_before = client.request("GET", f"/products/{product_id}", 200)["data"]
    profile_before = client.request("GET", f"/users/{user_id}/profile", 200)["data"]
    assert orders_before["total"] == 1
    assert product_before["orderCount"] == before_product["orderCount"] + 3
    assert profile_before["user"]["stamps"] == 3

    # Current service inserts a header and updates the first product before the
    # second (missing) product raises. These observable writes must roll back.
    client.request("POST", "/orders", 400, {"details": [
        {"productId": product_id, "quantity": 4},
        {"productId": 2147483647, "quantity": 1},
    ]}, "BAD_REQUEST")
    orders_after = client.request("GET", orders_path, 200)["data"]
    product_after = client.request("GET", f"/products/{product_id}", 200)["data"]
    profile_after = client.request("GET", f"/users/{user_id}/profile", 200)["data"]
    assert orders_after == orders_before, "Failed order left persisted order data"
    assert product_after == product_before, "Failed order left a product counter increment"
    assert profile_after == profile_before, "Failed order changed profile/rewards"
    client.request("POST", "/auth/logout", 200)
    client.request("GET", "/auth/me", 401, error_code="UNAUTHORIZED")


def main():
    mutating = os.environ.get("PUBBURI_SMOKE_MUTATING") == "1"
    report = {"status": "failed", "mode": "disposable-db" if mutating else "read-only", "completed": []}
    try:
        client = ApiClient(os.environ.get("PUBBURI_SERVER_PORT", "9987"))
        product = read_only_contract(client)
        report["completed"].append("read-only HTTP envelope, pagination, auth and validation")
        if mutating:
            mutating_contract(client, product, os.environ)
            report["completed"].append("session, authorization, order persistence and rollback")
            print("Packaged HTTP session, validation, order persistence and rollback checks passed.")
        else:
            print("Read-only packaged HTTP contract checks passed; mutating checks not enabled.")
        report["status"] = "passed"
    except Exception as error:
        # Keep diagnostics useful without exporting credentials, tokens or payloads.
        report["error_type"] = type(error).__name__
        raise
    finally:
        output = Path("target/api-smoke-report.json")
        output.parent.mkdir(exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

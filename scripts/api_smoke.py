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


def verify_feature_openapi(document):
    """Check the real generated document, independent from the business API responses."""
    def resolve(schema):
        while "$ref" in schema:
            target = document
            for part in schema["$ref"].removeprefix("#/").split("/"):
                target = target[part.replace("~1", "/").replace("~0", "~")]
            schema = target
        if "allOf" in schema:
            merged = dict(schema, properties={})
            for part in schema["allOf"]:
                merged["properties"].update(resolve(part).get("properties", {}))
            return merged
        return schema

    read = document["paths"]["/api/products/{id}/features"]["get"]
    write = document["paths"]["/api/admin/products/{id}/features"]["put"]
    envelope = resolve(read["responses"]["200"]["content"]["application/json"]["schema"])
    response = resolve(envelope["properties"]["data"])
    request = resolve(write["requestBody"]["content"]["application/json"]["schema"])
    values = {"volumeMl", "description", "tasteTags", "aromaTags", "sourceName", "sourceUrl", "sourceNote"}
    assert set(response["properties"]) == values | {"productId"}, "Generated feature response fields differ"
    assert set(request["properties"]) == values, "Generated feature request exposed hidden fields"
    for field in values:
        schema = resolve(response["properties"][field])
        nullable = schema.get("nullable") is True or "null" in schema.get("type", [])
        nullable = nullable or any(part.get("type") == "null" for part in schema.get("anyOf", []))
        assert nullable, f"Generated response lost unknown/null semantics: {field}"


def documentation_contract(port):
    client = ApiClient(port)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    root = client.base.removesuffix("/api")
    with opener.open(root + "/v3/api-docs", timeout=15) as response:
        assert response.status == 200 and response.headers.get_content_type() == "application/json"
        document = json.load(response)
    assert document["openapi"].startswith("3."), "Expected an OpenAPI v3 document"
    verify_feature_openapi(document)
    with opener.open(root + "/docs", timeout=15) as response:
        assert response.status == 200 and response.headers.get_content_type() == "text/html"
        assert "swagger-ui" in response.read().decode("utf-8").lower(), "Swagger UI entry is absent"


def change_ci_product(environ, product_id, price, name):
    """Change only the proven disposable fixture; never accept SQL from a caller."""
    require_disposable_database(environ)
    if not isinstance(product_id, int) or product_id <= 0 or not isinstance(price, int) or price < 0:
        raise ValueError("Invalid CI product mutation")
    name_hex = name.encode("utf-8").hex()
    statement = (f"UPDATE t_product SET price = {price}, "
                 f"name = CONVERT(UNHEX('{name_hex}') USING utf8mb4) WHERE id = {product_id}")
    subprocess.run([
        "docker", "exec", "-e", "MYSQL_PWD", environ["PUBBURI_SMOKE_CONTAINER_ID"],
        "mysql", "-u" + environ["PUBBURI_DB_USER"], environ["PUBBURI_DB_NAME"],
        "--execute", statement,
    ], capture_output=True, text=True, check=True,
        env=dict(environ, MYSQL_PWD=environ.get("PUBBURI_DB_PASSWORD", "")))


def promote_ci_fixture_admin(environ, user_id):
    """Grant only a newly generated smoke fixture access to admin HTTP tests."""
    require_disposable_database(environ)
    if not re.fullmatch(r"ci_admin_[0-9a-f]{32}", user_id):
        raise ValueError("Only generated CI fixture accounts may be promoted")
    user_hex = user_id.encode("utf-8").hex()
    statement = ("UPDATE t_user SET role = 'ADMIN' WHERE id = "
                 f"CONVERT(UNHEX('{user_hex}') USING utf8mb4)")
    subprocess.run([
        "docker", "exec", "-e", "MYSQL_PWD", environ["PUBBURI_SMOKE_CONTAINER_ID"],
        "mysql", "-u" + environ["PUBBURI_DB_USER"], environ["PUBBURI_DB_NAME"],
        "--execute", statement,
    ], capture_output=True, text=True, check=True,
        env=dict(environ, MYSQL_PWD=environ.get("PUBBURI_DB_PASSWORD", "")))


def count_ci_product_features(environ, product_id):
    """Read only the feature counts for an owned fixture in the proven CI database."""
    require_disposable_database(environ)
    if type(product_id) is not int or not 1 <= product_id <= 2147483647:
        raise ValueError("Invalid fixture product ID")
    statement = (
        f"SELECT (SELECT COUNT(*) FROM t_product_feature WHERE product_id={product_id}), "
        f"(SELECT COUNT(*) FROM t_product_feature_tag WHERE product_id={product_id})")
    result = subprocess.run([
        "docker", "exec", "-e", "MYSQL_PWD", environ["PUBBURI_SMOKE_CONTAINER_ID"],
        "mysql", "-u" + environ["PUBBURI_DB_USER"], "--batch", "--skip-column-names",
        environ["PUBBURI_DB_NAME"], "--execute", statement,
    ], capture_output=True, text=True, check=True,
        env=dict(environ, MYSQL_PWD=environ.get("PUBBURI_DB_PASSWORD", "")))
    counts = tuple(int(value) for value in result.stdout.split())
    assert len(counts) == 2, "Unexpected feature-count result"
    return counts


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
        features = client.request("GET", f"/products/{product['id']}/features", 200)["data"]
        assert set(features) == {"productId", "volumeMl", "description", "tasteTags", "aromaTags",
                                 "sourceName", "sourceUrl", "sourceNote"}
        assert features["productId"] == product["id"]
    client.request("GET", "/products/0", 404, error_code="NOT_FOUND")
    client.request("GET", "/products/0/features", 404, error_code="NOT_FOUND")
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
    assert created["details"][0]["unitPrice"] == before_product["price"]
    assert created["totalPrice"] == before_product["price"] * 3
    assert created["priceBasis"] == "ORDER_SNAPSHOT"
    client.request("GET", f"/orders/{created['oId']}", 200)
    try:
        changed_price = before_product["price"] + 123
        change_ci_product(environ, product_id, changed_price, "CI renamed product")
        changed_product = client.request("GET", f"/products/{product_id}", 200)["data"]
        assert changed_product["price"] == changed_price and changed_product["name"] == "CI renamed product"
        assert client.request("GET", f"/orders/{created['oId']}", 200)["data"] == created, (
            "Changing catalog price/name changed an existing order snapshot")
        assert client.request("GET", orders_path, 200)["data"]["items"] == [created], (
            "Order page does not use the same immutable snapshots")
    finally:
        change_ci_product(environ, product_id, before_product["price"], before_product["name"])
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


def history_preservation_contract(environ):
    require_disposable_database(environ)
    port = environ.get("PUBBURI_SERVER_PORT", "9987")
    admin = ApiClient(port)
    admin_id, admin_password = "ci_admin_" + uuid.uuid4().hex, uuid.uuid4().hex
    admin.request("POST", "/users", 201, {"id": admin_id, "name": "CI admin", "pass": admin_password})
    promote_ci_fixture_admin(environ, admin_id)
    assert admin.request("POST", "/auth/login", 200, {
        "id": admin_id, "pass": admin_password})["data"]["role"] == "ADMIN"
    product = admin.request("POST", "/admin/products", 201, {
        "name": "CI history product", "type": "탁주", "price": 1234,
        "img": "ci-history.png", "abv": 6, "orderCount": 0,
    })["data"]
    buyer = ApiClient(port)
    buyer_id, buyer_password = "ci_history_" + uuid.uuid4().hex, uuid.uuid4().hex
    buyer.request("POST", "/users", 201, {"id": buyer_id, "name": "CI buyer", "pass": buyer_password})
    buyer.request("POST", "/auth/login", 200, {"id": buyer_id, "pass": buyer_password})
    created = buyer.request("POST", "/orders", 201, {
        "orderType": "TAKEOUT", "orderTable": "CI private note",
        "details": [{"productId": product["id"], "quantity": 2}],
    })["data"]
    order_path = f"/orders/{created['oId']}"
    before_total = admin.request("GET", "/admin/orders?page=1&size=100", 200)["data"]["total"]
    admin.request("DELETE", f"/admin/products/{product['id']}", 200)
    buyer.request("GET", f"/products/{product['id']}", 404, error_code="NOT_FOUND")
    expected = json.loads(json.dumps(created))
    expected["details"][0]["productId"] = None
    assert buyer.request("GET", order_path, 200)["data"] == expected, "Product deletion changed order history"
    buyer.request("POST", "/orders", 400, {
        "details": [{"productId": product["id"], "quantity": 1}],
    }, "BAD_REQUEST")
    admin.request("DELETE", f"/admin/users/{buyer_id}", 200)
    expected["userId"], expected["orderTable"] = None, None
    assert admin.request("GET", order_path, 200)["data"] == expected, "Account deletion changed order snapshots"
    buyer.request("GET", order_path, 401, error_code="UNAUTHORIZED")
    admin_orders = admin.request("GET", "/admin/orders?page=1&size=100", 200)["data"]
    assert admin_orders["total"] == before_total, "Deletion removed an order from pagination"
    assert next(o for o in admin_orders["items"] if o["oId"] == created["oId"]) == expected
    replacement = ApiClient(port)
    replacement_password = uuid.uuid4().hex
    replacement.request("POST", "/users", 201, {
        "id": buyer_id, "name": "New account with reused ID", "pass": replacement_password,
    })
    replacement.request("POST", "/auth/login", 200, {"id": buyer_id, "pass": replacement_password})
    assert replacement.request("GET", f"/users/{buyer_id}/orders", 200)["data"]["total"] == 0
    replacement.request("GET", order_path, 403, error_code="FORBIDDEN")


def session_authority_contract(environ):
    """Use independent cookie jars to exercise live account/credential boundaries."""
    require_disposable_database(environ)
    port = environ.get("PUBBURI_SERVER_PORT", "9987")

    def account(role="USER"):
        client = ApiClient(port)
        user_id = ("ci_admin_" if role == "ADMIN" else "ci_session_") + uuid.uuid4().hex
        password = uuid.uuid4().hex
        client.request("POST", "/users", 201, {"id": user_id, "name": "CI session", "pass": password})
        if role == "ADMIN":
            promote_ci_fixture_admin(environ, user_id)
        client.request("POST", "/auth/login", 200, {"id": user_id, "pass": password})
        return client, user_id, password

    admin, admin_id, admin_password = account("ADMIN")
    demoted, demoted_id, _ = account("ADMIN")
    demoted.request("GET", "/admin/users", 200)
    admin.request("PUT", f"/admin/users/{demoted_id}", 200, {"role": "USER"})
    demoted.request("GET", "/admin/users", 403, error_code="FORBIDDEN")
    assert demoted.request("GET", "/auth/me", 200)["data"]["role"] == "USER"

    deleted, deleted_id, deleted_password = account()
    dormant = ApiClient(port)
    dormant.request("POST", "/auth/login", 200, {"id": deleted_id, "pass": deleted_password})
    admin.request("DELETE", f"/admin/users/{deleted_id}", 200)
    deleted.request("GET", "/auth/me", 401, error_code="UNAUTHORIZED")
    replacement = ApiClient(port)
    replacement_password = uuid.uuid4().hex
    replacement.request("POST", "/users", 201, {
        "id": deleted_id, "name": "Recreated account", "pass": replacement_password,
    })
    # This cookie was not queried between deletion and recreation.
    dormant.request("GET", f"/users/{deleted_id}/profile", 401, error_code="UNAUTHORIZED")
    replacement.request("POST", "/auth/login", 200, {"id": deleted_id, "pass": replacement_password})
    assert replacement.request("GET", "/auth/me", 200)["data"]["name"] == "Recreated account"

    changed, changed_id, old_password = account()
    new_password = uuid.uuid4().hex
    admin.request("PUT", f"/admin/users/{changed_id}", 200, {"pass": new_password})
    changed.request("GET", "/auth/me", 401, error_code="UNAUTHORIZED")
    login = ApiClient(port)
    login.request("POST", "/auth/login", 401, {"id": changed_id, "pass": old_password}, "UNAUTHORIZED")
    login.request("POST", "/auth/login", 200, {"id": changed_id, "pass": new_password})
    login.request("GET", "/auth/me", 200)

    profile, profile_id, _ = account()
    admin.request("PUT", f"/admin/users/{profile_id}", 200, {"name": "Updated session", "stamps": 17})
    current = profile.request("GET", "/auth/me", 200)["data"]
    assert set(current) == {"id", "name", "stamps", "role", "admin"}
    assert current["name"] == "Updated session" and current["stamps"] == 17 and current["role"] == "USER"

    other_admin_session = ApiClient(port)
    other_admin_session.request("POST", "/auth/login", 200, {"id": admin_id, "pass": admin_password})
    admin.request("PUT", f"/admin/users/{admin_id}", 200, {"pass": uuid.uuid4().hex})
    admin.request("GET", "/admin/users", 200)
    other_admin_session.request("GET", "/auth/me", 401, error_code="UNAUTHORIZED")


def product_feature_contract(environ):
    """Exercise unknown/known attributes without treating fixture values as product facts."""
    require_disposable_database(environ)
    port = environ.get("PUBBURI_SERVER_PORT", "9987")
    public = ApiClient(port)
    admin_id = "ci_admin_" + uuid.uuid4().hex
    password = uuid.uuid4().hex
    public.request("POST", "/users", 201, {"id": admin_id, "name": "CI features", "pass": password})
    member = ApiClient(port)
    member.request("POST", "/auth/login", 200, {"id": admin_id, "pass": password})
    public.request("PUT", "/admin/products/0/features", 401, {}, "UNAUTHORIZED")
    member.request("PUT", "/admin/products/0/features", 403, {}, "FORBIDDEN")
    promote_ci_fixture_admin(environ, admin_id)
    admin = ApiClient(port)
    admin.request("POST", "/auth/login", 200, {"id": admin_id, "pass": password})
    created = admin.request("POST", "/admin/products", 201, {
        "name": "CI feature fixture", "type": "탁주", "price": 15000, "img": "ci-feature.png",
        "abv": 10, "orderCount": 0,
    })["data"]
    product_id = created["id"]
    path = f"/products/{product_id}/features"
    write_path = f"/admin/products/{product_id}/features"
    unknown = {"productId": product_id, "volumeMl": None, "description": None, "tasteTags": None,
               "aromaTags": None, "sourceName": None, "sourceUrl": None, "sourceNote": None}
    assert public.request("GET", path, 200)["data"] == unknown
    values = {
        "volumeMl": 750, "description": " Synthetic validation data ", "tasteTags": [" Sweet ", "DRY"],
        "aromaTags": [], "sourceName": " Synthetic CI fixture ",
        "sourceUrl": "https://example.invalid/fixture", "sourceNote": "Not product facts or rights approval",
    }
    saved = admin.request("PUT", write_path, 200, values)["data"]
    assert saved == dict(values, productId=product_id, description="Synthetic validation data",
                         tasteTags=["dry", "sweet"], sourceName="Synthetic CI fixture")
    assert saved["aromaTags"] == [] and saved["tasteTags"] != []
    assert public.request("GET", path, 200)["data"] == saved
    assert admin.request("PUT", write_path, 200, values)["data"] == saved
    assert public.request("GET", f"/products/{product_id}", 200)["data"] == created
    assert count_ci_product_features(environ, product_id) == (1, 2)
    for invalid in ({"tasteTags": []}, {"volumeMl": 0, "sourceName": "Fixture"},
                    {"volumeMl": 750.5, "sourceName": "Fixture"},
                    {"sourceUrl": "file:///invalid"}, {"tasteTags": [None], "sourceName": "Fixture"},
                    {"tasteTags": [1], "sourceName": "Fixture"},
                    {"aromaTags": [True], "sourceName": "Fixture"},
                    {"tasteTags": [{}], "sourceName": "Fixture"},
                    {"description": True, "sourceName": "Fixture"}, {"sourceName": 42, "tasteTags": []}):
        admin.request("PUT", write_path, 400, invalid, "VALIDATION_FAILED")
    admin.request("PUT", write_path, 400, {"tasteTags": ["sweet", " SWEET "], "sourceName": "Fixture"},
                  "BAD_REQUEST")
    assert public.request("GET", path, 200)["data"] == saved
    assert admin.request("PUT", write_path, 200, {})["data"] == unknown
    assert public.request("GET", path, 200)["data"] == unknown
    assert count_ci_product_features(environ, product_id) == (1, 0)
    admin.request("PUT", write_path, 200, values)
    admin.request("DELETE", f"/admin/products/{product_id}", 200)
    public.request("GET", path, 404, error_code="NOT_FOUND")
    admin.request("PUT", write_path, 404, {}, "NOT_FOUND")
    assert count_ci_product_features(environ, product_id) == (0, 0)


def main():
    mutating = os.environ.get("PUBBURI_SMOKE_MUTATING") == "1"
    report = {"status": "failed", "mode": "disposable-db" if mutating else "read-only", "completed": []}
    try:
        client = ApiClient(os.environ.get("PUBBURI_SERVER_PORT", "9987"))
        product = read_only_contract(client)
        report["completed"].append("read-only HTTP envelope, pagination, auth and validation")
        documentation_contract(os.environ.get("PUBBURI_SERVER_PORT", "9987"))
        report["completed"].append("runtime OpenAPI feature paths/schemas and Swagger UI")
        if mutating:
            mutating_contract(client, product, os.environ)
            report["completed"].append("session, authorization, order price snapshots and rollback")
            history_preservation_contract(os.environ)
            report["completed"].append("deleted references, anonymized owner and preserved order snapshots")
            session_authority_contract(os.environ)
            report["completed"].append("live roles, account incarnation, password revocation and profile freshness")
            product_feature_contract(os.environ)
            report["completed"].append("product attributes, unknown/empty semantics, source validation and deletion")
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

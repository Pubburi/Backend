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

    def request(self, method, path, expected_status, payload=None, error_code=None, *,
                content_type="application/json", accept="application/json", allowed_methods=None):
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base + path, data=data, method=method,
            headers={"Content-Type": content_type, "Accept": accept})
        try:
            response = self.opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == expected_status, (
                f"{method} {path}: expected HTTP {expected_status}, got {response.status}")
            assert response.headers.get_content_type() == "application/json", "Expected JSON response"
            if allowed_methods is not None:
                allowed = {item.strip() for item in response.headers.get("Allow", "").split(",")}
                assert set(allowed_methods) <= allowed, "Required Allow methods are missing"
            envelope = json.load(response)
        assert set(envelope) == {"data", "message", "error"}, "ApiResponse contract changed"
        if error_code is None:
            assert envelope["error"] is None, f"Unexpected API error from {method} {path}"
        else:
            assert envelope["data"] is None, "Error response exposed a data payload"
            assert envelope["error"]["code"] == error_code, "Unexpected API error code"
        return envelope


def multipart_security_contract(environ):
    """Tiny parser probes only; never induce large uploads or memory exhaustion."""
    require_disposable_database(environ)
    root = ApiClient(environ.get("PUBBURI_SERVER_PORT", "9987")).base
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    boundary = "pubburi-owned-fixture"
    for extra, status, code in (("", 415, "UNSUPPORTED_MEDIA_TYPE"),
                                ("X-Fixture: " + "a" * 2048 + "\r\n", 413, "PAYLOAD_TOO_LARGE")):
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"fixture\"\r\n"
                + extra + f"\r\nx\r\n--{boundary}--\r\n").encode("ascii")
        request = urllib.request.Request(root + "/auth/login", data=body, method="POST", headers={
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "Accept": "application/json",
        })
        try:
            response = opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == status, "Unexpected multipart status"
            assert response.headers.get_content_type() == "application/json"
            envelope = json.load(response)
        assert set(envelope) == {"data", "message", "error"}
        assert envelope["data"] is None and envelope["error"]["code"] == code
        assert envelope["error"]["fields"] == {}
        assert envelope["error"]["message"] == (
            "Unsupported Content-Type" if status == 415 else "Multipart request is too large")


def json_charset_contract(environ):
    """Small owned JSON fixtures; charset compatibility is not a DoS benchmark."""
    require_disposable_database(environ)
    client = ApiClient(environ.get("PUBBURI_SERVER_PORT", "9987"))
    user_id, password = "ci_json_" + uuid.uuid4().hex, uuid.uuid4().hex
    client.request("POST", "/users", 201, {"id": user_id, "name": "CI JSON fixture", "pass": password})
    login = {"id": user_id, "pass": password}

    def send(payload, charset, expected_status):
        request = urllib.request.Request(client.base + "/auth/login", method="POST",
            data=json.dumps(payload).encode(charset), headers={
                "Content-Type": "application/json; charset=" + charset,
                "Accept": "application/json",
            })
        try:
            response = client.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == expected_status, "Unexpected JSON charset response"
            assert response.headers.get_content_type() == "application/json"
            envelope = json.load(response)
        assert set(envelope) == {"data", "message", "error"}
        return envelope

    for charset in ("utf-8", "iso-8859-1", "utf-16"):
        normal = send(login, charset, 200)
        assert normal["error"] is None and normal["data"]["id"] == user_id
        assert set(normal["data"]) == {"id", "name", "stamps", "role", "admin"}
        profile = client.request("GET", "/auth/me", 200)["data"]
        invalid = send(dict(login, **{"private-source-" + "a" * 60000: None}), charset, 400)
        assert invalid["data"] is None
        assert invalid["error"] == {"code": "VALIDATION_FAILED", "message": "Malformed request body", "fields": {}}
        assert client.request("GET", "/auth/me", 200)["data"] == profile


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
    verify_preference_openapi(document)
    verify_favorite_openapi(document)
    verify_default_recommendation_openapi(document)
    verify_product_filter_openapi(document)
    with opener.open(root + "/docs", timeout=15) as response:
        assert response.status == 200 and response.headers.get_content_type() == "text/html"
        assert "swagger-ui" in response.read().decode("utf-8").lower(), "Swagger UI entry is absent"


def verify_preference_openapi(document):
    def resolve(schema):
        while "$ref" in schema:
            target = document
            for part in schema["$ref"].removeprefix("#/").split("/"):
                target = target[part.replace("~1", "/").replace("~0", "~")]
            schema = target
        return schema

    def nullable(schema):
        schema = resolve(schema)
        return schema.get("nullable") is True or "null" in schema.get("type", [])

    operations = document["paths"]["/api/users/me/preferences"]
    assert "/api/users/{id}/preferences" not in document["paths"], "Unexpected private-by-ID endpoint"
    envelope = resolve(operations["get"]["responses"]["200"]["content"]["application/json"]["schema"])
    response = resolve(envelope["properties"]["data"])
    request = resolve(operations["put"]["requestBody"]["content"]["application/json"]["schema"])
    expected = {"surveyStatus", "softPreferences", "hardConstraints"}
    assert set(response["properties"]) == set(request["properties"]) == expected
    statuses = {"NOT_STARTED", "SKIPPED", "COMPLETED"}
    assert set(response["properties"]["surveyStatus"]["enum"]) == statuses
    assert set(request["properties"]["surveyStatus"]["enum"]) == statuses
    for group, fields in (
        ("softPreferences", {"categories", "tasteTags", "aromaTags", "targetAbvPercent"}),
        ("hardConstraints", {"minPriceKrw", "maxPriceKrw", "minAbvPercent", "maxAbvPercent"}),
    ):
        values = resolve(response["properties"][group])["properties"]
        assert set(values) == fields, "Soft targets and hard bounds were mixed"
        assert all(nullable(value) for value in values.values()), "Unknown preference fields lost null"
        assert nullable(request["properties"][group]), "Omitted/null input groups must mean unknown"


def verify_favorite_openapi(document):
    def resolve(schema):
        while "$ref" in schema:
            target = document
            for part in schema["$ref"].removeprefix("#/").split("/"):
                target = target[part.replace("~1", "/").replace("~0", "~")]
            schema = target
        return schema

    paths = document["paths"]
    assert "/api/users/{id}/favorites" not in paths, "Unexpected other-account favorites endpoint"
    listing = paths["/api/users/me/favorites"]["get"]
    member = paths["/api/users/me/favorites/{productId}"]
    assert "post" not in member, "Toggle POST must not be advertised"
    envelope = resolve(listing["responses"]["200"]["content"]["application/json"]["schema"])
    page = resolve(envelope["properties"]["data"])
    assert set(page["properties"]) == {"items", "page", "size", "total", "totalPages", "hasNext"}
    product = resolve(page["properties"]["items"]["items"])
    assert set(product["properties"]) == {"id", "name", "type", "price", "img", "abv", "orderCount"}
    for method in ("get", "put", "delete"):
        operation = member[method]
        assert "requestBody" not in operation, "Membership operations do not consume a body"
        envelope = resolve(operation["responses"]["200"]["content"]["application/json"]["schema"])
        assert set(resolve(envelope["properties"]["data"])["properties"]) == {"productId", "favorited"}


def verify_default_recommendation_openapi(document):
    def resolve(schema):
        while "$ref" in schema:
            target = document
            for part in schema["$ref"].removeprefix("#/").split("/"):
                target = target[part]
            schema = target
        return schema
    operation = document["paths"]["/api/recommendations/default"]["get"]
    assert "requestBody" not in operation
    envelope = resolve(operation["responses"]["200"]["content"]["application/json"]["schema"])
    data = resolve(envelope["properties"]["data"])
    assert set(data["properties"]) == {"items", "source"}
    assert set(resolve(data["properties"]["source"])["enum"]) == {"BASELINE", "FALLBACK"}
    product = resolve(data["properties"]["items"]["items"])
    assert set(product["properties"]) == {"id", "name", "type", "price", "img", "abv", "orderCount"}



def verify_product_filter_openapi(document):
    operation = document["paths"]["/api/products"]["get"]
    assert "application/json" in operation["responses"]["200"]["content"]
    parameters = {item["name"]: item for item in operation["parameters"]}
    assert {"type", "q", "sort", "page", "size", "minPriceKrw", "maxPriceKrw",
            "minAbvPercent", "maxAbvPercent", "tasteTag", "aromaTag"} <= set(parameters)
    for name in ("tasteTag", "aromaTag"):
        value = parameters[name]
        assert value["style"] == "form" and value["explode"] is True
        assert value["schema"]["type"] == "array" and value["schema"]["maxItems"] == 20
    assert "2147483647 OFFSET" in parameters["page"]["description"]
    assert "400 BAD_REQUEST" in parameters["page"]["description"]
    assert parameters["minPriceKrw"]["schema"]["type"] == "integer"
    assert parameters["maxAbvPercent"]["schema"]["maximum"] == 100



def product_page_offset_contract(client):
    """Fixed invalid integer offsets must not wrap into SQL or a different page."""
    for page, size in ((2147483647, 100), (1073741825, 4), (42949674, 100), (21474838, 100)):
        error = client.request("GET", f"/products?page={page}&size={size}", 400, error_code="BAD_REQUEST")["error"]
        assert error["message"] == "Product page offset exceeds the supported range" and error["fields"] == {}
    for page, size, normalized_size in ((0, 0, 12), (-5, -5, 12), (1, 1000, 100),
                                         (-2147483648, 2147483647, 100)):
        query = f"/products?page={page}&size={size}&sort=priceAsc"
        expected = f"/products?page=1&size={normalized_size}&sort=priceAsc"
        assert client.request("GET", query, 200)["data"] == client.request("GET", expected, 200)["data"]


def default_recommendation_contract(client, expected_source=None):
    total = client.request("GET", "/products?size=1", 200)["data"]["total"]
    for query, size in (("", 4), ("?size=1", 1), ("?size=12", 12)):
        data = client.request("GET", "/recommendations/default" + query, 200)["data"]
        assert set(data) == {"items", "source"}
        assert data["source"] in {"BASELINE", "FALLBACK"}
        if expected_source is not None:
            assert data["source"] == expected_source, "Unexpected configured recommendation source"
        assert len(data["items"]) == min(total, size)
        ids = [item["id"] for item in data["items"]]
        assert ids == sorted(set(ids)), "Default selection must remain unique and ID-ordered"
        for item in data["items"]:
            assert set(item) == {"id", "name", "type", "price", "img", "abv", "orderCount"}
            assert client.request("GET", f"/products/{item['id']}", 200)["data"] == item
    for size in ("0", "13", "abc", "1.5", "2147483648"):
        client.request("GET", "/recommendations/default?size=" + size, 400, error_code="BAD_REQUEST")


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
    admin.request("PUT", write_path, 415, {}, "UNSUPPORTED_MEDIA_TYPE", content_type="text/plain")
    public.request("GET", path, 406, error_code="NOT_ACCEPTABLE", accept="text/plain")
    assert public.request("GET", path, 200)["data"] == saved
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



def product_filter_contract(environ):
    """Mandatory search filters over synthetic, disposable product facts only."""
    require_disposable_database(environ)
    public = ApiClient(environ.get("PUBBURI_SERVER_PORT", "9987"))
    admin_id, password = "ci_admin_" + uuid.uuid4().hex, uuid.uuid4().hex
    public.request("POST", "/users", 201, {"id": admin_id, "name": "CI filters", "pass": password})
    promote_ci_fixture_admin(environ, admin_id)
    admin = ApiClient(environ.get("PUBBURI_SERVER_PORT", "9987"))
    admin.request("POST", "/auth/login", 200, {"id": admin_id, "pass": password})
    prefix = "ci_filter_" + uuid.uuid4().hex
    products = []
    for index, (price, abv, taste, aroma) in enumerate((
        (1000, 5.1, ["sweet", "dry", "dry,fruit"], ["floral"]),
        (2000, 5.55, ["sweet"], []),
        (2000, 5.555, None, None),
        (3000, 0, [], ["floral"]),
        (2000, 5.1, ["sweet"], ["floral"]),
    )):
        product = admin.request("POST", "/admin/products", 201, {
            "name": prefix + str(index), "type": "맥주" if index == 4 else "탁주", "price": price,
            "img": "ci-filter.png", "abv": abv, "orderCount": 0,
        })["data"]
        products.append(product)
        if taste is not None:
            admin.request("PUT", f"/admin/products/{product['id']}/features", 200, {
                "tasteTags": taste, "aromaTags": aroma, "sourceName": "Synthetic search fixture",
                "sourceNote": "Validation input, not product facts or rights approval",
            })
    a, b, c, d, e = [product["id"] for product in products]

    def search(expected, values=(), *, sort="priceAsc", page=1, size=100, total=None):
        query = urllib.parse.urlencode([("q", prefix), ("sort", sort), ("page", page), ("size", size), *values])
        data = public.request("GET", "/products?" + query, 200)["data"]
        expected_total = len(expected) if total is None else total
        assert set(data) == {"items", "page", "size", "total", "totalPages", "hasNext"}
        assert [row["id"] for row in data["items"]] == expected
        assert data["total"] == expected_total and data["page"] == page and data["size"] == size
        assert data["totalPages"] == (expected_total + size - 1) // size
        assert data["hasNext"] == (page < data["totalPages"])
        for row in data["items"]:
            assert set(row) == {"id", "name", "type", "price", "img", "abv", "orderCount"}
        return data

    search([a, b, c, e, d])
    search([a, b, c, e], [("minPriceKrw", "1000"), ("maxPriceKrw", "2000")])
    search([], [("maxPriceKrw", "0")])
    search([a, e], [("minAbvPercent", "5.10"), ("maxAbvPercent", "5.10")])
    search([a, b, e, d], [("maxAbvPercent", "5.55")])
    search([], [("minAbvPercent", "5.56")])
    search([d], [("minAbvPercent", "0"), ("maxAbvPercent", "0")])
    search([a], [("tasteTag", " SWEET "), ("tasteTag", "dry")])
    search([a], [("tasteTag", "dry,fruit")])
    search([a, b, e], [("tasteTag", "sweet")])  # unknown c and reviewed-empty d do not match.
    both = [("tasteTag", "sweet"), ("aromaTag", "floral")]
    search([a], both, size=1, total=2)
    search([e], both, page=2, size=1, total=2)
    search([a], [*both, ("type", "탁주"), ("minPriceKrw", "1000"), ("maxAbvPercent", "5.10")])
    search([], [("tasteTag", "x' OR 1=1 --")])
    search([d, b, c, e, a], sort="priceDesc")
    search([a, b, c, d, e], sort="name")
    search([a, b, c, d, e], sort="popular")
    search([a, b, c, d, e], sort="unsupported")  # Existing fallback remains popular.
    for values in ([('minPriceKrw', '-1')], [('maxPriceKrw', '2147483648')],
                   [('minPriceKrw', '1.1')], [('minPriceKrw', '')],
                   [('minPriceKrw', '2'), ('maxPriceKrw', '1')],
                   [('minAbvPercent', '5.555')], [('maxAbvPercent', 'NaN')],
                   [('minAbvPercent', '100.01')], [('minAbvPercent', '1e1')],
                   [('minAbvPercent', '5.2'), ('maxAbvPercent', '5.1')],
                   [('tasteTag', '')], [('aromaTag', 'floral'), ('aromaTag', ' FLORAL ')],
                   [('tasteTag', 'x' * 41)], [('tasteTag', str(i)) for i in range(21)]):
        public.request("GET", "/products?" + urllib.parse.urlencode([("q", prefix), *values]),
                       400, error_code="BAD_REQUEST")
    search([a, b, c, e, d])
    for product in products:
        assert public.request("GET", f"/products/{product['id']}", 200)["data"] == product
        admin.request("DELETE", f"/admin/products/{product['id']}", 200)
    search([])


def count_ci_preferences(environ, user_id):
    require_disposable_database(environ)
    if not isinstance(user_id, str) or not re.fullmatch(r"ci_pref_[0-9a-f]{32}", user_id):
        raise ValueError("Only generated private-preference fixture IDs are supported")
    user_hex = user_id.encode("utf-8").hex()
    predicate = f"CAST(user_id AS BINARY)=UNHEX('{user_hex}')"
    statement = (f"SELECT (SELECT COUNT(*) FROM t_user_preference WHERE {predicate}), "
                 f"(SELECT COUNT(*) FROM t_user_preference_value WHERE {predicate})")
    result = subprocess.run([
        "docker", "exec", "-e", "MYSQL_PWD", environ["PUBBURI_SMOKE_CONTAINER_ID"],
        "mysql", "-u" + environ["PUBBURI_DB_USER"], "--batch", "--skip-column-names",
        environ["PUBBURI_DB_NAME"], "--execute", statement,
    ], capture_output=True, text=True, check=True,
        env=dict(environ, MYSQL_PWD=environ.get("PUBBURI_DB_PASSWORD", "")))
    counts = tuple(int(value) for value in result.stdout.split())
    assert len(counts) == 2, "Unexpected private-preference count result"
    return counts


def private_preference_contract(environ):
    """Synthetic declarations test storage/privacy, never inferred preferences or user quality."""
    require_disposable_database(environ)
    port = environ.get("PUBBURI_SERVER_PORT", "9987")
    path = "/users/me/preferences"
    public = ApiClient(port)

    def account(admin=False):
        user_id = ("ci_admin_" if admin else "ci_pref_") + uuid.uuid4().hex
        password = uuid.uuid4().hex
        public.request("POST", "/users", 201, {"id": user_id, "name": "CI explicit preference", "pass": password})
        if admin:
            promote_ci_fixture_admin(environ, user_id)
        client = ApiClient(port)
        client.request("POST", "/auth/login", 200, {"id": user_id, "pass": password})
        return client, user_id, password

    public.request("GET", path, 401, error_code="UNAUTHORIZED")
    public.request("PUT", path, 401, {"surveyStatus": "SKIPPED"}, "UNAUTHORIZED")
    owner, user_id, password = account()
    other, _, _ = account()
    admin, _, _ = account(True)
    soft_null = {"categories": None, "tasteTags": None, "aromaTags": None, "targetAbvPercent": None}
    hard_null = {"minPriceKrw": None, "maxPriceKrw": None, "minAbvPercent": None, "maxAbvPercent": None}

    def empty(status="NOT_STARTED"):
        return {"surveyStatus": status, "softPreferences": dict(soft_null), "hardConstraints": dict(hard_null)}

    assert owner.request("GET", path, 200)["data"] == empty()
    declared = {"surveyStatus": "COMPLETED", "softPreferences": {
        "categories": [" 탁주 "], "tasteTags": [" Sweet ", "DRY"], "aromaTags": None, "targetAbvPercent": 80.25,
    }, "hardConstraints": {"minPriceKrw": 0, "maxPriceKrw": 15000, "minAbvPercent": 0, "maxAbvPercent": 10.5}}
    saved = owner.request("PUT", path, 200, declared)["data"]
    assert saved["softPreferences"] == dict(declared["softPreferences"], categories=["탁주"], tasteTags=["dry", "sweet"])
    assert saved["hardConstraints"] == declared["hardConstraints"]
    assert owner.request("GET", path, 200)["data"] == saved
    assert owner.request("PUT", path, 200, declared)["data"] == saved
    assert other.request("GET", path, 200)["data"] == empty()
    assert admin.request("GET", path + "?userId=" + user_id, 200)["data"] == empty()
    admin.request("PUT", path, 200, {"surveyStatus": "COMPLETED", "userId": user_id,
                                   "softPreferences": {"tasteTags": ["admin-own"]}})
    assert owner.request("GET", path, 200)["data"] == saved
    assert admin.request("GET", path, 200)["data"]["softPreferences"]["tasteTags"] == ["admin-own"]
    owner.request("PUT", path, 415, {"surveyStatus": "SKIPPED"}, "UNSUPPORTED_MEDIA_TYPE", content_type="text/plain")
    owner.request("GET", path, 406, error_code="NOT_ACCEPTABLE", accept="text/plain")
    invalids = [
        ({}, "VALIDATION_FAILED"), ({"surveyStatus": True}, "VALIDATION_FAILED"),
        ({"surveyStatus": "UNKNOWN"}, "VALIDATION_FAILED"),
        ({"surveyStatus": "SKIPPED", "softPreferences": {"tasteTags": []}}, "VALIDATION_FAILED"),
        ({"surveyStatus": "NOT_STARTED", "hardConstraints": {"maxPriceKrw": 0}}, "VALIDATION_FAILED"),
    ]
    for value in ("12.5", True, -1, 100.01, 10.123):
        invalids.append(({"surveyStatus": "COMPLETED", "softPreferences": {"targetAbvPercent": value}}, "VALIDATION_FAILED"))
    for value in ("100", True, 1.5, -1, 2147483648):
        invalids.append(({"surveyStatus": "COMPLETED", "hardConstraints": {"maxPriceKrw": value}}, "VALIDATION_FAILED"))
    for tags in ([1], [True], [None], [{}], [" "]):
        invalids.append(({"surveyStatus": "COMPLETED", "softPreferences": {"tasteTags": tags}}, "VALIDATION_FAILED"))
    invalids.extend([
        ({"surveyStatus": "COMPLETED", "softPreferences": {"tasteTags": ["SWEET", " sweet "]}}, "BAD_REQUEST"),
        ({"surveyStatus": "COMPLETED", "softPreferences": {"categories": ["invented"]}}, "BAD_REQUEST"),
        ({"surveyStatus": "COMPLETED", "hardConstraints": {"minPriceKrw": 2, "maxPriceKrw": 1}}, "VALIDATION_FAILED"),
        ({"surveyStatus": "COMPLETED", "hardConstraints": {"minAbvPercent": 2, "maxAbvPercent": 1}}, "VALIDATION_FAILED"),
    ])
    for value, code in invalids:
        owner.request("PUT", path, 400, value, code)
    assert owner.request("GET", path, 200)["data"] == saved
    zeros = {"surveyStatus": "COMPLETED", "softPreferences": {
        "categories": [], "tasteTags": [], "aromaTags": [], "targetAbvPercent": 0,
    }, "hardConstraints": {"minPriceKrw": 0, "maxPriceKrw": 0, "minAbvPercent": 0, "maxAbvPercent": 0}}
    assert owner.request("PUT", path, 200, zeros)["data"] == zeros
    assert owner.request("GET", path, 200)["data"] == zeros
    for status in ("SKIPPED", "NOT_STARTED", "COMPLETED"):
        assert owner.request("PUT", path, 200, {"surveyStatus": status})["data"] == empty(status)
        assert owner.request("GET", path, 200)["data"] == empty(status)
    owner.request("PUT", path, 200, declared)
    dormant = ApiClient(port)
    dormant.request("POST", "/auth/login", 200, {"id": user_id, "pass": password})
    admin.request("DELETE", f"/admin/users/{user_id}", 200)
    assert count_ci_preferences(environ, user_id) == (0, 0)
    owner.request("GET", path, 401, error_code="UNAUTHORIZED")
    replacement_password = uuid.uuid4().hex
    public.request("POST", "/users", 201, {"id": user_id, "name": "Fresh private fixture", "pass": replacement_password})
    fresh = ApiClient(port)
    fresh.request("POST", "/auth/login", 200, {"id": user_id, "pass": replacement_password})
    assert fresh.request("GET", path, 200)["data"] == empty()
    dormant.request("GET", path, 401, error_code="UNAUTHORIZED")
    dormant.request("PUT", path, 401, {"surveyStatus": "SKIPPED"}, "UNAUTHORIZED")
    assert fresh.request("GET", path, 200)["data"] == empty()


def count_ci_favorites(environ, user_id):
    require_disposable_database(environ)
    if not isinstance(user_id, str) or not re.fullmatch(r"ci_favorite_[0-9a-f]{32}", user_id):
        raise ValueError("Only generated favorite fixture IDs are supported")
    user_hex = user_id.encode("utf-8").hex()
    result = subprocess.run([
        "docker", "exec", "-e", "MYSQL_PWD", environ["PUBBURI_SMOKE_CONTAINER_ID"],
        "mysql", "-u" + environ["PUBBURI_DB_USER"], "--batch", "--skip-column-names",
        environ["PUBBURI_DB_NAME"], "--execute",
        f"SELECT COUNT(*) FROM t_user_favorite WHERE CAST(user_id AS BINARY)=UNHEX('{user_hex}')",
    ], capture_output=True, text=True, check=True,
        env=dict(environ, MYSQL_PWD=environ.get("PUBBURI_DB_PASSWORD", "")))
    return int(result.stdout.strip())


def favorite_contract(environ):
    """Private persisted state, not popularity, behavioral training or conversion evidence."""
    require_disposable_database(environ)
    port = environ.get("PUBBURI_SERVER_PORT", "9987")
    path = "/users/me/favorites"
    public = ApiClient(port)

    def account(admin=False):
        user_id = ("ci_admin_" if admin else "ci_favorite_") + uuid.uuid4().hex
        password = uuid.uuid4().hex
        public.request("POST", "/users", 201, {"id": user_id, "name": "CI favorite fixture", "pass": password})
        if admin:
            promote_ci_fixture_admin(environ, user_id)
        client = ApiClient(port)
        client.request("POST", "/auth/login", 200, {"id": user_id, "pass": password})
        return client, user_id, password

    public.request("GET", path, 401, error_code="UNAUTHORIZED")
    public.request("GET", path + "/1", 401, error_code="UNAUTHORIZED")
    public.request("PUT", path + "/1", 401, error_code="UNAUTHORIZED")
    public.request("DELETE", path + "/1", 401, error_code="UNAUTHORIZED")
    owner, user_id, password = account()
    other, _, _ = account()
    admin, _, _ = account(True)
    fixture = {"name": "CI favorite product", "type": "맥주", "price": 4000, "img": "ci-favorite.png", "abv": 4.5, "orderCount": 0}
    first = admin.request("POST", "/admin/products", 201, fixture)["data"]
    second = admin.request("POST", "/admin/products", 201, dict(fixture, name="CI second favorite"))["data"]
    first_path = path + "/" + str(first["id"])
    second_path = path + "/" + str(second["id"])
    assert owner.request("GET", path, 200)["data"]["total"] == 0
    assert owner.request("GET", first_path, 200)["data"] == {"productId": first["id"], "favorited": False}
    assert owner.request("PUT", first_path, 200)["data"] == {"productId": first["id"], "favorited": True}
    assert owner.request("GET", first_path, 200)["data"]["favorited"] is True
    owner.request("PUT", second_path, 200)
    owner.request("PUT", first_path, 200)
    page = owner.request("GET", path + "?size=1", 200)["data"]
    assert page["total"] == 2 and page["totalPages"] == 2 and page["hasNext"] is True
    assert page["items"] == [second]
    page2 = owner.request("GET", path + "?page=2&size=1", 200)["data"]
    assert page2["items"] == [first] and page2["hasNext"] is False
    assert owner.request("GET", path + "?page=3&size=1", 200)["data"]["items"] == []
    assert count_ci_favorites(environ, user_id) == 2
    another_session = ApiClient(port)
    another_session.request("POST", "/auth/login", 200, {"id": user_id, "pass": password})
    assert another_session.request("GET", path + "?size=1", 200)["data"] == page
    assert other.request("GET", path, 200)["data"]["total"] == 0
    assert other.request("GET", first_path + "?userId=" + user_id, 200)["data"]["favorited"] is False
    assert admin.request("GET", path + "?userId=" + user_id, 200)["data"]["total"] == 0
    assert admin.request("GET", first_path, 200)["data"]["favorited"] is False
    admin.request("PUT", first_path, 200, {"userId": user_id})
    assert owner.request("GET", path, 200)["data"]["total"] == 2
    assert public.request("GET", f"/products/{first['id']}", 200)["data"] == first
    owner.request("POST", first_path, 405, error_code="METHOD_NOT_ALLOWED", allowed_methods={"GET", "PUT", "DELETE"})
    owner.request("GET", path, 406, error_code="NOT_ACCEPTABLE", accept="text/plain")
    for suffix in ("/abc", "/0", "/-1", "/1.5", "/2147483648"):
        owner.request("GET", path + suffix, 400, error_code="BAD_REQUEST")
        owner.request("PUT", path + suffix, 400, error_code="BAD_REQUEST")
        owner.request("DELETE", path + suffix, 400, error_code="BAD_REQUEST")
    for query in ("?page=abc", "?size=1.5", "?page=2147483647&size=100"):
        owner.request("GET", path + query, 400, error_code="BAD_REQUEST")
    assert owner.request("GET", path + "?size=1", 200)["data"] == page
    updated = dict(fixture, name="Updated current favorite", price=4500)
    admin.request("PUT", f"/admin/products/{first['id']}", 200, updated)
    current = public.request("GET", f"/products/{first['id']}", 200)["data"]
    assert owner.request("GET", path + "?page=2&size=1", 200)["data"]["items"] == [current]
    public.request("GET", "/products/2147483647", 404, error_code="NOT_FOUND")
    owner.request("PUT", path + "/2147483647", 404, error_code="NOT_FOUND")
    assert owner.request("GET", path + "/2147483647", 200)["data"]["favorited"] is False
    assert owner.request("DELETE", path + "/2147483647", 200)["data"]["favorited"] is False
    owner.request("DELETE", second_path, 200)
    owner.request("DELETE", second_path, 200)
    assert owner.request("GET", second_path, 200)["data"]["favorited"] is False
    assert another_session.request("GET", path, 200)["data"]["items"] == [current]
    admin.request("DELETE", f"/admin/products/{first['id']}", 200)
    assert owner.request("GET", path, 200)["data"]["total"] == 0
    assert count_ci_favorites(environ, user_id) == 0
    assert owner.request("GET", first_path, 200)["data"]["favorited"] is False
    owner.request("DELETE", first_path, 200)
    owner.request("PUT", first_path, 404, error_code="NOT_FOUND")
    owner.request("PUT", second_path, 200)
    dormant = ApiClient(port)
    dormant.request("POST", "/auth/login", 200, {"id": user_id, "pass": password})
    admin.request("DELETE", f"/admin/users/{user_id}", 200)
    assert count_ci_favorites(environ, user_id) == 0
    owner.request("GET", path, 401, error_code="UNAUTHORIZED")
    owner.request("GET", second_path, 401, error_code="UNAUTHORIZED")
    replacement_password = uuid.uuid4().hex
    public.request("POST", "/users", 201, {"id": user_id, "name": "Fresh favorite fixture", "pass": replacement_password})
    fresh = ApiClient(port)
    fresh.request("POST", "/auth/login", 200, {"id": user_id, "pass": replacement_password})
    assert fresh.request("GET", path, 200)["data"]["total"] == 0
    dormant.request("GET", path, 401, error_code="UNAUTHORIZED")
    dormant.request("GET", second_path, 401, error_code="UNAUTHORIZED")
    dormant.request("PUT", second_path, 401, error_code="UNAUTHORIZED")
    dormant.request("DELETE", second_path, 401, error_code="UNAUTHORIZED")
    assert fresh.request("GET", path, 200)["data"]["total"] == 0
    assert fresh.request("GET", second_path, 200)["data"]["favorited"] is False
    admin.request("DELETE", f"/admin/products/{second['id']}", 200)


def ci_search_outbox(environ, product_id, *, failing_trigger=None):
    """Only the owned CI fixture: inspect durable events or inject one scoped failure."""
    require_disposable_database(environ)
    if type(product_id) is not int or not 1 <= product_id <= 2147483647:
        raise ValueError("Invalid fixture product ID")
    trigger = "ci_search_outbox_fail_" + str(product_id)
    if failing_trigger is True:
        statement = (f"CREATE TRIGGER {trigger} BEFORE INSERT ON t_product_search_outbox "
                     f"FOR EACH ROW SET NEW.source_version = IF(NEW.product_id={product_id}, 0, NEW.source_version)")
    elif failing_trigger is False:
        statement = f"DROP TRIGGER {trigger}"
    else:
        statement = ("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION READ ONLY; "
                     f"SELECT source_version FROM t_product_search_version WHERE product_id={product_id}; "
                     f"SELECT payload FROM t_product_search_outbox WHERE product_id={product_id} ORDER BY source_version; COMMIT;")
    result = subprocess.run([
        "docker", "exec", "-e", "MYSQL_PWD", environ["PUBBURI_SMOKE_CONTAINER_ID"],
        "mysql", "-u" + environ["PUBBURI_DB_USER"], "--default-character-set=utf8mb4",
        "--batch", "--raw", "--skip-column-names", environ["PUBBURI_DB_NAME"], "--execute", statement,
    ], capture_output=True, text=True, check=True,
        env=dict(environ, MYSQL_PWD=environ.get("PUBBURI_DB_PASSWORD", "")))
    if failing_trigger is not None:
        return None
    rows = result.stdout.splitlines()
    return {"version": int(rows[0]) if rows else None, "events": [json.loads(row) for row in rows[1:]]}


def search_outbox_contract(environ):
    require_disposable_database(environ)
    admin = ApiClient(environ.get("PUBBURI_SERVER_PORT", "9987"))
    admin_id, password = "ci_admin_" + uuid.uuid4().hex, uuid.uuid4().hex
    admin.request("POST", "/users", 201, {"id": admin_id, "name": "CI outbox admin", "pass": password})
    promote_ci_fixture_admin(environ, admin_id)
    admin.request("POST", "/auth/login", 200, {"id": admin_id, "pass": password})
    values = {"name": "Authored outbox fixture", "type": "탁주", "price": 1200,
              "img": "ci-outbox.png", "abv": 5.555, "orderCount": 0}
    product = admin.request("POST", "/admin/products", 201, values)["data"]
    product_id = product["id"]
    def state(): return ci_search_outbox(environ, product_id)
    first = state()
    assert first["version"] == 1 and len(first["events"]) == 1
    document = first["events"][0]["document"]
    assert document == {"name": values["name"], "type": values["type"], "priceKrw": 1200,
                        "abvPercent": 5.555, "orderCount": 0, "tasteTags": None, "aromaTags": None}
    feature_path = f"/admin/products/{product_id}/features"
    features = {"tasteTags": [" Sweet ", "DRY"], "aromaTags": [], "sourceName": "Authored fixture"}
    admin.request("PUT", feature_path, 200, features)
    feature_state = state()
    assert feature_state["version"] == 2
    assert feature_state["events"][-1]["document"]["tasteTags"] == ["dry", "sweet"]
    assert feature_state["events"][-1]["document"]["aromaTags"] == []
    changed = dict(values, name="Changed outbox fixture", price=1500)
    assert admin.request("PUT", f"/admin/products/{product_id}", 200, changed)["data"] is True
    updated = admin.request("GET", f"/products/{product_id}", 200)["data"]
    before_failure = state()
    ci_search_outbox(environ, product_id, failing_trigger=True)
    try:
        admin.request("PUT", f"/admin/products/{product_id}", 500, dict(changed, price=9999), "INTERNAL_SERVER_ERROR")
        assert admin.request("GET", f"/products/{product_id}", 200)["data"] == updated
        assert state() == before_failure
        admin.request("PUT", feature_path, 500, {}, "INTERNAL_SERVER_ERROR")
        assert admin.request("GET", f"/products/{product_id}/features", 200)["data"]["tasteTags"] == ["dry", "sweet"]
        assert state() == before_failure
    finally:
        ci_search_outbox(environ, product_id, failing_trigger=False)
    admin.request("POST", "/orders", 201, {"orderType": "TAKEOUT", "details": [{"productId": product_id, "quantity": 2}]})
    after_order = state()
    assert after_order["version"] == 4 and after_order["events"][-1]["document"]["orderCount"] == 2
    admin.request("POST", "/orders", 400, {"orderType": "TAKEOUT", "details": [
        {"productId": product_id, "quantity": 1}, {"productId": 2147483647, "quantity": 1}]}, "BAD_REQUEST")
    assert state() == after_order
    admin.request("DELETE", f"/admin/products/{product_id}", 200)
    final = state()
    assert final["version"] == 5 and len(final["events"]) == 5
    assert final["events"][-1] == {"productId": product_id, "sourceVersion": 5, "deleted": True, "document": None}
    assert final["events"][:4] == after_order["events"], "Earlier event projections changed"
    admin.request("PUT", f"/admin/products/{product_id}", 404, changed, "NOT_FOUND")
    assert state() == final
    from search_index import batch
    wire = json.dumps({"schemaVersion": 1, "dataKind": "backend_product_events", "events": final["events"]})
    batch(wire.encode())
    # Authored disposable product projection only; never upload this input as a CI artifact.
    private = Path(".pubburi"); private.mkdir(exist_ok=True)
    (private / "search-outbox-smoke-events.json").write_text(wire, encoding="utf-8")


def main():
    mutating = os.environ.get("PUBBURI_SMOKE_MUTATING") == "1"
    report = {"status": "failed", "mode": "disposable-db" if mutating else "read-only", "completed": []}
    try:
        client = ApiClient(os.environ.get("PUBBURI_SERVER_PORT", "9987"))
        product = read_only_contract(client)
        report["completed"].append("read-only HTTP envelope, pagination, auth and validation")
        product_page_offset_contract(client)
        report["completed"].append("checked product page offsets and preserved normalization")
        documentation_contract(os.environ.get("PUBBURI_SERVER_PORT", "9987"))
        report["completed"].append("runtime OpenAPI feature paths/schemas and Swagger UI")
        default_recommendation_contract(client, os.environ.get("PUBBURI_SMOKE_EXPECT_RECOMMENDATION_SOURCE"))
        report["completed"].append("public default recommendation envelope, current products and fallback availability")
        if mutating:
            multipart_security_contract(os.environ)
            report["completed"].append("bounded multipart headers, safe client errors and unchanged media rejection")
            json_charset_contract(os.environ)
            report["completed"].append("JSON charset compatibility, field-name constraints and unchanged session state")
            mutating_contract(client, product, os.environ)
            report["completed"].append("session, authorization, order price snapshots and rollback")
            history_preservation_contract(os.environ)
            report["completed"].append("deleted references, anonymized owner and preserved order snapshots")
            session_authority_contract(os.environ)
            report["completed"].append("live roles, account incarnation, password revocation and profile freshness")
            product_feature_contract(os.environ)
            product_filter_contract(os.environ)
            report["completed"].append("mandatory current-MySQL price/ABV/reviewed-tag filters and unchanged paging")
            report["completed"].append("product attributes, unknown/empty semantics, source validation and deletion")
            private_preference_contract(os.environ)
            report["completed"].append("private explicit preferences, soft/hard separation, account isolation and lifecycle")
            favorite_contract(os.environ)
            report["completed"].append("account favorites, idempotent membership, current products and ownership lifecycle")
            search_outbox_contract(os.environ)
            report["completed"].append("transactional product search versions, immutable projections, rollback and retained tombstones")
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

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
import unicodedata
import urllib.request
import uuid
from decimal import Decimal, ROUND_HALF_UP


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
        data = None if payload is None else json.dumps(payload, ensure_ascii=True).encode("utf-8")
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
    verify_search_phrase_openapi(document)
    verify_content_recommendation_openapi(document)
    verify_similar_products_openapi(document)
    assert "/api/users/me/recommendations/feedback/{productId}" not in document["paths"]
    verify_product_filter_openapi(document)
    verify_product_suggestions_openapi(document)
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
    response = operation["responses"]["200"]
    assert "application/json" in response["content"]
    assert response["headers"]["X-Pubburi-Search-Hint"]["schema"]["type"] == "string"
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


def verify_search_phrase_openapi(document):
    operation = document["paths"]["/api/users/me/search-phrases"]["post"]
    schemas = document["components"]["schemas"]
    request = operation["requestBody"]["content"]["application/json"]["schema"]
    request = schemas[request["$ref"].split("/")[-1]]
    assert request["additionalProperties"] is False
    assert set(request["properties"]) == {"searchOptInFields", "limit"}
    assert not operation.get("parameters"), "The owner cannot be chosen with query parameters"
    response = operation["responses"]["200"]["content"]["application/json"]["schema"]
    envelope = schemas[response["$ref"].split("/")[-1]]
    data = schemas[envelope["properties"]["data"]["$ref"].split("/")[-1]]
    assert set(data["properties"]) == {"source", "result"}
    assert set(data["properties"]["source"]["enum"]) == {"PROVIDER", "UNAVAILABLE"}


def verify_content_recommendation_openapi(document):
    path = document["paths"]["/api/users/me/recommendations/content"]
    assert {method for method in path if method in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}} == {"post"}
    operation = path["post"]; schemas = document["components"]["schemas"]
    request = operation["requestBody"]["content"]["application/json"]["schema"]
    request = schemas[request["$ref"].split("/")[-1]]
    assert request["additionalProperties"] is False and set(request["properties"]) == {"limit", "includeActivity"}
    assert request["properties"]["limit"]["minimum"] == 1 and request["properties"]["limit"]["maximum"] == 12
    activity = request["properties"]["includeActivity"]
    assert activity["type"] == "boolean" and activity["default"] is False
    assert "includeActivity" not in request.get("required", [])
    parameters = operation.get("parameters", [])
    assert len(parameters) == 1
    (confirmation,) = parameters
    assert confirmation["in"] == "header" and confirmation["name"] == "X-Pubburi-Feedback-Owner"
    assert not confirmation.get("required"), "Owned content must preserve requests without feedback opt-in"
    response = operation["responses"]["200"]["content"]["application/json"]["schema"]
    envelope = schemas[response["$ref"].split("/")[-1]]
    data = schemas[envelope["properties"]["data"]["$ref"].split("/")[-1]]
    assert set(data["properties"]) == {"source", "catalogScope", "result", "products", "activityEvidence", "feedbackEvidence"}
    assert {"activityEvidence", "feedbackEvidence"}.isdisjoint(data.get("required", []))
    evidence = schemas[data["properties"]["activityEvidence"]["$ref"].split("/")[-1]]
    assert set(evidence["properties"]) == {"favoriteCategories", "highlyRatedCategories"}
    assert all(value["type"] == "array" and value["items"]["type"] == "string" for value in evidence["properties"].values())
    feedback = schemas[data["properties"]["feedbackEvidence"]["$ref"].split("/")[-1]]
    assert set(feedback["properties"]) == {"rankingMode", "excludedNotInterestedCount", "positiveReferences"}
    reference = schemas[feedback["properties"]["positiveReferences"]["items"]["$ref"].split("/")[-1]]
    assert set(reference["properties"]) == {"productId", "name"}
    assert set(data["properties"]["source"]["enum"]) == {"PROVIDER", "UNAVAILABLE"}
    assert data["properties"]["catalogScope"]["enum"] == ["REVIEWED_DEMO", "REVIEWED_FAMILY_BROWSE_DEMO", "REVIEWED_LOCAL_CATALOG"]


def verify_product_suggestions_openapi(document):
    path = document["paths"]["/api/products/suggestions"]
    assert {method for method in path if method in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}} == {"get"}
    operation = path["get"]
    (query,) = operation["parameters"]
    assert query["name"] == "q" and query["in"] == "query" and query["required"] is True
    assert query["schema"]["type"] == "string"
    schemas = document["components"]["schemas"]
    response = operation["responses"]["200"]["content"]["application/json"]["schema"]
    envelope = schemas[response["$ref"].split("/")[-1]]
    data = schemas[envelope["properties"]["data"]["$ref"].split("/")[-1]]
    assert set(data["properties"]) == {"items"}
    items = data["properties"]["items"]
    assert items["type"] == "array" and items["maxItems"] == 8
    product = schemas[items["items"]["$ref"].split("/")[-1]]
    assert {name: prop["type"] for name, prop in product["properties"].items()} == {
        "id": "integer", "name": "string", "type": "string", "price": "integer",
    }


def verify_similar_products_openapi(document):
    path = document["paths"]["/api/products/{id}/similar"]
    assert {key for key in path if key in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}} == {"get"}
    operation = path["get"]
    parameters = {(parameter["name"], parameter["in"]): parameter for parameter in operation["parameters"]}
    assert len(operation["parameters"]) == 2 and set(parameters) == {("id", "path"), ("includeExplanations", "query")}
    selector = parameters[("includeExplanations", "query")]
    assert selector.get("required", False) is False
    assert selector["schema"]["type"] == "boolean" and selector["schema"]["default"] is False
    assert "requestBody" not in operation
    schemas = document["components"]["schemas"]
    response = operation["responses"]["200"]["content"]["application/json"]["schema"]
    envelope = schemas[response["$ref"].split("/")[-1]]
    data = schemas[envelope["properties"]["data"]["$ref"].split("/")[-1]]
    assert set(data["properties"]) == {"items", "catalogScope", "explanations"}
    assert "catalogScope" not in data.get("required", [])
    assert "explanations" not in data.get("required", [])
    assert set(data["properties"]["catalogScope"]["enum"]) == {"REVIEWED_LOCAL_CATALOG"}
    assert data["properties"]["items"] == {"type": "array", "items": {"$ref": "#/components/schemas/ProductResponse"}}
    reasons = data["properties"]["explanations"]
    assert reasons["type"] == "array"
    explanation = schemas[reasons["items"]["$ref"].split("/")[-1]]
    assert set(explanation["properties"]) == {"productId", "explanation"}
    assert set(explanation["required"]) == {"productId", "explanation"}


def verify_order_recommendation_openapi(document):
    path = document["paths"]["/api/users/me/orders/{id}/recommendations"]
    assert set(path) == {"post"}
    operation = path["post"]
    parameters = {(value["name"], value["in"]): value for value in operation["parameters"]}
    assert set(parameters) == {("id", "path"), ("X-Pubburi-Account-Owner", "header")}
    assert parameters[("X-Pubburi-Account-Owner", "header")]["required"] is True
    schemas = document["components"]["schemas"]
    request = operation["requestBody"]["content"]["application/json"]["schema"]
    request = schemas[request["$ref"].split("/")[-1]]
    assert request["additionalProperties"] is False and set(request["properties"]) == {"limit"}
    assert "limit" not in request.get("required", [])
    assert request["properties"]["limit"]["minimum"] == 1 and request["properties"]["limit"]["maximum"] == 12
    response = operation["responses"]["200"]["content"]["application/json"]["schema"]
    envelope = schemas[response["$ref"].split("/")[-1]]
    data = schemas[envelope["properties"]["data"]["$ref"].split("/")[-1]]
    fields = {"source", "catalogScope", "orderId", "referenceCount", "references", "unavailableReason", "result", "products"}
    assert set(data["properties"]) == fields and set(data["required"]) == fields
    assert set(data["properties"]["catalogScope"]["enum"]) == {"REVIEWED_DEMO", "REVIEWED_LOCAL_CATALOG"}
    reference = schemas[data["properties"]["references"]["items"]["$ref"].split("/")[-1]]
    assert set(reference["properties"]) == {"detailId", "productId", "name", "quantity", "state"}
    assert set(reference["required"]) == set(reference["properties"])
    for field, kind in (("productId", "integer"), ("name", "string")):
        assert set(reference["properties"][field]["type"]) == {kind, "null"}
    assert reference["properties"]["quantity"]["maximum"] == 2147483647


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
    if total:
        first = client.request("GET", "/products?size=1", 200)["data"]["items"][0]
        similar = client.request("GET", f"/products/{first['id']}/similar", 200)["data"]
        assert set(similar) == {"items"} and len(similar["items"]) <= 4
        ids = {item["id"] for item in similar["items"]}
        assert len(ids) == len(similar["items"]) and first["id"] not in ids
        for item in similar["items"]:
            assert client.request("GET", f"/products/{item['id']}", 200)["data"] == item
    client.request("GET", "/products/0/similar", 404, error_code="NOT_FOUND")


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
    created = admin.request("POST", "/orders", 201, {
        "userId": buyer_id.upper(),
        "orderType": "TAKEOUT", "orderTable": "CI private note",
        "details": [{"productId": product["id"], "quantity": 2}],
    })["data"]
    assert created["userId"] == buyer_id, "Order detail must expose the DB-canonical owner, without rewriting its FK"
    order_path = f"/orders/{created['oId']}"
    assert buyer.request("GET", order_path, 200)["data"] == created
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


def self_account_contract(environ):
    """Current session owns both writes; only new disposable accounts are changed."""
    require_disposable_database(environ)
    port = environ.get("PUBBURI_SERVER_PORT", "9987")
    public = ApiClient(port)
    admin_id, owner_id, other_id = ("ci_admin_" + uuid.uuid4().hex, "ci_self_" + uuid.uuid4().hex, "ci_self_" + uuid.uuid4().hex)
    password, fresh = uuid.uuid4().hex, " literal self password "
    for account_id in (admin_id, owner_id, other_id):
        public.request("POST", "/users", 201, {"id": account_id, "name": "CI self fixture", "pass": password})
    promote_ci_fixture_admin(environ, admin_id)
    admin, owner, other, second = (ApiClient(port) for _ in range(4))
    for client, account_id in ((admin, admin_id), (owner, owner_id), (other, other_id), (second, owner_id)):
        client.request("POST", "/auth/login", 200, {"id": account_id, "pass": password})
    profile_path, password_path = "/users/me/profile", "/users/me/password"
    try:
        public.request("PUT", profile_path, 401, {"name": "unauthorized"}, "UNAUTHORIZED")
        public.request("PUT", password_path, 401, {"currentPassword": password, "newPassword": fresh}, "UNAUTHORIZED")
        admin.request("PUT", "/admin/users/" + owner_id, 200, {"stamps": 63})
        before = owner.request("GET", "/auth/me", 200)["data"]
        other_before = other.request("GET", "/auth/me", 200)["data"]
        assert owner.request("PUT", profile_path, 200, {"name": " literal self name "})["data"] is True
        expected = dict(before, name=" literal self name ")
        assert owner.request("GET", "/auth/me", 200)["data"] == expected
        assert second.request("GET", "/auth/me", 200)["data"] == expected
        for body in ({}, {"name": None}, {"name": 1}, {"name": True}, {"name": " "}, {"name": "a" * 101}, {"name": "valid", "role": "ADMIN"}, {"name": "valid", "id": other_id}):
            owner.request("PUT", profile_path, 400, body, "VALIDATION_FAILED")
        for body in ({}, {"currentPassword": password}, {"currentPassword": 1, "newPassword": fresh}, {"currentPassword": password, "newPassword": "abc"}, {"currentPassword": password, "newPassword": "a" * 101}, {"currentPassword": password, "newPassword": fresh, "authEpoch": "forged"}):
            owner.request("PUT", password_path, 400, body, "VALIDATION_FAILED")
        for raw in ("a" * 73, "가" * 25, "😀" * 19):
            owner.request("PUT", password_path, 400, {"currentPassword": password, "newPassword": raw}, "VALIDATION_FAILED")
        owner.request("PUT", profile_path + "?id=" + other_id, 400, {"name": "wrong owner"}, "BAD_REQUEST")
        owner.request("PUT", password_path, 403, {"currentPassword": "wrong", "newPassword": fresh}, "FORBIDDEN")
        assert owner.request("GET", "/auth/me", 200)["data"] == expected
        assert second.request("GET", "/auth/me", 200)["data"] == expected
        assert owner.request("PUT", password_path, 200, {"currentPassword": password, "newPassword": fresh})["data"] is True
        assert owner.request("GET", "/auth/me", 200)["data"] == expected
        second.request("GET", "/auth/me", 401, error_code="UNAUTHORIZED")
        public.request("POST", "/auth/login", 401, {"id": owner_id, "pass": password}, "UNAUTHORIZED")
        second.request("POST", "/auth/login", 200, {"id": owner_id, "pass": fresh})
        assert second.request("GET", "/auth/me", 200)["data"] == expected
        current = fresh
        for raw in ("a" * 72, "가" * 24, "😀" * 18):
            owner.request("PUT", password_path, 200, {"currentPassword": current, "newPassword": raw})
            assert owner.request("GET", "/auth/me", 200)["data"] == expected
            check = ApiClient(port)
            check.request("POST", "/auth/login", 200, {"id": owner_id, "pass": raw})
            current = raw
        assert other.request("GET", "/auth/me", 200)["data"] == other_before
        admin.request("DELETE", "/admin/users/" + owner_id, 200)
        public.request("POST", "/users", 201, {"id": owner_id, "name": "Rejoined self", "pass": password})
        owner.request("PUT", profile_path, 401, {"name": "stale"}, "UNAUTHORIZED")
        second.request("PUT", password_path, 401, {"currentPassword": fresh, "newPassword": password}, "UNAUTHORIZED")
    finally:
        for account_id in (owner_id, other_id):
            admin.request("DELETE", "/admin/users/" + account_id, 200)


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



def comment_rating_contract(environ):
    """Public review summary/order on this run's authored disposable products only."""
    require_disposable_database(environ)
    port = environ.get("PUBBURI_SERVER_PORT", "9987")
    public = ApiClient(port)
    user = "ci_admin_" + uuid.uuid4().hex
    password = uuid.uuid4().hex
    public.request("POST", "/users", 201, {"id": user, "name": "CI review summary", "pass": password})
    promote_ci_fixture_admin(environ, user)
    owner = ApiClient(port)
    owner.request("POST", "/auth/login", 200, {"id": user, "pass": password})
    products = []
    page_fields = {"items", "page", "size", "total", "totalPages", "hasNext"}
    try:
        for suffix in ("current", "legacy"):
            created = owner.request("POST", "/admin/products", 201, {
                "name": "CI review fixture " + suffix, "type": "맥주", "price": 1000,
                "img": "ci-review.png", "abv": 5, "orderCount": 0,
            })["data"]
            products.append(created["id"])
        product, legacy = products
        path = f"/products/{product}/comments"
        def summary():
            return public.request("GET", path + "?includeSummary=true", 200)["data"]
        empty = summary()
        assert set(empty) == page_fields | {"ratingSummary"}
        assert empty["ratingSummary"] == {"count": 0, "average": None}
        ids = []
        for rating in (4.25, 1.25, 4.25):
            ids.append(owner.request("POST", "/comments", 201, {"productId": product,
                "rating": rating, "comment": "Authored review summary", "tasteTags": [" SWEET ", "과일,향\n산미"] if not ids else []})["data"]["id"])
        original = public.request("GET", path, 200)["data"]
        assert set(original) == page_fields and [x["id"] for x in original["items"]] == ids[::-1]
        assert all(len(item) == 5 for item in original["items"])
        tagged = public.request("GET", path + "?includeTasteTags=true&includeSummary=true&sort=rating_desc", 200)["data"]
        expected_tags = ["sweet", "과일,향\n산미"]
        assert {item["id"]: item["tasteTags"] for item in tagged["items"]} == {ids[0]: expected_tags, ids[1]: [], ids[2]: []}
        assert all(len(item) == 5 for item in public.request("GET", path + "?includeTasteTags=false", 200)["data"]["items"])
        before = public.request("GET", path + "?includeTasteTags=true", 200)["data"]
        for invalid in (None, "tag", [1], [True], ["SWEET", "sweet"], [" "], ["x" * 41], ["nul\x00tag"], ["x"] * 21):
            owner.request("PUT", f"/comments/{ids[0]}", 400, {"rating": 1.25, "comment": "must not change", "tasteTags": invalid}, "VALIDATION_FAILED")
        assert public.request("GET", path + "?includeTasteTags=true", 200)["data"] == before
        for tag in ("\ud800", "\udfff"):
            malformed = {"productId": product, "rating": 1.25, "comment": "must not change", "tasteTags": [tag]}
            wire = json.dumps(malformed, ensure_ascii=True).encode("ascii")
            assert json.dumps(tag, ensure_ascii=True).encode("ascii") in wire
            owner.request("POST", "/comments", 400, malformed, "VALIDATION_FAILED")
            owner.request("PUT", f"/comments/{ids[0]}", 400, malformed, "VALIDATION_FAILED")
            assert public.request("GET", path + "?includeTasteTags=true", 200)["data"] == before
        paired = owner.request("POST", "/comments", 201, {"productId": product, "rating": 3.75,
            "comment": "Valid Unicode pair control", "tasteTags": ["🌾"]})["data"]["id"]
        tagged_pair = next(item for item in public.request("GET", path + "?includeTasteTags=true", 200)["data"]["items"] if item["id"] == paired)
        assert tagged_pair["tasteTags"] == ["🌾"]
        owner.request("DELETE", f"/comments/{paired}", 200)
        assert public.request("GET", path + "?includeTasteTags=true", 200)["data"] == before
        assert next(item for item in before["items"] if item["id"] == ids[0])["tasteTags"] == expected_tags
        # Failure after updating the review and deleting its old tags must roll back all three fields.
        require_disposable_database(environ)
        if not environ.get("PUBBURI_DB_ROOT_PASSWORD"):
            raise RuntimeError("Review fault DDL requires the isolated root credential")
        trigger = "ci_review_opinion_" + uuid.uuid4().hex
        assert type(ids[0]) is int and ids[0] > 0 and re.fullmatch(r"ci_review_opinion_[a-f0-9]{32}", trigger)
        def fault_sql(statement):
            require_disposable_database(environ)
            return subprocess.run(["docker", "exec", "-e", "MYSQL_PWD", environ["PUBBURI_SMOKE_CONTAINER_ID"],
                "mysql", "-uroot", "--batch", "--skip-column-names", environ["PUBBURI_DB_NAME"], "--execute", statement],
                env=dict(environ, MYSQL_PWD=environ["PUBBURI_DB_ROOT_PASSWORD"]), capture_output=True, text=True, check=True).stdout
        fault_body = f"SET NEW.tag = IF(NEW.comment_id={ids[0]}, NULL, NEW.tag)"
        fault_sql("CREATE TRIGGER " + trigger + " BEFORE INSERT ON t_comment_taste_tag FOR EACH ROW " + fault_body)
        try:
            owner.request("PUT", f"/comments/{ids[0]}", 409, {"rating": 1.25, "comment": "must roll back", "tasteTags": ["after"]}, "CONFLICT")
            assert public.request("GET", path + "?includeTasteTags=true", 200)["data"] == before
        finally:
            observed = json.loads(fault_sql("SELECT JSON_OBJECT('table',EVENT_OBJECT_TABLE,'timing',ACTION_TIMING,'event',EVENT_MANIPULATION,'body',ACTION_STATEMENT) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='" + trigger + "'").strip())
            normalized = lambda text: re.sub(r"\s+|`", "", text).rstrip(";").lower()
            if observed != dict(observed, table="t_comment_taste_tag", timing="BEFORE", event="INSERT") or normalized(observed["body"]) != normalized(fault_body):
                raise RuntimeError("Refusing to remove an unowned review fixture trigger")
            fault_sql("DROP TRIGGER " + trigger)

        middle = public.request("GET", path + "?includeSummary=true&sort=rating_desc&page=2&size=1", 200)["data"]
        assert [x["id"] for x in middle["items"]] == [ids[0]]
        assert middle["total"] == 3 and middle["ratingSummary"] == {"count": 3, "average": 3.25}
        low = public.request("GET", path + "?includeSummary=false&sort=rating_asc&size=1", 200)["data"]
        assert set(low) == page_fields and low["items"][0]["id"] == ids[1]
        for query in ("?sort=nope", "?sort=latest&sort=latest", "?includeSummary=TRUE", "?includeSummary=true&includeSummary=true", "?includeTasteTags=TRUE", "?includeTasteTags=true&includeTasteTags=false"):
            public.request("GET", path + query, 400, error_code="BAD_REQUEST")
        owner.request("PUT", f"/comments/{ids[0]}", 200, {"rating": 3.75, "comment": "Edited summary"})
        assert summary()["ratingSummary"] == {"count": 3, "average": 3.08}
        assert next(item for item in public.request("GET", path + "?includeTasteTags=true", 200)["data"]["items"] if item["id"] == ids[0])["tasteTags"] == expected_tags
        owner.request("PUT", f"/comments/{ids[0]}", 200, {"rating": 3.75, "comment": "clear tags", "tasteTags": []})
        assert next(item for item in public.request("GET", path + "?includeTasteTags=true", 200)["data"]["items"] if item["id"] == ids[0])["tasteTags"] == []
        owner.request("DELETE", f"/comments/{ids[1]}", 200)
        assert summary()["ratingSummary"] == {"count": 2, "average": 4}
        for id_ in (ids[0], ids[2]):
            owner.request("DELETE", f"/comments/{id_}", 200)
        assert summary()["ratingSummary"] == {"count": 0, "average": None}
        for rating in (.58, .59):
            owner.request("POST", "/comments", 201, {"productId": product, "rating": rating, "comment": "Stored FLOAT boundary"})
        boundary = summary()
        assert [x["rating"] for x in boundary["items"]] == [.59, .58]
        assert boundary["ratingSummary"] == {"count": 2, "average": .58}
        # An owned legacy fixture must not loosen POST validation or rewrite imported rows.
        require_disposable_database(environ)
        assert re.fullmatch(r"ci_admin_[a-f0-9]{32}", user) and type(legacy) is int and legacy > 0
        sql = ("INSERT INTO t_comment(user_id,product_id,rating,comment) SELECT id," + str(legacy)
               + ",5.555,'CI legacy rating' FROM t_user WHERE id=CONVERT(0x" + user.encode().hex()
               + " USING utf8mb4); SELECT AVG(rating) FROM t_comment WHERE product_id=" + str(legacy))
        output = subprocess.run(["docker", "exec", "-e", "MYSQL_PWD", environ["PUBBURI_SMOKE_CONTAINER_ID"],
            "mysql", "-u" + environ["PUBBURI_DB_USER"], "--batch", "--skip-column-names", environ["PUBBURI_DB_NAME"],
            "--execute", sql], env=dict(environ, MYSQL_PWD=environ["PUBBURI_DB_PASSWORD"]),
            capture_output=True, text=True, check=True)
        expected = Decimal(output.stdout.strip()).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
        legacy_page = public.request("GET", f"/products/{legacy}/comments?includeSummary=true", 200)["data"]
        assert legacy_page["items"][0]["rating"] == 5.555
        assert Decimal(str(legacy_page["ratingSummary"]["average"])) == expected
    finally:
        for product in products:
            owner.request("DELETE", f"/admin/products/{product}", 200)


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
    product_suggestion_contract(public, admin, prefix)


def product_suggestion_contract(public, admin, prefix):
    """Use the same verified disposable DB and fixture administrator as product filtering."""
    path = "/products/suggestions"
    created = []

    def put_name(name, kind="탁주", price=1000):
        body = {"name": name, "type": kind, "price": price, "img": "authored.png", "abv": 5, "orderCount": 0}
        row = admin.request("POST", "/admin/products", 201, body)["data"]
        created.append(row["id"])
        return row

    def items(term):
        data = public.request("GET", path + "?" + urllib.parse.urlencode({"q": term}), 200)["data"]
        assert set(data) == {"items"}
        assert all(set(row) == {"id", "name", "type", "price"} for row in data["items"])
        return data["items"]

    def projection(row):
        return {key: row[key] for key in ("id", "name", "type", "price")}

    try:
        literal = prefix + " %_!\\한글"
        first = put_name(literal)
        duplicate = put_name(literal, kind="맥주", price=2200)
        put_name(prefix + " xy!\\한글")
        put_name("Infix " + literal)
        assert items("\u00a0" + unicodedata.normalize("NFD", literal) + " ") == [
            projection(first), projection(duplicate),
        ]
        assert items("") == [] and items("한") == []
        over_limit = "?" + urllib.parse.urlencode({"q": "가" * 101})
        for query in ("", "?q=aa&q=bb", "?q=aa&limit=1", over_limit, "?q=aa%00"):
            public.request("GET", path + query, 400, error_code="BAD_REQUEST")
        ordered = [put_name(prefix + f" Cap {index:02}") for index in range(9)]
        assert items(prefix + " Cap") == [projection(row) for row in ordered[:8]]
        changed = {key: first[key] for key in ("name", "type", "price", "img", "abv", "orderCount")}
        changed.update(name=prefix + " renamed", price=3200)
        admin.request("PUT", "/admin/products/" + str(first["id"]), 200, changed)
        assert items(literal) == [projection(duplicate)]
        assert items(prefix + " renamed") == [projection(dict(first, **changed))]
        admin.request("DELETE", "/admin/products/" + str(duplicate["id"]), 200)
        created.remove(duplicate["id"])
        assert items(literal) == []
    finally:
        for product_id in created:
            admin.request("DELETE", "/admin/products/" + str(product_id), 200)


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
    # The caller reads only this already-stored snapshot; it never accepts another owner's data.
    phrase_path = "/users/me/search-phrases"
    public.request("POST", phrase_path, 401, {}, "UNAUTHORIZED")
    phrases = owner.request("POST", phrase_path, 200, {"searchOptInFields": ["tasteTags"], "limit": 5})["data"]
    assert set(phrases) == {"source", "result"}
    assert phrases["source"] in {"PROVIDER", "UNAVAILABLE"}
    if phrases["source"] == "UNAVAILABLE":
        assert phrases["result"] is None
    else:
        assert phrases["result"]["algorithm"] == "r06-explicit-search-phrases-v1"
        assert phrases["result"]["product_count"] is None
    for invalid in ({"userId": user_id}, {"preferences": saved}, {"limit": True}, {"limit": "1"},
                    {"limit": 1.0}, {"limit": 0}, {"searchOptInFields": None},
                    {"searchOptInFields": [None]}, {"searchOptInFields": ["tasteTags", "tasteTags"]}):
        owner.request("POST", phrase_path, 400, invalid, "VALIDATION_FAILED")
    owner.request("POST", phrase_path + "?userId=other", 400, {}, "BAD_REQUEST")
    assert owner.request("GET", path, 200)["data"] == saved
    assert owner.request("PUT", path, 200, declared)["data"] == saved
    content_path = "/users/me/recommendations/content"
    public.request("POST", content_path, 401, {}, "UNAUTHORIZED")
    assert owner.request("POST", content_path, 200, {})["data"] == {
        "source": "UNAVAILABLE", "catalogScope": "REVIEWED_DEMO", "result": None, "products": []}
    for invalid in ({"profile": {}}, {"preferences": saved}, {"catalog": []}, {"limit": True}, {"limit": "1"}, {"limit": 1.0}, {"limit": 0}, {"limit": 13}):
        owner.request("POST", content_path, 400, invalid, "VALIDATION_FAILED")
    owner.request("POST", content_path + "?userId=other", 400, {}, "BAD_REQUEST")
    assert owner.request("GET", path, 200)["data"] == saved
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
    if failing_trigger is not None and type(failing_trigger) is not bool:
        raise ValueError("Invalid fixture trigger operation")
    trigger = "ci_search_outbox_fail_" + str(product_id)
    body = f"SET NEW.source_version = IF(NEW.product_id={product_id}, 0, NEW.source_version)"
    if failing_trigger is not None and not environ.get("PUBBURI_DB_ROOT_PASSWORD"):
        raise RuntimeError("Disposable failure injection requires a separate root credential")

    def execute(statement, *, ddl=False):
        # Application/query credentials stay unchanged. Only these fixed fixture DDLs use root;
        # binlog-enabled MySQL can require SUPER to create a trigger even with database ALL.
        return subprocess.run([
            "docker", "exec", "-e", "MYSQL_PWD", environ["PUBBURI_SMOKE_CONTAINER_ID"],
            "mysql", "-u" + ("root" if ddl else environ["PUBBURI_DB_USER"]), "--default-character-set=utf8mb4",
            "--batch", "--raw", "--skip-column-names", environ["PUBBURI_DB_NAME"], "--execute", statement,
        ], capture_output=True, text=True, check=True, env=dict(environ, MYSQL_PWD=(
            environ["PUBBURI_DB_ROOT_PASSWORD"] if ddl else environ.get("PUBBURI_DB_PASSWORD", ""))))

    if failing_trigger is True:
        execute(f"CREATE TRIGGER {trigger} BEFORE INSERT ON t_product_search_outbox FOR EACH ROW " + body, ddl=True)
        return None
    elif failing_trigger is False:
        inspected = execute("SELECT JSON_OBJECT('table', EVENT_OBJECT_TABLE, 'timing', ACTION_TIMING, "
            "'event', EVENT_MANIPULATION, 'body', ACTION_STATEMENT) FROM information_schema.TRIGGERS "
            f"WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='{trigger}'").stdout.strip()
        owned = json.loads(inspected) if inspected else {}
        def normalized(value): return re.sub(r"\s+|`", "", value).rstrip(";").lower() if isinstance(value, str) else None
        if (owned.get("table") != "t_product_search_outbox" or owned.get("timing") != "BEFORE"
                or owned.get("event") != "INSERT" or normalized(owned.get("body")) != normalized(body)):
            raise RuntimeError("Refusing to remove an unowned fixture trigger")
        execute(f"DROP TRIGGER {trigger}", ddl=True)
        return None
    else:
        statement = ("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION READ ONLY; "
                     f"SELECT source_version FROM t_product_search_version WHERE product_id={product_id}; "
                     f"SELECT payload FROM t_product_search_outbox WHERE product_id={product_id} ORDER BY source_version; COMMIT;")
    result = execute(statement)
    rows = result.stdout.splitlines()
    return {"version": int(rows[0]) if rows else None, "events": [json.loads(row) for row in rows[1:]]}


def search_outbox_contract(environ):
    require_disposable_database(environ)
    if not environ.get("PUBBURI_DB_ROOT_PASSWORD"):
        raise RuntimeError("Disposable failure injection requires a separate root credential")
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
        feedback_path = "/users/me/recommendations/feedback/1"
        for method in ("GET", "PUT", "DELETE"):
            client.request(method, feedback_path, 404, {"feedback": "MATCHED"} if method == "PUT" else None, "NOT_FOUND")
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
            self_account_contract(os.environ)
            report["completed"].append("strict own profile/password, current-password rejection and session/epoch rotation")
            report["completed"].append("live roles, account incarnation, password revocation and profile freshness")
            product_feature_contract(os.environ)
            product_filter_contract(os.environ)
            report["completed"].append("mandatory current-MySQL price/ABV/reviewed-tag filters and unchanged paging")
            report["completed"].append("product attributes, unknown/empty semantics, source validation and deletion")
            private_preference_contract(os.environ)
            report["completed"].append("private explicit preferences, soft/hard separation, account isolation and lifecycle")
            favorite_contract(os.environ)
            report["completed"].append("account favorites, idempotent membership, current products and ownership lifecycle")
            comment_rating_contract(os.environ)
            report["completed"].append("public whole-review summary, stable rating order, legacy FLOAT precision and unchanged default page")
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

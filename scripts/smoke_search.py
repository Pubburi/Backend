#!/usr/bin/env python3
"""Own a disposable local engine and verify authored replay contracts, never business data."""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

from search_index import Engine, Http, SearchError, document

IMAGE = "pubburi-search:3.9.0-nori-v1"


def docker(*args, check=True):
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=check)


def authored(product_id=1, version=1, deleted=False):
    return {"productId": product_id, "sourceVersion": version, "deleted": deleted,
            "document": None if deleted else {"name": "검증용 가상 상품", "type": "탁주", "priceKrw": 1000,
                "abvPercent": 5.555, "orderCount": 0, "tasteTags": None, "aromaTags": []}}


def wait_ready(http):
    end = time.monotonic() + 120
    while time.monotonic() < end:
        try:
            status, value = http.request("GET", "/_cluster/health?wait_for_status=yellow&timeout=1s")
            if status == 200 and value.get("status") in ("green", "yellow") and value.get("timed_out") is False:
                return
        except SearchError:
            pass
        time.sleep(1)
    raise RuntimeError("owned_engine_not_ready")


def visible(http, alias):
    status, result = http.request("POST", f"/{alias}/_search", {"size": 10, "_source": False, "sort": [{"product_id": "asc"}], "query": {"match_all": {}}})
    assert status == 200
    return [int(row["_id"]) for row in result["hits"]["hits"]]


def exercise(http):
    engine = Engine(http, "pubburi-products-v1-validation")
    engine.ready(create=True); engine.ready(create=True)
    first, update = authored(), authored(version=2)
    update["document"]["priceKrw"] = 2000
    assert engine.apply(document(first)) == "applied"
    assert engine.apply(document(first)) == "duplicate"
    status, stored = http.request("GET", f"/{engine.index}/_doc/1")
    assert status == 200 and stored["_source"]["taste_tags"] is None and stored["_source"]["aroma_tags"] == []
    assert engine.apply(document(update)) == "applied"
    assert engine.apply(document(first)) == "stale_ignored"
    conflicting = authored(version=2); conflicting["document"]["priceKrw"] = 9999
    try:
        engine.apply(document(conflicting))
        raise AssertionError("same_version_conflict_was_not_rejected")
    except SearchError as error:
        assert str(error) == "same_version_content_conflict"
    assert http.request("GET", f"/{engine.index}/_doc/1")[1]["_source"]["price_krw"] == 2000
    assert engine.apply(document(authored(2))) == "applied"
    tombstone = document(authored(version=3, deleted=True))
    assert engine.apply(tombstone) == "applied"
    assert engine.apply(document(update)) == "stale_ignored"
    assert visible(http, engine.alias) == [2]
    # Alias filters are not authorization and do not hide a tombstone from GET-by-ID.
    status, by_alias = http.request("GET", f"/{engine.alias}/_doc/1")
    assert status == 200 and by_alias["_source"] == tombstone
    for mutation in ("extra_field", "numeric_string"):
        invalid = document(authored(3))
        if mutation == "extra_field": invalid["unexpected"] = True
        else: invalid["price_krw"] = "1000"
        assert http.request("PUT", f"/{engine.alias}/_doc/3?require_alias=true", invalid)[0] == 400
    assert http.request("GET", f"/{engine.index}/_doc/3")[0] == 404
    alternatives = [authored(2, 2), authored(2, 2)]
    alternatives[0]["document"]["priceKrw"] = 3000
    alternatives[1]["document"]["priceKrw"] = 4000
    def concurrent_apply(row):
        try: return engine.apply(document(row))
        except SearchError as error: return str(error)
    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(concurrent_apply, alternatives))
    assert sorted(outcomes) == ["applied", "same_version_content_conflict"]
    assert http.request("GET", f"/{engine.index}/_doc/2")[1]["_version"] == 2
    analysis = http.request("POST", f"/{engine.index}/_analyze", {"analyzer": "pubburi_ko", "text": "한국어 주류"})
    assert analysis[0] == 200 and analysis[1]["tokens"]
    return engine, [first, update, authored(2), authored(version=3, deleted=True)]


def main():
    run = os.environ.get("PUBBURI_SEARCH_VALIDATION_RUN", uuid.uuid4().hex)
    if not re.fullmatch(r"[a-z0-9-]{8,64}", run): raise ValueError("Invalid validation run")
    label, name = run, "pubburi-search-smoke-" + run
    private = Path(".pubburi") / (name + "-" + uuid.uuid4().hex[:8])
    private.mkdir(parents=True, exist_ok=False)
    state = {"run": run, "label": label, "container_id": None, "volume": None, "port": None, "phase": "reserving"}
    report = {"status": "failed", "data_kind": "authored_fixture", "version": "3.9.0", "completed": [],
              "client_sha256": hashlib.sha256(Path("scripts/search_index.py").read_bytes()).hexdigest()}
    target = Path("target/search-smoke-report.json"); target.parent.mkdir(parents=True, exist_ok=True)
    def save(): (private / "runtime.json").write_text(json.dumps(state, indent=2) + "\n")
    def owned_container():
        value = json.loads(docker("inspect", state["container_id"]).stdout)[0]
        assert value["Config"]["Labels"]["pubburi.validation"] == label
        return value
    try:
        if docker("inspect", name, check=False).returncode == 0 or docker("volume", "inspect", name, check=False).returncode == 0:
            raise RuntimeError("validation_resources_already_exist")
        state["image_id"] = docker("image", "inspect", IMAGE, "--format", "{{.Id}}").stdout.strip()
        state["volume"] = docker("volume", "create", "--label", "pubburi.validation=" + label, name).stdout.strip(); save()
        state["container_id"] = docker("create", "--name", name, "--label", "pubburi.validation=" + label,
            "--memory", "1536m", "--cpus", "1.5", "--ulimit", "memlock=-1:-1", "--ulimit", "nofile=65536:65536",
            "-p", "127.0.0.1::9200", "-v", name + ":/usr/share/opensearch/data", "-e", "discovery.type=single-node",
            "-e", "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m", "-e", "DISABLE_INSTALL_DEMO_CONFIG=true",
            "-e", "DISABLE_SECURITY_PLUGIN=true", IMAGE).stdout.strip(); save()
        docker("start", state["container_id"])
        raw = owned_container(); state.update(port=int(raw["NetworkSettings"]["Ports"]["9200/tcp"][0]["HostPort"]),
                                             container_pid=raw["State"]["Pid"], phase="running"); save()
        endpoint = "http://127.0.0.1:" + str(state["port"]); http = Http(endpoint)
        wait_ready(http)
        engine, events = exercise(http)
        report["completed"].append("pinned engine/nori, strict owned schema, IDs, replay, stale/conflicting versions, tombstones, unknown and controlled concurrent conflict")
        docker("restart", state["container_id"])
        # Dynamic host ports can change when Docker restarts a container.
        raw = owned_container(); state.update(port=int(raw["NetworkSettings"]["Ports"]["9200/tcp"][0]["HostPort"]), container_pid=raw["State"]["Pid"]); save()
        endpoint = "http://127.0.0.1:" + str(state["port"]); http = Http(endpoint); wait_ready(http)
        engine = Engine(http, engine.index); engine.ready()
        assert engine.apply(document(events[1])) == "stale_ignored" and visible(http, engine.alias) == [2]
        report["completed"].append("persistent restart keeps tombstone version and prevents stale resurrection")
        source = private / "events.json"
        source.write_text(json.dumps({"schemaVersion": 1, "dataKind": "authored_fixture", "events": events}))
        base = [sys.executable, "scripts/search_index.py"]
        rebuilt = "pubburi-products-v1-rebuilt"
        for command, output in (("init", "init"), ("replay", "replay"), ("replay", "duplicate")):
            args = [*base, command, "--endpoint", endpoint, "--index", rebuilt, "--data-kind", "authored_fixture", "--output-dir", str(private / output)]
            if command == "replay": args.extend(["--input", str(source)])
            result = subprocess.run(args, capture_output=True, text=True)
            assert result.returncode == 0
            assert json.loads((private / output / "receipt.json").read_text())["completed"] is True
        assert visible(http, rebuilt + "-visible") == [2]
        duplicate = json.loads((private / "duplicate/receipt.json").read_text())
        assert duplicate["applied"] == 0 and duplicate["duplicate"] == 2 and duplicate["stale_ignored"] == 2
        report["completed"].append("fresh index CLI history replay, duplicate replay and complete receipts")
        owned_container(); docker("stop", "--time", "20", state["container_id"])
        args = [*base, "replay", "--endpoint", endpoint, "--index", rebuilt, "--data-kind", "authored_fixture", "--input", str(source), "--output-dir", str(private / "unavailable")]
        assert subprocess.run(args, capture_output=True, text=True).returncode == 2
        assert json.loads((private / "unavailable/receipt.json").read_text())["completed"] is False
        report["completed"].append("stopped engine fails with incomplete receipt")
        report["status"] = "passed"
    except Exception as error:
        report["error_code"] = str(error) if isinstance(error, SearchError) else type(error).__name__
    finally:
        try:
            if state["container_id"]:
                owned_container()
                try:
                    logs = docker("logs", state["container_id"])
                    (private / "engine.log").write_text(logs.stdout + logs.stderr)
                except Exception as error:
                    report["status"] = "failed"
                    report["diagnostic_error"] = type(error).__name__
                # Diagnostic IO must not bypass cleanup of the already verified owner.
                docker("rm", "-f", state["container_id"])
            if state["volume"]:
                volume = json.loads(docker("volume", "inspect", state["volume"]).stdout)[0]
                assert volume["Labels"]["pubburi.validation"] == label
                docker("volume", "rm", state["volume"])
            state["phase"] = "cleaned"
        except Exception as error:
            state["phase"] = "cleanup_failed"; report["status"] = "failed"; report["cleanup_error"] = type(error).__name__
        save(); target.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__": raise SystemExit(main())

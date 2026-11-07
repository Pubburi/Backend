"""Actual public search on already-owned databases/engine; never provisions or resets resources."""
from http.cookiejar import CookieJar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from jpa_smoke import child_environment, cleanup_on_termination, defer_termination, verify_jar
from search_index import Engine, document
from search_relay import Relay, Store, connect_database, database_settings


TABLE_KEYS = {"t_user": "id", "t_product": "id", "t_product_feature": "product_id",
    "t_product_feature_tag": "product_id,kind,tag", "t_market": "id", "t_order": "o_id",
    "t_order_detail": "d_id", "t_stamp": "id", "t_comment": "id",
    "t_user_favorite": "user_id,product_id", "t_user_preference": "user_id",
    "t_user_preference_value": "user_id,kind,value_text"}


def verify_fixture_login_transition(before,after,identifier,password):
    allowed=copy.deepcopy(before)
    old=next(row for row in allowed["t_user"]if row["id"]==identifier)
    current=next(row for row in after["t_user"]if row["id"]==identifier)
    upgraded=old["password_hash"]!=current["password_hash"]
    if upgraded:
        assert identifier.startswith(("ci_jpa_","ci_h0201_","ci_admin_"))
        assert old["password_hash"]==hashlib.sha256(password.encode()).hexdigest()
        assert current["password_hash"].startswith("$2")
        old["password_hash"]=current["password_hash"]
    assert after==allowed,"Login changed more than the authored legacy credential hash"
    return upgraded


def verify(http, endpoint, private, environ, index, dialect="postgresql", baseline_jar=None):
    """Compare the entire public JSON with a database-only process using the same owned data."""
    from postgresql_schema import ROOT
    if dialect not in {"postgresql","mysql"} or not re.fullmatch(r"pubburi-products-v1(?:-[a-z0-9]{1,32})?",index):
        raise ValueError("public_search_explicit_database_and_owned_index_required")
    try: private.resolve().relative_to((ROOT/".pubburi").resolve())
    except ValueError: raise ValueError("public_search_private_output_required") from None
    if not private.is_dir() or any(private.glob("public-search-"+dialect+"*")):
        raise ValueError("public_search_new_private_output_required")
    deadline=time.monotonic()+160
    if dialect == "postgresql":
        from postgresql_relay_smoke import fixture, validate_business_events
        store, owner, _ = fixture(environ)
        jar = Path(environ.get("PUBBURI_PG_RELAY_JPA_JAR", ROOT / "target/pubburi-server-0.0.1-SNAPSHOT.jar"))
        verify_jar(jar); base_env = child_environment(environ)
        payload_sql = "payload::text"
    elif dialect == "mysql":
        from api_smoke import require_disposable_database
        require_disposable_database(environ)
        settings = database_settings(environ)
        store = owner = Store(lambda: connect_database(settings))
        jar = ROOT / "tools/mysql-baseline/target/pubburi-mysql-baseline-0.0.1-SNAPSHOT.jar"
        base_env = {key: value for key, value in environ.items() if key in {"PATH", "JAVA_HOME", "HOME", "TMPDIR", "LANG", "PUBBURI_DB_URL", "PUBBURI_DB_USER", "PUBBURI_DB_PASSWORD"}}
        payload_sql = "CAST(payload AS CHAR)"
        if not environ.get("PUBBURI_JPA_ADMIN_ID") and not environ.get("PUBBURI_JPA_ADMIN_PASSWORD"):
            # Only a fresh actor in the already proven disposable schema; retain all prior users.
            actor="ci_admin_"+uuid.uuid4().hex;password=uuid.uuid4().hex
            with owner.transaction() as cursor:
                cursor.execute("INSERT INTO t_user(id,name,password_hash,role,auth_epoch,stamps) VALUES(%s,%s,%s,%s,%s,0)",
                    (actor,"Authored public search",hashlib.sha256(password.encode()).hexdigest(),"ADMIN",str(uuid.uuid4())))
            environ=dict(environ,PUBBURI_JPA_ADMIN_ID=actor,PUBBURI_JPA_ADMIN_PASSWORD=password)
    else:
        raise ValueError("public_search_explicit_database_required")
    source = store.source_id()
    engine = Engine(http, index, "backend_product_events", source, refresh="false")

    def rows(statement, params=()):
        with owner.transaction() as cursor:
            cursor.execute(statement, params); return cursor.fetchall()
    def business(): return {table: rows("SELECT * FROM " + table + " ORDER BY " + keys) for table, keys in TABLE_KEYS.items()}
    before = business()
    initial_snapshot=private/("public-search-"+dialect+"-business-before.json")
    initial_snapshot.write_text(json.dumps(before,ensure_ascii=False,default=str));initial_snapshot.chmod(0o600)
    old_events = rows("SELECT event_id,product_id,source_version,deleted," + payload_sql + " AS payload,created_at FROM t_product_search_outbox ORDER BY event_id")
    old_versions = rows("SELECT product_id,source_version FROM t_product_search_version ORDER BY product_id")
    old_acks = rows("SELECT * FROM t_product_search_delivery WHERE status='DELIVERED' ORDER BY event_id")
    if dialect == "postgresql": validate_business_events(old_events)
    else:
        for event in old_events: document(json.loads(event["payload"]))
    engine.ready(create=True)
    relay = Relay(store, engine, source)
    def drain():
        for _ in range(1500):
            assert time.monotonic()<deadline,"Owned public-search overall deadline"
            result = relay.once()
            if result["status"] == "idle":
                assert http.request("POST", "/" + index + "/_refresh")[0] == 200
                return
            assert result["status"] == "delivered", "Owned public-search delivery failed"
        raise AssertionError("Owned public-search delivery limit")
    drain()
    process_rows = []; handles = []; processes = []; products = []; views = []; fixture_hash_upgrade=False
    record = private / ("public-search-" + dialect + "-processes.json")
    progress = private / ("public-search-" + dialect + "-observations.json")
    java = str(Path(base_env["JAVA_HOME"]) / "bin/java") if base_env.get("JAVA_HOME") else "java"
    baseline_jar = Path(baseline_jar) if baseline_jar else jar
    def save():
        record.write_text(json.dumps({"processes": process_rows})); record.chmod(0o600)
    def available_port():
        with socket.socket() as listener: listener.bind(("127.0.0.1", 0)); port = listener.getsockname()[1]
        assert port not in (5173,9987,8000,4173); return port
    def start(name, enabled, origin=endpoint, artifact=jar):
        port = available_port(); child = dict(base_env, PUBBURI_SEARCH_ENABLED=str(enabled).lower(), PUBBURI_SEARCH_ENDPOINT=origin, PUBBURI_SEARCH_INDEX=index)
        child.update({"PUBBURI_JPA_PORT" if dialect == "postgresql" else "PUBBURI_SERVER_PORT": str(port)})
        if dialect == "postgresql": child["PUBBURI_JPA_REHEARSAL"] = "1"
        log = private / ("public-search-" + dialect + "-" + name + ".log"); output = log.open("xb"); handles.append(output)
        with defer_termination():
            process = subprocess.Popen([java, "-Duser.timezone=UTC", "-Dserver.address=127.0.0.1", "-jar", str(artifact)], env=child, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
            processes.append(process); process_rows.append({"pid":process.pid,"port":port,"stopped":False}); save()
        base = "http://127.0.0.1:" + str(port)
        for _ in range(250):
            assert time.monotonic()<deadline,"Owned public-search overall deadline"
            assert process.poll() is None, "Owned public-search API exited"
            if re.search(r"Tomcat started on port " + str(port), log.read_text(errors="replace")):
                try:
                    request(base,"GET","/api/products?size=1"); return base
                except (OSError,AssertionError): pass
            time.sleep(.1)
        raise AssertionError("Owned public-search startup deadline")
    def request(base, method, path, body=None, cookies=None, expected=200):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies if cookies is not None else CookieJar()))
        req = urllib.request.Request(base+path, method=method, data=None if body is None else json.dumps(body).encode(), headers={"Accept":"application/json","Content-Type":"application/json"})
        try: response = opener.open(req, timeout=8)
        except urllib.error.HTTPError as error: response = error
        with response:
            assert response.status == expected, "Owned public-search HTTP status changed"
            assert response.headers.get_content_type() == "application/json"
            if path.startswith("/api/products/suggestions"):
                assert response.headers.get("Cache-Control") == "no-store"
            return json.load(response), response.headers.get("X-Pubburi-Search-Source")
    def compare(query, source, name, expected_status=200):
        actual, header = request(live,"GET","/api/products"+query,expected=expected_status)
        expected, _ = request(baseline,"GET","/api/products"+query,expected=expected_status)
        assert actual == expected, "Public search JSON differs from current database-only contract"
        if expected_status == 200: assert header == source, "Public search source changed"
        views.append({"case":name,"status":expected_status,"source":header,"response":actual})
        progress.write_text(json.dumps({"status":"running","cases":views},ensure_ascii=False));progress.chmod(0o600)
        return actual["data"]
    def suggestions(prefix, source, name, base=None):
        path="/api/products/suggestions?"+urllib.parse.urlencode({"q":prefix})
        actual,header=request(base or live,"GET",path)
        expected,baseline_header=request(baseline,"GET",path)
        assert baseline_header=="DATABASE" or baseline_jar!=jar and baseline_header is None
        assert actual==expected,"Suggestions differ from current database-only contract"
        assert header==source,"Suggestion source changed: "+name
        assert len(actual["data"]["items"])<=8
        views.append({"case":"suggestions-"+name,"status":200,"source":header,"response":actual})
        return actual["data"]["items"]
    admin = CookieJar(); live = baseline = None
    with cleanup_on_termination(), socket.socket() as refusal:
        refusal.bind(("127.0.0.1",0))
        try:
            baseline = start("database",False,artifact=baseline_jar); live = start("engine",True)
            request(live,"POST","/api/auth/login",{"id":environ["PUBBURI_JPA_ADMIN_ID"],"pass":environ["PUBBURI_JPA_ADMIN_PASSWORD"]},admin)
            # Only this authored admin can receive the existing login's SHA256->bcrypt CAS.
            # Keep every other row/column (including its epoch) in the preservation denominator.
            authenticated=business();identifier=environ["PUBBURI_JPA_ADMIN_ID"]
            after_auth=private/("public-search-"+dialect+"-business-after-auth.json");after_auth.write_text(json.dumps(authenticated,ensure_ascii=False,default=str));after_auth.chmod(0o600)
            fixture_hash_upgrade=verify_fixture_login_transition(before,authenticated,identifier,environ["PUBBURI_JPA_ADMIN_PASSWORD"])
            before=authenticated
            token = uuid.uuid4().hex; root = "?q="+token
            def create(suffix, price, abv):
                body={"name":"ci_jpa_search_"+token+suffix,"type":"맥주" if suffix!="B" else "증류주","price":price,"img":"authored.png","abv":abv,"orderCount":0}
                value=request(live,"POST","/api/admin/products",body,admin,201)[0]["data"]; products.append(value["id"]); return value["id"],body
            def features(identifier,taste,aroma): request(live,"PUT","/api/admin/products/"+str(identifier)+"/features",{"tasteTags":taste,"aromaTags":aroma,"sourceName":"Authored search fixture"},admin)
            a, a_body = create("A",1000,5.555); features(a,["a,b","line\nx","sweet"],["floral"]); drain()
            assert compare(root,"ENGINE_VERIFIED","first-current-product")["total"]==1
            name_prefix="ci_jpa_search_"+token
            assert [row["id"] for row in suggestions(name_prefix,"ENGINE_VERIFIED","first-current-product")]==[a]
            b, _ = create("B",2000,0)
            assert compare(root,"DATABASE_FALLBACK","missing-pending-product")["total"]==2
            assert len(suggestions(name_prefix,"DATABASE_FALLBACK","missing-pending-product"))==2
            features(b,[],None); c, _ = create("C",1000,5.1); features(c,["a","b","line\nx"],[]); drain()
            suggestions(name_prefix,"ENGINE_VERIFIED","replayed-products")
            literal=" %_!\\*?한글"; first,first_body=create(literal,1234,5); second,_=create(literal,4321,6)
            drain()
            assert [row["id"] for row in suggestions("\u00a0"+name_prefix+literal+"\u00a0","ENGINE_VERIFIED","literal-duplicate-skus")]==[first,second]
            for n in range(9):create("cap"+str(n),1000+n,5)
            drain(); assert len(suggestions(name_prefix+"cap","ENGINE_VERIFIED","fixed-eight-cap"))==8
            first_body["name"]="ci_jpa_renamed_"+token
            request(live,"PUT","/api/admin/products/"+str(first),first_body,admin)
            assert [row["id"] for row in suggestions(name_prefix+literal,"DATABASE_FALLBACK","delayed-rename")]==[second]
            drain(); suggestions(name_prefix+literal,"ENGINE_VERIFIED","rename-replayed")
            request(live,"DELETE","/api/admin/products/"+str(second),cookies=admin)
            assert suggestions(name_prefix+literal,"DATABASE_FALLBACK","delayed-delete")==[]
            drain(); assert suggestions(name_prefix+literal,"ENGINE_VERIFIED","delete-replayed")==[]
            meta=http.request("GET","/"+index+"/_mapping")[1][index]["mappings"]["_meta"]
            try:
                assert http.request("PUT","/"+index+"/_mapping",{"_meta":dict(meta,source_id=str(uuid.uuid4()))})[0]==200
                suggestions(name_prefix,"DATABASE_FALLBACK","wrong-source")
            finally:
                assert http.request("PUT","/"+index+"/_mapping",{"_meta":meta})[0]==200
            suggestions(name_prefix,"ENGINE_VERIFIED","source-restored")
            # Inject a partial reply after the real owned engine responds; never accept partial IDs.
            class PartialReply(BaseHTTPRequestHandler):
                def log_message(self,*args):pass
                def do_GET(self):self.forward()
                def do_POST(self):self.forward()
                def forward(self):
                    body=None
                    if self.command=="POST":body=json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                    status,value=http.request(self.command,self.path,body)
                    if self.command=="POST" and status==200:value["_shards"]["failed"]=1
                    raw=json.dumps(value).encode();self.send_response(status);self.send_header("Content-Type","application/json")
                    self.send_header("Content-Length",str(len(raw)));self.end_headers();self.wfile.write(raw)
            proxy=ThreadingHTTPServer(("127.0.0.1",0),PartialReply);worker=threading.Thread(target=proxy.serve_forever,daemon=True);worker.start()
            try:
                partial=start("partial",True,"http://127.0.0.1:"+str(proxy.server_address[1]))
                suggestions(name_prefix,"DATABASE_FALLBACK","injected-partial-real-engine-reply",partial)
            finally:
                proxy.shutdown();proxy.server_close();worker.join(timeout=3);assert not worker.is_alive()
            for sort in ("popular","priceAsc","priceDesc","name"):
                compare(root+"&sort="+sort+"&size=2&page=1","ENGINE_VERIFIED","sort-"+sort)
                compare(root+"&sort="+sort+"&size=2&page=2","ENGINE_VERIFIED","page-"+sort)
            for query,name in (("&minPriceKrw=0&maxPriceKrw=1500","price-range"),("&minAbvPercent=5.55&maxAbvPercent=5.56","fractional-real"),("&minAbvPercent=5.56","no-rounding"),("&minAbvPercent=0&maxAbvPercent=0","actual-zero-abv"),("&maxPriceKrw=0","real-zero-empty"),("&type=%EB%A7%A5%EC%A3%BC","exact-category"),("&tasteTag=a%2Cb&tasteTag=line%0Ax","literal-AND"),("&tasteTag=a&tasteTag=b","two-atoms"),("&aromaTag=floral","aroma-known")):
                compare(root+query,"ENGINE_VERIFIED",name)
            for query in ("?q=%25","?q=%5F","?q=a%5Cb","?page=101&size=100"):
                compare(query,"DATABASE_FALLBACK","legacy-query-or-offset")
            for query in ("?tasteTag=a&tasteTag=A","?minPriceKrw=2&maxPriceKrw=1","?page=2147483647&size=100"):
                compare(query,None,"strict-input",400)
            a_body["price"]=9000; request(live,"PUT","/api/admin/products/"+str(a),a_body,admin)
            compare(root+"&maxPriceKrw=1500","DATABASE_FALLBACK","stale-price-hard-bound")
            latest=compare(root,"ENGINE_VERIFIED","latest-fields-with-same-ids")
            assert next(row for row in latest["items"] if row["id"]==a)["price"]==9000
            assert next(row for row in suggestions(name_prefix+"A","ENGINE_VERIFIED","current-price") if row["id"]==a)["price"]==9000
            drain(); compare(root+"&maxPriceKrw=1500","ENGINE_VERIFIED","price-replayed")
            features(a,[],None); compare(root+"&tasteTag=a%2Cb","DATABASE_FALLBACK","stale-tag-condition"); drain()
            compare(root+"&tasteTag=a%2Cb","ENGINE_VERIFIED","known-empty-after-replay")
            request(live,"DELETE","/api/admin/products/"+str(a),cookies=admin)
            compare(root+"A","DATABASE_FALLBACK","stale-live-after-delete"); drain()
            compare(root+"A","ENGINE_VERIFIED","durable-tombstone-filtered")
            assert http.request("GET","/"+index+"/_doc/"+str(a))[1]["_source"]["deleted"] is True
            unavailable = start("unavailable",True,"http://127.0.0.1:"+str(refusal.getsockname()[1]))
            wanted=root+"&maxPriceKrw=1500&tasteTag=a&tasteTag=b"
            bad, source_header=request(unavailable,"GET","/api/products"+wanted)
            expected,_=request(baseline,"GET","/api/products"+wanted)
            assert source_header=="DATABASE_FALLBACK" and bad==expected
            views.append({"case":"non-listening-configured-origin","status":200,"source":source_header,"response":bad})
            suggestions(name_prefix,"DATABASE_FALLBACK","non-listening-configured-origin",unavailable)
        finally:
            try:
                if live is not None:
                    for identifier in products:
                        try: request(live,"DELETE","/api/admin/products/"+str(identifier),cookies=admin)
                        except AssertionError: request(live,"GET","/api/products/"+str(identifier),expected=404)
                    drain()
            finally:
                for process in reversed(processes):
                    if process.poll() is None:
                        process.terminate()
                        try: process.wait(timeout=10)
                        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
                    for row in process_rows:
                        if row["pid"]==process.pid: row.update(stopped=True,exit=process.returncode)
                for handle in handles: handle.close()
                save()
    after=business();after_file=private/("public-search-"+dialect+"-business-after.json");after_file.write_text(json.dumps(after,ensure_ascii=False,default=str));after_file.chmod(0o600)
    assert after==before, "Original business rows changed"
    current_events = rows("SELECT event_id,product_id,source_version,deleted,"+payload_sql+" AS payload,created_at FROM t_product_search_outbox ORDER BY event_id")
    assert all(row in current_events for row in old_events)
    assert all(row in rows("SELECT product_id,source_version FROM t_product_search_version ORDER BY product_id") for row in old_versions)
    current_acks=rows("SELECT * FROM t_product_search_delivery WHERE status='DELIVERED' ORDER BY event_id")
    assert all(row in current_acks for row in old_acks) and store.source_id()==source
    report={"status":"passed","dialect":dialect,"cases":views,"whole_status_JSON_equal_to_database_only":True,
        "jar_sha256":hashlib.sha256(jar.read_bytes()).hexdigest(),"baseline_jar_sha256":hashlib.sha256(baseline_jar.read_bytes()).hexdigest(),
        "original_business_preserved":True,"original_event_version_and_completed_ACK_preserved":True,"original_completed_ACKs":len(old_acks),
        "current_completed_ACKs":len(current_acks),"source_UUID_unchanged":True,"all_owned_processes_stopped":all(row["stopped"] for row in process_rows),"performance_claim":False}
    report["authored_admin_legacy_hash_upgraded"]=fixture_hash_upgrade
    output=private/("public-search-"+dialect+"-report.json");output.write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str));output.chmod(0o600)
    return {key:value for key,value in report.items() if key not in {"cases"}}

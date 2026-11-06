#!/usr/bin/env python3
"""Verify an owned JPA JAR, account/market/product/order/comment contracts and restart."""
import hashlib
from contextlib import contextmanager
import http.client
import http.cookiejar
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from urllib.parse import urlsplit

from postgresql_schema import ROOT, verify_owned_postgres
from search_index import _DeadlineConnection
from api_smoke import verify_content_recommendation_openapi, verify_similar_products_openapi


@contextmanager
def cleanup_on_termination():
    previous=signal.getsignal(signal.SIGTERM)
    def terminate(signum,frame):raise SystemExit(128+signum)
    signal.signal(signal.SIGTERM,terminate)
    try:yield
    finally:signal.signal(signal.SIGTERM,previous)


@contextmanager
def defer_termination():
    # Register the child before a pending termination can unwind its owner's finally block.
    # Defer Python handlers, not the OS mask: exec'ed children must not inherit blocked SIGTERM.
    previous={kind:signal.getsignal(kind) for kind in (signal.SIGTERM,signal.SIGINT)};pending=[]
    def remember(kind,frame):pending.append((kind,frame))
    for kind in previous:signal.signal(kind,remember)
    try:yield
    finally:
        for kind,handler in previous.items():signal.signal(kind,handler)
        for kind,frame in pending:
            handler=previous[kind]
            if callable(handler):handler(kind,frame)
            elif handler!=signal.SIG_IGN:raise SystemExit(128+kind)


def request_json(cookies,base,method,path,body,deadline):
    """Bound connect/write/headers/body by one request-owned absolute IO deadline."""
    origin=urlsplit(base)
    if origin.scheme!="http" or not ipaddress.ip_address(origin.hostname).is_loopback or origin.path or origin.query or origin.fragment or origin.username or origin.password or not origin.port:
        raise ValueError("jpa_runtime_loopback_origin_required")
    budget=min(6,deadline-time.monotonic())
    if budget<=0:raise ValueError("jpa_runtime_total_deadline")
    connection=_DeadlineConnection(origin.hostname,origin.port,budget)
    try:
        data=None if body is None else json.dumps(body,allow_nan=False).encode()
        req=urllib.request.Request(base+path,data=data,method=method,headers={"Accept":"application/json",**({"Content-Type":"application/json"} if body is not None else {})})
        cookies.add_cookie_header(req)
        connection.request(method,path,data,dict(req.header_items()))
        transport=connection.sock
        transport.settimeout(connection.remaining())
        with connection.getresponse() as response:
            length=response.getheader("Content-Length")
            if length is not None and (not length.isascii() or not length.isdigit() or int(length)>262144):raise ValueError("jpa_runtime_response_limit")
            content=bytearray()
            while True:
                transport.settimeout(connection.remaining())
                chunk=response.read1(min(16384,262145-len(content)))
                connection.remaining();content.extend(chunk)
                if len(content)>262144:raise ValueError("jpa_runtime_response_limit")
                if not chunk or response.isclosed():break
            if length is not None and len(content)!=int(length):raise ValueError("jpa_runtime_response_length_mismatch")
            result=response.status,json.loads(content)
            connection.remaining()
            cookies.extract_cookies(response,req)
            return result
    except (OSError,http.client.HTTPException,ValueError,RecursionError):
        if connection.expired.is_set() or time.monotonic()>=connection.end:raise ValueError("jpa_runtime_total_deadline") from None
        raise ValueError("jpa_runtime_http_rejected") from None
    finally:connection.finish()


def child_environment(environ):
    allowed={"PATH","JAVA_HOME","HOME","TMPDIR","TMP","TEMP","LANG","LC_ALL","LC_CTYPE"}
    allowed.update("PUBBURI_PG_"+s for s in ("URL","DB_NAME","USER","PASSWORD","RUN_TOKEN","CA"))
    allowed.update("PUBBURI_JPA_"+s for s in ("REHEARSAL","SOURCE_ID","PORT"))
    return {k:v for k,v in environ.items() if k in allowed}


def verify_jar(path):
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist()
    if "BOOT-INF/classes/com/pubburi/rehearsal/JpaRehearsalApplication.class" not in names:
        raise ValueError("jpa_runtime_provider_missing")
    if any(name.startswith("BOOT-INF/lib/") and any(bad in name.lower() for bad in ("mybatis","mysql-connector","h2-")) for name in names):
        raise ValueError("jpa_runtime_legacy_driver_rejected")
    if any(name.endswith(suffix) for name in names for suffix in ("ServerApplication.class","JpaSessionController.class")):
        raise ValueError("jpa_runtime_unregistered_scope_rejected")
    if not any("spring-data-jpa-" in name for name in names) or not any("hibernate-core-" in name for name in names):
        raise ValueError("jpa_runtime_provider_missing")


def verify_openapi_scope(docs):
    if set(docs["paths"])!={"/api/auth/login","/api/auth/logout","/api/auth/me","/api/markets","/api/markets/{id}","/api/admin/markets","/api/admin/markets/{id}",
            "/api/products","/api/products/popular","/api/products/{id}","/api/products/{id}/features","/api/products/{id}/similar",
            "/api/admin/products","/api/admin/products/{id}","/api/admin/products/{id}/features",
            "/api/orders","/api/orders/{id}","/api/users/{userId}/orders","/api/admin/orders","/api/admin/orders/{id}","/api/admin/orders/{id}/complete",
            "/api/users","/api/users/id-available","/api/users/{id}/profile","/api/admin/users","/api/admin/users/{id}",
            "/api/products/{productId}/comments","/api/comments","/api/comments/{id}","/api/admin/comments",
            "/api/users/me/favorites","/api/users/me/favorites/{productId}","/api/users/me/preferences","/api/recommendations/default","/api/users/me/profile","/api/users/me/password","/api/users/me/search-phrases","/api/users/me/recommendations/content"}:raise ValueError("jpa_runtime_route_scope_changed")
    operations=sum(method in {"get","post","put","patch","delete","head","options","trace"} for path in docs["paths"].values() for method in path)
    if operations!=45:raise ValueError("jpa_runtime_operation_scope_changed")
    verify_content_recommendation_openapi(docs)
    verify_similar_products_openapi(docs)
    return operations


def cleanup_owned(processes,stop):
    failed=False
    with defer_termination():
        for process,stream in processes:
            try:stop(process)
            except Exception:failed=True
            finally:
                try:stream.close()
                except Exception:failed=True
    if failed:raise ValueError("jpa_runtime_cleanup_failed")


def run(environ):
    if environ.get("PUBBURI_JPA_SMOKE_MUTATING")!="1" or environ.get("PUBBURI_JPA_REHEARSAL")!="1":
        raise ValueError("jpa_smoke_explicit_fixture_required")
    if not re.fullmatch(r"[1-9][0-9]{0,9}",environ.get("PUBBURI_JPA_EXPECTED_COMMENT_NEXT","")) or int(environ["PUBBURI_JPA_EXPECTED_COMMENT_NEXT"])>2147483647:
        raise ValueError("jpa_smoke_comment_source_next_required")
    verify_owned_postgres(environ)
    for role in ("ADMIN","USER"):
        if not re.fullmatch(r"ci_jpa_[a-zA-Z0-9_]{1,80}",environ.get("PUBBURI_JPA_"+role+"_ID","")) or not environ.get("PUBBURI_JPA_"+role+"_PASSWORD"):
            raise ValueError("jpa_smoke_authored_credentials_required")
    failure_name=environ.get("PUBBURI_JPA_FAILURE_DB_NAME","");failure_token=environ.get("PUBBURI_JPA_FAILURE_RUN_TOKEN","")
    if not re.fullmatch(r"[a-z][a-z0-9_]*_ci",failure_name) or failure_name==environ.get("PUBBURI_PG_DB_NAME") or not re.fullmatch(r"[A-Za-z0-9_-]{12,100}",failure_token):
        raise ValueError("jpa_smoke_separate_failure_fixture_required")
    failure_env={"PUBBURI_PG_DB_NAME":failure_name,"PUBBURI_PG_URL":environ["PUBBURI_PG_URL"].rsplit("/",1)[0]+"/"+failure_name,"PUBBURI_PG_RUN_TOKEN":failure_token}
    verify_owned_postgres(dict(environ,**failure_env))
    jar=ROOT/"tools/jpa-rehearsal/target/pubburi-jpa-rehearsal-0.0.1-SNAPSHOT.jar"
    verify_jar(jar)
    directory=ROOT/".pubburi"/("worker-jpa-runtime-"+str(uuid.uuid4()));directory.mkdir(mode=0o700)
    immutable=directory/"runtime.jar";immutable.write_bytes(jar.read_bytes());immutable.chmod(0o600)
    state={"jar_sha256":hashlib.sha256(immutable.read_bytes()).hexdigest(),"processes":[],"phase":"starting"}
    checkpoint=directory/"owned.json"
    def save():checkpoint.write_text(json.dumps(state));checkpoint.chmod(0o600)
    processes=[];deadline=time.monotonic()+120;unavailable_upstream=None
    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        for item in state["processes"]:
            if item["pid"]==process.pid:item["stopped"]=True
        save()
    def start(name,extra=None,expect_failure=None):
        env=child_environment(environ);env["PUBBURI_JPA_PORT"]="0"
        # Keep the owned fallback target refused without borrowing a shared recommendation port.
        env["PUBBURI_RECOMMENDATION_BASE_URL"]="http://127.0.0.1:"+str(unavailable_upstream.getsockname()[1])
        if extra:env.update(extra)
        log=directory/(name+".log");stream=log.open("w");log.chmod(0o600)
        java=str(Path(env["JAVA_HOME"])/"bin/java") if env.get("JAVA_HOME") else "java"
        with defer_termination():
            process=subprocess.Popen([java,"-jar",str(immutable)],env=env,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
            processes.append((process,stream));state["processes"].append({"pid":process.pid,"kind":name,"stopped":False});save()
        end=min(deadline,time.monotonic()+45)
        while time.monotonic()<end:
            text=log.read_text(errors="replace")
            if process.poll() is not None:
                if expect_failure and process.returncode!=0 and expect_failure in text:return process,None
                raise ValueError("jpa_runtime_start_failed")
            match=re.search(r"Tomcat started on port (\d+)",text)
            if match:
                if expect_failure:raise ValueError("jpa_runtime_unsafe_start_accepted")
                port=int(match.group(1))
                if port in (5173,9987,8000):raise ValueError("jpa_runtime_shared_port_rejected")
                return process,"http://127.0.0.1:"+str(port)
            time.sleep(.1)
        raise ValueError("jpa_runtime_start_deadline")
    def client():return http.cookiejar.CookieJar()
    def request(c,base,method,path,body=None):
        return request_json(c,base,method,path,body,deadline)
    def expect(status,expected):
        if status!=expected:raise ValueError("jpa_runtime_http_contract_failed")
    def login(c,base,role):
        status,body=request(c,base,"POST","/api/auth/login",{"id":environ["PUBBURI_JPA_"+role+"_ID"],"pass":environ["PUBBURI_JPA_"+role+"_PASSWORD"]});expect(status,200)
        if any(key in json.dumps(body) for key in ("password_hash","authEpoch","auth_epoch")):raise ValueError("jpa_runtime_private_field_exposed")
    def default_selection(base):
        status,page=request(client(),base,"GET","/api/products?size=1");expect(status,200)
        for size in (1,4,12):
            status,result=request(client(),base,"GET","/api/recommendations/default?size="+str(size));expect(status,200)
            data=result["data"];ids=[p["id"] for p in data.get("items",[])]
            if set(data)!={"items","source"} or data["source"]!="FALLBACK" or len(ids)!=min(size,page["data"]["total"]) or ids!=sorted(set(ids)):
                raise ValueError("jpa_runtime_default_fallback_changed")
            for product in data["items"]:
                status,current=request(client(),base,"GET","/api/products/"+str(product["id"]));expect(status,200)
                if product!=current["data"]:raise ValueError("jpa_runtime_default_current_product_changed")
        for size in ("0","13","1.5","2147483648"):
            expect(request(client(),base,"GET","/api/recommendations/default?size="+size)[0],400)
    try:
        unavailable_upstream=socket.socket();unavailable_upstream.bind(("127.0.0.1",0))
        state["unavailable_upstream_port"]=unavailable_upstream.getsockname()[1];save()
        # Each rejection is checked against a specific guard, not merely any process failure.
        start("implicit",{**failure_env,"PUBBURI_JPA_REHEARSAL":"0"},"jpa_rehearsal_explicit_fixture_required")
        start("auto-ddl",{**failure_env,"SPRING_JPA_HIBERNATE_DDL_AUTO":"create"},"jpa_rehearsal_schema_policy_rejected")
        start("wrong-marker",{**failure_env,"PUBBURI_PG_RUN_TOKEN":"authored-wrong-marker"},"jpa_rehearsal_fixture_or_schema_rejected")
        start("wrong-source",{**failure_env,"PUBBURI_JPA_SOURCE_ID":"00000000-0000-4000-8000-000000000000"},"jpa_rehearsal_fixture_or_schema_rejected")
        first,base=start("normal");anonymous=client();admin=client();ordinary=client()
        default_selection(base)
        expect(request(anonymous,base,"GET","/api/markets")[0],200)
        payload={"name":"Authored packaged JPA market","latitude":37.123456789,"longitude":127.987654321,"image":"authored.png"}
        expect(request(anonymous,base,"POST","/api/admin/markets",payload)[0],401)
        login(ordinary,base,"USER");expect(request(ordinary,base,"POST","/api/admin/markets",payload)[0],403)
        login(admin,base,"ADMIN");status,created=request(admin,base,"POST","/api/admin/markets",payload);expect(status,201);market_id=created["data"]["id"]
        account_id="ci_jpa_packaged_account_"+uuid.uuid4().hex;account_pass="Authored-packaged-account-42";fresh_pass="Authored-packaged-fresh-84"
        registration={"id":account_id,"name":"Authored packaged account","pass":account_pass}
        expect(request(anonymous,base,"POST","/api/users",registration)[0],201)
        expect(request(anonymous,base,"POST","/api/users",registration)[0],400)
        status,available=request(anonymous,base,"GET","/api/users/id-available?id="+account_id);expect(status,200)
        if available["data"]["available"]:raise ValueError("jpa_runtime_duplicate_account_changed")
        account=client();expect(request(account,base,"POST","/api/auth/login",{"id":account_id,"pass":account_pass})[0],200)
        expect(request(ordinary,base,"GET","/api/users/"+account_id+"/profile")[0],403)
        expect(request(admin,base,"PUT","/api/admin/users/"+account_id,{"name":"Authored persisted account","stamps":51})[0],200)
        expect(request(anonymous,base,"PUT","/api/users/me/profile",{"name":"Unauthorized"})[0],401)
        expect(request(anonymous,base,"PUT","/api/users/me/password",{"currentPassword":account_pass,"newPassword":fresh_pass})[0],401)
        expect(request(account,base,"PUT","/api/users/me/profile",{"name":"Authored self persisted account"})[0],200)
        expect(request(account,base,"PUT","/api/users/me/profile?userId=other",{"name":"Forbidden selector"})[0],400)
        expect(request(account,base,"PUT","/api/users/me/profile",{"name":"Forbidden role","role":"ADMIN"})[0],400)
        status,account_profile=request(account,base,"GET","/api/users/"+account_id+"/profile");expect(status,200)
        profile_data=account_profile["data"]
        if set(profile_data)!={"user","orders","grade"} or set(profile_data["user"])!={"id","name","stamps","role","admin"} or profile_data["user"]["stamps"]!=51 or profile_data["grade"]["title"]!="꽃" or profile_data["orders"]!=[]:raise ValueError("jpa_runtime_profile_contract_changed")
        expect(request(admin,base,"GET","/api/admin/users?size=100")[0],200)
        expect(request(admin,base,"PUT","/api/admin/users/"+account_id,{"pass":fresh_pass})[0],200)
        expect(request(account,base,"GET","/api/auth/me")[0],401)
        expect(request(account,base,"POST","/api/auth/login",{"id":account_id,"pass":fresh_pass})[0],200)
        other_account_session=client();expect(request(other_account_session,base,"POST","/api/auth/login",{"id":account_id,"pass":fresh_pass})[0],200)
        expect(request(account,base,"PUT","/api/users/me/password",{"currentPassword":"wrong pass","newPassword":"Authored-self-new-pass"})[0],403)
        expect(request(account,base,"GET","/api/auth/me")[0],200)
        next_self_pass=" Authored-self-new-pass "
        expect(request(account,base,"PUT","/api/users/me/password",{"currentPassword":fresh_pass,"newPassword":next_self_pass})[0],200)
        expect(request(account,base,"GET","/api/auth/me")[0],200);expect(request(other_account_session,base,"GET","/api/auth/me")[0],401)
        expect(request(client(),base,"POST","/api/auth/login",{"id":account_id,"pass":fresh_pass})[0],401)
        fresh_pass=next_self_pass
        preference_path="/api/users/me/preferences"
        unanswered={"surveyStatus":"NOT_STARTED","softPreferences":dict.fromkeys(("categories","tasteTags","aromaTags","targetAbvPercent")),"hardConstraints":dict.fromkeys(("minPriceKrw","maxPriceKrw","minAbvPercent","maxAbvPercent"))}
        expect(request(anonymous,base,"GET",preference_path)[0],401)
        status,empty_preference=request(account,base,"GET",preference_path);expect(status,200)
        if empty_preference["data"]!=unanswered:raise ValueError("jpa_runtime_unanswered_preference_changed")
        authored_preference={"surveyStatus":"COMPLETED","softPreferences":{"tasteTags":[" SMOKY ","dry"],"aromaTags":[],"targetAbvPercent":15.25},"hardConstraints":{"minPriceKrw":0,"maxAbvPercent":10}}
        for _ in range(2):
            status,saved_preference=request(account,base,"PUT",preference_path,authored_preference);expect(status,200)
        expected_preference={"surveyStatus":"COMPLETED","softPreferences":{"categories":None,"tasteTags":["dry","smoky"],"aromaTags":[],"targetAbvPercent":15.25},"hardConstraints":{"minPriceKrw":0,"maxPriceKrw":None,"minAbvPercent":None,"maxAbvPercent":10}}
        if saved_preference["data"]!=expected_preference:raise ValueError("jpa_runtime_preference_values_changed")
        status,admin_preference=request(admin,base,"GET",preference_path);expect(status,200)
        if admin_preference["data"]!=unanswered:raise ValueError("jpa_runtime_preference_owner_leaked")
        expect(request(account,base,"PUT",preference_path,{"surveyStatus":"SKIPPED","softPreferences":{"tasteTags":[]}})[0],400)
        status,retained_preference=request(account,base,"GET",preference_path);expect(status,200)
        if retained_preference["data"]!=expected_preference:raise ValueError("jpa_runtime_invalid_preference_mutated")
        phrase_path="/api/users/me/search-phrases"
        expect(request(anonymous,base,"POST",phrase_path,{})[0],401)
        status,phrases=request(account,base,"POST",phrase_path,{"searchOptInFields":["tasteTags"],"limit":5});expect(status,200)
        if phrases["data"]!={"source":"UNAVAILABLE","result":None}:raise ValueError("jpa_runtime_search_phrase_failure_broadened")
        expect(request(account,base,"POST",phrase_path+"?userId=other",{})[0],400)
        expect(request(account,base,"POST",phrase_path,{"preferences":{}})[0],400)
        if request(account,base,"GET",preference_path)[1]["data"]!=expected_preference:raise ValueError("jpa_runtime_search_phrase_mutated_preferences")
        content_path="/api/users/me/recommendations/content"
        expect(request(anonymous,base,"POST",content_path,{})[0],401)
        status,content=request(account,base,"POST",content_path,{});expect(status,200)
        if content["data"]!={"source":"UNAVAILABLE","catalogScope":"REVIEWED_DEMO","result":None,"products":[]}:raise ValueError("jpa_runtime_unapproved_content_catalog_sent")
        expect(request(account,base,"POST",content_path,{"catalog":[]})[0],400)
        expect(request(account,base,"POST",content_path+"?userId=other",{})[0],400)
        if market_id<int(environ.get("PUBBURI_JPA_EXPECTED_MARKET_NEXT","1")):raise ValueError("jpa_runtime_identity_reused")
        if set(created["data"])!={"id","name","latitude","longitude","image"}:raise ValueError("jpa_runtime_market_shape_changed")
        payload["name"]="Authored packaged edited";expect(request(admin,base,"PUT","/api/admin/markets/"+str(market_id),payload)[0],200)
        status,catalog=request(anonymous,base,"GET","/api/products?size=12");expect(status,200)
        page=catalog["data"]
        if set(page)!={"items","page","size","total","totalPages","hasNext"} or len(page["items"])>12:
            raise ValueError("jpa_runtime_product_page_shape_changed")
        product_fields={"id","name","type","price","img","abv","orderCount"}
        if any(set(item)!=product_fields for item in page["items"]):raise ValueError("jpa_runtime_product_shape_changed")
        status,popular=request(anonymous,base,"GET","/api/products/popular");expect(status,200)
        if popular["data"]!=page["items"]:raise ValueError("jpa_runtime_product_order_changed")
        if page["items"]:
            first_product=page["items"][0]
            status,detail=request(anonymous,base,"GET","/api/products/"+str(first_product["id"]));expect(status,200)
            if detail["data"]!=first_product:raise ValueError("jpa_runtime_product_detail_changed")
            status,features=request(anonymous,base,"GET","/api/products/"+str(first_product["id"])+"/features");expect(status,200)
            if set(features["data"])!={"productId","volumeMl","description","tasteTags","aromaTags","sourceName","sourceUrl","sourceNote"} or features["data"]["productId"]!=first_product["id"]:
                raise ValueError("jpa_runtime_product_feature_shape_changed")
            status,similar=request(anonymous,base,"GET",f"/api/products/{first_product['id']}/similar");expect(status,200)
            if similar["data"]!={"items":[]}:raise ValueError("jpa_runtime_unapproved_similar_catalog_sent")
        expect(request(anonymous,base,"GET","/api/products/0")[0],404)
        expect(request(anonymous,base,"GET","/api/products/0/features")[0],404)
        expect(request(anonymous,base,"GET","/api/products/0/similar")[0],404)
        expect(request(anonymous,base,"GET","/api/products?page=2147483647&size=12")[0],400)
        expect(request(anonymous,base,"GET","/api/products?minAbvPercent=5.555")[0],400)
        authored_product={"name":"ci_jpa_packaged_"+uuid.uuid4().hex,"type":"증류주","price":1200,"img":"authored.png","abv":15.5,"orderCount":0}
        expect(request(anonymous,base,"POST","/api/admin/products",authored_product)[0],401)
        expect(request(ordinary,base,"POST","/api/admin/products",authored_product)[0],403)
        status,created_product=request(admin,base,"POST","/api/admin/products",authored_product);expect(status,201);product_id=created_product["data"]["id"]
        if set(created_product["data"])!=product_fields:raise ValueError("jpa_runtime_product_shape_changed")
        authored_product["price"]=2300
        expect(request(admin,base,"PUT","/api/admin/products/"+str(product_id),authored_product)[0],200)
        status,reviewed=request(admin,base,"PUT","/api/admin/products/"+str(product_id)+"/features",{"tasteTags":[" DRY ","fruity"],"aromaTags":[],"sourceName":"Authored packaged verification"});expect(status,200)
        if reviewed["data"]["tasteTags"]!=["dry","fruity"] or reviewed["data"]["aromaTags"]!=[]:raise ValueError("jpa_runtime_product_features_changed")
        expect(request(admin,base,"PUT","/api/admin/products/"+str(product_id)+"/features",{"volumeMl":"750","sourceName":"authored"})[0],400)
        favorite_path="/api/users/me/favorites/"+str(product_id)
        expect(request(anonymous,base,"GET",favorite_path)[0],401)
        status,absent=request(ordinary,base,"GET",favorite_path);expect(status,200)
        if absent["data"]!={"productId":product_id,"favorited":False}:raise ValueError("jpa_runtime_favorite_absence_changed")
        for _ in range(2):
            status,present=request(ordinary,base,"PUT",favorite_path);expect(status,200)
            if present["data"]!={"productId":product_id,"favorited":True}:raise ValueError("jpa_runtime_favorite_put_changed")
        status,admin_favorite=request(admin,base,"GET",favorite_path);expect(status,200)
        if admin_favorite["data"]["favorited"]:raise ValueError("jpa_runtime_favorite_owner_leaked")
        authored_comment={"productId":product_id,"rating":3.7,"comment":"Authored packaged review","tasteTags":[" SWEET ","dry,fruit\none"]}
        expect(request(anonymous,base,"POST","/api/comments",authored_comment)[0],401)
        status,created_comment=request(ordinary,base,"POST","/api/comments",authored_comment);expect(status,201);comment_id=created_comment["data"]["id"]
        if comment_id<int(environ["PUBBURI_JPA_EXPECTED_COMMENT_NEXT"]) or set(created_comment["data"])!={"id","userId","productId","rating","comment"} or created_comment["data"]["rating"]!=3.7 or created_comment["data"]["userId"]!=environ["PUBBURI_JPA_USER_ID"]:raise ValueError("jpa_runtime_comment_identity_or_shape_changed")
        status,edited_comment=request(ordinary,base,"PUT","/api/comments/"+str(comment_id),{"rating":1.25,"comment":"Authored edited review"});expect(status,200)
        comment_data=edited_comment["data"]
        status,public_comments=request(anonymous,base,"GET","/api/products/"+str(product_id)+"/comments");expect(status,200)
        if public_comments["data"]["items"]!=[comment_data]:raise ValueError("jpa_runtime_comment_edit_not_public")
        tagged_comment=dict(comment_data,tasteTags=["dry,fruit\none","sweet"])
        status,tagged_page=request(anonymous,base,"GET","/api/products/"+str(product_id)+"/comments?includeTasteTags=true");expect(status,200)
        if tagged_page["data"]["items"]!=[tagged_comment]:raise ValueError("jpa_runtime_comment_tags_lost_on_omitted_update")
        expect(request(ordinary,base,"GET","/api/admin/comments")[0],403)
        expect(request(admin,base,"GET","/api/admin/comments")[0],200)
        authored_order={"details":[{"productId":product_id,"quantity":1},{"productId":product_id,"quantity":2}]}
        expect(request(anonymous,base,"POST","/api/orders",authored_order)[0],401)
        expect(request(ordinary,base,"POST","/api/orders",dict(authored_order,userId=environ["PUBBURI_JPA_ADMIN_ID"]))[0],403)
        status,created_order=request(ordinary,base,"POST","/api/orders",authored_order);expect(status,201);order_id=created_order["data"]["oId"]
        order_data=created_order["data"]
        if set(order_data)!={"oId","userId","orderType","orderTable","completed","orderTime","details","totalPrice","priceBasis"} or order_data["totalPrice"]!=6900 or order_data["priceBasis"]!="ORDER_SNAPSHOT" or len(order_data["details"])!=1 or order_data["details"][0]["quantity"]!=3:
            raise ValueError("jpa_runtime_order_snapshot_changed")
        status,order_profile=request(ordinary,base,"GET","/api/users/"+environ["PUBBURI_JPA_USER_ID"]+"/profile");expect(status,200)
        if not any(row["oId"]==order_id for row in order_profile["data"]["orders"]):raise ValueError("jpa_runtime_profile_order_missing")
        expect(request(ordinary,base,"GET","/api/admin/orders")[0],403)
        expect(request(ordinary,base,"PATCH","/api/admin/orders/"+str(order_id)+"/complete")[0],403)
        expect(request(admin,base,"GET","/api/orders")[0],405)
        status,docs=request(admin,base,"GET","/v3/api-docs");expect(status,200)
        operations=verify_openapi_scope(docs)
        stop(first)
        second,base=start("restart");status,persisted=request(client(),base,"GET","/api/markets/"+str(market_id));expect(status,200)
        default_selection(base)
        status,restarted_tags=request(client(),base,"GET","/api/products/"+str(product_id)+"/comments?includeTasteTags=true");expect(status,200)
        if restarted_tags["data"]["items"]!=[tagged_comment]:raise ValueError("jpa_runtime_restart_lost_comment_tags")
        if persisted["data"]["name"]!=payload["name"]:raise ValueError("jpa_runtime_restart_lost_data")
        expect(request(admin,base,"GET","/api/auth/me")[0],401)  # In-memory sessions are not persisted across restart.
        login(admin,base,"ADMIN");expect(request(admin,base,"DELETE","/api/admin/markets/"+str(market_id))[0],200)
        expect(request(account,base,"GET","/api/auth/me")[0],401)
        expect(request(account,base,"POST","/api/auth/login",{"id":account_id,"pass":fresh_pass})[0],200)
        status,persisted_profile=request(account,base,"GET","/api/users/"+account_id+"/profile");expect(status,200)
        if persisted_profile["data"]!=profile_data:raise ValueError("jpa_runtime_profile_restart_lost_data")
        status,persisted_preference=request(account,base,"GET",preference_path);expect(status,200)
        if persisted_preference["data"]!=expected_preference:raise ValueError("jpa_runtime_preference_restart_lost_data")
        for survey_status in ("SKIPPED","NOT_STARTED","COMPLETED"):
            status,cleared_preference=request(account,base,"PUT",preference_path,{"surveyStatus":survey_status});expect(status,200)
            if cleared_preference["data"]!=dict(unanswered,surveyStatus=survey_status):raise ValueError("jpa_runtime_preference_whole_replace_changed")
        expect(request(account,base,"PUT",preference_path,authored_preference)[0],200)
        expect(request(admin,base,"GET","/api/markets/"+str(market_id))[0],404)
        status,persisted_product=request(client(),base,"GET","/api/products/"+str(product_id));expect(status,200)
        if persisted_product["data"]["price"]!=2300:raise ValueError("jpa_runtime_product_restart_lost_data")
        status,persisted_features=request(client(),base,"GET","/api/products/"+str(product_id)+"/features");expect(status,200)
        if persisted_features["data"]["tasteTags"]!=["dry","fruity"] or persisted_features["data"]["aromaTags"]!=[]:raise ValueError("jpa_runtime_product_restart_lost_features")
        login(ordinary,base,"USER")
        status,persisted_favorite=request(ordinary,base,"GET",favorite_path);expect(status,200)
        if persisted_favorite["data"]!={"productId":product_id,"favorited":True}:raise ValueError("jpa_runtime_restart_lost_favorite")
        status,favorites=request(ordinary,base,"GET","/api/users/me/favorites");expect(status,200)
        if favorites["data"]["items"]!=[persisted_product["data"]] or set(favorites["data"]["items"][0])!=product_fields:raise ValueError("jpa_runtime_favorite_current_product_changed")
        for _ in range(2):expect(request(ordinary,base,"DELETE",favorite_path)[0],200)
        expect(request(ordinary,base,"PUT",favorite_path)[0],200)
        status,persisted_comments=request(client(),base,"GET","/api/products/"+str(product_id)+"/comments");expect(status,200)
        if persisted_comments["data"]["items"]!=[comment_data]:raise ValueError("jpa_runtime_restart_lost_comment")
        expect(request(ordinary,base,"DELETE","/api/comments/"+str(comment_id))[0],200)
        expect(request(ordinary,base,"DELETE","/api/comments/"+str(comment_id))[0],404)
        status,cascade_comment=request(ordinary,base,"POST","/api/comments",dict(authored_comment,rating=2.75,comment="Authored cascade review"));expect(status,201)
        cascade_comment_id=cascade_comment["data"]["id"]
        status,persisted_order=request(ordinary,base,"GET","/api/orders/"+str(order_id));expect(status,200)
        if persisted_order["data"]!=order_data:raise ValueError("jpa_runtime_order_restart_lost_snapshot")
        status,user_orders=request(ordinary,base,"GET","/api/users/"+environ["PUBBURI_JPA_USER_ID"]+"/orders");expect(status,200)
        if not any(row["oId"]==order_id for row in user_orders["data"]["items"]):raise ValueError("jpa_runtime_order_list_changed")
        expect(request(admin,base,"GET","/api/admin/orders")[0],200)
        expect(request(admin,base,"PATCH","/api/admin/orders/"+str(order_id)+"/complete")[0],200)
        expect(request(admin,base,"DELETE","/api/admin/products/"+str(product_id))[0],200)
        expect(request(client(),base,"GET","/api/products/"+str(product_id))[0],404)
        expect(request(client(),base,"GET","/api/products/"+str(product_id)+"/features")[0],404)
        status,deleted_favorite=request(ordinary,base,"GET",favorite_path);expect(status,200)
        if deleted_favorite["data"]["favorited"]:raise ValueError("jpa_runtime_favorite_parent_cascade_failed")
        status,empty_favorites=request(ordinary,base,"GET","/api/users/me/favorites");expect(status,200)
        if empty_favorites["data"]["items"]!=[] or empty_favorites["data"]["total"]!=0:raise ValueError("jpa_runtime_favorite_deleted_product_listed")
        status,deleted_comments=request(client(),base,"GET","/api/products/"+str(product_id)+"/comments");expect(status,200)
        if deleted_comments["data"]["items"]!=[] or deleted_comments["data"]["total"]!=0:raise ValueError("jpa_runtime_comment_parent_cascade_failed")
        expect(request(admin,base,"DELETE","/api/comments/"+str(cascade_comment_id))[0],404)
        status,history=request(ordinary,base,"GET","/api/orders/"+str(order_id));expect(status,200)
        if history["data"]["totalPrice"]!=6900 or history["data"]["details"][0]["unitPrice"]!=2300 or history["data"]["details"][0]["productId"] is not None or history["data"]["completed"]!="Y":raise ValueError("jpa_runtime_order_deleted_product_history_changed")
        expect(request(admin,base,"DELETE","/api/admin/orders/"+str(order_id))[0],200)
        expect(request(ordinary,base,"GET","/api/orders/"+str(order_id))[0],404)
        dormant=client();expect(request(dormant,base,"POST","/api/auth/login",{"id":account_id,"pass":fresh_pass})[0],200)
        expect(request(admin,base,"DELETE","/api/admin/users/"+account_id)[0],200)
        expect(request(anonymous,base,"POST","/api/users",registration)[0],201)
        expect(request(dormant,base,"GET","/api/users/"+account_id+"/profile")[0],401)
        expect(request(dormant,base,"GET",preference_path)[0],401)
        expect(request(account,base,"POST","/api/auth/login",{"id":account_id,"pass":account_pass})[0],200)
        status,rejoined_profile=request(account,base,"GET","/api/users/"+account_id+"/profile");expect(status,200)
        if rejoined_profile["data"]["orders"]!=[] or rejoined_profile["data"]["user"]["stamps"]!=0:raise ValueError("jpa_runtime_rejoined_profile_inherited_state")
        status,rejoined_preference=request(account,base,"GET",preference_path);expect(status,200)
        if rejoined_preference["data"]!=unanswered:raise ValueError("jpa_runtime_rejoined_preference_inherited_state")
        expect(request(admin,base,"DELETE","/api/admin/users/"+account_id)[0],200)
        expect(request(admin,base,"POST","/api/auth/logout")[0],200);expect(request(admin,base,"GET","/api/auth/me")[0],401)
        state["phase"]="passed";save()
        return {"status":"passed","provider":"JPA_POSTGRESQL_REHEARSAL","legacy_runtime_dependencies":False,"registered_api_paths":38,"registered_api_operations":operations,
            "public_default_current_fallback":True,
            "self_profile_password_and_session_rotation":True,
            "private_preferences_and_lifecycle":True,
            "private_favorites_and_lifecycle":True,
            "comment_reads_writes_and_parent_cascades":True,
            "account_registration_profile_and_lifecycle":True,
            "order_transactions_and_history":True,
            "product_writes_and_feature_replacement":True,
            "product_reads":True,"product_detail_and_features_checked":bool(page["items"]),"restart_data_preserved":True,"session_relogin_after_restart":True,"full_cutover":False}
    finally:
        with defer_termination():
            cleanup_failed=False
            try:cleanup_owned(processes,stop)
            except Exception:cleanup_failed=True
            if unavailable_upstream is not None:
                unavailable_upstream.close();state["unavailable_upstream_closed"]=True
            state["all_owned_processes_stopped"]=all(p.poll() is not None for p,_ in processes)
            try:
                save()
                if state["all_owned_processes_stopped"]:immutable.unlink(missing_ok=True)
            except Exception:cleanup_failed=True
            if cleanup_failed or not state["all_owned_processes_stopped"]:raise ValueError("jpa_runtime_cleanup_failed")


def main():
    try:
        with cleanup_on_termination():report=run(os.environ)
        target=ROOT/"target";target.mkdir(exist_ok=True);(target/"jpa-rehearsal-report.json").write_text(json.dumps(report)+"\n");print(json.dumps(report));return 0
    except Exception:
        print(json.dumps({"status":"failed","error_code":"jpa_rehearsal_smoke_rejected"}));return 2


if __name__=="__main__":raise SystemExit(main())

#!/usr/bin/env python3
"""Verify one explicitly owned local JPA rehearsal JAR, HTTP session/market flow and restart."""
import hashlib
import http.cookiejar
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

from postgresql_schema import ROOT, verify_owned_postgres


def child_environment(environ):
    allowed={"PATH","JAVA_HOME","HOME","TMPDIR","TMP","TEMP","LANG","LC_ALL","LC_CTYPE"}
    allowed.update("PUBBURI_PG_"+s for s in ("URL","DB_NAME","USER","PASSWORD","RUN_TOKEN","CA"))
    allowed.update("PUBBURI_JPA_"+s for s in ("REHEARSAL","SOURCE_ID","PORT"))
    return {k:v for k,v in environ.items() if k in allowed}


def verify_jar(path):
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist()
    if any(name.startswith("BOOT-INF/lib/") and any(bad in name.lower() for bad in ("mybatis","mysql-connector","h2-")) for name in names):
        raise ValueError("jpa_runtime_legacy_driver_rejected")
    if any(name.endswith(suffix) for name in names for suffix in ("ServerApplication.class","UserRestController.class","ProductRestController.class","OrderRestController.class")):
        raise ValueError("jpa_runtime_unregistered_scope_rejected")
    if not any("spring-data-jpa-" in name for name in names) or not any("hibernate-core-" in name for name in names):
        raise ValueError("jpa_runtime_provider_missing")


def cleanup_owned(processes,stop):
    failed=False
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
    processes=[];deadline=time.monotonic()+120
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
        if extra:env.update(extra)
        log=directory/(name+".log");stream=log.open("w");log.chmod(0o600)
        java=str(Path(env["JAVA_HOME"])/"bin/java") if env.get("JAVA_HOME") else "java"
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
    def client():return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(c,base,method,path,body=None):
        if time.monotonic()>=deadline:raise ValueError("jpa_runtime_total_deadline")
        data=None if body is None else json.dumps(body).encode();req=urllib.request.Request(base+path,data=data,method=method,headers={"Accept":"application/json",**({"Content-Type":"application/json"} if body is not None else {})})
        try:
            response=c.open(req,timeout=6)
        except urllib.error.HTTPError as error:response=error
        with response:
            raw=response.read(262145)
            if len(raw)>262144:raise ValueError("jpa_runtime_response_limit")
            return response.code,json.loads(raw)
    def expect(status,expected):
        if status!=expected:raise ValueError("jpa_runtime_http_contract_failed")
    def login(c,base,role):
        status,body=request(c,base,"POST","/api/auth/login",{"id":environ["PUBBURI_JPA_"+role+"_ID"],"pass":environ["PUBBURI_JPA_"+role+"_PASSWORD"]});expect(status,200)
        if any(key in json.dumps(body) for key in ("password_hash","authEpoch","auth_epoch")):raise ValueError("jpa_runtime_private_field_exposed")
    try:
        # Each rejection is checked against a specific guard, not merely any process failure.
        start("implicit",{**failure_env,"PUBBURI_JPA_REHEARSAL":"0"},"jpa_rehearsal_explicit_fixture_required")
        start("auto-ddl",{**failure_env,"SPRING_JPA_HIBERNATE_DDL_AUTO":"create"},"jpa_rehearsal_schema_policy_rejected")
        start("wrong-marker",{**failure_env,"PUBBURI_PG_RUN_TOKEN":"authored-wrong-marker"},"jpa_rehearsal_fixture_or_schema_rejected")
        start("wrong-source",{**failure_env,"PUBBURI_JPA_SOURCE_ID":"00000000-0000-4000-8000-000000000000"},"jpa_rehearsal_fixture_or_schema_rejected")
        first,base=start("normal");anonymous=client();admin=client();ordinary=client()
        expect(request(anonymous,base,"GET","/api/markets")[0],200)
        payload={"name":"Authored packaged JPA market","latitude":37.123456789,"longitude":127.987654321,"image":"authored.png"}
        expect(request(anonymous,base,"POST","/api/admin/markets",payload)[0],401)
        login(ordinary,base,"USER");expect(request(ordinary,base,"POST","/api/admin/markets",payload)[0],403)
        login(admin,base,"ADMIN");status,created=request(admin,base,"POST","/api/admin/markets",payload);expect(status,201);market_id=created["data"]["id"]
        if market_id<int(environ.get("PUBBURI_JPA_EXPECTED_MARKET_NEXT","1")):raise ValueError("jpa_runtime_identity_reused")
        if set(created["data"])!={"id","name","latitude","longitude","image"}:raise ValueError("jpa_runtime_market_shape_changed")
        payload["name"]="Authored packaged edited";expect(request(admin,base,"PUT","/api/admin/markets/"+str(market_id),payload)[0],200)
        expect(request(admin,base,"GET","/api/products")[0],404);expect(request(admin,base,"GET","/api/orders")[0],404)
        status,docs=request(admin,base,"GET","/v3/api-docs");expect(status,200)
        if set(docs["paths"])!={"/api/auth/login","/api/auth/logout","/api/auth/me","/api/markets","/api/markets/{id}","/api/admin/markets","/api/admin/markets/{id}"}:raise ValueError("jpa_runtime_route_scope_changed")
        stop(first)
        second,base=start("restart");status,persisted=request(client(),base,"GET","/api/markets/"+str(market_id));expect(status,200)
        if persisted["data"]["name"]!=payload["name"]:raise ValueError("jpa_runtime_restart_lost_data")
        expect(request(admin,base,"GET","/api/auth/me")[0],401)  # In-memory sessions are not persisted across restart.
        login(admin,base,"ADMIN");expect(request(admin,base,"DELETE","/api/admin/markets/"+str(market_id))[0],200)
        expect(request(admin,base,"GET","/api/markets/"+str(market_id))[0],404)
        expect(request(admin,base,"POST","/api/auth/logout")[0],200);expect(request(admin,base,"GET","/api/auth/me")[0],401)
        state["phase"]="passed";save()
        return {"status":"passed","provider":"JPA_POSTGRESQL_REHEARSAL","legacy_runtime_dependencies":False,"registered_api_paths":7,"restart_data_preserved":True,"session_relogin_after_restart":True,"full_cutover":False}
    finally:
        cleanup_failed=False
        try:cleanup_owned(processes,stop)
        except Exception:cleanup_failed=True
        state["all_owned_processes_stopped"]=all(p.poll() is not None for p,_ in processes)
        try:
            save()
            if state["all_owned_processes_stopped"]:immutable.unlink(missing_ok=True)
        except Exception:cleanup_failed=True
        if cleanup_failed or not state["all_owned_processes_stopped"]:raise ValueError("jpa_runtime_cleanup_failed")


def main():
    try:
        report=run(os.environ);target=ROOT/"target";target.mkdir(exist_ok=True);(target/"jpa-rehearsal-report.json").write_text(json.dumps(report)+"\n");print(json.dumps(report));return 0
    except Exception:
        print(json.dumps({"status":"failed","error_code":"jpa_rehearsal_smoke_rejected"}));return 2


if __name__=="__main__":raise SystemExit(main())

#!/usr/bin/env python3
"""Run JPA only in newly prepared import targets; called from the owned source CI lifecycle."""
import json
import os
from pathlib import Path
import secrets
import re
import subprocess
import signal

from postgresql_schema import ROOT, verify_owned_postgres


def command(args,env,log):
    with log.open("w") as output:
        process=subprocess.Popen(args,env=env,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
        try:
            code=process.wait(timeout=180)
        except subprocess.TimeoutExpired:
            # wait(timeout) has not reaped this leader: its PID/session identity cannot be reused.
            # Kill the entire owned session before reaping, including Java children of Python/Maven.
            try:os.killpg(process.pid,signal.SIGKILL)
            except ProcessLookupError:pass
            process.wait(timeout=5)
            raise ValueError("jpa_ci_process_deadline") from None
    if code:raise ValueError("jpa_ci_rehearsal_failed")

def run(environ,snapshot,directory):
    if environ.get("PUBBURI_JPA_CI")!="1":raise ValueError("jpa_ci_explicit_rehearsal_required")
    verify_owned_postgres(environ)
    metadata=json.loads((snapshot/"snapshot.json").read_text());receipt=json.loads((snapshot/"receipt.json").read_text())
    roles={"PUBBURI_JPA_ADMIN_ID":"ci_jpa_"+secrets.token_hex(8)+"_admin","PUBBURI_JPA_USER_ID":"ci_jpa_"+secrets.token_hex(8)+"_member",
           "PUBBURI_JPA_ADMIN_PASSWORD":secrets.token_hex(24),"PUBBURI_JPA_USER_PASSWORD":secrets.token_hex(24)}
    normal=None;failure=None
    for purpose in ("JPA","JPA_FAILURE"):
        database=environ["PUBBURI_PG_"+purpose+"_DB_NAME"];token=environ["PUBBURI_PG_"+purpose+"_RUN_TOKEN"]
        env=dict(environ,**roles,PUBBURI_PG_DB_NAME=database,PUBBURI_PG_RUN_TOKEN=token,PUBBURI_PG_URL=environ["PUBBURI_PG_URL"].rsplit("/",1)[0]+"/"+database,
            PUBBURI_SOURCE_SNAPSHOT_DIR=str(snapshot),PUBBURI_PG_MUTATING="1",PUBBURI_PG_IMPORT_WRITES_STOPPED="1",PUBBURI_PG_IMPORT_SHA256=receipt["sha256"],
            PUBBURI_PG_IMPORT_SOURCE_ID=metadata["sourceId"],PUBBURI_PG_IMPORT_WINDOW_UUID=metadata["window"],PUBBURI_JPA_REHEARSAL="1",PUBBURI_JPA_SOURCE_ID=metadata["sourceId"],
            PUBBURI_JPA_EXPECTED_MARKET_NEXT=metadata["sourceNext"]["t_market"],PUBBURI_JPA_PORT="0")
        verify_owned_postgres(env)
        command(["python3","scripts/postgresql_schema.py","migrate"],env,directory/(purpose+"-migrate.log"))
        command(["python3","scripts/postgresql_import.py","import-source"],env,directory/(purpose+"-import.log"))
        runtime=environ["PUBBURI_PG_JPA_RUNTIME_USER"]
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,50}",runtime) or runtime==env["PUBBURI_PG_USER"]:raise ValueError("jpa_ci_runtime_role_rejected")
        grants=("GRANT USAGE ON SCHEMA pubburi TO "+runtime+"; GRANT SELECT,INSERT,UPDATE,DELETE ON pubburi.t_user,pubburi.t_market TO "+runtime+"; "
                "GRANT USAGE ON SEQUENCE pubburi.t_market_id_seq TO "+runtime+"; GRANT SELECT ON pubburi.flyway_schema_history,pubburi.migration_source_receipt,pubburi.t_product_search_relay_target TO "+runtime+"; "
                "GRANT SELECT(user_id),UPDATE(order_table) ON pubburi.t_order TO "+runtime+";")
        control=dict(env,PGPASSWORD=env["PUBBURI_PG_PASSWORD"],PGSSLMODE="verify-full",PGSSLROOTCERT="/tmp/pubburi-pg-ci-tls/ca.pem")
        result=subprocess.run(["docker","exec","-i","-e","PGPASSWORD","-e","PGSSLMODE","-e","PGSSLROOTCERT",environ["PUBBURI_PG_CONTAINER_ID"],
            "psql","--no-psqlrc","--no-password","-h","127.0.0.1","-U",env["PUBBURI_PG_USER"],"-d",database,"-v","ON_ERROR_STOP=1","-At"],
            input=grants,capture_output=True,text=True,env=control,timeout=15)
        if result.returncode:raise ValueError("jpa_ci_runtime_grants_failed")
        env.update(PUBBURI_JPA_CONTROL_USER=env["PUBBURI_PG_USER"],PUBBURI_JPA_CONTROL_PASSWORD=env["PUBBURI_PG_PASSWORD"],
                   PUBBURI_PG_USER=runtime,PUBBURI_PG_PASSWORD=environ["PUBBURI_PG_JPA_RUNTIME_PASSWORD"])
        command([str(ROOT/"mvnw"),"--batch-mode","--no-transfer-progress","-f","tools/jpa-rehearsal/pom.xml","-Dtest=JpaFixturePreparationTests","test"],
                dict(env,PUBBURI_JPA_PREPARE_FIXTURE="1"),directory/(purpose+"-fixture.log"))
        if purpose=="JPA":normal=env
        else:failure=env
    normal.update(PUBBURI_JPA_FAILURE_DB_NAME=failure["PUBBURI_PG_DB_NAME"],PUBBURI_JPA_FAILURE_RUN_TOKEN=failure["PUBBURI_PG_RUN_TOKEN"])
    command([str(ROOT/"mvnw"),"--batch-mode","--no-transfer-progress","-f","tools/jpa-rehearsal/pom.xml","verify"],
            dict(normal,PUBBURI_JPA_INTEGRATION="1"),directory/"jpa-http.log")
    command(["python3","scripts/jpa_smoke.py"],dict(normal,PUBBURI_JPA_SMOKE_MUTATING="1"),directory/"jpa-packaged.log")
    return True

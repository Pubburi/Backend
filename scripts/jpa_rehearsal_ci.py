#!/usr/bin/env python3
"""Run JPA only in newly prepared import targets; called from the owned source CI lifecycle."""
import json
import os
from pathlib import Path
import secrets
import re
import subprocess
import signal
import select
import socket
from types import SimpleNamespace
import sys
import time

from postgresql_schema import ROOT, verify_owned_postgres
from jpa_smoke import cleanup_on_termination,defer_termination


# The supervisor holds its session identity until the parent cleans the complete group.
# Even after an abruptly terminated command, the parent never reaps this leader before killpg.
SUPERVISOR="""
import os,subprocess,sys
fd=int(sys.argv[1])
try:
    code=subprocess.Popen(sys.argv[2:]).wait()
    os.write(fd,(str(code)+'\\n').encode())
finally:os.close(fd)
sys.stdin.buffer.read(1)
"""


def command(args,env,log,budget=180):
    if not 0<budget<=180:raise ValueError("jpa_ci_process_budget_rejected")
    read,write=os.pipe()
    try:
        with cleanup_on_termination(),log.open("w") as output:
            process=None
            try:
                with defer_termination():
                    process=subprocess.Popen([sys.executable,"-c",SUPERVISOR,str(write),*args],env=env,cwd=ROOT,stdin=subprocess.PIPE,
                        stdout=output,stderr=subprocess.STDOUT,start_new_session=True,pass_fds=(write,))
                    os.close(write);write=None
                end=time.monotonic()+budget;status=bytearray()
                while b"\n" not in status:
                    remaining=end-time.monotonic()
                    if remaining<=0:raise ValueError("jpa_ci_process_deadline")
                    if not select.select([read],[],[],remaining)[0]:raise ValueError("jpa_ci_process_deadline")
                    chunk=os.read(read,32-len(status))
                    if not chunk or len(status)+len(chunk)>=32:raise ValueError("jpa_ci_supervisor_rejected")
                    status.extend(chunk)
                if not re.fullmatch(rb"-?[0-9]+\n",status):raise ValueError("jpa_ci_supervisor_rejected")
                code=int(status)
            finally:
                # The leader is still held/unreaped, so this PGID cannot belong to another job.
                # Include normal/failed/signalled command exits and parent SIGTERM in cleanup.
                if process is not None:
                    with defer_termination():
                        try:
                            try:os.killpg(process.pid,signal.SIGKILL)
                            except ProcessLookupError:pass
                        finally:
                            process.wait(timeout=5)
                            process.stdin.close()
    finally:
        os.close(read)
        if write is not None:os.close(write)
    if code:raise ValueError("jpa_ci_rehearsal_failed")

def local_release(environ, cutover_environment):
    import local_stack
    import postgresql_local as pg
    if environ.get('PUBBURI_JPA_CI') != '1':
        pg.reject()
    name = 'pg-local-ci-' + secrets.token_hex(16)
    directory = local_stack.STATE / name
    directory.mkdir(mode=0o700)
    # This validation must never publish over the user's real local default.
    local = SimpleNamespace(**vars(local_stack))
    local.STATE = directory
    local.operation_lock = lambda: local_stack.operation_lock(directory)
    release = directory / 'release'
    env = {key: environ[key] for key in pg.REQUIRED if not key.endswith('_PORT')}
    with socket.socket() as one, socket.socket() as two, socket.socket() as three:
        for listener, key in zip((one,two,three), ('PUBBURI_SERVER_PORT','PUBBURI_FRONTEND_PORT','PUBBURI_RECOMMENDATION_PORT')):
            listener.bind(('127.0.0.1',0))
            env[key] = str(listener.getsockname()[1])
    config = directory / 'config.json'
    pg.write_json(config, {'schema_version':1,'environment':env})
    import postgresql_import
    cutover_config = directory / 'cutover.json'
    pg.write_json(cutover_config, {'schema_version':1,'environment':{
        key:cutover_environment[key] for key in postgresql_import.CUTOVER_REQUIRED}})
    # Run in this process so TERM/KeyboardInterrupt unwinds the launcher's
    # owned process-group cleanup, rather than killing a nested CLI on timeout.
    with cleanup_on_termination():
        pg.package(local, release)
        pg.start(local, SimpleNamespace(pg_config=config, release_dir=release,
            cutover_config=cutover_config, backend_only=True, command='activate-pg'))
        # A valid sibling copy shares the source UUID but has its own marker.
        selector = directory / pg.DEFAULT_SELECTION
        selected_bytes = selector.read_bytes()
        sibling_name = environ['PUBBURI_JPA_FAILURE_DB_NAME']
        sibling = dict(env, PUBBURI_PG_DB_NAME=sibling_name,
            PUBBURI_PG_URL=env['PUBBURI_PG_URL'].rsplit('/', 1)[0] + '/' + sibling_name,
            PUBBURI_PG_RUN_TOKEN=environ['PUBBURI_JPA_FAILURE_RUN_TOKEN'])
        sibling_config = directory / 'sibling.json'
        pg.write_json(sibling_config, {'schema_version': 1, 'environment': sibling})
        try:
            pg.start(local, SimpleNamespace(pg_config=sibling_config, release_dir=release,
                backend_only=True, command='activate-pg'))
        except RuntimeError as rejected:
            if 'cannot replace the selected PostgreSQL database' not in str(rejected):
                raise
        else:
            raise ValueError('jpa_ci_sibling_activation_accepted')
        if selector.read_bytes() != selected_bytes:
            raise ValueError('jpa_ci_existing_selection_changed')
        # Exercise the developer launcher route in a separate owner process. Its
        # TERM handler unwinds the launcher's child sessions before it exits.
        cli = """import signal,sys
from pathlib import Path
sys.path.insert(0,'scripts')
import local_stack as local
local.STATE=Path(sys.argv[1])
signal.signal(signal.SIGTERM,local.interrupted)
sys.argv=['local_stack.py',*sys.argv[2:]]
try:local.main()
except KeyboardInterrupt:pass
"""
        child_env = pg.base_environment()
        args = [sys.executable,'-B','-c',cli,str(directory)]
        log_path = directory/'selected-up.log'
        with log_path.open('x') as log:
            log_path.chmod(0o600)
            with defer_termination():
                up = subprocess.Popen([*args,'dev'],cwd=ROOT,env=child_env,stdout=log,
                    stderr=subprocess.STDOUT,start_new_session=True)
            try:
                deadline=time.monotonic()+90
                while 'PostgreSQL applications ready' not in log_path.read_text():
                    if up.poll() is not None or time.monotonic()>=deadline:
                        raise ValueError('jpa_ci_selected_up_failed')
                    time.sleep(.1)
                # A live up holds the exclusive lock; status must still observe it.
                command([*args,'status','--backend-only'],child_env,directory/'selected-status.log',budget=30)
            finally:
                local_stack.stop(up)
        if up.returncode!=0 or (directory/'local-stack-lock').exists():
            raise ValueError('jpa_ci_selected_cleanup_failed')
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            probe.bind(('127.0.0.1',int(env['PUBBURI_SERVER_PORT'])))
    print('Owned PostgreSQL activation and selected-default restart passed; database retained.')
    return {'state':str(directory), 'config':str(config), 'release':str(release), 'environment':env}



def runtime_grants(runtime):
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,50}",runtime):
        raise ValueError("jpa_ci_runtime_role_rejected")
    return ("GRANT USAGE ON SCHEMA pubburi TO "+runtime+"; GRANT SELECT,INSERT,UPDATE,DELETE ON pubburi.t_user,pubburi.t_market TO "+runtime+"; "
            "GRANT SELECT,INSERT,UPDATE,DELETE ON pubburi.t_product,pubburi.t_product_feature TO "+runtime+"; "
            "GRANT SELECT,INSERT,DELETE ON pubburi.t_product_feature_tag TO "+runtime+"; "
            "GRANT UPDATE(tag) ON pubburi.t_product_feature_tag TO "+runtime+"; "
            "GRANT SELECT,INSERT,UPDATE ON pubburi.t_product_search_version TO "+runtime+"; "
            "GRANT SELECT,INSERT ON pubburi.t_product_search_outbox,pubburi.t_product_search_delivery TO "+runtime+"; "
            "GRANT USAGE ON SEQUENCE pubburi.t_product_id_seq,pubburi.t_product_search_outbox_event_id_seq TO "+runtime+"; "
            "GRANT USAGE ON SEQUENCE pubburi.t_market_id_seq TO "+runtime+"; GRANT SELECT ON pubburi.flyway_schema_history,pubburi.migration_source_receipt,pubburi.t_product_search_relay_target TO "+runtime+"; "
            "GRANT SELECT,INSERT,DELETE ON pubburi.t_order,pubburi.t_order_detail TO "+runtime+"; "
            "GRANT UPDATE(completed,total_price,order_table) ON pubburi.t_order TO "+runtime+"; "
            "GRANT SELECT,INSERT ON pubburi.t_stamp TO "+runtime+"; "
            "GRANT SELECT,INSERT,DELETE ON pubburi.t_comment TO "+runtime+"; "
            "GRANT UPDATE(rating,comment) ON pubburi.t_comment TO "+runtime+"; "
            "GRANT SELECT,INSERT,DELETE ON pubburi.t_comment_taste_tag TO "+runtime+"; "
            "GRANT USAGE ON SEQUENCE pubburi.t_comment_id_seq TO "+runtime+"; "
            "GRANT SELECT,INSERT,DELETE ON pubburi.t_user_favorite TO "+runtime+"; "
            "GRANT SELECT,INSERT,DELETE ON pubburi.t_user_recommendation_feedback TO "+runtime+"; "
            "GRANT UPDATE(feedback) ON pubburi.t_user_recommendation_feedback TO "+runtime+"; "
            "GRANT SELECT,INSERT,DELETE ON pubburi.t_user_notebook TO "+runtime+"; "
            "GRANT UPDATE(experience,rating,taste_tags,aroma_tags,memo,version,updated_at) ON pubburi.t_user_notebook TO "+runtime+"; "
            "GRANT SELECT,INSERT ON pubburi.t_user_preference TO "+runtime+"; "
            "GRANT UPDATE(survey_status,categories_known,taste_known,aroma_known,target_abv_percent,min_price_krw,max_price_krw,min_abv_percent,max_abv_percent) ON pubburi.t_user_preference TO "+runtime+"; "
            "GRANT SELECT,INSERT,DELETE ON pubburi.t_user_preference_value TO "+runtime+"; "
            "GRANT USAGE ON SEQUENCE pubburi.t_order_o_id_seq,pubburi.t_order_detail_d_id_seq,pubburi.t_stamp_id_seq TO "+runtime+";")


def run(environ,snapshot,directory,*,recovery_control=None):
    if environ.get("PUBBURI_JPA_CI")!="1":raise ValueError("jpa_ci_explicit_rehearsal_required")
    verify_owned_postgres(environ)
    metadata=json.loads((snapshot/"snapshot.json").read_text());receipt=json.loads((snapshot/"receipt.json").read_text())
    roles={"PUBBURI_JPA_ADMIN_ID":"ci_jpa_"+secrets.token_hex(8)+"_admin","PUBBURI_JPA_USER_ID":"ci_jpa_"+secrets.token_hex(8)+"_member",
           "PUBBURI_JPA_ADMIN_PASSWORD":secrets.token_hex(24),"PUBBURI_JPA_USER_PASSWORD":secrets.token_hex(24)}
    normal=None;failure=None;recommendation=None;relay_business=None
    purposes=["JPA","JPA_FAILURE","JPA_RECOMMENDATION"]
    if environ.get("PUBBURI_PG_RELAY_CI")=="1":purposes.append("RELAY_BUSINESS")
    for purpose in purposes:
        database=environ["PUBBURI_PG_"+purpose+"_DB_NAME"];token=environ["PUBBURI_PG_"+purpose+"_RUN_TOKEN"]
        env=dict(environ,**roles,PUBBURI_PG_DB_NAME=database,PUBBURI_PG_RUN_TOKEN=token,PUBBURI_PG_URL=environ["PUBBURI_PG_URL"].rsplit("/",1)[0]+"/"+database,
            PUBBURI_SOURCE_SNAPSHOT_DIR=str(snapshot),PUBBURI_PG_MUTATING="1",PUBBURI_PG_IMPORT_WRITES_STOPPED="1",PUBBURI_PG_IMPORT_SHA256=receipt["sha256"],
            PUBBURI_PG_IMPORT_SOURCE_ID=metadata["sourceId"],PUBBURI_PG_IMPORT_WINDOW_UUID=metadata["window"],PUBBURI_JPA_REHEARSAL="1",PUBBURI_JPA_SOURCE_ID=metadata["sourceId"],
            PUBBURI_JPA_EXPECTED_MARKET_NEXT=metadata["sourceNext"]["t_market"],
            PUBBURI_JPA_EXPECTED_COMMENT_NEXT=metadata["sourceNext"]["t_comment"],PUBBURI_JPA_PORT="0")
        verify_owned_postgres(env)
        command(["python3","scripts/postgresql_schema.py","migrate"],env,directory/(purpose+"-migrate.log"))
        command(["python3","scripts/postgresql_import.py","import-source"],env,directory/(purpose+"-import.log"))
        runtime=environ["PUBBURI_PG_JPA_RUNTIME_USER"]
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,50}",runtime) or runtime==env["PUBBURI_PG_USER"]:raise ValueError("jpa_ci_runtime_role_rejected")
        grants=runtime_grants(runtime)
        control=dict(env,PGPASSWORD=env["PUBBURI_PG_PASSWORD"],PGSSLMODE="verify-full",PGSSLROOTCERT="/tmp/pubburi-pg-ci-tls/ca.pem")
        result=subprocess.run(["docker","exec","-i","-e","PGPASSWORD","-e","PGSSLMODE","-e","PGSSLROOTCERT",environ["PUBBURI_PG_CONTAINER_ID"],
            "psql","--no-psqlrc","--no-password","-h","127.0.0.1","-U",env["PUBBURI_PG_USER"],"-d",database,"-v","ON_ERROR_STOP=1","-At"],
            input=grants,capture_output=True,text=True,env=control,timeout=15)
        if result.returncode:raise ValueError("jpa_ci_runtime_grants_failed")
        env.update(PUBBURI_JPA_CONTROL_USER=env["PUBBURI_PG_USER"],PUBBURI_JPA_CONTROL_PASSWORD=env["PUBBURI_PG_PASSWORD"],
                   PUBBURI_PG_USER=runtime,PUBBURI_PG_PASSWORD=environ["PUBBURI_PG_JPA_RUNTIME_PASSWORD"])
        if purpose=="JPA":
            # The first selector may only certify the untouched source copy.
            # Later fixture accounts/business writes belong to PG recovery, not initial cutover.
            cutover=dict(env,PUBBURI_PG_USER=env['PUBBURI_JPA_CONTROL_USER'],PUBBURI_PG_PASSWORD=env['PUBBURI_JPA_CONTROL_PASSWORD'])
            env.update(PUBBURI_JPA_FAILURE_DB_NAME=environ['PUBBURI_PG_JPA_FAILURE_DB_NAME'],
                       PUBBURI_JPA_FAILURE_RUN_TOKEN=environ['PUBBURI_PG_JPA_FAILURE_RUN_TOKEN'])
            local_release(env,cutover)
        command([str(ROOT/"mvnw"),"--batch-mode","--no-transfer-progress","-Dtest=JpaFixturePreparationTests","test"],
                dict(env,PUBBURI_JPA_PREPARE_FIXTURE="1"),directory/(purpose+"-fixture.log"))
        if purpose=="JPA":normal=env
        elif purpose=="JPA_FAILURE":failure=env
        elif purpose=="JPA_RECOMMENDATION":recommendation=env
        else:relay_business=env
    scratch=dict(recommendation,PUBBURI_JPA_RECOMMENDATION_DB_NAME=recommendation["PUBBURI_PG_DB_NAME"])
    command([str(ROOT/"mvnw"),"--batch-mode","--no-transfer-progress","-Dtest=JpaRecommendationPreparationTests","test"],
            dict(scratch,PUBBURI_JPA_PREPARE_RECOMMENDATION="1"),directory/"JPA_RECOMMENDATION-empty-catalog.log")
    normal.update(PUBBURI_JPA_FAILURE_DB_NAME=failure["PUBBURI_PG_DB_NAME"],PUBBURI_JPA_FAILURE_RUN_TOKEN=failure["PUBBURI_PG_RUN_TOKEN"],
                  PUBBURI_JPA_RECOMMENDATION_DB_NAME=recommendation["PUBBURI_PG_DB_NAME"],PUBBURI_JPA_RECOMMENDATION_RUN_TOKEN=recommendation["PUBBURI_PG_RUN_TOKEN"])
    command([str(ROOT/"mvnw"),"--batch-mode","--no-transfer-progress","verify"],
            dict(normal,PUBBURI_JPA_INTEGRATION="1"),directory/"jpa-http.log")
    command(["python3","scripts/jpa_smoke.py"],dict(normal,PUBBURI_JPA_SMOKE_MUTATING="1"),directory/"jpa-packaged.log")
    from postgresql_recovery_ci import run as run_recovery
    run_recovery(environ,normal,recovery_control)
    if environ.get("PUBBURI_PG_RELAY_CI")=="1":
        from prepare_postgresql_relay_ci import prepare
        prepare(environ,relay_business,directory)
    return True

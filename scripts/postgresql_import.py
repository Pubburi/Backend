#!/usr/bin/env python3
"""Import one approved typed artifact into an owned empty PostgreSQL rehearsal target."""
import argparse
import json
import os
from pathlib import Path
import subprocess

from mysql_source_snapshot import ROOT, private_path, verify_owned_source, child_environment as source_environment
from postgresql_schema import verify_owned_postgres

CUTOVER_REQUIRED = frozenset('PUBBURI_SOURCE_'+key for key in (
    'URL','DB_NAME','USER','PASSWORD','TRUSTSTORE','TRUSTSTORE_PASSWORD','RUN_TOKEN','WINDOW_UUID',
    'WRITES_STOPPED','DATETIME_ZONE','CONTAINER_ID','VALIDATION_LABEL','SNAPSHOT_DIR')) | frozenset(
    'PUBBURI_PG_'+key for key in ('URL','DB_NAME','USER','PASSWORD','RUN_TOKEN','CA','CONTAINER_ID',
    'VALIDATION_LABEL','IMPORT_WRITES_STOPPED','IMPORT_SHA256','IMPORT_SOURCE_ID','IMPORT_WINDOW_UUID'))


def child_environment(environ, cutover=False):
    keys={"PATH","JAVA_HOME","HOME","TMPDIR","TMP","TEMP","LANG","LC_ALL","LC_CTYPE","PUBBURI_SOURCE_SNAPSHOT_DIR"}
    keys.update("PUBBURI_PG_"+suffix for suffix in ("URL","DB_NAME","USER","PASSWORD","RUN_TOKEN","CA","MUTATING",
                "IMPORT_WRITES_STOPPED","IMPORT_SHA256","IMPORT_SOURCE_ID","IMPORT_WINDOW_UUID"))
    child={key:value for key,value in environ.items() if key in keys}
    if cutover:
        child.update(source_environment(environ, True))
    return child


def execute(environ, command):
    if (command=="import-source" and environ.get("PUBBURI_PG_MUTATING")!="1") or environ.get("PUBBURI_PG_IMPORT_WRITES_STOPPED")!="1":
        raise ValueError("postgresql_import_explicit_window_required")
    private_path(environ,True)
    verify_owned_postgres(environ)
    if command=="verify-cutover":
        verify_owned_source(environ)
    target=ROOT/"tools/postgresql-migration/target"
    if not (target/"classes/com/pubburi/migration/SourceImportTool.class").is_file():
        raise ValueError("postgresql_import_build_required")
    env=child_environment(environ,command=="verify-cutover")
    java=str(Path(env["JAVA_HOME"])/"bin/java") if env.get("JAVA_HOME") else "java"
    runtime=str(target/"classes")+os.pathsep+str(target/"dependency/*")
    return subprocess.run([java,"-cp",runtime,"com.pubburi.migration.SourceImportTool",command],
                          cwd=ROOT,env=env,timeout=120,capture_output=True,text=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=("import-source","verify-import","verify-cutover"))
    args=parser.parse_args()
    try:
        result=execute(os.environ,args.command)
        print(result.stdout if result.returncode==0 else result.stderr,end="")
        return 0 if result.returncode==0 else 2
    except subprocess.TimeoutExpired:
        # The child was killed/waited by subprocess.run, but COMMIT could already have reached the server.
        code="postgresql_import_commit_status_unknown" if args.command=="import-source" else "postgresql_import_verification_timed_out"
        print(json.dumps({"status":"failed","error_code":code}));return 2
    except Exception:
        print(json.dumps({"status":"failed","error_code":"postgresql_source_import_fixture_rejected"}));return 2


if __name__=="__main__":raise SystemExit(main())

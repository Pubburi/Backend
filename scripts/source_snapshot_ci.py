#!/usr/bin/env python3
"""Create, verify and remove one owned TLS MySQL source rehearsal; never adopt a database."""
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time
import uuid

from mysql_source_snapshot import ROOT, verify_owned_source


def run(environ):
    label = environ.get("PUBBURI_SOURCE_CI_RUN", "")
    if not re.fullmatch(r"[a-z0-9-]{5,100}", label):
        raise ValueError("source_ci_explicit_run_required")
    parent = ROOT / ".pubburi"
    parent.mkdir(mode=0o700, exist_ok=True)
    directory = parent / ("worker-source-ci-" + str(uuid.uuid4()))
    directory.mkdir(mode=0o700)  # No adoption or overwrite of TLS/credentials/artifacts.
    root_password, password, control_password, store_password = [secrets.token_hex(24) for _ in range(4)]
    database, reader, control = "source_snapshot_ci", "source_reader", "source_control"
    token, window = secrets.token_hex(24), str(uuid.uuid4())
    cid = None
    cleanup_error = None

    def command(args, **kw):
        return subprocess.run(args, capture_output=True, check=True, timeout=kw.pop("timeout", 30), **kw)

    def openssl(*args):
        command(["openssl", *args])

    try:
        openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2", "-subj", "/CN=Pubburi disposable source CA",
                "-keyout", str(directory / "ca.key"), "-out", str(directory / "ca.pem"))
        openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=127.0.0.1",
                "-keyout", str(directory / "server.key"), "-out", str(directory / "server.csr"))
        (directory / "server.ext").write_text("subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\n")
        openssl("x509", "-req", "-sha256", "-days", "2", "-in", str(directory / "server.csr"), "-CA", str(directory / "ca.pem"),
                "-CAkey", str(directory / "ca.key"), "-CAserial", str(directory / "ca.srl"), "-CAcreateserial",
                "-extfile", str(directory / "server.ext"), "-out", str(directory / "server.pem"))
        child = dict(environ, PUBBURI_SOURCE_STOREPASS=store_password)
        java_home = Path(environ["JAVA_HOME"]) if environ.get("JAVA_HOME") else None
        keytool = str(java_home / "bin/keytool") if java_home else "keytool"
        command([keytool, "-importcert", "-noprompt", "-alias", "pubburi-source", "-file", str(directory / "ca.pem"), "-keystore", str(directory / "truststore.p12"),
                 "-storetype", "PKCS12", "-storepass:env", "PUBBURI_SOURCE_STOREPASS"], env=child)
        for file in directory.iterdir():
            file.chmod(0o600)
        # The original API/relay service and its generated /var/lib/mysql/ca.pem are untouched.
        entry = ("mkdir -p /tmp/pubburi-source-tls; cp /source-certs/ca.pem /source-certs/server.pem /source-certs/server.key /tmp/pubburi-source-tls/; "
                 "chown -R mysql:mysql /tmp/pubburi-source-tls; chmod 700 /tmp/pubburi-source-tls; chmod 600 /tmp/pubburi-source-tls/*; "
                 "exec docker-entrypoint.sh mysqld --ssl-ca=/tmp/pubburi-source-tls/ca.pem --ssl-cert=/tmp/pubburi-source-tls/server.pem "
                 "--ssl-key=/tmp/pubburi-source-tls/server.key --require-secure-transport=ON --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci")
        created = command(["docker", "create", "--pull=never", "--label", "pubburi.validation=" + label, "--tmpfs", "/var/lib/mysql:rw,nosuid,size=1g",
                 "-e", "MYSQL_ROOT_PASSWORD", "-p", "127.0.0.1::3306", "--mount", "type=bind,src=" + str(directory) + ",dst=/source-certs,readonly",
                 "mysql:8.4", "/bin/bash", "-ec", entry], env=dict(environ, MYSQL_ROOT_PASSWORD=root_password), text=True)
        cid = created.stdout.strip()
        if not re.fullmatch(r"[a-f0-9]{64}", cid):
            raise ValueError("source_ci_container_identity_invalid")
        checkpoint = directory / "owned.json"
        checkpoint.write_text(json.dumps({"container_id": cid, "label": label})); checkpoint.chmod(0o600)
        command(["docker", "start", cid])
        info = json.loads(command(["docker", "inspect", "--type", "container", cid], text=True).stdout)[0]
        ports = info["NetworkSettings"]["Ports"]["3306/tcp"]
        if info["Id"] != cid or info["Config"]["Labels"].get("pubburi.validation") != label or len(ports) != 1 or ports[0]["HostIp"] != "127.0.0.1":
            raise ValueError("source_ci_publication_rejected")
        source = dict(environ, PUBBURI_SOURCE_URL="jdbc:mysql://127.0.0.1:" + ports[0]["HostPort"] + "/" + database,
            PUBBURI_SOURCE_DB_NAME=database, PUBBURI_SOURCE_CONTAINER_ID=cid, PUBBURI_SOURCE_VALIDATION_LABEL=label,
            PUBBURI_SOURCE_USER=reader, PUBBURI_SOURCE_PASSWORD=password, PUBBURI_SOURCE_TRUSTSTORE=str(directory / "truststore.p12"),
            PUBBURI_SOURCE_TRUSTSTORE_PASSWORD=store_password, PUBBURI_SOURCE_CA=str(directory / "ca.pem"),
            PUBBURI_SOURCE_RUN_TOKEN=token, PUBBURI_SOURCE_WINDOW_UUID=window, PUBBURI_SOURCE_WRITES_STOPPED="1", PUBBURI_SOURCE_DATETIME_ZONE="UTC",
            PUBBURI_SOURCE_SNAPSHOT_DIR=str(directory / "accepted"), PUBBURI_SOURCE_INTEGRATION="1", PUBBURI_SOURCE_MUTATING="1",
            PUBBURI_SOURCE_CONTROL_USER=control, PUBBURI_SOURCE_CONTROL_PASSWORD=control_password)
        verify_owned_source(source)

        def sql(text, timeout=20):
            return command(["docker", "exec", "-i", "-e", "MYSQL_PWD", cid, "mysql", "--protocol=TCP", "-h127.0.0.1", "-uroot", "--connect-timeout=2",
                     "--ssl-mode=VERIFY_IDENTITY", "--ssl-ca=/tmp/pubburi-source-tls/ca.pem", "--default-character-set=utf8mb4", "--batch", "--skip-column-names"],
                     input=text, text=True, env=dict(environ, MYSQL_PWD=root_password), timeout=timeout).stdout.strip()
        end = time.monotonic() + 100
        while True:
            try:
                if sql("SELECT 1", timeout=5) == "1": break
            except (subprocess.SubprocessError, OSError):
                pass
            if time.monotonic() >= end: raise ValueError("source_ci_tcp_tls_readiness_failed")
            time.sleep(.5)
        if sql("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('mysql','information_schema','performance_schema','sys')") != "0":
            raise ValueError("source_ci_fresh_database_required")
        if not sql("SELECT @@version").startswith("8.4."):
            raise ValueError("source_ci_version_rejected")
        # All following DDL targets only this new owned container/database.
        sql("SET time_zone='+00:00'; CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; USE " + database + ";\n" + (ROOT / "data.sql").read_text(), timeout=40)
        sql("CREATE USER '" + reader + "'@'%' IDENTIFIED BY '" + password + "' REQUIRE SSL; GRANT SELECT, SHOW VIEW ON " + database + ".* TO '" + reader + "'@'%'; "
            "CREATE USER '" + control + "'@'%' IDENTIFIED BY '" + control_password + "' REQUIRE SSL; GRANT SELECT ON " + database + ".* TO '" + control + "'@'%'; "
            "GRANT UPDATE,INSERT,ALTER ON " + database + ".t_product TO '" + control + "'@'%'; GRANT UPDATE ON " + database + ".t_user TO '" + control + "'@'%'; "
            "USE " + database + "; CREATE TABLE pubburi_source_snapshot_guard(token VARCHAR(100) PRIMARY KEY,state VARCHAR(16) NOT NULL,window_uuid CHAR(36) NOT NULL); "
            "INSERT INTO pubburi_source_snapshot_guard VALUES ('" + token + "','QUIESCED','" + window + "'); GRANT UPDATE ON " + database + ".pubburi_source_snapshot_guard TO '" + control + "'@'%';")
        # Test logs may contain private fixture data on failure: private only, never CI artifacts/stdout.
        with (directory / "integration.log").open("w") as log:
            result = subprocess.run([str(ROOT / "mvnw"), "--batch-mode", "--no-transfer-progress", "-f", "tools/postgresql-migration/pom.xml",
                "-Dtest=SourceContractTests,SourceIntegrationTests", "test"], cwd=ROOT, env=source, stdout=log, stderr=subprocess.STDOUT, timeout=120)
        if result.returncode:
            raise ValueError("source_ci_integration_failed")
        # The executable wrapper proves container ownership and strips control credentials before export.
        for operation in ("export-source", "validate-source", "validate-source"):
            command(["python3", "scripts/mysql_source_snapshot.py", operation], env=source, cwd=ROOT, timeout=120)
        duplicate = subprocess.run(["python3", "scripts/mysql_source_snapshot.py", "export-source"], env=source, cwd=ROOT, capture_output=True, timeout=30)
        if duplicate.returncode != 2:
            raise ValueError("source_ci_output_reuse_not_rejected")
        return {"status": "passed", "source_tables": 16, "tls": "VERIFY_IDENTITY", "reader_only": True,
                "full_copy": False, "source_original_touched": False}
    finally:
        # Diagnostic failures never bypass cleanup; never remove a non-owned container.
        if cid:
            try:
                info = json.loads(command(["docker", "inspect", "--type", "container", cid], text=True).stdout)[0]
                if info["Id"] != cid or info["Config"].get("Labels", {}).get("pubburi.validation") != label:
                    raise ValueError("source_ci_cleanup_ownership_rejected")
                command(["docker", "rm", "-f", cid])
            except Exception as error:
                cleanup_error = error
        if cleanup_error is None:
            shutil.rmtree(directory)
        if cleanup_error:
            raise ValueError("source_ci_cleanup_failed") from None


def main():
    try:
        report = run(os.environ)
        report["owned_container_removed"] = True
        target = ROOT / "target"; target.mkdir(exist_ok=True)
        (target / "source-snapshot-report.json").write_text(json.dumps(report) + "\n")
        print(json.dumps(report)); return 0
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and re.fullmatch(r"source_[a-z_]{1,80}", str(error)) else "source_ci_rejected"
        print(json.dumps({"status": "failed", "error_code": code})); return 2


if __name__ == "__main__":
    raise SystemExit(main())

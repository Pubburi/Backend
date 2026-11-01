#!/usr/bin/env python3
"""Bootstrap TLS and a non-superuser on a fresh, explicitly labelled PostgreSQL CI service only."""
import json
import os
from pathlib import Path
import re
import subprocess
import time

from postgresql_schema import ROOT, verify_owned_postgres


def prepare(environ):
    if environ.get("PUBBURI_PG_MUTATING") != "1":
        raise ValueError("postgresql_ci_explicit_preparation_required")
    verify_owned_postgres(environ)
    cid = environ["PUBBURI_PG_CONTAINER_ID"]
    role = environ["PUBBURI_PG_USER"]
    admin = environ["PUBBURI_PG_ADMIN_USER"]
    if not all(re.fullmatch(r"[a-z][a-z0-9_]{0,50}", value) for value in (role, admin)) or role == admin:
        raise ValueError("postgresql_ci_role_rejected")
    if not environ.get("PUBBURI_PG_ADMIN_PASSWORD") or not environ.get("PUBBURI_PG_PASSWORD"):
        raise ValueError("postgresql_ci_credentials_required")
    databases = [(environ["PUBBURI_PG_DB_NAME"], environ["PUBBURI_PG_RUN_TOKEN"])]
    for purpose in ("RECOVERY", "BLOCKED"):
        databases.append((environ["PUBBURI_PG_" + purpose + "_DB_NAME"], environ["PUBBURI_PG_" + purpose + "_RUN_TOKEN"]))
    if len({item[0] for item in databases}) != 3 or any(not re.fullmatch(r"[a-z][a-z0-9_]*_ci", name)
            or not re.fullmatch(r"[A-Za-z0-9_-]{12,100}", token) for name, token in databases):
        raise ValueError("postgresql_ci_databases_rejected")

    def docker(*args, input=None):
        return subprocess.run(["docker", *args], input=input, capture_output=True, text=True, check=True, timeout=30)

    def sql(statement, database="postgres", tls=False):
        child = dict(environ, PGPASSWORD=environ["PUBBURI_PG_ADMIN_PASSWORD"],
                     PGSSLMODE="verify-full" if tls else "disable", PGSSLROOTCERT="/tmp/pubburi-pg-ci-tls/ca.pem")
        return subprocess.run(["docker", "exec", "-i", "-e", "PGPASSWORD", "-e", "PGSSLMODE", "-e", "PGSSLROOTCERT", cid,
            "psql", "--no-psqlrc", "--no-password", "-h", "127.0.0.1", "-U", admin, "-d", database,
            "-v", "ON_ERROR_STOP=1", "-At"], input=statement, capture_output=True, text=True, check=True,
            env=child, timeout=10).stdout.strip()

    # Never reconfigure an existing user database, even when its container happens to carry the label.
    if sql("SELECT datname FROM pg_database WHERE NOT datistemplate ORDER BY datname") != "postgres":
        raise ValueError("postgresql_ci_fresh_cluster_required")
    if sql("SHOW server_version_num") != "180006":
        raise ValueError("postgresql_ci_pinned_version_required")
    directory = ROOT / ".pubburi/postgres-ci-tls"
    public_ca = directory / "ca.pem"
    if Path(environ["PUBBURI_PG_CA"]).resolve() != public_ca.resolve():
        raise ValueError("postgresql_ci_ca_path_rejected")
    directory.mkdir(mode=0o700)  # Existing material is not adopted or overwritten.

    def openssl(*args):
        subprocess.run(["openssl", *args], capture_output=True, text=True, check=True, timeout=20)

    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
            "-subj", "/CN=Pubburi disposable CI CA", "-keyout", str(directory / "ca.key"), "-out", str(public_ca))
    openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=127.0.0.1",
            "-keyout", str(directory / "server.key"), "-out", str(directory / "server.csr"))
    extensions = directory / "server.ext"
    extensions.write_text("subjectAltName=IP:127.0.0.1,IP:::1\nextendedKeyUsage=serverAuth\n")
    openssl("x509", "-req", "-in", str(directory / "server.csr"), "-CA", str(public_ca), "-CAkey", str(directory / "ca.key"),
            "-CAserial", str(directory / "ca.srl"), "-CAcreateserial", "-days", "2", "-sha256",
            "-extfile", str(extensions), "-out", str(directory / "server.pem"))
    for name in ("ca.key", "server.key"):
        (directory / name).chmod(0o600)
    verify_owned_postgres(environ)
    docker("exec", "--user", "0", cid, "mkdir", "-p", "/tmp/pubburi-pg-ci-tls")
    for name in ("ca.pem", "server.pem", "server.key"):
        docker("cp", str(directory / name), cid + ":/tmp/pubburi-pg-ci-tls/" + name)
    docker("exec", "--user", "0", cid, "chown", "-R", "postgres:postgres", "/tmp/pubburi-pg-ci-tls")
    docker("exec", "--user", "0", cid, "chmod", "600", "/tmp/pubburi-pg-ci-tls/server.key")
    docker("exec", "--user", "0", cid, "chmod", "644", "/tmp/pubburi-pg-ci-tls/server.pem", "/tmp/pubburi-pg-ci-tls/ca.pem")
    sql("ALTER SYSTEM SET ssl='on';\nALTER SYSTEM SET ssl_cert_file='/tmp/pubburi-pg-ci-tls/server.pem';\n"
        "ALTER SYSTEM SET ssl_key_file='/tmp/pubburi-pg-ci-tls/server.key';\n")
    docker("exec", "--user", "postgres", "-i", cid, "sh", "-c", 'cat > "$PGDATA/pg_hba.conf"',
           input="local all all trust\nhostnossl all all all reject\nhostssl all all all scram-sha-256\n")
    verify_owned_postgres(environ)
    if sql("SELECT context FROM pg_settings WHERE name='ssl'") != "sighup":
        raise ValueError("postgresql_ci_reloadable_tls_required")
    sql("SELECT pg_reload_conf()")  # Preserve the owned dynamic host publication; no container restart.
    end = time.monotonic() + 60
    while True:
        try:
            if sql("SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()", tls=True) == "t": break
        except (subprocess.SubprocessError, OSError):
            pass
        if time.monotonic() >= end: raise RuntimeError("postgresql_ci_tls_readiness_failed")
        time.sleep(.25)

    def literal(value):
        return "'" + value.replace("'", "''") + "'"

    sql("SET standard_conforming_strings='on';\nSET password_encryption='scram-sha-256';\nCREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
        "NOREPLICATION NOBYPASSRLS PASSWORD " + literal(environ["PUBBURI_PG_PASSWORD"]) + ";", tls=True)
    for database, token in databases:
        sql("CREATE DATABASE " + database + " ENCODING 'UTF8' TEMPLATE template0;\nGRANT CONNECT,CREATE ON DATABASE " + database + " TO " + role + ";", tls=True)
        sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC;\nCREATE TABLE public.pubburi_pg_migration_guard(token VARCHAR(100) PRIMARY KEY);\n"
            "INSERT INTO public.pubburi_pg_migration_guard VALUES (" + literal(token) + ");\n"
            "GRANT SELECT ON public.pubburi_pg_migration_guard TO " + role + ";", database, tls=True)
    # No process needs the CA signing key or CSR after the fresh service has been configured.
    for name in ("ca.key", "server.key", "server.csr", "server.ext", "ca.srl"):
        (directory / name).unlink(missing_ok=True)
    print(json.dumps({"status": "passed", "tls": "verify-full", "databases_created": len(databases), "superuser_migrator": False}))


def main():
    try:
        prepare(os.environ); return 0
    except Exception:
        print(json.dumps({"status": "failed", "error_code": "postgresql_ci_preparation_rejected"})); return 2


if __name__ == "__main__":
    raise SystemExit(main())

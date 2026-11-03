"""Prepare relay fixtures after owned CI's JPA import, without resetting a source or ACK."""
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

from postgresql_schema import ROOT, verify_owned_postgres


def prepare(environ, business, directory):
    if environ.get("PUBBURI_PG_RELAY_CI") != "1":
        raise ValueError("postgresql_relay_ci_explicit_preparation_required")
    role = environ.get("PUBBURI_PG_RELAY_USER", "")
    database = environ.get("PUBBURI_PG_RELAY_DB_NAME", "")
    if (not re.fullmatch(r"[a-z][a-z0-9_]{0,50}", role) or not environ.get("PUBBURI_PG_RELAY_PASSWORD")
            or role in (business["PUBBURI_PG_USER"], business["PUBBURI_JPA_CONTROL_USER"])
            or not re.fullmatch(r"[a-z][a-z0-9_]*_ci", database)
            or database in {value for key, value in environ.items() if key.endswith("DB_NAME") and key != "PUBBURI_PG_RELAY_DB_NAME"}):
        raise ValueError("postgresql_relay_ci_scope_rejected")
    token = environ["PUBBURI_PG_RELAY_RUN_TOKEN"]
    if not re.fullmatch(r"[A-Za-z0-9_-]{12,100}", token):
        raise ValueError("postgresql_relay_ci_marker_rejected")
    path = Path(environ["PUBBURI_PG_RELAY_FIXTURE"])
    path.resolve().relative_to((ROOT / ".pubburi").resolve())
    if path.exists() or path.is_symlink():
        raise ValueError("postgresql_relay_ci_existing_fixture_rejected")
    queue = dict(business, PUBBURI_PG_DB_NAME=database, PUBBURI_PG_RUN_TOKEN=token,
        PUBBURI_PG_URL=business["PUBBURI_PG_URL"].rsplit("/", 1)[0] + "/" + database,
        PUBBURI_PG_USER=business["PUBBURI_JPA_CONTROL_USER"], PUBBURI_PG_PASSWORD=business["PUBBURI_JPA_CONTROL_PASSWORD"])
    verify_owned_postgres(queue); verify_owned_postgres(business)
    from jpa_rehearsal_ci import command
    command(["python3", "scripts/postgresql_schema.py", "migrate"], queue, directory / "relay-queue-migrate.log")

    def control(env, statement):
        verify_owned_postgres(env)
        child = dict(env, PGPASSWORD=business["PUBBURI_JPA_CONTROL_PASSWORD"], PGSSLMODE="verify-full", PGSSLROOTCERT="/tmp/pubburi-pg-ci-tls/ca.pem")
        result = subprocess.run(["docker", "exec", "-i", "-e", "PGPASSWORD", "-e", "PGSSLMODE", "-e", "PGSSLROOTCERT", env["PUBBURI_PG_CONTAINER_ID"],
            "psql", "--no-psqlrc", "--no-password", "-h", "127.0.0.1", "-U", business["PUBBURI_JPA_CONTROL_USER"], "-d", env["PUBBURI_PG_DB_NAME"],
            "-v", "ON_ERROR_STOP=1", "-At"], input=statement, capture_output=True, text=True, env=child, timeout=15)
        if result.returncode: raise ValueError("postgresql_relay_ci_preparation_rejected")

    control(queue, "DO $$ BEGIN IF EXISTS(SELECT FROM pubburi.t_product_search_version) OR EXISTS(SELECT FROM pubburi.t_product_search_outbox) "
        "OR EXISTS(SELECT FROM pubburi.t_product_search_delivery) OR EXISTS(SELECT FROM pubburi.t_product_search_relay_target) "
        "THEN RAISE EXCEPTION 'fresh relay fixture required'; END IF; END $$; "
        "INSERT INTO pubburi.t_product_search_relay_target(singleton_id,source_id) VALUES(1,'" + str(uuid.uuid4()) + "');")
    grants = ("GRANT USAGE ON SCHEMA pubburi TO " + role + "; GRANT SELECT ON pubburi.t_product_search_outbox,pubburi.t_product_search_delivery,pubburi.t_product_search_relay_target TO " + role + "; "
        "GRANT UPDATE(status,claim_token,lease_until,available_at,attempts,delivered_at,last_error) ON pubburi.t_product_search_delivery TO " + role + "; "
        "GRANT UPDATE(fingerprint,identity_json,bound_at) ON pubburi.t_product_search_relay_target TO " + role + ";")
    for env in (queue, business): control(env, grants)
    allow = {"PATH", "JAVA_HOME", "HOME", "LANG", "LC_ALL"}
    allow.update("PUBBURI_PG_" + key for key in ("URL", "DB_NAME", "RUN_TOKEN", "USER", "PASSWORD", "CA", "CONTAINER_ID", "VALIDATION_LABEL"))
    allow.update("PUBBURI_JPA_" + key for key in ("SOURCE_ID", "REHEARSAL", "ADMIN_ID", "ADMIN_PASSWORD"))
    def selected(env):
        return dict({key: value for key, value in env.items() if key in allow}, PUBBURI_PG_RELAY_MUTATING="1",
            PUBBURI_RELAY_USER=role, PUBBURI_RELAY_PASSWORD=environ["PUBBURI_PG_RELAY_PASSWORD"],
            PUBBURI_RELAY_CONTROL_USER=business["PUBBURI_JPA_CONTROL_USER"], PUBBURI_RELAY_CONTROL_PASSWORD=business["PUBBURI_JPA_CONTROL_PASSWORD"])
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
        json.dump({"business": selected(business), "queue": selected(queue)}, output)
    return True

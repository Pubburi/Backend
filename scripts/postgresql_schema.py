#!/usr/bin/env python3
"""Install/validate only an explicitly owned, guarded local PostgreSQL rehearsal database."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]


def verify_owned_postgres(environ):
    raw = environ.get("PUBBURI_PG_URL", "")
    if not raw.startswith("jdbc:postgresql://"):
        raise ValueError("postgresql_explicit_fixture_required")
    url = urlsplit(raw[5:])
    name = environ.get("PUBBURI_PG_DB_NAME", "")
    cid = environ.get("PUBBURI_PG_CONTAINER_ID", "")
    label = environ.get("PUBBURI_PG_VALIDATION_LABEL", "")
    if (url.hostname not in ("127.0.0.1", "::1") or not url.port or url.query or url.fragment or url.username is not None
            or not re.fullmatch(r"[a-z][a-z0-9_]*_ci", name) or url.path != "/" + name
            or not re.fullmatch(r"[a-f0-9]{12,64}", cid) or not re.fullmatch(r"[a-z0-9-]{5,100}", label)):
        raise ValueError("postgresql_explicit_fixture_required")
    result = subprocess.run(["docker", "inspect", "--type", "container", cid], capture_output=True, text=True,
                            check=True, timeout=5)
    parsed = json.loads(result.stdout)
    if len(parsed) != 1:
        raise ValueError("postgresql_container_guard_rejected")
    owned = parsed[0]
    if (not owned["Id"].startswith(cid) or not owned["State"]["Running"]
            or owned["Config"].get("Labels", {}).get("pubburi.validation") != label):
        raise ValueError("postgresql_container_guard_rejected")
    publications = owned["NetworkSettings"]["Ports"].get("5432/tcp") or []
    if not any(item["HostIp"] == url.hostname and int(item["HostPort"]) == url.port for item in publications):
        raise ValueError("postgresql_published_port_guard_rejected")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("migrate", "validate", "reserve-identities"))
    args = parser.parse_args()
    try:
        if args.command != "validate" and os.environ.get("PUBBURI_PG_MUTATING") != "1":
            raise ValueError("postgresql_explicit_mutation_required")
        verify_owned_postgres(os.environ)
        target = ROOT / "tools/postgresql-migration/target"
        if not (target / "classes/com/pubburi/migration/PostgresSchemaTool.class").is_file():
            raise ValueError("postgresql_tool_build_required")
        child_env = dict(os.environ)
        for key in ("PUBBURI_DB_ROOT_PASSWORD", "MYSQL_PWD", "PGPASSWORD", "PUBBURI_PG_ADMIN_USER", "PUBBURI_PG_ADMIN_PASSWORD"):
            child_env.pop(key, None)
        runtime = str(target / "classes") + os.pathsep + str(target / "dependency/*")
        result = subprocess.run(["java", "-cp", runtime, "com.pubburi.migration.PostgresSchemaTool", args.command],
                                cwd=ROOT, env=child_env, timeout=90)
        return 0 if result.returncode == 0 else 2
    except Exception:
        print(json.dumps({"status": "failed", "error_code": "postgresql_schema_fixture_rejected"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

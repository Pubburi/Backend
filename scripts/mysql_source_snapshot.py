#!/usr/bin/env python3
"""Read a guarded local MySQL source into a private, typed migration artifact."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def verify_owned_source(environ):
    raw = environ.get("PUBBURI_SOURCE_URL", "")
    if not raw.startswith("jdbc:mysql://"):
        raise ValueError("source_explicit_fixture_required")
    url = urlsplit(raw[5:])
    name = environ.get("PUBBURI_SOURCE_DB_NAME", "")
    cid = environ.get("PUBBURI_SOURCE_CONTAINER_ID", "")
    label = environ.get("PUBBURI_SOURCE_VALIDATION_LABEL", "")
    if (url.hostname not in ("127.0.0.1", "::1") or not url.port or url.query or url.fragment or url.username is not None
            or not re.fullmatch(r"[a-z][a-z0-9_]*_ci", name) or url.path != "/" + name
            or not re.fullmatch(r"[a-f0-9]{12,64}", cid) or not re.fullmatch(r"[a-z0-9-]{5,100}", label)):
        raise ValueError("source_explicit_fixture_required")
    result = subprocess.run(["docker", "inspect", "--type", "container", cid], capture_output=True,
                            text=True, check=True, timeout=5)
    parsed = json.loads(result.stdout)
    if len(parsed) != 1:
        raise ValueError("source_container_guard_rejected")
    owned = parsed[0]
    if (not owned["Id"].startswith(cid) or not owned["State"]["Running"]
            or owned["Config"].get("Labels", {}).get("pubburi.validation") != label):
        raise ValueError("source_container_guard_rejected")
    ports = owned["NetworkSettings"]["Ports"].get("3306/tcp") or []
    if len(ports) != 1 or ports[0]["HostIp"] != url.hostname or int(ports[0]["HostPort"]) != url.port:
        raise ValueError("source_published_port_guard_rejected")


def private_path(environ, existing):
    requested = Path(environ.get("PUBBURI_SOURCE_SNAPSHOT_DIR", "")).absolute()
    parent = requested.parent.resolve(strict=True)
    ignored = (ROOT / ".pubburi").resolve(strict=True)
    if (not parent.is_relative_to(ignored) or requested.is_symlink()
            or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,79}", requested.name)
            or requested.exists() != existing):
        raise ValueError("source_private_directory_required")
    return requested


def child_environment(environ, exporting):
    allowed = {"PATH", "JAVA_HOME", "HOME", "TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL", "LC_CTYPE", "PUBBURI_SOURCE_SNAPSHOT_DIR"}
    if exporting:
        allowed.update("PUBBURI_SOURCE_" + name for name in ("URL", "DB_NAME", "USER", "PASSWORD", "TRUSTSTORE", "TRUSTSTORE_PASSWORD",
                       "RUN_TOKEN", "WINDOW_UUID", "WRITES_STOPPED", "DATETIME_ZONE"))
    return {key: value for key, value in environ.items() if key in allowed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("export-source", "validate-source"))
    args = parser.parse_args()
    try:
        private_path(os.environ, args.command == "validate-source")
        if args.command == "export-source":
            verify_owned_source(os.environ)
        target = ROOT / "tools/postgresql-migration/target"
        if not (target / "classes/com/pubburi/migration/SourceSnapshotTool.class").is_file():
            raise ValueError("source_tool_build_required")
        # Export gets only its reader settings; offline validation gets no database credentials.
        child = child_environment(os.environ, args.command == "export-source")
        java = str(Path(child["JAVA_HOME"]) / "bin/java") if child.get("JAVA_HOME") else "java"
        runtime = str(target / "classes") + os.pathsep + str(target / "dependency/*")
        result = subprocess.run([java, "-cp", runtime, "com.pubburi.migration.SourceSnapshotTool", args.command],
                                cwd=ROOT, env=child, timeout=120)
        return 0 if result.returncode == 0 else 2
    except Exception:
        print(json.dumps({"status": "failed", "error_code": "source_snapshot_fixture_rejected"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

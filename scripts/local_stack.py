#!/usr/bin/env python3
"""Run the sibling Pubburi repositories locally, without cloud credentials."""

import argparse
import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
from contextlib import ExitStack, contextmanager
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
FRONTEND = BACKEND.parent / "Frontend"
RECOMMENDATION = BACKEND.parent / "Recommendation"
STATE = BACKEND / ".pubburi"


def run(command, cwd=BACKEND, env=None, capture=False):
    return subprocess.run(command, cwd=cwd, env=env, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.STDOUT if capture else None)


def environment():
    env = os.environ.copy()
    # Local init writes shell-compatible KEY=value entries. Do not execute .env.
    if not (BACKEND / ".env").is_file():
        raise RuntimeError("Run ./scripts/local-stack.sh init first.")
    for line in (BACKEND / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, sep, value = line.partition("=")
            if not sep or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
                raise RuntimeError("Use simple KEY=value entries in Backend/.env.")
            value = value.strip()
            if len(value) >= 2 and value[0] in "\"'" and value[-1] == value[0]:
                value = value[1:-1]
            env.setdefault(key, value)
    for key, default in [("PUBBURI_SERVER_PORT", "9987"),
                         ("PUBBURI_FRONTEND_PORT", "5173"),
                         ("PUBBURI_RECOMMENDATION_PORT", "8000"),
                         ("PUBBURI_DB_PORT", "3306")]:
        value = env.get(key) or default
        if not value.isdigit() or not 1 <= int(value) <= 65535:
            raise RuntimeError(f"{key} must be a valid TCP port.")
        env[key] = value
    for key in ["PUBBURI_DB_NAME", "PUBBURI_DB_USER", "PUBBURI_DB_PASSWORD",
                "PUBBURI_DB_ROOT_PASSWORD", "PUBBURI_DB_URL"]:
        if not env.get(key):
            raise RuntimeError(f"Set {key} in Backend/.env.")
    local_url = rf"jdbc:mysql://(?:127\.0\.0\.1|localhost):{env['PUBBURI_DB_PORT']}/{re.escape(env['PUBBURI_DB_NAME'])}(?:\?.*)?"
    if not re.fullmatch(local_url, env["PUBBURI_DB_URL"]):
        raise RuntimeError("Local stack requires a loopback JDBC URL matching the local DB name and port.")
    env["VITE_API_PROXY_TARGET"] = "http://127.0.0.1:" + env["PUBBURI_SERVER_PORT"]
    return env


def compose(*args, env):
    return run(["docker", "compose", "--project-name", "pubburi-local",
                "--env-file", str(BACKEND / ".env"), "-f",
                str(BACKEND / "docker-compose.yml"), *args], env=env)


def doctor(env):
    for repo in [FRONTEND, RECOMMENDATION]:
        if not (repo / ".git").exists():
            raise RuntimeError("Clone Frontend and Recommendation beside Backend.")
    for tool in ["java", "node", "npm", "uv", "docker"]:
        if not shutil.which(tool):
            raise RuntimeError(f"Install {tool} and add it to PATH.")
    java = run(["java", "-version"], env=env, capture=True).stdout
    node = run(["node", "--version"], env=env, capture=True).stdout
    if not re.search(r'version "17[.\"]', java):
        raise RuntimeError("Select Java 17 using JAVA_HOME and PATH.")
    if not node.strip().startswith("v24."):
        raise RuntimeError("Select Node.js 24 in PATH.")
    run(["uv", "python", "find", "--no-python-downloads", "3.12"],
        cwd=RECOMMENDATION, env=env, capture=True)
    run(["docker", "info", "--format", "{{.ServerVersion}}"], env=env, capture=True)
    compose("config", "--quiet", env=env)
    print("Local prerequisites passed: Java 17, Node 24, Python 3.12, uv, Docker.")


def initialize():
    STATE.mkdir(mode=0o700, exist_ok=True)
    settings = {
        "PUBBURI_DB_NAME": "pubburi_local", "PUBBURI_DB_USER": "pubburi_local",
        "PUBBURI_DB_PASSWORD": secrets.token_hex(24),
        "PUBBURI_DB_ROOT_PASSWORD": secrets.token_hex(24),
        "PUBBURI_DB_PORT": "3306",
        "PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:3306/pubburi_local",
        "PUBBURI_SERVER_PORT": "9987", "PUBBURI_FRONTEND_PORT": "5173",
        "PUBBURI_RECOMMENDATION_PORT": "8000", "PUBBURI_LOG_LEVEL": "INFO",
    }
    try:
        with (BACKEND / ".env").open("x", encoding="utf-8") as output:
            os.chmod(output.name, 0o600)
            output.write("# Local-only settings; never commit this file.\n")
            output.writelines(f"{key}={value}\n" for key, value in settings.items())
        print("Created Backend/.env with random local database passwords.")
    except FileExistsError:
        print("Preserved existing Backend/.env; no values were changed.")


def endpoints(env):
    return {
        "frontend": f"http://127.0.0.1:{env['PUBBURI_FRONTEND_PORT']}/",
        "backend": f"http://127.0.0.1:{env['PUBBURI_SERVER_PORT']}/api/products?page=1&size=1",
        "recommendation": f"http://127.0.0.1:{env['PUBBURI_RECOMMENDATION_PORT']}/health",
    }


def probe(name, url):
    with urllib.request.urlopen(url, timeout=3) as response:
        body = response.read()
        if name == "backend":
            envelope = json.loads(body)
            data = envelope.get("data")
            if ("error" not in envelope or envelope["error"] is not None
                    or not isinstance(data, dict) or not isinstance(data.get("items"), list)):
                raise RuntimeError("Product API returned an error or an invalid page.")
        elif name == "recommendation":
            result = json.loads(body)
            if result != {"status": "ok", "service": "pubburi-recommendation"}:
                raise RuntimeError("Recommendation health contract changed.")
        elif b'<div id="app">' not in body:
            raise RuntimeError("Frontend did not return the application document.")


class ProcessCleanupError(RuntimeError):
    """Keep the operation lock when owned processes could not be stopped."""


def group_exists(group):
    try:
        os.killpg(group, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        # macOS can reject killpg(0) after the last member exits. Confirm the
        # group using process metadata instead of assuming permission means death.
        pass
    rows = subprocess.check_output(["ps", "-axo", "pgid=,stat="], text=True)
    return any(int(fields[0]) == group and not fields[1].startswith("Z")
               for row in rows.splitlines() if len(fields := row.split()) == 2)


def stop(child, grace=15):
    # The launcher can exit before its descendants. Its PID is the PGID created
    # by start_new_session, so inspect and signal the group independently.
    try:
        if group_exists(child.pid):
            os.killpg(child.pid, signal.SIGTERM)
        deadline = time.monotonic() + grace
        while group_exists(child.pid) and time.monotonic() < deadline:
            child.poll()  # Reap the launcher, even if descendants are still running.
            time.sleep(0.05)
        if group_exists(child.pid):
            os.killpg(child.pid, signal.SIGKILL)
        child.wait()
        deadline = time.monotonic() + 5
        while group_exists(child.pid) and time.monotonic() < deadline:
            time.sleep(0.05)
        if group_exists(child.pid):
            raise ProcessCleanupError("Owned process group remains; preserve the local-stack lock and inspect it.")
    except ProcessLookupError:
        child.wait()
    except (OSError, subprocess.CalledProcessError) as error:
        raise ProcessCleanupError("Cannot stop owned processes; preserve the local-stack lock.") from error
    except KeyboardInterrupt as error:
        raise ProcessCleanupError("Process cleanup interrupted; preserve the local-stack lock.") from error


@contextmanager
def operation_lock():
    STATE.mkdir(mode=0o700, exist_ok=True)
    lock = STATE / "local-stack-lock"
    try:
        lock.mkdir(mode=0o700)
    except FileExistsError:
        raise RuntimeError("Local stack is already owned. Inspect its terminal before retrying.")
    try:
        yield
    except ProcessCleanupError:
        # Do not let another command stop/reset the DB while app ownership is uncertain.
        raise
    except BaseException:
        lock.rmdir()
        raise
    else:
        lock.rmdir()


def start(env, smoke):
    doctor(env)
    if not (FRONTEND / "node_modules").is_dir() or not (RECOMMENDATION / ".venv").is_dir():
        raise RuntimeError("Run ./scripts/local-stack.sh install first.")
    # The same atomic lock serializes up, down and reset-db.
    with operation_lock(), ExitStack() as stack:
        for key in ["PUBBURI_FRONTEND_PORT", "PUBBURI_SERVER_PORT", "PUBBURI_RECOMMENDATION_PORT"]:
            with socket.socket() as candidate:
                candidate.bind(("127.0.0.1", int(env[key])))
        compose("up", "-d", "--wait", "db", env=env)
        commands = {
            "backend": (BACKEND, ["./mvnw", "--batch-mode", "--no-transfer-progress", "spring-boot:run"]),
            "frontend": (FRONTEND, ["./scripts/dev.sh"]),
            "recommendation": (RECOMMENDATION, ["./scripts/dev.sh"]),
        }
        children = []
        for name, (cwd, command) in commands.items():
            log = stack.enter_context((STATE / f"{name}.log").open("w", encoding="utf-8"))
            child = subprocess.Popen(command, cwd=cwd, env=env, stdout=log,
                                     stderr=subprocess.STDOUT, start_new_session=True)
            children.append(child)
            stack.callback(stop, child)
        pending = endpoints(env)
        deadline = time.monotonic() + 180
        while pending and time.monotonic() < deadline:
            if any(child.poll() is not None for child in children):
                raise RuntimeError("A service exited. Inspect Backend/.pubburi/*.log locally.")
            for name, url in list(pending.items()):
                try:
                    probe(name, url)
                    print(f"Ready: {name} ({url})", flush=True)
                    del pending[name]
                except (urllib.error.URLError, TimeoutError, OSError):
                    pass
            if pending:
                time.sleep(1)
        if pending:
            raise RuntimeError("Timed out waiting for: " + ", ".join(pending))
        # Check the session-compatible /api proxy, not only the Vite HTML page.
        probe("backend", f"http://127.0.0.1:{env['PUBBURI_FRONTEND_PORT']}/api/products?page=1&size=1")
        print("Local integration passed. Recommendation currently provides health only.", flush=True)
        if not smoke:
            print("Ctrl+C stops only these application processes; database data is retained.", flush=True)
            while all(child.poll() is None for child in children):
                time.sleep(1)
            raise RuntimeError("A service stopped. Inspect the local logs.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "doctor", "install", "up", "smoke", "status", "down", "reset-db"])
    parser.add_argument("--confirm-delete-local-data", action="store_true")
    args = parser.parse_args()
    if args.command == "init":
        initialize()
        return
    env = environment()
    if args.command == "doctor":
        doctor(env)
    elif args.command == "install":
        doctor(env)
        run(["./mvnw", "--batch-mode", "--no-transfer-progress", "verify"], env=env)
        run(["npm", "ci"], cwd=FRONTEND, env=env)
        run(["uv", "sync", "--locked", "--python", "3.12"], cwd=RECOMMENDATION, env=env)
    elif args.command in ["up", "smoke"]:
        start(env, args.command == "smoke")
    elif args.command == "status":
        for name, url in endpoints(env).items():
            try:
                probe(name, url)
                print(f"{name}: ready")
            except (urllib.error.URLError, TimeoutError, OSError, ValueError, RuntimeError):
                print(f"{name}: unavailable")
    else:
        with operation_lock():
            if args.command == "reset-db":
                if not args.confirm_delete_local_data:
                    raise RuntimeError("Reset deletes the dedicated local DB volume. Pass --confirm-delete-local-data explicitly.")
                compose("down", "--volumes", env=env)
                print("Dedicated local DB removed; the next up initializes data.sql.")
            else:
                compose("down", env=env)
                print("Local database stopped; its volume was retained.")


def interrupted(*_):
    raise KeyboardInterrupt


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except KeyboardInterrupt:
        print("Application processes stopped; local database data was retained.")
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        # Do not print command output: Docker/JDBC diagnostics may contain secrets.
        raise SystemExit(f"Local stack failed ({type(error).__name__}). " +
                         (str(error) if isinstance(error, RuntimeError) else "Check prerequisites and local logs."))

#!/usr/bin/env python3
"""Run the sibling Pubburi repositories locally, without cloud credentials."""

import argparse
import errno
import fcntl
import hashlib
import json
import os
import re
import secrets
import shutil
import signal
import socket
import stat
import types
import subprocess
import time
import urllib.error
import urllib.request
from contextlib import ExitStack, contextmanager
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
FRONTEND = BACKEND.parent / "Frontend"
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
    return {key: value for key, value in env.items() if not key.startswith(
        ("PUBBURI_RECOMMENDATION_", "PUBBURI_CONTENT_", "PUBBURI_REVIEWED_"))}


def compose(*args, env):
    return run(["docker", "compose", "--project-name", "pubburi-local",
                "--env-file", str(BACKEND / ".env"), "-f",
                str(BACKEND / "docker-compose.yml"), *args], env=env)


def doctor(env):
    if not (FRONTEND / ".git").exists():
        raise RuntimeError("Clone Frontend beside Backend.")
    for tool in ["java", "node", "npm", "docker"]:
        if not shutil.which(tool):
            raise RuntimeError(f"Install {tool} and add it to PATH.")
    java = run(["java", "-version"], env=env, capture=True).stdout
    node = run(["node", "--version"], env=env, capture=True).stdout
    if not re.search(r'version "17[.\"]', java):
        raise RuntimeError("Select Java 17 using JAVA_HOME and PATH.")
    if not node.strip().startswith("v24."):
        raise RuntimeError("Select Node.js 24 in PATH.")
    run(["docker", "info", "--format", "{{.ServerVersion}}"], env=env, capture=True)
    compose("config", "--quiet", env=env)
    print("Local prerequisites passed: Java 17, Node 24 and Docker.")


def initialize():
    STATE.mkdir(mode=0o700, exist_ok=True)
    settings = {
        "PUBBURI_DB_NAME": "pubburi_local", "PUBBURI_DB_USER": "pubburi_local",
        "PUBBURI_DB_PASSWORD": secrets.token_hex(24),
        "PUBBURI_DB_ROOT_PASSWORD": secrets.token_hex(24),
        "PUBBURI_DB_PORT": "3306",
        "PUBBURI_DB_URL": "jdbc:mysql://127.0.0.1:3306/pubburi_local",
        "PUBBURI_SERVER_PORT": "9987", "PUBBURI_FRONTEND_PORT": "5173",
        "PUBBURI_LOG_LEVEL": "INFO",
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


def ownership_error():
    raise ProcessCleanupError("Local runtime ownership is uncertain; preserve its files and inspect its terminal.")


def private_directory_fd(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    info = os.fstat(descriptor)
    if info.st_uid != os.getuid() or info.st_mode & 0o022:
        os.close(descriptor)
        ownership_error()
    return descriptor


def verify_state_instance(state, descriptor):
    try:
        current = os.stat(state, follow_symlinks=False)
        opened = os.fstat(descriptor)
        if ((current.st_dev, current.st_ino) != (opened.st_dev, opened.st_ino)
                or any(not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid()
                       or info.st_mode & 0o022 for info in (current, opened))):
            ownership_error()
    except OSError:
        ownership_error()


@contextmanager
def operation_gate(state=None, *, wait=0):
    state = STATE if state is None else state
    state.mkdir(mode=0o700, exist_ok=True)
    parent = private_directory_fd(state)
    deadline = time.monotonic() + wait
    try:
        verify_state_instance(state, parent)
        while True:
            try:
                os.mkdir("local-stack-lock", mode=0o700, dir_fd=parent)
                break
            except FileExistsError:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Local stack is already owned. Inspect its terminal before retrying.")
                time.sleep(0.05)
        original = os.stat("local-stack-lock", dir_fd=parent, follow_symlinks=False)
        try:
            verify_state_instance(state, parent)
            yield parent
        except ProcessCleanupError:
            # An uncertain child or ownership file must keep the durable fence.
            raise
        except BaseException:
            release_gate(state, parent, original)
            raise
        else:
            release_gate(state, parent, original)
    finally:
        os.close(parent)


def release_gate(state, parent, original):
    try:
        verify_state_instance(state, parent)
        current = os.stat("local-stack-lock", dir_fd=parent, follow_symlinks=False)
        if ((current.st_dev, current.st_ino) != (original.st_dev, original.st_ino)
                or not stat.S_ISDIR(current.st_mode) or current.st_uid != os.getuid()
                or current.st_mode & 0o077):
            ownership_error()
        os.rmdir("local-stack-lock", dir_fd=parent)
    except OSError:
        ownership_error()


RUNTIME_REGISTRY = "local-stack-runtimes.json"
RUNTIME_PREFIX = "local-stack-runtime-"
RUNTIME_IDENTITY = {"container_id", "source_id", "database_name", "database_port", "ports", "marker_sha256"}


def private_record(parent, name, maximum=16384):
    try:
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    except FileNotFoundError:
        raise
    except OSError:
        ownership_error()
    try:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1
                or info.st_mode & 0o077 or info.st_size > maximum):
            ownership_error()
        raw = os.read(descriptor, maximum + 1)
        if len(raw) > maximum:
            ownership_error()
        return descriptor, info, raw
    except BaseException:
        os.close(descriptor)
        raise


def owner_json(raw):
    def pairs(items):
        value = {}
        for key, item in items:
            if key in value:
                ownership_error()
            value[key] = item
        return value
    try:
        return json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: ownership_error())
    except (ValueError, UnicodeError):
        ownership_error()


def runtime_records(parent, *, live):
    names = {name for name in os.listdir(parent) if name.startswith(RUNTIME_PREFIX)}
    try:
        descriptor, _, raw = private_record(parent, RUNTIME_REGISTRY)
    except FileNotFoundError:
        if names:
            ownership_error()
        return {}
    try:
        registry = owner_json(raw)
    finally:
        os.close(descriptor)
    if (not isinstance(registry, dict) or set(registry) != {"schema_version", "owners"}
            or type(registry["schema_version"]) is not int or registry["schema_version"] != 1
            or not isinstance(registry["owners"], dict) or set(registry["owners"]) - {"operating", "demo"}):
        ownership_error()
    owners = registry["owners"]
    if any(not isinstance(v, dict) or set(v) != {"name", "device", "inode"}
           or not isinstance(v["name"], str) for v in owners.values()):
        ownership_error()
    if names != {v["name"] for v in owners.values()}:
        ownership_error()
    result = {}
    for lane, entry in owners.items():
        name = entry["name"]
        if (not isinstance(name, str) or not re.fullmatch(RUNTIME_PREFIX + lane + r"-[0-9a-f]{64}\.json", name)
                or any(type(entry[k]) is not int or entry[k] < 0 for k in ("device", "inode"))):
            ownership_error()
        try:
            descriptor, info, raw = private_record(parent, name)
        except FileNotFoundError:
            ownership_error()
        try:
            value = owner_json(raw)
            if ((info.st_dev, info.st_ino) != (entry["device"], entry["inode"])
                    or hashlib.sha256(raw).hexdigest() != name[-69:-5]
                    or not isinstance(value, dict) or set(value) != RUNTIME_IDENTITY | {"schema_version", "lane", "pid", "nonce"}
                    or type(value["schema_version"]) is not int or value["schema_version"] != 1
                    or value["lane"] != lane or type(value["pid"]) is not int or value["pid"] <= 1
                    or not isinstance(value["nonce"], str) or not re.fullmatch(r"[0-9a-f]{48}", value["nonce"])):
                ownership_error()
            try:
                validate_runtime_identity({key: value[key] for key in RUNTIME_IDENTITY})
            except RuntimeError:
                ownership_error()
            if live:
                try:
                    os.kill(value["pid"], 0)
                except OSError:
                    ownership_error()
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except OSError as error:
                    if error.errno not in (errno.EAGAIN, errno.EACCES):
                        ownership_error()
                else:
                    # A surviving PID alone cannot adopt a dead/replaced owner's file.
                    fcntl.flock(descriptor, fcntl.LOCK_UN)
                    ownership_error()
            result[lane] = dict(entry=entry, value=value)
        finally:
            os.close(descriptor)
    return result


def validate_runtime_identity(identity):
    if (not isinstance(identity, dict) or set(identity) != RUNTIME_IDENTITY
            or not isinstance(identity["container_id"], str) or not re.fullmatch(r"[0-9a-f]{64}", identity["container_id"])
            or not isinstance(identity["source_id"], str) or not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", identity["source_id"])
            or not isinstance(identity["database_name"], str) or not re.fullmatch(r"[a-z][a-z0-9_]*_ci", identity["database_name"])
            or type(identity["database_port"]) is not int or not isinstance(identity["ports"], list)
            or len(identity["ports"]) not in (3, 4) or any(type(p) is not int or not 1 <= p <= 65535 for p in identity["ports"])
            or len(set(identity["ports"])) != len(identity["ports"]) or identity["database_port"] not in identity["ports"]
            or not isinstance(identity["marker_sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", identity["marker_sha256"])):
        raise RuntimeError("Invalid PostgreSQL runtime isolation settings.")


def publish_runtime_records(parent, owners):
    name = ".local-stack-runtimes-" + secrets.token_hex(12) + ".tmp"
    descriptor = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
    try:
        with os.fdopen(descriptor, "w") as output:
            json.dump({"schema_version": 1, "owners": owners}, output, sort_keys=True)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, RUNTIME_REGISTRY, src_dir_fd=parent, dst_dir_fd=parent)
    finally:
        try:
            os.unlink(name, dir_fd=parent)
        except FileNotFoundError:
            pass


@contextmanager
def operation_lock(state=None):
    with operation_gate(state) as parent:
        if runtime_records(parent, live=False):
            raise RuntimeError("Local stack is already owned. Inspect its terminal before retrying.")
        yield


@contextmanager
def runtime_lock(state, lane, prepare):
    if lane not in ("operating", "demo"):
        ownership_error()
    descriptor = parent_pin = None
    registered = uncertain = False
    try:
        with operation_gate(state, wait=15) as parent:
            owners = runtime_records(parent, live=True)
            if lane in owners:
                raise RuntimeError("Local stack is already owned. Inspect its terminal before retrying.")
            identity, prepared = prepare()
            verify_state_instance(state, parent)
            validate_runtime_identity(identity)
            if runtime_records(parent, live=True) != owners:
                ownership_error()
            for peer in owners.values():
                other = peer["value"]
                if (identity["container_id"] == other["container_id"] or identity["source_id"] == other["source_id"]
                        or set(identity["ports"]).intersection(other["ports"])):
                    raise RuntimeError("PostgreSQL runtimes require separate containers, sources and all runtime ports.")
            value = dict(identity, schema_version=1, lane=lane, pid=os.getpid(), nonce=secrets.token_hex(24))
            raw = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
            name = RUNTIME_PREFIX + lane + "-" + hashlib.sha256(raw).hexdigest() + ".json"
            verify_state_instance(state, parent)
            descriptor = os.open(name, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
            os.set_inheritable(descriptor, False)
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with os.fdopen(os.dup(descriptor), "wb") as output:
                output.write(raw); output.flush(); os.fsync(output.fileno())
            original = os.fstat(descriptor)
            parent_info = os.fstat(parent)
            parent_pin = os.dup(parent)
            os.set_inheritable(parent_pin, False)
            entry = dict(name=name, device=original.st_dev, inode=original.st_ino)
            verify_state_instance(state, parent_pin)
            publish_runtime_records(parent, {**{key: item["entry"] for key, item in owners.items()}, lane: entry})
            registered = True
            verify_state_instance(state, parent_pin)
        verify_state_instance(state, parent_pin)
        yield prepared
    except ProcessCleanupError:
        uncertain = True
        raise
    finally:
        try:
            if registered and not uncertain:
                try:
                    verify_state_instance(state, parent_pin)
                    with operation_gate(state, wait=15) as parent:
                        verify_state_instance(state, parent_pin)
                        current_parent = os.fstat(parent)
                        owners = runtime_records(parent, live=False)
                        current = owners.get(lane)
                        if ((current_parent.st_dev, current_parent.st_ino) != (parent_info.st_dev, parent_info.st_ino)
                                or current != dict(entry=entry, value=value) or os.pread(descriptor, 16385, 0) != raw):
                            ownership_error()
                        # Inner ExitStack has already stopped every child before reaching here.
                        publish_runtime_records(parent, {key: item["entry"] for key, item in owners.items() if key != lane})
                        os.unlink(name, dir_fd=parent)
                except BaseException as error:
                    raise ProcessCleanupError("Cannot release this runtime owner; preserve its fence.") from error
        finally:
            if descriptor is not None:
                os.close(descriptor)
            if parent_pin is not None:
                os.close(parent_pin)


def start(env, smoke):
    doctor(env)
    if not (FRONTEND / "node_modules").is_dir():
        raise RuntimeError("Run ./scripts/local-stack.sh install first.")
    # The same atomic lock serializes up, down and reset-db.
    with operation_lock(), ExitStack() as stack:
        for key in ["PUBBURI_FRONTEND_PORT", "PUBBURI_SERVER_PORT"]:
            with socket.socket() as candidate:
                candidate.bind(("127.0.0.1", int(env[key])))
        compose("up", "-d", "--wait", "db", env=env)
        commands = {
            "backend": (BACKEND, ["./mvnw", "--batch-mode", "--no-transfer-progress", "-f", "tools/mysql-baseline/pom.xml", "spring-boot:run"]),
            "frontend": (FRONTEND, ["./scripts/dev.sh"]),
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
        print("Local frontend and API integration passed.", flush=True)
        if not smoke:
            print("Ctrl+C stops only these application processes; database data is retained.", flush=True)
            while all(child.poll() is None for child in children):
                time.sleep(1)
            raise RuntimeError("A service stopped. Inspect the local logs.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "doctor", "install", "up", "smoke", "status", "down", "reset-db", "package-pg", "activate-pg", "dev", "demo"],
                        help="demo runs a separate PostgreSQL copy with explicit --pg-config and --release-dir.")
    parser.add_argument("--confirm-delete-local-data", action="store_true")
    parser.add_argument("--provider", choices=("mysql", "postgresql"))
    parser.add_argument("--pg-config")
    parser.add_argument("--cutover-config")
    parser.add_argument("--release-dir")
    parser.add_argument("--output-dir")
    parser.add_argument("--backend-only", action="store_true")
    args = parser.parse_args()
    developer = args.command == "dev"
    if developer and any(value for key, value in vars(args).items() if key != "command"):
        parser.error("Use ./scripts/dev.sh for a single backend or --provider mysql for the legacy runtime.")
    import postgresql_local
    local = types.SimpleNamespace(**globals())
    if args.command in ("package-pg", "activate-pg", "demo"):
        if args.provider == "mysql":
            postgresql_local.reject()
        return postgresql_local.dispatch(local, args)
    if args.provider is None:
        args.provider = "postgresql" if postgresql_local.selected(local, args) else "mysql"
    if developer:
        if args.provider == "mysql":
            os.execv("./scripts/dev.sh", ["./scripts/dev.sh", "--provider", "mysql"])
        args.command = "up"
        args.backend_only = True
    if args.provider == "postgresql":
        return postgresql_local.dispatch(local, args)
    if args.pg_config or args.cutover_config or args.release_dir or args.output_dir or args.backend_only:
        raise RuntimeError("PostgreSQL options require the explicit PostgreSQL provider.")
    if args.command == "init":
        initialize()
        return
    env = environment()
    if args.command == "doctor":
        doctor(env)
    elif args.command == "install":
        doctor(env)
        run(["./mvnw", "--batch-mode", "--no-transfer-progress", "-f", "tools/mysql-baseline/pom.xml", "verify"], env=env)
        run(["npm", "ci"], cwd=FRONTEND, env=env)
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
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as error:
        # Do not print command output: Docker/JDBC diagnostics may contain secrets.
        raise SystemExit(f"Local stack failed ({type(error).__name__}). " +
                         (str(error) if isinstance(error, RuntimeError) else "Check prerequisites and local logs."))

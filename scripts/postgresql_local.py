#!/usr/bin/env python3
"""Explicit, non-destructive local launch of the guarded PostgreSQL JPA artifact."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid
from contextlib import ExitStack

from postgresql_schema import verify_owned_postgres
from jpa_smoke import defer_termination, verify_jar as validate_jar, verify_openapi_scope

REQUIRED = frozenset(('PUBBURI_PG_URL', 'PUBBURI_PG_DB_NAME', 'PUBBURI_PG_USER',
    'PUBBURI_PG_PASSWORD', 'PUBBURI_PG_RUN_TOKEN', 'PUBBURI_PG_CA',
    'PUBBURI_PG_CONTAINER_ID', 'PUBBURI_PG_VALIDATION_LABEL', 'PUBBURI_JPA_SOURCE_ID',
    'PUBBURI_JPA_REHEARSAL', 'PUBBURI_SERVER_PORT', 'PUBBURI_FRONTEND_PORT',
    'PUBBURI_RECOMMENDATION_PORT'))
OPTIONAL = frozenset(('PUBBURI_LOG_LEVEL', 'PUBBURI_RECOMMENDATION_BASE_URL',
    'PUBBURI_SEARCH_ENABLED', 'PUBBURI_SEARCH_ENDPOINT', 'PUBBURI_SEARCH_INDEX',
    'PUBBURI_CONTENT_CATALOG_PATH', 'PUBBURI_CONTENT_CATALOG_SHA256',
    'PUBBURI_CONTENT_FAMILY_BROWSE_DEMO_ENABLED'))
INPUTS = ('pom.xml', 'mvnw', '.mvn', 'src/main', 'tools/jpa-rehearsal/pom.xml',
          'tools/jpa-rehearsal/src/main', 'tools/postgresql-migration/src/main/resources')


def reject():
    raise RuntimeError('PostgreSQL local launch rejected; inspect the private configuration, release and local logs.')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                reject()
            result[key] = value
        return result
    try:
        return json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: reject())
    except (ValueError, UnicodeError):
        reject()


def private_file(path, maximum):
    path = Path(path)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as source:
        info = os.fstat(source.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077 or not info.st_mode & stat.S_IRUSR or info.st_size > maximum):
            reject()
        raw = source.read(maximum + 1)
        if len(raw) > maximum:
            reject()
        return raw


def base_environment():
    # An explicit allowlist keeps inherited DB/control credentials and JVM injection
    # out of Docker, build commands and all three application processes.
    return {key: value for key, value in os.environ.items()
            if key in ('PATH', 'JAVA_HOME', 'HOME', 'TMPDIR', 'TMP', 'TEMP', 'LANG', 'LC_ALL',
                       'SYSTEMROOT', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG')}


def configuration(path, expected_sha256=None):
    raw = private_file(path, 65536)
    if expected_sha256 is not None and hashlib.sha256(raw).hexdigest() != expected_sha256:
        reject()
    value = strict_json(raw)
    if not isinstance(value, dict) or set(value) != {'schema_version', 'environment'} or type(value['schema_version']) is not int or value['schema_version'] != 1:
        reject()
    env = value['environment']
    if (not isinstance(env, dict) or not REQUIRED <= set(env) or set(env) - REQUIRED - OPTIONAL
            or any(not isinstance(v, str) or not v or '\x00' in v for v in env.values())):
        reject()
    if env['PUBBURI_JPA_REHEARSAL'] != '1' or not re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', env['PUBBURI_JPA_SOURCE_ID']):
        reject()
    if not re.fullmatch(r'[A-Za-z0-9_-]{12,100}', env['PUBBURI_PG_RUN_TOKEN']):
        reject()
    ports = [env[k] for k in ('PUBBURI_SERVER_PORT', 'PUBBURI_FRONTEND_PORT', 'PUBBURI_RECOMMENDATION_PORT')]
    if len(set(ports)) != 3 or any(not re.fullmatch(r'[1-9][0-9]{0,4}', p) or int(p) > 65535 for p in ports):
        reject()
    for key in ('PUBBURI_RECOMMENDATION_BASE_URL', 'PUBBURI_SEARCH_ENDPOINT'):
        if key in env:
            url = urlsplit(env[key])
            if (url.scheme != 'http' or url.hostname not in ('127.0.0.1', '::1')
                    or not url.port or url.username is not None or url.password is not None
                    or url.path not in ('', '/') or url.query or url.fragment):
                reject()
    for key in ('PUBBURI_SEARCH_ENABLED', 'PUBBURI_CONTENT_FAMILY_BROWSE_DEMO_ENABLED'):
        if key in env and env[key] not in ('true', 'false'):
            reject()
    return dict(env)


DEFAULT_SELECTION = 'local-provider.json'
DATABASE_IDENTITY = ('PUBBURI_PG_CONTAINER_ID', 'PUBBURI_PG_VALIDATION_LABEL', 'PUBBURI_PG_DB_NAME',
    'PUBBURI_PG_RUN_TOKEN', 'PUBBURI_JPA_SOURCE_ID')


def selection_pins(config, directory):
    return {'config_sha256': hashlib.sha256(private_file(config, 65536)).hexdigest(),
            'manifest_sha256': hashlib.sha256(private_file(directory / 'manifest.json', 2 * 1024 * 1024)).hexdigest(),
            'jar_sha256': hashlib.sha256(private_file(directory / 'runtime.jar', 150 * 1024 * 1024)).hexdigest()}


def selection_record(local):
    path = local.STATE / DEFAULT_SELECTION
    # A dangling symlink or invalid selector must never silently select MySQL.
    try:
        path.lstat()
    except FileNotFoundError:
        return None
    value = strict_json(private_file(path, 16384))
    keys = {'schema_version', 'provider', 'pg_config', 'release_dir',
            'config_sha256', 'manifest_sha256', 'jar_sha256'}
    if (not isinstance(value, dict) or set(value) != keys
            or type(value['schema_version']) is not int or value['schema_version'] != 1
            or value['provider'] != 'postgresql'
            or any(not isinstance(value[k], str) or not value[k] or '\x00' in value[k] for k in keys - {'schema_version'})
            or not Path(value['pg_config']).is_absolute() or not Path(value['release_dir']).is_absolute()
            or any(not re.fullmatch(r'[0-9a-f]{64}', value[k]) for k in keys if k.endswith('_sha256'))):
        reject()
    return value


def selection(local):
    value = selection_record(local)
    if value is None:
        return None
    pins = selection_pins(Path(value['pg_config']), Path(value['release_dir']))
    if any(value[k] != v for k, v in pins.items()):
        reject()
    return value


def selected(local, args):
    value = selection(local)
    if value is None:
        return False
    if args.pg_config or args.release_dir:
        reject()
    args.pg_config, args.release_dir = value['pg_config'], value['release_dir']
    args.selected_default = value
    return True


def guard_selection(local, args):
    expected = getattr(args, 'selected_default', None)
    if expected is not None and selection(local) != expected:
        reject()


def activation_selection(local, environment):
    previous = selection_record(local)
    if previous is not None:
        # Old release files are not needed to refresh the same DB. The pinned
        # old config is needed to distinguish it from another valid copy.
        selected_environment = configuration(previous['pg_config'], previous['config_sha256'])
        if any(environment[key] != selected_environment[key] for key in DATABASE_IDENTITY):
            raise RuntimeError('activate-pg cannot replace the selected PostgreSQL database.')
    return previous


def publish_selection(local, config, directory, pins, *, announce=True):
    if selection_pins(config, directory) != pins:
        reject()
    path = local.STATE / DEFAULT_SELECTION
    if os.path.lexists(path):
        private_file(path, 16384)  # Do not replace another owner's/shared/symlink selection.
    temporary = local.STATE / ('.local-provider-' + uuid.uuid4().hex + '.tmp')
    try:
        write_json(temporary, dict(schema_version=1, provider='postgresql',
            pg_config=str(Path(config).absolute()), release_dir=str(directory.absolute()), **pins))
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    if announce:
        print('Owned PostgreSQL selected for option-free local commands; MySQL data was not changed.', flush=True)


def source_files(local):
    command = ['git', 'ls-files', '-z', '--', *INPUTS]
    tracked = subprocess.check_output(command, cwd=local.BACKEND).decode().split('\x00')
    dirty = subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=all', '--', *INPUTS], cwd=local.BACKEND)
    if dirty:
        reject()
    if any((local.BACKEND / p).is_symlink() for p in tracked if p):
        reject()
    return {p: sha(local.BACKEND / p) for p in tracked if p}


def private_directory(path, local, create=False):
    path = Path(path).absolute()
    local.STATE.mkdir(mode=0o700, exist_ok=True)
    if path.parent.resolve() != local.STATE.resolve() or path.is_symlink():
        reject()
    path = path.parent.resolve() / path.name
    if create:
        path.mkdir(mode=0o700)
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        reject()
    return path


def write_json(path, value):
    with path.open('x', encoding='utf-8') as output:
        os.chmod(path, 0o600)
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write('\n')


def java17(local):
    env = base_environment()
    result = local.run(['java', '-version'], env=env, capture=True).stdout
    if not re.search(r'version "17[.\"]', result):
        reject()
    return env


def package(local, destination):
    with local.operation_lock():
        directory = private_directory(destination, local, create=True)
        before = source_files(local)
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=local.BACKEND, text=True).strip()
        env = java17(local)
        with ExitStack() as stack:
            log = stack.enter_context((directory / 'build.log').open('x'))
            os.chmod(directory / 'build.log', 0o600)
            with defer_termination():
                child = subprocess.Popen(['./mvnw', '--batch-mode', '--no-transfer-progress', '-f',
                'tools/jpa-rehearsal/pom.xml', 'verify'], cwd=local.BACKEND, env=env,
                    stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                stack.callback(local.stop, child)
            if child.wait() != 0:
                reject()
        if source_files(local) != before or subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=local.BACKEND, text=True).strip() != commit:
            reject()
        candidates = list((local.BACKEND / 'tools/jpa-rehearsal/target').glob('*.jar'))
        if len(candidates) != 1:
            reject()
        shutil.copyfile(candidates[0], directory / 'runtime.jar')
        os.chmod(directory / 'runtime.jar', 0o600)
        validate_jar(directory / 'runtime.jar')
        write_json(directory / 'manifest.json', {'schema_version': 1, 'completed': True,
            'provider': 'JPA_POSTGRESQL_REHEARSAL', 'source_commit': commit,
            'source_files': before, 'jar_sha256': sha(directory / 'runtime.jar'),
            'java_feature': 17, 'postgresql_version': '18.6', 'api_paths': 38, 'api_operations': 45,
            'preparation': 'database-independent-maven-verify'})
        print('PostgreSQL release packaged; no database was accessed or prepared.', flush=True)


def release(path, local):
    directory = private_directory(path, local)
    value = strict_json(private_file(directory / 'manifest.json', 2 * 1024 * 1024))
    keys = {'schema_version', 'completed', 'provider', 'source_commit', 'source_files', 'jar_sha256',
            'java_feature', 'postgresql_version', 'api_paths', 'api_operations', 'preparation'}
    if (not isinstance(value, dict) or set(value) != keys or value['schema_version'] != 1
            or value['completed'] is not True or value['provider'] != 'JPA_POSTGRESQL_REHEARSAL'
            or value['java_feature'] != 17 or value['postgresql_version'] != '18.6'
            or value['api_paths'] != 38 or value['api_operations'] != 45
            or value['preparation'] != 'database-independent-maven-verify'
            or not re.fullmatch(r'[0-9a-f]{40}', value['source_commit'])
            or value['source_files'] != source_files(local)):
        reject()
    # Current inputs are clean and hash-pinned above; one tree comparison binds
    # the whole set to the recorded commit instead of re-reading each Git blob.
    if subprocess.run(['git', 'diff', '--quiet', value['source_commit'], '--', *INPUTS],
                      cwd=local.BACKEND, stderr=subprocess.PIPE).returncode:
        reject()
    private_file(directory / 'runtime.jar', 150 * 1024 * 1024)
    if sha(directory / 'runtime.jar') != value['jar_sha256']:
        reject()
    validate_jar(directory / 'runtime.jar')
    return directory, value


def doctor(local, config, release_path, backend_only):
    env = configuration(config)
    directory, manifest = release(release_path, local)
    base = java17(local)
    verify_owned_postgres(env, child_env=base)
    if not backend_only:
        for repo in (local.FRONTEND, local.RECOMMENDATION):
            if not (repo / '.git').exists():
                reject()
        if not (local.FRONTEND / 'node_modules').is_dir() or not (local.RECOMMENDATION / '.venv').is_dir():
            reject()
        if not local.run(['node', '--version'], env=base, capture=True).stdout.startswith('v24.'):
            reject()
        local.run(['uv', 'python', 'find', '--no-python-downloads', '3.12'], cwd=local.RECOMMENDATION, env=base, capture=True)
    print('PostgreSQL release, Java 17 and owned container passed; DB startup guards run in the application.', flush=True)
    return env, directory, manifest


def api_ready(base_url):
    with urllib.request.urlopen(base_url + '/api/products?page=1&size=1', timeout=3) as response:
        body = response.read(1024 * 1024 + 1)
        if len(body) > 1024 * 1024:
            reject()
        page = strict_json(body)
    if not isinstance(page, dict) or 'error' not in page or page['error'] is not None or not isinstance(page.get('data'), dict) or not isinstance(page['data'].get('items'), list):
        reject()
    with urllib.request.urlopen(base_url + '/v3/api-docs', timeout=3) as response:
        body = response.read(2 * 1024 * 1024 + 1)
        if len(body) > 2 * 1024 * 1024:
            reject()
        docs = strict_json(body)
    verify_openapi_scope(docs)


def child_environments(env):
    base = base_environment()
    backend = dict(base, **{k: v for k, v in env.items() if k not in (
        'PUBBURI_FRONTEND_PORT', 'PUBBURI_RECOMMENDATION_PORT',
        'PUBBURI_PG_CONTAINER_ID', 'PUBBURI_PG_VALIDATION_LABEL')})
    backend['PUBBURI_JPA_PORT'] = env['PUBBURI_SERVER_PORT']
    backend.setdefault('PUBBURI_RECOMMENDATION_BASE_URL', 'http://127.0.0.1:' + env['PUBBURI_RECOMMENDATION_PORT'])
    frontend = dict(base, PUBBURI_FRONTEND_PORT=env['PUBBURI_FRONTEND_PORT'],
                    VITE_API_PROXY_TARGET='http://127.0.0.1:' + env['PUBBURI_SERVER_PORT'])
    recommendation = dict(base, PUBBURI_RECOMMENDATION_PORT=env['PUBBURI_RECOMMENDATION_PORT'])
    return backend, frontend, recommendation


def start(local, args):
    with local.operation_lock():
        guard_selection(local, args)
        pins = selection_pins(Path(args.pg_config), Path(args.release_dir)) if args.command == 'activate-pg' else None
        env, directory, manifest = doctor(local, args.pg_config, args.release_dir, args.backend_only)
        guard_selection(local, args)
        if pins is not None:
            if selection_pins(Path(args.pg_config), directory) != pins:
                reject()
            previous = activation_selection(local, env)
        with ExitStack() as stack:
            ports = ['PUBBURI_SERVER_PORT'] if args.backend_only else ['PUBBURI_SERVER_PORT', 'PUBBURI_FRONTEND_PORT', 'PUBBURI_RECOMMENDATION_PORT']
            for key in ports:
                with socket.socket() as candidate:
                    # Match Tomcat's restart semantics without accepting a LISTEN socket.
                    candidate.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    candidate.bind(('127.0.0.1', int(env[key])))
            run_dir = private_directory(local.STATE / ('pg-run-' + uuid.uuid4().hex), local, create=True)
            shutil.copyfile(directory / 'runtime.jar', run_dir / 'runtime.jar')
            os.chmod(run_dir / 'runtime.jar', 0o600)
            if sha(run_dir / 'runtime.jar') != manifest['jar_sha256']:
                reject()
            backend_env, frontend_env, recommendation_env = child_environments(env)
            children = []
            def spawn(name, cwd, command, child_env):
                log = stack.enter_context((run_dir / (name + '.log')).open('x'))
                os.chmod(run_dir / (name + '.log'), 0o600)
                with defer_termination():
                    child = subprocess.Popen(command, cwd=cwd, env=child_env, stdout=log,
                        stderr=subprocess.STDOUT, start_new_session=True)
                    children.append(child)
                    stack.callback(local.stop, child)
                return child
            backend = spawn('backend', local.BACKEND, ['java', '-Dserver.address=127.0.0.1', '-jar', str(run_dir / 'runtime.jar')], backend_env)
            base_url = 'http://127.0.0.1:' + env['PUBBURI_SERVER_PORT']
            deadline = time.monotonic() + 180
            while True:
                if backend.poll() is not None:
                    reject()
                try:
                    # Readiness must belong to our JVM, never an unrelated listener.
                    if 'Tomcat started on port ' + env['PUBBURI_SERVER_PORT'] + ' ' in (run_dir / 'backend.log').read_text():
                        api_ready(base_url)
                        break
                except (urllib.error.URLError, TimeoutError, OSError):
                    pass
                if time.monotonic() >= deadline:
                    reject()
                time.sleep(0.25)
            if not args.backend_only:
                spawn('frontend', local.FRONTEND, ['./scripts/dev.sh'], frontend_env)
                spawn('recommendation', local.RECOMMENDATION, ['./scripts/dev.sh'], recommendation_env)
                pending = {k: v for k, v in local.endpoints(env).items() if k != 'backend'}
                while pending:
                    if any(c.poll() is not None for c in children) or time.monotonic() >= deadline:
                        reject()
                    for name, url in list(pending.items()):
                        try:
                            local.probe(name, url)
                            del pending[name]
                        except (urllib.error.URLError, TimeoutError, OSError):
                            pass
                    if pending:
                        time.sleep(0.25)
                local.probe('backend', 'http://127.0.0.1:' + env['PUBBURI_FRONTEND_PORT'] + '/api/products?page=1&size=1')
            print('PostgreSQL applications ready (38 paths / 45 operations); database retained on every exit.', flush=True)
            if args.command == 'up':
                while all(child.poll() is None for child in children):
                    time.sleep(0.5)
                reject()
        if pins is not None:
            if activation_selection(local, env) != previous:
                reject()
            publish_selection(local, args.pg_config, directory, pins)


def dispatch(local, args):
    if args.command == 'package-pg':
        if not args.output_dir or args.pg_config or args.release_dir or args.backend_only or args.confirm_delete_local_data:
            reject()
        return package(local, args.output_dir)
    if args.command not in ('doctor', 'up', 'smoke', 'status', 'activate-pg') or not args.pg_config or not args.release_dir or args.output_dir or args.confirm_delete_local_data:
        reject()
    if args.command in ('up', 'smoke', 'activate-pg'):
        return start(local, args)
    # Observers must remain usable while up owns the lifecycle lock.
    guard_selection(local, args)
    env, _, _ = doctor(local, args.pg_config, args.release_dir, args.backend_only)
    guard_selection(local, args)
    if args.command == 'status':
        # Endpoint observation only; status never adopts or signals another process.
        api_ready('http://127.0.0.1:' + env['PUBBURI_SERVER_PORT'])
        guard_selection(local, args)
        print('PostgreSQL API observed ready; endpoint observation does not prove process ownership.')

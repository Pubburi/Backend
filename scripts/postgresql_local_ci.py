#!/usr/bin/env python3
"""Exercise the real local release CLI against an already owned CI import target."""
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import uuid
import types

import local_stack
import postgresql_local as pg


def run(environ):
    if environ.get('PUBBURI_JPA_CI') != '1':
        pg.reject()
    name = 'pg-local-ci-' + uuid.uuid4().hex
    directory = local_stack.STATE / name
    release = local_stack.STATE / (name + '-release')
    directory.mkdir(mode=0o700)
    env = {key: environ[key] for key in pg.REQUIRED if not key.endswith('_PORT')}
    with socket.socket() as one, socket.socket() as two, socket.socket() as three:
        for listener, key in zip((one,two,three), ('PUBBURI_SERVER_PORT','PUBBURI_FRONTEND_PORT','PUBBURI_RECOMMENDATION_PORT')):
            listener.bind(('127.0.0.1',0))
            env[key] = str(listener.getsockname()[1])
    config = directory / 'config.json'
    pg.write_json(config, {'schema_version':1,'environment':env})
    # Run in this process so TERM/KeyboardInterrupt unwinds the launcher's
    # owned process-group cleanup, rather than killing a nested CLI on timeout.
    from jpa_smoke import cleanup_on_termination
    with cleanup_on_termination():
        pg.package(local_stack, release)
        pg.start(local_stack, types.SimpleNamespace(pg_config=config, release_dir=release,
            backend_only=True, command='smoke'))
    print('Owned PostgreSQL packaged local launcher passed; database retained.')


if __name__=='__main__':
    signal.signal(signal.SIGTERM, local_stack.interrupted)
    try:run(os.environ)
    except (RuntimeError,ValueError,OSError,subprocess.SubprocessError):
        raise SystemExit('PostgreSQL local CI failed; inspect private logs.')

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
port="${PUBBURI_SERVER_PORT:-9987}"
python3 - "$port" <<'CHECK_PORT'
import socket
import sys
import errno

port = int(sys.argv[1])
if not 1 <= port <= 65535:
    raise SystemExit('Invalid smoke server port')
with socket.socket() as probe:
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        probe.bind(('127.0.0.1', port))
        probe.listen(1)
    except OSError as error:
        if error.errno == errno.EADDRINUSE:
            raise SystemExit('Smoke port is occupied; refusing to test an unrelated server')
        raise SystemExit(f'Cannot reserve smoke port: {error.strerror}')
CHECK_PORT
shopt -s nullglob
jars=(target/pubburi-server-*.jar)
if ((${#jars[@]} != 1)); then
  echo 'Expected one packaged server jar; clean stale artifacts and package again' >&2
  exit 1
fi
scratch=$(mktemp -d)
# The validation driver retains its separate fixture-DDL credential; the app never needs it.
env -u PUBBURI_DB_ROOT_PASSWORD java -jar "${jars[0]}" > "$scratch/server.log" 2>&1 &
server_pid=$!
cleanup() {
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  rm -rf "$scratch"
}
trap cleanup EXIT
for attempt in $(seq 1 60); do
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  if grep -q 'Started ServerApplication' "$scratch/server.log" &&
      curl --silent --fail --connect-timeout 1 --max-time 5 \
        "http://127.0.0.1:$port/api/products?page=1&size=1" -o /dev/null; then
    python3 scripts/api_smoke.py
    exit 0
  fi
  sleep 1
done
tail -n 80 "$scratch/server.log"
exit 1

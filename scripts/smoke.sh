#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scratch=$(mktemp -d)
java -jar target/pubburi-server-*.jar > "$scratch/server.log" 2>&1 &
server_pid=$!
cleanup() {
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  rm -rf "$scratch"
}
trap cleanup EXIT
port="${PUBBURI_SERVER_PORT:-9987}"
for attempt in $(seq 1 60); do
  if curl --silent --fail "http://127.0.0.1:$port/api/products?page=1&size=1" -o "$scratch/products.json"; then
    python3 - "$scratch/products.json" <<'CHECK'
import json
import sys

with open(sys.argv[1], encoding='utf-8') as response:
    envelope = json.load(response)
assert envelope['error'] is None, 'Unexpected API error'
assert envelope['data']['total'] == 80, 'Expected 80 initialized products'
assert len(envelope['data']['items']) == 1, 'Pagination contract changed'
print('MySQL initialization and API startup passed.')
CHECK
    exit 0
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  sleep 1
done
tail -n 80 "$scratch/server.log"
exit 1

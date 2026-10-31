#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  set -a
  source .env
  set +a
fi
export PUBBURI_SERVER_PORT="${PUBBURI_SERVER_PORT:-9987}"
export PUBBURI_LOG_LEVEL="${PUBBURI_LOG_LEVEL:-INFO}"
exec ./mvnw spring-boot:run

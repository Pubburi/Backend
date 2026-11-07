#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$#" -eq 0 ]; then
  exec ./scripts/local-stack.sh dev
fi
if [ "$#" -ne 2 ] || [ "$1" != --provider ] || [ "$2" != mysql ]; then
  echo "Usage: ./scripts/dev.sh [--provider mysql]" >&2
  exit 2
fi
if [ -f .env ]; then
  set -a
  source .env
  set +a
fi
export PUBBURI_SERVER_PORT="${PUBBURI_SERVER_PORT:-9987}"
export PUBBURI_LOG_LEVEL="${PUBBURI_LOG_LEVEL:-INFO}"
exec ./mvnw -f tools/mysql-baseline/pom.xml spring-boot:run

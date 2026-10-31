#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Select Java 17 only for this process; leave the user's shell unchanged.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
  if [ -n "$JAVA_HOME" ]; then export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"; fi
fi
exec python3 scripts/local_stack.py "$@"

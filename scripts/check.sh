#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check_repository.py
python3 -m unittest discover -s scripts -p 'test_*.py'
./mvnw --batch-mode --no-transfer-progress verify
./mvnw --batch-mode --no-transfer-progress -f tools/postgresql-migration/pom.xml verify

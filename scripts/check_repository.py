#!/usr/bin/env python3
"""Check tracked and newly added files without printing secret values."""

import pathlib
import re
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
names = (
    subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root
    )
    .decode()
    .split("\0")
)
blocked_dirs = {".venv", "node_modules", "target", "dist", "__pycache__", ".cache", ".pubburi"}
patterns = [
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{40,}\b"),
    re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
]
errors = []
for name in sorted(set(filter(None, names))):
    path = pathlib.PurePosixPath(name)
    file = root / name
    if not file.is_file():
        continue
    secret_env = path.name == ".env" or (
        path.name.startswith(".env.") and path.name != ".env.example"
    )
    if (
        secret_env
        or set(path.parts) & blocked_dirs
        or path.suffix in {".pem", ".key", ".p12", ".jks", ".keystore"}
    ):
        errors.append(f"{name}: local secret or generated artifact must not be tracked")
        continue
    try:
        content = file.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    for line, value in enumerate(content.splitlines(), 1):
        if any(pattern.search(value) for pattern in patterns):
            errors.append(f"{name}:{line}: credential pattern detected (value hidden)")
if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print("Repository checks passed.")

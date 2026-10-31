#!/usr/bin/env python3
"""Coordinate repository ownership without expiring or stealing another lease."""

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["acquire", "release", "status"])
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--owner", required=True, help="Current chat or task identifier")
    args = parser.parse_args()
    repo = args.repo.resolve()
    if not (repo / ".git").exists():
        parser.error("--repo must be a Git checkout")
    lease = repo / ".pubburi" / "automation-lease"
    record = lease / "owner.json"
    if args.command == "acquire":
        lease.parent.mkdir(mode=0o700, exist_ok=True)
        try:
            lease.mkdir(mode=0o700)
        except FileExistsError:
            raise SystemExit("Repository is already leased; inspect it before resuming.")
        record.write_text(json.dumps({
            "owner": args.owner,
            "acquired_at": datetime.now(timezone.utc).isoformat(),
        }) + "\n", encoding="utf-8")
        print("Repository lease acquired.")
    elif args.command == "status":
        if not lease.exists():
            print("Repository is not leased.")
        elif not record.exists():
            raise SystemExit("Lease is incomplete; preserve it and inspect the owning task.")
        else:
            print(record.read_text(encoding="utf-8").strip())
    else:
        if not lease.exists():
            print("Repository is not leased.")
            return
        if not record.exists() or json.loads(record.read_text())["owner"] != args.owner:
            raise SystemExit("Cannot release another task's lease.")
        record.unlink()
        lease.rmdir()
        print("Repository lease released.")


if __name__ == "__main__":
    main()
